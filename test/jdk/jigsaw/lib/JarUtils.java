/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.lang.module.ModuleDescriptor;

/**
 * This class consists exclusively of static utility methods that are useful
 * for creating and manipulating JAR files.
 *
 * It will eventually be combined with jdk.testlibrary.JarUtils.
 */

public final class JarUtils {
    private JarUtils() { }

    /**
     * Creates a JAR file.
     *
     * Equivalent to {@code jar cf <jarfile> -C <dir> file...}
     *
     * The input files are resolved against the given directory. Any input
     * files that are directories are processed recursively.
     */
    public static void createJarFile(Path jarfile, Path dir, Path... file)
        throws IOException
    {
        // create the target directory
        Path parent = jarfile.getParent();
        if (parent != null)
            Files.createDirectories(parent);

        List<Path> entries = new ArrayList<>();
        for (Path entry : file) {
            Files.find(dir.resolve(entry), Integer.MAX_VALUE,
                        (p, attrs) -> attrs.isRegularFile())
                    .map(e -> dir.relativize(e))
                    .forEach(entries::add);
        }

        try (OutputStream out = Files.newOutputStream(jarfile);
             JarOutputStream jos = new JarOutputStream(out))
        {
            for (Path entry : entries) {

                // map the file path to a name in the JAR file
                Path normalized = entry.normalize();
                String name = normalized
                        .subpath(0, normalized.getNameCount())  // drop root
                        .toString()
                        .replace(File.separatorChar, '/');

                jos.putNextEntry(new JarEntry(name));
                Files.copy(dir.resolve(entry), jos);
            }
        }
    }

    /**
     * Creates a JAR file.
     *
     * Equivalent to {@code jar cf <jarfile> -C <dir> file...}
     *
     * The input files are resolved against the given directory. Any input
     * files that are directories are processed recursively.
     */
    public static void createJarFile(Path jarfile, Path dir, String... input)
        throws IOException
    {
        Path[] paths = Stream.of(input).map(Paths::get).toArray(Path[]::new);
        createJarFile(jarfile, dir, paths);
    }

    /**
     * Creates a JAR file from the contents of a directory.
     *
     * Equivalent to {@code jar cf <jarfile> -C <dir> .}
     */
    public static void createJarFile(Path jarfile, Path dir) throws IOException {
        createJarFile(jarfile, dir, Paths.get("."));
    }


    /**
     * Generates a modular jar with module descriptor.
     */
    public static void createModularjar(Path jarfile, Path dir,
            ModuleDescriptor moduleDescriptor, Path... file)
            throws IOException {

        Path parent = jarfile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        List<Path> entries = new ArrayList<>();
        for (Path entry : file) {
            Files.find(dir.resolve(entry), Integer.MAX_VALUE,
                    (p, attrs) -> attrs.isRegularFile())
                    .map(e -> dir.relativize(e))
                    .forEach(entries::add);
        }
        try (JarOutputStream jos = new JarOutputStream(
                Files.newOutputStream(jarfile))) {
            JarEntry je = new JarEntry("module-info.class");
            jos.putNextEntry(je);
            jdk.internal.module.ModuleInfoWriter.write(moduleDescriptor, jos);
            jos.closeEntry();

            for (Path entry : entries) {
                Path normalized = entry.normalize();
                String name = normalized
                        .subpath(0, normalized.getNameCount())
                        .toString()
                        .replace(File.separatorChar, '/');

                jos.putNextEntry(new JarEntry(name));
                Files.copy(dir.resolve(entry), jos);
            }
        }
    }

    public static void createModularjar(Path jarfile, Path dir,
            ModuleDescriptor moduleDescriptor) throws IOException {
        createModularjar(jarfile, dir, moduleDescriptor, Paths.get("."));
    }

}
