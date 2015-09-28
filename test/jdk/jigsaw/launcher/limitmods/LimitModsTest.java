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
 * @build LimitModsTest CompilerUtils jdk.testlibrary.*
 * @run testng LimitModsTest
 * @summary Basic tests for java -limitmods
 */

import java.nio.file.Path;
import java.nio.file.Paths;

import static jdk.testlibrary.ProcessTools.*;

import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;
import static org.testng.Assert.*;


@Test
public class LimitModsTest {

    private static final String TEST_SRC = System.getProperty("test.src");

    private static final Path SRC_DIR = Paths.get(TEST_SRC, "src");
    private static final Path MODS_DIR = Paths.get("mods");

    // the module name of the test module
    private static final String TEST_MODULE = "test";

    // the module main class
    private static final String MAIN_CLASS = "jdk.test.UseAWT";


    @BeforeTest
    public void compileTestModule() throws Exception {

        // javac -d mods/$TESTMODULE src/$TESTMODULE/**
        boolean compiled
            = CompilerUtils.compile(SRC_DIR.resolve(TEST_MODULE),
                                    MODS_DIR.resolve(TEST_MODULE));

        assertTrue(compiled, "test module did not compile");
    }

    /**
     * Run class path application with -limitmods
     */
    public void testUnnamedModule() throws Exception {
        String classpath = MODS_DIR.resolve(TEST_MODULE).toString();

        // java -limitmods java.base -cp mods/$TESTMODULE ...
        int exitValue1
            = executeTestJava("-limitmods", "java.base",
                              "-cp", classpath,
                              MAIN_CLASS)
                .outputTo(System.out)
                .errorTo(System.out)
                .shouldContain("NoClassDefFoundError")
                .getExitValue();

        assertTrue(exitValue1 != 0);


        // java -limitmods java.base -cp mods/$TESTMODULE ...
        int exitValue2
            = executeTestJava("-limitmods", "java.desktop",
                              "-cp", classpath,
                             MAIN_CLASS)
                .outputTo(System.out)
                .errorTo(System.out)
                .getExitValue();

        assertTrue(exitValue2 == 0);
    }

    /**
     * Run named module with -limitmods
     */
    public void testNamedModule() throws Exception {
        String modulepath = MODS_DIR.toString();
        String mid = TEST_MODULE + "/" + MAIN_CLASS;

        // java -limitmods java.base -mp mods -m $TESTMODULE/$MAINCLASS
        int exitValue
            = executeTestJava("-limitmods", "java.base",
                              "-mp", modulepath,
                              "-m", mid)
                .outputTo(System.out)
                .errorTo(System.out)
                .getExitValue();

        assertTrue(exitValue == 0);
    }

}
