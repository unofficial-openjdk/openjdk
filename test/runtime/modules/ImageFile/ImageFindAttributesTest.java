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
 * @test ImageFindAttributesTest
 * @summary Unit test for JVM_ImageFindAttributes() method
 * @author sergei.pikalev@oracle.com
 * @library /testlibrary /../../test/lib
 * @build ImageFindAttributesTest
 * @run main ClassFileInstaller sun.hotspot.WhiteBox
 *                              sun.hotspot.WhiteBox$WhiteBoxPermission
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI ImageFindAttributesTest
 */

import java.io.File;
import java.nio.ByteOrder;
import sun.hotspot.WhiteBox;

public class ImageFindAttributesTest {

    public static final WhiteBox wb = WhiteBox.getWhiteBox();

    public static void main(String... args) throws Exception {
        String javaHome = System.getProperty("java.home");
        String imageFile = javaHome + "/lib/modules/bootmodules.jimage";

        if (!(new File(imageFile)).exists()) {
            System.out.printf("Test skipped.");
            return;
        }

        if (!testImageFindAttributes(imageFile))
            throw new RuntimeException("Some cases are failed");
    }

    public static boolean testImageFindAttributes(String imageFile) {
        boolean bigEndian = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
        long id = wb.imageOpenImage(imageFile, bigEndian);
        boolean passed = true;

        // class resource
        String className = "/java.base/java/lang/String.class";
        long[] longArr = wb.imageFindAttributes(id, className.getBytes());
        if (longArr != null) {
            System.out.printf("Passed. Found attributes for %s\n", className);
        } else {
            System.out.printf("Failed. Did not find attributes for %s\n", className);
            passed = false;
        }

        // non-existent resource
        String neClassName = "/java.base/java/lang/NonExistentClass.class";
        longArr = wb.imageFindAttributes(id, neClassName.getBytes());

        if (longArr == null) {
            System.out.printf("Passed. Did not found attributes for non-existent %s\n", neClassName);
        } else {
            System.out.printf("Failed. Returned not null for non-existent %s\n", neClassName);
            passed = false;
        }

        // garbage byte array
        byte[] buf = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 };
        longArr = wb.imageFindAttributes(id, buf);
        if (longArr == null) {
            System.out.println("Passed. Did not found attributes for binary byte-array");
        } else {
            System.out.println("Failed. Returned not null for binary byte-array");
            passed = false;
        }

        return passed;
    }
}
