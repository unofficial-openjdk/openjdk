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

package sun.misc;

import java.security.SecureClassLoader;

import java.lang.module.ModuleArtifact;

/**
 * A ClassLoader that has support for loading classes and resources from modules.
 */

public abstract class ModuleClassLoader extends SecureClassLoader {

    /**
     * Creates a new ModuleClassLoader using the specified parent class loader
     * for delegation.
     */
    protected ModuleClassLoader(ClassLoader parent) {
        super(parent);
    }

    /**
     * Creates a new ModuleClassLoader using the default parent class loader
     * for delegation.
     */
    protected ModuleClassLoader() {
        super();
    }

    /**
     * Define the module in the given module artifact to the class loader
     * with the effect of making the types in the module visible.
     */
    public abstract void defineModule(ModuleArtifact artifact);

    /**
     * Finds the class with the specified <a href="#name">binary name</a>
     * in a module defined to this class loader.
     *
     * @param  artifact A module artifact
     * @param  name     The <a href="#name">binary name</a> of the class
     *
     * @return The resulting {@code Class} object; {@code null} if the
     * class could not be found from the given module
     */
    public abstract Class<?> findClass(ModuleArtifact artifact, String name);
}
