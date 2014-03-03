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
import java.util.Set;

import jdk.jigsaw.module.ViewDependence.Modifier;

/**
 * A simple resolver to select the set of modules needed when starting
 * with a set of root modules.
 */

public final class SimpleResolver {

    // maps view or alias names to modules
    private final Map<String, Module> namesToModules = new HashMap<>();

    /**
     * Creates a {@code SimpleResolver} for the given modules.
     */
    public SimpleResolver(Iterable<Module> modules) {
        for (Module m: modules) {
            // ##FIMXE replace this when Views go away
            for (View v: m.views()) {
                String name = v.id().name();
                namesToModules.put(name, m);
                v.aliases().forEach(id -> namesToModules.put(id.name(), m));
            }
        }
    }

    public SimpleResolver(Module... modules) {
        this(Arrays.asList(modules));
    }

    /**
     * Resolve the given root modules, returning a {@code Resolution}
     * to represent the result that includes the set of selected modules.
     */
    public Resolution resolve(Iterable<String> rootModules) {
        // the selected modules
        Set<Module> selected = new HashSet<>();

        // the visit stack
        Deque<Module> stack = new ArrayDeque<>();

        // push the root modules onto the visit stack to get us started
        for (String name: rootModules) {
            Module m = namesToModules.get(name);
            if (m == null)
                throw new ResolveException("Module %s does not exist", name);
            stack.offer(m);
        }

        // visit modules until the root modules and their transitive
        // dependencies have been visited
        while (!stack.isEmpty()) {
            Module m = stack.poll();
            selected.add(m);

            // process dependencies
            for (ViewDependence d: m.viewDependences()) {
                String dn = d.query().name();
                Module other = namesToModules.get(dn);
                if (other == null) {
                    throw new ResolveException("%s requires unknown module %s",
                                               m.mainView().id().name(), dn);
                }
                if (!selected.contains(other))
                    stack.offer(other);
            }
        }

        // propagate requires through the "requires public" edges.
        Map<Module, Set<String>> resolvedDependences = resolveDependences(selected);

        // ## FIXME, check permits when Module supports module-level permits

        // return result
        return new Resolution(selected, resolvedDependences);
    }

    public Resolution resolve(String... roots) {
        return resolve(Arrays.asList(roots));
    }

    /**
     * Resolves the dependences for the given set of modules.
     *
     * If m1 requires m2 && m2 requires public m3 then the resolved dependences
     * will expand this to m1 requires m2, m1 requires m3, m2 requires m3.
     */
    private Map<Module, Set<String>> resolveDependences(Set<Module> selected) {
        // the "requires" edges
        Map<Module, Set<String>> requires = new HashMap<>();

        // the "requires public" edges
        Map<Module, Set<String>> requiresPublic = new HashMap<>();

        // initialize the map of edges
        for (Module m: selected) {
            for (ViewDependence d: m.viewDependences()) {
                String dn = d.query().name();
                requires.computeIfAbsent(m, k -> new HashSet<>()).add(dn);
                if (d.modifiers().contains(Modifier.PUBLIC)) {
                    requiresPublic.computeIfAbsent(m, k -> new HashSet<>()).add(dn);
                }
            }
        }

        // iteratively find "m1 requires m2" && "m2 requires public m3" and
        // add "m1 requires m3" to the requires set.
        boolean changed;
        Map<Module, Set<String>> needToAdd = new HashMap<>();
        do {
            changed = false;
            for (Map.Entry<Module, Set<String>> entry: requires.entrySet()) {
                Module m1 = entry.getKey();
                Set<String> m1Requires = entry.getValue();

                for (String m2Name: m1Requires) {
                    // m1 requires m2
                    Module m2 = namesToModules.get(m2Name);
                    Set<String> m2RequiresPublic = requiresPublic.get(m2);
                    if (m2RequiresPublic != null) {
                        // m2 requires public m3
                        for (String m3Name: m2RequiresPublic) {
                            if (!m1Requires.contains(m3Name)) {
                                // need to add "m1 requires m3"
                                needToAdd.computeIfAbsent(m1, k -> new HashSet<>())
                                         .add(m3Name);
                                changed = true;
                            }
                        }
                    }
                }
            }
            if (changed) {
                for (Map.Entry<Module, Set<String>> entry: needToAdd.entrySet()) {
                    Module m1 = entry.getKey();
                    requires.computeIfAbsent(m1, k -> new HashSet<>())
                            .addAll(entry.getValue());
                }
                needToAdd.clear();
            }

        } while (changed);

        return requires;
    }
}
