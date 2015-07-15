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

import java.io.IOException;
import java.io.OutputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import jdk.internal.module.ModuleInfoWriter;

/**
 * This class consists exclusively of static utility methods that are useful
 * for creating tests for modules.
 */

public final class ModuleUtils {
    private ModuleUtils() { }

    /**
     * Parses a string of the form {@code name[@version]} and returns a
     * ModuleDescriptor with that name and version. The ModuleDescriptor
     * will have a requires on java.base.
     */
    private static ModuleDescriptor newModuleDescriptor(String mid) {
        String mn;
        String vs;
        int i = mid.indexOf("@");
        if (i == -1) {
            mn = mid;
            vs = null;
        } else {
            mn = mid.substring(0, i);
            vs = mid.substring(i+1);
        }
        ModuleDescriptor.Builder builder
            = new ModuleDescriptor.Builder(mn).requires("java.base");
        if (vs != null)
            builder.version(vs);
        return builder.build();
    }

    /**
     * Returns a ModuleFinder that finds modules with the given module
     * descriptors.
     */
    static ModuleFinder finderOf(ModuleDescriptor... descriptors) {

        // Create a ModuleReference for each module
        Map<String, ModuleReference> namesToReference = new HashMap<>();
        for (ModuleDescriptor descriptor: descriptors) {
            String name = descriptor.name();
            URI uri = URI.create("module:/" + descriptor.name());
            ModuleReference mref = new ModuleReference(descriptor, uri) {
                @Override
                public ModuleReader open() throws IOException {
                    throw new IOException("No module reader for: " + uri);
                }
            };
            namesToReference.put(name, mref);
        }

        return new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String name) {
                Objects.requireNonNull(name);
                return Optional.ofNullable(namesToReference.get(name));
            }
            @Override
            public Set<ModuleReference> findAll() {
                return new HashSet<>(namesToReference.values());
            }
        };
    }


    /**
     * Creates an exploded module in the given directory and containing a
     * module descriptor with the given module name.
     */
    static void createExplodedModule(Path dir, String mid) throws Exception {
        ModuleDescriptor descriptor = newModuleDescriptor(mid);
        Files.createDirectories(dir);
        Path mi = dir.resolve("module-info.class");
        try (OutputStream out = Files.newOutputStream(mi)) {
            ModuleInfoWriter.write(descriptor, out);
        }
    }

    /**
     * Creates a JAR file with the given file path and containing a module
     * descriptor with the given module name.
     */
    static void createModularJar(Path file, String mid, String ... entries)
        throws Exception
    {
        ModuleDescriptor descriptor = newModuleDescriptor(mid);
        try (OutputStream out = Files.newOutputStream(file)) {
            try (JarOutputStream jos = new JarOutputStream(out)) {

                JarEntry je = new JarEntry("module-info.class");
                jos.putNextEntry(je);
                ModuleInfoWriter.write(descriptor, jos);
                jos.closeEntry();

                for (String entry : entries) {
                    je = new JarEntry(entry);
                    jos.putNextEntry(je);
                    jos.closeEntry();
                }
            }

        }
    }

    /**
     * Creates a JAR file, optionally with a manifest, and with the given
     * entries. The entries will be empty in the resulting JAR file.
     */
    static void createJarFile(Path file, Manifest man, String... entries)
        throws IOException
    {
        try (OutputStream out = Files.newOutputStream(file)) {
            try (JarOutputStream jos = new JarOutputStream(out)) {

                if (man != null) {
                    JarEntry je = new JarEntry(JarFile.MANIFEST_NAME);
                    jos.putNextEntry(je);
                    man.write(jos);
                    jos.closeEntry();
                }

                for (String entry : entries) {
                    JarEntry je = new JarEntry(entry);
                    jos.putNextEntry(je);
                    jos.closeEntry();
                }
            }
        }
    }

    /**
     * Creates a JAR file and with the given entries. The entries will be empty
     * in the resulting JAR file.
     */
    static void createJarFile(Path file, String... entries)
        throws IOException
    {
        createJarFile(file, null, entries);
    }

}
