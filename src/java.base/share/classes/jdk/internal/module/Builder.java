/*
 * Copyright (c) 2015, 2016, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleDescriptor.Provides;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor.Version;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import jdk.internal.misc.JavaLangModuleAccess;
import jdk.internal.misc.SharedSecrets;

/**
 * This builder is optimized for reconstituting ModuleDescriptor
 * for system modules.  The validation should be done at jlink time.
 *
 * 1. skip name validation
 * 2. ignores dependency hashes.
 * 3. ModuleDescriptor skips the defensive copy and directly uses the
 *    sets/maps created in this Builder.
 *
 * SystemModules should contain modules for the boot layer.
 */
final class Builder {
    private static final JavaLangModuleAccess jlma =
        SharedSecrets.getJavaLangModuleAccess();

    // Static cache of the most recently seen Version to cheaply deduplicate
    // most Version objects.  JDK modules have the same version.
    static Version cachedVersion;

    /**
     * Create module dependence with the given (and possibly empty) set
     * of modifiers.
     */
    public static Requires newRequires(Set<Requires.Modifier> mods, String mn) {
        return jlma.newRequires(mods, mn);
    }

    /**
     * Create a qualified export to a set of target modules with a given set of
     * modifiers.
     */
    public static Exports newExports(Set<Exports.Modifier> ms,
                                     String pn,
                                     Set<String> targets) {
        return jlma.newExports(ms, pn, targets);
    }

    /**
     * Create an unqualified export with a given set of modifiers.
     */
    public static Exports newExports(Set<Exports.Modifier> ms, String pn) {
        return jlma.newExports(ms, pn);
    }

    /**
     * Provides service {@code st} with implementations {@code pcs}.
     */
    public static Provides newProvides(String st, Set<String> pcs) {
        return jlma.newProvides(st, pcs);
    }

    final String name;
    Map<String, Provides> provides;
    Set<Requires> requires;
    Set<Exports> exports;
    boolean weak;
    boolean automatic;
    boolean synthetic;
    Set<String> packages;
    Set<String> uses;
    Version version;
    String mainClass;
    String osName;
    String osArch;
    String osVersion;
    String algorithm;
    Map<String, String> hashes;

    Builder(String name) {
        this.name = name;
        this.provides = Collections.emptyMap();
        this.exports = Collections.emptySet();
        this.requires = Collections.emptySet();
        this.uses = Collections.emptySet();
    }

    Builder weak(boolean value) {
        this.weak = value;
        return this;
    }

    Builder automatic(boolean value) {
        this.automatic = value;
        return this;
    }

    Builder synthetic(boolean value) {
        this.synthetic = value;
        return this;
    }

    /**
     * Set module exports.
     */
    public Builder exports(Exports[] exports) {
        this.exports = Set.of(exports);
        return this;
    }

    /**
     * Set module requires.
     */
    public Builder requires(Requires[] requires) {
        this.requires = Set.of(requires);
        return this;
    }

    /**
     * Set module provides.
     */
    @SuppressWarnings(value = { "unchecked", "rawtypes" })
    public Builder provides(Provides[] provides) {
        Map.Entry<String, Provides>[] provideEntries = new Map.Entry[provides.length];
        for (int i = 0; i < provides.length; i++) {
            provideEntries[i] = Map.entry(provides[i].service(), provides[i]);
        }
        this.provides = Map.ofEntries(provideEntries);
        return this;
    }

    /**
     * Adds a set of (possible empty) packages.
     */
    public Builder packages(Set<String> packages) {
        this.packages = packages;
        return this;
    }

    /**
     * Sets the set of service dependences.
     */
    public Builder uses(Set<String> uses) {
        this.uses = uses;
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
        Version ver = cachedVersion;
        if (ver != null && v.equals(ver.toString())) {
            version = ver;
        } else {
            cachedVersion = version = Version.parse(v);
        }
        return this;
    }

    /**
     * Sets the module main class.
     *
     * @throws IllegalStateException if already set
     */
    public Builder mainClass(String mc) {
        if (mainClass != null)
            throw new IllegalStateException("main class already set");
        mainClass = mc;
        return this;
    }

    /**
     * Sets the OS name.
     *
     * @throws IllegalStateException if already set
     */
    public Builder osName(String name) {
        if (osName != null)
            throw new IllegalStateException("OS name already set");
        this.osName = name;
        return this;
    }

    /**
     * Sets the OS arch.
     *
     * @throws IllegalStateException if already set
     */
    public Builder osArch(String arch) {
        if (osArch != null)
            throw new IllegalStateException("OS arch already set");
        this.osArch = arch;
        return this;
    }

    /**
     * Sets the OS version.
     *
     * @throws IllegalStateException if already set
     */
    public Builder osVersion(String version) {
        if (osVersion != null)
            throw new IllegalStateException("OS version already set");
        this.osVersion = version;
        return this;
    }

    /**
     * Sets the algorithm of the module hashes
     */
    public Builder algorithm(String algorithm) {
        this.algorithm = algorithm;
        return this;
    }

    /**
     * Sets the module hash for the given module name
     */
    public Builder moduleHash(String mn, String hash) {
        if (hashes == null)
            hashes = new HashMap<>();

        hashes.put(mn, hash);
        return this;
    }

    /**
     * Builds a {@code ModuleDescriptor} from the components.
     */
    public ModuleDescriptor build() {
        assert name != null;

        ModuleHashes moduleHashes =
            hashes != null ? new ModuleHashes(algorithm, hashes) : null;

        return jlma.newModuleDescriptor(name,
                                        weak,         // weak
                                        automatic,    // automatic
                                        synthetic,    // synthetic
                                        requires,
                                        uses,
                                        exports,
                                        provides,
                                        version,
                                        mainClass,
                                        osName,
                                        osArch,
                                        osVersion,
                                        packages,
                                        moduleHashes);
    }
}
