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

import java.nio.file.Path;
import java.nio.file.Paths;

import static jdk.testlibrary.ProcessTools.*;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import static org.testng.Assert.assertTrue;

/**
 * @test
 * @bug 8087335
 * @summary Tests for Class.forName(Module,String)
 * @library /lib/testlibrary /jdk/jigsaw/lib
 * @build TestDriver CompilerUtils jdk.testlibrary.ProcessTools TestMain
 * @run testng TestDriver
 */

public class TestDriver {

    private static final String TEST_SRC =
            Paths.get(System.getProperty("test.src")).toString();
    private static final String TEST_CLASSES =
            Paths.get(System.getProperty("test.classes")).toString();

    private static final Path MOD_SRC_DIR = Paths.get(TEST_SRC, "src");
    private static final Path MOD_DEST_DIR = Paths.get("mods");

    private static final String[] modules = new String[] {"m1", "m2"};

    /**
     * Compiles all modules used by the test.
     */
    @BeforeClass
    public void setup() throws Exception {
        assertTrue(CompilerUtils.compile(
                        MOD_SRC_DIR, MOD_DEST_DIR,
                        "-modulesourcepath",
                        MOD_SRC_DIR.toString()));
    }

    @Test
    public void test() throws Exception {
        assertTrue(executeTestJava(
                        "-XaddExports:m1/p1=ALL-UNNAMED",
                        "-cp", TEST_CLASSES,
                        "-mp", MOD_DEST_DIR.toString(),
                        "-addmods", String.join(",", modules),
                        "-m", "m2/p2.test.Main")
                        .outputTo(System.out)
                        .errorTo(System.err)
                        .getExitValue() == 0);
    }

    @Test
    public void testUnnamedModule() throws Exception {
        assertTrue(executeTestJava(
                        "-XaddExports:m1/p1=ALL-UNNAMED",
                        "-cp", TEST_CLASSES,
                        "-mp", MOD_DEST_DIR.toString(),
                        "-addmods", String.join(",", modules),
                        "TestMain")
                        .outputTo(System.out)
                        .errorTo(System.err)
                        .getExitValue() == 0);
    }
}
