
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jdk.tools.jlink.internal.ImageFilePoolImpl;
import jdk.tools.jlink.internal.JvmHandler;
import jdk.tools.jlink.plugins.ImageFilePool;
import jdk.tools.jlink.plugins.ImageFilePool.ImageFile;

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
 * @summary Test jvm handling
 * @author Jean-Francois Denise
 * @modules jdk.jlink/jdk.tools.jlink.internal
 * @run main/othervm JvmHandlerTest
 */
public class JvmHandlerTest {
    private static final String TAG = "# orig in test\n";

    private static final String CFG_ALL = TAG + "-server KNOWN\n -client KNOWN\n"
            + "-minimal KNOWN\n";
    private static final String CFG_CLIENT_SERVER = TAG + "-server KNOWN\n -client KNOWN\n";
    private static final String CFG_NO_SERVER = TAG + "-client KNOWN\n"
            + "-minimal KNOWN\n"
            + "-server ALIASED_TO -client\n";
    private static final String CFG_NO_CLIENT = TAG + "-server KNOWN\n"
            + "-client ALIASED_TO -server\n";
    private static final String CFG_ONLY_MINIMAL = TAG + "-minimal KNOWN\n"
            + "-client ALIASED_TO -minimal\n"
            + "-server ALIASED_TO -minimal\n";

    public static void main(String[] args) throws Exception {
        boolean failed = false;
        try {
            JvmHandler handler = new JvmHandler();
            handler.handlePlatforms(null, null);
            failed = true;
        } catch (Exception ex) {
            // XXX OK
        }
        if (failed) {
            throw new Exception("Should have failed.");
        }

        {
            List<String> jvm = new ArrayList<>();
            jvm.add("client");
            jvm.add("minimal");
            jvm.add("server");
            // input client, server, minimal, removed none, cfg untouched, no alias
            test(jvm, CFG_ALL, Collections.emptyList(), CFG_ALL);
        }

        {
            // input client, server, minimal, remove non platform, cfg untouched,
            List<String> jvm = new ArrayList<>();
            jvm.add("client");
            jvm.add("minimal");
            jvm.add("server");
            List<String> removed = new ArrayList<>();
            removed.add("toto");
            test(jvm, CFG_ALL, removed, false, CFG_ALL);
        }

        {
            // input client, server, minimal, removed all ==> exception
            List<String> jvm = new ArrayList<>();
            jvm.add("client");
            jvm.add("minimal");
            jvm.add("server");
            try {
                test(jvm, CFG_ALL, jvm, CFG_ALL);
                failed = true;
            } catch (Exception ex) {
                // XXX OK
            }
            if (failed) {
                throw new Exception("Should have failed.");
            }
        }

        {
            // input client, server, minimal, remove server, cfg generated,
            // all in cfg, server aliased to client
            List<String> jvm = new ArrayList<>();
            jvm.add("client");
            jvm.add("minimal");
            jvm.add("server");
            List<String> removed = new ArrayList<>();
            removed.add("server");
            test(jvm, CFG_ALL, removed, CFG_NO_SERVER);
        }

        {
            // input client, server, remove client, cfg generated, all in cfg,
            // client aliased to server
            List<String> jvm = new ArrayList<>();
            jvm.add("client");
            jvm.add("server");
            List<String> removed = new ArrayList<>();
            removed.add("client");
            test(jvm, CFG_CLIENT_SERVER, removed, CFG_NO_CLIENT);
        }

        {
            // input client, server, minimal remove client, server,
            // cfg generated, all in cfg, client and server aliased to minimal
            List<String> jvm = new ArrayList<>();
            jvm.add("client");
            jvm.add("server");
            jvm.add("minimal");
            List<String> removed = new ArrayList<>();
            removed.add("client");
            removed.add("server");
            test(jvm, CFG_ALL, removed, CFG_ONLY_MINIMAL);
        }

    }

    private static void test(List<String> jvm, String jvmcfg, List<String> removed,
            String expectedcfg) throws Exception {
        test(jvm, jvmcfg, removed, true, expectedcfg);
    }

    private static void test(List<String> jvm, String jvmcfg, List<String> removed,
            boolean platformRemoved,
            String expectedcfg) throws Exception {
        String lib = "libjvm.so";
        if (isMac()) {
            lib = "libjvm.dylib";
        } else {
            if (isWindows()) {
                lib = "jvm.dll";
            }
        }
        test(jvm, jvmcfg, removed, platformRemoved, expectedcfg, lib);
    }

    private static void test(List<String> jvm, String jvmcfg, List<String> removed,
            boolean platformRemoved, String expectedcfg, String dll) throws Exception {
        ImageFilePoolImpl files = new ImageFilePoolImpl();
        List<ImageFile> removedFiles = new ArrayList<>();

        // remaining platforms
        for (String j : jvm) {
            String path = "/native/" + j + "/" + dll;
            System.err.println("PATH " + path);
            ImageFile f = new ImageFile("java.base", path, j + "/" + dll,
                    ImageFile.ImageFileType.NATIVE_LIB) {

                @Override
                public long size() {
                    return 0;
                }

                @Override
                public InputStream stream() throws IOException {
                    return new ByteArrayInputStream(new byte[0]);
                }
            };
            files.addFile(f);
        }

        // removed platforms or other file
        for (String j : removed) {
            String name = j + "/" + (platformRemoved ? dll : "crazy.txt");
            String path = "/native/" + name;
            ImageFile f = new ImageFile("java.base", path, name,
                    ImageFile.ImageFileType.NATIVE_LIB) {

                @Override
                public long size() {
                    return 0;
                }

                @Override
                public InputStream stream() throws IOException {
                    return new ByteArrayInputStream(new byte[0]);
                }
            };
            removedFiles.add(f);
        }

        String jvmcfgpath = "/native/jvm.cfg";
        byte[] content = jvmcfg.getBytes();
        ImageFile f = new ImageFile("java.base", jvmcfgpath, "jvm.cfg",
                ImageFile.ImageFileType.NATIVE_LIB) {

            @Override
            public long size() {
                return content.length;
            }

            @Override
            public InputStream stream() throws IOException {
                return new ByteArrayInputStream(content);
            }
        };
        files.addFile(f);

        JvmHandler handler = new JvmHandler();
        ImageFilePool res = handler.handlePlatforms(files, removedFiles);

        // check that all files are still there
        ImageFile newcfg = null;
        for (ImageFile file : res.getFiles()) {
            if (file.getPath().equals(jvmcfgpath)) {
                newcfg = file;
            }
            if (!files.contains(file)) {
                throw new Exception("File " + file + ", not contained in input");
            }
        }
        if (newcfg == null) {
            throw new Exception("No jvm.cfg file found");
        }
        for (ImageFile file : files.getFiles()) {
            if (!res.contains(file)) {
                throw new Exception("File " + file + ", not contained in output");
            }
        }
        byte[] bytes = newcfg.stream().readAllBytes();
        String newcontent = new String(bytes);
        if (!newcontent.equals(expectedcfg)) {
            throw new Exception(expectedcfg + " NOT EQUAL TO " + newcontent);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    private static boolean isMac() {
        return System.getProperty("os.name").startsWith("Mac OS");
    }
}
