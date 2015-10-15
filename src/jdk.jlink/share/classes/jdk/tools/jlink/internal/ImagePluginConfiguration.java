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

import java.io.DataOutputStream;

import jdk.tools.jlink.plugins.DefaultImageBuilderProvider;
import jdk.tools.jlink.plugins.Plugin;
import java.io.IOException;
import java.lang.reflect.Layer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jdk.tools.jlink.plugins.ExecutableImage;

import jdk.tools.jlink.plugins.ImageBuilder;
import jdk.tools.jlink.plugins.ImageFilePlugin;
import jdk.tools.jlink.plugins.ImageFilePluginProvider;
import jdk.tools.jlink.plugins.ImageFilePool;
import jdk.tools.jlink.plugins.Jlink;
import jdk.tools.jlink.plugins.Jlink.StackedPluginConfiguration;
import jdk.tools.jlink.plugins.ResourcePlugin;
import jdk.tools.jlink.plugins.ResourcePluginProvider;
import jdk.tools.jlink.plugins.PluginProvider;
import jdk.tools.jlink.plugins.PostProcessingPlugin;
import jdk.tools.jlink.plugins.PostProcessingPluginProvider;

/**
 * Plugins configuration.
 */
public final class ImagePluginConfiguration {
    private static final class OrderedPlugin implements Comparable<OrderedPlugin> {

        private final int order;
        private final Plugin plugin;

        private OrderedPlugin(int order, Plugin plugin) {
            this.order = order;
            this.plugin = plugin;
        }

        @Override
        public int compareTo(OrderedPlugin o) {
            int diff = order - o.order;
            if (diff == 0) {
                throw new IllegalArgumentException("Plugin index should never "
                        + "be equal. Occurs for " + plugin.getName() + " index " + order);
            }
            return diff;
        }

        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }
            if (!(other instanceof OrderedPlugin)) {
                return false;
            }
            OrderedPlugin op = (OrderedPlugin) other;
            return op.plugin.equals(plugin) && op.order == order;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 71 * hash + this.order;
            hash = 71 * hash + Objects.hashCode(this.plugin);
            return hash;
        }
    }

    private static final Map<String, Integer> RESOURCES_RANGES = new HashMap<>();
    private static final List<String> RESOURCES_CATEGORIES = new ArrayList<>();

    private static final List<String> FILES_CATEGORIES = new ArrayList<>();
    private static final Map<String, Integer> FILES_RANGES = new HashMap<>();

    private static final List<String> POST_PROCESSORS_CATEGORIES = new ArrayList<>();
    private static final Map<String, Integer> POST_PROCESSORS_RANGES = new HashMap<>();

    private static final int RANGE_LENGTH = 5000;

    static {
        RESOURCES_CATEGORIES.add(PluginProvider.FILTER);
        RESOURCES_CATEGORIES.add(PluginProvider.TRANSFORMER);
        RESOURCES_CATEGORIES.add(PluginProvider.SORTER);
        RESOURCES_CATEGORIES.add(PluginProvider.COMPRESSOR);

        int end = RANGE_LENGTH;
        for(String category : RESOURCES_CATEGORIES) {
            RESOURCES_RANGES.put(category, end);
            end +=RANGE_LENGTH;
        }

        FILES_CATEGORIES.add(PluginProvider.FILTER);
        FILES_CATEGORIES.add(PluginProvider.TRANSFORMER);
        FILES_CATEGORIES.add(PluginProvider.SORTER);
        FILES_CATEGORIES.add(PluginProvider.COMPRESSOR);

        int end2 = RANGE_LENGTH;
        for(String category : FILES_CATEGORIES) {
            FILES_RANGES.put(category, end2);
            end2 +=RANGE_LENGTH;
        }

        POST_PROCESSORS_CATEGORIES.add(PluginProvider.VERIFIER);
        POST_PROCESSORS_CATEGORIES.add(PluginProvider.PROCESSOR);
        POST_PROCESSORS_CATEGORIES.add(PluginProvider.PACKAGER);

        int end3 = RANGE_LENGTH;
        for (String category : POST_PROCESSORS_CATEGORIES) {
            POST_PROCESSORS_RANGES.put(category, end3);
            end3 += RANGE_LENGTH;
        }

    }

    private ImagePluginConfiguration() {
    }

    public static ImagePluginStack parseConfiguration(Jlink.PluginsConfiguration plugins)
            throws Exception {
        return parseConfiguration(null, plugins, Layer.boot(), null);
    }
    /*
     * Create a stack of plugins from a a configuration.
     *
     */
    public static ImagePluginStack parseConfiguration(Path outDir,
            Jlink.PluginsConfiguration plugins,
            Layer pluginsLayer,
            String bom)
            throws Exception {
        if (plugins == null) {
            return new ImagePluginStack(bom);
        }

        List<OrderedPlugin> resourcePlugins = new ArrayList<>();
        List<OrderedPlugin> filePlugins = new ArrayList<>();
        Map<String, PluginProvider> providers
                = toMap(ImagePluginProviderRepository.getPluginProviders(pluginsLayer));
        // Validate stack
        Map<String, List<Integer>> resources = new HashMap<>();
        Map<String, List<Integer>> files = new HashMap<>();
        List<OrderedPlugin> postProcessingPlugins = new ArrayList<>();
        List<StackedPluginConfiguration> allPlugins = new ArrayList<>();

        for (Jlink.StackedPluginConfiguration plug : plugins.getTransformerPluginsConfig()) {
            if (plug.getIndex() < 0) {
                throw new Exception("Invalid index " + plug.getIndex() + " for "
                        + plug.getName());
            }
            PluginProvider prov = providers.get(plug.getName());
            if (prov == null) {
                throw new Exception("Unknown plugin " + plug.getName());
            }
            if (!isImageFileProvider(prov) && !isResourceProvider(prov)) {
                throw new Exception("Invalid provider type " + prov);
            }
            Map<String, List<Integer>> map = isResourceProvider(prov) ? resources : files;
            List<Integer> lst = map.get(prov.getCategory());
            if (lst == null) {
                lst = new ArrayList<>();
                map.put(prov.getCategory(), lst);
            }
            int index;
            if (isResourceProvider(prov)) {
                index = getAbsoluteIndex(plug.getIndex(),
                        prov.getCategory(),
                        plug.isAbsoluteIndex(),
                        RESOURCES_RANGES);
            } else {
                index = getAbsoluteIndex(plug.getIndex(),
                        prov.getCategory(),
                        plug.isAbsoluteIndex(),
                        FILES_RANGES);
            }
            if (lst.contains(index)) {
                throw new Exception(plug.getName() + ", a Plugin is already located at index " + index);
            }
            lst.add(index);
            allPlugins.add(plug);
        }

        Map<String, List<Integer>> postprocessors = new HashMap<>();
        for (Jlink.StackedPluginConfiguration plug : plugins.getPostProcessorPluginsConfig()) {
            if (plug.getIndex() < 0) {
                throw new Exception("Invalid index " + plug.getIndex() + " for "
                        + plug.getName());
            }
            PluginProvider prov = providers.get(plug.getName());
            if (prov == null) {
                throw new Exception("Unknown plugin " + plug.getName());
            }

            if (!isPostProcessingProvider(prov)) {
                throw new Exception("Invalid provider type " + prov);
            }

            List<Integer> lst = postprocessors.get(prov.getCategory());
            if (lst == null) {
                lst = new ArrayList<>();
                postprocessors.put(prov.getCategory(), lst);
            }
            int index = getAbsoluteIndex(plug.getIndex(),
                    prov.getCategory(),
                    plug.isAbsoluteIndex(),
                    POST_PROCESSORS_RANGES);

            if (lst.contains(index)) {
                throw new Exception(plug.getName() + ", a Plugin is already located at index " + index);
            }
            lst.add(index);
            allPlugins.add(plug);
        }

        List<String> seen = new ArrayList<>();
        for (StackedPluginConfiguration prop : allPlugins) {
            PluginProvider prov = providers.get(prop.getName());
            if (!prov.isFunctional()) {
                throw new Exception("Provider " + prov.getName() + " is not functional");
            }
            if (seen.contains(prov.getName())) {
                throw new Exception("Plugin " + prov.getName()
                        + " added more than once to stack ");
            }
            seen.add(prov.getName());
            if (isResourceProvider(prov)) {
                int index = getAbsoluteIndex(prop.getIndex(),
                        prov.getCategory(),
                        prop.isAbsoluteIndex(),
                        RESOURCES_RANGES);
                resourcePlugins.addAll(createOrderedPlugins(index, prop.getName(),
                        prop.getConfig(), pluginsLayer));
            } else {
                if (isImageFileProvider(prov)) {
                    int index = getAbsoluteIndex(prop.getIndex(),
                            prov.getCategory(),
                            prop.isAbsoluteIndex(),
                            FILES_RANGES);
                    filePlugins.addAll(createOrderedPlugins(index, prop.getName(),
                            prop.getConfig(), pluginsLayer));
                } else {
                    if (isPostProcessingProvider(prov)) {
                        int index = getAbsoluteIndex(prop.getIndex(),
                                prov.getCategory(),
                                prop.isAbsoluteIndex(),
                                POST_PROCESSORS_RANGES);
                        postProcessingPlugins.addAll(createOrderedPlugins(index, prop.getName(),
                                prop.getConfig(), pluginsLayer));
                    }
                }
            }
        }

        List<ResourcePlugin> resourcePluginsList = toPluginsList(resourcePlugins);
        Plugin lastSorter = null;
        for (Plugin plugin : resourcePluginsList) {
            if (plugin.getName().equals(plugins.getLastSorterPluginName())) {
                lastSorter = plugin;
                break;
            }
        }
        if (plugins.getLastSorterPluginName() != null && lastSorter == null) {
            throw new IOException("Unknown last plugin "
                    + plugins.getLastSorterPluginName());
        }
        List<ImageFilePlugin> filePluginsList = toPluginsList(filePlugins);

        List<PostProcessingPlugin> postprocessorPluginsList = toPluginsList(postProcessingPlugins);

        ImageBuilder builder;
        if (outDir == null) {
            // This should be the case for jimage only creation or post-install.
            builder = new ImageBuilder() {

                @Override
                public void storeFiles(ImageFilePool files,
                        List<ImageFilePool.ImageFile> removed,
                        String bom, ResourceRetriever resources)
                        throws IOException {
                    throw new IOException("No directory setup to store files");
                }

                @Override
                public DataOutputStream getJImageOutputStream() throws IOException {
                    throw new IOException("No directory setup to store files");
                }

                @Override
                public ExecutableImage getExecutableImage() throws IOException {
                    throw new UnsupportedOperationException("No directory setup to store files");
                }

                @Override
                public void storeJavaLauncherOptions(ExecutableImage image, List<String> args) throws IOException {
                    throw new UnsupportedOperationException("No directory setup to store files");
                }
            };
        } else {
            String builderName = plugins.getImageBuilder() == null
                    ? DefaultImageBuilderProvider.NAME : plugins.getImageBuilder().getName();
            Map<String, Object> builderConfig = plugins.getImageBuilder() == null
                    ? Collections.emptyMap() : plugins.getImageBuilder().getConfig();
            Map<String, Object> map = getDefaultContent();
            map.putAll(builderConfig);
            builder = ImagePluginProviderRepository.newImageBuilder(map,
                    outDir,
                    builderName,
                    pluginsLayer);
        }
        return new ImagePluginStack(builder, resourcePluginsList,
                lastSorter, filePluginsList, postprocessorPluginsList, bom);
    }

    private static Map<String, Object> getDefaultContent() {
        Map<String, Object> map = new HashMap<>();
        // Direct mapping from system properties
        map.put(PluginProvider.PLATFORM_NAME_OPTION, System.getProperty("os.name"));

        return map;
    }
    private static boolean isResourceProvider(PluginProvider prov) {
        return prov instanceof ResourcePluginProvider;
    }

    private static boolean isImageFileProvider(PluginProvider prov) {
        return prov instanceof ImageFilePluginProvider;
    }

    private static boolean isPostProcessingProvider(PluginProvider prov) {
        return prov instanceof PostProcessingPluginProvider;
    }

    private static int getAbsoluteIndex(int index, String category,
            boolean absolute, Map<String, Integer> ranges) throws Exception {

        if (absolute) {
            return index;
        }
        // If non null category and not absolute, get index within category
        if (category != null) {
            return ranges.get(category) + index;
        }

        throw new Exception("Can't compute index, no category");
    }

    private static Map<String, PluginProvider> toMap(List<PluginProvider> providers) {
        Map<String, PluginProvider> ret = new HashMap<>();
        for (PluginProvider prov : providers) {
            ret.put(prov.getName(), prov);
        }
        return ret;
    }

    /**
     * Retrieve the range array (index 0 is range start, index 1 is range end)
     * associated to a category.
     * @param provider The provider for which the range is wanted.
     * @return The range or null if the provider category is unknown.
     */
    public static Integer[] getRange(PluginProvider provider) {
        Map<String, Integer> ranges = null;
        if (isResourceProvider(provider)) {
            ranges = RESOURCES_RANGES;
        } else if (isImageFileProvider(provider)) {
            ranges = FILES_RANGES;
        } else if (isPostProcessingProvider(provider)) {
            ranges = POST_PROCESSORS_RANGES;
        } else {
            throw new IllegalArgumentException("Unknown provider type");
        }
        return getRange(provider.getCategory(), ranges);
    }

    private static Integer[] getRange(String category, Map<String, Integer> ranges) {
        Objects.requireNonNull(category);
        Integer[] range = null;
        Integer i = ranges.get(category);
        if (i != null) {
            range = new Integer[2];
            range[0] = i;
            range[1] = i + RANGE_LENGTH;
        }
        return range;
    }

    private static List<OrderedPlugin> createOrderedPlugins(int index,
            String name, Map<String, Object> config, Layer pluginsLayer) throws IOException {
        Map<String, Object> map = getDefaultContent();
        map.putAll(config);
        Plugin[] plugins = ImagePluginProviderRepository.newPlugins(map,
                name, pluginsLayer);
        List<OrderedPlugin> ordered = new ArrayList<>();
        if (plugins != null) {
            for (Plugin plugin : plugins) {
                ordered.add(new OrderedPlugin(index, plugin));
                index = index + 1;
            }
        }
        return ordered;
    }

    private static <T> List<T> toPluginsList(List<OrderedPlugin> lst)
            throws IOException {
        Collections.sort(lst);
        List<T> plugins = new ArrayList<>();
        lst.stream().forEach((op) -> {
            @SuppressWarnings("unchecked")
            T p = (T) op.plugin;
            plugins.add(p);
        });
        return plugins;
    }
}
