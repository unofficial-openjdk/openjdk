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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jdk.tools.jlink.internal.ImagePluginProviderRepository;
import jdk.tools.jlink.plugins.ExecutableImage;
import jdk.tools.jlink.plugins.OnOffPostProcessingPluginProvider;
import jdk.tools.jlink.plugins.PostProcessingPlugin;
import jdk.tools.jlink.plugins.ProcessingManager;
import jdk.tools.jlink.plugins.ProcessingManager.ProcessingSession;
import jdk.tools.jlink.plugins.ProcessingManager.RunningProcess;
import tests.Helper;
import tests.JImageGenerator;
import tests.JImageGenerator.InMemoryFile;

/*
 * @test
 * @summary Test post processing
 * @author Jean-Francois Denise
 * @library ../lib
 * @modules java.base/jdk.internal.jimage
 *          jdk.jdeps/com.sun.tools.classfile
 *          jdk.jlink/jdk.tools.jlink
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jmod
 *          jdk.jlink/jdk.tools.jimage
 * @build tests.*
 * @run main/othervm JLinkPostProcessingTest
 */
public class JLinkPostProcessingTest {

    private static class PostProcessingTest extends OnOffPostProcessingPluginProvider {

        private static ExecutableImage called;
        @Override
        public PostProcessingPlugin[] createPlugins(Map<String, String> otherOptions) throws IOException {
            return new PostProcessingPlugin[]{new PPPlugin()};
        }

        private static boolean isWindows() {
            return System.getProperty("os.name").startsWith("Windows");
        }

        private static class PPPlugin implements PostProcessingPlugin {

            @Override
            public List<String> process(ProcessingManager manager) throws Exception {
                called = manager.getImage();
                List<String> args = new ArrayList<>();
                args.add("-version");

                ProcessingSession session = manager.newSession("test");
                {
                    RunningProcess i = session.newImageProcess(args);
                    String str = i.getStdout();
                    if (!str.isEmpty()) {
                        throw new Exception("Unexpected out " + str);
                    }
                    String str2 = i.getStderr();
                    if (str2.isEmpty()) {
                        throw new Exception("Version not print ");
                    } else {
                        System.out.println("REMOTE PROCESS output: " + str2);
                    }
                    if (i.getExitCode() != 0) {
                        throw new Exception("Not valid exit code " + i.getExitCode());
                    }
                }

                {
                    ProcessBuilder builder = new ProcessBuilder(manager.getImage().
                            getHome().resolve("bin").
                            resolve(isWindows() ? "java.exe" : "java").toString(), "-version");
                    RunningProcess i = session.newRunningProcess(builder);
                    String str = i.getStdout();
                    if (!str.isEmpty()) {
                        throw new Exception("Unexpected out " + str);
                    }
                    String str2 = i.getStderr();
                    if (str2.isEmpty()) {
                        throw new Exception("Version not print ");
                    } else {
                        System.out.println("REMOTE PROCESS output: " + str2);
                    }
                    if (i.getExitCode() != 0) {
                        throw new Exception("Not valid exit code " + i.getExitCode());
                    }
                }


                session.close();

                Path gen = manager.getImage().getHome().resolve("lib").resolve("toto.txt");
                Files.createFile(gen);
                return null;
            }

            @Override
            public String getName() {
                return NAME;
            }

        }
        private static final String NAME = "pp";

        PostProcessingTest() {
            super(NAME, "");
        }

        @Override
        public String getToolOption() {
            return NAME;
        }

        @Override
        public Map<String, String> getAdditionalOptions() {
            return null;
        }

        @Override
        public String getCategory() {
            return PROCESSOR;
        }

    }
    public static void main(String[] args) throws Exception {

        Helper helper = Helper.newHelper();
        if (helper == null) {
            System.err.println("Test not run");
            return;
        }
        helper.generateDefaultModules();

        ImagePluginProviderRepository.registerPluginProvider(new PostProcessingTest());

        // Generate an image and post-process in same jlink execution.
        {
            String[] userOptions = {"--pp", "on"};
            String moduleName = "postprocessing1";
            helper.generateDefaultJModule(moduleName, "composite2");
            String[] res = {};
            String[] files = {};
            Path imageDir = helper.generateDefaultImage(userOptions, moduleName).assertSuccess();
            helper.checkImage(imageDir, moduleName, res, files);

            test(imageDir);
        }

        // Generate an image, post-process in 2 jlink executions.
        {
            String[] userOptions = {};
            String moduleName = "postprocessing2";
            helper.generateDefaultJModule(moduleName, "composite2");
            String[] res = {};
            String[] files = {};
            Path imageDir = helper.generateDefaultImage(userOptions, moduleName).assertSuccess();
            helper.checkImage(imageDir, moduleName, res, files);

            String[] ppOptions = {"--pp", "on"};
            helper.postProcessImage(imageDir, ppOptions);
            test(imageDir);
        }
    }

    private static void test(Path imageDir)
            throws Exception {
        if (PostProcessingTest.called == null) {
            throw new Exception("Post processor not called.");
        }
        if (!PostProcessingTest.called.getHome().equals(imageDir)) {
            throw new Exception("Not right imageDir " + PostProcessingTest.called.getHome());
        }
        if (PostProcessingTest.called.getExecutionArgs().isEmpty()) {
            throw new Exception("No arguments to run java...");
        }
        Path gen = imageDir.resolve("lib").resolve("toto.txt");
        if (!Files.exists(gen)) {
            throw new Exception("Generated file doesn;t exist");
        }
        PostProcessingTest.called = null;
    }
}
