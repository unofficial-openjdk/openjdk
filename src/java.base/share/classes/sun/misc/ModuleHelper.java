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
package sun.misc;

import java.lang.reflect.Module;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import jdk.jigsaw.module.ModuleDescriptor;

/**
 * A helper class to allow JDK classes (and tests) to easily create modules,
 * update modules, and update the readability graph.
 *
 * The parameters that are package names in this API are the fully-qualified
 * names of the packages as defined in section 6.5.3 of <cite>The Java&trade;
 * Language Specification </cite>, for example, {@code "java.lang"}.
 */

public class ModuleHelper {
    private ModuleHelper() { }

    /**
     * Creates a new module.
     *
     * @param loader the class loader to define the module to
     * @param descriptor the module descriptor
     * @param packages the set of packages (contents)
     */
    public static Module newModule(ClassLoader loader,
                                   ModuleDescriptor descriptor,
                                   Set<String> packages)
    {
        return SharedSecrets.getJavaLangReflectAccess()
                .defineModule(loader, descriptor, packages);
    }

    /**
     * Create a new module.
     *
     * @param name the module name
     * @param version ignored
     * @param location ignored
     * @param loader the class loader to define the module to
     * @param packages the array of packages (contents)
     *
     * @deprecated Use {@link #newModule} instead.
     */
    @Deprecated
    public static Module defineModule(String name,
                                      String version,
                                      String location,
                                      ClassLoader loader,
                                      String[] packages)
    {
        ModuleDescriptor descriptor = new ModuleDescriptor.Builder(name).build();
        Set<String> pkgs = new HashSet<>(Arrays.asList(packages));
        return newModule(loader, descriptor, pkgs);
    }

    /**
     * Update a module to export a package.
     *
     * @param m1 the module that exports the package
     * @param pkg the package to export
     * @param m2 the module to export the package, when {@code null} then
     *           the package is exported all modules that read m1.
     */
    public static void addModuleExports(Module m1, String pkg, Module m2) {
        SharedSecrets.getJavaLangReflectAccess().addExports(m1, pkg, m2);
    }

    /**
     * Adds a read-edge so that module {@code m1} reads module {@code m1}.
     */
    public static void addReadsModule(Module m1, Module m2) {
        SharedSecrets.getJavaLangReflectAccess().addReadsModule(m1, m2);
    }

    /**
     * Adds a package to a module's content.
     *
     * This method is a no-op if the module already contains the package.
     */
    public static void addModulePackage(Module m, String pkg) {
        SharedSecrets.getJavaLangReflectAccess().addPackage(m, pkg);
    }

    /**
     * Returns {@ocde true} if module {@code m1} reads module {@code m2}.
     *
     * @deprecated Use Module.canRead instead.
     */
    @Deprecated
    public static boolean canReadModule(Module m1, Module m2) {
        return m1.canRead(m2);
    }

    /**
     * Returns {@code true} if module {@code m1} exports package {@code pkg}
     * to module {@code m1}. If {@code m2} is {@code null} then returns
     * {@code true} if pkg is exported to all modules that read m1.
     */
    public static boolean isExportedToModule(Module m1, String pkg, Module m2) {
        return SharedSecrets.getJavaLangReflectAccess().isExported(m1, pkg, m2);
    }
}
