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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import jdk.tools.jlink.internal.ResourcePoolImpl;
import jdk.tools.jlink.plugins.ResourcePool.Resource;
import jdk.tools.jlink.plugins.ResourcePool;
import jdk.tools.jlink.plugins.ResourcePool.CompressedResource;
import jdk.tools.jlink.plugins.StringTable;
/*
 * ResourcePool class unit testing.
 * @test
 * @summary Test ResourcePool class
 * @author Jean-Francois Denise
 * @modules jdk.jlink/jdk.tools.jlink.internal
 * @run build ResourcePoolTest
 * @run main ResourcePoolTest
 */

public class ResourcePoolTest {

    interface ResourceAdder {

        void add(ResourcePool resources, String path);
    }

    public static void main(String[] args) throws Exception {
        List<String> samples = new ArrayList<>();
        samples.add("java.base");
        samples.add("java/lang/Object");
        samples.add("java.base");
        samples.add("java/lang/String");
        samples.add("java.management");
        samples.add("javax/management/ObjectName");
        test(samples, (resources, path) -> {
            try {
                resources.addResource(new ResourcePool.Resource(path,
                        ByteBuffer.allocate(0)));
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        test(samples, (resources, path) -> {
            try {
                resources.addTransformedResource(new ResourcePool.Resource(path,
                        ByteBuffer.allocate(0)), ByteBuffer.allocate(56));
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        test(samples, (resources, path) -> {
            try {
                resources.addResource(CompressedResource.
                        newCompressedResource(new ResourcePool.Resource(path, ByteBuffer.allocate(0)),
                        ByteBuffer.allocate(99), "bitcruncher", null, new StringTable() {

            @Override
            public int addString(String str) {
                return -1;
            }

            @Override
            public String getString(int id) {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }
        }, ByteOrder.nativeOrder()));
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    private static void test(List<String> samples, ResourceAdder adder)
            throws Exception {
        if (samples.isEmpty()) {
            throw new Exception("No sample to test");
        }
        ResourcePool resources = new ResourcePoolImpl(ByteOrder.nativeOrder());
        Set<String> modules = new HashSet<>();
        for (int i = 0; i < samples.size(); i++) {
            String module = samples.get(i);
            i++;
            String clazz = samples.get(i);
            modules.add(module);
            String path = "/" + module + "/" + clazz + ".class";
            adder.add(resources, path);
        }
        for (int i = 0; i < samples.size(); i++) {
            String module = samples.get(i);
            i++;
            String clazz = samples.get(i);
            String path = "/" + module + "/" + clazz + ".class";
            Resource res = resources.getResource(path);
            if (res == null) {
                throw new Exception("Resource not found " + res);
            }
            Resource res2 = resources.getResource(clazz);
            if (res2 != null) {
                throw new Exception("Resource found " + res2);
            }
        }
        if (resources.getResources().size() != samples.size() / 2) {
            throw new Exception("Invalid number of resources");
        }
        if (resources.getResources().size() != resources.getResources().size()) {
            throw new Exception("Invalid number of resources");
        }
    }

}
