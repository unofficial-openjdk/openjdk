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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessMode;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import jdk.internal.jimage.ImageReader;
import jdk.internal.jimage.ImageReader.Directory;
import jdk.internal.jimage.ImageReader.Node;
import jdk.internal.jimage.ImageReader.Resource;
import jdk.internal.jimage.UTF8String;

/**
 * A FileSystem built on a jimage file
 */
final class ImageFileSystem extends FileSystem {

    private final ImageFileSystemProvider provider;
    private final Path imagePath;
    private final ImageReader jimage;
    private final ImagePath rootPath;
    private volatile boolean isOpen;

    ImageFileSystem(ImageFileSystemProvider provider,
            Path imagePath,
            Map<String, ?> env)
            throws IOException {
        this.provider = provider;
        this.imagePath = imagePath;
        if (Files.notExists(imagePath)) {
            throw new FileSystemNotFoundException(imagePath.toString());
        }
        // open image file
        this.jimage = ImageReader.open(imagePath.toString());
        // build directory structure
        jimage.getRootDirectory();
        rootPath = new ImagePath(this, new byte[]{'/'});
        isOpen = true;
    }

    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public boolean isOpen() {
        return isOpen;
    }

    private void ensureOpen() throws IOException {
        if (!isOpen) {
            throw new ClosedFileSystemException();
        }
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    private ReadOnlyFileSystemException readOnly() {
        return new ReadOnlyFileSystemException();
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        ArrayList<Path> pathArr = new ArrayList<>();
        pathArr.add(rootPath);
        return pathArr;
    }

    ImagePath getRootPath() {
        return rootPath;
    }

    @Override
    public ImagePath getPath(String first, String... more) {
        String path;
        if (more.length == 0) {
            path = first;
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(first);
            for (String segment : more) {
                if (segment.length() > 0) {
                    if (sb.length() > 0) {
                        sb.append('/');
                    }
                    sb.append(segment);
                }
            }
            path = sb.toString();
        }
        return new ImagePath(this, getBytes(path));
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException();
    }

    FileStore getFileStore(ImagePath path) {
        return new ImageFileStore(path);
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        ArrayList<FileStore> list = new ArrayList<>(1);
        list.add(new ImageFileStore(new ImagePath(this, new byte[]{'/'})));
        return list;
    }

    private static final Set<String> supportedFileAttributeViews
            = Collections.unmodifiableSet(
                    new HashSet<String>(Arrays.asList("basic", "jimage")));

    @Override
    public Set<String> supportedFileAttributeViews() {
        return supportedFileAttributeViews;
    }

    @Override
    public String toString() {
        return imagePath.toString();
    }

    Path getImageFile() {
        return imagePath;
    }

    private static final String GLOB_SYNTAX = "glob";
    private static final String REGEX_SYNTAX = "regex";

    @Override
    public PathMatcher getPathMatcher(String syntaxAndInput) {
        int pos = syntaxAndInput.indexOf(':');
        if (pos <= 0 || pos == syntaxAndInput.length()) {
            throw new IllegalArgumentException();
        }
        String syntax = syntaxAndInput.substring(0, pos);
        String input = syntaxAndInput.substring(pos + 1);
        String expr;
        if (syntax.equals(GLOB_SYNTAX)) {
            expr = ImageUtils.toRegexPattern(input);
        } else {
            if (syntax.equals(REGEX_SYNTAX)) {
                expr = input;
            } else {
                throw new UnsupportedOperationException("Syntax '" + syntax
                        + "' not recognized");
            }
        }
        // return matcher
        final Pattern pattern = Pattern.compile(expr);
        return (Path path) -> pattern.matcher(path.toString()).matches();
    }

    @Override
    public void close() throws IOException {
        if (!isOpen) {
            return;
        }

        synchronized(this) {
            jimage.close();
            isOpen = false;
        }

        IOException ioe = null;
        // clear all temp files created for FileChannels.
        synchronized (tmppaths) {
            for (Path p: tmppaths) {
                try {
                    AccessController.doPrivileged(
                        (PrivilegedExceptionAction<Boolean>)() -> Files.deleteIfExists(p));
                } catch (PrivilegedActionException e) {
                    IOException x = (IOException)e.getException();
                    if (ioe == null)
                        ioe = x;
                    else
                        ioe.addSuppressed(x);
                }
            }
        }
        provider.removeFileSystem(imagePath, this);
        if (ioe != null)
           throw ioe;
    }

    @Override
    protected void finalize() throws Throwable {
        close();
        super.finalize();
    }

    final byte[] getBytes(String name) {
        return name.getBytes(StandardCharsets.UTF_8);
    }

    final String getString(byte[] name) {
        return new String(name, StandardCharsets.UTF_8);
    }

    private Node checkNode(byte[] path) throws IOException {
        ensureOpen();
        Node node = jimage.findNode(path);
        if (node == null) {
            // try removing leading '/' and check
            if (path.length != 0 && path[0] == '/') {
                node = jimage.findNode(new UTF8String(path, 1));
            }
        }
        if (node == null) {
            throw new NoSuchFileException(getString(path));
        }
        return node;
    }

    private Resource checkResource(byte[] path) throws IOException {
        Node node = checkNode(path);
        if (node.isDirectory()) {
            throw new FileSystemException(getString(path) + " is a directory");
        }
        return (Resource) node;
    }

    // package private helpers
    ImageFileAttributes getFileAttributes(byte[] path)
            throws IOException {
        Node node = checkNode(path);
        return new ImageFileAttributes(node);
    }

    void setTimes(byte[] path, FileTime mtime, FileTime atime, FileTime ctime)
            throws IOException {
        throw readOnly();
    }

    boolean exists(byte[] path)
            throws IOException {
        ensureOpen();
        return jimage.findNode(path) != null;
    }

    boolean isDirectory(byte[] path)
            throws IOException {
        ensureOpen();
        Node node = checkNode(path);
        return node.isDirectory();
    }

    ImagePath toImagePath(String path) {
        return toImagePath(getBytes(path));
    }

    ImagePath toImagePath(byte[] path) {
        return new ImagePath(this, path);
    }

    // returns the list of child paths of "path"
    Iterator<Path> iteratorOf(byte[] path,
            DirectoryStream.Filter<? super Path> filter)
            throws IOException {
        Node node = checkNode(path);
        if (!node.isDirectory()) {
            throw new NotDirectoryException(getString(path));
        }

        Directory dir = (Directory) node;
        List<Node> childNodes = dir.getChildren();
        List<Path> childPaths = new ArrayList<>(childNodes.size());
        childNodes.stream().forEach((child) -> {
            childPaths.add(toImagePath(child.getNameString()));
        });

        return childPaths.iterator();
    }

    void createDirectory(byte[] dir, FileAttribute<?>... attrs)
            throws IOException {
        throw readOnly();
    }

    void copyFile(boolean deletesrc, byte[] src, byte[] dst, CopyOption... options)
            throws IOException {
        throw readOnly();
    }

    public void deleteFile(byte[] path, boolean failIfNotExists)
            throws IOException {
        throw readOnly();
    }

    OutputStream newOutputStream(byte[] path, OpenOption... options)
            throws IOException {
        throw readOnly();
    }

    private void checkOptions(Set<? extends OpenOption> options) {
        // check for options of null type and option is an intance of StandardOpenOption
        for (OpenOption option : options) {
            if (option == null) {
                throw new NullPointerException();
            }
            if (!(option instanceof StandardOpenOption)) {
                throw new IllegalArgumentException();
            }
        }
    }

    // Returns an input stream for reading the contents of the specified
    // file entry.
    InputStream newInputStream(byte[] path) throws IOException {
        final Resource rs = checkResource(path);
        return new ByteArrayInputStream(jimage.getResource(rs));
    }

    private final Set<Path> tmppaths = Collections.synchronizedSet(new HashSet<Path>());

    // Creates a new empty temporary file in the same directory as the
    // specified file.  A variant of Files.createTempFile.
    private Path createTempFileInSameDirectoryAs(Path path)
            throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        Path dir = (parent == null) ? path.getFileSystem().getPath(".") : parent;
        Path tmpPath = Files.createTempFile(dir, "imagefstmp", null);
        tmppaths.add(tmpPath);
        return tmpPath;
    }

    private Path getTempPathForEntry(byte[] path) throws IOException {
        ensureOpen();
        Path tmpPath = createTempFileInSameDirectoryAs(imagePath);
        if (path != null) {
            Resource rs = checkResource(path);
            try (InputStream is = newInputStream(path)) {
                Files.copy(is, tmpPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return tmpPath;
    }

    private void removeTempPathForEntry(Path path) throws IOException {
        Files.delete(path);
        tmppaths.remove(path);
    }

    SeekableByteChannel newByteChannel(byte[] path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attrs)
            throws IOException {
        checkOptions(options);
        if (options.contains(StandardOpenOption.WRITE)
                || options.contains(StandardOpenOption.APPEND)) {
            throw readOnly();
        }

        Resource rs = checkResource(path);
        byte[] buf = jimage.getResource(rs);
        final ReadableByteChannel rbc
                = Channels.newChannel(new ByteArrayInputStream(buf));
        final long size = buf.length;
        return new SeekableByteChannel() {
            long read = 0;

            @Override
            public boolean isOpen() {
                return rbc.isOpen();
            }

            @Override
            public long position() throws IOException {
                return read;
            }

            @Override
            public SeekableByteChannel position(long pos)
                    throws IOException {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read(ByteBuffer dst) throws IOException {
                int n = rbc.read(dst);
                if (n > 0) {
                    read += n;
                }
                return n;
            }

            @Override
            public SeekableByteChannel truncate(long size)
                    throws IOException {
                throw new NonWritableChannelException();
            }

            @Override
            public int write(ByteBuffer src) throws IOException {
                throw new NonWritableChannelException();
            }

            @Override
            public long size() throws IOException {
                return size;
            }

            @Override
            public void close() throws IOException {
                rbc.close();
            }
        };
    }

    // Returns a FileChannel of the specified path.
    //
    // This implementation creates a temporary file on the default file system,
    // copy the entry data into it if the entry exists, and then create a
    // FileChannel on top of it.
    FileChannel newFileChannel(byte[] path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attrs)
            throws IOException {
        checkOptions(options);
        if (options.contains(StandardOpenOption.WRITE)
            || options.contains(StandardOpenOption.APPEND)) {
            throw readOnly();
        }

        Resource rs = checkResource(path);
        final Path tmpfile = getTempPathForEntry(path);
        final FileChannel fch = tmpfile.getFileSystem()
                .provider()
                .newFileChannel(tmpfile, options, attrs);

        return new FileChannel() {
            @Override
            public int write(ByteBuffer src) throws IOException {
                return fch.write(src);
            }

            @Override
            public long write(ByteBuffer[] srcs, int offset, int length)
                    throws IOException {
                return fch.write(srcs, offset, length);
            }

            @Override
            public long position() throws IOException {
                return fch.position();
            }

            @Override
            public FileChannel position(long newPosition)
                    throws IOException {
                fch.position(newPosition);
                return this;
            }

            @Override
            public long size() throws IOException {
                return fch.size();
            }

            @Override
            public FileChannel truncate(long size)
                    throws IOException {
                fch.truncate(size);
                return this;
            }

            @Override
            public void force(boolean metaData)
                    throws IOException {
                fch.force(metaData);
            }

            @Override
            public long transferTo(long position, long count,
                    WritableByteChannel target)
                    throws IOException {
                return fch.transferTo(position, count, target);
            }

            @Override
            public long transferFrom(ReadableByteChannel src,
                    long position, long count)
                    throws IOException {
                return fch.transferFrom(src, position, count);
            }

            @Override
            public int read(ByteBuffer dst) throws IOException {
                return fch.read(dst);
            }

            @Override
            public int read(ByteBuffer dst, long position)
                    throws IOException {
                return fch.read(dst, position);
            }

            @Override
            public long read(ByteBuffer[] dsts, int offset, int length)
                    throws IOException {
                return fch.read(dsts, offset, length);
            }

            @Override
            public int write(ByteBuffer src, long position)
                    throws IOException {
                return fch.write(src, position);
            }

            @Override
            public MappedByteBuffer map(MapMode mode,
                    long position, long size)
                    throws IOException {
                throw new UnsupportedOperationException();
            }

            @Override
            public FileLock lock(long position, long size, boolean shared)
                    throws IOException {
                return fch.lock(position, size, shared);
            }

            @Override
            public FileLock tryLock(long position, long size, boolean shared)
                    throws IOException {
                return fch.tryLock(position, size, shared);
            }

            @Override
            protected void implCloseChannel() throws IOException {
                fch.close();
                removeTempPathForEntry(tmpfile);
            }
        };
    }
}
