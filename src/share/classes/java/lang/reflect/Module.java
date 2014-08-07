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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import jdk.jigsaw.module.Layer;
import jdk.jigsaw.module.ModuleArtifact;
import jdk.jigsaw.module.ModuleDescriptor;
import jdk.jigsaw.module.ServiceDependence;

import sun.misc.JavaLangAccess;
import sun.misc.ModuleCatalog;
import sun.misc.SharedSecrets;

/**
 * Represents a runtime module.
 *
 * <p> {@code Module} does not define a public constructor. Instead {@code
 * Module} objects are constructed automatically by the Java Virtual Machine as
 * modules are defined by {@link jdk.jigsaw.module.Layer#create Layer.create}. </p>
 *
 * @apiNote Need to see if this API is consistent with other APis in
 * java.lang.reflect, in particular array vs. collection and whether to
 * use getXXX instead of XXX.
 *
 * @apiNote The types in {@code java.lang.reflect} usually return an array
 * rather than collections. Also the convention is to use getXXX for getters.
 *
 * @since 1.9
 * @see java.lang.Class#getModule
 */
public final class Module {

    private static final JavaLangAccess langAccess =
        SharedSecrets.getJavaLangAccess();

    private static final Permission ADD_READS_PERMISSION =
        new ReflectPermission("addReadsModule");

    private final String name;
    private final ClassLoader loader;

    // handle to module in VM, this will go away
    private final long handle;

    // initialized lazily for modules defined by VM during bootstrapping
    private volatile ModuleDescriptor descriptor;
    private volatile Set<String> packages;

    // the modules that this module reads
    private final Map<Module, Boolean> reads = new WeakHashMap<>();

    // this module's exports, cached here for access checks
    private final Map<String, Set<Module>> exports = new HashMap<>();

    // use RW locks (changes are rare)
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock readLock = lock.readLock();
    private final Lock writeLock = lock.writeLock();

    // indicates whether the Module is fully defined - will be used later
    // by snapshot APIs (JVM TI for example)
    private volatile boolean defined;

    // called by VM during startup for at least java.base
    Module(ClassLoader loader, String name) {
        this.loader = loader;
        this.name = name;
        this.handle = 0L;
    }

    Module(ClassLoader loader, ModuleDescriptor descriptor, Set<String> packages) {
        this.name = descriptor.name();
        this.loader = loader;
        this.descriptor = descriptor;
        this.packages = packages;
        this.handle = sun.misc.VM.defineModule(name,
                                               loader,
                                               packages.toArray(new String[0]));

        // register this Module in the loader's catalog - this will go away
        // once the Class#getModule has an implementation in the VM
        ModuleCatalog catalog;
        if (loader == null) {
            catalog = ModuleCatalog.getSystemModuleCatalog();
        } else {
            catalog = langAccess.getModuleCatalog(loader);
        }
        catalog.register(this);
    }

    /**
     * Initialize descriptor and packages for modules that are defined during
     * VM bootstrapping.
     */
    private void postBootInit() {
        Layer bootLayer = Layer.bootLayer();
        if (bootLayer == null)
            throw new InternalError("boot layer not set");

        ModuleArtifact artifact = bootLayer.findArtifact(name);
        if (artifact == null)
            throw new InternalError("boot layer does not include: " + name);

        this.descriptor = artifact.descriptor();
        this.packages = artifact.packages();
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
        ModuleDescriptor descriptor = this.descriptor;
        if (descriptor == null) {
            postBootInit();
            descriptor = this.descriptor;
        }
        return descriptor;
    }

    /**
     * Returns the set of packages that this module includes.
     */
    public Set<String> packages() {
        if (descriptor == null)
            postBootInit();
        return packages;
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

            implAddReads(target);
        }
    }

    void implAddReads(Module target) {
        writeLock.lock();
        try {
            sun.misc.VM.addReadsModule(this.handle, target.handle);
            reads.put(target, Boolean.TRUE);
        } finally {
            writeLock.unlock();
        }
    }

    void implAddExports(String pkg, Module who) {
        writeLock.lock();
        try {
            long h2 = (who != null) ? who.handle : 0L;
            sun.misc.VM.addExports(this.handle, pkg, h2);
            Set<Module> permits = exports.computeIfAbsent(pkg, k -> new HashSet<>());
            if (who != null)
                permits.add(who);
        } finally {
            writeLock.unlock();
        }
    }

    boolean isExported(String pkg, Module who) {
        readLock.lock();
        try {
            Set<Module> permits = exports.get(pkg);
            if (permits == null)
                return false; // not exported

            if (permits.isEmpty())
                return true;  // exported

            //assert !permits.contains(null);
            return permits.contains(who);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Indicates if this {@code Module} reads the given {@code Module}.
     *
     * <p> Returns {@code true} if {@code m} is {@code null} (the unnamed
     * readable is readable to all modules), or {@code m} is this module (a
     * module can read itself). </p>
     *
     * @see #addReads
     */
    public boolean canRead(Module target) {
        if (target == null || target == this)
            return true;

        readLock.lock();
        try {
            return reads.containsKey(target);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Returns the set of modules that this module reads.
     */
    public Set<Module> reads() {
        readLock.lock();
        try {
            return new HashSet<>(reads.keySet());
        } finally {
            readLock.unlock();
        }
    }

    Set<String> uses() {
        // already cached
        Set<String> uses = this.uses;
        if (uses != null)
            return uses;

        uses = descriptor().serviceDependences()
                           .stream()
                           .map(ServiceDependence::service)
                           .collect(Collectors.toSet());
        uses = Collections.unmodifiableSet(uses);
        this.uses = uses;
        return uses;

    }
    private volatile Set<String> uses;

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
                   m1.implAddReads(m2);
                }
                @Override
                public void addExport(Module m, String pkg, Module who) {
                    m.implAddExports(pkg, who);
                }
                @Override
                public boolean isExported(Module m, String pkg, Module who) {
                    return m.isExported(pkg, who);
                }
                @Override
                public boolean uses(Module m, String sn) {
                    return m.uses().contains(sn);
                }
                @Override
                public Set<String> provides(Module m, String sn) {
                    Set<String> provides = m.descriptor().services().get(sn);
                    if (provides == null) {
                        return Collections.emptySet();
                    } else {
                        return provides;
                    }
                }
            });
    }
}
