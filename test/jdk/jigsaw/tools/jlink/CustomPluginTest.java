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
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import tests.JImageGenerator.JLinkResult;

/*
 * @test
 * @summary Test custom plugin
 * @author Jean-Francois Denise
 * @library ../lib
 * @modules java.base/jdk.internal.jimage
 *          jdk.jdeps/com.sun.tools.classfile
 *          jdk.jlink/jdk.tools.jlink
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jmod
 *          jdk.jlink/jdk.tools.jimage
 * @build tests.JImageGenerator tests.JImageValidator
 * @run main/othervm CustomPluginTest
 */

public class CustomPluginTest {

    public static void main(String[] args) throws Exception {
        new CustomPluginTest().test();
    }

    private void test() throws Exception {
        Helper helper = Helper.newHelper();
        if (helper == null) {
            System.err.println("Test not run");
            return;
        }
        File jmod = registerServices(helper);
        String pluginModulePath = jmod.getParent();

        testHelloProvider(helper, pluginModulePath);
        testCustomPlugins(pluginModulePath);
        testHelp(pluginModulePath);
    }

    private void testHelp(String pluginModulePath) {
        StringWriter writer = new StringWriter();
        int rc = jdk.tools.jlink.Main.run(new String[]{"--help", "--plugins-modulepath", pluginModulePath},
                new PrintWriter(writer));
        String output = writer.toString();
        if (rc != 0) {
            System.out.println(output);
            throw new AssertionError("jlink crashed: " + rc);
        }
        List<String> plugins = new ArrayList<>();
        String[] lines = output.split("\n");
        for (String s : lines) {
            if (s.startsWith(" --custom") || s.startsWith(" custom")) {
                plugins.add(s);
            }
        }
        /*if (plugins.size() != 6) {
            System.out.println(output);
            throw new AssertionError("Expected three plugins " + plugins);
        }*/
        for (int i = 0; i < plugins.size(); i += 2) {
            String[] ss = plugins.get(i).trim().split(" +");
            String pluginName = ss[0].substring(2, ss[0].lastIndexOf('-'));
            assertEquals("--" + pluginName + "-option", ss[0], output);
            assertEquals(pluginName + "-argument", ss[1], output);
            assertEquals(pluginName + "-description", plugins.get(i + 1).trim(), output);
        }
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            System.out.println(message);
            throw new AssertionError("Expected: " + expected + ", got: " + actual);
        }
    }

    private void testCustomPlugins(String pluginModulePath) {
        StringWriter writer = new StringWriter();
        int rc = jdk.tools.jlink.Main.run(new String[]{"--list-plugins", "--plugins-modulepath", pluginModulePath},
                new PrintWriter(writer));
        String output = writer.toString();
        /*if (rc != 0) {
            System.out.println(output);
            throw new AssertionError("jlink crashed: " + rc);
        }*/
        List<String> customPlugins = Stream.of(writer.toString().split("\n"))
                .filter(s -> s.startsWith("Plugin Name:"))
                .filter(s -> s.contains("custom"))
                .collect(Collectors.toList());
        /*if (customPlugins.size() != 3) {
            System.out.println(output);
            throw new AssertionError("Found plugins: " + customPlugins);
        }*/
    }

    private File registerServices(Helper helper) throws IOException {
        String name = "customplugin";
        File src = new File(System.getProperty("test.src"), name);
        File classes = helper.getGenerator().compileModule(src, (String) null,
                "-XaddExports:jdk.jlink/jdk.tools.jlink.internal=customplugin");
        return helper.getGenerator().buildJModule(name, null, classes);
    }

    private void testHelloProvider(Helper helper, String pluginModulePath) throws Exception {
        File pluginFile = new File("customplugin.txt");
        if (pluginFile.exists()) {
            throw new Exception("Custom plugin output file already exists");
        }
        {
            // Add the path but not the option, plugin musn't be called
            String[] userOptions = {"--plugins-modulepath", pluginModulePath};
            JLinkResult jLinkResult = helper.getGenerator().
                    generateImage(userOptions, "customplugin");
            if (jLinkResult.getExitCode() != 0) {
                throw new RuntimeException("jlink failed: "
                        + jLinkResult.getExitCode());
            }
        }

        if (pluginFile.exists()) {
            throw new Exception("Custom plugin output file exists, plugin "
                    + " called although shouldn't have been");
        }

        { // Add the path and the option, plugin should be called.
            String[] userOptions = {"--plugins-modulepath", pluginModulePath, "--hello"};
            JLinkResult jLinkResult = helper.getGenerator().generateImage(userOptions, "customplugin");
            if (jLinkResult.getExitCode() != 0) {
                throw new RuntimeException("jlink failed: " + jLinkResult.getExitCode());
            }
        }

        if (!pluginFile.exists()) {
            throw new Exception("Custom plugin not called");
        }
    }
}
