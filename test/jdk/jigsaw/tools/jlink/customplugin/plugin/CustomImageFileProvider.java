/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

package plugin;

import java.util.Map;
import jdk.tools.jlink.api.plugin.PluginOption;
import jdk.tools.jlink.api.plugin.PluginOptionBuilder;

import jdk.tools.jlink.api.plugin.transformer.TransformerPlugin;
import jdk.tools.jlink.api.plugin.transformer.TransformerPluginProvider;

public class CustomImageFileProvider extends TransformerPluginProvider {

    private final static String NAME = "custom-file-plugin";

    private final static PluginOption NAME_OPTION = new PluginOptionBuilder(NAME + "-option").
            description(NAME + "-description").
            argumentDescription(NAME + "-argument").build();

    public CustomImageFileProvider() {
        super(NAME, NAME + "-description");
    }

    @Override
    public String getCategory() {
        return TRANSFORMER;
    }

    @Override
    public PluginOption getOption() {
        return NAME_OPTION;
    }

    @Override
    public Type getType() {
        return Type.IMAGE_FILE_PLUGIN;
    }

    @Override
    public TransformerPlugin newPlugin(Map<PluginOption, Object> config) {
        return null;
    }
}
