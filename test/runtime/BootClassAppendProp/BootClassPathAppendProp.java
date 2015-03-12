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

import java.io.File;

/*
 * @test
 * @build BootClassPathAppendProp
 * @run main/othervm -Xbootclasspath/a:/usr/lib -showversion -Xbootclasspath/a:/i/dont/exist BootClassPathAppendProp set
 * @run main/othervm -Xoverride:/not/here -Xbootclasspath/a:/i/may/exist BootClassPathAppendProp override
 * @run main/othervm BootClassPathAppendProp empty
 */

// Test that property jdk.boot.class.path.append contains only the bootclasspath
// info following the .jimage file.
public class BootClassPathAppendProp {

    public static void test_prop(String expected_val) {
        String propVal = System.getProperty("jdk.boot.class.path.append");
        if (!propVal.equals(expected_val)) {
            throw new RuntimeException(
                 "Bad jdk.boot.class.path.append property value: " + propVal);
        }
    }

    public static void main(String[] args) {
        if (args[0].equals("set")) {
            test_prop("/usr/lib" + File.pathSeparator + "/i/dont/exist");
        } else if (args[0].equals("override")) {
            test_prop("/i/may/exist");
        } else if (args[0].equals("empty")) {
            test_prop("");
        } else {
            throw new RuntimeException("Unexpected arg to main: " + args[0]);
        }
    }
}
