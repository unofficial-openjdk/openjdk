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

package jdk.jigsaw.module.runtime;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
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
import jdk.jigsaw.module.ModuleId;

import sun.misc.Launcher;
import sun.misc.ModuleLoader;
import sun.reflect.Reflection;

/**
 * Helper class used by the VM/runtime to initialize the module system.
 *
 * In summary, creates a Configuration by resolving a set of module names
 * specified via the launcher (or equivalent) -m and -mods options. The
 * Configuration is then used to define the selected modules to runtime
 * to create the boot Layer.
 */
class ModuleBootstrap {
    private ModuleBootstrap() { }

    /**
     * Invoked by the VM at startup to initialize the module system.
     */
    static void boot() {

        // module path of the installed modules
        ModuleArtifactFinder systemLibrary = ModuleArtifactFinder.installedModules();

        // if -XX:AddModuleRequires or -XX:AddModuleExports is specified then
        // interpose on the system library so that the requires/exports are
        // updated as they modules are found
        String moreRequires = System.getProperty("jdk.runtime.addModuleRequires");
        String moreExports = System.getProperty("jdk.runtime.addModuleExports");
        if (moreRequires != null || moreExports != null) {
            systemLibrary =  ArtifactInterposer.interpose(systemLibrary,
                                                          moreRequires,
                                                          moreExports);
        }

        // launcher -verbose:mods option
        boolean verbose =
            Boolean.parseBoolean(System.getProperty("jdk.launcher.modules.verbose"));

        // initial module(s) as specified by -mods
        Set<String> mods = new HashSet<>();
        String propValue = System.getProperty("jdk.launcher.modules");
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

        // create the module finder
        ModuleArtifactFinder finder;
        if (launcherModulePath != null) {
            finder = ModuleArtifactFinder.concat(systemLibrary, launcherModulePath);
        } else {
            finder = systemLibrary;
        }

        // run the resolver to create the configuration
        Configuration cf = Configuration.resolve(finder,
                                                 Layer.emptyLayer(),
                                                 ModuleArtifactFinder.nullFinder(),
                                                 input).bind();
        if (verbose) {
            cf.descriptors().stream()
                            .sorted()
                            .forEach(md -> System.out.println(md.name()));
        }

        // setup module to ClassLoader mapping
        Map<ModuleArtifact, ClassLoader> moduleToLoaders = loaderMap(systemLibrary);

        // -Xoverride
        String override = System.getProperty("jdk.runtime.override");
        if (override != null) {
            Set<ModuleArtifact> systemModules = systemLibrary.allModules();
            cf.descriptors().stream()
                            .map(md -> cf.findArtifact(md.name()))
                            .filter(systemModules::contains)
                            .forEach(artifact -> {
                                ClassLoader loader = moduleToLoaders.get(artifact);
                                if (loader != null) {
                                    String name = artifact.descriptor().name();
                                    setOverrideDirectory(override, loader, name);
                                }
                            });
        }

        // if -modulepath specified then need to add to system class loader
        if (launcherModulePath != null) {
            Set<ModuleArtifact> systemModules = systemLibrary.allModules();

            ClassLoader cl = ClassLoader.getSystemClassLoader();
            if (!(cl instanceof ModuleLoader))
                throw new Error("System class loader does not support modules");

            ModuleLoader ml = (ModuleLoader) cl;
            cf.descriptors().stream()
                            .map(md -> cf.findArtifact(md.name()))
                            .filter(artifact -> !systemModules.contains(artifact))
                            .forEach(artifact -> {
                                moduleToLoaders.put(artifact, cl);
                                ml.addURL(artifact.packages(), artifact.location());
                            });
        }

        // define to runtime
        Layer bootLayer = Layer.create(cf, moduleToLoaders::get);

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

        ClassLoader extClassLoader = Launcher.getLauncher().getExtClassLoader();
        for (String name: ((ModuleLoader)extClassLoader).installedModules()) {
            ModuleArtifact artifact = systemLibrary.find(name);
            if (artifact != null) {
                moduleToLoaders.put(artifact, extClassLoader);
            }
        }

        // the modules linked into the image that are associated with the system class loader
        // need to be associated with Launcher$AppClassLoader even if the system class loader
        // is overridden
        ClassLoader sysClassLoader = Launcher.getLauncher().getClassLoader();
        for (String name: ((ModuleLoader)sysClassLoader).installedModules()) {
            ModuleArtifact artifact = systemLibrary.find(name);
            if (artifact != null)
                moduleToLoaders.put(artifact, sysClassLoader);
        }

        return moduleToLoaders;
    }

    /**
     * Add an override directory for the given ClassLoader/module-name
     */
    private static void setOverrideDirectory(String override, ClassLoader cl, String name) {
        if (!(cl instanceof ModuleLoader)) {
            // this should not happen as custom system class loaders don't support modules
            throw new Error(cl + " does not support -Xoverride");
        }

        Path dir = Paths.get(override, name);
        if (Files.exists(dir)) {
            try {
                URL url = dir.toUri().toURL();
                ((ModuleLoader)cl).prependURL(url);
            } catch (MalformedURLException e) {
                throw new InternalError(e);
            }
        }
    }
}
