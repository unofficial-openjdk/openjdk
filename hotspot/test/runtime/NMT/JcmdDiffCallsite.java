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
 * @summary Test detail memory comparison against early baseline
 * @bug 8013917
 * @key nmt jcmd
 * @library /testlibrary /testlibrary/whitebox
 * @build JcmdDiffCallsite
 * @run main ClassFileInstaller sun.hotspot.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -XX:NativeMemoryTracking=detail JcmdDiffCallsite
 */

import com.oracle.java.testlibrary.*;
import sun.hotspot.WhiteBox;

public class JcmdDiffCallsite {

  public static void main(String args[]) throws Exception {
    OutputAnalyzer output;
    WhiteBox wb = WhiteBox.getWhiteBox();

    // Grab my own PID
    String pid = Integer.toString(ProcessTools.getProcessId());
    ProcessBuilder pb = new ProcessBuilder();

    // Use WB API to alloc and free with the mtTest type
    long memAlloc1 = wb.NMTMalloc(512 * 1024);

    // Use WB API to ensure that all data has been merged before we continue
    if (!wb.NMTWaitForDataMerge()) {
      throw new Exception("Call to WB API NMTWaitForDataMerge() failed");
    }

    // Run 'jcmd <pid> VM.native_memory baseline'
    pb.command(new String[] { JDKToolFinder.getJDKTool("jcmd"), pid, "VM.native_memory", "baseline"});
    pb.start();

    wb.NMTFree(memAlloc1);
    // Use WB API to ensure that all data has been merged before we continue
    if (!wb.NMTWaitForDataMerge()) {
      throw new Exception("Call to WB API NMTWaitForDataMerge() failed");
    }

    // Run 'jcmd <pid> VM.native_memory detail.diff'
    pb.command(new String[] { JDKToolFinder.getJDKTool("jcmd"), pid, "VM.native_memory", "detail.diff"});
    output = new OutputAnalyzer(pb.start());


    // Use WB API to ensure that all data has been merged before we continue
    if (!wb.NMTWaitForDataMerge()) {
      throw new Exception("Call to WB API NMTWaitForDataMerge() failed");
    }

    // above malloc callsite should report 0
    output = new OutputAnalyzer(pb.start());
    output.shouldContain("(malloc=0");
  }
}
