/*
 * Copyright (c) 2012, 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.tools.classanalyzer;

import com.sun.tools.classfile.ClassFile;
import com.sun.tools.classfile.ConstantPoolException;
import com.sun.tools.classfile.Dependencies.ClassFileError;
import java.io.*;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * ClassFileReader reads ClassFile(s) of a given path that can be
 * a .class file, a directory, or a JAR file.
 */
public class ClassFileReader {
    /**
     * Returns a ClassFileReader instance of a given path.
     */
    public static ClassFileReader newInstance(File path) throws IOException {
        if (!path.exists()) {
            throw new FileNotFoundException(path.getAbsolutePath());
        }

        if (path.isDirectory()) {
            return new DirectoryReader(path.toPath());
        } else if (path.getName().endsWith(".jar")) {
            return new JarFileReader(path.toPath());
        } else {
            return new ClassFileReader(path.toPath());
        }
    }

    /**
     * Returns a ClassFileReader instance of a given JarFile.
     */
    public static ClassFileReader newInstance(Path path, JarFile jf) throws IOException {
        return new JarFileReader(path, jf);
    }

    protected final Path path;
    protected final String baseFileName;
    private ClassFileReader(Path path) {
        this.path = path;
        this.baseFileName = path.getFileName() != null
                                ? path.getFileName().toString()
                                : path.toString();
    }

    public String getFileName() {
        return baseFileName;
    }

    public Iterable<ClassFile> getClassFiles() throws IOException {
        return new Iterable<ClassFile>() {
            public Iterator<ClassFile> iterator() {
                return new FileIterator();
            }
        };
    }

    public Iterable<Resource> getResources() throws IOException {
        String fn = path.toFile().getName();
        if (Resource.isResource(fn)) {
            return Collections.singletonList(readResource(path));
        }
        return Collections.<Resource>emptyList();
    }

    public byte[] readBytes(String name) throws IOException {
        String n = path.toString().replace(File.separatorChar, '/');
        if (name.equals(n)) {
            return load(path.toFile());
        }
        return null;
    }

    private static byte[] load(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            return load(fis, f.length());
        }
    }

    private static byte[] load(InputStream is, long n) throws IOException {
        byte[] bs = new byte[(int)n];
        try (DataInputStream in = new DataInputStream(is)) {
            in.readFully(bs);
            return bs;
        }
    }

    private static ClassFile readClassFile(Path p) throws IOException {
        try (InputStream is = Files.newInputStream(p)) {
            return ClassFile.read(is);
        } catch (ConstantPoolException e) {
            throw new ClassFileError(e);
        }
    }

    private static Resource readResource(Path dir, Path p) throws IOException {
        String fn = dir.relativize(p).toString();
        return readResource(p, fn);
    }

    private static Resource readResource(Path p) throws IOException {
        return readResource(p, p.toString());
    }
    private static Resource readResource(Path p, String fn) throws IOException {
        if (Resource.isResource(fn)) {
            try (InputStream is = Files.newInputStream(p)) {
                return Resource.getResource(fn, is, p.toFile().length());
            }
        }
        if (p.toString().contains("META-INF")) {
            throw new RuntimeException(p + " not a resource");
        }
        return null;
    }

    class FileIterator implements Iterator<ClassFile> {
        int count;
        FileIterator() {
            this.count = 0;
        }
        public boolean hasNext() {
            return count == 0 && baseFileName.endsWith(".class");
        }

        public ClassFile next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            try {
                ClassFile cf = readClassFile(path);
                count++;
                return cf;
            } catch (IOException e) {
                throw new ClassFileError(e);
            }
        }

        public void remove() {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    }

    public String toString() {
        return path.toString();
    }

    private static class DirectoryReader extends ClassFileReader {
        final DirectoryIterator iter = new DirectoryIterator();
        DirectoryReader(Path path) throws IOException {
            super(path);
        }

        public Iterable<ClassFile> getClassFiles() throws IOException {
            iter.walkTree(path);
            return new Iterable<ClassFile>() {
                public Iterator<ClassFile> iterator() {
                    return iter;
                }
            };
        }

        public Iterable<Resource> getResources() throws IOException {
            iter.walkTree(path);
            if (iter.hasNext()) {
                throw new RuntimeException("getClassFile must be called first");
            }
            return iter.resources;
        }

        public byte[] readBytes(String name) throws IOException {
            String n = name.replace('/', File.separatorChar);
            Path p = path.resolve(n);
            if (Files.exists(p)) {
                return load(p.toFile());
            }
            return null;
        }

        class DirectoryIterator implements Iterator<ClassFile> {
            final List<Path> classFiles = new ArrayList<>();
            final List<Resource> resources = new ArrayList<>();
            private boolean inited = false;
            private int index = 0;
            DirectoryIterator() throws IOException {
                index = 0;
            }

            public boolean hasNext() {
                return index != classFiles.size();
            }

            public ClassFile next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Path path = classFiles.get(index++);
                try {
                    return readClassFile(path);
                } catch (IOException e) {
                    throw new ClassFileError(e);
                }
            }

            public void remove() {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            synchronized void walkTree(final Path dir) throws IOException {
                if (inited) return;

                inited = true;
                Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        String fn = dir.relativize(file).toString();
                        if (fn.endsWith(".class")) {
                            classFiles.add(file);
                        } else if (Resource.isResource(fn)) {
                            resources.add(readResource(dir, file));
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
    }

    private static class JarFileReader extends ClassFileReader {
        final JarFileIterator iter;
        final JarFile jarfile;
        JarFileReader(Path path) throws IOException {
            this(path, new JarFile(path.toFile()));
        }
        JarFileReader(Path path, JarFile jf) throws IOException {
            super(path);
            this.jarfile = jf;
            this.iter = new JarFileIterator(jf);
        }

        public Iterable<ClassFile> getClassFiles() throws IOException {
            return new Iterable<ClassFile>() {
                public Iterator<ClassFile> iterator() {
                    return iter;
                }
            };
        }

        public Iterable<Resource> getResources() throws IOException {
            if (iter.hasNext()) {
                throw new RuntimeException("getClassFile must be called first");
            }
            return iter.resources;
        }

        public byte[] readBytes(String name) throws IOException {
            JarEntry e = jarfile.getJarEntry(name);
            if (e != null) {
                try (InputStream in = jarfile.getInputStream(e)) {
                    load(in, e.getSize());
                }
            }
            return null;
        }

        class JarFileIterator implements Iterator<ClassFile> {
            private Enumeration<JarEntry> entries;
            final List<Resource> resources = new ArrayList<>();
            final JarFile jarfile;

            private JarEntry nextEntry;
            JarFileIterator(JarFile jarfile) {
                this.jarfile = jarfile;
                this.entries = jarfile.entries();
                this.nextEntry = nextEntry();
            }

            private JarEntry nextEntry() {
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    if (e.isDirectory())
                        continue;

                    String name = e.getName();
                    if (name.endsWith(".class")) {
                        return e;
                    } else if (Resource.isResource(name)) {
                        resources.add(getResource(e));
                    }
                }
                return null;
            }
            private Resource getResource(JarEntry e) {
                try (InputStream is = jarfile.getInputStream(e)) {
                    return Resource.getResource(e.getName(), is, e.getSize());
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }

            public boolean hasNext() {
                return nextEntry != null;
            }

            public ClassFile next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }

                ClassFile cf;
                try {
                    cf = readClassFile(jarfile, nextEntry);
                } catch (IOException ex) {
                    throw new ClassFileError(ex);
                }
                JarEntry entry = nextEntry;
                nextEntry = nextEntry();
                return cf;
            }

            private ClassFile readClassFile(JarFile jf, JarEntry e) throws IOException {
                try (InputStream is = jf.getInputStream(e)) {
                    return ClassFile.read(is);
                } catch (ConstantPoolException ex) {
                    throw new ClassFileError(ex);
                }
            }

            public void remove() {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        }
    }
}
