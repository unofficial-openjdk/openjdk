/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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

package java.lang.module;

import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor.Requires;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jdk.internal.module.Hasher.DependencyHashes;

/**
 * The resolver used by {@link Configuration#resolve} and {@link Configuration#bind}.
 *
 * TODO:
 * - decide on representation of service-use graph, if any. A method that takes a
 *   service type (by class name) and returns a sequence of Module and provider
 *   class is sufficient for ServiceLoader.
 * - avoid most of the cost of bind for the cases where augmenting the module
 *   graph does not add any modules
 * - replace makeGraph with efficient implementation for multiple layers
 * - replace cycle detection. The current DFS is fast at startup for the boot
 *   layer but isn't generally scalable
 * - sort out relationship to Configuration
 */

final class Resolver {

    /**
     * The result of resolution or binding.
     */
    final class Resolution {

        // the set of module descriptors
        private final Set<ModuleDescriptor> selected;

        // maps name to module reference for modules in this resolution
        private final Map<String, ModuleReference> nameToReference;

        // set of nameToReference.values()
        private final Set<ModuleReference> references;

        // the readability graph
        private final Map<ModuleDescriptor, Set<ModuleDescriptor>> graph;

        Resolution(Set<ModuleDescriptor> selected,
                   Map<String, ModuleReference> nameToReference,
                   Map<ModuleDescriptor, Set<ModuleDescriptor>> graph)
        {
            this.selected = Collections.unmodifiableSet(selected);
            this.nameToReference = Collections.unmodifiableMap(nameToReference);
            Set<ModuleReference> refs = new HashSet<>(nameToReference.values());
            this.references = Collections.unmodifiableSet(refs);
            this.graph = graph; // no need to make defensive copy
        }

        Set<ModuleDescriptor> selected() {
            return selected;
        }

        Set<ModuleReference> references() {
            return references;
        }

        Optional<ModuleReference> findReference(String name) {
            return Optional.ofNullable(nameToReference.get(name));
        }

        Set<ModuleDescriptor> reads(ModuleDescriptor descriptor) {
            Set<ModuleDescriptor> reads = graph.get(descriptor);
            if (reads == null) {
                return null;
            } else {
                return Collections.unmodifiableSet(reads);
            }
        }

        /**
         * Returns a new Resolution that this is this Resolution augmented with
         * modules (located via the module reference finders) that are induced
         * by service-use relationships.
         */
        Resolution bind() {
            return Resolver.this.bind();
        }

    }


    private final ModuleFinder beforeFinder;
    private final Layer layer;
    private final ModuleFinder afterFinder;

    // the set of module descriptors, added to at each iteration of resolve
    private final Set<ModuleDescriptor> selected = new HashSet<>();

    // map of module names to references
    private final Map<String, ModuleReference> nameToReference = new HashMap<>();


    private Resolver(ModuleFinder beforeFinder,
                     Layer layer,
                     ModuleFinder afterFinder)
    {
        this.beforeFinder = beforeFinder;
        this.layer = layer;
        this.afterFinder = afterFinder;
    }

    /**
     * Resolves the given named modules. Module dependences are resolved by
     * locating them (in order) using the given {@code beforeFinder}, {@code
     * layer}, and {@code afterFinder}.
     *
     * @throws ResolutionException
     */
    static Resolution resolve(ModuleFinder beforeFinder,
                              Layer layer,
                              ModuleFinder afterFinder,
                              Collection<String> roots)
    {
        Resolver resolver = new Resolver(beforeFinder, layer, afterFinder);
        return resolver.resolve(roots);
    }

    /**
     * Resolve the given collection of modules (by name).
     */
    private Resolution resolve(Collection<String> roots) {

        // create the visit stack to get us started
        Deque<ModuleDescriptor> q = new ArrayDeque<>();
        for (String root : roots) {

            ModuleReference mref = find(beforeFinder, root);
            if (mref == null) {
                // ## Does it make sense to attempt to locate root modules with
                //    a finder other than the beforeFinder?
                mref = find(afterFinder, root);
                if (mref == null) {
                    fail("Module %s does not exist", root);
                }
            }

            trace("Module %s located (%s)", root, mref.location());

            nameToReference.put(root, mref);
            q.push(mref.descriptor());
        }

        resolve(q);

        detectCycles();

        checkHashes();

        Map<ModuleDescriptor, Set<ModuleDescriptor>> graph = makeGraph();

        return new Resolution(selected, nameToReference, graph);
    }

    /**
     * Poll the given {@code Deque} for modules to resolve. The {@code selected}
     * set is updated as modules are processed. On completion the {@code Deque}
     * will be empty.
     *
     * @return The set of module (descriptors) selected by this invocation of
     *         resolve
     */
    private Set<ModuleDescriptor> resolve(Deque<ModuleDescriptor> q) {
        Set<ModuleDescriptor> newlySelected = new HashSet<>();

        // true if all modules are selected
        boolean allModulesSelected = false;

        while (!q.isEmpty()) {
            ModuleDescriptor descriptor = q.poll();
            assert nameToReference.containsKey(descriptor.name());
            selected.add(descriptor);

            // process dependences
            for (ModuleDescriptor.Requires requires : descriptor.requires()) {
                String dn = requires.name();

                // before finder
                ModuleReference mref = find(beforeFinder, dn);

                // already defined to the runtime
                if (mref == null && layer.findModule(dn).isPresent()) {
                    continue;
                }

                // after finder
                if (mref == null) {
                    mref = find(afterFinder, dn);
                }

                // not found
                if (mref == null) {
                    fail("%s requires unknown module %s", descriptor.name(), dn);
                }

                // check if module descriptor has already been seen
                ModuleDescriptor other = mref.descriptor();
                if (!selected.contains(other) && !newlySelected.contains(other)) {

                    trace("Module %s located (%s), required by %s",
                            dn, mref.location(), descriptor.name());

                    newlySelected.add(other);
                    nameToReference.put(dn, mref);
                    q.offer(other);
                }
            }

            // If an automatic module is encountered then its dependences are
            // not known so this requires resolving all modules. We do this at
            // most once.
            if (descriptor.isAutomatic() && !allModulesSelected) {

                findAll().forEach(mref -> {
                    ModuleDescriptor other = mref.descriptor();
                    if (!selected.contains(other) && !newlySelected.contains(other)) {

                        trace("Module %s located (%s), implicitly required by %s",
                                other.name(), mref.location(), descriptor.name());

                        newlySelected.add(other);
                        nameToReference.put(other.name(), mref);
                        q.offer(other);
                    }
                });

                allModulesSelected = true;
            }

        }

        return newlySelected;
    }

    /**
     * Updates the Resolver with modules (located via the module reference finders)
     * that are induced by service-use relationships.
     */
    private Resolution bind() {

        // Scan the finders for all available service provider modules. As java.base
        // uses services then all finders will need to be scanned anyway.
        Map<String, Set<ModuleReference>> availableProviders = new HashMap<>();
        findAll().forEach(mref -> {
            ModuleDescriptor descriptor = mref.descriptor();
            if (!descriptor.provides().isEmpty()) {
                descriptor.provides().keySet().forEach(s ->
                    availableProviders.computeIfAbsent(s, k -> new HashSet<>()).add(mref));
            }
        });

        // create the visit stack
        Deque<ModuleDescriptor> q = new ArrayDeque<>();

        // the initial set of modules that may use services
        Set<ModuleDescriptor> candidateConsumers;
        if (layer == null) {
            candidateConsumers = selected;
        } else {
            candidateConsumers = new HashSet<>();
            candidateConsumers.addAll(layer.allModuleDescriptors());
            candidateConsumers.addAll(selected);
        }

        // Where there is a consumer of a service then resolve all modules
        // that provide an implementation of that service
        // ### TBD to to record the service-use graph
        do {
            for (ModuleDescriptor descriptor : candidateConsumers) {
                if (!descriptor.uses().isEmpty()) {
                    for (String service : descriptor.uses()) {
                        Set<ModuleReference> mrefs = availableProviders.get(service);
                        if (mrefs != null) {
                            for (ModuleReference mref : mrefs) {
                                ModuleDescriptor provider = mref.descriptor();
                                if (!provider.equals(descriptor)) {
                                    if (!selected.contains(provider)) {
                                        trace("Module %s provides %s, used by %s",
                                                provider.name(), service, descriptor.name());
                                        nameToReference.put(provider.name(), mref);
                                        q.push(provider);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            candidateConsumers = resolve(q);

        } while (!candidateConsumers.isEmpty());

        detectCycles();

        checkHashes();

        Map<ModuleDescriptor, Set<ModuleDescriptor>> graph = makeGraph();

        return new Resolution(selected, nameToReference, graph);
    }

    /**
     * Computes and sets the readability graph for the modules in the given
     * Resolution object.
     *
     * The readability graph is created by propagating "requires" through the
     * "public requires" edges of the module dependence graph. So if the module
     * dependence graph has m1 requires m2 && m2 requires public m3 then the
     * resulting readability graph will contain m1 requires requires m2, m1
     * requires m3, and m2 requires m3.
     *
     * ###TBD Replace this will be more efficient implementation
     */
    private Map<ModuleDescriptor, Set<ModuleDescriptor>> makeGraph() {

        // name -> ModuleDescriptor lookup for newly selected modules
        Map<String, ModuleDescriptor> nameToModule = new HashMap<>();
        selected.forEach(d -> nameToModule.put(d.name(), d));

        // the "requires" graph starts as a module dependence graph and
        // is iteratively updated to be the readability graph
        Map<ModuleDescriptor, Set<ModuleDescriptor>> g1 = new HashMap<>();

        // the "requires public" graph, contains requires public edges only
        Map<ModuleDescriptor, Set<ModuleDescriptor>> g2 = new HashMap<>();

        // need "requires public" from the modules in parent layers as
        // there may be selected modules that have a dependence.
        Layer current = this.layer;
        while (current != null) {
            Configuration cf = current.configuration().orElse(null);
            if (cf != null) {
                for (ModuleDescriptor descriptor: cf.descriptors()) {
                    // requires
                    //Set<ModuleDescriptor> reads = cf.readDependences(descriptor);
                    //g1.put(descriptor, reads);

                    // requires public
                    g2.put(descriptor, new HashSet<>());
                    for (Requires d: descriptor.requires()) {
                        if (d.modifiers().contains(Requires.Modifier.PUBLIC)) {
                            String dn = d.name();
                            ModuleReference mref
                                = current.findReference(dn).orElse(null);
                            if (mref == null)
                                throw new InternalError();
                            g2.get(descriptor).add(mref.descriptor());
                        }
                    }
                }
            }
            current = current.parent().orElse(null);
        }

        // add the module dependence edges from the newly selected modules
        for (ModuleDescriptor m : selected) {
            g1.put(m, new HashSet<>());
            g2.put(m, new HashSet<>());
            for (Requires d: m.requires()) {
                String dn = d.name();
                ModuleDescriptor other = nameToModule.get(dn);
                if (other == null && layer != null)
                    other = layer.findReference(dn)
                        .map(ModuleReference::descriptor).orElse(null);
                if (other == null)
                    throw new InternalError(dn + " not found??");

                // requires (and requires public)
                g1.get(m).add(other);

                // requires public only
                if (d.modifiers().contains(Requires.Modifier.PUBLIC)) {
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

        return g1;
    }

    /**
     * Checks the hashes in the extended module descriptor to ensure that they
     * match the hash of the dependency's module reference.
     */
    private void checkHashes() {

        for (ModuleDescriptor descriptor : selected) {
            String mn = descriptor.name();

            // get map of module names to hash
            Optional<DependencyHashes> ohashes
                = nameToReference.get(mn).descriptor().hashes();
            if (!ohashes.isPresent())
                continue;
            DependencyHashes hashes = ohashes.get();

            // check dependences
            for (Requires md: descriptor.requires()) {
                String dn = md.name();
                String recordedHash = hashes.hashFor(dn);

                if (recordedHash != null) {
                    ModuleReference mref = nameToReference.get(dn);
                    if (mref == null)
                        mref = layer.findReference(dn).orElse(null);
                    if (mref == null)
                        throw new InternalError(dn + " not found");

                    String actualHash = mref.computeHash(hashes.algorithm());
                    if (actualHash == null)
                        fail("Unable to compute the hash of module %s", dn);

                    if (!recordedHash.equals(actualHash)) {
                        fail("Hash of %s (%s) differs to expected hash (%s)",
                                dn, actualHash, recordedHash);
                    }
                }
            }
        }

    }

    /**
     * Checks the given module graph for cycles.
     *
     * For now the implementation is a simple depth first search on the
     * dependency graph. We'll replace this later, maybe with Tarjan if we
     * are also checking connectedness.
     */
    private void detectCycles() {
        visited = new HashSet<>();
        visitPath = new LinkedHashSet<>(); // preserve insertion order
        selected.forEach(d -> visit(d));
    }

    // the modules that were visited
    private Set<ModuleDescriptor> visited;

    // the modules in the current visit path
    private Set<ModuleDescriptor> visitPath;

    private void visit(ModuleDescriptor descriptor) {
        if (!visited.contains(descriptor)) {
            boolean added = visitPath.add(descriptor);
            if (!added) {
                throw new ResolutionException("Cycle detected: " +
                                              cycleAsString(descriptor));
            }
            for (Requires requires : descriptor.requires()) {
                ModuleReference mref = nameToReference.get(requires.name());
                if (mref != null) {
                    // dependency is in this configuration
                    ModuleDescriptor other = mref.descriptor();
                    // ignore self reference
                    if (other != descriptor)
                        visit(other);
                }
            }
            visitPath.remove(descriptor);
            visited.add(descriptor);
        }
    }

    /**
     * Returns a String with a list of the modules in a detected cycle.
     */
    private String cycleAsString(ModuleDescriptor descriptor) {
        List<ModuleDescriptor> list = new ArrayList<>(visitPath);
        list.add(descriptor);
        int index = list.indexOf(descriptor);
        return list.stream()
                   .skip(index)
                   .map(ModuleDescriptor::name)
                   .collect(Collectors.joining(" -> "));
    }


    /**
     * Returns true if a module of the given module's name is in a parent Layer
     */
    private boolean inParentLayer(ModuleReference mref) {
        return layer.findModule(mref.descriptor().name()).isPresent();
    }

    /**
     * Invokes the finder's find method to find the given module.
     */
    private static ModuleReference find(ModuleFinder finder, String mn) {
        try {
            return finder.find(mn).orElse(null);
        } catch (UncheckedIOException e) {
            throw new ResolutionException(e.getCause());
        } catch (RuntimeException | Error e) {
            throw new ResolutionException(e);
        }
    }

    /**
     * Invokes the finder's allModules to find all modules.
     */
    private Stream<ModuleReference> findAll() {
        try {
            Stream<ModuleReference> s1
                = beforeFinder.findAll().stream();
            Stream<ModuleReference> s2
                = afterFinder.findAll().stream().filter(m -> !inParentLayer(m));
            return Stream.concat(s1, s2);
        } catch (UncheckedIOException e) {
            throw new ResolutionException(e.getCause());
        } catch (RuntimeException | Error e) {
            throw new ResolutionException(e);
        }
    }

    private static final boolean debug = false;

    private static void trace(String fmt, Object ... args) {
        if (debug) {
            System.out.format(fmt, args);
            System.out.println();
        }
    }

    private static void fail(String fmt, Object ... args) {
        throw new ResolutionException(fmt, args);
    }
}
