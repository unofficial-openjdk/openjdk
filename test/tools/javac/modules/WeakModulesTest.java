/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
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
 * @summary tests for multi-module mode compilation
 * @library /tools/lib
 * @modules jdk.compiler/com.sun.tools.javac.api
 *          jdk.compiler/com.sun.tools.javac.main
 *          jdk.jdeps/com.sun.tools.javap
 * @build toolbox.ToolBox toolbox.JavacTask toolbox.ModuleBuilder ModuleTestBase
 * @run main WeakModulesTest
 */

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import toolbox.JavacTask;
import toolbox.JavapTask;
import toolbox.Task;
import toolbox.Task.Expect;
import toolbox.Task.OutputKind;

public class WeakModulesTest extends ModuleTestBase {

    public static void main(String... args) throws Exception {
        new WeakModulesTest().runTests();
    }

    @Test
    public void testWeakModule(Path base) throws Exception {
        Path m1 = base.resolve("m1");
        tb.writeJavaFiles(m1,
                          "weak module m1 { }",
                          "package api1; public class Api1 {}",
                          "package api2; public class Api2 {}");
        Path classes = base.resolve("classes");
        Path m1Classes = classes.resolve("m1");
        tb.createDirectories(m1Classes);

        String log = new JavacTask(tb)
                .outdir(m1Classes)
                .files(findJavaFiles(m1))
                .run()
                .writeAll()
                .getOutput(Task.OutputKind.DIRECT);

        if (!log.isEmpty())
            throw new Exception("expected output not found: " + log);

        String decompiled = new JavapTask(tb)
                .options("--system", "none", "-bootclasspath", "")
                .classpath(m1Classes.toString())
                .classes("module-info")
                .run()
                .writeAll()
                .getOutput(OutputKind.DIRECT)
                .replace(System.getProperty("line.separator"), "\n");

        String expected = "weak module m1 {\n" +
                          "  requires java.base;\n" +
                          "}";

        if (!decompiled.contains(expected)) {
            throw new Exception("expected output not found: " + decompiled);
        }

        //compiling against a weak module read from binary:
        Path m2 = base.resolve("m2");
        tb.writeJavaFiles(m2,
                          "module m2 { requires m1; }",
                          "package test; public class Test { api1.Api1 a1; api2.Api2 a2; }");
        Path m2Classes = classes.resolve("m2");
        tb.createDirectories(m2Classes);

        String log2 = new JavacTask(tb)
                .options("--module-path", m1Classes.toString())
                .outdir(m2Classes)
                .files(findJavaFiles(m2))
                .run()
                .writeAll()
                .getOutput(Task.OutputKind.DIRECT);

        if (!log2.isEmpty())
            throw new Exception("expected output not found: " + log2);
    }

    @Test
    public void testWeakModuleOnModuleSourcePath(Path base) throws Exception {
        Path src = base.resolve("src");
        Path m1 = src.resolve("m1");
        tb.writeJavaFiles(m1,
                          "weak module m1 { }",
                          "package api1; public class Api1 {}",
                          "package api2; public class Api2 {}");
        Path m2 = base.resolve("m2");
        tb.writeJavaFiles(m2,
                          "module m2 { requires m1; }",
                          "package test; public class Test { api1.Api1 a1; api2.Api2 a2; }");
        Path classes = base.resolve("classes");
        tb.createDirectories(classes);

        String log = new JavacTask(tb)
                .options("--module-source-path", src.toString())
                .outdir(classes)
                .files(findJavaFiles(src))
                .run()
                .writeAll()
                .getOutput(Task.OutputKind.DIRECT);

        if (!log.isEmpty())
            throw new Exception("expected output not found: " + log);
    }

    @Test
    public void testNoExportsInWeakModules(Path base) throws Exception {
        Path src = base.resolve("src");
        Path m1 = src.resolve("m1");
        tb.writeJavaFiles(m1,
                          "weak module m1 { exports api1; }",
                          "package api1; public class Api1 {}");
        Path classes = base.resolve("classes");
        tb.createDirectories(classes);

        List<String> log = new JavacTask(tb)
                .options("--module-source-path", src.toString(),
                         "-XDrawDiagnostics")
                .outdir(classes)
                .files(findJavaFiles(src))
                .run(Expect.FAIL)
                .writeAll()
                .getOutputLines(Task.OutputKind.DIRECT);

        List<String> expected = Arrays.asList(
                "module-info.java:1:18: compiler.err.no.exports.in.weak",
                "1 error"
        );
        if (!expected.equals(log))
            throw new Exception("expected output not found: " + log);
    }

}
