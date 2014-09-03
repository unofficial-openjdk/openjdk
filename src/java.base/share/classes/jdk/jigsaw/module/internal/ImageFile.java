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
package jdk.jigsaw.module.internal;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import jdk.jigsaw.module.internal.ImageModules.Loader;

/**
 * An image (native endian.)
 * <pre>{@code
 * {
 *   u4 magic;
 *   u2 major_version;
 *   u2 minor_version;
 *   u4 location_count;
 *   u4 location_attributes_size;
 *   u4 strings_size;
 *   u4 packages_size;
 *   u4 redirect[location_count];
 *   u4 offsets[location_count];
 *   u1 location_attributes[location_attributes_size];
 *   u1 strings[strings_size];
 *   u1 content[if !EOF];
 * }
 * }</pre>
 */
public final class ImageFile {
    private static final String JAVA_BASE = "java.base";
    private static final String JMOD_EXT = ".jmod";
    private static final String IMAGE_EXT = ".jimage";
    private static final String JAR_EXT = ".jar";
    private final Path root;
    private final Path mdir;
    private final Map<String, List<Resource>> resourcesForModule = new HashMap<>();

    private ImageFile(Path path) {
        this.root = path;
        this.mdir = root.resolve(Paths.get("lib", "modules"));
    }

    public static ImageFile open(Path path) throws IOException {
        ImageFile lib = new ImageFile(path);
        return lib.open();
    }

    private ImageFile open() throws IOException {
        Path path = mdir.resolve("bootmodules" + IMAGE_EXT);

        ImageReader reader = new ImageReader(path.toString());
        ImageHeader header = reader.getHeader();

        if (header.getMagic() != ImageHeader.MAGIC) {
            if (header.getMagic() == ImageHeader.BADMAGIC) {
                throw new IOException(path + ": Image may be not be native endian");
            } else {
                throw new IOException(path + ": Invalid magic number");
            }
        }

        if (header.getMajorVersion() > ImageHeader.MAJOR_VERSION ||
            (header.getMajorVersion() == ImageHeader.MAJOR_VERSION &&
             header.getMinorVersion() > ImageHeader.MINOR_VERSION)) {
            throw new IOException("invalid version number");
        }

        return this;
    }

    public static ImageFile create(Path output,
                                   Set<Path> jmods,
                                   ImageModules modules)
            throws IOException
    {
        ImageFile lib = new ImageFile(output);
        // get all resources
        lib.readModuleEntries(modules, jmods);
        // write to modular image
        lib.writeImage(modules, jmods);
        return lib;
    }

    private void writeImage(ImageModules modules, Set<Path> jmods) throws IOException {
        // name to Jmod file
        Map<String, Path> nameToJmod =
            jmods.stream()
                 .collect(Collectors.toMap(p -> moduleName(p), Function.identity()));

        Files.createDirectories(mdir);
        for (Loader l : Loader.values()) {
            Set<String> mods = modules.getModules(l);
            if (mods.isEmpty()) {
                continue;
            }

            try (OutputStream fos = Files.newOutputStream(mdir.resolve(l.getName() + IMAGE_EXT));
                    BufferedOutputStream bos = new BufferedOutputStream(fos);
                    DataOutputStream out = new DataOutputStream(bos)) {
                // store index in addition of the class loader map for boot loader
                ImageWriter writer = new ImageWriter();
                Set<String> duplicates = new HashSet<>();

                // the order of traversing the resources and the order of
                // writing the module content must be the same
                long offset = 0;
                for (String mn : mods) {
                    for (Resource res : resourcesForModule.get(mn)) {
                        String fn = res.name();
                        if (duplicates.contains(fn)) {
                            System.err.format("duplicate resource \"%s\", skipping%n", fn);
                            continue;
                        }
                        duplicates.add(fn);
                        writer.addLocation(fn, offset, res.csize(), res.size());
                        offset += res.csize() != 0 ? res.csize() : res.size();
                    }
                }

                byte[] bytes = writer.getBytes();
                out.write(bytes, 0, bytes.length);

                // write module content
                for (String mn : mods) {
                    writeModule(nameToJmod.get(mn), out);
                }
            }
        }
    }

    private void readModuleEntries(ImageModules modules, Set<Path> jmods) throws IOException {
        for (Path jmod : jmods) {
            String filename = jmod.getFileName().toString();
            if (filename.endsWith(JMOD_EXT)) {
                String mn = filename.substring(0, filename.indexOf(JMOD_EXT));
                List<Resource> res = getResources(jmod);
                resourcesForModule.put(mn, res);

                Set<String> pkgs = res.stream().map(Resource::name)
                        .filter(n -> n.endsWith(".class"))
                        .map(this::toPackage)
                        .distinct()
                        .collect(Collectors.toSet());
                modules.setPackages(mn, pkgs);
            } else if (filename.endsWith(JAR_EXT)) {
                throw new UnsupportedOperationException(jmod.toString());
            }
        }
    }

    private Resource toResource(ZipEntry ze) {
        String name = ze.getName();
        // trim the "classes/" path
        String fn = name.substring(name.indexOf('/') + 1);
        long entrySize = ze.getSize();
        return new Resource(fn, entrySize, 0 /* no compression support yet */);
    }

    /**
     * Gets all resources of the given jmod file
     */
    private List<Resource> getResources(Path jmod) throws IOException {
        try (ZipFile zf = new ZipFile(jmod.toFile())) {
            // ## filter _* files generated by the jdk module
            // ## jlink should provide options to specify files to be included
            // ## or excluded in creating a jmod file
            List<Resource> res = zf.stream()
                    .filter(ze -> !ze.isDirectory() &&
                                  ze.getName().startsWith("classes"))
                    .filter(ze -> !ze.getName().startsWith("classes/_") &&
                                  !ze.getName().equals("classes/module-info.class"))
                    .map(this::toResource)
                    .collect(Collectors.toList());
            return res;
        }
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

    private static String moduleName(Path jmod) {
        String fn = jmod.getFileName().toString();
        return fn.substring(0, fn.length() - JMOD_EXT.length());
    }

    private void writeModule(Path jmod, OutputStream out) throws IOException {
        try (ZipFile zf = new ZipFile(jmod.toFile())) {
            ModuleEntryWriter consumer = new ModuleEntryWriter(out, zf);
            zf.stream()
              .filter(ze -> !ze.isDirectory() && !ze.getName().startsWith("classes/_"))
              .forEach(consumer);
        }
    }

    private static final String MODULE_INFO = "module-info.class";

    /*
     * Process each ZipEntry and write to the appropriate location
     *
     */
    private class ModuleEntryWriter implements Consumer<ZipEntry> {
        private static final String MODULE_NAME = "module";
        private static final String CLASSES     = "classes";
        private static final String NATIVE_LIBS = "native";
        private static final String NATIVE_CMDS = "bin";
        private static final String CONFIG      = "conf";
        private static final String SERVICES    = "module/services";
        private final OutputStream out;
        private final ZipFile zipfile;
        ModuleEntryWriter(OutputStream out, ZipFile zf) {
            this.zipfile = zf;
            this.out = out;
        }

        @Override
        public void accept(ZipEntry ze) {
            try {
                if (ze.isDirectory()) {
                    return;
                }

                String name = ze.getName();
                String section = name.substring(0, name.indexOf('/'));
                String filename = name.substring(name.indexOf('/') + 1);
                try (InputStream in = zipfile.getInputStream(ze)) {
                    switch (section) {
                        case CLASSES:
                            if (!filename.equals(MODULE_INFO) && !filename.startsWith("_")) {
                                writeEntry(in);
                            }
                            break;
                        case NATIVE_LIBS:
                            ModuleEntryWriter.this.writeEntry(in, destFile(nativeDir(), filename));
                            break;
                        case NATIVE_CMDS:
                            Path path = destFile("bin", filename);
                            ModuleEntryWriter.this.writeEntry(in, path);
                            path.toFile().setExecutable(true);
                            break;
                        case CONFIG:
                            ModuleEntryWriter.this.writeEntry(in, destFile("lib", filename));
                            break;
                        case MODULE_NAME:
                            // skip
                            break;
                        case SERVICES:
                            throw new UnsupportedOperationException(name + " in " + zipfile.toString());
                        default:
                            throw new InternalError("unexpected entry: " + name + " " + zipfile.toString());
                    }
                }
            } catch (IOException x) {
                throw new UncheckedIOException(x);
            }
        }

        private Path destFile(String dir, String filename) {
            return root.resolve(dir).resolve(filename);
        }

        private void writeEntry(InputStream in, Path dstFile) throws IOException {
            if (Files.notExists(dstFile.getParent())) {
                Files.createDirectories(dstFile.getParent());
            }
            Files.copy(in, dstFile);
        }

        private void writeEntry(InputStream in) throws IOException {
            if (true) { // testing compression
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } else {
                int size = in.available();
                byte[] inBytes = new byte[size];
                in.read(inBytes);
                byte[] outBytes = Compression.compress(inBytes);
                out.write(outBytes, 0, outBytes.length);
            }
        }

        private String nativeDir() {
            if (System.getProperty("os.name").startsWith("Windows")) {
                return "bin";
            } else {
                return "lib";
            }
        }
    }

    /**
     * Resource can be a class or resource file
     */
    class Resource {
        private final String name;
        private final long size;
        private final long csize;

        Resource(String name, long size, long csize) {
            this.name = name;
            this.size = size;
            this.csize = csize;
        }

        /**
         * Returns the name of this entry.
         */
        public String name() {
            return name;
        }

        /**
         * Returns the number of uncompressed bytes for this entry.
         */
        public long size() {
            return size;
        }

        /**
         * Returns the number of compressed bytes for this entry; 0 if
         * uncompressed.
         */
        public long csize() {
            return csize;
        }

        @Override
        public String toString() {
            return String.format("%s uncompressed size %d compressed size %d", name, size, csize);
        }
    }

    static class Compression {
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
