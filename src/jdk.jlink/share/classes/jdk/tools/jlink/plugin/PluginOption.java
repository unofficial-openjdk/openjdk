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

/**
 * A plugin option. An option is passed to the command line when
 * calling jlink tool.
 */
public final class PluginOption {

    private final String name;
    private final String description;
    private final String argumentDescription;
    private final boolean showHelp;

    PluginOption(String name,
                 String description,
                 String argumentDescription,
                 boolean showHelp) {
        Objects.requireNonNull(name);
        this.name = name;
        this.description = description;
        this.argumentDescription = argumentDescription;
        this.showHelp = showHelp;
    }

    /**
     * Option name (e.g.: compress, strip-debug)
     * @return  The option name.
     */
    public String getName() {
        return name;
    }

    /**
     * Option description. Information on the option usage.
     * @return The description.
     */
    public String getDescription() {
        return description;
    }

    /**
     * If this option takes an argument, then this description must
     * describes the content of the argument.
     * @return null (or empty String) if no argument. A non empty String otherwise.
     */
    public String getArgumentDescription() {
        return argumentDescription;
    }

    /**
     * The option wants to be listed in the jlink help. By default all options
     * are listed in the extended help.
     * @return True to have this option listed in the jlink help. False to only
     * have this option listed in the extended help.
     */
    public boolean showHelp() {
        return showHelp;
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

    /**
     * A PluginOption builder. Used to build PluginOption instances.
     */
    public static final class Builder {

        private final String name;
        private String description = "";
        private String argumentDescription = "";
        private boolean showHelp;

        /**
         * A builder for the given option name.
         * @param name Option name, can't be null.
         */
        public Builder(String name) {
            Objects.requireNonNull(name);
            this.name = name;
        }
        /**
         * A builder for the given option name and description.
         * @param name Option name, can't be null.
         * @param description Option description.
         */
        public Builder(String name, String description) {
            Objects.requireNonNull(name);
            this.name = name;
            this.description = description;
        }

        /**
         * Set the description and returns this builder.
         * @param description Option description.
         * @return This Builder.
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Set the argument description and returns this builder.
         * @param argumentDescription Argument description.
         * @return This Builder.
         */
        public Builder argumentDescription(String argumentDescription) {
            this.argumentDescription = argumentDescription;
            return this;
        }

        /**
         * Set the help flag and returns this builder.
         * @param value True, appears in help, otherwise appears only in extended help.
         * @return This Builder.
         */
        public Builder showHelp(boolean value) {
            this.showHelp = value;
            return this;
        }

        /**
         * Build a PluginOption.
         * @return A new PluginOption.
         */
        public PluginOption build() {
            return new PluginOption(name, description, argumentDescription, showHelp);
        }
    }

}
