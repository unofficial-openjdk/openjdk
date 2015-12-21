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
 * @summary Negative test for ImagePluginStack.
 * @author Andrei Eremeev
 * @modules jdk.jlink/jdk.tools.jlink.internal
 * @run main/othervm PluginsNegativeTest
 */

import java.lang.reflect.Layer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import jdk.tools.jlink.internal.ImagePluginConfiguration;
import jdk.tools.jlink.internal.ImagePluginProviderRepository;
import jdk.tools.jlink.internal.ImagePluginStack;
import jdk.tools.jlink.internal.PoolImpl;
import jdk.tools.jlink.plugins.Jlink;
import jdk.tools.jlink.plugins.Jlink.PluginsConfiguration;
import jdk.tools.jlink.plugins.PluginProvider;
import jdk.tools.jlink.plugins.Pool;
import jdk.tools.jlink.plugins.TransformerCmdProvider;
import jdk.tools.jlink.plugins.TransformerPlugin;

public class PluginsNegativeTest {
    public static void main(String[] args) throws Exception {
        new PluginsNegativeTest().test();
    }

    public void test() throws Exception {
        testDuplicateBuiltInProviders();
        testUnknownProvider();
        ImagePluginProviderRepository.registerPluginProvider(new CustomProvider("plugin"));
        testEmptyInputResource();
        testEmptyOutputResource();
    }

    private void testDuplicateBuiltInProviders() {
        List<PluginProvider> javaPlugins = new ArrayList<>();
        javaPlugins.addAll(ImagePluginProviderRepository.getTransformerProviders(Layer.boot()));
        for (PluginProvider javaPlugin : javaPlugins) {
            System.out.println("Registered plugin: " + javaPlugin.getName());
        }
        for (PluginProvider javaPlugin : javaPlugins) {
            String pluginName = javaPlugin.getName();
            try {
                ImagePluginProviderRepository.registerPluginProvider(new CustomProvider(pluginName));
                try {
                    ImagePluginProviderRepository.getTransformerPluginProvider(pluginName, Layer.boot());
                    throw new AssertionError("Exception is not thrown for duplicate plugin: " + pluginName);
                } catch (Exception ignored) {
                }
            } finally {
                ImagePluginProviderRepository.unregisterPluginProvider(pluginName);
            }
        }
    }

    private void testUnknownProvider() {
        if (ImagePluginProviderRepository.getTransformerPluginProvider("unknown", Layer.boot()) != null) {
            throw new AssertionError("Exception expected for unknown plugin name");
        }
        if (ImagePluginProviderRepository.getPostProcessingPluginProvider("unknown", Layer.boot()) != null) {
            throw new AssertionError("Exception expected for unknown plugin name");
        }
        if (ImagePluginProviderRepository.getImageBuilderProvider("unknown", Layer.boot()) != null) {
            throw new AssertionError("Exception expected for unknown plugin name");
        }
    }

    private static Jlink.OrderedPluginConfiguration createConfig(String name, int index) {
        return new Jlink.OrderedPluginConfiguration(name, index, true, Collections.emptyMap());
    }

    private void testEmptyOutputResource() throws Exception {
        List<Jlink.OrderedPluginConfiguration> plugins = new ArrayList<>();
        plugins.add(createConfig("plugin", 0));
        ImagePluginStack stack = ImagePluginConfiguration.parseConfiguration(new PluginsConfiguration(plugins,
                Collections.emptyList(), null));
        PoolImpl inResources = new PoolImpl();
        inResources.add(Pool.newResource("/aaa/bbb/A", new byte[10]));
        try {
            stack.visitResources(inResources);
            throw new AssertionError("Exception expected when output resource is empty");
        } catch (Exception ignored) {
        }
    }

    private void testEmptyInputResource() throws Exception {
        List<Jlink.OrderedPluginConfiguration> plugins = new ArrayList<>();
        plugins.add(createConfig("plugin", 0));
        ImagePluginStack stack = ImagePluginConfiguration.parseConfiguration(new PluginsConfiguration(plugins,
                Collections.emptyList(), null));
        PoolImpl inResources = new PoolImpl();
        PoolImpl outResources = (PoolImpl) stack.visitResources(inResources);
        if (!outResources.isEmpty()) {
            throw new AssertionError("Output resource is not empty");
        }
    }

    public static class CustomPlugin implements TransformerPlugin {

        @Override
        public void visit(Pool inResources, Pool outResources) {
            // do nothing
        }

        @Override
        public String getName() {
            return "custom-provider";
        }
    }

    public static class CustomProvider extends TransformerCmdProvider {

        protected CustomProvider(String name) {
            super(name, "");
        }

        @Override
        public String getCategory() {
            return null;
        }

        @Override
        public String getToolArgument() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getToolOption() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, String> getAdditionalOptions() {
            return null;
        }

        @Override
        public List<TransformerPlugin> newPlugins(String[] arguments, Map<String, String> otherOptions) {
            List<TransformerPlugin> lst = new ArrayList<>();
            lst.add(new CustomPlugin());
            return lst;
        }

        @Override
        public Type getType() {
            return Type.RESOURCE_PLUGIN;
        }
    }
}
