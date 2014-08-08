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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jdk.jigsaw.module.Configuration;
import jdk.jigsaw.module.Layer;
import jdk.jigsaw.module.ModuleArtifact;
import jdk.jigsaw.module.ModuleArtifactFinder;
import jdk.jigsaw.module.ModuleDescriptor;
import jdk.jigsaw.module.ModuleId;

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
        ModuleArtifactFinder systemLibrary = ModuleArtifactFinder.installedModules();

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
        ModuleArtifactFinder launcherModulePath;
        propValue = System.getProperty("java.module.path");
        if (propValue != null) {
            String[] dirs = propValue.split(File.pathSeparator);
            Path[] paths = new Path[dirs.length];
            int i = 0;
            for (String dir: dirs) {
                paths[i++] = Paths.get(dir);
            }
            launcherModulePath = ModuleArtifactFinder.ofDirectories(paths);
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
                                 .map(md -> md.descriptor().name())
                                 .collect(Collectors.toSet());
        } else {
            input = new HashSet<>(mods);
            if (mainMid != null)
                input.add(mainMid.name());
        }

        // run the resolver to create the configuration
        ModuleArtifactFinder finder;
        if (launcherModulePath != null) {
            finder = ModuleArtifactFinder.concat(systemLibrary, launcherModulePath);
        } else {
            finder = systemLibrary;
        }
        Configuration cf = Configuration.resolve(finder,
                                                 Layer.emptyLayer(),
                                                 ModuleArtifactFinder.nullFinder(),
                                                 input).bind();
        if (verbose) {
            cf.descriptors().stream()
                            .sorted()
                            .forEach(md -> System.out.println(md.name()));
        }

        // If -m was specified as name@version then check that the right version
        // of the initial module was selected
        if (mainMid != null && mainMid.version() != null) {
            ModuleArtifact artifact = cf.findArtifact(mainMid.name());
            ModuleId id = artifact.descriptor().id();
            if (!id.equals(mainMid)) {
                throw new RuntimeException(id + " found first on module-path");
            }
        }


        // setup module to ClassLoader mapping
        Map<ModuleArtifact, ClassLoader> moduleToLoaders = loaderMap(systemLibrary);
        if (launcherModulePath != null) {
            Set<ModuleArtifact> systemModules = systemLibrary.allModules();
            Launcher launcher = Launcher.getLauncher();
            cf.descriptors().stream()
                            .map(md -> cf.findArtifact(md.name()))
                            .filter(artifact -> !systemModules.contains(artifact))
                            .forEach(artifact -> {
                                moduleToLoaders.put(artifact, launcher.getClassLoader());
                                launcher.addAppClassLoaderURL(artifact.location());
                            });
        }

        // define to runtime
        Layer bootLayer = Layer.create(cf, moduleToLoaders::get);

        // if -mods or -m is specified then we have to hide the linked modules
        // that are not selected. For now we just define the modules without
        // any readability relationship or exports. Yes, this is a hack.
        if (mainMid != null || !mods.isEmpty()) {
            Set<ModuleDescriptor> selected = cf.descriptors();
            systemLibrary.allModules()
                         .stream()
                         .filter(md -> !selected.contains(md))
                         .forEach(md -> defineProtoModule(md, moduleToLoaders.get(md)));
        }

        // reflection checks enabled?
        String s = System.getProperty("sun.reflect.enableModuleChecks");
        boolean enableModuleChecks = (s == null) || !s.equals("false");
        boolean debugging = enableModuleChecks && "debug".equals(s);
        Reflection.enableModules(enableModuleChecks, debugging);

        // set system module graph so that other module graphs can be composed
        Layer.setBootLayer(bootLayer);
    }

    /**
     * Returns a map of module to class loader.
     */
    private static Map<ModuleArtifact, ClassLoader> loaderMap(ModuleArtifactFinder systemLibrary) {
        Map<ModuleArtifact, ClassLoader> moduleToLoaders = new HashMap<>();
        Launcher launcher = Launcher.getLauncher();
        ClassLoader extClassLoader = launcher.getExtClassLoader();
        for (String name: launcher.getExtModuleNames()) {
            ModuleArtifact artifact = systemLibrary.find(name);
            if (artifact != null) {
                moduleToLoaders.put(artifact, extClassLoader);
            }
        }
        ClassLoader appClassLoader = launcher.getClassLoader();
        for (String name: launcher.getAppModuleNames()) {
            ModuleArtifact artifact = systemLibrary.find(name);
            if (artifact != null)
                moduleToLoaders.put(artifact, appClassLoader);
        }
        return moduleToLoaders;
    }

    /**
     * Defines the given module to the VM in "proto form". Proto form is the
     * module defined to the runtime without any readability relationships and
     * without exports. This is used by the launcher -mods option for testing
     * purposes.
     */
    private static void defineProtoModule(ModuleArtifact artifact, ClassLoader loader) {
        sun.misc.VM.defineModule(artifact.descriptor().name(),
                                 loader,
                                 artifact.packages().toArray(new String[0]));
    }
}
