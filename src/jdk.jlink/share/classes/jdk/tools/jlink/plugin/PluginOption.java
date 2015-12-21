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
package jdk.tools.jlink.plugin;

import java.util.Objects;
import jdk.tools.jlink.internal.plugins.PluginsResourceBundle;

public final class PluginOption {

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
