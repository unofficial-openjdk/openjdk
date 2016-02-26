/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.io.PrintStream;
import java.lang.module.ModuleDescriptor;
import static java.lang.module.ModuleDescriptor.Requires.Modifier.*;

import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.sun.tools.jdeps.Module.*;
import static com.sun.tools.jdeps.ModulePaths.SystemModulePath.JAVA_BASE;

/**
 * Analyze module dependences and compare with module descriptor.
 * Also identify any qualified exports not used by the target module.
 */
class ModuleAnalyzer {
    final ModulePaths modulePaths;
    final DependencyFinder dependencyFinder;
    final Module root;
    final Set<Module> modules;
    final Set<String> requiresPublic = new HashSet<>();
    final Set<String> requires = new HashSet<>();
    final Set<Module> exportTargets = new HashSet<>();
    final JdepsFilter filter;
    ModuleAnalyzer(ModulePaths modulePaths, DependencyFinder finder,
                   String moduleName) {
        this.modulePaths = modulePaths;
        this.dependencyFinder = finder;
        this.root = modulePaths.getModules().get(moduleName);
        this.modules = modulePaths.dependences(moduleName);

        root.exports().values().stream()
             .flatMap(Set::stream)
             .map(target -> modulePaths.getModules().get(target))
             .forEach(this.exportTargets::add);

        this.filter = new JdepsFilter.Builder().filter(true, true).build();
    }

    /**
     * Returns a graph of transitive closure of the given modules.
     *
     * This method does not add the implicit read edges and is intended to
     * get all transitive closures in (reverse) topological sort.
     */
    public static Graph<Module> graph(ModulePaths modulePaths, Module... modules) {
        Graph.Builder<Module> gb = new Graph.Builder<>();
        for (Module module : modules) {
            module.descriptor().requires().stream()
                    .map(ModuleDescriptor.Requires::name)
                    .map(mn -> modulePaths.getModules().get(mn))
                    .forEach(m -> {
                        gb.addNode(m);
                        gb.addEdge(module, m);
                    });
        }
        return gb.build();
    }

    /**
     * Do the analysis
     */
    public boolean run() throws IOException {
        computeRequiresPublic();
        computeRequires();
        // apply transitive reduction and reports recommended requires.
        analyzeDeps();
        // detect any qualiifed exports not used by the target module
        checkQualifiedExports();
        return true;
    }

    /**
     * Compute 'requires public' dependences by analyzing API dependencies
     */
    private void computeRequiresPublic() throws IOException {
        JdepsFilter.Builder builder = new JdepsFilter.Builder();
        // only analyze exported API
        root.descriptor.exports().stream()
                .filter(exp -> !exp.targets().isPresent())
                .map(ModuleDescriptor.Exports::source)
                .forEach(builder::includePackage);

        JdepsFilter filter = builder.filter(true, true).build();

        // analyze dependences for exported packages
        dependencyFinder.findDependencies(filter, true /* api only */, 1);
        Analyzer analyzer = new Analyzer(Analyzer.Type.CLASS, filter);
        analyzer.run(modules);

        // record requires public
        analyzer.requires(root)
                .filter(m -> m != JAVA_BASE && m != root)
                .map(Archive::getName)
                .forEach(requiresPublic::add);
        trace("requires public: %s%n", requiresPublic);
    }

    private void computeRequires() throws IOException {
        // add the exportTargets of the qualified exports to the root set
        exportTargets.stream()
                .peek(target -> trace("add root: %s%n", target))
                .forEach(dependencyFinder::addRoot);

        // analyze all classes
        dependencyFinder.findDependencies(filter, false /* all classes */, 1);
        Analyzer analyzer = new Analyzer(Analyzer.Type.CLASS, filter);
        analyzer.run(modules);

        transitiveClosure(analyzer, root).stream()
                .filter(m -> m != JAVA_BASE && m != root)
                .map(Module::name)
                .forEach(requires::add);
        trace("dependences: %s%n", requires);
    }

    /**
     * Apply transitive reduction on the resulting graph and reports
     * recommended requires.
     */
    private void analyzeDeps() {
        String moduleName = root.name();

        Graph.Builder<String> builder = new Graph.Builder<>();
        requires.stream()
                .forEach(mn -> {
                    builder.addNode(mn);
                    builder.addEdge(moduleName, mn);
                });

        Graph.Builder<String> builder1 = new Graph.Builder<>();
        requiresPublic.stream()
                .forEach(mn -> {
                    builder1.addNode(mn);
                    builder1.addEdge(moduleName, mn);
                });
        Graph<String> rbg = builder1.build().reduce();
        Graph<String> graph = builder.build().reduce(rbg);
        if (traceOn) {
            graph.printGraph(System.out);
        };

        if (matches(root.descriptor(), requires, requiresPublic)) {
            System.out.println("--- Analysis result: no change for " + root.name());
        } else {
            System.out.println("--- Analysis result: new requires for " + root.name());
            System.out.format("module %s%n", root.name());
            if (!requires.isEmpty()) {
                graph.adjacentNodes(moduleName)
                     .stream()
                     .sorted()
                     .forEach(dn -> System.out.format("  requires %s%s;%n",
                                requiresPublic.contains(dn) ? "public " : "", dn));
            }
            System.out.println("\n---  Module descriptor");
            Graph<Module> mdGraph = graph(modulePaths, root);
            mdGraph.reverse(m -> printModuleDescriptor(System.out, m.descriptor()));
        }
    }

    /**
     * Detects any qualified exports not used by the target module.
     */
    private void checkQualifiedExports() throws IOException {
        Analyzer analyzer = new Analyzer(Analyzer.Type.CLASS, filter);
        analyzer.run(dependencyFinder.roots());

        // build the qualified exports map
        Map<String, Set<String>> qualifiedExports =
            root.exports().entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .collect(Collectors.toMap(Function.identity(), _k -> new HashSet<>()));

        // adds to the qualified exports map if a module references it
        for (Module m : exportTargets) {
            analyzer.dependences(m).stream()
                    .map(this::toPackageName)
                    .filter(qualifiedExports::containsKey)
                    .forEach(pn -> qualifiedExports.get(pn).add(m.name()));
        }

        // compare with the exports from ModuleDescriptor
        Set<String> staleQualifiedExports =
            qualifiedExports.keySet().stream()
                .filter(pn -> !qualifiedExports.get(pn).equals(root.exports().get(pn)))
                .collect(Collectors.toSet());

        if (!staleQualifiedExports.isEmpty()) {
            System.out.println("--- Unused qualified exports in " + root.name());
            for (String pn : staleQualifiedExports) {
                Set<String> unused = new HashSet<>(root.exports().get(pn));
                unused.removeAll(qualifiedExports.get(pn));
                System.out.format("  exports %s to %s%n", pn,
                                  unused.stream().collect(Collectors.joining(",")));
            }
        }
    }

    private String toPackageName(String cn) {
        int i = cn.lastIndexOf('.');
        return i > 0 ? cn.substring(0, i) : "";
    }

    private boolean matches(ModuleDescriptor md, Set<String> requires, Set<String> requiresPublic) {
        Set<String> reqPublic = md.requires().stream()
                .filter(req -> req.modifiers().contains(PUBLIC))
                .map(ModuleDescriptor.Requires::name)
                .collect(Collectors.toSet());
        if (!requiresPublic.equals(reqPublic))
            return false;

        if (requires.size() != md.requires().size())
            return false;

        return md.requires().stream()
                 .map(ModuleDescriptor.Requires::name)
                 .filter(req -> !requires.contains(req))
                 .findAny().isPresent() == false;
    }

    private void printModuleDescriptor(PrintStream out, ModuleDescriptor descriptor) {
        if (descriptor.name().equals("java.base"))
            return;

        out.format("module %s%n", descriptor.name());
        descriptor.requires()
                .stream()
                .sorted(Comparator.comparing(ModuleDescriptor.Requires::name))
                .forEach(req -> out.format("  requires %s;%n", req));
    }

    private Set<Module> transitiveClosure(Analyzer analyzer, Module module) {
        Set<Module> transitiveDeps = new HashSet<>();
        transitiveDeps.add(module);
        Deque<Module> deque = new LinkedList<>();
        analyzer.requires(module)
                .map(Archive::getModule)
                .forEach(deque::add);

        Module source;
        while ((source = deque.poll()) != null) {
            if (transitiveDeps.contains(source))
                continue;
            transitiveDeps.add(source);
            analyzer.requires(source)
                    .map(Archive::getModule)
                    .forEach(deque::add);
        }
        return transitiveDeps;
    }

    static class Graph<T> {
        private final Set<T> nodes;
        private final Map<T, Set<T>> edges;

        private Graph(Set<T> nodes, Map<T, Set<T>> edges) {
            this.nodes = nodes;
            this.edges = edges;
        }

        public Set<T> nodes() {
            return nodes;
        }

        public Map<T, Set<T>> edges() {
            return edges;
        }

        public Set<T> adjacentNodes(T u) {
            return edges.get(u);
        }

        /**
         * Returns a new Graph after transitive reduction
         */
        public Graph<T> reduce() {
            Graph.Builder<T> builder = new Builder<>();
            nodes.stream()
                    .forEach(u -> {
                        builder.addNode(u);
                        edges.get(u).stream()
                                .filter(v -> !pathExists(u, v, false))
                                .forEach(v -> builder.addEdge(u, v));
                    });
            return builder.build();
        }

        /**
         * Returns a new Graph after transitive reduction.  All edges in
         * the given g takes precedence over this graph.
         *
         * @throw IllegalArgumentException g must be a subgraph this graph
         */
        public Graph<T> reduce(Graph<T> g) {
            boolean subgraph = nodes.containsAll(g.nodes) && g.edges.keySet().stream()
                    .allMatch(u -> adjacentNodes(u).containsAll(g.adjacentNodes(u)));
            if (!subgraph) {
                throw new IllegalArgumentException(g + " is not a subgraph of " + this);
            }

            Graph.Builder<T> builder = new Builder<>();
            nodes.stream()
                    .forEach(u -> {
                        builder.addNode(u);
                        // filter the edge if there exists a path from u to v in the given g
                        // or there exists another path from u to v in this graph
                        edges.get(u).stream()
                                .filter(v -> !g.pathExists(u, v) && !pathExists(u, v, false))
                                .forEach(v -> builder.addEdge(u, v));
                    });

            // add the overlapped edges from this graph and the given g
            g.edges().keySet().stream()
                    .forEach(u -> g.adjacentNodes(u).stream()
                            .filter(v -> isAdjacent(u, v))
                            .forEach(v -> builder.addEdge(u, v)));
            return builder.build();
        }

        /**
         * Returns nodes sorted in topological order.
         */
        public Stream<T> orderedNodes() {
            TopoSorter<T> sorter = new TopoSorter<>(this);
            return sorter.result.stream();
        }

        /**
         * Iterates the nodes sorted in topological order and performs the
         * given action.
         */
        public void ordered(Consumer<T> action) {
            TopoSorter<T> sorter = new TopoSorter<>(this);
            sorter.ordered(action);
        }

        /**
         * Iterates the nodes sorted in reverse topological order and
         * performs the given action.
         */
        public void reverse(Consumer<T> action) {
            TopoSorter<T> sorter = new TopoSorter<>(this);
            sorter.reverse(action);
        }

        private boolean isAdjacent(T u, T v) {
            return edges.containsKey(u) && edges.get(u).contains(v);
        }

        private boolean pathExists(T u, T v) {
            return pathExists(u, v, true);
        }

        /**
         * Returns true if there exists a path from u to v in this graph.
         * If includeAdjacent is false, it returns true if there exists
         * another path from u to v of distance > 1
         */
        private boolean pathExists(T u, T v, boolean includeAdjacent) {
            if (!nodes.contains(u) || !nodes.contains(v)) {
                return false;
            }
            if (includeAdjacent && isAdjacent(u, v)) {
                return true;
            }
            Deque<T> stack = new LinkedList<>();
            Set<T> visited = new HashSet<>();
            stack.push(u);
            while (!stack.isEmpty()) {
                T node = stack.pop();
                if (node.equals(v)) {
                    return true;
                }
                if (!visited.contains(node)) {
                    visited.add(node);
                    edges.get(node).stream()
                            .filter(e -> includeAdjacent || !node.equals(u) || !e.equals(v))
                            .forEach(e -> stack.push(e));
                }
            }
            assert !visited.contains(v);
            return false;
        }

        void printGraph(PrintStream out) {
            out.println("graph for " + nodes);
            nodes.stream()
                 .forEach(u -> adjacentNodes(u).stream()
                            .forEach(v -> out.format("%s -> %s%n", u, v)));
        }

        @Override
        public String toString() {
            return nodes.toString();
        }

        static class Builder<T> {
            final Set<T> nodes = new HashSet<>();
            final Map<T, Set<T>> edges = new HashMap<>();

            public void addNode(T node) {
                if (nodes.contains(node)) {
                    return;
                }
                nodes.add(node);
                edges.computeIfAbsent(node, _e -> new HashSet<>());
            }

            public void addEdge(T u, T v) {
                addNode(u);
                addNode(v);
                edges.get(u).add(v);
            }

            public Graph<T> build() {
                return new Graph<>(nodes, edges);
            }
        }
    }

    static class TopoSorter<T> {
        final Deque<T> result = new LinkedList<>();
        final Deque<T> nodes;
        final Graph<T> graph;
        TopoSorter(Graph<T> graph) {
            this.graph = graph;
            this.nodes = new LinkedList<>(graph.nodes);
            sort();
        }

        public void ordered(Consumer<T> action) {
            result.iterator().forEachRemaining(action);
        }

        public void reverse(Consumer<T> action) {
            result.descendingIterator().forEachRemaining(action);
        }

        private void sort() {
            Deque<T> visited = new LinkedList<>();
            Deque<T> done = new LinkedList<>();
            T node;
            while ((node = nodes.poll()) != null) {
                if (!visited.contains(node)) {
                    visit(node, visited, done);
                }
            }
        }

        private void visit(T node, Deque<T> visited, Deque<T> done) {
            if (visited.contains(node)) {
                if (!done.contains(node)) {
                    throw new IllegalArgumentException("Cyclic detected: " +
                            node + " " + graph.edges().get(node));
                }
                return;
            }
            visited.add(node);
            graph.edges().get(node).stream()
                 .forEach(x -> visit(x, visited, done));
            done.add(node);
            result.addLast(node);
        }
    }
}
