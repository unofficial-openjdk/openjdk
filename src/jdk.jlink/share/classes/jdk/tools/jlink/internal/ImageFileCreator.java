/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import jdk.internal.jimage.Archive;
import jdk.internal.jimage.Archive.Entry;
import jdk.internal.jimage.Archive.Entry.EntryType;
import jdk.internal.jimage.BasicImageWriter;
import jdk.internal.jimage.ImageModuleDataWriter;
import jdk.internal.jimage.ImageResourcesTree;
import jdk.tools.jlink.plugins.ImageFilePool.ImageFile;
import jdk.tools.jlink.plugins.ImageFilePool.ImageFile.ImageFileType;
import jdk.tools.jlink.plugins.ResourcePool;
import jdk.tools.jlink.plugins.StringTable;

/**
 * An image (native endian.)
 * <pre>{@code
 * {
 *   u4 magic;
 *   u2 major_version;
 *   u2 minor_version;
 *   u4 resource_count;
 *   u4 table_length;
 *   u4 location_attributes_size;
 *   u4 strings_size;
 *   u4 redirect[table_length];
 *   u4 offsets[table_length];
 *   u1 location_attributes[location_attributes_size];
 *   u1 strings[strings_size];
 *   u1 content[if !EOF];
 * }
 * }</pre>
 */
public final class ImageFileCreator {
    private final Map<String, List<Entry>> entriesForModule = new HashMap<>();
    private final ImagePluginStack plugins;
    private ImageFileCreator(ImagePluginStack plugins) {
        this.plugins = plugins;
    }

    public static ImageFileCreator create(Set<Archive> archives,
            ImagePluginStack plugins)
            throws IOException {
        return ImageFileCreator.create(archives, ByteOrder.nativeOrder(),
                plugins);
    }

    public static ImageFileCreator create(Set<Archive> archives,
            ByteOrder byteOrder)
            throws IOException {
        return ImageFileCreator.create(archives, byteOrder,
                new ImagePluginStack());
    }

    public static ImageFileCreator create(Set<Archive> archives,
                                   ByteOrder byteOrder,
                                   ImagePluginStack plugins)
        throws IOException
    {
        ImageFileCreator image = new ImageFileCreator(plugins);
        // get all entries
        Map<String, Set<String>> modulePackagesMap = new HashMap<>();
        image.readAllEntries(modulePackagesMap, archives);
        // write to modular image
        image.writeImage(modulePackagesMap, archives, byteOrder);
        return image;
    }

    private void readAllEntries(Map<String, Set<String>> modulePackagesMap,
                                  Set<Archive> archives) {
        archives.stream().forEach((archive) -> {
            Map<Boolean, List<Entry>> es = archive.entries()
                    .collect(Collectors.partitioningBy(n -> n.type()
                                    == EntryType.CLASS_OR_RESOURCE));
            String mn = archive.moduleName();
            List<Entry> all = new ArrayList<>();
            all.addAll(es.get(false));
            all.addAll(es.get(true));
            entriesForModule.put(mn, all);
            // Extract package names
            Set<String> pkgs = es.get(true).stream().map(Entry::name)
                    .filter(n -> isClassPackage(n))
                    .map(ImageFileCreator::toPackage)
                    .collect(Collectors.toSet());
            modulePackagesMap.put(mn, pkgs);
        });
    }

    public static boolean isClassPackage(String path) {
        return path.endsWith(".class") && !path.endsWith("module-info.class");
    }

    public static void recreateJimage(Path jimageFile,
            Set<Archive> archives,
            Map<String, Set<String>> modulePackages,
            ImagePluginStack pluginSupport)
            throws IOException {
        Map<String, List<Entry>> entriesForModule
                = archives.stream().collect(Collectors.toMap(
                                Archive::moduleName,
                                a -> a.entries().collect(Collectors.toList())));
        ByteOrder order = ByteOrder.nativeOrder();
        Pools pools = createPools(modulePackages, entriesForModule, order);
        try (OutputStream fos = Files.newOutputStream(jimageFile);
                BufferedOutputStream bos = new BufferedOutputStream(fos);
                DataOutputStream out = new DataOutputStream(bos)) {
            generateJImage(pools.resources, order, pluginSupport, out);
        }
        //Close all archives
        for(Archive a : archives) {
            a.close();
        }
    }

    private void writeImage(Map<String, Set<String>> modulePackagesMap,
            Set<Archive> archives,
            ByteOrder byteOrder)
            throws IOException {
        Pools pools = createPools(modulePackagesMap,
                entriesForModule, byteOrder);
        generateJImage(pools.resources,
                byteOrder, plugins, plugins.getJImageFileOutputStream());

        //Handle files.
        try {
            plugins.storeFiles(pools.files,
                    pools.resources.getModulePackages().keySet());
        } catch (Exception ex) {
            throw new IOException(ex);
        }
        //Close all archives
        for(Archive a : archives) {
            a.close();
        }
    }

    private static void generateJImage(ResourcePoolImpl resources,
            ByteOrder byteOrder,
            ImagePluginStack pluginSupport,
            DataOutputStream out
    ) throws IOException {
        BasicImageWriter writer = new BasicImageWriter(byteOrder);
        ResourcePool resultResources;
        try {
            resultResources = pluginSupport.visitResources(resources, new StringTable() {

                @Override
                public int addString(String str) {
                    return writer.addString(str);
                }

                @Override
                public String getString(int id) {
                    return writer.getString(id);
                }
            });
        } catch (Exception ex) {
            throw new IOException(ex);
        }
        Map<String, Set<String>> modulePackagesMap = resultResources.getModulePackages();

            Set<String> duplicates = new HashSet<>();
            ImageModuleDataWriter moduleData =
            ImageModuleDataWriter.buildModuleData(writer, modulePackagesMap);
            moduleData.addLocation(BasicImageWriter.BOOT_NAME, writer);
            long offset = moduleData.size();

            List<ResourcePool.Resource> content = new ArrayList<>();
            List<String> paths = new ArrayList<>();
                 // the order of traversing the resources and the order of
            // the module content being written must be the same
            for (ResourcePool.Resource res : resultResources.getResources()) {
                String path = res.getPath();
                content.add(res);
                long uncompressedSize = res.getLength();
                long compressedSize = 0;
                if (res instanceof ResourcePool.CompressedResource) {
                    ResourcePool.CompressedResource comp =
                            (ResourcePool.CompressedResource) res;
                    compressedSize = res.getLength();
                    uncompressedSize = comp.getUncompressedSize();
                }
                long onFileSize = res.getLength();

                if (duplicates.contains(path)) {
                    System.err.format("duplicate resource \"%s\", skipping%n",
                            path);
                     // TODO Need to hang bytes on resource and write
                    // from resource not zip.
                    // Skipping resource throws off writing from zip.
                    offset += onFileSize;
                    continue;
                }
                duplicates.add(path);
                writer.addLocation(path, offset, compressedSize, uncompressedSize);
                paths.add(path);
                offset += onFileSize;
            }

            ImageResourcesTree tree = new ImageResourcesTree(offset, writer, paths);

            // write header and indices
            byte[] bytes = writer.getBytes();
            out.write(bytes, 0, bytes.length);

            // write module meta data
            moduleData.writeTo(out);

            // write module content
            for(ResourcePool.Resource res : content) {
                byte[] buf = res.getByteArray();
                out.write(buf, 0, buf.length);
            }

            tree.addContent(out);

            out.close();
    }

    private static class Pools {
        private ResourcePoolImpl resources;
        private ImageFilePoolImpl files;
    }

    private static class EntryFile extends ImageFile {
        private final Entry entry;
        private EntryFile(Entry entry) {
            super(entry.archive().moduleName(),
                    entry.path(), entry.name(),
                    mapImageFileType(entry.type()));
            this.entry = entry;
        }

        @Override
        public long size() {
            return entry.size();
        }

        @Override
        public InputStream stream() throws IOException {
            return entry.stream();
        }

    }

    private static ImageFileType mapImageFileType(EntryType type) {
        switch(type) {
            case CONFIG: {
                return ImageFileType.CONFIG;
            }
            case NATIVE_CMD: {
                return ImageFileType.NATIVE_CMD;
            }
            case NATIVE_LIB: {
                return ImageFileType.NATIVE_LIB;
            }
        }
        return null;
    }

    private static Pools createPools(Map<String, Set<String>> modulePackagesMap,
            Map<String, List<Entry>> entriesForModule,
            ByteOrder byteOrder) throws IOException {
        ResourcePoolImpl resources = new ResourcePoolImpl(byteOrder);
        ImageFilePoolImpl files = new ImageFilePoolImpl();
        Set<String> mods = modulePackagesMap.keySet();
        for (String mn : mods) {
            for (Entry entry : entriesForModule.get(mn)) {
                String path = entry.name();
                if (entry.type() == EntryType.CLASS_OR_RESOURCE) {
                    if (!entry.path().endsWith(BasicImageWriter.BOOT_NAME)) {
                        try (InputStream stream = entry.stream()) {
                            byte[] bytes = readAllBytes(stream);
                            if (path.endsWith("module-info.class")) {
                                path = "/" + path;
                            } else {
                                path = "/" + mn + "/" + path;
                            }
                            try {
                                resources.addResource(new ResourcePool.Resource(path,
                                        ByteBuffer.wrap(bytes)));
                            } catch (Exception ex) {
                                throw new IOException(ex);
                            }
                        }
                    }
                } else {
                    try {
                        files.addFile(new EntryFile(entry));
                    } catch (Exception ex) {
                        throw new IOException(ex);
                    }
                }
            }
        }
        Pools pools = new Pools();
        pools.resources = resources;
        pools.files = files;
        return pools;
    }

    private static final int BUF_SIZE = 8192;

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[BUF_SIZE];
        while (true) {
            int n = is.read(buf);
            if (n < 0) {
                break;
            }
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    /**
     * Helper method that splits a Resource path onto 3 items: module, parent
     * and resource name.
     *
     * @param path
     * @return An array containing module, parent and name.
     */
    public static String[] splitPath(String path) {
        Objects.requireNonNull(path);
        String noRoot = path.substring(1);
        int pkgStart = noRoot.indexOf("/");
        String module = noRoot.substring(0, pkgStart);
        List<String> result = new ArrayList<>();
        result.add(module);
        String pkg = noRoot.substring(pkgStart + 1);
        String resName;
        int pkgEnd = pkg.lastIndexOf("/");
        if (pkgEnd == -1) { // No package.
            resName = pkg;
        } else {
            resName = pkg.substring(pkgEnd + 1);
        }

        pkg = toPackage(pkg, false);
        result.add(pkg);
        result.add(resName);

        String[] array = new String[result.size()];
        return result.toArray(array);
    }

    private static String toPackage(String name) {
        return toPackage(name, true);
    }

    private static String toPackage(String name, boolean log) {
        int index = name.lastIndexOf('/');
        if (index > 0) {
            return name.substring(0, index).replace('/', '.');
        } else {
            // ## unnamed package
            if (log) {
                System.err.format("Warning: %s in unnamed package%n", name);
            }
            return "";
        }
    }
}
