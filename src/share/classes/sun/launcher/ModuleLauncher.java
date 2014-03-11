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

package sun.launcher;

import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import jdk.jigsaw.module.Module;
import jdk.jigsaw.module.ModuleLibrary;
import jdk.jigsaw.module.Resolution;
import jdk.jigsaw.module.SimpleResolver;
import jdk.jigsaw.module.View;

import sun.misc.JavaLangAccess;
import sun.misc.JavaLangReflectAccess;
import sun.misc.Launcher;
import sun.misc.ModulePath;
import sun.misc.SharedSecrets;
import sun.misc.VM;
import sun.reflect.ModuleCatalog;
import sun.reflect.Reflection;

/**
 * Used at startup to resolve the set of readable modules, which may include both
 * JDK modules and modules on the modulepath. Defines each of the modules to the
 * VM so that accessibility can be checked at runtime.
 */
class ModuleLauncher {
    private ModuleLauncher() { }

    /**
     * A module library for the JDK modules.
     */
    private static class JdkModuleLibrary extends ModuleLibrary {
        private final Set<Module> modules = new HashSet<>();
        private final Map<String, Module> namesToModules = new HashMap<>();

        JdkModuleLibrary(Module... mods) {
            for (Module m: mods) {
                modules.add(m);
                namesToModules.put(name(m), m);
            }
        }

        @Override
        public Module findLocalModule(String name) {
            return namesToModules.get(name);
        }

        @Override
        public Set<Module> localModules() {
            return Collections.unmodifiableSet(modules);
        }
    }

    /**
     * Initialize the runtime for modules.
     *
     * @param jdkModules the JDK modules
     * @param roots the initial module(s), specified via the -mods option for now
     * @param verbose true for tracing
     */
    static void init(Module[] jdkModules, Set<String> roots, boolean verbose) {

        // create a module library that the resolver uses to locate modules.
        // If -modulepath is specified then create a ModulePath that delegates
        // to the JDK module library
        ModuleLibrary systemLibrary = new JdkModuleLibrary(jdkModules);
        ModuleLibrary library;
        String mp = System.getProperty("java.module.path");
        if (mp != null) {
            library = new ModulePath(mp, systemLibrary);

            // If -mods is not specified then all add modules on the module path
            // to the root set. This is temporary until we know whether the main
            // class is in a named or unnamed module.
            if (roots.size() == 1 && roots.contains("jdk")) {
                library.localModules().forEach(m -> roots.add(name(m)));
            }
        } else {
            library = systemLibrary;
        }

        // resolve the required modules
        SimpleResolver resolver = new SimpleResolver(library);
        Resolution resolution = resolver.resolve(roots);
        if (verbose) {
            Set<Module> sorted = new TreeSet<>(resolution.selectedModules());
            sorted.forEach(m -> System.out.println(m.id().name()));
        }

        // assign "system" modules to class loaders
        Map<Module, ClassLoader> moduleToLoaders = new HashMap<>();
        Launcher launcher = Launcher.getLauncher();
        ClassLoader extClassLoader = launcher.getExtClassLoader();
        for (String name: launcher.getExtModuleNames()) {
            Module m = systemLibrary.findModule(name);
            if (m != null) {
                moduleToLoaders.put(m, extClassLoader);
            }
        }
        ClassLoader appClassLoader = launcher.getClassLoader();
        for (String name: launcher.getAppModuleNames()) {
            Module m = systemLibrary.findModule(name);
            if (m != null)
                moduleToLoaders.put(m, appClassLoader);
        }

        // assign selected modules on the module path to the application
        // class loader.
        Set<Module> systemModules = systemLibrary.localModules();
        for (Module m: resolution.selectedModules()) {
            if (!systemModules.contains(m)) {
                moduleToLoaders.put(m, appClassLoader);

                // add to the application class loader so that classes
                // and resources can be loaded
                URL url = ((ModulePath)library).toURL(m);
                launcher.addAppClassLoaderURL(url);
            }
        }

        // setup the VM access control
        initAccessControl(systemLibrary, library ,resolution, moduleToLoaders);

        // setup the reflection machinery
        initReflection(systemLibrary, resolution, moduleToLoaders);

        if (System.getProperty("sun.reflect.enableModuleChecks") != null) {
            Reflection.enableModuleChecks();
        }
    }

    /**
     * Setup the VM access control
     */
    private static void initAccessControl(ModuleLibrary systemLibrary,
                                          ModuleLibrary library,
                                          Resolution resolution,
                                          Map<Module, ClassLoader> moduleToLoaders) {

        // used to map modules to their opaque handles
        Map<Module, Long> moduleToHandle = new HashMap<>();

        // define JDK modules to VM
        //
        // Note that we define all the JDK modules, even if they aren't
        // selected by the resolver. This is because they are installed
        // in the JDK image and therefore observable. The JDK modules
        // that aren't selected will not have their exports setup and
        // so none of the types in these modules will be accessible.
        for (Module m: systemLibrary.localModules()) {
            long handle = VM.defineModule(name(m));
            moduleToHandle.put(m, handle);
            ClassLoader loader = moduleToLoaders.get(m);
            for (String pkg: m.packages()) {
                if (pkg != null) {
                    VM.bindToModule(loader, pkg, handle);
                }
            }
        }

        // define the selected modules on module path
        Set<Module> installed = systemLibrary.localModules();
        for (Module m: resolution.selectedModules()) {
            if (!installed.contains(m)) {
                long handle = VM.defineModule(name(m));
                moduleToHandle.put(m, handle);
                ClassLoader loader = moduleToLoaders.get(m);
                for (String pkg: m.packages()) {
                    if (pkg != null) {
                        VM.bindToModule(loader, pkg, handle);
                    }
                }
            }

        }

        // setup the requires (resolved dependences)
        for (Map.Entry<Module, Set<String>> entry:
                resolution.resolvedDependences().entrySet()) {
            Module m1 = entry.getKey();
            Set<String> dependences = entry.getValue();  // null when java.base
            if (dependences != null) {
                for (String name: dependences) {
                    Module m2 = library.findModule(name);
                    VM.addRequires(moduleToHandle.get(m1),
                                   moduleToHandle.get(m2));
                }
            }
        }

        // setup the exports
        for (Module m: resolution.selectedModules()) {
            long fromHandle  = moduleToHandle.get(m);
            for (View v: m.views()) {
                if (v.permits().isEmpty()) {
                    for (String pkg: v.exports()) {
                        VM.addExports(fromHandle, pkg);
                    }
                } else for (String pkg: v.exports()) {
                    // qualified exports
                    for (String who: v.permits()) {
                        Module m2 = library.findModule(who);
                        if (m2 != null) {
                            long toHandle = moduleToHandle.get(m2);
                            VM.addExportsWithPermits(fromHandle, pkg, toHandle);
                        }
                    }
                }
            }
        }
    }

    /**
     * Setup the reflection machinery.
     *
     * @implNote We can remove this once we have a more efficient way to read the
     * reflective information from the VM.
     */
    private static void initReflection(ModuleLibrary systemLibrary,
                                       Resolution resolution,
                                       Map<Module, ClassLoader> moduleToLoaders) {

        JavaLangAccess langAccess = SharedSecrets.getJavaLangAccess();
        JavaLangReflectAccess reflectAccess = SharedSecrets.getJavaLangReflectAccess();

        // used to map a module name to a java.lang.reflect.Module
        Map<String, java.lang.reflect.Module> nameToReflectModule = new HashMap<>();

        // all modules in the "system module library"
        for (Module m: systemLibrary.localModules()) {
            ClassLoader loader = moduleToLoaders.get(m);
            nameToReflectModule.put(name(m),  defineReflectModule(langAccess, loader, m));
        }

        // selected modules on the module path
        Set<Module> installed = systemLibrary.localModules();
        for (Module m: resolution.selectedModules()) {
            if (!installed.contains(m)) {
                ClassLoader loader = moduleToLoaders.get(m);
                assert loader == Launcher.getLauncher().getClassLoader();
                nameToReflectModule.put(name(m), defineReflectModule(langAccess, loader, m));
            }
        }

        // setup requires and exports
        for (Module m: resolution.selectedModules()) {
            java.lang.reflect.Module reflectModule = nameToReflectModule.get(name(m));

            // requires
            Set<String> requires = resolution.resolvedDependences().get(m);
            if (requires != null) {
                for (String dn: requires) {
                    reflectAccess.addRequires(reflectModule, nameToReflectModule.get(dn));
                }
            }

            // exports
            for (View v: m.views()) {
                for (String pkg: v.exports()) {
                    Set<java.lang.reflect.Module> who =
                        v.permits()
                         .stream()
                         .map(nameToReflectModule::get)
                         .collect(Collectors.toSet());
                    reflectAccess.addExport(reflectModule, pkg, who);
                }
            }
        }
    }

    /**
     * Returns a module name (will go away when views are removed)
     */
    private static String name(Module m) {
        return m.mainView().id().name();
    }

    /**
     * Defines a new java.lang.reflect.Module for the given Module and
     * assoicated with the given class loader.
     */
    private static java.lang.reflect.Module defineReflectModule(JavaLangAccess langAccess,
                                                                ClassLoader loader,
                                                                Module m) {
        ModuleCatalog catalog;
        if (loader == null) {
            catalog = ModuleCatalog.getSystemModuleCatalog();
        } else {
            catalog = langAccess.getModuleCatalog(loader);
        }
        String name = m.mainView().id().name();
        return catalog.defineModule(name, m.packages());
    }
}
