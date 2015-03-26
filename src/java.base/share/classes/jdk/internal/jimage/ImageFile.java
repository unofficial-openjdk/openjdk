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
package jdk.internal.jimage;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import jdk.internal.jimage.Archive.Entry;
import jdk.internal.jimage.Archive.Entry.EntryType;

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
public final class ImageFile {
    private static final String JAVA_BASE = "java.base";
    public static final String IMAGE_EXT = ".jimage";
    public static final String BOOT_NAME = "bootmodules";
    public static final String BOOT_IMAGE_NAME = BOOT_NAME + IMAGE_EXT;
    private static final String JAR_EXT = ".jar";
    private final Path root;
    private final Path mdir;
    private final Map<String, List<Entry>> entriesForModule = new HashMap<>();
    private final boolean compress;

    private ImageFile(Path path, boolean compress) {
        this.root = path;
        this.mdir = root.resolve(path.getFileSystem().getPath("lib", "modules"));
        this.compress = compress;
    }

    public static ImageFile create(Path output,
                                   Set<Archive> archives,
                                   boolean compress)
        throws IOException
    {
        return ImageFile.create(output, archives, ByteOrder.nativeOrder(), compress);
    }

    public static ImageFile create(Path output,
                                   Set<Archive> archives,
                                   ByteOrder byteOrder,
                                   boolean compress)
        throws IOException
    {
        ImageFile image = new ImageFile(output, compress);
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
            List<Entry> archiveResources = new ArrayList<>();
            archive.visitEntries(x-> archiveResources.add(x));
            String mn = archive.moduleName();
            entriesForModule.put(mn, archiveResources);
            // Extract package names
            List<Entry> classes = archiveResources.stream()
                    .filter(n -> n.type() == EntryType.CLASS_OR_RESOURCE)
                    .collect(Collectors.toList());
            Set<String> pkgs = classes.stream().map(Entry::name)
                    .filter(n -> n.endsWith(".class") && !n.endsWith("module-info.class"))
                    .map(this::toPackage)
                    .collect(Collectors.toSet());
            modulePackagesMap.put(mn, pkgs);
        });
    }

    private void writeImage(Map<String, Set<String>> modulePackagesMap,
                            Set<Archive> archives,
                            ByteOrder byteOrder)
        throws IOException
    {
        // name to Archive file
        Map<String, Archive> nameToArchive =
            archives.stream()
                  .collect(Collectors.toMap(Archive::moduleName, Function.identity()));

        Files.createDirectories(mdir);

         try (OutputStream fos = Files.newOutputStream(mdir.resolve(BOOT_IMAGE_NAME));
                 BufferedOutputStream bos = new BufferedOutputStream(fos);
                 DataOutputStream out = new DataOutputStream(bos)) {
            // store index in addition of the class loader map for boot loader
            BasicImageWriter writer = new BasicImageWriter(byteOrder);
            Set<String> duplicates = new HashSet<>();

            ImageModuleDataWriter moduleData =
                    ImageModuleDataWriter.buildModuleData(writer, modulePackagesMap);
            moduleData.addLocation(BOOT_NAME, writer);
            long offset = moduleData.size();

            List<byte[]> content = new ArrayList<>();
            ExternalFilesWriter filesWriter = new ExternalFilesWriter(root);
            List<String> paths = new ArrayList<>();
            Set<String> mods = modulePackagesMap.keySet();
            // the order of traversing the resources and the order of
            // the module content being written must be the same
            for (String mn : mods) {
                for (Entry res : entriesForModule.get(mn)) {
                    String fn = res.name();
                    String path;

                    if (fn.endsWith("module-info.class")) {
                        path = "/" + fn;
                    } else {
                        path = "/" + mn + "/" + fn;
                    }
                    if (res.type() == EntryType.CLASS_OR_RESOURCE) {
                        long uncompressedSize = res.size();
                        long compressedSize = 0;
                        try (InputStream stream = res.stream()) {
                            byte[] bytes = readAllBytes(stream);
                            if (bytes.length != uncompressedSize) {
                                throw new IOException("Size differ for " + path + "@" + res.archive().moduleName());
                            }
                            if (compress) {
                                bytes = ImageFile.Compressor.compress(bytes);
                                compressedSize = bytes.length;
                            }
                            content.add(bytes);
                        }
                        long onFileSize = compressedSize != 0 ? compressedSize : uncompressedSize;

                        if (duplicates.contains(path)) {
                            System.err.format("duplicate resource \"%s\", skipping%n", path);
                            // TODO Need to hang bytes on resource and write from resource not zip.
                            // Skipping resource throws off writing from zip.
                            offset += onFileSize;
                            continue;
                        }
                        duplicates.add(path);
                        writer.addLocation(path, offset, compressedSize, uncompressedSize);
                        paths.add(path);
                        offset += onFileSize;
                    } else {
                        filesWriter.accept(res);
                    }
                }
                // Done with this archive, close it.
                Archive archive = nameToArchive.get(mn);
                archive.close();
            }

            ImageResourcesTree tree = new ImageResourcesTree(offset, writer, paths);

            // write header and indices
            byte[] bytes = writer.getBytes();
            out.write(bytes, 0, bytes.length);

            // write module meta data
            moduleData.writeTo(out);

            // write module content
            for (byte[] buf : content) {
                out.write(buf, 0, buf.length);
            }

            tree.addContent(out);
        }
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

    private String toPackage(String name) {
        int index = name.lastIndexOf('/');
        if (index > 0) {
            return name.substring(0, index).replace('/', '.');
        } else {
            // ## unnamed package
            System.err.format("Warning: %s in unnamed package%n", name);
            return "";
        }
    }

    static class Compressor {
        public static byte[] compress(byte[] bytesIn) {
            Deflater deflater = new Deflater();
            deflater.setInput(bytesIn);
            ByteArrayOutputStream stream = new ByteArrayOutputStream(bytesIn.length);
            byte[] buffer = new byte[1024];

            deflater.finish();
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                stream.write(buffer, 0, count);
            }

            try {
                stream.close();
            } catch (IOException ex) {
                return bytesIn;
            }

            byte[] bytesOut = stream.toByteArray();
            deflater.end();

            return bytesOut;
        }

        public static byte[] decompress(byte[] bytesIn) {
            Inflater inflater = new Inflater();
            inflater.setInput(bytesIn);
            ByteArrayOutputStream stream = new ByteArrayOutputStream(bytesIn.length);
            byte[] buffer = new byte[1024];

            while (!inflater.finished()) {
                int count;

                try {
                    count = inflater.inflate(buffer);
                } catch (DataFormatException ex) {
                    return null;
                }

                stream.write(buffer, 0, count);
            }

            try {
                stream.close();
            } catch (IOException ex) {
                return null;
            }

            byte[] bytesOut = stream.toByteArray();
            inflater.end();

            return bytesOut;
        }
    }
}
