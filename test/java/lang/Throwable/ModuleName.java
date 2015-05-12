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

/* @test
   @summary Stack trace should have module information
 */

import java.util.Arrays;

public class ModuleName {
    public static void main(String... args) {
        try {
            Integer.parseInt("a");
        } catch(NumberFormatException ex) {
            StackTraceElement[] stack = ex.getStackTrace();
            StackTraceElement firstFrame = stack[0];
            StackTraceElement thisFrame = findOurFrame(stack);
            if (!firstFrame.getModuleId().startsWith("java.base@")) {
                throw new RuntimeException("First frame should have java.base as module, but had " + firstFrame.getModuleId());
            }
            if (!firstFrame.toString().contains("java.base")) {
                throw new RuntimeException("First frame's toString should contain 'java.base': " + firstFrame.toString());
            }
            if (thisFrame.getModuleId() != null) {
                // This test class is not in a module
                throw new RuntimeException("This frame should not have a module, but had " + thisFrame.getModuleId());
            }
        }
    }

    private static StackTraceElement findOurFrame(StackTraceElement[] stack) {
        return Arrays.stream(stack).filter(s -> s.getClassName().equals("ModuleName")).findFirst().get();
    }
}
