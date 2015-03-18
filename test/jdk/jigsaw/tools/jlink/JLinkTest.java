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
import java.io.File;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import tests.JImageGenerator;
import tests.JImageValidator;

/*
 * @test
 * @summary Test jimage creation
 * @author Jean-Francois Denise
 * @library /lib/testlibrary/jlink
 * @modules jdk.jlink/jdk.jigsaw.tools.jlink jdk.jlink/jdk.jigsaw.tools.jmod jdk.compiler/com.sun.tools.classfile java.base/jdk.internal.jimage
 * @build tests.JImageGenerator tests.JImageValidator
 * @run main JLinkTest
 */
public class JLinkTest {

    private static final List<String> bootClasses = new ArrayList<>();
    private static final List<String> appClasses = new ArrayList<>();

    public static void main(String[] args) throws Exception {

        FileSystem fs;
        try {
            fs = FileSystems.getFileSystem(URI.create("jrt:/"));
        } catch (ProviderNotFoundException | FileSystemNotFoundException e) {
            System.out.println("Not an image build, test skipped.");
            return;
        }

        Consumer<Path> c = (p) -> {
               // take only the .class resources.
               if (Files.isRegularFile(p) && p.toString().endsWith(".class")
                       && !p.toString().endsWith("module-info.class")) {
                   String loc = p.toString().substring("/modules".length());
                   bootClasses.add(loc);
               }
           };

        Path javabase = fs.getPath("/modules/java.base");
        Path mgtbase = fs.getPath("/modules/java.management");
        try (Stream<Path> stream = Files.walk(javabase)) {
            stream.forEach(c);
        }
        try (Stream<Path> stream = Files.walk(mgtbase)) {
            stream.forEach(c);
        }

        System.out.println("Num of boot classes to check against " + bootClasses.size());
        if (bootClasses.isEmpty()) {
            throw new RuntimeException("No boot class to check against");
        }

        File jdkHome = new File(System.getProperty("test.jdk"));
        // JPRT not yet ready for jmods
        if (JImageGenerator.getJModsDir(jdkHome) == null) {
            System.err.println("Test not run, NO jmods directory");
            return;
        }

        JImageGenerator helper = new JImageGenerator(new File("."), jdkHome);

        generateJModule(helper, "leaf1");
        generateJModule(helper, "leaf2");
        generateJModule(helper, "leaf3");

        generateJarModule(helper, "leaf4");
        generateJarModule(helper, "leaf5");

        generateJarModule(helper, "composite1", "leaf1", "leaf2", "leaf4");
        generateJModule(helper, "composite2", "composite1", "leaf3", "leaf5", "java.management");

        // Images with app modules
        checkImage(helper, "composite2", null);
        String[] userOptions = {"--compress"};
        checkImage(helper, "composite2", userOptions);

        // Generate a new module and associated image.
        generateJModule(helper, "composite3", "composite2");
        checkImage(helper, "composite3", null);

        // Standard images
        appClasses.clear();
        checkImage(helper, "java.se", null);

        // failing tests
        boolean failed = false;
        try {
            generateJModule(helper, "failure", "leaf5", "java.COCO");
            checkImage(helper, "failure", null);
            failed = true;
        } catch (Exception ex) {
            System.err.println("OK, Got expected exception " + ex);
            // XXX OK expected
        }
        if (failed) {
            throw new Exception("Image creation should have failed");
        }

        try {
            checkImage(helper, "failure2", null);
            failed = true;
        } catch (Exception ex) {
            // XXX OK expected
        }
        if (failed) {
            throw new Exception("Image creation should have failed");
        }

        // Multiple modules with the same name in modulepath, take the first one in the path.
        // First jmods then jars. So jmods are found, jars are hidden.
        String[] jarClasses = {"amodule.jar.Main"};
        String[] jmodsClasses = {"amodule.jmods.Main"};
        helper.generateJarModule("amodule", jarClasses);
        helper.generateJModule("amodule", jmodsClasses);
        List<String> okLocations = new ArrayList<>();
        okLocations.addAll(toLocation("amodule", jmodsClasses));
        File image = helper.generateImage(null, "amodule");
        JImageValidator validator = new JImageValidator(okLocations, image);
        validator.validate();
    }

    private static void generateJModule(JImageGenerator helper,
                                        String module,
                                        String... dependencies)
        throws Exception
    {
        helper.generateJModule(module, getClasses(module), dependencies);
    }

    private static void generateJarModule(JImageGenerator helper,
                                          String module,
                                          String... dependencies)
        throws Exception
    {
        helper.generateJarModule(module, getClasses(module), dependencies);
    }

    private static String[] getClasses(String module) {
        String[] classes = {module + ".Main", module + ".com.foo.bar.X"};
        for (String clazz : toLocation(module, classes)) {
            appClasses.add(clazz);
        }
        String moduleClazz = toLocation(module, "module-info");
        appClasses.add(moduleClazz);

        return classes;
    }

    private static String toLocation(String module, String className) {
        return "/" + module + "/" + className.replaceAll("\\.", "/") + ".class";
    }

    private static List<String> toLocation(String module, String[] classNames) {
        List<String> classes = new ArrayList<>();
        for (String clazz : classNames) {
            classes.add(toLocation(module, clazz));
        }
        return classes;
    }

    private static void checkImage(JImageGenerator helper,
                                   String module,
                                   String[] userOptions)
        throws Exception
    {
        File image = helper.generateImage(userOptions, module);
        List<String> expectedLocations = new ArrayList<>();
        expectedLocations.addAll(bootClasses);
        expectedLocations.addAll(appClasses);
        JImageValidator validator = new JImageValidator(expectedLocations,
                                                        image);
        validator.validate();
        appClasses.clear();
        System.out.println("*** Image " + module);
        System.out.println(validator.getResourceExtractionTime() +
            "ms, Average time to extract " + validator.getNumberOfResources() +
            " compressed resources " + validator.getAverageResourceExtractionTime() * 1000 +
            "microsecs");
        System.out.println(validator.getResourceTime() + "ms total time, Average time " +
            validator.getNumberOfResources() + " compressed resources " +
            validator.getAverageResourceTime() * 1000 + "microsecs");
    }
}
