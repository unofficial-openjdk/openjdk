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

import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * Represents a module artifact. A module artifact is typically a modular JAR
 * or jmod but it can be anything.
 *
 * @apiNote ModuleDefinition doesn't work well as a name.
 */
public final class ModuleArtifact {

    private final ExtendedModuleDescriptor descriptor;
    private final Set<String> packages;
    private final URL url;

    ModuleArtifact(ModuleInfo mi, String id, Set<String> packages, URL url) {
        this.descriptor = new ExtendedModuleDescriptor(ModuleId.parse(id),
                                                       mi.moduleDependences(),
                                                       mi.serviceDependences(),
                                                       mi.exports(),
                                                       mi.services());
        this.packages = Collections.unmodifiableSet(packages);
        this.url = url;
    }

    ModuleArtifact(ModuleInfo mi, Set<String> packages, URL url) {
        this(mi, mi.name(), packages, url);
    }

    public ModuleArtifact(ExtendedModuleDescriptor descriptor, Set<String> packages, URL url) {
        this.descriptor = descriptor;
        this.packages = Collections.unmodifiableSet(packages);
        this.url = url;
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
     * Returns a URL to the artifact.
     */
    public URL location() {
        return url;
    }

    public int hashCode() {
        return descriptor.hashCode() ^ packages.hashCode() ^ url.hashCode();
    }

    public boolean equals(Object obj) {
        if (!(obj instanceof ModuleArtifact))
            return false;
        ModuleArtifact that = (ModuleArtifact)obj;
        if (!this.descriptor.equals(that.descriptor))
            return false;
        if (!this.packages.equals(that.packages))
            return false;
        if (!this.url.equals(that.url))
            return false;
        return true;
    }
}
