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
import java.util.HashMap;
import java.util.Map;
import jdk.tools.jlink.plugins.Pool;
import jdk.tools.jlink.plugins.TransformerPlugin;

/**
 *
 * Replaces files with custom content
 */
final class FileReplacerPlugin implements TransformerPlugin {

    private final Map<String, File> mapping = new HashMap<>();

    FileReplacerPlugin(String[] arguments) {
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

    @Override
    public String getName() {
        return FileReplacerProvider.NAME;
    }

    @Override
    public void visit(Pool inFiles, Pool outFiles) {
        inFiles.visit((file) -> {
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
            return file;
        }, outFiles);
    }
}
