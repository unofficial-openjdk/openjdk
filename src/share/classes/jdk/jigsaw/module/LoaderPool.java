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

package jdk.jigsaw.module;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages a pool of class loaders where each class loader defines the types
 * for one module -- i.e. one class loader per module.
 *
 * Class loaders are created lazily, the first attempt to load a class in a
 * module will trigger the {@code ClassLoader} to be created. Within the loader
 * pool then direct delegation is used when the initiating loader is invoked
 * to load a type that is exported by another loader in the pool. When invoked
 * to load a class that is not local to a module or is not exported by another
 * module in the pool then the load request is delegated to the loader pool's
 * parent class loader.
 *
 * @implNote In a loader pool then all types in exported packages are visible to
 * all other types. This is to keep things simple and also for space efficiency
 * reasons.
 */
public final class LoaderPool {

    // parent class loader - need to keep this to avoid calling getParent
    // when lazily creating class loaders
    private final ClassLoader parent;

    // the module graph
    private final ModuleGraph graph;

    // modules in this loader pool
    private final Set<Module> modules;

    // exported package -> Module
    private final Map<String, Module> pkgToModule;

    // module -> Loader
    // ## should allow Loader to be GC'ed ?
    private final Map<Module, Loader> moduleToLoader = new ConcurrentHashMap<>();

    /**
     * Creates a {@code LoaderPool} that lazily creates a {@code ClassLoader} for
     * each of the modules in the given module graph that are not in its initial
     * module graph. In other words, create a {@code LoaderPool} for each of
     * the newly selected modules in the module graph.
     */
    public LoaderPool(ModuleGraph g, ClassLoader parent) {
        Set<Module> selected = g.minusInitialModuleGraph();
        Map<String, Module> map = new HashMap<>();

        // maps exported packages to Module
        selected.stream().forEach(m -> {
            m.exports().forEach(export -> {
                String pkg = export.pkg();
                Module other = map.putIfAbsent(pkg, m);
                if (other != null) {
                    throw new Error(m.id() + " and " + other.id() +
                            " both export " + pkg);
                }
            });
        });

        this.parent = parent;
        this.graph = g;
        this.modules = selected;
        this.pkgToModule = map;
    }

    /**
     * Creates a {@code LoaderPool} with the system class loader as the parent
     * class loader of the pool.
     */
    public LoaderPool(ModuleGraph g) {
        this(g, ClassLoader.getSystemClassLoader());
    }

    /**
     * Returns the {@code ClassLoader} for the given module name.
     *
     * @throws IllegalArgumentException if {@code name} is not the name of a
     *   module in this loader pool
     */
    public ClassLoader findLoader(String name) {
        Module m = graph.modulePath().findModule(name);
        if (m == null || !modules.contains(m))
            throw new IllegalArgumentException(name + " not in this loader pool");
        return findLoader(m);
    }

    /**
     * Returns the {@code Loader] for the given module, creating it and defining
     * the module to the runtime if not already created and defined. Returns {@code
     * null} if the given module is not associated with this loader pool.
     */
    Loader findLoader(Module m) {
        Loader ld = moduleToLoader.get(m);
        if (ld == null && modules.contains(m)) {
            URL url = graph.modulePath().localLocationOf(m);
            if (url == null)
                throw new Error("No module artifact for " + m.id());

            ld = new Loader(m, this, url, parent);

            synchronized (this) {
                if (ModuleRuntime.defineModule(graph, m, ld)) {
                    Loader other = moduleToLoader.putIfAbsent(m, ld);
                    assert other == null;
                } else {
                    // already defined, another thread best us
                    ld = moduleToLoader.get(m);
                    assert ld != null;
                }
            }
        }
        return ld;
    }

    /**
     * Returns the {@code Loader] for the given exported package, {@code null}}
     * if the packaged is not exported by any of the modules in this loader
     * pool.
     */
    Loader findLoaderForPackage(String pkg) {
        Module m = pkgToModule.get(pkg);
        if (m == null)
            return null;

        Loader ld = findLoader(m);
        assert ld != null;
        return ld;
    }

    /**
     * A {@code URLClassLoader} that loads the types for one module.
     */
    static class Loader extends URLClassLoader {
        private final Module module;
        private final LoaderPool pool;

        Loader(Module m, LoaderPool pool, URL url, ClassLoader parent) {
            super(new URL[]{url}, parent);
            this.module = m;
            this.pool = pool;
        }

        @Override
        protected Class<?> loadClass(String cn, boolean resolve)
            throws ClassNotFoundException
        {
            Class<?> c = findLoadedClass(cn);
            if (c == null) {
                Loader ld = null;

                // local or other loader in this loader pool?
                int i = cn.lastIndexOf('.');
                if (i > 0) {
                    String pkg = cn.substring(0, i);
                    if (module.packages().contains(pkg)) {
                        ld = this;
                    } else {
                        ld = pool.findLoaderForPackage(pkg);
                    }
                }
                if (ld == null)
                    return super.loadClass(cn, resolve);

                c = ld.findClass(cn);
            }

            if (resolve)
                resolveClass(c);

            return c;
        }

        // ### Need to decide on getResource/getResources/findLibrary/etc.

        @Override
        public String toString() {
            return "Loader-" + module.id().toString();
        }

        static {
            ClassLoader.registerAsParallelCapable();
        }
    }
}
