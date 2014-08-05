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

package java.lang.reflect;

import java.security.Permission;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jdk.jigsaw.module.ModuleDescriptor;

import jdk.jigsaw.module.ServiceDependence;
import sun.misc.JavaLangAccess;
import sun.misc.ModuleCatalog;
import sun.misc.SharedSecrets;
import sun.reflect.CallerSensitive;
import sun.reflect.Reflection;

/**
 * Represents a runtime module.
 *
 * <p> {@code Module} does not define a public constructor. Instead {@code
 * Module} objects are constructed automatically by the Java Virtual Machine as
 * modules are defined by the {@code Layer.create}. </p>
 *
 * @apiNote Need to see if this API is consistent with other APis in
 *  java.lang.reflect, in particular array vs. collection and whether to
 *  use getXXX instead of XXX.
 *
 *  @apiNote The types in {@code java.lang.reflect} usually return an array
 *  rather than collections. Also the convention is to use getXXX for getters.
 *
 * @since 1.9
 * @see java.lang.Class#getModule
 */
public final class Module {

    private static final JavaLangAccess langAccess =
        SharedSecrets.getJavaLangAccess();

    private static final Permission ADD_READS_PERMISSION =
        new ReflectPermission("addReadsModule");

    private final ClassLoader loader;
    private final ModuleDescriptor descriptor;
    private final Set<String> packages;

    private final String name;
    private final long handle;

    // the modules that this module reads
    // TBD - this needs to be a weak map.
    private final Map<Module, Object> reads = new ConcurrentHashMap<>();

    // this module's exported, cached here for access checks
    private final Map<String, Set<Module>> exports = new ConcurrentHashMap<>();

    private volatile boolean defined;

    // called by VM during startup?
    Module(ClassLoader loader, String name) {
        this.loader = loader;
        this.descriptor = null;
        this.packages = null;

        this.name = name;
        this.handle = 0L;
    }

    Module(ClassLoader loader, ModuleDescriptor descriptor, Set<String> packages) {
        this.loader = loader;
        this.descriptor = descriptor;
        this.packages = packages;

        // register this Module in the loader's catalog - this will go away
        // once the Class#getModule has an implementation in the VM
        ModuleCatalog catalog;
        if (loader == null) {
            catalog = ModuleCatalog.getSystemModuleCatalog();
        } else {
            catalog = langAccess.getModuleCatalog(loader);
        }
        catalog.register(this);

        this.name = descriptor.name();
        this.handle = sun.misc.VM.defineModule(name,
                                               loader,
                                               packages.toArray(new String[0]));
    }

    /**
     * Returns the module name.
     */
    public String name() {
        return name;
    }

    /**
     * Returns the {@code ClassLoader} that this module is associated with.
     */
    public ClassLoader classLoader() {
        return loader;
    }

    /**
     * Returns the module descriptor from which this {@code Module} was defined.
     */
    public ModuleDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Returns the set of packages that this module includes.
     */
    public Set<String> packages() {
        return packages;
    }

    /**
     * Makes this module readable to the module of the caller. This method
     * does nothing if the caller is this module, the caller already reads
     * this module, or the caller is in the <em>unnamed module</em> and this
     * module does not have a permits.
     */
    @CallerSensitive
    public void setReadable() {
        Module caller = Reflection.getCallerClass().getModule();
        if (caller == this || (caller != null && caller.reads.containsKey(this)))
            return;

        if (caller != null) {
            sun.misc.VM.addReadsModule(caller.handle, this.handle);
            caller.reads.putIfAbsent(this, Boolean.TRUE);
        }
    }

    /**
     * Makes the given {@code Module} readable to this module. This method
     * is no-op if {@code target} is {@code null} (all modules can read the
     * unanmed module).
     *
     * @throws SecurityException if denied by the security manager
     */
    public void addReads(Module target) {
        if (target != null) {
            SecurityManager sm = System.getSecurityManager();
            if (sm != null)
                sm.checkPermission(ADD_READS_PERMISSION);
            reads.putIfAbsent(target, Boolean.TRUE);
        }
    }

    /**
     * Indicates if this {@code Module} reads the given {@code Module}.
     *
     * <p> Returns {@code true} if {@code m} is {@code null} (the unnamed
     * readable is readable to all modules, or {@code m} is this module (a
     * module can read itself). </p>
     *
     * @see #setReadable()
     */
    public boolean canRead(Module target) {
        return target == null || target == this || reads.containsKey(target);
    }

    /**
     * Returns the set of modules that this module reads.
     */
    public Set<Module> reads() {
        return new HashSet<>(reads.keySet());
    }

    Set<String> uses() {
        // already cached
        Set<String> uses = this.uses;
        if (uses != null)
            return uses;

        if (descriptor == null) {
            return Collections.emptySet();
        } else {
            uses = descriptor().serviceDependences()
                               .stream()
                               .map(ServiceDependence::service)
                               .collect(Collectors.toSet());
            uses = Collections.unmodifiableSet(uses);
            this.uses = uses;
            return uses;
        }
    }
    private volatile Set<String> uses;

    /**
     * Returns a map of the service providers that the module provides.
     * The map key is the type name of the service interface. The map key
     * is the set of type names for the service implementations.
     */
    Map<String, Set<String>> provides() {
        if (descriptor== null) {
            return Collections.emptyMap();
        } else {
            return descriptor().services();
        }
    }

    /**
     * Return the string representation of the module.
     */
    public String toString() {
        return "module " + name;
    }

    static {
        sun.misc.SharedSecrets.setJavaLangReflectAccess(
            new sun.misc.JavaLangReflectAccess() {
                @Override
                public Module defineUnnamedModule() {
                    return new Module(null, "<unnamed>");
                }
                @Override
                public Module defineModule(ClassLoader loader,
                                           ModuleDescriptor descriptor,
                                           Set<String> packages) {
                    return new Module(loader, descriptor, packages);
                }
                @Override
                public void setDefined(Module m) {
                    m.defined = true;
                }
                @Override
                public void addReadsModule(Module m1, Module m2) {
                    sun.misc.VM.addReadsModule(m1.handle, m2.handle);
                    m1.reads.put(m2, Boolean.TRUE);
                }
                @Override
                public void addExport(Module m, String pkg, Module permit) {
                    long handle = (permit != null) ? permit.handle : 0L;
                    sun.misc.VM.addExports(m.handle, pkg, handle);
                    Set<Module> permits = m.exports.computeIfAbsent(pkg, k -> new HashSet<>());
                    if (permit != null) {
                        synchronized (permits) {
                            permits.add(permit);
                        }
                    }
                }
                @Override
                public Set<Module> exports(Module m, String pkg) {
                    // returns null if not exported
                    return m.exports.get(pkg);
                }
                @Override
                public boolean uses(Module m, String sn) {
                    return m.uses().contains(sn);
                }
                @Override
                public Set<String> provides(Module m, String sn) {
                    Set<String> provides = m.provides().get(sn);
                    if (provides == null) {
                        return Collections.emptySet();
                    } else {
                        return provides;
                    }
                }
            });
    }
}
