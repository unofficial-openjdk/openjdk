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
 * @build JVMDefineModule
 * @run main ClassFileInstaller sun.hotspot.WhiteBox
 *                              sun.hotspot.WhiteBox$WhiteBoxPermission
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI JVMDefineModule
 */

import com.oracle.java.testlibrary.*;
import sun.hotspot.WhiteBox;
import static com.oracle.java.testlibrary.Asserts.*;

public class JVMDefineModule {

    public static void main(String args[]) throws Exception {
    WhiteBox wb = WhiteBox.getWhiteBox();
    MyClassLoader cl = new MyClassLoader();
    Object m;

    // NULL classloader argument, expect an IAE
    m = wb.DefineModule("mymodule", null, new String[] { "mypackage" });
    assertNotNull(m, "Module should not be null");

    // Invalid classloader argument, expect an IAE
    try {
        wb.DefineModule("mymodule1", new Object(), new String[] { "mypackage1" });
        throw new RuntimeException("Failed to get the expected IAE");
    } catch(IllegalArgumentException e) {
        // Expected
    }

    // NULL package argument, should not throw an exception
    m = wb.DefineModule("mymodule2", cl, null);
    assertNotNull(m, "Module should not be null");
    }

    static class MyClassLoader extends ClassLoader {
    }
}

