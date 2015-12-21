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
import java.io.UncheckedIOException;
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
import jdk.tools.jlink.api.plugin.postprocessor.PostProcessorPluginProvider;
import jdk.tools.jlink.internal.PluginRepository;
import jdk.tools.jlink.api.Jlink;
import jdk.tools.jlink.api.Jlink.JlinkConfiguration;
import jdk.tools.jlink.api.Jlink.OrderedPluginConfiguration;
import jdk.tools.jlink.api.Jlink.PluginConfiguration;
import jdk.tools.jlink.api.Jlink.PluginsConfiguration;
import jdk.tools.jlink.api.plugin.PluginOption;
import jdk.tools.jlink.api.plugin.PluginOptionBuilder;
import jdk.tools.jlink.api.plugin.builder.DefaultImageBuilderProvider;
import jdk.tools.jlink.api.plugin.postprocessor.ExecutableImage;
import jdk.tools.jlink.api.plugin.transformer.Pool;
import jdk.tools.jlink.api.plugin.postprocessor.PostProcessorPlugin;
import jdk.tools.jlink.api.plugin.transformer.TransformerPlugin;
import jdk.tools.jlink.api.plugin.transformer.TransformerPluginProvider;
import jdk.tools.jlink.internal.plugins.DefaultCompressProvider;
import jdk.tools.jlink.internal.plugins.StripDebugProvider;

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
 *          jdk.jlink/jdk.tools.jlink.internal.plugins
 *          jdk.jlink/jdk.tools.jmod
 *          jdk.jlink/jdk.tools.jimage
 *          jdk.compiler
 * @build tests.*
 * @run main IntegrationTest
 */
public class IntegrationTest {

    static {
        PluginRepository.registerPluginProvider(new MyProvider(MyProvider.NAME + "0"));
        PluginRepository.registerPluginProvider(new MyProvider(MyProvider.NAME + "1"));
        PluginRepository.registerPluginProvider(new MyProvider(MyProvider.NAME + "2"));
        PluginRepository.registerPluginProvider(new MyProvider(MyProvider.NAME + "3"));
        PluginRepository.registerPluginProvider(new MyProvider(MyProvider.NAME + "4"));
        PluginRepository.registerPluginProvider(new MyPostProcessorProvider());
    }

    private static final List<Integer> ordered = new ArrayList<>();

    public static class MyPostProcessorProvider extends PostProcessorPluginProvider {

        public class MyPostProcessor implements PostProcessorPlugin {

            @Override
            public List<String> process(ExecutableImage image) {
                try {
                    Files.createFile(image.getHome().resolve("toto.txt"));
                    return null;
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
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
        public PostProcessorPlugin newPlugin(Map<PluginOption, Object> config) {
            return new MyPostProcessor();
        }

        @Override
        public String getCategory() {
            return PROCESSOR;
        }

    }

    public static class MyProvider extends TransformerPluginProvider {

        public class MyPlugin1 implements TransformerPlugin {

            Integer index;

            private MyPlugin1(Integer index) {
                this.index = index;
            }

            @Override
            public String getName() {
                return NAME;
            }

            @Override
            public void visit(Pool in, Pool out) {
                System.err.println(NAME + index);
                ordered.add(index);
                in.visit((file) -> {
                    return file;
                }, out);
            }

        }
        static final String NAME = "myprovider";
        static final String INDEX = "INDEX";
        static final PluginOption INDEX_OPTION = new PluginOptionBuilder(INDEX).build();

        public MyProvider(String name) {
            super(name, "");
        }

        @Override
        public String getCategory() {
            return TRANSFORMER;
        }

        @Override
        public Type getType() {
            return Type.IMAGE_FILE_PLUGIN;
        }

        @Override
        public TransformerPlugin newPlugin(Map<PluginOption, Object> config) {
            return new MyPlugin1((Integer) config.get(new PluginOptionBuilder(INDEX).build()));
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
            OrderedPluginConfiguration spc = new OrderedPluginConfiguration(null, 0, failed, null);
            failed = true;
        } catch (Exception ex) {
            // XXX OK
        }
        if (failed) {
            throw new Exception("Should have failed");
        }
        System.out.println(new OrderedPluginConfiguration("toto", 0, failed, null));

        try {
            OrderedPluginConfiguration spc = new OrderedPluginConfiguration("toto", -1, failed, null);
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

        List<Jlink.OrderedPluginConfiguration> lst = new ArrayList<>();
        List<Jlink.OrderedPluginConfiguration> post = new ArrayList<>();

        //Strip debug
        {
            Map<PluginOption, Object> config1 = new HashMap<>();
            config1.put(StripDebugProvider.NAME_OPTION, PluginOptionBuilder.ON_ARGUMENT);
            OrderedPluginConfiguration strip
                    = new OrderedPluginConfiguration("strip-java-debug", 0, false, config1);
            lst.add(strip);
        }
        // compress
        {
            Map<PluginOption, Object> config1 = new HashMap<>();
            config1.put(DefaultCompressProvider.NAME_OPTION, PluginOptionBuilder.ON_ARGUMENT);
            config1.put(DefaultCompressProvider.LEVEL_OPTION, "0");
            OrderedPluginConfiguration compress
                    = new OrderedPluginConfiguration("compress-resources", 0, false, config1);
            lst.add(compress);
        }
        // Post processor
        {
            Map<PluginOption, Object> config1 = new HashMap<>();
            OrderedPluginConfiguration postprocessor
                    = new OrderedPluginConfiguration(MyPostProcessorProvider.NAME, 0, false, config1);
            post.add(postprocessor);
        }
        // Image builder
        Map<PluginOption, Object> config1 = new HashMap<>();
        config1.put(DefaultImageBuilderProvider.GEN_BOM_OPTION, "true");
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

        List<Jlink.OrderedPluginConfiguration> lst = new ArrayList<>();

        // packager 1
        {
            Map<PluginOption, Object> config1 = new HashMap<>();
            config1.put(MyProvider.INDEX_OPTION, 2);
            OrderedPluginConfiguration compress
                    = new OrderedPluginConfiguration(MyProvider.NAME + "0", 0, false, config1);
            lst.add(compress);
        }

        // packager 2
        {
            Map<PluginOption, Object> config1 = new HashMap<>();
            config1.put(MyProvider.INDEX_OPTION, 0);
            OrderedPluginConfiguration compress
                    = new OrderedPluginConfiguration(MyProvider.NAME + "1", 0, true, config1);
            lst.add(compress);
        }

        // packager 3
        {
            Map<PluginOption, Object> config1 = new HashMap<>();
            config1.put(MyProvider.INDEX_OPTION, 1);
            OrderedPluginConfiguration compress
                    = new OrderedPluginConfiguration(MyProvider.NAME + "2", 1, true, config1);
            lst.add(compress);
        }

        // packager 4
        {
            Map<PluginOption, Object> config1 = new HashMap<>();
            config1.put(MyProvider.INDEX_OPTION, 3);
            OrderedPluginConfiguration compress
                    = new OrderedPluginConfiguration(MyProvider.NAME + "3", 1, false, config1);
            lst.add(compress);
        }

        // Image builder
        Map<PluginOption, Object> config1 = new HashMap<>();
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

        List<Jlink.OrderedPluginConfiguration> lst = new ArrayList<>();

        // packager 1
        {
            Map<PluginOption, Object> config1 = new HashMap<>();
            config1.put(MyProvider.INDEX_OPTION, 2);
            OrderedPluginConfiguration compress
                    = new OrderedPluginConfiguration(MyProvider.NAME, 0, false, config1);
            lst.add(compress);
        }

        // packager 2
        {
            Map<PluginOption, Object> config1 = new HashMap<>();
            config1.put(MyProvider.INDEX_OPTION, 0);
            OrderedPluginConfiguration compress
                    = new OrderedPluginConfiguration(MyProvider.NAME, 0, false, config1);
            lst.add(compress);
        }

        // Image builder
        Map<PluginOption, Object> config1 = new HashMap<>();
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
