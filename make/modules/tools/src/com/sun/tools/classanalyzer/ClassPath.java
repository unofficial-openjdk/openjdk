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
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * ClassPath for Java SE and JDK
 */
class ClassPath {
    /**
     * Returns a list of JAR files and directories as follows:
     *    javahome/classes
     *    javahome/classes_security
     *    javahome/lib/*.jar
     *    javahome/lib/ext/*.jar
     *    javahome/../lib/*.jar
     *
     * It will filter out the cobundled JAR files such as FX, JMC, JVisualVM
     */
    static List<Archive> getArchives(Path home) throws IOException {
        List<Archive> result = new ArrayList<>();

        if (home.endsWith("jre")) {
            // jar files in <javahome>/jre/lib
            result.addAll(addJarFiles(home.resolve("lib")));
            result.addAll(addJarFiles(home.getParent().resolve("lib")));

        } else if (home.resolve("lib").toFile().exists()) {
            // either a JRE or a jdk build image
            File classes = home.resolve("classes").toFile();
            if (classes.exists() && classes.isDirectory()) {
                // jdk build outputdir
                result.add(new Archive(classes, ClassFileReader.newInstance(classes)));
            }
            File securityClasses = home.resolve("classes_security").toFile();
            if (securityClasses.exists() && securityClasses.isDirectory()) {
                // jdk build outputdir
                result.add(new Archive(securityClasses, ClassFileReader.newInstance(securityClasses)));
            }
            // add other JAR files
            result.addAll(addJarFiles(home.resolve("lib")));
        } else {
            throw new RuntimeException("\"" + home + "\" not a JDK home");
        }
        return result;
    }

    private static List<String> SKIP_JARFILES =
            Arrays.asList("alt-rt.jar", "jfxrt.jar",
                          "ant-javafx.jar", "javafx-mx.jar",
                          "jfxswt.jar");
    private static List<Archive> addJarFiles(final Path root) throws IOException {
        final List<Archive> result = new ArrayList<Archive>();
        final Path ext = root.resolve("ext");
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                if (dir.equals(root) || dir.equals(ext)) {
                    // only look at lib/*.jar and lib/ext/*.jar
                    return FileVisitResult.CONTINUE;
                } else {
                    // skip other cobundled JAR files
                    return FileVisitResult.SKIP_SUBTREE;
                }
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                File f = file.toFile();
                String fn = f.getName();
                if (fn.endsWith(".jar") && !SKIP_JARFILES.contains(fn)) {
                    result.add(new Archive(f, ClassFileReader.newInstance(f)));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    /**
     * Represents the source of the class files.
     */
    public static class Archive {
        private final File file;
        private final String filename;
        private final ClassFileReader reader;
        public Archive(File file, ClassFileReader reader) {
            this.file = file;
            this.filename = file.getName();
            this.reader = reader;
        }

        public String getFileName() {
            return filename;
        }

        public Iterable<ClassFile> getClassFiles() throws IOException {
            return reader.getClassFiles();
        }

        public Iterable<Resource> getResources() throws IOException {
            return reader.getResources();
        }

        public byte[] readBytes(String name) throws IOException {
            return reader.readBytes(name);
        }

        public String toString() {
            return file != null ? file.getPath() : filename;
        }
    }
}
