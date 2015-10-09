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
package jdk.tools.jlink.plugins;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import jdk.tools.jlink.internal.BasicImageWriter;
import jdk.tools.jlink.internal.JvmHandler;
import jdk.tools.jlink.plugins.ImageFilePool.ImageFile;
import jdk.tools.jlink.plugins.ImageFilePool.SymImageFile;
import jdk.tools.jlink.plugins.ResourcePool.Resource;

/**
 *
 * Default Image Builder.
 */
public class DefaultImageBuilder implements ImageBuilder {

    private final Path root;
    private final Path mdir;
    private final boolean genBom;

    public DefaultImageBuilder(Map<String, Object> properties, Path root) throws IOException {
        Objects.requireNonNull(root);

        genBom = properties.containsKey(DefaultImageBuilderProvider.GEN_BOM);

        this.root = root;
        this.mdir = root.resolve(root.getFileSystem().getPath("lib", "modules"));
        Files.createDirectories(mdir);
    }

    private void storeFiles(Set<String> modules, String bom) throws IOException {
        // Retrieve release file from JDK home dir.
        String path = System.getProperty("java.home");
        File f = new File(path, "release");
        Properties release = null;
        if (!f.exists()) {
            // XXX When jlink is exposed to user.
            //System.err.println("WARNING, no release file found in " + path +
            //     ". release file not added to generated image");
        } else {
            release = new Properties();
            try (FileInputStream fi = new FileInputStream(f)) {
                release.load(fi);
            }
            addModules(release, modules);
        }

        if (release != null) {
            File r = new File(root.toFile(), "release");
            try (FileOutputStream fo = new FileOutputStream(r)) {
                release.store(fo, null);
            }
        }
        // Generate bom
        if (genBom) {
            File bomFile = new File(root.toFile(), "bom");
            createUtf8File(bomFile, bom);
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
    public void storeFiles(ImageFilePool pool, List<ImageFile> removedFiles,
            String bom, ResourceRetriever retriever) throws IOException {

        ImageFilePool files = new JvmHandler().handlePlatforms(pool, removedFiles);

        for (ImageFile f : files.getFiles()) {
            accept(f);
        }
        Set<String> modules = retriever.getModules();
        storeFiles(modules, bom);

        if (Files.getFileStore(root).supportsFileAttributeView(PosixFileAttributeView.class)) {
            // launchers in the bin directory need execute permission
            Path bin = root.resolve("bin");
            if (Files.isDirectory(bin)) {
                Files.list(bin)
                        .filter(f -> !f.toString().endsWith(".diz"))
                        .filter(f -> Files.isRegularFile(f))
                        .forEach(this::setExecutable);
            }

            // jspawnhelper is in lib or lib/<arch>
            Path lib = root.resolve("lib");
            if (Files.isDirectory(lib)) {
                Files.find(lib, 2, (path, attrs) -> {
                    return path.getFileName().toString().equals("jspawnhelper");
                }).forEach(this::setExecutable);
            }
        }

        prepareApplicationFiles(retriever, modules);
    }

    protected void prepareApplicationFiles(ResourceRetriever retriever, Set<String> modules) throws IOException {
        // generate launch scripts for the modules with a main class
        for (String module : modules) {
            String path = "/" + module + "/module-info.class";
            Resource res = retriever.retrieves(path);
            if (res == null) {
                throw new IOException("module-info not found for " + module);
            }
            Optional<String> mainClass;
            ByteArrayInputStream stream = new ByteArrayInputStream(res.getByteArray());
            mainClass = ModuleDescriptor.read(stream).mainClass();
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
                    if (Files.getFileStore(root.resolve("bin"))
                            .supportsFileAttributeView(PosixFileAttributeView.class)) {
                        setExecutable(cmd);
                    }
                }
            }
        }
    }

    @Override
    public DataOutputStream getJImageOutputStream() throws IOException {
        Path jimageFile = mdir.resolve(BasicImageWriter.BOOT_IMAGE_NAME);
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
                case OTHER:
                    int i = name.indexOf('/');
                    String dir = i < 0 ? "" : name.substring(0, i);
                    if (file instanceof SymImageFile) {
                        SymImageFile sym = (SymImageFile) file;
                        Path target = root.resolve(sym.getTargetPath());
                        if (!Files.exists(target)) {
                            throw new IOException("Sym link target " + target
                                    + " doesn't exist");
                        }
                        writeSymEntry(destFile(dir, filename), target);
                    } else {
                        writeEntry(in, destFile(dir, filename));
                    }
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
        Objects.requireNonNull(in);
        Objects.requireNonNull(dstFile);
        Files.createDirectories(Objects.requireNonNull(dstFile.getParent()));
        Files.copy(in, dstFile);
    }

    private void writeSymEntry(Path dstFile, Path target) throws IOException {
        Objects.requireNonNull(dstFile);
        Objects.requireNonNull(target);
        Files.createDirectories(Objects.requireNonNull(dstFile.getParent()));
        Files.createLink(dstFile, target);
    }

    private static String nativeDir(String filename) {
        if (isWindows()) {
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

    static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    static boolean isMac() {
        return System.getProperty("os.name").startsWith("Mac OS");
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

    private static void createUtf8File(File file, String content) throws IOException {
        try (OutputStream fout = new FileOutputStream(file);
                Writer output = new OutputStreamWriter(fout, "UTF-8")) {
            output.write(content);
        }
    }
}
