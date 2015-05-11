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

/*
 * @test ImageGetDataAddressTest
 * @summary Unit test for JVM_ImageGetDataAddress() method
 * @author sergei.pikalev@oracle.com
 * @library /testlibrary /../../test/lib
 * @build ImageGetDataAddressTest
 * @run main ClassFileInstaller sun.hotspot.WhiteBox
 *                              sun.hotspot.WhiteBox$WhiteBoxPermission
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -XX:+MemoryMapImage ImageGetDataAddressTest +
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -XX:-MemoryMapImage ImageGetDataAddressTest -
 */

import java.io.File;
import java.nio.ByteOrder;
import sun.hotspot.WhiteBox;

public class ImageGetDataAddressTest {

    public static final WhiteBox wb = WhiteBox.getWhiteBox();

    public static void main(String... args) throws Exception {
        String javaHome = System.getProperty("java.home");
        String imageFile = javaHome + "/lib/modules/bootmodules.jimage";

        if (!(new File(imageFile)).exists()) {
            return;
        }

        boolean isMMap = true;
        for (String arg : args)
            if (arg.equals("-"))
                isMMap = false;

        if (!testImageGetDataAddress(imageFile, isMMap))
            throw new RuntimeException("Some cases are failed");
    }

    private static boolean testImageGetDataAddress(String imageFile, boolean isMMap) {
        String mm = isMMap? "-XX:+MemoryMapImage" : "-XX:-MemoryMapImage";
        boolean bigEndian = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
        long id = wb.imageOpenImage(imageFile, bigEndian);
        boolean passed = true;

        // get data for valid id
        long dataAddr = wb.imageGetDataAddress(id);
        if ((dataAddr != 0) && isMMap) {
            System.out.printf("Passed. Data address is %d for valid id and %s\n", dataAddr, mm);
        } else if ((dataAddr  == 0) && !isMMap) {
            System.out.printf("Passed. Data address is zero for valid id and %s\n", mm);
        } else {
            System.out.printf("Failed. Data address is %d for valid id and %s\n", dataAddr, mm);
            passed = false;
        }

        // get data for invalid id == 0
        dataAddr = wb.imageGetDataAddress(0);
        if (dataAddr == 0) {
            System.out.println("Passed. Data address is zero for zero id");
        } else {
            System.out.printf("Failed. Data address is %d for zero id\n", dataAddr);
            passed = false;
        }

        wb.imageCloseImage(id);
        return passed;
    }
}
