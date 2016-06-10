/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
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
 * @bug 8136930
 * @summary Examine implications for custom launchers, equivalent of java -X options in particular
 * @modules java.base/jdk.internal.misc
 * @library /testlibrary
 */

import jdk.test.lib.*;

// Test that the VM behaves correctly when processing module related options.
public class ModuleOptionsTest {

    public static void main(String[] args) throws Exception {

        // Test that last -addmods is the only one recognized.  No exception
        // should be thrown.
        ProcessBuilder pb = ProcessTools.createJavaProcessBuilder(
            "-addmods", "i_dont_exist", "-addmods", "java.base", "-version");
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.shouldHaveExitValue(0);

        // Test that last -limitmods is the only one recognized.  No exception
        // should be thrown.
        pb = ProcessTools.createJavaProcessBuilder(
            "-limitmods", "i_dont_exist", "-limitmods", "java.base", "-version");
        output = new OutputAnalyzer(pb.start());
        output.shouldHaveExitValue(0);

        // Test what happens when the value for -addmods is missing.
        pb = ProcessTools.createJavaProcessBuilder(
            "-addmods", "-XX:-UseCompressedOops", "-version");
        output = new OutputAnalyzer(pb.start());
        output.shouldContain("ResolutionException");
        output.shouldHaveExitValue(1);
    }
}
