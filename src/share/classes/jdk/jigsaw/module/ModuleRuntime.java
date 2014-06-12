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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import sun.misc.JavaLangAccess;
import sun.misc.JavaLangReflectAccess;
import sun.misc.SharedSecrets;
import sun.misc.VM;
import sun.reflect.CallerSensitive;
import sun.reflect.ModuleCatalog;
import sun.reflect.Reflection;

/**
 * Module runtime support. This class defines static methods to define modules
 * to the runtime and to adjust the readability graph at runtime.
 */
public final class ModuleRuntime {
    private ModuleRuntime() { }

    // access to plumbing to setup mirrors in java.lang.reflect
    private static JavaLangAccess langAccess = SharedSecrets.getJavaLangAccess();
    private static JavaLangReflectAccess reflectAccess =
        SharedSecrets.getJavaLangReflectAccess();

    // System-wide dictionary to map "raw modules" to modules defined in the runtime.
    // This is needed until we have a means to identify modules in the runtime when
    // extending the readability graph at runtime (dynamic configurations for example).
    //
    // To support layering, the dictionary is keyed on the {@code ModuleGraph}.
    //
    private static class DictionaryEntry {
        final long handle;
        final java.lang.reflect.Module reflectModule;

        DictionaryEntry(long handle, java.lang.reflect.Module reflectModule) {
            this.handle = handle;
            this.reflectModule = reflectModule;
        }

        long handle() {
            return handle;
        }

        java.lang.reflect.Module reflectModule() {
            return reflectModule;
        }
    }
    private static final Map<ModuleGraph, Map<Module, DictionaryEntry>> dictionary;

    // A mapping of java.lang.reflect.Module to raw module. This is needed for now
    // to support changing the readability graph at runtime via {@link #setReadable}.
    private static final Map<java.lang.reflect.Module, Module> moduleToModule;

    static {
        dictionary = new HashMap<>();
        moduleToModule = new ConcurrentHashMap<>();
    }

    /**
     * Returns the {@code DictionaryEntry} for given module in the given
     * module graph. Returns {@code null} if not found.
     */
    private static DictionaryEntry findDictionaryEntry(ModuleGraph g, Module m) {
        assert Thread.holdsLock(dictionary);

        while (!g.isEmpty()) {
            Map<Module, DictionaryEntry> map = dictionary.get(g);
            if (map != null) {
                DictionaryEntry entry = map.get(m);
                if (entry != null)
                    return entry;
            }
            g = g.initialModuleGraph();
        }
        return null;
    }

    /**
     * A simple factory for {@code ClassLoader}s.
     */
    @FunctionalInterface
    public static interface LoaderFactory {
        /**
         * Returns the class loader for the given module.
         */
        ClassLoader getLoader(Module m);
    }

    /**
     * Defines the given module to the VM in "proto form". Proto form is the
     * module defined to the runtime without any readability relationships and
     * without exports. This is used by the launcher -mods option for testing
     * purposes.
     */
    public static void defineProtoModule(Module m, ClassLoader loader) {
        long handle = VM.defineModule(m.id().name());
        m.packages().forEach(pkg -> VM.bindToModule(loader, pkg, handle));
    }

    /**
     * Defines the modules in the given module graph that are not in its initial
     * module graph to the runtime. The given {@code LoaderFactory} is invoked
     * to provide the mapping of each module to a {@code ClassLoader}.
     */
    public static void defineModules(ModuleGraph g, LoaderFactory factory) {
        g.minusInitialModuleGraph()
         .modules()
         .forEach(m -> defineModule(g, m, factory.getLoader(m)));
    }

    /**
     * Defines the given modules in the given module graph to the runtime. The
     * module is associated with the given {@code ClassLoader}.
     */
    public static boolean defineModule(ModuleGraph g, Module m, ClassLoader loader) {
        synchronized (dictionary) {

            // check if already defined
            Map<Module, DictionaryEntry> map = dictionary.get(g);
            if (map != null && map.containsKey(m))
                return false;

            // the newly selected modules, not present in the initial module graph
            Set<Module> selected = g.minusInitialModuleGraph().modules();
            if (!selected.contains(m)) {
                throw new IllegalArgumentException(m.id() +
                    " not a newly selected module in this module graph");
            }

            // VM
            long handle = VM.defineModule(m.id().name());
            m.packages().forEach(pkg -> VM.bindToModule(loader, pkg, handle));

            // java.lang.reflect.Module
            java.lang.reflect.Module reflectModule =
                defineReflectModule(langAccess, loader, m);

            // setup the exports
            for (ModuleExport export: m.exports()) {
                String pkg = export.pkg();
                String permit = export.permit();
                if (permit == null) {
                    VM.addExports(handle, export.pkg());
                    reflectAccess.addExport(reflectModule, export.pkg(), null);
                } else {
                    Module other = g.modulePath().findModule(permit);
                    if (other != null) {
                        DictionaryEntry entry = findDictionaryEntry(g, other);
                        if (entry != null) {
                            VM.addExportsWithPermits(handle, export.pkg(), entry.handle());
                            reflectAccess.addExport(reflectModule, export.pkg(),
                                                    entry.reflectModule());
                        } else {
                            // qualified-export but target module not defined yet
                        }
                    }
                }
            }

            // setup qualified exports for modules that export to {@code m}.
            // These are qualified exports from modules that were previously
            // defined to runtime.
            for (Module other: selected) {
                DictionaryEntry entry = findDictionaryEntry(g, other);
                if (entry != null) {
                    for (ModuleExport export: other.exports()) {
                        String pkg = export.pkg();
                        String permit = export.permit();
                        if (permit != null && permit.equals(m.id().name())) {
                            VM.addExportsWithPermits(entry.handle(), export.pkg(), handle);
                            reflectAccess.addExport(entry.reflectModule(), export.pkg(),
                                                    reflectModule);
                        }
                    }
                }
            }

            // m reads other
            for (Module other: g.readDependences(m)) {
                DictionaryEntry entry = findDictionaryEntry(g, other);
                if (entry != null) {
                    VM.addReadsModule(handle, entry.handle());
                    reflectAccess.addReadsModule(reflectModule, entry.reflectModule());
                } else {
                    // other not defined to runtime yet
                }
            }

            // previously defined modules may read {@code m}, these read edges are
            // setup now.
            for (Module other: selected) {
                DictionaryEntry entry = findDictionaryEntry(g, other);
                if (entry != null) {
                    if (g.readDependences(other).contains(m)) {
                        VM.addReadsModule(entry.handle(), handle);
                        reflectAccess.addReadsModule(entry.reflectModule(), reflectModule);
                    }
                }
            }

            // services used
            Set<ServiceDependence> sd = m.serviceDependences();
            if (!sd.isEmpty()) {
                sd.stream().map(ServiceDependence::service)
                           .forEach(sn -> reflectAccess.addUses(reflectModule, sn));
            }

            // services provided
            reflectAccess.addProvides(reflectModule, m.services());

            // module is defined to runtime so add it to dictionary and
            dictionary.computeIfAbsent(g, k -> new HashMap<>())
                      .put(m, new DictionaryEntry(handle, reflectModule));
            moduleToModule.put(reflectModule, m);

            return true;
        }
    }

    /**
     * Makes the given module readable to the module of the caller.
     *
     * @throws java.lang.IllegalStateException if the given module has a
     *   permits and the permits does not include the caller. We need to
     *   decide on a better exception for this.
     */
    @CallerSensitive
    public static void setReadable(java.lang.reflect.Module who) {
        if (who == null)
            return;
        java.lang.reflect.Module caller = Reflection.getCallerClass().getModule();
        if (caller == null)
            return;

        Module m = moduleToModule.get(who);
        if (m == null)
            throw new InternalError();

        Set<String> permits = m.permits();
        if (!permits.isEmpty()) {
            if (!permits.contains(caller))
                throw new IllegalStateException(m.id() + " has a permits");
            return;
        }

        // update core reflection for now
        reflectAccess.addReadsModule(caller, who);
    }

    /**
     * Defines a new java.lang.reflect.Module for the given Module and
     * associate with the given class loader.
     */
    private static java.lang.reflect.Module
        defineReflectModule(JavaLangAccess langAccess, ClassLoader loader, Module m) {

        ModuleCatalog catalog;
        if (loader == null) {
            catalog = ModuleCatalog.getSystemModuleCatalog();
        } else {
            catalog = langAccess.getModuleCatalog(loader);
        }
        String name = m.id().name();
        return catalog.defineModule(name, m.packages());
    }
}
