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
 * @modules java.base/sun.misc
 */

import java.lang.reflect.Constructor;
import java.lang.reflect.Module;

/**
 * Test exporting a JDK-internal API (sun.security.x509) to an unnamed module.
 */

public class ExportToUnnamed {

    public static void main(String[] args) throws Exception {

        Class<?> thisClass = ExportToUnnamed.class;

        Module thisModule = thisClass.getModule();
        assertTrue(!thisModule.isNamed());

        Module baseModule = Object.class.getModule();
        if (baseModule.isExported("sun.security.x509", thisModule))
            throw new RuntimeException("sun.security.x509 is already exported");

        Class<?> clazz = Class.forName("sun.security.x509.X500Name");
        Constructor<?> ctor = clazz.getConstructor(String.class);

        // Check that sun.security.x509.X500Name is not accessible
        try {
            Object name = ctor.newInstance("CN=Duke");
            throw new RuntimeException(name + " created, unexpected");
        } catch (IllegalAccessException expected) { }

        // Use JDK-internal API to export sun.security.x509
        sun.misc.Modules.addExports(baseModule, "sun.security.x509", thisModule);

        Object name = ctor.newInstance("CN=Duke");
        System.out.println(name);
    }

    static void assertTrue(boolean e) {
        if (!e) throw new RuntimeException();
    }
}
