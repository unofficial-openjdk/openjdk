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
 * @test TestG1ConcurrentGCHeapDump
 * @bug 8038925
 * @summary Checks that a heap dump can be made with G1 when no fullgc
 *          has been made
 * @run main/othervm -Xms512m -Xmx1024m -XX:+ExplicitGCInvokesConcurrent TestG1ConcurrentGCHeapDump
 */
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedList;

import javax.management.MBeanServer;
import java.lang.management.ManagementFactory;
import java.lang.management.GarbageCollectorMXBean;

import sun.management.ManagementFactoryHelper;
import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;

import java.io.IOException;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;

public class TestG1ConcurrentGCHeapDump {

  private static final String HOTSPOT_BEAN_NAME =
    "com.sun.management:type=HotSpotDiagnostic";

  private static final String G1_OLD_BEAN_NAME =
    "java.lang:type=GarbageCollector,name=G1 Old Generation";

  private static MBeanServer server = ManagementFactory.getPlatformMBeanServer();

  private static void dumpHeap() throws IOException {
    HotSpotDiagnosticMXBean hotspot_bean =
      ManagementFactory.newPlatformMXBeanProxy(server,
          HOTSPOT_BEAN_NAME, HotSpotDiagnosticMXBean.class);

    Path dir = Files.createTempDirectory("JDK-8038925_");
    String file = dir + File.separator + "heapdump";
    hotspot_bean.dumpHeap(file, false);
    Files.delete(Paths.get(file));
    Files.delete(dir);
  }

  private static void verifyNoFullGC() throws IOException {
    GarbageCollectorMXBean g1_old_bean =
      ManagementFactory.newPlatformMXBeanProxy(server,
          G1_OLD_BEAN_NAME, GarbageCollectorMXBean.class);

    if (g1_old_bean.getCollectionCount() != 0) {
      throw new RuntimeException("A full GC has occured, this test will not work.");
    }
  }

  public static void main(String[] args) throws IOException {
    HotSpotDiagnosticMXBean diagnostic = ManagementFactoryHelper.getDiagnosticMXBean();
    VMOption option = diagnostic.getVMOption("UseG1GC");
    if (option.getValue().equals("false")) {
      System.out.println("Skipping this test. It is only a G1 test.");
      return;
    }

    // Create some dead objects
    ArrayList<List<Integer>> arraylist = new ArrayList<List<Integer>>();
    for (int i = 0; i < 2; i++) {
      for (int j = 0; j < 100; j++) {
        LinkedList<Integer> li = new LinkedList<Integer>();
        arraylist.add(li);
        for (int k = 0; k < 10000; k++) {
          li.add(k);
        }
      }
      arraylist = new ArrayList<List<Integer>>();
      System.gc();
    }
    // Try to dump heap
    dumpHeap();
    // Make sure no full GC has happened, since test won't work if that is the case
    verifyNoFullGC();
  }
}
