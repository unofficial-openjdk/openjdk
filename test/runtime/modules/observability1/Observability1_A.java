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

import p2.*;

public class Observability1_A {
    public static void main(String args[]) throws Exception {
        try {
            // Try loading a class within a named package
            // in the unnamed module.
            // Ensure the class can still be loaded successfully by the
            // boot loader since it is located on -Xbootclasspath/a.
            p2.Observability1_B b = new p2.Observability1_B();
            if (b.getClass().getClassLoader() != null) {
              throw new RuntimeException("Observability1 FAILED - class B should be loaded by boot class loader\n");
            }
            b.m();

            // Now that the package p2 has been recorded in the
            // unnamed module within the boot loader's PackageEntryTable,
            // ensure that a different class within the same package
            // can be located on -Xbootclasspath/a as well.
            p2.Observability1_C c = new p2.Observability1_C();
            if (c.getClass().getClassLoader() != null) {
              throw new RuntimeException("Observability1 FAILED - class C should be loaded by boot class loader\n");
            }
            c.m();
        } catch (Exception e) {
            System.out.println(e);
            throw new RuntimeException("Observability1 FAILED - test should not throw exception\n");
        }
        System.out.println("Observability1 PASSED\n");
    }
}
