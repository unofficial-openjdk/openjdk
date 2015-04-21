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

/**
 * Implement this interface to develop your own plugin.
 * Plugin can modify the Resources located in jimage file.
 */
public interface Plugin {

    /**
     * Plugin unique name.
     * @return The plugin name.
     */
    public String getName();

    /**
     * Visit the collection of resources.
     * @param inResources Read only resources.
     * @param outResources The pool to fill with resources. Will contain the result of the visit
     * @param strings Bridge to the jimage strings table.
     * @throws Exception
     */
    public void visit(ResourcePool inResources, ResourcePool outResources, StringTable strings)
            throws Exception;
}
