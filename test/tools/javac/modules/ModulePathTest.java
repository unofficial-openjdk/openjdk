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

/*
 * @test
 * @summary tests for -modulepath
 * @library /tools/lib
 * @modules
 *      jdk.compiler/com.sun.tools.javac.api
 *      jdk.compiler/com.sun.tools.javac.main
 * @build ToolBox ModuleTestBase
 * @run main ModulePathTest
 */

import java.nio.file.Path;

public class ModulePathTest extends ModuleTestBase {

    public static void main(String... args) throws Exception {
        ModulePathTest t = new ModulePathTest();
        t.runTests();
    }

    @Test
    void testNotExistsOnPath(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src, "class C { }");

        String log = tb.new JavacTask(ToolBox.Mode.CMDLINE)
                .options("-XDrawDiagnostics",
                        "-modulepath", "doesNotExist")
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.FAIL)
                .writeAll()
                .getOutput(ToolBox.OutputKind.DIRECT);

        if (!log.contains("- compiler.err.illegal.argument.for.option: -modulepath, doesNotExist"))
            throw new Exception("expected output not found");
    }

    @Test
    void testNotADirOnPath_1(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src, "class C { }");
        tb.writeFile("dummy.jar", "");

        String log = tb.new JavacTask(ToolBox.Mode.CMDLINE)
                .options("-XDrawDiagnostics",
                        "-modulepath", "dummy.jar")
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.FAIL)
                .writeAll()
                .getOutput(ToolBox.OutputKind.DIRECT);

        if (!log.contains("- compiler.err.illegal.argument.for.option: -modulepath, dummy.jar"))
            throw new Exception("expected output not found");
    }

    @Test
    void testNotADirOnPath_2(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src, "class C { }");
        tb.writeFile("dummy.txt", "");

        String log = tb.new JavacTask(ToolBox.Mode.CMDLINE)
                .options("-XDrawDiagnostics",
                        "-modulepath", "dummy.txt")
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.FAIL)
                .writeAll()
                .getOutput(ToolBox.OutputKind.DIRECT);

        if (!log.contains("- compiler.err.illegal.argument.for.option: -modulepath, dummy.txt"))
            throw new Exception("expected output not found");
    }
}
