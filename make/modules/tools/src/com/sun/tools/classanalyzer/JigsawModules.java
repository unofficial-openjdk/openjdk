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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jdk.jigsaw.module.ModuleDependence;
import jdk.jigsaw.module.ModuleDescriptor;
import jdk.jigsaw.module.ModuleDescriptor.Builder;
import jdk.jigsaw.module.ModuleExport;
import jdk.jigsaw.module.ServiceDependence;

/**
 * Jigsaw module builder
 */
public class JigsawModules {
    public static final String MODULE_GRAPH = "jdk/jigsaw/module/resources/modules.ser";
    private Map<String, Set<String>> contents = new HashMap<>();
    private final Map<String, ModuleDescriptor> modules;

    public JigsawModules() {
        this.modules = new LinkedHashMap<>();
    }

    public jdk.jigsaw.module.ModuleDescriptor get(String name) {
        return modules.get(name);
    }

    public Set<String> moduleNames() {
        return modules.keySet();
    }

    public void build(Module m, Collection<Dependence> requires) {
        Builder b = new Builder(m.name());

        // requires and uses
        requires.forEach(d -> {
            if (d.requiresService()) {
                b.requires(serviceDependence(d));
            } else {
                b.requires(moduleDependence(d));
            }
        });

        // contents
        Set<String> packages = contents.computeIfAbsent(m.name(), k -> new HashSet<>());
        m.packages().stream()
            .filter(Package::hasClasses)
            .map(Package::name)
            .forEach(p -> packages.add(p));

        // (unqualified) exports
        m.exports().forEach(p -> b.export(p));

        // qualified exports
        Map<String, Set<Module>> exportsTo = m.exportsTo();
        for (Map.Entry<String, Set<Module>> entry: exportsTo.entrySet()) {
            String p = entry.getKey();
            Set<Module> permits = entry.getValue();
            permits.forEach(who -> b.export(p, who.name()));
        }

        // services
        m.providers().keySet().forEach(s ->
                m.providers().get(s).forEach(impl ->
                        b.service(s.service.getClassName(), impl.getClassName())));

        // build and add to map
        ModuleDescriptor descriptor = b.build();
        modules.put(descriptor.name(), descriptor);
    }

    private ModuleDependence moduleDependence(Dependence d) {
        Set<ModuleDependence.Modifier> ms = new HashSet<>();
        if (d.requiresPublic())
            ms.add(ModuleDependence.Modifier.PUBLIC);
        return new ModuleDependence(ms, d.name());
    }

    private ServiceDependence serviceDependence(Dependence d) {
        return new ServiceDependence(EnumSet.of(ServiceDependence.Modifier.OPTIONAL), d.name());
    }

    /**
     * Write an array of jigsaw Module to the given OutputStream
     */
    public void store(OutputStream out) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(modules.values().toArray(new ModuleDescriptor[0]));
            oos.writeObject(contents);
        }
    }

    public void load(InputStream in) throws IOException {
        try (ObjectInputStream ois = new ObjectInputStream(in)) {
            for (ModuleDescriptor m : (ModuleDescriptor[]) ois.readObject()) {
                modules.put(m.name(), m);
            }
            contents = (Map<String, Set<String>>) ois.readObject();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public void printModuleInfo(PrintWriter writer, Module m) throws IOException {
        printModule(writer, modules.get(m.name()));
    }

    private String toRequires(ModuleDependence d) {
        String name = d.query().name();
        Stream<String> mods = d.modifiers().stream().map(e -> e.toString().toLowerCase());
        return (Stream.concat(Stream.of("requires"),
                              Stream.concat(mods, Stream.of(name)))
                      .collect(Collectors.joining(" ")));
    }

    private static final String INDENT = "    ";
    private void printModule(PrintWriter writer, ModuleDescriptor m) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("module %s {%n", m.name()));

        Stream<String> reqs = m.moduleDependences().stream().map(this::toRequires);
        reqs.sorted().forEach(d -> sb.append(format(1, "%s;%n", d)));

        // exports (sorted)
        Map<String, Set<String>> exports = new TreeMap<>();
        for (ModuleExport export: m.exports()) {
            String pkg = export.pkg();
            String who = export.permit();
            Set<String> permits = exports.computeIfAbsent(pkg, k -> new TreeSet<>());
            if (who != null) {
                permits.add(who);
            }
        }
        for (Map.Entry<String, Set<String>> entry: exports.entrySet()) {
            String pkg = entry.getKey();
            sb.append(format(1, "exports %s", pkg));
            Set<String> permits = entry.getValue();
            int count = permits.size();
            if (count > 0)
                sb.append(" to ");
            for (String permit: permits) {
                sb.append("\n");
                sb.append(format(2, "%s", permit));
                if (--count > 0) sb.append(',');
            }
            sb.append(";\n");
        }

        for (Map.Entry<String, Set<String>> entry: m.services().entrySet()) {
            String sn = entry.getKey();
            for (String cn: entry.getValue()) {
                sb.append(format(1, "provides %s with %s;%n", sn, cn));
            }
        }

        m.serviceDependences().stream()
                .map(ServiceDependence::service)
                .sorted().forEach(s -> sb.append(format(1, "uses %s;%n", s)));

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

    private StringBuilder formatList(StringBuilder sb,
                                     int level,
                                     String firstElement,
                                     Collection<?> c, String sep)
    {
        assert !c.isEmpty();

        TreeSet<?> ls = new TreeSet<>(c);
        boolean first = true;
        for (Object o : ls) {
            if (first) {
                sb.append(format(level, "%s %s", firstElement, o));
                first = false;
            } else {
                sb.append(sep);
                sb.append(format(level, "%s %s", firstElement, o));
            }
        }
        sb.append(";\n");
        return sb;
    }

    private StringBuilder formatList(StringBuilder sb,
                                     int level,
                                     String fmt,
                                     Collection<?> c)
    {
        return formatList(sb, level, fmt, c, false);
    }

    private StringBuilder formatList(StringBuilder sb,
                                     int level,
                                     String fmt,
                                     Collection<?> c,
                                     boolean newline)
    {
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
}
