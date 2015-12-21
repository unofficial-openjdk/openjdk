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
package jdk.tools.jlink;

import java.lang.reflect.Layer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import jdk.tools.jlink.internal.JlinkTask;
import jdk.tools.jlink.plugin.Plugin;
import jdk.tools.jlink.plugin.PluginException;
import jdk.tools.jlink.plugin.ExecutableImage;
import jdk.tools.jlink.builder.ImageBuilder;
import jdk.tools.jlink.plugin.PluginOption;
import jdk.tools.jlink.internal.PluginRepository;

/**
 * API for jlink tool.
 */
public final class Jlink {

    /**
     * A plugin located inside a stack of plugins. Ordered plugin has an index
     * in the stack.
     */
    public static final class OrderedPlugin {

        private final int index;
        private final boolean absIndex;
        private final Plugin plugin;
        /**
         * A plugin inside the stack configuration.
         *
         * @param plugin Plugin.
         * @param index index in the plugin stack. Must be > 0.
         * @param absIndex true, the index is absolute otherwise index is within
         * the plugin category.
         */
        public OrderedPlugin(Plugin plugin, int index, boolean absIndex) {
            Objects.requireNonNull(plugin);
            if (index < 0) {
                throw new IllegalArgumentException("negative index");
            }
            this.plugin = plugin;
            this.index = index;
            this.absIndex = absIndex;
        }

        /**
         * A builtin plugin inside the stack configuration.
         *
         * @param name Plugin name. The name of the plugin,
         * will be resolved thanks to ServiceLoader.
         * @param index index in the plugin stack. Must be > 0.
         * @param absIndex true, the index is absolute otherwise index is within
         * the plugin category.
         * @param configuration
         */
        public OrderedPlugin(String name, int index,
                boolean absIndex, Map<PluginOption, String> configuration) {
            this(createPlugin(name, configuration, Layer.boot()), index, absIndex);
        }

        /**
         * A plugin inside the stack configuration.
         *
         * @param pluginsLayer Layer to resolve plugins.
         * @param name Plugin name. The name of the plugin,
         * will be resolved thanks to ServiceLoader.
         * @param index index in the plugin stack. Must be > 0.
         * @param absIndex true, the index is absolute otherwise index is within
         * the plugin category.
         * @param configuration
         */
        public OrderedPlugin(Layer pluginsLayer, String name, int index,
                boolean absIndex, Map<PluginOption, String> configuration) {
            this(createPlugin(name, configuration, pluginsLayer), index, absIndex);
        }

        private static Plugin createPlugin(String name,
                Map<PluginOption, String> configuration, Layer pluginsLayer) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(configuration);
            Objects.requireNonNull(pluginsLayer);
            return PluginRepository.newPlugin(configuration, name, pluginsLayer);
        }

        /**
         * Get the plugin index.
         *
         * @return the index
         */
        public int getIndex() {
            return index;
        }

        @Override
        public String toString() {
            return plugin.getName() + "[" + index + "]";
        }

        public Plugin getPlugin() {
            return plugin;
        }

        /**
         * The plugin index.
         *
         * @return the absolute index.
         */
        public boolean isAbsoluteIndex() {
            return absIndex;
        }
    }

    /**
     * A complete plugin configuration. Instances of this class are used to
     * configure jlink.
     */
    public static final class PluginsConfiguration {

        private final List<OrderedPlugin> plugins;
        private final ImageBuilder imageBuilder;
        private final String lastSorterPluginName;

        /**
         * Empty plugins configuration.
         */
        public PluginsConfiguration() {
            this(Collections.emptyList());
        }

        /**
         * Plugins configuration.
         *
         * @param plugins List of  plugins.
         */
        public PluginsConfiguration(List<OrderedPlugin> plugins) {
            this(plugins, null, null);
        }

        /**
         * Plugins configuration with a last sorter. No sorting can occur after
         * the last sorter plugin.
         *
         * @param plugins List of transformer plugins.
         * @param imageBuilder Image builder (null default builder).
         * @param lastSorterPluginName Name of last sorter plugin, no sorting
         * can occur after it.
         */
        public PluginsConfiguration(List<OrderedPlugin> plugins,
                ImageBuilder imageBuilder, String lastSorterPluginName) {
            this.plugins = plugins == null ? Collections.emptyList()
                    : plugins;
            this.imageBuilder = imageBuilder;
            this.lastSorterPluginName = lastSorterPluginName;
        }

        /**
         * @return the plugins
         */
        public List<OrderedPlugin> getPlugins() {
            return plugins;
        }

        /**
         * @return the imageBuilder
         */
        public ImageBuilder getImageBuilder() {
            return imageBuilder;
        }

        /**
         * @return the lastSorterPluginName
         */
        public String getLastSorterPluginName() {
            return lastSorterPluginName;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("imagebuilder=").append(imageBuilder).append("\n");
            StringBuilder pluginsBuilder = new StringBuilder();
            for (OrderedPlugin p : plugins) {
                pluginsBuilder.append(p).append(",");
            }
            builder.append("plugins=").append(pluginsBuilder).append("\n");
            builder.append("lastsorter=").append(lastSorterPluginName).append("\n");

            return builder.toString();
        }
    }

    /**
     * Jlink configuration. Instances of this class are used to configure jlink.
     */
    public static final class JlinkConfiguration {

        private final List<Path> modulepaths;
        private final Path output;
        private final Set<String> modules;
        private final Set<String> limitmods;

        private final ByteOrder endian;

        /**
         * jlink configuration,
         *
         * @param output Output directory, must not exist.
         * @param modulepaths Modules paths
         * @param modules Root modules to resolve
         * @param limitmods Limit the universe of observable modules
         * @param endian Jimage byte order. Native order by default
         */
        public JlinkConfiguration(Path output,
                List<Path> modulepaths,
                Set<String> modules,
                Set<String> limitmods,
                ByteOrder endian) {
            this.output = output;
            this.modulepaths = modulepaths == null ? Collections.emptyList() : modulepaths;
            this.modules = modules == null ? Collections.emptySet() : modules;
            this.limitmods = limitmods == null ? Collections.emptySet() : limitmods;
            this.endian = endian == null ? ByteOrder.nativeOrder() : endian;
        }

        /**
         * jlink configuration,
         *
         * @param output Output directory, must not exist.
         * @param modulepaths Modules paths
         * @param modules Root modules to resolve
         * @param limitmods Limit the universe of observable modules
         */
        public JlinkConfiguration(Path output,
                List<Path> modulepaths,
                Set<String> modules,
                Set<String> limitmods) {
            this(output, modulepaths, modules, limitmods,
                    ByteOrder.nativeOrder());
        }

        /**
         * @return the modulepaths
         */
        public List<Path> getModulepaths() {
            return modulepaths;
        }

        /**
         * @return the byte ordering
         */
        public ByteOrder getByteOrder() {
            return endian;
        }

        /**
         * @return the output
         */
        public Path getOutput() {
            return output;
        }

        /**
         * @return the modules
         */
        public Set<String> getModules() {
            return modules;
        }

        /**
         * @return the limitmods
         */
        public Set<String> getLimitmods() {
            return limitmods;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();

            builder.append("output=").append(output).append("\n");
            StringBuilder pathsBuilder = new StringBuilder();
            for (Path p : modulepaths) {
                pathsBuilder.append(p).append(",");
            }
            builder.append("modulepaths=").append(pathsBuilder).append("\n");

            StringBuilder modsBuilder = new StringBuilder();
            for (String p : modules) {
                modsBuilder.append(p).append(",");
            }
            builder.append("modules=").append(modsBuilder).append("\n");

            StringBuilder limitsBuilder = new StringBuilder();
            for (String p : limitmods) {
                limitsBuilder.append(p).append(",");
            }
            builder.append("limitmodules=").append(limitsBuilder).append("\n");
            builder.append("endian=").append(endian).append("\n");
            return builder.toString();
        }
    }

    /**
     * Jlink instance constructor, if a security manager is set, the jlink
     * permission is checked.
     */
    public Jlink() {
        if (System.getSecurityManager() != null) {
            System.getSecurityManager().
                    checkPermission(new JlinkPermission("jlink"));
        }
    }

    /**
     * Build the image.
     *
     * @param config Jlink config, must not be null.
     * @throws PluginException
     */
    public void build(JlinkConfiguration config) {
        build(config, null);
    }

    /**
     * Build the image with a plugin configuration.
     *
     * @param config Jlink config, must not be null.
     * @param pluginsConfig Plugins config, can be null
     * @throws PluginException
     */
    public void build(JlinkConfiguration config, PluginsConfiguration pluginsConfig) {
        Objects.requireNonNull(config);
        try {
            JlinkTask.createImage(config, pluginsConfig);
        } catch (Exception ex) {
            throw new PluginException(ex);
        }
    }

    /**
     * Post process the image with a plugin configuration.
     *
     * @param image Existing image.
     * @param plugins Plugins config, cannot be null
     */
    public void postProcess(ExecutableImage image, List<OrderedPlugin> plugins) {
        Objects.requireNonNull(image);
        Objects.requireNonNull(plugins);
        try {
            JlinkTask.postProcessImage(image, plugins);
        } catch (Exception ex) {
            throw new PluginException(ex);
        }
    }
}
