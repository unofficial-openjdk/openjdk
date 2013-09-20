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

/**
 * A simple resolver to select the set of modules needed when starting
 * with a set of root modules.
 */

public final class SimpleResolver {

    // maps view or alias names to modules
    private final Map<String, Module> namesToModules = new HashMap<>();

    // maps a service name to a set of modules names that include an
    // implementation of that service
    private final Map<String, Set<String>> serviceProviders = new HashMap<>();

    /**
     * Creates a {@code SimpleResolver} with the given set of modules.
     */
    public SimpleResolver(Module[] modules) {
        for (Module m: modules) {
            for (View v: m.views()) {
                String name = v.id().name();
                namesToModules.put(name, m);
                v.aliases().forEach(id -> namesToModules.put(id.name(), m));

                for (String s: v.services().keySet()) {
                    serviceProviders.computeIfAbsent(s, k -> new HashSet<>()).add(name);
                }
            }
        }
    }

    /**
     * Returns the set of modules required to satisfy the given array of root
     * modules.
     */
    public Set<Module> resolve(String... rootModules) {
        // the selected modules
        Set<Module> selected = new HashSet<>();

        // the visit stack
        Deque<Module> stack = new ArrayDeque<>();

        // push the root modules onto the visit stack to get us started
        for (String name: rootModules) {
            Module m = namesToModules.get(name);
            if (m == null)
                throw new RuntimeException(name + " not found!!");
            stack.offer(m);
        }

        // visit modules until the root modules and their transitive
        // dependencies have been visited
        while (!stack.isEmpty()) {
            Module m = stack.poll();
            selected.add(m);

            // process dependencies
            for (ViewDependence d: m.viewDependences()) {
                // ## FIXME should really doing matching here
                String name = d.query().name();
                Module m2 = namesToModules.get(name);
                if (m2 == null)
                    throw new RuntimeException(name + " not found!!");
                if (!selected.contains(m2))
                    stack.offer(m2);
            }
            for (ServiceDependence d: m.serviceDependences()) {
                Set<String> providers = serviceProviders.get(d.service());
                 // ## FIXME should check is dependency is optional
                if (providers != null) {
                    for (String provider: providers) {
                        Module m2 = namesToModules.get(provider);
                        if (m2 == null)
                            throw new RuntimeException(provider + " not found!!");

                        if (!selected.contains(m2))
                            stack.offer(m2);
                    }
                }
            }
        }

        return selected;
    }
}
