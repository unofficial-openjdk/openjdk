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
 * @library /testlibrary  /../../test/lib /compiler/whitebox ..
 * @build JVMDefineModule
 * @run main ClassFileInstaller sun.hotspot.WhiteBox
 *                              sun.hotspot.WhiteBox$WhiteBoxPermission
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -XX:AddModuleExports=java.base/sun.misc JVMDefineModule
 */

import static com.oracle.java.testlibrary.Asserts.*;

public class JVMDefineModule {

    public static void main(String args[]) throws Throwable {
        MyClassLoader cl = new MyClassLoader();
        Object m;

        // NULL classloader argument, expect success
        m = ModuleHelper.DefineModule("mymodule", null, new String[] { "mypackage" });
        assertNotNull(m, "Module should not be null");

        // Invalid classloader argument, expect an IAE
        try {
            ModuleHelper.DefineModule("mymodule1", new Object(), new String[] { "mypackage1" });
            throw new RuntimeException("Failed to get expected IAE for bad loader");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // NULL package argument, should not throw an exception
        m = ModuleHelper.DefineModule("mymodule2", cl, null);
        assertNotNull(m, "Module should not be null");

        // NULL module name, expect an NPE
        try {
            ModuleHelper.DefineModule(null, cl, new String[] { "mypackage2" });
            throw new RuntimeException("Failed to get expected NPE for NULL module");
        } catch(NullPointerException e) {
            // Expected
        }

        // module name is java.base, expect an IAE
        try {
            ModuleHelper.DefineModule("java.base", cl, new String[] { "mypackage3" });
            throw new RuntimeException("Failed to get expected IAE for java.base");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Duplicates in package list, expect an IAE
        try {
            ModuleHelper.DefineModule("java.base", cl, new String[] { "mypackage4", "mypackage5", "mypackage4" });
            throw new RuntimeException("Failed to get IAE for duplicate packages");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Empty entry in package list, expect an IAE
        try {
            ModuleHelper.DefineModule("java.base", cl, new String[] { "mypackageX", "", "mypackageY" });
            throw new RuntimeException("Failed to get IAE for empty package");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Duplicate module name, expect an IAE
        m = ModuleHelper.DefineModule("module.name", cl, new String[] { "mypackage6" });
        assertNotNull(m, "Module should not be null");
        try {
            ModuleHelper.DefineModule("module.name", cl, new String[] { "mypackage7" });
            throw new RuntimeException("Failed to get IAE for duplicate module");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Package is already defined for class loader, expect an IAE
        try {
            ModuleHelper.DefineModule("dupl.pkg.module", cl, new String[] { "mypackage6" });
            throw new RuntimeException("Failed to get IAE for existing package");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Empty module name, expect an IAE
        try {
            ModuleHelper.DefineModule("", cl, new String[] { "mypackage8" });
            throw new RuntimeException("Failed to get expected IAE for empty module name");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Bad module name, expect an IAE
        try {
            ModuleHelper.DefineModule("bad;name", cl, new String[] { "mypackage9" });
            throw new RuntimeException("Failed to get expected IAE for bad;name");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Bad module name, expect an IAE
        try {
            ModuleHelper.DefineModule(".leadingdot", cl, new String[] { "mypackage9a" });
            throw new RuntimeException("Failed to get expected IAE for .leadingdot");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Bad module name, expect an IAE
        try {
            ModuleHelper.DefineModule("trailingdot.", cl, new String[] { "mypackage9b" });
            throw new RuntimeException("Failed to get expected IAE for trailingdot.");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Bad module name, expect an IAE
        try {
            ModuleHelper.DefineModule("consecutive..dots", cl, new String[] { "mypackage9c" });
            throw new RuntimeException("Failed to get expected IAE for consecutive..dots");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // module name with multiple dots, should be okay
        m = ModuleHelper.DefineModule("more.than.one.dat", cl, new String[] { "mypackage9d" });
        assertNotNull(m, "Module should not be null");

        // Zero length package list, should be okay
        m = ModuleHelper.DefineModule("zero.packages", cl, new String[] { });
        assertNotNull(m, "Module should not be null");

        // Invalid package name, expect an IAE
        try {
            ModuleHelper.DefineModule("module5", cl, new String[] { "your.package" });
            throw new RuntimeException("Failed to get expected IAE for your.package");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Invalid package name, expect an IAE
        try {
            ModuleHelper.DefineModule("module6", cl, new String[] { ";your/package" });
            throw new RuntimeException("Failed to get expected IAE for ;your.package");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Invalid package name, expect an IAE
        try {
            ModuleHelper.DefineModule("module7", cl, new String[] { "7[743" });
            throw new RuntimeException("Failed to get expected IAE for package 7[743");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // module version that is null, should be okay
        m = ModuleHelper.DefineModule("module8", null, "/not/here", cl, new String[] { "a_package_8" });
        assertNotNull(m, "Module should not be null");

        // module version that is "", should be okay
        m = ModuleHelper.DefineModule("module9", "", "/not/here", cl, new String[] { "a_package_9" });
        assertNotNull(m, "Module should not be null");

        // module location that is null, should be okay
        m = ModuleHelper.DefineModule("module10", "9.5", null, cl, new String[] { "a_package_10" });
        assertNotNull(m, "Module should not be null");

        // module location that is "", should be okay
        m = ModuleHelper.DefineModule("module11", "9.5", "", cl, new String[] { "a_package_11" });
        assertNotNull(m, "Module should not be null");
    }

    static class MyClassLoader extends ClassLoader { }
}

