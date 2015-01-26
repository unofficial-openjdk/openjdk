/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jdk.jigsaw.module.internal.ControlFile;
import jdk.jigsaw.module.internal.Hasher;
import jdk.jigsaw.module.internal.Hasher.HashSupplier;
import jdk.jigsaw.module.internal.Hasher.DependencyHashes;
import jdk.jigsaw.module.internal.ModuleInfo;

/**
 * Represents a module artifact. A module artifact is typically a modular JAR
 * or jmod but it can be anything.
 *
 * @apiNote In the API sketch then this is ModuleDefinition
 */
public final class ModuleArtifact {

    private final ExtendedModuleDescriptor descriptor;
    private final Set<String> packages;
    private final URI location;

    // the function that computes the hash of this module artifact
    private final HashSupplier hasher;

    // cached hash string to avoid needing to compute it many times
    private String cachedHash;

    ModuleArtifact(ModuleInfo mi,
                   Set<String> packages,
                   URI location,
                   ControlFile cf,
                   HashSupplier hasher)
    {
        ModuleId id = ModuleId.parse(mi.name(), cf.version());

        // decode the hashes encoded in the extended module descriptor
        DependencyHashes hashes = DependencyHashes.decode(cf.dependencyHashes());

        this.descriptor = new ExtendedModuleDescriptor(id,
                                                       cf.mainClass(),
                                                       hashes,
                                                       mi.moduleDependences(),
                                                       mi.serviceDependences(),
                                                       mi.exports(),
                                                       mi.services());
        this.packages = Collections.unmodifiableSet(packages);
        this.location = location;
        this.hasher = hasher;
    }

    ModuleArtifact(ModuleInfo mi, Set<String> packages, URI location) {
        this(mi, packages, location, new ControlFile(), null);
    }

    /**
     * Constructs a new instance of this class.
     *
     * @throws IllegalArgumentException if {@code packages} does not include
     * an element for each of the exported packages in the module descriptor,
     * or {@code packages} contains the empty string or {@code null}.
     *
     * @apiNote Need to discuss what other validation should be done here. For
     * example, should this method check the package names to ensure that they
     * are composed of valid Java identifiers for a package name?
     */
    public ModuleArtifact(ExtendedModuleDescriptor descriptor,
                          Set<String> packages,
                          URI location)
    {
        packages = Collections.unmodifiableSet(packages);
        if (packages.contains("") || packages.contains(null))
            throw new IllegalArgumentException("<unnamed> package or null not allowed");
        for (ModuleExport export: descriptor.exports()) {
            String pkg = export.pkg();
            if (!packages.contains(pkg)) {
                throw new IllegalArgumentException("exported package " + pkg +
                    " not in contents");
            }
        }

        this.descriptor = Objects.requireNonNull(descriptor);
        this.packages = packages;
        this.location = Objects.requireNonNull(location);
        this.hasher = null;
    }

    /**
     * Return the artifact's extended module descriptor.
     */
    public ExtendedModuleDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Return the set of packages for the module.
     */
    public Set<String> packages() {
        return packages;
    }

    /**
     * Returns the URI that locates the artifact.
     */
    public URI location() {
        return location;
    }

    /**
     * Computes the MD5 hash of this module, returning it as a hex string.
     * Returns {@code null} if the hash cannot be computed.
     *
     * @throws java.io.UncheckedIOException if an I/O error occurs
     */
    String computeHash(String algorithm) {
        String result = cachedHash;
        if (result != null)
            return result;
        if (hasher == null)
            return null;
        cachedHash = result = hasher.generate(algorithm);
        return result;
    }

    private int hash;

    public int hashCode() {
        int hc = hash;
        if (hc == 0) {
            hc = descriptor.hashCode() ^ packages.hashCode() ^ location.hashCode();
            hash = hc;
        }
        return hc;
    }

    public boolean equals(Object obj) {
        if (!(obj instanceof ModuleArtifact))
            return false;
        ModuleArtifact that = (ModuleArtifact)obj;
        if (!this.descriptor.equals(that.descriptor))
            return false;
        if (!this.packages.equals(that.packages))
            return false;
        if (!this.location.equals(that.location))
            return false;
        return true;
    }

    public String toString() {
        return "[module " + descriptor().name() + ", location=" + location + "]";
    }
}
