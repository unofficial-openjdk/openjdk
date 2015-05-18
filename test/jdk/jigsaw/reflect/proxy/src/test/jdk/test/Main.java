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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Module;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Basic test of java.lang.reflect.Proxy
 */
public class Main {
    public static void main(String[] args) throws Exception {
        Module m1 = p.one.I.class.getModule();
        assertTrue(m1.isNamed());

        Module m2 = p.two.A.class.getModule();
        assertTrue(m2.isNamed());

        Module m3 = p.three.P.class.getModule();
        assertTrue(m3.isNamed());

        Module unnamed = q.U.class.getModule();
        ClassLoader unnamedModuleLoader = q.U.class.getClassLoader();
        assertTrue(!unnamed.isNamed());
        ClassLoader customLoader = new URLClassLoader(new URL[0]);
        Module unnamed2 = customLoader.getUnnamedModule();

        // test class loader
        // proxies of exported APIs go to unnamed module
        ClassLoader ld = Main.class.getClassLoader();
        test(ld, unnamed, Runnable.class);
        test(ld, unnamed, p.one.I.class);
        test(ld, unnamed, p.one.I.class, p.two.A.class);
        test(ld, unnamed, p.one.I.class, p.two.A.class, q.U.class);
        test(ld, unnamed, p.one.I.class, p.two.A.class, q.U.class);
        test(customLoader, unnamed2, p.one.I.class);
        test(customLoader, unnamed2, p.one.I.class, p.two.A.class);
        test(customLoader, unnamed2, p.one.I.class, p.two.A.class, q.U.class);
        test(customLoader, unnamed2, p.one.I.class, p.two.A.class, q.U.class);

        // package-private interface
        // must be in the same runtime package as the package-private interface
        // i.e. go to the same module
        Class<?> bClass = Class.forName("p.two.B");
        test(m2, p.two.A.class, bClass);
        test(m2, p.two.A.class, bClass, p.two.internal.C.class);
        testInaccessible(customLoader, p.two.A.class, bClass);

        // module-private interface
        // ## perhaps should check the proxy class is not in an exported package
        test(m1, p.one.internal.J.class);

        // m1 is strict module, it can't access unnamed module
        // test(m1, p.one.internal.J.class, q.U.class);
        testInaccessible(p.one.internal.J.class, p.two.A.class);

        test(m2, p.two.A.class, p.two.internal.C.class);
        test(m3, p.three.internal.Q.class, p.two.A.class);
        testInaccessible(p.three.internal.Q.class, p.two.internal.C.class);
        testInaccessible(customLoader, p.one.internal.J.class, p.two.internal.C.class);

        // framework case - qualified exports may be added
        // ## m3 and test are defined in the same class loader
        Module test = Main.class.getModule();
        // only one module-private type: ambiguous since the same class loader
        test(m3.getClassLoader(), test, p.three.P.class, jdk.test.R.class);
        // two module-private types in m2 and caller's module
        // m3 can't read test
        testInaccessible(m3.getClassLoader(), p.three.P.class, p.three.internal.Q.class, jdk.test.R.class);
        testInaccessible(m3.getClassLoader(), p.three.P.class, p.two.internal.C.class, jdk.test.R.class);
        // ambiguous
        test(m3.getClassLoader(), test, p.three.P.class, p.two.A.class, jdk.test.R.class);
        test(unnamed, q.U.class);
        test(unnamed, q.U.class, p.one.I.class);

        // test is strict module and can't read unnamed module where q.U is defined
        testInaccessible(jdk.test.R.class, q.U.class, p.one.I.class);
        testInaccessible(q.U.class, p.two.internal.C.class, p.three.internal.Q.class);

        // make test module loose and now can access q.U
        test.addReads(null);
        test(test, q.U.class, p.one.I.class, jdk.test.R.class);
    }

    static void test(Module expected, Class<?>... interfaces) {
        Class<?> c = interfaces[0];
        test(c.getClassLoader(), expected, interfaces);
    }

    static void test(ClassLoader ld, Module expected, Class<?>... interfaces) {
        Object proxy = proxy(ld, interfaces);
        System.out.format("%s %s expected %s%n",
            proxy.getClass().getName(), proxy.getClass().getModule(), expected);
        assertTrue(proxy.getClass().getModule() == expected);
    }

    static void testInaccessible(Class<?>... interfaces) {
        Class<?> c = interfaces[0];
        testInaccessible(c.getClassLoader(), interfaces);
    }

    static void testInaccessible(ClassLoader ld, Class<?>... interfaces) {
        try {
            Object proxy = proxy(ld, interfaces);
            expectedIllegalArgumentException();
        } catch (IllegalArgumentException e) {};
    }

    static Object proxy(ClassLoader ld, Class<?>... interfaces) {
        return Proxy.newProxyInstance(ld, interfaces, handler);
    }

    private final static InvocationHandler handler = new InvocationHandler() {
        @Override
        public Object invoke(Object proxy, Method m, Object... params) {
            throw new RuntimeException(m.toString());
        }
    };

    static void assertTrue(boolean expr) {
        if (!expr)
            throw new RuntimeException("Assertion failed");
    }

    static void expectedIllegalArgumentException() {
        throw new RuntimeException("IllegalArgumentException expected");
    }
}
