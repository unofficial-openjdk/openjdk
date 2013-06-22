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

/* @test TestHandleExceedingProcessSizeLimitIn32BitBuilds.java
 * @key gc
 * @bug 6761744
 * @summary Test run with "-Xmx3072m -XX:MaxPermSize=1024m" to correctly handle VM error (if any)
 * @library /testlibrary
 */

import com.oracle.java.testlibrary.OutputAnalyzer;
import com.oracle.java.testlibrary.ProcessTools;
import java.util.ArrayList;
import java.util.Collections;

public class TestHandleExceedingProcessSizeLimitIn32BitBuilds {
  public static void main(String args[]) throws Exception {
    ArrayList<String> vmOpts = new ArrayList<>();
    String testVmOptsStr = System.getProperty("test.java.opts");
    if (!testVmOptsStr.isEmpty()) {
      String[] testVmOpts = testVmOptsStr.split(" ");
      Collections.addAll(vmOpts, testVmOpts);
    }
    Collections.addAll(vmOpts, new String[] {"-Xmx3072m", "-XX:MaxPermSize=1024m", "-version"});

    ProcessBuilder pb
      = ProcessTools.createJavaProcessBuilder(vmOpts.toArray(new String[vmOpts.size()]));
    OutputAnalyzer output = new OutputAnalyzer(pb.start());

    String dataModel = System.getProperty("sun.arch.data.model");
    if (dataModel.equals("32")) {
      output.shouldContain("The size of the object heap + perm gen exceeds the maximum representable size");
      if (output.getExitValue() == 0) {
        throw new RuntimeException("Not expected to get exit value 0");
      }
    }
  }
}