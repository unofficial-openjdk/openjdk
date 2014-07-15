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
import java.util.stream.Collectors;
import static java.util.Objects.*;


/**
 * <p> A module definition </p>
 */

@SuppressWarnings("serial")             // serialVersionUID intentionally omitted
public final class Module
    implements Comparable<Module>, Serializable
{

    private final ModuleId id;
    private final Set<ModuleDependence> moduleDependences;
    private final Set<ServiceDependence> serviceDependences;
    private final Set<String> permits;
    private final Set<String> packages;
    private final Set<ModuleExport> exports;
    private final Map<String, Set<String>> services;

    // Every exported package must be included in this module
    //
    private void checkExportedPackages() {
        for (ModuleExport export : exports()) {
            String pkg = export.pkg();
            if (!packages.contains(pkg)) {
                Set<String> ps = exports().stream()
                                          .map(ModuleExport::pkg)
                                          .collect(Collectors.toSet());
                ps.removeAll(packages);
                String msg = String.format("Package%s %s exported"
                                           + " but not included in module %s",
                                           ps.size() > 1 ? "s" : "", ps,
                                           this.id());
                throw new IllegalArgumentException(msg);
            }
        }
    }

    private Module(ModuleId id,
                   Set<ModuleDependence> moduleDeps,
                   Set<ServiceDependence> serviceDeps,
                   Set<String> permits,
                   Set<String> packages,
                   Set<ModuleExport> exports,
                   Map<String, Set<String>> services)
    {
        this.id = requireNonNull(id);
        this.moduleDependences = Collections.unmodifiableSet(moduleDeps);
        this.serviceDependences = Collections.unmodifiableSet(serviceDeps);
        this.permits = Collections.unmodifiableSet(permits);
        this.packages = Collections.unmodifiableSet(packages);
        this.exports = Collections.unmodifiableSet(exports);
        // ## FIXME values are mutable
        this.services = Collections.unmodifiableMap(services);
        checkExportedPackages();
    }

    /**
     * <p> This module's identifier </p>
     */
    public ModuleId id() {
        return id;
    }

    /**
     * <p> The names of the modules that are permitted to require this module </p>
     *
     * @return  A possibly-empty unmodifiable set of module names
     */
    public Set<String> permits() {
        return permits;
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
     * The names of the packages included in this module, not all of which are
     * necessarily {@linkplain #exports() exported}.
     *
     * @return  A possibly-empty unmodifiable set of package names
     */
    public Set<String> packages() {
        return packages;
    }

    /**
     * A builder used for building {@link Module} objects.
     */
    public final static class Builder {

        private ModuleId id;
        private final Set<String> permits = new HashSet<>();
        private final Set<ModuleDependence> moduleDeps = new HashSet<>();
        private final Set<ServiceDependence> serviceDeps = new HashSet<>();
        private final Set<ModuleExport> exports = new HashSet<>();
        private final Set<String> packages = new HashSet<>();
        private final Map<String, Set<String>> services = new HashMap<>();

        /**
         * Initializes a new builder.
         */
        public Builder() { }

        /**
         * Sets the module id.
         *
         * @throws IllegalStateException if already set
         */
        public Builder id(ModuleId id) {
            if (this.id != null)
                throw new IllegalStateException("id already set");
            this.id = requireNonNull(id);
            return this;
        }

        /**
         * Sets the module id.
         *
         * @throws IllegalStateException if already set
         */
        public Builder id(String id) {
            return id(ModuleId.parse(id));
        }

        /**
         * Add/includes the given package name in the module contents.
         *
         * @throws IllegalArgumentException if {@code p} is the empty string
         */
        public Builder include(String p) {
            if (p.isEmpty())
                throw new IllegalArgumentException("<unnamed> package not allowed");
            packages.add(p);
            return this;
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
         * Adds a permits, by module name.
         */
        public Builder permit(String m) {
            permits.add(requireNonNull(m));
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
         * Builds a {@code Module} from the components.
         *
         * @throws IllegalStateException if the module id is not set
         */
        public Module build() {
            if (id == null)
                throw new IllegalStateException("id not set");
            return new Module(id,
                              moduleDeps,
                              serviceDeps,
                              permits,
                              packages,
                              exports,
                              services);
        }
    }

    @Override
    public int compareTo(Module that) {
        return this.id().compareTo(that.id());
    }

    @Override
    public boolean equals(Object ob) {
        if (!(ob instanceof Module))
            return false;
        Module that = (Module)ob;
        return (id.equals(that.id)
                && moduleDependences.equals(that.moduleDependences)
                && serviceDependences.equals(that.serviceDependences)
                && permits.equals(that.permits)
                && packages.equals(that.packages)
                && exports.equals(that.exports)
                && services.equals(that.services));
    }

    private transient int hash;  // cached hash code

    @Override
    public int hashCode() {
        int hc = hash;
        if (hc == 0) {
            hc = id.hashCode();
            hc = hc * 43 + permits.hashCode();
            hc = hc * 43 + moduleDependences.hashCode();
            hc = hc * 43 + serviceDependences.hashCode();
            hc = hc * 43 + exports.hashCode();
            hc = hc * 43 + packages.hashCode();
            hc = hc * 43 + services.hashCode();
            hash = hc;
        }
        return hc;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Module { id: ").append(id());
        if (!moduleDependences.isEmpty())
            sb.append(", ").append(moduleDependences);
        if (!serviceDependences.isEmpty())
            sb.append(", ").append(serviceDependences);
        if (!permits.isEmpty())
            sb.append(", permits: ").append(permits);
        if (!packages.isEmpty())
            sb.append(", packages: ").append(packages);
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
