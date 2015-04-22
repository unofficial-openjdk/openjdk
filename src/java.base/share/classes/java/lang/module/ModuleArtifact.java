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

package java.lang.module;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

import jdk.jigsaw.module.internal.Hasher.HashSupplier;

/**
 * Represents a module artifact. A module artifact contains the contents of a
 * module along with its module descriptor that defines the module name,
 * dependences and exports. Examples of module artifacts are modular JAR files
 * or jmod files.
 *
 * A {@code ModuleArtifact} is a concrete subclass of this class that implements
 * the abstract {@link #open open} method defined below.
 *
 * @see ModuleArtifactFinder
 * @since 1.9
 */

public abstract class ModuleArtifact {

    private final ExtendedModuleDescriptor descriptor;
    private final Set<String> packages;
    private final URI location;

    // the function that computes the hash of this module artifact
    private final HashSupplier hasher;

    // cached hash string to avoid needing to compute it many times
    private String cachedHash;

    /**
     * Constructs a new instance of this class.
     */
    ModuleArtifact(ExtendedModuleDescriptor descriptor,
                   Set<String> packages,
                   URI location,
                   HashSupplier hasher)
    {

        packages = Collections.unmodifiableSet(packages);
        if (packages.contains("") || packages.contains(null))
            throw new IllegalArgumentException("<unnamed> package or null not allowed");

        this.descriptor = Objects.requireNonNull(descriptor);
        this.packages = packages;
        this.location = Objects.requireNonNull(location);
        this.hasher = hasher;

        // all exported packages must be in contents
        for (ModuleDescriptor.Exports export: descriptor.exports()) {
            String pkg = export.source();
            if (!packages.contains(pkg)) {
                String name = descriptor.name();
                throw new IllegalArgumentException(name + " cannot export " +
                        pkg + ": not in module");
            }
        }
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
    protected ModuleArtifact(ExtendedModuleDescriptor descriptor,
                             Set<String> packages,
                             URI location)
    {
        this(descriptor, packages, location, null);
    }

    /**
     * Return the artifact's extended module descriptor.
     */
    public ExtendedModuleDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Return the set of packages for the module. The set is immutable.
     */
    public Set<String> packages() {
        return packages;
    }

    /**
     * Returns the URI that locates the artifact.
     *
     * <p> When loading classes from a module artifact with a {@link
     * java.security.SecureClassLoader SecureClassLoader}, then this URI is
     * typically the location associated with {@link java.security.CodeSource
     * CodeSource}.
     */
    public URI location() {
        return location;
    }

    /**
     * Opens the modules artifact for reading, returning a {@code ModuleReader}
     * that may be used to locate or read classes and resources.
     *
     * @throws IOException
     *         If an I/O error occurs
     * @throws SecurityException
     *         If denied by the security manager
     */
    public abstract ModuleReader open() throws IOException;

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
