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
 * @summary tests for multi-module mode compilation
 * @library /tools/lib
 * @modules
 *      jdk.compiler/com.sun.tools.javac.api
 *      jdk.compiler/com.sun.tools.javac.code
 *      jdk.compiler/com.sun.tools.javac.main
 * @build ToolBox ModuleTestBase
 * @run main EdgeCases
 */

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.sun.source.util.JavacTask;
import com.sun.tools.javac.code.Symbol.ModuleSymbol;

public class EdgeCases extends ModuleTestBase {

    public static void main(String... args) throws Exception {
        new EdgeCases().runTests();
    }

    @Test
    void testAddExportUndefinedModule(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src, "package test; import undef.Any; public class Test {}");
        Path classes = base.resolve("classes");
        tb.createDirectories(classes);

        List<String> log = tb.new JavacTask()
                .options("-XaddExports:undef/undef=ALL-UNNAMED", "-XDrawDiagnostics")
                .outdir(classes)
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.FAIL)
                .writeAll()
                .getOutputLines(ToolBox.OutputKind.DIRECT);

        List<String> expected = Arrays.asList("- compiler.err.cant.find.module: undef",
                                              "Test.java:1:27: compiler.err.doesnt.exist: undef",
                                              "2 errors");

        if (!expected.equals(log))
            throw new Exception("expected output not found: " + log);
    }

    @Test
    void testModuleSymbolOutterMostClass(Path base) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            Path moduleSrc = base.resolve("module-src");
            Path m1 = moduleSrc.resolve("m1");

            tb.writeJavaFiles(m1, "module m1 { }");

            Iterable<? extends JavaFileObject> files = fm.getJavaFileObjects(findJavaFiles(moduleSrc));
            JavacTask task = (JavacTask) compiler.getTask(null, fm, null, null, null, files);

            task.analyze();

            ModuleSymbol msym = (ModuleSymbol) task.getElements().getModuleElement("m1");

            msym.outermostClass();
        }
    }

}
