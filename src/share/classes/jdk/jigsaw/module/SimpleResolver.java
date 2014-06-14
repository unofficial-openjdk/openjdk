/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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

package jdk.jigsaw.module;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jdk.jigsaw.module.ModuleDependence.Modifier;

/**
 * A simple resolver that constructs a module graph from an initial, possibly
 * empty module graph, a module path, and a set of module names.
 */
public final class SimpleResolver {

    // the initial (possibly empty module graph)
    private final ModuleGraph initialGraph;

    // the module path
    private final ModulePath modulePath;

    /**
     * Creates a {@code SimpleResolver} to construct module graphs from an
     * initial module graph. Modules are located on the given module path.
     */
    public SimpleResolver(ModuleGraph initialGraph, ModulePath modulePath) {
        this.initialGraph = Objects.requireNonNull(initialGraph);
        this.modulePath = Objects.requireNonNull(modulePath);
    }

    /**
     * Creates a {@code SimpleResolver} that locates modules on the given module
     * path.
     */
    public SimpleResolver(ModulePath modulePath) {
        this(ModuleGraph.emptyModuleGraph(), modulePath);
    }

    /**
     * Resolve the given named modules.
     */
    public ModuleGraph resolve(Iterable<String> roots) {
        // the selected modules
        Set<Module> selected = new HashSet<>(initialGraph.modules());

        // the visit stack
        Deque<Module> stack = new ArrayDeque<>();

        // push the root modules onto the visit stack to get us started
        for (String root: roots) {
            Module m = modulePath.findModule(Objects.requireNonNull(root));
            if (m == null)
                fail("Module %s does not exist", root);
            stack.offer(m);
        }

        // visit modules until the root modules and their transitive
        // dependencies have been visited
        while (!stack.isEmpty()) {
            Module m = stack.poll();
            selected.add(m);

            // process dependencies
            for (ModuleDependence d: m.moduleDependences()) {
                String dn = d.query().name();

                // find module on module path
                Module other = modulePath.findModule(dn);

                // if not found then check initial module graph
                if (other == null)
                    other = initialGraph.findModule(dn);

                if (other == null)
                    fail("%s requires unknown module %s", m.id().name(), dn);

                if (!selected.contains(other))
                    stack.offer(other);
            }
        }

        // create the readability graph
        Map<Module, Set<Module>> graph = makeGraph(selected);

        // check for permits violations
        checkPermits(graph);

        // TBD check connectedness
        // TDB check for cycles

        // sanity check implementation, -esa only
        assert isSubsetOfInitialGraph(graph);

        return new ModuleGraph(graph, modulePath, initialGraph);
    }

    /**
     * Resolve the given named modules.
     */
    public ModuleGraph resolve(String... roots) {
        return resolve(Arrays.asList(roots));
    }

    /**
     * Returns a {@code Map} representing the readability graph for the given
     * set of modules.
     *
     * The readability graph is created by propagating "requires" through the
     * "public requires" edges of the module dependence graph. So if the module
     * dependence graph has m1 requires m2 && m2 requires public m3 then the
     * resulting readability will contain m1 requires requires m2, m1 requires
     * m3, and m2 requires m3.
     *
     * ###TBD Need to write up a detailed description of this algorithm.
     */
    private Map<Module, Set<Module>> makeGraph(Set<Module> modules) {
        // name -> Module lookup
        Map<String, Module> nameToModule = new HashMap<>();
        modules.forEach(m -> nameToModule.put(m.id().name(), m));

        // the "requires" graph starts as a module dependence graph and
        // is iteratively updated to be the readability graph
        Map<Module, Set<Module>> g1 = new HashMap<>();

        // the "requires public" graph, contains requires public edges only
        Map<Module, Set<Module>> g2 = new HashMap<>();

        // initialize the graphs
        for (Module m: modules) {

            // if the module was present in the initial module graph then
            // its read dependences are copied from the initial graph.
            if (initialGraph.modules().contains(m)) {
                // requires
                Set<Module> reads = initialGraph.readDependences(m);
                g1.put(m, reads);

                // requires public
                g2.put(m, new HashSet<>());
                Map<String, Module> names = new HashMap<>();
                reads.stream().forEach(x -> names.put(x.id().name(), x));
                for (ModuleDependence d: m.moduleDependences()) {
                    if (d.modifiers().contains(Modifier.PUBLIC)) {
                        String dn = d.query().name();
                        Module other = names.get(dn);
                        if (other == null)
                            throw new InternalError();
                        g2.get(m).add(other);
                    }
                }
            } else {
                // module is newly selected
                g1.put(m, new HashSet<>());
                g2.put(m, new HashSet<>());
                for (ModuleDependence d: m.moduleDependences()) {
                    String dn = d.query().name();
                    Module other = nameToModule.get(dn);
                    if (other == null)
                        throw new InternalError();

                    // requires (and requires public)
                    g1.get(m).add(other);

                    // requires public only
                    if (d.modifiers().contains(Modifier.PUBLIC)) {
                        g2.get(m).add(other);
                    }
                }
            }
        }

        // add to g1 until there are no more requires public to propagate
        boolean changed;
        Map<Module, Set<Module>> changes = new HashMap<>();
        do {
            changed = false;
            for (Map.Entry<Module, Set<Module>> entry: g1.entrySet()) {
                Module m1 = entry.getKey();
                Set<Module> m1_requires = entry.getValue();
                for (Module m2: m1_requires) {
                    Set<Module> m2_requires_public = g2.get(m2);
                    for (Module m3: m2_requires_public) {
                        if (!m1_requires.contains(m3)) {
                            changes.computeIfAbsent(m1, k -> new HashSet<>()).add(m3);
                            changed = true;
                        }
                    }
                }
            }
            if (changed) {
                for (Map.Entry<Module, Set<Module>> entry: changes.entrySet()) {
                    Module m1 = entry.getKey();
                    g1.get(m1).addAll(entry.getValue());
                }
                changes.clear();
            }

        } while (changed);

        return g1;
    }

    /**
     * Check the module graph for permits violations.
     *
     * @throws ResolveException if m1 reads m2, and m2 has a permits that
     * doesn't include the name of m1
     */
    private void checkPermits(Map<Module, Set<Module>> graph) {
        for (Map.Entry<Module, Set<Module>> entry: graph.entrySet()) {
            Module m1 = entry.getKey();
            String name = m1.id().name();
            for (Module m2: entry.getValue()) {
                Set<String> permits = m2.permits();
                if (!permits.isEmpty() && !permits.contains(name)) {
                    fail("%s does not permit %s", m2.id(), name);
                }
            }
        }
    }

    /**
     * For debugging purposes, checks that the given graph is a superset of the
     * initial graph. Also checks that the readability relationship of modules
     * that were in the initial graph are identical in the new graph -- ie: the
     * only new edges in the new graph should be from the newly selected
     * modules.
     */
    private boolean isSubsetOfInitialGraph(Map<Module, Set<Module>> graph) {
        if (!graph.keySet().containsAll(initialGraph.modules()))
            return false;
        for (Module m: initialGraph.modules()) {
            Set<Module> s1 = initialGraph.readDependences(m);
            Set<Module> s2 = graph.get(m);
            if (!s1.equals(s2))
                return false;
        }
        return true;
    }

    private void fail(String fmt, Object ... args) {
        throw new ResolveException(fmt, args);
    }
}
