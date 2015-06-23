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
 * @test ImageGetStringBytesTest
 * @summary Unit test for JVM_ImageGetStringBytes() method
 * @author sergei.pikalev@oracle.com
 * @library /testlibrary /../../test/lib
 * @build LocationConstants ImageGetStringBytesTest
 * @run main ClassFileInstaller sun.hotspot.WhiteBox
 *                              sun.hotspot.WhiteBox$WhiteBoxPermission
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI ImageGetStringBytesTest
 */

import java.io.File;
import java.nio.ByteOrder;
import sun.hotspot.WhiteBox;

public class ImageGetStringBytesTest implements LocationConstants {

    public static final WhiteBox wb = WhiteBox.getWhiteBox();

    public static void main(String... args) throws Exception {
        String javaHome = System.getProperty("java.home");
        String imageFile = javaHome + "/lib/modules/bootmodules.jimage";

        if (!(new File(imageFile)).exists()) {
            System.out.printf("Test skipped.");
            return;
        }

        if (!testImageGetStringBytes(imageFile))
            throw new RuntimeException("Some cases are failed");
    }

    private static boolean testImageGetStringBytes(String imageFile) {
        boolean bigEndian = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
        long id = wb.imageOpenImage(imageFile, bigEndian);
        boolean passed = true;

        String className = "/java.base/java/lang/String.class";
        long[] offsetArr = wb.imageFindAttributes(id, className.getBytes());

        // Module
        passed = checkAttribute(id, offsetArr, LOCATION_ATTRIBUTE_MODULE, "Module");

        // Parent
        passed = checkAttribute(id, offsetArr, LOCATION_ATTRIBUTE_PARENT, "Parent");

        // Base
        passed = checkAttribute(id, offsetArr, LOCATION_ATTRIBUTE_BASE, "Base");

        // Extension
        passed = checkAttribute(id, offsetArr, LOCATION_ATTRIBUTE_EXTENSION, "Extension");

        wb.imageCloseImage(id);
        return passed;
    }

    private static boolean checkAttribute(long id, long[] offsetArr, int attrId, String attrName) {
        long offset = offsetArr[attrId];
        byte[] buf = wb.imageGetStringBytes(id, (int)offset);
        boolean passed = true;

        if (buf != null) {
            System.out.printf("Passed. Retieved %s string: %s\n", attrName, new String(buf));
        } else {
            System.out.printf("Failed. Could not retrieve %s string. Offset = %d\n", offset);
            passed = false;
        }

        return passed;
    }
}
