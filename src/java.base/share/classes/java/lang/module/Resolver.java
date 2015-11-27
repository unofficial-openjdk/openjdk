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

import java.lang.reflect.Layer;
import java.lang.module.ModuleDescriptor.Provides;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
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
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.lang.module.Configuration.ReadDependence;

import jdk.internal.module.Hasher.DependencyHashes;

/**
 * The resolver used by {@link Configuration#resolve} and {@link
 * Configuration#bind}.
 */

final class Resolver {

    /**
     * Accumulates references to modules selected by the resolver during
     * resolution.
     *
     * When resolution is complete then the {@code finish} method should be
     * invoked to perform the post-resolution checks, generate the readability
     * graph and create a {@code Result} object.
     */
    static final class Selected {

        private final Resolver resolver;

        // module name -> module reference
        private final Map<String, ModuleReference> nameToReference;

        /**
         * Creates a new Selected object for use by the resolver.
         */
        Selected(Resolver resolver) {
            this.resolver = resolver;
            this.nameToReference = new HashMap<>();
        }

        /**
         * Creates a new Selected object that starts out with the modules
         * in the given Result object.
         */
        Selected(Result result) {
            this.resolver = result.resolver;
            this.nameToReference = result.copySelected();
        }

        Resolver resolver() {
            return resolver;
        }

        void add(ModuleReference mref) {
            String name = mref.descriptor().name();
            nameToReference.put(name, mref);
        }

        boolean contains(String name) {
            return nameToReference.containsKey(name);
        }

        int count() {
            return nameToReference.size();
        }

        Collection<ModuleReference> modules() {
            return nameToReference.values();
        }

        ModuleReference findModule(String name) {
            return nameToReference.get(name);
        }

        ModuleDescriptor findDescriptor(String name) {
            ModuleReference mref = nameToReference.get(name);
            return (mref != null) ? mref.descriptor() : null;
        }

        /**
         * Finish the resolution or binding process.
         */
        Result finish(Configuration cf) {

            resolver.detectCycles(this);

            resolver.checkHashes(this);

            // generate the readability graph
            Map<ReadDependence, Set<ReadDependence>> graph
                = resolver.makeGraph(this, cf);

            resolver.checkExportSuppliers(graph);

            return new Result(resolver, nameToReference, graph);
        }

    }


    /**
     * The final result of resolution or binding.
     *
     * A Result encapsulates the readability graph,
     */
    static final class Result {

        private final Resolver resolver;

        // module name -> module reference for the selected modules
        private final Map<String, ModuleReference> nameToReference;

        // the module references of the selected modules
        private final Set<ModuleReference> modules;

        // the module descriptors of the selected modules
        private final Set<ModuleDescriptor> descriptors;

        // readability graph
        private final Map<ReadDependence, Set<ReadDependence>> graph;

        Result(Resolver resolver,
               Map<String, ModuleReference> nameToReference,
               Map<ReadDependence, Set<ReadDependence>> graph)
        {
            int nselected = nameToReference.size();
            Map<String, ModuleReference> map = new HashMap<>(nselected);
            Set<ModuleReference> modules = new HashSet<>(nselected);
            Set<ModuleDescriptor> descriptors = new HashSet<>(nselected);

            for (Entry<String, ModuleReference> e : nameToReference.entrySet()) {
                String name = e.getKey();
                ModuleReference mref = e.getValue();

                map.put(name, mref);
                modules.add(mref);
                descriptors.add(mref.descriptor());
            }

            this.resolver = resolver;
            this.nameToReference = map; // no need to copy
            this.modules = Collections.unmodifiableSet(modules);
            this.descriptors = Collections.unmodifiableSet(descriptors);
            this.graph = graph; // no need to copy
        }

        Resolver resolver() {
            return resolver;
        }

        Map<String, ModuleReference> copySelected() {
            return new HashMap<>(nameToReference);
        }

        Set<ModuleReference> modules() {
            return modules;
        }

        Set<ModuleDescriptor> descriptors() {
            return descriptors;
        }

        ModuleReference findModule(String name) {
            return nameToReference.get(name);
        }

        ModuleDescriptor findDescriptor(String name) {
            ModuleReference mref = nameToReference.get(name);
            return (mref != null) ? mref.descriptor() : null;
        }

        Set<ReadDependence> reads(ReadDependence rd) {
            Set<ReadDependence> reads = graph.get(rd);
            if (reads == null) {
                return Collections.emptySet();
            } else {
                return Collections.unmodifiableSet(reads);
            }
        }


        // maps a service type to the set of provider modules (created lazily)
        private volatile Map<String, Set<ModuleDescriptor>> serviceToProviders;

        /**
         * Returns an immutable set of the module descriptors that provide
         * one or more implementations of the given service type.
         */
        Set<ModuleDescriptor> provides(String st) {
            Map<String, Set<ModuleDescriptor>> map = this.serviceToProviders;

            // create the map if needed
            if (map == null) {
                map = new HashMap<>();
                for (ModuleDescriptor descriptor : descriptors()) {
                    Map<String, Provides> provides = descriptor.provides();
                    for (String sn : provides.keySet()) {
                        map.computeIfAbsent(sn, k -> new HashSet<>()).add(descriptor);
                    }
                }
                this.serviceToProviders = map;
            }

            Set<ModuleDescriptor> provides = map.get(st);
            if (provides == null) {
                return Collections.emptySet();
            } else {
                return Collections.unmodifiableSet(provides);
            }
        }
    }


    private final ModuleFinder beforeFinder;
    private final Configuration parent;
    private final ModuleFinder afterFinder;

    Resolver(ModuleFinder beforeFinder,
             Configuration parent,
             ModuleFinder afterFinder)
    {
        this.beforeFinder = beforeFinder;
        this.parent = parent;
        this.afterFinder = afterFinder;

    }

    /**
     * Resolves the given named modules, returning a Configuration that
     * encapsulates the result.
     *
     * @throws ResolutionException
     */
    Configuration resolve(Collection<String> roots) {

        long start = trace_start("Resolve");

        Selected selected = new Selected(this);

        // create the visit stack to get us started
        Deque<ModuleDescriptor> q = new ArrayDeque<>();
        for (String root : roots) {

            // find root module
            ModuleReference mref = findWithBeforeFinder(root);
            if (mref == null) {
                if (parent.findDescriptor(root).isPresent()) {
                    // in parent, nothing to do
                    continue;
                }
                mref = findWithAfterFinder(root);
                if (mref == null) {
                    fail("Module %s not found", root);
                }
            }

            if (TRACE) {
                trace("Root module %s located", root);
                if (mref.location().isPresent())
                    trace("  (%s)", mref.location().get());
            }

            selected.add(mref);
            q.push(mref.descriptor());
        }

        resolve(q, selected);

        Configuration cf = new Configuration(parent, selected);

        if (TRACE) {
            long duration = System.currentTimeMillis() - start;
            Set<String> names = cf.descriptors().stream()
                    .map(ModuleDescriptor::name)
                    .sorted()
                    .collect(Collectors.toSet());
            trace("Resolver completed in %s ms", duration);
            names.stream().sorted().forEach(name -> trace("  %s", name));
        }

        return cf;
    }


    /**
     * Poll the given {@code Deque} for modules to resolve. On completion the
     * {@code Deque} will be empty and any selected modules will be added to
     * the given Resolution.
     *
     * @return The set of module selected by this invocation of resolve
     */
    private Set<ModuleDescriptor> resolve(Deque<ModuleDescriptor> q,
                                          Selected selected)
    {

        Set<ModuleDescriptor> newlySelected = new HashSet<>();

        while (!q.isEmpty()) {
            ModuleDescriptor descriptor = q.poll();

            // process dependences
            for (ModuleDescriptor.Requires requires : descriptor.requires()) {
                String dn = requires.name();

                // find dependence
                ModuleReference mref = findWithBeforeFinder(dn);
                if (mref == null) {
                    if (parent.findDescriptor(dn).isPresent())
                        continue;

                    mref = findWithAfterFinder(dn);
                    if (mref == null) {
                        fail("Module %s not found, required by %s",
                                dn, descriptor.name());
                    }
                }

                if (!selected.contains(dn)) {
                    selected.add(mref);
                    q.offer(mref.descriptor());
                    newlySelected.add(mref.descriptor());

                    if (TRACE) {
                        trace("Module %s located, required by %s",
                                dn, descriptor.name());
                        if (mref.location().isPresent())
                            trace("  (%s)", mref.location().get());
                    }
                }

            }
        }

        return newlySelected;
    }


    /**
     * Returns a configuration that is the given configuration augmented with
     * modules induced by the service-use relation.
     */
    Configuration bind(Configuration cf) {

        long start = trace_start("Bind");

        Result result = cf.result();

        Selected selected = new Selected(result);

        // Scan the finders for all available service provider modules. As
        // java.base uses services then then module finders will be scanned
        // anyway.
        Map<String, Set<ModuleReference>> availableProviders = new HashMap<>();
        for (ModuleReference mref : findAll()) {
            ModuleDescriptor descriptor = mref.descriptor();
            if (!descriptor.provides().isEmpty()) {

                for (String sn : descriptor.provides().keySet()) {
                    // computeIfAbsent
                    Set<ModuleReference> providers = availableProviders.get(sn);
                    if (providers == null) {
                        providers = new HashSet<>();
                        availableProviders.put(sn, providers);
                    }
                    providers.add(mref);
                }

            }
        }

        // create the visit stack
        Deque<ModuleDescriptor> q = new ArrayDeque<>();

        // the initial set of modules that may use services
        Set<ModuleDescriptor> candidateConsumers = new HashSet<>();
        Configuration p = parent;
        while (p != null) {
            candidateConsumers.addAll(p.descriptors());
            p = p.parent().orElse(null);
        }
        candidateConsumers.addAll(result.descriptors());

        // Where there is a consumer of a service then resolve all modules
        // that provide an implementation of that service
        do {
            for (ModuleDescriptor descriptor : candidateConsumers) {
                if (!descriptor.uses().isEmpty()) {
                    for (String service : descriptor.uses()) {
                        Set<ModuleReference> mrefs = availableProviders.get(service);
                        if (mrefs != null) {
                            for (ModuleReference mref : mrefs) {
                                ModuleDescriptor provider = mref.descriptor();
                                if (!provider.equals(descriptor)) {

                                    trace("Module %s provides %s, used by %s",
                                        provider.name(), service, descriptor.name());

                                    String pn = provider.name();
                                    if (!selected.contains(pn)) {

                                        if (TRACE && mref.location().isPresent())
                                            trace("  (%s)", mref.location().get());

                                        selected.add(mref);
                                        q.push(provider);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            candidateConsumers = resolve(q, selected);

        } while (!candidateConsumers.isEmpty());

        // if binding has increased the number of modules then we need to
        // create a new Configuration
        if (selected.count() > result.modules().size()) {
            cf = new Configuration(parent, selected);
        }

        if (TRACE) {
            long duration = System.currentTimeMillis() - start;
            Set<String> names = selected.modules().stream()
                    .map(ModuleReference::descriptor)
                    .map(ModuleDescriptor::name)
                    .sorted()
                    .collect(Collectors.toSet());
            trace("Bind completed in %s ms", duration);
            names.stream().sorted().forEach(name -> trace("  %s", name));
        }

        return cf;
    }


    /**
     * Checks the given module graph for cycles.
     *
     * For now the implementation is a simple depth first search on the
     * dependency graph. We'll replace this later, maybe with Tarjan.
     */
    private void detectCycles(Selected selected ) {
        visited = new HashSet<>();
        visitPath = new LinkedHashSet<>(); // preserve insertion order
        for (ModuleReference mref : selected.modules()) {
            ModuleDescriptor descriptor = mref.descriptor();
            visit(selected, descriptor);
        }
    }

    // the modules that were visited
    private Set<ModuleDescriptor> visited;

    // the modules in the current visit path
    private Set<ModuleDescriptor> visitPath;

    private void visit(Selected selected, ModuleDescriptor descriptor) {
        if (!visited.contains(descriptor)) {
            boolean added = visitPath.add(descriptor);
            if (!added) {
                throw new ResolutionException("Cycle detected: " +
                        cycleAsString(descriptor));
            }
            for (ModuleDescriptor.Requires requires : descriptor.requires()) {
                String dn = requires.name();
                ModuleDescriptor other = selected.findDescriptor(dn);
                if (other != null && other != descriptor) {
                    // dependency is in this configuration
                    if (other != descriptor)
                        visit(selected, other);
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
     * Checks the hashes in the module descriptor to ensure that they match
     * the hash of the dependency's module reference.
     */
    private void checkHashes(Selected selected) {

        for (ModuleReference mref : selected.modules()) {
            ModuleDescriptor descriptor = mref.descriptor();

            // get map of module names to hash
            Optional<DependencyHashes> ohashes = descriptor.hashes();
            if (!ohashes.isPresent())
                continue;
            DependencyHashes hashes = ohashes.get();

            // check dependences
            for (ModuleDescriptor.Requires d : descriptor.requires()) {
                String dn = d.name();
                String recordedHash = hashes.hashFor(dn);

                if (recordedHash != null) {

                    ModuleReference other = selected.findModule(dn);
                    if (other == null)
                        other = parent.findModule(dn).orElse(null);
                    if (other == null)
                        throw new InternalError(dn + " not found");

                    String actualHash = other.computeHash(hashes.algorithm());
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
     * Computes and sets the readability graph for the modules in the given
     * Resolution object.
     *
     * The readability graph is created by propagating "requires" through the
     * "public requires" edges of the module dependence graph. So if the module
     * dependence graph has m1 requires m2 && m2 requires public m3 then the
     * resulting readability graph will contain m1 reads m2, m1
     * reads m3, and m2 reads m3.
     *
     * TODO: Use a more efficient algorithm, maybe cache the requires public
     *       in parent configurations.
     */
    private Map<ReadDependence, Set<ReadDependence>> makeGraph(Selected selected,
                                                               Configuration cf) {

        // the "reads" graph starts as a module dependence graph and
        // is iteratively updated to be the readability graph
        Map<ReadDependence, Set<ReadDependence>> g1 = new HashMap<>();

        // the "requires public" graph, contains requires public edges only
        Map<ReadDependence, Set<ReadDependence>> g2 = new HashMap<>();


        // need "requires public" from the modules in parent configurations as
        // there may be selected modules that have a dependency on modules in
        // the parent configuration.

        Configuration p = parent;
        while (p != null) {
            for (ModuleDescriptor descriptor : p.descriptors()) {
                ReadDependence x = p.findReadDependence(descriptor.name());
                for (ModuleDescriptor.Requires d : descriptor.requires()) {
                    if (d.modifiers().contains(Modifier.PUBLIC)) {
                        String dn = d.name();
                        ReadDependence y = p.findReadDependence(dn);
                        if (y == null)
                            throw new InternalError(dn + " not found");
                        g2.computeIfAbsent(x, k -> new HashSet<>()).add(y);
                    }
                }
            }
            p = p.parent().orElse(null);
        }

        // populate g1 and g2 with the dependences from the selected modules

        for (ModuleReference mref : selected.modules()) {
            ModuleDescriptor descriptor = mref.descriptor();
            ReadDependence x = new ReadDependence(cf, descriptor);

            Set<ReadDependence> reads = new HashSet<>();
            g1.put(x, reads);

            Set<ReadDependence> requiresPublic = new HashSet<>();
            g2.put(x, requiresPublic);

            for (ModuleDescriptor.Requires d : descriptor.requires()) {
                String dn = d.name();

                ReadDependence y;
                ModuleDescriptor other = selected.findDescriptor(dn);
                if (other != null) {
                    y = new ReadDependence(cf, other);
                } else {
                    y = parent.findReadDependence(dn);
                    if (y == null)
                        throw new InternalError("unable to find " + dn);
                }

                // m requires other => m reads other
                reads.add(y);

                // m requires public other
                if (d.modifiers().contains(Modifier.PUBLIC)) {
                    requiresPublic.add(y);
                }

            }

            // if m is an automatic module then it requires public all selected
            // modules and all modules in parent layers
            if (descriptor.isAutomatic()) {
                String name = descriptor.name();

                // requires public all selected modules
                for (ModuleReference mref2 : selected.modules()) {
                    ModuleDescriptor other = mref2.descriptor();
                    if (!name.equals(other.name())) {
                        ReadDependence rd = new ReadDependence(cf, other);
                        reads.add(rd);
                        requiresPublic.add(rd);
                    }
                }

                // requires public all modules in parent configurations
                p = parent;
                while (p != null) {
                    for (ModuleDescriptor other : p.descriptors()) {
                        ReadDependence rd = new ReadDependence(p, other);
                        reads.add(rd);
                        requiresPublic.add(rd);
                    }
                    p = p.parent().orElse(null);
                }

            }

        }

        // Iteratively update g1 until there are no more requires public to propagate
        boolean changed;
        Map<ReadDependence, Set<ReadDependence>> changes = new HashMap<>();
        do {
            changed = false;
            for (Entry<ReadDependence, Set<ReadDependence>> entry : g1.entrySet()) {

                ReadDependence d1 = entry.getKey();
                Set<ReadDependence> d1Reads = entry.getValue();

                for (ReadDependence d2 : d1Reads) {
                    Set<ReadDependence> d2RequiresPublic = g2.get(d2);
                    if (d2RequiresPublic != null) {
                        for (ReadDependence d3 : d2RequiresPublic) {
                            if (!d1Reads.contains(d3)) {

                                // computeIfAbsent
                                Set<ReadDependence> s = changes.get(d1);
                                if (s == null) {
                                    s = new HashSet<>();
                                    changes.put(d1, s);
                                }
                                s.add(d3);
                                changed = true;

                            }
                        }
                    }
                }
            }

            if (changed) {
                for (Entry<ReadDependence, Set<ReadDependence>> e : changes.entrySet()) {
                    ReadDependence d = e.getKey();
                    g1.get(d).addAll(e.getValue());
                }
                changes.clear();
            }

        } while (changed);

        return g1;
    }


    /**
     * Checks the readability graph to ensure that no two modules export the
     * same package to a module. This includes the case where module M has
     * a local package P and M reads another module that exports P to M.
     */
    private void checkExportSuppliers(Map<ReadDependence, Set<ReadDependence>> graph) {

        for (Map.Entry<ReadDependence, Set<ReadDependence>> e : graph.entrySet()) {
            ModuleDescriptor descriptor1 = e.getKey().descriptor();

            // the map of packages that are local or exported to descriptor1
            Map<String, ModuleDescriptor> packageToExporter = new HashMap<>();

            // local packages
            for (String pn : descriptor1.packages()) {
                packageToExporter.put(pn, descriptor1);
            }

            // descriptor1 reads descriptor2
            Set<ReadDependence> reads = e.getValue();
            for (ReadDependence endpoint : reads) {
                ModuleDescriptor descriptor2 = endpoint.descriptor();

                for (ModuleDescriptor.Exports export : descriptor2.exports()) {

                    Optional<Set<String>> otargets = export.targets();
                    if (otargets.isPresent()) {
                        if (!otargets.get().contains(descriptor1.name()))
                            continue;
                    }

                    // source is exported to descriptor2
                    String source = export.source();
                    ModuleDescriptor other
                            = packageToExporter.put(source, descriptor2);

                    if (other != null && other != descriptor2) {
                        // package might be local to descriptor1
                        if (other == descriptor1) {
                            fail("Module %s contains package %s"
                                 + ", module %s exports package %s to %s",
                                    descriptor1.name(),
                                    source,
                                    descriptor2.name(),
                                    source,
                                    descriptor1.name());
                        } else {
                            fail("Modules %s and %s export package %s to module %s",
                                    descriptor2.name(),
                                    other.name(),
                                    source,
                                    descriptor1.name());
                        }

                    }
                }
            }

        }

    }


    /**
     * Invokes the beforeFinder to find method to find the given module.
     */
    private ModuleReference findWithBeforeFinder(String mn) {
        try {
            return beforeFinder.find(mn).orElse(null);
        } catch (FindException e) {
            // unwrap
            throw new ResolutionException(e.getMessage(), e.getCause());
        }
    }

    /**
     * Invokes the afterFinder to find method to find the given module.
     */
    private ModuleReference findWithAfterFinder(String mn) {
        try {
            return afterFinder.find(mn).orElse(null);
        } catch (FindException e) {
            // unwrap
            throw new ResolutionException(e.getMessage(), e.getCause());
        }
    }

    /**
     * Returns the set of all modules that are observable with the before
     * and after ModuleFinders.
     */
    private Set<ModuleReference> findAll() {
        try {

            Set<ModuleReference> beforeModules = beforeFinder.findAll();
            Set<ModuleReference> afterModules = afterFinder.findAll();

            if (afterModules.isEmpty())
                return beforeModules;

            if (beforeModules.isEmpty() && parent == Configuration.empty())
                return afterModules;

            Set<ModuleReference> result = new HashSet<>(beforeModules);
            for (ModuleReference mref : afterModules) {
                String name = mref.descriptor().name();
                if (!beforeFinder.find(name).isPresent()
                        && !parent.findDescriptor(name).isPresent())
                    result.add(mref);
            }

            return result;

        } catch (FindException e) {
            // unwrap
            throw new ResolutionException(e.getMessage(), e.getCause());
        }
    }


    /**
     * Throw ResolutionException with the given format string and arguments
     */
    private static void fail(String fmt, Object ... args) {
        String msg = String.format(fmt, args);
        throw new ResolutionException(msg);
    }


    /**
     * Tracing support, limited to boot layer for now.
     */

    private final static boolean TRACE
        = Boolean.getBoolean("jdk.launcher.traceResolver")
            && (Layer.boot() == null);

    private String op;

    private long trace_start(String op) {
        this.op = op;
        return System.currentTimeMillis();
    }

    private void trace(String fmt, Object ... args) {
        if (TRACE) {
            System.out.print("[" + op + "] ");
            System.out.format(fmt, args);
            System.out.println();
        }
    }

}
