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
 * @test ImageGetAttributesTest
 * @summary Unit test for JVM_ImageGetAttributes() method
 * @author sergei.pikalev@oracle.com
 * @library /testlibrary /../../test/lib
 * @build LocationConstants ImageGetAttributesTest
 * @run main ClassFileInstaller sun.hotspot.WhiteBox
 *                              sun.hotspot.WhiteBox$WhiteBoxPermission
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI ImageGetAttributesTest
 */

import java.io.File;
import java.nio.ByteOrder;
import sun.hotspot.WhiteBox;

public class ImageGetAttributesTest implements LocationConstants {

    public static final WhiteBox wb = WhiteBox.getWhiteBox();

    public static void main(String... args) throws Exception {
        String javaHome = System.getProperty("java.home");
        String imageFile = javaHome + "/lib/modules/bootmodules.jimage";

        if (!(new File(imageFile)).exists()) {
            return;
        }

        if (!testImageGetAttributes(imageFile))
            throw new RuntimeException("Some cases are failed");
    }

    private static boolean testImageGetAttributes(String imageFile) {
        boolean passed = true;
        boolean bigEndian = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
        long id = wb.imageOpenImage(imageFile, bigEndian);
        long stringsSize = wb.imageGetStringsSize(id);
        if (stringsSize == 0) {
            System.out.println("strings size is 0");
            wb.imageCloseImage(id);
            return false;
        }
        int[] array = wb.imageAttributeOffsets(id);
        if (array == null) {
            System.out.println("Failed. Offsets\' array is NULL");
            wb.imageCloseImage(id);
            return false;
        }

        // Get non-null attributes
        boolean attFound = false;
        int[] idx = { -1, -1, -1 };
        // first non-null attribute
        for (int i = 0; i < array.length; i++) {
            if (array[i] != 0) {
                attFound = true;
                idx[0] = i;
                break;
            }
        }

        // middle non-null attribute
        for (int i = array.length/2; i < array.length; i++) {
            if (array[i] != 0) {
                attFound = true;
                idx[1] = i;
                break;
            }
        }

        // last non-null attribute
        for (int i = array.length - 1; i >= 0; i--) {
            if (array[i] != 0) {
                attFound = true;
                idx[2] = i;
                break;
            }
        }

        if (attFound == false) {
            System.out.println("Failed. No non-null offset attributes");
            passed = false;
        } else {
            // test cases above
            for (int i = 0; i < 3; i++) {
                if (idx[i] != -1) {
                    long[] attrs = wb.imageGetAttributes(id, (int)array[idx[i]]);
                    long module = attrs[LOCATION_ATTRIBUTE_MODULE];
                    long parent = attrs[LOCATION_ATTRIBUTE_PARENT];
                    long base = attrs[LOCATION_ATTRIBUTE_BASE];
                    long ext = attrs[LOCATION_ATTRIBUTE_EXTENSION];

                    if ((module >=0) && (module < stringsSize) &&
                        (parent >=0) && (parent < stringsSize) &&
                        (base != 0) &&
                        (ext >=0) && (ext < stringsSize)) {
                        System.out.printf("Passed. Read attribute offset %d (position %d)\n",
                                array[idx[i]], idx[i]);
                        System.out.printf("    offsets: module = %d parent = %d base = %d extention = %d\n",
                                module, parent, base, ext);
                    } else {
                        System.out.printf("Failed. Read attribute offset %d (position %d) but wrong offsets\n",
                                array[idx[i]], idx[i]);
                        System.out.printf("    offsets: module = %d parent = %d base = %d extention = %d\n",
                                module, parent, base, ext);
                        passed = false;
                    }
                } else {
                    System.out.printf("Failed. Could not read attribute offset %d (position %d)\n",
                            array[idx[i]], idx[i]);
                    passed = false;
                }
            }
        }

        wb.imageCloseImage(id);
        return passed;
    }
}
