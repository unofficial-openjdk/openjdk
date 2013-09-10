/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
 *
 */
package com.sun.tools.classanalyzer;

import jdk.jigsaw.module.View;
import jdk.jigsaw.module.ViewDependence;
import jdk.jigsaw.module.ServiceDependence;
import jdk.jigsaw.module.Module.Builder;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Jigsaw module builder
 */
public class JigsawModules {
    private final Map<String,jdk.jigsaw.module.Module> modules;
    public JigsawModules() {
        this.modules = new HashMap<>();
    }

    public jdk.jigsaw.module.Module get(String name) {
        return modules.get(name);
    }

    public Set<String> modules() {
        return modules.keySet();
    }

    public void build(Module m, Collection<Dependence> requires) {
        Builder b = new Builder();
        b.main(build(m.defaultView()));
        requires.stream().forEach(d -> {
            if (d.requiresService()) {
                b.requires(serviceDependence(d));
            } else {
                b.requires(viewDependence(d));
            }
        });
        m.views().stream()
            .filter(v -> !v.isEmpty() && v != m.defaultView())
            .forEach(v -> b.view(build(v)));
        m.packages().stream()
            .filter(p -> p.hasClasses())
            .forEach(p -> b.include(p.name()));
        modules.put(m.name(), b.build());
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

    private View build(Module.View v) {
        View.Builder b = new View.Builder();
        b.id(v.id());
        v.aliases().stream().forEach(alias -> b.alias(alias));

        // a view may have a platform-specific class
        if (v.mainClass() != null) {
            b.mainClass(v.mainClassName());
        }

        // filter out platform-specific exports
        v.exports().stream()
            .filter(pn -> v.module.getPackage(pn) != null)
            .forEach(pn -> b.export(pn));

        for (Map.Entry<String, Set<String>> e : v.providers().entrySet()) {
            String service = e.getKey();
            e.getValue().stream().forEach((impl) -> {
                b.service(service, impl);
            });
        }

        v.permits().stream().forEach((String name) -> {
            b.permit(name);
        });
        return b.build();
    }

    /**
     * Write an array of jigsaw Module to the given OutputStream
     */
    public void store(OutputStream out) throws IOException {
        try (ObjectOutputStream sout = new ObjectOutputStream(out)) {
            sout.writeObject(modules.values().toArray(new jdk.jigsaw.module.Module[0]));
        }
    }

    public void load(InputStream in) throws IOException {
        int count = 0;
        try (ObjectInputStream sin = new ObjectInputStream(in)) {
            for (jdk.jigsaw.module.Module m : (jdk.jigsaw.module.Module[]) sin.readObject()) {
                modules.put(m.id().name(), m);
            }
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public void printModuleInfos(String minfoDir) throws IOException {
        for (jdk.jigsaw.module.Module m : modules.values()) {
            Path mdir = Paths.get(minfoDir, m.id().name());
            mdir.toFile().mkdirs();
            try (PrintWriter writer = new PrintWriter(mdir.resolve("module-info.java").toFile())) {
                printModule(writer, m);
            }
        }
    }

    private <E> Iterable<E> orderedSet(Set<E> set, Function<E, String> f) {
        Map<String, E> result = new TreeMap<>();
        set.stream().forEach((e) -> {
            result.put(f.apply(e), e);
        });
        return result.values();
    }

    private static final String INDENT = "    ";
    private void printModule(PrintWriter writer, jdk.jigsaw.module.Module m) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("module %s {%n", m.id()));
        for (ViewDependence vd : orderedSet(m.viewDependences(),
                                            (ViewDependence d) -> d.query().name())) {
            String ms = vd.modifiers().stream()
                          .map(e -> e.name().toLowerCase())
                          .collect(Collectors.joining(" "));
            sb.append(format(1, "requires %s%s%s;%n",
                             ms, vd.modifiers().isEmpty() ? "" : " ",
                             vd.query()));
        }
        for (ServiceDependence sd : orderedSet(m.serviceDependences(),
                                               (ServiceDependence d) -> d.service())) {
            String ms = sd.modifiers().stream()
                          .map(e -> e.name().toLowerCase())
                          .collect(Collectors.joining(" "));
            sb.append(format(1, "requires %s%sservice %s;%n",
                             ms, sd.modifiers().isEmpty() ? "" : " ",
                             sd.service()));
        }

        for (Map.Entry<String,Set<String>> entry: m.mainView().services().entrySet()) {
            String sn = entry.getKey();
            for (String cn: entry.getValue()) {
                sb.append(format(1, "provides service %s with %s;%n", sn, cn));
            }
        }
        sb.append("\n");
        // print main view first
        printView(0, sb, m.mainView());

        for (View view : orderedSet(m.views(), (View v) -> v.id().name())) {
            if (view != m.mainView()) {
                printView(1, sb, view);
            }
        }
        sb.append("}\n");
        writer.println(sb.toString());
    }

    private String format(String fmt, Object... args) {
        return format(0, fmt, args);
    }

    private String format(int level, String fmt, Object... args) {
        String s = "";
        for (int i=0; i < level; i++) {
            s += INDENT;
        }
        return s + String.format(fmt, args);
    }

    private StringBuilder formatList(StringBuilder sb, int level, String fmt, Collection<?> c) {
        return formatList(sb, level, fmt, c, false);
    }

    private StringBuilder formatList(StringBuilder sb, int level, String fmt, Collection<?> c, boolean newline) {
        if (c.isEmpty())
            return sb;

        if (newline)
            sb.append("\n");

        TreeSet<?> ls = new TreeSet<>(c);
        for (Object o : ls) {
            sb.append(format(level, fmt, o));
        }
        return sb;
    }

    private void printView(int level, StringBuilder sb, View view) {
        if (level > 0) {
            // non-default view
            sb.append("\n");
            sb.append(format(level, "view %s {%n", view.id().name()));
        }

        formatList(sb, level+1, "provides %s;%n", view.aliases());
        if (view.mainClass() != null) {
            sb.append(format(level+1, "class %s;%n", view.mainClass()));
        }

        boolean newline = !view.aliases().isEmpty() || view.mainClass() != null;
        if (!view.exports().isEmpty()) {
            if (level == 0) {
                sb.append(newline ? "\n" : "");
                sb.append(format(level+1, "// default view exports%n"));
                newline = false;
            }
            Set<String> exports = view.exports();
            formatList(sb, level+1, "exports %s;%n", exports, newline);
            newline = true;
        }

        formatList(sb, level + 1, "permits %s;%n", view.permits(), newline);

        if (level > 0)
            sb.append(format(level, "}%n"));
    }

    /**
     * Load the module graph.
     */
    public static void main(String... argv) throws Exception {
        JigsawModules graph = new JigsawModules();
        if (argv.length == 0) {
            // default path
            String MODULES_SER = "jdk/jigsaw/module/resources/modules.ser";
            try (InputStream in = ClassLoader.getSystemResourceAsStream(MODULES_SER)) {
                graph.load(in);
            }
        } else {
            System.out.println("reading from " + argv[0]);
            try (FileInputStream in = new FileInputStream(argv[0])) {
                graph.load(in);
            }
        }

        System.out.format("%d modules:%n", graph.modules.size());
        PrintWriter writer = new PrintWriter(System.out);
        for (jdk.jigsaw.module.Module m : graph.modules.values()) {
            graph.printModule(writer, m);
        }
        writer.flush();
    }
}
