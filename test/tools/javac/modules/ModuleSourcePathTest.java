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
 * @summary tests for -modulesourcepath
 * @library /tools/lib
 * @modules
 *      jdk.compiler/com.sun.tools.javac.api
 *      jdk.compiler/com.sun.tools.javac.main
 * @build ToolBox ModuleTestBase
 * @run main ModuleSourcePathTest
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ModuleSourcePathTest extends ModuleTestBase {

    public static void main(String... args) throws Exception {
        ModuleSourcePathTest t = new ModuleSourcePathTest();
        t.runTests();
    }

    @Test
    void testUnnormalizedPath1(Path base) throws Exception {
        Path src = base.resolve("src");
        Path src_m1 = src.resolve("m1");
        tb.writeJavaFiles(src_m1, "module m1 { }");
        Path modules = base.resolve("modules");
        Files.createDirectories(modules);

        tb.new JavacTask(ToolBox.Mode.CMDLINE)
                .options("-XDrawDiagnostics",
                        "-modulesourcepath", src.toString())
                .outdir(modules)
                .files(prefixAll(findJavaFiles(src), Paths.get("./")))
                .run()
                .writeAll();
    }

    @Test
    void testUnnormalizedPath2(Path base) throws Exception {
        Path src = base.resolve("src");
        Path src_m1 = src.resolve("m1");
        tb.writeJavaFiles(src_m1, "module m1 { }");
        Path modules = base.resolve("modules");
        Files.createDirectories(modules);

        tb.new JavacTask(ToolBox.Mode.CMDLINE)
                .options("-XDrawDiagnostics",
                        "-modulesourcepath", "./" + src.toString())
                .outdir(modules)
                .files(findJavaFiles(src))
                .run()
                .writeAll();
    }

    private Path[] prefixAll(Path[] paths, Path prefix) {
        return Stream.of(paths)
                .map(p -> prefix.resolve(p))
                .collect(Collectors.toList())
                .toArray(new Path[paths.length]);
    }
}
