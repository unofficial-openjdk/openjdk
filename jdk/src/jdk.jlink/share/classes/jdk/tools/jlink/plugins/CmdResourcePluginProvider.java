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

/**
 * An Resource Plugin provider that creates command line oriented plugins.
 */
public abstract class CmdResourcePluginProvider extends ResourcePluginProvider
        implements CmdPluginProvider<ResourcePlugin> {

    protected CmdResourcePluginProvider(String name, String description) {
        super(name, description);
    }

    @Override
    public abstract ResourcePlugin[] newPlugins(String[] arguments,
            Map<String, String> otherOptions) throws IOException;

    // Must be implemented, an abstract method can't be implemented with a default method
    @Override
    public ResourcePlugin[] newPlugins(Map<String, Object> conf) throws IOException {
        return CmdPluginProvider.super.newPlugins(conf);
    }
}
