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
package com.sun.tools.jdeps;

import com.sun.tools.classfile.ClassFile;
import com.sun.tools.classfile.ConstantPoolException;
import com.sun.tools.classfile.Dependencies;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.xml.stream.XMLStreamException;

/**
 * ClassPath for Java SE and JDK
 */
class PlatformClassPath {
    private static List<Archive> modules;
    static synchronized List<Archive> getArchives(Path mpath) throws IOException {
        if (modules == null) {
            initPlatformArchives(mpath);
        }
        return modules;
    }

    /**
     * Finds the module with the given name. Returns null
     * if such module doesn't exist.
     *
     * @param mn module name
     */
    static Module findModule(String mn) {
        for (Archive a : modules) {
            if (Module.class.isInstance(a)) {
                Module m = (Module)a;
                if (mn.equals(m.name())) {
                    return m;
                }
            }
        }
        return null;
    }

    private static List<Archive> initPlatformArchives(Path mpath) throws IOException {
        Path home = Paths.get(System.getProperty("java.home"));
        Path mlib = home.resolve("lib").resolve("modules");
        if (Files.exists(mlib)) {
            modules = mpath != null ? initModuleImage(mpath, mlib)
                                     : initModuleImage(mlib);
        } else {
            modules = initLegacyImage(home);
        }
        if (findModule("java.base") != null) {
            Profile.initProfiles();
        }
        return modules;
    }

    private static List<Archive> initModuleImage(Path... mpaths) throws IOException {
        try (InputStream in = PlatformClassPath.class
                .getResourceAsStream("resources/modules.xml")) {
            ModulesXmlReader reader = new ModulesXmlReader(mpaths);
            return new ArrayList<Archive>(reader.load(in));
        } catch (XMLStreamException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static List<Archive> initLegacyImage(Path home) throws IOException {
        LegacyImageHelper cfr = new LegacyImageHelper(home);
        List<Archive> archives = new ArrayList<>(cfr.nonPlatformArchives);
        try (InputStream in = PlatformClassPath.class
                .getResourceAsStream("resources/modules.xml")) {
            ModulesXmlReader reader = new ModulesXmlReader(cfr);
            archives.addAll(reader.load(in));
            return archives;
        } catch (XMLStreamException ex) {
            throw new RuntimeException(ex);
        }
    }

    static class LegacyImageHelper {
        static final List<String> NON_PLATFORM_JARFILES =
            Arrays.asList("alt-rt.jar", "jfxrt.jar", "ant-javafx.jar", "javafx-mx.jar");
        final List<Archive> nonPlatformArchives = new ArrayList<>();
        final List<JarFile> jarfiles = new ArrayList<>();
        final Path home;
        LegacyImageHelper(Path home) throws IOException {
            this.home = home;
            if (home.endsWith("jre")) {
                // jar files in <javahome>/jre/lib
                addJarFiles(home.resolve("lib"));
            } else if (Files.exists(home.resolve("lib"))) {
                // either a JRE or a jdk build image
                Path classes = home.resolve("classes");
                if (Files.isDirectory(classes)) {
                    // legacy jdk build outputdir - unsupported
                    nonPlatformArchives.add(Archive.getInstance(classes));
                }
                // add other JAR files
                addJarFiles(home.resolve("lib"));
            } else {
                throw new RuntimeException("\"" + home + "\" not a JDK home");
            }
        }

        ClassFileReader getClassReader(String name, Set<String> packages) {
            return new ModuleClassReader(name, packages);
        }

        private void addJarFiles(final Path root) throws IOException {
            final Path ext = root.resolve("ext");
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    if (dir.equals(root) || dir.equals(ext)) {
                        return FileVisitResult.CONTINUE;
                    } else {
                        // skip other cobundled JAR files
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }

                @Override
                public FileVisitResult visitFile(Path p, BasicFileAttributes attrs)
                        throws IOException {
                    String fn = p.getFileName().toString();
                    if (fn.endsWith(".jar")) {
                        if (NON_PLATFORM_JARFILES.contains(fn)) {
                            // JDK may cobundle with JavaFX that doesn't belong to any profile
                            // Treat jfxrt.jar as regular Archive
                            nonPlatformArchives.add(Archive.getInstance(p));
                        } else {
                            jarfiles.add(new JarFile(p.toFile()));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        class ModuleClassReader extends ClassFileReader {
            private JarFile cachedJarFile = getJarFile(0);
            private final Set<String> packages;
            private final String module;

            ModuleClassReader(String module, Set<String> packages) {
                super(home);
                this.module = module;
                this.packages = packages;
                    // System.err.println(module);
                // packages.stream().sorted().forEach(p -> System.err.println("   " + p));
            }

            boolean includes(String name) {
                String cn = name.replace('/', '.');
                int i = cn.lastIndexOf('.');
                String pn = i > 0 ? cn.substring(0, i) : "";
                return packages.contains(pn);
            }

            private JarEntry findJarEntry(JarFile jarfile, String entryName1, String entryName2) {
                JarEntry e = jarfile.getJarEntry(entryName1);
                if (e == null) {
                    e = jarfile.getJarEntry(entryName2);
                }
                return e;
            }

            public ClassFile getClassFile(String name) throws IOException {
                if (jarfiles.isEmpty() || !includes(name)) {
                    return null;
                }

                if (name.indexOf('.') > 0) {
                    int i = name.lastIndexOf('.');
                    String entryName = name.replace('.', '/') + ".class";
                    String innerClassName = entryName.substring(0, i) + "$"
                            + entryName.substring(i + 1, entryName.length());
                    JarEntry e = findJarEntry(cachedJarFile, entryName, innerClassName);
                    if (e != null) {
                        return readClassFile(cachedJarFile, e);
                    }
                    for (JarFile jf : jarfiles) {
                        if (jf == cachedJarFile) {
                            continue;
                        }
                        e = findJarEntry(jf, entryName, innerClassName);
                        if (e != null) {
                            cachedJarFile = jf;
                            return readClassFile(jf, e);
                        }
                    }
                } else {
                    String entryName = name + ".class";
                    JarEntry e = cachedJarFile.getJarEntry(entryName);
                    if (e != null) {
                        return readClassFile(cachedJarFile, e);
                    }
                    for (JarFile jf : jarfiles) {
                        if (jf == cachedJarFile) {
                            continue;
                        }
                        e = jf.getJarEntry(entryName);
                        if (e != null) {
                            cachedJarFile = jf;
                            return readClassFile(jf, e);
                        }
                    }
                }
                return null;
            }

            private ClassFile readClassFile(JarFile jarfile, JarEntry e) throws IOException {
                InputStream is = null;
                try {
                    is = jarfile.getInputStream(e);
                    return ClassFile.read(is);
                } catch (ConstantPoolException ex) {
                    throw new Dependencies.ClassFileError(ex);
                } finally {
                    if (is != null) {
                        is.close();
                    }
                }
            }

            public Iterable<ClassFile> getClassFiles() throws IOException {
                final Iterator<ClassFile> iter = new ModuleClassIterator();
                return new Iterable<ClassFile>() {
                    public Iterator<ClassFile> iterator() {
                        return iter;
                    }
                };
            }

            private JarFile getJarFile(int index) {
                return index < jarfiles.size() ? jarfiles.get(index) : null;
            }

            class ModuleClassIterator implements Iterator<ClassFile> {
                private Enumeration<JarEntry> entries;
                private JarFile jarfile;
                private JarEntry nextEntry;
                private int index;
                private ClassFile cf;

                ModuleClassIterator() {
                    this.index = 0;
                    this.jarfile = getJarFile(index);
                    this.entries = jarfile != null ? jarfile.entries() : null;
                    this.nextEntry = nextEntry();
                }

                private String errorMessage(Throwable t) {
                    if (t.getCause() != null) {
                        return t.getCause().toString();
                    } else {
                        return t.toString();
                    }
                }

                public boolean hasNext() {
                    if (nextEntry != null && cf != null) {
                        return true;
                    }
                    while (nextEntry != null) {
                        try {
                            cf = readClassFile(jarfile, nextEntry);
                            return true;
                        } catch (Dependencies.ClassFileError ex) {
                            System.err.println("Bad entry: " + nextEntry + " " + errorMessage(ex));
                        } catch (IOException ex) {
                            System.err.println("IO error: " + nextEntry + " " + errorMessage(ex));
                        }
                        nextEntry = nextEntry();
                    }
                    return false;
                }

                public ClassFile next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    ClassFile classFile = cf;
                    cf = null;
                    nextEntry = nextEntry();
                    return classFile;
                }

                private JarEntry nextEntry() {
                    while (jarfile != null) {
                        while (entries.hasMoreElements()) {
                            JarEntry e = entries.nextElement();
                            String name = e.getName();
                            if (name.endsWith(".class") && includes(name)) {
                                return e;
                            }
                        }
                        System.err.format("%d: %s done%n", index, jarfile.getName());
                        jarfile = getJarFile(++index);
                        entries = jarfile != null ? jarfile.entries() : null;
                    }
                    return null;
                }

                public void remove() {
                    throw new UnsupportedOperationException("Not supported yet.");
                }
            }
        }
    }
}
