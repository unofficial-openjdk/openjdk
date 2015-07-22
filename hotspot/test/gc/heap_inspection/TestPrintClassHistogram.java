/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
 * @test TestPrintClassHistogram
 * @bug 8010294
 * @summary Checks that a class histogram can be printed both before and after
 *          a GC
 * @library /testlibrary
 * @run main/othervm TestPrintClassHistogram launch BeforeGC
 * @run main/othervm TestPrintClassHistogram launch AfterGC
 * @run main/othervm TestPrintClassHistogram launch BeforeGC AfterGC
 */

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

import com.oracle.java.testlibrary.OutputAnalyzer;
import com.oracle.java.testlibrary.ProcessTools;

/* This test uses a "trick" to be able to analyze the output of the test:
 *
 *   The test starts a java process that runs the same code, but the first
 *   argument is different ("run" instead of "launch"). The change of flags
 *   will run the test code instead of launching the test again.
 */
public class TestPrintClassHistogram {
    public static void main(String[] args) throws Exception {
        if (shouldLaunchTest(args)) {
            launchTest(args);
            return;
        }

        // This is the actual test code
        System.gc();
    }

    private static boolean shouldLaunchTest(String[] cmdlineArguments) {
        return cmdlineArguments[0].equals("launch");
    }

    private static void launchTest(String[] args) throws Exception {
        String[] testArgs = createTestArguments(args);
        ProcessBuilder pb = ProcessTools.createJavaProcessBuilder(testArgs);

        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.shouldNotContain("WARNING");

        // There will always be at least one java.lang.Class instance
        output.shouldContain("java.lang.Class");

        // There will always be at least one java.lang.String instance
        output.shouldContain("java.lang.String");

        output.shouldHaveExitValue(0);
    }

    private static String[] createTestArguments(String[] cmdlineArguments) {
        List<String> cmdlineArgs = Arrays.asList(cmdlineArguments);
        List<String> testArgs = new ArrayList<String>();

        if (cmdlineArgs.contains("BeforeGC")) {
            testArgs.add("-XX:+PrintClassHistogramBeforeFullGC");
        }
        if (cmdlineArgs.contains("AfterGC")) {
            testArgs.add("-XX:+PrintClassHistogramAfterFullGC");
        }

        testArgs.add("TestPrintClassHistogram");
        testArgs.add("run");

        return testArgs.toArray(new String[testArgs.size()]);
    }
}
