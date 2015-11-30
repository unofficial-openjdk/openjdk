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
package jdk.tools.jlink.internal.plugins;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jdk.tools.jlink.plugins.ImageFilePlugin;
import jdk.tools.jlink.plugins.CmdImageFilePluginProvider;
import jdk.tools.jlink.plugins.ImageFilePool;
import jdk.tools.jlink.plugins.ImageFilePool.ImageFile;
import jdk.tools.jlink.plugins.ImageFilePool.SymImageFile;
import jdk.tools.jlink.plugins.PluginProvider;

/**
 *
 * Copy files to image from various locations.
 */
public class FileCopierProvider extends CmdImageFilePluginProvider {

    private static final class ImageFileImpl extends ImageFile {

        private final Path file;

        public ImageFileImpl(Path file, String module, String path, String name, ImageFileType type) {
            super(module, path, name, type);
            this.file = file;
        }

        @Override
        public long size() {
            try {
                return Files.size(file);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        public InputStream stream() throws IOException {
            return Files.newInputStream(file);
        }

    }

    private static final class SymImageFileImpl extends SymImageFile {

        private final Path file;
        public SymImageFileImpl(String targetPath, Path file, String module,
                String path, String name, ImageFile.ImageFileType type) {
            super(targetPath, module, path, name, type);
            this.file = file;
        }

        @Override
        public long size() {
            try {
                return Files.size(file);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        public InputStream stream() throws IOException {
            return Files.newInputStream(file);
        }

    }

    private static final class DirectoryCopy implements FileVisitor<Path> {

        private final Path source;
        private final ImageFilePool pool;
        private final String targetDir;
        private final List<ImageFile> symlinks = new ArrayList<>();

        DirectoryCopy(Path source, ImageFilePool pool, String targetDir) {
            this.source = source;
            this.pool = pool;
            this.targetDir = targetDir;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir,
                BasicFileAttributes attrs) throws IOException {
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file,
                BasicFileAttributes attrs) throws IOException {
            Objects.requireNonNull(file);
            Objects.requireNonNull(attrs);
            String path = targetDir + "/" + source.relativize(file);
            if (attrs.isSymbolicLink()) {
                Path symTarget = Files.readSymbolicLink(file);
                if (!Files.exists(symTarget)) {
                    // relative to file parent?
                    Path parent = file.getParent();
                    if (parent != null) {
                        symTarget = parent.resolve(symTarget);
                    }
                }
                if (!Files.exists(symTarget)) {
                    System.err.println("WARNING: Skipping sym link, target "
                            + Files.readSymbolicLink(file) + "not found");
                    return FileVisitResult.CONTINUE;
                }
                SymImageFileImpl impl = new SymImageFileImpl(symTarget.toString(),
                        file, "$jlink-file-copier",
                        path, Objects.requireNonNull(file.getFileName()).toString(),
                        ImageFile.ImageFileType.OTHER);
                symlinks.add(impl);
            } else {
                addFile(pool, file, path);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException {
            if (exc != null) {
                throw exc;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc)
                throws IOException {
            throw exc;
        }
    }

    private static void addFile(ImageFilePool pool, Path file, String path)
            throws IOException {
        Objects.requireNonNull(pool);
        Objects.requireNonNull(file);
        Objects.requireNonNull(path);
        ImageFileImpl impl = new ImageFileImpl(file, "$jlink-file-copier",
                path, Objects.requireNonNull(file.getFileName()).toString(),
                ImageFile.ImageFileType.OTHER);
        try {
            pool.addFile(impl);
        } catch (Exception ex) {
            throw new IOException(ex);
        }
    }

    public static final class FileCopier implements ImageFilePlugin {

        private static final class CopiedFile {
            Path source;
            Path target;
        }

        private final List<CopiedFile> files = new ArrayList<>();

        public FileCopier(String[] argument) {
            if (argument == null || argument.length == 0) {
                throw new RuntimeException("Invalid argument for " + NAME);
            }
            String javahome = System.getProperty("java.home");
            for (String a : argument) {
                int i = a.indexOf("=");
                CopiedFile cf = new CopiedFile();
                if (i == -1) {
                    Path file = Paths.get(a);
                    if (file.isAbsolute()) {
                        cf.source = file;
                        // The target is the image root directory.
                        cf.target = file.getFileName();
                    } else {
                        file = new File(javahome, a).toPath();
                        cf.source = file;
                        cf.target = Paths.get(a);
                    }
                } else {
                    String target = a.substring(i + 1);
                    String f = a.substring(0, i);
                    Path file = Paths.get(f);
                    if (file.isAbsolute()) {
                        cf.source = file;
                    } else {
                        cf.source = new File(javahome,
                                file.toFile().getPath()).toPath();
                    }
                    cf.target = Paths.get(target);
                }
                if (!Files.exists(cf.source)) {
                    System.err.println("Skipping file " + cf.source
                            + ", it doesn't exist");
                } else {
                    files.add(cf);
                }
            }
        }

        @Override
        public void visit(ImageFilePool inFiles, ImageFilePool outFiles)
                throws Exception {
            inFiles.visit((ImageFile file) -> {
                return file;
            }, outFiles);

            // Add new files.
            for (CopiedFile file : files) {
                if (Files.isRegularFile(file.source)) {
                    addFile(outFiles, file.source, file.target.toString());
                } else {
                    if (Files.isDirectory(file.source)) {
                        DirectoryCopy dc = new DirectoryCopy(file.source,
                                outFiles, file.target.toString());
                        Files.walkFileTree(file.source, dc);
                        // Add symlinks after actual content
                        for (ImageFile imf : dc.symlinks) {
                            try {
                                outFiles.addFile(imf);
                            } catch (Exception ex) {
                                throw new IOException(ex);
                            }
                        }
                    }
                }
            }
        }

        @Override
        public String getName() {
            return NAME;
        }

    }
    public static final String NAME = "copy-files";

    public FileCopierProvider() {
        super(NAME, PluginsResourceBundle.getDescription(NAME));
    }

    @Override
    public String getToolArgument() {
        return PluginsResourceBundle.getArgument(NAME);
    }

    @Override
    public String getCategory() {
        return PluginProvider.TRANSFORMER;
    }

    @Override
    public String getToolOption() {
        return NAME;
    }

    @Override
    public Map<String, String> getAdditionalOptions() {
        return null;
    }

    @Override
    public ImageFilePlugin[] newPlugins(String[] arguments,
            Map<String, String> otherOptions) throws IOException {
        return new ImageFilePlugin[]{new FileCopier(arguments)};
    }

}
