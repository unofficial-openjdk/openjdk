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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import jdk.testlibrary.FileUtils;
import jdk.testlibrary.JDKToolFinder;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import static java.lang.String.format;
import static java.lang.System.out;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.Assert.assertTrue;

/*
 * @test
 * @library /lib/testlibrary
 * @build jdk.testlibrary.FileUtils jdk.testlibrary.JDKToolFinder
 * @run testng CLICompatibility
 * @summary Basic test for CLI options compatibility
 */

public class CLICompatibility {
    static final Path TEST_CLASSES = Paths.get(System.getProperty("test.classes", "."));
    static final Path USER_DIR = Paths.get(System.getProperty("user.dir"));

    final boolean legacyOnly;  // for running on older JDK's ( test validation )

    // Resources we know to exist, that can be used for creating jar files.
    static final String RES1 = "CLICompatibility.class";
    static final String RES2 = "CLICompatibility$Result.class";

    @BeforeTest
    public void setupResourcesForJar() throws Exception {
        // Copy the files that we are going to use for creating/updating test
        // jar files, so that they can be referred to without '-C dir'
        Files.copy(TEST_CLASSES.resolve(RES1), USER_DIR.resolve(RES1));
        Files.copy(TEST_CLASSES.resolve(RES2), USER_DIR.resolve(RES2));
    }

    static final Consumer<InputStream> ASSERT_CONTAINS_RES1 = in -> {
        try (JarInputStream jin = new JarInputStream(in)) {
            assertTrue(jarContains(jin, RES1),
                       "Failed to find " + RES1);
        } catch (IOException x) {
            throw new UncheckedIOException(x);
        }
    };
    static final Consumer<InputStream> ASSERT_CONTAINS_RES2 = in -> {
        try (JarInputStream jin = new JarInputStream(in)) {
            assertTrue(jarContains(jin, RES2),
                       "Failed to find " + RES2);
        } catch (IOException x) {
            throw new UncheckedIOException(x);
        }
    };
    static final Consumer<InputStream> ASSERT_CONTAINS_MAINFEST = in -> {
        try (JarInputStream jin = new JarInputStream(in)) {
            assertTrue(jin.getManifest() != null,
                        "No META-INF/MANIFEST.MF");
        } catch (IOException x) {
            throw new UncheckedIOException(x);
        }
    };
    static final Consumer<InputStream> ASSERT_DOES_NOT_CONTAIN_MAINFEST = in -> {
        try (JarInputStream jin = new JarInputStream(in)) {
            assertTrue(jin.getManifest() == null,
                       "Found unexpected META-INF/MANIFEST.MF");
        } catch (IOException x) {
            throw new UncheckedIOException(x);
        }
    };

    static final FailCheckerWithMessage FAIL_TOO_MANY_MAIN_OPS =
            new FailCheckerWithMessage("You must specify one of -ctxui options",
                    "{ctxui}[vfmn0Me] [jar-file] [manifest-file] [entry-point] [-C dir] files" /*Legacy*/);

    // Create

    @Test
    public void createBadArgs() {
        final FailCheckerWithMessage FAIL_CREATE_NO_ARGS = new FailCheckerWithMessage(
                "'c' flag requires manifest or input files to be specified!");

        FAIL_CREATE_NO_ARGS.accept(jarWithResult("c"));
        FAIL_CREATE_NO_ARGS.accept(jarWithResult("-c"));
        if (!legacyOnly)
            FAIL_CREATE_NO_ARGS.accept(jarWithResult("--create"));
        FAIL_TOO_MANY_MAIN_OPS.accept(jarWithResult("ct"));
        FAIL_TOO_MANY_MAIN_OPS.accept(jarWithResult("-ct"));
        if (!legacyOnly)
            FAIL_TOO_MANY_MAIN_OPS.accept(jarWithResult("--create --list"));
    }

    @Test
    public void createWriteToFile() throws IOException {
        Path path = Paths.get("createJarFile.jar");  // a jar file suitable for creating
        String jn = path.toString();
        for (String opts : new String[]{"cf " + jn, "-cf " + jn, "--create --archive=" + jn}) {
            if (legacyOnly && opts.startsWith("--"))
                continue;

            PASS.accept(jarWithResult(opts, RES1));
            ASSERT_CONTAINS_RES1.accept(Files.newInputStream(path));
            ASSERT_CONTAINS_MAINFEST.accept(Files.newInputStream(path));
        }
        FileUtils.deleteFileIfExistsWithRetry(path);
    }

    @Test
    public void createWriteToStdout() throws IOException {
        for (String opts : new String[]{"c", "-c", "--create"}) {
            if (legacyOnly && opts.startsWith("--"))
                continue;

            Result r = jarWithResult(opts, RES1);
            PASS.accept(r);
            ASSERT_CONTAINS_RES1.accept(r.stdoutAsStream());
            ASSERT_CONTAINS_MAINFEST.accept(r.stdoutAsStream());
        }
    }

    @Test
    public void createWriteToStdoutNoManifest() throws IOException {
        for (String opts : new String[]{"cM", "-cM", "--create --no-manifest"} ){
            if (legacyOnly && opts.startsWith("--"))
                continue;

            Result r = jarWithResult(opts, RES1);
            PASS.accept(r);
            ASSERT_CONTAINS_RES1.accept(r.stdoutAsStream());
            ASSERT_DOES_NOT_CONTAIN_MAINFEST.accept(r.stdoutAsStream());
        }
    }

    // Update

    @Test
    public void updateBadArgs() {
        final FailCheckerWithMessage FAIL_UPDATE_NO_ARGS = new FailCheckerWithMessage(
                "'u' flag requires manifest, 'e' flag or input files to be specified!");

        FAIL_UPDATE_NO_ARGS.accept(jarWithResult("u"));
        FAIL_UPDATE_NO_ARGS.accept(jarWithResult("-u"));
        if (!legacyOnly)
            FAIL_UPDATE_NO_ARGS.accept(jarWithResult("--update"));
        FAIL_TOO_MANY_MAIN_OPS.accept(jarWithResult("ut"));
        FAIL_TOO_MANY_MAIN_OPS.accept(jarWithResult("-ut"));
        if (!legacyOnly)
            FAIL_TOO_MANY_MAIN_OPS.accept(jarWithResult("--update --list"));
    }

    @Test
    public void updateReadFileWriteFile() throws IOException {
        Path path = Paths.get("updateReadWriteStdout.jar");  // a jar file suitable for updating
        String jn = path.toString();

        for (String opts : new String[]{"uf " + jn, "-uf " + jn, "--update --archive=" + jn}) {
            if (legacyOnly && opts.startsWith("--"))
                continue;

            createJar(path, RES1);
            PASS.accept(jarWithResult(opts, RES2));
            ASSERT_CONTAINS_RES1.accept(Files.newInputStream(path));
            ASSERT_CONTAINS_RES2.accept(Files.newInputStream(path));
            ASSERT_CONTAINS_MAINFEST.accept(Files.newInputStream(path));
        }
        FileUtils.deleteFileIfExistsWithRetry(path);
    }

    @Test
    public void updateReadStdinWriteStdout() throws IOException {
        Path path = Paths.get("updateReadStdinWriteStdout.jar");

        for (String opts : new String[]{"u", "-u", "--update"}) {
            if (legacyOnly && opts.startsWith("--"))
                continue;

            createJar(path, RES1);
            Result r = jarWithResultAndStdin(path.toFile(), opts, RES2);
            PASS.accept(r);
            ASSERT_CONTAINS_RES1.accept(r.stdoutAsStream());
            ASSERT_CONTAINS_RES2.accept(r.stdoutAsStream());
            ASSERT_CONTAINS_MAINFEST.accept(r.stdoutAsStream());
        }
        FileUtils.deleteFileIfExistsWithRetry(path);
    }

    @Test
    public void updateReadStdinWriteStdoutNoManifest() throws IOException {
        Path path = Paths.get("updateReadStdinWriteStdoutNoManifest.jar");

        for (String opts : new String[]{"uM", "-uM", "--update --no-manifest"} ){
            if (legacyOnly && opts.startsWith("--"))
                continue;

            createJar(path, RES1);
            Result r = jarWithResultAndStdin(path.toFile(), opts, RES2);
            PASS.accept(r);
            ASSERT_CONTAINS_RES1.accept(r.stdoutAsStream());
            ASSERT_CONTAINS_RES2.accept(r.stdoutAsStream());
            ASSERT_DOES_NOT_CONTAIN_MAINFEST.accept(r.stdoutAsStream());
        }
        FileUtils.deleteFileIfExistsWithRetry(path);
    }

    // List

    @Test
    public void listBadArgs() {
        FAIL_TOO_MANY_MAIN_OPS.accept(jarWithResult("te"));
        FAIL_TOO_MANY_MAIN_OPS.accept(jarWithResult("-te"));
        if (!legacyOnly)
            FAIL_TOO_MANY_MAIN_OPS.accept(jarWithResult("--list --main-class"));
    }

    @Test
    public void listReadFromFileWriteToStdout() throws IOException {
        Path path = Paths.get("listReadFromFileWriteToStdout.jar");  // a jar file suitable for listing
        createJar(path, RES1);
        String jn = path.toString();

        for (String opts : new String[]{"tf " + jn, "-tf " + jn, "--list --archive " + jn}) {
            if (legacyOnly && opts.startsWith("--"))
                continue;

            Result r = jarWithResult(opts);
            PASS.accept(r);
            assertTrue(r.output.contains("META-INF/MANIFEST.MF") && r.output.contains(RES1),
                       "Failed, got [" + r.output + "]");
        }
        FileUtils.deleteFileIfExistsWithRetry(path);
    }

    @Test
    public void listReadFromStdinWriteToStdout() throws IOException {
        Path path = Paths.get("listReadFromStdinWriteToStdout.jar");
        createJar(path, RES1);

        for (String opts : new String[]{"t", "-t", "--list"} ){
            if (legacyOnly && opts.startsWith("--"))
                continue;

            Result r = jarWithResultAndStdin(path.toFile(), opts);
            PASS.accept(r);
            assertTrue(r.output.contains("META-INF/MANIFEST.MF") && r.output.contains(RES1),
                       "Failed, got [" + r.output + "]");
        }
        FileUtils.deleteFileIfExistsWithRetry(path);
    }

    // Extract

    @Test
    public void extractBadArgs() {
        FAIL_TOO_MANY_MAIN_OPS.accept(jarWithResult("xi"));
        FAIL_TOO_MANY_MAIN_OPS.accept(jarWithResult("-xi"));
        if (!legacyOnly)
            FAIL_TOO_MANY_MAIN_OPS.accept(jarWithResult("--extract --generate-index"));
    }

    @Test
    public void extractReadFromStdin() throws IOException {
        Path path = Paths.get("extract");
        Path jarPath = path.resolve("extractReadFromStdin.jar"); // a jar file suitable for extracting
        createJar(jarPath, RES1);

        for (String opts : new String[]{"x" ,"-x", "--extract"}) {
            if (legacyOnly && opts.startsWith("--"))
                continue;

            PASS.accept(jarWithResultAndStdinAndWorkingDir(jarPath.toFile(), path.toFile(), opts));
            assertTrue(Files.exists(path.resolve(RES1)), "Expected to find:" + path.resolve(RES1));
            FileUtils.deleteFileIfExistsWithRetry(path.resolve(RES1));
        }
        FileUtils.deleteFileTreeWithRetry(path);
    }

    @Test
    public void extractReadFromFile() throws IOException {
        Path path = Paths.get("extract");
        String jn = "extractReadFromFile.jar";
        Path jarPath = path.resolve(jn);
        createJar(jarPath, RES1);

        for (String opts : new String[]{"xf "+jn ,"-xf "+jn, "--extract --archive="+jn}) {
            if (legacyOnly && opts.startsWith("--"))
                continue;

            PASS.accept(jarWithResultAndStdinAndWorkingDir(null, path.toFile(), opts));
            assertTrue(Files.exists(path.resolve(RES1)), "Expected to find:" + path.resolve(RES1));
            FileUtils.deleteFileIfExistsWithRetry(path.resolve(RES1));
        }
        FileUtils.deleteFileTreeWithRetry(path);
    }

    // -- Infrastructure

    static boolean jarContains(JarInputStream jis, String entryName)
        throws IOException
    {
        JarEntry e;
        boolean found = false;
        while((e = jis.getNextJarEntry()) != null) {
            if (e.getName().equals(entryName))
                return true;
        }
        return false;
    }

    /* Creates a simple jar with entries of size 0, good enough for testing */
    static void createJar(Path path, String... entries) throws IOException {
        FileUtils.deleteFileIfExistsWithRetry(path);
        Path parent = path.getParent();
        if (parent != null)
            Files.createDirectories(parent);
        try (OutputStream out = Files.newOutputStream(path);
             JarOutputStream jos = new JarOutputStream(out)) {
            JarEntry je = new JarEntry("META-INF/MANIFEST.MF");
            jos.putNextEntry(je);
            jos.closeEntry();

            for (String entry : entries) {
                je = new JarEntry(entry);
                jos.putNextEntry(je);
                jos.closeEntry();
            }
        }
    }

    static final Consumer<Result> PASS = r -> {
        //out.printf("%s%n", r.output);
        assertTrue(r.exitValue == 0,
                  "Expected exitValue 0, got:" + r.exitValue + ". Output: " + r.output);
    };

    static class FailCheckerWithMessage implements Consumer<Result> {
        final String[] messages;
        FailCheckerWithMessage(String... m) {
            messages = m;
        }
        @Override
        public void accept(Result r) {
            //out.printf("%s%n", r.output);
            assertTrue(r.exitValue != 0, "Expected exitValue != 0, got:" + r.exitValue);
            boolean found = false;
            for (String m : messages) {
                if (r.output.contains(m)) {
                    found = true;
                    break;
                }
            }
            assertTrue(found,
                       "Excepted out to contain one of: " + Arrays.asList(messages)
                           + " but got: " + r.output);
        }
    }

    static Result jarWithResult(String... args) {
        return jarWithResultAndStdinAndWorkingDir(null, null, args);
    }

    static Result jarWithResultAndStdin(File stdinSource, String... args) {
        return jarWithResultAndStdinAndWorkingDir(stdinSource, null, args);
    }

    static Result jarWithResultAndStdinAndWorkingDir(File stdinFrom,
                                                     File workingDir,
                                                     String... args) {
        String jar = getJDKTool("jar");
        List<String> commands = new ArrayList<>();
        commands.add(jar);
        Stream.of(args).map(s -> s.split(" "))
                       .flatMap(Arrays::stream)
                       .forEach(x -> commands.add(x));
        ProcessBuilder p = new ProcessBuilder(commands);
        if (stdinFrom != null)
            p.redirectInput(stdinFrom);
        if (workingDir != null)
            p.directory(workingDir);
        return run(p);
    }

    static Result run(ProcessBuilder pb) {
        Process p;
        byte[] stdout, stderr;
        out.printf("Running: %s%n", pb.command());
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new RuntimeException(
                    format("Couldn't start process '%s'", pb.command()), e);
        }

        String output;
        try {
            stdout = readAllBytes(p.getInputStream());
            stderr = readAllBytes(p.getErrorStream());

            output = toString(stdout, stderr);
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
        return new Result(p.exitValue(), stdout, stderr, output);
    }

    static final Path JAVA_HOME = Paths.get(System.getProperty("java.home"));

    static String getJDKTool(String name) {
        try {
            return JDKToolFinder.getJDKTool(name);
        } catch (Exception x) {
            Path j = JAVA_HOME.resolve("bin").resolve(name);
            if (Files.exists(j))
                return j.toString();
            j = JAVA_HOME.resolve("..").resolve("bin").resolve(name);
            if (Files.exists(j))
                return j.toString();
            throw new RuntimeException(x);
        }
    }

    static String toString(byte[] ba1, byte[] ba2) {
        return (new String(ba1, UTF_8)).concat(new String(ba2, UTF_8));
    }

    static class Result {
        final int exitValue;
        final byte[] stdout;
        final byte[] stderr;
        final String output;

        private Result(int exitValue, byte[] stdout, byte[] stderr, String output) {
            this.exitValue = exitValue;
            this.stdout = stdout;
            this.stderr = stderr;
            this.output = output;
        }

        InputStream stdoutAsStream() {
            return new ByteArrayInputStream(stdout);
        }
    }

    // readAllBytes implementation so the test can be run pre 1.9 ( legacyOnly )
    static byte[] readAllBytes(InputStream is) throws IOException {
        byte[] buf = new byte[8192];
        int capacity = buf.length;
        int nread = 0;
        int n;
        for (;;) {
            // read to EOF which may read more or less than initial buffer size
            while ((n = is.read(buf, nread, capacity - nread)) > 0)
                nread += n;

            // if the last call to read returned -1, then we're done
            if (n < 0)
                break;

            // need to allocate a larger buffer
            capacity = capacity << 1;

            buf = Arrays.copyOf(buf, capacity);
        }
        return (capacity == nread) ? buf : Arrays.copyOf(buf, nread);
    }

    // Standalone entry point for running with, possibly older, JDKs.
    public static void main(String[] args) throws Throwable {
        boolean legacyOnly = false;
        if (args.length != 0 && args[0].equals("legacyOnly"))
            legacyOnly = true;

        CLICompatibility test = new CLICompatibility(legacyOnly);
        for (Method m : CLICompatibility.class.getDeclaredMethods()) {
            if (m.getAnnotation(Test.class) != null) {
                System.out.println("Invoking " + m.getName());
                m.invoke(test);
            }
        }
    }
    CLICompatibility(boolean legacyOnly) { this.legacyOnly = legacyOnly; }
    CLICompatibility() { this.legacyOnly = false; }
}
