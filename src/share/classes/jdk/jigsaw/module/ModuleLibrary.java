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

package jdk.jigsaw.module;

import java.util.Collections;
import java.util.Set;
import java.util.HashSet;

/**
 * A library of modules, for example installed modules or the launcher module
 * path. A {@code ModuleLibrary} can optionally to be arranged in a path of
 * module libraries.
 */

public abstract class ModuleLibrary {
    private final ModuleLibrary next;

    protected ModuleLibrary(ModuleLibrary next) {
        this.next = next;
    }

    protected ModuleLibrary() {
        this(null);
    }

    /**
     * Returns the next module library in the path, may be {@code null}.
     */
    public final ModuleLibrary next() {
        return next;
    }

    /**
     * Locates a module of the given name in this module library. Returns
     * {@code null} if not found.
     */
    public abstract Module findLocalModule(String name);

    /**
     * Locates a module of the given name. If the module is not found in this
     * module library then the next module library in the path is searched.
     */
    public final Module findModule(String name) {
        Module m = findLocalModule(name);
        if (m == null && next != null)
            m = next.findModule(name);
        return m;
    }

    /**
     * Returns the set of modules that are local to this module library.
     */
    public abstract Set<Module> localModules();

    /**
     * Returns the complete set of modules in this library and all module
     * libraries that in the path of libraries.
     */
    public final Set<Module> allModules() {
        if (next == null)
            return localModules();
        Set<Module> result = new HashSet<>();
        result.addAll(next.allModules());
        result.addAll(localModules());
        return result;
    }

    /**
     * Returns an empty module library.
     */
    public static ModuleLibrary emptyModuleLibrary() {
        return new ModuleLibrary(null) {
            @Override
            public Module findLocalModule(String name) { return null; }
            @Override
            public Set<Module> localModules() { return Collections.emptySet(); }
        };
    }
}
