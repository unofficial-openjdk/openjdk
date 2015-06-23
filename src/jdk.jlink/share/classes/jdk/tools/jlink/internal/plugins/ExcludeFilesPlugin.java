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

import jdk.tools.jlink.plugins.ImageFilePlugin;
import jdk.tools.jlink.plugins.ImageFilePool;

/**
 *
 * Exclude files plugin
 */
final class ExcludeFilesPlugin implements ImageFilePlugin {

    private final ResourceFilter filter;

    ExcludeFilesPlugin(String[] patterns) {
        this.filter = new ResourceFilter(patterns, true);
    }

    @Override
    public String getName() {
        return ExcludeFilesProvider.NAME;
    }

    @Override
    public void visit(ImageFilePool inFiles, ImageFilePool outFiles)
            throws Exception {
        inFiles.visit((file) -> {
            return filter.accept("/" + file.getModule() + "/" + file.getPath()) ? file : null;
        }, outFiles);
    }
}
