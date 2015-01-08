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

/**
 * Used with -Xoverride to exercise the replacement or addition of classes
 * in modules that are linked into the runtime image.
 */

public class Basic {

    static final ClassLoader BOOT_LOADER  = null;
    static final ClassLoader EXT_LOADER   = extLoader();
    static final ClassLoader SYS_LOADER   = ClassLoader.getSystemClassLoader();

    public static void main(String[] args) throws Exception {

        // boot loader, replace class
        Class<?> clazz = Class.forName("java.text.Annotation");
        assertLoader(clazz, BOOT_LOADER);
        String s = clazz.newInstance().toString();
        assertTrue(s.equals("hi"));

        // boot loader, add class
        assertLoader("java.text.AnnotationBuddy", BOOT_LOADER);

        // ext loader, replace class
        clazz = Class.forName("com.sun.jndi.dns.DnsClient");
        assertLoader(clazz, EXT_LOADER);
        s = clazz.newInstance().toString();
        assertTrue(s.equals("hi"));

        // ext loader, add class
        assertLoader("com.sun.jndi.dns.DnsClientBuddy", EXT_LOADER);

        // system class loader, replace class
        clazz = Class.forName("com.sun.tools.javac.Main");
        assertLoader(clazz, SYS_LOADER);
        s = clazz.newInstance().toString();
        assertTrue(s.equals("hi"));

        // system class loader, add class
        assertLoader("com.sun.tools.javac.MainBuddy", SYS_LOADER);
    }


    /**
     * Locates and returns the extension class loader.
     */
    static ClassLoader extLoader() {
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        ClassLoader parent;
        while ((parent = loader.getParent()) != null) {
            loader = parent;
        }
        return loader;
    }

    /**
     * Asserts that class with the given name has the expected defining
     * loader.
     */
    static void assertLoader(String cn, ClassLoader expected) {
        try {
            Class<?> clazz = Class.forName(cn);
            assertLoader(clazz, expected);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Asserts that given class has the expected defining loader.
     */
    static void assertLoader(Class<?> clazz, ClassLoader expected) {
        ClassLoader loader = clazz.getClassLoader();
        if (loader != expected) {
            throw new RuntimeException(clazz + " loaded by " + loader +
                ", expected " + expected);
        }
    }

    static void assertTrue(boolean expr) {
        if (!expr)
            throw new RuntimeException("assertion failed");
    }
}
