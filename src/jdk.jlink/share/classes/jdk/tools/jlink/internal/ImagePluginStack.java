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

import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import jdk.tools.jlink.plugins.Plugin;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jdk.internal.jimage.decompressor.Decompressor;
import jdk.tools.jlink.plugins.ExecutableImage;
import jdk.tools.jlink.plugins.ImageBuilder;
import jdk.tools.jlink.plugins.TransformerPlugin;
import jdk.tools.jlink.plugins.PluginException;
import jdk.tools.jlink.plugins.Pool;
import jdk.tools.jlink.plugins.Pool.ModuleData;
import jdk.tools.jlink.plugins.PostProcessorPlugin;

/**
 * Plugins Stack. Plugins entry point to apply transformations onto resources and files.
 */
public final class ImagePluginStack {

    public interface ImageProvider {
        ExecutableImage retrieve(ImagePluginStack stack) throws IOException;

        public void storeLauncherArgs(ImagePluginStack stack, ExecutableImage image,
                List<String> args) throws IOException;
    }

    private static final class OrderedResourcePool extends PoolImpl {

        private final List<ModuleData> orderedList = new ArrayList<>();

        public OrderedResourcePool(ByteOrder order, StringTable table) {
            super(order, table);
        }

        /**
         * Add a resource.
         *
         * @param resource The Resource to add.
         * @throws java.lang.Exception If the pool is read only.
         */
        @Override
        public void add(ModuleData resource) {
            super.add(resource);
            orderedList.add(resource);
        }

        List<ModuleData> getOrderedList() {
            return Collections.unmodifiableList(orderedList);
        }
    }

    private final static class CheckOrderResourcePool extends PoolImpl {

        private final List<ModuleData> orderedList;
        private int currentIndex;

        public CheckOrderResourcePool(ByteOrder order, List<ModuleData> orderedList, StringTable table) {
            super(order, table);
            this.orderedList = orderedList;
        }

        /**
         * Add a resource.
         *
         * @param resource The Resource to add.
         */
        @Override
        public void add(ModuleData resource) {
            ModuleData ordered = orderedList.get(currentIndex);
            if (!resource.equals(ordered)) {
                throw new PluginException("Resource " + resource.getPath() + " not in the right order");
            }
            super.add(resource);
            currentIndex += 1;
        }
    }

    private static final class PreVisitStrings implements StringTable {

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
    private final List<TransformerPlugin> resourcePlugins = new ArrayList<>();
    private final List<TransformerPlugin> filePlugins = new ArrayList<>();
    private final List<PostProcessorPlugin> postProcessingPlugins = new ArrayList<>();
    private final List<ResourcePrevisitor> resourcePrevisitors = new ArrayList<>();

    private final ImageBuilder imageBuilder;

    private final String bom;

    public ImagePluginStack(String bom) {
        this(null, Collections.emptyList(), null, Collections.emptyList(),
                Collections.emptyList(), null);
    }

    public ImagePluginStack(ImageBuilder imageBuilder,
            List<TransformerPlugin> resourcePlugins,
            Plugin lastSorter,
            List<TransformerPlugin> filePlugins,
            List<PostProcessorPlugin> postprocessingPlugins,
            String bom) {
        Objects.requireNonNull(resourcePlugins);
        Objects.requireNonNull(filePlugins);
        this.lastSorter = lastSorter;
        for (TransformerPlugin p : resourcePlugins) {
            Objects.requireNonNull(p);
            if (p instanceof ResourcePrevisitor) {
                resourcePrevisitors.add((ResourcePrevisitor) p);
            }
            this.resourcePlugins.add(p);
        }
        for (TransformerPlugin p : filePlugins) {
            Objects.requireNonNull(p);
            this.filePlugins.add(p);
        }
        for (PostProcessorPlugin p : postprocessingPlugins) {
            Objects.requireNonNull(p);
            this.postProcessingPlugins.add(p);
        }
        this.imageBuilder = imageBuilder;
        this.bom = bom;
    }

    public void operate(ImageProvider provider) throws Exception {
        ExecutableImage img = provider.retrieve(this);
        List<String> arguments;
        // Could be autocloseable but not right behavior
        // with InterruptedException
        ProcessingManagerImpl manager = new ProcessingManagerImpl(img);
        arguments = new ArrayList<>();
        try {
            for (PostProcessorPlugin plugin : postProcessingPlugins) {
                List<String> lst = plugin.process(manager);
                if (lst != null) {
                    arguments.addAll(lst);
                }
            }
        } finally {
            manager.close();
        }
        provider.storeLauncherArgs(this, img, arguments);
    }

    public DataOutputStream getJImageFileOutputStream() throws IOException {
        return imageBuilder.getJImageOutputStream();
    }

    public ImageBuilder getImageBuilder() {
        return imageBuilder;
    }

    /**
     * Resource Plugins stack entry point. All resources are going through all the
     * plugins.
     *
     * @param resources The set of resources to visit
     * @return The result of the visit.
     * @throws IOException
     */
    public PoolImpl visitResources(PoolImpl resources)
            throws Exception {
        Objects.requireNonNull(resources);
        resources.setReadOnly();
        if (resources.isEmpty()) {
            return new PoolImpl(resources.getByteOrder(),
                    resources.getStringTable());
        }
        PreVisitStrings previsit = new PreVisitStrings();
        for (ResourcePrevisitor p : resourcePrevisitors) {
            p.previsit(resources, previsit);
        }

        // Store the strings resulting from the previsit.
        List<String> sorted = previsit.getSortedStrings();
        for (String s : sorted) {
            resources.getStringTable().addString(s);
        }

        PoolImpl current = resources;
        List<Pool.ModuleData> frozenOrder = null;
        for (TransformerPlugin p : resourcePlugins) {
            current.setReadOnly();
            PoolImpl output = null;
            if (p == lastSorter) {
                if (frozenOrder != null) {
                    throw new Exception("Oder of resources is already frozen. Plugin "
                            + p.getName() + " is badly located");
                }
                // Create a special Resource pool to compute the indexes.
                output = new OrderedResourcePool(current.getByteOrder(),
                        resources.getStringTable());
            } else {
                // If we have an order, inject it
                if (frozenOrder != null) {
                    output = new CheckOrderResourcePool(current.getByteOrder(),
                            frozenOrder, resources.getStringTable());
                } else {
                    output = new PoolImpl(current.getByteOrder(),
                            resources.getStringTable());
                }
            }
            p.visit(current, output);
            if (output.isEmpty()) {
                throw new Exception("Invalid resource pool for plugin " + p);
            }
            if (output instanceof OrderedResourcePool) {
                frozenOrder = ((OrderedResourcePool) output).getOrderedList();
            }

            current = output;
        }
        current.setReadOnly();
        return current;
    }

    private class LastPool extends Pool {

        private final PoolImpl pool;
        Decompressor decompressor = new Decompressor();
        Collection<ModuleData> content;
        LastPool(PoolImpl pool) {
            this.pool = pool;
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public void add(ModuleData resource) {
            throw new PluginException("pool is readonly");
        }

        /**
         * Get all resources contained in this pool instance.
         *
         * @return The collection of resources;
         */
        @Override
        public Collection<ModuleData> getContent() {
            if (content == null) {
                content = new ArrayList<>();
                for (ModuleData md : pool.getContent()) {
                    content.add(getUncompressed(md));
                }
            }
            return content;
        }

        /**
         * Get the resource for the passed path.
         *
         * @param path A resource path
         * @return A Resource instance or null if the resource is not found
         */
        @Override
        public ModuleData get(String path) {
            Objects.requireNonNull(path);
            Pool.ModuleData res = pool.get(path);
            return getUncompressed(res);
        }

        @Override
        public boolean contains(ModuleData res) {
            return pool.contains(res);
        }

        @Override
        public boolean isEmpty() {
            return pool.isEmpty();
        }

        @Override
        public void visit(Visitor visitor, Pool output) {
            pool.visit(visitor, output);
        }

        @Override
        public ByteOrder getByteOrder() {
            return pool.getByteOrder();
        }

        private ModuleData getUncompressed(ModuleData res) {
            if (res != null) {
                if (res instanceof PoolImpl.CompressedModuleData) {
                    try {
                        byte[] bytes = decompressor.decompressResource(getByteOrder(),
                                (int offset) -> pool.getStringTable().getString(offset),
                                res.getBytes());
                        res = Pool.newResource(res.getPath(),
                                new ByteArrayInputStream(bytes),
                                bytes.length);
                    } catch (IOException ex) {
                        throw new PluginException(ex);
                    }
                }
            }
            return res;
        }

        @Override
        public Map<String, Set<String>> getModulePackages() {
            return pool.getModulePackages();
        }

        @Override
        public void addTransformedResource(ModuleData original, InputStream transformed, long length) {
            pool.addTransformedResource(original, transformed, length);
        }
    }

    /**
     * ImageFile Plugins stack entry point. All files are going through all the
     * plugins.
     *
     * @param files
     * @param resources
     * @param writer
     * @throws Exception
     */
    public void storeFiles(PoolImpl files, PoolImpl resources,
            BasicImageWriter writer)
            throws Exception {
        Objects.requireNonNull(files);
        PoolImpl current = files;
        for (TransformerPlugin p : filePlugins) {
            current.setReadOnly();
            PoolImpl output = new PoolImpl();
            p.visit(current, output);
            if (output.isEmpty()) {
                throw new Exception("Invalid files pool for plugin " + p);
            }
            current = output;
        }
        current.setReadOnly();
        // Build the diff between input and output
        List<ModuleData> removed = new ArrayList<>();
        for (ModuleData f : files.getContent()) {
            if (!current.contains(f)) {
                removed.add(f);
            }
        }
        imageBuilder.storeFiles(current, removed, bom, new LastPool(resources));
    }

    public ExecutableImage getExecutableImage() throws IOException {
        return imageBuilder.getExecutableImage();
    }
}
