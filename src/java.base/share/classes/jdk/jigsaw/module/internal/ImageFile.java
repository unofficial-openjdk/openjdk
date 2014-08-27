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
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
import jdk.jigsaw.module.ModuleDescriptor;
import static jdk.jigsaw.module.internal.ImageFile.Loader.*;

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
 *   u4 redirect[location_count];
 *   u4 offsets[location_count];
 *   u1 location_attributes[location_attributes_size];
 *   u1 strings[strings_size];
 *   u1 content[if !EOF];
 * }
 * }</pre>
 */
public final class ImageFile {
    private static final String MODULES_DIR = "lib/modules/";
    private static final String JAVA_BASE = "java.base";
    private static final String MODULE_EXT = ".jmod";
    private static final String IMAGE_EXT = ".jimage";
    private static final String JAR_EXT = ".jar";
    private final Path root;
    private final Path mdir;
    private List<ModuleCatalog> catalogs;
    private SeekableByteChannel bch;
    private long entryStartPosition;
    private IndexBuilder builder;
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
                                       Set<ModuleDescriptor> bootModules,
                                       Set<ModuleDescriptor> extModules,
                                       Set<ModuleDescriptor> modules)
            throws IOException
    {
        ImageFile lib = new ImageFile(output);
        // name to Jmod file
        Map<String, Path> nameToJmod = jmods.stream()
                .collect(Collectors.toMap(p -> moduleName(p), Function.identity()));
        // build index
        lib.buildIndex(bootModules, extModules, modules, nameToJmod);
        // write to modular image
        lib.writeIndexedImage(nameToJmod);
        return lib;
    }

    private void buildIndex(Set<ModuleDescriptor> bootModules,
                            Set<ModuleDescriptor> extModules,
                            Set<ModuleDescriptor> modules,
                            Map<String, Path> nameToJmod)
            throws IOException
    {
        builder = new IndexBuilder(bootModules, extModules, modules);

        // build locations
        for (ModuleDescriptor md : builder.modules()) {
            Path jmod = nameToJmod.get(md.name());
            String filename = jmod.getFileName().toString();
            if (filename.endsWith(MODULE_EXT)) {
                builder.readJmod(jmod);
            } else if (filename.endsWith(JAR_EXT)) {
                throw new UnsupportedOperationException(jmod.toString());
            }
        }
    }

    private void writeIndexedImage(Map<String, Path> nameToJmod) throws IOException {
        Files.createDirectories(mdir);
        for (Loader l : Loader.values()) {
            if (builder.modules(l).isEmpty()) {
                continue;
            }

            try (OutputStream fos = Files.newOutputStream(mdir.resolve(l.filename));
                    BufferedOutputStream bos = new BufferedOutputStream(fos);
                    DataOutputStream out = new DataOutputStream(bos)) {
                // store index in addition of the class loader map for boot loader
                ImageWriter writer = new ImageWriter();
                Set<String> duplicates = new HashSet<>();

                if (l == BOOT_LOADER) {
                    builder.store(writer, duplicates);
                } else {
                    builder.storeClassLoaderMap(l, writer, duplicates);
                }

                byte[] bytes = writer.getBytes();
                out.write(bytes, 0, bytes.length);

                // write module content
                for (ModuleDescriptor m : builder.modules(l)) {
                    writeModule(nameToJmod.get(m.name()), out);
                }
            }
        }
        // ## temporary - replaced with the readability graph
        Path modulesSer = root.resolve("lib").resolve("modules.ser");
        try (OutputStream os = Files.newOutputStream(modulesSer);
                ObjectOutputStream oos = new ObjectOutputStream(os)) {
            oos.writeObject(builder.modules().toArray(new ModuleDescriptor[0]));
        }
    }

    private static String moduleName(Path jmod) {
        String fn = jmod.getFileName().toString();
        return fn.substring(0, fn.length() - MODULE_EXT.length());
    }

    private void writeModule(Path jmod, OutputStream out) throws IOException {
        try (ZipFile zf = new ZipFile(jmod.toFile())) {
            ModuleEntryWriter consumer = new ModuleEntryWriter(out, zf);
            zf.stream()
              .filter(ze -> !ze.isDirectory() && !ze.getName().startsWith("classes/_"))
              .forEach(consumer);
        }
    }

    private byte[] readEntry(String mn, String entryName) throws IOException {
        ModuleCatalog catalog = catalogs.stream()
                .filter(c -> c.hasModule(mn))
                .findFirst().orElse(null);
        Resource e;
        if (catalog != null && (e = catalog.findEntry(entryName)) != null) {
            int len = (int) e.csize();
            ByteBuffer bb = ByteBuffer.allocate(len);
            bch.position(entryStartPosition + e.offset()).read(bb);
            return bb.array();
        }
        throw new IOException(entryName + " not found from " + mn);
    }

    public byte[] readLocalClass(String mn, String className)
            throws IOException {
        return readEntry(mn, className.replace('.', '/') + ".class");
    }

    class IndexBuilder {
        private final Map<Loader, LoaderModuleIndex> loaders = new LinkedHashMap<>();
        private final Map<ModuleDescriptor, Integer> indexForModule = new LinkedHashMap<>();
        private final Map<String, ModuleDescriptor> nameToModule = new HashMap<>();
        private int moduleIndex = 1; // 1 is reserved for java.base
        private int packageIndex = 0;
        private Map<String, Integer> indexForPackage;

        IndexBuilder(Set<ModuleDescriptor> bootModules,
                     Set<ModuleDescriptor> extModules,
                     Set<ModuleDescriptor> modules) {
            loader(BOOT_LOADER, bootModules);
            loader(EXT_LOADER, extModules);
            loader(APP_LOADER, modules);
        }

        public List<ModuleDescriptor> modules() {
            return indexForModule.entrySet().stream()
                                 .sorted(Map.Entry.comparingByValue())
                                 .map(Map.Entry::getKey)
                                 .collect(Collectors.toList());
        }

        public List<ModuleDescriptor> modules(Loader loader) {
            return loaders.containsKey(loader)
                       ? loaders.get(loader).modules()
                       : Collections.emptyList();
        }

        private void loader(Loader loader, Set<ModuleDescriptor> modules) {
            if (modules.isEmpty()) {
                return;
            }

            // put java.base first
            List<ModuleDescriptor> mods = new ArrayList<>();
            modules.stream()
                    .filter(m -> m.name().equals(JAVA_BASE))
                    .forEach(m -> {
                        indexForModule.put(m, 1);
                        nameToModule.put(m.name(), m);
                        mods.add(m);
                    });
            modules.stream().sorted(Comparator.comparing(m -> m.name()))
                    .filter(m -> !m.name().equals(JAVA_BASE))
                    .forEach(m -> {
                        indexForModule.put(m, ++moduleIndex);
                        nameToModule.put(m.name(), m);
                        mods.add(m);
                    });
            loaders.put(loader, new LoaderModuleIndex(this, loader, mods, MODULES_DIR + loader.filename));
        }

        private ModuleDescriptor getModule(String mn) {
            return nameToModule.get(mn);
        }

        void readJmod(Path jmod) throws IOException {
            String filename = jmod.getFileName().toString();
            String mn = filename.substring(0, filename.indexOf(MODULE_EXT));
            ModuleDescriptor m = getModule(mn);
            LoaderModuleIndex loader = loaders.values().stream()
                                              .filter(l -> l.modules.contains(m))
                                              .findFirst().get();
            try (ZipFile zf = new ZipFile(jmod.toFile())) {
                zf.stream()
                  .filter(ze -> !ze.isDirectory() && ze.getName().startsWith("classes"))
                  .forEach(ze -> loader.addEntry(m, ze));
            }
        }

        void store(ImageWriter writer, Set<String> duplicates) {
            // build package map
            indexForPackage = loaders.values().stream()
                    .map(LoaderModuleIndex::packages)
                    .flatMap(ps -> ps.stream())
                    .distinct()
                    .sorted()
                    .collect(Collectors.toMap(Function.identity(), p -> ++packageIndex));

            loaders.get(BOOT_LOADER).store(writer, duplicates, 0);
        }

        void storeClassLoaderMap(Loader loader, ImageWriter writer, Set<String> duplicates) throws IOException {
            loaders.get(loader).store(writer, duplicates, 0);
        }
    }

    class LoaderModuleIndex {
        private final IndexBuilder builder;
        private final Loader loader;
        private final List<ModuleDescriptor> modules;
        private final String path;
        // classes and resource files
        private final Map<ModuleDescriptor, List<Resource>> entries = new LinkedHashMap<>();
        private long size = 0;
        LoaderModuleIndex(IndexBuilder builder, Loader loader, List<ModuleDescriptor> modules, String path) {
            this.builder = builder;
            this.loader = loader;
            this.modules = Collections.unmodifiableList(modules);
            this.path = path;
        }

        List<ModuleDescriptor> modules() {
            return modules;
        }

        Set<String> packages() {
            return entries.values().stream()
                          .flatMap(l -> l.stream())
                          .map(Resource::getName)
                          .map(this::toPackage)
                          .distinct()
                          .sorted()
                          .collect(Collectors.toSet());
        }

        void addEntry(ModuleDescriptor m, ZipEntry ze) {
            List<Resource> classes = entries.computeIfAbsent(m, _e -> new ArrayList<>());

            String name = ze.getName();
            String fn = name.substring(name.indexOf('/') + 1);
            if (fn.startsWith("_") || fn.equals(MODULE_INFO)) {
                return;
            }
            long entrySize = ze.getSize();
            classes.add(new Resource(fn, size, entrySize, 0 /* no compression support yet */));
            size += entrySize;
        }

        private String toPackage(String path) {
            int index = path.lastIndexOf('/');
            return index > 0 ? path.substring(0, index).replace('/', '.') : "";
        }

        void store(ImageWriter writer, Set<String> duplicates, long pos) {
            long offset = pos;
            for (ModuleDescriptor m : modules) {
                for (Resource e : entries.get(m)) {
                    String fn = e.getName();

                    if (duplicates.contains(fn)) {
                        System.err.format("duplicate resource \"%s\", skipping%n", fn);
                        continue;
                    }

                    duplicates.add(fn);

                    int i = fn.lastIndexOf('/');
                    String pn = i > 0 ? fn.substring(0, i).replace('/', '.') : "";

                    if (!builder.indexForPackage.containsKey(pn)) {
                        System.err.format("missing package \"%s\"%n", pn);
                        continue;
                    }

                    writer.addLocation(fn, offset, e.csize(), e.size());
                    offset += e.csize() != 0 ? e.csize() : e.size();
                }
            }
        }
    }

    enum Loader {
        BOOT_LOADER(0, "Boot loader", "bootmodules" + IMAGE_EXT),
        EXT_LOADER(1, "Ext loader", "extmodules" + IMAGE_EXT),
        APP_LOADER(2, "App loader", "appmodules" + IMAGE_EXT);  // ## may be more than 1 loader

        final int id;
        final String name;
        final String filename;

        Loader(int id, String name, String filename) {
            this.id = id;
            this.name = name;
            this.filename = filename;
        }

        static Loader get(int id) {
            switch (id) {
                case 0:
                    return BOOT_LOADER;
                case 1:
                    return EXT_LOADER;
                case 2:
                    return APP_LOADER;
                default:
                    throw new IllegalArgumentException("invalid loader id: " + id);
            }
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
     * An entry represents a class or resource file looked up from this catalog.
     */
    class Resource {
        private final String name;
        private final long offset;
        private final long size;
        private final long csize;

        Resource(String name, long offset, long size, long csize) {
            this.name = name;
            this.offset = offset;
            this.size = size;
            this.csize = csize;
        }

        /**
         * Returns the name of this entry.
         */
        public String getName() {
            return name;
        }

        /**
         * Returns the byte offset for this entry relative to the raw content.
         */
        public long offset() {
            return offset;
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
            return String.format("%s offset %d size %d", name, offset, size);
        }
    }

    class ModuleCatalog {
        private final String name;
        private final ImageFile lib;
        private final String mpath;
        private final Set<String> modules;
        private final Map<String, String> packageToModule;
        private final Lookup lookup;
        private final int storedSize;
        ModuleCatalog(String name,
                      ImageFile lib,
                      String mpath,
                      int indexTableSize,
                      Set<String> modules,
                      Map<String, String> localPkgs,
                      Map<String, Map<String, String>> remoteExportedPkgs,
                      Map<String, List<Resource>> entries) {
            this.name = name;
            this.lib = lib;
            this.mpath = mpath;
            this.storedSize = indexTableSize;
            this.modules = Collections.unmodifiableSet(modules);
            this.packageToModule = localPkgs;
            this.lookup = new Lookup(entries);
        }

        public Set<String> modules() {
            return modules;
        }

        public boolean hasModule(String mn) {
            return modules.contains(mn);
        }

        /**
         * Finds the {@code Entry} of the given entry name in this catalog;
         * return {@code null} if not found.
         *
         * @param entryName the entry name
         */
        public Resource findEntry(String entryName) {
            return lookup.nameToLocation.get(entryName);
        }

        int entryCount() {
            return lookup.nameToLocation.size();
        }

        /**
         * Finds the module where the class of the given name is found from this
         * catalog.
         *
         * @param cn fully-qualified class name
         * @return the name of the matching module, or {@code null} if not
         * found.
         */
        public String findModuleForLocalClass(String cn) {
            return packageToModule.get(toPackageName(cn));
        }

        private String toPackageName(String cn) {
            int i = cn.lastIndexOf('.');
            String pn = i > 0 ? cn.substring(0, i) : "";
            return pn;
        }

        private String toEntryName(String cn) {
            return cn.replace('.', '/') + ".class";
        }

        class Lookup {
            private final Map<String, List<Resource>> entries;
            private final Map<String, Resource> nameToLocation = new HashMap<>();
            private final Map<String, String> nameToModule = new HashMap<>();
            Lookup(Map<String, List<Resource>> entries) {
                this.entries = entries;  // ## statistic only
                entries.values().stream()
                       .flatMap(v -> v.stream())
                       .forEach(e -> nameToLocation.put(e.name, e));
                entries.keySet().stream().forEach(m ->
                        entries.get(m).stream()
                               .forEach(e -> nameToModule.putIfAbsent(e.name, m)));
            }

            int entryCount(String mn) {
                return entries.get(mn).size();
            }

            long entrySize(String mn) {
                return entries.get(mn).stream().mapToLong(e -> e.size).sum();
            }
        }

        public void dump(PrintStream out) {
            out.println(name);
            out.format("Content: %s%n", mpath);
            out.format("%d modules [index %d bytes %d entries]%n", modules.size(), storedSize, entryCount());
            modules.stream().sorted()
                    .forEach(m -> out.format("  %-20s %6d entries %d bytes%n",
                                             m, lookup.entryCount(m), lookup.entrySize(m)));
            out.format("local package map%n");
            packageToModule.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> out.format("  %-50s module %s%n", e.getKey(), e.getValue()));
        }

        public void dumpEntries(PrintStream out) {
            out.format("entry map%n");
            lookup.nameToLocation.values().stream()
                  .sorted(Comparator.comparing(e -> e.offset))
                  .forEach(e -> out.format("  %-50s  off %d size %d csize %d%n",
                                           e.name, e.offset, e.size, e.csize()));
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

    public void dump(PrintStream out, boolean verbose) {
        int count = catalogs.stream().mapToInt(ModuleCatalog::entryCount).sum();
        out.format("Total Index table size: %d bytes %d entries%n", entryStartPosition, count);
        catalogs.forEach(c -> c.dump(out));
        if (verbose) {
            catalogs.forEach(c -> c.dumpEntries(out));
        }
    }
}
