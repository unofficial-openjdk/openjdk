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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jdk.jigsaw.module.Configuration;
import jdk.jigsaw.module.Layer;
import jdk.jigsaw.module.ModuleArtifact;
import jdk.jigsaw.module.ModuleArtifactFinder;
import jdk.jigsaw.module.ModuleDescriptor;
import jdk.jigsaw.module.ModuleId;
import sun.misc.Launcher;
import sun.misc.ModuleLoader;
import sun.reflect.Reflection;

/**
 * Helper class used by the VM/runtime to initialize the module system.
 *
 * In summary, creates a Configuration by resolving a set of module names
 * specified via the launcher (or equivalent) -m and -addmods options. The
 * Configuration is then used to define the selected modules to runtime
 * to create the boot Layer.
 */
class ModuleBootstrap {
    private ModuleBootstrap() { }

    /**
     * Invoked by the VM at startup to initialize the module system.
     */
    static void boot() {

        // system module path, aka the installed modules
        ModuleArtifactFinder systemModulePath = ModuleArtifactFinder.installedModules();

        // -modulepath specified to the launcher
        ModuleArtifactFinder appModulePath = null;
        String propValue = System.getProperty("java.module.path");
        if (propValue != null) {
            String[] dirs = propValue.split(File.pathSeparator);
            Path[] paths = new Path[dirs.length];
            int i = 0;
            for (String dir: dirs) {
                paths[i++] = Paths.get(dir);
            }
            appModulePath = ModuleArtifactFinder.ofDirectories(paths);
        }

        // The module finder. For now this is the system module path followed
        // by the application module path (if specified). In the future then
        // an upgrade module path may need to be prepended.
        ModuleArtifactFinder finder;
        if (appModulePath != null) {
            finder = ModuleArtifactFinder.concat(systemModulePath, appModulePath);
        } else {
            finder = systemModulePath;
        }

        // if -XX:AddModuleRequires or -XX:AddModuleExports is specified then
        // interpose on finder so that the requires/exports are updated
        String moreRequires = System.getProperty("jdk.runtime.addModuleRequires");
        String moreExports = System.getProperty("jdk.runtime.addModuleExports");
        if (moreRequires != null || moreExports != null) {
            finder = ArtifactInterposer.interpose(finder, moreRequires, moreExports);
        }

        // launcher -m option to specify the initial module
        ModuleId mainMid = null;
        propValue = System.getProperty("java.module.main");
        if (propValue != null) {
            int i = propValue.indexOf('/');
            String s = (i == -1) ? propValue : propValue.substring(0, i);
            mainMid = ModuleId.parse(s);
        }

        // additional module(s) specified by -addmods
        Set<String> additionalMods = null;
        propValue = System.getProperty("jdk.launcher.addmods");
        if (propValue != null) {
            additionalMods = new HashSet<>();
            for (String mod: propValue.split(",")) {
                additionalMods.add(mod);
            }
        }

        // -limitmods
        propValue = System.getProperty("jdk.launcher.limitmods");
        if (propValue != null) {
            Set<String> mods = new HashSet<>();
            for (String mod: propValue.split(",")) {
                mods.add(mod);
            }
            if (mainMid != null)
                mods.add(mainMid.name());
            finder = limitFinder(finder, mods);
        }

        // If the class path is set then assume the unnamed module is observable.
        // We implement this here by putting the names of all observable (named)
        // modules into the set of modules to resolve.
        Set<String> input = Collections.emptySet();
        String cp = System.getProperty("java.class.path");
        if (mainMid == null || (cp != null && cp.length() > 0)) {
            input = finder.allModules()
                          .stream()
                          .map(md -> md.descriptor().name())
                          .collect(Collectors.toSet());
        }

        // If -m or -addmods is specified then these module names must be resolved
        if (mainMid != null || additionalMods != null) {
            input = new HashSet<>(input);
            if (mainMid != null)
                input.add(mainMid.name());
            if (additionalMods != null)
                input.addAll(additionalMods);
        }

        // run the resolver to create the configuration
        Configuration cf = Configuration.resolve(finder,
                                                 Layer.emptyLayer(),
                                                 ModuleArtifactFinder.nullFinder(),
                                                 input).bind();

        // mapping of modules to class loaders
        Layer.ClassLoaderFinder clf = classLoaderFinder(cf);

        // define modules to VM/runtime
        Layer bootLayer = Layer.create(cf, clf);

        // define modules to class loaders
        String overrideDirectory = System.getProperty("jdk.runtime.override");
        defineModulesToClassLoaders(cf, clf, overrideDirectory);

        // reflection checks enabled?
        String s = System.getProperty("sun.reflect.enableModuleChecks");
        boolean enableModuleChecks = (s == null) || !s.equals("false");
        boolean debugging = enableModuleChecks && "debug".equals(s);
        Reflection.enableModules(enableModuleChecks, debugging);

        // set system module graph so that other module graphs can be composed
        Layer.setBootLayer(bootLayer);

        // launcher -verbose:mods option
        if (Boolean.parseBoolean(System.getProperty("jdk.launcher.modules.verbose"))) {
            cf.descriptors().stream()
                            .sorted()
                            .forEach(md -> System.out.println(md.name()));
        }
    }

    /**
     * Returns a ModuleArtifactFinder that locates modules via the given
     * ModuleArtifactFinder but limits what can be found to the given
     * modules and their transitive dependences.
     */
    private static ModuleArtifactFinder limitFinder(ModuleArtifactFinder finder,
                                                    Set<String> mods)
    {
        Configuration cf = Configuration.resolve(finder,
                                                 Layer.emptyLayer(),
                                                 ModuleArtifactFinder.nullFinder(),
                                                 mods);

        // module name -> artifact
        Map<String, ModuleArtifact> map = new HashMap<>();
        cf.descriptors().forEach(md -> {
            String name = md.name();
            map.put(name, finder.find(name));
        });

        Set<ModuleArtifact> artifacts = new HashSet<>(map.values());

        return new ModuleArtifactFinder() {
            @Override
            public ModuleArtifact find(String name) {
                return map.get(name);
            }
            @Override
            public Set<ModuleArtifact> allModules() {
                return artifacts;
            }
        };
    }

    /**
     * Returns the ClassLoaderFinder that maps modules in the given
     * Configuration to a ClassLoader.
     */
    private static Layer.ClassLoaderFinder classLoaderFinder(Configuration cf) {
        Set<String> bootModules = readModuleSet("boot.modules");
        Set<String> extModules = readModuleSet("ext.modules");

        ClassLoader extClassLoader = Launcher.getLauncher().getExtClassLoader();
        ClassLoader appClassLoader = Launcher.getLauncher().getAppClassLoader();

        Map<ModuleArtifact, ClassLoader> map = new HashMap<>();
        cf.descriptors()
          .stream()
          .map(ModuleDescriptor::name)
          .filter(name -> !bootModules.contains(name))
          .forEach(name -> {
              ClassLoader cl = extModules.contains(name) ? extClassLoader : appClassLoader;
              map.put(cf.findArtifact(name), cl);
          });
        return map::get;
    }

    /**
     * Defines the modules in the given Configuration to their
     * respective ClassLoader.
     */
    private static void defineModulesToClassLoaders(Configuration cf,
                                                    Layer.ClassLoaderFinder clf,
                                                    String overrideDirectory)
    {
        for (ModuleDescriptor md: cf.descriptors()) {
            String name = md.name();
            ModuleArtifact artifact = cf.findArtifact(name);
            ClassLoader cl = clf.loaderForModule(artifact);
            if (cl == null) {
                // TBD: define modules to boot loader for the purposes
                // of resource loading
            } else {
                ((ModuleLoader)cl).defineModule(artifact, overrideDirectory);
            }
        }
    }

    /**
     * Reads the contents of the given modules file in {@code ${java.home}/lib}.
     */
    private static Set<String> readModuleSet(String name) {
        Path file = Paths.get(System.getProperty("java.home"), "lib", name);
        try (Stream<String> stream = Files.lines(file)) {
            return stream.collect(Collectors.toSet());
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }
}
