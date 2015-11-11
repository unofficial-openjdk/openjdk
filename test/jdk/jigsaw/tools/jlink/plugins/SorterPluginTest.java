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
 * @summary Test sorter plugin
 * @author Jean-Francois Denise
 * @modules jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jlink.internal.plugins
 * @run main SorterPluginTest
 */

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;

import jdk.tools.jlink.internal.ResourcePoolImpl;
import jdk.tools.jlink.internal.plugins.SortResourcesProvider;
import jdk.tools.jlink.plugins.ResourcePlugin;
import jdk.tools.jlink.plugins.ResourcePool;

public class SorterPluginTest {

    public static void main(String[] args) throws Exception {
        new SorterPluginTest().test();
    }

    public void test() throws Exception {
        ResourcePool.Resource[] array = {
                new ResourcePool.Resource("/module1/toto1", ByteBuffer.allocate(0)),
                new ResourcePool.Resource("/module2/toto1", ByteBuffer.allocate(0)),
                new ResourcePool.Resource("/module3/toto1", ByteBuffer.allocate(0)),
                new ResourcePool.Resource("/module3/toto1/module-info.class", ByteBuffer.allocate(0)),
                new ResourcePool.Resource("/zazou/toto1", ByteBuffer.allocate(0)),
                new ResourcePool.Resource("/module4/zazou", ByteBuffer.allocate(0)),
                new ResourcePool.Resource("/module5/toto1", ByteBuffer.allocate(0)),
                new ResourcePool.Resource("/module6/toto1/module-info.class", ByteBuffer.allocate(0))
        };

        ResourcePool.Resource[] sorted = {
                new ResourcePool.Resource("/zazou/toto1", ByteBuffer.allocate(0)),
                new ResourcePool.Resource("/module3/toto1/module-info.class", ByteBuffer.allocate(0)),
                new ResourcePool.Resource("/module6/toto1/module-info.class", ByteBuffer.allocate(0)),
                new ResourcePool.Resource("/module1/toto1", ByteBuffer.allocate(0)),
                new ResourcePool.Resource("/module2/toto1", ByteBuffer.allocate(0)),
                new ResourcePool.Resource("/module3/toto1", ByteBuffer.allocate(0)),
                new ResourcePool.Resource("/module4/zazou", ByteBuffer.allocate(0)),
                new ResourcePool.Resource("/module5/toto1", ByteBuffer.allocate(0)),
};

        ResourcePool.Resource[] sorted2 = {
            new ResourcePool.Resource("/module5/toto1", ByteBuffer.allocate(0)),
            new ResourcePool.Resource("/module6/toto1/module-info.class", ByteBuffer.allocate(0)),
            new ResourcePool.Resource("/module4/zazou", ByteBuffer.allocate(0)),
            new ResourcePool.Resource("/module3/toto1", ByteBuffer.allocate(0)),
            new ResourcePool.Resource("/module3/toto1/module-info.class", ByteBuffer.allocate(0)),
            new ResourcePool.Resource("/module1/toto1", ByteBuffer.allocate(0)),
            new ResourcePool.Resource("/module2/toto1", ByteBuffer.allocate(0)),
            new ResourcePool.Resource("/zazou/toto1", ByteBuffer.allocate(0)),};

        ResourcePool resources = new ResourcePoolImpl(ByteOrder.nativeOrder());
        for (ResourcePool.Resource r : array) {
            resources.addResource(r);
        }

        {
            ResourcePool out = new ResourcePoolImpl(ByteOrder.nativeOrder());
            String[] arguments = {"/zazou/*", "*/module-info.class"};
            ResourcePlugin p = new SortResourcesProvider().newPlugins(arguments, null)[0];
            p.visit(resources, out, null);
            check(out.getResources(), sorted);
        }

        {
            // Order of resources in the file, then un-ordered resources.
            File order = new File("resources.order");
            order.createNewFile();
            StringBuilder builder = new StringBuilder();
            // 5 first resources come from file
            for (int i = 0; i < 5; i++) {
                builder.append(sorted2[i].getPath()).append("\n");
            }
            Files.write(order.toPath(), builder.toString().getBytes());

            ResourcePool out = new ResourcePoolImpl(ByteOrder.nativeOrder());
            String[] arguments = {order.getAbsolutePath()};
            ResourcePlugin p = new SortResourcesProvider().newPlugins(arguments, null)[0];
            p.visit(resources, out, null);
            check(out.getResources(), sorted2);

        }
    }

    private void check(Collection<ResourcePool.Resource> outResources,
            ResourcePool.Resource[] sorted) {
        if (outResources.size() != sorted.length) {
            throw new AssertionError("Wrong number of resources:\n"
                    + "expected: " + Arrays.toString(sorted) + ",\n"
                    + "     got: " + outResources);
        }
        int i = 0;
        for (ResourcePool.Resource r : outResources) {
            System.err.println("Resource: " + r);
            if (!sorted[i].getPath().equals(r.getPath())) {
                throw new AssertionError("Resource not properly sorted, difference at: " + i + "\n"
                        + "expected: " + Arrays.toString(sorted) + ",\n"
                        + "     got: " + outResources);
            }
            i++;
        }
    }
}
