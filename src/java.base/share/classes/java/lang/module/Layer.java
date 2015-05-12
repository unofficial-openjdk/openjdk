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

package java.lang.module;

import java.lang.reflect.Module;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import sun.misc.JavaLangModuleAccess;
import sun.misc.JavaLangReflectAccess;
import sun.misc.SharedSecrets;

/**
 * Represents a layer of modules in the Java virtual machine.
 *
 * <p> The following example resolves a module named <em>myapp</em> and creates
 * a {@code Layer} with the resulting {@link Configuration}. In the example
 * then all modules are associated with the same class loader. </p>
 *
 * <pre>{@code
 *     ModuleFinder finder =
 *         ModuleFinder.ofDirectories(dir1, dir2, dir3);
 *
 *     Configuration cf =
 *         Configuration.resolve(ModuleFinder.nullFinder(),
 *                               Layer.bootLayer(),
 *                               finder,
 *                               "myapp");
 *
 *     ClassLoader loader = ...
 *
 *     Layer layer = Layer.create(cf, m -> loader);
 *
 *     Class<?> c = layer.findLoader("myapp").loadClass("app.Main");
 * }</pre>
 *
 * @since 1.9
 */

public final class Layer {

    private static final JavaLangReflectAccess reflectAccess =
        SharedSecrets.getJavaLangReflectAccess();

    private static final Layer EMPTY_LAYER =
        new Layer(null, Collections.emptyMap());

    private final Configuration cf;
    private final Map<String, Module> nameToModule;

    /**
     * Finds the class loader for module references.
     *
     * @see Layer#create
     * @since 1.9
     */
    @FunctionalInterface
    public static interface ClassLoaderFinder {
        /**
         * Returns the class loader for the given module reference.
         */
        ClassLoader loaderForModule(ModuleReference mref);
    }

    /**
     * Creates a new {@code Layer} object.
     */
    private Layer(Configuration cf, Map<String, Module> map) {
        this.cf = cf;
        this.nameToModule = map;
    }

    /**
     * Creates a {@code Layer} by defining the modules, as described in the given
     * {@code Configuration}, to the Java virtual machine. The given {@code
     * ClassLoaderFinder} is used to associate each module with a {@code ClassLoader}.
     *
     * @throws Exception if a module is to be associated with a class loader that
     * already has an associated module of the same name
     *
     * @throws Exception if a module is to be associated with a class loader that has
     * already defined types in any of the packages that the module includes
     *
     * @apiNote The exact exceptions are TBD. Also need to discuss the topic of whether
     * this method is assumed to be atomic. For now, an exception thrown will leave
     * the VM in a state where some (but not all) modules may have been defined.
     */
    public static Layer create(Configuration cf, ClassLoaderFinder clf) {
        Objects.requireNonNull(cf);
        Objects.requireNonNull(clf);
        return new Layer(cf, reflectAccess.defineModules(cf, clf));
    }

    /**
     * Returns the {@code Configuration} used to create this layer. Return
     * {@code null} in the case of the {@link #emptyLayer empty-layer}.
     */
    public Configuration configuration() {
        return cf;
    }

    /**
     * Returns this layer's parent. If this layer is the {@link #emptyLayer empty-layer}
     * then its parent is {@code null}.
     */
    public Layer parent() {
        if (cf == null) {
            return null;
        } else {
            return cf.layer();
        }
    }

    /**
     * Returns the {@code Module} with the given name in this layer, or if not
     * in this layer, the {@link #parent} layer. Returns {@code null} if not
     * found.
     */
    public Module findModule(String name) {
        Module m = nameToModule.get(Objects.requireNonNull(name));
        Layer parent = parent();
        if (m == null && parent != null)
            m = parent.findModule(name);
        return m;
    }

    /**
     * Returns the {@code ModuleReference} from where the module with the given
     * name was originally defined. If a module of the given name is not
     * in this layer then the {@link #parent} layer is checked. Returns {@code
     * null} if not found.
     */
    ModuleReference findReference(String name) {
        if (cf == null) {
            return null;
        } else {
            ModuleReference mref = cf.findReference(name);
            if (mref == null) {
                Layer parent = parent();
                if (parent != null)
                    mref = parent.findReference(name);
            }
            return mref;
        }
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
     * @throws IllegalArgumentException if a module of the given name is not
     * defined in this layer or any parent of this layer.
     *
     * @throws SecurityException if denied by the security manager
     *
     * @apiNote {@code null} is a valid return from this method.
     */
    public ClassLoader findLoader(String name) {
        Module m = nameToModule.get(name);
        if (m == null) {
            Layer parent = parent();
            if (parent != null)
                return parent.findLoader(name);
            throw new IllegalArgumentException(name + " not known to this Layer");
        }
        return m.getClassLoader();
    }

    /**
     * Returns the set of module descriptors in this layer and all
     * parent layers.
     */
    Set<ModuleDescriptor> allModuleDescriptors() {
        Set<ModuleDescriptor> result = new HashSet<>();
        Layer parent = parent();
        if (parent != null)
            result.addAll(parent.allModuleDescriptors());
        if (cf != null)
            result.addAll(cf.descriptors());
        return result;
    }


    /**
     * Returns the <em>empty</em> {@code Layer}.
     */
    public static Layer emptyLayer() {
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
     * @throws SecurityException if denied by the security manager
     */
    public static Layer bootLayer() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null)
            sm.checkPermission(new RuntimePermission("getBootLayer"));
        return bootLayer;
    }

    // the boot Layer
    private static Layer bootLayer;

    static {
        SharedSecrets.setJavaLangModuleAccess(new JavaLangModuleAccess() {
            @Override
            public void setBootLayer(Layer layer) {
                bootLayer = layer;
            }
        });
    }

}
