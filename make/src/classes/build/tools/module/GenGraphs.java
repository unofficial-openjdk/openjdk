/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

package build.tools.module;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import jdk.jigsaw.module.Module;
import static jdk.jigsaw.module.ModuleDependence.Modifier.PUBLIC;
import jdk.jigsaw.module.ModuleLibrary;
import jdk.jigsaw.module.Resolution;
import jdk.jigsaw.module.SimpleResolver;

public class GenGraphs {
    private static final String MODULES_SER = "jdk/jigsaw/module/resources/modules.ser";

    private static class JdkModuleLibrary extends ModuleLibrary {
        private final Set<Module> modules = new HashSet<>();
        private final Map<String, Module> namesToModules = new HashMap<>();
        JdkModuleLibrary(Module... mods) {
            for (Module m: mods) {
                modules.add(m);
                namesToModules.put(m.id().name(), m);
            }
        }

        @Override
        public Module findLocalModule(String name) {
            return namesToModules.get(name);
        }

        @Override
        public Set<Module> localModules() {
            return Collections.unmodifiableSet(modules);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("ERROR: specify the output directory");
            System.exit(1);
        }
        Path dir = Paths.get(args[0]);
        Files.createDirectories(dir);
        Module[] modules = readModules();

        JdkModuleLibrary mlib = new JdkModuleLibrary(modules);
        SimpleResolver resolver = new SimpleResolver(mlib);

        Set<Module> javaSEModules = resolver.resolve(Collections.singleton("java.se"))
                                            .selectedModules();
        Set<Module> jdkModules = Arrays.stream(modules)
                                       .filter(m -> !javaSEModules.contains(m))
                                       .collect(Collectors.toSet());
        GenGraphs genGraphs = new GenGraphs(javaSEModules, jdkModules);
        for (Module m: modules) {
            String name = m.id().name();
            Set<String> roots = new HashSet<>();
            roots.add(name);

            Resolution r = resolver.resolve(roots);
            Map<Module, Set<String>> deps = r.resolvedDependences();

            genGraphs.genDotFile(dir, name, deps);
        }
    }

    private static Module[] readModules() throws Exception {
        InputStream stream = ClassLoader.getSystemResourceAsStream(MODULES_SER);
        if (stream == null) {
            System.err.format("WARNING: %s not found%n", MODULES_SER);
            return new Module[0];
        }
        try (InputStream in = stream) {
            ObjectInputStream ois = new ObjectInputStream(in);
            Module[] mods = (Module[]) ois.readObject();
            if (mods.length == 0)
                System.err.format("WARNING: %s is empty%n", MODULES_SER);
            return mods;
        }
    }

    private static final String ORANGE = "#e76f00";
    private static final String BLUE = "#437291";
    private static final String GREEN = "#97b101";
    private final Set<Module> javaGroup;
    private final Set<Module> jdkGroup;
    GenGraphs(Set<Module> javaGroup, Set<Module> jdkGroup) {
        this.javaGroup = Collections.unmodifiableSet(javaGroup);
        this.jdkGroup = Collections.unmodifiableSet(jdkGroup);
    }

    private void genDotFile(Path dir, String name, Map<Module, Set<String>> deps)
        throws IOException
    {
        try (PrintStream out = new PrintStream(Files.newOutputStream(dir.resolve(name + ".dot")))) {
            out.format("digraph \"%s\" {%n", name);
            out.format("nodesep=.5;%n");
            out.format("ranksep=1.5;%n");
            out.format("edge [arrowhead=open, arrowsize=2];%n");
            out.format("node [shape=plaintext, fontname=\"DejaVuSan\"];%n");

            deps.keySet().stream()
                .filter(javaGroup::contains)
                .map(this::name)
                .forEach(mn -> out.format("  \"%s\" [fontcolor=\"%s\", group=%s];%n",
                                          mn, ORANGE, "java"));
            deps.keySet().stream()
                .filter(jdkGroup::contains)
                .map(this::name)
                .forEach(mn -> out.format("  \"%s\" [fontcolor=\"%s\", group=%s];%n",
                                          mn, BLUE, "jdk"));
            for (Map.Entry<Module, Set<String>> entry: deps.entrySet()) {
                Module m = entry.getKey();
                Set<String> requiresPublic = m.moduleDependences().stream()
                                                 .filter(d -> d.modifiers().contains(PUBLIC))
                                                 .map(d -> d.query().name())
                                                 .collect(Collectors.toSet());
                name = m.id().name();
                for (String dn: entry.getValue()) {
                    String attr = requiresPublic.contains(dn)
                            ? String.format(" [type=reqPublic, color=\"%s\"]", GREEN)
                            : "";
                    out.format("  \"%s\" -> \"%s\"%s;%n", name, dn, attr);
                }
            }
            out.println("}");
        }
    }

    private String name(Module m) {
        return m.id().name();
    }
}
