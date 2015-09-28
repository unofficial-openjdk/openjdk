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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static java.lang.module.Checks.*;
import static java.util.Objects.*;

import jdk.internal.module.Hasher.DependencyHashes;


/**
 * A module descriptor.
 *
 * @since 1.9
 */

public class ModuleDescriptor
    implements Comparable<ModuleDescriptor>
{

    /**
     * <p> A dependence upon a module </p>
     *
     * @see ModuleDescriptor#requires()
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
         * Returns the set of modifiers.
         *
         * @return A possibly-empty unmodifiable set of modifiers
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
     *
     * @see ModuleDescriptor#exports()
     * @since 1.9
     */

    public final static class Exports {

        private final String source;
        private final Optional<Set<String>> targets;

        private Exports(String source, Set<String> targets) {
            this.source = requirePackageName(source);
            targets = Collections.unmodifiableSet(new HashSet<>(targets));
            if (targets.isEmpty())
                throw new IllegalArgumentException("Empty target set");
            targets.stream().forEach(Checks::requireModuleName);
            this.targets = Optional.of(targets);
        }

        /**
         * Constructs an {@code Exports} to represent the exporting of package
         * {@code source}.
         */
        private Exports(String source) {
            this.source = requirePackageName(source);
            this.targets = Optional.empty();
        }

        /**
         * Returns the package name.
         */
        public String source() {
            return source;
        }

        /**
         * For a qualified export, returns the non-empty and immutable set
         * of the module names to which the package is exported. For an
         * unqualified export, returns an empty {@code Optional}.
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
     * <p> A service that a module provides one or more implementations of. </p>
     *
     * @see ModuleDescriptor#provides()
     * @since 1.9
     */

    public final static class Provides {

        private final String service;
        private final Set<String> providers;

        private Provides(String service, Set<String> providers) {
            this.service = requireServiceTypeName(service);
            providers = Collections.unmodifiableSet(new HashSet<>(providers));
            if (providers.isEmpty())
                throw new IllegalArgumentException("Empty providers set");
            providers.forEach(Checks::requireServiceProviderName);
            this.providers = providers;
        }

        /**
         * Returns the service type.
         */
        public String service() { return service; }

        /**
         * Returns the set of provider names.
         *
         * @return A non-empty and unmodifiable set of provider names
         */
        public Set<String> providers() { return providers; }

        public int hashCode() {
            return hash(service, providers);
        }

        public boolean equals(Object obj) {
            if (!(obj instanceof Provides))
                return false;
            Provides other = (Provides)obj;
            return Objects.equals(this.service, other.service) &&
                    Objects.equals(this.providers, other.providers);
        }

    }



    /**
     * Vaguely Debian-like version strings, for now.
     * This will, eventually, change.
     *
     * @see <a href="http://www.debian.org/doc/debian-policy/ch-controlfields.html#s-f-Version">Debian
     * Policy Manual, Chapter 5: Control files and their fields<a>
     *
     * @see ModuleDescriptor#version()
     * @since 1.9
     */

    public final static class Version
        implements Comparable<Version>
    {

        private final String version;

        // If Java had disjunctive types then we'd write List<Integer|String> here
        //
        private final List<Object> sequence;
        private final List<Object> branch;

        // Take a numeric token starting at position i
        // Append it to the given list
        // Return the index of the first character not taken
        // Requires: s.charAt(i) is (decimal) numeric
        //
        private static int takeNumber(String s, int i, List<Object> acc) {
            char c = s.charAt(i);
            int d = (c - '0');
            int n = s.length();
            while (++i < n) {
                c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    d = d * 10 + (c - '0');
                    continue;
                }
                break;
            }
            acc.add(d);
            return i;
        }

        // Take a string token starting at position i
        // Append it to the given list
        // Return the index of the first character not taken
        // Requires: s.charAt(i) is not '.'
        //
        private static int takeString(String s, int i, List<Object> acc) {
            int b = i;
            int n = s.length();
            while (++i < n) {
                char c = s.charAt(i);
                if (c != '.' && c != '-' && !(c >= '0' && c <= '9'))
                    continue;
                break;
            }
            acc.add(s.substring(b, i));
            return i;
        }

        // Version syntax, for now: tok+ ( '-' tok+)?
        // First token string is sequence, second is branch
        // Tokens are delimited by '.', or by changes between alpha & numeric
        // chars
        // Numeric tokens are compared as decimal numbers
        // Non-numeric tokens are compared lexicographically
        // Tokens in branch may contain '-'
        //
        private Version(String v) {

            if (v == null)
                throw new IllegalArgumentException("Null version string");
            int n = v.length();
            if (n == 0)
                throw new IllegalArgumentException("Empty version string");

            int i = 0;
            char c = v.charAt(i);
            if (!(c >= '0' && c <= '9'))
                throw new
                        IllegalArgumentException(v
                        + ": Version does not start"
                        + " with a number");

            List<Object> sequence = new ArrayList<>(4);
            List<Object> branch = new ArrayList<>(2);

            i = takeNumber(v, i, sequence);

            while (i < n) {
                c = v.charAt(i);
                if (c == '.') {
                    i++;
                    continue;
                }
                if (c == '-') {
                    i++;
                    break;
                }
                if (c >= '0' && c <= '9')
                    i = takeNumber(v, i, sequence);
                else
                    i = takeString(v, i, sequence);
            }

            if (c == '-' && i >= n)
                throw new IllegalArgumentException(v + ": Empty branch");

            while (i < n) {
                c = v.charAt(i);
                if (c >= '0' && c <= '9')
                    i = takeNumber(v, i, branch);
                else
                    i = takeString(v, i, branch);
                if (i >= n)
                    break;
                c = v.charAt(i);
                if (c == '.') {
                    i++;
                    continue;
                }
            }

            this.version = v;
            this.sequence = sequence;
            this.branch = branch;
        }

        /**
         * Parses the given string as a version string.
         *
         * @throws IllegalArgumentException
         *         If {@code v} is {@code null}, an empty string, or cannot be
         *         parsed as a version string
         */
        public static Version parse(String v) {
            return new Version(v);
        }

        @SuppressWarnings("unchecked")
        private int cmp(Object o1, Object o2) {
            return ((Comparable)o1).compareTo(o2);
        }

        private int compareTokens(List<Object> ts1, List<Object> ts2) {
            int n = Math.min(ts1.size(), ts2.size());
            for (int i = 0; i < n; i++) {
                Object o1 = ts1.get(i);
                Object o2 = ts2.get(i);
                if (   (o1 instanceof Integer && o2 instanceof Integer)
                        || (o1 instanceof String && o2 instanceof String)) {
                    int c = cmp(o1, o2);
                    if (c == 0)
                        continue;
                    return c;
                }
                // Types differ, so convert number to string form
                int c = o1.toString().compareTo(o2.toString());
                if (c == 0)
                    continue;
                return c;
            }
            List<Object> rest = ts1.size() > ts2.size() ? ts1 : ts2;
            int e = rest.size();
            for (int i = n; i < e; i++) {
                Object o = rest.get(i);
                if (o instanceof Integer && ((Integer)o) == 0)
                    continue;
                return ts1.size() - ts2.size();
            }
            return 0;
        }

        @Override
        public int compareTo(Version that) {
            int c = compareTokens(this.sequence, that.sequence);
            if (c != 0)
                return c;
            return compareTokens(this.branch, that.branch);
        }

        @Override
        public boolean equals(Object ob) {
            if (!(ob instanceof Version))
                return false;
            return compareTo((Version)ob) == 0;
        }

        @Override
        public int hashCode() {
            return version.hashCode();
        }

        @Override
        public String toString() {
            return version;
        }

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

        this.name = name;
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
        this.mainClass = Optional.ofNullable(mainClass);
        this.hashes = Optional.ofNullable(hashes);

        assert !exports.keySet().stream().anyMatch(conceals::contains)
            : "Module " + name + ": Package sets overlap";
        this.conceals = Collections.unmodifiableSet(conceals);
        Set<String> pkgs = new HashSet<>(conceals);
        pkgs.addAll(exports.keySet());
        this.packages = Collections.unmodifiableSet(pkgs);

    }

    /**
     * Clones the given module descriptor with an augmented set of packages
     */
    ModuleDescriptor(ModuleDescriptor md, Set<String> pkgs) {
        this.name = md.name;
        this.automatic = md.automatic;

        this.requires = md.requires;
        this.exports = md.exports;
        this.uses = md.uses;
        this.provides = md.provides;

        this.version = md.version;
        this.mainClass = md.mainClass;
        this.hashes = Optional.empty(); // need to ignore

        // compute new set of concealed packages
        Set<String> conceals = new HashSet<>(pkgs);
        exports.stream().map(Exports::source).forEach(conceals::remove);

        this.conceals = Collections.unmodifiableSet(conceals);
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
     *
     * @return The possibly-empty unmodifiable map of the services that this
     *         module provides. The map key is the service type.
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
     *
     * @return A possibly-empty unmodifiable set of the concealed packages
     */
    public Set<String> conceals() {
        return conceals;
    }

    /**
     * Returns the names of all the packages defined in this module, whether
     * exported or concealed.
     *
     * @return A possibly-empty unmodifiable set of the all packages
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
     * @since 1.9
     */
    public static final class Builder {

        final String name;
        final boolean automatic;
        final Map<String, Requires> requires = new HashMap<>();
        final Set<String> uses = new HashSet<>();
        final Map<String, Exports> exports = new HashMap<>();
        final Map<String, Provides> provides = new HashMap<>();
        Set<String> conceals = Collections.emptySet();
        Version version;
        String mainClass;
        DependencyHashes hashes;

        /**
         * Ensures that the given package name has not been declared as an
         * exported or concealed package.
         */
        private void ensureNotExportedOrConcealed(String pn) {
            if (exports.containsKey(pn))
                throw new IllegalStateException("Export of package "
                                                + pn + " already declared");
            if (conceals.contains(pn))
                throw new IllegalStateException("Concealed package "
                                                + pn + " already declared");
        }

        /**
         * Initializes a new builder with the given module name.
         *
         * @throws IllegalArgumentException if the module name is {@code null}
         *         or is not a legal Java identifier
         */
        public Builder(String name) {
            this(name, false);
        }

        /* package */ Builder(String name, boolean automatic) {
            this.name = requireModuleName(name);
            this.automatic = automatic;
        }

        /**
         * Adds a module dependence with the given (and possibly empty) set
         * of modifiers.
         *
         * @throws IllegalArgumentException if the module name is {@code null},
         *         is not a legal Java identifier, or is equal to the module
         *         name that this builder was initialized to build
         * @throws IllegalStateException if a dependency on the module has
         *         already been declared
         */
        public Builder requires(Set<Requires.Modifier> mods, String mn) {
            if (name.equals(mn))
                throw new IllegalArgumentException("Dependence on self");
            if (requires.containsKey(mn))
                throw new IllegalStateException("Dependence upon " + mn
                                                + " already declared");
            requires.put(mn, new Requires(mods, mn)); // checks mn
            return this;
        }

        /**
         * Adds a module dependence with an empty set of modifiers.
         *
         * @throws IllegalArgumentException if the module name is {@code null},
         *         is not a legal Java identifier, or is equal to the module
         *         name that this builder was initialized to build
         * @throws IllegalStateException if a dependency on the module has
         *         already been declared
         */
        public Builder requires(String mn) {
            return requires(EnumSet.noneOf(Requires.Modifier.class), mn);
        }

        /**
         * Adds a module dependence with the given modifier.
         *
         * @throws IllegalArgumentException if the module name is {@code null},
         *         is not a legal Java identifier, or is equal to the module
         *         name that this builder was initialized to build
         * @throws IllegalStateException if a dependency on the module has
         *         already been declared
         */
        public Builder requires(Requires.Modifier mod, String mn) {
            return requires(EnumSet.of(mod), mn);
        }

        /**
         * Adds a service dependence.
         *
         * @throws IllegalArgumentException if the service type is {@code null}
         *         or is not a legal Java identifier
         * @throws IllegalStateException if a dependency on the service type
         *         has already been declared
         */
        public Builder uses(String st) {
            if (uses.contains(requireServiceTypeName(st)))
                throw new IllegalStateException("Dependence upon service "
                                                + st + " already declared");
            uses.add(st);
            return this;
        }

        /**
         * Adds an export to a set of target modules.
         *
         * @throws IllegalArgumentException if the package name or any of the
         *         target modules is {@code null} or is not a legal Java
         *         identifier, or the set of targets is empty
         * @throws IllegalStateException if the package is already declared as
         *         an exported or concealed package
         */
        public Builder exports(String pn, Set<String> targets) {
            ensureNotExportedOrConcealed(pn);
            exports.put(pn, new Exports(pn, targets)); // checks pn and targets
            return this;
        }

        /**
         * Adds an export to a target module.
         *
         * @throws IllegalArgumentException if the package name or target
         *         module is {@code null} or is not a legal Java identifier
         * @throws IllegalStateException if the package is already declared as
         *         an exported or concealed package
         */
        public Builder exports(String pn, String target) {
            return exports(pn, Collections.singleton(target));
        }

        /**
         * Adds an export.
         *
         * @throws IllegalArgumentException if the package name is {@code null}
         *         or is not a legal Java identifier
         * @throws IllegalStateException if the package is already declared as
         *         an exported or concealed package
         */
        public Builder exports(String pn) {
            ensureNotExportedOrConcealed(pn);
            exports.put(pn, new Exports(pn)); // checks pn
            return this;
        }

        // Used by ModuleInfo, after a packageFinder is invoked
        /* package */ Set<String> exportedPackages() {
            return exports.keySet();
        }

        /**
         * Provides service {@code st} with implementations {@code pcs}.
         *
         * @throws IllegalArgumentException if the service type or any of the
         *         provider class names is {@code null} or is not a legal Java
         *         identifier, or the set of provider class names is empty
         * @throws IllegalStateException if the providers for the service type
         *         have already been declared
         */
        public Builder provides(String st, Set<String> pcs) {
            if (provides.containsKey(st))
                throw new IllegalStateException("Providers of service "
                                                + st + " already declared");
            provides.put(st, new Provides(st, pcs)); // checks st and pcs
            return this;
        }

        /**
         * Provides service {@code st} with implementation {@code pc}.
         *
         * @throws IllegalArgumentException if the service type or the
         *         provider class name is {@code null} or is not a legal Java
         *         identifier
         * @throws IllegalStateException if the providers for the service type
         *         have already been declared
         */
        public Builder provides(String st, String pc) {
            return provides(st, Collections.singleton(pc));
        }

        /**
         * Adds a set of (possible empty) concealed packages.
         *
         * @throws IllegalArgumentException if any of the package names is
         *         {@code null} or is not a legal Java identifier
         * @throws IllegalStateException if any of packages are already declared
         *         as a concealed or exported package
         */
        public Builder conceals(Set<String> packages) {
            packages.forEach(this::conceals);
            return this;
        }

        /**
         * Adds a concealed package.
         *
         * @throws IllegalArgumentException if the package name is {@code null},
         *         or is not a legal Java identifier
         * @throws IllegalStateException if the package is already declared as
         *         a concealed or exported package
         */
        public Builder conceals(String pn) {
            Checks.requirePackageName(pn);
            ensureNotExportedOrConcealed(pn);
            if (conceals.isEmpty())
                conceals = new HashSet<>();
            conceals.add(pn);
            return this;
        }

        /**
         * Sets the module version.
         *
         * @throws IllegalArgumentException if {@code v} is null or cannot be
         *         parsed as a version string
         * @throws IllegalStateException if the module version is already set
         *
         * @see Version#parse(String)
         */
        public Builder version(String v) {
            if (version != null)
                throw new IllegalStateException("module version already set");
            version = Version.parse(v);
            return this;
        }

        /**
         * Sets the module main class.
         *
         * @throws IllegalArgumentException if {@code mainClass} is null or
         *         is not a legal Java identifier
         * @throws IllegalStateException if the module main class is already
         *         set
         */
        public Builder mainClass(String mc) {
            if (mainClass != null)
                throw new IllegalStateException("main class already set");
            mainClass = requireJavaIdentifier("main class name", mc);
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


    /**
     * Compares this module descriptor to another.
     *
     * <p> Two {@code ModuleDescriptor} objects are compared by comparing their
     * module name lexicographically.  Where the module names are equal then
     * the versions, if present, are compared.
     *
     * @return A negative integer, zero, or a positive integer if this module
     *         descriptor is less than, equal to, or greater than the given
     *         module descriptor
     */
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
            sb.append("]");
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
     * <p> If there are bytes following the module descriptor then it is
     * implementation specific as to whether those bytes are read, ignored,
     * or reported as an {@code InvalidModuleDescriptorException}. If this
     * method fails with a {@code InvalidModuleDescriptorException} or {@code
     * IOException} then it may do so after some, but not all, bytes have
     * been read from the input stream. It is strongly recommended that the
     * stream be promptly closed and discarded if an exception occurs.
     *
     * @param  packageFinder  A supplier that can produce a set of package
     *         names
     *
     * @throws InvalidModuleDescriptorException If an invalid module descriptor
     *         is detected
     *
     * @throws IOException If an I/O error occurs reading from the input stream
     *         or {@code UncheckedIOException} is thrown by the package finder
     */
    public static ModuleDescriptor read(InputStream in,
                                        Supplier<Set<String>> packageFinder)
        throws IOException
    {
        return ModuleInfo.read(in, requireNonNull(packageFinder));
    }

    /**
     * Reads a module descriptor from an input stream.
     *
     * @throws InvalidModuleDescriptorException If an invalid module descriptor
     *         is detected
     *
     * @throws IOException If an I/O error occurs reading from the input stream
     */
    public static ModuleDescriptor read(InputStream in) throws IOException {
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
     * <p> The module descriptor is read from the buffer stating at index
     * {@code p}, where {@code p} is the buffer's {@link ByteBuffer#position()
     * position} when this method is invoked. Upon return the buffer's position
     * will be equal to {@code p + n} where {@code n} is the number of bytes
     * read from the buffer.
     *
     * @apiNote The {@code packageFinder} parameter is for use when reading
     * module descriptors from legacy module-artifact formats that do not
     * record the set of concealed packages in the descriptor itself.
     *
     * <p> If there are bytes following the module descriptor then it is
     * implementation specific as to whether those bytes are read, ignored,
     * or reported as an {@code InvalidModuleDescriptorException}. If this
     * method fails with an {@code InvalidModuleDescriptorException} then it
     * may do so after some, but not all,
     * bytes have been read.
     *
     * @throws InvalidModuleDescriptorException If an invalid module descriptor
     *         is detected
     */
    public static ModuleDescriptor read(ByteBuffer bb,
                                        Supplier<Set<String>> packageFinder)
    {
        return ModuleInfo.read(bb, requireNonNull(packageFinder));
    }

    /**
     * Reads a module descriptor from a byte buffer.
     *
     * @throws InvalidModuleDescriptorException If an invalid module descriptor
     *         is detected
     */
    public static ModuleDescriptor read(ByteBuffer bb) {
        return ModuleInfo.read(bb, null);
    }


    static {
        /**
         * Setup the shared secret to allow code in other packages know if a
         * module is an automatic module. If isAutomatic becomes part of the
         * API then this setup can go away.
         */
        sun.misc.SharedSecrets
            .setJavaLangModuleAccess(new sun.misc.JavaLangModuleAccess() {
                @Override
                public boolean isAutomatic(ModuleDescriptor descriptor) {
                    return descriptor.isAutomatic();
                }
            });
    }

}
