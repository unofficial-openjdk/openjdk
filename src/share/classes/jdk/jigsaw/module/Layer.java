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

package jdk.jigsaw.module;

import java.lang.reflect.Module;
import java.util.*;

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
 *
 *     ModuleArtifactFinder finder =
 *         ModuleArtifactFinder.ofDirectories(dir1, dir2, dir3);
 *
 *     Configuration cf =
 *         Configuration.resolve(ModuleArtifactFinder.nullFinder(),
 *                               Layer.bootLayer(),
 *                               finder,
 *                               "myapp");
 *
 *     ClassLoader loader = ...
 *
 *     Layer layer = Layer.create(Layer.bootLayer(), cf, m -> loader);
 *
 *     Class<?> c = layer.findLoader("myapp").loadClass("app.Main");
 *
 * }</pre>
 */

public final class Layer {

    private static final JavaLangReflectAccess reflectAccess =
        SharedSecrets.getJavaLangReflectAccess();

    private static final Layer EMPTY_LAYER =
        new Layer(null, null, Collections.emptyMap());

    private final Layer parent;
    private final Configuration cf;
    private final Map<String, Module> nameToModule;

    /**
     * Finds the class loader for module artifacts.
     *
     * @see Layer#create
     */
    @FunctionalInterface
    public static interface ClassLoaderFinder {
        /**
         * Returns the class loader for the given module artifact.
         */
        ClassLoader loaderForModule(ModuleArtifact artifact);
    }

    /**
     * Defines each of the module in the given configuration to the runtime.
     *
     * @return a map of module name to runtime {@code Module}
     */
    private static Map<String, Module> defineModules(Layer parent,
                                                     Configuration cf,
                                                     ClassLoaderFinder clf)
    {
        Map<String, Module> map = new HashMap<>();

        // define each of the modules in the configuration to the VM
        for (ModuleDescriptor descriptor: cf.descriptors()) {
            String name = descriptor.name();

            ModuleArtifact artifact = cf.findArtifact(name);
            ClassLoader loader = clf.loaderForModule(artifact);

            // TBD: what if this throws an error? rollback or specify as not-atomic???
            assert !artifact.packages().contains("");
            Module m = reflectAccess.defineModule(loader, descriptor, artifact.packages());
            map.put(name, m);
        }

        // setup exports and readability
        for (ModuleDescriptor descriptor: cf.descriptors()) {
            Module m = map.get(descriptor.name());
            assert m != null;

            // exports
            for (ModuleExport export: descriptor.exports()) {
                String pkg = export.pkg();
                String permit = export.permit();
                if (permit == null) {
                    reflectAccess.addExport(m, pkg, null);
                } else {
                    // only export to modules that are in this configuration
                    Module m2 = map.get(permit);
                    if (m2 != null)
                        reflectAccess.addExport(m, pkg, m2);
                }
            }

            // reads
            for (ModuleDescriptor other: cf.readDependences(descriptor)) {
                String dn = other.name();
                Module m2 = map.get(dn);
                if (m2 == null && parent != null)
                    m2 = parent.findModule(other.name());
                if (m2 == null) {
                    throw new InternalError(descriptor.name() +
                        "reads unknown module: " + other.name());
                }
                reflectAccess.addReadsModule(m, m2);
            }
        }

        // modules are now defined
        for (Module m: map.values()) {
            reflectAccess.setDefined(m);
        }

        return map;
    }

    /**
     * Creates a new {@code Layer} object.
     */
    private Layer(Layer parent, Configuration cf, Map<String, Module> map) {
        this.parent = parent;
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
     *
     * @apiNote The parent Layer should not be in the signature.
     */
    public static Layer create(Layer parent, Configuration cf, ClassLoaderFinder clf) {
        Objects.requireNonNull(cf);
        Objects.requireNonNull(clf);
        Map<String, Module> map = defineModules(parent, cf, clf);
        return new Layer(parent, cf, map);
    }

    /**
     * Returns the {@code Configuration} used to create this layer. Return
     * {@code null} in the case of the {@link #emptyLayer empty-layer}.
     */
    public Configuration configuration() {
        return cf;
    }

    /**
     * Returns this layer's parent, {@code null} for the {@link #emptyLayer()
     * empty-layer}.
     */
    public Layer parent() {
        return parent;
    }

    /**
     * Returns the {@code Module} with the given name in this layer, or if not
     * in this layer, the {@link #parent} layer. Returns {@code null} if not
     * found.
     */
    public Module findModule(String name) {
        Module m = nameToModule.get(Objects.requireNonNull(name));
        if (m == null && parent != null)
            m = parent.findModule(name);
        return m;
    }

    /**
     * Returns the {@code ModuleArtifact} from where the module with the given
     * name was originally defined. If a module of the given name is not
     * in this layer then the {@link #parent} layer is checked. Returns {@code
     * null} if not found.
     */
    public ModuleArtifact findArtifact(String name) {
        if (cf == null) {
            return null;
        } else {
            ModuleArtifact artifact = cf.findArtifact(name);
            if (artifact == null && parent != null)
                artifact = parent.findArtifact(name);
            return artifact;
        }
    }

    /**
     * Returns the {@code ClassLoader} for the {@code Module} with the given
     * name. If a module of the given name is not in this layer then the {@link
     * #parent} layer is checked.
     *
     * @throws IllegalArgumentException if a module of the given name is not
     * defined in this layer or any parent of this layer.
     *
     * @apiNote {@code null} is a valid return from this method.
     */
    public ClassLoader findLoader(String name) {
        Module m = nameToModule.get(name);
        if (m == null && parent != null)
            return parent.findLoader(name);
        if (m == null)
            throw new IllegalArgumentException();
        return m.classLoader();
    }

    /**
     * Returns the set of module descriptors in this layer and all
     * parent layers.
     */
    Set<ModuleDescriptor> allModuleDescriptors() {
        Set<ModuleDescriptor> result = new HashSet<>();
        if (parent != null)
            result.addAll(parent.allModuleDescriptors());
        if (cf != null)
            result.addAll(cf.descriptors());
        return result;
    }


    /**
     * Returns an <em>empty</em> {@code Layer}.
     */
    public static Layer emptyLayer() {
        return EMPTY_LAYER;
    }

    // TBD how this is set
    private static Layer bootLayer;

    /**
     * Returns the boot layer. Returns {@code null} if the boot layer has not
     * been set.
     *
     * @throws SecurityException if denied by the security manager
     *
     * @apiNote This will probably need a permission check as the boot layer
     * reveals interesting things to a potential attacker.
     */
    public static Layer bootLayer() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null)
            sm.checkPermission(new RuntimePermission("getBootLayer"));
        return bootLayer;
    }

    /**
     * Sets the boot layer. The boot layer typically includes the modules installed
     * in the runtime image and any modules on the launcher module path.
     *
     * @throws IllegalStateException if the boot layer is already set
     * @throws SecurityException if denied by the security manager
     *
     * @apiNote Need to decide if we need this.
     */
    public static void setBootLayer(Layer layer) {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null)
            sm.checkPermission(new RuntimePermission("setBootLayer"));
        if (bootLayer != null)
            throw new IllegalStateException("boot layer already set");
        bootLayer = layer;
    }
}
