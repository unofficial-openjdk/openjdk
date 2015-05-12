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
import java.util.ArrayList;
import java.util.List;
import jdk.tools.jlink.TaskHelper;
import jdk.tools.jlink.plugins.PluginProvider;
import jdk.tools.jlink.internal.ImagePluginProviderRepository;

/*
 * @test
 * @ignore
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
 * @run main JLinkTest
 */
public class JLinkTest {

    public static void main(String[] args) throws Exception {

        Helper helper = Helper.newHelper();
        if (helper == null) {
            System.err.println("Test not run");
            return;
        }

        int num = 0;
        for (PluginProvider pf : ImagePluginProviderRepository.getImageWriterProviders(null)) {
            num += 1;
        }
        if (num != 7) {
            throw new Exception("Plugins not found. " + num);
        }

        //Help
        String[] opts2 = {"--help"};
        jdk.tools.jlink.Main.run(opts2, new PrintWriter(System.out));

        //List plugins
        String[] opts = {"--list-plugins"};
        jdk.tools.jlink.Main.run(opts, new PrintWriter(System.out));

        // Standard module image
        helper.checkImage("java.management", null, null, null);

        // filter out files and resources + Skip debug + compress
        {
            String[] userOptions = {"--compress-resources", "on", "--strip-java-debug", "on",
                "--exclude-resources", "*.jcov, */META-INF/*", "--exclude-files",
                "*" + Helper.getDebugSymbolsExtension()};
            helper.generateJModule("excludezipskipdebugcomposite2", "composite2");
            String[] res = {".jcov", "/META-INF/"};
            String[] files = {Helper.getDebugSymbolsExtension()};
            helper.checkImage("excludezipskipdebugcomposite2", userOptions, res, files);
        }

        // filter out + Skip debug + compress with filter
        {
            String[] userOptions2 = {"--compress-resources", "on", "--compress-resources-filter",
                "^/java.base/*", "--strip-java-debug", "on", "--exclude-resources",
                "*.jcov, */META-INF/*"};
            helper.generateJModule("excludezipfilterskipdebugcomposite2", "composite2");
            String[] res = {".jcov", "/META-INF/"};
            helper.checkImage("excludezipfilterskipdebugcomposite2", userOptions2,
                    res, null);
        }

        // default compress
        {
            String[] userOptions = {"--compress-resources", "on"};
            helper.generateJModule("compresscmdcomposite2", "composite2");
            helper.checkImage("compresscmdcomposite2", userOptions, null, null);
        }

        {
            String[] userOptions = {"--compress-resources", "on", "--compress-resources-filter",
                "^/java.base/java/lang/*"};
            helper.generateJModule("compressfiltercmdcomposite2", "composite2");
            helper.checkImage("compressfiltercmdcomposite2", userOptions, null, null);
        }

        // compress 0
        {
            String[] userOptions = {"--compress-resources", "on", "--compress-resources-level", "0",
                "--compress-resources-filter", "^/java.base/java/lang/*"};
            helper.generateJModule("compress0filtercmdcomposite2", "composite2");
            helper.checkImage("compress0filtercmdcomposite2", userOptions, null, null);
        }

        // compress 1
        {
            String[] userOptions = {"--compress-resources", "on", "--compress-resources-level", "1",
                "--compress-resources-filter", "^/java.base/java/lang/*"};
            helper.generateJModule("compress1filtercmdcomposite2", "composite2");
            helper.checkImage("compress1filtercmdcomposite2", userOptions, null, null);
        }

        // configuration
        {
            File f = new File("embedded.properties");
            f.createNewFile();
            try (FileOutputStream stream = new FileOutputStream(f);) {
                String content = "jdk.jlink.defaults=--strip-java-debug on --addmods toto.unknown "
                        + "--compress-resources UNKNOWN\n";
                stream.write(content.getBytes());
                String[] userOptions = {"--configuration", f.getAbsolutePath(),
                    "--compress-resources", "off"};
                helper.generateJModule("configembeddednocompresscomposite2", "composite2");
                helper.checkImage("configembeddednocompresscomposite2",
                        userOptions, null, null);
            }
        }

        // Defaults configuration unit parsing
        List<String> lst = new ArrayList<>();
        lst.add("--aaaa");
        lst.add("a,b,c,d");
        lst.add("--koko");
        lst.add("--bbbbb");
        lst.add("x,y,z");
        lst.add("--xxx");
        lst.add("-x");
        lst.add("--ddd");
        lst.add("ddd");
        lst.add("--compress");
        lst.add("--doit");
        String sample = "  --aaaa a, b, c, d --koko --bbbbb    x,y,z   --xxx -x  --ddd ddd --compress --doit";
        String sample2 = sample + " ";

        checkDefaults(sample, lst);
        checkDefaults(sample2, lst);

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
