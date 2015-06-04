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
package tests;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import jdk.internal.jimage.BasicImageReader;
import jdk.internal.jimage.ImageLocation;
import com.sun.tools.classfile.ClassFile;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import jdk.internal.jimage.BasicImageWriter;
/**
 *
 * JDK Modular image validator
 */
public class JImageValidator {

    private static final String[] dirs = {"bin", "lib"};

    private static final List<String> EXPECTED_JIMAGES = new ArrayList<>();

    static {
        EXPECTED_JIMAGES.add(BasicImageWriter.BOOT_IMAGE_NAME);
    }

    private final File rootDir;
    private final List<String> expectedLocations;
    private final String module;
    private long moduleExecutionTime;
    private long javaExecutionTime;
    private final List<String> unexpectedPaths;
    private final List<String> unexpectedFiles;

    public JImageValidator(String module, List<String> expectedLocations,
            File rootDir,
            List<String> unexpectedPaths,
            List<String> unexpectedFiles) throws Exception {
        if (!rootDir.exists()) {
            throw new Exception("Image root dir not found " +
                    rootDir.getAbsolutePath());
        }
        this.expectedLocations = expectedLocations;
        this.rootDir = rootDir;
        this.module = module;
        this.unexpectedPaths = unexpectedPaths;
        this.unexpectedFiles = unexpectedFiles;
    }

    public void validate() throws Exception {
        for (String d : dirs) {
            File dir = new File(rootDir, d);
            if (!dir.isDirectory()) {
                throw new Exception("Invalid directory " + d);
            }
        }

        //check all jimages
        File modules = new File(rootDir, "lib" + File.separator + "modules");
        if (modules.list() == null || modules.list().length == 0) {
            throw new Exception("No jimage files generated");
        }
        List<String> seenImages = new ArrayList<>();
        seenImages.addAll(EXPECTED_JIMAGES);
        for (File f : modules.listFiles()) {
            if (f.getName().endsWith(".jimage")) {
                if (!EXPECTED_JIMAGES.contains(f.getName())) {
                    throw new Exception("Unexpected image " + f.getName());
                }
                seenImages.remove(f.getName());
                validate(f, expectedLocations, unexpectedPaths);
            }
        }
        if (!seenImages.isEmpty()) {
            throw new Exception("Some images not seen " + seenImages);
        }
        // Check binary file
        File launcher = new File(rootDir, "bin" + File.separator + module);
        if (launcher.exists()) {
            ProcessBuilder builder = new ProcessBuilder("sh", launcher.getAbsolutePath());
            long t = System.currentTimeMillis();
            Process process = builder.inheritIO().start();
            int ret = process.waitFor();
            moduleExecutionTime += System.currentTimeMillis() - t;
            if (ret != 0) {
                throw new Exception("Image " + module +
                        " execution failed, check logs.");
            }
        }

        //Walk and check that unexpected files are not there
        try (java.util.stream.Stream<Path> stream = Files.walk(rootDir.toPath())) {
            stream.forEach((p) -> {
                for (String u : unexpectedFiles) {
                    if (p.toString().contains(u)) {
                        throw new RuntimeException("Seen unexpected path " + p);
                    }
                }
            });
        }

        File javalauncher = new File(rootDir, "bin" + File.separator +
                (isWindows() ? "java.exe" : "java"));
        if (javalauncher.exists()) {
            ProcessBuilder builder = new ProcessBuilder(javalauncher.getAbsolutePath(),
                    "-version");
            long t = System.currentTimeMillis();
            Process process = builder.start();
            int ret = process.waitFor();
            javaExecutionTime += System.currentTimeMillis() - t;
            if (ret != 0) {
                throw new Exception("java launcher execution failed, check logs.");
            }
        } else {
            throw new Exception("java launcher not found.");
        }

        //Check release file
        File release = new File(rootDir, "release");
        if (!release.exists()) {
            throw new Exception("Release file not generated");
        } else {
            Properties props = new Properties();
            try (FileInputStream fs = new FileInputStream(release)) {
                props.load(fs);
                String s = props.getProperty("MODULES");
                if (s == null) {
                    throw new Exception("No MODULES property in release");
                }
                if (!s.contains(module)) {
                    throw new Exception("Module not found in release file " + s);
                }
            }
        }

    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    public static void validate(File jimage, List<String> expectedLocations,
            List<String> unexpectedPaths) throws Exception {
        BasicImageReader reader = BasicImageReader.open(jimage.getAbsolutePath());
        // Validate expected locations
        List<String> seenLocations = new ArrayList<>();
        for (String loc : expectedLocations) {
            ImageLocation il = reader.findLocation(loc);
            if (il == null) {
                throw new Exception("Location " + loc + " not present in " + jimage);
            }
        }
        seenLocations.addAll(expectedLocations);

        for (String s : reader.getEntryNames()) {
            if (s.endsWith(".class") && !s.endsWith("module-info.class")) {
                ImageLocation il = reader.findLocation(s);
                try {
                    byte[] r = reader.getResource(il);
                    if(r == null) {
                        System.out.println("IL, compressed " +
                                il.getCompressedSize() + " uncompressed " +
                                il.getUncompressedSize());
                        throw new Exception("NULL RESOURCE " + s);
                    }
                    readClass(r);
                } catch (Exception ex) {
                    System.err.println(s + " ERROR " + ex);
                    throw ex;
                }
            }
            if (seenLocations.contains(s)) {
                seenLocations.remove(s);
            }
            for(String p : unexpectedPaths) {
                if(s.contains(p)) {
                    throw new Exception("Seen unexpected path " + s);
                }
            }
        }
        if (!seenLocations.isEmpty()) {
            throw new Exception("ImageReader did not return " + seenLocations);
        }
    }

    public long getJavaLauncherExecutionTime() {
        return javaExecutionTime;
    }

    public long getModuleLauncherExecutionTime() {
        return moduleExecutionTime;
    }

    public static void readClass(byte[] clazz) throws Exception {
        try (InputStream stream = new ByteArrayInputStream(clazz);) {
            ClassFile.read(stream);
        }
    }
}
