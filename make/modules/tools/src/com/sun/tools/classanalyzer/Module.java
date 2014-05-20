/*
 * Copyright (c) 2009, 2013, Oracle and/or its affiliates. All rights reserved.
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
 *
 */
package com.sun.tools.classanalyzer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Module contains a list of classes and resources.
 *
 */
public class Module implements Comparable<Module> {
    static Properties moduleProps = new Properties();
    static String getModuleProperty(String key) {
        return getModuleProperty(key, null);
    }
    static String getModuleProperty(String key, String defaultValue) {
        String value = moduleProps.getProperty(key);
        if (value == null)
            return defaultValue;
        else
            return value;
    }

    static void initModuleProperties(InputStream in) throws IOException {
        moduleProps.load(in);
    }

    private final String name;
    private final String version;
    private final ModuleConfig config;
    private final Set<Klass> classes;
    private final Set<Resource> resources;
    private final Set<Module> members;
    private final Set<Service> services;
    private final Set<Package> exports;
    private final Set<String> permits;
    private final Map<Klass, Set<Module>> classExportsTo;
    private final Map<Service, Set<Klass>> providers;
    private final Map<String, Package> packages;
    private final Map<String, Dependence> configRequires; // requires came from ModuleConfig

    // update during the analysis
    private Module group;
    private Profile profile;
    protected Module(ModuleConfig config) {
        this.name = config.module;
        this.profile = Profile.profileForModule(this.name);
        this.version = config.version;
        this.classes = new HashSet<>();
        this.resources = new HashSet<>();
        this.config = config;
        this.members = new HashSet<>();
        this.providers = new HashMap<>();
        this.configRequires = new LinkedHashMap<>(config.requires());
        this.services = new HashSet<>();
        this.exports = new HashSet<>();
        this.classExportsTo = new HashMap<>();
        this.packages = new HashMap<>();
        this.permits = new HashSet<>(config.permits);

        this.group = this; // initialize to itself

        // add to the resources if defined in the modules.properties
        String res = getModuleProperty(name + ".resources");
        if (res != null) {
            for (String n : res.split("\\s+")) {
                this.resources.add(new Resource(n));
            }
        }
    }

    String name() {
        return name;
    }

    String version() {
        return version;
    }

    ModuleConfig config() {
        return config;
    }

    Module group() {
        return group;
    }

    Profile profile() {
        return profile;
    }

    Collection<Package> packages() {
        return packages.values();
    }

    Set<Klass> classes() {
        return Collections.unmodifiableSet(classes);
    }

    Set<Resource> resources() {
        return Collections.unmodifiableSet(resources);
    }

    Set<Service> services() {
        return services;
    }

    Map<Service,Set<Klass>> providers() {
        return providers;
    }

    Map<String, Dependence> configRequires() {
        return configRequires;
    }

    Set<Module> members() {
        return Collections.unmodifiableSet(members);
    }

    Map<Klass,Set<Module>> classExportsTo() {
        return Collections.unmodifiableMap(classExportsTo);
    }

    Set<String> exports() {
        Set<String> epkgs = exports.stream().map(Package::name).collect(Collectors.toSet());
        if (!config.exportsTo.isEmpty()) {
            epkgs.addAll(config.exportsTo.keySet().stream()
                             .filter(pn -> packages.containsKey(pn) && config.exportsTo.get(pn).isEmpty())
                             .collect(Collectors.toSet()));
        }
        return epkgs;
    }

    Map<String,Set<Module>> exportsTo() {
        Map<String,Set<Module>> packageExportsToModule = new HashMap<>();
        classExportsTo.keySet().forEach(k ->
            packageExportsToModule.computeIfAbsent(k.getPackageName(), _k -> new HashSet<>())
            .addAll(classExportsTo.get(k)));
        // process exports to from the config
        for (Map.Entry<String, Set<String>> e : config.exportsTo.entrySet()) {
            Package pkg = packages.get(e.getKey());
            if (pkg == null) {
                System.err.println("Warning: " + e.getKey() + " exported in config not found in " + name());
                continue;
            }
            if (!e.getValue().isEmpty()) {
                Set<Module> ms = packageExportsToModule.computeIfAbsent(e.getKey(), k -> new HashSet<>());
                e.getValue().forEach(n -> ms.add( getFactory().getModule(n)));
            }
        }
        return packageExportsToModule;
    }

    Set<String> permits() {
        return permits;
    }

    void exportsInternalClass(Klass k, Module m) {
        classExportsTo.computeIfAbsent(k, _k -> new HashSet<>()).add(m);
    }

    boolean contains(Klass k) {
        return k != null && classes.contains(k);
    }

    // returns true if a property named <module-name>.<key> is set to "true"
    // otherwise; return false
    boolean moduleProperty(String key) {
        String value = moduleProps.getProperty(name + "." + key);
        if (value == null)
            return false;
        else
            return Boolean.parseBoolean(value);
    }

    boolean isEmpty() {
        if (!classes.isEmpty() || !resources.isEmpty())
            return false;

        return true;
    }

    boolean allowsEmpty() {
        String value = moduleProps.getProperty(name + ".allows.empty");
        if ("closed".equals(value)) {
            // include empty closed module only if the system property is set
            return Boolean.getBoolean("classanalyzer.closed.modules");
        } else {
            return moduleProperty("allows.empty");
        }
    }

    @Override
    public int compareTo(Module o) {
        if (o == null) {
            return -1;
        }
        return name.compareTo(o.name);
    }

    @Override
    public String toString() {
        return name;
    }

    void addKlass(Klass k) {
        classes.add(k);
        k.setModule(this);

        Package pkg = getPackage(k.getPackageName());
        pkg.addKlass(k);
        if (pkg.isExported) {
            // ## TODO: handle implementation classes
            if (profile == null) {
                profile = pkg.profile;
            } else if (pkg.profile != null && profile != pkg.profile) {
                throw new RuntimeException("module " + name + " in profile " +
                      profile + " but class " + k + " in " + pkg.profile);
            }
        }
    }

    void addPackage(Package p) {
        packages.put(p.name(), p);
    }

    Package getPackage(String pn) {
        return packages.computeIfAbsent(pn, _p -> new Package(pn, this));
    }

    void addResource(Resource res) {
        resources.add(res);
        res.setModule(this);

        String pn = "";
        int i = res.getName().lastIndexOf('/');
        if (i > 0) {
            pn = res.getName().substring(0, i).replace('/', '.');
        }
        Package pkg = getPackage(pn);
        pkg.addResource(res);
    }

    void addService(Service s) {
        assert classes.contains(s.service);
        services.add(s);
    }

    void addProvider(Service s, Klass k) {
        assert k.getModule() == this;
        Set<Klass> implClasses = providers.get(s);
        if (implClasses == null) {
            providers.put(s, implClasses = new LinkedHashSet<>());
        }
        if (!implClasses.contains(k)) {
            implClasses.add(k);
        }
    }

    void addPermit(Module m) {
        permits.add(m.name());
    }

    <P> void visitMembers(Set<Module> visited, ModuleVisitor<P> visitor, P p) {
        if (!visited.contains(this)) {
            visited.add(this);
            visitor.preVisit(this, p);
            for (Module m : members) {
                m.visitMembers(visited, visitor, p);
                visitor.visited(this, m, p);
            }
            visitor.postVisit(this, p);
        } else {
            throw new RuntimeException("Cycle detected: module " + this.name);
        }
    }

    private <K, E> void mergeMaps(Map<K, Set<E>> map1, Map<K, Set<E>> map2) {
        // merge providers
        for (Map.Entry<K, Set<E>> e : map2.entrySet()) {
            if (!map1.containsKey(e.getKey())) {
                map1.put(e.getKey(), e.getValue());
            }
            Set<E> value = map1.get(e.getKey());
            value.addAll(e.getValue());
        }
    }

    private static <K, V> void mergeMaps(Map<K,V> map1, Map<K,V> map2, BiFunction<V,V,Void> function) {
        // merge providers
        for (Map.Entry<K, V> e : map2.entrySet()) {
            if (!map1.containsKey(e.getKey())) {
                map1.put(e.getKey(), e.getValue());
            } else {
                V value = map1.get(e.getKey());
                function.apply(value, e.getValue());
            }
        }
    }

    void addMember(Module m) {
        // merge class list and resource list
        classes.addAll(m.classes);
        resources.addAll(m.resources);
        services.addAll(m.services);

        // merge providers
        mergeMaps(this.providers, m.providers, new BiFunction<Set<Klass>,Set<Klass>,Void>() {
            @Override
            public Void apply(Set<Klass> v1, Set<Klass> v2) {
                v1.addAll(v2);
                return null;
            }
        });

        // merge package infos
        mergeMaps(this.packages, m.packages, new BiFunction<Package,Package,Void>() {
            @Override
            public Void apply(Package p1, Package p2) {
                p1.merge(p2);
                return null;
            }
        });

        // merge requires from module configs
        mergeMaps(this.configRequires, m.configRequires, new BiFunction<Dependence,Dependence,Void>() {
            @Override
            public Void apply(Dependence d1, Dependence d2) {
                if (!d1.equals(d2)) {
                    throw new UnsupportedOperationException();
                }
                return null;
            }
        });

        // propagate profile
        if (profile == null) {
            profile = m.profile;
        } else if (m.profile != null && m.profile.requires(profile)) {
            // a member must be in the same profile as its group's profile
            // or a member is in a profile smaller than its group's profile
            // if there is a split package
            profile = m.profile;
        }
    }


    void addProviders(Map<Service, Set<Klass>> map) {
        mergeMaps(this.providers, map, new BiFunction<Set<Klass>, Set<Klass>, Void>() {
            @Override
            public Void apply(Set<Klass> v1, Set<Klass> v2) {
                v1.addAll(v2);
                return null;
            }
        });
    }

    void buildExports() {
        // rebuild exports after Package are merged
        boolean all = moduleProperty("exports.all");
        for (Package p : packages.values()) {
            if (p.hasClasses() && (all || p.isExported))
                exports.add(p);
        }
    }

    private static Factory INSTANCE = new Factory();
    public static Factory getFactory() {
        return INSTANCE;
    }

    static class Factory {
        // view to module map
        protected Map<String, Module> modules = new LinkedHashMap<>();

        protected final void addModule(Module m) {
            // ## For now, maintain the static all modules list.
            // ## Need to revisit later
            String name = m.name();
            if (modules.containsKey(name)) {
                throw new RuntimeException("module \"" + name + "\" already exists");
            }
            modules.put(name, m);
        }

        public final Module findModule(String name) {
            return modules.get(name);
        }

        public final Module getModule(String name) {
            Module m = findModule(name);
            if (m != null)
                return m;

            throw new RuntimeException("module " + name + " doesn't exist");
        }

        public final Set<Module> getAllModules() {
            // initialize unknown module (last to add to the list)
            unknownModule();
            Set<Module> ms = new LinkedHashSet<>(modules.values());
            return ms;
        }

        public void init(List<ModuleConfig> mconfigs) {
            for (ModuleConfig mconfig : mconfigs) {
                Module m = this.newModule(mconfig);
                addModule(m);
            }
        }

        public Module newModule(String name, String version) {
            return this.newModule(new ModuleConfig(name, version));
        }

        public Module newModule(ModuleConfig config) {
            return new Module(config);
        }

        public final void addModules(Set<Module> ms) {
            for (Module m : ms) {
                addModule(m);
            }
        }
        private static Module unknown;
        Module unknownModule() {
            synchronized (Factory.class) {
                if (unknown == null) {
                    unknown = this.newModule(ModuleConfig.moduleConfigForUnknownModule());
                    addModule(unknown);
                }
            }
            return unknown;
        }

        void buildModuleMembers() {
            // set up module member relationship
            for (Module m : getAllModules()) {
                m.group = m;       // initialize to itself
                for (String name : m.config.members()) {
                    Module member = findModule(name);
                    if (member != null) {
                        m.members.add(member);
                    }
                }
            }

            // set up the top-level module
            ModuleVisitor<Module> groupSetter = new ModuleVisitor<Module>() {
                public void preVisit(Module m, Module p) {
                    m.group = p;
                }

                public void visited(Module m, Module child, Module p) {
                    // nop - breadth-first search
                }

                public void postVisit(Module m, Module p) {
                    // nop - breadth-first search
                }
            };

            // propagate the top-level module to all its members
            for (Module p : getAllModules()) {
                for (Module m : p.members) {
                    if (m.group == m) {
                        m.visitMembers(new HashSet<Module>(), groupSetter, p);
                    }
                }
            }

            ModuleVisitor<Module> mergeClassList = new ModuleVisitor<Module>() {
                public void preVisit(Module m, Module p) {
                    // nop - depth-first search
                }

                public void visited(Module m, Module child, Module p) {
                    m.addMember(child);
                }

                public void postVisit(Module m, Module p) {
                }
            };

            Set<Module> visited = new HashSet<>();
            Set<Module> groups = new HashSet<>();
            for (Module m : getAllModules()) {
                if (m.group() == m) {
                    groups.add(m);
                    if (m.members().size() > 0) {
                        // merge class list from all its members
                        m.visitMembers(visited, mergeClassList, m);
                    }
                    m.buildExports();
                }
            }
        }
    }

    public interface Visitor<R, P> {
        R visitClass(Klass k, P p);
        R visitResource(Resource r, P p);
    }

    public <R, P> void visit(Visitor<R, P> visitor, P p) {
        for (Klass c : classes) {
            visitor.visitClass(c, p);
        }
        for (Resource res : resources) {
            visitor.visitResource(res, p);
        }
    }

    interface ModuleVisitor<P> {
        public void preVisit(Module m, P param);
        public void visited(Module m, Module child, P param);
        public void postVisit(Module m, P param);
    }
}
