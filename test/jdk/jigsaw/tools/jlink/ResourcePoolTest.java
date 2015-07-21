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
 * @summary Test ResourcePool class
 * @author Jean-Francois Denise
 * @modules jdk.jlink/jdk.tools.jlink.internal
 * @run build ResourcePoolTest
 * @run main ResourcePoolTest
 */

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.tools.jlink.internal.ResourcePoolImpl;
import jdk.tools.jlink.plugins.ResourcePool.Resource;
import jdk.tools.jlink.plugins.ResourcePool;
import jdk.tools.jlink.plugins.ResourcePool.CompressedResource;
import jdk.tools.jlink.plugins.ResourcePool.Visitor;
import jdk.tools.jlink.plugins.StringTable;

public class ResourcePoolTest {

    public static void main(String[] args) throws Exception {
        new ResourcePoolTest().test();
    }

    public void test() throws Exception {
        checkResourceAdding();
        checkResourceVisitor();
        checkResourcesAfterCompression();
    }

    private static final String SUFFIX = "END";

    private void checkResourceVisitor() throws Exception {
        ResourcePool input = new ResourcePoolImpl(ByteOrder.nativeOrder());
        for (int i = 0; i < 1000; ++i) {
            String resourceName = "/module" + (i / 10) + "/java/package" + i;
            input.addResource(new Resource(resourceName, ByteBuffer.wrap(resourceName.getBytes())));
        }
        ResourcePool output = new ResourcePoolImpl(input.getByteOrder());
        ResourceVisitor visitor = new ResourceVisitor();
        input.visit(visitor, output, new StringTable() {
            @Override
            public int addString(String str) {
                return 0;
            }

            @Override
            public String getString(int id) {
                return null;
            }
        });
        if (visitor.getAmountBefore() == 0) {
            throw new AssertionError("Resources not found");
        }
        if (visitor.getAmountBefore() != input.getResources().size()) {
            throw new AssertionError("Number of visited resources. Expected: " +
                    visitor.getAmountBefore() + ", got: " + input.getResources().size());
        }
        if (visitor.getAmountAfter() != output.getResources().size()) {
            throw new AssertionError("Number of added resources. Expected: " +
                    visitor.getAmountAfter() + ", got: " + output.getResources().size());
        }
        for (Resource outResource : output.getResources()) {
            String path = outResource.getPath().replaceAll(SUFFIX + "$", "");
            Resource inResource = input.getResource(path);
            if (inResource == null) {
                throw new AssertionError("Unknown resource: " + path);
            }
        }
    }

    private static class ResourceVisitor implements Visitor {

        private int amountBefore;
        private int amountAfter;

        @Override
        public Resource visit(Resource resource, ByteOrder order, StringTable strings) throws Exception {
            int index = ++amountBefore % 3;
            switch (index) {
                case 0:
                    ++amountAfter;
                    return new Resource(resource.getPath() + SUFFIX, resource.getContent());
                case 1:
                    ++amountAfter;
                    return new Resource(resource.getPath(), resource.getContent());
            }
            return null;
        }

        public int getAmountAfter() {
            return amountAfter;
        }

        public int getAmountBefore() {
            return amountBefore;
        }
    }

    private void checkResourceAdding() {
        List<String> samples = new ArrayList<>();
        samples.add("java.base");
        samples.add("java/lang/Object");
        samples.add("java.base");
        samples.add("java/lang/String");
        samples.add("java.management");
        samples.add("javax/management/ObjectName");
        test(samples, (resources, path) -> {
            try {
                resources.addResource(new Resource(path, ByteBuffer.allocate(0)));
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        test(samples, (resources, path) -> {
            try {
                resources.addTransformedResource(new Resource(path,
                        ByteBuffer.allocate(0)), ByteBuffer.allocate(56));
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        test(samples, (resources, path) -> {
            try {
                resources.addResource(CompressedResource.
                        newCompressedResource(new Resource(path, ByteBuffer.allocate(0)),
                                ByteBuffer.allocate(99), "bitcruncher", null, new StringTable() {
                                    @Override
                                    public int addString(String str) {
                                        return -1;
                                    }

                                    @Override
                                    public String getString(int id) {
                                        throw new UnsupportedOperationException("Not supported yet.");
                                    }
                                }, ByteOrder.nativeOrder()));
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    private void test(List<String> samples, ResourceAdder adder) {
        if (samples.isEmpty()) {
            throw new AssertionError("No sample to test");
        }
        ResourcePool resources = new ResourcePoolImpl(ByteOrder.nativeOrder());
        for (int i = 0; i < samples.size(); i++) {
            String module = samples.get(i);
            i++;
            String clazz = samples.get(i);
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
                throw new AssertionError("Resource not found " + path);
            }
            Resource res2 = resources.getResource(clazz);
            if (res2 != null) {
                throw new AssertionError("Resource found " + clazz);
            }
        }
        if (resources.getResources().size() != samples.size() / 2) {
            throw new AssertionError("Invalid number of resources");
        }
    }

    private void checkResourcesAfterCompression() throws Exception {
        ResourcePool resources1 = new ResourcePoolImpl(ByteOrder.nativeOrder());
        ResourcePool.Resource res1 = new ResourcePool.Resource("/module1/toto1", ByteBuffer.allocate(0));
        ResourcePool.Resource res2 = new ResourcePool.Resource("/module2/toto1", ByteBuffer.allocate(0));
        resources1.addResource(res1);
        resources1.addResource(res2);

        checkResources(resources1, res1, res2);
        ResourcePool resources2 = new ResourcePoolImpl(resources1.getByteOrder());
        resources2.addTransformedResource(res2, ByteBuffer.allocate(7));
        resources2.addResource(ResourcePool.CompressedResource.newCompressedResource(res1,
                ByteBuffer.allocate(7), "zip", null, new StringTable() {
                    @Override
                    public int addString(String str) {
                        return -1;
                    }

                    @Override
                    public String getString(int id) {
                        throw new UnsupportedOperationException("Not supported yet.");
                    }
                }, ByteOrder.nativeOrder()));
        checkResources(resources2, res1, res2);
    }

    private void checkResources(ResourcePool resources, ResourcePool.Resource... expected) {
        Map<String, Set<String>> modules = resources.getModulePackages();
        for (ResourcePool.Resource res : expected) {
            if (!resources.contains(res)) {
                throw new AssertionError("Resource not found: " + res);
            }

            if (resources.getResource(res.getPath()) == null) {
                throw new AssertionError("Resource not found: " + res);
            }

            if (!modules.containsKey(res.getModule())) {
                throw new AssertionError("Module not found: " + res.getModule());
            }

            if (!resources.getResources().contains(res)) {
                throw new AssertionError("Resources not found: " + res);
            }

            try {
                resources.addResource(res);
                throw new AssertionError(res + " already present, but an exception is not thrown");
            } catch (Exception ex) {
                // Expected
            }
        }

        if (resources.isReadOnly()) {
            throw new AssertionError("ReadOnly resources");
        }

        ((ResourcePoolImpl) resources).setReadOnly();
        try {
            resources.addResource(new ResourcePool.Resource("module2/toto1", ByteBuffer.allocate(0)));
            throw new AssertionError("Pool is read-only, but an exception is not thrown");
        } catch (Exception ex) {
            // Expected
        }
    }

    interface ResourceAdder {
        void add(ResourcePool resources, String path);
    }
}
