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

import java.util.Set;

/**
 * A library of modules, for example installed modules or a module path.
 * A {@code ModuleLibrary} can optionally have a parent module library
 * for delegation.
 */

public abstract class ModuleLibrary {
    private final ModuleLibrary parent;

    protected ModuleLibrary() {
        this.parent = null;
    }

    protected ModuleLibrary(ModuleLibrary parent) {
        this.parent = parent;
    }

    /**
     * Returns the module library's parent or {@code null} if if does not have
     * a parent.
     */
    public final ModuleLibrary parent() {
        return parent;
    }

    /**
     * Locates a module of the given name in this library. Returns {@code null}
     * if not found.
     */
    public abstract Module findLocalModule(String name);

    /**
     * Locates a module of the given name in parent library and if not found,
     * attempts to locate it in this module library.
     */
    public final Module findModule(String name) {
        Module m = null;
        if (parent != null)
            m = parent.findModule(name);
        if (m == null)
            m = findLocalModule(name);
        return m;
    }

    /**
     * Returns the set of modules in this library. The set does not include
     * the modules in the parent module library.
     */
    public abstract Set<Module> localModules();
}
