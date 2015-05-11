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
 * @test ImageReadTest
 * @summary Unit test for JVM_ImageRead() method
 * @author sergei.pikalev@oracle.com
 * @library /testlibrary /../../test/lib
 * @build LocationConstants ImageReadTest
 * @run main ClassFileInstaller sun.hotspot.WhiteBox
 *                              sun.hotspot.WhiteBox$WhiteBoxPermission
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -XX:+MemoryMapImage ImageReadTest +
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -XX:-MemoryMapImage ImageReadTest -
 */

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import sun.hotspot.WhiteBox;

public class ImageReadTest implements LocationConstants {

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

        if (!testImageRead(imageFile, isMMap))
            throw new RuntimeException("Some cases are failed");
    }

    private static int htonl(int value, boolean bigEndian) {
        return bigEndian? value : Integer.reverseBytes(value);
    }

    private static boolean testImageRead(String imageFile, boolean isMMap) {
        boolean bigEndian = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
        boolean passed = true;
        long id = wb.imageOpenImage(imageFile, isMMap);
        final String mm = isMMap? "-XX:+MemoryMapImage" : "-XX:-MemoryMapImage";
        final int magic = htonl(0xCAFEBABE, bigEndian);

        String className = "/java.base/java/lang/String.class";
        long[] offsetArr = wb.imageFindAttributes(id, className.getBytes());
        long offset = offsetArr[LOCATION_ATTRIBUTE_OFFSET];
        long size = offsetArr[LOCATION_ATTRIBUTE_UNCOMPRESSED];

        // positive: read
        ByteBuffer buf = ByteBuffer.allocateDirect((int)size);
        if (wb.imageRead(id, offset, buf, size)) {
            int m = buf.getInt();
            if (m != magic) {
                System.out.println("Passed. Read operation returned true and correct magic");
            } else {
                System.out.printf("Failed. Read operation returned true but wrong magic = %x\n", magic);
                passed = false;
            }
        } else {
            System.out.println("Failed. Read operation returned false, should be true");
            passed = false;
        }

        // positive: mmap
        if (isMMap) {
            long dataAddr = wb.imageGetDataAddress(id);
            if (dataAddr != 0L) {
                int data = wb.imageGetIntAtAddress(dataAddr, (int)offset);
                if (data == magic) {
                    System.out.println("Passed. MMap operation returned true and correct magic");
                } else {
                    System.out.printf("Failed. MMap operation returned true but wrong magic = %x\n", data);
                    passed = false;
                }
            } else {
                System.out.println("Failed. Did not obtain data address on mmapped test");
                passed = false;
            }
        }

        // negative: wrong offset
        boolean success = wb.imageRead(id, -100, buf, size);
        if (success) {
            System.out.println("Failed. Read operation (wrong offset): returned true");
            passed = false;
        } else {
            System.out.println("Passed. Read operation (wrong offset) returned false");
        }

        // negative: too big offset
        long filesize = new File(imageFile).length();
        success = wb.imageRead(id, filesize + 1, buf, size);
        if (!success) {
            System.out.println("Passed. Read operation (offset > file size) returned false");
        } else {
            System.out.println("Failed. Read operation (offset > file size) returned true");
            passed = false;
        }

        // negative: negative size
        success = wb.imageRead(id, offset, buf, -100);
        if (!success) {
            System.out.println("Passed. Read operation (negative size) returned false");
        } else {
            System.out.println("Failed. Read operation (negative size) returned true");
            passed = false;
        }

        wb.imageCloseImage(id);
        return passed;
    }
}
