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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import jdk.tools.jlink.api.plugin.Plugin.PluginOption;
import jdk.tools.jlink.api.plugin.Plugin.PluginOption.Builder;
import jdk.tools.jlink.api.plugin.transformer.Pool;
import jdk.tools.jlink.api.plugin.transformer.TransformerPlugin;
import jdk.tools.jlink.internal.Utils;

/**
 *
 * Replaces files with custom content
 */
public final class FileReplacerPlugin implements TransformerPlugin {

    private final Map<String, File> mapping = new HashMap<>();

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void visit(Pool inFiles, Pool outFiles) {
        inFiles.visit((file) -> {
            if (!file.getType().equals(Pool.ModuleDataType.CLASS_OR_RESOURCE)) {
                File replaced = mapping.get("/" + file.getModule() + "/"
                        + file.getPath());
                if (replaced != null) {
                    try {
                        file = Pool.newImageFile(file.getModule(), file.getPath(),
                                file.getType(), new FileInputStream(replaced), replaced.length());
                    } catch (FileNotFoundException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }
            }
            return file;
        }, outFiles);
    }

    public static final String NAME = "replace-file";
    public static final PluginOption NAME_OPTION =
            new Builder(NAME).
            description(PluginsResourceBundle.getDescription(NAME)).
            argumentDescription(PluginsResourceBundle.getArgument(NAME))
                    .build();

    @Override
    public PluginOption getOption() {
        return NAME_OPTION;
    }

   @Override
    public Set<PluginType> getType() {
        Set<PluginType> set = new HashSet<>();
        set.add(CATEGORY.COMPRESSOR);
        return Collections.unmodifiableSet(set);
    }

    @Override
    public String getDescription() {
        return PluginsResourceBundle.getDescription(NAME);
    }

    @Override
    public void configure(Map<PluginOption, String> config) {
        String val = config.get(NAME_OPTION);
        String[] arguments = Utils.listParser.apply(val);
        for (int i = 0; i < arguments.length; i++) {
            String path = arguments[i];
            i++;
            if (i < arguments.length) {
                File replacement = new File(arguments[i]);
                if (!replacement.exists()) {
                    throw new RuntimeException("Replacement file " + replacement
                            + " doesn't exist.");
                }
                mapping.put(path, replacement);
            } else {
                throw new RuntimeException("Replacing file, "
                        + "invalid number of arguments");
            }
        }
    }

}
