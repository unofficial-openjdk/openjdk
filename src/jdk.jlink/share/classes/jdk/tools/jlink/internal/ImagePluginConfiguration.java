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

import jdk.tools.jlink.plugin.Plugin;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jdk.tools.jlink.plugin.ExecutableImage;
import jdk.tools.jlink.builder.ImageBuilder;
import jdk.tools.jlink.Jlink;
import jdk.tools.jlink.plugin.TransformerPlugin;
import jdk.tools.jlink.plugin.PluginException;
import jdk.tools.jlink.plugin.Plugin.CATEGORY;
import jdk.tools.jlink.plugin.Pool;
import jdk.tools.jlink.plugin.PostProcessorPlugin;

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

    private static final Map<Plugin.CATEGORY, Integer> CATEGORIES_RANGES = new HashMap<>();
    private static final List<Plugin.CATEGORY> CATEGORIES_ORDER = new ArrayList<>();

    private static final int RANGE_LENGTH = 5000;

    static {
        CATEGORIES_ORDER.add(Plugin.CATEGORY.FILTER);
        CATEGORIES_ORDER.add(Plugin.CATEGORY.TRANSFORMER);
        CATEGORIES_ORDER.add(Plugin.CATEGORY.MODULEINFO_TRANSFORMER);
        CATEGORIES_ORDER.add(Plugin.CATEGORY.SORTER);
        CATEGORIES_ORDER.add(Plugin.CATEGORY.COMPRESSOR);
        CATEGORIES_ORDER.add(Plugin.CATEGORY.VERIFIER);
        CATEGORIES_ORDER.add(Plugin.CATEGORY.PROCESSOR);
        CATEGORIES_ORDER.add(Plugin.CATEGORY.PACKAGER);

        int end = RANGE_LENGTH;
        for (Plugin.CATEGORY category : CATEGORIES_ORDER) {
            CATEGORIES_RANGES.put(category, end);
            end += RANGE_LENGTH;
        }
    }

    private ImagePluginConfiguration() {
    }

    public static ImagePluginStack parseConfiguration(Jlink.PluginsConfiguration plugins)
            throws Exception {
        return parseConfiguration(plugins, null);
    }

    /*
     * Create a stack of plugins from a a configuration.
     *
     */
    public static ImagePluginStack parseConfiguration(Jlink.PluginsConfiguration plugins,
            String bom)
            throws Exception {
        if (plugins == null) {
            return new ImagePluginStack(bom);
        }
        List<OrderedPlugin> resourcePlugins = new ArrayList<>();
        List<OrderedPlugin> postProcessingPlugins = new ArrayList<>();
        // Validate stack
        Map<Plugin.CATEGORY, List<Integer>> resources = new HashMap<>();
        List<String> seen = new ArrayList<>();
        for (Jlink.OrderedPlugin plug : plugins.getPlugins()) {
            if (plug.getIndex() < 0) {
                throw new Exception("Invalid index " + plug.getIndex() + " for "
                        + plug);
            }
            if (seen.contains(plug.getPlugin().getName())) {
                throw new Exception("Plugin " + plug.getPlugin().getName()
                        + " added more than once to stack ");
            }
            seen.add(plug.getPlugin().getName());
            CATEGORY category = Utils.getCategory(plug.getPlugin());
            if (category == null) {
                throw new PluginException("Invalid category for "
                        + plug.getPlugin().getName());
            }
            List<Integer> lst = resources.get(category);
            if (lst == null) {
                lst = new ArrayList<>();
                resources.put(category, lst);
            }
            int index = getAbsoluteIndex(plug.getIndex(),
                    category,
                    plug.isAbsoluteIndex(),
                    CATEGORIES_RANGES);
            if (lst.contains(index)) {
                throw new Exception(plug.getPlugin().getName()
                        + ", a Plugin is already located at index " + index);
            }
            lst.add(index);
            OrderedPlugin p = new OrderedPlugin(index, plug.getPlugin());
            if (Utils.isPostProcessor(category)) {
                postProcessingPlugins.add(p);
            } else {
                resourcePlugins.add(p);
            }
        }

        List<TransformerPlugin> resourcePluginsList = toPluginsList(resourcePlugins);
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
        List<PostProcessorPlugin> postprocessorPluginsList = toPluginsList(postProcessingPlugins);

        ImageBuilder builder = plugins.getImageBuilder();
        if (builder == null) {
            // This should be the case for jimage only creation or post-install.
            builder = new ImageBuilder() {

                @Override
                public DataOutputStream getJImageOutputStream() {
                    throw new PluginException("No directory setup to store files");
                }

                @Override
                public ExecutableImage getExecutableImage() {
                    throw new PluginException("No directory setup to store files");
                }

                @Override
                public void storeFiles(Pool files, String bom) {
                    throw new PluginException("No directory setup to store files");
                }
            };
        }

        return new ImagePluginStack(builder, resourcePluginsList,
                lastSorter, postprocessorPluginsList, bom);
    }

    private static int getAbsoluteIndex(int index, Plugin.CATEGORY category,
            boolean absolute, Map<Plugin.CATEGORY, Integer> ranges) throws Exception {

        if (absolute) {
            return index;
        }
        if (index == Integer.MAX_VALUE) {
            return ranges.get(category) + RANGE_LENGTH - 1;
        } else { // If non null category and not absolute, get index within category
            if (category != null) {
                return ranges.get(category) + index;
            }
        }

        throw new Exception("Can't compute index, no category");
    }

    /**
     * Retrieve the range array (index 0 is range start, index 1 is range end)
     * associated to a category.
     *
     * @param category The category for which the range is wanted.
     * @return The range or null if the provider category is unknown.
     */
    public static Integer[] getRange(CATEGORY category) {
        Objects.requireNonNull(category);
        Integer[] range = null;
        Integer i = CATEGORIES_RANGES.get(category);
        if (i != null) {
            range = new Integer[2];
            range[0] = i;
            range[1] = i + RANGE_LENGTH;
        }
        return range;
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
