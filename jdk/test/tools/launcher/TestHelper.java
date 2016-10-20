/*
 * Copyright (c) 2008, 2013, Oracle and/or its affiliates. All rights reserved.
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * This class provides some common utilites for the launcher tests.
 */
public class TestHelper {
    // commonly used jtreg constants
    static final File TEST_CLASSES_DIR;
    static final File TEST_SOURCES_DIR;

    static final String JAVAHOME = System.getProperty("java.home");
    static final String JAVA_JRE_BIN;
    static final boolean isSDK = JAVAHOME.endsWith("jre");
    static final String javaCmd;
    static final String javawCmd;
    static final String java64Cmd;
    static final String javacCmd;
    static final JavaCompiler compiler;

    static final boolean debug = Boolean.getBoolean("TestHelper.Debug");
    static final boolean isWindows =
            System.getProperty("os.name", "unknown").startsWith("Windows");
    static final boolean is64Bit =
            System.getProperty("sun.arch.data.model").equals("64");
    static final boolean is32Bit =
            System.getProperty("sun.arch.data.model").equals("32");
    static final boolean isSolaris =
            System.getProperty("os.name", "unknown").startsWith("SunOS");
    static final boolean isLinux =
            System.getProperty("os.name", "unknown").startsWith("Linux");
    static final boolean isDualMode = isSolaris;
    static final boolean isSparc = System.getProperty("os.arch").startsWith("sparc");

    // make a note of the golden default locale
    static final Locale DefaultLocale = Locale.getDefault();

    static final String JAVA_FILE_EXT  = ".java";
    static final String CLASS_FILE_EXT = ".class";
    static final String JAR_FILE_EXT   = ".jar";
    static final String JLDEBUG_KEY     = "_JAVA_LAUNCHER_DEBUG";
    static final String EXPECTED_MARKER = "TRACER_MARKER:About to EXEC";

    static int testExitValue = 0;

    static {
        String tmp = System.getProperty("test.classes", null);
        if (tmp == null) {
            throw new Error("property test.classes not defined ??");
        }
        TEST_CLASSES_DIR = new File(tmp).getAbsoluteFile();

        tmp = System.getProperty("test.src", null);
        if (tmp == null) {
            throw new Error("property test.src not defined ??");
        }
        TEST_SOURCES_DIR = new File(tmp).getAbsoluteFile();

        if (is64Bit && is32Bit) {
            throw new RuntimeException("arch model cannot be both 32 and 64 bit");
        }
        if (!is64Bit && !is32Bit) {
            throw new RuntimeException("arch model is not 32 or 64 bit ?");
        }
        compiler = ToolProvider.getSystemJavaCompiler();
        File binDir = (isSDK)
                ? new File((new File(JAVAHOME)).getParentFile(), "bin")
                : new File(JAVAHOME, "bin");
        JAVA_JRE_BIN = new File((new File(JAVAHOME)).getParentFile(),
                        (isSDK) ? "jre/bin" : "bin").getAbsolutePath();
        File javaCmdFile = (isWindows)
                ? new File(binDir, "java.exe")
                : new File(binDir, "java");
        javaCmd = javaCmdFile.getAbsolutePath();
        if (!javaCmdFile.canExecute()) {
            throw new RuntimeException("java <" + TestHelper.javaCmd +
                    "> must exist and should be executable");
        }

        File javacCmdFile = (isWindows)
                ? new File(binDir, "javac.exe")
                : new File(binDir, "javac");
        javacCmd = javacCmdFile.getAbsolutePath();

        if (isWindows) {
            File javawCmdFile = new File(binDir, "javaw.exe");
            javawCmd = javawCmdFile.getAbsolutePath();
            if (!javawCmdFile.canExecute()) {
                throw new RuntimeException("java <" + javawCmd +
                        "> must exist and should be executable");
            }
        } else {
            javawCmd = null;
        }

        if (!javacCmdFile.canExecute()) {
            throw new RuntimeException("java <" + javacCmd +
                    "> must exist and should be executable");
        }
        if (isSolaris) {
            File sparc64BinDir = new File(binDir,isSparc ? "sparcv9" : "amd64");
            File java64CmdFile= new File(sparc64BinDir, "java");
            if (java64CmdFile.exists() && java64CmdFile.canExecute()) {
                java64Cmd = java64CmdFile.getAbsolutePath();
            } else {
                java64Cmd = null;
            }
        } else {
            java64Cmd = null;
        }
    }

    /*
     * is a dual mode available in the test jdk
     */
    static boolean dualModePresent() {
        return isDualMode && java64Cmd != null;
    }

    /*
     * usually the jre/lib/arch-name is the same as os.arch, except for x86.
     */
    static String getJreArch() {
        String arch = System.getProperty("os.arch");
        return arch.equals("x86") ? "i386" : arch;
    }
    static String getArch() {
        return System.getProperty("os.arch");
    }
    static File getClassFile(File javaFile) {
        String s = javaFile.getAbsolutePath().replace(JAVA_FILE_EXT, CLASS_FILE_EXT);
        return new File(s);
    }

    static File getJavaFile(File classFile) {
        String s = classFile.getAbsolutePath().replace(CLASS_FILE_EXT, JAVA_FILE_EXT);
        return new File(s);
    }

    static String baseName(File f) {
        String s = f.getName();
        return s.substring(0, s.indexOf("."));
    }

    /*
     * A convenience method to create a jar with jar file name and defs
     */
    static void createJar(File jarName, String... mainDefs)
            throws FileNotFoundException{
        createJar(null, jarName, new File("Foo"), mainDefs);
    }

    /*
     * A convenience method to create a java file, compile and jar it up, using
     * the sole class file name in the jar, as the Main-Class attribute value.
     */
    static void createJar(File jarName, File mainClass, String... mainDefs)
            throws FileNotFoundException {
            createJar(null, jarName, mainClass, mainDefs);
    }

    /*
     * A convenience method to compile java files.
     */
    static void compile(String... compilerArgs) {
        if (compiler.run(null, null, null, compilerArgs) != 0) {
            String sarg = "";
            for (String x : compilerArgs) {
                sarg.concat(x + " ");
            }
            throw new Error("compilation failed: " + sarg);
        }
    }

    /*
     * A generic jar file creator to create a java file, compile it
     * and jar it up, a specific Main-Class entry name in the
     * manifest can be specified or a null to use the sole class file name
     * as the Main-Class attribute value.
     */
    static void createJar(String mEntry, File jarName, File mainClass,
            String... mainDefs) throws FileNotFoundException {
        if (jarName.exists()) {
            jarName.delete();
        }
        PrintStream ps = new PrintStream(new FileOutputStream(mainClass + ".java"));
        ps.println("public class Foo {");
        if (mainDefs != null) {
            for (String x : mainDefs) {
                ps.println(x);
            }
        }
        ps.println("}");
        ps.close();

        String compileArgs[] = {
            mainClass + ".java"
        };
        if (compiler.run(null, null, null, compileArgs) != 0) {
            throw new RuntimeException("compilation failed " + mainClass + ".java");
        }
        if (mEntry == null) {
            mEntry = mainClass.getName();
        }
        String jarArgs[] = {
            (debug) ? "cvfe" : "cfe",
            jarName.getAbsolutePath(),
            mEntry,
            mainClass.getName() + ".class"
        };
        sun.tools.jar.Main jarTool =
                new sun.tools.jar.Main(System.out, System.err, "JarCreator");
        if (!jarTool.run(jarArgs)) {
            throw new RuntimeException("jar creation failed " + jarName);
        }
    }

    static void createJar(String... args) {
        sun.tools.jar.Main jarTool =
                new sun.tools.jar.Main(System.out, System.err, "JarCreator");
        if (!jarTool.run(args)) {
            String message = "jar creation failed with command:";
            for (String x : args) {
                message = message.concat(" " + x);
            }
            throw new RuntimeException(message);
        }
    }

    static void createFile(File outFile, List<String> content) throws IOException {
	PrintWriter writer = null;
	try {
	    writer = new PrintWriter(outFile, Charset.defaultCharset().name());
	    for (String line : content)
		writer.println(line);
	} finally {
	    if (writer != null)
		writer.close();
	}
    }
    
    static TestResult doExec(String...cmds) {
        return doExec(null, cmds);
    }

    /*
     * A method which executes a java cmd and returns the results in a container
     */
    static TestResult doExec(Map<String, String> envToSet, String...cmds) {
        String cmdStr = "";
        for (String x : cmds) {
            cmdStr = cmdStr.concat(x + " ");
        }
        ProcessBuilder pb = new ProcessBuilder(cmds);
        Map<String, String> env = pb.environment();
        if (envToSet != null) {
            env.putAll(envToSet);
        }
        BufferedReader rdr = null;
        try {
            List<String> outputList = new ArrayList<String>();
            pb.redirectErrorStream(true);
            Process p = pb.start();
            rdr = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String in = rdr.readLine();
            while (in != null) {
                outputList.add(in);
                in = rdr.readLine();
            }
            p.waitFor();
            p.destroy();
            return new TestHelper.TestResult(cmdStr, p.exitValue(), outputList);
        } catch (Exception ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex.getMessage());
        }
    }

    static FileFilter createFilter(final String extension) {
        return new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                String name = pathname.getName();
                if (name.endsWith(extension)) {
                    return true;
                }
                return false;
            }
        };
    }

    static boolean isEnglishLocale() {
        return Locale.getDefault().getLanguage().equals("en");
    }

    /*
     * A class to encapsulate the test results and stuff, with some ease
     * of use methods to check the test results.
     */
    static class TestResult {
        StringBuilder status;
        int exitValue;
        List<String> testOutput;

        public TestResult(String str, int rv, List<String> oList) {
            status = new StringBuilder("Executed command: " + str + "\n");
            exitValue = rv;
            testOutput = oList;
        }

        void appendStatus(String x) {
            status = status.append("  " + x + "\n");
            testExitValue++;
        }

        void checkNegative() {
            if (exitValue == 0) {
                appendStatus("Error: test must not return 0 exit value");
            }
        }

        void checkPositive() {
            if (exitValue != 0) {
                appendStatus("Error: test did not return 0 exit value");
            }
        }

        boolean isOK() {
            return exitValue == 0;
        }

        boolean isZeroOutput() {
            if (!testOutput.isEmpty()) {
                appendStatus("Error: No message from cmd please");
                return false;
            }
            return true;
        }

        boolean isNotZeroOutput() {
            if (testOutput.isEmpty()) {
                appendStatus("Error: Missing message");
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            status = status.append("++++Test Output Begin++++\n");
            for (String x : testOutput) {
                appendStatus(x);
            }
            status = status.append("++++Test Output End++++\n");
            return status.toString();
        }

        boolean contains(String str) {
            for (String x : testOutput) {
                if (x.contains(str)) {
                    return true;
                }
            }
            appendStatus("Error: string <" + str + "> not found");
            return false;
        }

        boolean notContains(String str) {
             for (String x : testOutput) {
                if (x.contains(str)) {
                    appendStatus("string <" + str + "> found");
                    return false;
                }
            }
            return true;
        }

        boolean matches(String stringToMatch) {
          for (String x : testOutput) {
                if (x.matches(stringToMatch)) {
                    return true;
                }
            }
            appendStatus("Error: string <" + stringToMatch + "> not found");
            return false;
        }

        boolean notMatches(String stringToMatch) {
            for (String x : testOutput) {
                if (!x.matches(stringToMatch)) {
                    return true;
                }
            }
            appendStatus("string <" + stringToMatch + "> found");
            return false;
        }
    }
}
