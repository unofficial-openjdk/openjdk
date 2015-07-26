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
 * @build AddExportsTest CompilerUtils jdk.testlibrary.ProcessTools
 * @run testng AddExportsTest
 * @summary Basic tests for java -XaddExports
 */

import java.nio.file.Path;
import java.nio.file.Paths;

import static jdk.testlibrary.ProcessTools.*;

import org.testng.annotations.BeforeTest;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;


@Test
public class AddExportsTest {

    private static final String TEST_SRC = System.getProperty("test.src");

    private static final Path SRC_DIR = Paths.get(TEST_SRC, "src");
    private static final Path MODS_DIR = Paths.get("mods");

    // the module name of the test module
    private static final String TEST_MODULE = "test";

    // the module main class
    private static final String MAIN_CLASS = "jdk.test.UsesUnsafe";


    @BeforeTest
    public void compileTestModule() throws Exception {

        // javac -d mods/$TESTMODULE src/$TESTMODULE/**
        boolean compiled
            = CompilerUtils.compile(SRC_DIR.resolve(TEST_MODULE),
                                    MODS_DIR.resolve(TEST_MODULE),
                                    "-XaddExports:java.base/sun.misc=test");

        assertTrue(compiled, "test module did not compile");
    }


    /**
     * Run class path application that uses sun.misc.Unsafe
     */
    public void testUnnamedModule() throws Exception {

        // java -XaddExports:java.base/sun.misc=ALL-UNNAMED \
        //      -cp mods/$TESTMODULE jdk.test.UsesUnsafe

        String classpath = MODS_DIR.resolve(TEST_MODULE).toString();
        int exitValue
            = executeTestJava("-XaddExports:java.base/sun.misc=ALL-UNNAMED",
                              "-cp", classpath,
                              MAIN_CLASS)
                .getExitValue();

        assertTrue(exitValue == 0);
    }


    /**
     * Run named module that uses sun.misc.Unsafe
     */
    public void testNamedModule() throws Exception {

        //  java -XaddExports:java.base/sun.misc=test \
        //       -mp mods -m $TESTMODULE/$MAIN_CLASS

        String mid = TEST_MODULE + "/" + MAIN_CLASS;
        int exitValue =
            executeTestJava("-XaddExports:java.base/sun.misc=" + TEST_MODULE,
                            "-mp", MODS_DIR.toString(),
                            "-m", mid)
                .getExitValue();

        assertTrue(exitValue == 0);
    }


    /**
     * Exercise -XaddExports with bad values
     */
    @Test(dataProvider = "badvalues")
    public void testWithBadValue(String value, String ignore) throws Exception {

        //  -XaddExports:$VALUE -cp mods/test jdk.test.UsesUnsafe
        String classpath = MODS_DIR.resolve(TEST_MODULE).toString();
        int exitValue =
            executeTestJava("-XaddExports:" + value,
                            "-cp", classpath,
                            MAIN_CLASS)
                .getExitValue();

        assertTrue(exitValue != 0);
    }

    @DataProvider(name = "badvalues")
    public Object[][] badValues() {
        return new Object[][]{

            { "java.base/sun.misc,java.monkey/sun.monkey", null }, // unknown module
            { "java.base/sun.misc,java.base/sun.monkey",   null }, // unknown package
            { "java.base/sun.misc,java.base",              null }  // missing package

        };
    }
}