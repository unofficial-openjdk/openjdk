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

import java.io.*;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import jdk.testlibrary.FileUtils;
import jdk.testlibrary.JDKToolFinder;
import static java.lang.String.format;
import static java.lang.System.out;

/*
 * @test
 * @library /lib/testlibrary
 * @build jdk.testlibrary.FileUtils jdk.testlibrary.JDKToolFinder
 * @compile Basic.java
 * @run main Basic
 * @summary Basic test for Modular jars
 */

public class Basic {
    static final Path TEST_SRC = Paths.get(System.getProperty("test.src", "."));
    static final Path TEST_CLASSES = Paths.get(System.getProperty("test.classes", "."));

    static class TestModuleData {
        // Details based on the checked in module source
        static TestModuleData FOO = new TestModuleData("foo",
                                                       "1.123",
                                                       "jdk.test.foo.Foo",
                                                       "Hello World!!!", null,
                                                       "jdk.test.foo.internal");
        static TestModuleData BAR = new TestModuleData("bar",
                                                       "4.5.6.7",
                                                       "jdk.test.bar.Bar",
                                                       "Hello from Bar!", null,
                                                       "jdk.test.bar",
                                                       "jdk.test.bar.internal");
        final String moduleName;
        final String mainClass;
        final String version;
        final String message;
        final String hashes;
        final Set<String> conceals;
        TestModuleData(String mn, String v, String mc, String m, String h, String... pkgs) {
            moduleName = mn; mainClass = mc; version = v; message = m; hashes = h;
            conceals = new HashSet<>();
            Stream.of(pkgs).forEach(conceals::add);
        }
        TestModuleData(String mn, String v, String mc, String m, String h, Set<String> pkgs) {
            moduleName = mn; mainClass = mc; version = v; message = m; hashes = h;
            conceals = pkgs;
        }
        static TestModuleData from(String s) {
            try {
                BufferedReader reader = new BufferedReader(new StringReader(s));
                String line;
                String message = null;
                String name = null, version = null, mainClass = null;
                String hashes = null;
                Set<String> conceals = null;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("message:")) {
                        message = line.substring("message:".length());
                    } else if (line.startsWith("nameAndVersion:")) {
                        line = line.substring("nameAndVersion:".length());
                        int i = line.indexOf('@');
                        if (i != -1) {
                            name = line.substring(0, i);
                            version = line.substring(i + 1, line.length());
                        } else {
                            name = line;
                        }
                    } else if (line.startsWith("mainClass:")) {
                        mainClass = line.substring("mainClass:".length());
                    } else if (line.startsWith("hashes:")) {
                        hashes = line.substring("hashes:".length());
                    }  else if (line.startsWith("conceals:")) {
                        line = line.substring("conceals:".length());
                        conceals = new HashSet<>();
                        int i = line.indexOf(',');
                        if (i != -1) {
                            String[] p = line.split(",");
                            Stream.of(p).forEach(conceals::add);
                        } else {
                            conceals.add(line);
                        }
                    } else {
                        throw new AssertionError("Unknown value " + line);
                    }
                }

                return new TestModuleData(name, version, mainClass, message, hashes, conceals);
            } catch (IOException x) {
                throw new UncheckedIOException(x);
            }
        }
    }

    static final BiConsumer<Result,TestModuleData> PASS = (r,expected) -> {
        out.printf("%s%n", r.output);

        check(r.exitValue == 0, "Expected exitValue 0, got:", r.exitValue);

        TestModuleData received = TestModuleData.from(r.output);
        if (expected.message != null)
            check(expected.message.equals(received.message),
                  "Expected message:", expected.message, ", got:", received.message);
        check(expected.moduleName.equals(received.moduleName),
              "Expected moduleName: ", expected.moduleName, ", got:", received.moduleName);
        check(expected.version.equals(received.version),
                "Expected version: ", expected.version, ", got:", received.version);
        check(expected.mainClass.equals(received.mainClass),
                "Expected mainClass: ", expected.mainClass, ", got:", received.mainClass);
        expected.conceals.forEach(p -> check(received.conceals.contains(p),
                                             "Expected ", p, ", in ", received.conceals));
        received.conceals.forEach(p -> check(expected.conceals.contains(p),
                                            "Expected ", p, ", in ", expected.conceals));
    };
    static final BiConsumer<Result,TestModuleData> BAR_PASS = (r,expected) -> {
        PASS.accept(r, expected);

        TestModuleData received = TestModuleData.from(r.output);
        check(received.hashes != null, "Expected non-null hashes value.");
    };
    static final BiConsumer<Result,TestModuleData> FAIL = (r,expected) -> {
        out.printf("%s%n", r.output);
        check(r.exitValue != 0, "Expected exitValue != 0, got:", r.exitValue);
    };

    public static void main(String[] args) throws Throwable {
        compileModule(TestModuleData.FOO.moduleName);
        compileModule(TestModuleData.BAR.moduleName, TEST_CLASSES);

        testCreate(TestModuleData.FOO, PASS);
        testUpdate(TestModuleData.FOO, PASS);
        testUpdatePartial(TestModuleData.FOO, PASS);
        testDependences(TestModuleData.FOO, TestModuleData.BAR, BAR_PASS);

        testBadOptions(TestModuleData.FOO, FAIL);
    }

    static void testCreate(TestModuleData testModule,
                           BiConsumer<Result,TestModuleData> resultChecker)
        throws IOException
    {
        Path mp = Paths.get(testModule.moduleName + "-create");
        out.println("---Testing " + mp.getFileName());
        createTestDir(mp);
        Path modClasses = TEST_CLASSES.resolve(testModule.moduleName);

        Path modularJar = mp.resolve(testModule.moduleName + ".jar");
        jar("--create",
            "--archive=" + modularJar.toString(),
            "--main-class=" + testModule.mainClass,
            "--module-version=" + testModule.version,
            "--no-manifest",
            "-C", modClasses.toString(), ".");

        Result r = java(mp, testModule.moduleName + "/" + testModule.mainClass);

        resultChecker.accept(r, testModule);
    }

    static void testUpdate(TestModuleData testModule,
                           BiConsumer<Result,TestModuleData> resultChecker)
        throws IOException
    {
        Path mp = Paths.get(testModule.moduleName + "-update");
        out.println("---Testing " + mp.getFileName());
        createTestDir(mp);
        Path modClasses = TEST_CLASSES.resolve(testModule.moduleName);

        Path modularJar = mp.resolve(testModule.moduleName + ".jar");
        jar("--create",
            "--archive=" + modularJar.toString(),
            "--no-manifest",
            "-C", modClasses.toString(), "jdk");

        jar("--update",
            "--archive=" + modularJar.toString(),
            "--main-class=" + testModule.mainClass,
            "--module-version=" + testModule.version,
            "--no-manifest",
            "-C", modClasses.toString(), "module-info.class");

        Result r = java(mp, testModule.moduleName + "/" + testModule.mainClass);

        resultChecker.accept(r, testModule);
    }

    static void testUpdatePartial(TestModuleData testModule,
                                  BiConsumer<Result,TestModuleData> resultChecker)
        throws IOException
    {
        Path mp = Paths.get(testModule.moduleName + "-updatePartial");
        out.println("---Testing " + mp.getFileName());
        createTestDir(mp);
        Path modClasses = TEST_CLASSES.resolve(testModule.moduleName);
        Path modularJar = mp.resolve(testModule.moduleName + ".jar");

        // just the main class in first create
        jar("--create",
            "--archive=" + modularJar.toString(),
            "--main-class=" + "IAmNotAnEntryPoint",  // no all attributes
            "--no-manifest",
            "-C", modClasses.toString(), ".");  // includes module-info.class

        jar("--update",
            "--archive=" + modularJar.toString(),
            "--main-class=" + testModule.mainClass,
            "--module-version=" + testModule.version,
            "--no-manifest");
        Result r = java(mp, testModule.moduleName + "/" + testModule.mainClass);
        resultChecker.accept(r, testModule);

        // just the version in first create
        FileUtils.deleteFileWithRetry(modularJar);
        jar("--create",
                "--archive=" + modularJar.toString(),
                "--module-version=" + "100000000",  // no all attributes
                "--no-manifest",
                "-C", modClasses.toString(), ".");  // includes module-info.class

        jar("--update",
                "--archive=" + modularJar.toString(),
                "--main-class=" + testModule.mainClass,
                "--module-version=" + testModule.version,
                "--no-manifest");
        r = java(mp, testModule.moduleName + "/" + testModule.mainClass);
        resultChecker.accept(r, testModule);

        // just some files, no concealed packages, in first create
        FileUtils.deleteFileWithRetry(modularJar);
        jar("--create",
            "--archive=" + modularJar.toString(),
            "--no-manifest",
            "-C", modClasses.toString(), "module-info.class",
            "-C", modClasses.toString(), "jdk/test/foo/Foo.class");

        jar("--update",
            "--archive=" + modularJar.toString(),
            "--main-class=" + testModule.mainClass,
            "--module-version=" + testModule.version,
            "--no-manifest",
            "-C", modClasses.toString(), "jdk/test/foo/internal/Message.class");
        r = java(mp, testModule.moduleName + "/" + testModule.mainClass);
        resultChecker.accept(r, testModule);

        // all attributes and files
        FileUtils.deleteFileWithRetry(modularJar);
        jar("--create",
            "--archive=" + modularJar.toString(),
            "--main-class=" + testModule.mainClass,
            "--module-version=" + testModule.version,
            "--no-manifest",
            "-C", modClasses.toString(), ".");

        jar("--update",
            "--archive=" + modularJar.toString(),
            "--main-class=" + testModule.mainClass,
            "--module-version=" + testModule.version,
            "--no-manifest",
            "-C", modClasses.toString(), ".");
        r = java(mp, testModule.moduleName + "/" + testModule.mainClass);
        resultChecker.accept(r, testModule);
    }

    static void testDependences(TestModuleData fooData, TestModuleData barData,
                                BiConsumer<Result,TestModuleData> resultChecker)
        throws IOException
    {
        Path mp = Paths.get(fooData.moduleName + "-" + barData.moduleName
                            + "-dependences");
        out.println("---Testing " + mp.getFileName());
        createTestDir(mp);
        Path modClasses = TEST_CLASSES.resolve(fooData.moduleName);

        Path modularJar = mp.resolve(fooData.moduleName + ".jar");
        jar("--create",
            "--archive=" + modularJar.toString(),
            "--main-class=" + fooData.mainClass,
            "--module-version=" + fooData.version,
            "--no-manifest",
            "-C", modClasses.toString(), ".");

        modClasses = TEST_CLASSES.resolve(barData.moduleName);

        modularJar = mp.resolve(barData.moduleName + ".jar");
        jar("--create",
            "--archive=" + modularJar.toString(),
            "--main-class=" + barData.mainClass,
            "--module-version=" + barData.version,
            "--modulepath=" + mp.toString(),
            "--hash-dependences=" + "foo",  // dependence on foo
            "--no-manifest",
            "-C", modClasses.toString(), ".");

        Result r = java(mp, barData.moduleName + "/" + barData.mainClass,
                        "-XaddExports:java.base/jdk.internal.module");

        resultChecker.accept(r, barData);
    }

    static void testBadOptions(TestModuleData testModule,
                               BiConsumer<Result,TestModuleData> resultChecker)
        throws IOException
    {
        Path mp = Paths.get(testModule.moduleName + "-bad-options");
        out.println("---Testing " + mp.getFileName());
        createTestDir(mp);
        Path modClasses = TEST_CLASSES.resolve(testModule.moduleName);

        Path modularJar = mp.resolve(testModule.moduleName + ".jar");
        Result r = jarWithResult("--create",
                                 "--archive=" + modularJar.toString(),
                                 "--module-version=" + 1.1,   // no module-info.class
                                 "-C", modClasses.toString(), "jdk");

        resultChecker.accept(r, null);  // TODO: expected failure message

         r = jarWithResult("--create",
                           "--archive=" + modularJar.toString(),
                           "--hash-dependences=" + ".*",   // no module-info.class
                           "-C", modClasses.toString(), "jdk");

        resultChecker.accept(r, null);  // TODO: expected failure message
    }

    static void jar(String... args) {
        quickFail(jarWithResult(args));
    }

    static Result jarWithResult(String... args) {
        String jar = getJDKTool("jar");
        List<String> commands = new ArrayList<>();
        commands.add(jar);
        Stream.of(args).forEach(x -> commands.add(x));
        ProcessBuilder p = new ProcessBuilder(commands);
        return run(p);
    }

    static Path compileModule(String mn) throws IOException {
        return compileModule(mn, null);
    }

    static Path compileModule(String mn, Path mp)
        throws IOException
    {
        Path fooSourcePath = TEST_SRC.resolve("src").resolve(mn);
        Path build = Files.createDirectories(TEST_CLASSES.resolve(mn));
        javac(build, mp, fileList(fooSourcePath));
        return build;
    }

    // Re-enable when there is support in javax.tools for module path
//    static void javac(Path dest, Path... sourceFiles) throws IOException {
//        out.printf("Compiling %d source files %s%n", sourceFiles.length,
//                   Arrays.asList(sourceFiles));
//        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
//        try (StandardJavaFileManager fileManager =
//                     compiler.getStandardFileManager(null, null, null)) {
//
//            List<File> files = Stream.of(sourceFiles)
//                                     .map(p -> p.toFile())
//                                     .collect(Collectors.toList());
//            List<File> dests = Stream.of(dest)
//                                     .map(p -> p.toFile())
//                                     .collect(Collectors.toList());
//            Iterable<? extends JavaFileObject> compilationUnits =
//                    fileManager.getJavaFileObjectsFromFiles(files);
//            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, dests);
//            JavaCompiler.CompilationTask task =
//                    compiler.getTask(null, fileManager, null, null, null, compilationUnits);
//            boolean passed = task.call();
//            if (!passed)
//                throw new RuntimeException("Error compiling " + files);
//        }
//    }

    static void javac(Path dest, Path... sourceFiles) throws IOException {
        javac(dest, null, sourceFiles);
    }

    static void javac(Path dest, Path modulePath, Path... sourceFiles)
        throws IOException
    {
        String javac = getJDKTool("javac");

        List<String> commands = new ArrayList<>();
        commands.add(javac);
        commands.add("-d");
        commands.add(dest.toString());
        if (dest.toString().contains("bar"))
            commands.add("-XaddExports:java.base/jdk.internal.module");
        if (modulePath != null) {
            commands.add("-mp");
            commands.add(modulePath.toString());
        }
        Stream.of(sourceFiles).map(Object::toString).forEach(x -> commands.add(x));

        quickFail(run(new ProcessBuilder(commands)));
    }

    static Result java(Path modulePath, String entryPoint, String... args) {
        String java = getJDKTool("java");

        List<String> commands = new ArrayList<>();
        commands.add(java);
        Stream.of(args).forEach(x -> commands.add(x));
        commands.add("-mp");
        commands.add(modulePath.toString());
        commands.add("-m");
        commands.add(entryPoint);

        return run(new ProcessBuilder(commands));
    }

    static Path[] fileList(Path directory) throws IOException {
        final List<Path> filePaths = new ArrayList<>();
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file,
                                             BasicFileAttributes attrs) {
                filePaths.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return filePaths.toArray(new Path[filePaths.size()]);
    }

    static void createTestDir(Path p) throws IOException{
        if (Files.exists(p))
            FileUtils.deleteFileTreeWithRetry(p);
        Files.createDirectory(p);
    }

    static void quickFail(Result r) {
        if (r.exitValue != 0)
            throw new RuntimeException(r.output);
    }

    static Result run(ProcessBuilder pb) {
        Process p;
        out.printf("Running: %s%n", pb.command());
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new RuntimeException(
                    format("Couldn't start process '%s'", pb.command()), e);
        }

        String output;
        try {
            output = toString(p.getInputStream(), p.getErrorStream());
        } catch (IOException e) {
            throw new RuntimeException(
                    format("Couldn't read process output '%s'", pb.command()), e);
        }

        try {
            p.waitFor();
        } catch (InterruptedException e) {
            throw new RuntimeException(
                    format("Process hasn't finished '%s'", pb.command()), e);
        }
        return new Result(p.exitValue(), output);
    }

    static final String DEFAULT_IMAGE_BIN = System.getProperty("java.home")
            + File.separator + "bin" + File.separator;

    static String getJDKTool(String name) {
        try {
            return JDKToolFinder.getJDKTool(name);
        } catch (Exception x) {
            return DEFAULT_IMAGE_BIN + name;
        }
    }

    static String toString(InputStream in1, InputStream in2) throws IOException {
        try (ByteArrayOutputStream dst = new ByteArrayOutputStream();
             InputStream concatenated = new SequenceInputStream(in1, in2)) {
            concatenated.transferTo(dst);
            return new String(dst.toByteArray(), "UTF-8");
        }
    }

    static class Result {
        final int exitValue;
        final String output;

        private Result(int exitValue, String output) {
            this.exitValue = exitValue;
            this.output = output;
        }
    }

    static void check(boolean cond, Object ... failedArgs) {
        if (cond)
            return;
        StringBuilder sb = new StringBuilder();
        for (Object o : failedArgs)
            sb.append(o);
        throw new RuntimeException(sb.toString());
    }
}
