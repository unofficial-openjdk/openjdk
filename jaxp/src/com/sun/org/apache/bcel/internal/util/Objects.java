/*
 * Copyright (c) 2013 Oracle and/or its affiliates. All rights reserved.
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
package com.sun.org.apache.bcel.internal.util;

/**
 * Emulate two methods of java.util.Objects. We can't use JDK 7 new APIs in
 * JAXP because the jaxp repository is built before the jdk repository. This
 * is a temporary solution to simplify backports from JDK 8 to JDK 7.
 **/
public final class Objects {
    private Objects() {
        throw new IllegalAccessError();
    }

    public static int hashCode(final Object o) {
        return o == null ? 0 : o.hashCode();
    }

    public static boolean equals(Object one, Object two) {
        return one == two || one != null && one.equals(two);
    }

}
