/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.tools.jlink.internal.plugins;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jdk.tools.jlink.plugins.ResourcePlugin;
import jdk.tools.jlink.plugins.ResourcePool;
import jdk.tools.jlink.plugins.ResourcePool.Resource;
import jdk.tools.jlink.plugins.StringTable;

/**
 *
 * Sort Resources plugin
 */
final class SortResourcesPlugin implements ResourcePlugin {

    private final List<Pattern> filters = new ArrayList<>();
    private final List<String> orderedPaths;
    private final boolean isFile;
    SortResourcesPlugin(String[] patterns) throws IOException {
        boolean isf = false;
        List<String> paths = null;
        if (patterns != null) {
            if (patterns.length == 1) {
                String filePath = patterns[0];
                File f = new File(filePath);
                if (f.exists()) {
                    isf = true;
                    try (FileInputStream fis = new FileInputStream(f);
                            InputStreamReader ins
                            = new InputStreamReader(fis, StandardCharsets.UTF_8);
                            BufferedReader reader = new BufferedReader(ins)) {
                        paths = reader.lines().collect(Collectors.toList());
                    }
                }
            }
            if (!isf) {
                for (String p : patterns) {
                    p = p.replaceAll(" ", "");
                    Pattern pattern = Pattern.compile(ResourceFilter.escape(p));
                    filters.add(pattern);
                }
            }
        }
        orderedPaths = paths;
        isFile = isf;
    }

    @Override
    public String getName() {
        return SortResourcesProvider.NAME;
    }

    static class SortWrapper {
        private final Resource resource;
        private final int ordinal;

        SortWrapper(Resource resource, int ordinal) {
            this.resource = resource;
            this.ordinal = ordinal;
        }

        Resource getResource() {
            return resource;
        }

        String getPath() {
            return resource.getPath();
        }

        int getOrdinal() {
            return ordinal;
        }
    }

    private int getPatternOrdinal(String path) {
        int ordinal = -1;
        for (int i = 0; i < filters.size(); i++) {
            Matcher m = filters.get(i).matcher(path);
            if (m.matches()) {
                ordinal = i;
                break;
            }
        }
        return ordinal;
    }

    private int getFileOrdinal(String path) {
        return orderedPaths.indexOf(path);
    }

    @Override
    public void visit(ResourcePool inResources, ResourcePool outResources, StringTable strings) throws Exception {

        inResources.getResources().stream()
                .map((r) -> new SortWrapper(r, isFile
                                        ? getFileOrdinal(r.getPath())
                                        : getPatternOrdinal(r.getPath())))
                .sorted((sw1, sw2) -> {
                    int ordinal1 = sw1.getOrdinal();
                    int ordinal2 = sw2.getOrdinal();

                    if (ordinal1 >= 0) {
                        if (ordinal2 >= 0) {
                            return ordinal1 - ordinal2;
                        } else {
                            return -1;
                        }
                    } else if (ordinal2 >= 0) {
                        return 1;
                    }

                    return sw1.getPath().compareTo(sw2.getPath());
                }).forEach((sw) -> {
                    try {
                        outResources.addResource(sw.getResource());
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
    }
}
