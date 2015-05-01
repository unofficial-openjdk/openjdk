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

import java.io.UncheckedIOException;
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
 *     ModuleArtifactFinder finder =
 *         ModuleArtifactFinder.ofDirectories(dir1, dir2, dir3);
 *
 *     Configuration cf =
 *         Configuration.resolve(ModuleArtifactFinder.nullFinder(),
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
     * Resolves the given named modules. Module dependences are resolved by
     * locating them (in order) using the given {@code beforeFinder}, {@code
     * layer}, and {@code afterFinder}.
     *
     * @throws ResolutionException If a named module (or any its transitive
     *                             dependencies) cannot be resolved
     */
    public static Configuration resolve(ModuleArtifactFinder beforeFinder,
                                        Layer parent,
                                        ModuleArtifactFinder afterFinder,
                                        Collection<String> roots)
    {
        Objects.requireNonNull(beforeFinder);
        Objects.requireNonNull(parent);
        Objects.requireNonNull(afterFinder);

        try {

            Resolver.Resolution resolution =
                Resolver.resolve(beforeFinder, parent, afterFinder, roots);
            return new Configuration(parent, resolution);

        } catch (ClassFormatError | UncheckedIOException e) {
            // TBD need to specify how CFE and I/O errors are handled
            throw new ResolutionException(e);
        }
    }

    /**
     * Resolves the given named modules. Module dependences are resolved by
     * locating them (in order) using the given {@code beforeFinder}, {@code
     * layer}, and {@code afterFinder}.
     *
     * @throws ResolutionException If a named module (or any its transitive
     *                             dependencies) cannot be resolved
     */
    public static Configuration resolve(ModuleArtifactFinder beforeFinder,
                                        Layer layer,
                                        ModuleArtifactFinder afterFinder,
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
     * (located via the module artifact finders) that are induced by service-use
     * relationships.
     *
     * @throws ResolutionException If a named module (or any its transitive
     *                             dependencies) cannot be resolved
     *
     * @apiNote This method is not thread safe
     */
    public Configuration bind() {
        try {

            Resolver.Resolution r = resolution.bind();
            return new Configuration(parent, r);

        } catch (ClassFormatError | UncheckedIOException e) {
            // TBD need to specify how CFE and I/O errors are handled
            throw new ResolutionException(e);
        }
    }

    /**
     * Returns an immutable set of the module descriptors in this
     * configuration.
     */
    public Set<ModuleDescriptor> descriptors() {
        return resolution.selected();
    }

    /**
     * Returns the {@code ModuleArtifact} for the given named module or
     * {@code null} if a module of the given name is not in this
     * configuration.
     */
    public ModuleArtifact findArtifact(String name) {
        return resolution.findArtifact(name);
    }

    /**
     * Returns the {@code ModuleDescriptor} for the given named module
     * or {@code null} if a module of the given name is not in this
     * configuration.
     *
     * @apiNote It's not clear that this method is useful,
     */
    public ModuleDescriptor findDescriptor(String name) {
        ModuleArtifact artifact = findArtifact(name);
        if (artifact == null) {
            return null;
        } else {
            return artifact.descriptor();
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

