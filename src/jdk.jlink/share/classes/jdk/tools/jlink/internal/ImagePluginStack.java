/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package jdk.tools.jlink.internal;

import java.io.DataOutputStream;
import jdk.tools.jlink.plugins.Plugin;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jdk.tools.jlink.plugins.ImageBuilder;
import jdk.tools.jlink.plugins.ImageFilePlugin;
import jdk.tools.jlink.plugins.ResourcePlugin;
import jdk.tools.jlink.plugins.ResourcePool;
import jdk.tools.jlink.plugins.ResourcePool.Resource;
import jdk.tools.jlink.plugins.ResourcePrevisitor;
import jdk.tools.jlink.plugins.StringTable;

/**
 * Plugins Stack. Plugins entry point to apply transformations onto resources and files.
 */
public final class ImagePluginStack {

    private final class OrderedResourcePool extends ResourcePoolImpl {

        private final List<Resource> orderedList = new ArrayList<>();

        public OrderedResourcePool(ByteOrder order) {
            super(order);
        }

        /**
         * Add a resource.
         *
         * @param resource The Resource to add.
         * @throws java.lang.Exception If the pool is read only.
         */
        @Override
        public void addResource(Resource resource) throws Exception {
            super.addResource(resource);
            orderedList.add(resource);
        }

        List<Resource> getOrderedList() {
            return Collections.unmodifiableList(orderedList);
        }
    }

    private final class CheckOrderResourcePool extends ResourcePoolImpl {

        private final List<Resource> orderedList;
        private int currentIndex;

        public CheckOrderResourcePool(ByteOrder order, List<Resource> orderedList) {
            super(order);
            this.orderedList = orderedList;
        }

        /**
         * Add a resource.
         *
         * @param resource The Resource to add.
         * @throws java.lang.Exception If the pool is read only.
         */
        @Override
        public void addResource(Resource resource) throws Exception {
            Resource ordered = orderedList.get(currentIndex);
            if (!resource.equals(ordered)) {
                throw new Exception("Resource " + resource.getPath() + " not in the right order");
            }
            super.addResource(resource);
            currentIndex += 1;
        }
    }

    private final class PreVisitStrings implements StringTable {

        private int currentid = 0;
        private final Map<String, Integer> stringsUsage = new HashMap<>();

        private final Map<String, Integer> stringsMap = new HashMap<>();
        private final Map<Integer, String> reverseMap = new HashMap<>();

        @Override
        public int addString(String str) {
            Objects.requireNonNull(str);
            Integer count = stringsUsage.get(str);
            if (count == null) {
                count = 0;
            }
            count += 1;
            stringsUsage.put(str, count);
            Integer id = stringsMap.get(str);
            if (id == null) {
                id = currentid;
                stringsMap.put(str, id);
                currentid += 1;
                reverseMap.put(id, str);
            }

            return id;
        }

        private List<String> getSortedStrings() {
            Stream<java.util.Map.Entry<String, Integer>> stream
                    = stringsUsage.entrySet().stream();
            // Remove strings that have a single occurence
            List<String> result = stream.sorted(Comparator.comparing(e -> e.getValue(),
                    Comparator.reverseOrder())).filter((e) -> {
                        return e.getValue() > 1;
                    }).map(java.util.Map.Entry::getKey).
                    collect(Collectors.toList());
            return result;
        }

        @Override
        public String getString(int id) {
            return reverseMap.get(id);
        }
    }

    private final Plugin lastSorter;
    private final List<ResourcePlugin> resourcePlugins = new ArrayList<>();
    private final List<ImageFilePlugin> filePlugins = new ArrayList<>();
    private final List<ResourcePrevisitor> resourcePrevisitors = new ArrayList<>();

    private final ImageBuilder imageBuilder;

    public ImagePluginStack() {
        this(null, Collections.emptyList(), null, Collections.emptyList());
    }

    ImagePluginStack(ImageBuilder imageBuilder,
            List<ResourcePlugin> resourcePlugins,
            Plugin lastSorter,
            List<ImageFilePlugin> filePlugins) {
        Objects.requireNonNull(resourcePlugins);
        Objects.requireNonNull(filePlugins);
        this.lastSorter = lastSorter;
        for (ResourcePlugin p : resourcePlugins) {
            Objects.requireNonNull(p);
            if (p instanceof ResourcePrevisitor) {
                resourcePrevisitors.add((ResourcePrevisitor) p);
            }
            this.resourcePlugins.add(p);
        }
        for (ImageFilePlugin p : filePlugins) {
            Objects.requireNonNull(p);
            this.filePlugins.add(p);
        }
        this.imageBuilder = imageBuilder;
    }

    public DataOutputStream getJImageFileOutputStream() throws IOException {
        return imageBuilder.getJImageOutputStream();
    }

    /**
     * Resource Plugins stack entry point. All resources are going through all the
     * plugins.
     *
     * @param resources The set of resources to visit
     * @param strings Allows to add strings to the JImage
     * @return The result of the visit.
     * @throws IOException
     */
    public ResourcePool visitResources(ResourcePoolImpl resources, StringTable strings)
            throws Exception {
        Objects.requireNonNull(resources);
        Objects.requireNonNull(strings);
        resources.setReadOnly();
        if (resources.isEmpty()) {
            return new ResourcePoolImpl(resources.getByteOrder());
        }
        PreVisitStrings previsit = new PreVisitStrings();
        for (ResourcePrevisitor p : resourcePrevisitors) {
            p.previsit(resources, previsit);
        }

        // Store the strings resulting from the previsit.
        List<String> sorted = previsit.getSortedStrings();
        for (String s : sorted) {
            strings.addString(s);
        }

        ResourcePoolImpl current = resources;
        List<Resource> frozenOrder = null;
        for (ResourcePlugin p : resourcePlugins) {
            current.setReadOnly();
            ResourcePoolImpl output = null;
            if (p == lastSorter) {
                if (frozenOrder != null) {
                    throw new Exception("Oder of resources is already frozen. Plugin "
                            + p.getName() + " is badly located");
                }
                // Create a special Resource pool to compute the indexes.
                output = new OrderedResourcePool(current.getByteOrder());
            } else {
                // If we have an order, inject it
                if (frozenOrder != null) {
                    output = new CheckOrderResourcePool(current.getByteOrder(),
                            frozenOrder);
                } else {
                    output = new ResourcePoolImpl(current.getByteOrder());
                }
            }
            p.visit(current, output, strings);
            if (output.getResources().isEmpty()) {
                throw new Exception("Invalid resource pool for plugin " + p);
            }
            if (output instanceof OrderedResourcePool) {
                frozenOrder = ((OrderedResourcePool) output).getOrderedList();
            }

            current = output;
        }
        return current;
    }

    /**
     * ImageFile Plugins stack entry point. All files are going through all the
     * plugins.
     *
     * @param modules
     * @throws IOException
     */
    public void storeFiles(ImageFilePoolImpl files, Set<String> modules)
            throws Exception {
        Objects.requireNonNull(files);
        ImageFilePoolImpl current = files;
        for (ImageFilePlugin p : filePlugins) {
            current.setReadOnly();
            ImageFilePoolImpl output = new ImageFilePoolImpl();
            p.visit(current, output);
             if (output.getFiles().isEmpty()) {
                throw new Exception("Invalid files pool for plugin " + p);
            }
            current = output;
        }
        imageBuilder.storeFiles(current, modules);
    }
}
