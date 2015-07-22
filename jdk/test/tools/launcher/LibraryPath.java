/*
 * Copyright (c) 2010, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/*
 * @test LibraryPath
 * @bug 6983554
 * @build LibraryPath
 * @run main LibraryPath
 * @summary Verify that an empty LD_LIBRARY_PATH is ignored on Unix.
 * @author ksrini
 */
public class LibraryPath {
    static final String JAVAHOME = System.getProperty("java.home");
    static final boolean isSDK = JAVAHOME.endsWith("jre");
    static final String javaCmd;
    static final String java64Cmd;
    static final String javacCmd;
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

    static final String LLP = "LD_LIBRARY_PATH";
    static final String LLP32 = LLP + "_32";
    static final String LLP64 = LLP + "_64";
    static final String JLP = "java.library.path";

    static {
        assert is64Bit ^ is32Bit;

        File binDir = (isSDK) ? new File((new File(JAVAHOME)).getParentFile(), "bin")
                : new File(JAVAHOME, "bin");
        File javaCmdFile = (isWindows)
                ? new File(binDir, "java.exe")
                : new File(binDir, "java");
        javaCmd = javaCmdFile.getAbsolutePath();
        if (!javaCmdFile.canExecute()) {
            throw new RuntimeException("java <" + javaCmd + "> must exist");
        }

        File javacCmdFile = (isWindows)
                ? new File(binDir, "javac.exe")
                : new File(binDir, "javac");
        javacCmd = javacCmdFile.getAbsolutePath();
        if (!javacCmdFile.canExecute()) {
            throw new RuntimeException("java <" + javacCmd + "> must exist");
        }
        if (isSolaris) {
            File sparc64BinDir = new File(binDir, isSparc ? "sparcv9" : "amd64");
            File java64CmdFile = new File(sparc64BinDir, "java");
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
     * usually the jre/lib/arch-name is the same as os.arch, except for x86.
     */
    static String getJreArch() {
        String arch = System.getProperty("os.arch");
        return arch.equals("x86") ? "i386" : arch;
    }
    /*
     * A method which executes a java cmd and returns the results in a container
     */
    static TestResult doExec(Map<String, String> envToSet, String... cmds) {
        String cmdStr = "";
        for (String x : cmds) {
            cmdStr = cmdStr.concat(x + " ");
        }
        System.out.println(cmdStr);
        ProcessBuilder pb = new ProcessBuilder(cmds);
        Map<String, String> env = pb.environment();
        if (envToSet != null) {
            env.putAll(envToSet);
        }
        
        BufferedReader rdr = null;
        Process p = null;
        try {
            List<String> outputList = new ArrayList<String>();
            pb.redirectErrorStream(true);
            p = pb.start();
            rdr = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String in = rdr.readLine();
            while (in != null) {
                outputList.add(in);
                in = rdr.readLine();
            }
            p.waitFor();
            p.destroy();
            return new TestResult(p.exitValue(), outputList);
        } catch (Exception ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex.getMessage());
        } finally {
            p.destroy();
        }
    }
    /*
     * test if all the LLP* enviroment variables and java.library.path are set
     * correctly in the child, that is any empty value should not be trickled
     * through, and should not be translated to a "." either.
     */
    static void checkStrings(String name, String value) {
        if (value != null) {
            System.out.println(name + "=" + value);
            String s[] = value.split(":");
            for (String x : s) {
                if (x.equals(".")) {
                    throw new Error("found a . in " + name);
                }
                if (x.trim().equals("")) {
                    throw new Error("found an empty string in " + name);
                }
            }
            System.out.println("OK");
        }
    }

    static void doTest(Map<String, String> envMap, String javaCmd) {
        TestResult tr = doExec(envMap, javaCmd, "LibraryPath", "run");
        tr.print(System.out);
        if (tr.exitCode != 0) {
            throw new Error("Test fails");
        }
    }

    /*
     * This test behaves like a fork/exec but synchronously, ie. with no
     * arguments, the parent java process sets the various environmental
     * variables required for a given platform and re-execs this main with
     * a "run" argument, and the child tests if the required variables
     * and properties are sane.
     */
    public static void main(String[] args) {
        // no windows please
        if (isWindows) {
            return;
        }
        if (args != null && args.length > 0 && args[0].equals("run")) {
            checkStrings(LLP, System.getenv(LLP));
            checkStrings(JLP, System.getProperty(JLP, null));
            checkStrings(LLP32, System.getenv(LLP32));
            checkStrings(LLP64, System.getenv(LLP64));
        } else {
            Map<String, String> envMap = new HashMap<String, String>();

            // test with a null string
            envMap.put(LLP, "");
            doTest(envMap, javaCmd);

            // test the Solaris variants now
            if (isSolaris && is32Bit) {
                envMap.put(LLP32, "");
                doTest(envMap, javaCmd);
            }

            // if we have 64-bit variant, ie. dual-mode jre, then test that too.
            if (isDualMode && java64Cmd != null) {
                envMap.clear(); // get rid of 32-bit'isms
                envMap.put(LLP, "");
                envMap.put(LLP64, "");
                doTest(envMap, java64Cmd);
            }
        }
    }
}

class TestResult {
    int exitCode = 0;
    List<String> outputList = null;
    public TestResult(int exitCode, List<String> outputList) {
        this.exitCode = exitCode;
        this.outputList = outputList;
    }
    public void print(PrintStream ps) {
        for (String x : outputList) {
            ps.println(x);
        }
    }
}
