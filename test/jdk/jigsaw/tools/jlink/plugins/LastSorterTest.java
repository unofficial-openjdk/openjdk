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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.tools.jlink.internal.ImagePluginConfiguration;
import jdk.tools.jlink.internal.PluginRepository;
import jdk.tools.jlink.internal.ImagePluginStack;
import jdk.tools.jlink.internal.PoolImpl;
import jdk.tools.jlink.Jlink;
import jdk.tools.jlink.Jlink.OrderedPlugin;
import jdk.tools.jlink.Jlink.PluginsConfiguration;
import jdk.tools.jlink.plugin.PluginOption;
import jdk.tools.jlink.plugin.Pool;
import jdk.tools.jlink.plugin.Pool.ModuleData;
import jdk.tools.jlink.plugin.TransformerPlugin;

public class LastSorterTest {

    public LastSorterTest() {
        for (int i = 1; i <= 6; i++) {
            PluginRepository.registerPlugin(new SorterPlugin("sorterplugin" + i));
        }
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
        List<OrderedPlugin> plugins = new ArrayList<>();
        plugins.add(createConfig("sorterplugin5", "/a", 0));
        plugins.add(createConfig("sorterplugin6", "/a", 1));
        PluginsConfiguration config = new Jlink.PluginsConfiguration(plugins,
                null, "sorterplugin5");

        ImagePluginStack stack = ImagePluginConfiguration.parseConfiguration(config);

        // check order
        PoolImpl res = fillOutResourcePool();

        try {
            stack.visitResources(res);
            throw new AssertionError("Exception expected: Order of resources is already frozen." +
                    "Plugin sorterplugin6 is badly located");
        } catch (Exception e) {
            // expected
        }
    }

    private PoolImpl fillOutResourcePool() throws Exception {
        PoolImpl res = new PoolImpl();
        res.add(Pool.newResource("/eee/bbb/res1.class", new byte[90]));
        res.add(Pool.newResource("/aaaa/bbb/res2.class", new byte[90]));
        res.add(Pool.newResource("/bbb/aa/res1.class", new byte[90]));
        res.add(Pool.newResource("/aaaa/bbb/res3.class", new byte[90]));
        res.add(Pool.newResource("/bbb/aa/res2.class", new byte[90]));
        res.add(Pool.newResource("/fff/bbb/res1.class", new byte[90]));
        res.add(Pool.newResource("/aaaa/bbb/res1.class", new byte[90]));
        res.add(Pool.newResource("/bbb/aa/res3.class", new byte[90]));
        res.add(Pool.newResource("/ccc/bbb/res1.class", new byte[90]));
        res.add(Pool.newResource("/ddd/bbb/res1.class", new byte[90]));
        return res;
    }

    private static OrderedPlugin createConfig(String name, String arg, int index) {
        Map<PluginOption, String> conf = new HashMap<>();
        conf.put(new PluginOption.Builder(name).build(), arg);
        return new OrderedPlugin(name, index, true, conf);
    }

    private void checkPositiveCase() throws Exception {
        List<OrderedPlugin> plugins = new ArrayList<>();
        plugins.add(createConfig("sorterplugin1", "/c", 0));
        plugins.add(createConfig("sorterplugin2", "/b", 1));
        plugins.add(createConfig("sorterplugin3", "/a", 2));

        PluginsConfiguration config = new Jlink.PluginsConfiguration(plugins,
                null, "sorterplugin3");

        ImagePluginStack stack = ImagePluginConfiguration.parseConfiguration(config);

        // check order
        PoolImpl res = fillOutResourcePool();

        stack.visitResources(res);
    }

    private void checkUnknownPlugin() {
        List<OrderedPlugin> plugins = new ArrayList<>();
        plugins.add(createConfig("sorterplugin1", "/1", 0));
        plugins.add(createConfig("sorterplugin2", "/1", 1));
        plugins.add(createConfig("sorterplugin3", "/1", 2));
        plugins.add(createConfig("sorterplugin4", "/1", 3));

        PluginsConfiguration config = new Jlink.PluginsConfiguration(plugins,
                null, "sorterplugin5");
        try {
            ImagePluginConfiguration.parseConfiguration(config);
            throw new AssertionError("Unknown plugin should have failed.");
        } catch (Exception ex) {
            // XXX OK expected
        }
    }

    private void checkOrderAfterLastSorter() throws Exception {
        List<OrderedPlugin> plugins = new ArrayList<>();
        plugins.add(createConfig("sorterplugin1", "/c", 0));
        plugins.add(createConfig("sorterplugin2", "/b", 1));
        plugins.add(createConfig("sorterplugin3", "/a", 2));
        plugins.add(createConfig("sorterplugin4", "/d", 3));

        PluginsConfiguration config = new Jlink.PluginsConfiguration(plugins,
                null, "sorterplugin3");

        ImagePluginStack stack = ImagePluginConfiguration.parseConfiguration(config);

        // check order
        PoolImpl res = fillOutResourcePool();
        try {
            stack.visitResources(res);
            throw new AssertionError("Order was changed after the last sorter, but no exception occurred");
        } catch (Exception ex) {
            // XXX OK expected
        }
    }

    public static class SorterPlugin implements TransformerPlugin {

        private final String name;
        private String starts;

        private SorterPlugin(String name) {
            this.name = name;
        }

        @Override
        public void visit(Pool resources, Pool output) {
            List<ModuleData> paths = new ArrayList<>();
            for (ModuleData res : resources.getContent()) {
                if (res.getPath().startsWith(starts)) {
                    paths.add(0, res);
                } else {
                    paths.add(res);
                }
            }

            for (ModuleData r : paths) {
                output.add(r);
            }
        }

        @Override
        public String getName() {
            return name;
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

        @Override
        public void configure(Map<PluginOption, String> config) {
            String arguments = config.get(new PluginOption.Builder(name).build());
            this.starts = arguments;
        }
    }
}
