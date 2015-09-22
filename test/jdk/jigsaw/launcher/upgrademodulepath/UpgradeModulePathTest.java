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
 * @build UpgradeModulePathTest CompilerUtils jdk.testlibrary.*
 * @run testng UpgradeModulePathTest
 * @summary Basic test for java -upgrademodulepath
 */

import java.nio.file.Path;
import java.nio.file.Paths;

import static jdk.testlibrary.ProcessTools.executeTestJava;

import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;
import static org.testng.Assert.*;


@Test
public class UpgradeModulePathTest {

    private static final String TEST_SRC = System.getProperty("test.src");
    private static final Path SRC_DIR = Paths.get(TEST_SRC, "src");
    private static final Path MODS_DIR = Paths.get("mods");

    // the module that is upgraded
    private static final String UPGRADED_MODULE = "java.annotations.common";
    private static final Path UPGRADEDMODS_DIR = Paths.get("upgradedmods");

    // the test module
    private static final String TEST_MODULE = "test";
    private static final String MAIN_CLASS = "jdk.test.Main";


    @BeforeTest
    public void compileModules() throws Exception {

        // javac -d upgradedmods/$UPGRADED_MODULE src/$UPGRADED_MODULE/**
        boolean compiled = CompilerUtils.compile(
            SRC_DIR.resolve(UPGRADED_MODULE),
            UPGRADEDMODS_DIR.resolve(UPGRADED_MODULE)
        );
        assertTrue(compiled, UPGRADED_MODULE + " did not compile");

        // javac -d mods/test -upgradedmodulepath upgradedmods ...
        compiled = CompilerUtils.compile(
            SRC_DIR.resolve(TEST_MODULE),
            MODS_DIR.resolve(TEST_MODULE),
            "-upgrademodulepath", UPGRADEDMODS_DIR.toString()
        );
        assertTrue(compiled, UPGRADED_MODULE + " did not compile");
    }

    /**
     * Run the test with an upgraded java.annotations.common module.
     */
    public void testWithUpgradedModule() throws Exception {

        String mid = TEST_MODULE + "/" + MAIN_CLASS;
        int exitValue
            = executeTestJava(
                "-upgrademodulepath", UPGRADEDMODS_DIR.toString(),
                "-mp", MODS_DIR.toString(),
                "-m", mid)
            .outputTo(System.out)
            .errorTo(System.out)
            .getExitValue();

        assertTrue(exitValue == 0);
    }

}
