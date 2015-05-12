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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jdk.tools.jlink.internal.DefaultImageBuilderProvider;
import jdk.tools.jlink.internal.ImagePluginConfiguration;
import tests.JImageValidator;

/*
 * @test
 * @summary Test image creation
 * @author Jean-Francois Denise
 * @library /lib/testlibrary/jlink
 * @modules java.base/jdk.internal.jimage
 *          jdk.compiler/com.sun.tools.classfile
 *          jdk.jlink/jdk.tools.jlink
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jmod
 *          jdk.jlink/jdk.tools.jimage
 * @build tests.JImageGenerator tests.JImageValidator
 * @run main JLink2Test
 */
public class JLink2Test {

    public static void main(String[] args) throws Exception {
        Helper helper = Helper.newHelper();
        if (helper == null) {
           System.err.println("Test not run");
            return;
        }
        // failing tests
        boolean failed = false;
        try {
            helper.generateJModule("failure", "leaf5", "java.COCO");
            helper.checkImage("failure", null, null, null);
            failed = true;
        } catch (Exception ex) {
            System.err.println("OK, Got expected exception " + ex);
            // XXX OK expected
        }
        if (failed) {
            throw new Exception("Image creation should have failed");
        }

        try {
            helper.checkImage("failure2", null, null, null);
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
        helper.getGenerator().generateJarModule("amodule", jarClasses);
        helper.getGenerator().generateJModule("amodule", jmodsClasses);
        List<String> okLocations = new ArrayList<>();
        okLocations.addAll(Helper.toLocation("amodule", jmodsClasses));
        File image = helper.generateImage(null, "amodule");
        JImageValidator validator = new JImageValidator("amodule", okLocations,
                 image, Collections.<String>emptyList(),
                Collections.<String>emptyList());
        validator.validate();


        // Customize generated image
        {
            File f = new File("plugins.properties");
            f.createNewFile();
            try (FileOutputStream stream = new FileOutputStream(f);) {
                String fileName = "toto.jimage";
                String content = DefaultImageBuilderProvider.NAME + "."
                        + DefaultImageBuilderProvider.JIMAGE_NAME_PROPERTY + "=" + fileName;
                stream.write(content.getBytes());
                String[] userOptions = {"--plugins-configuration", f.getAbsolutePath()};
                helper.generateJModule("totoimagemodule", "composite2");
                File img = helper.generateImage(userOptions, "totoimagemodule");
                File imgFile = new File(img, "lib" + File.separator + "modules"
                        + File.separator + fileName);
                if (!imgFile.exists()) {
                    throw new Exception("Expected file doesn't exist " + imgFile);
                }
            }
        }

        // Bom file
        {
            File fplugins = new File("plugins.properties");
            fplugins.createNewFile();
            try (FileOutputStream stream = new FileOutputStream(fplugins)) {
                String content = ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY
                        + "=zip\n";
                stream.write(content.getBytes());
                File defaults = new File("embedded.properties");
                defaults.createNewFile();
                try (FileOutputStream defaultsstream = new FileOutputStream(defaults);) {
                    String defaultscontent = "jdk.jlink.defaults=--genbom "
                            + "--exclude-resources *.jcov,*/META-INF/* --addmods UNKNOWN\n";
                    defaultsstream.write(defaultscontent.getBytes());
                }
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
                try (FileInputStream bomstream = new FileInputStream(bom)) {
                    String bomcontent = new String(Helper.readAllBytes(bomstream));
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
            }
        }
        // Replace jvm.cfg and jvm.hprof.txt with some content
        // having an header to check against
        {
            String header = "# YOU SHOULD FIND ME\n";
            File jvmcfg = new File(System.getProperty("java.home")
                    + File.separator + "lib" + File.separator + "jvm.cfg");
            File jvmhprof = new File(System.getProperty("java.home")
                    + File.separator + "lib" + File.separator + "jvm.hprof.txt");
            if (jvmcfg.exists() && jvmhprof.exists()) {
                try (FileInputStream cfgstream = new FileInputStream(jvmcfg);
                     FileInputStream hprofstream = new FileInputStream(jvmhprof); ) {
                    String cfgcontent = header + new String(Helper.readAllBytes(cfgstream));
                    File cfgnewContent = File.createTempFile("jvmcfgtest", null);
                    cfgnewContent.deleteOnExit();
                    try (FileOutputStream out = new FileOutputStream(cfgnewContent)) {
                        out.write(cfgcontent.getBytes());
                    }

                    String hprofcontent = header + new String(Helper.readAllBytes(hprofstream));
                    File hprofnewContent = File.createTempFile("hproftest", null);
                    hprofnewContent.deleteOnExit();
                    try (FileOutputStream out = new FileOutputStream(hprofnewContent)) {
                        out.write(hprofcontent.getBytes());
                    }

                    String[] userOptions = {"--replace-file",
                        "/java.base/native/jvm.cfg,"
                        + cfgnewContent.getAbsolutePath()
                        + ",/jdk.hprof.agent/native/jvm.hprof.txt,"
                        + hprofnewContent.getAbsolutePath()};
                    helper.generateJModule("jvmcfg", "composite2", "jdk.hprof.agent");
                    File imageDir = helper.checkImage("jvmcfg", userOptions,
                            null, null);
                    File jvmcfg2 = new File(imageDir, "lib" + File.separator +
                            "jvm.cfg");
                    checkFile(header, jvmcfg2);

                    File hprof2 = new File(imageDir, "lib" + File.separator +
                            "jvm.hprof.txt");
                    checkFile(header, hprof2);

                }
            } else {
                System.err.println("Warning, jvm.cfg or jvm.hprof.txt files not found, "
                        + "file replacement not checked");
            }
        }
    }

    private static void checkFile(String header, File file) throws Exception {
        if (!file.exists()) {
            throw new RuntimeException(file.getAbsolutePath() +" not generated");
        }
        try (FileInputStream stream = new FileInputStream(file)) {
            String content2 = new String(Helper.readAllBytes(stream));
            if (!content2.startsWith(header)) {
                throw new Exception("jvm.cfg not replaced with "
                        + "expected content");
            }
        }
    }

}
