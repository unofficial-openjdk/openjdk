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
 * @summary Test previsitor
 * @author Andrei Eremeev
 * @modules jdk.jlink/jdk.tools.jlink.internal
 * @run main/othervm PrevisitorTest
 */

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import jdk.tools.jlink.internal.ImagePluginConfiguration;
import jdk.tools.jlink.internal.ImagePluginProviderRepository;
import jdk.tools.jlink.internal.ImagePluginStack;
import jdk.tools.jlink.internal.ResourcePoolImpl;
import jdk.tools.jlink.plugins.CmdPluginProvider;
import jdk.tools.jlink.plugins.CmdResourcePluginProvider;
import jdk.tools.jlink.plugins.Plugin;
import jdk.tools.jlink.plugins.PluginProvider;
import jdk.tools.jlink.plugins.ResourcePlugin;
import jdk.tools.jlink.plugins.ResourcePool;
import jdk.tools.jlink.plugins.ResourcePrevisitor;
import jdk.tools.jlink.plugins.StringTable;

public class PrevisitorTest {

    public static void main(String[] args) throws Exception {
        new PrevisitorTest().test();
    }

    public void test() throws Exception {
        CustomProvider provider = new CustomProvider("plugin");
        ImagePluginProviderRepository.registerPluginProvider(provider);
        Properties properties = new Properties();
        properties.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY, "plugin");
        ImagePluginStack stack = ImagePluginConfiguration.parseConfiguration(properties);
        ResourcePoolImpl inResources = new ResourcePoolImpl(ByteOrder.nativeOrder());
        inResources.addResource(new ResourcePool.Resource("/aaa/bbb/res1.class", ByteBuffer.allocate(90)));
        inResources.addResource(new ResourcePool.Resource("/aaa/bbb/res2.class", ByteBuffer.allocate(90)));
        inResources.addResource(new ResourcePool.Resource("/aaa/bbb/res3.class", ByteBuffer.allocate(90)));
        inResources.addResource(new ResourcePool.Resource("/aaa/ddd/res1.class", ByteBuffer.allocate(90)));
        inResources.addResource(new ResourcePool.Resource("/aaa/res1.class", ByteBuffer.allocate(90)));
        CustomStringTable stringTable = new CustomStringTable();
        ResourcePool outResources = stack.visitResources(inResources, stringTable);
        Collection<String> input = inResources.getResources().stream()
                .map(Object::toString)
                .collect(Collectors.toList());
        Collection<String> output = outResources.getResources().stream()
                .map(Object::toString)
                .collect(Collectors.toList());
        if (!input.equals(output)) {
            throw new AssertionError("Input and output resources differ: input: " +
                    input + ", output: " + output);
        }
    }

    private static class CustomStringTable implements StringTable {
        private final List<String> strings = new ArrayList<>();

        @Override
        public int addString(String str) {
            strings.add(str);
            return strings.size() - 1;
        }

        @Override
        public String getString(int id) {
            return strings.get(id);
        }

        public int size() {
            return strings.size();
        }
    }

    private static class CustomPlugin implements ResourcePlugin, ResourcePrevisitor {

        private boolean isPrevisitCalled = false;

        @Override
        public void visit(ResourcePool inResources, ResourcePool outResources, StringTable strings) throws Exception {
            if (!isPrevisitCalled) {
                throw new AssertionError("Previsit was not called");
            }
            CustomStringTable table = (CustomStringTable) strings;
            if (table.size() == 0) {
                throw new AssertionError("Table is empty");
            }
            Map<String, Integer> count = new HashMap<>();
            for (int i = 0; i < table.size(); ++i) {
                String s = table.getString(i);
                if (inResources.getResource(s) != null) {
                    throw new AssertionError();
                }
                count.compute(s, (k, c) -> 1 + (c == null ? 0 : c));
            }
            count.forEach((k, v) -> {
                if (v != 1) {
                    throw new AssertionError("Expected one entry in the table, got: " + v + " for " + k);
                }
            });
            for (ResourcePool.Resource r : inResources.getResources()) {
                outResources.addResource(r);
            }
        }

        @Override
        public String getName() {
            return "custom-plugin";
        }

        @Override
        public void previsit(ResourcePool resources, StringTable strings) throws Exception {
            isPrevisitCalled = true;
            for (ResourcePool.Resource r : resources.getResources()) {
                String s = r.getPath();
                int lastIndexOf = s.lastIndexOf('/');
                if (lastIndexOf >= 0) {
                    strings.addString(s.substring(0, lastIndexOf));
                }
            }
        }
    }

    public static class CustomProvider extends CmdResourcePluginProvider {

        CustomProvider(String name) {
            super(name, "");
        }

        @Override
        public ResourcePlugin[] newPlugins(String[] arguments, Map<String, String> options)
                throws IOException {
            CustomPlugin customPlugin = new CustomPlugin();
            return new ResourcePlugin[]{customPlugin};
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
}
