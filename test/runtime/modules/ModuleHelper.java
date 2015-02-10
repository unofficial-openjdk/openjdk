/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Module;

public class ModuleHelper {

    public static Module DefineModule(String name, Object loader, String[] pkgs) throws Throwable {
        return DefineModule(name, "1.0", "location", loader, pkgs);
    }

    public static Module DefineModule(String name, String version, String location,
                                      Object loader, String[] pkgs) throws Throwable {
        return (Module)invoke(findMethod("defineModule"), name, version, location, loader, pkgs);
    }

    public static void AddModuleExports(Object from, String pkg, Object to) throws Throwable {
        invoke(findMethod("addModuleExports"), from, pkg, to);
    }

    public static void AddReadsModule(Object from, Object to) throws Throwable {
        invoke(findMethod("addReadsModule"), from, to);
    }

    public static void AddModulePackage(Object m, String pkg) throws Throwable {
        invoke(findMethod("addModulePackage"), m, pkg);
    }

    public static boolean CanReadModule(Object from, Object to) throws Throwable {
        return (boolean)invoke(findMethod("canReadModule"), from, to);
    }

    public static boolean IsExportedToModule(Object from, String pkg,
                                             Object to) throws Throwable {
        return (boolean)invoke(findMethod("isExportedToModule"), from, pkg, to);
    }

    private static Object invoke(Method m, Object... args) throws Throwable {
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Method findMethod(String name) {
        for (Method m : sun.misc.VM.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new RuntimeException("Failed to find method " + name + "in sun.misc.VM");
    }
}
