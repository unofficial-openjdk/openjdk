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
import jdk.tools.jlink.plugins.Plugin;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import jdk.tools.jlink.plugins.ImageBuilder;
import jdk.tools.jlink.plugins.ImageFilePlugin;
import jdk.tools.jlink.plugins.ImageFilePluginProvider;
import jdk.tools.jlink.plugins.ImageFilePool;
import jdk.tools.jlink.plugins.ResourcePlugin;
import jdk.tools.jlink.plugins.ResourcePluginProvider;
import jdk.tools.jlink.plugins.PluginProvider;

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

    }

    public static final String ON_ARGUMENT = "on";
    public static final String OFF_ARGUMENT = "off";

    public static final String IMAGE_BUILDER_PROPERTY = "jdk.jlink.image.builder";

    public static final String RESOURCES_RADICAL_PROPERTY = "jdk.jlink.plugins.resources.";
    public static final String FILES_RADICAL_PROPERTY = "jdk.jlink.plugins.files.";

    public static final String COMPRESSOR = "compressor";
    public static final String SORTER = "sorter";
    public static final String TRANSFORMER = "transformer";
    public static final String FILTER = "filter";


    public static final String FILES_TRANSFORMER_PROPERTY = FILES_RADICAL_PROPERTY +
            TRANSFORMER;
    public static final String FILES_FILTER_PROPERTY = FILES_RADICAL_PROPERTY +
            FILTER;

    public static final String RESOURCES_COMPRESSOR_PROPERTY = RESOURCES_RADICAL_PROPERTY +
            COMPRESSOR;
    public static final String RESOURCES_SORTER_PROPERTY = RESOURCES_RADICAL_PROPERTY +
            SORTER;
    public static final String RESOURCES_TRANSFORMER_PROPERTY = RESOURCES_RADICAL_PROPERTY +
            TRANSFORMER;
    public static final String RESOURCES_FILTER_PROPERTY = RESOURCES_RADICAL_PROPERTY +
            FILTER;
    public static final String PATH_PROPERTY = "jdk.jlink.classpath";

    public static final String RESOURCES_LAST_SORTER_PROPERTY = RESOURCES_RADICAL_PROPERTY +
            "resources.last-sorter";


    private static final Map<String, Integer> RESOURCES_RANGES = new HashMap<>();
    private static final List<String> RESOURCES_CATEGORIES = new ArrayList<>();

    private static final List<String> FILES_CATEGORIES = new ArrayList<>();
    private static final Map<String, Integer> FILES_RANGES = new HashMap<>();

    private static final int RANGE_LENGTH = 5000;

    static {
        RESOURCES_CATEGORIES.add(FILTER);
        RESOURCES_CATEGORIES.add(TRANSFORMER);
        RESOURCES_CATEGORIES.add(SORTER);
        RESOURCES_CATEGORIES.add(COMPRESSOR);

        int end = RANGE_LENGTH;
        for(String category : RESOURCES_CATEGORIES) {
            RESOURCES_RANGES.put(category, end);
            end +=RANGE_LENGTH;
        }

        FILES_CATEGORIES.add(FILTER);
        FILES_CATEGORIES.add(TRANSFORMER);

        int end2 = RANGE_LENGTH;
        for(String category : FILES_CATEGORIES) {
            FILES_RANGES.put(category, end2);
            end2 +=RANGE_LENGTH;
        }
    }

    private ImagePluginConfiguration() {}

    /**
     * Create a stack of plugins from a configuration file.
     * @param p Properties file.
     * @return A stack of plugins.
     * @throws IOException
     */
    public static ImagePluginStack parseConfiguration(Properties p)
            throws IOException {
        return parseConfiguration(null, null, p);
    }

    /**
     * Create a stack of plugins from a configuration file.
     * @param outDir The directory where to generate the image.
     * Used to build an ImageBuilder.
     * @param mods
     * @param p Properties file.
     * @return A stack of plugins.
     * @throws IOException
     */
    public static ImagePluginStack parseConfiguration(Path outDir,
            Map<String, Path> mods,
            Properties p)
            throws IOException {
        if (p == null) {
            return new ImagePluginStack();
        }
        String path = (String) p.remove(PATH_PROPERTY);
        String lastSorterName = (String) p.remove(RESOURCES_LAST_SORTER_PROPERTY);
        ClassLoader loader = path == null ? null : newClassLoader(path);
        List<OrderedPlugin> resourcePlugins = new ArrayList<>();
        List<OrderedPlugin> filePlugins = new ArrayList<>();
        for (String prop : p.stringPropertyNames()) {
            if (prop.startsWith(RESOURCES_RADICAL_PROPERTY)) {
                int index = getIndex(prop, RESOURCES_RADICAL_PROPERTY,
                        RESOURCES_RANGES);
                resourcePlugins.addAll(createOrderedPlugins(index, p, prop, loader));
            } else if (prop.startsWith(FILES_RADICAL_PROPERTY)) {
                int index = getIndex(prop, FILES_RADICAL_PROPERTY, FILES_RANGES);
                filePlugins.addAll(createOrderedPlugins(index, p, prop, loader));
            }
        }
        List<ResourcePlugin> resourcePluginsList = toPluginsList(resourcePlugins);
        Plugin lastSorter = null;
        for(Plugin plugin : resourcePluginsList) {
            if(plugin.getName().equals(lastSorterName)) {
                lastSorter = plugin;
                break;
            }
        }
        if(lastSorterName != null && lastSorter == null) {
            throw new IOException("Unknown last plugin " + lastSorterName);
        }
        List<ImageFilePlugin> filePluginsList = toPluginsList(filePlugins);
        ImageBuilder builder;
        if (outDir == null) {
            // This should be the case for jimage only creation.
            builder = new ImageBuilder() {

                @Override
                public void storeFiles(ImageFilePool files, Set<String> modules)
                        throws IOException {
                    throw new IOException("No directory setup to store files");
                }

                @Override
                public DataOutputStream getJImageOutputStream() throws IOException {
                    throw new IOException("No directory setup to store files");
                }
            };
        } else {
            String builderName = p.getProperty(IMAGE_BUILDER_PROPERTY);
            Properties filtered = filter(p, builderName == null ?
                    DefaultImageBuilderProvider.NAME : builderName);
            if (builderName != null) {
                builder = ImagePluginProviderRepository.newImageBuilder(filtered, outDir,
                        builderName, loader);
            } else {
                builder = new DefaultImageBuilder(filtered, outDir, mods);
            }
        }
        return new ImagePluginStack(builder, resourcePluginsList,
                lastSorter, filePluginsList);
    }

    private static Properties filter(Properties p, String name) {
        Properties filtered = new Properties();
        p.stringPropertyNames().stream().filter(
                (n) -> (n.startsWith(name))).forEach((n) -> {
                    String pluginProp = n.substring(name.length() + 1);
                    filtered.setProperty(pluginProp, p.getProperty(n));
                });
        return filtered;
    }

    private static ClassLoader newClassLoader(String path)
            throws MalformedURLException {
        String[] split = path.split(File.pathSeparator);
        URL[] urls = new URL[split.length];
        for(int i = 0; i < split.length; i++) {
            urls[i] = new File(split[i]).toURI().toURL();
        }
        return new URLClassLoader(urls);
    }

    /**
     * Retrieve the range array (index 0 is range start, index 1 is range end)
     * associated to a category.
     * @param provider The provider for which the range is wanted.
     * @return The range or null if the provider category is unknown.
     */
    public static Integer[] getRange(PluginProvider provider) {
        Map<String, Integer> ranges = null;
        if (provider instanceof ResourcePluginProvider) {
            ranges = RESOURCES_RANGES;
        } else if (provider instanceof ImageFilePluginProvider) {
            ranges = FILES_RANGES;
        } else {
            throw new IllegalArgumentException("Unknown provider type");
        }
        return getRange(provider.getCategory(), ranges);
    }

    /**
     * Retrieve the range array (index 0 is range start, index 1 is range end)
     * associated to a category.
     * @param category The category for which the range is wanted.
     * @return The range or null if the category is unknown.
     */
    public static Integer[] getFilesRange(String category) {
        return getRange(category, FILES_RANGES);
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

    /**
     * Return a list of the names of the known plugin categories ordered from
     * the smaller range start index to the bigger range start index.
     * @return
     */
    public List<String> getOrderedResourcesCategories() {
        return Collections.unmodifiableList(RESOURCES_CATEGORIES);
    }

    /**
     * Return a list of the names of the known plugin categories ordered from
     * the smaller range start index to the bigger range start index.
     * @return
     */
    public List<String> getOrderedFilesCategories() {
        return Collections.unmodifiableList(FILES_CATEGORIES);
    }

    public static void addPluginProperty(Properties properties,
            PluginProvider provider) throws IllegalArgumentException {

        String radical = null;
        Map<String, Integer> ranges = null;
        if (provider instanceof ResourcePluginProvider) {
            ranges = RESOURCES_RANGES;
            radical = RESOURCES_RADICAL_PROPERTY;
        } else if (provider instanceof ImageFilePluginProvider) {
            ranges = FILES_RANGES;
            radical = FILES_RADICAL_PROPERTY;
        } else {
            throw new IllegalArgumentException("Unknown provider type");
        }
        int index = getNextIndex(properties, provider.getCategory(), radical, ranges);
        properties.setProperty(radical
                + provider.getCategory() + "." + index, provider.getName());
    }

    private static int getNextIndex(Properties props,
            String category, String radical, Map<String, Integer> ranges)
            throws IllegalArgumentException {
        Objects.requireNonNull(props);
        Objects.requireNonNull(category);
        Integer range_start = ranges.get(category);
        if (range_start == null) {
            throw new IllegalArgumentException("Unknown " + category);
        }
        int index = range_start;
        for (String prop : props.stringPropertyNames()) {
            if (prop.startsWith(radical)) {
                int i = getIndex(prop, radical, ranges);
                // we are in same range
                if (i >= range_start && i < range_start + RANGE_LENGTH) {
                    if (i > index) {
                        index = i;
                    }
                }
            }
        }
        index = index - range_start + 1;
        if (index >= RANGE_LENGTH) {
            throw new IllegalArgumentException("Can't find an available index for "
                    + category);
        }
        return index;
    }

    private static int getIndex(String prop, String radical,
            Map<String, Integer> ranges) {
        String suffix = prop.substring(radical.length());
        String[] split = suffix.split("\\.");
        if (split.length > 2 || split.length == 0) {
            throw new IllegalArgumentException("Invalid property " + prop);
        }
        int order = 0;
        boolean label = false;
        // radical.label[.num] or radical.num
        for (int i = 0; i < split.length; i++) {
            Integer val = null;
            String s = split[i];
            if (i == 0) {
                val = ranges.get(s);
                if (val == null) {
                    val = Integer.valueOf(s);
                } else {
                    label = true;
                }
            } else {
                if (!label) {
                    throw new IllegalArgumentException("Invalid property " + prop);
                }
                val = Integer.valueOf(s);
            }
            order += val;
        }
        return order;
    }

    private static List<OrderedPlugin> createOrderedPlugins(int index, Properties p,
            String prop, ClassLoader loader) throws IOException {
        String name = p.getProperty(prop);
        Properties filtered = filter(p, name);
        Plugin[] plugins = ImagePluginProviderRepository.newPlugins(filtered,
                name, loader);
        List<OrderedPlugin> ordered = new ArrayList<>();
        for (Plugin plugin : plugins) {
            ordered.add(new OrderedPlugin(index, plugin));
            index = index+1;
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
