/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.Configuration;
import java.lang.module.Layer;
import java.lang.module.Layer.ClassLoaderFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleDescriptor.Provides;
import java.lang.module.Version;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import jdk.internal.module.ServicesCatalog;
import sun.misc.BootLoader;
import sun.misc.JavaLangReflectAccess;
import sun.misc.SharedSecrets;
import sun.misc.Unsafe;

/**
 * Represents a run-time module, either {@link #isNamed() named} or unnamed.
 *
 * <p> Named modules have a {@link #getName() name} and are constructed by the
 * Java Virtual Machine when a {@link java.lang.module.Configuration
 * Configuration} is reified by creating a module {@link java.lang.module.Layer
 * Layer}. </p>
 *
 * <p> An unnamed module does not have a name. There is an unnamed module
 * per {@link ClassLoader ClassLoader} that is obtained by invoking the class
 * loader's {@link ClassLoader#getUnnamedModule() getUnnamedModule} method. The
 * {@link Class#getModule() getModule} method of all types defined by a class
 * loader that are not in a named module return the class loader's unnamed
 * module. An unnamed module reads all other run-time modules. </p>
 *
 * @since 1.9
 * @see java.lang.Class#getModule
 */

public final class Module {

    // no <clinit> as this class is initialized very early in the startup

    // module name and loader, these fields are read by VM
    private final String name;
    private final ClassLoader loader;

    // the module descriptor
    private final ModuleDescriptor descriptor;

    // The set of packages in the module if this is a named module.
    // The field is volatile as it may be replaced at run-time
    private volatile Set<String> packages;

    // true if this module reads all unnamed modules (a.k.a. loose module)
    private volatile boolean loose;

    // the modules that this module permanently reads
    // FIXME: This should be final but need new VM interface to do this
    private volatile Set<Module> reads = Collections.emptySet();

    // created lazily, additional modules that this module temporarily reads
    private volatile WeakSet<Module> transientReads;

    // module exports. The key is the package name; the value is an empty map
    // for unqualified exports; the value is a WeakHashMap when there are
    // qualified exports
    private volatile Map<String, Map<Module, Boolean>> exports = Collections.emptyMap();


    /**
     * Invoked by the VM to create java.base early in the startup.
     */
    private Module(ClassLoader loader, String name) {
        if (name == null)
            throw new Error();

        this.name = name;
        this.loader = loader;
        this.descriptor = null;
    }

    /**
     * Used to create named Module, except for java.base.
     */
    private Module(ClassLoader loader, ModuleDescriptor descriptor) {
        this.name = descriptor.name();
        this.loader = loader;

        this.descriptor = descriptor;
        this.packages = descriptor.packages();
    }

    /**
     * Used to create an unnamed Module.
     *
     * @see ClassLoader#getUnnamedModule
     */
    private Module(ClassLoader loader) {
        this.name = null;
        this.loader = loader;
        this.descriptor = null;

        // unnamed modules are loose
        this.loose = true;
    }


    /**
     * Returns {@code true} if this module is a named module.
     *
     * @see ClassLoader#getUnnamedModule()
     */
    public boolean isNamed() {
        return name != null;
    }

    /**
     * Returns the module name.
     *
     * For now, this method returns {@code null} if this module is an
     * unnamed module.
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the {@code ClassLoader} for this module.
     *
     * <p> If there is a security manager then its {@code checkPermission}
     * method if first called with a {@code RuntimePermission("getClassLoader")}
     * permission to check that the caller is allowed to get access to the
     * class loader. </p>
     *
     * @throws SecurityException
     *         If denied by the security manager
     */
    public ClassLoader getClassLoader() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            RuntimePermission perm = new RuntimePermission("getClassLoader");
            sm.checkPermission(perm);
        }
        return loader;
    }

    /**
     * Returns the module descriptor for this module.
     *
     * For now, this method returns {@code null} if this module is an
     * unnamed module.
     */
    public ModuleDescriptor getDescriptor() {
        return descriptor;
    }

    /**
     * @apiNote Need to decide whether to add this method as a public method.
     */
    Layer getLayer() {
        throw new RuntimeException();
    }

    /**
     * Returns an array of the package names of the packages in this module.
     *
     * <p> The package names are the fully-qualified names of the packages as
     * defined in section 6.5.3 of <cite>The Java&trade; Language Specification
     * </cite>, for example, {@code "java.lang"}. </p>
     *
     * <p> The returned array contains an element for each package in the
     * module when it was initially created. It may contain elements
     * corresponding to packages added to the module after it was created
     * (packages added to support dynamic proxy classes for example). A package
     * name appears at most once in the returned array. </p>
     *
     * @apiNote This method returns an array rather than a {@code Set} for
     * consistency with other {@code java.lang.reflect} types.
     *
     * @return an array of the package names of the packages in this module
     */
    public String[] getPackages() {
        if (isNamed()) {
            return packages.toArray(new String[0]);
        } else {
            // TBD: need to invoke protected loader.getPackages();
            return new String[0];
        }
    }


    // -- readability --


    /**
     * Makes the given {@code Module} readable to this module. This method
     * is a no-op if {@code target} is {@code this} module (all modules can
     * read themselves) or this module is not a {@link #isNamed() named}
     * module (an unnamed module reads all other modules).
     *
     * <p> If {@code target} is {@code null}, and this module does not read
     * all unnamed modules, then this method changes this module so that it
     * reads all unnamed modules. </p>
     *
     * <p> If there is a security manager then its {@code checkPermission}
     * method if first called with a {@code ReflectPermission("addReadsModule")}
     * permission to check that the caller is allowed to change the
     * readability graph. </p>
     *
     * @throws SecurityException if denied by the security manager
     *
     * @see #canRead
     */
    public void addReads(Module target) {
        if (target != this && this.isNamed()) {
            SecurityManager sm = System.getSecurityManager();
            if (sm != null) {
                ReflectPermission perm = new ReflectPermission("addReadsModule");
                sm.checkPermission(perm);
            }
            addReads(target, true);
        }
    }

    /**
     * Makes the given {@code Module} readable to this module without
     * notifying the VM.
     *
     * This method is for use by VM whitebox tests only.
     */
    void addReadsNoSync(Module target) {
        addReads(target, false);
    }

    /**
     * Makes the given {@code Module} readable to this module.
     *
     * If {@code syncVM} is {@code true} then the VM is notified.
     */
    private void addReads(Module target, boolean syncVM) {

        // nothing to do
        if (target == this || !this.isNamed())
            return;

        // if the target is null then change this module to be loose.
        // FIXME: Need to notify VM
        if (target == null) {
            this.loose = true;
            return;
        }

        // check if we already read this module
        Set<Module> reads = this.reads;
        if (reads.contains(target))
            return;

        // update VM first, just in case it fails
        // FIXME: Need to notify VM about unnamed too when VM is ready
        if (target.isNamed() && syncVM)
            jvmAddReadsModule(this, target);

        // add temporary read.
        WeakSet<Module> tr = this.transientReads;
        if (tr == null) {
            synchronized (this) {
                tr = this.transientReads;
                if (tr == null) {
                    tr = new WeakSet<>();
                    this.transientReads = tr;
                }
            }
        }
        tr.add(target);
    }


    /**
     * Indicates if this {@code Module} reads the given {@code Module}.
     * If {@code target} is {@code null} then this method tests if this
     * module reads all unnamed modules.
     *
     * @see #addReads
     */
    public boolean canRead(Module target) {

        // all modules read themselves
        if (target == this)
            return true;

        // loose modules read all unnamed modules
        if (this.loose && (target == null || !target.isNamed()))
            return true;

        // an unnamed module reads all modules
        if (!this.isNamed())
            return true;

        // check if module reads target
        Set<Module> reads = this.reads; // volatile read
        if (reads.contains(target))
            return true;

        // check if module reads the target temporarily
        WeakSet<Module> tr = this.transientReads; // volatile read
        if (tr != null && tr.contains(target))
            return true;

        return false;
    }


    /**
     * A "not-a-Set" set of weakly referenced objects that supports concurrent
     * access.
     */
    private static class WeakSet<E> {
        private final ReadWriteLock lock = new ReentrantReadWriteLock();
        private final Lock readLock = lock.readLock();
        private final Lock writeLock = lock.writeLock();

        private final WeakHashMap<E, Boolean> map = new WeakHashMap<>();

        /**
         * Adds the specified element to the set.
         */
        void add(E e) {
            writeLock.lock();
            try {
                map.put(e, Boolean.TRUE);
            } finally {
                writeLock.unlock();
            }
        }

        /**
         * Returns {@code true} if this set contains the specified element.
         */
        boolean contains(E e) {
            readLock.lock();
            try {
                return map.containsKey(e);
            } finally {
                readLock.unlock();
            }
        }
    }



    // -- creating Module objects --

    /**
     * Define a new Module to the runtime. The resulting Module will be
     * defined to the VM but will not read any other modules or have any
     * exports. It will also not be registered in the service catalog.
     */
    static Module defineModule(ClassLoader loader, ModuleReference mref) {
        Module m;

        ModuleDescriptor descriptor = mref.descriptor();
        Set<String> packages = descriptor.packages();

        // define module to VM, except java.base as it is defined by VM
        String name = descriptor.name();
        if (loader == null && name.equals("java.base")) {
            m = Object.class.getModule();

            // set descriptor and packages fields
            try {
                final Unsafe U = Unsafe.getUnsafe();
                Class<?> c = Module.class;
                long address = U.objectFieldOffset(c.getDeclaredField("descriptor"));
                U.putObject(m, address, descriptor);
            } catch (Exception e) {
                throw new Error(e);
            }
            m.packages = packages;

        } else {
            m = new Module(loader, descriptor);

            // define module to VM

            int n = packages.size();
            String[] array = new String[n];
            int i = 0;
            for (String pkg: packages) {
                array[i++] = pkg.replace('.', '/');
            }

            String vs = descriptor.version().map(Version::toString).orElse("");
            String loc = mref.location().map(URI::toString).orElse(null);

            jvmDefineModule(m, vs, loc, array);
        }

        return m;
    }

    /**
     * Defines each of the module in the given configuration to the runtime.
     *
     * @return a map of module name to runtime {@code Module}
     */
    static Map<String, Module> defineModules(Configuration cf,
                                             Layer.ClassLoaderFinder clf)
    {
        Map<String, Module> modules = new HashMap<>();
        Map<String, ClassLoader> loaders = new HashMap<>();

        // define each of the modules in the configuration to the VM
        for (ModuleDescriptor descriptor: cf.descriptors()) {
            String name = descriptor.name();

            ModuleReference mref = cf.findReference(name);
            ClassLoader loader = clf.loaderForModule(mref);

            Module m = defineModule(loader, mref);
            modules.put(name, m);
            loaders.put(name, loader);
        }

        // setup readability and exports
        for (ModuleDescriptor descriptor: cf.descriptors()) {
            Module m = modules.get(descriptor.name());
            assert m != null;

            // reads
            Set<Module> reads = new HashSet<>();
            for (ModuleDescriptor other: cf.readDependences(descriptor)) {
                String dn = other.name();
                Module m2 = modules.get(dn);
                Layer parent = cf.layer();
                if (m2 == null && parent != null)
                    m2 = parent.findModule(other.name());
                if (m2 == null) {
                    throw new InternalError(descriptor.name() +
                            " reads unknown module: " + other.name());
                }
                reads.add(m2);

                // update VM view
                jvmAddReadsModule(m, m2);
            }
            m.reads = reads;

            // exports
            Map<String, Map<Module, Boolean>> exports = new HashMap<>();
            for (Exports export: descriptor.exports()) {
                String source = export.source();
                if (!export.targets().isPresent()) {
                    exports.computeIfAbsent(source, k -> Collections.emptyMap());
                    // update VM view
                    jvmAddModuleExports(m, source.replace('.', '/'), null);
                } else {
                    export.targets().get()
                        .forEach(mn -> {
                                // only export to modules that are in this configuration
                                Module m2 = modules.get(mn);
                                if (m2 != null) {
                                    exports.computeIfAbsent(source, k -> new HashMap<>())
                                        .put(m2, Boolean.TRUE);
                                    // update VM view
                                    jvmAddModuleExports(m, source.replace('.', '/'), m2);
                                }
                            });
                }
            }
            m.exports = exports;
        }

        // register the modules in the service catalog if they provide services
        // and set the "defined" field to mark as fully defined.
        for (ModuleDescriptor descriptor: cf.descriptors()) {
            String name = descriptor.name();
            Module m = modules.get(name);
            ClassLoader loader = loaders.get(name);

            Map<String, Provides> services = descriptor.provides();
            if (!services.isEmpty()) {
                ServicesCatalog catalog;
                if (loader == null) {
                    catalog = BootLoader.getServicesCatalog();
                } else {
                    catalog = SharedSecrets.getJavaLangAccess()
                                           .createOrGetServicesCatalog(loader);
                }
                catalog.register(m);
            }
        }

        return modules;
    }


    // -- packages --

    /**
     * Add a package to this module.
     *
     * @apiNote This is an expensive operation, not expected to be used often.
     * At this time then it does not validate that the package name is a
     * valid java identifier.
     */
    void addPackage(String pkg) {
        addPackage(pkg, true);
    }

    /**
     * Add a package to this module without notifying the VM.
     *
     * This method is for use by VM whitebox tests only.
     */
    void addPackageNoSync(String pkg) {
        addPackage(pkg.replace('/', '.'), false);
    }

    /**
     * Add a package to this module.
     *
     * If {@code syncVM} is {@code true} then the VM is notified.
     */
    private void addPackage(String pkg, boolean syncVM) {
        if (pkg.length() == 0)
            throw new IllegalArgumentException("<unnamed> package not allowed");

        synchronized (this) {
            // copy set
            Set<String> pkgs = new HashSet<>(this.packages);
            if (!pkgs.add(pkg)) {
                // already has this package
                return;
            }

            // update VM first, just in case it fails
            if (syncVM)
                jvmAddModulePackage(this, pkg.replace('.', '/'));

            // replace with new set
            this.packages = pkgs; // volatile write
        }
    }


    // -- exports --

    /**
     * Returns {@code true} if this module exports the given package to the
     * given module. If {@code target} is {@code null} then this method
     * checks if the package is exported un-conditionally (meaning an
     * unqualified export).
     *
     * This method does not check if the given module reads this module.
     */
    public boolean isExported(String pkg, Module target) {
        Objects.nonNull(pkg);

        // all packages are exported by unnamed modules
        if (!isNamed())
            return true;

        Map<String, Map<Module, Boolean>>  exports = this.exports; // volatile read
        Map<Module, Boolean> permits = exports.get(pkg);
        if (permits != null) {
            // unqualified export or exported to 'target'
            if (permits.isEmpty() || permits.containsKey(target)) return true;
        }

        // not exported
        return false;
    }

    /**
     * Updates the exports so that package {@code pkg} is exported to module
     * {@code who} but without notifying the VM.
     *
     * This method is for use by VM whitebox tests only.
     */
    void addExportsNoSync(String pkg, Module who) {
        addExports(pkg.replace('/', '.'), who, false);
    }

    /**
     * Updates the exports so that package {@code pkg} is exported to module
     * {@code target}. If {@code target} is {@code null} then the package is exported
     * to all modules that read this module.
     *
     * @throws IllegalArgumentException if {@code pkg} is not a module package
     *
     * @apiNote This is an expensive operation, not expected to be used often. Also
     * this method is not public as it would otherwise be used to get to JDK
     * internal APIs.
     */
    void addExports(String pkg, Module target) {
        addExports(pkg, target, true);
    }

    /**
     * Updates the exports so that package {@code pkg} is exported to module
     * {@code target}.
     *
     * If {@code syncVM} is {@code true} then the VM is notified.
     */
    private void addExports(String pkg, Module target, boolean syncVM) {
        Objects.nonNull(pkg);

        if (!packages.contains(pkg)) {
            throw new IllegalArgumentException("exported package " + pkg +
                " not in contents");
        }

        synchronized (this) {

            // update VM first, just in case it fails
            if (syncVM) {

                // FIXME - VM does not know about unnamed modules yet
                if (target == null || target.isNamed())
                   jvmAddModuleExports(this, pkg.replace('.', '/'), target);

            }

            // copy existing map
            Map<String, Map<Module, Boolean>> exports = new HashMap<>(this.exports);

            // the package may be exported already, need to handle all cases
            Map<Module, Boolean> permits = exports.get(pkg);
            if (permits == null) {
                // pkg not already exported
                if (target == null) {
                    // unqualified export
                    exports.put(pkg, Collections.emptyMap());
                } else {
                    // qualified export
                    permits = new WeakHashMap<>();
                    permits.put(target, Boolean.TRUE);
                    exports.put(pkg, permits);
                }
            } else {
                // pkg already exported
                if (!permits.isEmpty()) {
                    if (target == null) {
                        // change from qualified to unqualified
                        exports.put(pkg, Collections.emptyMap());
                    } else {
                        // copy the existing qualified exports
                        permits = new WeakHashMap<>(permits);
                        permits.put(target, Boolean.TRUE);
                        exports.put(pkg, permits);
                    }
                }
            }

            // volatile write
            this.exports = exports;

        }
    }


    // -- misc --


    /**
     * Returns an input stream for reading a resource in this module. Returns
     * {@code null} if the resource is not in this module or access to the
     * resource is denied by the security manager.
     * The {@code name} is a {@code '/'}-separated path name that identifies
     * the resource.
     *
     * <p> If this module is an unnamed module, and the {@code ClassLoader} for
     * this module is not {@code null}, then this method is equivalent to
     * invoking the {@link ClassLoader#getResourceAsStream(String)
     * getResourceAsStream} method on the class loader for this module.
     *
     * @throws IOException
     *         If an I/O error occurs
     */
    public InputStream getResourceAsStream(String name) throws IOException {
        Objects.requireNonNull(name);

        if (isNamed()) {
            if (loader == null) {
                return BootLoader.getResourceAsStream(this.name, name);
            } else {
                // use SharedSecrets to invoke protected method
                return SharedSecrets.getJavaLangAccess()
                        .getResourceAsStream(loader, this.name, name);
            }
        }

        // unnamed module
        URL url;
        if (loader == null) {
            url = BootLoader.findResource(name);
        } else {
            url = loader.getResource(name);
        }
        try {
            return url != null ? url.openStream() : null;
        } catch (IOException e) {
            return null;
        }
    }


    /**
     * Returns the string representation.
     */
    @Override
    public String toString() {
        if (isNamed()) {
            return "module " + name;
        } else {
            String id = Integer.toHexString(System.identityHashCode(this));
            return "<unnamed module @" + id + ">";
        }
    }


    // -- native methods --

    // JVM_DefineModule
    private static native void jvmDefineModule(Module module,
                                               String version,
                                               String location,
                                               String[] pkgs);

    // JVM_AddReadsModule
    private static native void jvmAddReadsModule(Module from, Module to);

    // JVM_AddModuleExports
    private static native void jvmAddModuleExports(Module from, String pkg, Module to);

    // JVM_AddModulePackage
    private static native void jvmAddModulePackage(Module m, String pkg);

    /**
     * Register shared secret to provide access to package-private methods
     */
    static {
        SharedSecrets.setJavaLangReflectAccess(
            new JavaLangReflectAccess() {
                @Override
                public Module defineUnnamedModule(ClassLoader loader) {
                    return new Module(loader);
                }
                @Override
                public Module defineModule(ClassLoader loader, ModuleReference mref) {
                   return Module.defineModule(loader, mref);
                }
                @Override
                public Map<String, Module> defineModules(Configuration cf, ClassLoaderFinder clf) {
                    return Module.defineModules(cf, clf);
                }
                @Override
                public void addPackage(Module m, String pkg) {
                    m.addPackage(pkg);
                }
                @Override
                public void addReadsModule(Module m1, Module m2) {
                    m1.addReads(m2, true);
                }
                @Override
                public void addExports(Module m, String pkg, Module target) {
                    m.addExports(pkg, target);
                }
            });
    }
}
