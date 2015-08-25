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

import java.io.IOException;
import java.lang.reflect.Layer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import jdk.tools.jlink.internal.ImagePluginConfiguration;
import jdk.tools.jlink.internal.ImagePluginProviderRepository;
import jdk.tools.jlink.internal.ImagePluginStack;
import jdk.tools.jlink.internal.ResourcePoolImpl;
import jdk.tools.jlink.plugins.CmdResourcePluginProvider;
import jdk.tools.jlink.plugins.PluginProvider;
import jdk.tools.jlink.plugins.ResourcePlugin;
import jdk.tools.jlink.plugins.ResourcePool;
import jdk.tools.jlink.plugins.StringTable;

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
        List<PluginProvider> javaPlugins = ImagePluginProviderRepository.getPluginProviders(Layer.boot());
        for (PluginProvider javaPlugin : javaPlugins) {
            System.out.println("Registered plugin: " + javaPlugin.getName());
        }
        for (PluginProvider javaPlugin : javaPlugins) {
            String pluginName = javaPlugin.getName();
            try {
                ImagePluginProviderRepository.registerPluginProvider(new CustomProvider(pluginName));
                try {
                    ImagePluginProviderRepository.getPluginProvider(pluginName, Layer.boot());
                    throw new AssertionError("Exception is not thrown for duplicate plugin: " + pluginName);
                } catch (Exception ignored) {
                }
            } finally {
                ImagePluginProviderRepository.unregisterPluginProvider(pluginName);
            }
        }
    }

    private void testUnknownProvider() {
        try {
            ImagePluginProviderRepository.getPluginProvider("unknown", Layer.boot());
            throw new AssertionError("Exception expected for unknown plugin name");
        } catch (Exception ignored) {
        }
    }

    private void testEmptyOutputResource() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY, "plugin");
        ImagePluginStack stack = ImagePluginConfiguration.parseConfiguration(properties);
        ResourcePoolImpl inResources = new ResourcePoolImpl(ByteOrder.nativeOrder());
        inResources.addResource(new ResourcePool.Resource("/aaa/bbb/A", ByteBuffer.allocate(10)));
        try {
            stack.visitResources(inResources, new StringTable() {
                @Override
                public int addString(String str) {
                    return -1;
                }

                @Override
                public String getString(int id) {
                    return null;
                }
            });
            throw new AssertionError("Exception expected when output resource is empty");
        } catch (Exception ignored) {
        }
    }

    private void testEmptyInputResource() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY, "plugin");
        ImagePluginStack stack = ImagePluginConfiguration.parseConfiguration(properties);
        ResourcePoolImpl inResources = new ResourcePoolImpl(ByteOrder.nativeOrder());
        ResourcePoolImpl outResources = (ResourcePoolImpl) stack.visitResources(inResources, new StringTable() {
            @Override
            public int addString(String str) {
                return -1;
            }

            @Override
            public String getString(int id) {
                return null;
            }
        });
        if (!outResources.isEmpty()) {
            throw new AssertionError("Output resource is not empty");
        }
    }

    public static class CustomPlugin implements ResourcePlugin {

        @Override
        public void visit(ResourcePool inResources, ResourcePool outResources, StringTable strings) throws Exception {
            // do nothing
        }

        @Override
        public String getName() {
            return "custom-provider";
        }
    }

    public static class CustomProvider extends CmdResourcePluginProvider {

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
        public ResourcePlugin[] newPlugins(String[] arguments, Map<String, String> otherOptions) throws IOException {
            return new ResourcePlugin[]{new CustomPlugin()};
        }
    }
}
