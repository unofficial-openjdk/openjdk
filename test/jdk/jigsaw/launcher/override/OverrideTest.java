/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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

/**
 * @test
 * @library ../../lib /lib/testlibrary
 * @build OverrideTest CompilerUtils jdk.testlibrary.*
 * @run testng OverrideTest
 * @summary Basic test for -Xoverride
 */

import java.nio.file.Path;
import java.nio.file.Paths;

import static jdk.testlibrary.ProcessTools.*;

import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class OverrideTest {

    private static final String TEST_SRC = System.getProperty("test.src");

    private static final Path SRC_DIR = Paths.get(TEST_SRC, "src");
    private static final Path PATCHES_DIR = Paths.get("patches");
    private static final Path MODS_DIR = Paths.get("mods");

    // the test module and main-class
    private static final String TEST_MODULE = "test";
    private static final String MAIN_CLASS = "jdk.test.Main";

    // the modules with classes to compile
    private static final String[] PATCHED_MODULES
        = { "java.base", "jdk.compiler", "jdk.naming.dns" };


    @BeforeTest
    public void compileClasses() throws Exception {

        // compiled the patched classes
        for (String module : PATCHED_MODULES) {
            boolean compiled
                = CompilerUtils.compile(SRC_DIR.resolve(module),
                                        PATCHES_DIR.resolve(module));
            assertTrue(compiled, "classes did not compile");
        }

        // compile the test that uses the patched modules
        boolean compiled
            = CompilerUtils.compile(SRC_DIR.resolve(TEST_MODULE),
                                    MODS_DIR.resolve(TEST_MODULE));
        assertTrue(compiled, "classes did not compile");
    }

    /**
     * Run test with -Xoverride
     */
    public void testOverrde() throws Exception {

        int exitValue
            =  executeTestJava("-XaddExports:jdk.naming.dns/com.sun.jndi.dns=test",
                               "-mp", MODS_DIR.toString(),
                               "-Xoverride:" + PATCHES_DIR,
                               "-m", TEST_MODULE + "/" + MAIN_CLASS)
                .outputTo(System.out)
                .errorTo(System.err)
                .getExitValue();

        assertTrue(exitValue == 0);
    }

}
