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

package java.lang.module;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jdk.internal.jimage.ImageLocation;
import jdk.internal.jimage.ImageReader;
import jdk.internal.jimage.ImageReaderFactory;
import jdk.jigsaw.module.internal.Hasher;
import jdk.jigsaw.module.internal.ModuleInfo;
import sun.misc.JModCache;
import sun.net.www.ParseUtil;

/**
 * A factory for creating ModuleArtifact implementations where the modules are
 * located in the run-time image, packaged as jmod or modular JAR files, or
 * where the modules are exploded on the file system.
 */

class ModuleArtifacts {
    private ModuleArtifacts() { }

    /**
     * Creates a ModuleArtifact. An extended module descriptor is created from the
     * module information read from a {@code module-info} class file.
     */
    static ModuleArtifact newModuleArtifact(ModuleInfo mi,
                                            Set<String> packages,
                                            URI location,
                                            Hasher.HashSupplier hasher)
    {
        ModuleId id = ModuleId.parse(mi.name(), mi.version());
        ExtendedModuleDescriptor descriptor =
            new ExtendedModuleDescriptor(id,
                                         mi.mainClass(),
                                         mi.hashes(),
                                         mi.moduleDependences(),
                                         mi.serviceDependences(),
                                         mi.exports(),
                                         mi.services());

        String scheme = location.getScheme();
        if (scheme.equalsIgnoreCase("jrt"))
            return new JrtModuleArtifact(descriptor, packages, location, hasher);
        if (scheme.equalsIgnoreCase("jmod"))
            return new JModModuleArtifact(descriptor, packages, location, hasher);
        if (scheme.equalsIgnoreCase("jar"))
            return new JarModuleArtifact(descriptor, packages, location, hasher);
        if (scheme.equalsIgnoreCase("file"))
            return new ExplodedModuleArtifact(descriptor, packages, location, hasher);

        throw new InternalError("Should not get here");
    }

    /**
     * A ModuleArtifact for a module that is linked into the run-time image.
     */
    static class JrtModuleArtifact extends ModuleArtifact {
        JrtModuleArtifact(ExtendedModuleDescriptor descriptor,
                          Set<String> packages,
                          URI location,
                          Hasher.HashSupplier hasher) {
            super(descriptor, packages, location, hasher);
        }

        public ModuleReader open() throws IOException {
            return new JrtModuleReader(this);
        }
    }

    /**
     * A ModuleArtifact for a module that is packaged as jmod file.
     */
    static class JModModuleArtifact extends ModuleArtifact {
        JModModuleArtifact(ExtendedModuleDescriptor descriptor,
                           Set<String> packages,
                           URI location,
                           Hasher.HashSupplier hasher) {
            super(descriptor, packages, location, hasher);
        }

        public ModuleReader open() throws IOException {
            return new JModModuleReader(this);
        }
    }

    /**
     * A ModuleArtifact for a module that is packaged as a modular JAR file.
     */
    static class JarModuleArtifact extends ModuleArtifact {
        JarModuleArtifact(ExtendedModuleDescriptor descriptor,
                          Set<String> packages,
                          URI location,
                          Hasher.HashSupplier hasher) {
            super(descriptor, packages, location, hasher);
        }

        @Override
        public ModuleReader open() throws IOException {
            return new JarModuleReader(this);
        }
    }

    /**
     * A ModuleArtifact for a module that is exploded on the file system.
     */
    static class ExplodedModuleArtifact extends ModuleArtifact {
        ExplodedModuleArtifact(ExtendedModuleDescriptor descriptor,
                               Set<String> packages,
                               URI location,
                               Hasher.HashSupplier hasher) {
            super(descriptor, packages, location, hasher);
        }

        public ModuleReader open() throws IOException {
            return new ExplodedModuleReader(this);
        }
    }

    /**
     * A base ModuleReader implementation
     */
    static abstract class BaseModuleReader implements ModuleReader {
        private static final int BUFFER_SIZE = 8192;
        private static final int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 8;

        /**
         * Returns a ByteBuffer with all bytes read from the given InputStream.
         * The given size parameter is the expected size, the actual size may
         * differ.
         */
        protected ByteBuffer readFully(InputStream source, int size) throws IOException {
            int capacity = size;
            byte[] buf = new byte[capacity];
            int nread = 0;
            int n;
            for (; ; ) {
                // read to EOF which may read more or less than size
                while ((n = source.read(buf, nread, capacity - nread)) > 0)
                    nread += n;

                // if last call to source.read() returned -1, we are done
                // otherwise, try to read one more byte; if that failed we're done too
                if (n < 0 || (n = source.read()) < 0)
                    break;

                // one more byte was read; need to allocate a larger buffer
                if (capacity <= MAX_BUFFER_SIZE - capacity) {
                    capacity = Math.max(capacity << 1, BUFFER_SIZE);
                } else {
                    if (capacity == MAX_BUFFER_SIZE)
                        throw new OutOfMemoryError("Required array size too large");
                    capacity = MAX_BUFFER_SIZE;
                }
                buf = Arrays.copyOf(buf, capacity);
                buf[nread++] = (byte) n;
            }

            return ByteBuffer.wrap(buf, 0, nread);
        }

        /**
         * Returns a file URL to the given file Path
         */
        protected URL toFileURL(Path path) {
            try {
                return path.toUri().toURL();
            } catch (MalformedURLException e) {
                throw new InternalError(e);
            }
        }

        @Override
        public void close() {
            throw new RuntimeException("not implemented");
        }
    }

    /**
     * A ModuleReader for reading resources from a module linked into the
     * run-time image.
     */
    static class JrtModuleReader extends BaseModuleReader {
        private static ImageReader imageReader;
        static {
            // detect image or exploded build
            String home = System.getProperty("java.home");
            Path libModules = Paths.get(home, "lib", "modules");
            if (Files.isDirectory(libModules)) {
                // this can throw UncheckedIOException
                imageReader = ImageReaderFactory.getImageReader();
            } else {
                imageReader = null;
            }
        }

        private final String module;

        JrtModuleReader(ModuleArtifact artifact) throws IOException {
            // when running with a security manager then check that the caller
            // has access to the run-time image
            SecurityManager sm = System.getSecurityManager();
            if (sm != null) {
                URLConnection uc = artifact.location().toURL().openConnection();
                sm.checkPermission(uc.getPermission());
            }
            this.module = artifact.descriptor().name();
        }

        /**
         * Returns the ImageLocation for the given resource, {@code null}
         * if not found.
         */
        private ImageLocation findImageLocation(String name) {
            if (imageReader == null) {
                return null;
            } else {
                String rn = "/" + module + "/" + name;
                return imageReader.findLocation(rn);
            }
        }

        /**
         * Returns a jrt URL for the given module and resource name.
         */
        private URL toJrtURL(String name) {
            try {
                return new URL("jrt:/" + module + "/" + name);
            } catch (MalformedURLException e) {
                throw new InternalError(e);
            }
        }

        @Override
        public URL findResource(String name) {
            if (findImageLocation(name) != null)
                return toJrtURL(name);

            // not found
            return null;
        }

        @Override
        public ByteBuffer readResource(String name) throws IOException {
            ImageLocation location = findImageLocation(name);
            if (location != null) {
                return imageReader.getResourceBuffer(location);
            }
            throw new IOException(module + "/" + name + " not found");
        }

        @Override
        public void releaseBuffer(ByteBuffer bb) {
            ImageReader.releaseByteBuffer(bb);
        }
    }

    /**
     * A ModuleReader for a jmod file.
     */
    static class JModModuleReader extends BaseModuleReader {
        private String module;
        private final URL baseURL;
        private final ZipFile zf;

        JModModuleReader(ModuleArtifact artifact) throws IOException {
            module = artifact.descriptor().name();
            baseURL = artifact.location().toURL();

            // FIXME - need permission check here as the jmod may already
            // be open and in the cache

            zf = JModCache.get(baseURL);
        }

        @Override
        public URL findResource(String name) {
            ZipEntry ze = zf.getEntry("classes/" + name);
            if (ze == null)
                return null;
            try {
                return new URL(baseURL + "!/" + ParseUtil.encodePath(name, false));
            } catch (MalformedURLException e) {
                throw new InternalError(e);
            }
        }

        @Override
        public ByteBuffer readResource(String name) throws IOException {
            ZipEntry ze = zf.getEntry("classes/" + name);
            if (ze == null)
                throw new IOException(module + "/" + name + " not found");
            try (InputStream in = zf.getInputStream(ze)) {
                return readFully(in, (int) ze.getSize());
            }
        }
    }

    /**
     * A ModuleReader for a modular JAR file.
     */
    static class JarModuleReader extends BaseModuleReader {
        private final String module;
        private final URL baseURL;
        private final JarFile jf;

        JarModuleReader(ModuleArtifact artifact) throws IOException {
            URI uri = artifact.location();
            String s = uri.toString();
            String fileURIString = s.substring(4, s.length()-2);

            module = artifact.descriptor().name();
            baseURL = uri.toURL();
            jf = new JarFile(Paths.get(URI.create(fileURIString)).toString());
        }

        @Override
        public URL findResource(String name) {
            JarEntry je = jf.getJarEntry(name);
            if (je == null)
                return null;
            try {
                return new URL(baseURL + ParseUtil.encodePath(name, false));
            } catch (MalformedURLException e) {
                throw new InternalError(e);
            }
        }

        @Override
        public ByteBuffer readResource(String name) throws IOException {
            JarEntry je = jf.getJarEntry(name);
            if (je == null)
                throw new IOException(module + "/" + name + " not found");
            try (InputStream in = jf.getInputStream(je)) {
                return readFully(in, (int) je.getSize());
            }
        }
    }

    /**
     * A ModuleReader for an exploded module.
     */
    static class ExplodedModuleReader extends BaseModuleReader {
        private final Path dir;

        ExplodedModuleReader(ModuleArtifact artifact) {
            dir = Paths.get(artifact.location());
        }

        @Override
        public URL findResource(String name) {
            Path path = Paths.get(name.replace('/', File.separatorChar));
            int n = path.getNameCount();
            if (n == 0)
                return null;  // root component only

            // drop root component and resolve against module directory
            path = dir.resolve(path.subpath(0, n));
            if (Files.isRegularFile(path))
                return toFileURL(path);

            return null;
        }

        @Override
        public ByteBuffer readResource(String name) throws IOException {
            Path path = dir.resolve(name.replace('/', File.separatorChar));
            return ByteBuffer.wrap(Files.readAllBytes(path));
        }
    }
}
