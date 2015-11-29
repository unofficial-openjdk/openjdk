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
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jdk.internal.module.Hasher;
import sun.net.www.ParseUtil;


/**
 * A factory for creating ModuleReference implementations where the modules are
 * packaged as modular JAR file, JMOD files or where the modules are exploded
 * on the file system.
 */

class ModuleReferences {

    private ModuleReferences() { }

    /**
     * Creates the ModuleReference.
     */
    static ModuleReference newModuleReference(ModuleDescriptor md,
                                              URI location,
                                              Hasher.HashSupplier hasher)
    {
        // use new ModuleDescriptor if new packages added by -Xpatch
        ModuleDescriptor descriptor = ModulePatcher.patchIfNeeded(md);

        String scheme = location.getScheme();

        Supplier<ModuleReader> readerSupplier;
        if (scheme.equalsIgnoreCase("jar")) {
            readerSupplier = () -> new JarModuleReader(location);
        } else if (scheme.equalsIgnoreCase("jmod")) {
            readerSupplier = () -> new JModModuleReader(location);
        } else if (scheme.equalsIgnoreCase("file")) {
            readerSupplier = () -> new ExplodedModuleReader(location);
        } else {
            throw new InternalError("Should not get here");
        }

        return new ModuleReference(descriptor, location, readerSupplier, hasher);
    }


    /**
     * A base module reader that encapsulates machinery required to close the
     * module reader safely.
     */
    static abstract class SafeCloseModuleReader implements ModuleReader {

        // RW lock to support safe close
        private final ReadWriteLock lock = new ReentrantReadWriteLock();
        private final Lock readLock = lock.readLock();
        private final Lock writeLock = lock.writeLock();
        private volatile boolean closed;

        SafeCloseModuleReader() { }

        /**
         * Returns a URL to  resource. This method is invoked by the find
         * method to do the actual work of finding the resource.
         */
        abstract Optional<URI> implFind(String name) throws IOException;

        /**
         * Returns an input stream for reading a resource. This method is
         * invoked by the open method to do the actual work of opening
         * an input stream to the resource.
         */
        abstract Optional<InputStream> implOpen(String name) throws IOException;

        /**
         * Closes the module reader. This method is invoked by close to do the
         * actual work of closing the module reader.
         */
        abstract void implClose() throws IOException;

        @Override
        public final Optional<URI> find(String name) throws IOException {
            readLock.lock();
            try {
                if (!closed) {
                    return implFind(name);
                } else {
                    throw new IOException("ModuleReader is closed");
                }
            } finally {
                readLock.unlock();
            }
        }


        @Override
        public final Optional<InputStream> open(String name) throws IOException {
            readLock.lock();
            try {
                if (!closed) {
                    return implOpen(name);
                } else {
                    throw new IOException("ModuleReader is closed");
                }
            } finally {
                readLock.unlock();
            }
        }

        @Override
        public void close() throws IOException {
            writeLock.lock();
            try {
                if (!closed) {
                    closed = true;
                    implClose();
                }
            } finally {
                writeLock.unlock();
            }
        }
    }


    /**
     * A ModuleReader for a modular JAR file.
     */
    static class JarModuleReader extends SafeCloseModuleReader {
        private final URI uri;
        private final JarFile jf;

        static JarFile newJarFile(String name) {
            try {
                return new JarFile(name);
            } catch (IOException ioe) {
                throw new UncheckedIOException(ioe);
            }
        }

        JarModuleReader(URI uri) {
            String s = uri.toString();
            URI fileURI = URI.create(s.substring(4, s.length()-2));
            this.uri = uri;
            this.jf = newJarFile(Paths.get(fileURI).toString());
        }

        private JarEntry getEntry(String name) {
            return jf.getJarEntry(Objects.requireNonNull(name));
        }

        @Override
        Optional<URI> implFind(String name) throws IOException {
            JarEntry je = getEntry(name);
            if (je != null) {
                String encodedPath = ParseUtil.encodePath(name, false);
                return Optional.of(URI.create(uri + encodedPath));
            } else {
                return Optional.empty();
            }
        }

        @Override
        Optional<InputStream> implOpen(String name) throws IOException {
            JarEntry je = getEntry(name);
            if (je != null) {
                return Optional.of(jf.getInputStream(je));
            } else {
                return Optional.empty();
            }
        }

        @Override
        void implClose() throws IOException {
            jf.close();
        }
    }


    /**
     * A ModuleReader for a JMOD file.
     */
    static class JModModuleReader extends SafeCloseModuleReader {
        private final URI uri;
        private final ZipFile zf;

        static ZipFile newZipFile(String name) {
            try {
                return new ZipFile(name);
            } catch (IOException ioe) {
                throw new UncheckedIOException(ioe);
            }
        }

        JModModuleReader(URI uri) {
            String s = uri.toString();
            URI fileURI = URI.create(s.substring(5, s.length() - 2));
            this.uri = uri;
            this.zf = newZipFile(Paths.get(fileURI).toString());
        }

        private ZipEntry getEntry(String name) {
            return zf.getEntry("classes/" + Objects.requireNonNull(name));
        }

        @Override
        Optional<URI> implFind(String name) {
            ZipEntry ze = getEntry(name);
            if (ze != null) {
                String encodedPath = ParseUtil.encodePath(name, false);
                return Optional.of(URI.create(uri + encodedPath));
            } else {
                return Optional.empty();
            }
        }

        @Override
        Optional<InputStream> implOpen(String name) throws IOException {
            ZipEntry ze = getEntry(name);
            if (ze != null) {
                return Optional.of(zf.getInputStream(ze));
            } else {
                return Optional.empty();
            }
        }

        @Override
        void implClose() throws IOException {
            zf.close();
        }
    }


    /**
     * A ModuleReader for an exploded module.
     */
    static class ExplodedModuleReader implements ModuleReader {
        private final Path dir;
        private volatile boolean closed;

        ExplodedModuleReader(URI location) {
            dir = Paths.get(location);

            // when running with a security manager then check that the caller
            // has access to the directory.
            SecurityManager sm = System.getSecurityManager();
            if (sm != null) {
                boolean unused = Files.isDirectory(dir);
            }
        }

        /**
         * Returns a Path to access to the given resource.
         */
        private Path toPath(String name) {
            Path path = Paths.get(name.replace('/', File.separatorChar));
            if (path.getRoot() == null) {
                return dir.resolve(path);
            } else {
                // drop the root component so that the resource is
                // located relative to the module directory
                int n = path.getNameCount();
                return (n > 0) ? dir.resolve(path.subpath(0, n)) : null;
            }
        }

        /**
         * Throws IOException if the module reader is closed;
         */
        private void ensureOpen() throws IOException {
            if (closed) throw new IOException("ModuleReader is closed");
        }

        @Override
        public Optional<URI> find(String name) throws IOException {
            ensureOpen();
            Path path = toPath(name);
            if (path != null && Files.isRegularFile(path)) {
                try {
                    return Optional.of(path.toUri());
                } catch (IOError e) {
                    throw (IOException) e.getCause();
                }
            } else {
                return Optional.empty();
            }
        }

        @Override
        public Optional<InputStream> open(String name) throws IOException {
            ensureOpen();
            Path path = toPath(name);
            if (path != null && Files.isRegularFile(path)) {
                return Optional.of(Files.newInputStream(path));
            } else {
                return Optional.empty();
            }
        }

        @Override
        public Optional<ByteBuffer> read(String name) throws IOException {
            ensureOpen();
            Path path = toPath(name);
            if (path != null && Files.isRegularFile(path)) {
                return Optional.of(ByteBuffer.wrap(Files.readAllBytes(path)));
            } else {
                return Optional.empty();
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }

}
