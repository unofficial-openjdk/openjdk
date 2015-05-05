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

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import jdk.internal.jimage.BasicImageWriter;
import jdk.tools.jlink.plugins.ImageBuilder;
import jdk.tools.jlink.plugins.ImageFilePool;
import jdk.tools.jlink.plugins.ImageFilePool.ImageFile;
/**
 *
 * @author jdenise
 */
public class DefaultImageBuilder implements ImageBuilder {

    private final Path root;
    private final Path mdir;
    private final Map<String, Path> mods;
    private final String jimage;
    public DefaultImageBuilder(Properties properties, Path root,
            Map<String, Path> mods) throws IOException {
        Objects.requireNonNull(root);
        Objects.requireNonNull(mods);
        String img = properties.getProperty(DefaultImageBuilderProvider.JIMAGE_NAME_PROPERTY);
        jimage= img == null ? BasicImageWriter.BOOT_IMAGE_NAME : img;
        this.root = root;
        this.mdir = root.resolve(root.getFileSystem().getPath("lib", "modules"));
        this.mods = mods;
        Files.createDirectories(mdir);
    }

    private void storeRelease(Set<String> modules) throws IOException {
        // Retrieve release file from JDK home dir.
        String path = System.getProperty("java.home");
        File f = new File(path, "release");
        Properties release = null;
        if (!f.exists()) {
            System.err.println("WARNING, no release file found in " + path +
                    ". release file not added to generated image");
        } else {
            release = new Properties();
            try(FileInputStream fi = new FileInputStream(f)) {
                release.load(fi);
            }
            addModules(release, modules);
        }

        if (release != null) {
            File r = new File(root.toFile(), "release");
            try(FileOutputStream fo = new FileOutputStream(r)) {
                release.store(fo, null);
            }
        }
    }

    private void addModules(Properties release, Set<String> modules) throws IOException {
        if (release != null) {
            StringBuilder builder = new StringBuilder();
            int i = 0;
            for (String m : modules) {
                builder.append(m);
                if (i < modules.size() - 1) {
                    builder.append(",");
                }
                i++;
            }
            release.setProperty("MODULES", builder.toString());
        }
    }

    @Override
    public void storeFiles(ImageFilePool files, Set<String> modules) throws IOException {
        for (ImageFile f : files.getFiles()) {
            accept(f);
        }
        // XXX TODO, what about other files src.zip, licenses, ....
        // Do we make that configurable?
        storeRelease(modules);

         // launchers in the bin directory need execute permission
        Path bin = root.resolve("bin");
        if (Files.getFileStore(bin).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.list(bin)
                 .filter(f -> !f.toString().endsWith(".diz"))
                 .filter(f -> Files.isRegularFile(f))
                 .forEach(this::setExecutable);

            // jspawnhelper is in lib or lib/<arch>
            Path lib = root.resolve("lib");
            Files.find(lib, 2, (path, attrs) -> {
                return path.getFileName().toString().equals("jspawnhelper");
            }).forEach(this::setExecutable);
        }

        // generate launch scripts for the modules with a main class
        for (Map.Entry<String, Path> entry : mods.entrySet()) {
            String module = entry.getKey();
            if (modules.contains(module)) {
                Path jmodpath = entry.getValue();

                Optional<String> mainClass = Optional.empty();

                try (ZipFile zf = new ZipFile(jmodpath.toString())) {
                    String e = "classes/module-info.class";
                    ZipEntry ze = zf.getEntry(e);
                    if (ze != null) {
                        try (InputStream in = zf.getInputStream(ze)) {
                            mainClass = ModuleDescriptor.read(in).mainClass();
                        }
                    }
                }

                if (mainClass.isPresent()) {
                    Path cmd = root.resolve("bin").resolve(module);
                    if (!Files.exists(cmd)) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("#!/bin/sh")
                                .append("\n");
                        sb.append("DIR=`dirname $0`")
                                .append("\n");
                        sb.append("$DIR/java -m ")
                                .append(module).append('/')
                                .append(mainClass.get())
                                .append(" $@\n");

                        try (BufferedWriter writer = Files.newBufferedWriter(cmd,
                                StandardCharsets.ISO_8859_1,
                                StandardOpenOption.CREATE_NEW)) {
                            writer.write(sb.toString());
                        }
                        if (Files.getFileStore(bin)
                                .supportsFileAttributeView(PosixFileAttributeView.class)) {
                            setExecutable(cmd);
                        }
                    }
                }
            }
        }
    }

    @Override
    public DataOutputStream getJImageOutputStream() throws IOException {
        Path jimageFile = mdir.resolve(jimage);
        OutputStream fos = Files.newOutputStream(jimageFile);
        BufferedOutputStream bos = new BufferedOutputStream(fos);
        return new DataOutputStream(bos);
    }

    private void accept(ImageFile file) throws IOException {
        String name = file.getPath();
        String filename = name.substring(name.indexOf('/') + 1);
        try (InputStream in = file.stream()) {
            switch (file.getType()) {
                case NATIVE_LIB:
                    writeEntry(in, destFile(nativeDir(filename), filename));
                    break;
                case NATIVE_CMD:
                    Path path = destFile("bin", filename);
                    writeEntry(in, path);
                    path.toFile().setExecutable(true);
                    break;
                case CONFIG:
                    writeEntry(in, destFile("conf", filename));
                    break;
                default:
                    //throw new InternalError("unexpected entry: " + name + " " + zipfile.toString()); //TODO
                    throw new InternalError("unexpected entry: " + name + " " + name);
            }
        }
    }

    private Path destFile(String dir, String filename) {
        return root.resolve(dir).resolve(filename);
    }

    private void writeEntry(InputStream in, Path dstFile) throws IOException {
        Files.createDirectories(dstFile.getParent());
        Files.copy(in, dstFile);
    }

    private static String nativeDir(String filename) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            if (filename.endsWith(".dll") || filename.endsWith(".diz")
                || filename.endsWith(".pdb") || filename.endsWith(".map")) {
                return "bin";
            } else {
                return "lib";
            }
        } else {
            return "lib";
        }
    }

    /**
     * chmod ugo+x file
     */
    private void setExecutable(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }
}
