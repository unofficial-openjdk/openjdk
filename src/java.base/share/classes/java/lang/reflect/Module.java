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
import java.lang.module.ModuleReference;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleDescriptor.Provides;
import java.lang.module.ModuleDescriptor.Version;
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

import jdk.internal.misc.BuiltinClassLoader;
import jdk.internal.module.ServicesCatalog;
import jdk.internal.misc.BootLoader;
import sun.misc.JavaLangReflectModuleAccess;
import sun.misc.SharedSecrets;
import sun.reflect.CallerSensitive;
import sun.reflect.Reflection;
import sun.security.util.SecurityConstants;

/**
 * Represents a run-time module, either {@link #isNamed() named} or unnamed.
 *
 * <p> Named modules have a {@link #getName() name} and are constructed by the
 * Java Virtual Machine when a {@link java.lang.module.Configuration
 * Configuration} is reified by creating a module {@link Layer Layer}. </p>
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

    // the layer that contains this module, can be null
    private final Layer layer;

    // module name and loader, these fields are read by VM
    private final String name;
    private final ClassLoader loader;

    // the module descriptor
    private final ModuleDescriptor descriptor;


    /**
     * Creates a new named Module. The resulting Module will be defined to the
     * VM but will not read any other modules, will not have any exports setup
     * and will not be registered in the service catalog.
     */
    private Module(Layer layer,
                   ClassLoader loader,
                   ModuleDescriptor descriptor,
                   URI uri)
    {
        this.layer = layer;
        this.name = descriptor.name();
        this.loader = loader;
        this.descriptor = descriptor;

        Set<String> packages = descriptor.packages();
        this.packages = packages;

        // define module to VM

        int n = packages.size();
        String[] array = new String[n];
        int i = 0;
        for (String pn : packages) {
            array[i++] = pn.replace('.', '/');
        }

        Version version = descriptor.version().orElse(null);
        String vs = Objects.toString(version, "");
        String loc = Objects.toString(uri, null);

        defineModule0(this, vs, loc, array);
    }


    /**
     * Create the unnamed Module for the given ClassLoader.
     *
     * @see ClassLoader#getUnnamedModule
     */
    private Module(ClassLoader loader) {
        this.layer = null;
        this.name = null;
        this.loader = loader;
        this.descriptor = null;

        // unnamed modules are loose
        this.loose = true;
    }


    /**
     * Creates a named module but without defining the module to the VM.
     *
     * @apiNote This constructor is for VM white-box testing.
     */
    Module(ClassLoader loader, ModuleDescriptor descriptor) {
        this.layer = null;
        this.name = descriptor.name();
        this.loader = loader;
        this.descriptor = descriptor;
        this.packages = descriptor.packages();
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
     * Returns the layer that contains this module or {@code null} if this
     * module is not in a layer.
     *
     * <p> A module {@code Layer} contains named modules and therefore this
     * method always returns {@code null} when invoked on an unnamed {@code
     * Module}. </p>
     *
     * <p> <i>Dynamic modules</i> are named modules that are generated at
     * runtime. A dynamic module may or may not be in a module Layer. </p>
     *
     * @return The layer that contains this module
     *
     * @see Layer#create
     * @see Proxy
     */
    public Layer getLayer() {
        if (isNamed()) {
            Layer layer = this.layer;
            if (layer != null)
                return layer;

            // special-case java.base as it is created before the boot Layer
            if (loader == null && name.equals("java.base")) {
                return SharedSecrets.getJavaLangAccess().getBootLayer();
            }
        }

        return null;
    }


    // -- readability --

    // true if this module reads all unnamed modules (a.k.a. loose module)
    private volatile boolean loose;

    // the modules that this module permanently reads
    // (will be final when the modules are defined in reverse topology order)
    private volatile Set<Module> reads = Collections.emptySet();

    // created lazily, additional modules that this module temporarily reads
    private volatile WeakSet<Module> transientReads;


    /**
     * Indicates if this module reads the given {@code Module}.
     * If {@code source} is {@code null} then this method tests if this
     * module reads all unnamed modules.
     *
     * @return {@code true} if this module reads {@code source}
     *
     * @see #addReads(Module)
     */
    public boolean canRead(Module source) {

        // an unnamed module reads all modules
        if (!this.isNamed())
            return true;

        // all modules read themselves
        if (source == this)
            return true;

        // test if this module is loose
        if (source == null)
            return this.loose;

        // check if module reads source
        if (source.isNamed()) {

            Set<Module> reads = this.reads; // volatile read
            if (reads.contains(source))
                return true;

        } else {

            // loose modules read all unnamed modules
            if (this.loose)
                return true;

        }

        // check if this module reads the source module temporarily
        WeakSet<Module> tr = this.transientReads; // volatile read
        if (tr != null && tr.contains(source))
            return true;

        return false;
    }

    /**
     * If the caller's module is this module then update this module to read
     * the given source {@code Module}.
     *
     * <p> This method is a no-op if {@code source} is {@code this} module (all
     * modules can read themselves) or this module is not a {@link #isNamed()
     * named} module (an unnamed module reads all other modules). </p>
     *
     * <p> If {@code source} is {@code null}, and this module does not read
     * all unnamed modules, then this method changes this module so that it
     * reads all unnamed modules (both present and future) in the Java
     * virtual machine. </p>
     *
     * @apiNote As this method can only be used to update the caller's module
     * then this method could be static and the IllegalStateException would
     * not be needed.
     *
     * @return this module
     *
     * @throws IllegalStateException
     *         If this is a named module and the caller is not this module
     *
     * @see #canRead
     */
    @CallerSensitive
    public Module addReads(Module source) {
        if (this.isNamed()) {
            Module caller = Reflection.getCallerClass().getModule();
            if (caller != this) {
                throw new IllegalStateException(caller + " != " + this);
            }
            implAddReads(source, true);
        }
        return this;
    }

    /**
     * Updates this module to read the source module.
     *
     * @apiNote This method is for Proxy use and white-box testing.
     */
    void implAddReads(Module source) {
        implAddReads(source, true);
    }

    /**
     * Makes the given {@code Module} readable to this module without
     * notifying the VM.
     *
     * @apiNote This method is for VM white-box testing.
     */
    void implAddReadsNoSync(Module source) {
        implAddReads(source, false);
    }

    /**
     * Makes the given {@code Module} readable to this module.
     *
     * If {@code syncVM} is {@code true} then the VM is notified.
     */
    private void implAddReads(Module source, boolean syncVM) {

        // nothing to do
        if (source == this || !this.isNamed())
            return;

        // if the source is null then change this module to be loose.
        if (source == null) {
            if (syncVM)
                addReads0(this, null);
            this.loose = true;
            return;
        }

        // check if we already read this module
        Set<Module> reads = this.reads;
        if (reads.contains(source))
            return;

        // update VM first, just in case it fails
        if (syncVM)
            addReads0(this, source);

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
        tr.add(source);
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


    // -- exports --

    // Module exports. The key is the package name; The value is a map of
    // the modules that the package is exported to. When exports are
    // changed at run-time then the value is a WeakHashMap to allow for
    // target Modules to be GC'ed.
    private volatile Map<String, Map<Module, Boolean>> exports = Collections.emptyMap();

    // Placeholder module: if a package is exported to this Module then it
    // means the package is exported to all modules
    private static final Module EVERYONE_MODULE = new Module(null);

    private static final Map<Module, Boolean> EVERYONE
        = Collections.singletonMap(EVERYONE_MODULE, Boolean.TRUE);

    // Placeholder module: if a package is exported to this Module then it
    // means the package is exported to all unnamed modules
    private static final Module ALL_UNNAMED_MODULE = new Module(null);


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
     * unconditionally.
     *
     * <p> If invoked on an unnamed module then this method always returns
     * {@code true} for any non-{@code null} package name. </p>
     */
    public boolean isExported(String pn) {
        Objects.requireNonNull(pn);
        return implIsExported(pn, EVERYONE_MODULE);
    }

    /**
     * Returns {@code true} if this module exports the given package to the
     * given module. If the target module is {@code EVERYONE_MODULE} then
     * this method tests if the package is exported unconditionally.
     */
    private boolean implIsExported(String pn, Module target) {

        // all packages are exported by unnamed modules
        if (!isNamed())
            return true;

        Map<String, Map<Module, Boolean>>  exports = this.exports; // volatile read
        Map<Module, Boolean> targets = exports.get(pn);

        if (targets != null) {

            // exported to all
            if (targets.containsKey(EVERYONE_MODULE))
                return true;

            // exported to target
            if (targets.containsKey(target))
                return true;

            // target is an unnamed module && exported to all unnamed modules
            if (!target.isNamed() && targets.containsKey(ALL_UNNAMED_MODULE))
                return true;

        }

        // not exported or not exported to target
        return false;
    }

    /**
     * If the caller's module is this module then update this module to export
     * package {@code pn} to the given {@code target} module.
     *
     * <p> This method has no effect if the package is already exported to the
     * target module. If also has no effect if invoked on an unnamed module.
     * </p>
     *
     * @apiNote As this method can only be used to update the caller's module
     * then this method could be static and the IllegalStateException would
     * not be needed.
     *
     * @implNote Augmenting the exports is potentially an expensive operation,
     * it is not expected to be used very often.
     *
     * @return this module
     *
     * @throws IllegalArgumentException
     *         If {@code pn} is {@code null}, or this is a named module and the
     *         package {@code pn} is not a package in this module
     * @throws IllegalStateException
     *         If this is a named module and the caller is not this module
     */
    @CallerSensitive
    public Module addExports(String pn, Module target) {
        if (pn == null)
            throw new IllegalArgumentException("package is null");
        Objects.requireNonNull(target);

        if (isNamed()) {
            Module caller = Reflection.getCallerClass().getModule();
            if (caller != this) {
                throw new IllegalStateException(caller + " != " + this);
            }
            implAddExports(pn, target, true);
        }

        return this;
    }

    /**
     * Updates the exports so that package {@code pn} is exported to module
     * {@code target} but without notifying the VM.
     *
     * @apiNote This method is for VM white-box testing.
     */
    void implAddExportsNoSync(String pn, Module target) {
        if (target == null)
            target = EVERYONE_MODULE;
        implAddExports(pn.replace('/', '.'), target, false);
    }

    /**
     * Updates the exports so that package {@code pn} is exported to module
     * {@code target}.
     *
     * @apiNote This method is for white-box testing.
     */
    void implAddExports(String pn, Module target) {
        implAddExports(pn, target, true);
    }

    /**
     * Updates the exports so that package {@code pn} is exported to module
     * {@code target}.
     *
     * If {@code syncVM} is {@code true} then the VM is notified.
     */
    private void implAddExports(String pn, Module target, boolean syncVM) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(pn);

        // unnamed modules export all packages
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
                String pkgInternalForm = pn.replace('.', '/');
                if (target == EVERYONE_MODULE) {
                    addExportsToAll0(this, pkgInternalForm);
                } else if (target == ALL_UNNAMED_MODULE) {
                    addExportsToAllUnnamed0(this, pkgInternalForm);
                } else {
                    addExports0(this, pkgInternalForm, target);
                }
            }

            // copy existing map
            Map<String, Map<Module, Boolean>> exports = new HashMap<>(this.exports);

            if (target == EVERYONE_MODULE) {

                // export to everyone
                exports.put(pn, EVERYONE);

            } else {

                // the package may or may not be exported already
                Map<Module, Boolean> targets = exports.get(pn);
                if (targets == null) {
                    // not already exported
                    targets = new WeakHashMap<>();
                } else {
                    // already exported, need to copy
                    targets = new WeakHashMap<>(targets);
                }

                targets.put(target, Boolean.TRUE);
                exports.put(pn, targets);

            }

            // volatile write
            this.exports = exports;
        }
    }


    // -- services --

    // created lazily, additional service types that this module uses
    private volatile WeakSet<Class<?>> transientUses;

    /**
     * If the caller's module is this module then update this module to add a
     * service dependence on the given service type. This method is intended
     * for use by frameworks that invoke {@link java.util.ServiceLoader
     * ServiceLoader} on behalf of other modules or where the framework is
     * passed a reference to the service type by other code.
     *
     * <p> This method does not trigger {@link java.lang.module.Configuration#bind
     * service-binding}. </p>
     *
     * @apiNote As this method can only be used to update the caller's module
     * then this method could be static and the IllegalStateException would
     * not be needed.
     *
     * @return this module
     *
     * @throws IllegalStateException
     *         If this is a named module and the caller is not this module
     *
     * @see #canUse(Class)
     * @see ModuleDescriptor#uses()
     */
    @CallerSensitive
    public Module addUses(Class<?> st) {
        Objects.requireNonNull(st);

        if (isNamed()) {

            Module caller = Reflection.getCallerClass().getModule();
            if (caller != this) {
                throw new IllegalStateException(caller + " != " + this);
            }

            if (!canUse(st)) {
                WeakSet<Class<?>> uses = this.transientUses;
                if (uses == null) {
                    synchronized (this) {
                        uses = this.transientUses;
                        if (uses == null) {
                            uses = new WeakSet<>();
                            this.transientUses = uses;
                        }
                    }
                }
                uses.add(st);
            }

        }

        return this;
    }

    /**
     * Indicates if this module has a service dependence on the given type.
     *
     * @return {@code true} if this module uses service type {@code st}
     *
     * @see #addUses(Class)
     */
    public boolean canUse(Class<?> st) {
        Objects.requireNonNull(st);

        if (!isNamed())
            return true;

        if (SharedSecrets.getJavaLangModuleAccess().isAutomatic(descriptor))
            return true;

        // uses was declared
        if (descriptor.uses().contains(st.getName()))
            return true;

        // uses added via addUses
        WeakSet<Class<?>> uses = this.transientUses;
        if (uses != null && uses.contains(st))
            return true;

        return false;
    }



    // -- packages --

    // The set of packages in the module if this is a named module.
    // The field is volatile as it may be replaced at run-time
    private volatile Set<String> packages;

    /**
     * Returns an array of the package names of the packages in this module.
     *
     * <p> For named modules, the returned array contains an element for each
     * package in the module when it was initially created. It may contain
     * elements corresponding to packages added to the module after it was
     * created. </p>
     *
     * <p> For unnamed modules, this method is the equivalent of invoking the
     * {@link ClassLoader#getDefinedPackages() getDefinedPackages} method of
     * this module's class loader and returning the array of package names. </p>
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

    /**
     * Add a package to this module.
     *
     * @apiNote This method is for Proxy use.
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
     * @apiNote This method is VM white-box testing.
     */
    void implAddPackageNoSync(String pn) {
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
                addPackage0(this, pn.replace('.', '/'));

            // replace with new set
            this.packages = pns; // volatile write
        }
    }



    // -- creating Module objects --

    /**
     * Defines each of the module in the given configuration to the runtime.
     *
     * @return a map of module name to runtime {@code Module}
     *
     * @throws IllegalArgumentException
     *         If defining any of the modules to the VM fails
     */
    static Map<String, Module> defineModules(Configuration cf,
                                             Layer.ClassLoaderFinder clf,
                                             Layer layer)
    {
        Map<String, Module> modules = new HashMap<>();
        Map<String, ClassLoader> loaders = new HashMap<>();

        // define each module in the configuration to the VM
        for (ModuleReference mref : cf.modules()) {
            ModuleDescriptor descriptor = mref.descriptor();
            String name = descriptor.name();
            ClassLoader loader = clf.loaderForModule(name);
            URI uri = mref.location().orElse(null);

            Module m;
            if (loader == null && name.equals("java.base")) {
                m = Object.class.getModule();
            } else {
                m = new Module(layer, loader, descriptor, uri);
            }

            modules.put(name, m);
            loaders.put(name, loader);
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
                addReads0(m, m2);
            }
            m.reads = reads;

            // automatic modules reads all unnamed modules
            if (SharedSecrets.getJavaLangModuleAccess().isAutomatic(descriptor)) {
                m.implAddReads(null, true);
            }

            // exports
            Map<String, Map<Module, Boolean>> exports = new HashMap<>();
            for (Exports export: descriptor.exports()) {
                String source = export.source();
                String sourceInternalForm = source.replace('.', '/');

                if (!export.targets().isPresent()) {

                    // unqualified export
                    exports.put(source, EVERYONE);
                    addExportsToAll0(m, sourceInternalForm);

                } else {

                    // qualified export
                    Map<Module, Boolean> targets = new HashMap<>();
                    for (String target : export.targets().get()) {
                        // only export to modules that are in this configuration
                        Module m2 = modules.get(target);
                        if (m2 != null) {
                            targets.put(m2, Boolean.TRUE);
                            addExports0(m, sourceInternalForm, m2);
                        }
                    }
                    if (!targets.isEmpty()) {
                        exports.put(source, targets);
                    }

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
     * @param  name
     *         The resource name
     *
     * @throws IOException
     *         If an I/O error occurs
     *
     * @see java.lang.module.ModuleReader#open(String)
     */
    public InputStream getResourceAsStream(String name) throws IOException {
        Objects.requireNonNull(name);

        URL url = null;

        if (isNamed()) {
            String mn = this.name;

            // special-case built-in class loaders to avoid URL connection
            if (loader == null) {
                return BootLoader.findResourceAsStream(mn, name);
            } else if (loader instanceof BuiltinClassLoader) {
                return ((BuiltinClassLoader) loader).findResourceAsStream(mn, name);
            }

            // use SharedSecrets to invoke protected method
            url = SharedSecrets.getJavaLangAccess().findResource(loader, mn, name);

        } else {

            // unnamed module
            if (loader == null) {
                url = BootLoader.findResource(name);
            } else {
                return loader.getResourceAsStream(name);
            }

        }

        // fallthrough to URL case
        if (url != null) {
            try {
                return url.openStream();
            } catch (SecurityException e) { }
        }

        return null;
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
    private static native void addReads0(Module from, Module to);

    // JVM_AddModuleExports
    private static native void addExports0(Module from, String pn, Module to);

    // JVM_AddModuleExportsToAll
    private static native void addExportsToAll0(Module from, String pn);

    // JVM_AddModuleExportsToAllUnnamed
    private static native void addExportsToAllUnnamed0(Module from, String pn);

    // JVM_AddModulePackage
    private static native void addPackage0(Module m, String pn);

    /**
     * Register shared secret to provide access to package-private methods
     */
    static {
        SharedSecrets.setJavaLangReflectModuleAccess(
            new JavaLangReflectModuleAccess() {
                @Override
                public Module defineUnnamedModule(ClassLoader loader) {
                    return new Module(loader);
                }
                @Override
                public Module defineModule(ClassLoader loader,
                                           ModuleDescriptor descriptor,
                                           URI uri) {
                   return new Module(null, loader, descriptor, uri);
                }
                @Override
                public void addReads(Module m1, Module m2) {
                    m1.implAddReads(m2, true);
                }
                @Override
                public void addExports(Module m, String pn, Module target) {
                    m.implAddExports(pn, Objects.requireNonNull(target), true);
                }
                @Override
                public void addExportsToAll(Module m, String pn) {
                    m.implAddExports(pn, Module.EVERYONE_MODULE, true);
                }
                @Override
                public void addExportsToAllUnnamed(Module m, String pn) {
                    m.implAddExports(pn, Module.ALL_UNNAMED_MODULE, true);
                }
                @Override
                public void addPackage(Module m, String pn) {
                    m.implAddPackage(pn, true);
                }
            });
    }
}
