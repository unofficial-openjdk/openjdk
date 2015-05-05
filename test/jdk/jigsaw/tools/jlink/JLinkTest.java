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
import jdk.tools.jlink.internal.DefaultImageBuilderProvider;
import jdk.tools.jlink.internal.ImagePluginConfiguration;
import jdk.tools.jlink.plugins.PluginProvider;
import jdk.tools.jlink.internal.ImagePluginProviderRepository;
import tests.JImageGenerator;
import tests.JImageValidator;

/*
 * @test
 * @summary Test jimage creation
 * @author Jean-Francois Denise
 * @library /lib/testlibrary/jlink
 * @modules java.base/jdk.internal.jimage
 *          jdk.compiler/com.sun.tools.classfile
 *          jdk.jlink/jdk.tools.jlink
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jmod
 *          jdk.jlink/jdk.tools.jimage
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
        checkImage(helper, "composite2", null, null, null);

        int num = 0;
        for (PluginProvider pf : ImagePluginProviderRepository.getImageWriterProviders(null)) {
            num += 1;
        }
        if (num != 6) {
            throw new Exception("Plugins not found. " + num);
        }

        //Help
        String[] opts2 = {"--help"};
        jdk.tools.jlink.Main.run(opts2, new PrintWriter(System.out));

        //List plugins
        String[] opts = {"--list-plugins"};
        jdk.tools.jlink.Main.run(opts, new PrintWriter(System.out));

        // configuration
        {
            File f = new File("embedded.properties");
            f.createNewFile();
            try (FileOutputStream stream = new FileOutputStream(f);) {
            String content = "jdk.jlink.defaults=--strip-java-debug on --addmods toto.unknown "
                    + "--compress-resources on\n";
            stream.write(content.getBytes());
            String[] userOptions = {"--configuration", f.getAbsolutePath()};
            generateJModule(helper, "configembeddedcomposite2", "composite2");
            checkImage(helper, "configembeddedcomposite2",
                    userOptions, null, null);
            }
        }

        {
            File f = new File("embedded.properties");
            f.createNewFile();
            try (FileOutputStream stream = new FileOutputStream(f);) {
            String content = "jdk.jlink.defaults=--strip-java-debug on --addmods toto.unknown "
                    + "--compress-resources UNKNOWN\n";
            stream.write(content.getBytes());
            String[] userOptions = {"--configuration", f.getAbsolutePath(),
                "--compress-resources", "off"};
            generateJModule(helper, "configembeddednocompresscomposite2", "composite2");
            checkImage(helper, "configembeddednocompresscomposite2",
                    userOptions, null, null);
            }
        }

        // ZIP
        File f = new File("plugins.properties");
        f.createNewFile();
        try (FileOutputStream stream = new FileOutputStream(f);) {
            String content = "jdk.jlink.plugins.compressor=zip\n";
            stream.write(content.getBytes());
            String[] userOptions = {"--plugins-configuration", f.getAbsolutePath()};
            generateJModule(helper, "zipcomposite", "composite2");
            checkImage(helper, "zipcomposite", userOptions, null, null);
        }

        File f1 = new File("plugins.properties");
        f1.createNewFile();
        try (FileOutputStream stream = new FileOutputStream(f1);) {
            String content = ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY+"=zip\n" +
                    "zip."+ PluginProvider.TOOL_ARGUMENT_PROPERTY +
                    "=*Error.class,*Exception.class, ^/java.base/java/lang/*\n";
            stream.write(content.getBytes());
            String[] userOptions = {"--plugins-configuration", f.getAbsolutePath()};
            generateJModule(helper, "zipfiltercomposite", "composite2");
            checkImage(helper, "zipfiltercomposite", userOptions, null, null);
        }

        // Skip debug
        File f4 = new File("plugins.properties");
        f4.createNewFile();
        try (FileOutputStream stream = new FileOutputStream(f4);) {
            String content = ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY+"=strip-java-debug\n" +
                    "strip-java-debug." + PluginProvider.TOOL_ARGUMENT_PROPERTY + "=" +
                    "on";
            stream.write(content.getBytes());
            String[] userOptions = {"--plugins-configuration", f4.getAbsolutePath()};
            generateJModule(helper, "skipdebugcomposite", "composite2");
            checkImage(helper, "skipdebugcomposite", userOptions, null, null);
        }

        // Skip debug + zip
        File f5 = new File("plugins.properties");
        f5.createNewFile();
        try (FileOutputStream stream = new FileOutputStream(f5);) {
            String content = ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY+"=strip-java-debug\n" +
                    "strip-java-debug." + PluginProvider.TOOL_ARGUMENT_PROPERTY + "=" +
                    "on\n" +
                    ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY+"=zip\n";
            stream.write(content.getBytes());
            String[] userOptions = {"--plugins-configuration", f5.getAbsolutePath()};
            generateJModule(helper, "zipskipdebugcomposite", "composite2");
            checkImage(helper, "zipskipdebugcomposite", userOptions, null, null);
        }

        // Filter out files
        File f6 = new File("plugins.properties");
        f6.createNewFile();
        try (FileOutputStream stream = new FileOutputStream(f6);) {
            String content = ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY+"=exclude-resources\n" +
                    ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY+"=strip-java-debug\n" +
                    "strip-java-debug." + PluginProvider.TOOL_ARGUMENT_PROPERTY + "=" +
                    "on\n"+
                    ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY+"=zip\n" +
                    "exclude-resources."+ PluginProvider.TOOL_ARGUMENT_PROPERTY +
                    "=*.jcov, */META-INF/*\n";
            stream.write(content.getBytes());
            String[] userOptions = {"--plugins-configuration", f6.getAbsolutePath()};
            generateJModule(helper, "excludezipskipdebugcomposite", "composite2");
            String[] res = {".jcov", "/META-INF/"};
            checkImage(helper, "excludezipskipdebugcomposite", userOptions, res, null);
        }

        // Filter out files
        File f7 = new File("plugins.properties");
        f7.createNewFile();
        try (FileOutputStream stream = new FileOutputStream(f7);) {
            String content = ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY+"=exclude-resources\n" +
                    "exclude-resources."+ PluginProvider.TOOL_ARGUMENT_PROPERTY +
                    "=*.jcov, */META-INF/*\n";
            stream.write(content.getBytes());
            String[] userOptions = {"--plugins-configuration", f7.getAbsolutePath()};
            generateJModule(helper, "excludecomposite", "composite2");
            String[] res = {".jcov", "/META-INF/"};
            checkImage(helper, "excludecomposite", userOptions, res, null);
        }

        // Shared UTF_8 Constant Pool entries
        String fact = "compact-cp";
        File f8 = new File("plugins.properties");
        f8.createNewFile();
        try (FileOutputStream stream = new FileOutputStream(f8);) {
            String content = ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY+"=" + fact + "\n";
            stream.write(content.getBytes());
            String[] userOptions = {"--plugins-configuration", f8.getAbsolutePath()};
            generateJModule(helper, "cpccomposite", "composite2");
            checkImage(helper, "cpccomposite", userOptions, null, null);
        }

        File f9 = new File("plugins.properties");
        f9.createNewFile();
        try (FileOutputStream stream = new FileOutputStream(f9);) {
            String content = ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY+".0=" + fact + "\n"
                    + ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY+".1=zip\n";
            stream.write(content.getBytes());
            String[] userOptions = {"--plugins-configuration", f9.getAbsolutePath()};
            generateJModule(helper, "zipcpccomposite", "composite2");
            checkImage(helper, "zipcpccomposite", userOptions, null, null);
        }

        // command line options
        {
            String[] userOptions = {"--exclude-files", "*"+getDebugSymbolsExtension()};
            generateJModule(helper, "excludenativedebugcomposite2", "composite2");
            String[] files = {getDebugSymbolsExtension()};
            checkImage(helper, "excludenativedebugcomposite2", userOptions, null, files);
        }
        // filter out files and resources + Skip debug + compress
        {
            String[] userOptions = {"--compress-resources", "on", "--strip-java-debug", "on",
                "--exclude-resources", "*.jcov, */META-INF/*", "--exclude-files",
                "*"+getDebugSymbolsExtension()};
            generateJModule(helper, "excludezipskipdebugcomposite2", "composite2");
            String[] res = {".jcov", "/META-INF/"};
            String[] files = {getDebugSymbolsExtension()};
            checkImage(helper, "excludezipskipdebugcomposite2", userOptions, res, files);
        }
        // filter out + Skip debug + compress with filter
        {
            String[] userOptions2 = {"--compress-resources", "on", "--compress-resources-filter",
                "^/java.base/*", "--strip-java-debug", "on", "--exclude-resources",
                "*.jcov, */META-INF/*"};
            generateJModule(helper, "excludezipfilterskipdebugcomposite2", "composite2");
            String[] res = {".jcov", "/META-INF/"};
            checkImage(helper, "excludezipfilterskipdebugcomposite2", userOptions2,
                    res, null);
        }

        // default compress
        {
            String[] userOptions = {"--compress-resources", "on"};
            generateJModule(helper, "compresscmdcomposite2", "composite2");
            checkImage(helper, "compresscmdcomposite2", userOptions, null, null);
        }

        {
            String[] userOptions = {"--compress-resources", "on", "--compress-resources-filter",
                "^/java.base/java/lang/*"};
            generateJModule(helper, "compressfiltercmdcomposite2", "composite2");
            checkImage(helper, "compressfiltercmdcomposite2", userOptions, null, null);
        }

        // compress 0
        {
            String[] userOptions = {"--compress-resources", "on", "--compress-resources-level", "0",
                "--compress-resources-filter", "^/java.base/java/lang/*"};
            generateJModule(helper, "compress0filtercmdcomposite2", "composite2");
            checkImage(helper, "compress0filtercmdcomposite2", userOptions, null, null);
        }

        // compress 1
        {
            String[] userOptions = {"--compress-resources", "on", "--compress-resources-level", "1",
                "--compress-resources-filter", "^/java.base/java/lang/*"};
            generateJModule(helper, "compress1filtercmdcomposite2", "composite2");
            checkImage(helper, "compress1filtercmdcomposite2", userOptions, null, null);
        }

        // Standard images
        checkImage(helper, "java.management", null, null, null);

        // failing tests
        boolean failed = false;
        try {
            generateJModule(helper, "failure", "leaf5", "java.COCO");
            checkImage(helper, "failure", null, null, null);
            failed = true;
        } catch (Exception ex) {
            System.err.println("OK, Got expected exception " + ex);
            // XXX OK expected
        }
        if (failed) {
            throw new Exception("Image creation should have failed");
        }

        try {
            checkImage(helper, "failure2", null, null, null);
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
                 image, Collections.<String>emptyList(),
                Collections.<String>emptyList());
        validator.validate();


        // Customize generated image
        File f11 = new File("plugins.properties");
        f11.createNewFile();
        try (FileOutputStream stream = new FileOutputStream(f11);) {
            String fileName = "toto.jimage";
            String content = DefaultImageBuilderProvider.NAME+"." +
                    DefaultImageBuilderProvider.JIMAGE_NAME_PROPERTY + "=" + fileName;
            stream.write(content.getBytes());
            String[] userOptions = {"--plugins-configuration", f9.getAbsolutePath()};
            generateJModule(helper, "totoimagemodule", "composite2");
            File img = helper.generateImage(userOptions, "totoimagemodule");
            File imgFile = new File(img, "lib" + File.separator + "modules" +
                    File.separator + fileName);
            if (!imgFile.exists()) {
                throw new Exception("Expected file doesn't exist " + imgFile);
            }
        }
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
                                   String[] userOptions, String[] paths,
                                   String[] files)
        throws Exception
    {
        List<String> unexpectedPaths = new ArrayList<>();
        if (paths != null) {
            for (String un : paths) {
                unexpectedPaths.add(un);
            }
        }
        List<String> unexpectedFiles = new ArrayList<>();
        if (files != null) {
            for (String un : files) {
                unexpectedFiles.add(un);
            }
        }
        File image = helper.generateImage(userOptions, module);
        List<String> expectedLocations = new ArrayList<>();
        expectedLocations.addAll(bootClasses);
        List<String> appClasses = moduleDependencies.get(module);
        if (appClasses != null) {
            expectedLocations.addAll(appClasses);
        }
        JImageValidator validator = new JImageValidator(module, expectedLocations,
                image,
                unexpectedPaths,
                unexpectedFiles);
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

    private static String getDebugSymbolsExtension() {
        String s = System.getProperty("os.name");
        if(s.startsWith("Mac OS")) {
            return ".diz";
        } else {
            System.out.println("WARNING no debug extension for OS, update test");
            return ".unknown";
        }
    }
}
