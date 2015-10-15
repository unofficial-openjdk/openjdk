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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Layer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import jdk.tools.jlink.TaskHelper;
import jdk.tools.jlink.plugins.PluginProvider;
import jdk.tools.jlink.internal.ImagePluginProviderRepository;
import tests.Helper;
import tests.JImageGenerator;
import tests.JImageGenerator.InMemoryFile;

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
 * @run main/othervm -verbose:gc -Xmx1g JLinkTest
 */
public class JLinkTest {

    public static void main(String[] args) throws Exception {

        Helper helper = Helper.newHelper();
        if (helper == null) {
            System.err.println("Test not run");
            return;
        }
        helper.generateDefaultModules();
        int numPlugins = 11;
        {
            // number of built-in plugins
            List<PluginProvider> builtInPluginsProviders = ImagePluginProviderRepository.getPluginProviders(Layer.boot());
            for (PluginProvider p : builtInPluginsProviders) {
                p.isExposed();
                p.isFunctional();
            }
            if (builtInPluginsProviders.size() != numPlugins) {
                throw new AssertionError("Plugins not found: " + builtInPluginsProviders.size());
            }
        }

        {
            String moduleName = "bug8134651";
            JImageGenerator.getJLinkTask()
                    .modulePath(helper.defaultModulePath())
                    .output(helper.createNewImageDir(moduleName))
                    .addMods("leaf1")
                    .option("")
                    .call().assertSuccess();
            JImageGenerator.getJLinkTask()
                    .modulePath(helper.defaultModulePath())
                    .addMods("leaf1")
                    .option("--output")
                    .option("")
                    .call().assertFailure("Error: no value given for --output");
            JImageGenerator.getJLinkTask()
                    .modulePath("")
                    .output(helper.createNewImageDir(moduleName))
                    .addMods("leaf1")
                    .option("")
                    .call().assertFailure("Error: no value given for --modulepath");
        }

        {
            String moduleName = "filter";
            Path jmod = helper.generateDefaultJModule(moduleName).assertSuccess();
            String className = "_A.class";
            JImageGenerator.addFiles(jmod, new InMemoryFile(className, new byte[0]));
            Path image = helper.generateDefaultImage(moduleName).assertSuccess();
            helper.checkImage(image, moduleName, new String[] {"/" + moduleName + "/" + className}, null);
        }

        {
            // Help
            StringWriter writer = new StringWriter();
            jdk.tools.jlink.Main.run(new String[]{"--help"}, new PrintWriter(writer));
            String output = writer.toString();
            if (output.split("\n").length < 30) {
                System.err.println(output);
                throw new AssertionError("Help");
            }
        }

        {
            // License files
            String copied = "LICENSE";
            String[] arr = copied.split(",");
            String[] copyFiles = new String[2];
            copyFiles[0] = "--copy-files";
            copyFiles[1] = copied;
            Path imageDir = helper.generateDefaultImage(copyFiles, "composite2").assertSuccess();
            helper.checkImage(imageDir, "composite2", null, null, arr);
        }

        {
            // List plugins
            StringWriter writer = new StringWriter();
            jdk.tools.jlink.Main.run(new String[]{"--list-plugins"}, new PrintWriter(writer));
            String output = writer.toString();
            long number = Stream.of(output.split("\n"))
                    .filter((s) -> s.matches("Plugin Name:.*"))
                    .count();
            if (number != numPlugins) {
                System.err.println(output);
                throw new AssertionError("Plugins not found: " + number);
            }
        }

        // filter out files and resources + Skip debug + compress
        {
            String[] userOptions = {"--compress-resources", "on", "--strip-java-debug", "on",
                "--exclude-resources", "*.jcov, */META-INF/*", "--exclude-files",
                "*" + Helper.getDebugSymbolsExtension()};
            String moduleName = "excludezipskipdebugcomposite2";
            helper.generateDefaultJModule(moduleName, "composite2");
            String[] res = {".jcov", "/META-INF/"};
            String[] files = {Helper.getDebugSymbolsExtension()};
            Path imageDir = helper.generateDefaultImage(userOptions, moduleName).assertSuccess();
            helper.checkImage(imageDir, moduleName, res, files);
        }

        // filter out + Skip debug + compress with filter + sort resources
        {
            String[] userOptions2 = {"--compress-resources", "on", "--compress-resources-filter",
                "^/java.base/*", "--strip-java-debug", "on", "--exclude-resources",
                "*.jcov, */META-INF/*", "--sort-resources",
                "*/module-info.class,/sortcomposite2/*,*/javax/management/*"};
            String moduleName = "excludezipfilterskipdebugcomposite2";
            helper.generateDefaultJModule(moduleName, "composite2");
            String[] res = {".jcov", "/META-INF/"};
            Path imageDir = helper.generateDefaultImage(userOptions2, moduleName).assertSuccess();
            helper.checkImage(imageDir, moduleName, res, null);
        }

        // default compress
        {
            testCompress(helper, "compresscmdcomposite2", "--compress-resources", "on");
        }

        {
            testCompress(helper, "compressfiltercmdcomposite2",
                    "--compress-resources", "on", "--compress-resources-filter",
                    "^/java.base/java/lang/*");
        }

        // compress 0
        {
            testCompress(helper, "compress0filtercmdcomposite2",
                    "--compress-resources", "on", "--compress-resources-level", "0",
                    "--compress-resources-filter", "^/java.base/java/lang/*");
        }

        // compress 1
        {
            testCompress(helper, "compress1filtercmdcomposite2",
                    "--compress-resources", "on", "--compress-resources-level", "1",
                    "--compress-resources-filter", "^/java.base/java/lang/*");
        }

        // compress 2
        {
            testCompress(helper, "compress2filtercmdcomposite2",
                    "--compress-resources", "on", "--compress-resources-level", "2",
                    "--compress-resources-filter", "^/java.base/java/lang/*");
        }

        // invalid compress level
        {
            String[] userOptions = {"--compress-resources", "on", "--compress-resources-level", "invalid"};
            String moduleName = "invalidCompressLevel";
            helper.generateDefaultJModule(moduleName, "composite2");
            helper.generateDefaultImage(userOptions, moduleName).assertFailure("Error: Invalid level invalid");
        }

        // configuration
        {
            Path path = Paths.get("embedded.properties");
            Files.write(path, Collections.singletonList("jdk.jlink.defaults=--strip-java-debug on --addmods " +
                    "toto.unknown --compress-resources UNKNOWN\n"));
            String[] userOptions = {"--configuration", path.toAbsolutePath().toString(),
                    "--compress-resources", "off"};
            String moduleName = "configembeddednocompresscomposite2";
            helper.generateDefaultJModule(moduleName, "composite2");
            Path imageDir = helper.generateDefaultImage(userOptions, moduleName).assertSuccess();
            helper.checkImage(imageDir, moduleName, null, null);
        }

        {
            // Defaults configuration unit parsing
            List<String> lst = Arrays.asList("--aaaa", "a,b,c,d", "--koko", "--bbbbb",
                    "x,y,z", "--xxx", "-x", "--ddd", "ddd", "--compress", "--doit");
            String sample = "  --aaaa a, b, c, d --koko --bbbbb    x,y,z   --xxx -x  --ddd ddd --compress --doit";

            checkDefaults(sample, lst);
            checkDefaults(sample + " ", lst);
        }

    }

    private static void testCompress(Helper helper, String moduleName, String... userOptions) throws IOException {
        helper.generateDefaultJModule(moduleName, "composite2");
        Path imageDir = helper.generateDefaultImage(userOptions, moduleName).assertSuccess();
        helper.checkImage(imageDir, moduleName, null, null);
    }

    private static void checkDefaults(String value, List<String> expected)
            throws Exception {
        List<String> arguments = TaskHelper.parseDefaults(value);
        if (!expected.equals(arguments)) {
            throw new Exception("Lists are not equal. Expected: " + expected
                    + " Actual: " + arguments);
        }
    }
}
