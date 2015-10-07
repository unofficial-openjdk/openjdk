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
 * @summary simple tests of module uses
 * @library /tools/lib
 * @modules
 *      jdk.compiler/com.sun.tools.javac.api
 *      jdk.compiler/com.sun.tools.javac.main
 * @build ToolBox ModuleTestBase
 * @run main UsesTest
 */

import java.nio.file.*;

public class UsesTest extends ModuleTestBase {
    public static void main(String... args) throws Exception {
        UsesTest t = new UsesTest();
        t.runTests();
    }

    @Test
    void testSimple(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { uses p.C; }",
                "package p; public class C { }");
        Path classes = base.resolve("classes");
        Files.createDirectories(classes);

        tb.new JavacTask()
                .outdir(classes)
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.SUCCESS)
                .writeAll();
    }

    @Test
    void testMulti(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src.resolve("m1"),
                "module m1 { exports p; }",
                "package p; public class C { }");
        tb.writeJavaFiles(src.resolve("m2"),
                "module m2 { requires m1; uses p.C; }");
        Path modules = base.resolve("modules");
        Files.createDirectories(modules);

        tb.new JavacTask()
                .options("-modulesourcepath", src.toString())
                .outdir(modules)
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.SUCCESS)
                .writeAll();

    }
}