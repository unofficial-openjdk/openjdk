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

import java.lang.module.Configuration;
import java.lang.module.Layer;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleDescriptor;
import sun.misc.ClassLoaders;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The module to class loader map.  The list of boot modules and ext modules
 * are generated at build time.
 */
final class ModuleLoaderMap {
    /*
     * The list of boot modules and ext modules are generated at build time.
     */
    private static final Set<String> BOOT_MODULES =
        Arrays.stream(new String[] {
            "@@BOOT_MODULE_NAMES@@"
        }).collect(Collectors.toSet());
    private static final Set<String> EXT_MODULES =
        Arrays.stream(new String[] {
            "@@EXT_MODULE_NAMES@@"
        }).collect(Collectors.toSet());

    /**
     * Returns the ClassLoaderFinder that maps modules in the given
     * Configuration to a ClassLoader.
     */
    static Layer.ClassLoaderFinder classLoaderFinder(Configuration cf) {
        ClassLoader extClassLoader = ClassLoaders.extClassLoader();
        ClassLoader appClassLoader = ClassLoaders.appClassLoader();

        Map<String, ClassLoader> map = new HashMap<>();
        cf.descriptors()
            .stream()
            .map(ModuleDescriptor::name)
            .filter(name -> !BOOT_MODULES.contains(name))
            .forEach(name -> {
                ClassLoader cl = EXT_MODULES.contains(name) ? extClassLoader : appClassLoader;
                map.put(name, cl);
            });
        return map::get;
    }
}
