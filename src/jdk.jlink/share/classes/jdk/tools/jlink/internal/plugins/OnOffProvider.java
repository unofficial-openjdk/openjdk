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
import java.util.Objects;
import jdk.tools.jlink.internal.ImagePluginConfiguration;
import jdk.tools.jlink.plugins.ResourcePlugin;
import jdk.tools.jlink.plugins.ResourcePluginProvider;

/**
 *
 * Abstract class for provider that requires ON/OFF support
 */
public abstract class OnOffProvider extends ResourcePluginProvider {

    public OnOffProvider(String name, String description) {
        super(name, description);
    }

    @Override
    public ResourcePlugin[] newPlugins(String[] arguments,
            Map<String, String> otherOptions)
            throws IOException {
        Objects.requireNonNull(arguments);
        if(arguments.length != 1) {
            throw new IOException("Invalid number of arguments expecting " +
                    getToolArgument());
        }
        if(!ImagePluginConfiguration.OFF_ARGUMENT.equals(arguments[0]) &&
           !ImagePluginConfiguration.ON_ARGUMENT.equals(arguments[0])     ) {
            throw new IOException("Invalid argument " + arguments[0] +
                    ", expecting " + ImagePluginConfiguration.ON_ARGUMENT + " or " +
                    ImagePluginConfiguration.OFF_ARGUMENT);
        }
        if(ImagePluginConfiguration.OFF_ARGUMENT.equals(arguments[0])) {
            return new ResourcePlugin[0];
        }
        return newPlugins(otherOptions);
    }

    public abstract ResourcePlugin[] newPlugins(Map<String, String> otherOptions)
            throws IOException;

    @Override
    public String getToolArgument() {
        return ImagePluginConfiguration.ON_ARGUMENT + "|"
                + ImagePluginConfiguration.OFF_ARGUMENT;
    }

}
