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
 * @summary Test last sorter property
 * @author Jean-Francois Denise
 * @modules jdk.jlink/jdk.tools.jlink.internal
 * @run main/othervm LastSorterTest
 */

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jdk.tools.jlink.internal.ImagePluginConfiguration;
import jdk.tools.jlink.internal.ImagePluginProviderRepository;
import jdk.tools.jlink.internal.ImagePluginStack;
import jdk.tools.jlink.internal.ResourcePoolImpl;
import jdk.tools.jlink.plugins.CmdPluginProvider;
import jdk.tools.jlink.plugins.CmdResourcePluginProvider;
import jdk.tools.jlink.plugins.Jlink;
import jdk.tools.jlink.plugins.Jlink.PluginsConfiguration;
import jdk.tools.jlink.plugins.Jlink.StackedPluginConfiguration;
import jdk.tools.jlink.plugins.ResourcePlugin;
import jdk.tools.jlink.plugins.ResourcePool;
import jdk.tools.jlink.plugins.StringTable;

public class LastSorterTest {

    public LastSorterTest() {
        for (int i = 1; i <= 5; i++) {
            ImagePluginProviderRepository.registerPluginProvider(new SorterProvider("sorterplugin" + i));
        }
        ImagePluginProviderRepository.registerPluginProvider(new SorterProvider2("sorterplugin" + 6));
    }

    public static void main(String[] args) throws Exception {
        new LastSorterTest().test();
    }

    public void test() throws Exception {
        checkUnknownPlugin();

        checkOrderAfterLastSorter();

        checkPositiveCase();

        checkTwoLastSorters();
    }

    private void checkTwoLastSorters() throws Exception {
        List<StackedPluginConfiguration> plugins = new ArrayList<>();
        plugins.add(createConfig("sorterplugin6", "/a", 0));
        PluginsConfiguration config = new Jlink.PluginsConfiguration(plugins,
                Collections.emptyList(), null, "sorterplugin6");

        ImagePluginStack stack = ImagePluginConfiguration.parseConfiguration(config);

        // check order
        ResourcePoolImpl res = fillOutResourcePool();

        try {
            stack.visitResources(res, new StringTable() {
                @Override
                public int addString(String str) {
                    return -1;
                }

                @Override
                public String getString(int id) {
                    throw new UnsupportedOperationException("Not supported yet.");
                }
            });
            throw new AssertionError("Exception expected: Order of resources is already frozen." +
                    "Plugin sorterplugin6 is badly located");
        } catch (Exception e) {
            // expected
        }
    }

    private ResourcePoolImpl fillOutResourcePool() throws Exception {
        ResourcePoolImpl res = new ResourcePoolImpl(ByteOrder.nativeOrder());
        res.addResource(new ResourcePool.Resource("/eee/bbb/res1.class", ByteBuffer.allocate(90)));
        res.addResource(new ResourcePool.Resource("/aaaa/bbb/res2.class", ByteBuffer.allocate(90)));
        res.addResource(new ResourcePool.Resource("/bbb/aa/res1.class", ByteBuffer.allocate(90)));
        res.addResource(new ResourcePool.Resource("/aaaa/bbb/res3.class", ByteBuffer.allocate(90)));
        res.addResource(new ResourcePool.Resource("/bbb/aa/res2.class", ByteBuffer.allocate(90)));
        res.addResource(new ResourcePool.Resource("/fff/bbb/res1.class", ByteBuffer.allocate(90)));
        res.addResource(new ResourcePool.Resource("/aaaa/bbb/res1.class", ByteBuffer.allocate(90)));
        res.addResource(new ResourcePool.Resource("/bbb/aa/res3.class", ByteBuffer.allocate(90)));
        res.addResource(new ResourcePool.Resource("/ccc/bbb/res1.class", ByteBuffer.allocate(90)));
        res.addResource(new ResourcePool.Resource("/ddd/bbb/res1.class", ByteBuffer.allocate(90)));
        return res;
    }

    private static StackedPluginConfiguration createConfig(String name, String arg, int index) {
        Map<String, Object> conf = new HashMap<>();
        conf.put(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY, arg);
        return new StackedPluginConfiguration(name, index, true, conf);
    }

    private void checkPositiveCase() throws Exception {
        List<StackedPluginConfiguration> plugins = new ArrayList<>();
        plugins.add(createConfig("sorterplugin1", "/c", 0));
        plugins.add(createConfig("sorterplugin2", "/b", 1));
        plugins.add(createConfig("sorterplugin3", "/a", 2));

        PluginsConfiguration config = new Jlink.PluginsConfiguration(plugins,
                Collections.emptyList(), null, "sorterplugin3");

        ImagePluginStack stack = ImagePluginConfiguration.parseConfiguration(config);

        // check order
        ResourcePoolImpl res = fillOutResourcePool();

        stack.visitResources(res, new StringTable() {
            @Override
            public int addString(String str) {
                return -1;
            }

            @Override
            public String getString(int id) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        });
    }

    private void checkUnknownPlugin() {
        List<StackedPluginConfiguration> plugins = new ArrayList<>();
        plugins.add(createConfig("sorterplugin1", "/1", 0));
        plugins.add(createConfig("sorterplugin2", "/1", 1));
        plugins.add(createConfig("sorterplugin3", "/1", 2));
        plugins.add(createConfig("sorterplugin4", "/1", 3));

        PluginsConfiguration config = new Jlink.PluginsConfiguration(plugins,
                Collections.emptyList(), null, "sorterplugin5");
        try {
            ImagePluginConfiguration.parseConfiguration(config);
            throw new AssertionError("Unknown plugin should have failed.");
        } catch (Exception ex) {
            // XXX OK expected
        }
    }

    private void checkOrderAfterLastSorter() throws Exception {
        List<StackedPluginConfiguration> plugins = new ArrayList<>();
        plugins.add(createConfig("sorterplugin1", "/c", 0));
        plugins.add(createConfig("sorterplugin2", "/b", 1));
        plugins.add(createConfig("sorterplugin3", "/a", 2));
        plugins.add(createConfig("sorterplugin4", "/d", 3));

        PluginsConfiguration config = new Jlink.PluginsConfiguration(plugins,
                Collections.emptyList(), null, "sorterplugin3");

        ImagePluginStack stack = ImagePluginConfiguration.parseConfiguration(config);

        // check order
        ResourcePoolImpl res = fillOutResourcePool();
        try {
            stack.visitResources(res, new StringTable() {
                @Override
                public int addString(String str) {
                    return -1;
                }

                @Override
                public String getString(int id) {
                    throw new UnsupportedOperationException("Not supported yet.");
                }
            });
            throw new AssertionError("Order was changed after the last sorter, but no exception occurred");
        } catch (Exception ex) {
            // XXX OK expected
        }
    }

    public static class SorterPlugin implements ResourcePlugin {

        private final String name;
        private final String starts;

        private SorterPlugin(String name, String starts) {
            this.name = name;
            this.starts = starts;
        }

        @Override
        public void visit(ResourcePool resources, ResourcePool output, StringTable strings)
                throws Exception {
            List<ResourcePool.Resource> paths = new ArrayList<>();
            for (ResourcePool.Resource res : resources.getResources()) {
                if (res.getPath().startsWith(starts)) {
                    paths.add(0, res);
                } else {
                    paths.add(res);
                }
            }

            for (ResourcePool.Resource r : paths) {
                output.addResource(r);
            }
        }

        @Override
        public String getName() {
            return name;
        }
    }

    public static class SorterProvider extends CmdResourcePluginProvider {

        SorterProvider(String name) {
            super(name, "");
        }

        @Override
        public ResourcePlugin[] newPlugins(String[] arguments, Map<String, String> options)
                throws IOException {
            return new ResourcePlugin[]{new SorterPlugin(getName(), arguments == null ? null : arguments[0])};
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

    public static class SorterProvider2 extends SorterProvider {

        SorterProvider2(String name) {
            super(name);
        }

        @Override
        public ResourcePlugin[] newPlugins(String[] arguments, Map<String, String> options)
                throws IOException {
            SorterPlugin sorterPlugin = (SorterPlugin) super.newPlugins(arguments, options)[0];
            return new ResourcePlugin[]{                    sorterPlugin,
                    sorterPlugin
            };
        }
    }
}
