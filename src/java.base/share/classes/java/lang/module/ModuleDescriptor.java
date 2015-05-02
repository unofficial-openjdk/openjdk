/*
 * Copyright (c) 2009, 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Objects;
import static java.util.Objects.*;

import jdk.internal.module.Hasher.DependencyHashes;

import static java.lang.module.Checks.*;


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
            this.name = requireJavaIdentifier("module name", mn);
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

        private final String source;
        private final Optional<Set<String>> targets;

        /**
         * Constructs an {@code Exports} to represent the exporting of package
         * {@code source} to the modules named in {@code targets}.
         */
        public Exports(String source, Set<String> targets) {
            this.source = requireNonNull(source);
            requireNonNull(targets);
            if (targets.isEmpty())
                throw new IllegalArgumentException("Empty target set");
            this.targets = Optional.of(Collections.unmodifiableSet(targets));
        }

        /**
         * Constructs an {@code Exports} to represent the exporting of package
         * {@code source}.
         */
        public Exports(String source) {
            this.source = requireNonNull(source);
            this.targets = Optional.empty();
        }

        /**
         * Returns the package name.
         */
        public String source() {
            return source;
        }

        /**
         * Returns the name of the module that the package is exported to,
         * or {@code null} if this is an unqualified export.
         */
        public Optional<Set<String>> targets() {
            return targets;
        }

        public int hashCode() {
            return hash(source, targets);
        }

        public boolean equals(Object obj) {
            if (!(obj instanceof Exports))
                return false;
            Exports other = (Exports)obj;
            return Objects.equals(this.source, other.source) &&
                Objects.equals(this.targets, other.targets);
        }

        public String toString() {
            if (targets.isPresent())
                return source + " to " + targets.get().toString();
            return source;
        }

    }



    /**
     * Service providers
     */

    public final static class Provides {

        private final String service;
        private final Set<String> providers;

        public Provides(String service, Set<String> providers) {
            this.service = requireJavaIdentifier("service type name", service);
            providers.forEach(s -> requireJavaIdentifier("service provider name",
                                                         service));
            this.providers = Collections.unmodifiableSet(providers);
        }

        public String service() { return service; }
        public Set<String> providers() { return providers; }

    }



    // From module declarations
    private final String name;
    private final Set<Requires> requires;
    private final Set<Exports> exports;
    private final Set<String> uses;
    private final Map<String, Provides> provides;

    // "Extended" information, added post-compilation
    private final Optional<Version> version;
    private final Optional<String> mainClass;
    private final Optional<DependencyHashes> hashes;

    ModuleDescriptor(String name,
                     Set<Requires> requires,
                     Set<String> uses,
                     Set<Exports> exports,
                     Map<String, Provides> provides,
                     Version version,
                     String mainClass,
                     DependencyHashes hashes)
    {
        this.name = requireJavaIdentifier("module name", name);

        assert (requires.stream().map(Requires::name).sorted().distinct().count()
                == requires.size())
            : String.format("Module %s has duplicate requires", name);
        this.requires = Collections.unmodifiableSet(requires);

        assert (exports.stream().map(Exports::source).sorted().distinct().count()
                == exports.size())
            : String.format("Module %s has duplicate exports", name);
        this.exports = Collections.unmodifiableSet(exports);

        this.uses = Collections.unmodifiableSet(uses);
        // ## FIXME values are mutable
        this.provides = Collections.unmodifiableMap(provides);

        this.version = Optional.ofNullable(version);
        if (mainClass != null)
            this.mainClass = Optional.of(requireJavaIdentifier("main class name",
                                                               mainClass));
        else
            this.mainClass = Optional.empty();
        this.hashes = Optional.ofNullable(hashes);

    }

    /**
     * <p> The module name </p>
     */
    public String name() {
        return name;
    }

    /**
     * <p> The dependences of this module </p>
     *
     * @return  A possibly-empty unmodifiable set of {@link Requires} objects
     */
    public Set<Requires> requires() {
        return requires;
    }

    /**
     * <p> The service dependences of this module </p>
     *
     * @return  A possibly-empty unmodifiable set of the service types used
     */
    public Set<String> uses() {
        return uses;
    }

    /**
     * <p> The services that this module provides </p>
     */
    public Map<String, Provides> provides() {
        return provides;
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
     * Returns this module's version.
     */
    public Optional<Version> version() {
        return version;
    }

    /**
     * Returns a string containing this module's name and, if present, its
     * version.
     */
    public String toNameAndVersion() {
        return version.map(v -> name() + "@" + v.toString()).orElse(name());
    }

    /**
     * Returns the module's main class.
     */
    public Optional<String> mainClass() {
        return mainClass;
    }

    /**
     * Returns the object with the hashes of the dependences.
     */
    Optional<DependencyHashes> hashes() {
        return hashes;
    }

    /**
     * A builder used for building {@link ModuleDescriptor} objects.
     */
    public static class Builder {

        String name;
        final Set<Requires> requires = new HashSet<>();
        final Set<String> uses = new HashSet<>();
        final Set<Exports> exports = new HashSet<>();
        final Map<String, Provides> provides = new HashMap<>();
        Version version;
        String mainClass;

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
            uses.add(requireNonNull(s));
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
         */
        public Builder export(String p, String m) {
            return export(new Exports(p, Collections.singleton(m)));
        }

        /**
         * Provides service {@code s} with implementation {@code p}.
         */
        public Builder provides(String s, String p) {
            assert provides.get(s) == null;
            provides.put(s, new Provides(s, Collections.singleton(p)));
            return this;
        }

        /**
         * Provides service {@code s} with implementations {@code ps}.
         */
        public Builder provides(String s, Set<String> ps) {
            assert provides.get(s) == null;
            provides.put(s, new Provides(s, ps));
            return this;
        }

        public Builder version(String v) {
            version = Version.parse(v);
            return this;
        }

        public Builder mainClass(String mainClass) {
            this.mainClass = mainClass;
            return this;
        }

        /**
         * Builds a {@code ModuleDescriptor} from the components.
         */
        public ModuleDescriptor build() {
            assert name != null;
            return new ModuleDescriptor(name,
                                        requires,
                                        uses,
                                        exports,
                                        provides,
                                        version,
                                        mainClass,
                                        null);
        }

    }

    @Override
    public int compareTo(ModuleDescriptor that) {
        int c = this.name().compareTo(that.name());
        if (c != 0) return c;
        if (!version.isPresent()) {
            if (!that.version.isPresent())
                return 0;
            return -1;
        }
        if (!that.version.isPresent())
            return +1;
        return version.get().compareTo(that.version.get());
    }

    @Override
    public boolean equals(Object ob) {
        if (!(ob instanceof ModuleDescriptor))
            return false;
        ModuleDescriptor that = (ModuleDescriptor)ob;
        return (name.equals(that.name)
                && requires.equals(that.requires)
                && uses.equals(that.uses)
                && exports.equals(that.exports)
                && provides.equals(that.provides)
                && Objects.equals(version, that.version)
                && Objects.equals(mainClass, that.mainClass)
                && Objects.equals(hashes, that.hashes));
    }

    private transient int hash;  // cached hash code

    @Override
    public int hashCode() {
        int hc = hash;
        if (hc == 0) {
            hc = name.hashCode();
            hc = hc * 43 + requires.hashCode();
            hc = hc * 43 + uses.hashCode();
            hc = hc * 43 + exports.hashCode();
            hc = hc * 43 + provides.hashCode();
            hc = hc * 43 + Objects.hashCode(version);
            hc = hc * 43 + Objects.hashCode(mainClass);
            hc = hc * 43 + Objects.hashCode(hashes);
            hash = hc;
        }
        return hc;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Module { name: ").append(toNameAndVersion());
        if (!requires.isEmpty())
            sb.append(", ").append(requires);
        if (!uses.isEmpty())
            sb.append(", ").append(uses);
        if (!exports.isEmpty())
            sb.append(", exports: ").append(exports);
        if (!provides.isEmpty()) {
            sb.append(", provides: [");
            for (Map.Entry<String, Provides> entry : provides.entrySet()) {
                sb.append(entry.getKey())
                   .append(" with ")
                   .append(entry.getValue());
            }
        }
        sb.append(" }");
        return sb.toString();
    }

    public static ModuleDescriptor read(InputStream in) throws IOException {
        return ModuleInfo.read(in);
    }

    public static ModuleDescriptor read(ByteBuffer bb) throws IOException {
        return ModuleInfo.read(bb);
    }

}
