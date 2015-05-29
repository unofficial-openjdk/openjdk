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
 * @modules java.base/sun.misc
 * @build AccessCheckSuper
 * @run main ClassFileInstaller sun.hotspot.WhiteBox
 *                              sun.hotspot.WhiteBox$WhiteBoxPermission
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -XX:AddModuleExports=java.base/sun.misc -Dsun.reflect.useHotSpotAccessCheck=true AccessCheckSuper
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -XX:AddModuleExports=java.base/sun.misc -Dsun.reflect.useHotSpotAccessCheck=false AccessCheckSuper
 */

import java.lang.reflect.Module;
import static jdk.test.lib.Asserts.*;

public class AccessCheckSuper {

    // Test that when a class cannot access its super class the message
    // contains  both "superclass" text and module text.
    public static void main(String args[]) throws Throwable {
        Object m2;

        // Get the class loader for AccessCheckSuper and assume it's also used to
        // load class p2.c2.
        ClassLoader this_cldr = AccessCheckSuper.class.getClassLoader();

        // Define a module for p2.
        m2 = ModuleHelper.ModuleObject("module2", this_cldr, new String[] { "p2" });
        assertNotNull(m2, "Module should not be null");
        ModuleHelper.DefineModule(m2, "9.0", "m2/there", new String[] { "p2" });

        // p2.c2 cannot read its superclass java.lang.Object
        try {
            Class p2_c2_class = Class.forName("p2.c2");
            throw new RuntimeException("Failed to get IAE (can't read superclass)");
        } catch (IllegalAccessError e) {
            if (!e.getMessage().contains("superclass access check failed") ||
                !e.getMessage().contains("cannot read")) {
                throw new RuntimeException("Wrong message: " + e.getMessage());
            }
        }
    }
}
