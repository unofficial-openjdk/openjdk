/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.tools.classanalyzer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jdk.jigsaw.module.ExtendedModuleDescriptor;
import jdk.jigsaw.module.ModuleDescriptor;
import jdk.jigsaw.module.ModuleDependence;
import jdk.jigsaw.module.ModuleDescriptor.Builder;
import jdk.jigsaw.module.ServiceDependence;

/**
 * Compile module-info.java from the given sourcepath into modules.ser.
 */
public class GenModuleGraph extends Task {
    private final List<ModuleDescriptor> descriptors = new ArrayList<>();
    private final Map<String, Set<String>> contents = new HashMap<>();
    private final Options options = new Options();

    GenModuleGraph() {
        super("GenModuleGraph");
    }

    private static List<Option<? extends Task>> recognizedOptions = Arrays.asList(
        new Option<GenModuleGraph>(false, "-h", "-?", "--help") {
            void process(GenModuleGraph task, String opt, String arg) {
                task.showHelp = true;
            }
        },
        new Option<GenModuleGraph>(true, "-buildmodules") {
            void process(GenModuleGraph task, String opt, String arg) {
                task.options.buildModulesPath = arg;
            }
        },
        new Option<GenModuleGraph>(true, "-sourcepath") {
            void process(GenModuleGraph task, String opt, String arg) {
                task.options.sourcepath = arg;
            }
        },
        new Option<GenModuleGraph>(true, "-o") {
            void process(GenModuleGraph task, String opt, String arg) {
                task.options.outFile = arg;
            }
        }
    );

    protected List<Option<? extends Task>> options() {
        return recognizedOptions;
    }

    protected boolean validateOptions() {
        return options.sourcepath != null &&
                  options.buildModulesPath != null && options.outFile != null;
    }

    @Override
    protected boolean run() throws IOException {
        List<ModuleConfig> configs = readModuleInfos(options.sourcepath);
        for (ModuleConfig mc : configs) {
            Path mclasses = Paths.get(options.buildModulesPath, mc.module);
            build(mc, mclasses);
        }

        try (OutputStream os = Files.newOutputStream(Paths.get(options.outFile));
             ObjectOutputStream sout = new ObjectOutputStream(os)) {
            sout.writeObject(descriptors.toArray(new ModuleDescriptor[0]));
            sout.writeObject(contents);
        }
        return true;
    }

    private List<ModuleConfig> readModuleInfos(String sourcepath) throws IOException {
        List<ModuleConfig> configs = new ArrayList<>();
        String[] paths = sourcepath.split(File.pathSeparator);

        for (String p : paths) {
            Path src = Paths.get(p);
            /*if (Files.exists(src)) {
                List<ModuleConfig> result = Files.list(src)
                     .filter(m -> Files.exists(m.resolve("module-info.java")))
                     .map(m -> readFile(m)).collect(Collectors.toList());
                configs.addAll(result);
            }*/
            if (Files.exists(src.resolve("module-info.java"))) {
                configs.add(readFile(src));
            }
        }
        return configs;
    }

    private ModuleConfig readFile(Path moduleDir) {
        Path p = moduleDir.resolve("module-info.java");
        try (final InputStream in = Files.newInputStream(p)) {
            List<ModuleConfig> result =
                ModuleConfig.readConfigurationFile(p.toString(), in, options.version);
            assert result.size() == 1;
            return result.get(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void build(ModuleConfig mconfig, Path mclasses) throws IOException {
        Builder b = new Builder(mconfig.module);

        // requires and uses
        mconfig.requires().values().forEach(d -> {
            if (d.requiresService()) {
                b.requires(serviceDependence(d));
            } else {
                b.requires(moduleDependence(d));
            }
        });

        // exports
        for (Map.Entry<String, Set<String>> entry: mconfig.exportsTo.entrySet()) {
            String p = entry.getKey();
            Set<String> permits = entry.getValue();
            if (permits.isEmpty()) {
                b.export(p);
            } else {
                permits.forEach(m -> b.export(p, m));
            }
        }

        // provides (service providers)
        mconfig.providers.keySet().forEach(service ->
            mconfig.providers.get(service).forEach(impl -> b.service(service, impl)));

        descriptors.add(b.build());

        // content
        Set<String> packages =
            contents.computeIfAbsent(mconfig.module, k -> new HashSet<>());
        if (Files.exists(mclasses)) {
            // find all packages included in the module
            Files.find(mclasses, Integer.MAX_VALUE,
                       (Path p,BasicFileAttributes attr) ->
                            p.getFileName().toString().endsWith(".class")
                            && !p.getFileName().toString().equals("module-info.class"))
                 .map(Path::getParent)
                 .forEach(pkg -> packages.add(mclasses.relativize(pkg)
                                                      .toString()
                                                      .replace(File.separatorChar, '.')));
        }
    }

    private ModuleDependence moduleDependence(Dependence d) {
        Set<ModuleDependence.Modifier> ms = new HashSet<>();
        if (d.requiresPublic())
            ms.add(ModuleDependence.Modifier.PUBLIC);
        return new ModuleDependence(ms, d.name());
    }

    private ServiceDependence serviceDependence(Dependence d) {                ;
        return new ServiceDependence(EnumSet.of(ServiceDependence.Modifier.OPTIONAL), d.name());
    }

    private static class Options {
        String outFile;
        String sourcepath;
        String buildModulesPath;
        String version = "9-ea"; // this will be ignored
    }

    public static void main(String... args) throws Exception {
        GenModuleGraph gen = new GenModuleGraph();
        int rc = gen.run(args);
        System.exit(rc);
    }

    private <T> void assertEquals(Set<T> set1, Set<T> set2) {
        if (set1.equals(set2)) return;

        List<T> list = new ArrayList<>(set1);
        System.out.println(set1.stream()
                               .sorted()
                               .map(Object::toString).collect(Collectors.joining("\n")));
        System.out.println("----");
        System.out.println(set2.stream()
                               .sorted()
                               .map(Object::toString)
                               .collect(Collectors.joining("\n")));
        System.out.println("----");
    }

    private void assertEquals(ModuleDescriptor m1, ModuleDescriptor m2) {
        if (m1.equals(m2)) return;
        if (!m1.name().equals(m2.name())) {
            throw new AssertionError("Module " + m1.name() + " != " + m2.name());
        }
        assertEquals(m1.moduleDependences(), m2.moduleDependences());
        assertEquals(m1.serviceDependences(), m2.serviceDependences());

        assertEquals(m1.exports(), m2.exports());
        assertEquals(m1.services().keySet(), m2.services().keySet());

        for (Map.Entry<String, Set<String>> e : m1.services().entrySet()) {
            Set<String> providers1 = e.getValue();
            Set<String> providers2 = m2.services().get(e.getKey());
            assertEquals(providers1, providers2);
        }
    }
}
