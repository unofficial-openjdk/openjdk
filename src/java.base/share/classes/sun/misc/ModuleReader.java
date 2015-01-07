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

package sun.misc;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jdk.jigsaw.module.ModuleArtifact;
import sun.net.www.ParseUtil;

/**
 * A reader of resources in a module artifact.
 *
 * <p> A {@code ModuleReader} is used to locate or access resources in a
 * module artifact. The {@link #create} method is a factory method to create
 * a {@code ModuleReader} for modules packaged as a jmod, modular JAR, or
 * exploded on the file system. </p>
 *
 * @apiNote We need to consider having jdk.jigsaw.module.ModuleArtifact
 * define these methods instead.
 *
 * @apiNote For now then reading returns a byte array, an more efficient
 * read that returns a ByteBuffer could help with some module artifact
 * types.
 */

interface ModuleReader {

    /**
     * Returns the URL for a resource in the given module or {@code null}
     * if not found.
     */
    URL findResource(String mn, String name);

    /**
     * Returns the bytes for a resource in a module.
     */
    byte[] readResource(String mn, String name) throws IOException;

    /**
     * Returns the URL that is CodeSource location. The URL may be
     * used to construct a {@code CodeSource}.
     *
     * @see java.security.SecureClassLoader
     */
    URL codeSourceLocation(String mn);

    /**
     * Returns a byte array contains all bytes reads from the given input
     * stream. The {@code size} parameter is the expected size although
     * the actual size may differ.
     *
     * @throws UncheckedIOException if there is an I/O error
     */
    static ModuleReader create(ModuleArtifact artifact) {
        URI location = artifact.location();
        String scheme = location.getScheme();
        try {
            if (scheme.equalsIgnoreCase("file")) {
                String s = location.toString();
                if (s.endsWith(".jmod")) {
                    return new JModModuleReader(artifact);
                } else {
                    if (s.endsWith(".jar")) {
                        return new JarModuleReader(artifact);
                    } else {
                        return new ExplodedModuleReader(artifact);
                    }
                }
            }
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }

        throw new InternalError("No module reader for: " + location);
    }
}

/**
 * A base ModuleReader implementation
 */
abstract class BaseModuleReader implements ModuleReader {
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 8;

    /**
     * Returns a byte array with all bytes read from the given InputStream.
     * The given size parameter is the expected size, the actual size may
     * differ.
     */
    protected byte[] readFully(InputStream source, int size) throws IOException {
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
        return (capacity == nread) ? buf : Arrays.copyOf(buf, nread);
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
}

/**
 * A ModuleReader for a jmod file.
 */
class JModModuleReader extends BaseModuleReader {
    private final URL baseURL;
    private final ZipFile zf;

    JModModuleReader(ModuleArtifact artifact) throws IOException {
        baseURL = new URL("jmod" + artifact.location().toString().substring(4));
        zf = JModCache.get(baseURL);
    }

    @Override
    public URL findResource(String mn, String name) {
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
    public byte[] readResource(String mn, String name) throws IOException {
        ZipEntry ze = zf.getEntry("classes/" + name);
        if (ze == null)
            throw new IOException(mn + "/" + name + " not found");
        try (InputStream in = zf.getInputStream(ze)) {
            return readFully(in, (int) ze.getSize());
        }
    }

    @Override
    public URL codeSourceLocation(String mn) {
        return baseURL;
    }
}

/**
 * A ModuleReader for a modular JAR file.
 */
class JarModuleReader extends BaseModuleReader {
    private final URL baseURL;
    private final JarFile jf;

    JarModuleReader(ModuleArtifact artifact) throws IOException {
        URI uri = artifact.location();
        baseURL = new URL("jar:" + uri.toString() + "!/");
        jf = new JarFile(Paths.get(uri).toString());
    }

    @Override
    public URL findResource(String mn, String name) {
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
    public byte[] readResource(String mn, String name) throws IOException {
        JarEntry je = jf.getJarEntry(name);
        if (je == null)
            throw new IOException(mn + "/" + name + " not found");
        try (InputStream in = jf.getInputStream(je)) {
            return readFully(in, (int) je.getSize());
        }
    }

    @Override
    public URL codeSourceLocation(String mn) {
        return baseURL;
    }
}

/**
 * A ModuleReader for an exploded module.
 */
class ExplodedModuleReader extends BaseModuleReader {
    private final Path dir;

    ExplodedModuleReader(ModuleArtifact artifact) {
        dir = Paths.get(artifact.location());
    }

    @Override
    public URL findResource(String mn, String name) {
        Path path = dir.resolve(name.replace('/', File.separatorChar));
        if (Files.exists(path))
            return toFileURL(path);
        return null;
    }

    @Override
    public byte[] readResource(String mn, String name) throws IOException {
        Path path = dir.resolve(name.replace('/', File.separatorChar));
        return Files.readAllBytes(path);
    }

    @Override
    public URL codeSourceLocation(String mn) {
        Path path = dir.resolve(mn);
        return toFileURL(path);
    }
}

