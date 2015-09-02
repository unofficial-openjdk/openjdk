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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jdk.tools.jlink.plugins.OnOffPluginProviderSupport.PluginBuilder;

/**
 *
 * Abstract class for command line resource provider that requires ON/OFF
 * support. Plugin created by this provider can be enabled by default (enabled
 * although no option is provided to the command line).
 */
public abstract class OnOffResourcePluginProvider extends CmdResourcePluginProvider {

    public OnOffResourcePluginProvider(String name, String description) {
        super(name, description);
    }

    @Override
    public ResourcePlugin[] newPlugins(String[] arguments,
            Map<String, String> otherOptions)
            throws IOException {
        PluginBuilder<ResourcePlugin> builder = (Map<String, String> otherOptions1) -> {
            ResourcePlugin[] ret = createPlugins(otherOptions1);
            List<ResourcePlugin> lst = new ArrayList<>();
            if (ret != null) {
                for (ResourcePlugin p : ret) {
                    lst.add(p);
                }
            }
            return lst;
        };
        List<ResourcePlugin> ret = OnOffPluginProviderSupport.newPlugins(arguments,
                otherOptions, builder);
        ResourcePlugin[] arr = new ResourcePlugin[ret.size()];
        return ret.toArray(arr);
    }

    public abstract ResourcePlugin[] createPlugins(Map<String, String> otherOptions)
            throws IOException;

    @Override
    public String getToolArgument() {
        return OnOffPluginProviderSupport.getToolArgument();
    }

    /**
     * Plugin wishing to be enabled by default (no need for command line option)
     * can override this method and return true.
     *
     * @return true, the plugin is enabled by default, otherwise is is not.
     */
    public boolean isEnabledByDefault() {
        return false;
    }

}
