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

package com.sun.corba.se.impl.util;

import org.omg.CORBA.ORB;
import java.lang.reflect.Method;

/**
 * Utility class to aid calling java.lang.reflect.Module.addReads.
 *
 * @implNote The implementation uses core reflection to call addReads. This
 * is because of bootstrapping issues in the build where the interim corba
 * build is compiled with the boot JDK.
 */

public class Modules {
    private Modules() { }

    /**
     * Ensures that module java.corba that read the module of the given class.
     */
    public static void ensureReadable(Class<?> targetClass) {
        try {
            Method getModuleMethod = Class.class.getMethod("getModule");
            Object thisModule = getModuleMethod.invoke(ORB.class);
            Object targetModule = getModuleMethod.invoke(targetClass);
            Class<?> moduleClass = getModuleMethod.getReturnType();
            Method addReadsMethod = moduleClass.getMethod("addReads", moduleClass);
            addReadsMethod.invoke(thisModule, targetModule);
        } catch (Exception e) {
            throw new InternalError(e);
        }
    }
}
