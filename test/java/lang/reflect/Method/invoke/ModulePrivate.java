/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

/**
 * @test
 * @run main/othervm -Dsun.reflect.enableModuleChecks ModulePrivate
 */

import java.lang.reflect.Method;

/**
 * A few basic test using reflection in an attempt to invoke a method
 * on a public type that is not exported.
 */

public class ModulePrivate {

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("sun.misc.VM");
        Method m = c.getMethod("getFinalRefCount");

        System.out.format("%s, isAccessible: %s%n", m, m.isAccessible());
        try {
            System.out.println(m.invoke(null));
            throw new RuntimeException("IllegalAccessException expected");
        } catch (IllegalAccessException iae) {
            // expected
            System.out.println(iae);
        }

        System.out.format("%s, isAccessible: %s%n", m, m.isAccessible());
        m.setAccessible(true);
        System.out.println(m.invoke(null));
    }

}
