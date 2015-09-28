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
 * @summary Test files copy plugin
 * @author Jean-Francois Denise
 * @modules jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jlink.internal.plugins
 * @run main FileCopierPluginTest
 */

import java.io.File;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Properties;
import java.util.Set;
import jdk.tools.jlink.plugins.DefaultImageBuilder;
import jdk.tools.jlink.internal.ImageFilePoolImpl;
import jdk.tools.jlink.internal.ResourcePoolImpl;

import jdk.tools.jlink.internal.plugins.FileCopierProvider;
import jdk.tools.jlink.plugins.ImageBuilder;
import jdk.tools.jlink.plugins.ImageFilePlugin;
import jdk.tools.jlink.plugins.ImageFilePool;
import jdk.tools.jlink.plugins.ImageFilePool.ImageFile;
import jdk.tools.jlink.plugins.ResourcePool;

public class FileCopierPluginTest {

    public static void main(String[] args) throws Exception {
        new FileCopierPluginTest().test();
    }

    /**
     * 3 cases - Absolute, no target ==> copy in image root dir - Absolute and
     * target ==> copy in image root dir/target - Relative ==> copy from JDK
     * home dir.
     *
     * @throws Exception
     */
    public void test() throws Exception {
        FileCopierProvider prov = new FileCopierProvider();
        String content = "You \n should \n be \bthere.\n";
        String name = "sample.txt";
        File src = new File("src");
        src.mkdir();
        // Need a fake bin
        File bin = new File("bin");
        bin.mkdir();

        File txt = new File(src, name);
        txt.createNewFile();

        String target = "target" + File.separator + name;
        Files.write(txt.toPath(), content.getBytes());
        File lic = new File(System.getProperty("java.home"), "LICENSE");
        String[] args = new String[lic.exists() ? 4 : 3];
        int i = 0;
        if (lic.exists()) {
            args[i] = "LICENSE";
            i += 1;
        }
        args[i++] = txt.getAbsolutePath();
        args[i++] = txt.getAbsolutePath() + "=" + target;
        args[i++] = src.getAbsolutePath() + "=src2";

        ImageFilePlugin plug = prov.newPlugins(args, null)[0];
        ImageFilePool pool = new ImageFilePoolImpl();
        plug.visit(new ImageFilePoolImpl(), pool);
        if (pool.getFiles().size() != args.length) {
            throw new AssertionError("Wrong number of added files");
        }
        for (ImageFile f : pool.getFiles()) {
            if (!f.getType().equals(ImageFile.ImageFileType.OTHER)) {
                throw new AssertionError("Invalid type " + f.getType()
                        + " for file " + f.getName());
            }
            if (f.stream() == null) {
                throw new AssertionError("Null stream for file " + f.getName());
            }

        }
        Path root = new File(".").toPath();
        DefaultImageBuilder builder = new DefaultImageBuilder(new HashMap<String, Object>(),
                root);
        builder.storeFiles(pool, Collections.EMPTY_LIST,
                "", new ImageBuilder.ResourceRetriever() {

                    @Override
                    public ResourcePool.Resource retrieves(String path) throws IOException {
                        return null;
                    }

                    @Override
                    public Set<String> getModules() {
                        return Collections.emptySet();
                    }
                });

        if (lic.exists()) {
            File license = new File(root.toFile(), "LICENSE");
            if (!license.exists() || license.length() == 0) {
                throw new AssertionError("Invalide license file "
                        + license.getAbsoluteFile());
            }
        }

        File sample1 = new File(root.toFile(), txt.getName());
        if (!sample1.exists() || sample1.length() == 0) {
            throw new AssertionError("Invalide sample1 file "
                    + sample1.getAbsoluteFile());
        }
        if (!new String(Files.readAllBytes(sample1.toPath())).equals(content)) {
            throw new AssertionError("Invalid Content in sample1");
        }

        File sample2 = new File(root.toFile(), target);
        if (!sample2.exists() || sample2.length() == 0) {
            throw new AssertionError("Invalide sample2 file "
                    + sample2.getAbsoluteFile());
        }
        if (!new String(Files.readAllBytes(sample2.toPath())).equals(content)) {
            throw new AssertionError("Invalid Content in sample2");
        }

        File src2 = new File(root.toFile(), "src2");
        if (!src2.exists() || src2.list().length != 1) {
            throw new AssertionError("Invalide src2 dir "
                    + src2.getAbsoluteFile());
        }
        File f = src2.listFiles()[0];
        if (!f.getName().equals(txt.getName())) {
            throw new AssertionError("Invalide file name in src2 dir "
                    + f.getAbsoluteFile());
        }
        if (!new String(Files.readAllBytes(f.toPath())).equals(content)) {
            throw new AssertionError("Invalid Content in src2 dir");
        }
    }
}
