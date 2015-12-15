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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

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
                .outdir(classes)
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
                .outdir(modules)
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
                .outdir(classes)
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.FAIL)
                .writeAll()
                .getOutput(ToolBox.OutputKind.DIRECT);

        if (!log.contains("module-info.java:1:24: compiler.err.expected: 'with'"))
            throw new Exception("expected output not found");

    }

    //@ignore JDK-8145012
    //@Test
    void testDuplicateProvides(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { provides p1.C1 with p2.C2; provides p1.C1 with p2.C2; }",
                "package p1; public class C1 { }",
                "package p2; public class C2 extends p1.C1 { }");
        Path classes = base.resolve("classes");
        Files.createDirectories(classes);

        tb.new JavacTask()
                .options("-XDrawDiagnostic")
                .outdir(classes)
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.FAIL)
                .writeAll();
    }

    @Test
    void testMissingService(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { provides p.Missing with p.C; }",
                "package p; public class C extends p.Missing { }");

        List<String> output = tb.new JavacTask()
                .options("-XDrawDiagnostics")
                .outdir(Files.createDirectories(base.resolve("classes")))
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.FAIL)
                .writeAll()
                .getOutputLines(ToolBox.OutputKind.DIRECT);

        List<String> expected = Arrays.asList("C.java:1:36: compiler.err.cant.resolve.location: kindname.class, Missing, , , (compiler.misc.location: kindname.package, p, null)",
                "module-info.java:1:22: compiler.err.cant.resolve.location: kindname.class, Missing, , , (compiler.misc.location: kindname.package, p, null)",
                "2 errors");
        if (!output.containsAll(expected)) {
            throw new Exception("Expected output not found");
        }
    }

    @Test
    void testProvidesFromAnotherModule(Path base) throws Exception {
        Path modules = base.resolve("modules");
        new ModuleBuilder("M")
                .exports("pack")
                .classes("package pack; public enum Singleton { One; } ")
                .build(modules);

        new ModuleBuilder("L")
                .requires("M", modules)
                .provides("pack.Singleton", "pack.Singleton")
                .classes("package p; public class A { } ")
                .build(modules);
    }

    @Test
    void testServiceIsNotImplemented(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { provides p.A with p.B; }",
                "package p; public class A { }",
                "package p; public class B { }");

        List<String> output = tb.new JavacTask()
                .options("-XDrawDiagnostics")
                .outdir(Files.createDirectories(base.resolve("classes")))
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.FAIL)
                .writeAll()
                .getOutputLines(ToolBox.OutputKind.DIRECT);

        List<String> expected = Arrays.asList("module-info.java:1:31: compiler.err.prob.found.req: (compiler.misc.inconvertible.types: p.B, p.A)",
                "1 error");
        if (!output.containsAll(expected)) {
            throw new Exception("Expected output not found");
        }
    }

    @Test
    void testMissingImplementation(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { provides p.C with p.Impl; }",
                "package p; public class C { }");

        List<String> output = tb.new JavacTask()
                .options("-XDrawDiagnostics")
                .outdir(Files.createDirectories(base.resolve("classes")))
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.FAIL)
                .writeAll()
                .getOutputLines(ToolBox.OutputKind.DIRECT);

        List<String> expected = Arrays.asList("module-info.java:1:31: compiler.err.cant.resolve.location: kindname.class, Impl, , , (compiler.misc.location: kindname.package, p, null)",
                "1 error");
        if (!output.containsAll(expected)) {
            throw new Exception("Expected output not found");
        }
    }

    @Test
    void testSeveralImplementations(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { provides p.C with p.Impl1; provides p.C with p.Impl2; }",
                "package p; public class C { }",
                "package p; public class Impl1 extends p.C { }",
                "package p; public class Impl2 extends p.C { }");

        tb.new JavacTask()
                .outdir(Files.createDirectories(base.resolve("classes")))
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.SUCCESS)
                .writeAll();
    }

    @Test
    void testOneImplementationsForServices(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { provides p.Service1 with p.Impl; provides p.Service2 with p.Impl; }",
                "package p; public interface Service1 { }",
                "package p; public abstract class Service2 { }",
                "package p; public class Impl extends p.Service2 implements p.Service1 { }");

        tb.new JavacTask()
                .outdir(Files.createDirectories(base.resolve("classes")))
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.SUCCESS)
                .writeAll();
    }

    //@ignore JDK-8145016
    //@Test
    void testAbstractImplementation(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { provides p1.C1 with p2.C2; }",
                "package p1; public class C1 { }",
                "package p2; public abstract class C2 extends p1.C1 { }");

        List<String> output = tb.new JavacTask()
                .options("-XDrawDiagnostics")
                .outdir(Files.createDirectories(base.resolve("classes")))
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.FAIL)
                .writeAll()
                .getOutputLines(ToolBox.OutputKind.DIRECT);

        List<String> expected = Arrays.asList("#");
        if (!output.containsAll(expected)) {
            throw new Exception("Expected output not found");
        }
    }

    //@ignore JDK-8145016
    //@Test
    void testInterfaceImplementation(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { provides p1.Service with p2.Impl; }",
                "package p1; public interface Service { }",
                "package p2; public interface Impl extends p1.Service { }");

        List<String> output = tb.new JavacTask()
                .options("-XDrawDiagnostics")
                .outdir(Files.createDirectories(base.resolve("classes")))
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.FAIL)
                .writeAll()
                .getOutputLines(ToolBox.OutputKind.DIRECT);

        List<String> expected = Arrays.asList("#");
        if (!output.containsAll(expected)) {
            throw new Exception("Expected output not found");
        }
    }

    @Test
    void testProtectedImplementation(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { provides p1.C1 with p2.C2; }",
                "package p1; public class C1 { }",
                "package p2; class C2 extends p1.C1 { }");

        List<String> output = tb.new JavacTask()
                .options("-XDrawDiagnostics")
                .outdir(Files.createDirectories(base.resolve("classes")))
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.FAIL)
                .writeAll()
                .getOutputLines(ToolBox.OutputKind.DIRECT);

        List<String> expected = Arrays.asList("module-info.java:1:34: compiler.err.not.def.public.cant.access: p2.C2, p2",
                "1 error");
        if (!output.containsAll(expected)) {
            throw new Exception("Expected output not found");
        }
    }

    //@ignore JDK-8145016
    //@Test
    void testNoNoArgConstructor(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { provides p1.C1 with p2.C2; }",
                "package p1; public class C1 { }",
                "package p2; public class C2 extends p1.C1 { public C2(String str){} }");

        List<String> output = tb.new JavacTask()
                .outdir(Files.createDirectories(base.resolve("classes")))
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.FAIL)
                .writeAll()
                .getOutputLines(ToolBox.OutputKind.DIRECT);

        List<String> expected = Arrays.asList("#");
        if (!output.containsAll(expected)) {
            throw new Exception("Expected output not found");
        }
    }

    //@ignore JDK-8145016
    //@Test
    void testPrivateNoArgConstructor(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { provides p1.C1 with p2.C2; }",
                "package p1; public class C1 { }",
                "package p2; public class C2 extends p1.C1 { private C2(){} }");

        List<String> output = tb.new JavacTask()
                .outdir(Files.createDirectories(base.resolve("classes")))
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.FAIL)
                .writeAll()
                .getOutputLines(ToolBox.OutputKind.DIRECT);

        List<String> expected = Arrays.asList("#");
        if (!output.containsAll(expected)) {
            throw new Exception("Expected output not found");
        }
    }

    @Test
    void testServiceIndirectlyImplemented(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { provides p1.C1 with p2.C3; }",
                "package p1; public class C1 { }",
                "package p2; public class C2 extends p1.C1 {  }",
                "package p2; public class C3 extends p2.C2 {  }");

        tb.new JavacTask()
                .outdir(Files.createDirectories(base.resolve("classes")))
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.SUCCESS)
                .writeAll();
    }
    //@ignore JDK-8145016
    //@Test
    void testInnerClass(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { provides p1.C1 with p2.C2.Inner; }",
                "package p1; public class C1 { }",
                "package p2; public class C2  { public class Inner extends p1.C1 { } }");

        List<String> output = tb.new JavacTask()
                .options("-XDrawDiagnostics")
                .outdir(Files.createDirectories(base.resolve("classes")))
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.FAIL)
                .writeAll()
                .getOutputLines(ToolBox.OutputKind.DIRECT);

        if (!output.containsAll(Arrays.asList("#"))) {
            throw new Exception("Expected output not found");
        }
    }
}
