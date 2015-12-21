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
import jdk.tools.jlink.Jlink;
import jdk.tools.jlink.Jlink.JlinkConfiguration;
import jdk.tools.jlink.Jlink.OrderedPlugin;
import jdk.tools.jlink.Jlink.PluginsConfiguration;
import jdk.tools.jlink.plugin.PluginOption;
import jdk.tools.jlink.builder.DefaultImageBuilder;
import jdk.tools.jlink.plugin.ExecutableImage;
import jdk.tools.jlink.plugin.Pool;
import jdk.tools.jlink.plugin.PostProcessorPlugin;
import jdk.tools.jlink.plugin.TransformerPlugin;
import jdk.tools.jlink.internal.plugins.DefaultCompressPlugin;
import jdk.tools.jlink.internal.plugins.StripDebugPlugin;

import tests.Helper;
import tests.JImageGenerator;

/*
 * @test
 * @summary Test integration API
 * @author Jean-Francois Denise
 * @library ../lib
 * @modules java.base/jdk.internal.jimage
 *          jdk.jdeps/com.sun.tools.classfile
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jlink.internal.plugins
 *          jdk.jlink/jdk.tools.jmod
 *          jdk.jlink/jdk.tools.jimage
 *          jdk.compiler
 * @build tests.*
 * @run main IntegrationTest
 */
public class IntegrationTest {

    private static final List<Integer> ordered = new ArrayList<>();

    public static class MyPostProcessor implements PostProcessorPlugin {

        public static final String NAME = "mypostprocessor";

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

        @Override
        public Set<PluginType> getType() {
            Set<PluginType> set = new HashSet<>();
            set.add(CATEGORY.PROCESSOR);
            return Collections.unmodifiableSet(set);
        }

        @Override
        public String getDescription() {
            return null;
        }

        @Override
        public PluginOption getOption() {
            return null;
        }

        @Override
        public void configure(Map<PluginOption, String> config) {
            throw new UnsupportedOperationException("Shouldn't be called");
        }
    }

    public static class MyPlugin1 implements TransformerPlugin {

        Integer index;

        private MyPlugin1(Integer index) {
            this.index = index;
        }

        @Override
        public String getName() {
            return NAME + index;
        }

        @Override
        public void visit(Pool in, Pool out) {
            System.err.println(NAME + index);
            ordered.add(index);
            in.visit((file) -> {
                return file;
            }, out);
        }

        @Override
        public Set<PluginType> getType() {
            Set<PluginType> set = new HashSet<>();
            set.add(CATEGORY.TRANSFORMER);
            return Collections.unmodifiableSet(set);
        }

        @Override
        public String getDescription() {
            return null;
        }

        @Override
        public PluginOption getOption() {
            return null;
        }
        static final String NAME = "myprovider";
        static final String INDEX = "INDEX";
        static final PluginOption INDEX_OPTION = new PluginOption.Builder(INDEX).build();

        @Override
        public void configure(Map<PluginOption, String> config) {
            throw new UnsupportedOperationException("Shouldn't be called");
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
                = new JlinkConfiguration(null, null, null, null);

        System.out.println(config);

        try {
            OrderedPlugin spc = new OrderedPlugin(null, 0, false);
            failed = true;
        } catch (Exception ex) {
            // XXX OK
        }
        if (failed) {
            throw new Exception("Should have failed");
        }

        try {
            OrderedPlugin spc = new OrderedPlugin(null, null, 0, false, null);
            failed = true;
        } catch (Exception ex) {
            // XXX OK
        }
        if (failed) {
            throw new Exception("Should have failed");
        }

        System.out.println(new OrderedPlugin(new MyPlugin1(0), 0, false));

        System.out.println(new OrderedPlugin(StripDebugPlugin.NAME, 0, false, Collections.emptyMap()));

        try {
            new OrderedPlugin("toto", 0, false, Collections.emptyMap());
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

        List<Jlink.OrderedPlugin> lst = new ArrayList<>();

        //Strip debug
        {
            Map<PluginOption, String> config1 = new HashMap<>();
            config1.put(StripDebugPlugin.NAME_OPTION, PluginOption.Builder.ON_ARGUMENT);
            OrderedPlugin strip
                    = new OrderedPlugin("strip-debug", 0, false, config1);
            lst.add(strip);
        }
        // compress
        {
            Map<PluginOption, String> config1 = new HashMap<>();
            config1.put(DefaultCompressPlugin.NAME_OPTION, PluginOption.Builder.ON_ARGUMENT);
            config1.put(DefaultCompressPlugin.LEVEL_OPTION, "0");
            OrderedPlugin compress
                    = new OrderedPlugin("compress-resources", 0, false, config1);
            lst.add(compress);
        }
        // Post processor
        {
            Map<PluginOption, Object> config1 = new HashMap<>();
            OrderedPlugin postprocessor
                    = new OrderedPlugin(new MyPostProcessor(), 0, false);
            lst.add(postprocessor);
        }
        // Image builder
        Map<PluginOption, String> config1 = new HashMap<>();
        DefaultImageBuilder builder = new DefaultImageBuilder(true, output);
        PluginsConfiguration plugins
                = new Jlink.PluginsConfiguration(lst, builder, null);

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

        List<Jlink.OrderedPlugin> lst = new ArrayList<>();

        // packager 1
        {
            OrderedPlugin compress
                    = new OrderedPlugin(new MyPlugin1(2), 0, false);
            lst.add(compress);
        }

        // packager 2
        {
            OrderedPlugin compress
                    = new OrderedPlugin(new MyPlugin1(0), 0, true);
            lst.add(compress);
        }

        // packager 3
        {
            OrderedPlugin compress
                    = new OrderedPlugin(new MyPlugin1(1), 1, true);
            lst.add(compress);
        }

        // packager 4
        {
            OrderedPlugin compress
                    = new OrderedPlugin(new MyPlugin1(3), 1, false);
            lst.add(compress);
        }

        // Image builder
        DefaultImageBuilder builder = new DefaultImageBuilder(false, output);
        PluginsConfiguration plugins
                = new Jlink.PluginsConfiguration(lst, builder, null);

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

        List<Jlink.OrderedPlugin> lst = new ArrayList<>();

        // packager 1
        {
            OrderedPlugin compress
                    = new OrderedPlugin(new MyPlugin1(2), 0, false);
            lst.add(compress);
        }

        // packager 2
        {
            OrderedPlugin compress
                    = new OrderedPlugin(new MyPlugin1(0), 0, false);
            lst.add(compress);
        }

        // Image builder
        DefaultImageBuilder builder = new DefaultImageBuilder(false, output);
        PluginsConfiguration plugins
                = new Jlink.PluginsConfiguration(lst, builder, null);
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
