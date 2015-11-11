/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
package jdk.internal.module;

import jdk.internal.misc.JavaLangModuleAccess;
import jdk.internal.misc.SharedSecrets;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleDescriptor.Provides;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor.Version;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/*
 * This builder is optimized for reconstituting ModuleDescriptor
 * for installed modules.  The validation should be done at jlink time.
 *
 * 1. skip name validation
 * 2. ignores dependency hashes.
 * 3. ModuleDescriptor skips the defensive copy and directly uses the
 *    sets/maps created in this Builder.
 *
 * InstalledModules should contain modules for the boot layer.
 */
final class Builder {
    private static final JavaLangModuleAccess jlma =
        SharedSecrets.getJavaLangModuleAccess();

    final String name;
    final Set<Requires> requires;
    final Set<String> uses;
    final Set<Exports> exports;
    final Map<String, Provides> provides;
    final Set<String> conceals;
    final Set<String> packages;
    Version version;
    String mainClass;

    Builder(String name, int reqs, int exports, int uses,
            int provides, int conceals, int packages) {
        this.name = name;
        this.requires = reqs > 0 ? new HashSet<>(reqs) : Collections.emptySet();
        this.exports  = exports > 0 ? new HashSet<>(exports) : Collections.emptySet();
        this.uses     = uses > 0 ? new HashSet<>(uses) : Collections.emptySet();
        this.provides = provides > 0 ? new HashMap<>(provides) : Collections.emptyMap();
        this.conceals = conceals > 0 ? new HashSet<>(conceals) : Collections.emptySet();
        this.packages = packages > 0 ? new HashSet<>(packages) : Collections.emptySet();
    }

    /**
     * Adds a module dependence with the given (and possibly empty) set
     * of modifiers.
     */
    public Builder requires(Set<Requires.Modifier> mods, String mn) {
        requires.add(jlma.newRequires(mods, mn));
        return this;
    }

    /**
     * Adds a module dependence with an empty set of modifiers.
     */
    public Builder requires(String mn) {
        return requires(EnumSet.noneOf(Requires.Modifier.class), mn);
    }

    /**
     * Adds a module dependence with the given modifier.
     */
    public Builder requires(Requires.Modifier mod, String mn) {
        return requires(EnumSet.of(mod), mn);
    }

    /**
     * Adds a service dependence.
     */
    public Builder uses(String st) {
        uses.add(st);
        return this;
    }

    /**
     * Adds an export to a set of target modules.
     */
    public Builder exports(String pn, Set<String> targets) {
        exports.add(jlma.newExports(pn, targets));
        packages.add(pn);
        return this;
    }

    /**
     * Adds an export to a target module.
     */
    public Builder exports(String pn, String target) {
        return exports(pn, Collections.singleton(target));
    }

    /**
     * Adds an export.
     */
    public Builder exports(String pn) {
        exports.add(jlma.newExports(pn));
        packages.add(pn);
        return this;
    }

    /**
     * Provides service {@code st} with implementations {@code pcs}.
     */
    public Builder provides(String st, Set<String> pcs) {
        if (provides.containsKey(st))
            throw new IllegalStateException("Providers of service "
                    + st + " already declared");
        provides.put(st, jlma.newProvides(st, pcs));
        return this;
    }

    /**
     * Provides service {@code st} with implementation {@code pc}.
     */
    public Builder provides(String st, String pc) {
        return provides(st, Collections.singleton(pc));
    }

    /**
     * Adds a set of (possible empty) concealed packages.
     */
    public Builder conceals(Set<String> packages) {
        conceals.addAll(packages);
        packages.addAll(packages);
        return this;
    }

    /**
     * Adds a concealed package.
     */
    public Builder conceals(String pn) {
        conceals.add(pn);
        packages.add(pn);
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
        mainClass = mc;
        return this;
    }

    /**
     * Builds a {@code ModuleDescriptor} from the components.
     */
    public ModuleDescriptor build() {
        assert name != null;
        return jlma.newModuleDescriptor(name,
                                        false,
                                        requires,
                                        uses,
                                        exports,
                                        provides,
                                        version,
                                        mainClass,
                                        conceals,
                                        packages);
    }
}
