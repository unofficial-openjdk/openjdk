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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jdk.tools.jlink.internal.ImagePluginProviderRepository;
import jdk.tools.jlink.plugins.CmdPluginProvider;
import jdk.tools.jlink.plugins.ImageFilePlugin;
import jdk.tools.jlink.plugins.ImageFilePluginProvider;
import jdk.tools.jlink.plugins.ImageFilePool;
import jdk.tools.jlink.plugins.Jlink;
import jdk.tools.jlink.plugins.Jlink.JlinkConfiguration;
import jdk.tools.jlink.plugins.Jlink.PluginConfiguration;
import jdk.tools.jlink.plugins.Jlink.PluginsConfiguration;
import jdk.tools.jlink.plugins.Jlink.StackedPluginConfiguration;
import jdk.tools.jlink.plugins.PluginProvider;
import jdk.tools.jlink.plugins.PostProcessingPlugin;
import jdk.tools.jlink.plugins.PostProcessingPluginProvider;
import jdk.tools.jlink.plugins.ProcessingManager;

import tests.Helper;
import tests.JImageGenerator;

/*
 * @test
 * @summary Test integration API
 * @author Jean-Francois Denise
 * @library ../lib
 * @modules java.base/jdk.internal.jimage
 *          jdk.jdeps/com.sun.tools.classfile
 *          jdk.jlink/jdk.tools.jlink
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jmod
 *          jdk.jlink/jdk.tools.jimage
 *          jdk.compiler
 * @build tests.*
 * @run main IntegrationTest
 */
public class IntegrationTest {

    static {
        ImagePluginProviderRepository.registerPluginProvider(new MyProvider(MyProvider.NAME + "0"));
        ImagePluginProviderRepository.registerPluginProvider(new MyProvider(MyProvider.NAME + "1"));
        ImagePluginProviderRepository.registerPluginProvider(new MyProvider(MyProvider.NAME + "2"));
        ImagePluginProviderRepository.registerPluginProvider(new MyProvider(MyProvider.NAME + "3"));
        ImagePluginProviderRepository.registerPluginProvider(new MyProvider(MyProvider.NAME + "4"));
        ImagePluginProviderRepository.registerPluginProvider(new MyPostProcessorProvider());
    }

    private static final List<Integer> ordered = new ArrayList<>();

    public static class MyPostProcessorProvider extends PostProcessingPluginProvider {

        public class MyPostProcessor implements PostProcessingPlugin {

            @Override
            public List<String> process(ProcessingManager manager) throws Exception {
                Files.createFile(manager.getImage().getHome().resolve("toto.txt"));
                return null;
            }

            @Override
            public String getName() {
                return NAME;
            }

        }

        public static final String NAME = "mypostprocessor";

        public MyPostProcessorProvider() {
            super(NAME, "");
        }

        @Override
        public PostProcessingPlugin[] newPlugins(Map<String, Object> config) throws IOException {
            return new PostProcessingPlugin[]{new MyPostProcessor()};
        }

        @Override
        public String getCategory() {
            return PROCESSOR;
        }

    }

    public static class MyProvider extends ImageFilePluginProvider {
        public class MyPlugin1 implements ImageFilePlugin {

            Integer index;

            private MyPlugin1(Integer index) {
                this.index = index;
            }

            @Override
            public void visit(ImageFilePool inFiles, ImageFilePool outFiles) throws Exception {
                System.err.println(NAME + index);
                ordered.add(index);
                inFiles.visit((ImageFilePool.ImageFile file) -> {
                    return file;
                }, outFiles);
            }

            @Override
            public String getName() {
                return NAME;
            }

        }
        static final String NAME = "myprovider";
        static final String INDEX = "INDEX";

        public MyProvider(String name) {
            super(name, "");
        }

        @Override
        public ImageFilePlugin[] newPlugins(Map<String, Object> config) throws IOException {
            return new ImageFilePlugin[]{new MyPlugin1((Integer) config.get(INDEX))};
        }

        @Override
        public String getCategory() {
            return PluginProvider.TRANSFORMER;
        }
    }

    public static void main(String[] args) throws Exception {

        Helper helper = Helper.newHelper();
        if (helper == null) {
            System.err.println("Test not run");
            return;
        }
        apitest();
        test();
        testOrder();
        testNegativeOrder();
    }

    private static void apitest() throws Exception {
        boolean failed = false;
        Jlink jl = new Jlink();

        try {
            jl.build(null);
            failed = true;
        } catch (Exception ex) {
            // XXX OK
        }
        if (failed) {
            throw new Exception("Should have failed");
        }
        System.out.println(jl);

        JlinkConfiguration config
                = new JlinkConfiguration(null, null, null, null, null);

        System.out.println(config);

        try {
            PluginConfiguration pc = new PluginConfiguration(null, null);
            failed = true;
        } catch (Exception ex) {
            // XXX OK
        }
        if (failed) {
            throw new Exception("Should have failed");
        }
        System.out.println(new PluginConfiguration("toto", null));

        try {
            StackedPluginConfiguration spc = new StackedPluginConfiguration(null, 0, failed, null);
            failed = true;
        } catch (Exception ex) {
            // XXX OK
        }
        if (failed) {
            throw new Exception("Should have failed");
        }
        System.out.println(new StackedPluginConfiguration("toto", 0, failed, null));

        try {
            StackedPluginConfiguration spc = new StackedPluginConfiguration("toto", -1, failed, null);
            failed = true;
        } catch (Exception ex) {
            // XXX OK
        }
        if (failed) {
            throw new Exception("Should have failed");
        }
    }

    private static void test() throws Exception {
        Jlink jlink = new Jlink();
        Path output = Paths.get("integrationout");
        List<Path> modulePaths = new ArrayList<>();
        File jmods
                = JImageGenerator.getJModsDir(new File(System.getProperty("test.jdk")));
        modulePaths.add(jmods.toPath());
        Set<String> mods = new HashSet<>();
        mods.add("java.management");
        Set<String> limits = new HashSet<>();
        limits.add("java.management");
        JlinkConfiguration config = new Jlink.JlinkConfiguration(output,
                modulePaths, mods, limits, null);

        List<Jlink.StackedPluginConfiguration> lst = new ArrayList<>();
        List<Jlink.StackedPluginConfiguration> post = new ArrayList<>();

        //Strip debug
        {
            Map<String, Object> config1 = new HashMap<>();
            config1.put(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY, "on");
            StackedPluginConfiguration strip
                    = new StackedPluginConfiguration("strip-java-debug", 0, false, config1);
            lst.add(strip);
        }
        // compress
        {
            Map<String, Object> config1 = new HashMap<>();
            config1.put(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY, "on");
            config1.put("compress-resources-level", "0");
            StackedPluginConfiguration compress
                    = new StackedPluginConfiguration("compress-resources", 0, false, config1);
            lst.add(compress);
        }
        // Post processor
        {
            Map<String, Object> config1 = new HashMap<>();
            StackedPluginConfiguration postprocessor
                    = new StackedPluginConfiguration(MyPostProcessorProvider.NAME, 0, false, config1);
            post.add(postprocessor);
        }
        // Image builder
        Map<String, Object> config1 = new HashMap<>();
        config1.put("genbom", "true");
        PluginConfiguration imgBuilder
                = new Jlink.PluginConfiguration("default-image-builder", config1);
        PluginsConfiguration plugins
                = new Jlink.PluginsConfiguration(lst, post, imgBuilder);

        jlink.build(config, plugins);

        if (!Files.exists(output)) {
            throw new AssertionError("Directory not created");
        }
        File jimage = new File(output.toString(), "lib" + File.separator
                + "modules" + File.separator + "bootmodules.jimage");
        if (!jimage.exists()) {
            throw new AssertionError("jimage not generated");
        }
        File bom = new File(output.toString(), "bom");
        if (!bom.exists()) {
            throw new AssertionError("bom not generated");
        }
        File release = new File(output.toString(), "release");
        if (!release.exists()) {
            throw new AssertionError("release not generated");
        }

        if (!Files.exists(output.resolve("toto.txt"))) {
            throw new AssertionError("Post processing not called");
        }

    }

    private static void testOrder() throws Exception {
        Jlink jlink = new Jlink();
        Path output = Paths.get("integrationout2");
        List<Path> modulePaths = new ArrayList<>();
        File jmods
                = JImageGenerator.getJModsDir(new File(System.getProperty("test.jdk")));
        modulePaths.add(jmods.toPath());
        Set<String> mods = new HashSet<>();
        mods.add("java.management");
        Set<String> limits = new HashSet<>();
        limits.add("java.management");
        JlinkConfiguration config = new Jlink.JlinkConfiguration(output,
                modulePaths, mods, limits, null);

        List<Jlink.StackedPluginConfiguration> lst = new ArrayList<>();

        // packager 1
        {
            Map<String, Object> config1 = new HashMap<>();
            config1.put(MyProvider.INDEX, 2);
            StackedPluginConfiguration compress
                    = new StackedPluginConfiguration(MyProvider.NAME + "0", 0, false, config1);
            lst.add(compress);
        }

        // packager 2
        {
            Map<String, Object> config1 = new HashMap<>();
            config1.put(MyProvider.INDEX, 0);
            StackedPluginConfiguration compress
                    = new StackedPluginConfiguration(MyProvider.NAME + "1", 0, true, config1);
            lst.add(compress);
        }

        // packager 3
        {
            Map<String, Object> config1 = new HashMap<>();
            config1.put(MyProvider.INDEX, 1);
            StackedPluginConfiguration compress
                    = new StackedPluginConfiguration(MyProvider.NAME + "2", 1, true, config1);
            lst.add(compress);
        }

        // packager 4
        {
            Map<String, Object> config1 = new HashMap<>();
            config1.put(MyProvider.INDEX, 3);
            StackedPluginConfiguration compress
                    = new StackedPluginConfiguration(MyProvider.NAME + "3", 1, false, config1);
            lst.add(compress);
        }

        // Image builder
        Map<String, Object> config1 = new HashMap<>();
        PluginConfiguration imgBuilder
                = new Jlink.PluginConfiguration("default-image-builder", config1);
        PluginsConfiguration plugins
                = new Jlink.PluginsConfiguration(lst, Collections.emptyList(), imgBuilder);

        jlink.build(config, plugins);

        if (ordered.isEmpty()) {
            throw new AssertionError("Plugins not called");
        }
        List<Integer> clone = new ArrayList<>();
        clone.addAll(ordered);
        Collections.sort(clone);
        if (!clone.equals(ordered)) {
            throw new AssertionError("Ordered is not properly sorted" + ordered);
        }
    }

    private static void testNegativeOrder() throws Exception {
        Jlink jlink = new Jlink();
        Path output = Paths.get("integrationout3");
        List<Path> modulePaths = new ArrayList<>();
        File jmods
                = JImageGenerator.getJModsDir(new File(System.getProperty("test.jdk")));
        modulePaths.add(jmods.toPath());
        Set<String> mods = new HashSet<>();
        mods.add("java.management");
        Set<String> limits = new HashSet<>();
        limits.add("java.management");
        JlinkConfiguration config = new Jlink.JlinkConfiguration(output,
                modulePaths, mods, limits, null);

        List<Jlink.StackedPluginConfiguration> lst = new ArrayList<>();

        // packager 1
        {
            Map<String, Object> config1 = new HashMap<>();
            config1.put(MyProvider.INDEX, 2);
            StackedPluginConfiguration compress
                    = new StackedPluginConfiguration(MyProvider.NAME, 0, false, config1);
            lst.add(compress);
        }

        // packager 2
        {
            Map<String, Object> config1 = new HashMap<>();
            config1.put(MyProvider.INDEX, 0);
            StackedPluginConfiguration compress
                    = new StackedPluginConfiguration(MyProvider.NAME, 0, false, config1);
            lst.add(compress);
        }

        // Image builder
        Map<String, Object> config1 = new HashMap<>();
        PluginConfiguration imgBuilder
                = new Jlink.PluginConfiguration("default-image-builder", config1);
        PluginsConfiguration plugins
                = new Jlink.PluginsConfiguration(lst, Collections.emptyList(), imgBuilder);
        boolean failed = false;
        try {
            jlink.build(config, plugins);
            failed = true;
        } catch (Exception ex) {
            // XXX OK
        }
        if (failed) {
            throw new AssertionError("Should have failed");
        }
    }
}
