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

package jdk.test;

import java.lang.reflect.*;

public class ProxyTest {
    public void test(Module module, ClassLoader ld, Class<?>[] interfaces) {
        testProxyClass(module, ld, interfaces);
        testModuleProxyClass(module, ld, interfaces);
    }

    public void testDynamicModule(ClassLoader ld, Class<?>[] interfaces) {
        Class<?> proxyClass = Proxy.getProxyClass(ld, interfaces);
        assertDynamicModule(proxyClass.getModule(), ld, proxyClass);

        Object proxy = Proxy.newProxyInstance(ld, interfaces, handler);
        assertDynamicModule(proxy.getClass().getModule(), ld, proxy.getClass());
    }

    private static void testProxyClass(Module module, ClassLoader ld, Class<?>... interfaces) {
        Class<?> proxyClass = Proxy.getProxyClass(ld, interfaces);
        assertEquals(proxyClass.getModule(), module);

        Object proxy = Proxy.newProxyInstance(ld, interfaces, handler);
        assertEquals(proxy.getClass().getModule(), module);
    }

    private static void testModuleProxyClass(Module module, ClassLoader ld,  Class<?>... interfaces) {
        Class<?> proxyClass = Proxy.getProxyClass(module, interfaces);
        assertEquals(proxyClass.getModule(), module);

        Object proxy = Proxy.newProxyInstance(module, handler, interfaces);
        assertEquals(proxy.getClass().getModule(), module);
    }

    public static void assertDynamicModule(Module m, ClassLoader ld, Class<?> proxyClass) {
        if (!m.isNamed() || !m.getName().startsWith("jdk.proxy")) {
            throw new RuntimeException(m.getName() + " not dynamic module");
        }

        if (ld != m.getClassLoader() || proxyClass.getClassLoader() != ld) {
            throw new RuntimeException("unexpected class loader");
        }

        try {
            Constructor<?> cons = proxyClass.getConstructor(InvocationHandler.class);
            cons.newInstance(handler);
            throw new RuntimeException("Expected IllegalAccessException: " + proxyClass);
        } catch (IllegalAccessException e) {
            // expected
        } catch (NoSuchMethodException|InstantiationException|InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    public static void assertEquals(Object o1, Object o2) {
        if (o1 != o2) {
            throw new RuntimeException(o1 + " != " + o2);
        }
    }
    private final static InvocationHandler handler =
        (proxy, m, params) -> { throw new RuntimeException(m.toString()); };
}
