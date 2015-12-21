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
import java.lang.reflect.Layer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import jdk.tools.jlink.plugins.ImageBuilder;
import jdk.tools.jlink.plugins.ImageBuilderProvider;
import jdk.tools.jlink.plugins.PluginException;
import jdk.tools.jlink.plugins.PostProcessorPlugin;
import jdk.tools.jlink.plugins.PostProcessorPluginProvider;
import jdk.tools.jlink.plugins.TransformerPlugin;
import jdk.tools.jlink.plugins.TransformerPluginProvider;

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
     * Retrieves the provider associated to the passed name. If multiple providers
     * exist for the same name,
     * then an exception is thrown.
     * @param name The plugin provider name.
     * @param pluginsLayer
     * @return A provider or null if not found.
     */
    public static TransformerPluginProvider getTransformerPluginProvider(String name,
            Layer pluginsLayer) {
        return getPluginProvider(TransformerPluginProvider.class, name, pluginsLayer);
    }

    /**
     * Retrieves the provider associated to the passed name. If multiple providers
     * exist for the same name,
     * then an exception is thrown.
     * @param name The plugin provider name.
     * @param pluginsLayer
     * @return A provider or null if not found.
     */
    public static ImageBuilderProvider getImageBuilderProvider(String name,
            Layer pluginsLayer) {
        return getPluginProvider(ImageBuilderProvider.class, name, pluginsLayer);
    }

    /**
     * Retrieves the provider associated to the passed name. If multiple providers
     * exist for the same name,
     * then an exception is thrown.
     * @param name The plugin provider name.
     * @param pluginsLayer
     * @return A provider or null if not found.
     */
    public static PostProcessorPluginProvider getPostProcessingPluginProvider(String name,
            Layer pluginsLayer) {
        return getPluginProvider(PostProcessorPluginProvider.class, name, pluginsLayer);
    }

    /**
     * Build module transformer plugins for the passed name.
     *
     * @param config Optional config.
     * @param name Non null name.
     * @param pluginsLayer
     * @return An array of plugins.
     */
    public static List<? extends TransformerPlugin> newTransformerPlugins(Map<String, Object> config, String name,
            Layer pluginsLayer) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(pluginsLayer);
        TransformerPluginProvider fact = getTransformerPluginProvider(name, pluginsLayer);
        if(fact != null) {
            return fact.newPlugins(config);
        }
        return null;
    }

    /**
     * Build post processing plugins for the passed name.
     *
     * @param config Optional config.
     * @param name Non null name.
     * @param pluginsLayer
     * @return An array of plugins.
     */
    public static List<? extends PostProcessorPlugin> newPostProcessingPlugins(Map<String, Object> config, String name,
            Layer pluginsLayer) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(pluginsLayer);
        PostProcessorPluginProvider fact = getPostProcessingPluginProvider(name, pluginsLayer);
        if(fact != null) {
            return fact.newPlugins(config);
        }
        return null;
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
            String name, Layer pluginsLayer) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(outputDir);
        Objects.requireNonNull(name);
        Objects.requireNonNull(pluginsLayer);
        List<ImageBuilderProvider> providers = getImageBuilderProviders(pluginsLayer);
        List<? extends ImageBuilder> builder = null;
        for (ImageBuilderProvider fact : providers) {
            if (fact.getName().equals(name)) {
                if(builder != null) {
                     throw new PluginException("Multiple ImageBuilderProvider "
                            + "for the name " + name);
                }
                Map<String, Object> all = new HashMap<>();
                all.putAll(config);
                all.put(ImageBuilderProvider.IMAGE_PATH_KEY, outputDir);
                builder = fact.newPlugins(all);
            }
        }
        if (builder == null || builder.isEmpty()) {
            throw new PluginException("Image builder not found for " + name);
        }
        return builder.get(0);
    }

    /**
     * The image builder providers accessible in the current context.
     *
     * @param pluginsLayer
     * @return The list of image builder provider.
     */
    public static List<ImageBuilderProvider> getImageBuilderProviders(Layer pluginsLayer) {
        return getPluginProviders(ImageBuilderProvider.class, pluginsLayer);
    }

    /**
     * The module transformers accessible in the current context.
     *
     * @param pluginsLayer
     * @return The list of module transformer.
     */
    public static List<TransformerPluginProvider> getTransformerProviders(Layer pluginsLayer) {
        return getPluginProviders(TransformerPluginProvider.class, pluginsLayer);
    }

    /**
     * The post processors accessible in the current context.
     *
     * @param pluginsLayer
     * @return The list of post processors.
     */
    public static List<PostProcessorPluginProvider> getPostProcessingProviders(Layer pluginsLayer) {
        return getPluginProviders(PostProcessorPluginProvider.class, pluginsLayer);
    }

    private static <T extends PluginProvider> T getPluginProvider(Class<T> clazz, String name,
            Layer pluginsLayer) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(pluginsLayer);
        @SuppressWarnings("unchecked")
        T provider = null;
        List<T> javaProviders = getPluginProviders(clazz, pluginsLayer);
        for(T factory : javaProviders) {
            if (factory.getName().equals(name)) {
                if (provider != null) {
                    throw new PluginException("Multiple plugin "
                            + "for the name " + name);
                }
                provider = factory;
            }
        }
        return provider;
    }

    /**
     * The post processors accessible in the current context.
     *
     * @param pluginsLayer
     * @return The list of post processors.
     */
    private static <T extends PluginProvider> List<T> getPluginProviders(Class<T> clazz, Layer pluginsLayer) {
        Objects.requireNonNull(pluginsLayer);
        List<T> factories = new ArrayList<>();
        Iterator<T> providers
                = ServiceLoader.load(pluginsLayer, clazz).iterator();
        while (providers.hasNext()) {
            factories.add(providers.next());
        }
        registeredProviders.values().stream().forEach((fact) -> {
            if (clazz.isInstance(fact)) {
                @SuppressWarnings("unchecked")
                T trans = (T) fact;
                factories.add(trans);
            }
        });
        return factories;
    }

}
