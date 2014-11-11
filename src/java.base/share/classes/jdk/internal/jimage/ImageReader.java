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
package jdk.internal.jimage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class ImageReader extends BasicImageReader {
    // well-known strings needed for image file system.
    static final UTF8String ROOT_STRING   = new UTF8String("/");
    static final UTF8String META_INF_STRING = new UTF8String("META-INF");
    static final UTF8String PKG_MAP_STRING = new UTF8String("META-INF/package-to-module.properties");

    // attributes of the .jimage file. jimage file does not contain
    // attributes for the individual resources (yet). We use attributes
    // of the jimage file itself (creation, modification, access times).
    // Iniitalized lazily, see {@link #imageFileAttributes()}.
    private BasicFileAttributes imageFileAttributes;

    private final Map<String, String> packageMap;

    // directory management implementation
    private final Map<UTF8String, Node> nodes;
    private volatile Directory rootDir;

    ImageReader(String imagePath, ByteOrder byteOrder) throws IOException {
        super(imagePath, byteOrder);
        this.packageMap = PackageModuleMap.readFrom(this);
        this.nodes = Collections.synchronizedMap(new HashMap<>());
    }

    ImageReader(String imagePath) throws IOException {
        this(imagePath, ByteOrder.nativeOrder());
    }

    public static ImageReader open(String imagePath, ByteOrder byteOrder) throws IOException {
        return new ImageReader(imagePath, byteOrder);
    }

    /**
     * Opens the given file path as an image file, returning an {@code ImageReader}.
     */
    public static ImageReader open(String imagePath) throws IOException {
        return open(imagePath, ByteOrder.nativeOrder());
    }

    @Override
    public synchronized void close() throws IOException {
        super.close();
        clearNodes();
    }

    /**
     * Return the module name that contains the given package name.
     */
    public String getModule(String pkg) {
        return packageMap.get(pkg);
    }

     // jimage file does not store directory structure. We build nodes
    // using the "path" strings found in the jimage file.
    // Node can be a directory or a resource
    public static abstract class Node {

        private final UTF8String name;
        private final BasicFileAttributes fileAttrs;

        Node(UTF8String name, BasicFileAttributes fileAttrs) {
            assert name != null;
            assert fileAttrs != null;
            this.name = name;
            this.fileAttrs = fileAttrs;
        }

        public final UTF8String getName() {
            return name;
        }

        public boolean isDirectory() {
            return false;
        }

        public long size() {
            return 0L;
        }

        public long compressedSize() {
            return 0L;
        }

        public String extension() {
            return null;
        }

        public long contentOffset() {
            return 0L;
        }

        public final FileTime creationTime() {
            return fileAttrs.creationTime();
        }

        public final FileTime lastAccessTime() {
            return fileAttrs.lastAccessTime();
        }

        public final FileTime lastModifiedTime() {
            return fileAttrs.lastModifiedTime();
        }

        public final String getNameString() {
            return name.toString();
        }

        @Override
        public final String toString() {
            return getNameString();
        }

        @Override
        public final int hashCode() {
            return name.hashCode();
        }

        @Override
        public final boolean equals(Object other) {
            if (this == other) {
                return true;
            }

            if (other instanceof Node) {
                return name.equals(((Node) other).name);
            }

            return false;
        }
    }

    // directory node - directory has full path name and no trailing "/"
    public static final class Directory extends Node {

        private final List<Node> children;

        @SuppressWarnings("LeakingThisInConstructor")
        Directory(Directory parent, UTF8String name, BasicFileAttributes fileAttrs) {
            super(name, fileAttrs);
            children = new ArrayList<>();
            if (parent != null) {
                parent.addChild(this);
            }
        }

        @Override
        public boolean isDirectory() {
            return true;
        }

        public List<Node> getChildren() {
            return Collections.unmodifiableList(children);
        }

        private void addChild(Node node) {
            children.add(node);
        }
    }

    // "resource" is .class or any other resource (compressed/uncompressed) in a jimage.
    // full path of the resource is the "name" of the resource.
    public static class Resource extends Node {

        private final ImageLocation loc;

        @SuppressWarnings("LeakingThisInConstructor")
        Resource(Directory parent, ImageLocation loc, BasicFileAttributes fileAttrs) {
            super(loc.getFullname(), fileAttrs);
            this.loc = loc;
            parent.addChild(this);
        }

        @SuppressWarnings("LeakingThisInConstructor")
        Resource(Directory parent, UTF8String name, BasicFileAttributes fileAttrs) {
            super(name, fileAttrs);
            this.loc = null;
            parent.addChild(this);
        }

        public final ImageLocation getLocation() {
            return loc;
        }

        @Override
        public long size() {
            return loc.getUncompressedSize();
        }

        @Override
        public long compressedSize() {
            return loc.getCompressedSize();
        }

        @Override
        public String extension() {
            return loc.getExtensionString();
        }

        @Override
        public long contentOffset() {
            return loc.getContentOffset();
        }
    }

    // a resource that is not in .jimage file - but has lazily computed data
    public static final class ComputedResource extends Resource {
        // function that supplies the data
        private final Supplier<byte[]> supplier;
        // lazily initialized data
        private volatile byte[] buf;

        ComputedResource(Directory parent, UTF8String name,
                BasicFileAttributes fileAttrs, Supplier<byte[]> func) {
            super(parent, name, fileAttrs);
            func.getClass();
            this.supplier = func;
        }

        @Override
        public long size() {
            return getData().length;
        }

        @Override
        public long compressedSize() {
            return 0L;
        }

        @Override
        public String extension() {
            return "";
        }

        @Override
        public long contentOffset() {
            return 0L;
        }

        public byte[] getData() {
            if (buf == null) {
                buf = supplier.get();
            }

            return buf;
        }
    }

    // directory management interface
    public Directory getRootDirectory() {
        return buildRootDirectory();
    }

    public Node findNode(String name) {
        return findNode(new UTF8String(name));
    }

    public Node findNode(byte[] name) {
        return findNode(new UTF8String(name));
    }

    public synchronized Node findNode(UTF8String name) {
        buildRootDirectory();
        return nodes.get(name);
    }

    private synchronized void clearNodes() {
        nodes.clear();
        rootDir = null;
    }

    /**
     * Returns the file attributes of the image file.
     */
    private BasicFileAttributes imageFileAttributes() {
        BasicFileAttributes attrs = imageFileAttributes;
        if (attrs == null) {
            try {
                Path file = Paths.get(imagePath());
                attrs = Files.readAttributes(file, BasicFileAttributes.class);
            } catch (IOException ioe) {
                throw new UncheckedIOException(ioe);
            }
            imageFileAttributes = attrs;
        }
        return attrs;
    }

    private synchronized Directory buildRootDirectory() {
        if (rootDir != null) {
            return rootDir;
        }

        // FIXME no time information per resource in jimage file (yet?)
        // we use file attributes of jimage itself.
        // root directory
        rootDir = new Directory(null, ROOT_STRING, imageFileAttributes());
        nodes.put(rootDir.getName(), rootDir);
        nodes.put(UTF8String.EMPTY_STRING, rootDir);

        ImageLocation[] locs = getAllLocations(true);
        for (ImageLocation loc : locs) {
            UTF8String parent = loc.getParent();
            // directory where this location goes as child
            Directory dir;
            if (parent == null || parent.isEmpty()) {
                // top level entry under root
                dir = rootDir;
            } else {
                int idx = parent.lastIndexOf('/');
                assert idx != -1 : "invalid parent string";
                UTF8String name = parent.substring(0, idx);
                dir = (Directory) nodes.get(name);
                if (dir == null) {
                    // make all parent directories (as needed)
                    dir = makeDirectories(parent);
                }
            }
            Resource entry = new Resource(dir, loc, imageFileAttributes());
            nodes.put(entry.getName(), entry);
        }

        fillPackageModuleInfo();

        return rootDir;
    }

    private Directory newDirectory(Directory parent, UTF8String name) {
        Directory dir = new Directory(parent, name, imageFileAttributes());
        nodes.put(dir.getName(), dir);
        return dir;
    }

    private Directory makeDirectories(UTF8String parent) {
        assert !parent.isEmpty() : "non empty parent expected";

        int idx = parent.indexOf('/');
        assert idx != -1 : "invalid parent string";
        UTF8String name = parent.substring(0, idx);
        Directory top = (Directory) nodes.get(name);
        if (top == null) {
            top = newDirectory(rootDir, name);
            // support trailing '/' directory name as well
            nodes.put(parent.substring(0, idx+1), top);
        }
        Directory last = top;
        while ((idx = parent.indexOf('/', idx + 1)) != -1) {
            name = parent.substring(0, idx);
            Directory nextDir = (Directory) nodes.get(name);
            if (nextDir == null) {
                nextDir = newDirectory(last, name);
                // support trailing '/' directory name as well
                nodes.put(parent.substring(0, idx+1), nextDir);
            }
            last = nextDir;
        }

        return last;
    }

    // fill META-INF/package-to-module.properties resource entry
    private void fillPackageModuleInfo() {
        assert rootDir != null;

        // find or create /META-INF directory
        Directory metaInfDir = null;
        if (! nodes.containsKey(META_INF_STRING)) {
            metaInfDir = makeDirectories(META_INF_STRING);
            nodes.put(metaInfDir.getName(), metaInfDir);
        } else {
            Node node = nodes.get(META_INF_STRING);
            if (node.isDirectory()) {
                metaInfDir = (Directory)node;
            }
        }

        // put "package-to-module.properties under it
        if (metaInfDir != null) {
            Resource rs = new ComputedResource(
                    metaInfDir, PKG_MAP_STRING,
                    imageFileAttributes(), this::makePackageMapData);
            nodes.put(rs.getName(), rs);
        }
    }

    // lazily create computed resource data for package-to-module.properties
    private byte[] makePackageMapData() {
        StringBuilder buf = new StringBuilder();
        packageMap.entrySet().stream().map((entry) -> {
            buf.append(entry.getKey());
            return entry;
        }).map((entry) -> {
            buf.append('=');
            buf.append(entry.getValue());
            return entry;
        }).forEach((_item) -> {
            buf.append('\n');
        });

        return buf.toString().getBytes(UTF8String.UTF_8);
    }

    public byte[] getResource(Resource rs) throws IOException {
        return rs instanceof ComputedResource?
            ((ComputedResource)rs).getData() : super.getResource(rs.getLocation());
    }
}
