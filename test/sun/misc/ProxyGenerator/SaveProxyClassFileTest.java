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

/* @test
 * @bug 8059563
 * @summary ProxyGenerator should create intermediate directories
 *          for the generated class file
 * @build SaveProxyClassFileTest
 * @run main/othervm -Dsun.misc.ProxyGenerator.saveGeneratedFiles=true SaveProxyClassFileTest
 */

import java.io.File;
import sun.misc.ProxyGenerator;

public class SaveProxyClassFileTest {

    static final File dir1 = new File("a");
    static final File dir2 = new File(dir1, "b");
    static final File cf = new File(dir2, "c.class");

    public static void main(String[] args) throws Throwable {
        // remove the files in case they were left from
        // the previous run
        deleteFiles();

        try {
            ProxyGenerator.generateProxyClass("a.b.c",
                    new Class[] {Inf.class});

            if (!cf.exists()) {
                throw new RuntimeException(cf + " wasn't created");
            }
        } finally {
            deleteFiles();
        }
    }

    static interface Inf {
    }

    /**
     * Removes generated file and directories
     */
    private static void deleteFiles() {
        cf.delete();
        dir2.delete();
        dir1.delete();
    }
}
