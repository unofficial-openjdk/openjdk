/*
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

/*
 * @test
 * @library /testlibrary /testlibrary/whitebox
 * @build JVMAddModulePackage
 * @run main ClassFileInstaller sun.hotspot.WhiteBox
 *                              sun.hotspot.WhiteBox$WhiteBoxPermission
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI JVMAddModulePackage
 */

import com.oracle.java.testlibrary.*;
import sun.hotspot.WhiteBox;
import static com.oracle.java.testlibrary.Asserts.*;

public class JVMAddModulePackage {

    public static void main(String args[]) throws Exception {
        WhiteBox wb = WhiteBox.getWhiteBox();
        MyClassLoader cl1 = new MyClassLoader();
        MyClassLoader cl3 = new MyClassLoader();
        Object module1, module2, module3;
        boolean result;

        module1 = wb.DefineModule("module1", cl1, new String[] { "mypackage" });
        assertNotNull(module1, "Module should not be null");
        module2 = wb.DefineModule("module2", cl1, new String[] { "yourpackage" });
        assertNotNull(module2, "Module should not be null");
        module3 = wb.DefineModule("module3", cl3, new String[] { "package/num3" });
        assertNotNull(module3, "Module should not be null");

        // Simple call
        wb.AddModulePackage(module1, "new_package");

        // Add a package and export it
        wb.AddModulePackage(module1, "package/num3");
        wb.AddModuleExports(module1, "package/num3", null);

        // Null module argument, expect an IAE
        try {
            wb.AddModulePackage(null, "new_package");
            throw new RuntimeException("Failed to get the expected IAE");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Bad module argument, expect an IAE
        try {
            wb.AddModulePackage(cl1, "new_package");
            throw new RuntimeException("Failed to get the expected IAE");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Null package argument, expect an IAE
        try {
            wb.AddModulePackage(module1, null);
            throw new RuntimeException("Failed to get the expected IAE");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Existing package, expect an IAE
        try {
            wb.AddModulePackage(module1, "yourpackage");
            throw new RuntimeException("Failed to get the expected IAE");
        } catch(IllegalArgumentException e) {
            // Expected
        }
    }

    static class MyClassLoader extends ClassLoader { }
}
