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

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import jdk.jigsaw.module.Module;
import jdk.jigsaw.module.ModuleExport;
import jdk.jigsaw.module.ModuleGraph;
import jdk.jigsaw.module.ModulePath;
import jdk.jigsaw.module.ServiceDependence;
import jdk.jigsaw.module.SimpleResolver;

import sun.misc.JavaLangAccess;
import sun.misc.JavaLangReflectAccess;
import sun.misc.Launcher;
import sun.misc.SharedSecrets;
import sun.misc.VM;
import sun.reflect.ModuleCatalog;
import sun.reflect.Reflection;

/**
 * Used at startup to run the resolver and generate the module graph. Modules
 * are installed in the runtime image or located via the module path specified
 * to the launcher. The readbility graph is used to define the modules to the
 * VM so that accessibility can be checked at runtime. The same graph is used
 * to setup the Core Reflection mechanism.
 */
class ModuleLauncher {
    private ModuleLauncher() { }

    /**
     * Initialize the runtime for modules.
     *
     * @param jdkModules the JDK modules
     * @param roots the initial module(s), specified via the -mods option for now
     * @param verbose true for tracing
     */
    static void init(Module[] jdkModules, Set<String> roots, boolean verbose) {

        // create a module path that the resolver uses to locate modules.
        // If -modulepath is specified then create a ModulePath that prepends
        // the module path specified to the launcher.
        ModulePath systemLibrary = ModulePath.installed(jdkModules);
        ModulePath modulePath;
        String mp = System.getProperty("java.module.path");
        if (mp != null) {
            modulePath = ModulePath.fromPath(mp, systemLibrary);
        } else {
            modulePath = systemLibrary;
        }

        // If -mods is not specified then add all modules to the root set.
        // This is temporary until we know whether the main class is in a named
        // or unnamed module.
        if (roots.isEmpty()) {
            roots = modulePath.allModules()
                              .stream()
                              .filter(m -> m.permits().isEmpty())
                              .map(m -> name(m)).collect(Collectors.toSet());
        }

        // resolve the required modules
        SimpleResolver resolver = new SimpleResolver(modulePath);
        ModuleGraph graph = resolver.resolve(roots);
        if (verbose) {
            Set<Module> sorted = new TreeSet<>(graph.modules());
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
        for (Module m: graph.modules()) {
            if (!systemModules.contains(m)) {
                moduleToLoaders.put(m, appClassLoader);

                // add to the application class loader so that classes
                // and resources can be loaded
                URL url = modulePath.localLocationOf(m);

                // check url is not null?

                launcher.addAppClassLoaderURL(url);
            }
        }

        // setup the VM access control
        initAccessControl(modulePath, graph, moduleToLoaders);

        // setup the reflection machinery
        initReflection(modulePath, graph, moduleToLoaders);

        // set system module graph so that other module graphs can be composed
        ModuleGraph.setSystemModuleGraph(graph);
    }

    /**
     * Setup the VM access control
     */
    private static void initAccessControl(ModulePath systemLibrary,
                                          ModuleGraph graph,
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
        for (Module m: graph.modules()) {
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

        // setup the readability relationships
        for (Module m1: graph.modules()) {
            graph.readDependences(m1).forEach(m2 -> {
                VM.addRequires(moduleToHandle.get(m1), moduleToHandle.get(m2));
            });
        }

        // setup the exports
        for (Module m: graph.modules()) {
            long fromHandle  = moduleToHandle.get(m);
            for (ModuleExport export: m.exports()) {
                String pkg = export.pkg();
                String who = export.permit();
                if (who == null) {
                    VM.addExports(fromHandle, export.pkg());
                } else {
                    Module m2 = graph.modulePath().findModule(who);
                    if (m2 != null) {
                        long toHandle = moduleToHandle.get(m2);
                        VM.addExportsWithPermits(fromHandle, export.pkg(), toHandle);
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
    private static void initReflection(ModulePath systemLibrary,
                                       ModuleGraph graph,
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
        for (Module m: graph.modules()) {
            if (!installed.contains(m)) {
                ClassLoader loader = moduleToLoaders.get(m);
                assert loader == Launcher.getLauncher().getClassLoader();
                nameToReflectModule.put(name(m), defineReflectModule(langAccess, loader, m));
            }
        }

        // setup requires, exports and services
        for (Module m: graph.modules()) {
            java.lang.reflect.Module reflectModule = nameToReflectModule.get(name(m));

            // setup the readability relationships
            graph.readDependences(m).forEach(m2 -> {
                String name = name(m2);
                reflectAccess.addRequires(reflectModule, nameToReflectModule.get(name));
            });

            // exports
            m.exports().forEach(e ->
                reflectAccess.addExport(reflectModule,
                                        e.pkg(),
                                        nameToReflectModule.get(e.permit())));

            // services used
            Set<ServiceDependence> sd = m.serviceDependences();
            if (!sd.isEmpty()) {
                sd.stream()
                  .map(ServiceDependence::service)
                  .forEach(sn -> reflectAccess.addUses(reflectModule, sn));
            }

            // services provided
            reflectAccess.addProvides(reflectModule, m.services());
        }

        // reflection checks enabled?
        String s = System.getProperty("sun.reflect.enableModuleChecks");
        boolean enableModuleChecks = (s == null) || !s.equals("false");
        boolean debugging = enableModuleChecks && "debug".equals(s);
        Reflection.enableModules(enableModuleChecks, debugging);
    }

    /**
     * Returns a module name
     */
    private static String name(Module m) {
        return m.id().name();
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
        String name = m.id().name();
        return catalog.defineModule(name, m.packages());
    }
}
