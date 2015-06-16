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
import jdk.tools.jlink.internal.ImagePluginConfiguration;
import jdk.tools.jlink.plugins.PluginProvider;

/*
 * @test
 * @summary Test image creation
 * @author Jean-Francois Denise
 * @library /lib/testlibrary/jlink
 * @modules java.base/jdk.internal.jimage
 *          jdk.jdeps/com.sun.tools.classfile
 *          jdk.jlink/jdk.tools.jlink
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jmod
 *          jdk.jlink/jdk.tools.jimage
 * @build tests.JImageGenerator tests.JImageValidator
 * @run main/othervm -verbose:gc -Xmx1g JLinkPluginsTest
 */
public class JLinkPluginsTest {

    public static void main(String[] args) throws Exception {
        Helper helper = Helper.newHelper();
        if (helper == null) {
            System.err.println("Test not run");
            return;
        }

        {
            File f = new File("plugins.properties");
            f.createNewFile();
            try (FileOutputStream stream = new FileOutputStream(f);) {
                String content = ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY + "=zip\n"
                        + "zip." + PluginProvider.TOOL_ARGUMENT_PROPERTY
                        + "=*Error.class,*Exception.class, ^/java.base/java/lang/*\n";
                stream.write(content.getBytes());
                String[] userOptions = {"--plugins-configuration", f.getAbsolutePath()};
                helper.generateJModule("zipfiltercomposite", "composite2");
                helper.checkImage("zipfiltercomposite", userOptions, null, null);
            }
        }

        {
            // Skip debug
            File f = new File("plugins.properties");
            f.createNewFile();
            try (FileOutputStream stream = new FileOutputStream(f);) {
                String content = ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY + "=strip-java-debug\n"
                        + "strip-java-debug." + PluginProvider.TOOL_ARGUMENT_PROPERTY + "="
                        + "on";
                stream.write(content.getBytes());
                String[] userOptions = {"--plugins-configuration", f.getAbsolutePath()};
                helper.generateJModule("skipdebugcomposite", "composite2");
                helper.checkImage("skipdebugcomposite", userOptions, null, null);
            }
        }
        {
            // Skip debug + zip
            File f = new File("plugins.properties");
            f.createNewFile();
            try (FileOutputStream stream = new FileOutputStream(f);) {
                String content = ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY + "=strip-java-debug\n"
                        + "strip-java-debug." + PluginProvider.TOOL_ARGUMENT_PROPERTY + "="
                        + "on\n"
                        + ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY + "=zip\n";
                stream.write(content.getBytes());
                String[] userOptions = {"--plugins-configuration", f.getAbsolutePath()};
                helper.generateJModule("zipskipdebugcomposite", "composite2");
                helper.checkImage("zipskipdebugcomposite", userOptions, null, null);
            }
        }
        {
            // Filter out files
            File f = new File("plugins.properties");
            f.createNewFile();
            try (FileOutputStream stream = new FileOutputStream(f);) {
                String content = ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY + "=exclude-resources\n"
                        + "exclude-resources." + PluginProvider.TOOL_ARGUMENT_PROPERTY
                        + "=*.jcov, */META-INF/*\n";
                stream.write(content.getBytes());
                String[] userOptions = {"--plugins-configuration", f.getAbsolutePath()};
                helper.generateJModule("excludecomposite", "composite2");
                String[] res = {".jcov", "/META-INF/"};
                helper.checkImage("excludecomposite", userOptions, res, null);
            }
        }
        // Shared UTF_8 Constant Pool entries
        {
            File f = new File("plugins.properties");
            f.createNewFile();
            try (FileOutputStream stream = new FileOutputStream(f);) {
                String content = ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY
                        + "=compact-cp\n";
                stream.write(content.getBytes());
                String[] userOptions = {"--plugins-configuration", f.getAbsolutePath()};
                helper.generateJModule("cpccomposite", "composite2");
                helper.checkImage("cpccomposite", userOptions, null, null);
            }
        }
        {
            File f = new File("plugins.properties");
            f.createNewFile();
            try (FileOutputStream stream = new FileOutputStream(f);) {
                String content = ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY
                        + ".0=compact-cp\n"
                        + ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY + ".1=zip\n";
                stream.write(content.getBytes());
                String[] userOptions = {"--plugins-configuration", f.getAbsolutePath()};
                helper.generateJModule("zipcpccomposite", "composite2");
                helper.checkImage("zipcpccomposite", userOptions, null, null);
            }
        }
    }
}
