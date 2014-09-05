/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
 * @ignore
 * @summary Basic test for jlink
 */

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.security.*;
import java.util.*;

public class Basic {

    static final String slash = File.separator;
    static final String testSrc = System.getProperty("test.src");
    static final String testSrcDir = testSrc != null ? testSrc : ".";
    static final String testClasses = System.getProperty("test.classes");
    static final String testClassesDir = testClasses != null ? testClasses : ".";
    static final boolean debug = true;

    static final String aClassName = "HelloWally";
    static final String aClassSource = new StringBuilder()
            .append("package foo;")
            .append("public class " + aClassName + " {")
            .append("  public static void main(String[] args) {")
            .append("    System.out.println(\"Hello Wally\");")
            .append("  } }").toString();
    static final File aClassFile = new File(testClassesDir + slash + aClassName + ".java");
    static final File aJarFile = new File(testClassesDir + slash + "HelloWally.jar");
    static final File anImage = new File(testClassesDir + slash + "jlinkImage");
    static final String appCmd = anImage.getPath() + slash + "bin" + slash + "hello";

    public static void main(String[] args) throws Exception {
        createTestFiles();
        createImage();
        runApp();
    }

    static void createImage() throws IOException {
        Files.createDirectory(anImage.toPath());
        jlink("--class-path", aJarFile.getPath(),
              "--command", "hello:foo.HelloWally",
              "--mods", "jdk.base",
              "--output", anImage.getPath(),
              "--format", "image");
    }

    static void runApp() throws Exception {
        debug("Running: " + appCmd);
        ProcessBuilder pb = new ProcessBuilder(appCmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        InputStream is = p.getInputStream();
        InputStreamReader ir = new InputStreamReader(is);
        BufferedReader rd = new BufferedReader(ir, 8192);

        String in = rd.readLine();
        while (in != null) {
            //alist.add(in);
            System.out.println(in);
            in = rd.readLine();
        }
        int retval = p.waitFor();
        //if (retval != 0) {
        //    throw new RuntimeException("process failed with non-zero exit, " + retval);
        //}
    }

    // create/compile/jar files necessary to run the test
    static void createTestFiles() throws IOException {
        try (FileOutputStream fos = new FileOutputStream(aClassFile)) {
            fos.write(aClassSource.getBytes("ASCII"));
        }
        compile("-d" , testClassesDir,
                testClassesDir + slash + aClassName + ".java");

        jar("-cf", aJarFile.getPath(),
            "-C", testClassesDir, "foo" + slash + aClassName + ".class");
    }

    // run jar <args>
    static void jar(String... args) {
        debug("Running: jar " + Arrays.toString(args));
        sun.tools.jar.Main jar = new sun.tools.jar.Main(System.out, System.err, "jar");
        if (!jar.run(args)) {
            throw new RuntimeException("jar failed: args=" + Arrays.toString(args));
        }
    }

    // run javac <args>
    static void compile(String... args) {
        debug("Running: javac " + Arrays.toString(args));
        if (com.sun.tools.javac.Main.compile(args) != 0) {
             throw new RuntimeException("javac failed: args=" + Arrays.toString(args));
        }
    }

    // run jlink <args>
    static void jlink(String... args) {
        debug("Running: jlink " + Arrays.toString(args));
        if (jdk.jigsaw.tools.jlink.Main.run(args, new PrintWriter(System.out)) != 0) {
             throw new RuntimeException("jlink failed: args=" + Arrays.toString(args));
        }
    }

    static void debug(String message) {
        if (debug) System.out.println(message);
    }

    static boolean check(boolean cond, Object... failedArgs) {
        if (cond)
            return true;
        // We are going to fail...
        StringBuilder sb = new StringBuilder();
        for (Object o : failedArgs)
            sb.append(o);
        throw new RuntimeException(sb.toString());
    }
}
