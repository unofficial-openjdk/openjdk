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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jdk.jigsaw.module.Module;
import jdk.jigsaw.module.ModuleGraph;
import jdk.jigsaw.module.ModuleId;
import jdk.jigsaw.module.ModulePath;
import jdk.jigsaw.module.Resolver;
import jdk.jigsaw.module.internal.ModuleRuntime;

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
     */
    static void init() {

        // Do nothing if running with -XX:-UseModuleBoundaries
        String propValue = System.getProperty("jdk.runtime.useModuleBoundaries");
        if (propValue != null) {
            boolean useModuleBoundaries = Boolean.parseBoolean(propValue);
            if (!useModuleBoundaries)
                return;
        }

        // module path of the installed modules
        ModulePath systemLibrary = ModulePath.installedModules();

        // launcher -verbose:mods option
        boolean verbose =
            Boolean.parseBoolean(System.getProperty("jdk.launcher.modules.verbose"));

        // initial module(s) as specified by -mods
        Set<String> mods = new HashSet<>();
        propValue = System.getProperty("jdk.launcher.modules");
        if (propValue != null) {
            for (String mod: propValue.split(",")) {
                mods.add(mod);
            }
        }

        // launcher -modulepath option
        ModulePath launcherModulePath;
        propValue = System.getProperty("java.module.path");
        if (propValue != null) {
            String[] dirs = propValue.split(File.pathSeparator);
            launcherModulePath = ModulePath.ofDirectories(dirs);
        } else {
            launcherModulePath = null;
        }

        // launcher -m option to specify the initial module
        ModuleId mainMid;
        propValue = System.getProperty("java.module.main");
        if (propValue != null) {
            int i = propValue.indexOf('/');
            String s = (i == -1) ? propValue : propValue.substring(0, i);
            mainMid = ModuleId.parse(s);
        } else {
            mainMid = null;
        }

        // If neither -m nor -mods is specified then the initial module is the
        // set of all installed modules, otherwise the initial module is the
        // union of both -m (if specified) and -mods (if specified).
        Set<String> input;
        if (mainMid == null && mods.isEmpty()) {
            input = systemLibrary.allModules()
                                 .stream()
                                 .filter(m -> m.permits().isEmpty())
                                 .map(m -> m.id().name())
                                 .collect(Collectors.toSet());
        } else {
            input = new HashSet<>(mods);
            if (mainMid != null)
                input.add(mainMid.name());
        }

        // run the resolver
        ModulePath modulePath;
        if (launcherModulePath != null) {
            modulePath = systemLibrary.join(launcherModulePath);
        } else {
            modulePath = systemLibrary;
        }
        ModuleGraph graph = new Resolver(modulePath).resolve(input).bindServices();
        if (verbose) {
            graph.modules().stream()
                           .sorted()
                           .forEach(m -> System.out.println(m.id()));
        }

        // If -m was specified as name@version then check that the right version
        // of the initial module was selected
        if (mainMid != null && mainMid.version() != null) {
            Module selected = graph.findModule(mainMid.name());
            if (!selected.id().equals(mainMid)) {
                throw new RuntimeException(selected.id() + " found first on module-path");
            }
        }

        // setup module to ClassLoader mapping
        Map<Module, ClassLoader> moduleToLoaders = loaderMap(systemLibrary);
        if (launcherModulePath != null) {
            Set<Module> systemModules = systemLibrary.allModules();
            Launcher launcher = Launcher.getLauncher();
            graph.modules().stream().filter(m -> !systemModules.contains(m)).forEach(m -> {
                moduleToLoaders.put(m, launcher.getClassLoader());
                launcher.addAppClassLoaderURL(launcherModulePath.locationOf(m));
            });
        }

        // define to runtime
        Module base = graph.findModule("java.base");
        if (base != null) {
            ModuleRuntime.defineModule(graph, base, null);
            ModuleRuntime.defineModules(graph, moduleToLoaders::get);
        }

        // if -mods or -m is specified then we have to hide the linked modules
        // that are not selected. For now we just define the modules without
        // any readability relationship or exports. Yes, this is a hack.
        if (mainMid != null || !mods.isEmpty()) {
            Set<Module> selected = graph.modules();
            systemLibrary.allModules()
                         .stream()
                         .filter(m -> !selected.contains(m))
                         .forEach(m -> ModuleRuntime.defineProtoModule(m, moduleToLoaders.get(m)));
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
