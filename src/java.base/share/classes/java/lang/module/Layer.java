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
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import sun.misc.JavaLangModuleAccess;
import sun.misc.JavaLangReflectAccess;
import sun.misc.Modules;
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
 *         ModuleFinder.of(dir1, dir2, dir3);
 *
 *     Configuration cf =
 *         Configuration.resolve(ModuleFinder.empty(),
 *                               Layer.boot(),
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
     * Finds the class loader for a module reference.
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
        Layer layer = new Layer(cf, reflectAccess.defineModules(cf, clf));

        // Update the readability graph so that every automatic module in the
        // newly created Layer reads every other module.
        // We do this here for now but it may move.
        cf.descriptors().stream()
                .filter(ModuleDescriptor::isAutomatic)
                .map(ModuleDescriptor::name)
                .map(layer::findModule)
                .forEach(om -> layer.fixupAutomaticModule(om.get()));

        return layer;
    }

    /**
     * Changes an automatic module to be a "loose" module, and adds a
     * read edge to every module in the Layer (and all parent Layers).
     */
    private void fixupAutomaticModule(Module autoModule) {
        assert autoModule.getDescriptor().isAutomatic();

        // automatic modules read all unnamed modules
        // (use Modules.addReads to skip permission check)
        Modules.addReads(autoModule, null);

        // automatic modules read all modules in this, and parent, layers
        Layer l = this;
        do {
            Collection<Module> modules = l.nameToModule.values();
            modules.forEach(m -> Modules.addReads(autoModule, m));
            l = l.parent().orElse(null);
        } while (l != null);
    }

    /**
     * Returns the {@code Configuration} used to create this layer unless this
     * is the {@linkplain #empty empty layer}, which has no configuration.
     */
    public Optional<Configuration> configuration() {
        return Optional.ofNullable(cf);
    }

    /**
     * Returns this layer's parent unless this is the {@linkplain #empty empty
     * layer}, which has no parent.
     */
    public Optional<Layer> parent() {
        if (cf == null) {
            return Optional.empty();
        } else {
            return Optional.of(cf.layer());
        }
    }

    /**
     * Returns the {@code Module} with the given name in this layer, or if not
     * in this layer, the {@linkplain #parent parent} layer.
     */
    public Optional<Module> findModule(String name) {
        Module m = nameToModule.get(Objects.requireNonNull(name));
        if (m != null)
            return Optional.of(m);
        return parent().flatMap(l -> l.findModule(name));
    }

    /**
     * Returns the {@code ModuleReference} that was used to define the module
     * with the given name.  If a module of the given name is not in this layer
     * then the {@linkplain #parent parent} layer is checked.
     */
    public Optional<ModuleReference> findReference(String name) {
        if (cf == null)
            return Optional.empty();
        Optional<ModuleReference> omref = cf.findReference(name);
        if (omref.isPresent())
            return omref;
        return parent().flatMap(l -> l.findReference(name));
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
     * Returns the set of module descriptors in this layer and all
     * parent layers.
     */
    Set<ModuleDescriptor> allModuleDescriptors() {
        Set<ModuleDescriptor> result = new HashSet<>();
        Optional<Layer> ol = parent();
        if (ol.isPresent())
            result.addAll(ol.get().allModuleDescriptors());
        if (cf != null)
            result.addAll(cf.descriptors());
        return result;
    }

    /**
     * Returns the <em>empty</em> layer.
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
     * @throws SecurityException if denied by the security manager
     */
    public static Layer boot() {
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
