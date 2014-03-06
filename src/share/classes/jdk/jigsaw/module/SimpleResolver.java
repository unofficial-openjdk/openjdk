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
    private final ModuleLibrary library;

    /**
     * Creates a {@code SimpleResolver} to resolve modules located in the given
     * module library.
     */
    public SimpleResolver(ModuleLibrary library) {
        this.library = library;
    }

    /**
     * Resolve the given modules, returning a {@code Resolution} result.
     */
    public Resolution resolve(Iterable<String> roots) {
        // the selected modules
        Set<Module> selected = new HashSet<>();

        // the visit stack
        Deque<Module> stack = new ArrayDeque<>();

        // push the root modules onto the visit stack to get us started
        for (String root: roots) {
            Module m = library.findModule(root);
            if (m == null)
                fail("Module %s does not exist", root);
            stack.offer(m);
        }

        // visit modules until the root modules and their transitive
        // dependencies have been visited
        while (!stack.isEmpty()) {
            Module m = stack.poll();
            selected.add(m);

            // process dependencies (needs to replaced with module dependences)
            for (ViewDependence d: m.viewDependences()) {
                String dn = d.query().name();
                Module other = library.findModule(dn);
                if (other == null) {
                    fail("%s requires unknown module %s",
                         m.mainView().id().name(), dn);
                }
                if (!selected.contains(other))
                    stack.offer(other);
            }
        }

        // propagate requires through the "requires public" edges.
        Map<Module, Set<String>> resolvedDependences = resolveDependences(selected);

        // check the resolved dependences for permits violations
        checkPermits(resolvedDependences);

        // result is the set of selected modules and the resolved dependences
        return new Resolution(selected, resolvedDependences);
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
                    Module m2 = library.findModule(m2Name);
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

    /**
     * Checks the permits on the resolved dependences.
     *
     * @throws ResolveException
     */
    private void checkPermits(Map<Module, Set<String>> resolvedDependences) {
        for (Map.Entry<Module, Set<String>> entry: resolvedDependences.entrySet()) {
            Module m1 = entry.getKey();
            String m1Name = m1.mainView().id().name();
            for (String m2Name: entry.getValue()) {
                Module m2 = library.findModule(m2Name);
                Set<String> permits = m2.permits();
                if (!permits.isEmpty() && !permits.contains(m1Name)) {
                    fail("%s does not permit %s", m2Name, m1Name);
                }
            }
        }
    }

    private void fail(String fmt, Object ... args) {
        throw new ResolveException(fmt, args);
    }
}
