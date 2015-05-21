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

package com.sun.xml.internal.ws;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class used to enforce accessibility of a module when using Reflection API.
 *
 * @author Miroslav Kos (miroslav.kos at oracle.com)
 */
public class ModuleAccessHelper {

    private static final Logger logger = Logger.getLogger(ModuleAccessHelper.class.getName());

    /**
     * This method uses jdk9 specific API. For all the JDKs <= 8 empty.
     *
     * @param sourceClass class (current module) usinf Core Reflection API
     * @param targetClass class to be accessed via Core Reflection
     */
    public static void ensureAccess(Class<?> sourceClass, Class<?> targetClass) {
        java.lang.reflect.Module targetModule = targetClass.getModule();
        java.lang.reflect.Module sourceModule = sourceClass.getModule();
        if (!sourceModule.canRead(targetModule)) {
            logger.log(Level.FINE, "Adding module [{0}] to module [{1}]'s reads",
                       new Object[]{targetModule.getName(), sourceModule.getName()});
            AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
                sourceModule.addReads(targetModule);
                return null;
            });
        }
    }


}
