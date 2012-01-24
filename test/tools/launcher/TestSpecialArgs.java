/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
 * @bug 7124089
 * @summary Checks for MacOSX specific flags are accepted or rejected
 * @compile -XDignore.symbol.file TestHelper.java TestSpecialArgs.java
 * @run main TestSpecialArgs
 */
import java.util.HashMap;
import java.util.Map;

public class TestSpecialArgs {

    public static void main(String... args) {
        final Map<String, String> envMap = new HashMap<>();
        envMap.put("_JAVA_LAUNCHER_DEBUG", "true");

        TestHelper.TestResult tr = TestHelper.doExec(envMap,
                TestHelper.javaCmd, "-XstartOnFirstThread", "-version");
        if (TestHelper.isMacOSX) {
            if (!tr.contains("In same thread")) {
                System.out.println(tr);
                throw new RuntimeException("Error: not running in the same thread ?");
            }
            if (!tr.isOK()) {
                System.out.println(tr);
                throw new RuntimeException("Error: arg was rejected ????");
            }
        } else {
            if (tr.isOK()) {
                System.out.println(tr);
                throw new RuntimeException("Error: argument was accepted ????");
            }
        }

        tr = TestHelper.doExec(TestHelper.javaCmd, "-Xdock:/tmp/not-available",
                "-version");
        if (TestHelper.isMacOSX) {
            if (!tr.isOK()) {
                System.out.println(tr);
                throw new RuntimeException("Error: arg was rejected ????");
            }
        } else {
            if (tr.isOK()) {
                System.out.println(tr);
                throw new RuntimeException("Error: argument was accepted ????");
            }
        }
    }
}
