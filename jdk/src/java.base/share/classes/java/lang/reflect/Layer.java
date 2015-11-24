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

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jdk.internal.misc.SharedSecrets;
import sun.security.util.SecurityConstants;


/**
 * Represents a layer of modules in the Java virtual machine.
 *
 * <p> The following example resolves a module named <em>myapp</em>. It then
 * instantiates the {@link Configuration} as a {@code Layer}. In the example
 * then all modules in the configuration are defined to the same class loader.
 * </p>
 *
 * <pre>{@code
 *     ModuleFinder finder = ModuleFinder.of(dir1, dir2, dir3);
 *
 *     Configuration cf
 *         = Configuration.resolve(ModuleFinder.empty(),
 *                                 Layer.boot(),
 *                                 finder,
 *                                 "myapp");
 *
 *     ClassLoader loader = new ModuleClassLoader(cf);
 *
 *     Layer layer = Layer.create(cf, mn -> loader);
 *
 *     Class<?> c = layer.findLoader("myapp").loadClass("app.Main");
 * }</pre>
 *
 * @apiNote As Layer is in java.lang.reflect then its method names may
 * need to follow the convention in this package.
 *
 * @since 1.9
 */

public final class Layer {


    /**
     * Finds the class loader for a module.
     *
     * @see Layer#create
     * @since 1.9
     */
    @FunctionalInterface
    public static interface ClassLoaderFinder {
        /**
         * Returns the class loader for the given module.
         *
         * <p> If this method is invoked several times to locate the same
         * module (by name) then it will return the same result each time.
         * Failure to do so will lead to unspecified behavior when creating
         * a Layer. </p>
         *
         * @return The class loader for the given module
         */
        ClassLoader loaderForModule(String moduleName);
    }


    // the empty Layer
    private static final Layer EMPTY_LAYER = new Layer(null, null);

    // the configuration from which this Layer was created
    private final Configuration cf;

    // maps module name to jlr.Module
    private final Map<String, Module> nameToModule;


    /**
     * Creates a new Layer from the modules in the given configuration.
     */
    private Layer(Configuration cf, ClassLoaderFinder clf) {

        Map<String, Module> map;
        if (cf == null) {
            map = Collections.emptyMap();
        } else {
            map = Module.defineModules(cf, clf, this);
        }

        this.cf = cf;
        this.nameToModule = map; // no need to do defensive copy
    }


    /**
     * Creates a {@code Layer} by defining the modules, as described in the
     * given {@code Configuration}, to the Java virtual machine.
     *
     * <p> Modules are mapped to module-capable class loaders by means of the
     * given {@code ClassLoaderFinder} and defined to the Java virtual machine.
     * The entity creating the layer should arrange for the class loaders to be
     * ready to load from these modules before there are any attempts to load
     * classes or resources. </p>
     *
     * <p> Creating a {@code Layer} may fail for several reasons: </p>
     *
     * <ul>
     *
     *     <li> Two or more modules with the same package (exported or
     *          concealed) are mapped to the same class loader. </li>
     *
     *     <li> A module is mapped to a class loader that already has a module
     *          of the same name defined to it. </li>
     *
     *     <li> A module is mapped to a class loader that has already defined
     *          types in any of the packages in the module. </li>
     *
     * </ul>
     *
     * <p> If there is a security manager then its {@code checkPermission}
     * method if first called with a {@code RuntimePermission("getClassLoader")}
     * permission to check that the caller is allowed to get access to the
     * class loaders that the {@code ClassLoaderFinder} returns. </p>
     *
     * @implNote Some of the failure reasons listed cannot be detected in
     * advance, hence it is possible for Layer.create to fail with some of the
     * modules in the configuration defined to the run-time.
     *
     * @param  cf
     *         The configuration to instantiate
     * @param  clf
     *         The {@code ClassLoaderFinder} to map modules to class loaders
     *
     * @return The newly created layer
     *
     * @throws LayerInstantiationException
     *         If creating the {@code Layer} fails for any of the reasons
     *         listed above
     * @throws SecurityException
     *         If denied by the security manager
     */
    public static Layer create(Configuration cf, ClassLoaderFinder clf) {
        Objects.requireNonNull(cf);
        Objects.requireNonNull(clf);

        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(SecurityConstants.GET_CLASSLOADER_PERMISSION);
        }

        // For now, no two modules in the boot Layer may contain the same
        // package so we use a simple check for the boot Layer to keep
        // the overhead at startup to a minimum
        if (bootLayer() == null) {
            checkBootModulesForDuplicatePkgs(cf);
        } else {
            checkForDuplicatePkgs(cf, clf);
        }

        try {
            return new Layer(cf, clf);
        } catch (IllegalArgumentException iae) {
            // IAE is thrown by VM when defining the module fails
            throw new LayerInstantiationException(iae.getMessage());
        }
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
                                              ClassLoaderFinder clf)
    {
        // HashMap allows null keys
        Map<ClassLoader, Set<String>> loaderToPackages = new HashMap<>();

        for (ModuleDescriptor descriptor : cf.descriptors()) {
            ClassLoader loader = clf.loaderForModule(descriptor.name());

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
     * Returns the {@code Configuration} used to create this layer unless this
     * is the {@linkplain #empty empty layer}, which has no configuration.
     *
     * @return The configuration used to create this layer
     */
    public Optional<Configuration> configuration() {
        return Optional.ofNullable(cf);
    }


    /**
     * Returns this layer's parent unless this is the {@linkplain #empty empty
     * layer}, which has no parent.
     *
     * @return This layer's parent
     */
    public Optional<Layer> parent() {
        if (cf == null) {
            return Optional.empty();
        } else {
            return Optional.of(cf.layer());
        }
    }


    /**
     * Returns a set of the {@code Module}s in this layer.
     *
     * @return The set of modules in this layer
     */
    public Set<Module> modules() {
        return nameToModule.values().stream().collect(Collectors.toSet());
    }


    /**
     * Returns the {@code Module} with the given name in this layer, or if not
     * in this layer, the {@linkplain #parent parent} layer.
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
     * Returns the {@code ClassLoader} for the {@code Module} with the given
     * name. If a module of the given name is not in this layer then the {@link
     * #parent} layer is checked.
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
     * defined in this layer or any parent of this layer
     *
     * @throws SecurityException if denied by the security manager
     */
    public ClassLoader findLoader(String name) {
        Module m = nameToModule.get(name);
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
     * Returns the boot layer. Returns {@code null} if the boot layer has not
     * been set.
     *
     * <p> If there is a security manager then its {@code checkPermission}
     * method if first called with a {@code RuntimePermission("getBootLayer")}
     * permission to check that the caller is allowed access to the boot
     * {@code Layer}. </p>
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
