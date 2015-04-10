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

import java.lang.reflect.Module;
import java.util.Map;

import jdk.jigsaw.module.Configuration;
import jdk.jigsaw.module.Layer.ClassLoaderFinder;
import jdk.jigsaw.module.ModuleArtifact;

/**
 * Provides access to package-private modules in java.lang.reflect.
 */

public interface JavaLangReflectAccess {

    /**
     * Defines a new module to the Java virtual machine. The module
     * is defined to the given class loader.
     */
    Module defineModule(ClassLoader loader, ModuleArtifact artifact);

    /**
     * Defines the modules in the given {@code Configuration} to the Java
     * virtual machine. The modules are mapped to class loaders using the
     * given {@code ClassLoaderFinder}.
     */
    Map<String, Module> defineModules(Configuration cf, ClassLoaderFinder clf);

    /**
     * Returns {@code true} if module m1 exports a package to module m2.
     * This method is used by sun.misc.Reflection.verifyModuleAccess.
     */
    boolean isExported(Module m1, String pkg, Module m2);

    /**
     * Add a package to the given module.
     */
    void addPackage(Module m, String pkg);

    /**
     * Updates the readability so that module m1 reads m2. The new read edge
     * does not result in a strong reference to m2 (m2 can be GC'ed).
     */
    void addReadsModule(Module m1, Module m2);

    /**
     * Causes module m1 to export a package to module m2. If m2 is {@code null}
     * then the package is exported to all module that read m1. The export does
     * not result in a strong reference to m2 (m2 can be GC'ed).
     */
    void addExports(Module m1, String pkg, Module m2);
}
