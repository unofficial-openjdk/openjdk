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

        Helper helper = Helper.newHelper();
        if (helper == null) {
            System.err.println("Test not run");
            return;
        }
        File pluginFile = new File("customplugin.txt");
        if (pluginFile.exists()) {
            throw new Exception("Custom plugin output file already exists");
        }

        String name = "customplugin";
        File src = new File(System.getProperty("test.src"), name);
        File jmod = helper.getGenerator().buildModule(name, src);

        {
            // Add the path but not the option, plugin musn't be called
            String[] userOptions = {"--plugins-modulepath",
                jmod.getParentFile().getAbsolutePath()};
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
            String[] userOptions = {"--plugins-modulepath",
                jmod.getParentFile().getAbsolutePath(), "--hello"};
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
