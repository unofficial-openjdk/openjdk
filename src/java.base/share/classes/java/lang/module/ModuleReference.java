/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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
import java.util.Objects;
import java.util.Optional;

import jdk.internal.module.Hasher.HashSupplier;


/**
 * A reference to a module's content.
 *
 * <p> A module reference contains the module's descriptor and its location, if
 * known.  It also has the ability to open a {@link ModuleReader} in order to
 * access the module's content, which may be inside the Java run-time system
 * itself or in an artifact such as a modular JAR file or a JMOD file.
 *
 * @see ModuleFinder
 * @see ModuleReader
 * @since 1.9
 */

public abstract class ModuleReference {

    private final ModuleDescriptor descriptor;
    private final Optional<URI> location;

    // the function that computes the hash of this module reference
    private final HashSupplier hasher;

    // cached hash string to avoid needing to compute it many times
    private String cachedHash;

    /**
     * Constructs a new instance of this class.
     */
    ModuleReference(ModuleDescriptor descriptor,
                    URI location,
                    HashSupplier hasher)
    {
        this.descriptor = Objects.requireNonNull(descriptor);
        this.location = Optional.ofNullable(location);
        this.hasher = hasher;
    }

    /**
     * Constructs a new instance of this class.
     */
    protected ModuleReference(ModuleDescriptor descriptor,
                              URI location)
    {
        this(descriptor, location, null);
    }

    /**
     * Return the reference's extended module descriptor.
     */
    public ModuleDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Returns the location of this module's content, if known.
     *
     * <p> This URI, when present, is used as the {@linkplain
     * java.security.CodeSource#getLocation location} value of a {@link
     * java.security.CodeSource CodeSource} so that a module's classes can be
     * granted specific permissions when loaded by a {@link
     * java.security.SecureClassLoader SecureClassLoader}.
     */
    public Optional<URI> location() {
        return location;
    }

    /**
     * Opens the modules reference for reading, returning a {@code ModuleReader}
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
            hc = descriptor.hashCode() ^ location.hashCode();
            hash = hc;
        }
        return hc;
    }

    public boolean equals(Object obj) {
        if (!(obj instanceof ModuleReference))
            return false;
        ModuleReference that = (ModuleReference)obj;
        if (!this.descriptor.equals(that.descriptor))
            return false;
        if (!this.location.equals(that.location))
            return false;
        return true;
    }

    public String toString() {
        return ("[module " + descriptor().name()
                + ", location=" + location.get() + "]");
    }

}
