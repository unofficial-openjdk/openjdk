/*
 * Copyright (c) 2016, 2017, Oracle and/or its affiliates. All rights reserved.
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
package jdk.internal.loader;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import jdk.internal.module.Checks;

/**
 * Helper class for Class#getResource, Module#getResourceAsStream, and other
 * methods that locate a resource in a module.
 */
public final class ResourceHelper {
    private ResourceHelper() { }

    /**
     * Returns the <em>package name</em> for a resource or the empty package if
     * the resource name does not contain a slash.
     */
    public static String getPackageName(String name) {
        int index = name.lastIndexOf('/');
        if (index != -1) {
            return name.substring(0, index).replace("/", ".");
        } else {
            return "";
        }
    }

    /**
     * Returns true if the resource is a <em>simple resource</em>. Simple
     * resources can never be encapsulated. Resources ending in "{@code .class}"
     * or where the package name is not a legal package name can not be
     * encapsulated.
     */
    public static boolean isSimpleResource(String name) {
        int len = name.length();
        if (len > 6 && name.endsWith(".class")) {
            return true;
        }
        if (!Checks.isPackageName(getPackageName(name))) {
            return true;
        }
        return false;
    }

    /**
     * Converts a resource name to file path that does not have a root
     * component.
     */
    public static Path toFilePath(String name) {
        // consecutive slashes do not collapse
        if (name.startsWith("/") || name.contains("//") || name.endsWith("/"))
            return null;

        // convert to file path
        Path path;
        if (File.separatorChar == '/') {
            path = Paths.get(name);
        } else {
            // not allowed to embed file separators
            if (name.contains(File.separator))
                return null;
            path = Paths.get(name.replace('/', File.separatorChar));
        }

        // file path not allowed to have root component
        return (path.getRoot() == null) ? path : null;
    }

}
