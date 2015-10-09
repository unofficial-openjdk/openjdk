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
import java.util.Map;
import java.util.Objects;

/**
 * An abstract plugin provider class. A provider has a name, a description and
 * an optional category.<br>
 * The provider classes to extend to add plugins to jlink are:
 * <ul>
 * <li><code>ResourcePluginProvider</code></li>
 * <li><code>ImageFilePluginProvider</code></li>
 * </ul>
 *
 * Order of known categories are:
 * <ol>
 * <li>FILTER: Filter in/out resources or files.</li>
 * <li>TRANSFORMER: Transform resources or files(eg: refactoring, bytecode
 * manipulation).</li>
 * <li>SORTER: Sort resources within the resource container.</li>
 * <li>COMPRESSOR: Compress resource within the resouce containers.</li>
 * <li>VERIFIER: Does some image verification.</li>
 * <li>PROCESSOR: Does some post processing on image.</li>
 * <li>PACKAGER: Final processing</li>
 * </ol>
 */
public abstract class PluginProvider {

    /**
     * This option is present in the configuration passed at Plugin creation
     * time.
     */
    public static final String PLATFORM_NAME_OPTION = "jlink.platform";

    public static final String COMPRESSOR = "compressor";
    public static final String SORTER = "sorter";
    public static final String TRANSFORMER = "transformer";
    public static final String FILTER = "filter";
    public static final String PACKAGER = "packager";
    public static final String PROCESSOR = "processor";
    public static final String VERIFIER = "verifier";

    public static enum ORDER {
        FIRST,
        ANY,
        LAST
    }

    private final String name;
    private final String description;

    PluginProvider(String name, String description) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(description);
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public abstract String getCategory();

    /**
     * Order of the plugin within its category. By default ANY.
     *
     * @return Expected order.
     */
    public ORDER getOrder() {
        return ORDER.ANY;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Create plugins based on passed configuration.
     *
     * @param config The plugins configuration.
     * @return An array of plugins.
     * @throws IOException
     */
    public abstract Plugin[] newPlugins(Map<String, Object> config)
            throws IOException;
}
