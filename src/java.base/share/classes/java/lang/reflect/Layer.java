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

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jdk.internal.loader.Loader;
import jdk.internal.loader.LoaderPool;
import jdk.internal.misc.SharedSecrets;
import sun.security.util.SecurityConstants;


/**
 * A layer of modules in the Java virtual machine.
 *
 * <p> A layer is created from a graph of modules that is the {@link
 * Configuration} and a function that maps each module to a {@link ClassLoader}.
 * Creating a layer informs the Java virtual machine about the classes that
 * may be loaded from modules so that the Java virtual machine knows which
 * module that each class is a member of. Each layer, except the {@link
 * #empty() empty} layer, has a {@link #parent() parent}.
 *
 * <p> Creating a layer creates a {@link Module} object for each {@link
 * ModuleDescriptor} in the configuration. For each {@link
 * java.lang.module.Configuration.ReadDependence ReadDependence}, then the
 * {@code Module} {@link Module#canRead reads} the corresponding run-time
 * {@code Module}, which may be in the same layer or a parent layer.
 * The {@code Module} {@link Module#isExported(String) exports} the packages
 * described by its {@code ModuleDescriptor}. </p>
 *
 * <p> The {@link #createWithOneLoader createWithOneLoader} and {@link
 * #createWithManyLoaders createWithManyLoaders} methods provide convenient ways
 * to create a {@code Layer} where all modules are mapped to a single class
 * loader or where each module is mapped to its own class loader. The {@link
 * #create create} method is for more advanced cases where modules are mapped
 * to custom class loaders by means of a function specified to the method. </p>
 *
 * <p> A Java virtual machine has at least one layer, the {@link #boot() boot}
 * layer, that is created when the Java virtual machine is started. The
 * <em>system modules</em>, including {@code java.base}, are in the boot layer.
 * The modules in the boot layer are mapped to the bootstrap class loader and
 * other class loaders that are built-in into the Java virtual machine.
 * The boot layer will often be the {@link #parent() parent} when creating
 * additional layers. </p>
 *
 * <p> As when creating a {@code Configuration},
 * {@link ModuleDescriptor#isAutomatic() automatic} modules receive
 * <a href="../module/Configuration.html#automaticmoduleresolution">special
 * treatment</a> when creating a layer. An automatic module is created in the
 * Java virtual machine as a {@code Module} that reads every unnamed {@code
 * Module} in the Java virtual machine. </p>
 *
 * <h3> Example usage: </h3>
 *
 * <p> This example invokes the {@link Configuration#resolve
 * Configuration.resolve} method to resolve a module named <em>myapp</em>. It
 * uses the configuration for the boot layer as the parent configuration. It
 * then <em>instantiates</em> the configuration as a {@code Layer}. In the
 * example then all modules defined to the same class loader. </p>
 *
 * <pre>{@code
 *     ModuleFinder finder = ModuleFinder.of(dir1, dir2, dir3);
 *
 *     Configuration cf = Configuration.resolve(finder,
 *                                              Layer.boot().configuration(),
 *                                              ModuleFinder.empty(),
 *                                              "myapp");
 *
 *     ClassLoader scl = ClassLoader.getSystemClassLoader();
 *
 *     Layer layer = Layer.createWithOneLoader(cf, Layer.boot(), scl);
 *
 *     Class<?> c = layer.findLoader("myapp").loadClass("app.Main");
 * }</pre>
 *
 * @since 9
 * @see Module#getLayer()
 */

public final class Layer {

    // the empty Layer
    private static final Layer EMPTY_LAYER
        = new Layer(Configuration.empty(), null, null);

    // the configuration from which this Layer was created
    private final Configuration cf;

    // parent layer, null in the case of the empty layer
    private final Layer parent;

    // maps module name to jlr.Module
    private final Map<String, Module> nameToModule;


    /**
     * Creates a new Layer from the modules in the given configuration.
     */
    private Layer(Configuration cf,
                  Layer parent,
                  Function<String, ClassLoader> clf)
    {
        this.cf = cf;
        this.parent = parent;

        Map<String, Module> map;
        if (parent == null) {
            map = Collections.emptyMap();
        } else {
            map = Module.defineModules(cf, clf, this);
        }
        this.nameToModule = map; // no need to do defensive copy
    }


    /**
     * Creates a {@code Layer} by defining the modules in the given {@code
     * Configuration} to the Java virtual machine. This method creates one
     * class loader and defines all modules to that class loader.
     *
     * <p> The class loader created by this method implements <em>direct
     * delegation</em> when loading types from modules. When its {@link
     * ClassLoader#loadClass(String, boolean) loadClass} method is invoked to
     * load a class then it uses the package name of the class to map it to a
     * module. This may be a module in this layer and hence defined to the same
     * class loader. It may be a package in a module in a parent layer that is
     * exported to one or more of the modules in this layer. The class
     * loader delegates to the class loader of the module, throwing {@code
     * ClassNotFoundException} if not found by that class loader.
     *
     * When {@code loadClass} is invoked to load classes that do not map to a
     * module then it delegates to the parent class loader. </p>
     *
     * <p> Attempting to create a layer with all modules defined to the same
     * class loader can fail for the following reasons:
     *
     * <ul>
     *
     *     <li><p> <em>Overlapping packages</em>: Two or more modules in the
     *     configuration have the same package (exported or concealed). </p></li>
     *
     *     <li><p> <em>Split delegation</em>: The resulting class loader would
     *     need to delegate to more than one class loader in order to load types
     *     in a specific package. </p></li>
     *
     * </ul>
     *
     * <p> If there is a security manager then the class loader created by
     * this method will load classes and resources with privileges that are
     * restricted by the calling context of this method. </p>
     *
     * @param  cf
     *         The configuration to instantiate as a layer
     * @param  parentLayer
     *         The parent layer
     * @param  parentLoader
     *         The parent class loader for the class loader created by this
     *         method; may be {@code null} for the bootstrap class loader
     *
     * @return The newly created layer
     *
     * @throws IllegalArgumentException
     *         If the parent of the given configuration is not the configuration
     *         of the parent {@code Layer}
     * @throws LayerInstantiationException
     *         If all modules cannot be defined to the same class loader for any
     *         of the reasons listed above
     * @throws SecurityException
     *         If {@code RuntimePermission("createClassLoader")} or
     *         {@code RuntimePermission("getClassLoader")} is denied by
     *         the security manager
     *
     * @see #findLoader
     */
    public static Layer createWithOneLoader(Configuration cf,
                                            Layer parentLayer,
                                            ClassLoader parentLoader)
    {
        checkConfiguration(cf, parentLayer);
        checkCreateClassLoaderPermission();
        checkGetClassLoaderPermission();

        Loader loader;
        try {
            loader = new Loader(cf.modules(), parentLoader)
                    .initRemotePackageMap(cf, parentLayer);
        } catch (IllegalArgumentException e) {
            throw new LayerInstantiationException(e.getMessage());
        }
        return new Layer(cf, parentLayer, mn -> loader);
    }


    /**
     * Creates a {@code Layer} by defining the modules in the given {@code
     * Configuration} to the Java virtual machine. Each module is defined to
     * its own {@link ClassLoader} created by this method. The {@link
     * ClassLoader#getParent() parent} of each class loader is the given
     * parent class loader.
     *
     * <p> The class loaders created by this method implement <em>direct
     * delegation</em> when loading types from modules. When {@link
     * ClassLoader#loadClass(String, boolean) loadClass} method is invoked to
     * load a class then it uses the package name of the class to map it to a
     * module. The package may be in the module defined to the class loader.
     * The package may be exported by another module in this layer to the
     * module defined to the class loader. It may be in a package exported by a
     * module in a parent layer. The class loader delegates to the class loader
     * of the module, throwing {@code ClassNotFoundException} if not found by
     * that class loader.
     *
     * When {@code loadClass} is invoked to load classes that do not map to a
     * module then it delegates to the parent class loader. </p>
     *
     * <p> If there is a security manager then the class loaders created by
     * this method will load classes and resources with privileges that are
     * restricted by the calling context of this method. </p>
     *
     * @param  cf
     *         The configuration to instantiate as a layer
     * @param  parentLayer
     *         The parent layer
     * @param  parentLoader
     *         The parent class loader for each of the class loaders created by
     *         this method; may be {@code null} for the bootstrap class loader
     *
     * @return The newly created layer
     *
     * @throws IllegalArgumentException
     *         If the parent of the given configuration is not the configuration
     *         of the parent {@code Layer}
     * @throws SecurityException
     *         If {@code RuntimePermission("createClassLoader")} or
     *         {@code RuntimePermission("getClassLoader")} is denied by
     *         the security manager
     *
     * @see #findLoader
     */
    public static Layer createWithManyLoaders(Configuration cf,
                                              Layer parentLayer,
                                              ClassLoader parentLoader)
    {
        checkConfiguration(cf, parentLayer);
        checkCreateClassLoaderPermission();
        checkGetClassLoaderPermission();

        LoaderPool pool = new LoaderPool(cf, parentLayer, parentLoader);
        return new Layer(cf, parentLayer, pool::loaderFor);
    }


    /**
     * Creates a {@code Layer} by defining the modules in the given {@code
     * Configuration} to the Java virtual machine. Each module is mapped, by
     * name, to its class loader by means of the given function. The class
     * loader delegation implemented by these class loaders must respect
     * module readability. In addition, the caller needs to arrange that the
     * class loaders are ready to load from these modules before there are
     * any attempts to load classes or resources.
     *
     * <p> Creating a {@code Layer} can fail for the following reasons: </p>
     *
     * <ul>
     *
     *     <li><p> Two or more modules with the same package (exported or
     *     concealed) are mapped to the same class loader. </p></li>
     *
     *     <li><p> A module is mapped to a class loader that already has a
     *     module of the same name defined to it. </p></li>
     *
     *     <li><p> A module is mapped to a class loader that has already
     *     defined types in any of the packages in the module. </p></li>
     *
     * </ul>
     *
     * @apiNote It is implementation specific as to whether creating a Layer
     * with this method is an atomic operation or not. Consequentially it is
     * possible for this method to fail with some modules, but not all, defined
     * to Java virtual machine.
     *
     * @param  cf
     *         The configuration to instantiate as a layer
     * @param  parentLayer
     *         The parent layer
     * @param  clf
     *         The function to map a module name to a class loader
     *
     * @return The newly created layer
     *
     * @throws IllegalArgumentException
     *         If the parent of the given configuration is not the configuration
     *         of the parent {@code Layer}
     * @throws LayerInstantiationException
     *         If creating the {@code Layer} fails for any of the reasons
     *         listed above
     * @throws SecurityException
     *         If {@code RuntimePermission("getClassLoader")} is denied by
     *         the security manager
     */
    public static Layer create(Configuration cf,
                               Layer parentLayer,
                               Function<String, ClassLoader> clf)
    {
        Objects.requireNonNull(clf);
        checkConfiguration(cf, parentLayer);
        checkGetClassLoaderPermission();

        // For now, no two modules in the boot Layer may contain the same
        // package so we use a simple check for the boot Layer to keep
        // the overhead at startup to a minimum
        if (bootLayer() == null) {
            checkBootModulesForDuplicatePkgs(cf);
        } else {
            checkForDuplicatePkgs(cf, clf);
        }

        try {
            return new Layer(cf, parentLayer, clf);
        } catch (IllegalArgumentException iae) {
            // IAE is thrown by VM when defining the module fails
            throw new LayerInstantiationException(iae.getMessage());
        }
    }


    private static void checkConfiguration(Configuration cf, Layer parentLayer) {
        Objects.requireNonNull(cf);
        Objects.requireNonNull(parentLayer);

        Optional<Configuration> oparent = cf.parent();
        if (!oparent.isPresent() || oparent.get() != parentLayer.configuration()) {
            throw new IllegalArgumentException(
                    "Parent of configuration != configuration of parent Layer");
        }
    }

    private static void checkCreateClassLoaderPermission() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null)
            sm.checkPermission(SecurityConstants.CREATE_CLASSLOADER_PERMISSION);
    }

    private static void checkGetClassLoaderPermission() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null)
            sm.checkPermission(SecurityConstants.GET_CLASSLOADER_PERMISSION);
    }

    /**
     * Checks a configuration for the boot Layer to ensure that no two modules
     * have the same package.
     *
     * @throws LayerInstantiationException
     */
    private static void checkBootModulesForDuplicatePkgs(Configuration cf) {
        Map<String, String> packageToModule = new HashMap<>();
        for (ModuleDescriptor md : cf.descriptors()) {
            String name = md.name();
            for (String p : md.packages()) {
                String other = packageToModule.putIfAbsent(p, name);
                if (other != null) {
                    throw fail("Package " + p + " in both module "
                               + name + " and module " + other);
                }
            }
        }
    }

    /**
     * Checks a configuration and the module-to-loader mapping to ensure that
     * no two modules mapped to the same class loader have the same package.
     * It also checks that no two automatic modules have the same package.
     *
     * @throws LayerInstantiationException
     */
    private static void checkForDuplicatePkgs(Configuration cf,
                                              Function<String, ClassLoader> clf)
    {
        // HashMap allows null keys
        Map<ClassLoader, Set<String>> loaderToPackages = new HashMap<>();

        for (ModuleDescriptor descriptor : cf.descriptors()) {
            ClassLoader loader = clf.apply(descriptor.name());

            Set<String> loaderPackages
                = loaderToPackages.computeIfAbsent(loader, k -> new HashSet<>());

            for (String pkg : descriptor.packages()) {
                boolean added = loaderPackages.add(pkg);
                if (!added) {
                    throw fail("More than one module with package %s mapped" +
                               " to the same class loader", pkg);
                }
            }
        }
    }

    /**
     * Creates a LayerInstantiationException with the a message formatted from
     * the given format string and arguments.
     */
    private static LayerInstantiationException fail(String fmt, Object ... args) {
        String msg = String.format(fmt, args);
        return new LayerInstantiationException(msg);
    }


    /**
     * Returns the configuration for this layer.
     *
     * @return The configuration for this layer
     */
    public Configuration configuration() {
        return cf;
    }


    /**
     * Returns this layer's parent unless this is the {@linkplain #empty empty
     * layer}, which has no parent.
     *
     * @return This layer's parent
     */
    public Optional<Layer> parent() {
        return Optional.ofNullable(parent);
    }


    /**
     * Returns a set of the modules in this layer.
     *
     * @return A possibly-empty unmodifiable set of the modules in this layer
     */
    public Set<Module> modules() {
        return Collections.unmodifiableSet(
                nameToModule.values().stream().collect(Collectors.toSet()));
    }


    /**
     * Returns the module with the given name in this layer, or if not in this
     * layer, the {@linkplain #parent parent} layer.
     *
     * @param  name
     *         The name of the module to find
     *
     * @return The module with the given name or an empty {@code Optional}
     *         if there isn't a module with this name in this layer or any
     *         parent layer
     */
    public Optional<Module> findModule(String name) {
        Module m = nameToModule.get(Objects.requireNonNull(name));
        if (m != null)
            return Optional.of(m);
        return parent().flatMap(l -> l.findModule(name));
    }


    /**
     * Returns the {@code ClassLoader} for the module with the given name. If
     * a module of the given name is not in this layer then the {@link #parent}
     * layer is checked.
     *
     * <p> If there is a security manager then its {@code checkPermission}
     * method is called with a {@code RuntimePermission("getClassLoader")}
     * permission to check that the caller is allowed to get access to the
     * class loader. </p>
     *
     * @apiNote This method does not return an {@code Optional<ClassLoader>}
     * because `null` must be used to represent the bootstrap class loader.
     *
     * @param  name
     *         The name of the module to find
     *
     * @return The ClassLoader that the module is defined to
     *
     * @throws IllegalArgumentException if a module of the given name is not
     *         defined in this layer or any parent of this layer
     *
     * @throws SecurityException if denied by the security manager
     */
    public ClassLoader findLoader(String name) {
        Module m = nameToModule.get(Objects.requireNonNull(name));
        if (m != null)
            return m.getClassLoader();
        Optional<Layer> ol = parent();
        if (ol.isPresent())
            return ol.get().findLoader(name);
        throw new IllegalArgumentException("Module " + name
                                           + " not known to this layer");
    }


    /**
     * Returns the <em>empty</em> layer.
     *
     * @return The empty layer
     */
    public static Layer empty() {
        return EMPTY_LAYER;
    }


    /**
     * Returns the boot layer.
     *
     * <p> If there is a security manager then its {@code checkPermission}
     * method if first called with a {@code RuntimePermission("getBootLayer")}
     * permission to check that the caller is allowed access to the boot
     * {@code Layer}. </p>
     *
     * @apiNote This method returns {@code null} during startup and before
     * the boot layer is fully initialized.
     *
     * @return The boot layer
     *
     * @throws SecurityException if denied by the security manager
     */
    public static Layer boot() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null)
            sm.checkPermission(new RuntimePermission("getBootLayer"));
        return bootLayer();
    }

    /**
     * Returns the boot layer. Returns {@code null} if the boot layer has not
     * been set.
     */
    private static Layer bootLayer() {
        return SharedSecrets.getJavaLangAccess().getBootLayer();
    }

}
