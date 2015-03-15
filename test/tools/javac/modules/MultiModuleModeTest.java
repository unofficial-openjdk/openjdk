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
 * @library /tools/lib
 * @modules
 *      jdk.compiler/com.sun.tools.javac.api
 *      jdk.compiler/com.sun.tools.javac.main
 * @build ToolBox ModuleTestBase
 * @run main MultiModuleModeTest
 */

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MultiModuleModeTest extends ModuleTestBase {

    public static void main(String... args) throws Exception {
        new MultiModuleModeTest().runTests();
    }

    @Test
    void testDuplicateModules(Path base) throws Exception {
        Path src = base.resolve("src");
        Path src_m1 = src.resolve("m1");
        tb.writeJavaFiles(src_m1, "module m1 { }");
        Path src_m2 = src.resolve("m2");
        tb.writeJavaFiles(src_m2, "module m1 { }");
        Path classes = base.resolve("classes");
        tb.createDirectories(classes);

        String log = tb.new JavacTask()
                .options("-XDrawDiagnostics",
                        "-modulesourcepath", src.toString())
                .outdir(classes.toString()) // should allow Path here
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.FAIL)
                .writeAll()
                .getOutput(ToolBox.OutputKind.DIRECT);

        if (!log.contains("module-info.java:1:1: compiler.err.duplicate.module: m1"))
            throw new Exception("expected output not found");
    }

    @Test
    void testCantFindModule(Path base) throws Exception {
        Path src = base.resolve("src");
        Path src_m1 = src.resolve("m1");
        tb.writeJavaFiles(src_m1, "module m1 { }");
        Path misc = base.resolve("misc");
        tb.writeJavaFiles(misc, "package p; class C { }");
        Path classes = base.resolve("classes");
        tb.createDirectories(classes);

        String log = tb.new JavacTask()
                .options("-XDrawDiagnostics",
                        "-modulesourcepath", src.toString())
                .outdir(classes.toString()) // should allow Path here
                .files(join(findJavaFiles(src), findJavaFiles(misc)))
                .run(ToolBox.Expect.FAIL)
                .writeAll()
                .getOutput(ToolBox.OutputKind.DIRECT);

        if (!log.contains("C.java:1:1: compiler.err.cant.determine.module"))
            throw new Exception("expected output not found");
    }

    Path[] join(Path[] a, Path[] b) {
        List<Path> result = new ArrayList<>();
        result.addAll(Arrays.asList(a));
        result.addAll(Arrays.asList(b));
        return result.toArray(new Path[result.size()]);
    }
}
