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

import java.lang.Exception;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Module;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import jdk.test.ProxyTest;
import static jdk.test.ProxyTest.*;

public class ProxyAccess {
    private final static Module m1 = p.one.I.class.getModule();
    private final static Module m2 = p.two.A.class.getModule();
    private final static Module m3 = p.three.P.class.getModule();
    private final static Module unnamed = ProxyAccess.class.getModule();
    private final static ProxyTest ptest = new jdk.test.ProxyTest();

    public static void main(String... args) throws Exception {
        if (jdk.test.ProxyTest.class.getModule().isNamed()) {
            throw new RuntimeException("ProxyTest must be on classpath");
        }
        for (Data d : proxiesForExportedTypes()) {
            System.out.println(d);
            ptest.test(d.module, d.loader, d.interfaces);
        }
        for (Data d : proxiesForModulePrivateTypes()) {
            System.out.println(d);
            ptest.test(d.module, d.loader, d.interfaces);
        }

        // this will add qualified export of sun.invoke from java.base to a dynamic module
        testModuleMethodHandle();
        // this will add qualified export of sun.invoke from java.base to a dynamic module
        testRunnableMethodHandle();
    }

    static class Data {
        final ClassLoader loader;
        final Module module;
        final Class<?>[] interfaces;
        Data(Module m, ClassLoader loader, Class<?>... interfaces) {
            this.module = m;
            this.loader = loader;
            this.interfaces = interfaces;
        }

        private String moduleName() {
            if (module == null) {
                return "dynamic";
            } else {
                return module.isNamed() ? module.getName() : "unnamed";
            }
        }
        @Override
        public String toString() {
            return String.format("Proxy test for %s loaded by %s Expected: %s%n",
                    Arrays.toString(interfaces), loader, moduleName());
        }
    }

    /*
     * Test cases for proxy class to implement exported proxy interfaces
     * will result in the ptest or unnamed module
     *
     * The proxy class is accessible to unnamed module.
     */
    static Data[] proxiesForExportedTypes() {
        ClassLoader ld = unnamed.getClassLoader();
        ClassLoader ld2 = new URLClassLoader(new URL[0], ld);
        Module unnamed2 = ld2.getUnnamedModule();

        return new Data[] {
            new Data(unnamed,  ld,  Runnable.class),
            new Data(unnamed,  ld,  p.one.I.class),
            new Data(unnamed,  ld,  p.one.I.class, p.two.A.class),
            new Data(unnamed,  ld,  p.one.I.class, q.U.class),
            new Data(unnamed2, ld2, Runnable.class),
            new Data(unnamed2, ld2, p.one.I.class),
            new Data(unnamed2, ld2, p.one.I.class, p.two.A.class),
            new Data(unnamed2, ld2, p.one.I.class, q.U.class),

            new Data(unnamed,  m1.getClassLoader(), p.one.I.class),
            new Data(unnamed,  m2.getClassLoader(), p.two.A.class),
            new Data(unnamed,  m3.getClassLoader(), p.three.P.class),

        };
    }

    /*
     * Test cases for proxy class to implement package-private proxy interface
     * will result in same runtime package and same module as the proxy interface
     *
     * The proxy class is accessible to classes in the same runtime package
     */
    static Data[] proxiesForModulePrivateTypes() {
        Class<?> bClass = classForName("p.two.B");  // package-private type
        Class<?> cClass = classForName("p.two.internal.C");
        Class<?> jClass = classForName("p.one.internal.J");
        Class<?> qClass = classForName("p.three.internal.Q");
        return new Data[] {
            new Data(m2, m2.getClassLoader(), p.two.A.class, bClass),
            new Data(m2, m2.getClassLoader(), p.two.A.class, bClass, cClass),
            new Data(m3, m3.getClassLoader(), p.two.A.class, qClass)
        };
    }

    static void testRunnableMethodHandle() throws Exception {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType mt = MethodType.methodType(void.class);
        MethodHandle mh = lookup.findStatic(ProxyAccess.class, "runForRunnable", mt);
        Runnable proxy = MethodHandleProxies.asInterfaceInstance(Runnable.class, mh);
        proxy.run();

        Class<?> proxyClass = proxy.getClass();
        Module target = proxyClass.getModule();
        assertDynamicModule(target, proxyClass.getClassLoader(), proxyClass);
    }

    static void testModuleMethodHandle() throws Exception {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType mt = MethodType.methodType(void.class);
        MethodHandle mh = lookup.findStatic(ProxyAccess.class, "runForRunnable", mt);
        p.one.I proxy = MethodHandleProxies.asInterfaceInstance(p.one.I.class, mh);
        proxy.run();
        Class<?> proxyClass = proxy.getClass();
        Module target = proxyClass.getModule();
        assertDynamicModule(target, proxyClass.getClassLoader(), proxyClass);
    }

    static void runForRunnable() {
        System.out.println("runForRunnable");
    }

    private static Class<?> classForName(String cn) {
        try {
            return Class.forName(cn);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
    private final static InvocationHandler handler =
         (proxy, m, params) -> { throw new RuntimeException(m.toString()); };
}
