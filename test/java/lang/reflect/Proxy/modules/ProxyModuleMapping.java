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

/* @test
 * @summary Basic test of proxy module mapping and the access to Proxy class
 *          call Constructor.verifyNewInstanceAccess
 * @modules java.base/sun.invoke
 */

import java.lang.reflect.*;

public class ProxyModuleMapping {
    public static void main(String... args) throws Exception {
        ClassLoader ld = ProxyModuleMapping.class.getClassLoader();
        ProxyModuleMapping p1 = new ProxyModuleMapping(ld, Runnable.class);
        p1.verifyNewInstanceAccess(ld.getUnnamedModule());

        // unnamed module gets access to sun.invoke package (e.g. via -XaddExports)
        ProxyModuleMapping p2 = new ProxyModuleMapping(ld, sun.invoke.WrapperInstance.class);
        p2.checkDynamicModule();

        Class<?> modulePrivateIntf = Class.forName("sun.net.ProgressListener");
        ProxyModuleMapping p3 = new ProxyModuleMapping(ld, modulePrivateIntf);
        p3.checkDynamicModule();
    }

    final ClassLoader loader;
    final Class<?>[] interfaces;
    ProxyModuleMapping(ClassLoader loader, Class<?>... interfaces) {
        this.loader = loader;
        this.interfaces = interfaces;
    }

    Class<?> getProxyClass() {
        return Proxy.getProxyClass(loader, interfaces);
    }

    void verifyNewInstanceAccess(Module expectd) throws Exception {
        Class<?> c = getProxyClass();
        if (c.getModule() != expectd) {
            throw new RuntimeException(c.getModule() + " not expected: " + expectd);
        }

        Constructor<?> cons = c.getConstructor(InvocationHandler.class);
        cons.newInstance(ih);
    }

    void checkDynamicModule() throws Exception {
        Class<?> c = getProxyClass();
        Module m = c.getModule();
        if (!m.isNamed() || !m.getName().startsWith("jdk.proxy")) {
            throw new RuntimeException();
        }

        try {
            Constructor<?> cons = c.getConstructor(InvocationHandler.class);
            cons.newInstance(ih);
            throw new RuntimeException("expected IAE not thrown");
        } catch (IllegalAccessException e) {}

        Object o = Proxy.newProxyInstance(loader, interfaces, ih);
        m = o.getClass().getModule();
        if (!m.isNamed() || !m.getName().startsWith("jdk.proxy")) {
            throw new RuntimeException(c.getModule() + " not expected: dynamic module");
        }
    }
    private final static InvocationHandler ih =
        (proxy, m, params) -> { System.out.println(m); return null; };
}
