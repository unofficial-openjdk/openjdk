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

package java.lang.module;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jdk.internal.misc.JavaLangAccess;
import jdk.internal.misc.SharedSecrets;

/**
 * Provides support for patching modules in the boot layer with -Xpatch.
 */

class ModulePatcher {

    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();

    // list of directories specified to -Xpatch, may be empty
    private static final List<Path> PATH_DIRS;

    static {
        String s = System.getProperty("jdk.launcher.patchdirs");
        PATH_DIRS = toPathList(s);
    }

    private ModulePatcher() { }

    /**
     * Scans the patch directories for .class files to override or add to
     * the given module. If there are .class in new packages then it returns
     * a new ModuleDescriptor that is the same as the given ModuleDescriptor
     * but with an expanded set of concealed packages.
     *
     * If -Xpatch is not specified or the boot layer is created then this
     * method does nothing (patching is for the boot layer only).
     *
     * @throws UncheckedIOException if an I/O error is detected
     */
    static ModuleDescriptor patchIfNeeded(ModuleDescriptor md) {
        List<Path> dirs = PATH_DIRS;
        if (!dirs.isEmpty() && JLA.getBootLayer() == null) {

            String mn = md.name();

            // scan each $DIR/$MODULE for .class files
            Set<String> packages = new HashSet<>();
            dirs.stream()
                .map(dir -> dir.resolve(mn))
                .filter(Files::isDirectory)
                .forEach(dir -> packages.addAll(explodedPackages(dir)));

            if (packages.size() > 0) {
                Set<String> original = md.packages();
                packages.addAll(original);
                if (packages.size() > original.size()) {
                    // new packages so re-create module descriptor
                    md = new ModuleDescriptor(md, packages);
                }
            }

        }
        return md;
    }

    /**
     * Walks the given file tree and returns the set of packages corresponding
     * to the .class files found in the file tree.
     */
    private static Set<String> explodedPackages(Path top) {
        try {
            return Files.find(top, Integer.MAX_VALUE,
                    ((path, attrs) -> attrs.isRegularFile() &&
                            path.toString().endsWith(".class")))
                    .map(path -> toPackageName(top.relativize(path)))
                    .filter(pkg -> pkg.length() > 0)   // module-info
                    .distinct()
                    .collect(Collectors.toSet());
        } catch (IOException x) {
            throw new UncheckedIOException(x);
        }
    }

    /**
     * Derives a package name from a file path to a .class file.
     */
    private static String toPackageName(Path path) {
        String name = path.toString();
        assert name.endsWith(".class");
        int index = name.lastIndexOf(File.separatorChar);
        if (index != -1) {
            return name.substring(0, index).replace(File.separatorChar, '.');
        } else {
            return "";
        }
    }

    /**
     * Parses the given string as a sequence of directories, returning a list
     * of Path objects to represent each directory.
     */
    private static List<Path> toPathList(String s) {
        if (s == null) {
            return Collections.emptyList();
        } else {
            String[] dirs = s.split(File.pathSeparator);

            // too early in startup to use Stream.of(dirs)
            List<Path> result = new ArrayList<>();
            for (String dir : dirs) {
                if (dir.length() > 0)
                    result.add(Paths.get(dir));
            }
            return result;
        }
    }

}
