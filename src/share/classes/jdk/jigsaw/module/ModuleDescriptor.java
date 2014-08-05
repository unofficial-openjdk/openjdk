/*
 * Copyright (c) 2009, 2013, Oracle and/or its affiliates. All rights reserved.
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

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static java.util.Objects.*;

/**
 * A module descriptor.
 */

@SuppressWarnings("serial")             // serialVersionUID intentionally omitted
public class ModuleDescriptor
    implements Serializable, Comparable<ModuleDescriptor>
{
    private final String name;
    private final Set<ModuleDependence> moduleDependences;
    private final Set<ServiceDependence> serviceDependences;
    private final Set<ModuleExport> exports;
    private final Map<String, Set<String>> services;

    ModuleDescriptor(String name,
                     Set<ModuleDependence> moduleDeps,
                     Set<ServiceDependence> serviceDeps,
                     Set<ModuleExport> exports,
                     Map<String, Set<String>> services)
    {
        this.name = requireNonNull(name);
        this.moduleDependences = Collections.unmodifiableSet(moduleDeps);
        this.serviceDependences = Collections.unmodifiableSet(serviceDeps);
        this.exports = Collections.unmodifiableSet(exports);
        // ## FIXME values are mutable
        this.services = Collections.unmodifiableMap(services);
    }

    /**
     * <p> The module name </p>
     */
    public String name() {
        return name;
    }

    /**
     * <p> The view dependences of this module </p>
     *
     * @return  A possibly-empty unmodifiable set of {@link ModuleDependence}s
     */
    public Set<ModuleDependence> moduleDependences() {
        return moduleDependences;
    }

    /**
     * <p> The service dependences of this module </p>
     *
     * @return  A possibly-empty unmodifiable set of
     *          {@link ServiceDependence}s
     */
    public Set<ServiceDependence> serviceDependences() {
        return serviceDependences;
    }

    /**
     * <p> The services that this module provides </p>
     *
     * @return  A possibly-empty unmodifiable map with a key that is the
     *          fully-qualified name of a service type and value that is
     *          the set of class names of the service providers that are
     *          provided by this module.
     */
    public Map<String, Set<String>> services() {
        return services;
    }

    /**
     * <p> The module exports </p>
     *
     * @return  A possibly-empty unmodifiable set of exported packages
     */
    public Set<ModuleExport> exports() {
        return exports;
    }

    /**
     * A builder used for building {@link ModuleDescriptor} objects.
     */
    public static class Builder {

        String name;
        final Set<ModuleDependence> moduleDeps = new HashSet<>();
        final Set<ServiceDependence> serviceDeps = new HashSet<>();
        final Set<ModuleExport> exports = new HashSet<>();
        final Map<String, Set<String>> services = new HashMap<>();

        /**
         * For sub-class usage.
         */
        Builder() {
        }

        /**
         * Initializes a new builder.
         */
        public Builder(String name) {
            this.name = name;
        }

        /**
         * Adds a module dependence.
         */
        public Builder requires(ModuleDependence md) {
            moduleDeps.add(requireNonNull(md));
            return this;
        }

        /**
         * Adds a service dependence.
         */
        public Builder requires(ServiceDependence sd) {
            serviceDeps.add(requireNonNull(sd));
            return this;
        }

        /**
         * Adds a module export.
         */
        public Builder export(ModuleExport e) {
            exports.add(requireNonNull(e));
            return this;
        }

        /**
         * Exports the given package name.
         */
        public Builder export(String p) {
            return export(new ModuleExport(p));
        }

        /**
         * Exports the given package name to the given named module.
         */
        public Builder export(String p, String m) {
            return export(new ModuleExport(p, m));
        }

        /**
         * Provides service {@code s} with implementation {@code p}.
         */
        public Builder service(String s, String p) {
            services.computeIfAbsent(requireNonNull(s), k -> new HashSet<>())
                    .add(requireNonNull(p));
            return this;
        }

        /**
         * Builds a {@code ModuleDescriptor} from the components.
         */
        public ModuleDescriptor build() {
            assert name != null;
            return new ModuleDescriptor(name,
                                        moduleDeps,
                                        serviceDeps,
                                        exports,
                                        services);
        }
    }

    @Override
    public int compareTo(ModuleDescriptor that) {
        return this.name().compareTo(that.name());
    }

    @Override
    public boolean equals(Object ob) {
        if (!(ob instanceof ModuleDescriptor))
            return false;
        ModuleDescriptor that = (ModuleDescriptor)ob;
        return (name.equals(that.name)
                && moduleDependences.equals(that.moduleDependences)
                && serviceDependences.equals(that.serviceDependences)
                && exports.equals(that.exports)
                && services.equals(that.services));
    }

    private transient int hash;  // cached hash code

    @Override
    public int hashCode() {
        int hc = hash;
        if (hc == 0) {
            hc = name.hashCode();
            hc = hc * 43 + moduleDependences.hashCode();
            hc = hc * 43 + serviceDependences.hashCode();
            hc = hc * 43 + exports.hashCode();
            hc = hc * 43 + services.hashCode();
            hash = hc;
        }
        return hc;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Module { name: ").append(name());
        if (!moduleDependences.isEmpty())
            sb.append(", ").append(moduleDependences);
        if (!serviceDependences.isEmpty())
            sb.append(", ").append(serviceDependences);
        if (!exports.isEmpty())
            sb.append(", exports: ").append(exports);
        if (!services.isEmpty()) {
            sb.append(", provides: [");
            for (Map.Entry<String, Set<String>> entry: services.entrySet()) {
                sb.append(entry.getKey())
                   .append(" with ")
                   .append(entry.getValue());
            }
        }
        sb.append(" }");
        return sb.toString();
    }

}
