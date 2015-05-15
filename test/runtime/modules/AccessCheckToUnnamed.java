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

/*
 * @test
 * @library /testlibrary /../../test/lib /compiler/whitebox ..
 * @compile p2/c2.java
 * @compile p1/c1.java
 * @modules java.base/sun.misc
 * @build AccessCheckToUnnamed
 * @run main ClassFileInstaller sun.hotspot.WhiteBox
 *                              sun.hotspot.WhiteBox$WhiteBoxPermission
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -XX:AddModuleExports=java.base/sun.misc  -Dsun.reflect.useHotSpotAccessCheck=true AccessCheckToUnnamed
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -XX:AddModuleExports=java.base/sun.misc  -Dsun.reflect.useHotSpotAccessCheck=false AccessCheckToUnnamed
 */

import java.lang.reflect.Module;
import static com.oracle.java.testlibrary.Asserts.*;

public class AccessCheckToUnnamed {

    // Check that a class in a package in module1 can successfully access a
    // class that is in the unnamed module.
    // has been exported.
    public static void main(String args[]) throws Throwable {
        Object m1;

        // Get the java.lang.reflect.Module object for module java.base.
        Class jlObject = Class.forName("java.lang.Object");
        Object jlObject_jlrM = jlObject.getModule();
        assertNotNull(jlObject_jlrM, "jlrModule object of java.lang.Object should not be null");

        // Get the class loader for AccessCheckToUnnamed and assume it's also used to
        // load class p1.c1.
        ClassLoader this_cldr = AccessCheckToUnnamed.class.getClassLoader();

        // Define a module for p1.
        m1 = ModuleHelper.ModuleObject("module1", this_cldr, new String[] { "p1" });
        assertNotNull(m1, "Module should not be null");
        ModuleHelper.DefineModule(m1, "9.0", "m1/here", new String[] { "p1" });
        ModuleHelper.AddReadsModule(m1, jlObject_jlrM);

        // Make package p1 in m1 visible to everyone.
        ModuleHelper.AddModuleExports(m1, "p1", null);

        // p1.c1's ctor tries to call a method in p2.c2.  This should work because
        // p2 is in the unnamed module.
        Class p1_c1_class = Class.forName("p1.c1");
        p1_c1_class.newInstance();
    }
}

