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

/**
 * @test
 * @library /lib/testlibrary
 * @build ListModsTest jdk.testlibrary.*
 * @run testng ListModsTest
 * @summary Basic test for java -listmods
 */

import static jdk.testlibrary.ProcessTools.*;
import jdk.testlibrary.OutputAnalyzer;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

/**
 * Basic tests for java -listmods
 */

public class ListModsTest {

    private static String TEST_CLASSES = System.getProperty("test.classes");

    public static void main(String[] args) {
        int ret = Integer.parseInt(args[0]);
        System.exit(ret);
    }


    @Test
    public void testListAll() throws Exception {
        OutputAnalyzer output
            = executeTestJava("-listmods")
                .outputTo(System.out)
                .errorTo(System.out);
        output.shouldContain("java.base");
        assertTrue(output.getExitValue() == 0);
    }


    @Test
    public void testListOne() throws Exception {
        OutputAnalyzer output
            = executeTestJava("-listmods:java.base")
                .outputTo(System.out)
                .errorTo(System.out);
        output.shouldContain("java.base");
        output.shouldContain("exports java.lang");
        assertTrue(output.getExitValue() == 0);
    }


    @Test
    public void testListMany() throws Exception {
        OutputAnalyzer output
            = executeTestJava("-listmods:java.base,java.xml")
                .outputTo(System.out)
                .errorTo(System.out);
        output.shouldContain("java.base");
        output.shouldContain("exports java.lang");
        output.shouldContain("java.xml");
        output.shouldContain("exports javax.xml");
        assertTrue(output.getExitValue() == 0);
    }


    @Test
    public void testListUnknownModule() throws Exception {
        OutputAnalyzer output
            = executeTestJava("-listmods:java.rhubarb")
                .outputTo(System.out)
                .errorTo(System.out);
        output.shouldNotContain("java.rhubarb");
        assertTrue(output.getExitValue() == 0);
    }


    @Test
    public void testWithMainClass() throws Exception {
        OutputAnalyzer output
            = executeTestJava("-listmods",
                              "-cp", TEST_CLASSES, "ListModsTest", "0")
                .outputTo(System.out)
                .errorTo(System.out);
        output.shouldContain("java.base");
        assertTrue(output.getExitValue() == 0);
    }


    @Test
    public void testWithFailingClass() throws Exception {
        OutputAnalyzer output
            = executeTestJava("-listmods",
                              "-cp", TEST_CLASSES, "ListModsTest", "1")
                .outputTo(System.out)
                .errorTo(System.out);
        output.shouldContain("java.base");
        assertTrue(output.getExitValue() == 1);
    }

}
