/**
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

/*
 * @test
 * @summary Basic test of access checks in Core Reflection
 * @run testng CheckAccess
 */

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class CheckAccess {

    void expectedIllegalAccessException() {
        throw new RuntimeException("IllegalAccessException not thrown");
    }

    @Test
    public void testConstructor() throws Exception {
        Class<?> c = Class.forName("sun.misc.BASE64Encoder");
        assertTrue(Modifier.isPublic(c.getModifiers()));

        Constructor<?> ctor = c.getConstructor();
        assertTrue(Modifier.isPublic(ctor.getModifiers()));

        // public class, public constructor, sun.misc not exported
        try {
            ctor.newInstance();
            expectedIllegalAccessException();
        } catch (IllegalAccessException e) { }

        // suppress access check
        ctor.setAccessible(true);
        Object encoder = ctor.newInstance();
    }

    @Test
    public void testMethodInvoke() throws Exception {
        Class<?> c = Class.forName("sun.misc.VM");
        assertTrue(Modifier.isPublic(c.getModifiers()));

        Method m = c.getMethod("isBooted");
        assertTrue(Modifier.isPublic(m.getModifiers()));

        // public class, public method, sun.misc not exported
        try {
            m.invoke(null);
            expectedIllegalAccessException();
        } catch (IllegalAccessException e) { }

        // suppress access check
        m.setAccessible(true);
        m.invoke(null);
    }

    @Test
    public void testFieldGet() throws Exception {
        Class<?> c = Class.forName("sun.misc.Unsafe");
        assertTrue(Modifier.isPublic(c.getModifiers()));

        Field f = c.getField("ADDRESS_SIZE");
        assertTrue(Modifier.isPublic(f.getModifiers()));

        // public class, public field, sun.misc not exported
        try {
            f.get(null);
            expectedIllegalAccessException();
        } catch (IllegalAccessException e) { }

        // suppress access check
        f.setAccessible(true);
        int addressSize = (Integer)f.get(null);
    }
}

