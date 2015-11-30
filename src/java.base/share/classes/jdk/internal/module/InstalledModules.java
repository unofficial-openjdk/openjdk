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

package jdk.internal.module;

import java.lang.module.ModuleDescriptor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/*
 * InstalledModules class will be generated at link time to create
 * ModuleDescriptor for the installed modules directly to improve
 * the module descriptor reconstitution time.
 *
 * This will skip parsing of module-info.class file and validating
 * names such as module name, package name, service and provider type names.
 * It also avoids taking a defensive copy of any collection.
 *
 * @see jdk.tools.jlink.internal.plugins.InstalledModuleDescriptorPlugin
 */
public final class InstalledModules {
    /**
     * Name of the installed modules
     */
    public final static String[] MODULE_NAMES = new String[1];

    /**
     * Number of packages in the boot layer from the installed modules.
     *
     * Don't make it final to avoid inlining during compile time as
     * the value will be changed at jlink time.
     */
    public static int PACKAGES_IN_BOOT_LAYER = 1024;

    /**
     * Map of module name to a fast loaded ModuleDescriptor
     * of all installed modules.
     *
     * Empty if this fastpath is not used.
     */
    private final static Map<String, ModuleDescriptor> MAP = new HashMap<>();

    /**
     * Initialize the map of module name to ModuleDescriptor of the installed
     * modules.
     *
     * * It must be a non-empty map if running on an image.  For exploded image,
     * this map is empty.
     */
    public static Map<String, ModuleDescriptor> modules() {
        return MAP;
    }
}