/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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

package java.lang;

import java.lang.module.ModuleReference;

/**
 * A class loader implementation that implements this interface is capable
 * of loading classes and resources from one or more named modules.
 *
 * @since 1.9
 */

public interface ModuleCapableLoader {

    /**
     * Register a module with this class loader so that its classes (and
     * resources) become visible via this class loader.
     *
     * <p> This method is not intended to be invoked directly by applications.
     * It is instead invoked by {@link java.lang.module.Layer#create
     * Layer.create} method when creating a layer of modules. </p>
     *
     * @throws IllegalStateException
     *         If a module of the same name is already registered with this
     *         class loader
     * @throws SecurityException
     *         If denied by security manager.
     */
    void register(ModuleReference mref);
}
