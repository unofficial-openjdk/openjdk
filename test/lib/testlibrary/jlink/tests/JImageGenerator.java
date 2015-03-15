/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
package tests;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 *
 * A generator for jmods, jars and images.
 */
public class JImageGenerator {

    private static final String CREATE_CMD = "create";

    private static final String OUTPUT_OPTION = "--output";
    private static final String MID_OPTION = "--mid";
    private static final String MAIN_CLASS_OPTION = "--main-class";
    private static final String CLASS_PATH_OPTION = "--class-path";
    private static final String MODULE_PATH_OPTION = "--modulepath";
    private static final String ADD_MODS_OPTION = "--addmods";

    private static final String COMPILER_SRC_PATH_OPTION = "-sourcepath";
    private static final String COMPILER_MODULE_PATH_OPTION = "-modulepath";
    private static final String COMPILER_DIRECTORY_OPTION = "-d";

    private static class SourceFilesVisitor implements FileVisitor<Path> {

        private final List<Path> files = new ArrayList<>();

        @Override
        public FileVisitResult preVisitDirectory(Path dir,
                BasicFileAttributes attrs) throws IOException {
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file,
                BasicFileAttributes attrs) throws IOException {
            if (file.toString().endsWith(".java")) {
                files.add(file);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file,
                IOException exc) throws IOException {
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir,
                IOException exc) throws IOException {
            return FileVisitResult.CONTINUE;
        }

    }

    private final File jmodssrc;
    private final File jarssrc;
    private final File jmodsclasses;
    private final File jarsclasses;
    private final File jmods;
    private final File jars;
    private final File images;
    private final File stdjmods;

    public JImageGenerator(File output, File jdkHome) throws Exception {
        stdjmods = getJModsDir(jdkHome);
        if (stdjmods == null) {
            throw new Exception("No standard jmods ");
        }
        if (!output.exists()) {
            throw new Exception("Output directory doesn't exist " + output);
        }

        this.jmods = new File(output, "jmods");
        this.jmods.mkdir();
        this.jars = new File(output, "jars");
        this.jars.mkdir();
        this.jarssrc = new File(jars, "src");
        jarssrc.mkdir();
        this.jmodssrc = new File(jmods, "src");
        jmodssrc.mkdir();
        this.jmodsclasses = new File(jmods, "classes");
        jmodsclasses.mkdir();
        this.jarsclasses = new File(jars, "classes");
        jarsclasses.mkdir();
        this.images = new File(output, "images");
        images.mkdir();
    }

    public static File getJModsDir(File jdkHome) {
        File jdkjmods = new File(jdkHome, ".." + File.separator + "jmods");
        if (!jdkjmods.exists()) {
            jdkjmods = new File(jdkHome, ".." + File.separator + "images" + File.separator + "jmods");
            if (!jdkjmods.exists()) {
                return null;
            }
        }
        return jdkjmods;
    }

    public File generateJModule(String moduleName, String[] classNames, String... dependencies) throws Exception {
        String modulePath = jmods.getAbsolutePath() + File.pathSeparator + jars.getAbsolutePath();
        File compiled = generateModule(jmodsclasses, jmodssrc, moduleName, classNames, modulePath, dependencies);
        String mainClass = classNames == null || classNames.length == 0 ? null : classNames[0];
        return buildJModule(moduleName, mainClass, compiled);
    }

    public File generateJarModule(String moduleName, String[] classNames, String... dependencies) throws Exception {
        String modulePath = jmods.getAbsolutePath() + File.pathSeparator + jars.getAbsolutePath();
        File compiled = generateModule(jarsclasses, jarssrc, moduleName, classNames, modulePath, dependencies);
        return buildJarModule(moduleName, compiled);
    }

    public File generateImage(String[] options, String module) throws Exception {
        if (!getStandardJModule(module).exists() && !localModuleExists(module)) {
            throw new Exception("No module for " + module);
        }
        File outDir = createNewFile(images, module, ".image");
        jdk.jigsaw.tools.jlink.Main.run(jimageCreateOptions(outDir, options, module), new PrintWriter(System.out));
        if (!outDir.exists() || outDir.list() == null || outDir.list().length == 0) {
            throw new Exception("Error generating jimage, check log file");
        }
        return outDir;
    }

    private String[] jimageCreateOptions(File output, String[] userOptions, String module) {
        List<String> opt = new ArrayList<>();

        if (userOptions != null) {
            opt.addAll(Arrays.asList(userOptions));
        }

        opt.add(OUTPUT_OPTION);
        opt.add(output.toString());
        opt.add(ADD_MODS_OPTION);
        opt.add(module);
        opt.add(MODULE_PATH_OPTION);
        // This is expect FIRST jmods THEN jars, if you change this, some tests could fail
        opt.add(stdjmods.getAbsolutePath() + File.pathSeparator + jmods.getAbsolutePath()
                + File.pathSeparator + jars.getAbsolutePath());
        String[] options = new String[opt.size()];
        System.out.println("jimage options " + opt);
        return opt.toArray(options);
    }

    private static String[] jmodCreateOptions(File cp, String main, String id, File outFile) {
        List<String> opt = new ArrayList<>();
        opt.add(CREATE_CMD);
        if (main != null) {
            opt.add(MAIN_CLASS_OPTION);
            opt.add(main);
        }
        opt.add(MID_OPTION);
        opt.add(id);
        opt.add(CLASS_PATH_OPTION);
        opt.add(cp.getAbsolutePath());
        opt.add(outFile.getAbsolutePath());
        System.out.println("jmod options " + opt);
        String[] options = new String[opt.size()];
        return opt.toArray(options);
    }

    private static File writeFile(File f, String s) throws IOException {
        if (f.getParentFile() != null) {
            f.getParentFile().mkdirs();
        }
        try (FileWriter out = new FileWriter(f)) {
            out.write(s);
        }
        return f;
    }

    private static File generateModule(File classes, File src, String name,
            String[] classNames, String modulePath, String... dependencies) throws Exception {
        if (classNames == null || classNames.length == 0) {
            classNames = new String[1];
            classNames[0] = name + ".Main";
        }
        File moduleDirectory = new File(src, name);
        moduleDirectory.mkdirs();
        File moduleInfo = new File(moduleDirectory, "module-info.java");
        StringBuilder dependenciesBuilder = new StringBuilder();
        for (String dep : dependencies) {
            dependenciesBuilder.append("requires ").append(dep).append(";\n");
        }

        Map<String, List<String>> pkgs = Arrays.asList(classNames).stream().
                collect(Collectors.groupingBy(JImageGenerator::toPackage));

        StringBuilder moduleMetaBuilder = new StringBuilder();
        moduleMetaBuilder.append("module ").append(name).append("{\n").
                append(dependenciesBuilder.toString());
        for (Entry<String, List<String>> e : pkgs.entrySet()) {
            String pkgName = e.getKey();
            File pkgDirs = new File(moduleDirectory, pkgName.replaceAll("\\.", Matcher.quoteReplacement(File.separator)));
            pkgDirs.mkdirs();
            moduleMetaBuilder.append("exports ").append(pkgName).append(";\n");
            for (String clazz : e.getValue()) {
                String clazzName = clazz.substring(clazz.lastIndexOf(".") + 1, clazz.length());
                File mainClass = new File(pkgDirs, clazzName + ".java");
                String mainContent = "package " + pkgName + ";\n"
                        + "public class " + clazzName + " {\n"
                        + "public static void main(String[] args) {\n"
                        + " System.out.println(\"Hello World from " + name + "/" + pkgName + "\");\n"
                        + "}\n"
                        + "}\n";
                writeFile(mainClass, mainContent);
            }
        }
        moduleMetaBuilder.append("}");
        writeFile(moduleInfo, moduleMetaBuilder.toString());

        File compiled = compileModule(classes, moduleDirectory, modulePath);
        return compiled;
    }

    private static String toPackage(String name) {
        int index = name.lastIndexOf('.');
        if (index > 0) {
            return name.substring(0, index);
        } else {
            throw new RuntimeException("No package name");
        }
    }

    private static File compileModule(File classes, File moduleDirectory, String modulePath) throws Exception {
        File outDir = new File(classes, moduleDirectory.getName());
        outDir.mkdirs();
        SourceFilesVisitor visitor = new SourceFilesVisitor();
        Files.walkFileTree(moduleDirectory.toPath(), visitor);
        List<Path> files = visitor.files;

        String[] args = new String[files.size() + 6];
        args[0] = COMPILER_SRC_PATH_OPTION;
        args[1] = moduleDirectory.getAbsolutePath();
        args[2] = COMPILER_DIRECTORY_OPTION;
        args[3] = outDir.getPath();
        args[4] = COMPILER_MODULE_PATH_OPTION;
        args[5] = modulePath;
        int i = 6;
        for (Path f : visitor.files) {
            args[i++] = f.toString();
        }
        System.out.println("compile: " + Arrays.asList(args));
        StringWriter sw = new StringWriter();
        int rc;
        try (PrintWriter pw = new PrintWriter(sw)) {
            rc = com.sun.tools.javac.Main.compile(args, pw);
        }
        if (rc != 0) {
            System.err.println(sw.toString());
            throw new Exception("unexpected exit from javac: " + rc);
        }
        return outDir;
    }

    private File buildJModule(String id, String main, File moduleDirectory) {
        File outFile = new File(jmods, id + ".jmod");
        jdk.jigsaw.tools.jmod.Main.run(jmodCreateOptions(moduleDirectory, main, id, outFile),
                                       new PrintWriter(System.out));
        return outFile;
    }

    private File buildJarModule(String id, File moduleDirectory) throws IOException {
        File outFile = new File(jars, id + ".jar");
        try (JarOutputStream classesJar = new JarOutputStream(new FileOutputStream(outFile))) {
            String classDir = moduleDirectory.getPath();
            Files.walkFileTree(moduleDirectory.toPath(), new FileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(moduleDirectory.toPath())) {
                        addFile(classDir, dir.toFile(), classesJar);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    addFile(classDir, file.toFile(), classesJar);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    throw exc;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return outFile;
    }

    private static void addFile(String radical, File source, JarOutputStream target) throws IOException {
        String fileName = source.getPath();
        if (fileName.startsWith(radical)) {
            fileName = fileName.substring(radical.length() + 1);
        }
        fileName = fileName.replace("\\", "/");
        if (source.isDirectory()) {
            if (!fileName.isEmpty()) {
                if (!fileName.endsWith("/")) {
                    fileName += "/";
                }
                JarEntry entry = new JarEntry(fileName);
                entry.setTime(source.lastModified());
                target.putNextEntry(entry);
                target.closeEntry();
            }
        } else {
            JarEntry entry = new JarEntry(fileName);
            entry.setTime(source.lastModified());
            target.putNextEntry(entry);
            Files.copy(source.toPath(), target);
            target.closeEntry();
        }
    }

    private static File createNewFile(File root, String module, String extension) {
        File outDir = new File(root, module + extension);
        int i = 1;
        while (outDir.exists()) {
            outDir = new File(root, module + "-" + i + extension);
            i += 1;
        }
        return outDir;
    }

    private File getJModule(String name) {
        return new File(jmods, name + ".jmod");
    }

    private File getJarModule(String name) {
        return new File(jars, name + ".jar");
    }

    private File getStandardJModule(String module) {
        return new File(stdjmods, module + ".jmod");
    }

    private boolean localModuleExists(String name) {
        return getJModule(name).exists() || getJarModule(name).exists();
    }
}
