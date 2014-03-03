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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import jdk.jigsaw.module.Module;
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
class ModuleBooter {
    private ModuleBooter() { }

    /**
     * @param jdkModules the JDK modules
     * @param mp the module path
     * @param roots the initial module(s), specified via the -mods option for now
     * @param verbose true for tracing
     */
    static void boot(Module[] jdkModules,
                     ModulePath mp,
                     Set<String> roots,
                     boolean verbose)
    {
        // the complete set of modules includes the JDK modules and any modules
        // on the modulepath
        Set<Module> modules = new HashSet<>();
        for (Module m: jdkModules) {
            modules.add(m);
        }
        if (mp != null) {
            for (Module m: mp.modules()) {
                modules.add(m);

                // for now the -mods options cannot be used to make
                // modules on the modulepath non-readable.
                roots.add(m.id().name());

                if (verbose)
                    System.out.println(m);
            }
        }

        // resolve the required modules
        SimpleResolver resolver = new SimpleResolver(modules);
        Resolution resolution = resolver.resolve(roots);
        if (verbose) {
            Set<Module> sorted = new TreeSet<>(resolution.selectedModules());
            sorted.forEach(m -> System.out.println(m.id().name()));
        }

        // -- setup the access control --

        // Need name to module mapping
        Map<String, Module> names = new HashMap<>();
        for (Module m: modules) {
            names.put(m.mainView().id().name(), m);
        }

        // Need to elide view names (assumes no dups, goes again with Views)
        Map<String, Module> viewToModule = new HashMap<>();
        for (Module m: modules) {
            m.views().forEach( v -> viewToModule.put(v.id().name(), m) );
        }

        // assign modules to loaders
        Map<Module, ClassLoader> moduleToLoaders = new HashMap<>();
        Launcher launcher = Launcher.getLauncher();
        ClassLoader extcl = launcher.getExtClassLoader();
        for (String name: launcher.getExtModuleNames()) {
            Module m = names.get(name);
            if (m != null) {
                moduleToLoaders.put(m, extcl);
            }
        }
        ClassLoader cl = launcher.getClassLoader();
        for (String name: launcher.getAppModuleNames()) {
            Module m = names.get(name);
            if (m != null)
                moduleToLoaders.put(m, cl);
        }

        // define modules in VM
        Map<Module, Long> moduleToHandle = new HashMap<>();
        for (Module m: modules) {
            long handle = VM.defineModule(m.mainView().id().name());
            moduleToHandle.put(m, handle);

            ClassLoader loader = moduleToLoaders.get(m);
            for (String pkg: m.packages()) {
                if (pkg != null) {
                    VM.bindToModule(loader, pkg, handle);
                }
            }
        }

        // setup the requires
        for (Map.Entry<Module, Set<String>> entry:
                resolution.resolvedDependences().entrySet())
        {
            Module m1 = entry.getKey();
            Set<String> dependences = entry.getValue();
            if (dependences != null) {
                for (String name: dependences) {
                    Module m2 = names.get(name);
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
                        Module m2 = names.get(who);
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


