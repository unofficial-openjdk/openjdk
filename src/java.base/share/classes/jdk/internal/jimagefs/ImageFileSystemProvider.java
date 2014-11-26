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

package jdk.internal.jimagefs;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

public final class ImageFileSystemProvider extends FileSystemProvider {
    private final Map<Path, ImageFileSystem> filesystems = new HashMap<>();

    public ImageFileSystemProvider() {}

    @Override
    public String getScheme() {
        return "jimage";
    }

    protected Path uriToPath(URI uri) {
        String scheme = uri.getScheme();
        if ((scheme == null) || !scheme.equalsIgnoreCase(getScheme())) {
            throw new IllegalArgumentException("URI scheme is not '" + getScheme() + "'");
        }
        try {
            // only support jimage URL syntax  jimage:{uri}!/{entry} for now
            String spec = uri.getRawSchemeSpecificPart();
            int sep = spec.indexOf("!/");
            if (sep != -1)
                spec = spec.substring(0, sep);
            return Paths.get(new URI(spec)).toAbsolutePath();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private boolean ensureFile(Path path) {
        try {
            BasicFileAttributes attrs =
                Files.readAttributes(path, BasicFileAttributes.class);
            if (!attrs.isRegularFile())
                throw new UnsupportedOperationException();
            return true;
        } catch (IOException ioe) {
            return false;
        }
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env)
        throws IOException
    {
        Path path = uriToPath(uri);
        synchronized(filesystems) {
            Path realPath = null;
            if (ensureFile(path)) {
                realPath = path.toRealPath();
                if (filesystems.containsKey(realPath))
                    throw new FileSystemAlreadyExistsException();
            }
            ImageFileSystem imagefs = new ImageFileSystem(this, path, env);
            filesystems.put(realPath, imagefs);
            return imagefs;
        }
    }

    @Override
    public FileSystem newFileSystem(Path path, Map<String, ?> env)
        throws IOException
    {
        if (!path.toString().endsWith(".jimage") || !Files.isRegularFile(path) ||
            (path.getFileSystem() != FileSystems.getDefault())) {
            throw new UnsupportedOperationException();
        }
        return new ImageFileSystem(this, path, env);
    }

    @Override
    public Path getPath(URI uri) {

        String spec = uri.getSchemeSpecificPart();
        int sep = spec.indexOf("!/");
        if (sep == -1)
            throw new IllegalArgumentException("URI: "
                + uri
                + " does not contain path info ex. jimage:file:/c:/foo.jimage!/BAR");
        return getFileSystem(uri).getPath(spec.substring(sep + 1));
    }


    @Override
    public FileSystem getFileSystem(URI uri) {
        synchronized (filesystems) {
            ImageFileSystem imagefs = null;
            try {
                imagefs = filesystems.get(uriToPath(uri).toRealPath());
            } catch (IOException x) {
                // ignore the ioe from toRealPath(), return FSNFE
            }
            if (imagefs == null)
                throw new FileSystemNotFoundException();
            return imagefs;
        }
    }

    // Checks that the given file is a ImagePath
    static final ImagePath toImagePath(Path path) {
        if (path == null)
            throw new NullPointerException();
        if (!(path instanceof ImagePath))
            throw new ProviderMismatchException();
        return (ImagePath)path;
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        toImagePath(path).checkAccess(modes);
    }

    @Override
    public void copy(Path src, Path target, CopyOption... options)
        throws IOException
    {
        toImagePath(src).copy(toImagePath(target), options);
    }

    @Override
    public void createDirectory(Path path, FileAttribute<?>... attrs)
        throws IOException
    {
        toImagePath(path).createDirectory(attrs);
    }

    @Override
    public final void delete(Path path) throws IOException {
        toImagePath(path).delete();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V extends FileAttributeView> V
        getFileAttributeView(Path path, Class<V> type, LinkOption... options)
    {
        return ImageFileAttributeView.get(toImagePath(path), type);
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
        return toImagePath(path).getFileStore();
    }

    @Override
    public boolean isHidden(Path path) {
        return toImagePath(path).isHidden();
    }

    @Override
    public boolean isSameFile(Path path, Path other) throws IOException {
        return toImagePath(path).isSameFile(other);
    }

    @Override
    public void move(Path src, Path target, CopyOption... options)
        throws IOException
    {
        toImagePath(src).move(toImagePath(target), options);
    }

    @Override
    public AsynchronousFileChannel newAsynchronousFileChannel(Path path,
            Set<? extends OpenOption> options,
            ExecutorService exec,
            FileAttribute<?>... attrs)
            throws IOException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path,
                                              Set<? extends OpenOption> options,
                                              FileAttribute<?>... attrs)
        throws IOException
    {
        return toImagePath(path).newByteChannel(options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(
        Path path, Filter<? super Path> filter) throws IOException
    {
        return toImagePath(path).newDirectoryStream(filter);
    }

    @Override
    public FileChannel newFileChannel(Path path,
                                      Set<? extends OpenOption> options,
                                      FileAttribute<?>... attrs)
        throws IOException
    {
        return toImagePath(path).newFileChannel(options, attrs);
    }

    @Override
    public InputStream newInputStream(Path path, OpenOption... options)
        throws IOException
    {
        return toImagePath(path).newInputStream(options);
    }

    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options)
        throws IOException
    {
        return toImagePath(path).newOutputStream(options);
    }

    @Override
    @SuppressWarnings("unchecked") // Cast to A
    public <A extends BasicFileAttributes> A
        readAttributes(Path path, Class<A> type, LinkOption... options)
        throws IOException
    {
        if (type == BasicFileAttributes.class || type == ImageFileAttributes.class)
            return (A)toImagePath(path).getAttributes();
        return null;
    }

    @Override
    public Map<String, Object>
        readAttributes(Path path, String attribute, LinkOption... options)
        throws IOException
    {
        return toImagePath(path).readAttributes(attribute, options);
    }

    @Override
    public void setAttribute(Path path, String attribute,
                             Object value, LinkOption... options)
        throws IOException
    {
        toImagePath(path).setAttribute(attribute, value, options);
    }

    //////////////////////////////////////////////////////////////
    void removeFileSystem(Path ifpath, ImageFileSystem imagefs) throws IOException {
        synchronized (filesystems) {
            ifpath = ifpath.toRealPath();
            if (filesystems.get(ifpath) == imagefs)
                filesystems.remove(ifpath);
        }
    }
}
