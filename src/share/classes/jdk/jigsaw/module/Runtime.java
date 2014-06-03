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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import sun.misc.JavaLangAccess;
import sun.misc.JavaLangReflectAccess;
import sun.misc.SharedSecrets;
import sun.misc.VM;
import sun.reflect.ModuleCatalog;

/**
 * Module runtime support. This class consists exclusively of static methods that
 * define modules to the runtime.
 */

public final class Runtime {
    private Runtime() { }

    // used to map modules to their VM handles
    private static Map<Module, Long> moduleToHandle = new ConcurrentHashMap<>();

    // used to map a module name to a java.lang.reflect.Module
    private static Map<String, java.lang.reflect.Module> nameToReflectModule =
        new ConcurrentHashMap<>();

    private static JavaLangAccess langAccess = SharedSecrets.getJavaLangAccess();
    private static JavaLangReflectAccess reflectAccess =
        SharedSecrets.getJavaLangReflectAccess();

    /**
     * A pool of class loaders.
     */
    public static interface LoaderPool {
        /**
         * Finds the class loader for the given module.
         */
        ClassLoader findLoader(Module m);
    }

    /**
     * Defines the modules in the given module graph to the VM.
     */
    public static void defineModules(ModuleGraph graph, LoaderPool pool) {
        Set<Module> modules = graph.modules();

        // define modules to VM
        modules.forEach(m -> defineProtoModule(m, pool.findLoader(m)));

        // setup the readability relationships in VM
        for (Module m1: modules) {
            graph.readDependences(m1).forEach(m2 -> {
                VM.addReadsModule(moduleToHandle.get(m1), moduleToHandle.get(m2));
            });
        }

        // setup the exports in VM
        for (Module m: modules) {
            long fromHandle  = moduleToHandle.get(m);
            for (ModuleExport export: m.exports()) {
                String pkg = export.pkg();
                String permit = export.permit();
                if (permit == null) {
                    VM.addExports(fromHandle, export.pkg());
                } else {
                    Module m2 = graph.modulePath().findModule(permit);
                    if (m2 != null) {
                        Long toHandle = moduleToHandle.get(m2);
                        if (toHandle != null)
                            VM.addExportsWithPermits(fromHandle, export.pkg(), toHandle);
                    }
                }
            }
        }

        // define modules for Core Reflection
        for (Module m: modules) {
            ClassLoader loader = pool.findLoader(m);
            nameToReflectModule.put(name(m), defineReflectModule(langAccess, loader, m));
        }

        // setup readability relationships, exports and services
        for (Module m: modules) {
            java.lang.reflect.Module reflectModule = nameToReflectModule.get(name(m));
            if (reflectModule == null)
                throw new InternalError(name(m) + ": no java.lang.reflect.Module ??");

            // setup the readability relationships
            graph.readDependences(m).forEach(m2 -> {
                String name = name(m2);
                java.lang.reflect.Module otherModule = nameToReflectModule.get(name);
                if (reflectModule == null)
                    throw new InternalError(name + ": no java.lang.reflect.Module ??");
                reflectAccess.addReadsModule(reflectModule, otherModule);
            });

            // exports
            for (ModuleExport e: m.exports()) {
                String permit = e.permit();
                java.lang.reflect.Module other;
                if (permit == null) {
                    other = null;
                } else {
                    other = nameToReflectModule.get(permit);
                }
                reflectAccess.addExport(reflectModule, e.pkg(), other);
            }

            // services used
            Set<ServiceDependence> sd = m.serviceDependences();
            if (!sd.isEmpty()) {
                sd.stream().map(ServiceDependence::service)
                           .forEach(sn -> reflectAccess.addUses(reflectModule, sn));
            }

            // services provided
            reflectAccess.addProvides(reflectModule, m.services());
        }
    }

    /**
     * Defines the given module to the VM. The module is associated with the
     * given class loader.
     */
    public static void defineProtoModule(Module m, ClassLoader loader) {
        long handle = VM.defineModule(name(m));
        if (moduleToHandle.putIfAbsent(m, handle) != null)
            throw new InternalError(); // not handled yet
        m.packages().forEach(pkg -> VM.bindToModule(loader, pkg, handle));
    }

    /**
     * Returns a module name
     */
    private static String name(Module m) {
        return m.id().name();
    }

    /**
     * Defines a new java.lang.reflect.Module for the given Module and
     * associate with the given class loader.
     */
    private static java.lang.reflect.Module
        defineReflectModule(JavaLangAccess langAccess, ClassLoader loader, Module m) {

        ModuleCatalog catalog;
        if (loader == null) {
            catalog = ModuleCatalog.getSystemModuleCatalog();
        } else {
            catalog = langAccess.getModuleCatalog(loader);
        }
        String name = m.id().name();
        return catalog.defineModule(name, m.packages());
    }
}
