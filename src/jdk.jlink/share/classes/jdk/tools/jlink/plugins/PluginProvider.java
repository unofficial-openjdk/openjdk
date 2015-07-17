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

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Extend this class and make your class available to the ServiceLoader in order
 * to expose your Plugin. A provider has a name, a description, an optional
 * category, configuration and command line option.
 */
public abstract class PluginProvider {

    public static final String TOOL_ARGUMENT_PROPERTY = "argument";

    private final String name;
    private final String description;

    protected PluginProvider(String name, String description) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(description);
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public abstract String getCategory();

    public String getDescription() {
        return description;
    }

    public abstract String getToolArgument();

    public abstract String getToolOption();

    public abstract Map<String, String> getAdditionalOptions();

    public final Plugin[] newPlugins(Properties properties) throws IOException {
        String[] arguments = null;
        Collection<String> options = Collections.emptyList();
        if (getAdditionalOptions() != null) {
            options = getAdditionalOptions().keySet();
        }
        Map<String, String> otherOptions = new HashMap<>();
        for (String a : properties.stringPropertyNames()) {
            if (options.contains(a)) {
                otherOptions.put(a, properties.getProperty(a));
                continue;
            }
            switch (a) {
                case TOOL_ARGUMENT_PROPERTY: {
                    arguments = properties.getProperty(a).
                            split(",");
                    for (int i = 0; i < arguments.length; i++) {
                        arguments[i] = arguments[i].trim();
                    }
                    break;
                }
            }
        }
        return newPlugins(arguments, otherOptions);
    }

    public abstract Plugin[] newPlugins(String[] arguments,
            Map<String, String> otherOptions) throws IOException;
}
