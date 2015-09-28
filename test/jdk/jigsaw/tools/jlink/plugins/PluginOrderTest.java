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
 * @summary Test order of plugins
 * @author Jean-Francois Denise
 * @modules jdk.jlink/jdk.tools.jlink.internal
 * @run main/othervm PluginOrderTest
 */

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
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

public class PluginOrderTest {

    public static void main(String[] args) throws Exception {
        new PluginOrderTest().test();
    }

    public void test() throws Exception {
        test1();

        List<String> order = new ArrayList<>();
        ImagePluginProviderRepository.registerPluginProvider(new PProvider("plugin1", order));
        ImagePluginProviderRepository.registerPluginProvider(new PProvider("plugin2", order));
        ImagePluginProviderRepository.registerPluginProvider(new PProvider("plugin3", order));
        ImagePluginProviderRepository.registerPluginProvider(new PProvider("plugin4", order));
        ImagePluginProviderRepository.registerPluginProvider(new PProvider("plugin5", order));
        ImagePluginProviderRepository.registerPluginProvider(new PProvider("plugin6", order));
        ImagePluginProviderRepository.registerPluginProvider(new PProvider("plugin7", order));
        ImagePluginProviderRepository.registerPluginProvider(new PProvider("plugin8", order));

        test2(order);

        test3(order);

        test4(order);

        test5(order);

        test6(order);

        test7(order);

        test8(order);

        test9(order);

        testInvalidProperties();
    }

    private void check(Properties props, List<String> expected, List<String> order)
            throws Exception {
        order.clear();
        ImagePluginStack plugins = ImagePluginConfiguration.parseConfiguration(props);
        ResourcePoolImpl pool = new ResourcePoolImpl(ByteOrder.nativeOrder());
        pool.addResource(new ResourcePool.Resource("/mod/com/foo/bar/A.somthing",
                ByteBuffer.allocate(0)));
        plugins.visitResources(pool, new StringTable() {

            @Override
            public int addString(String str) {
                return -1;
            }

            @Override
            public String getString(int id) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        });
        if (!order.equals(expected)) {
            throw new Exception("plugins not called in right order. Expected "
                    + expected + " actual " + order);
        }
        System.err.println("Gathered plugins: " + order);
    }

    private void test1() throws Exception {
        Properties props = new Properties();
        props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY, "plugin1");
        props.setProperty(ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY, "plugin2");
        props.setProperty(ImagePluginConfiguration.RESOURCES_SORTER_PROPERTY, "plugin3");
        props.setProperty(ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY, "plugin4");
        int i = getNextIndex(props, PluginProvider.FILTER);
        if (i != 1) {
            throw new Exception("Unexpected index " + i);
        }
        i = getNextIndex(props, PluginProvider.TRANSFORMER);
        if (i != 1) {
            throw new Exception("Unexpected index " + i);
        }
        i = getNextIndex(props, PluginProvider.SORTER);
        if (i != 1) {
            throw new Exception("Unexpected index " + i);
        }
        i = getNextIndex(props, PluginProvider.COMPRESSOR);
        if (i != 1) {
            throw new Exception("Unexpected index " + i);
        }

        i = getNextIndex(props, PluginProvider.TRANSFORMER);
        if (i != 2) {
            throw new Exception("Unexpected index " + i);
        }
        i = getNextIndex(props, PluginProvider.SORTER);
        if (i != 2) {
            throw new Exception("Unexpected index " + i);
        }
        i = getNextIndex(props, PluginProvider.COMPRESSOR);
        if (i != 2) {
            throw new Exception("Unexpected index " + i);
        }
        props = new Properties();
        props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY + ".500", "plugin1");
        i = getNextIndex(props, PluginProvider.FILTER);
        if (i != 501) {
            throw new Exception("Unexpected index " + i);
        }
    }

    private int getNextIndex(Properties props, String category) {
        ImagePluginConfiguration.addPluginProperty(props, new CategoryProvider(category));
        int max = 0;
        for (String prop : props.stringPropertyNames()) {
            if (prop.startsWith(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY + category)) {
                int i = prop.lastIndexOf(".");
                String v = prop.substring(i + 1);
                try {
                    int index = Integer.valueOf(v);
                    if (index > max) {
                        max = index;
                    }
                } catch (NumberFormatException ex) {
                    // XXX OK, not a number
                }
            }
        }
        if (max == 0) {
            throw new RuntimeException("Next index not found");
        }
        return max;
    }

    private void test2(List<String> order) throws Exception {
        Properties props = new Properties();
        props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY, "plugin1");
        props.setProperty(ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY, "plugin2");
        props.setProperty(ImagePluginConfiguration.RESOURCES_SORTER_PROPERTY, "plugin3");
        props.setProperty(ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY, "plugin4");

        check(props, Arrays.asList("plugin1", "plugin2", "plugin3", "plugin4"), order);
    }

    private void test3(List<String> order) throws Exception {
        Properties props = new Properties();
        props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY + ".250", "plugin4");
        props.setProperty(ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY + ".100", "plugin3");
        props.setProperty(ImagePluginConfiguration.RESOURCES_SORTER_PROPERTY + ".50", "plugin2");
        props.setProperty(ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY + ".10", "plugin1");

        check(props, Arrays.asList("plugin4", "plugin3", "plugin2", "plugin1"), order);
    }

    private void test4(List<String> order) throws Exception {
        Properties props = new Properties();
        props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY + ".0",
                "plugin2");
        props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY + ".1",
                "plugin3");
        props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY + ".2",
                "plugin4");
        props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY + ".3",
                "plugin1");

        check(props, Arrays.asList("plugin2", "plugin3", "plugin4", "plugin1"), order);
    }

    private void test5(List<String> order) throws Exception {
        Properties props = new Properties();
        props.setProperty(ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY + ".0",
                "plugin2");
        props.setProperty(ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY + ".1",
                "plugin3");
        props.setProperty(ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY + ".2",
                "plugin4");
        props.setProperty(ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY + ".3",
                "plugin1");

        check(props, Arrays.asList("plugin2", "plugin3", "plugin4", "plugin1"), order);
    }

    private void test6(List<String> order) throws Exception {
        Properties props = new Properties();
        props.setProperty(ImagePluginConfiguration.RESOURCES_SORTER_PROPERTY + ".0",
                "plugin2");
        props.setProperty(ImagePluginConfiguration.RESOURCES_SORTER_PROPERTY + ".1",
                "plugin3");
        props.setProperty(ImagePluginConfiguration.RESOURCES_SORTER_PROPERTY + ".2",
                "plugin4");
        props.setProperty(ImagePluginConfiguration.RESOURCES_SORTER_PROPERTY + ".3",
                "plugin1");

        check(props, Arrays.asList("plugin2", "plugin3", "plugin4", "plugin1"), order);
    }

    private void test7(List<String> order) throws Exception {
        Properties props = new Properties();
        props.setProperty(ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY + ".0",
                "plugin2");
        props.setProperty(ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY + ".1",
                "plugin3");
        props.setProperty(ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY + ".2",
                "plugin4");
        props.setProperty(ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY + ".3",
                "plugin1");

        check(props, Arrays.asList("plugin2", "plugin3", "plugin4", "plugin1"), order);
    }

    private void test8(List<String> order) throws Exception {
        Properties props = new Properties();
        props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY
                        + ImagePluginConfiguration.
                getRange(new CategoryProvider(PluginProvider.FILTER))[0],
                "plugin1");
        props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY
                        + ImagePluginConfiguration.
                getRange(new CategoryProvider(PluginProvider.TRANSFORMER))[0],
                "plugin3");
        props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY
                        + ImagePluginConfiguration.
                getRange(new CategoryProvider(PluginProvider.SORTER))[0],
                "plugin2");
        props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY
                        + ImagePluginConfiguration.
                getRange(new CategoryProvider(PluginProvider.COMPRESSOR))[0],
                "plugin4");

        check(props, Arrays.asList("plugin1", "plugin3", "plugin2", "plugin4"), order);
    }

    private void test9(List<String> order) throws Exception {
        Properties props = new Properties();
        props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY, "plugin1");
        props.setProperty(ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY, "plugin2");
        props.setProperty(ImagePluginConfiguration.RESOURCES_SORTER_PROPERTY, "plugin3");
        props.setProperty(ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY, "plugin4");
        props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY
                        + (ImagePluginConfiguration.
                getRange(new CategoryProvider(PluginProvider.FILTER))[0] + 1),
                "plugin5");
        props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY
                        + (ImagePluginConfiguration.
                getRange(new CategoryProvider(PluginProvider.TRANSFORMER))[0] + 1),
                "plugin6");
        props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY
                        + (ImagePluginConfiguration.
                getRange(new CategoryProvider(PluginProvider.SORTER))[0] + 1),
                "plugin7");
        props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY
                        + (ImagePluginConfiguration.
                getRange(new CategoryProvider(PluginProvider.COMPRESSOR))[0] + 1),
                "plugin8");

        List<String> expected = new ArrayList<>();
        expected.add("plugin1");
        expected.add("plugin5");
        expected.add("plugin2");
        expected.add("plugin6");
        expected.add("plugin3");
        expected.add("plugin7");
        expected.add("plugin4");
        expected.add("plugin8");

        check(props, expected, order);
    }

    private void testInvalidProperties() throws Exception {
        // Now invalid properties
        {
            boolean failed = false;
            try {
                Properties props = new Properties();
                props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY + ".0.90",
                        "plugin1");
                ImagePluginConfiguration.parseConfiguration(props);
            } catch (Exception ex) {
                failed = true;
            }
            if (!failed) {
                throw new Exception("Test case should have failed");
            }
        }

        {
            boolean failed = false;
            try {
                Properties props = new Properties();
                props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY + "90.23",
                        "plugin1");
                ImagePluginConfiguration.parseConfiguration(props);
            } catch (Exception ex) {
                failed = true;
            }
            if (!failed) {
                throw new Exception("Test case should have failed");
            }
        }

        {
            boolean failed = false;
            try {
                Properties props = new Properties();
                props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY,
                        "plugin1");
                ImagePluginConfiguration.parseConfiguration(props);
            } catch (Exception ex) {
                failed = true;
            }
            if (!failed) {
                throw new Exception("Test case should have failed");
            }
        }

        {
            boolean failed = false;
            try {
                Properties props = new Properties();
                props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY, "plugin1");
                props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY
                                + ImagePluginConfiguration.getRange(
                                new CategoryProvider(PluginProvider.FILTER))[0],
                        "plugin5");
                ImagePluginConfiguration.parseConfiguration(props);
            } catch (Exception ex) {
                failed = true;
            }
            if (!failed) {
                throw new Exception("Test case should have failed");
            }
        }
    }

    public static class PProvider extends CmdResourcePluginProvider {

        private final List<String> order;

        PProvider(String name, List<String> order) {
            super(name, "");
            this.order = order;
        }

        @Override
        public ResourcePlugin[] newPlugins(String[] argument, Map<String, String> options) throws IOException {
            return new ResourcePlugin[]{                    new PluginTrap(getName(), order)
            };
        }

        @Override
        public String getCategory() {
            return null;
        }

        @Override
        public String getToolOption() {
            return null;
        }

        @Override
        public String getToolArgument() {
            return null;
        }

        @Override
        public Map<String, String> getAdditionalOptions() {
            return null;
        }
    }

    public static class PluginTrap implements ResourcePlugin {

        private final String name;
        private final List<String> order;

        private PluginTrap(String name, List<String> order) {
            this.name = name;
            this.order = order;
        }

        @Override
        public void visit(ResourcePool resources, ResourcePool output, StringTable strings)
                throws Exception {
            order.add(name);
            output.addResource(new ResourcePool.Resource("/module/com/foo/bar/X.st", ByteBuffer.allocate(0)));
        }

        @Override
        public String getName() {
            return name;
        }
    }

    public static class CategoryProvider extends CmdResourcePluginProvider {

        private final String category;

        CategoryProvider(String category) {
            super("CategoryProvider", "");
            this.category = category;
        }

        @Override
        public ResourcePlugin[] newPlugins(String[] argument,
                                           Map<String, String> options) throws IOException {
            throw new IOException("Shouldn't be called");
        }

        @Override
        public String getCategory() {
            return category;
        }

        @Override
        public String getToolArgument() {
            throw new UnsupportedOperationException("Shouldn't be called");
        }

        @Override
        public String getToolOption() {
            throw new UnsupportedOperationException("Shouldn't be called");
        }

        @Override
        public Map<String, String> getAdditionalOptions() {
            throw new UnsupportedOperationException("Shouldn't be called");
        }
    }
}
