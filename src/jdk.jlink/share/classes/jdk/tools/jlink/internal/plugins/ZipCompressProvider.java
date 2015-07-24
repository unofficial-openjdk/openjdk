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
package jdk.tools.jlink.internal.plugins;

import java.io.IOException;
import java.util.Map;
import jdk.tools.jlink.plugins.ResourcePlugin;
import jdk.tools.jlink.plugins.ResourcePluginProvider;
import jdk.tools.jlink.internal.ImagePluginConfiguration;

/**
 *
 * ZIP compression plugin provider
 */
public class ZipCompressProvider extends ResourcePluginProvider {
    public static final String NAME = "zip";
    public ZipCompressProvider() {
        super(NAME, PluginsResourceBundle.getDescription(NAME));
    }

    @Override
    public ResourcePlugin[] newPlugins(String[] arguments, Map<String, String> otherOptions)
            throws IOException {
        return new ResourcePlugin[]{new ZipPlugin(arguments)};
    }

    @Override
    public String getCategory() {
        return ImagePluginConfiguration.COMPRESSOR;
    }

    @Override
    public String getToolArgument() {
        return PluginsResourceBundle.getArgument(NAME);
    }

    @Override
    public String getToolOption() {
        return null;
    }

    @Override
    public Map<String, String> getAdditionalOptions() {
        return null;
    }

}
