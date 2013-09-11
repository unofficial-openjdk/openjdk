/*
 * Copyright (c) 2010, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.sun.tools.classanalyzer;

import com.sun.tools.classanalyzer.ClassPath.Archive;
import com.sun.tools.classanalyzer.Module.Factory;
import com.sun.tools.classanalyzer.Service.ProviderConfigFile;
import static com.sun.tools.classanalyzer.Dependence.Identifier.*;

import com.sun.tools.classfile.ClassFile;
import com.sun.tools.classfile.ConstantPoolException;
import com.sun.tools.classfile.Dependencies;
import com.sun.tools.classfile.Dependency;
import com.sun.tools.classfile.Dependency.Location;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

/**
 * Module builder that creates modules as defined in the given
 * module configuration files.  The run() method assigns
 * all classes and resources according to the module definitions.
 * Additional dependency information can be specified e.g.
 * Class.forName, JNI_FindClass, and service providers.
 *
 * @see DependencyConfig
 */
public class ModuleBuilder {
    protected final List<ModuleConfig> mconfigs = new ArrayList<ModuleConfig>();
    protected final String version;
    private final List<Archive> archives;
    private final Map<String, Klass> classes = new HashMap<>();
    private final Map<String, Set<Package>> packages = new HashMap<>();
    private final Map<String, Service> services = new HashMap<>();
    private final Map<Module, Map<String,Dependence>> dependencesForModule = new HashMap<>();
    private final JigsawModules graph = new JigsawModules();
    public ModuleBuilder(List<String> configs,
                         List<Archive> archives,
                         String version) throws IOException {
        if (configs != null) {
            for (String file : configs) {
                mconfigs.addAll(ModuleConfig.readConfigurationFile(file, version));
            }
        }
        this.archives = archives;
        this.version = version;
    }

    public void store(OutputStream out) throws IOException {
        graph.store(out);
    }

    public void printModuleInfos(String minfoDir) throws IOException {
        graph.printModuleInfos(minfoDir);
    }

    /**
     * Returns the module factory.
     */
    protected Factory factory() {
        return Module.getFactory();
    }

    /**
     * This method assigns the classes and resource files
     * to modules and generates the package information and
     * the module information.
     *
     * This method can be overridden in a subclass implementation.
     */
    public void run() throws IOException {
        // assign classes and resource files to the modules and
        // group fine-grained modules per configuration files
        buildModules();

        // build jigsaw modules
        for (Map.Entry<Module, Map<String,Dependence>> e : dependencesForModule.entrySet()) {
            graph.build(e.getKey(), e.getValue().values());
        }
        System.out.format("%d modules %d classes analyzed%n",
                          dependencesForModule.size(), classes.size());
    }

    /**
     * Builds modules from the existing list of classes and resource
     * files according to the module configuration files.
     *
     */
    protected void buildModules() throws IOException {
        // create the modules for the given configs
        factory().init(mconfigs);

        // analyze class dependencies
        findDependencies();

        // group fine-grained modules
        factory().buildModuleMembers();

        // analyze cross-module dependencies
        for (Module m : factory().getAllModules()) {
            if (m.group() == m) {
                // module with no class is not included except the base module
                // or reexporting APIs from required modules
                boolean reexports = false;
                for (Dependence d : m.config().requires().values()) {
                    reexports = reexports || d.requiresPublic();
                }
                if (isBaseModule(m) || !m.isEmpty() || m.allowsEmpty() || reexports) {
                    dependencesForModule.put(m, buildModuleDependences(m));
                }
            }
        }

        // fixup permits after dependences are found
        fixupPermits();
    }

    private Module findModule(String name) {
        Module module = null;
        for (Module m : Module.getFactory().getAllModules()) {
            ModuleConfig config = m.config();
            if (config.matchesIncludes(name) && !config.isExcluded(name)) {
                return m;
            }
        }
        return null;
    }

    private void findDependencies() throws IOException {
        Dependency.Finder finder = Dependencies.getClassDependencyFinder();
        Dependency.Filter filter = new Dependency.Filter() {
            @Override
            public boolean accepts(Dependency dependency) {
                return !dependency.getOrigin().equals(dependency.getTarget());
            }
        };

        // get the immediate dependencies of the input files
        for (Archive a : archives) {
            for (ClassFile cf : a.getClassFiles()) {
                String classFileName;
                try {
                    classFileName = cf.getName();
                } catch (ConstantPoolException e) {
                    throw new Dependencies.ClassFileError(e);
                }

                Klass k = classes.get(classFileName);
                if (k == null) {
                    k = new Klass(classFileName, cf.access_flags, cf.byteLength());
                    String cn = k.getClassName();
                    Module module = getPackage(k).module;
                    module.addKlass(k);
                    classes.put(classFileName, k);
                }
                for (Dependency d : finder.findDependencies(cf)) {
                    if (filter.accepts(d)) {
                        k.reference(d.getTarget());
                    }
                }
            }
        }

        // process resources and service providers
        for (Archive a : archives) {
            for (Resource res : a.getResources()) {
                if (!res.isService()) {
                    Module module = getPackage(res).module;
                    module.addResource(res);
                } else {
                    ProviderConfigFile pcf = ProviderConfigFile.class.cast(res);
                    String name = pcf.getName().replace('.', '/');
                    Klass k = classes.get(name);
                    if (k == null) {
                        System.out.println("Warning: " + name + " not found");
                        continue;
                    }
                    Service s = services.get(k.getClassName());
                    if (s == null) {
                        services.put(k.getClassName(), s = new Service(k));
                        k.getModule().addService(s);
                    }
                    for (String p : pcf.providers) {
                        Klass provider = classes.get(p.replace('.', '/'));
                        if (provider == null) {
                            throw new RuntimeException(s + " provider not exist: " + p);
                        }
                        provider.getModule().addProvider(s, provider);
                    }
                }
            }
        }
    }

    // returns true if the given class is not in module m and
    // not in an exported package of the base module
    private boolean requiresModuleDependence(Module m, Klass k) {
        return m.group() != k.getModule().group();
    }

    private Module.View getRequiresModule(Module m, Klass k) {
        if (m.group() == k.getModule().group())
            return null;

        // Returns true if class k is exported from another module
        // and not from the base's default view
        Module other = k.getModule().group();
        return other.getView(k);
    }

    interface KlassFilter {
        boolean accept(Klass k);
    }

    // returns all classes that are not in the base module
    // referenced by the given class
    public Set<Klass> getDeps(Klass from) {
        return getDeps(from, new KlassFilter() {
            public boolean accept(Klass k) {
                Module m = k.getModule().group();
                return !isBaseModule(m) || !m.getPackage(k.getPackageName()).isExported;
            }
        });
    }

    public Set<Klass> getDeps(Klass from, KlassFilter filter) {
        Set<Klass> deps = new TreeSet<>();
        for (Location target : from.getDeps()) {
            Klass to = classes.get(target.getName());
            if (to == null) {
                System.err.println(target.getName() + " not found");
            } else if (requiresModuleDependence(from.getModule(), to)) {
                if (filter == null || filter.accept(to)) {
                    deps.add(to);
                }
            }
        }
        return deps;
    }

    private Package getPackage(Klass k) {
        String pn = k.getPackageName();
        Package p = getPackage(pn, k.getClassName());
        p.addKlass(k);
        return p;
    }

    private Package getPackage(Resource res) {
        String pn = "";
        int i = res.getName().lastIndexOf('/');
        if (i > 0) {
            pn = res.getName().substring(0, i).replace('/', '.');
        }
        Package p = getPackage(pn, res.getName());
        p.addResource(res);
        return p;
    }

    private Package getPackage(String pn, String name) {
        Module module = findModule(name);
        Package p = module.getPackage(pn);
        if (p == null) {
            Set<Package> pkgs = packages.get(pn);
            if (pkgs == null) {
                packages.put(pn, pkgs = new HashSet<>());
            }
            pkgs.add(p = new Package(pn, module));
            module.addPackage(p);
        }
        return p;
    }

    public Map<String, Set<Module>> getPackages() {
        Map<String, Set<Module>> modulesForPackage = new TreeMap<>();
        for (Module m : dependencesForModule.keySet()) {
            for (Package p : m.packages()) {
                Set<Module> ms = modulesForPackage.get(p.name());
                if (ms == null) {
                    modulesForPackage.put(p.name(), ms = new HashSet<>());
                }
                ms.add(m);
            }
        }
        return modulesForPackage;
    }

    private static List<String> baseModules = Arrays.asList("base", "jdk.base", "java.base");
    private boolean isBaseModule(Module m) {
        return baseModules.contains(m.name());
    }

    private void fixupPermits() {
        // fixup permits after all ModuleInfo are created in two passes:
        // 1. permits the requesting module if it requires local dependence
        // 2. if permits set is non-empty, permits
        //    all of its requesting modules
        for (Map.Entry<Module.View, Set<Module>> e : backedges.entrySet()) {
            Module.View dmv = e.getKey();
            if (dmv.permits().size() > 0) {
                for (Module m : e.getValue()) {
                    dmv.addPermit(m.defaultView().name);
                }
            }
        }
    }

    protected Map<String, Dependence> buildModuleDependences(Module m) {
        Map<String, Dependence> requires = new TreeMap<>();
        for (Map.Entry<String, Dependence> e : m.configRequires().entrySet()) {
            Dependence d = e.getValue();
            if (d.requiresService()) {
                Service s = services.get(e.getKey());
                if (s == null) {
                    // no service provider config file
                    String cf = e.getKey().replace('.', '/');
                    Klass k = classes.get(cf);
                    if (k == null) {
                        throw new RuntimeException("service " + e.getKey() + " not found");
                    }
                    services.put(k.getClassName(), s = new Service(k));
                    k.getModule().addService(s);
                }
                if (requiresModuleDependence(m, s.service)) {
                    addDependence(m, s.service, requires);
                }
                requires.put(e.getKey(), d);
            } else {
                addDependence(m, d, requires);
            }
        }

        // add static dependences
        for (Klass from : m.classes()) {
            for (Klass to : getDeps(from, null)) {
                addDependence(m, to, requires);
            }
        }

        // add dependency due to the main class
        for (Module.View v : m.views()) {
            if (v.mainClassName() != null) {
                Klass k = classes.get(v.mainClassName().replace('.', '/'));
                if (k != null) {
                    v.setMainClass(k);
                    // this main class may possibly be a platform-specific class
                    if (requiresModuleDependence(m, k)) {
                        addDependence(m, k, requires);
                    }
                }
            }
            for (String name : v.permitNames()) {
                Module pm = factory().getModuleForView(name);
                if (pm != null) {
                    v.addPermit(pm.group().defaultView().name);
                    addBackEdge(m, v);
                } else {
                    throw new RuntimeException("module " + name
                            + " specified in the permits rule for " + m.name()
                            + " doesn't exist");
                }
            }
        }

        for (Service s : m.services()) {
            String name = s.service.getClassName();
            requires.put(name, new Dependence(name, EnumSet.of(OPTIONAL, SERVICE)));
        }

        Map<String,Set<String>> providesServices = new HashMap<>();
        for (Map.Entry<Service,Set<Klass>> entry: m.providers().entrySet()) {
            String sn = entry.getKey().service.getClassName();
            Set<String> impls = providesServices.get(sn);
            if (impls == null) {
                providesServices.put(sn, impls = new LinkedHashSet<>());
            }
            // preserve order, assume no dups in input
            for (Klass k : entry.getValue()) {
                impls.add(k.getClassName());
            }
        }
        m.defaultView().addProviders(providesServices);

        return requires;
    }

    // backedges (i.e. reverse dependences)
    private final Map<Module.View, Set<Module>> backedges = new HashMap<>();
    private void addDependence(Module from, Klass k, Map<String, Dependence> requires) {
        Module dm = k.getModule().group();
        Module.View view = dm.getView(k);
        if (view == null)
            throw new RuntimeException("No view exporting " + k);

        addDependence(from, dm, view, requires);
    }

    private void addBackEdge(Module from, Module.View view) {
        Set<Module> refs = backedges.get(view);
        if (refs == null) {
            backedges.put(view, refs = new HashSet<>());
        }
        refs.add(from);
    }
    private void addDependence(Module m, Dependence d, Map<String, Dependence> requires) {
        Module other = factory().getModuleForView(d.name());
        if (other == null) {
            throw new RuntimeException(m.name() + " requires "
                    + d.name() + "not found");
        }

        if (other.isEmpty() && !other.allowsEmpty()) {
            return;
        }
        Module.View view = other.getView(d.name());
        addDependence(m, other, view, requires, d.mods());
    }

    private void addDependence(Module from, Module to, Module.View view,
                               Map<String, Dependence> requires) {
        addDependence(from, to, view, requires, EnumSet.noneOf(Dependence.Identifier.class));
    }

    private void addDependence(Module from, Module to, Module.View view,
                               Map<String, Dependence> requires,
                               Set<Dependence.Identifier> mods) {
        assert from.group() != to.group();
        addBackEdge(from, view);

        if (view == to.internalView()) {
            // if there is an optional dependence on the main view,
            // make this an optional dependence
            Dependence d = requires.get(to.group().name());
            if (d != null && d.requiresOptional() && !mods.contains(OPTIONAL)) {
                mods = new HashSet<>(mods);
                mods.add(OPTIONAL);
            }
            view.addPermit(from.group().name());
        }

        String name = view.name;
        Dependence dep = requires.get(name);
        if (dep == null) {
            requires.put(name, dep = new Dependence(name, mods));
        } else {
            assert dep.name().equals(name);
        }
    }

    public interface Visitor<R, P> {
        R visitModule(Module m) throws IOException;
    }

    public <R, P> void visit(Visitor<R, P> visitor) throws IOException {
        for (Module m : dependencesForModule.keySet()) {
            visitor.visitModule(m);
        }
    }
}
