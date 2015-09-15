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
 * The configuration that is the result of resolution or service binding.
 *
 * <p> Resolution is the process of computing the transitive closure of a set
 * of root modules over a set of observable modules with respect to a
 * dependence relation. Computing the transitive closure results in a
 * <em>dependence graph</em> that is then transformed to a <em>readability
 * graph</em> by adding edges indicated by the readability relation ({@code
 * requires} or {@code requires public}). </p>
 *
 * <p> Suppose we have the following observable modules: </p>
 * <pre> {@code
 *     module m1 { requires m2; }
 *     module m2 { requires public m3; }
 *     module m3 { }
 *     module m4 { }
 * } </pre>
 *
 * <p> If the module {@code m1} is resolved then the resulting configuration
 * contains three modules ({@code m1}, {@code m2}, {@code m3}). The edges in
 * its readability graph are: </p>
 * <pre> {@code
 *     m1 --> m2  (meaning m1 reads m2)
 *     m1 --> m3
 *     m2 --> m3
 * } </pre>
 *
 * <p> Resolution is an additive process. The dependence relation may include
 * dependences on modules that have already been instantiated in the Java
 * virtual machine (in a module {@link Layer Layer}). The result is a
 * <em>relative configuration</em> that is relative to a module layer and
 * where readability graph has read edges to modules in the layer (or
 * parent layers). </p>
 *
 * <p> Suppose we have the following observable modules: </p>
 * <pre> {@code
 *     module m1 { requires m2; requires java.xml; }
 *     module m2 { }
 * } </pre>
 *
 * <p> If module {@code m1} is resolved with the {@link Layer#boot() boot}
 * layer as the parent layer then the resulting configuration contains two
 * modules ({@code m1}, {@code m2}). The edges in its readability graph
 * are:
 * <pre> {@code
 *     m1 --> m2
 *     m1 --> java.xml
 * } </pre>
 * where module {@code java.xml} is in the parent layer. For simplicity, this
 * example omits the implicitly declared dependence on the {@code java.base}
 * module. </p>
 *
 * <p> Service binding is the process of augmenting a configuration with
 * modules from the set of observable modules induced by the service-use
 * relation. Any module that was not previously in the graph requires
 * resolution to compute its transitive closure. Service binding is an
 * iterative process as adding a module that satisfies some service-use
 * dependence may introduce new service-use dependences. </p>
 *
 * <p> Suppose we have the following observable modules: </p>
 * <pre> {@code
 *     module m1 { uses p.S; }
 *     module m2 { provides p.S with p2.S2; }
 *     module m3 { requires m4; provides p.S with p3.S3; }
 *     module m4 { }
 * } </pre>
 *
 * <p> If the module {@code m1} is resolved then the resulting configuration
 * contains one module ({@code m1}). If service binding is then performed then
 * it results in a new configuration that contains four modules ({@code m1},
 * {@code m2}, {@code m3}, {@code m4}). The edges in its readability graph
 * are:</p>
 * <pre> {@code
 *     m3 --> m4
 * } </pre>
 * <p> The edges in its service-use graph are: </p>
 * <pre> {@code
 *     m1 --> m2  (meaning m1 uses a service that is provided by m2)
 *     m1 --> m3
 * } </pre>
 *
 * <p> If this configuration is instantiated as a {@code Layer}, and if code in
 * module {@code m1} uses {@link java.util.ServiceLoader ServiceLoader} to
 * iterate over implementations of {@code p.S.class}, then it will iterate over
 * an instance of {@code p2.S2} and {@code p3.S3}. </p>
 *
 * <p> {@code Configuration} defines the {@link #resolve resolve} and {@link
 * #bind bind} methods to do resolution and service binding respectively. It
 * defines the {@link #reads reads} method to examine the readability graph,
 * and the {@link #provides provides} method to get the set of modules in
 * the configuration that provide one or more implementations of a service
 * type. </p>
 *
 * <p> The following example invokes the {@code resolve} method to resolve a
 * module named <em>myapp</em>. It then invokes {@code bind} on the
 * configuration to obtain a new configuration with additional modules (and
 * edges) induced by service-use relationships. </p>
 *
 * <pre>{@code
 *     ModuleFinder finder = ModuleFinder.of(dir1, dir2, dir3);
 *
 *     Configuration cf
 *         = Configuration.resolve(ModuleFinder.empty(),
 *                                 Layer.boot(),
 *                                 finder,
 *                                 "myapp")
 *                        .bind();
 * }</pre>
 *
 * @since 1.9
 * @see Layer
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
     * <p> Each root module is located using the given {@code beforeFinder}
     * and if not found, using the given {@code afterFinder}. Their transitive
     * dependences are located using the given {@code beforeFinder}, or if not
     * found then the parent {@code Layer}, or if not found then the given
     * {@code afterFinder}. Dependences located in the parent {@code Layer}
     * are resolved no further. </p>
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
     * @param  beforeFinder
     *         The module finder to find modules
     * @param  parent
     *         The parent layer, may be the {@link Layer#empty() empty} layer
     * @param  afterFinder
     *         The module finder to locate modules when not located by the
     *         {@code beforeFinder} or parent layer
     * @param  roots
     *         The possibly-empty collection of module names of the modules
     *         to resolve
     *
     * @return The configuration that is the result of resolving the given
     *         root modules
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
     * This method is equivalent to:
     * <pre>{@code
     *   resolve(beforeFinder, layer, afterFinder, Arrays.asList(roots));
     * }</pre>
     *
     * @param  beforeFinder
     *         The module finder to find modules
     * @param  parent
     *         The parent layer, may be the {@link Layer#empty() empty} layer
     * @param  afterFinder
     *         The module finder to locate modules when not located by the
     *         {@code beforeFinder} or parent layer
     * @param  roots
     *         The possibly-empty array of module names of the modules
     *         to resolve
     *
     * @return The configuration that is the result of resolving the given
     *         root modules
     *
     * @throws ResolutionException If resolution or the post-resolution checks
     *         fail for any of the reasons listed
     */
    public static Configuration resolve(ModuleFinder beforeFinder,
                                        Layer parent,
                                        ModuleFinder afterFinder,
                                        String... roots)
    {
        return resolve(beforeFinder, parent, afterFinder, Arrays.asList(roots));
    }

    /**
     * Returns the parent {@code Layer} on which this configuration is based.
     *
     * @return The parent layer
     */
    public Layer layer() {
        return parent;
    }

    /**
     * Returns a new configuration that is this configuration augmented with
     * modules induced by the service-use relation.
     *
     * <p> Service binding works by examining all modules in the configuration
     * and its parent layers with {@link ModuleDescriptor#uses()
     * service-dependences}. All observable modules that {@link
     * ModuleDescriptor#provides() provide} an implementation of one or more of
     * the service types are added to the configuration and resolved as if by
     * calling the {@link #resolve resolve} method. Adding modules to the
     * configuration may introduce new service-use dependences and so service
     * binding proceeds iteratively until no more modules are added. </p>
     *
     * <p> As service binding involves resolution then it may fail with {@link
     * ResolutionException} for exactly the same reasons as the {@link #resolve
     * resolve} methods. </p>
     *
     * @return A new configuration that is this configuration augmented
     *         with modules that are induced by the service-use relation
     *
     * @throws ResolutionException If resolution or the post-resolution checks
     *         fail for any of the reasons listed
     *
     * @apiNote This method is not required to be thread safe
     */
    public Configuration bind() {
        Resolver.Resolution r = resolution.bind();
        return new Configuration(parent, r);
    }

    /**
     * Returns an immutable set of the module descriptors in this
     * configuration.
     *
     * @return A possibly-empty unmodifiable set of module descriptors
     *         for the modules in this configuration
     */
    public Set<ModuleDescriptor> descriptors() {
        return resolution.selected();
    }

    /**
     * Returns an immutable set of the module references to the modules in this
     * configuration.
     *
     * @return A possibly-empty unmodifiable set of modules references
     *         to modules in this configuration
     */
    public Set<ModuleReference> modules() {
        return resolution.references();
    }

    /**
     * Returns the {@code ModuleDescriptor} for the named module.
     *
     * @apiNote Should this check parent to be consistent with Layer#findModule?
     *
     * @param name
     *        The name of the module to find
     *
     * @return The module descriptor for the module with the given name or an
     *         empty {@code Optional} if not in this configuration
     */
    public Optional<ModuleDescriptor> findDescriptor(String name) {
        return findModule(name).map(ModuleReference::descriptor);
    }

    /**
     * Returns the {@code ModuleReference} for the named module.
     *
     * @apiNote Should this check parent to be consistent with Layer#findModule?
     *
     * @param name
     *        The name of the module to find
     *
     * @return The reference to a module with the given name or an empty
     *         {@code Optional} if not in this configuration
     */
    public Optional<ModuleReference> findModule(String name) {
        return resolution.findModule(name);
    }

    /**
     * Returns an immutable set of the read dependences of the given module
     * descriptor. The set may include {@code ModuleDescriptor}s for modules
     * that are in a parent {@code Layer} rather than this configuration.
     *
     * @return A possibly-empty unmodifiable set of the read dependences
     *
     * @throws IllegalArgumentException
     *         If the module descriptor is not in this configuration
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
     * Configuration} that provide one or more implementations of the given
     * service.
     *
     * If this {@code Configuration} is not the result of {@link #bind()
     * binding} then an empty set is returned.
     *
     * @param  st
     *         The service type
     *
     * @return A possibly-empty unmodifiable set of the modules that provide
     *         implementations of the given service
     */
    public Set<ModuleDescriptor> provides(String st) {
        return resolution.provides(st);
    }

    @Override
    public String toString() {
        return descriptors().stream()
                .map(ModuleDescriptor::name)
                .collect(Collectors.joining(", "));
    }

}
