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

package java.lang.module;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The configuration that is the result of resolution or binding.
 *
 * <p> The following example resolves a module named <em>myapp</em> that results
 * in a configuration. It then augments that configuration with additional modules
 * (and edges) induced by service-use relationships. </p>
 *
 * <pre>{@code
 *
 *     ModuleFinder finder =
 *         ModuleFinder.of(dir1, dir2, dir3);
 *
 *     Configuration cf =
 *         Configuration.resolve(ModuleFinder.nullFinder(),
 *                               Layer.bootLayer(),
 *                               finder,
 *                               "myapp")
 *                      .bind();
 *
 * }</pre>
 *
 * @since 1.9
 */

public final class Configuration {

    private final Layer parent;
    private final Resolver.Resolution resolution;

    private Configuration(Layer parent, Resolver.Resolution resolution) {
        this.parent = parent;
        this.resolution = resolution;
    }

    /**
     * Resolves the given named modules. The given root modules are located
     * using {@code beforeFinder} or if not found then using {@code afterFinder}.
     * Module dependences are resolved by locating them (in order) using the
     * given {@code beforeFinder}, {@code layer}, and {@code afterFinder}.
     *
     * <p> Resolution can fail for several reasons including: </p>
     *
     * <ul>
     *     <li> A root module, or a direct or transitive dependency, is not
     *          found. </li>
     *
     *     <li> Some other error occurs when attempting to find a module.
     *          Possible errors include I/O errors, errors detected parsing a
     *          module descriptor ({@code module-info.class}), or an exception
     *          where access to some resource is denied by the security manager.
     *          </li>
     *
     *     <li> A cycle is detected, say where module {@code m1} requires module
     *          {@code m2} and {@code m2} requires {@code m1}. </li>
     *
     *     <li> Implementation specific checks, for example referential integrity
     *          checks that fail where incompatible versions of modules may not
     *          be combined in the same configuration. </li>
     * </ul>
     *
     * @throws ResolutionException  If resolution fails
     */
    public static Configuration resolve(ModuleFinder beforeFinder,
                                        Layer parent,
                                        ModuleFinder afterFinder,
                                        Collection<String> roots)
    {
        Objects.requireNonNull(beforeFinder);
        Objects.requireNonNull(parent);
        Objects.requireNonNull(afterFinder);

        Resolver.Resolution resolution =
            Resolver.resolve(beforeFinder, parent, afterFinder, roots);

        return new Configuration(parent, resolution);
    }

    /**
     * Resolves the given named modules. Module dependences are resolved by
     * locating them (in order) using the given {@code beforeFinder}, {@code
     * layer}, and {@code afterFinder}.
     *
     * @throws ResolutionException   If resolution fails
     */
    public static Configuration resolve(ModuleFinder beforeFinder,
                                        Layer layer,
                                        ModuleFinder afterFinder,
                                        String... roots)
    {
        return resolve(beforeFinder, layer, afterFinder, Arrays.asList(roots));
    }

    /**
     * Returns the {@code Layer} used when creating this configuration.
     */
    public Layer layer() {
        return parent;
    }

    /**
     * Returns a configuration that is this configuration augmented with modules
     * (located via the module reference finders) that are induced by service-use
     * relationships.
     *
     * <p> Binding involves resolution to resolve the dependences of service
     * provider modules. It may therefore fail with {@code ResolutionException}
     * for exactly the same reasons as the {@link #resolve resolve} methods.
     *
     * @throws ResolutionException  If resolution fails
     *
     * @apiNote This method is not thread safe
     */
    public Configuration bind() {
        Resolver.Resolution r = resolution.bind();
        return new Configuration(parent, r);
    }

    /**
     * Returns an immutable set of the module descriptors in this
     * configuration.
     */
    public Set<ModuleDescriptor> descriptors() {
        return resolution.selected();
    }

    /**
     * Returns the {@code ModuleReference} for the given named module or
     * {@code null} if a module of the given name is not in this
     * configuration.
     */
    public ModuleReference findReference(String name) {
        return resolution.findReference(name);
    }

    /**
     * Returns the {@code ModuleDescriptor} for the given named module
     * or {@code null} if a module of the given name is not in this
     * configuration.
     *
     * @apiNote It's not clear that this method is useful,
     */
    public ModuleDescriptor findDescriptor(String name) {
        ModuleReference mref = findReference(name);
        if (mref == null) {
            return null;
        } else {
            return mref.descriptor();
        }
    }

    /**
     * Returns an immutable set of the read dependences for the given module
     * descriptor.
     *
     * @throws IllegalArgumentException if the module descriptor is not in
     * this configuration.
     */
    public Set<ModuleDescriptor> readDependences(ModuleDescriptor descriptor) {
        Set<ModuleDescriptor> reads = resolution.readDependences(descriptor);
        if (reads == null) {
            throw new IllegalArgumentException(descriptor.name() +
                " not in this configuration");
        }
        return reads;
    }

    @Override
    public String toString() {
        return descriptors().stream()
                .map(ModuleDescriptor::name)
                .collect(Collectors.joining(", "));
    }
}

