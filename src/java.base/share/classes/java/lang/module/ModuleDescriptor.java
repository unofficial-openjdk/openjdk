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

package java.lang.module;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import static java.util.Objects.*;


/**
 * A module descriptor.
 */

public class ModuleDescriptor
    implements Comparable<ModuleDescriptor>
{

    /**
     * <p> A dependence upon a module </p>
     *
     * @since 1.9
     */

    public final static class Requires
        implements Comparable<Requires>
    {

        /**
         * A modifier on a module dependence.
         *
         * @since 1.9
         */
        public static enum Modifier {
            /**
             * The dependence causes any module which depends on the <i>current
             * module</i> to have an implicitly declared dependence on the module
             * named by the {@code Requires}.
             */
            PUBLIC,
            /**
             * The dependence was not explicitly or implicitly declared in the
             * source of the module declaration.
             */
            SYNTHETIC,
            /**
             * The dependence was implicitly declared in the source of the module
             * declaration.
             */
            MANDATED;
        }

        private final Set<Modifier> mods;
        private final String name;

        /**
         * Constructs a new instance of this class.
         *
         * @param ms the set of modifiers; {@code null} for no modifiers
         * @param mn the module name
         *
         * @throws IllegalArgumentException
         *         If the module name is not a legal Java identifier
         */
        public Requires(Set<Modifier> ms, String mn) {
            if (ms == null) {
                mods = Collections.emptySet();
            } else {
                mods = Collections.unmodifiableSet(ms);
            }
            this.name = ModuleName.check(mn);
        }

        /**
         * Returns the possibly empty set of modifiers. The set is immutable.
         */
        public Set<Modifier> modifiers() {
            return mods;
        }

        /**
         * Return the module name.
         */
        public String name() {
            return name;
        }

        /**
         * Compares this module dependence to another.
         *
         * <p> Two {@code Requires} objects are compared by comparing their
         * module name lexicographically.  Where the module names are equal then
         * the sets of modifiers are compared.
         *
         * @return A negative integer, zero, or a positive integer if this module
         *         dependence is less than, equal to, or greater than the given
         *         module dependence
         */
        @Override
        public int compareTo(Requires that) {
            int c = this.name().compareTo(that.name());
            if (c != 0)
                return c;
            // same name, compare by modifiers
            return Long.compare(this.modsValue(), that.modsValue());
        }

        /**
         * Return a value for the modifiers to allow sets of modifiers to be
         * compared.
         */
        private long modsValue() {
            return mods.stream()
                       .map(Modifier::ordinal)
                       .map(n -> 1 << n)
                       .reduce(0, (a, b) -> a + b);
        }


        @Override
        public boolean equals(Object ob) {
            if (!(ob instanceof Requires))
                return false;
            Requires that = (Requires)ob;
            return (name.equals(that.name) && mods.equals(that.mods));
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 43 + mods.hashCode();
        }

        @Override
        public String toString() {
            return Dependence.toString(mods, name);
        }

    }



    /**
     * <p> A module export, may be qualified or unqualified. </p>
     */

    public final static class Exports {

        private final String pkg;
        private final String permit;

        /**
         * Constructs a {@code Exports} to represent the exporting of package
         * {@code pkg} to module {@code who}.
         */
        public Exports(String pkg, String who) {
            this.pkg = requireNonNull(pkg);
            this.permit = who;
        }

        /**
         * Constructs a {@code Exports} to represent the exporting of package
         * {@code pkg}.
         */
        public Exports(String pkg) {
            this(pkg, null);
        }

        /**
         * Returns the package name.
         */
        public String pkg() {
            return pkg;
        }

        /**
         * Returns the name of the module that the package is exported to,
         * or {@code null} if this is an unqualified export.
         */
        public String permit() {
            return permit;
        }

        public int hashCode() {
            return hash(pkg, permit);
        }

        public boolean equals(Object obj) {
            if (!(obj instanceof Exports))
                return false;
            Exports other = (Exports)obj;
            return Objects.equals(this.pkg, other.pkg) &&
                Objects.equals(this.permit, other.permit);
        }

        public String toString() {
            if (permit == null)
                return pkg;
            return pkg + " to " + permit;
        }

    }



    private final String name;
    private final Set<Requires> requires;
    private final Set<String> serviceDependences;
    private final Set<Exports> exports;
    private final Map<String, Set<String>> services;

    ModuleDescriptor(String name,
                     Set<Requires> requires,
                     Set<String> serviceDeps,
                     Set<Exports> exports,
                     Map<String, Set<String>> services)
    {
        this.name = ModuleName.check(name);
        this.requires = Collections.unmodifiableSet(requires);
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
     * @return  A possibly-empty unmodifiable set of {@link Requires}s
     */
    public Set<Requires> requires() {
        return requires;
    }

    /**
     * <p> The service dependences of this module </p>
     *
     * @return  A possibly-empty unmodifiable set of the service types used
     */
    public Set<String> serviceDependences() {
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
    public Set<Exports> exports() {
        return exports;
    }

    /**
     * A builder used for building {@link ModuleDescriptor} objects.
     */
    public static class Builder {

        String name;
        final Set<Requires> requires = new HashSet<>();
        final Set<String> serviceDeps = new HashSet<>();
        final Set<Exports> exports = new HashSet<>();
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
        public Builder requires(Requires md) {
            requires.add(requireNonNull(md));
            return this;
        }

        /**
         * Adds a service dependence.
         */
        public Builder uses(String s) {
            serviceDeps.add(requireNonNull(s));
            return this;
        }

        /**
         * Adds a module export.
         *
         * ## FIXME need to check for conflicting exports
         */
        public Builder export(Exports e) {
            exports.add(requireNonNull(e));
            return this;
        }

        /**
         * Exports the given package name.
         *
         * ## FIXME need to check for conflicting exports
         */
        public Builder export(String p) {
            return export(new Exports(p));
        }

        /**
         * Exports the given package name to the given named module.
         *
         * ## FIXME need to check for conflicting exports
         */
        public Builder export(String p, String m) {
            return export(new Exports(p, m));
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
                                        requires,
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
                && requires.equals(that.requires)
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
            hc = hc * 43 + requires.hashCode();
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
        if (!requires.isEmpty())
            sb.append(", ").append(requires);
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
