/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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
package jdk.internal.jrtfs;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.FileSystemException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import static java.util.stream.Collectors.toList;
import jdk.internal.jimage.ImageReader;
import jdk.internal.jimage.ImageReader.Node;
import jdk.internal.jimage.UTF8String;

/**
 * jrt file system implementation built on System jimage files.
 */
class JrtFileSystem extends AbstractJrtFileSystem {

    // System image reader
    private ImageReader bootImage;
    // root path
    private final JrtPath rootPath;
    private volatile boolean isOpen;

    // open a .jimage and build directory structure
    private static ImageReader openImage(Path path) throws IOException {
        ImageReader image = ImageReader.open(path.toString());
        image.getRootDirectory();
        return image;
    }

    JrtFileSystem(JrtFileSystemProvider provider,
            Map<String, ?> env)
            throws IOException {
        super(provider, env);
        checkExists(SystemImages.bootImagePath);

        // open image file
        this.bootImage = openImage(SystemImages.bootImagePath);

        byte[] root = new byte[]{'/'};
        rootPath = new JrtPath(this, root);
        isOpen = true;
    }

    // FileSystem method implementations
    @Override
    public boolean isOpen() {
        return isOpen;
    }

    @Override
    public void close() throws IOException {
        cleanup();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            cleanup();
        } catch (IOException ignored) {
        }
        super.finalize();
    }

    // AbstractJrtFileSystem method implementations
    @Override
    JrtPath getRootPath() {
        return rootPath;
    }

    @Override
    boolean isSameFile(AbstractJrtPath p1, AbstractJrtPath p2) throws IOException {
        ensureOpen();
        NodeAndImage n1 = findNode(p1.getName());
        NodeAndImage n2 = findNode(p2.getName());
        return n1.node.equals(n2.node);
    }

    @Override
    boolean isLink(AbstractJrtPath jrtPath) throws IOException {
        return checkNode(jrtPath.getName()).node.isLink();
    }

    @Override
    AbstractJrtPath resolveLink(AbstractJrtPath jrtPath) throws IOException {
        NodeAndImage ni = checkNode(jrtPath.getName());
        if (ni.node.isLink()) {
            Node node = ni.node.resolveLink();
            return toJrtPath(node.getName().getBytesCopy());
        }

        return jrtPath;
    }

    @Override
    JrtFileAttributes getFileAttributes(byte[] path, LinkOption... options)
            throws IOException {
        NodeAndImage ni = checkNode(path);
        if (ni.node.isLink() && followLinks(options)) {
            return new JrtFileAttributes(ni.node.resolveLink(true));
        }
        return new JrtFileAttributes(ni.node);
    }

    @Override
    boolean exists(byte[] path) throws IOException {
        try {
            checkNode(path);
        } catch (NoSuchFileException exp) {
            return false;
        }
        return true;
    }

    @Override
    boolean isDirectory(byte[] path, boolean resolveLinks)
            throws IOException {
        NodeAndImage ni = checkNode(path);
        return resolveLinks && ni.node.isLink()
                ? ni.node.resolveLink(true).isDirectory()
                : ni.node.isDirectory();
    }

    @Override
    Iterator<Path> iteratorOf(byte[] path, String childPrefix)
            throws IOException {
        NodeAndImage ni = checkNode(path);
        Node node = ni.node.resolveLink(true);

        if (!node.isDirectory()) {
            throw new NotDirectoryException(getString(path));
        }

        if (node.isRootDir()) {
            return rootDirIterator(path, childPrefix);
        } else if (node.isModulesDir()) {
            return modulesDirIterator(path, childPrefix);
        } else if (node.isPackagesDir()) {
            return packagesDirIterator(path, childPrefix);
        }

        return nodesToIterator(toJrtPath(path), childPrefix, node.getChildren());
    }

    @Override
    byte[] getFileContent(byte[] path) throws IOException {
        final NodeAndImage ni = checkResource(path);
        return ni.getResource();
    }

    // Implementation details below this point
    // clean up this file system - called from finalize and close
    private void cleanup() throws IOException {
        if (!isOpen) {
            return;
        }

        synchronized (this) {
            isOpen = false;

            // close all image reader and null out
            bootImage.close();
            bootImage = null;
        }
    }

    private static class NodeAndImage {

        final Node node;
        final ImageReader image;

        NodeAndImage(Node node, ImageReader image) {
            this.node = node;
            this.image = image;
        }

        byte[] getResource() throws IOException {
            return image.getResource(node);
        }
    }

    private NodeAndImage lookup(byte[] path) {
        ImageReader image = bootImage;
        Node node;
        try {
            node = bootImage.findNode(path);
        } catch (RuntimeException re) {
            throw new InvalidPathException(getString(path), re.toString());
        }
        return node != null ? new NodeAndImage(node, image) : null;
    }

    private NodeAndImage lookupSymbolic(byte[] path) {
        for (int i = 1; i < path.length; i++) {
            if (path[i] == (byte) '/') {
                byte[] prefix = Arrays.copyOfRange(path, 0, i);
                NodeAndImage ni = lookup(prefix);
                if (ni == null) {
                    break;
                }

                if (ni.node.isLink()) {
                    Node link = ni.node.resolveLink(true);
                    // resolved symbolic path concatenated to the rest of the path
                    UTF8String resPath = link.getName().concat(new UTF8String(path, i));
                    byte[] resPathBytes = resPath.getBytesCopy();
                    ni = lookup(resPathBytes);
                    return ni != null ? ni : lookupSymbolic(resPathBytes);
                }
            }
        }

        return null;
    }

    private NodeAndImage findNode(byte[] path) throws IOException {
        NodeAndImage ni = lookup(path);
        if (ni == null) {
            ni = lookupSymbolic(path);
            if (ni == null) {
                throw new NoSuchFileException(getString(path));
            }
        }
        return ni;
    }

    private NodeAndImage checkNode(byte[] path) throws IOException {
        ensureOpen();
        return findNode(path);
    }

    private NodeAndImage checkResource(byte[] path) throws IOException {
        NodeAndImage ni = checkNode(path);
        if (ni.node.isDirectory()) {
            throw new FileSystemException(getString(path) + " is a directory");
        }

        assert ni.node.isResource() : "resource node expected here";
        return ni;
    }

    private JrtPath toJrtPath(String path) {
        return toJrtPath(getBytes(path));
    }

    private JrtPath toJrtPath(byte[] path) {
        return new JrtPath(this, path);
    }

    private Iterator<Path> nodesToIterator(Path path, String childPrefix, List<Node> childNodes) {
        Function<Node, Path> f = childPrefix == null
                ? child -> toJrtPath(child.getNameString())
                : child -> toJrtPath(childPrefix + child.getNameString().substring(1));
        return childNodes.stream().map(f).collect(toList()).iterator();
    }

    private List<Node> rootChildren;

    private synchronized void initRootChildren(byte[] path) {
        if (rootChildren == null) {
            rootChildren = new ArrayList<>();
            rootChildren.addAll(bootImage.findNode(path).getChildren());
        }
    }

    private Iterator<Path> rootDirIterator(byte[] path, String childPrefix) throws IOException {
        initRootChildren(path);
        return nodesToIterator(rootPath, childPrefix, rootChildren);
    }

    private List<Node> modulesChildren;

    private synchronized void initModulesChildren(byte[] path) {
        if (modulesChildren == null) {
            modulesChildren = new ArrayList<>();
            modulesChildren.addAll(bootImage.findNode(path).getChildren());
        }
    }

    private Iterator<Path> modulesDirIterator(byte[] path, String childPrefix) throws IOException {
        initModulesChildren(path);
        return nodesToIterator(new JrtPath(this, path), childPrefix, modulesChildren);
    }

    private List<Node> packagesChildren;

    private synchronized void initPackagesChildren(byte[] path) {
        if (packagesChildren == null) {
            packagesChildren = new ArrayList<>();
            packagesChildren.addAll(bootImage.findNode(path).getChildren());
        }
    }

    private Iterator<Path> packagesDirIterator(byte[] path, String childPrefix) throws IOException {
        initPackagesChildren(path);
        return nodesToIterator(new JrtPath(this, path), childPrefix, packagesChildren);
    }
}
