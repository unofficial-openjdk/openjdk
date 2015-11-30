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
 * @summary Test exclude files plugin
 * @author Jean-Francois Denise
 * @modules jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jlink.internal.plugins
 * @run main ExcludeFilesPluginTest
 */

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import jdk.tools.jlink.internal.ImageFilePoolImpl;
import jdk.tools.jlink.internal.plugins.ExcludeFilesProvider;
import jdk.tools.jlink.plugins.CmdPluginProvider;
import jdk.tools.jlink.plugins.ImageFilePlugin;
import jdk.tools.jlink.plugins.ImageFilePool;

public class ExcludeFilesPluginTest {
    public static void main(String[] args) throws Exception {
        new ExcludeFilesPluginTest().test();
    }

    public void test() throws Exception {
        checkFiles("*.jcov", "/num/toto.jcov", "", true);
        checkFiles("*.jcov", "//toto.jcov", "", true);
        checkFiles("*.jcov", "/toto.jcov/tutu/tata", "", false);
        checkFiles("/java.base/*.jcov", "/toto.jcov", "java.base", true);
        checkFiles("/java.base/toto.jcov", "/iti.jcov", "t/java.base", false);
        checkFiles("/java.base/*/toto.jcov", "/toto.jcov", "java.base", false);
        checkFiles("/java.base/*/toto.jcov", "/tutu/toto.jcov", "java.base", true);
        checkFiles("*/java.base/*/toto.jcov", "/java.base/tutu/toto.jcov", "/tutu", true);

        checkFiles("/*$*.properties", "/tutu/Toto$Titi.properties", "java.base", true);
        checkFiles("*$*.properties", "/tutu/Toto$Titi.properties", "java.base", true);

        // Excluded files list in a file
        File order = new File("files.exc");
        order.createNewFile();
        Files.write(order.toPath(), "*.jcov".getBytes());
        checkFiles(order.getAbsolutePath(), "/num/toto.jcov", "", true);
    }

    public void checkFiles(String s, String sample, String module, boolean exclude) throws Exception {
        Map<String, Object> prop = new HashMap<>();
        prop.put(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY, s);
        ExcludeFilesProvider fprovider = new ExcludeFilesProvider();
        ImageFilePlugin fplug = (ImageFilePlugin) fprovider.newPlugins(prop)[0];
        ImageFilePool files = new ImageFilePoolImpl();
        ImageFilePool fresult = new ImageFilePoolImpl();
        ImageFilePool.ImageFile f = new ImageFilePool.ImageFile(module, sample, sample,
                ImageFilePool.ImageFile.ImageFileType.CONFIG) {
            @Override
            public long size() {
                throw new UnsupportedOperationException("Shouldn't be called");
            }

            @Override
            public InputStream stream() throws IOException {
                throw new UnsupportedOperationException("Shouldn't be called");
            }
        };
        files.addFile(f);

        fplug.visit(files, fresult);

        if (exclude) {
            if (fresult.getFiles().contains(f)) {
                throw new Exception(sample + " should be excluded by " + s);
            }
        } else {
            if (!fresult.getFiles().contains(f)) {
                throw new Exception(sample + " shouldn't be excluded by " + s);
            }
        }
    }
}
