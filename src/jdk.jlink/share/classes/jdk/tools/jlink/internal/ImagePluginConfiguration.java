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

import jdk.tools.jlink.plugins.Plugin;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

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

    public static final String RADICAL_PROPERTY = "jdk.jlink.plugins.";
    public static final String COMPRESSOR = "compressor";
    public static final String SORTER = "sorter";
    public static final String TRANSFORMER = "transformer";
    public static final String FILTER = "filter";
    public static final String COMPRESSOR_PROPERTY = RADICAL_PROPERTY + COMPRESSOR;
    public static final String SORTER_PROPERTY = RADICAL_PROPERTY + SORTER;
    public static final String TRANSFORMER_PROPERTY = RADICAL_PROPERTY + TRANSFORMER;
    public static final String FILTER_PROPERTY = RADICAL_PROPERTY + FILTER;
    public static final String PATH_PROPERTY = RADICAL_PROPERTY + "path";
    public static final String LAST_SORTER_PROPERTY = RADICAL_PROPERTY + "last-sorter";


    private static final Map<String, Integer> RANGES = new HashMap<>();
    private static final List<String> CATEGORIES = new ArrayList<>();

    private static final int RANGE_LENGTH = 5000;

    static {
        CATEGORIES.add(FILTER);
        CATEGORIES.add(TRANSFORMER);
        CATEGORIES.add(SORTER);
        CATEGORIES.add(COMPRESSOR);

        int end = RANGE_LENGTH;
        for(String category : CATEGORIES) {
            RANGES.put(category, end);
            end +=RANGE_LENGTH;
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
        if (p == null) {
            return new ImagePluginStack();
        }
        String path = (String) p.remove(PATH_PROPERTY);
        String lastSorterName = (String) p.remove(LAST_SORTER_PROPERTY);
        ClassLoader loader = path == null ? null : newClassLoader(path);
        List<OrderedPlugin> plugins = new ArrayList<>();
        for (String prop : p.stringPropertyNames()) {

            if (prop.startsWith(RADICAL_PROPERTY)) {
                int index = getIndex(prop);
                plugins.add(createOrderedPlugin(index, p, prop, loader));
            }
        }
        List<Plugin> pluginsList = toPluginsList(plugins);
        Plugin lastSorter = null;
        for(Plugin plugin : pluginsList) {
            if(plugin.getName().equals(lastSorterName)) {
                lastSorter = plugin;
                break;
            }
        }
        if(lastSorterName != null && lastSorter == null) {
            throw new IOException("Unknown last plugin " + lastSorterName);
        }
        return new ImagePluginStack(pluginsList, lastSorter);
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
     * @param category The category for which the range is wanted.
     * @return The range or null if the category is unknown.
     */
    public static Integer[] getRange(String category) {
        Objects.requireNonNull(category);
        Integer[] range = null;
        Integer i = RANGES.get(category);
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
    public List<String> getOrderedCategories() {
        return Collections.unmodifiableList(CATEGORIES);
    }

    /**
     * Get the next index to be used inside a given category.
     * @param props The System properties that contain the plugins configuration
     * @param category The plugin category
     * @return An index
     * @throws IllegalArgumentException If the category is not known or if no
     * index is available.
     */
    public static int getNextIndex(Properties props, String category)
            throws IllegalArgumentException {
        Objects.requireNonNull(props);
        Objects.requireNonNull(category);
        Integer range_start = RANGES.get(category);
        if (range_start == null) {
            throw new IllegalArgumentException("Unknown " + category);
        }
        int index = range_start;
        for (String prop : props.stringPropertyNames()) {
            if (prop.startsWith(RADICAL_PROPERTY)) {
                int i = getIndex(prop);
                // we are in same range
                if (i >= range_start && i < range_start + RANGE_LENGTH) {
                    if (i > index) {
                        index = i;
                    }
                }
            }
        }
        index = index + 1;
        if (index >= range_start + RANGE_LENGTH) {
            throw new IllegalArgumentException("Can't find an available index for "
                    + category);
        }
        return index;
    }

    private static int getIndex(String prop) {
        String suffix = prop.substring(RADICAL_PROPERTY.length());
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
                val = RANGES.get(s);
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

    private static OrderedPlugin createOrderedPlugin(int index, Properties p,
            String prop, ClassLoader loader) throws IOException {
        String name = p.getProperty(prop);
        Properties filtered = new Properties();
        p.stringPropertyNames().stream().filter(
                (n) -> (n.startsWith(name))).forEach((n) -> {
            String pluginProp = n.substring(name.length() + 1);
            filtered.setProperty(pluginProp, p.getProperty(n));
        });
        Plugin plugin = ImagePluginProviderRepository.newImageWriterPlugin(filtered,
                name, loader);

        return new OrderedPlugin(index, plugin);
    }

    private static List<Plugin> toPluginsList(List<OrderedPlugin> lst)
            throws IOException {
        Collections.sort(lst);
        List<Plugin> plugins = new ArrayList<>();
        lst.stream().forEach((op) -> {
            plugins.add(op.plugin);
        });
        return plugins;
    }
}
