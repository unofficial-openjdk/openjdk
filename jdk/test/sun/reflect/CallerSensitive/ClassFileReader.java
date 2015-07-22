/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

import com.sun.tools.classfile.ClassFile;
import com.sun.tools.classfile.ConstantPoolException;

import java.io.*;
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
            return new DirectoryReader(path);
        } else if (path.getName().endsWith(".jar")) {
            return new JarFileReader(path);
        } else {
            return new ClassFileReader(path);
        }
    }

    /**
     * Returns a ClassFileReader instance of a given JarFile.
     */
    public static ClassFileReader newInstance(File path, JarFile jf) throws IOException {
        return new JarFileReader(path, jf);
    }

    protected final File path;
    protected final String baseFileName;
    private ClassFileReader(File path) {
        this.path = path;
        this.baseFileName = path.getName().equals("")
                                ? path.toString()
                                : path.getName();
    }

    public String getFileName() {
        return baseFileName;
    }

    /**
     * Returns the ClassFile matching the given binary name
     * or a fully-qualified class name.
     */
    public ClassFile getClassFile(String name) throws IOException {
        if (name.indexOf('.') > 0) {
            int i = name.lastIndexOf('.');
            String pathname = name.replace('.', File.separatorChar) + ".class";
            if (baseFileName.equals(pathname) ||
                    baseFileName.equals(pathname.substring(0, i) + "$" +
                                        pathname.substring(i+1, pathname.length()))) {
                return readClassFile(path);
            }
        } else {
            if (baseFileName.equals(name.replace('/', File.separatorChar) + ".class")) {
                return readClassFile(path);
            }
        }
        return null;
    }

    public Iterable<ClassFile> getClassFiles() throws IOException {
        return new Iterable<ClassFile>() {
            public Iterator<ClassFile> iterator() {
                return new FileIterator();
            }
        };
    }

    protected ClassFile readClassFile(File p) throws IOException {
        InputStream is = null;
        try {
            is = new FileInputStream(p);
            return ClassFile.read(is);
        } catch (ConstantPoolException e) {
            throw new ClassFileError(e);
        } finally {
            if (is != null) {
                is.close();
            }
        }
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
        DirectoryReader(File path) throws IOException {
            super(path);
        }

        public ClassFile getClassFile(String name) throws IOException {
            if (name.indexOf('.') > 0) {
                int i = name.lastIndexOf('.');
                String pathname = name.replace('.', File.separatorChar) + ".class";
                File p = new File(path, pathname);
                if (!p.exists()) {
                    p = new File(path, pathname.substring(0, i) + "$" +
                                 pathname.substring(i+1, pathname.length()));
                }
                if (p.exists()) {
                    return readClassFile(p);
                }
            } else {
                File p = new File(path, name + ".class");
                if (p.exists()) {
                    return readClassFile(p);
                }
            }
            return null;
        }

        public Iterable<ClassFile> getClassFiles() throws IOException {
            final Iterator<ClassFile> iter = new DirectoryIterator();
            return new Iterable<ClassFile>() {
                public Iterator<ClassFile> iterator() {
                    return iter;
                }
            };
        }

        private List<File> walkTree(File dir) throws IOException {
            final List<File> files = new ArrayList<File>();
            final List<String> dirContents = Arrays.asList(dir.list());
            for (String file : dirContents) {
                File f = new File(dir, file);
                if (f.isDirectory())
                    files.addAll(walkTree(f));
                else {
                    if (f.getName().endsWith(".class")) {
                        files.add(f);
                    }
                }
            }
            return files;
        }

        class DirectoryIterator implements Iterator<ClassFile> {
            private List<File> entries;
            private int index = 0;
            DirectoryIterator() throws IOException {
                entries = walkTree(path);
                index = 0;
            }

            public boolean hasNext() {
                return index != entries.size();
            }

            public ClassFile next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                File path = entries.get(index++);
                try {
                    return readClassFile(path);
                } catch (IOException e) {
                    throw new ClassFileError(e);
                }
            }

            public void remove() {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        }
    }

    private static class JarFileReader extends ClassFileReader {
        final JarFile jarfile;
        JarFileReader(File path) throws IOException {
            this(path, new JarFile(path));
        }
        JarFileReader(File path, JarFile jf) throws IOException {
            super(path);
            this.jarfile = jf;
        }

        public ClassFile getClassFile(String name) throws IOException {
            if (name.indexOf('.') > 0) {
                int i = name.lastIndexOf('.');
                String entryName = name.replace('.', '/') + ".class";
                JarEntry e = jarfile.getJarEntry(entryName);
                if (e == null) {
                    e = jarfile.getJarEntry(entryName.substring(0, i) + "$"
                            + entryName.substring(i + 1, entryName.length()));
                }
                if (e != null) {
                    return readClassFile(e);
                }
            } else {
                JarEntry e = jarfile.getJarEntry(name + ".class");
                if (e != null) {
                    return readClassFile(e);
                }
            }
            return null;
        }

        private ClassFile readClassFile(JarEntry e) throws IOException {
            InputStream is = null;
            try {
                is = jarfile.getInputStream(e);
                return ClassFile.read(is);
            } catch (ConstantPoolException ex) {
                throw new IOException(ex);
            } finally {
                if (is != null)
                    is.close();
            }
        }

        public Iterable<ClassFile> getClassFiles() throws IOException {
            final Iterator<ClassFile> iter = new JarFileIterator();
            return new Iterable<ClassFile>() {
                public Iterator<ClassFile> iterator() {
                    return iter;
                }
            };
        }

        class JarFileIterator implements Iterator<ClassFile> {
            private Enumeration<JarEntry> entries;
            private JarEntry nextEntry;
            JarFileIterator() {
                this.entries = jarfile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    String name = e.getName();
                    if (name.endsWith(".class")) {
                        this.nextEntry = e;
                        break;
                    }
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
                    cf = readClassFile(nextEntry);
                } catch (IOException e) {
                    throw new ClassFileError(e);
                }
                JarEntry entry = nextEntry;
                nextEntry = null;
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    String name = e.getName();
                    if (name.endsWith(".class")) {
                        nextEntry = e;
                        break;
                    }
                }
                return cf;
            }

            public void remove() {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        }
    }

    public static class ClassFileError extends Error {
        public ClassFileError(Throwable t) {
            super(t);
        }
    }
}
