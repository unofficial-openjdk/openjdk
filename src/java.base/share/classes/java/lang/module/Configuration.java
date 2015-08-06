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

import java.lang.reflect.Layer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
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
 *     ModuleFinder finder = ModuleFinder.of(dir1, dir2, dir3);
 *
 *     Configuration cf =
 *         = Configuration.resolve(ModuleFinder.empty(),
 *                                 Layer.boot(),
 *                                 finder,
 *                                 "myapp")
 *                        .bind();
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
     * Resolves the collection of root modules, specified by module names,
     * returning the resulting configuration.
     *
     * <p> The root modules and their transitive dependences are located using
     * the given {@code beforeFinder}, parent {@code Layer} and {@code
     * afterFinder}, in this order. Dependences located in the parent {@code
     * Layer} are resolved no further. </p>
     *
     * <p> When all modules have been resolved then the resulting <em>dependency
     * graph</em> is checked to ensure that it does not contain cycles. A
     * <em>readability graph</em> is then constructed to take account of
     * implicitly declared dependences (requires public). The readability
     * graph and modules exports are checked to ensure that two or more modules
     * do not export the same package to a module that reads both. </p>
     *
     * <p> Resolution and the post-resolution consistency checks may fail for
     * several reasons: </p>
     *
     * <ul>
     *     <li> A root module, or a direct or transitive dependency, is not
     *          found. </li>
     *
     *     <li> Some other error occurs when attempting to find a module.
     *          Possible errors include I/O errors, errors detected parsing a
     *          module descriptor ({@code module-info.class}) or two versions
     *          of the same module are found in the same directory. </li>
     *
     *     <li> A cycle is detected, say where module {@code m1} requires
     *          module {@code m2} and {@code m2} requires {@code m1}. </li>
     *
     *     <li> Two or more modules in the configuration export the same
     *          package to a module that reads both. This includes the case
     *          where a module {@code M} containing package {@code P} reads
     *          another module that exports {@code P} to {@code M}. </li>
     *
     *     <li> Other implementation specific checks, for example referential
     *          integrity checks that fail where incompatible versions of
     *          modules may not be combined in the same configuration. </li>
     * </ul>
     *
     * @throws ResolutionException If resolution or the post-resolution checks
     *         fail for any of the reasons listed
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
     * Resolves the root modules, specified by module names, returning the
     * resulting configuration.
     *
     * @throws ResolutionException If resolution or the post-resolution checks
     *         fail for any of the reasons listed
     */
    public static Configuration resolve(ModuleFinder beforeFinder,
                                        Layer layer,
                                        ModuleFinder afterFinder,
                                        String... roots)
    {
        return resolve(beforeFinder, layer, afterFinder, Arrays.asList(roots));
    }

    /**
     * Returns the parent {@code Layer} on which this configuration is based.
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
     * @throws ResolutionException If resolution or the post-resolution checks
     *         fail for any of the reasons listed
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
     * Returns an immutable set of the module references in this
     * configuration.
     */
    public Set<ModuleReference> modules() {
        return resolution.references();
    }

    /**
     * Returns the {@code ModuleDescriptor} for the named module.
     *
     * @apiNote It's not clear that this method is useful.
     */
    public Optional<ModuleDescriptor> findDescriptor(String name) {
        return findModule(name).map(ModuleReference::descriptor);
    }

    /**
     * Returns the {@code ModuleReference} for the named module.
     */
    public Optional<ModuleReference> findModule(String name) {
        return resolution.findModule(name);
    }

    /**
     * Returns an immutable set of the read dependences of the given module
     * descriptor.
     *
     * @throws IllegalArgumentException if the module descriptor is not in
     * this configuration.
     */
    public Set<ModuleDescriptor> reads(ModuleDescriptor descriptor) {
        Set<ModuleDescriptor> reads = resolution.reads(descriptor);
        if (reads == null) {
            throw new IllegalArgumentException(descriptor.name() +
                " not in this configuration");
        }
        return reads;
    }

    /**
     * Returns an immutable set of the module descriptors in this {@code
     * Configuration} that provide one of more implementations of the given
     * service.
     *
     * If this {@code Configuration} is not the result of {@link #bind()
     * binding} then an empty set is returned.
     */
    public Set<ModuleDescriptor> provides(String sn) {
        return resolution.provides(sn);
    }

    @Override
    public String toString() {
        return descriptors().stream()
                .map(ModuleDescriptor::name)
                .collect(Collectors.joining(", "));
    }

}
