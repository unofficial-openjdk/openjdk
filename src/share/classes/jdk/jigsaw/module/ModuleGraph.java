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

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Represents a module graph that is the result of {@link Resolver#resolve
 * resolution} or {@link #bindServices binding}.
 *
 * <p> A {@code ModuleGraph} is a set of {@link #modules() modules} with directed
 * edges that represent the {@link #readDependences readability-relationship}
 * between the modules. The modules in a module graph have a unique
 * {@link ModuleId module-id}, the names of the modules in the set of modules
 * returned by {#link minusInitialModuleGraph} are also unique. </p>
 *
 * <p> A module graph can be augmented with additional modules (and edges) at a
 * later time to produce a new, richer graph. This is the process of module graph
 * composition when a {@link Resolver} is created with an initial module graph.
 * When augmenting an existing module graph by resolution then the {@link
 * #minusInitialModuleGraph} method may be used to obtain the set of modules
 * added to the graph. </p>
 *
 * <p>  A module graph may also be augmented with modules from its module path
 * that are induced by <em></em>service-use</em> relationships. This is the
 * process of {@link #bindServices binding}.
 *
 * <p> The following example augments the {@link #getSystemModuleGraph
 * system-module-graph} with modules that are the result of resolving a module
 * named <em>myapp</em>. </p>
 *
 * <pre>{@code
 *     ModulePath mp = ModulePath.ofDirectories("dir1", "dir2", "dir3");
 *     Resolver r = new Resolver(ModuleGraph.getSystemModuleGraph(), mp);
 *     ModuleGraph g = r.resolve("myapp");
 *     Set<Module> added = g.minusInitialModuleGraph();
 * }</pre>
 *
 * @see ClassLoader#defineModules
 */
public final class ModuleGraph {

    // the resolver, can be {@code null} in the case of an empty module graph
    private final Resolver resolver;

    // readability graph
    private final Map<Module, Set<Module>> graph;

    // all modules in the module graph (including the initial module graph)
    private final Set<Module> modules;

    // map of selected module name -> module
    // "selected modules" = not in the initial module graph
    private final Map<String, Module> nameToModule;

    /**
     * Creates a new {@code ModuleGraph}.
     *
     * @param resolver The {@code Resolver} that created this module graph
     * @param graph The readability graph
     */
    ModuleGraph(Resolver resolver, Map<Module, Set<Module>> graph) {
        this.resolver = resolver;
        this.graph = graph;
        this.modules = Collections.unmodifiableSet(graph.keySet());

        // create name->module map of the recently selected modules
        Map<String, Module> map;
        if (resolver == null) {
            map = Collections.emptyMap();
        } else {
            ModuleGraph g = resolver.initialModuleGraph();
            Stream<Module> newlySelected;
            if (g.isEmpty()) {
                newlySelected = modules.stream();
            } else {
                newlySelected = graph.keySet()
                                     .stream()
                                     .filter(m -> !g.graph.containsKey(m));
            }
            map = new HashMap<>();
            newlySelected.forEach(m -> map.put(m.id().name(), m));
        }
        this.nameToModule = map;
    }

    /**
     * Returns an empty module graph.
     */
    static ModuleGraph emptyModuleGraph() {
        return new ModuleGraph(null, Collections.emptyMap());
    }

    /**
     * Returns the set of modules in this module graph.
     */
    public Set<Module> modules() {
        return modules;
    }

    /**
     * Finds a module by name in this module graph. This method first searches
     * the set of modules that are in this module graph but not in the
     * {@link #initialModuleGraph}. If not found then it searches the initial
     * module graph.
     *
     * @return The module or {@code null} if not found
     */
    public Module findModule(String name) {
        Module m = nameToModule.get(name);
        if (m == null && resolver != null) {
            // not a newly selected module so try the initial module graph
            m = resolver.initialModuleGraph().findModule(name);
        }
        return m;
    }

    /**
     * Returns the set of modules that the given module reads.
     *
     * @throws IllegalArgumentException if the module is not in this module graph
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
        return resolver.modulePath();
    }

    /**
     * Returns the initial module graph used when creating this module graph.
     */
    public ModuleGraph initialModuleGraph() {
        if (resolver == null) {
            return emptyModuleGraph();
        } else {
            return resolver.initialModuleGraph();
        }
    }

    /**
     * Returns {@code true} if the module graph does not contain any modules.
     */
    public boolean isEmpty() {
        return modules.size() == 0;
    }

    /**
     * Returns the set of modules that are in this module graph that are not
     * in the given module graph.
     */
    public Set<Module> minus(ModuleGraph g) {
        return graph.keySet()
                    .stream()
                    .filter(m -> !g.graph.containsKey(m))
                    .collect(Collectors.toSet());
    }

    /**
     * Returns the set of modules that are in this module graph that were not in
     * the initial module graph.
     */
    public Set<Module> minusInitialModuleGraph() {
        return minus(resolver.initialModuleGraph());
    }

    /**
     * Returns a module graph that is this module graph augmented with modules
     * from the module path that are induced by service-use relationships.
     *
     * <p> The {@link #initialModuleGraph} of the new module graph is the same
     * as this module graph. </p>
     *
     * @throws ResolveException if the module dependences of a service provider
     * module cannot be resolved
     */
    public ModuleGraph bindServices() {
        // empty module graph, nothing to do
        if (resolver == null)
            return this;

        // all resolved modules, add to this as service provider modules are resolved
        Set<Module> resolved = new HashSet<>(this.modules);

        // create the visit stack
        Deque<Module> q = new ArrayDeque<>();

        // the set of services (name of service type) for which service providers
        // have been searched for on the module path
        Set<String> servicesSearched = new HashSet<>();

        // the set of modules with service dependences that need to be visited
        Set<Module> serviceConsumersToVisit = new HashSet<>();

        // seed with the consumers in the existing module graph
        modules.stream().filter(m -> !m.serviceDependences().isEmpty())
                        .forEach(serviceConsumersToVisit::add);

        // iterate until there are no new service consumers to visit
        while (!serviceConsumersToVisit.isEmpty()) {

            // process the service dependences of service consumers
            for (Module m: serviceConsumersToVisit) {
                for (ServiceDependence d: m.serviceDependences()) {
                    String service = d.service();
                    if (!servicesSearched.contains(service)) {
                        // find all modules on module-path that provide "service"
                        for (Module other: resolver.modulePath().allModules()) {
                            if (other.services().containsKey(service)) {
                                // ignore permits
                                if (!resolved.contains(other)) {
                                    q.offer(other);
                                }
                            }
                        }
                        servicesSearched.add(service);
                    }
                }
            }
            serviceConsumersToVisit.clear();

            // there may be service providers to resolve
            if (!q.isEmpty()) {
                Set<Module> newlySelected = resolver.resolve(q, resolved);
                // newly selected modules may have service dependences
                for (Module m: newlySelected) {
                    if (!m.serviceDependences().isEmpty()) {
                        serviceConsumersToVisit.add(m);
                    }
                }
                resolved.addAll(newlySelected);
            }
        }

        // if there aren't any additional modules then we are done, otherwise
        // create a new readability graph that includes the newly selected
        // service provider modules (and their dependencies)
        if (resolved.size() == modules.size()) {
            return this;
        } else {
            resolved.removeAll(initialModuleGraph().modules());
            return resolver.finish(resolved);
        }
    }

    // system module graph; concurrency TBD
    private static ModuleGraph systemModuleGraph;

    /**
     * Sets the system module graph. The system module graph typically includes
     * the modules installed in the runtime image and any modules on the module
     * path specified to the launcher.
     *
     * @throws IllegalStateException if the system module graph is already set
     * @throws SecurityException if denied by the security manager
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
     * Returns the system module graph. Returns {@code null} if the system
     * module graph has not been setup,
     */
    public static ModuleGraph getSystemModuleGraph() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null)
            sm.checkPermission(new RuntimePermission("getSystemModuleGraph"));
        return systemModuleGraph;
    }
}
