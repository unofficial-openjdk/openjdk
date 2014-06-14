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
import java.util.stream.Collectors;

import jdk.jigsaw.module.Module;
import jdk.jigsaw.module.ModuleGraph;
import jdk.jigsaw.module.ModuleId;
import jdk.jigsaw.module.ModulePath;
import jdk.jigsaw.module.ModuleRuntime;
import jdk.jigsaw.module.SimpleResolver;

import sun.misc.Launcher;
import sun.reflect.Reflection;

/**
 * Used at startup to run the resolver and generate the module graph. Modules
 * are linked into the runtime image or located via the module path specified
 * to the launcher. The readability graph is used to define the modules so that
 * accessibility can be checked at runtime.
 */
class ModuleLauncher {
    private ModuleLauncher() { }

    /**
     * Initialize the runtime for modules.
     *
     * @param linkedModules the modules linked into the runtime image
     * @param mods the initial module(s), specified via the -mods option for now
     * @param verbose true for tracing
     */
    static void init(Module[] linkedModules, Set<String> mods, boolean verbose) {
        ModulePath systemLibrary = ModulePath.installed(linkedModules);

        // If -mods is not specified then add all linked in modules without
        // permits to the root set
        Set<String> roots;
        if (mods.isEmpty()) {
            roots = systemLibrary.allModules()
                                 .stream()
                                 .filter(m -> m.permits().isEmpty())
                                 .map(m -> m.id().name())
                                 .collect(Collectors.toSet());
        } else {
            roots = mods;
        }

        // run the resolver
        ModuleGraph graph = new SimpleResolver(systemLibrary).resolve(roots);
        if (verbose) {
            graph.modules().stream()
                           .sorted()
                           .forEach(m -> System.out.println(m.id()));
        }

        // assign linked modules to class loaders and define the selected
        // modules to the runtime.
        Map<Module, ClassLoader> moduleToLoaders = loaderMap(systemLibrary);
        ModuleRuntime.defineModules(graph, moduleToLoaders::get);

        // if -mods is specified then we have to hide the linked modules
        // that are not selected. For now we just define the modules without
        // any readability relationship or exports. Yes, this is a hack.
        if (!mods.isEmpty()) {
            Set<Module> selected = graph.modules();
            systemLibrary.allModules()
                         .stream()
                         .filter(m -> !selected.contains(m))
                         .forEach(m ->
                             ModuleRuntime.defineProtoModule(m, moduleToLoaders.get(m)));
        }

        // launcher -modulepath option specified
        String mp = System.getProperty("java.module.path");
        if (mp != null) {
            ModulePath modulePath = ModulePath.fromPath(mp);

            // if launcher -m also specified then the module name is the initial module
            String mainModule = System.getProperty("java.module.main");
            ModuleId mainMid = null;
            if (mainModule != null) {
                mainMid = ModuleId.parse(mainModule);
                roots = new HashSet<>();
                roots.add(mainMid.name());
            } else {
                // all modules on the launcher module path are the initial module(s)
                // when -m not specified.
                roots = modulePath.allModules()
                                  .stream()
                                  .filter(m -> m.permits().isEmpty())
                                  .map(m -> m.id().name())
                                  .collect(Collectors.toSet());
            }

            // compose a new module graph over the initial module graph
            graph = new SimpleResolver(graph, modulePath).resolve(roots);

            // if -m specified as name@version then we have to check the right
            // version was selected
            if (mainMid != null && mainMid.version() != null) {
                Module selected = modulePath.findModule(mainMid.name());
                if (!selected.id().equals(mainMid)) {
                    throw new RuntimeException(selected.id() + " found first on module-path");
                }
            }

            // -verbose:mods to trace the selection of the modules on the
            // launcher module path
            ModuleGraph deltaGraph = graph.minusInitialModuleGraph();
            if (verbose) {
                deltaGraph.modules().stream()
                                    .sorted()
                                    .forEach(m -> System.out.println(m.id()));
            }

            // define the newly selected modules to the runtime
            ModuleRuntime.defineModules(graph, m -> Launcher.getLauncher().getClassLoader());

            // make the system class loader aware of the locations
            deltaGraph.modules().stream()
                                .map(modulePath::locationOf)
                                .forEach(Launcher.getLauncher()::addAppClassLoaderURL);
        }

        // reflection checks enabled?
        String s = System.getProperty("sun.reflect.enableModuleChecks");
        boolean enableModuleChecks = (s == null) || !s.equals("false");
        boolean debugging = enableModuleChecks && "debug".equals(s);
        Reflection.enableModules(enableModuleChecks, debugging);

        // set system module graph so that other module graphs can be composed
        ModuleGraph.setSystemModuleGraph(graph);
    }

    /**
     * Returns a map of module to class loader.
     */
    private static Map<Module, ClassLoader> loaderMap(ModulePath systemLibrary) {
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
        return moduleToLoaders;
    }
}
