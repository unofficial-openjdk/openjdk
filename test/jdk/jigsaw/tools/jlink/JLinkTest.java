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
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import jdk.jigsaw.tools.jlink.plugins.PluginProvider;
import jdk.jigsaw.tools.jlink.internal.ImagePluginProviderRepository;
import tests.JImageGenerator;
import tests.JImageValidator;

/*
 * @test
 * @summary Test jimage creation
 * @author Jean-Francois Denise
 * @library /lib/testlibrary/jlink
 * @modules jdk.jlink/jdk.tools.jimage jdk.jlink/jdk.jigsaw.tools.jlink.internal
 * jdk.jlink/jdk.jigsaw.tools.jlink jdk.jlink/jdk.jigsaw.tools.jmod jdk.compiler/com.sun.tools.classfile
 * java.base/jdk.internal.jimage
 * @build tests.JImageGenerator tests.JImageValidator
 * @run main JLinkTest
 */
public class JLinkTest {

    private static final Map<String, List<String>> moduleDependencies =
            new HashMap<>();

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
        generateJModule(helper, "composite2", "composite1", "leaf3", "leaf5",
                "java.management");

        // Images with app modules
        checkImage(helper, "composite2", null);

        int num = 0;
        for (PluginProvider pf : ImagePluginProviderRepository.getImageWriterProviders(null)) {
            num += 1;
        }
        if (num != 3) {
            throw new Exception("Plugins not found. " + num);
        }

        //List plugins
        String[] opts = {"--list-plugins"};
        jdk.jigsaw.tools.jlink.Main.run(opts, new PrintWriter(System.out));

        // ZIP
        File f = new File("plugins.properties");
        f.createNewFile();
        try (FileOutputStream stream = new FileOutputStream(f);) {
            String content = "jdk.jlink.plugins.compressor=zip\n";
            stream.write(content.getBytes());
            String[] userOptions = {"--plugins-configuration", f.getAbsolutePath()};
            generateJModule(helper, "zipcomposite", "composite2");
            checkImage(helper, "zipcomposite", userOptions);
        }

        // Skip debug
        File f4 = new File("plugins.properties");
        f4.createNewFile();
        try (FileOutputStream stream = new FileOutputStream(f4);) {
            String content = "jdk.jlink.plugins.transformer=strip-debug\n";
            stream.write(content.getBytes());
            String[] userOptions = {"--plugins-configuration", f4.getAbsolutePath()};
            generateJModule(helper, "skipdebugcomposite", "composite2");
            checkImage(helper, "skipdebugcomposite", userOptions);
        }

        // Skip debug + zip
        File f5 = new File("plugins.properties");
        f5.createNewFile();
        try (FileOutputStream stream = new FileOutputStream(f5);) {
            String content = "jdk.jlink.plugins.transformer=strip-debug\n" +
                    "jdk.jlink.plugins.compressor=zip\n";
            stream.write(content.getBytes());
            String[] userOptions = {"--plugins-configuration", f5.getAbsolutePath()};
            generateJModule(helper, "zipskipdebugcomposite", "composite2");
            checkImage(helper, "zipskipdebugcomposite", userOptions);
        }

        // Filter out files
        File f6 = new File("plugins.properties");
        f6.createNewFile();
        try (FileOutputStream stream = new FileOutputStream(f6);) {
            String content = "jdk.jlink.plugins.filter=exclude\n" +
                    "jdk.jlink.plugins.transformer=strip-debug\n" +
                    "jdk.jlink.plugins.compressor=zip\n" +
                    "exclude.configuration=*.jcov, */META-INF/*\n";
            stream.write(content.getBytes());
            String[] userOptions = {"--plugins-configuration", f6.getAbsolutePath()};
            generateJModule(helper, "excludezipskipdebugcomposite", "composite2");
            checkImage(helper, "excludezipskipdebugcomposite", userOptions, ".jcov",
                    "/META-INF/");
        }

        // Filter out files
        File f7 = new File("plugins.properties");
        f7.createNewFile();
        try (FileOutputStream stream = new FileOutputStream(f7);) {
            String content = "jdk.jlink.plugins.filter=exclude\n" +
                    "exclude.configuration=*.jcov, */META-INF/*\n";
            stream.write(content.getBytes());
            String[] userOptions = {"--plugins-configuration", f7.getAbsolutePath()};
            generateJModule(helper, "excludecomposite", "composite2");
            checkImage(helper, "excludecomposite", userOptions, ".jcov", "/META-INF/");
        }

        // filter out + Skip debug + zip
        String[] userOptions = {"--compress", "--strip-debug", "--exclude",
            "*.jcov, */META-INF/*"};
        generateJModule(helper, "excludezipskipdebugcomposite2", "composite2");
        checkImage(helper, "excludezipskipdebugcomposite2", userOptions,
                ".jcov", "/META-INF/");

        // Standard images
        checkImage(helper, "java.management", null);

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
        JImageValidator validator = new JImageValidator("amodule", okLocations,
                 image, Collections.<String>emptyList());
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
        List<String> appClasses = new ArrayList<>();
        for (String clazz : toLocation(module, classes)) {
            appClasses.add(clazz);
        }
        String moduleClazz = toLocation(module, "module-info");
        appClasses.add(moduleClazz);
        moduleDependencies.put(module, appClasses);
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
                                   String[] userOptions, String... unexpectedFiles)
        throws Exception
    {
        List<String> unexpectedPaths = new ArrayList<>();
        for (String un : unexpectedFiles) {
            unexpectedPaths.add(un);
        }
        File image = helper.generateImage(userOptions, module);
        List<String> expectedLocations = new ArrayList<>();
        expectedLocations.addAll(bootClasses);
        List<String> appClasses = moduleDependencies.get(module);
        if (appClasses != null) {
            expectedLocations.addAll(appClasses);
        }
        JImageValidator validator = new JImageValidator(module, expectedLocations,
                                                        image, unexpectedPaths);
        System.out.println("*** Validate Image " + module);
        validator.validate();
        long moduleExecutionTime = validator.getModuleLauncherExecutionTime();
        if(moduleExecutionTime != 0) {
            System.out.println("Module launcher execution time " + moduleExecutionTime);
        }
        System.out.println("Java launcher execution time " +
                validator.getJavaLauncherExecutionTime());
        System.out.println("***");
    }
}
