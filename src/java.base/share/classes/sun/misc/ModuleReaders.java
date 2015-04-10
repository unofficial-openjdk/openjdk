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
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
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
 * A factory for module readers. The {@link #open open} method defined here
 * creates a {@code ModuleReader} for modules packaged as a jmod, modular JAR,
 * or exploded on the file system.
 */

class ModuleReaders {
    private ModuleReaders() { }

    /**
     * Creates a ModuleReader to access the given ModuleArtifact.
     */
    static ModuleReader open(ModuleArtifact artifact) throws IOException {
        String scheme = artifact.location().getScheme();

        if (scheme.equalsIgnoreCase("jmod"))
            return new JModModuleReader(artifact);
        if (scheme.equalsIgnoreCase("jar"))
            return new JarModuleReader(artifact);
        if (scheme.equalsIgnoreCase("file"))
            return new ExplodedModuleReader(artifact);

        throw new InternalError("No module reader for: " + scheme);
    }
}

/**
 * A base ModuleReader implementation
 */
abstract class BaseModuleReader implements ModuleReader {
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
 * A ModuleReader for a jmod file.
 */
class JModModuleReader extends BaseModuleReader {
    private String module;
    private final URL baseURL;
    private final ZipFile zf;

    JModModuleReader(ModuleArtifact artifact) throws IOException {
        module = artifact.descriptor().name();
        baseURL = artifact.location().toURL();
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
class JarModuleReader extends BaseModuleReader {
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
class ExplodedModuleReader extends BaseModuleReader {
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

