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
import java.util.Map.Entry;

/**
 * An abstract command line plugin provider class. Such a provider has a command
 * line option, an optional argument, and optional additional options. The
 * provider classes to extend to add plugins to jlink command line tool are:
 * <ul>
 * <li><code>CmdResourcePluginProvider</code></li>
 * <li><code>CmdImageFilePluginProvider</code></li>
 * </ul>
 */
public abstract class CmdPluginProvider extends PluginProvider {

    /**
     * This property is the main argument (if any) passed to the plugin.
     */
    public static final String TOOL_ARGUMENT_PROPERTY = "argument";

    CmdPluginProvider(String name, String description) {
        super(name, description);
    }

    /**
     * Returns the description
     *
     * @return null, meaning no argument, otherwise a description of the
     * structure of the argument.
     */
    public abstract String getToolArgument();

    /**
     * The command line option that identifies this provider.
     *
     * @return The command line option of this provider.
     */
    public abstract String getToolOption();

    /**
     * Additional command line options and their associated description.
     *
     * @return A map of the option to description mapping.
     */
    public abstract Map<String, String> getAdditionalOptions();

    @Override
    public final Plugin[] newPlugins(Map<String, Object> conf) throws IOException {
        Map<String, String> config = toString(conf);
        String[] arguments = null;
        Collection<String> options = Collections.emptyList();
        if (getAdditionalOptions() != null) {
            options = getAdditionalOptions().keySet();
        }
        Map<String, String> otherOptions = new HashMap<>();
        for (Entry<String, String> a : config.entrySet()) {
            if (options.contains(a.getKey())) {
                otherOptions.put(a.getKey(), a.getValue());
                continue;
            }
            switch (a.getKey()) {
                case TOOL_ARGUMENT_PROPERTY: {
                    arguments = a.getValue().
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

    /**
     * Concrete sub-classes of this abstract class must implement this method.
     *
     * @param arguments The main option value.
     * @param otherOptions The additional option values.
     * @return An array of plugins.
     * @throws IOException
     */
    public abstract Plugin[] newPlugins(String[] arguments,
            Map<String, String> otherOptions) throws IOException;

    static Map<String, String> toString(Map<String, Object> input) {
        Map<String, String> map = new HashMap<>();
        for (Entry<String, Object> entry : input.entrySet()) {
            if (!(entry.getKey() instanceof String)
                    || !(entry.getValue() instanceof String)) {
                throw new RuntimeException("Config should be string for "
                        + entry.getKey());
            }
            String k = entry.getKey();
            @SuppressWarnings("unchecked")
            String v = (String) entry.getValue();
            map.put(k, v);
        }
        return map;
    }
}
