/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
package jdk.tools.jlink.internal.plugins;

import jdk.tools.jlink.plugins.OnOffResourcePluginProvider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jdk.tools.jlink.plugins.ResourcePlugin;
import jdk.tools.jlink.plugins.Plugin;
import jdk.tools.jlink.plugins.PluginProvider;

/**
 *
 * Default compression provider.
 */
public class DefaultCompressProvider extends OnOffResourcePluginProvider {

    public static final String NAME = "compress-resources";
    public static final String LEVEL_OPTION = "compress-resources-level";
    public static final String FILTER_OPTION = "compress-resources-filter";
    public static final String LEVEL_0 = "0";
    public static final String LEVEL_1 = "1";
    public static final String LEVEL_2 = "2";

    public DefaultCompressProvider() {
        super(NAME, PluginsResourceBundle.getDescription(NAME));
    }

    @Override
    public ResourcePlugin[] createPlugins(Map<String, String> otherOptions)
            throws IOException {
        String filter = otherOptions.get(FILTER_OPTION);
        String[] patterns = filter == null ? null : filter.split(",");
        String level = otherOptions.get(LEVEL_OPTION);
        List<Plugin> plugins = new ArrayList<>();
        ResourceFilter resFilter = new ResourceFilter(patterns);
        if (level != null) {
            if (LEVEL_0.equals(level)) {
                plugins.add(new StringSharingPlugin(resFilter));
            } else if (LEVEL_1.equals(level)) {
                plugins.add(new ZipPlugin(resFilter));
            } else if (LEVEL_2.equals(level)) {
                plugins.add(new StringSharingPlugin(resFilter));
                plugins.add(new ZipPlugin(resFilter));
            } else {
                throw new IOException("Invalid level " + level);
            }
        } else {
            plugins.add(new StringSharingPlugin(resFilter));
            plugins.add(new ZipPlugin(resFilter));
        }
        ResourcePlugin[] array = new ResourcePlugin[plugins.size()];
        return plugins.toArray(array);
    }

    @Override
    public String getCategory() {
        return PluginProvider.COMPRESSOR;
    }

    @Override
    public String getToolOption() {
        return NAME;
    }

    @Override
    public Map<String, String> getAdditionalOptions() {
        Map<String, String> m = new HashMap<>();
        m.put(LEVEL_OPTION, PluginsResourceBundle.getOption(NAME, LEVEL_OPTION));
        m.put(FILTER_OPTION, PluginsResourceBundle.getOption(NAME, FILTER_OPTION));
        return m;
    }

}
