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
package jdk.tools.jlink.internal;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import jdk.tools.jlink.plugins.ImageFilePool;
import jdk.tools.jlink.plugins.ImageFilePool.ImageFile;

/**
 * A class to deal with JVM platforms
 */
public final class JvmHandler {

    private static final class JvmComparator implements Comparator<String> {

        @Override
        public int compare(String t, String t1) {
            return Jvm.getIndex(t) - Jvm.getIndex(t1);
        }
    }

    private enum Jvm {
        SERVER("server", 0), CLIENT("client", 1), MINIMAL("minimal", 2);
        private final String name;
        private final int index;

        Jvm(String name, int index) {
            this.name = name;
            this.index = index;
        }
        private static int getIndex(String platform) {
            return Jvm.valueOf(platform.toUpperCase(Locale.US)).index;
        }
    }

    private static final String JVM_CFG = "jvm.cfg";

    static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    static boolean isMac() {
        return System.getProperty("os.name").startsWith("Mac OS");
    }

    public ImageFilePool handlePlatforms(ImageFilePool files,
            List<ImageFile> removedFiles) throws IOException {
        Objects.requireNonNull(files);
        Objects.requireNonNull(removedFiles);

        List<String> removed = getJVM(removedFiles);
        ImageFilePool ret = files;
        if (!removed.isEmpty()) {
            List<String> existing = new ArrayList<>();
            String jvmlib = jvmlib();
            ret = new ImageFilePoolImpl();
            List<ImageFile> origHolder = new ArrayList<>();
            try {
                files.visit((file) -> {
                    // skip the original cfg file
                    if (file.getName().endsWith(JVM_CFG)) {
                        origHolder.add(file);
                        return null;
                    } else {
                        String jvm = getJVM(file, jvmlib);
                        if (jvm != null) {
                            existing.add(jvm);
                        }
                        return file;
                    }
                }, ret);
                if (existing.isEmpty()) {
                    throw new Exception("no JVM found, image must contain at least one jvm");
                }
                //create the cfg file based on removed and existing
                if (origHolder.size() == 1) {
                    StringBuilder builder = new StringBuilder();
                    ImageFile orig = origHolder.get(0);
                    // Keep comments
                    try (BufferedReader reader
                            = new BufferedReader(new InputStreamReader(orig.stream(),
                                            StandardCharsets.UTF_8))) {
                        reader.lines().forEach((s) -> {
                            if (s.startsWith("#")) {
                                builder.append(s).append("\n");
                            }
                        });
                    }

                    Collections.sort(existing, new JvmComparator());
                    List<String> remaining = new ArrayList<>();
                    for (String platform : existing) {
                        if (!removed.contains(platform)) {
                            remaining.add(platform);
                            builder.append("-").append(platform).append(" KNOWN\n");
                        }
                    }

                    // removed JVM are aliased to first one.
                    for (String platform : removed) {
                        builder.append("-").append(platform).
                                append(" ALIASED_TO -").
                                append(remaining.get(0)).append("\n");
                    }

                    byte[] content = builder.toString().getBytes(StandardCharsets.UTF_8);
                    ImageFile rewritten = new ImageFile(orig.getModule(),
                            orig.getPath(), orig.getName(), orig.getType()) {

                        @Override
                        public long size() {
                            return content.length;
                        }

                        @Override
                        public InputStream stream() throws IOException {
                            return new ByteArrayInputStream(content);
                        }
                    };
                    ret.addFile(rewritten);
                } else {
                    System.err.println("No jvm.cfg file, skipping rewriting.");
                }
            } catch (Exception ex) {
                throw new IOException(ex);
            }
        }
        return ret;
    }

    private static List<String> getJVM(Collection<ImageFile> removed) {
        List<String> ret = new ArrayList<>();
        String jvmlib = jvmlib();
        for (ImageFile f : removed) {
            String jvm = getJVM(f, jvmlib);
            if (jvm != null) {
                ret.add(jvm);
            }
        }
        return ret;
    }

    private static String getJVM(ImageFile f, String jvmlib) {
        String p = f.getName();
        String radical;
        int ind = p.lastIndexOf("/");
        if (ind != -1) {
            radical = p.substring(0, ind);
            p = p.substring(ind + 1);
        } else {
            radical = null;
        }
        if (p.equals(jvmlib)) {
            if (radical == null) {
                System.err.println("jvm lib not in a directory");
            }
            return radical;
        }
        return null;
    }

    private static String jvmlib() {
        String lib = "libjvm.so";
        if (isWindows()) {
            lib = "jvm.dll";
        } else {
            if (isMac()) {
                lib = "libjvm.dylib";
            }
        }
        return lib;
    }
}
