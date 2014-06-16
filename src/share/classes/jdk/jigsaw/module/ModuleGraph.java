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

package jdk.jigsaw.module;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Represents a module graph, typically the result of resolution.
 *
 * The {@link #modules} method returns the set of {@link Module Modules} in the
 * graph. The {@link #readDependences} method provides access to the readability
 * relationships.
 */
public final class ModuleGraph {

    // readability graph
    private final Map<Module, Set<Module>> graph;

    // the module path to locate the modules in this module graph
    private final ModulePath modulePath;

    // the (possibly empty) module graph from which this module graph was composed
    private final ModuleGraph initialGraph;

    // selected modules
    private final Set<Module> modules;

    // map of most recently selected module name -> module
    private final Map<String, Module> nameToModule;

    ModuleGraph(Map<Module, Set<Module>> graph,
                ModulePath modulePath,
                ModuleGraph initialGraph)
    {
        this.graph = graph;
        this.modulePath = modulePath;
        this.initialGraph = initialGraph;
        this.modules = Collections.unmodifiableSet(graph.keySet());

        // most recently selected modules
        Stream<Module> newlySelected;
        if (initialGraph == null) {
            newlySelected = modules.stream();
        } else {
            newlySelected = graph.keySet()
                                 .stream()
                                 .filter(m -> !initialGraph.graph.containsKey(m));
        }
        Map<String, Module> map = new HashMap<>();
        newlySelected.forEach(m -> map.put(m.id().name(), m));
        this.nameToModule = map;
    }

    /**
     * Returns an empty module graph.
     */
    public static ModuleGraph emptyModuleGraph() {
        return new ModuleGraph(Collections.emptyMap(),
                               ModulePath.emptyModulePath(),
                               null);
    }

    /**
     * Returns the set of modules in this module graph.
     */
    public Set<Module> modules() {
        return modules;
    }

    /**
     * Finds a module by name in this module graph. Returns {@code null}
     * if the module is not found.
     */
    public Module findModule(String name) {
        Module m = nameToModule.get(name);
        if (m == null && initialGraph != null)
            m = initialGraph.findModule(name);
        return m;
    }

    /**
     * Returns the set of modules that the given module reads.
     *
     * @apiNote The returned set may include modules that are not in this module
     * graph. This with a module graph created by {@link #}minusInitialModuleGraph}
     * as this module graph does not include the modules in the initial module
     * graph.
     *
     * @throws IllegalAccessException if the module is not in the module graph
     */
    public Set<Module> readDependences(Module m) {
        Set<Module> s = graph.get(m);
        if (s == null)
            throw new IllegalArgumentException(m.id() + " not in module graph");
        return Collections.unmodifiableSet(s);
    }

    /**
     * Returns the module path to locate modules in this module graph.
     */
    public ModulePath modulePath() {
        return modulePath;
    }

    /**
     * Returns the initial module graph used when creating this module graph.
     */
    public ModuleGraph initialModuleGraph() {
        if (initialGraph == null)
            return ModuleGraph.emptyModuleGraph();
        return initialGraph;
    }

    /**
     * Returns {@code true} if the module graph does not contain any modules.
     */
    public boolean isEmpty() {
        return modules.size() == 0;
    }

    /**
     * Returns the set of modules that are in this module graph that were not in
     * the initial module graph.
     */
    public Set<Module> minusInitialModuleGraph() {
        if (initialGraph == null) {
            return modules;
        } else {
            return graph.keySet()
                        .stream()
                        .filter(m -> !initialGraph.graph.containsKey(m))
                        .collect(Collectors.toSet());
        }
    }

    // system module graph; concurrency TBD
    private static ModuleGraph systemModuleGraph;

    /**
     * Sets the system module graph. The system module graph typically includes
     * the modules installed in the runtime image and any modules on the module
     * path specified to the launcher.
     */
    public static void setSystemModuleGraph(ModuleGraph g) {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null)
            sm.checkPermission(new RuntimePermission("setSystemModuleGraph"));
        if (systemModuleGraph != null)
            throw new IllegalStateException("System module graph already set");
        systemModuleGraph = g;
    }

    /**
     * Returns the system module graph.
     */
    public static ModuleGraph getSystemModuleGraph() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null)
            sm.checkPermission(new RuntimePermission("getSystemModuleGraph"));
        return systemModuleGraph;
    }
}
