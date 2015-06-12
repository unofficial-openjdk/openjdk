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
import java.util.stream.Stream;

import jdk.internal.module.ServicesCatalog;
import sun.misc.BootLoader;
import sun.misc.JavaLangReflectAccess;
import sun.misc.SharedSecrets;
import sun.misc.Unsafe;
import sun.security.util.SecurityConstants;

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
 * <p> The package names that are parameters or returned by methods defined in
 * this class are the fully-qualified names of the packages as defined in
 * section 6.5.3 of <cite>The Java&trade; Language Specification </cite>, for
 * example, {@code "java.lang"}. </p>
 *
 * @since 1.9
 * @see java.lang.Class#getModule
 */

public final class Module {

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
    // FIXME: This should be final; this will happen once we define the
    // modules in reverse topology order
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
            sm.checkPermission(SecurityConstants.GET_CLASSLOADER_PERMISSION);
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
     * <p> For named modules, the returned array contains an element for each
     * package in the module when it was initially created. It may contain
     * elements corresponding to packages added to the module after it was
     * created. </p>
     *
     * <p> For unnamed modules, this method is the equivalent of invoking
     * the {@link ClassLoader#getPackages() getPackages} method of this
     * module's class loader and returning the array of package names. </p>
     *
     * <p> A package name appears at most once in the returned array. </p>
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
            // unnamed module
            Stream<Package> packages;
            if (loader == null) {
                packages = BootLoader.packages();
            } else {
                packages = SharedSecrets.getJavaLangAccess().packages(loader);
            }
            return packages.map(Package::getName).toArray(String[]::new);
        }
    }


    // -- readability --


    /**
     * Updates this module to read the given {@code Module}. This method
     * is a no-op if {@code target} is {@code this} module (all modules can
     * read themselves) or this module is not a {@link #isNamed() named}
     * module (an unnamed module reads all other modules).
     *
     * <p> If {@code target} is {@code null}, and this module does not read
     * all unnamed modules, then this method changes this module so that it
     * reads all unnamed modules (both present and future) in the Java
     * virtual machine. </p>
     *
     * <p> If there is a security manager then its {@code checkPermission}
     * method is called with a {@code ReflectPermission("addReadsModule")}
     * permission to check that the caller is allowed to change the
     * readability graph. </p>
     *
     * @return this module
     *
     * @throws SecurityException
     *         If denied by the security manager
     *
     * @see #canRead
     */
    public Module addReads(Module target) {
        if (target != this && this.isNamed()) {
            SecurityManager sm = System.getSecurityManager();
            if (sm != null) {
                ReflectPermission perm = new ReflectPermission("addReadsModule");
                sm.checkPermission(perm);
            }
            implAddReads(target, true);
        }
        return this;
    }

    /**
     * Makes the given {@code Module} readable to this module without
     * notifying the VM.
     *
     * This method is for use by VM whitebox tests only.
     */
    void addReadsNoSync(Module target) {
        implAddReads(target, false);
    }

    /**
     * Makes the given {@code Module} readable to this module.
     *
     * If {@code syncVM} is {@code true} then the VM is notified.
     */
    private void implAddReads(Module target, boolean syncVM) {

        // nothing to do
        if (target == this || !this.isNamed())
            return;

        // if the target is null then change this module to be loose.
        if (target == null) {
            if (syncVM)
                addReadsModule0(this, null);
            this.loose = true;
            return;
        }

        // check if we already read this module
        Set<Module> reads = this.reads;
        if (reads.contains(target))
            return;

        // update VM first, just in case it fails
        if (syncVM)
            addReadsModule0(this, target);

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
     * Indicates if this module reads the given {@code Module}.
     * If {@code target} is {@code null} then this method tests if this
     * module reads all unnamed modules.
     *
     * @return {@code true} if this module reads {@code target}
     *
     * @see #addReads(Module)
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

    // for dynamic module to use
    void addReadsAll(Module target) {
        if (!target.isNamed()) {
            throw new IllegalArgumentException("can't require unnamed module");
        }
        if (this.isNamed()) {
            // add target and its dependences
            implAddReads(target, true);
            target.reads.stream().forEach(m -> implAddReads(m, true));
        }
    }

    // -- creating Module objects --

    /**
     * Define a new Module to the runtime. The resulting Module will be
     * defined to the VM but will not read any other modules or have any
     * exports. This method does not register the module with its class
     * loader or register the module in the service catalog.
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
            for (String pn : packages) {
                array[i++] = pn.replace('.', '/');
            }

            String vs = descriptor.version().map(Version::toString).orElse("");
            String loc = mref.location().map(URI::toString).orElse(null);

            defineModule0(m, vs, loc, array);
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

        // define each module in the configuration to the VM and register
        // with each class loader.
        for (ModuleReference mref : cf.references()) {
            String name = mref.descriptor().name();
            ClassLoader loader = clf.loaderForModule(name);
            Module m = defineModule(loader, mref);
            modules.put(name, m);
            loaders.put(name, loader);

            // register all modules (except java.base) with its class loader
            if (loader == null) {
                if (!mref.descriptor().name().equals("java.base"))
                   BootLoader.register(mref);
            } else {
                ((ModuleCapableLoader) loader).register(mref);
            }
        }

        // setup readability and exports
        for (ModuleDescriptor descriptor: cf.descriptors()) {
            Module m = modules.get(descriptor.name());
            assert m != null;

            // reads
            Set<Module> reads = new HashSet<>();
            for (ModuleDescriptor other: cf.reads(descriptor)) {
                String dn = other.name();
                Module m2 = modules.get(dn);
                Layer parent = cf.layer();
                if (m2 == null && parent != null)
                    m2 = parent.findModule(other.name()).orElse(null);
                if (m2 == null) {
                    throw new InternalError(descriptor.name() +
                            " reads unknown module: " + other.name());
                }
                reads.add(m2);

                // update VM view
                addReadsModule0(m, m2);
            }
            m.reads = reads;

            // exports
            Map<String, Map<Module, Boolean>> exports = new HashMap<>();
            for (Exports export: descriptor.exports()) {
                String source = export.source();
                String sourceInternalForm = source.replace('.', '/');
                if (!export.targets().isPresent()) {
                    exports.computeIfAbsent(source, k -> Collections.emptyMap());
                    // update VM view
                    addModuleExports0(m, sourceInternalForm , null);
                } else {
                    export.targets().get()
                        .forEach(mn -> {
                                // only export to modules that are in this configuration
                                Module m2 = modules.get(mn);
                                if (m2 != null) {
                                    exports.computeIfAbsent(source, k -> new HashMap<>())
                                        .put(m2, Boolean.TRUE);
                                    // update VM view
                                    addModuleExports0(m, sourceInternalForm, m2);
                                }
                            });
                }
            }
            m.exports = exports;
        }

        // register the modules in the service catalog if they provide services
        // ## FIXME: Need to decide whether to skip this if the configuration is
        //           not the result of service binding
        for (ModuleDescriptor descriptor: cf.descriptors()) {
            Map<String, Provides> services = descriptor.provides();
            if (!services.isEmpty()) {
                String name = descriptor.name();
                Module m = modules.get(name);
                ClassLoader loader = loaders.get(name);
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
    void addPackage(String pn) {
        implAddPackage(pn, true);
    }

    /**
     * Add a package to this module without notifying the VM.
     *
     * This method is for use by VM whitebox tests only.
     */
    void addPackageNoSync(String pn) {
        implAddPackage(pn.replace('/', '.'), false);
    }

    /**
     * Add a package to this module.
     *
     * If {@code syncVM} is {@code true} then the VM is notified.
     */
    private void implAddPackage(String pn, boolean syncVM) {
        if (pn.length() == 0)
            throw new IllegalArgumentException("<unnamed> package not allowed");

        synchronized (this) {
            // copy set
            Set<String> pns = new HashSet<>(this.packages);
            if (!pns.add(pn)) {
                // already has this package
                return;
            }

            // update VM first, just in case it fails
            if (syncVM)
                addModulePackage0(this, pn.replace('.', '/'));

            // replace with new set
            this.packages = pns; // volatile write
        }
    }


    // -- exports --

    /**
     * Returns {@code true} if this module exports the given package to the
     * given module.
     *
     * <p> If invoked on an unnamed module then this method always returns
     * {@code true} for any non-{@code null} package name. </p>
     *
     * <p> This method does not check if the given module reads this
     * module. </p>
     */
    public boolean isExported(String pn, Module target) {
        Objects.requireNonNull(pn);
        Objects.requireNonNull(target);
        return implIsExported(pn, target);
    }

    /**
     * Returns {@code true} if this module exports the given package
     * un-conditionally.
     *
     * <p> If invoked on an unnamed module then this method always returns
     * {@code true} for any non-{@code null} package name. </p>
     *
     * <p> This method does not check if the given module reads this
     * module. </p>
     */
    public boolean isExported(String pn) {
        Objects.requireNonNull(pn);
        return implIsExported(pn, null);
    }

    /**
     * Returns {@code true} if this module exports the given package to the
     * given module. If {@code target} is {@code null} then returns {@code
     * true} if the package is exported un-conditionally by this module.
     */
    private boolean implIsExported(String pn, Module target) {

        // all packages are exported by unnamed modules
        if (!isNamed())
            return true;

        Map<String, Map<Module, Boolean>>  exports = this.exports; // volatile read
        Map<Module, Boolean> targets = exports.get(pn);
        if (targets != null) {
            // unqualified export or exported to 'target'
            if (targets.isEmpty() || targets.containsKey(target)) return true;
        }

        // not exported
        return false;
    }

    /**
     * Adds a qualified export so that this module exports package {@code pn}
     * to a {@code target} module. This method has no effect if the package is
     * already exported to the target module. If also has no effect if invoked
     * on an unnamed module.
     *
     * <p> If there is a security manager then its {@code checkPermission}
     * method is called with a {@code ReflectPermission("addModuleExports")}
     * permission to check that the caller is allowed to do this operation. </p>
     *
     * @apiNote This method is intended for use by test libraries and frameworks
     * that need to break encapsulation and get to public types in otherwise
     * module-private packages. General use of this method is strongly
     * discouraged.
     *
     * @return this module
     *
     * @throws IllegalArgumentException
     *         If {@code pn} is not a package in this module
     * @throws SecurityException
     *         If denied by the security manager
     */
    public Module addExports(String pn, Module target) {
        Objects.requireNonNull(pn); // IAE or NPE for this?
        Objects.requireNonNull(target);

        if (isNamed()) {
            SecurityManager sm = System.getSecurityManager();
            if (sm != null) {
                ReflectPermission perm = new ReflectPermission("addModuleExports");
                sm.checkPermission(perm);
            }
            implAddExports(pn, target, true);
        }

        return this;
    }

    /**
     * Updates the exports so that package {@code pn} is exported to module
     * {@code target} but without notifying the VM.
     *
     * This method is for use by VM whitebox tests only.
     */
    void addExportsNoSync(String pn, Module target) {
        implAddExports(pn.replace('/', '.'), target, false);
    }

    /**
     * Updates the exports so that package {@code pn} is exported to module
     * {@code target}.
     *
     * If {@code syncVM} is {@code true} then the VM is notified.
     */
    private void implAddExports(String pn, Module target, boolean syncVM) {
        Objects.requireNonNull(pn);

        // unnamed module exports all packages
        if (!isNamed())
            return;

        if (!packages.contains(pn)) {
            throw new IllegalArgumentException("exported package " + pn +
                " not in contents");
        }

        synchronized (this) {

            // nothing to do if already exported to target
            if (implIsExported(pn, target))
                return;

            // update VM first, just in case it fails
            if (syncVM) {
                addModuleExports0(this, pn.replace('.', '/'), target);
            }

            // copy existing map
            Map<String, Map<Module, Boolean>> exports = new HashMap<>(this.exports);

            // the package may be exported already, need to handle all cases
            Map<Module, Boolean> targets = exports.get(pn);
            if (targets == null) {

                // package not previously exported

                if (target == null) {
                    // unqualified export
                    exports.put(pn, Collections.emptyMap());
                } else {
                    // qualified export to (possible transient) target. We
                    // insert a strongly reachable placeholder module into
                    // the map to ensure that it never becomes empty.
                    targets = new WeakHashMap<>();
                    targets.put(STRONGLY_REACHABLE_MODULE, Boolean.TRUE);
                    targets.put(target, Boolean.TRUE);

                    exports.put(pn, targets);
                }

            } else {

                // package already exported, nothing to do if already
                // exported unconditionally

                if (!targets.isEmpty()) {
                    if (target == null) {
                        // change from qualified to unqualified
                        exports.put(pn, Collections.emptyMap());
                    } else {
                        // extend existing qualified exports
                        assert (!(targets instanceof WeakHashMap))
                                || targets.containsKey(STRONGLY_REACHABLE_MODULE);
                        targets = new WeakHashMap<>(targets);
                        targets.putIfAbsent(STRONGLY_REACHABLE_MODULE, Boolean.TRUE);
                        targets.put(target, Boolean.TRUE);

                        exports.put(pn, targets);
                    }
                }
            }

            // volatile write
            this.exports = exports;

        }
    }

    // Dummy module that is the target for a qualified export. The dummy
    // module is strongly reachable and thus prevents the set of exports
    // from being empty when all other target modules have been GC'ed.
    private static final Module STRONGLY_REACHABLE_MODULE = new Module(null);


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
    private static native void defineModule0(Module module,
                                             String version,
                                             String location,
                                             String[] pns);

    // JVM_AddReadsModule
    private static native void addReadsModule0(Module from, Module to);

    // JVM_AddModuleExports
    private static native void addModuleExports0(Module from, String pn, Module to);

    // JVM_AddModulePackage
    private static native void addModulePackage0(Module m, String pn);

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
                public void addPackage(Module m, String pn) {
                    m.implAddPackage(pn, true);
                }
                @Override
                public void addReadsModule(Module m1, Module m2) {
                    m1.implAddReads(m2, true);
                }
                @Override
                public void addExports(Module m, String pn, Module target) {
                    m.implAddExports(pn, target, true);
                }
            });
    }
}
