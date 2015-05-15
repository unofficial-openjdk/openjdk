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
 * @modules java.base/sun.misc java.desktop
 * @library /testlibrary
 * @run main/othervm GetSysPkgTest
 */

// package GetSysPkg_package;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import com.oracle.java.testlibrary.*;

// Test that JVM get_system_package() returns the module location for defined packages.
public class GetSysPkgTest {

    private static Object invoke(Method m, Object obj, Object... args) throws Throwable {
        try {
            return m.invoke(obj, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Method findMethod(String name) {
        for (Method m : sun.misc.BootLoader.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new RuntimeException("Failed to find method " + name + " in java.lang.reflect.Module");
    }

    // Throw RuntimeException if getSystemPackageLocation() does not return
    // the expected location.
    static void getPkg(String name, String expected_loc) throws Throwable {
        String loc = (String)invoke(findMethod("getSystemPackageLocation"), null, name);
        if (loc == null) {
            if (expected_loc == null) return;
            System.out.println("Expected location: " + expected_loc +
                ", for package: " + name + ", got: null");
        } else if (expected_loc == null) {
            System.out.println("Expected location: null, for package: " +
                name + ", got: " + loc);
        } else if (!loc.equals(expected_loc)) {
            System.out.println("Expected location: " +
                expected_loc + ", for package: " + name + ", got: " + loc);
        } else {
            return;
        }
        throw new RuntimeException();
    }

    public static void main(String args[]) throws Throwable {
        if (args.length == 0 || !args[0].equals("do_tests")) {

            // Create a package found via -Xbootclasspath/a
            String source = "package BootLdr_package; " +
                            "public class BootLdrPkg { " +
                            "    public int mth() { return 4; } " +
                            "}";
            byte[] klassbuf =
                InMemoryJavaCompiler.compile("BootLdr_package.BootLdrPkg", source);
            ClassFileInstaller.writeClassToDisk("BootLdr_package/BootLdrPkg", klassbuf, "bl_dir");

            // Create a package found via -cp.
            source = "package GetSysPkg_package; " +
                     "public class GetSysClass { " +
                     "    public int mth() { return 4; } " +
                     "}";
            klassbuf =
                InMemoryJavaCompiler.compile("GetSysPkg_package.GetSysClass", source);
            ClassFileInstaller.writeClassToDisk("GetSysPkg_package/GetSysClass", klassbuf);

            ProcessBuilder pb = ProcessTools.createJavaProcessBuilder("-Xbootclasspath/a:bl_dir",
                "-XX:AddModuleExports=java.base/sun.misc", "-cp", "." + File.pathSeparator +
                System.getProperty("test.classes"), "GetSysPkgTest", "do_tests");
            OutputAnalyzer output = new OutputAnalyzer(pb.start());
            output.shouldHaveExitValue(0);
            return;
        }

        getPkg("java/lang/", "jrt:/java.base");
        getPkg("java/lang", null);              // Need trailing '/'
        getPkg("javax/script/", null);          // Package not defined
        getPkg("sun/invoke/util/", "jrt:/java.base");
        getPkg("java/nio/charset/", "jrt:/java.base");

        // Test a package in a module not owned by boot loader.
        Class clss = Class.forName("javax.activation.DataHandler");
        if (clss == null)
            throw new RuntimeException("Could not find class javax.activation.DataHandler");
        getPkg("javax/activation/", null);       // Not owned by boot loader

        // Test a package not in jimage file.
        clss = Class.forName("GetSysPkg_package.GetSysClass");
        if (clss == null)
            throw new RuntimeException("Could not find class GetSysPkg_package.GetSysClass");
        getPkg("GetSysPkg_package/", null);

        // Access a class with a package in a boot loader module other than java.base
        clss = Class.forName("java.awt.Button");
        if (clss == null)
            throw new RuntimeException("Could not find class java.awt.Button");
        getPkg("java/awt/", "jrt:/java.desktop");

        // Test getting the package location from a class found via -Xbootclasspath/a
        clss = Class.forName("BootLdr_package.BootLdrPkg");
        if (clss == null)
            throw new RuntimeException("Could not find class BootLdr_package.BootLdrPkg");
        String bootldrPkg = (String)invoke(findMethod("getSystemPackageLocation"), null, "BootLdr_package/");
        if (bootldrPkg == null || !bootldrPkg.toLowerCase().endsWith("bl_dir")) {
            System.out.println("Expected BootLdr_package/ to end in bl_dir, but got " +
                bootldrPkg == null ? "null" : bootldrPkg);
            throw new RuntimeException();
        }
    }
}
