/*
 * Copyright 2005 Sun Microsystems, Inc.  All Rights Reserved.
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
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

/*
 * @test
 * @bug     6272688
 * @summary Sanity test of Version methods to make sure JDK and JVM
 *          both have the same major, minor, and micro version.
 *          Update version and build number may be different when this
 *          test is run.
 * @author  Mandy Chung
 *
 * @run main VersionCheck
 */

import sun.misc.Version;

public class VersionCheck {
    public static void main(String[] argv) {
        if (Version.jvmMajorVersion() != Version.jdkMajorVersion()) {
            throw new RuntimeException("Mismatched jvmMajorVersion = " +
                Version.jvmMajorVersion() + " jdkMajorVersion = " +
                Version.jdkMajorVersion());
        }
        if (Version.jvmMinorVersion() != Version.jdkMinorVersion()) {
            throw new RuntimeException("Mismatched jvmMinorVersion = " +
                Version.jvmMinorVersion() + " jdkMinorVersion = " +
                Version.jdkMinorVersion());
        }
        if (Version.jvmMicroVersion() != Version.jdkMicroVersion()) {
            throw new RuntimeException("Mismatched jvmMicroVersion = " +
                Version.jvmMicroVersion() + " jdkMicroVersion = " +
                Version.jdkMicroVersion());
        }

        System.out.printf("JVM version is %1$d.%2$d.%3$d_%4$02d%5$s-b%6$02d\n",
             Version.jvmMajorVersion(),
             Version.jvmMinorVersion(),
             Version.jvmMicroVersion(),
             Version.jvmUpdateVersion(),
             Version.jvmSpecialVersion(),
             Version.jvmBuildNumber());
        System.out.printf("JDK version is %1$d.%2$d.%3$d_%4$02d%5$s-b%6$02d\n",
             Version.jdkMajorVersion(),
             Version.jdkMinorVersion(),
             Version.jdkMicroVersion(),
             Version.jdkUpdateVersion(),
             Version.jdkSpecialVersion(),
             Version.jdkBuildNumber());
    }
}
