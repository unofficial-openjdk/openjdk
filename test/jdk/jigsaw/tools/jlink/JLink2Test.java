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
 * @summary Test image creation
 * @author Jean-Francois Denise
 * @library ../lib
 * @modules java.base/jdk.internal.jimage
 *          jdk.jdeps/com.sun.tools.classfile
 *          jdk.jlink/jdk.tools.jlink
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jmod
 *          jdk.jlink/jdk.tools.jimage
 * @build tests.JImageGenerator tests.JImageValidator
 * @run main/othervm -verbose:gc -Xmx1g JLink2Test
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import jdk.tools.jlink.internal.DefaultImageBuilderProvider;
import jdk.tools.jlink.internal.ImagePluginConfiguration;
import tests.JImageGenerator;
import tests.JImageGenerator.InMemoryFile;
import tests.JImageValidator;

public class JLink2Test {

    public static void main(String[] args) throws Exception {
        Helper helper = Helper.newHelper();
        if (helper == null) {
           System.err.println("Test not run");
            return;
        }

        testNegative(helper);
        testSameNames(helper);
        testCustomization(helper);
        testBomFile(helper);
        testFileReplacement(helper);
        //testModulePath(helper);
    }

    private static void testModulePath(Helper helper) throws IOException {
        File unknownDir = createNewImageDir("jar");
        Path jar = Paths.get("jars").resolve("bad.jar");
        Helper.assertFailure("--plugins-modulepath", unknownDir.toString(), "--help");
        Files.createFile(jar);
        Helper.assertFailure("--plugins-modulepath", jar.toString(), "--help");
        Helper.assertFailure("--plugins-modulepath", jar.getParent().toString(), "--help");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
            JarEntry entry = new JarEntry("class");
            out.putNextEntry(entry);
            out.write("AAAA".getBytes());
            out.closeEntry();
        }
        Helper.assertFailure("--plugins-modulepath", jar.getParent().toString(), "--output", createNewImageDir("crash").toString(),
                        "--modulepath", jar.getParent().toString() + File.pathSeparator + helper.getJModsDir(), "--addmods", "bad");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
            JarEntry entry = new JarEntry("classes");
            out.putNextEntry(entry);
            out.closeEntry();

            entry = new JarEntry("classes/class");
            out.putNextEntry(entry);
            out.write("AAAA".getBytes());
            out.closeEntry();
        }
        Helper.assertFailure("--plugins-modulepath", jar.getParent().toString(), "--output", createNewImageDir("crash").toString(),
                "--modulepath", jar.getParent().toString() + File.pathSeparator + helper.getJModsDir(), "--addmods", "bad");
    }

    private static void testSameNames(Helper helper) throws Exception {
        // Multiple modules with the same name in modulepath, take the first one in the path.
        // First jmods then jars. So jmods are found, jars are hidden.
        String[] jarClasses = {"amodule.jar.Main"};
        String[] jmodsClasses = {"amodule.jmods.Main"};
        helper.getGenerator().generateJarModule("amodule", jarClasses);
        helper.getGenerator().generateJModule("amodule", jmodsClasses);
        List<String> okLocations = new ArrayList<>();
        okLocations.addAll(Helper.toLocation("amodule", jmodsClasses));
        File image = helper.generateImage("amodule").getImageFile();
        JImageValidator validator = new JImageValidator("amodule", okLocations,
                 image, Collections.emptyList(), Collections.emptyList());
        validator.validate();
    }

    private static void testCustomization(Helper helper) throws Exception {
        File f = new File("plugins.properties");
        String fileName = "toto.jimage";
        Files.write(f.toPath(), (DefaultImageBuilderProvider.NAME + "."
                + DefaultImageBuilderProvider.JIMAGE_NAME_PROPERTY + "=" + fileName).getBytes());
        String[] userOptions = {"--plugins-configuration", f.getAbsolutePath()};
        helper.generateJModule("totoimagemodule", "composite2");
        File img = helper.generateImage(userOptions, "totoimagemodule").getImageFile();
        File imgFile = new File(img, "lib" + File.separator + "modules" + File.separator + fileName);
        if (!imgFile.exists()) {
            throw new RuntimeException("Expected file doesn't exist " + imgFile);
        }
    }

    private static void testBomFile(Helper helper) throws Exception {
        File fplugins = new File("plugins.properties");
        Files.write(fplugins.toPath(),
                (ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY + "=zip\n").getBytes());
        File defaults = new File("embedded.properties");
        Files.write(defaults.toPath(), ("jdk.jlink.defaults=--genbom --exclude-resources *.jcov,*/META-INF/*" +
                        " --addmods UNKNOWN\n").getBytes());
        String[] userOptions = {"--plugins-configuration",
                fplugins.getAbsolutePath(),
                "--strip-java-debug", "on",
                "--configuration", defaults.getAbsolutePath()};
        helper.generateJModule("bomzip", "composite2");
        File imgDir = helper.checkImage("bomzip", userOptions, null,
                null);
        File bom = new File(imgDir, "bom");
        if (!bom.exists()) {
            throw new RuntimeException(bom.getAbsolutePath() + " not generated");
        }
        String bomcontent = new String(Files.readAllBytes(bom.toPath()));
        if (!bomcontent.contains("--strip-java-debug")
                || !bomcontent.contains("--plugins-configuration")
                || !bomcontent.contains(fplugins.getAbsolutePath())
                || !bomcontent.contains("--genbom")
                || !bomcontent.contains(ImagePluginConfiguration.
                        RESOURCES_COMPRESSOR_PROPERTY)
                || !bomcontent.contains("zip")
                || !bomcontent.contains("--exclude-resources *.jcov,"
                        + "*/META-INF/*")
                || !bomcontent.contains("--configuration")
                || !bomcontent.contains(defaults.getAbsolutePath())
                || !bomcontent.contains("--addmods UNKNOWN")) {
            throw new Exception("Not expected content in " + bom);
        }
    }

    private static void testFileReplacement(Helper helper) throws Exception {
        // Replace jvm.cfg and jvm.hprof.txt with some content
        // having an header to check against
        String header = "# YOU SHOULD FIND ME\n";
        File jvmcfg = new File(System.getProperty("java.home")
                + File.separator + "lib" + File.separator + "jvm.cfg");
        File jvmhprof = new File(System.getProperty("java.home")
                + File.separator + "lib" + File.separator + "jvm.hprof.txt");
        if (jvmcfg.exists() && jvmhprof.exists()) {
            String cfgcontent = header + new String(Files.readAllBytes(jvmcfg.toPath()));
            File cfgNewContent = File.createTempFile("jvmcfgtest", null);
            cfgNewContent.deleteOnExit();
            Files.write(cfgNewContent.toPath(), cfgcontent.getBytes());

            String hprofcontent = header + new String(Files.readAllBytes(jvmhprof.toPath()));
            File hprofNewContent = File.createTempFile("hproftest", null);
            hprofNewContent.deleteOnExit();
            Files.write(hprofNewContent.toPath(), hprofcontent.getBytes());

            String[] userOptions = {"--replace-file",
                "/java.base/native/jvm.cfg,"
                + cfgNewContent.getAbsolutePath()
                + ",/jdk.hprof.agent/native/jvm.hprof.txt,"
                + hprofNewContent.getAbsolutePath()};
            helper.generateJModule("jvmcfg", "composite2", "jdk.hprof.agent");
            File imageDir = helper.checkImage("jvmcfg", userOptions,
                    null, null);
            File jvmcfg2 = new File(imageDir, "lib" + File.separator +
                    "jvm.cfg");
            checkFile(header, jvmcfg2);

            File hprof2 = new File(imageDir, "lib" + File.separator +
                    "jvm.hprof.txt");
            checkFile(header, hprof2);
        } else {
            System.err.println("Warning, jvm.cfg or jvm.hprof.txt files not found, "
                    + "file replacement not checked");
        }
    }

    private static void testNegative(Helper helper) throws IOException {
        try {
            helper.generateJModule("failure", "leaf5", "java.COCO");
            helper.checkImage("failure", null, null, null);
            throw new RuntimeException("Image creation should have failed");
        } catch (IOException ex) {
            System.err.println("OK, Got expected exception " + ex);
        }

        try {
            helper.checkImage("failure2", null, null, null);
            throw new RuntimeException("Image creation should have failed");
        } catch (IOException ex) {
            System.err.println("OK, Got expected exception " + ex);
        }

        // cannot find jmod
        //helper.assertFailure(".", "images", "failure3", null, "--addmods", "failure3");

        // module is not found
        /*helper.assertFailure(JImageGenerator.createNewFile(new File("."), "modulepath", "").getAbsolutePath(),
                "images", "failure3", null, "--addmods", "failure3");*/
        {
            helper.generateJModule("failure3", "composite2");
            File image = helper.generateImage("failure3").getImageFile();
            helper.assertFailure("jmods", image, "failure3", "Error: not empty: .*images/failure3.image\n");
        }
        {
            // output == file
            /*Path image = createNewImageDir("failure4").toPath();
            Files.createFile(image);
            helper.assertFailure("jmods", image.toFile(), "failure4", "Error: not empty: .*images/failure4.image\n");*/
        }
        {
            // limit module is not found
            /*File imageFile = createNewImageDir("test");
            helper.assertFailure("--output", imageFile, "--addmods", "leaf1",
                    "--limitmods", "leaf1,failure5,java.base",
                    "--modulepath", "jmods" + File.pathSeparator + helper.getJModsDir());*/
        }
        {
            // added module is filtered out
            /*File imageFile = createNewImageDir("test");
            helper.assertFailure("--output", imageFile, "--addmods", "leaf1",
                    "--limitmods", "leaf2", "--modulepath",
                    "jmods" + File.pathSeparator + helper.getJModsDir());*/
        }
        {
            // malformed jars and jmods
            /*File imageFile = createNewImageDir("test");
            Path jmod = JImageGenerator.createNewFile(new File("jmods"), "not_zip", ".jmod").toPath();
            Files.createFile(jmod);
            Path jar = JImageGenerator.createNewFile(new File("jmods"), "not_zip", ".jar").toPath();
            Files.createFile(jar);
            Path dirJmod = JImageGenerator.createNewFile(new File("jmods"), "dir", ".jmod").toPath();
            Files.createDirectory(dirJmod);
            Path dirJar = JImageGenerator.createNewFile(new File("jmods"), "dir", ".jar").toPath();
            Files.createDirectory(dirJar);
            helper.assertFailure("--output", imageFile,
                    "--addmods", "leaf1", "--limit-mods", "leaf2", "--modulepath",
                    "jmods" + File.pathSeparator + helper.getJModsDir());*/
        }
        {
            // add some top-level files
            /*File module = helper.generateJModule("hacked1", "leaf1", "leaf2");
            helper.getGenerator().addFiles(module, new InMemoryFile("A.class", new byte[0]));
            helper.checkImage("hacked1", null, new String[] {"A.class"}, null);*/
        }
        {
            // add default package
            /*File module = helper.generateJModule("hacked2");
            helper.getGenerator().addFiles(module,
                    new InMemoryFile("classes/JLink2Test.class", JLink2Test.class.getResourceAsStream("JLink2Test.class")));
            helper.checkImage("hacked2", null, new String[] {"/hacked2/JLink2Test.class"}, null);*/
        }
        {
            // add some non-standard sections
            /*File module = helper.generateJModule("hacked3", "leaf1", "leaf2");
            helper.getGenerator().addFiles(module, new InMemoryFile("unknown/A.class", new byte[0]));
            helper.checkImage("hacked3", null, new String[] {"unknown/A.class"}, null);*/
        }
    }

    private static File createNewImageDir(String imageName) {
        return JImageGenerator.createNewFile(new File("images"), imageName, ".image");
    }

    private static void checkFile(String header, File file) throws IOException {
        if (!file.exists()) {
            throw new RuntimeException(file.getAbsolutePath() +" not generated");
        }
        String content = new String(Files.readAllBytes(file.toPath()));
        if (!content.startsWith(header)) {
            throw new AssertionError("jvm.cfg not replaced with "
                    + "expected content");
        }
    }

}
