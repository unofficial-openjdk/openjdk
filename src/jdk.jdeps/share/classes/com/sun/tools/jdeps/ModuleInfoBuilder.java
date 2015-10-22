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
package com.sun.tools.jdeps;
import com.sun.tools.classfile.Dependency;

import static com.sun.tools.jdeps.Analyzer.Type.CLASS;
import static com.sun.tools.jdeps.Module.*;
import static com.sun.tools.jdeps.Analyzer.NOT_FOUND;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ModuleInfoBuilder {
    final Analyzer analyzer;
    final DependencyFinder dependencyFinder;
    final DependencyFinder.DependencyFilter filter;
    final List<Archive> archives;
    private final Map<Archive, JarFileToModule> modules = new HashMap<>();

    ModuleInfoBuilder(DependencyFinder finder, List<Archive> archives) {
        this.analyzer = new Analyzer(CLASS, new Analyzer.Filter() {
            @Override
            public boolean accepts(Dependency.Location origin, Archive originArchive,
                                   Dependency.Location target, Archive targetArchive)
            {
                // accepts origin and target that from different archive
                return originArchive != targetArchive;
            }
        });
        this.dependencyFinder = finder;
        this.filter = new DependencyFinder.DependencyFilter(true);
        this.archives = archives;
    }

    private void findDependencies(boolean apiOnly) throws IOException {
        dependencyFinder.findDependencies(filter, apiOnly, 1);
    }

    boolean run(boolean showRequiresPublic, Analyzer.Type verbose, boolean quiet) throws IOException {
        Map<Archive, Set<Archive>> requiresPublic = new HashMap<>();

        if (showRequiresPublic) {
            // pass 1: find API dependencies
            findDependencies(true);

            Analyzer pass1 = new Analyzer(Analyzer.Type.CLASS, new Analyzer.Filter() {
                @Override
                public boolean accepts(Dependency.Location origin, Archive originArchive,
                                       Dependency.Location target, Archive targetArchive) {
                    // accepts origin and target that from different archive
                    return originArchive != targetArchive;
                }
            });

            pass1.run(archives, false);

            for (Archive archive : archives) {
                if (!Module.class.isInstance(archive) &&
                        archive.path() != null && archive.getPathName().endsWith(".jar")) {
                    requiresPublic.put(archive, pass1.archiveDependences(archive));
                }
            }
        }

        // pass 2: analyze all class dependences
        findDependencies(false);
        analyzer.run(archives, false);

        // check if any missing dependency
        boolean missingDeps = false;
        for (Archive archive : archives) {
            if (!Module.class.isInstance(archive) &&
                    archive.path() != null && archive.getPathName().endsWith(".jar")) {

                Map<Archive, Boolean> requires;
                if (requiresPublic.containsKey(archive)) {
                    requires = requiresPublic.get(archive)
                                    .stream()
                                    .collect(Collectors.toMap(Function.identity(), (v) -> Boolean.TRUE));
                } else {
                    requires = new HashMap<>();
                }
                analyzer.archiveDependences(archive)
                        .stream()
                        .forEach(d -> requires.putIfAbsent(d, Boolean.FALSE));

                if (!modules.containsKey(archive)) {
                    JarFileToModule jfm = new JarFileToModule(archive, requires);
                    modules.put(archive, jfm);
                }

                if (!quiet && analyzer.archiveDependences(archive).contains(NOT_FOUND)) {
                    missingDeps = true;
                    System.err.format("Missing dependencies from %s%n", archive.getName());
                    analyzer.visitDependences(archive,
                            new Analyzer.Visitor() {
                                @Override
                                public void visitDependence(String origin, Archive originArchive,
                                                            String target, Archive targetArchive) {
                                    if (targetArchive == NOT_FOUND)
                                        System.err.format("   %-50s -> %-50s %s%n",
                                                origin, target, targetArchive.getName());
                                }
                            }, verbose);
                    System.err.println();
                }
            }
        }

        if (missingDeps) {
            System.err.println("ERROR: missing dependencies (check \"requires NOT_FOUND;\")");
        }
        return missingDeps;
    }

    void build(Path dir) throws IOException {
        ModuleInfoWriter writer = new ModuleInfoWriter(dir);
        writer.generateOutput(modules.values(), analyzer);
    }

    private class ModuleInfoWriter {
        private final Path outputDir;
        ModuleInfoWriter(Path dir) {
            this.outputDir = dir;
        }

        void generateOutput(Iterable<JarFileToModule> modules, Analyzer analyzer) throws IOException {
            // generate module-info.java file for each archive
            for (JarFileToModule jfm : modules) {
                if (jfm.packages().contains("")) {
                    System.err.format("ERROR: %s contains unnamed package.  module-info.java not generated%n",
                                jfm.getPathName());
                    continue;
                }

                String mn = jfm.getName();
                Path srcFile = outputDir.resolve(mn).resolve("module-info.java");
                Files.createDirectories(srcFile.getParent());
                System.out.println("writing to " + srcFile);
                try (PrintWriter pw = new PrintWriter(Files.newOutputStream(srcFile))) {
                    printModuleInfo(pw, jfm);
                }
            }
        }

        private void printModuleInfo(PrintWriter writer, JarFileToModule jfm) {
            writer.format("module %s {%n", jfm.getName());

            Map<Archive, Boolean> requires = jfm.requires();
            // first print the JDK modules
            requires.keySet().stream()
                    .filter(archive -> Module.class.isInstance(archive))
                    .filter(archive -> !archive.getName().equals("java.base"))
                    .sorted(Comparator.comparing(Archive::getName))
                    .forEach(archive -> {
                        String target = toModuleName(archive);
                        String modifier = requires.get(archive) ? "public " : "";
                        writer.format("    requires %s%s;%n", modifier, target);
                    });

            // print the rest
            requires.keySet().stream()
                    .filter(archive -> !Module.class.isInstance(archive))
                    .sorted(Comparator.comparing(Archive::getName))
                    .forEach(archive -> {
                        String target = toModuleName(archive);
                        String modifier = requires.get(archive) ? "public " : "";
                        writer.format("    requires %s%s;%n", modifier, target);
                    });

            jfm.packages().stream()
                    .sorted()
                    .forEach(pn -> writer.format("    exports %s;%n", pn));

            jfm.provides().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        String service = e.getKey();
                        e.getValue().stream()
                                .sorted()
                                .forEach(impl -> writer.format("    provides %s with %s;%n", service, impl));
                    });

            writer.println("}");
        }
    }

    private String toModuleName(Archive archive) {
        return modules.containsKey(archive)
                    ? modules.get(archive).getName()
                    : (archive != NOT_FOUND ? archive.getName() : "NOT_FOUND");
    }
}
