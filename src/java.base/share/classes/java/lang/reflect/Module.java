/*
 * Copyright (c) 2014, 2016, Oracle and/or its affiliates. All rights reserved.
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
import java.lang.annotation.Annotation;
import java.lang.module.Configuration;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleDescriptor.Provides;
import java.lang.module.ModuleDescriptor.Version;
import java.lang.module.ResolvedModule;
import java.net.URI;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

import jdk.internal.loader.BuiltinClassLoader;
import jdk.internal.loader.BootLoader;
import jdk.internal.loader.ResourceHelper;
import jdk.internal.misc.JavaLangAccess;
import jdk.internal.misc.JavaLangReflectModuleAccess;
import jdk.internal.misc.SharedSecrets;
import jdk.internal.module.ServicesCatalog;
import jdk.internal.org.objectweb.asm.AnnotationVisitor;
import jdk.internal.org.objectweb.asm.Attribute;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.reflect.CallerSensitive;
import jdk.internal.reflect.Reflection;
import sun.security.util.SecurityConstants;

/**
 * Represents a run-time module, either {@link #isNamed() named} or unnamed.
 *
 * <p> Named modules have a {@link #getName() name} and are constructed by the
 * Java Virtual Machine when a graph of modules is defined to the Java virtual
 * machine to create a module {@link Layer Layer}. </p>
 *
 * <p> An unnamed module does not have a name. There is an unnamed module
 * per {@link ClassLoader ClassLoader} that is obtained by invoking the class
 * loader's {@link ClassLoader#getUnnamedModule() getUnnamedModule} method. The
 * {@link Class#getModule() getModule} method of all types defined by a class
 * loader that are not in a named module return the class loader's unnamed
 * module. </p>
 *
 * <p> The package names that are parameters or returned by methods defined in
 * this class are the fully-qualified names of the packages as defined in
 * section 6.5.3 of <cite>The Java&trade; Language Specification </cite>, for
 * example, {@code "java.lang"}. </p>
 *
 * <p> Unless otherwise specified, passing a {@code null} argument to a method
 * in this class causes a {@link NullPointerException NullPointerException} to
 * be thrown. </p>
 *
 * @since 9
 * @see java.lang.Class#getModule
 */

public final class Module implements AnnotatedElement {

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

        // define module to VM

        Set<String> packages = descriptor.packages();
        int n = packages.size();
        String[] array = new String[n];
        int i = 0;
        for (String pn : packages) {
            array[i++] = pn.replace('.', '/');
        }
        Version version = descriptor.version().orElse(null);
        String vs = Objects.toString(version, null);
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
    }



    /**
     * Returns {@code true} if this module is a named module.
     *
     * @return {@code true} if this is a named module
     *
     * @see ClassLoader#getUnnamedModule()
     */
    public boolean isNamed() {
        return name != null;
    }

    /**
     * Returns the module name or {@code null} if this module is an unnamed
     * module.
     *
     * @return The module name
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
     * @return The class loader for this module
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
     * Returns the module descriptor for this module or {@code null} if this
     * module is an unnamed module.
     *
     * @return The module descriptor for this module
     */
    public ModuleDescriptor getDescriptor() {
        return descriptor;
    }

    /**
     * Returns the layer that contains this module or {@code null} if this
     * module is not in a layer.
     *
     * A module {@code Layer} contains named modules and therefore this
     * method always returns {@code null} when invoked on an unnamed module.
     *
     * <p> <a href="Proxy.html#dynamicmodule">Dynamic modules</a> are named
     * modules that are generated at runtime. A dynamic module may or may
     * not be in a module Layer. </p>
     *
     * @return The layer that contains this module
     *
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


    // --

    // the special Module to mean reads or exported to "all unnamed modules"
    private static final Module ALL_UNNAMED_MODULE = new Module(null);

    // special Module to mean exported to "everyone"
    private static final Module EVERYONE_MODULE = new Module(null);


    // -- readability --

    // the modules that this module reads
    private volatile Set<Module> reads;

    // additional module (2nd key) that some module (1st key) reflectively reads
    private static final WeakPairMap<Module, Module, Boolean> reflectivelyReads
        = new WeakPairMap<>();


    /**
     * Indicates if this module reads the given module. This method returns
     * {@code true} if invoked to test if this module reads itself. It also
     * returns {@code true} if invoked on an unnamed module (as unnamed
     * modules read all modules).
     *
     * @param  other
     *         The other module
     *
     * @return {@code true} if this module reads {@code other}
     *
     * @see #addReads(Module)
     */
    public boolean canRead(Module other) {
        Objects.requireNonNull(other);

        // an unnamed module reads all modules
        if (!this.isNamed())
            return true;

        // all modules read themselves
        if (other == this)
            return true;

        // check if this module reads other
        if (other.isNamed()) {
            Set<Module> reads = this.reads; // volatile read
            if (reads != null && reads.contains(other))
                return true;
        }

        // check if this module reads the other module reflectively
        if (reflectivelyReads.containsKeyPair(this, other))
            return true;

        // if other is an unnamed module then check if this module reads
        // all unnamed modules
        if (!other.isNamed()
            && reflectivelyReads.containsKeyPair(this, ALL_UNNAMED_MODULE))
            return true;

        return false;
    }

    /**
     * If the caller's module is this module then update this module to read
     * the given module.
     *
     * This method is a no-op if {@code other} is this module (all modules can
     * read themselves) or this module is an unnamed module (as unnamed modules
     * read all modules).
     *
     * @param  other
     *         The other module
     *
     * @return this module
     *
     * @throws IllegalStateException
     *         If this is a named module and the caller is not this module
     *
     * @see #canRead
     */
    @CallerSensitive
    public Module addReads(Module other) {
        Objects.requireNonNull(other);
        if (this.isNamed()) {
            Module caller = Reflection.getCallerClass().getModule();
            if (caller != this) {
                throw new IllegalStateException(caller + " != " + this);
            }
            implAddReads(other, true);
        }
        return this;
    }

    /**
     * Updates this module to read another module.
     *
     * @apiNote This method is for Proxy use and white-box testing.
     */
    void implAddReads(Module other) {
        implAddReads(other, true);
    }

    /**
     * Updates this module to read another module without notifying the VM.
     *
     * @apiNote This method is for VM white-box testing.
     */
    void implAddReadsNoSync(Module other) {
        implAddReads(other, false);
    }

    /**
     * Makes the given {@code Module} readable to this module.
     *
     * If {@code syncVM} is {@code true} then the VM is notified.
     */
    private void implAddReads(Module other, boolean syncVM) {
        Objects.requireNonNull(other);

        // nothing to do
        if (other == this || !this.isNamed())
            return;

        // check if we already read this module
        Set<Module> reads = this.reads;
        if (reads != null && reads.contains(other))
            return;

        // update VM first, just in case it fails
        if (syncVM) {
            if (other == ALL_UNNAMED_MODULE) {
                addReads0(this, null);
            } else {
                addReads0(this, other);
            }
        }

        // add reflective read
        reflectivelyReads.putIfAbsent(this, other, Boolean.TRUE);
    }


    // -- exports --

    // the packages that are exported

    // package name (key) -> exported-private? (value)
    private volatile Map<String, Boolean> unqualifiedExports;

    // package name (key) -> (target-module, exported-private?) (value)
    private volatile Map<String, Map<Module, Boolean>> qualifiedExports;


    // additional exports added at run-time
    // this module (1st key), other module (2nd key)
    // (package name, exported-private?) (value)
    private static final WeakPairMap<Module, Module, Map<String, Boolean>>
        reflectivelyExports = new WeakPairMap<>();


    /**
     * Returns {@code true} if this module exports (or <em>exports-private</em>)
     * the given package to at least the given module.
     *
     * <p> This method always returns {@code true} when invoked on an unnamed
     * module. </p>
     *
     * <p> This method does not check if the given module reads this module. </p>
     *
     * @param  pn
     *         The package name
     * @param  other
     *         The other module
     *
     * @return {@code true} if this module exports the package to at least the
     *         given module
     */
    public boolean isExported(String pn, Module other) {
        Objects.requireNonNull(pn);
        Objects.requireNonNull(other);
        return implIsExported(pn, false, other);
    }

    /**
     * Returns {@code true} if this module <em>exports-private</em> the given
     * package to at least the given module.
     *
     * <p> This method always returns {@code true} when invoked on an unnamed
     * module. </p>
     *
     * <p> This method does not check if the given module reads this module. </p>
     *
     * @param  pn
     *         The package name
     * @param  other
     *         The other module
     *
     * @return {@code true} if this module <em>exports-private</em>  the package
     *         to at least the given module
     *
     * @see AccessibleObject#setAccessible(boolean)
     */
    public boolean isExportedPrivate(String pn, Module other) {
        Objects.requireNonNull(pn);
        Objects.requireNonNull(other);
        return implIsExported(pn, true, other);
    }

    /**
     * Returns {@code true} if this module exports (or <em>exports-private</em>)
     * the given package unconditionally.
     *
     * <p> This method always returns {@code true} when invoked on an unnamed
     * module. </p>
     *
     * <p> This method does not check if the given module reads this module. </p>
     *
     * @param  pn
     *         The package name
     *
     * @return {@code true} if this module exports the package unconditionally
     */
    public boolean isExported(String pn) {
        Objects.requireNonNull(pn);
        return implIsExported(pn, false, EVERYONE_MODULE);
    }

    /**
     * Returns {@code true} if this module <em>exports-private</em> the given
     * package unconditionally.
     *
     * <p> This method always returns {@code true} when invoked on an unnamed
     * module. </p>
     *
     * <p> This method does not check if the given module reads this module. </p>
     *
     * @param  pn
     *         The package name
     *
     * @return {@code true} if this module <em>exports-private</em>  the package
     *         unconditionally
     */
    public boolean isExportedPrivate(String pn) {
        Objects.requireNonNull(pn);
        return implIsExported(pn, true, EVERYONE_MODULE);
    }


    /**
     * Returns {@code true} if this module exports the given package to the
     * given module. If the other module is {@code EVERYONE_MODULE} then
     * this method tests if the package is exported unconditionally.
     */
    private boolean implIsExported(String pn, boolean nonPublic, Module other) {

        // all packages are exported-private by unnamed modules
        if (!isNamed())
            return true;

        // exported via module declaration/descriptor
        if (isExportedStatically(pn, nonPublic, other))
            return true;

        // exported via addExports
        if (isExportedReflectively(pn, nonPublic, other))
            return true;

        // not exported or not exported to other
        return false;
    }

    /**
     * Returns {@code true} if this module statically exports the given
     * package to the given module.
     */
    private boolean isExportedStatically(String pn, boolean nonPublic, Module other) {
        // exported unconditionally?
        Map<String, Boolean> unqualifiedExports = this.unqualifiedExports;
        if (unqualifiedExports != null) {
            Boolean b = unqualifiedExports.get(pn);
            if (b != null) {
                boolean exportedPrivate = b.booleanValue();
                if (!nonPublic || exportedPrivate) return true;
            }
        }

        // qualified export
        if (other != EVERYONE_MODULE && other != ALL_UNNAMED_MODULE) {
            Map<String, Map<Module, Boolean>> qualifiedExports = this.qualifiedExports;
            if (qualifiedExports != null) {
                Map<Module, Boolean> targets = qualifiedExports.get(pn);
                if (targets != null) {
                    Boolean b = targets.get(other);
                    if (b != null) {
                        boolean exportedPrivate = b.booleanValue();
                        if (!nonPublic || exportedPrivate) return true;
                    }
                }
            }
        }

        return false;
    }


    /**
     * Returns {@code true} if this module reflectively exports the given
     * package package to the given module.
     */
    private boolean isExportedReflectively(String pn, boolean nonPublic, Module other) {
        // exported to all modules
        Map<String, Boolean> exports = reflectivelyExports.get(this, EVERYONE_MODULE);
        if (exports != null) {
            Boolean b = exports.get(pn);
            if (b != null) {
                boolean exportedPrivate = b.booleanValue();
                if (!nonPublic || exportedPrivate) return true;
            }
        }

        if (other != EVERYONE_MODULE) {

            // exported to other
            exports = reflectivelyExports.get(this, other);
            if (exports != null) {
                Boolean b = exports.get(pn);
                if (b != null) {
                    boolean exportedPrivate = b.booleanValue();
                    if (!nonPublic || exportedPrivate) return true;
                }
            }

            // other is an unnamed module && exported to all unnamed
            if (!other.isNamed()) {
                exports = reflectivelyExports.get(this, ALL_UNNAMED_MODULE);
                if (exports != null) {
                    Boolean b = exports.get(pn);
                    if (b != null) {
                        boolean exportedPrivate = b.booleanValue();
                        if (!nonPublic || exportedPrivate) return true;
                    }
                }
            }

        }

        return false;
    }


    /**
     * If the caller's module is this module then update this module to export
     * the given package to the given module.
     *
     * <p> This method has no effect if the package is already exported (or
     * <em>exported private</em>) to the given module. It also has no effect if
     * invoked on an unnamed module (as unnamed modules <em>exports-private</em>
     * all packages). </p>
     *
     * @param  pn
     *         The package name
     * @param  other
     *         The module
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
    public Module addExports(String pn, Module other) {
        if (pn == null)
            throw new IllegalArgumentException("package is null");
        Objects.requireNonNull(other);

        if (isNamed()) {
            Module caller = Reflection.getCallerClass().getModule();
            if (caller != this) {
                throw new IllegalStateException(caller + " != " + this);
            }
            implAddExports(pn, other, false, true);
        }

        return this;
    }

    /**
     * If the caller's module is this module then update this module to
     * <em>exports-private</em> the given package to the given module.
     * Exporting a package with this method allows all types in the package,
     * and all their members, not just public types and their public members,
     * to be reflected on by the given module when using APIs that bypass or
     * suppress default Java language access control checks.
     *
     * <p> This method has no effect if the package is already <em>exported
     * private</em> to the given module. It also has no effect if invoked on an
     * unnamed module (as unnamed modules <em>exports-private</em> all
     * packages). </p>
     *
     * @param  pn
     *         The package name
     * @param  other
     *         The module
     *
     * @return this module
     *
     * @throws IllegalArgumentException
     *         If {@code pn} is {@code null}, or this is a named module and the
     *         package {@code pn} is not a package in this module
     * @throws IllegalStateException
     *         If this is a named module and the caller is not this module
     *
     * @see AccessibleObject#setAccessible(boolean)
     */
    @CallerSensitive
    public Module addExportsPrivate(String pn, Module other) {
        if (pn == null)
            throw new IllegalArgumentException("package is null");
        Objects.requireNonNull(other);

        if (isNamed()) {
            Module caller = Reflection.getCallerClass().getModule();
            if (caller != this) {
                throw new IllegalStateException(caller + " != " + this);
            }
            implAddExports(pn, other, true, true);
        }

        return this;
    }


    /**
     * Updates the exports so that package {@code pn} is exported to module
     * {@code other} but without notifying the VM.
     *
     * @apiNote This method is for VM white-box testing.
     */
    void implAddExportsNoSync(String pn, Module other) {
        if (other == null)
            other = EVERYONE_MODULE;
        implAddExports(pn.replace('/', '.'), other, false, false);
    }

    /**
     * Updates the exports so that package {@code pn} is exported to module
     * {@code other}.
     *
     * @apiNote This method is for white-box testing.
     */
    void implAddExports(String pn, Module other) {
        implAddExports(pn, other, false, true);
    }

    /**
     * Updates the exports so that package {@code pn} is <em>exports-private</em>
     * to module {@code other}.
     *
     * @apiNote This method is for white-box testing.
     */
    void implAddExportsPrivate(String pn, Module other) {
        implAddExports(pn, other, true, true);
    }

    /**
     * Updates the exports so that package {@code pn} is exported to module
     * {@code other}.
     *
     * If {@code syncVM} is {@code true} then the VM is notified.
     */
    private void implAddExports(String pn,
                                Module other,
                                boolean nonPublic,
                                boolean syncVM) {
        Objects.requireNonNull(other);
        Objects.requireNonNull(pn);

        // unnamed modules export all packages
        if (!isNamed())
            return;

        // nothing to do if already exported to other
        if (implIsExported(pn, nonPublic, other))
            return;

        // can only export a package in the module
        if (!containsPackage(pn)) {
            throw new IllegalArgumentException("package " + pn
                                               + " not in contents");
        }

        // update VM first, just in case it fails
        if (syncVM) {
            String pkgInternalForm = pn.replace('.', '/');
            if (other == EVERYONE_MODULE) {
                addExportsToAll0(this, pkgInternalForm);
            } else if (other == ALL_UNNAMED_MODULE) {
                addExportsToAllUnnamed0(this, pkgInternalForm);
            } else {
                addExports0(this, pkgInternalForm, other);
            }
        }

        // add package name to reflectivelyExports if absent
        Map<String, Boolean> map = reflectivelyExports
            .computeIfAbsent(this, other,
                             (m1, m2) -> new ConcurrentHashMap<>());

        if (nonPublic) {
            map.put(pn, Boolean.TRUE);  // may need to promote from FALSE to TRUE
        } else {
            map.putIfAbsent(pn, Boolean.FALSE);
        }
    }


    // -- services --

    // additional service type (2nd key) that some module (1st key) uses
    private static final WeakPairMap<Module, Class<?>, Boolean> reflectivelyUses
        = new WeakPairMap<>();

    /**
     * If the caller's module is this module then update this module to add a
     * service dependence on the given service type. This method is intended
     * for use by frameworks that invoke {@link java.util.ServiceLoader
     * ServiceLoader} on behalf of other modules or where the framework is
     * passed a reference to the service type by other code. This method is
     * a no-op when invoked on an unnamed module.
     *
     * <p> This method does not cause {@link
     * Configuration#resolveRequiresAndUses resolveRequiresAndUses} to be
     * re-run. </p>
     *
     * @param  service
     *         The service type
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
    public Module addUses(Class<?> service) {
        Objects.requireNonNull(service);

        if (isNamed()) {
            Module caller = Reflection.getCallerClass().getModule();
            if (caller != this) {
                throw new IllegalStateException(caller + " != " + this);
            }
            implAddUses(service);
        }

        return this;
    }

    /**
     * Update this module to add a service dependence on the given service
     * type.
     */
    void implAddUses(Class<?> service) {
        if (!canUse(service)) {
            reflectivelyUses.putIfAbsent(this, service, Boolean.TRUE);
        }
    }


    /**
     * Indicates if this module has a service dependence on the given service
     * type. This method always returns {@code true} when invoked on an unnamed
     * module.
     *
     * @param  service
     *         The service type
     *
     * @return {@code true} if this module uses service type {@code st}
     *
     * @see #addUses(Class)
     */
    public boolean canUse(Class<?> service) {
        Objects.requireNonNull(service);

        if (!isNamed())
            return true;

        if (descriptor.isAutomatic())
            return true;

        // uses was declared
        if (descriptor.uses().contains(service.getName()))
            return true;

        // uses added via addUses
        return reflectivelyUses.containsKeyPair(this, service);
    }



    // -- packages --

    // Additional packages that are added to the module at run-time.
    // The field is volatile as it may be replaced at run-time
    private volatile Set<String> extraPackages;

    private boolean containsPackage(String pn) {
        if (descriptor.packages().contains(pn))
            return true;
        Set<String> extraPackages = this.extraPackages;
        if (extraPackages != null && extraPackages.contains(pn))
            return true;
        return false;
    }


    /**
     * Returns an array of the package names of the packages in this module.
     *
     * <p> For named modules, the returned array contains an element for each
     * package in the module. It may contain elements corresponding to packages
     * added to the module, <a href="Proxy.html#dynamicmodule">dynamic modules</a>
     * for example, after it was loaded.
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

            Set<String> packages = descriptor.packages();
            Set<String> extraPackages = this.extraPackages;
            if (extraPackages == null) {
                return packages.toArray(new String[0]);
            } else {
                return Stream.concat(packages.stream(),
                                     extraPackages.stream())
                        .toArray(String[]::new);
            }

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

        if (descriptor.packages().contains(pn)) {
            // already in module
            return;
        }

        Set<String> extraPackages = this.extraPackages;
        if (extraPackages != null && extraPackages.contains(pn)) {
            // already added
            return;
        }
        synchronized (this) {
            // recheck under lock
            extraPackages = this.extraPackages;
            if (extraPackages != null) {
                if (extraPackages.contains(pn)) {
                    // already added
                    return;
                }

                // copy the set
                extraPackages = new HashSet<>(extraPackages);
                extraPackages.add(pn);
            } else {
                extraPackages = Collections.singleton(pn);
            }

            // update VM first, just in case it fails
            if (syncVM)
                addPackage0(this, pn.replace('.', '/'));

            // replace with new set
            this.extraPackages = extraPackages; // volatile write
        }
    }


    // -- creating Module objects --

    /**
     * Find the runtime Module corresponding to the given ResolvedModule
     * in the given parent Layer (or its parents).
     */
    private static Module find(ResolvedModule resolvedModule, Layer layer) {
        Configuration cf = resolvedModule.configuration();
        String dn = resolvedModule.name();

        Module m = null;
        while (layer != null) {
            if (layer.configuration() == cf) {
                Optional<Module> om = layer.findModule(dn);
                m = om.get();
                assert m.getLayer() == layer;
                break;
            }
            layer = layer.parent().orElse(null);
        }
        return m;
    }

    /**
     * Defines each of the module in the given configuration to the runtime.
     *
     * @return a map of module name to runtime {@code Module}
     *
     * @throws IllegalArgumentException
     *         If defining any of the modules to the VM fails
     */
    static Map<String, Module> defineModules(Configuration cf,
                                             Function<String, ClassLoader> clf,
                                             Layer layer)
    {
        Map<String, Module> modules = new HashMap<>();
        Map<String, ClassLoader> moduleToLoader = new HashMap<>();

        boolean isBootLayer = (Layer.boot() == null);
        Set<ClassLoader> loaders = new HashSet<>();

        // map each module to a class loader
        for (ResolvedModule resolvedModule : cf.modules()) {
            String name = resolvedModule.name();
            ClassLoader loader = clf.apply(name);
            if (loader != null) {
                moduleToLoader.put(name, loader);
                loaders.add(loader);
            } else if (!isBootLayer) {
                throw new IllegalArgumentException("loader can't be 'null'");
            }
        }

        // define each module in the configuration to the VM
        for (ResolvedModule resolvedModule : cf.modules()) {
            ModuleReference mref = resolvedModule.reference();
            ModuleDescriptor descriptor = mref.descriptor();
            String name = descriptor.name();
            URI uri = mref.location().orElse(null);
            ClassLoader loader = moduleToLoader.get(resolvedModule.name());
            Module m;
            if (loader == null && name.equals("java.base") && Layer.boot() == null) {
                m = Object.class.getModule();
            } else {
                m = new Module(layer, loader, descriptor, uri);
            }
            modules.put(name, m);
            moduleToLoader.put(name, loader);
        }

        // setup readability and exports
        for (ResolvedModule resolvedModule : cf.modules()) {
            ModuleReference mref = resolvedModule.reference();
            ModuleDescriptor descriptor = mref.descriptor();

            String mn = descriptor.name();
            Module m = modules.get(mn);
            assert m != null;

            // reads
            Set<Module> reads = new HashSet<>();
            for (ResolvedModule d : resolvedModule.reads()) {
                Module m2;
                if (d.configuration() == cf) {
                    String dn = d.reference().descriptor().name();
                    m2 = modules.get(dn);
                    assert m2 != null;
                } else {
                    m2 = find(d, layer.parent().orElse(null));
                }

                reads.add(m2);

                // update VM view
                addReads0(m, m2);
            }
            m.reads = reads;

            // automatic modules read all unnamed modules
            if (descriptor.isAutomatic()) {
                m.implAddReads(ALL_UNNAMED_MODULE, true);
            }

            // exports
            Map<String, Boolean> unqualifiedExports = new HashMap<>();
            Map<String, Map<Module, Boolean>> qualifiedExports = new HashMap<>();

            for (Exports export : descriptor.exports()) {
                String source = export.source();
                String sourceInternalForm = source.replace('.', '/');

                boolean b = export.modifiers().contains(Exports.Modifier.PRIVATE);
                Boolean value = Boolean.valueOf(b);

                if (export.isQualified()) {

                    // qualified export
                    Map<Module, Boolean> targets = new HashMap<>();
                    for (String target : export.targets()) {
                        // only export to modules that are in this configuration
                        Module m2 = modules.get(target);
                        if (m2 != null) {
                            addExports0(m, sourceInternalForm, m2);
                            targets.put(m2, value);
                        }
                    }
                    if (!targets.isEmpty()) {
                        qualifiedExports.put(source, targets);
                    }

                } else {

                    // unqualified export
                    addExportsToAll0(m, sourceInternalForm);
                    unqualifiedExports.put(source, value);
                }
            }

            if (!unqualifiedExports.isEmpty())
                m.unqualifiedExports = unqualifiedExports;
            if (!qualifiedExports.isEmpty())
                m.qualifiedExports = qualifiedExports;
        }

        // For the boot layer then register the modules in the class loader
        // services catalog
        if (isBootLayer) {
            for (ResolvedModule resolvedModule : cf.modules()) {
                ModuleReference mref = resolvedModule.reference();
                ModuleDescriptor descriptor = mref.descriptor();
                Map<String, Provides> services = descriptor.provides();
                if (!services.isEmpty()) {
                    String name = descriptor.name();
                    Module m = modules.get(name);
                    ClassLoader loader = moduleToLoader.get(name);
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
        }

        // ClassLoader::layers support
        for (ClassLoader loader : loaders) {
            SharedSecrets.getJavaLangAccess().bindToLayer(loader, layer);
        }

        return modules;
    }


    // -- annotations --

    /**
     * {@inheritDoc}
     * This method returns {@code null} when invoked on an unnamed module.
     */
    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        return moduleInfoClass().getDeclaredAnnotation(annotationClass);
    }

    /**
     * {@inheritDoc}
     * This method returns an empty array when invoked on an unnamed module.
     */
    @Override
    public Annotation[] getAnnotations() {
        return moduleInfoClass().getAnnotations();
    }

    /**
     * {@inheritDoc}
     * This method returns an empty array when invoked on an unnamed module.
     */
    @Override
    public Annotation[] getDeclaredAnnotations() {
        return moduleInfoClass().getDeclaredAnnotations();
    }

    // cached class file with annotations
    private volatile Class<?> moduleInfoClass;

    private Class<?> moduleInfoClass() {
        Class<?> clazz = this.moduleInfoClass;
        if (clazz != null)
            return clazz;

        synchronized (this) {
            clazz = this.moduleInfoClass;
            if (clazz == null) {
                if (isNamed()) {
                    PrivilegedAction<Class<?>> pa = this::loadModuleInfoClass;
                    clazz = AccessController.doPrivileged(pa);
                }
                if (clazz == null) {
                    class DummyModuleInfo { }
                    clazz = DummyModuleInfo.class;
                }
                this.moduleInfoClass = clazz;
            }
            return clazz;
        }
    }

    private Class<?> loadModuleInfoClass() {
        Class<?> clazz = null;
        try (InputStream in = getResourceAsStream("module-info.class")) {
            if (in != null)
                clazz = loadModuleInfoClass(in);
        } catch (Exception ignore) { }
        return clazz;
    }

    /**
     * Loads module-info.class as a package-private interface in a class loader
     * that is a child of this module's class loader.
     */
    private Class<?> loadModuleInfoClass(InputStream in) throws IOException {
        final String MODULE_INFO = "module-info";

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS
                                         + ClassWriter.COMPUTE_FRAMES);

        ClassVisitor cv = new ClassVisitor(Opcodes.ASM5, cw) {
            @Override
            public void visit(int version,
                              int access,
                              String name,
                              String signature,
                              String superName,
                              String[] interfaces) {
                cw.visit(version,
                        Opcodes.ACC_INTERFACE
                            + Opcodes.ACC_ABSTRACT
                            + Opcodes.ACC_SYNTHETIC,
                        MODULE_INFO,
                        null,
                        "java/lang/Object",
                        null);
            }
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                // keep annotations
                return super.visitAnnotation(desc, visible);
            }
            @Override
            public void visitAttribute(Attribute attr) {
                // drop non-annotation attributes
            }
        };

        ClassReader cr = new ClassReader(in);
        cr.accept(cv, 0);
        byte[] bytes = cw.toByteArray();

        ClassLoader cl = new ClassLoader(loader) {
            @Override
            protected Class<?> findClass(String cn)throws ClassNotFoundException {
                if (cn.equals(MODULE_INFO)) {
                    return super.defineClass(cn, bytes, 0, bytes.length);
                } else {
                    throw new ClassNotFoundException(cn);
                }
            }
        };

        try {
            return cl.loadClass(MODULE_INFO);
        } catch (ClassNotFoundException e) {
            throw new InternalError(e);
        }
    }


    // -- misc --


    /**
     * Returns an input stream for reading a resource in this module. The
     * {@code name} parameter is a {@code '/'}-separated path name that
     * identifies the resource.
     *
     * <p> A resource in a named modules may be <em>encapsulated</em> so that
     * it cannot be located by code in other modules. Whether a resource can be
     * located or not is determined as follows:
     *
     * <ul>
     *     <li> The <em>package name</em> of the resource is derived from the
     *     subsequence of characters that precedes the last {@code '/'} and then
     *     replacing each {@code '/'} character in the subsequence with
     *     {@code '.'}. For example, the package name derived for a resource
     *     named "{@code a/b/c/foo.properties}" is "{@code a.b.c}". </li>
     *
     *     <li> If the package name is a package in the module then the package
     *     must be {@link #isExportedPrivate exported-private} to the module of
     *     the caller of this method. If the package is not in the module then
     *     the resource is not encapsulated. Resources in the unnamed package
     *     or "{@code META-INF}", for example, are never encapsulated because
     *     they can never be packages in a named module. </li>
     *
     *     <li> As a special case, resources ending with "{@code .class}" are
     *     never encapsulated. </li>
     * </ul>
     *
     * <p> This method returns {@code null} if the resource is not in this
     * module, the resource is encapsulated and cannot be located by the caller,
     * or access to the resource is denied by the security manager.
     *
     * @param  name
     *         The resource name
     *
     * @return An input stream for reading the resource or {@code null}
     *
     * @throws IOException
     *         If an I/O error occurs
     *
     * @see java.lang.module.ModuleReader#open(String)
     */
    @CallerSensitive
    public InputStream getResourceAsStream(String name) throws IOException {
        Objects.requireNonNull(name);

        if (isNamed() && !ResourceHelper.isSimpleResource(name)) {
            Module caller = Reflection.getCallerClass().getModule();
            if (caller != this && caller != Object.class.getModule()) {
                // ignore packages added for proxies via addPackage
                Set<String> packages = getDescriptor().packages();
                String pn = ResourceHelper.getPackageName(name);
                if (packages.contains(pn) && !isExported(pn, caller)) {
                    // resource is in package not exported to caller
                    return null;
                }
            }
        }

        String mn = this.name;

        // special-case built-in class loaders to avoid URL connection
        if (loader == null) {
            return BootLoader.findResourceAsStream(mn, name);
        } else if (loader instanceof BuiltinClassLoader) {
            return ((BuiltinClassLoader) loader).findResourceAsStream(mn, name);
        }

        // locate resource in module
        JavaLangAccess jla = SharedSecrets.getJavaLangAccess();
        URL url = jla.findResource(loader, mn, name);
        if (url != null) {
            try {
                return url.openStream();
            } catch (SecurityException e) { }
        }

        return null;
    }

    /**
     * Returns the string representation of this module. For a named module,
     * the representation is the string {@code "module"}, followed by a space,
     * and then the module name. For an unnamed module, the representation is
     * the string {@code "unnamed module"}, followed by a space, and then an
     * implementation specific string that identifies the unnamed module.
     *
     * @return The string representation of this module
     */
    @Override
    public String toString() {
        if (isNamed()) {
            return "module " + name;
        } else {
            String id = Integer.toHexString(System.identityHashCode(this));
            return "unnamed module @" + id;
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
                public void addReadsAllUnnamed(Module m) {
                    m.implAddReads(Module.ALL_UNNAMED_MODULE);
                }
                @Override
                public void addExports(Module m, String pn, Module other) {
                    m.implAddExports(pn, other, false, true);
                }
                @Override
                public void addExportsPrivate(Module m, String pn, Module other) {
                    m.implAddExports(pn, other, true, true);
                }
                @Override
                public void addExportsToAll(Module m, String pn) {
                    m.implAddExports(pn, Module.EVERYONE_MODULE, false, true);
                }
                @Override
                public void addExportsPrivateToAll(Module m, String pn) {
                    m.implAddExports(pn, Module.EVERYONE_MODULE, true, true);
                }
                @Override
                public void addExportsToAllUnnamed(Module m, String pn) {
                    m.implAddExports(pn, Module.ALL_UNNAMED_MODULE, false, true);
                }
                @Override
                public void addExportsPrivateToAllUnnamed(Module m, String pn) {
                    m.implAddExports(pn, Module.ALL_UNNAMED_MODULE, true, true);
                }
                @Override
                public void addUses(Module m, Class<?> service) {
                    m.implAddUses(service);
                }
                @Override
                public void addPackage(Module m, String pn) {
                    m.implAddPackage(pn, true);
                }
                @Override
                public ServicesCatalog getServicesCatalog(Layer layer) {
                    return layer.getServicesCatalog();
                }
            });
    }
}
