/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
package jdk.tools.jlink.internal;

import jdk.tools.jlink.plugins.PluginProvider;
import jdk.tools.jlink.plugins.Plugin;
import java.io.IOException;
import java.lang.reflect.Layer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.ServiceLoader;
import jdk.tools.jlink.plugins.ImageBuilder;
import jdk.tools.jlink.plugins.ImageBuilderProvider;

/**
 *
 * Plugin Providers repository. Plugin Providers are
 * retrieved thanks to the ServiceLoader mechanism.
 */
public final class ImagePluginProviderRepository {

    private ImagePluginProviderRepository() {
    }

    private static final Map<String, PluginProvider> registeredProviders = new HashMap<>();

    /**
     * Retrieve the provider to build a plugin for the passed name.
     * @param config Optional config.
     * @param name Non null name.
     * @param pluginsLayer
     * @return An array of plugins.
     * @throws IOException
     */
    public static Plugin[] newPlugins(Map<String, Object> config, String name,
            Layer pluginsLayer) throws IOException {
        Objects.requireNonNull(name);
        Objects.requireNonNull(pluginsLayer);
        PluginProvider fact = getPluginProvider(name, pluginsLayer);
        return fact.newPlugins(config);
    }

    /**
     * Retrieves the provider associated to the passed name. If multiple providers
     * exist for the same name,
     * then an exception is thrown.
     * @param name The plugin provider name.
     * @param pluginsLayer
     * @return A provider.
     * @throws IOException
     */
    public static PluginProvider getPluginProvider(String name,
            Layer pluginsLayer) throws IOException {
        Objects.requireNonNull(name);
        Objects.requireNonNull(pluginsLayer);
        PluginProvider provider = registeredProviders.get(name);
        @SuppressWarnings("unchecked")
        Iterator<PluginProvider> javaProviders = getJavaPluginProviders(pluginsLayer);
        while (javaProviders.hasNext()) {
            @SuppressWarnings("unchecked")
            PluginProvider factory = javaProviders.next();
            if (factory.getName().equals(name)) {
                if (provider != null) {
                    throw new IOException("Multiple ImageWriterProvider "
                            + "for the name " + name);
                }
                provider = factory;
            }
        }
        if (provider == null) {
            throw new IOException("Provider not found for " + name);
        }
        return provider;
    }

    /**
     * The list of all the providers accessible in the current context.
     * @param pluginsLayer
     * @return A list of all the providers.
     */
    public static List<PluginProvider> getPluginProviders(Layer pluginsLayer) {
        Objects.requireNonNull(pluginsLayer);
        List<PluginProvider> factories = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Iterator<PluginProvider> javaProviders = getJavaPluginProviders(pluginsLayer);
        while (javaProviders.hasNext()) {
            @SuppressWarnings("unchecked")
            PluginProvider fact = javaProviders.next();
            factories.add(fact);
        }
        registeredProviders.values().stream().forEach((fact) -> {
            factories.add(fact);
        });
        return Collections.unmodifiableList(factories);
    }

    /**
     * Explicit registration of a provider in the repository. Used by unit tests
     *
     * @param provider The provider to register.
     */
    public synchronized static void registerPluginProvider(PluginProvider provider) {
        Objects.requireNonNull(provider);
        registeredProviders.put(provider.getName(), provider);
    }

    /**
     * Explicit unregistration of a provider in the repository. Used by unit
     * tests
     *
     * @param name Provider name
     */
    public synchronized static void unregisterPluginProvider(String name) {
        Objects.requireNonNull(name);
        registeredProviders.remove(name);
    }

    public static ImageBuilder newImageBuilder(Map<String, Object> config, Path outputDir,
            String name, Layer pluginsLayer) throws IOException {
        Objects.requireNonNull(config);
        Objects.requireNonNull(outputDir);
        Objects.requireNonNull(name);
        Objects.requireNonNull(pluginsLayer);
        Iterator<ImageBuilderProvider> providers
                = ServiceLoader.load(pluginsLayer, ImageBuilderProvider.class).iterator();
        ImageBuilder builder = null;
        while (providers.hasNext()) {
            ImageBuilderProvider fact = providers.next();
            if (fact.getName().equals(name)) {
                if(builder != null) {
                     throw new IOException("Multiple ImageBuilderProvider "
                            + "for the name " + name);
                }
                builder = fact.newBuilder(config, outputDir);
            }
        }
        if (builder == null) {
            throw new IOException("Image builder not found for " + name);
        }
        return builder;
    }

    /**
     * The image builder providers accessible in the current context.
     *
     * @param pluginsLayer
     * @return The image builder provider or null if no provider.
     */
    public static List<ImageBuilderProvider> getImageBuilderProviders(Layer pluginsLayer) {
        Objects.requireNonNull(pluginsLayer);
        List<ImageBuilderProvider> factories = new ArrayList<>();
        Iterator<ImageBuilderProvider> providers
                = ServiceLoader.load(pluginsLayer, ImageBuilderProvider.class).iterator();
        while (providers.hasNext()) {
            factories.add(providers.next());
        }
        return factories;
    }

    private static Iterator<PluginProvider> getJavaPluginProviders(Layer pluginsLayer) {
        Objects.requireNonNull(pluginsLayer);
        return ServiceLoader.load(pluginsLayer, PluginProvider.class).iterator();
    }
}
