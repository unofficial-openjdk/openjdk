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
 * @summary simple tests of module provides
 * @library /tools/lib
 * @modules
 *      jdk.compiler/com.sun.tools.javac.api
 *      jdk.compiler/com.sun.tools.javac.main
 * @build ToolBox ModuleTestBase
 * @run main ProvidesTest
 */

import java.nio.file.*;

public class ProvidesTest extends ModuleTestBase {
    public static void main(String... args) throws Exception {
        ProvidesTest t = new ProvidesTest();
        t.runTests();
    }

    @Test
    void testSimple(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { provides p1.C1 with p2.C2; }",
                "package p1; public class C1 { }",
                "package p2; public class C2 extends p1.C1 { }");
        Path classes = base.resolve("classes");
        Files.createDirectories(classes);

        tb.new JavacTask()
                .outdir(classes.toString())
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.SUCCESS)
                .writeAll();
    }

    @Test
    void testMulti(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src.resolve("m1"),
                "module m1 { exports p1; }",
                "package p1; public class C1 { }");
        tb.writeJavaFiles(src.resolve("m2"),
                "module m2 { requires m1; provides p1.C1 with p2.C2; }",
                "package p2; public class C2 extends p1.C1 { }");
        Path modules = base.resolve("modules");
        Files.createDirectories(modules);

        tb.new JavacTask()
                .options("-modulesourcepath", src.toString())
                .outdir(modules.toString())
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.SUCCESS)
                .writeAll();

    }

    @Test
    void testMissingWith(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { provides p.C; }",
                "package p; public class C { }");
        Path classes = base.resolve("classes");
        Files.createDirectories(classes);

        String log = tb.new JavacTask()
                .options("-XDrawDiagnostics")
                .outdir(classes.toString())
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.FAIL)
                .writeAll()
                .getOutput(ToolBox.OutputKind.DIRECT);

        if (!log.contains("module-info.java:1:24: compiler.err.expected: 'with'"))
            throw new Exception("expected output not found");

    }
}