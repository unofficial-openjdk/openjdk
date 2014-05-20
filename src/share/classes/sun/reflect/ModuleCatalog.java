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

package sun.reflect;

import java.lang.reflect.Module;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import sun.misc.SharedSecrets;

/**
 * A module catalog that supports defining and lookup of modules. Each
 * {@code ClassLoader} has an assoicated {@code ModuleCatalog} for modules
 * that are associated with that class loader.
 *
 * @apiNote The ModuleCatalog for the null class loader is defined here
 * rather than java.lang.ClassLoader to avoid early initialization of
 * CHM.
 */
public class ModuleCatalog {

    // ModuleCatalog for the null class loader
    private static final ModuleCatalog SYSTEM_MODULE_CATALOG = new ModuleCatalog();

    // the unnamed modules, should not be leaked
    public static final Module UNNAMED_MODULE =
        getSystemModuleCatalog().defineModule("<unnamed>", Collections.emptySet());

    // used to ensure that duplicate module or contents are defined
    private final Set<String> moduleNames = new HashSet<>();
    private final Set<String> modulePackages = new HashSet<>();

    // maps package name to module
    private final Map<String, Module> packageToModule = new ConcurrentHashMap<>();

    /**
     * Returns the ModuleCatalog for modules associated with the boot class loader.
     */
    public static ModuleCatalog getSystemModuleCatalog() {
        return SYSTEM_MODULE_CATALOG;
    }

    /**
     * Creates a new module catalog.
     */
    public ModuleCatalog() { }

    /**
     * Defines a new module with the given name and API packages.
     */
    public Module defineModule(String name, Set<String> packages) {
        synchronized (this) {
            if (moduleNames.contains(name))
                throw new InternalError("Module " + name + " already defined");
            for (String pkg: packages) {
                if (modulePackages.contains(pkg))
                    throw new InternalError(pkg + " already defined by another module");
            }
            moduleNames.add(name);
            modulePackages.addAll(packages);
        }

        Module m = SharedSecrets.getJavaLangReflectAccess().defineModule(name, packages);
        packages.forEach(p -> packageToModule.put(p, m));

        return m;
    }

    /**
     * Returns the module that the given API package is defined in, {@code null} if
     * not defined in any module in this catalog.
     */
    public Module getModule(String pkg) {
        return packageToModule.get(pkg);
    }
}
