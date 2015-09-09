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
 * @summary Check.compiled
 * @library /tools/lib
 * @modules
 *      jdk.compiler/com.sun.tools.javac.api
 *      jdk.compiler/com.sun.tools.javac.main
 * @build ToolBox ModuleTestBase
 * @run main DuplicateClassTest
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class DuplicateClassTest extends ModuleTestBase {

    public static void main(String... args) throws Exception {
        DuplicateClassTest t = new DuplicateClassTest();
        t.runTests();
    }

    @Test
    void testSimple(Path base) throws Exception {
        Path m1 = base.resolve("m1");
        Path m2 = base.resolve("m2");
        tb.writeJavaFiles(m1,
                          "module m1 { }",
                          "package impl; public class Impl {}");
        tb.writeJavaFiles(m2,
                          "module m2 { }",
                          "package impl; public class Impl {}");
        Path classes = base.resolve("classes");
        Files.createDirectories(classes);

        List<String> log = tb.new JavacTask()
                .options("-XDrawDiagnostics", "-modulesourcepath", base.toString())
                .outdir(classes)
                .files(findJavaFiles(base))
                .run()
                .writeAll()
                .getOutputLines(ToolBox.OutputKind.DIRECT);

        List<String> expected = Arrays.asList("Impl.java:1:1: compiler.warn.package.in.other.module.1: m1",
                                              "1 warning");
        if (!log.equals(expected))
            throw new Exception("expected output not found");
    }

}
