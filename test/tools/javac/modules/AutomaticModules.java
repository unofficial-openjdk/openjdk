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
 * @summary Verify modules can contain packages of the same name, unless these meet.
 * @library /tools/lib
 * @modules
 *      jdk.compiler/com.sun.tools.javac.api
 *      jdk.compiler/com.sun.tools.javac.main
 * @build ToolBox ModuleTestBase
 * @run main AutomaticModules
 */

import java.nio.file.Files;
import java.nio.file.Path;

public class AutomaticModules extends ModuleTestBase {

    public static void main(String... args) throws Exception {
        AutomaticModules t = new AutomaticModules();
        t.runTests();
    }

    @Test
    void testSimple(Path base) throws Exception {
        Path legacySrc = base.resolve("legacy-src");
        tb.writeJavaFiles(legacySrc,
                          "package api; import java.awt.event.ActionListener; public abstract class Api implements ActionListener {}");
        Path legacyClasses = base.resolve("legacy-classes");
        Files.createDirectories(legacyClasses);

        String log = tb.new JavacTask()
                .options()
                .outdir(legacyClasses)
                .files(findJavaFiles(legacySrc))
                .run()
                .writeAll()
                .getOutput(ToolBox.OutputKind.DIRECT);

        if (!log.isEmpty()) {
            throw new Exception("unexpected output: " + log);
        }

        Path modulePath = base.resolve("module-path");

        Files.createDirectories(modulePath);

        Path jar = modulePath.resolve("test-api-1.0.jar");

        tb.new JarTask(jar)
          .baseDir(legacyClasses)
          .files("api/Api.class")
          .run();

        Path moduleSrc = base.resolve("module-src");
        Path m1 = moduleSrc.resolve("m1");

        Path classes = base.resolve("classes");

        Files.createDirectories(classes);

        tb.writeJavaFiles(m1,
                          "module m1 { requires test.api; }",
                          "package impl; public class Impl { public void e(api.Api api) { api.actionPerformed(null); } }");

        tb.new JavacTask()
                .options("-modulesourcepath", moduleSrc.toString(), "-modulepath", modulePath.toString())
                .outdir(classes)
                .files(findJavaFiles(moduleSrc))
                .run()
                .writeAll()
                .getOutput(ToolBox.OutputKind.DIRECT);
    }

    @Test
    void testUnnamedModule(Path base) throws Exception {
        Path legacySrc = base.resolve("legacy-src");
        tb.writeJavaFiles(legacySrc,
                          "package api; public abstract class Api { public void run(CharSequence str) { } private void run(base.Base base) { } }",
                          "package base; public interface Base { public void run(); }");
        Path legacyClasses = base.resolve("legacy-classes");
        Files.createDirectories(legacyClasses);

        String log = tb.new JavacTask()
                .options()
                .outdir(legacyClasses)
                .files(findJavaFiles(legacySrc))
                .run()
                .writeAll()
                .getOutput(ToolBox.OutputKind.DIRECT);

        if (!log.isEmpty()) {
            throw new Exception("unexpected output: " + log);
        }

        Path modulePath = base.resolve("module-path");

        Files.createDirectories(modulePath);

        Path apiJar = modulePath.resolve("test-api-1.0.jar");

        tb.new JarTask(apiJar)
          .baseDir(legacyClasses)
          .files("api/Api.class")
          .run();

        Path baseJar = base.resolve("base.jar");

        tb.new JarTask(baseJar)
          .baseDir(legacyClasses)
          .files("base/Base.class")
          .run();

        Path moduleSrc = base.resolve("module-src");
        Path m1 = moduleSrc.resolve("m1");

        Path classes = base.resolve("classes");

        Files.createDirectories(classes);

        tb.writeJavaFiles(m1,
                          "module m1 { requires test.api; }",
                          "package impl; public class Impl { public void e(api.Api api) { api.run(\"\"); } }");

        tb.new JavacTask()
                .options("-modulesourcepath", moduleSrc.toString(), "-modulepath", modulePath.toString(), "-classpath", baseJar.toString())
                .outdir(classes)
                .files(findJavaFiles(moduleSrc))
                .run()
                .writeAll()
                .getOutput(ToolBox.OutputKind.DIRECT);
    }

}
