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
 * @build JVMAddModuleExports
 * @run main ClassFileInstaller sun.hotspot.WhiteBox
 *                              sun.hotspot.WhiteBox$WhiteBoxPermission
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI JVMAddModuleExports
 */

import com.oracle.java.testlibrary.*;
import sun.hotspot.WhiteBox;
import static com.oracle.java.testlibrary.Asserts.*;

public class JVMAddModuleExports {

    public static void main(String args[]) throws Exception {
        WhiteBox wb = WhiteBox.getWhiteBox();
        MyClassLoader from_cl = new MyClassLoader();
        MyClassLoader to_cl = new MyClassLoader();
        Object from_module, to_module;

        from_module = wb.DefineModule("from_module", from_cl, new String[] { "mypackage", "this/package" });
        assertNotNull(from_module, "Module should not be null");
        to_module = wb.DefineModule("to_module", to_cl, new String[] { "yourpackage", "that/package" });
        assertNotNull(to_module, "Module should not be null");

        // Null from_module argument, expect an NPE
        try {
            wb.AddModuleExports(null, "mypackage", to_module);
            throw new RuntimeException("Failed to get the expected NPE");
        } catch(NullPointerException e) {
            // Expected
        }

        // Normal export to null module
        wb.AddModuleExports(to_module, "that/package", null);

        // Bad from_module argument, expect an IAE
        try {
            wb.AddModuleExports(to_cl, "mypackage", to_module);
            throw new RuntimeException("Failed to get the expected IAE");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Null package argument, expect an NPE
        try {
            wb.AddModuleExports(from_module, null, to_module);
            throw new RuntimeException("Failed to get the expected NPE");
        } catch(NullPointerException e) {
            // Expected
        }

        // Bad to_module argument, expect an IAE
        try {
            wb.AddModuleExports(from_module, "mypackage", from_cl);
            throw new RuntimeException("Failed to get the expected IAE");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Exporting a package to the same module
        wb.AddModuleExports(from_module, "mypackage", from_module);

        // Export a package that does not exist to to_module
        try {
            wb.AddModuleExports(from_module, "notmypackage", to_module);
            throw new RuntimeException("Failed to get the expected IAE");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Export a package, that is not in from_module, to to_module
        try {
            wb.AddModuleExports(from_module, "yourpackage", to_module);
            throw new RuntimeException("Failed to get the expected IAE");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Export a package, that does not exist, to from_module
        try {
            wb.AddModuleExports(from_module, "notmypackage", from_module);
            throw new RuntimeException("Failed to get the expected IAE");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Export a package, that is not in from_module, to from_module
        try {
            wb.AddModuleExports(from_module, "that/package", from_module);
            throw new RuntimeException("Failed to get the expected IAE");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Export the same package twice to the same module
        wb.AddModuleExports(from_module, "this/package", to_module);
        wb.AddModuleExports(from_module, "this/package", to_module);

        // Export a package, using '.' instead of '/'
        try {
            wb.AddModuleExports(from_module, "this.package", to_module);
            throw new RuntimeException("Failed to get the expected IAE");
        } catch(IllegalArgumentException e) {
            // Expected
        }

        // Export a package to the unnamed module and then to a specific module.
        wb.AddModuleExports(to_module, "that/package", null);
        try {
            wb.AddModuleExports(to_module, "that/package", from_module);
            throw new RuntimeException("Failed to get the expected IAE");
        } catch(IllegalArgumentException e) {
            // Expected
        }
    }

    static class MyClassLoader extends ClassLoader { }
}
