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
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The resolver used by {@link Configuration#resolve} and {@link Configuration#bind}.
 *
 * @implNote This should be merged with Configuration or at least have the
 * result of resolve or bind be a Configuration.
 */

class Resolver {

    private final ModuleArtifactFinder beforeFinder;
    private final Layer layer;
    private final ModuleArtifactFinder afterFinder;

    /**
     * The result of resolution or binding.
     */
    static class Resolution {
        private final Resolver resolver;
        private final Map<String, ModuleArtifact> nameToArtifact = new HashMap<>();
        private final Set<ModuleDescriptor> selected = new HashSet<>();
        private Map<ModuleDescriptor, Set<ModuleDescriptor>> graph;

        Resolution(Resolver resolver) {
            this.resolver = resolver;
        }

        Resolver resolver() {
            return resolver;
        }

        Set<ModuleDescriptor> selected() {
            return selected;
        }

        ModuleArtifact findArtifact(String name) {
            return nameToArtifact.get(name);
        }

        Set<ModuleDescriptor> readDependences(ModuleDescriptor descriptor) {
            return graph.get(descriptor);
        }

        /**
         * Bind service provider modules (and their dependences) to
         * create a new result.
         *
         * @implNote Not thread safe
         */
        Resolution bind() {
            // copy the resolution result
            Resolution r = new Resolution(resolver);
            r.nameToArtifact.putAll(nameToArtifact);
            r.selected.addAll(selected);

            // bind service and create a new readability graph
            resolver.bind(r);
            resolver.makeGraph(r);
            return r;
        }
    }

    Resolver(ModuleArtifactFinder beforeFinder,
              Layer layer,
              ModuleArtifactFinder afterFinder)
    {
        this.beforeFinder = Objects.requireNonNull(beforeFinder);
        this.layer = Objects.requireNonNull(layer);
        this.afterFinder = Objects.requireNonNull(afterFinder);
    }

    Layer layer() {
        return layer;
    }

    /**
     * Given the given collection of modules (by name).
     */
    Resolution resolve(Collection<String> input) {
        Resolution r = new Resolution(this);

        // create the visit stack to get us started
        Deque<ModuleArtifact> q = new ArrayDeque<>();
        for (String name: input) {
            ModuleArtifact artifact = beforeFinder.find(name);
            if (artifact == null)
                artifact = afterFinder.find(name);
            if (artifact == null)
                fail("Module %s does not exist", name);
            q.push(artifact);
        }

        resolve(r, q);
        makeGraph(r);
        return r;
    }

    /**
     * Poll the given {@code Deque} for modules to resolve. Resolution
     * updates the given {@code Resolution} object with the result. On
     * completion the {@code Deque} will be empty.
     *
     * @return The module descriptors of the modules selected by this
     * invovation of resolve
     */
    private Set<ModuleDescriptor> resolve(Resolution r, Deque<ModuleArtifact> q) {
        Set<ModuleDescriptor> newlySelected = new HashSet<>();

        while (!q.isEmpty()) {
            ModuleArtifact artifact = q.poll();
            ModuleDescriptor descriptor = artifact.descriptor();

            r.selected.add(descriptor);
            newlySelected.add(descriptor);
            r.nameToArtifact.put(descriptor.name(), artifact);

            // process dependencies
            for (ModuleDependence d: descriptor.moduleDependences()) {
                String dn = d.query().name();

                // in overrides?
                ModuleArtifact other = beforeFinder.find(dn);

                // already defined to the runtime
                if (other == null && layer.findModule(dn) != null) {
                    continue;
                }

                // normal finder
                if (other == null)
                    other = afterFinder.find(dn);

                if (other == null) {
                    fail("%s requires unknown module %s",
                            descriptor.name(), dn);
                }

                ModuleDescriptor otherDescriptor = other.descriptor();
                if (!r.selected.contains(otherDescriptor))  {
                    r.selected.add(otherDescriptor);
                    newlySelected.add(otherDescriptor);
                    q.offer(other);
                    r.nameToArtifact.put(dn, artifact);
                }
            }
        }

        return newlySelected;
    }

    /**
     * Updates the given {@code Resolution} with modules located via the finders
     * that are induced by service-use relationships.
     */
    private void bind(Resolution r) {

        // modules bound to the runtime
        Set<ModuleDescriptor> boundModules;
        if (layer == null) {
            boundModules = Collections.emptySet();
        } else {
            boundModules = layer.allModuleDescriptors();
        }

        // we will add to this as service provider modules are resolved
        Set<ModuleDescriptor> resolved = new HashSet<>();
        resolved.addAll(boundModules);
        resolved.addAll(r.selected);

        // create the visit stack
        Deque<ModuleArtifact> q = new ArrayDeque<>();

        // the set of services (name of service type) for which service providers
        // have been searched for on the module path
        Set<String> servicesSearched = new HashSet<>();

        // the set of modules with service dependences that need to be visited
        Set<ModuleDescriptor> serviceConsumersToVisit = new HashSet<>();

        // seed with all consumers resolved so far
        resolved.stream()
                .filter(m -> !m.serviceDependences().isEmpty())
                .forEach(serviceConsumersToVisit::add);

        // iterate until there are no new service consumers to visit
        while (!serviceConsumersToVisit.isEmpty()) {

            // process the service dependences of service consumers
            for (ModuleDescriptor m: serviceConsumersToVisit) {
                for (ServiceDependence d: m.serviceDependences()) {
                    String service = d.service();
                    if (!servicesSearched.contains(service)) {

                        // find all modules that provide "service", the order
                        // doesn't matter here.
                        Stream.concat(beforeFinder.allModules().stream(),
                                afterFinder.allModules().stream())
                                .forEach(artifact -> {
                                    ModuleDescriptor descriptor = artifact.descriptor();
                                    if (descriptor.services().containsKey(service)) {
                                        if (!resolved.contains(descriptor)) {
                                            q.offer(artifact);
                                        }
                                    }
                                });

                        servicesSearched.add(service);
                    }
                }
            }
            serviceConsumersToVisit.clear();

            // there may be service providers to resolve
            if (!q.isEmpty()) {
                Set<ModuleDescriptor> newlySelected = resolve(r, q);

                // newly selected modules may have service dependences
                for (ModuleDescriptor descriptor: newlySelected) {
                    if (!descriptor.serviceDependences().isEmpty()) {
                        serviceConsumersToVisit.add(descriptor);
                    }
                }
                resolved.addAll(newlySelected);
            }
        }
    }

    /**
     * Returns a {@code Map} representing the readability graph for the given
     * set of modules.
     *
     * The readability graph is created by propagating "requires" through the
     * "public requires" edges of the module dependence graph. So if the module
     * dependence graph has m1 requires m2 && m2 requires public m3 then the
     * resulting readability graph will contain m1 requires requires m2, m1
     * requires m3, and m2 requires m3.
     *
     * ###TBD Need to write up a detailed description of this algorithm.
     */
    private void makeGraph(Resolution r) {

        // name -> ModuleDescriptor lookup for newly selected modules
        Map<String, ModuleDescriptor> nameToModule = new HashMap<>();
        r.selected.forEach(d -> nameToModule.put(d.name(), d));

        // the "requires" graph starts as a module dependence graph and
        // is iteratively updated to be the readability graph
        Map<ModuleDescriptor, Set<ModuleDescriptor>> g1 = new HashMap<>();

        // the "requires public" graph, contains requires public edges only
        Map<ModuleDescriptor, Set<ModuleDescriptor>> g2 = new HashMap<>();

        // need "requires public" from the modules in parent layers as
        // there may be selected modules that have a dependence.
        Layer current = this.layer;
        while (current != null) {
            Configuration cf = current.configuration();
            if (cf != null) {
                for (ModuleDescriptor descriptor: cf.descriptors()) {
                    // requires
                    //Set<ModuleDescriptor> reads = cf.readDependences(descriptor);
                    //g1.put(descriptor, reads);

                    // requires public
                    g2.put(descriptor, new HashSet<>());
                    for (ModuleDependence d: descriptor.moduleDependences()) {
                        if (d.modifiers().contains(ModuleDependence.Modifier.PUBLIC)) {
                            String dn = d.query().name();
                            ModuleArtifact artifact = current.findArtifact(dn);
                            if (artifact == null)
                                throw new InternalError();
                            g2.get(descriptor).add(artifact.descriptor());
                        }
                    }
                }
            }
            current = current.parent();
        }

        // add the module dependence edges from the newly selected modules
        for (ModuleDescriptor m: r.selected) {
            g1.put(m, new HashSet<>());
            g2.put(m, new HashSet<>());
            for (ModuleDependence d: m.moduleDependences()) {
                String dn = d.query().name();
                ModuleDescriptor other = nameToModule.get(dn);
                if (other == null && layer != null)
                    other = layer.findArtifact(dn).descriptor();
                if (other == null)
                    throw new InternalError(dn + " not found??");

                // requires (and requires public)
                g1.get(m).add(other);

                // requires public only
                if (d.modifiers().contains(ModuleDependence.Modifier.PUBLIC)) {
                    g2.get(m).add(other);
                }
            }
        }

        // add to g1 until there are no more requires public to propagate
        boolean changed;
        Map<ModuleDescriptor, Set<ModuleDescriptor>> changes = new HashMap<>();
        do {
            changed = false;
            for (Map.Entry<ModuleDescriptor, Set<ModuleDescriptor>> entry: g1.entrySet()) {
                ModuleDescriptor m1 = entry.getKey();
                Set<ModuleDescriptor> m1_requires = entry.getValue();
                for (ModuleDescriptor m2: m1_requires) {
                    Set<ModuleDescriptor> m2_requires_public = g2.get(m2);
                    for (ModuleDescriptor m3: m2_requires_public) {
                        if (!m1_requires.contains(m3)) {
                            changes.computeIfAbsent(m1, k -> new HashSet<>()).add(m3);
                            changed = true;
                        }
                    }
                }
            }
            if (changed) {
                for (Map.Entry<ModuleDescriptor, Set<ModuleDescriptor>> entry: changes.entrySet()) {
                    ModuleDescriptor m1 = entry.getKey();
                    g1.get(m1).addAll(entry.getValue());
                }
                changes.clear();
            }

        } while (changed);

        // TBD - for each m1 -> m2 then need to check that m2 exports something to
        // m1. Need to watch out for the "hollowed-out case" where m2 is an aggregator.

        r.graph = g1;
    }

    private static void fail(String fmt, Object ... args) {
        throw new ResolveException(fmt, args);
    }
}