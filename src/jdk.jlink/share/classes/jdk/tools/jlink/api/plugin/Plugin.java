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
package jdk.tools.jlink.api.plugin;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import jdk.tools.jlink.internal.plugins.PluginsResourceBundle;

/**
 * Implement this interface to develop your own plugin.
 */
public interface Plugin {

    public static final class PluginOption {

        private final String name;
        private final String description;
        private final String argumentDescription;
        private final boolean hasOnOffArgument;
        private final boolean isEnabled;

        PluginOption(String name,
                String description,
                String argumentDescription,
                boolean hasOnOffArgument,
                boolean isEnabled) {
            Objects.requireNonNull(name);
            this.name = name;
            this.description = description;
            this.argumentDescription = argumentDescription;
            this.hasOnOffArgument = hasOnOffArgument;
            this.isEnabled = isEnabled;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public String getArgumentDescription() {
            return argumentDescription;
        }

        public boolean hasOnOffArgument() {
            return hasOnOffArgument;
        }

        public boolean isEnabled() {
            return isEnabled;
        }

        @Override
        public String toString() {
            return name + ":" + description;
        }

        @Override
        public int hashCode() {
            int hash = 5;
            hash = 37 * hash + Objects.hashCode(this.name);
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof PluginOption)) {
                return false;
            }
            PluginOption po = (PluginOption) other;
            return name.equals(po.name);
        }

        public static final class Builder {

            public static final String ON_ARGUMENT = "on";
            public static final String OFF_ARGUMENT = "off";

            private final String name;
            private String description;
            private String argumentDescription;
            private boolean hasOnOffArgument;
            private boolean isEnabled;

            public Builder(String name) {
                Objects.requireNonNull(name);
                this.name = name;
            }

            public Builder description(String description) {
                this.description = description;
                return this;
            }

            public Builder argumentDescription(String argumentDescription) {
                this.argumentDescription = argumentDescription;
                return this;
            }

            public Builder hasOnOffArgument() {
                this.hasOnOffArgument = true;
                this.argumentDescription = PluginsResourceBundle.getMessage("onoff.argument");
                return this;
            }

            public Builder isEnabled() {
                hasOnOffArgument();
                isEnabled = true;
                return this;
            }

            public PluginOption build() {
                return new PluginOption(name, description, argumentDescription,
                        hasOnOffArgument, isEnabled);
            }
        }

    }

    public interface PluginType {

        public String getName();
    }

    public enum ORDER implements PluginType {
        FIRST("FIRST"),
        LAST("LAST"),
        ANY("ANY");

        private final String name;

        ORDER(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }
    }

    /**
     * Order of categories:
     * <ol>
     * <li>FILTER: Filter in/out resources or files.</li>
     * <li>TRANSFORMER: Transform resources or files(eg: refactoring, bytecode
     * manipulation).</li>
     * <li>MODULEINFO_TRANSFORMER: Transform only module-info.class</li>
     * <li>SORTER: Sort resources within the resource container.</li>
     * <li>COMPRESSOR: Compress resource within the resouce containers.</li>
     * <li>BUILDER: Layout image on disk.</li>
     * <li>VERIFIER: Does some image verification.</li>
     * <li>PROCESSOR: Does some post processing on image.</li>
     * <li>PACKAGER: Final processing</li>
     * </ol>
     */
    public enum CATEGORY implements PluginType {
        FILTER("FILTER"),
        TRANSFORMER("TRANSFORMER"),
        MODULEINFO_TRANSFORMER("MODULEINFO_TRANSFORMER"),
        SORTER("SORTER"),
        COMPRESSOR("COMPRESSOR"),
        BUILDER("BUILDER"),
        VERIFIER("VERIFIER"),
        PROCESSOR("PROCESSOR"),
        PACKAGER("PACKAGER");

        private final String name;

        CATEGORY(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }
    }

    public enum STATE {
        ENABLED,
        FUNCTIONAL
    }

    public abstract Set<PluginType> getType();

    public default Set<STATE> getState() {
        return EnumSet.of(STATE.ENABLED, STATE.FUNCTIONAL);
    }

    public String getName();

    public String getDescription();

    public default List<PluginOption> getAdditionalOptions() {
        return Collections.emptyList();
    }

    public PluginOption getOption();

    /**
     * Return a message indicating the status of the provider.
     *
     * @return A status description.
     */
    public default String getStateDescription() {
        return getState().contains(STATE.FUNCTIONAL)
                ? PluginsResourceBundle.getMessage("main.status.ok")
                : PluginsResourceBundle.getMessage("main.status.not.ok");
    }

    /**
     * Configure the plugin based on the passed configuration.
     *
     * @param config The plugin configuration.
     */
    public void configure(Map<PluginOption, String> config);
}
