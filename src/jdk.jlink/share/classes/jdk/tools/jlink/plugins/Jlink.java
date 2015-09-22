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
package jdk.tools.jlink.plugins;

import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import jdk.tools.jlink.JlinkTask;

/**
 * Jlink, entry point to interact with jlink support.
 *
 */
public final class Jlink {

    /**
     * A plugin configuration.
     */
    public static class PluginConfiguration {

        private final String name;
        private final Map<String, Object> config;

        /**
         * A configuration.
         *
         * @param name Plugin name
         * @param config Plugin configuration. Can be null;
         */
        public PluginConfiguration(String name, Map<String, Object> config) {
            Objects.requireNonNull(name);
            this.name = name;
            this.config = config == null ? Collections.emptyMap() : config;
        }

        /**
         * @return the name
         */
        public String getName() {
            return name;
        }

        /**
         * @return the config
         */
        public Map<String, Object> getConfig() {
            return config;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof PluginConfiguration)) {
                return false;
            }
            return name.equals(((PluginConfiguration) other).name);
        }

        @Override
        public int hashCode() {
            int hash = 5;
            hash = 41 * hash + Objects.hashCode(this.name);
            return hash;
        }
    }

    /**
     * A plugin located inside the stack of plugins. Such plugin as an index in
     * the stack.
     */
    public static final class StackedPluginConfiguration extends PluginConfiguration {

        private final int index;
        private final boolean absIndex;

        /**
         * A plugin inside the stack configuration.
         *
         * @param name Plugin name
         * @param index index in the plugin stack. Must be > 0.
         * @param absIndex true, the index is absolute otherwise index is within
         * the category.
         * @param config Plugin configuration. Can be null;
         */
        public StackedPluginConfiguration(String name, int index, boolean absIndex,
                Map<String, Object> config) {
            super(name, config);
            if (index < 0) {
                throw new IllegalArgumentException("negative index");
            }
            this.index = index;
            this.absIndex = absIndex;
        }

        /**
         * @return the index
         */
        public int getIndex() {
            return index;
        }

        @Override
        public String toString() {
            return getName() + "[" + index + "]";
        }

        /**
         * @return the absIndex
         */
        public boolean isAbsoluteIndex() {
            return absIndex;
        }

        @Override
        public boolean equals(Object other) {
            return super.equals(other);
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    /**
     * A complete plugin configuration. Instances of this class are used to
     * configure jlink.
     */
    public static final class PluginsConfiguration {

        private final List<StackedPluginConfiguration> pluginsConfig;
        private final PluginConfiguration imageBuilder;
        private final String lastSorterPluginName;

        /**
         * Empty plugins configuration.
         */
        public PluginsConfiguration() {
            this(Collections.emptyList(), null);
        }

        /**
         * Plugins configuration.
         *
         * @param pluginsConfig List of plugins configuration.
         * @param imageBuilder Image builder (null default builder).
         */
        public PluginsConfiguration(List<StackedPluginConfiguration> pluginsConfig,
                PluginConfiguration imageBuilder) {
            this(pluginsConfig, imageBuilder, null);
        }

        /**
         * Plugins configuration with a last sorter. No sorting can occur after
         * the last sorter plugin.
         *
         * @param pluginsConfig List of plugins configuration.
         * @param imageBuilder Image builder (null default builder).
         * @param lastSorterPluginName Name of last sorter plugin, no sorting
         * can occur after it.
         */
        public PluginsConfiguration(List<StackedPluginConfiguration> pluginsConfig,
                PluginConfiguration imageBuilder, String lastSorterPluginName) {
            this.pluginsConfig = pluginsConfig == null ? Collections.emptyList()
                    : pluginsConfig;
            this.imageBuilder = imageBuilder;
            this.lastSorterPluginName = lastSorterPluginName;
        }

        /**
         * @return the pluginsConfig
         */
        public List<StackedPluginConfiguration> getPluginsConfig() {
            return pluginsConfig;
        }

        /**
         * @return the imageBuilder
         */
        public PluginConfiguration getImageBuilder() {
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
            for (PluginConfiguration p : pluginsConfig) {
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
        private final List<Path> pluginpaths;
        private final ByteOrder endian;

        /**
         * jlink configuration,
         *
         * @param output Output directory, must not exist.
         * @param modulepaths Modules paths
         * @param modules Root modules to resolve
         * @param limitmods Limit the universe of observable modules
         * @param pluginpaths Custom plugins module path
         * @param endian Jimage byte order. Native order by default
         */
        public JlinkConfiguration(Path output,
                List<Path> modulepaths,
                Set<String> modules,
                Set<String> limitmods,
                List<Path> pluginpaths,
                ByteOrder endian) {
            this.output = output;
            this.modulepaths = modulepaths == null ? Collections.emptyList() : modulepaths;
            this.modules = modules == null ? Collections.emptySet() : modules;
            this.limitmods = limitmods == null ? Collections.emptySet() : limitmods;
            this.pluginpaths = pluginpaths == null ? Collections.emptyList() : pluginpaths;
            this.endian = endian == null ? ByteOrder.nativeOrder() : endian;
        }

        /**
         * jlink configuration,
         *
         * @param output Output directory, must not exist.
         * @param modulepaths Modules paths
         * @param modules Root modules to resolve
         * @param limitmods Limit the universe of observable modules
         * @param pluginpaths Custom plugins module path
         */
        public JlinkConfiguration(Path output,
                List<Path> modulepaths,
                Set<String> modules,
                Set<String> limitmods,
                List<Path> pluginpaths) {
            this(output, modulepaths, modules, limitmods, pluginpaths,
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

        /**
         * @return the pluginpaths
         */
        public List<Path> getPluginpaths() {
            return pluginpaths;
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

            StringBuilder pluginsBuilder = new StringBuilder();
            for (Path p : pluginpaths) {
                pluginsBuilder.append(p).append(",");
            }
            builder.append("pluginspaths=").append(pluginsBuilder).append("\n");
            builder.append("endian=").append(endian).append("\n");
            return builder.toString();
        }
    }

    /**
     * Build the image.
     *
     * @param config Jlink config, must not be null.
     * @throws Exception
     */
    public void build(JlinkConfiguration config) throws Exception {
        build(config, null);
    }

    /**
     * Build the image.
     *
     * @param config Jlink config, must not be null.
     * @param pluginsConfig Plugins config, can be null
     * @throws Exception
     */
    public void build(JlinkConfiguration config, PluginsConfiguration pluginsConfig) throws Exception {
        Objects.requireNonNull(config);
        JlinkTask.createImage(config, pluginsConfig);
    }
}
