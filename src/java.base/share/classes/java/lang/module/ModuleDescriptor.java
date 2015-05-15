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
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Objects;
import java.util.function.Supplier;
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

        private Requires(Set<Modifier> ms, String mn) {
            if (ms == null || ms.isEmpty()) {
                mods = Collections.emptySet();
            } else {
                mods = Collections.unmodifiableSet(EnumSet.copyOf(ms));
            }
            this.name = requireModuleName(mn);
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

        private Exports(String source, Set<String> targets) {
            this.source = requireNonNull(source);
            requireNonNull(targets);
            if (targets.isEmpty())
                throw new IllegalArgumentException("Empty target set");
            this.targets
                = Optional.of(Collections.unmodifiableSet(new HashSet<>(targets)));
        }

        /**
         * Constructs an {@code Exports} to represent the exporting of package
         * {@code source}.
         */
        private Exports(String source) {
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

        private Provides(String service, Set<String> providers) {
            this.service = requireServiceTypeName(service);
            providers.forEach(Checks::requireServiceProviderName);
            this.providers = Collections.unmodifiableSet(new HashSet<>(providers));
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

    // Indicates if synthesised for a JAR file found on the module path
    private final boolean automatic;

    // "Extended" information, added post-compilation
    private final Optional<Version> version;
    private final Optional<String> mainClass;
    private final Set<String> conceals;
    private final Set<String> packages;
    private final Optional<DependencyHashes> hashes;

    private ModuleDescriptor(String name,
                             boolean automatic,
                             Map<String, Requires> requires,
                             Set<String> uses,
                             Map<String, Exports> exports,
                             Map<String, Provides> provides,
                             Version version,
                             String mainClass,
                             Set<String> conceals,
                             DependencyHashes hashes)
    {

        this.name = requireModuleName(name);
        this.automatic = automatic;

        Set<Requires> rqs = new HashSet<>(requires.values());
        assert (rqs.stream().map(Requires::name).sorted().distinct().count()
                == rqs.size())
            : "Module " + name + " has duplicate requires";
        this.requires = Collections.unmodifiableSet(rqs);

        Set<Exports> exs = new HashSet<>(exports.values());
        assert (exs.stream().map(Exports::source).sorted().distinct().count()
                == exs.size())
            : "Module " + name + " has duplicate exports";
        this.exports = Collections.unmodifiableSet(exs);

        this.uses = Collections.unmodifiableSet(uses);
        this.provides = Collections.unmodifiableMap(provides);

        this.version = Optional.ofNullable(version);
        if (mainClass != null)
            this.mainClass = Optional.of(requireJavaIdentifier("main class name",
                                                               mainClass));
        else
            this.mainClass = Optional.empty();
        this.hashes = Optional.ofNullable(hashes);

        assert !exports.keySet().stream().anyMatch(conceals::contains)
            : "Module " + name + ": Package sets overlap";
        this.conceals = Collections.unmodifiableSet(conceals);
        Set<String> pkgs = new HashSet<>(conceals);
        pkgs.addAll(exports.keySet());
        this.packages = Collections.unmodifiableSet(pkgs);

    }

    /**
     * <p> The module name </p>
     */
    public String name() {
        return name;
    }

    /**
     * <p> Indicates if this is an automatic module. </p>
     */
    /* package */ boolean isAutomatic() {
        return automatic;
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
     * Returns the names of the packages defined in, but not exported by, this
     * module.
     */
    public Set<String> conceals() {
        return conceals;
    }

    /**
     * Returns the names of all the packages defined in this module, whether
     * exported or concealed.
     */
    public Set<String> packages() {
        return packages;
    }

    /**
     * Returns the object with the hashes of the dependences.
     */
    Optional<DependencyHashes> hashes() {
        return hashes;
    }

    /**
     * A builder used for building {@link ModuleDescriptor} objects.
     *
     * @apiNote Should Builder be final?
     */
    public static class Builder {

        final String name;
        final boolean automatic;
        final Map<String, Requires> requires = new HashMap<>();
        final Set<String> uses = new HashSet<>();
        final Map<String, Exports> exports = new HashMap<>();
        final Map<String, Provides> provides = new HashMap<>();
        Version version;
        String mainClass;
        Set<String> conceals = Collections.emptySet();
        DependencyHashes hashes;

        /**
         * Initializes a new builder.
         */
        public Builder(String name) {
            this(name, false);
        }

        /* package */ Builder(String name, boolean automatic) {
            this.name = name;
            this.automatic = automatic;
        }

        /**
         * Adds a module dependence.
         */
        public Builder requires(Set<Requires.Modifier> mods, String mn) {
            if (requires.get(requireModuleName(mn)) != null)
                throw new IllegalArgumentException("Dependence upon " + mn
                                                   + " already declared");
            requires.put(mn, new Requires(mods, mn));
            return this;
        }

        public Builder requires(String mn) {
            return requires(EnumSet.noneOf(Requires.Modifier.class), mn);
        }

        public Builder requires(Requires.Modifier mod, String mn) {
            return requires(EnumSet.of(mod), mn);
        }

        /**
         * Adds a service dependence.
         */
        public Builder uses(String st) {
            if (uses.contains(requireServiceTypeName(st)))
                throw new IllegalArgumentException("Dependence upon service "
                                                   + st + " already declared");
            uses.add(st);
            return this;
        }

        /**
         * Adds a module export.
         */
        public Builder exports(String pn, Set<String> targets) {
            if (exports.get(requirePackageName(pn)) != null)
                throw new IllegalArgumentException("Export of package "
                                                   + pn + " already declared");
            targets.stream().forEach(Checks::requireModuleName);
            exports.put(pn, new Exports(pn, targets));
            return this;
        }

        /**
         * Exports the given package to the given named module.
         */
        public Builder exports(String pn, String target) {
            return exports(pn, Collections.singleton(target));
        }

        public Builder exports(String pn) {
            if (exports.get(requirePackageName(pn)) != null)
                throw new IllegalArgumentException("Export of package "
                                                   + pn + " already declared");
            exports.put(pn, new Exports(pn));
            return this;
        }

        // Used by ModuleInfo, after a packageFinder is invoked
        /* package */ Set<String> exportedPackages() {
            return exports.keySet();
        }

        /**
         * Provides service {@code st} with implementations {@code pcs}.
         */
        public Builder provides(String st, Set<String> pcs) {
            if (provides.get(requireServiceTypeName(st)) != null)
                throw new IllegalArgumentException("Providers of service "
                                                   + st + " already declared");
            pcs.stream().forEach(Checks::requireServiceProviderName);
            provides.put(st, new Provides(st, pcs));
            return this;
        }

        public Builder provides(String st, String pc) {
            return provides(st, Collections.singleton(pc));
        }

        public Builder version(String v) {
            version = Version.parse(v);
            return this;
        }

        public Builder mainClass(String mainClass) {
            this.mainClass = mainClass;
            return this;
        }

        public Builder conceals(Set<String> packages) {
            packages.forEach(Checks::requirePackageName);
            if (this.conceals.isEmpty())
                this.conceals = new HashSet<>();
            this.conceals.addAll(packages);
            return this;
        }

        public Builder conceals(String pkg) {
            Checks.requirePackageName(pkg);
            if (this.conceals.isEmpty())
                this.conceals = new HashSet<>();
            this.conceals.add(pkg);
            return this;
        }

        /* package */ Builder hashes(DependencyHashes hashes) {
            this.hashes = hashes;
            return this;
        }

        /**
         * Builds a {@code ModuleDescriptor} from the components.
         */
        public ModuleDescriptor build() {
            assert name != null;
            return new ModuleDescriptor(name,
                                        automatic,
                                        requires,
                                        uses,
                                        exports,
                                        provides,
                                        version,
                                        mainClass,
                                        conceals,
                                        hashes);
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
                && automatic == that.automatic
                && requires.equals(that.requires)
                && uses.equals(that.uses)
                && exports.equals(that.exports)
                && provides.equals(that.provides)
                && Objects.equals(version, that.version)
                && Objects.equals(mainClass, that.mainClass)
                && Objects.equals(conceals, that.conceals)
                && Objects.equals(hashes, that.hashes));
    }

    private transient int hash;  // cached hash code

    @Override
    public int hashCode() {
        int hc = hash;
        if (hc == 0) {
            hc = name.hashCode();
            hc = hc * 43 + Boolean.hashCode(automatic);
            hc = hc * 43 + requires.hashCode();
            hc = hc * 43 + uses.hashCode();
            hc = hc * 43 + exports.hashCode();
            hc = hc * 43 + provides.hashCode();
            hc = hc * 43 + Objects.hashCode(version);
            hc = hc * 43 + Objects.hashCode(mainClass);
            hc = hc * 43 + Objects.hashCode(conceals);
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

    /**
     * Reads a module descriptor from an input stream.
     *
     * <p> If the descriptor encoded in the input stream does not indicate a
     * set of concealed packages then the {@code packageFinder} will be
     * invoked.  The packages it returns, except for those indicated as
     * exported in the encoded descriptor, will be considered to be concealed.
     * If the {@code packageFinder} throws an {@link UncheckedIOException} then
     * the original {@link IOException} will be re-thrown.
     *
     * @apiNote The {@code packageFinder} parameter is for use when reading
     * module descriptors from legacy module-artifact formats that do not
     * record the set of concealed packages in the descriptor itself.
     *
     * @param  packageFinder  A supplier that can produce a set of package
     *         names
     */
    public static ModuleDescriptor read(InputStream in,
                                        Supplier<Set<String>> packageFinder)
        throws IOException
    {
        return ModuleInfo.read(in, requireNonNull(packageFinder));
    }

    /**
     * Reads a module descriptor from an input stream.
     */
    public static ModuleDescriptor read(InputStream in)
        throws IOException
    {
        return ModuleInfo.read(in, null);
    }

    /**
     * Reads a module descriptor from a byte buffer.
     *
     * <p> If the descriptor encoded in the byte buffer does not indicate a
     * set of concealed packages then the {@code packageFinder} will be
     * invoked.  The packages it returns, except for those indicated as
     * exported in the encoded descriptor, will be considered to be concealed.
     *
     * @apiNote The {@code packageFinder} parameter is for use when reading
     * module descriptors from legacy module-artifact formats that do not
     * record the set of concealed packages in the descriptor itself.
     */
    public static ModuleDescriptor read(ByteBuffer bb,
                                        Supplier<Set<String>> packageFinder)
    {
        return ModuleInfo.read(bb, requireNonNull(packageFinder));
    }

    /**
     * Reads a module descriptor from a byte buffer.
     */
    public static ModuleDescriptor read(ByteBuffer bb) {
        return ModuleInfo.read(bb, null);
    }

}
