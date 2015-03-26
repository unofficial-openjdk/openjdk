/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

import com.sun.tools.classfile.ClassFile;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import jdk.internal.jimage.BasicImageReader;
import jdk.internal.jimage.ImageFile;
import jdk.internal.jimage.ImageLocation;

/**
 *
 * JDK Modular image validator
 */
public class JImageValidator {

    private static final String[] dirs = {"bin", "lib"};

    private static final List<String> EXPECTED_JIMAGES = new ArrayList<>();

    static {
        EXPECTED_JIMAGES.add(ImageFile.BOOT_IMAGE_NAME);
    }

    private final File rootDir;
    private final List<String> expectedLocations;
    private int numResources;
    private long resourceExtractionTime;
    private long totalTime;

    public JImageValidator(List<String> expectedLocations, File rootDir) throws Exception {
        if (!rootDir.exists()) {
            throw new Exception("Image root dir not found " + rootDir.getAbsolutePath());
        }
        this.expectedLocations = expectedLocations;
        this.rootDir = rootDir;
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
                System.out.println("Validating " + f.getName());
                if (!EXPECTED_JIMAGES.contains(f.getName())) {
                    throw new Exception("Unexpected image " + f.getName());
                }
                seenImages.remove(f.getName());
                validate(f);
            }
        }
        if (!seenImages.isEmpty()) {
            throw new Exception("Some images not seen " + seenImages);
        }
    }

    private void validate(File jimage) throws Exception {
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
                long t0 = System.currentTimeMillis();
                numResources++;
                ImageLocation il = reader.findLocation(s);
                long t = System.currentTimeMillis();
                byte[] r = reader.getResource(il);
                long end = System.currentTimeMillis();
                resourceExtractionTime += end - t;
                totalTime += end - t0;
                try {
                    readClass(r);
                } catch (Exception ex) {
                    System.err.println(s + " ERROR " + ex);
                    throw ex;
                }
            }
            if (seenLocations.contains(s)) {
                seenLocations.remove(s);
            }
        }
        if (!seenLocations.isEmpty()) {
            throw new Exception("ImageReader did not return " + seenLocations);
        }
    }

    public int getNumberOfResources() {
        return numResources;
    }

    public long getResourceExtractionTime() {
        return resourceExtractionTime;
    }

    public double getAverageResourceExtractionTime() {
        return (double) resourceExtractionTime / numResources;
    }

    public long getResourceTime() {
        return totalTime;
    }

    public double getAverageResourceTime() {
        return (double) totalTime / numResources;
    }

    private void readClass(byte[] clazz) throws Exception {
        try (InputStream stream = new ByteArrayInputStream(clazz);) {
            ClassFile.read(stream);
        }
    }
}
