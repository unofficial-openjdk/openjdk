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
 * @build tests.*
 * @run main/othervm -verbose:gc -Xmx1g JLink2Test
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import jdk.tools.jlink.plugins.DefaultImageBuilderProvider;
import jdk.tools.jlink.internal.ImagePluginConfiguration;
import tests.Helper;
import tests.JImageGenerator;
import tests.JImageValidator;

public class JLink2Test {

    public static void main(String[] args) throws Exception {
        Helper helper = Helper.newHelper();
        if (helper == null) {
           System.err.println("Test not run");
            return;
        }
        helper.generateDefaultModules();

        testSameNames(helper);
        testBomFile(helper);
        testFileReplacement(helper);
        testModulePath(helper);
    }

    private static void testModulePath(Helper helper) throws IOException {
        Path unknownDir = helper.createNewImageDir("jar");
        Path jar = helper.getJarDir().resolve("bad.jar");
        JImageGenerator.getJLinkTask()
                .pluginModulePath(unknownDir)
                .option("--help")
                .call().assertFailure("(\n|\r|.)*Error: java.nio.file.NoSuchFileException: .*jar.image(\n|\r|.)*");
        Files.createFile(jar);
        JImageGenerator.getJLinkTask()
                .pluginModulePath(jar)
                .option("--help")
                .call().assertFailure("(\n|\r|.)*Error: java.nio.file.NotDirectoryException: .*bad.jar(\n|\r|.)*");
        JImageGenerator.getJLinkTask()
                .pluginModulePath(jar.getParent())
                .option("--help")
                .call().assertFailure("Error: java.util.zip.ZipException: zip file is empty");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
            JarEntry entry = new JarEntry("class");
            out.putNextEntry(entry);
            out.write("AAAA".getBytes());
            out.closeEntry();
        }
        JImageGenerator.getJLinkTask()
                .pluginModulePath(jar.getParent())
                .output(helper.createNewImageDir("crash"))
                .addJmods(helper.getStdJmodsDir())
                .addJmods(jar.getParent())
                .addMods("bad")
                .call().assertFailure("Error: java.io.IOException: module-info not found for bad");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
            JarEntry entry = new JarEntry("classes");
            out.putNextEntry(entry);
            out.closeEntry();

            entry = new JarEntry("classes/class");
            out.putNextEntry(entry);
            out.write("AAAA".getBytes());
            out.closeEntry();
        }
        JImageGenerator.getJLinkTask()
                .pluginModulePath(jar.getParent())
                .output(helper.createNewImageDir("bad"))
                .addJmods(jar.getParent())
                .addJars(helper.getStdJmodsDir())
                .addMods("bad")
                .call().assertFailure("Error: java.io.IOException: module-info not found for bad");
    }

    private static void testSameNames(Helper helper) throws Exception {
        // Multiple modules with the same name in modulepath, take the first one in the path.
        // First jmods then jars. So jmods are found, jars are hidden.
        String[] jarClasses = {"amodule.jar.Main"};
        String[] jmodsClasses = {"amodule.jmods.Main"};
        helper.generateDefaultJarModule("amodule", Arrays.asList(jarClasses));
        helper.generateDefaultJModule("amodule", Arrays.asList(jmodsClasses));
        List<String> okLocations = new ArrayList<>();
        okLocations.addAll(Helper.toLocation("amodule", Arrays.asList(jmodsClasses)));
        Path image = helper.generateDefaultImage(new String[0], "amodule").assertSuccess();
        JImageValidator validator = new JImageValidator("amodule", okLocations,
                 image.toFile(), Collections.emptyList(), Collections.emptyList());
        validator.validate();
    }

    private static void testBomFile(Helper helper) throws Exception {
        File defaults = new File("embedded.properties");
        Files.write(defaults.toPath(), ("jdk.jlink.defaults=--genbom --exclude-resources *.jcov,*/META-INF/*" +
                " --addmods UNKNOWN\n").getBytes());
        String[] userOptions = {"--zip",
            "*",                "--strip-java-debug", "on",
                "--configuration", defaults.getAbsolutePath()};
        String moduleName = "bomzip";
        helper.generateDefaultJModule(moduleName, "composite2");
        Path imgDir = helper.generateDefaultImage(userOptions, moduleName).assertSuccess();
        helper.checkImage(imgDir, moduleName, userOptions, null, null);
        File bom = new File(imgDir.toFile(), "bom");
        if (!bom.exists()) {
            throw new RuntimeException(bom.getAbsolutePath() + " not generated");
        }
        String bomcontent = new String(Files.readAllBytes(bom.toPath()));
        if (!bomcontent.contains("--strip-java-debug")
                || !bomcontent.contains("--zip")
                || !bomcontent.contains("*")
                || !bomcontent.contains("--genbom")
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
            String moduleName = "jvmcfg";
            helper.generateDefaultJModule(moduleName, "composite2", "jdk.hprof.agent");
            Path imageDir = helper.generateDefaultImage(userOptions, moduleName).assertSuccess();
            helper.checkImage(imageDir, moduleName, null, null);
            Path jvmcfg2 = imageDir.resolve("lib").resolve("jvm.cfg");
            checkFile(header, jvmcfg2);

            Path hprof2 = imageDir.resolve("lib").resolve("jvm.hprof.txt");
            checkFile(header, hprof2);
        } else {
            System.err.println("Warning, jvm.cfg or jvm.hprof.txt files not found, "
                    + "file replacement not checked");
        }
    }

    private static void checkFile(String header, Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new RuntimeException(file.toAbsolutePath() +" not generated");
        }
        String content = new String(Files.readAllBytes(file));
        if (!content.startsWith(header)) {
            throw new AssertionError("jvm.cfg not replaced with "
                    + "expected content");
        }
    }

}
