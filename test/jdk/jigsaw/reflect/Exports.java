/**
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

/*
 * @test
 * @summary Basic test of java.lang.reflect.Module isExported and addExports
 * @run testng/othervm Exports
 */

import java.lang.reflect.Constructor;
import java.lang.reflect.Module;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class Exports {

    /**
     * Exercise Module#isExported
     */
    @Test
    public void testIsExported() {
        Module thisModule = this.getClass().getModule();
        Module baseModule = Object.class.getModule();

        assertFalse(thisModule.isNamed());
        assertTrue(thisModule.isExported(""));
        assertTrue(thisModule.isExported("", thisModule));
        assertTrue(thisModule.isExported("", baseModule));
        assertTrue(thisModule.isExported("p"));
        assertTrue(thisModule.isExported("p", thisModule));
        assertTrue(thisModule.isExported("p", baseModule));

        assertTrue(baseModule.isNamed());
        assertTrue(baseModule.isExported("java.lang"));
        assertTrue(baseModule.isExported("java.lang", thisModule));
        assertFalse(baseModule.isExported("sun.reflect"));
        assertFalse(baseModule.isExported("sun.reflect", thisModule));
    }

    /**
     * Exercise Module#addExports with parameters that make it a no-op.
     */
    @Test
    public void testNoopAddExports() {
        Module thisModule = this.getClass().getModule();
        Module baseModule = Object.class.getModule();

        // addExports is a no-op for unnamed modules
        assertFalse(thisModule.isNamed());
        assertTrue(thisModule.addExports("p", baseModule) == thisModule);
        assertTrue(thisModule.addExports("sun.misc", baseModule) == thisModule);

        // addExports is a no-op on named modules if already exported
        assertTrue(baseModule.isNamed());
        assertTrue(baseModule.addExports("java.lang", baseModule) == baseModule);
        assertTrue(baseModule.addExports("java.lang", thisModule) == baseModule);
    }

    /**
     * Exercise Module#addExports to export a package from a named module to
     * an unnamed module.
     */
    @Test
    public void testAddExportsToUnamed() throws Exception {
        Module thisModule = this.getClass().getModule();
        Module baseModule = Object.class.getModule();

        assertFalse(baseModule.isExported("sun.security.x509"));
        assertFalse(baseModule.isExported("sun.security.x509", thisModule));

        Class<?> clazz = Class.forName("sun.security.x509.X500Name");
        Constructor<?> ctor = clazz.getConstructor(String.class);

        // Check that sun.security.x509.X500Name is not accessible
        try {
            Object name = ctor.newInstance("CN=Duke");
            assertTrue(false);
        } catch (IllegalAccessException expected) { }

        baseModule.addExports("sun.security.x509", thisModule);

        // access should succeed
        Object name = ctor.newInstance("CN=Duke");
    }


    @Test(expectedExceptions = { IllegalArgumentException.class })
    public void testAddExportsWithBadPackage() {
        Module thisModule = this.getClass().getModule();
        Module baseModule = Object.class.getModule();
        baseModule.addExports("jdk.badpackage", thisModule);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testAddExportsToNull() {
        Module baseModule = Object.class.getModule();
        baseModule.addExports("java.lang", null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testIsExportedNull() {
        Module thisModule = this.getClass().getModule();
        thisModule.isExported(null, thisModule);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testIsExportedToNull() {
        Module thisModule = this.getClass().getModule();
        thisModule.isExported("", null);
    }

}
