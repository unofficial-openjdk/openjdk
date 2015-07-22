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
 * @test
 * @bug 5049299
 * @summary (process) Use posix_spawn, not fork, on S10 to avoid swap exhaustion
 * @compile BasicLauncher.java Basic.java
 * @run main BasicLauncher
 */

import java.io.*;
import java.nio.file.*;

public class BasicLauncher {

    private static boolean passed = false;

    public static void main(String args[]) throws Exception {
        String osName = System.getProperty("os.name");
        if (osName.startsWith("SunOS")) {
            BasicLauncher l = new BasicLauncher();
            l.start();
        }
    }

    private void start() throws Exception {
        String separator = System.getProperty("file.separator");
        String jdkpath = System.getProperty("test.jdk") + separator + "bin" + separator;
        String srcpath = System.getProperty("test.src", ".") + separator;
        String testClasses = System.getProperty("test.classes", ".");

        ProcessBuilder builder = new ProcessBuilder(
            jdkpath + "java",
            "-cp",
            testClasses,
            "-Djdk.lang.Process.launchMechanism=posix_spawn",
            "Basic");
        builder.redirectErrorStream(true);
        Process testProc = builder.start();
        printProcessThread ppt =
                new printProcessThread(testProc, "testproc");
        ppt.start();
        testProc.waitFor();
        System.out.println("testproc done");

        if (!passed)
            throw new RuntimeException("Test Failed: ");
    }


    class printProcessThread extends Thread {
        Process p;
        String pName;

        public printProcessThread(Process p, String pName) {
            this.p = p;
            this.pName = pName;
        }

        @Override
        public void run() {
            try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[Output: " + pName + "]" + line);
                    if (line.contains("failed = 0")) {
                        passed = true;
                    }
                }

            } catch (Exception e) {
                System.out.println("Exception encountered in " + pName
                        + " thread\n" + e);
            }
        }
    }
}
