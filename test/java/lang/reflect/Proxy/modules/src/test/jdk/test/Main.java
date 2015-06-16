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

import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.Module;
import java.util.Arrays;

public class Main {
    public static void main(String... args) {
        ProxyTest ptest = new jdk.test.ProxyTest();
        for (Data d : proxiesForExportedTypes()) {
            System.out.println(d);
            ptest.test(d.module, d.loader, d.interfaces);
        }

        for (Data d : proxiesForPackagePrivateTypes()) {
            System.out.println(d);
            ptest.test(d.module, d.loader, d.interfaces);
        }

        for (Data d : proxiesForModulePrivateTypes()) {
            System.out.println(d);
            ptest.test(d.module, d.loader, d.interfaces);
        }

        for (Data d : proxiesInDynamicModule()) {
            System.out.println(d);
            ptest.testDynamicModule(d.loader, d.interfaces);
        }

        for (Data d : proxiesWtihAddReads()) {
            System.out.println(d);
            ptest.test(d.module, d.loader, d.interfaces);
        }

        for (Data d : proxiesWtihAddExports()) {
            System.out.println(d);
            ptest.test(d.module, d.loader, d.interfaces);
        }
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


    private final static Module m1 = p.one.I.class.getModule();
    private final static Module m2 = p.two.A.class.getModule();
    private final static Module m3 = p.three.P.class.getModule();
    private final static Module test = Main.class.getModule();
    private final static Class<?> unnamedModuleClass = classForName("q.U");
    private final static Class<?> m3InternalType = classForName("p.three.internal.Q");

    /*
     * Test cases for proxy class to implement exported proxy interfaces
     * will result in the unnamed module.
     *
     * The proxy class is accessible to unnamed module.
     */
    static Data[] proxiesForExportedTypes() {
        ClassLoader ld = Main.class.getClassLoader();
        Module unnamed = ld.getUnnamedModule();
        ClassLoader ld2 = new URLClassLoader(new URL[0], ld);
        Module unnamed2 = ld2.getUnnamedModule();

        return new Data[] {
            new Data(unnamed,  ld, Runnable.class),
            new Data(unnamed,  ld, p.one.I.class),
            new Data(unnamed,  ld, p.one.I.class, p.two.A.class),
            new Data(unnamed,  ld, p.one.I.class, unnamedModuleClass),
            new Data(unnamed2, ld2, Runnable.class),
            new Data(unnamed2, ld2, p.one.I.class),
            new Data(unnamed2, ld2, p.one.I.class, p.two.A.class),
            new Data(unnamed2, ld2, p.one.I.class, unnamedModuleClass),
            new Data(unnamed, m1.getClassLoader(), p.one.I.class),
            new Data(unnamed, m2.getClassLoader(), p.two.A.class),
            new Data(unnamed, m3.getClassLoader(), p.three.P.class),
        };
    }

    /*
     * Test cases for proxy class to implement package-private proxy interface
     * will result in same runtime package and same module as the proxy interface
     *
     * The proxy class is accessible to classes in the same runtime package
     */
    static Data[] proxiesForPackagePrivateTypes() {
        Class<?> bClass = classForName("p.two.B");  // package-private type

        return new Data[] {
            new Data(m2, m2.getClassLoader(), p.two.A.class, bClass),
            new Data(m2, m2.getClassLoader(), p.two.A.class, bClass, p.two.internal.C.class)
        };
    }

    /*
     * Test cases for proxy class to implement module-private type from one module
     * will result in the same module as the module-private proxy interface.
     *
     * The proxy class is accessible to classes within the same module
     */
    static Data[] proxiesForModulePrivateTypes() {
        return new Data[] {
            new Data(m1, m1.getClassLoader(), p.one.internal.J.class),
            new Data(m2, m2.getClassLoader(), p.two.internal.C.class, Runnable.class),
            new Data(m2, m2.getClassLoader(), p.two.internal.C.class, p.two.A.class),
            // p.two.A is accessible to m3
            new Data(m3, m3.getClassLoader(), m3InternalType, p.two.A.class),
            // only one module-private type: ambiguous since the same class loader
            new Data(test, test.getClassLoader(), p.three.P.class, jdk.test.internal.R.class)
        };
    }

    /*
     * Test cases for proxy class to implement module-private proxy interfaces
     * in multiple modules and not accessible to unnamed module.  This will
     * result in a dynamic module which can read the modules of the proxy interfaces
     * and their dependences and also qualified exports to the module-private packages.
     *
     * The proxy class is not accessible to any module.
     */
    static Data[] proxiesInDynamicModule() {
        ClassLoader ld = Main.class.getClassLoader();
        ClassLoader customLoader = new URLClassLoader(new URL[0], ld);

        return new Data[] {
            new Data(null, customLoader, p.one.internal.J.class),
            new Data(null, customLoader , p.two.internal.C.class, p.two.A.class),
            new Data(null, customLoader, unnamedModuleClass, jdk.test.internal.R.class, p.one.I.class),

            // two module-private types in two different modules
            new Data(null, m3.getClassLoader(), p.two.internal.C.class, m3InternalType),
            new Data(null, m3.getClassLoader(), p.three.P.class, m3InternalType, jdk.test.internal.R.class),
        };
    }

    /*
     * Test cases for proxy class to implement accessible proxy interfaces
     * after addReads.
     */
    static Data[] proxiesWtihAddReads() {
        Module unnamed = test.getClassLoader().getUnnamedModule();
        test.addReads(unnamed);
        return new Data[] {
             new Data(test, test.getClassLoader(),
                      unnamedModuleClass, p.one.I.class,
                      jdk.test.internal.R.class), // module-private interface in test
        };
    }

    /*
     * Test cases for proxy class to implement accessible proxy interfaces
     * after addExports
     */
    static Data[] proxiesWtihAddExports() {
        m3.addExports("p.three.internal", test);
        return new Data[] {
                new Data(test, test.getClassLoader(),
                         p.one.I.class,
                         p.two.internal.C.class, m3InternalType), // module-private interfaces in m2 and m3

        };
    }

    static Class<?> classForName(String cn) {
        try {
            return Class.forName(cn);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
