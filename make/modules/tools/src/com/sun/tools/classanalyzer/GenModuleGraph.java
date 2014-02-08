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
import java.util.function.Function;
import java.util.stream.Collectors;
import jdk.jigsaw.module.Module;
import jdk.jigsaw.module.Module.Builder;
import jdk.jigsaw.module.ServiceDependence;
import jdk.jigsaw.module.View;
import jdk.jigsaw.module.ViewDependence;
import jdk.jigsaw.module.ViewId;

/**
 * Compile module-info.java from the given sourcepath into modules.ser.
 */
public class GenModuleGraph extends Task {
    private final List<Module> modules = new ArrayList<>();
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
            modules.add(build(mc, mclasses));
        }

        try (OutputStream os = Files.newOutputStream(Paths.get(options.outFile));
             ObjectOutputStream sout = new ObjectOutputStream(os)) {
            sout.writeObject(modules.toArray(new Module[0]));
        }
        return true;
    }

    private List<ModuleConfig> readModuleInfos(String sourcepath) throws IOException {
        List<ModuleConfig> configs = new ArrayList<>();
        String[] paths = sourcepath.split(File.pathSeparator);

        for (String p : paths) {
            Path src = Paths.get(p);
            if (Files.exists(src)) {
                List<ModuleConfig> result = Files.list(src)
                     .filter(m -> Files.exists(m.resolve("module-info.java")))
                     .map(m -> readFile(m)).collect(Collectors.toList());
                configs.addAll(result);
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

    private Module build(ModuleConfig mconfig, Path mclasses) throws IOException {
        Builder b = new Builder();
        mconfig.requires().values().stream().forEach(d -> {
            if (d.requiresService()) {
                b.requires(serviceDependence(d));
            } else {
                b.requires(viewDependence(d));
            }
        });
        mconfig.viewForName.values().stream()
            .forEach(v -> {
                if (v == mconfig.defaultView) {
                    b.main(build(v));
                } else {
                    b.view(build(v));
                }
            });

        if (Files.exists(mclasses)) {
            // find all packages included in the module
            Set<Path> packages = Files.find(mclasses, Integer.MAX_VALUE,
                       (Path p,BasicFileAttributes attr) ->
                            p.getFileName().toString().endsWith(".class"))
                 .map(Path::getParent).collect(Collectors.toSet());
            packages.forEach(pkg -> b.include(mclasses.relativize(pkg).toString().replace(File.separatorChar, '.')));
        }
        return b.build();
    }

    private View build(ModuleConfig.View v) {
        View.Builder vb = new View.Builder();
        vb.id(v.name);

        // filter out platform-specific exports
        v.exports.stream()
            .forEach(pn -> vb.export(pn));
        for (Map.Entry<String, Set<String>> e : v.providers.entrySet()) {
            String service = e.getKey();
            e.getValue().stream().forEach((impl) -> {
                vb.service(service, impl);
            });
        }
        v.permits.stream().forEach((String name) -> {
            vb.permit(name);
        });
        return vb.build();
    }

    private ViewDependence viewDependence(Dependence d) {
        Set<ViewDependence.Modifier> ms = new HashSet<>();
        if (d.requiresOptional())
            ms.add(ViewDependence.Modifier.OPTIONAL);
        if (d.requiresPublic())
            ms.add(ViewDependence.Modifier.PUBLIC);
        return new ViewDependence(ms, d.name());
    }

    private ServiceDependence serviceDependence(Dependence d) {
        Set<ServiceDependence.Modifier> ms =
            d.requiresOptional() ? EnumSet.of(ServiceDependence.Modifier.OPTIONAL) :
                EnumSet.noneOf(ServiceDependence.Modifier.class);
        return new ServiceDependence(ms, d.name());
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

    // for comparing with another modules.ser
    private void compare(String file) throws IOException, ClassNotFoundException {
        Map<String, Module> moduleForName = new HashMap<>();
        try (FileInputStream in = new FileInputStream(file);
             ObjectInputStream sin = new ObjectInputStream(in)) {
            for (Module m : (Module[]) sin.readObject()) {
                moduleForName.put(m.id().name(), m);
            }
        }
        for (Module m : modules) {
            Module module = moduleForName.get(m.id().name());
            assertEquals(module, m);
        }
    }

    private <T> void assertEquals(Set<T> set1, Set<T> set2) {
        if (set1.equals(set2)) return;

        List<T> list = new ArrayList<>(set1);
        System.out.println(set1.stream().sorted().map(Object::toString).collect(Collectors.joining("\n")));
        System.out.println("----");
        System.out.println(set2.stream().sorted().map(Object::toString).collect(Collectors.joining("\n")));
        System.out.println("----");
    }

    private void assertEquals(View v1, View v2) {
        if (v1.equals(v2)) return;

        if (!v1.id().equals(v2.id())) {
            throw new AssertionError("View " + v1.id() + " != " + v2.id());
        }

        assertEquals(v1.exports(), v2.exports());
        assertEquals(v1.permits(), v2.permits());
        assertEquals(v1.services().keySet(), v2.services().keySet());
        for (Map.Entry<String,Set<String>> e : v1.services().entrySet()) {
            Set<String> providers1 = e.getValue();
            Set<String> providers2 = v2.services().get(e.getKey());
            assertEquals(providers1, providers2);
        }
    }

    private void assertEquals(Module m1, Module m2) {
        if (m1.equals(m2)) return;
        if (!m1.id().equals(m2.id())) {
            throw new AssertionError("Module " + m1.id() + " != " + m2.id());
        }
        assertEquals(m1.viewDependences(), m2.viewDependences());
        assertEquals(m1.serviceDependences(), m2.serviceDependences());

        assertEquals(m1.mainView(), m2.mainView());
        assertEquals(m1.packages(), m2.packages());
        Map<ViewId,View> map = m2.views().stream().collect(Collectors.toMap(View::id, Function.identity()));
        for (View v1 : m1.views()) {
            View v2 = map.get(v1.id());
            if (v1.id().equals(m1.mainView().id()))
                continue;

            if (v2 == null) {
                System.out.println("View " + v1.id() + " not found " + m2.id());
            } else {
                assertEquals(v1, v2);
            }
        }
    }
}
