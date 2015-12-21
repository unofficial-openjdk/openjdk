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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import jdk.tools.jlink.internal.plugins.PluginsResourceBundle;

/**
 * An abstract plugin provider class. A provider has a name and a description.
 */
public abstract class PluginProvider {

    private final String name;
    private final String description;

    public PluginProvider(String name, String description) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(description);
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    /**
     * An exposed provider wants to be advertised (e.g.: displayed in help).
     *
     * @return True, the provider is exposed, false the provider is hidden.
     */
    public boolean isExposed() {
        return true;
    }

    /**
     * Check if the provider can properly operate in the current context.
     *
     * @return true, the provider can operate
     */
    public boolean isFunctional() {
        return true;
    }

    /**
     * Return a message indicating the status of the provider.
     *
     * @param functional
     * @return A status description.
     */
    public String getFunctionalStateDescription(boolean functional) {
        return functional
                ? PluginsResourceBundle.getMessage("main.status.ok")
                : PluginsResourceBundle.getMessage("main.status.not.ok");
    }

    /**
     * Create plugins based on passed configuration.
     *
     * @param config The plugins configuration.
     * @return An array of plugins.
     * @throws PluginException
     */
    public abstract List<? extends Plugin> newPlugins(Map<String, Object> config);
}
