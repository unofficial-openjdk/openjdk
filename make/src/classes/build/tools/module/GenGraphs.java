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
import jdk.jigsaw.module.ModuleGraph;
import jdk.jigsaw.module.ModulePath;
import jdk.jigsaw.module.Resolver;

public class GenGraphs {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("ERROR: specify the output directory");
            System.exit(1);
        }
        Path dir = Paths.get(args[0]);
        Files.createDirectories(dir);

        ModulePath mp = ModulePath.installedModules();
        Resolver resolver = new Resolver(mp);

        Set<Module> javaSEModules = mp.allModules().stream()
                                        .filter(m -> (m.id().name().startsWith("java.") &&
                                                      !m.id().name().equals("java.smartcardio")))
                                        .collect(Collectors.toSet());
        Set<Module> jdkModules = mp.allModules().stream()
                                       .filter(m -> !javaSEModules.contains(m))
                                       .collect(Collectors.toSet());
        GenGraphs genGraphs = new GenGraphs(javaSEModules, jdkModules);
        Set<String> mods = new HashSet<>();
        for (Module m: mp.allModules()) {
            String name = m.id().name();
            mods.add(name);
            ModuleGraph g = resolver.resolve(name);
            genGraphs.genDotFile(dir, name, g);
        }

        ModuleGraph g = resolver.resolve(mods);
        genGraphs.genDotFile(dir, "jdk", g);
    }

    private final Set<Module> javaGroup;
    private final Set<Module> jdkGroup;
    GenGraphs(Set<Module> javaGroup, Set<Module> jdkGroup) {
        this.javaGroup = Collections.unmodifiableSet(javaGroup);
        this.jdkGroup = Collections.unmodifiableSet(jdkGroup);
    }

    private static final String ORANGE = "#e76f00";
    private static final String BLUE = "#437291";
    private static final String GREEN = "#97b101";
    private static final String GRAY = "#c0c0c0";

    private static final String REEXPORTS = "[type=\"re-exports\", style=\"bold\", color=\"" + GREEN + "\"]";
    private static final String REQUIRES = "[style=\"dashed\"]";
    private static final String REQUIRES_BASE = "[color=\"" + GRAY + "\"]";

    private void genDotFile(Path dir, String name, ModuleGraph g) throws IOException {
        try (PrintStream out = new PrintStream(Files.newOutputStream(dir.resolve(name + ".dot")))) {
            out.format("digraph \"%s\" {%n", name);
            out.format("nodesep=.5;%n");
            out.format("ranksep=1.5;%n");
            out.format("edge [arrowhead=open];%n");
            out.format("node [shape=plaintext, fontname=\"DejaVuSan\"];%n");

            g.modules().stream()
                .filter(javaGroup::contains)
                .map(this::name)
                .forEach(mn -> out.format("  \"%s\" [fontcolor=\"%s\", group=%s];%n",
                                          mn, ORANGE, "java"));
            g.modules().stream()
                .filter(jdkGroup::contains)
                .map(this::name)
                .forEach(mn -> out.format("  \"%s\" [fontcolor=\"%s\", group=%s];%n",
                                          mn, BLUE, "jdk"));
            g.modules().forEach(m -> {
                Set<String> requiresPublic = m.moduleDependences().stream()
                                              .filter(d -> d.modifiers().contains(PUBLIC))
                                              .map(d -> d.query().name())
                                              .collect(Collectors.toSet());
                String mn = m.id().name();
                g.readDependences(m).forEach(d -> {
                    String dn = d.id().name();
                    String attr = dn.equals("java.base") ? REQUIRES_BASE
                            : (requiresPublic.contains(dn) ? REEXPORTS : REQUIRES);
                    out.format("  \"%s\" -> \"%s\" %s;%n", mn, dn, attr);
                });
            });
            out.println("}");
        }
    }

    private String name(Module m) {
        return m.id().name();
    }
}
