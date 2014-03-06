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

import java.util.*;
import java.net.URL;

import jdk.jigsaw.module.Module;
import jdk.jigsaw.module.ModuleLibrary;
import jdk.jigsaw.module.Resolution;
import jdk.jigsaw.module.SimpleResolver;
import jdk.jigsaw.module.View;

import sun.misc.Launcher;
import sun.misc.ModulePath;
import sun.misc.VM;

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
                namesToModules.put(m.mainView().id().name(), m);
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

        // -- setup the access control --

        // assign modules to loaders
        Map<Module, ClassLoader> moduleToLoaders = new HashMap<>();
        Launcher launcher = Launcher.getLauncher();
        ClassLoader extcl = launcher.getExtClassLoader();
        for (String name: launcher.getExtModuleNames()) {
            Module m = systemLibrary.findModule(name);
            if (m != null) {
                moduleToLoaders.put(m, extcl);
            }
        }
        final ClassLoader cl = launcher.getClassLoader();
        for (String name: launcher.getAppModuleNames()) {
            Module m = systemLibrary.findModule(name);
            if (m != null)
                moduleToLoaders.put(m, cl);
        }

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
            long handle = VM.defineModule(m.mainView().id().name());
            moduleToHandle.put(m, handle);
            ClassLoader loader = moduleToLoaders.get(m);
            for (String pkg: m.packages()) {
                if (pkg != null) {
                    VM.bindToModule(loader, pkg, handle);
                }
            }
        }

        // define modules on module path that are selected
        Set<Module> installed = systemLibrary.localModules();
        for (Module m: resolution.selectedModules()) {
            if (!installed.contains(m)) {
                long handle = VM.defineModule(m.mainView().id().name());
                moduleToHandle.put(m, handle);
                for (String pkg: m.packages()) {
                    if (pkg != null) {
                        VM.bindToModule(cl, pkg, handle);
                    }
                }

                // add to the application class loader so that classes
                // and resources can be loaded
                URL url = ((ModulePath)library).toURL(m);
                launcher.addAppClassLoaderURL(url);
            }

        }


        // setup the requires used the resolved dependences
        for (Map.Entry<Module, Set<String>> entry:
                resolution.resolvedDependences().entrySet()) {
            Module m1 = entry.getKey();
            Set<String> dependences = entry.getValue();
            if (dependences != null) {
                for (String name: dependences) {
                    Module m2 = library.findModule(name);
                    VM.addRequires(moduleToHandle.get(m1),
                                   moduleToHandle.get(m2));
                }
            }
        }

        // setup the exports (selected modules only)
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
}


