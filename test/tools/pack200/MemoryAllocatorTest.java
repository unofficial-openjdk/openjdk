/*
 * Copyright (c) 2008, Oracle and/or its affiliates. All rights reserved.
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
 * @test MemoryAllocatorTest.java
 * @bug 6755943 6792554
 * @summary Checks any memory overruns in archive length.
 * @run main/timeout=1200 MemoryAllocatorTest
 */

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

public class MemoryAllocatorTest {

    /*
     * The smallest possible pack file with 1 empty resource
     */
    static int[] magic = {
        0xCA, 0xFE, 0xD0, 0x0D
    };
    static int[] version_info = {
        0x07, // minor
        0x96  // major
    };
    static int[] option = {
        0x10
    };
    static int[] size_hi = {
        0x00
    };
    static int[] size_lo_ulong = {
        0xFF, 0xFC, 0xFC, 0xFC, 0xFC // ULONG_MAX 0xFFFFFFFF
    };
    static int[] size_lo_correct = {
        0x17
    };
    static int[] data = {
        0x00, 0xEC, 0xDA, 0xDE, 0xF8, 0x45, 0x01, 0x02,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x01, 0x31, 0x01, 0x00
    };
    // End of pack file data

    static final String JAVA_HOME = System.getProperty("java.home");

    static final boolean debug = Boolean.getBoolean("MemoryAllocatorTest.Debug");
    static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
    static final boolean LINUX = System.getProperty("os.name").startsWith("Linux");
    static final boolean SIXTYFOUR_BIT = System.getProperty("sun.arch.data.model", "32").equals("64");
    static final private int NATIVE_EXPECTED_EXIT_CODE = (WINDOWS) ? -1 : 255;
    static final private int JAVA_EXPECTED_EXIT_CODE = 1;
    
    static final private String EXPECTED_ERROR_MESSAGE[] = {
        "Native allocation failed",
        "overflow detected",
        "archive header had incorrect size",
        "EOF reading band",
        "impossible archive size",
	"bad value count",
	"file too large"
    };

    static int testExitValue = 0;
    
    static byte[] bytes(int[] a) {
        byte[] b = new byte[a.length];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) a[i];
        }
        return b;
    }
    
    static void createPackFile(boolean good, File packFile) throws IOException {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(packFile);
            fos.write(bytes(magic));
            fos.write(bytes(version_info));
            fos.write(bytes(option));
            fos.write(bytes(size_hi));
            if (good) {
                fos.write(bytes(size_lo_correct));
            } else {
                fos.write(bytes(size_lo_ulong));
            }
            fos.write(bytes(data));
        } finally {
            if (fos != null) {
                fos.close();
            }
        }
    }

    /*
     * This method modifies the LSB of the size_lo for various larger
     * wicked values between MAXINT-0x3F and MAXINT.
     */
    static int modifyPackFileLarge(File packFile) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(packFile, "rws");
        long len = packFile.length();
        FileChannel fc = raf.getChannel();
        MappedByteBuffer bb = fc.map(FileChannel.MapMode.READ_WRITE, 0, len);
        int pos = magic.length + version_info.length + option.length +
                size_hi.length;
        byte value = bb.get(pos);
        value--;
        bb.position(pos);
        bb.put(value);
        bb.force();
        fc.truncate(len);
        fc.close();
        return value & 0xFF;
    }
  
    /*
     * This method modifies the LSB of the size_lo for smaller wicked values
     */
    static int modifyPackFileSmall(File packFile, boolean increment) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(packFile, "rws");
        long len = packFile.length();
        FileChannel fc = raf.getChannel();
        MappedByteBuffer bb = fc.map(FileChannel.MapMode.READ_WRITE, 0, len);
        int pos = magic.length + version_info.length + option.length +
                size_hi.length;
        byte value = bb.get(pos);
        if (increment) 
            value++;
        else
            value--;
        bb.position(pos);
        bb.put(value);
        bb.force();
        fc.truncate(len);
        fc.close();
        return value & 0xFF;
    }
    
    
    static String getUnpack200Cmd() throws Exception {
        return getAjavaCmd("unpack200");
    }
    
    static String getJavaCmd() throws Exception {
        return getAjavaCmd("java");
    }
    
    static String getAjavaCmd(String cmdStr) throws Exception {
        File binDir = new File(JAVA_HOME, "bin");
        File unpack200File = WINDOWS
                ? new File(binDir, cmdStr + ".exe")
                : new File(binDir, cmdStr);

        String cmd = unpack200File.getAbsolutePath();
        if (!unpack200File.canExecute()) {
            throw new Exception("please check" +
                    cmd + " exists and is executable");
        }
        return cmd;
    }
    final static  String UNPACK_FN = "Unpack";

    
    static void createTestClass() throws IOException {
        PrintWriter pw = new PrintWriter(new FileWriter(UNPACK_FN + ".java"));
        try {
            pw.println("import java.io.File;");
            pw.println("import java.io.FileOutputStream;");
            pw.println("import java.util.jar.JarOutputStream;");
            pw.println("import java.util.jar.Pack200;");

            pw.println("public final class " + UNPACK_FN + "  {");
            pw.println("public static void main(String args[]) throws Exception {");
            pw.println("Pack200.Unpacker u = Pack200.newUnpacker();");
            pw.println("File in = new File(args[0]);");
            pw.println("File out = new File(args[1]);");
            pw.println("FileOutputStream os = new FileOutputStream(out) {");
            pw.println("public void write(int b) {");
            pw.println("}");
            pw.println("};");
            pw.println("u.unpack(in, new JarOutputStream(os));");
            pw.println("}");
            pw.println("}");
        } finally {
            if (pw != null) {
                pw.close();
            }
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        final String javacCmds[] = {UNPACK_FN + ".java"};
        javac.run(null, null, null, javacCmds);
    }
    
    static TestResult runUnpack200(File packFile) throws Exception {
        if (!packFile.exists()) {
            throw new Exception("please check" + packFile + " exists");
        }
        return runUnpacker(getUnpack200Cmd(), packFile.getName(), "testout.jar");
    }

    static TestResult runJavaUnpack(File packFile) throws Exception {
        if (!packFile.exists()) {
            throw new Exception("please check" + packFile + " exists");
        }
        return runUnpacker(getJavaCmd(), "-cp", ".", UNPACK_FN, packFile.getName(), "testout.jar");
    }
        
    static TestResult runUnpacker(String... cmds) throws Exception {
   
        ArrayList<String> alist = new ArrayList<String>();
        ProcessBuilder pb = 
                new ProcessBuilder(cmds);
        Map<String, String> env = pb.environment();
        pb.directory(new File("."));
        for (String x : cmds) {
            System.out.print(x + " ");
        }
        System.out.println("");
        int retval = 0;
        try {
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader rd = new BufferedReader(
                    new InputStreamReader(p.getInputStream()), 8192);
            String in = rd.readLine();
            while (in != null) {
                alist.add(in);
                System.out.println(in);
                in = rd.readLine();
            }
            retval = p.waitFor();
            p.destroy();
        } catch (Exception ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex.getMessage());
        }
        return new TestResult("", retval, alist);
    }

    /*
     * tests large size values
     */
    static void runLargeTests() throws Exception {
        File packFile = new File("big.pack");       
        // Create a good pack file and test if everything is ok
        createPackFile(true, packFile);
        TestResult tr = runUnpack200(packFile);
        tr.setDescription("unpack200: a good pack file");
        tr.checkPositive();
        tr.isOK();
        System.out.println(tr);
        if (testExitValue != 0) {
            throw new RuntimeException("Test ERROR");
        }
        
        // try the same with java unpacker
        tr = runJavaUnpack(packFile);
        tr.setDescription("unpack: a good pack file");
        tr.checkPositive();
        tr.isOK();
        System.out.println(tr);
        if (testExitValue != 0) {
            throw new RuntimeException("Test ERROR");
        }       

        int value = 0;
    
        // create a bad pack file
        createPackFile(false, packFile);
        
        // run the unpack200 unpacker
        tr = runUnpack200(packFile);
        tr.setDescription("unpack200: a wicked pack file");
        tr.contains(EXPECTED_ERROR_MESSAGE);
        tr.checkValue(NATIVE_EXPECTED_EXIT_CODE);
        // run the java unpacker now
        tr = runJavaUnpack(packFile);
        tr.setDescription("unpack: a wicked pack file");
        tr.contains(EXPECTED_ERROR_MESSAGE);
        tr.checkValue(JAVA_EXPECTED_EXIT_CODE);
      
        // Large values test
        System.out.println(tr);
        value = modifyPackFileLarge(packFile);

        // continue creating bad pack files by modifying the specimen pack file.
        while (value >= 0xc0) {
            tr.setDescription("unpack200: wicked value=0x" +
                    Integer.toHexString(value & 0xFF));
            tr = runUnpack200(packFile);
            tr.contains(EXPECTED_ERROR_MESSAGE);
            tr.checkValue(NATIVE_EXPECTED_EXIT_CODE);

            System.out.println(tr);
            
            tr.setDescription("unpack: wicked value=0x" +
                    Integer.toHexString(value & 0xFF));
            tr = runJavaUnpack(packFile);
            tr.contains(EXPECTED_ERROR_MESSAGE);
            tr.checkValue(JAVA_EXPECTED_EXIT_CODE);

            System.out.println(tr);
            
            value = modifyPackFileLarge(packFile);
        }        
    }
    
    /*
     * tests mutations near the 0x0 size area
     */
    static void runSmallTests() throws Exception {
          // small values test
         // create a good pack file to start with
        File packFile = new File("small.pack");    
        createPackFile(true, packFile);
        TestResult tr = null;
        int value = modifyPackFileSmall(packFile, false);
        while (value > 0) {
            // run the unpack200 unpacker
            tr = runUnpack200(packFile);
            tr.setDescription("unpack200: wicked value=0x" +
                    Integer.toHexString(value & 0xFF));
            tr.contains(EXPECTED_ERROR_MESSAGE);
            tr.checkValue(NATIVE_EXPECTED_EXIT_CODE);

            System.out.println(tr);
            
            // run the java unpacker now
            tr = runJavaUnpack(packFile);
            tr.setDescription("unpack: wicked value=0x" +
                    Integer.toHexString(value & 0xFF));
            tr.contains(EXPECTED_ERROR_MESSAGE);
            tr.checkValue(JAVA_EXPECTED_EXIT_CODE);
            System.out.println(tr);
            
            value = modifyPackFileSmall(packFile, false);
        }           
    }
    
    /*
     * These tests uses the unpacker on packfiles provided by external
     * contributors which cannot be recreated programmatically.
     * Note: The jar file containing the tests may not be included in all
     * opensource repositories and the test will pass if the packfiles.jar
     * is missing.
     */
    static void runBadPackTests() throws Exception {
       File packJar = new File(System.getProperty("test.src", "."), "packfiles.jar");
       if (!packJar.exists()) {
           System.out.println("Warning: packfiles.jar missing, therefore this test passes vacuously");
           return;
       }
       String[] jarCmd = { "xvf", packJar.getAbsolutePath()};
       ByteArrayOutputStream baos = new ByteArrayOutputStream();
       PrintStream jarout = new PrintStream(baos);
       sun.tools.jar.Main jarTool = new sun.tools.jar.Main(jarout, System.err, "jar-tool");
       if (!jarTool.run(jarCmd)) {
           throw new RuntimeException("Error: could not extract archive");
       }
       TestResult tr = null;
       jarout.close();
       baos.flush();
       for (String x : baos.toString().split("\n")) {
           String line[] = x.split(":");
           if (line[0].trim().startsWith("inflated")) {
               String pfile = line[1].trim();
               tr = runUnpack200(new File(pfile));
               tr.setDescription("unpack200: " + pfile);
               tr.contains(EXPECTED_ERROR_MESSAGE);
               tr.checkValue(NATIVE_EXPECTED_EXIT_CODE);
               System.out.println(tr);
               
               tr = runJavaUnpack(new File(pfile));
               tr.setDescription("unpack: " + pfile);
               tr.contains(EXPECTED_ERROR_MESSAGE);
               tr.checkValue(JAVA_EXPECTED_EXIT_CODE);
               System.out.println(tr);
           }
       }       
    }
    
    /**
     * @param args the command line arguments
     * @throws java.lang.Exception 
     */
    public static void main(String[] args) throws Exception {
        /*
         * jprt systems on windows and linux seem to have abundant memory
         * therefore can take a very long time to run, and even if it does
         * the error message is not accurate for us to discern if the test
         * passes successfully.
         */
        if (SIXTYFOUR_BIT && (LINUX || WINDOWS)) {
            System.out.println("Warning: Windows/Linux 64bit tests passes vacuously");
            return;
        }

        // Create the java unpacker
        createTestClass();        
        runLargeTests();
        runSmallTests();
        runBadPackTests();
    
        if (testExitValue != 0) {
            throw new Exception("Pack200 archive length tests(" +
                    testExitValue + ") failed ");
        } else {
            System.out.println("All tests pass");
        }
    }

    /*
     * A class to encapsulate the test results and stuff, with some ease
     * of use methods to check the test results.
     */
    static class TestResult {

        StringBuilder status;
        int exitValue;
        List<String> testOutput;
        String description;
        boolean isNPVersion;
        
        /*
         * The debug version builds of unpack200 call abort(3) which might set
         * an unexpected return value, therefore this test is to determine
         * if we are using a product or non-product build and check the
         * return value appropriately.
         */
        boolean isNonProductVersion() throws Exception {
            ArrayList<String> alist = new ArrayList<String>();
            ProcessBuilder pb = new ProcessBuilder(getUnpack200Cmd(), "--version");
            Map<String, String> env = pb.environment();
            System.out.println(new File(".").getAbsolutePath());
            pb.directory(new File("."));
            int retval = 0;
            try {
                pb.redirectErrorStream(true);
                Process p = pb.start();
                BufferedReader rd = new BufferedReader(
                        new InputStreamReader(p.getInputStream()), 8192);
                String in = rd.readLine();
                while (in != null) {
                    alist.add(in);
                    System.out.println(in);
                    in = rd.readLine();
                }
                retval = p.waitFor();
                p.destroy();
            } catch (Exception ex) {
                ex.printStackTrace();
                throw new RuntimeException(ex.getMessage());
            }
            for (String x : alist) {
                if (x.contains("non-product")) {
                    return true;
                }
            }
            return false;
        }


        public TestResult(String str, int rv, List<String> oList) throws Exception {
            status = new StringBuilder(str);
            exitValue = rv;
            testOutput = oList;
            isNPVersion = isNonProductVersion();
            if (isNPVersion) {
                System.err.println("Warning: exit values are not checked" +
                        " for non-product build");
            }
        }

        void setDescription(String description) {
            this.description = description;
        }

        void checkValue(int value) {
            if (isNPVersion) return;
            if (exitValue != value) {
                status =
                        status.append("  Error: test expected exit value " +
                        value + "got " + exitValue);
                testExitValue++;
            }
        }

        void checkNegative() {
            if (exitValue == 0) {
                status = status.append(
                        "  Error: test did not expect 0 exit value");

                testExitValue++;
            }
        }

        void checkPositive() {
            if (exitValue != 0) {
                status = status.append(
                        "  Error: test did not return 0 exit value");
                testExitValue++;
            }
        }

        boolean isOK() {
            return exitValue == 0;
        }

        boolean isZeroOutput() {
            if (!testOutput.isEmpty()) {
                status = status.append("  Error: No message from cmd please");
                testExitValue++;
                return false;
            }
            return true;
        }

        boolean isNotZeroOutput() {
            if (testOutput.isEmpty()) {
                status = status.append("  Error: Missing message");
                testExitValue++;
                return false;
            }
            return true;
        }

        public String toString() {
            if (debug) {
                for (String x : testOutput) {
                    status = status.append(x + "\n");
                }
            }
            if (description != null) {
                status.insert(0, description);
            }
            return status.append("\nexitValue = " + exitValue).toString();
        }

        boolean contains(String... strs) {
            for (String x : testOutput) {
                for (String y : strs) {
                    if (x.contains(y)) {
                        return true;
                    }
                }
            }
            status = status.append("   Error: expected strings not found");
            testExitValue++;
            return false;
        }
    }
}
