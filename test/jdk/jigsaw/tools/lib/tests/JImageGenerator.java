/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

/**
 *
 * A generator for jmods, jars and images.
 */
public class JImageGenerator {

    private static final String CREATE_CMD = "create";

    public static final String LOAD_ALL_CLASSES_TEMPLATE = "package PACKAGE;\n"
            + "\n"
            + "import java.net.URI;\n"
            + "import java.nio.file.FileSystems;\n"
            + "import java.nio.file.Files;\n"
            + "import java.nio.file.Path;\n"
            + "import java.util.function.Function;\n"
            + "\n"
            + "public class CLASS {\n"
            + "    private static long total_time;\n"
            + "    private static long num_classes;\n"
            + "    public static void main(String[] args) throws Exception {\n"
            + "        Function<Path, String> formatter = (path) -> {\n"
            + "            String clazz = path.toString().substring(\"modules/\".length()+1, path.toString().lastIndexOf(\".\"));\n"
            + "            clazz = clazz.substring(clazz.indexOf(\"/\") + 1);\n"
            + "            return clazz.replaceAll(\"/\", \"\\\\.\");\n"
            + "        };\n"
            + "        Files.walk(FileSystems.getFileSystem(URI.create(\"jrt:/\")).getPath(\"/modules/\")).\n"
            + "                filter((p) -> {\n"
            + "                    return Files.isRegularFile(p) && p.toString().endsWith(\".class\")\n"
            + "                    && !p.toString().endsWith(\"module-info.class\");\n"
            + "                }).\n"
            + "                map(formatter).forEach((clazz) -> {\n"
            + "                    try {\n"
            + "                        long t = System.currentTimeMillis();\n"
            + "                        Class.forName(clazz, false, Thread.currentThread().getContextClassLoader());\n"
            + "                        total_time+= System.currentTimeMillis()-t;\n"
            + "                        num_classes+=1;\n"
            + "                    } catch (IllegalAccessError ex) {\n"
            + "                        // Security exceptions can occur, this is not what we are testing\n"
            + "                        System.err.println(\"Access error, OK \" + clazz);\n"
            + "                    } catch (Exception ex) {\n"
            + "                        System.err.println(\"ERROR \" + clazz);\n"
            + "                        throw new RuntimeException(ex);\n"
            + "                    }\n"
            + "                });\n"
            + "    double res = (double) total_time / num_classes;\n"
            + "    // System.out.println(\"Total time \" + total_time + \" num classes \" + num_classes + \" average \" + res);\n"
            + "    }\n"
            + "}\n";

    private static final String OUTPUT_OPTION = "--output";
    private static final String MAIN_CLASS_OPTION = "--main-class";
    private static final String CLASS_PATH_OPTION = "--class-path";
    private static final String MODULE_PATH_OPTION = "--modulepath";
    private static final String ADD_MODS_OPTION = "--addmods";
    private static final String LIMIT_MODS_OPTION = "--limitmods";

    private static final String COMPILER_SRC_PATH_OPTION = "-sourcepath";
    private static final String COMPILER_MODULE_PATH_OPTION = "-modulepath";
    private static final String COMPILER_DIRECTORY_OPTION = "-d";
    private static final String COMPILER_DEBUG_OPTION = "-g";

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
    private final File extracted;
    private final File recreated;

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
        this.extracted = new File(output, "extracted");
        extracted.mkdir();
        this.recreated = new File(output, "recreated");
        recreated.mkdir();
    }

    public static File getJModsDir(File jdkHome) {
        File jdkjmods = new File(jdkHome, ".." + File.separator + "jmods");
        if (!jdkjmods.exists()) {
            jdkjmods = new File(jdkHome, ".." + File.separator + "images" +
                    File.separator + "jmods");
            if (!jdkjmods.exists()) {
                return null;
            }
        }
        return jdkjmods;
    }

    public File generateModuleCompiledClasses(String moduleName,
            String[] classNames, String... dependencies) throws Exception {
        String modulePath = jmods.getAbsolutePath() + File.pathSeparator +
                jars.getAbsolutePath();
        return generateModule(jmodsclasses, jmodssrc, moduleName, classNames,
                modulePath, dependencies);
    }

    public File generateJModule(String moduleName, String[] classNames,
            String... dependencies) throws IOException {
        String modulePath = jmods.getAbsolutePath() + File.pathSeparator +
                jars.getAbsolutePath();
        File compiled = generateModule(jmodsclasses, jmodssrc, moduleName,
                classNames, modulePath, dependencies);
        String mainClass = classNames == null || classNames.length == 0 ?
                null : classNames[0];
        // Generate garbage...
        File metaInf = new File(compiled, "META-INF/services");
        metaInf.mkdirs();
        File provider = new File(metaInf, "MyProvider");
        provider.createNewFile();
        File jcov = new File(compiled, "toto.jcov");
        jcov.createNewFile();
        return buildJModule(moduleName, mainClass, compiled);
    }

    public File addFiles(File module, InMemoryFile... resources) throws IOException {
        Path tempFile = Files.createTempFile("jlink-test", "");
        try (JarInputStream in = new JarInputStream(new FileInputStream(module));
             JarOutputStream out = new JarOutputStream(new FileOutputStream(tempFile.toFile()))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                out.putNextEntry(new ZipEntry(name));
                copy(in, out);
                out.closeEntry();
            }
            for (InMemoryFile r : resources) {
                addFile(r, out);
            }
        }
        Files.move(tempFile, module.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return module;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        int len;
        byte[] buf = new byte[4096];
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
    }

    public File generateJarModule(String moduleName, String[] classNames,
            String... dependencies) throws Exception {
        String modulePath = jmods.getAbsolutePath() + File.pathSeparator +
                jars.getAbsolutePath();
        File compiled = generateModule(jarsclasses, jarssrc, moduleName,
                classNames, modulePath, dependencies);
        return buildJarModule(moduleName, compiled);
    }

    public JLinkResult generateImage(String module) throws IOException {
        return generateImage(createNewFile(images, module, ".image"), new String[0], module);
    }

    public JLinkResult generateImage(String[] options, String module) throws IOException {
        return generateImage(createNewFile(images, module, ".image"), options, module);
    }

    public JLinkResult generateImage(File outDir, String[] options, String module) throws IOException {
        if (!getStandardJModule(module).exists() && !localModuleExists(module)) {
            throw new IOException("No module for " + module);
        }
        // This is expect FIRST jmods THEN jars, if you change this, some tests could fail
        String modulePath = stdjmods.getAbsolutePath() + File.pathSeparator +
                jmods.getAbsolutePath() + File.pathSeparator + jars.getAbsolutePath();
        return generateImage(outDir, modulePath, options, module);
    }

    public JLinkResult generateImage(File outDir, String modulePath, String[] options, String module) throws IOException {
        String[] args = optionsJLink(outDir, modulePath, options, module);
        System.out.println("jlink options: " + Arrays.toString(args));
        StringWriter writer = new StringWriter();
        int exitCode = jdk.tools.jlink.Main.run(args, new PrintWriter(writer));
        System.err.println(writer.toString());
        if (!outDir.exists() || outDir.list() == null || outDir.list().length == 0) {
            throw new IOException("Error generating jimage, check log file");
        }
        return new JLinkResult(exitCode, writer.toString(), outDir);
    }

    public File extractImageFile(File root, String imgName) throws Exception {
        File image = new File(root, "lib" + File.separator + "modules" +
                File.separator + imgName);
        if (!image.exists()) {
            throw new Exception("file to extract doesn't exists");
        }
        File outDir = createNewFile(extracted, imgName, ".extracted");
        outDir.mkdir();
        String[] args = {"extract", "--dir", outDir.getAbsolutePath(),
            image.getAbsolutePath()};
        jdk.tools.jimage.Main.run(args, new PrintWriter(System.out));
        if (!outDir.exists() || outDir.list() == null || outDir.list().length == 0) {
            throw new Exception("Error extracting jimage, check log file");
        }
        return outDir;
    }

    public File recreateImageFile(File extractedDir, String... userOptions)
            throws Exception {
        File outFile = createNewFile(recreated, extractedDir.getName(), ".jimage");
        List<String> options = new ArrayList<>();
        options.add("recreate");
        options.add("--dir");
        options.add(extractedDir.getAbsolutePath());
        for (String a : userOptions) {
            options.add(a);
        }
        options.add(outFile.getAbsolutePath());
        String[] args = new String[options.size()];
        options.toArray(args);
        jdk.tools.jimage.Main.run(args, new PrintWriter(System.out));
        if (!outFile.exists() || outFile.length() == 0) {
            throw new Exception("Error recreating jimage, check log file");
        }
        return outFile;
    }

    private String[] optionsJLink(File output, String modulePath, String[] userOptions, String module) {
        List<String> opt = new ArrayList<>();

        if (userOptions != null) {
            opt.addAll(Arrays.asList(userOptions));
        }

        opt.add(OUTPUT_OPTION);
        opt.add(output.toString());
        opt.add(ADD_MODS_OPTION);
        opt.add(module);
        opt.add(LIMIT_MODS_OPTION);
        opt.add(module);
        opt.add(MODULE_PATH_OPTION);
        opt.add(modulePath);
        String[] options = new String[opt.size()];
        return opt.toArray(options);
    }

    private static String[] jmodCreateOptions(File cp, String main, String name,
            File outFile) {
        List<String> opt = new ArrayList<>();
        opt.add(CREATE_CMD);
        if (main != null) {
            opt.add(MAIN_CLASS_OPTION);
            opt.add(main);
        }
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
            String[] classNames, String modulePath, String... dependencies)
            throws IOException {
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
            File pkgDirs = new File(moduleDirectory, pkgName.replaceAll("\\.",
                    Matcher.quoteReplacement(File.separator)));
            pkgDirs.mkdirs();
            moduleMetaBuilder.append("exports ").append(pkgName).append(";\n");
            for (String clazz : e.getValue()) {
                String clazzName = clazz.substring(clazz.lastIndexOf(".") + 1,
                        clazz.length());
                File mainClass = new File(pkgDirs, clazzName + ".java");
                String mainContent = readFromTemplate(LOAD_ALL_CLASSES_TEMPLATE,
                        pkgName, clazzName);
                writeFile(mainClass, mainContent);
            }
        }
        moduleMetaBuilder.append("}");
        writeFile(moduleInfo, moduleMetaBuilder.toString());

        return compileModule(classes, moduleDirectory, modulePath);
    }

    private static String readFromTemplate(String template, String pkgName,
            String className) throws IOException {
        String content = template.replace("PACKAGE", pkgName);
        content = content.replace("CLASS", className);
        return content;
    }

    private static String toPackage(String name) {
        int index = name.lastIndexOf('.');
        if (index > 0) {
            return name.substring(0, index);
        } else {
            throw new RuntimeException("No package name");
        }
    }

    public File buildModule(String moduleName, File src) throws IOException {
        File compiledDir = compileModule(jmodsclasses, src, "");
        File outFile = new File(jmods, moduleName + ".jmod");
        jdk.tools.jmod.Main.run(jmodCreateOptions(compiledDir, null, moduleName, outFile),
                new PrintWriter(System.out));
        return outFile;
    }

    private static File compileModule(File classes, File moduleDirectory,
            String modulePath) throws IOException {
        File outDir = new File(classes, moduleDirectory.getName());
        outDir.mkdirs();
        SourceFilesVisitor visitor = new SourceFilesVisitor();
        Files.walkFileTree(moduleDirectory.toPath(), visitor);
        List<Path> files = visitor.files;

        String[] args = new String[files.size() + 7];
        args[0] = COMPILER_SRC_PATH_OPTION;
        args[1] = moduleDirectory.getAbsolutePath();
        args[2] = COMPILER_DIRECTORY_OPTION;
        args[3] = outDir.getPath();
        args[4] = COMPILER_MODULE_PATH_OPTION;
        args[5] = modulePath;
        args[6] = COMPILER_DEBUG_OPTION;
        int i = 7;
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
            throw new IOException("unexpected exit from javac: " + rc);
        }
        return outDir;
    }

    private File buildJModule(String name, String main, File moduleDirectory) {
        File outFile = new File(jmods, name + ".jmod");
        jdk.tools.jmod.Main.run(jmodCreateOptions(moduleDirectory, main, name, outFile),
                new PrintWriter(System.out));
        return outFile;
    }

    private File buildJarModule(String name, File moduleDirectory)
            throws IOException {
        File outFile = new File(jars, name + ".jar");
        try (JarOutputStream classesJar = new JarOutputStream(new FileOutputStream(outFile))) {
            String classDir = moduleDirectory.getPath();
            Files.walkFileTree(moduleDirectory.toPath(), new FileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir,
                        BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(moduleDirectory.toPath())) {
                        addFile(classDir, dir.toFile(), classesJar);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file,
                        BasicFileAttributes attrs) throws IOException {
                    addFile(classDir, file.toFile(), classesJar);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file,
                        IOException exc) throws IOException {
                    throw exc;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir,
                        IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return outFile;
    }

    private static void addFile(String radical, File source,
            JarOutputStream target) throws IOException {
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

    private static void addFile(InMemoryFile resource, JarOutputStream target) throws IOException {
        String fileName = resource.getPath();
        fileName = fileName.replace("\\", "/");
        String[] ss = fileName.split("/");
        Path p = Paths.get("");
        for (int i = 0; i < ss.length; ++i) {
            if (i < ss.length - 1) {
                if (!ss[i].isEmpty()) {
                    p = p.resolve(ss[i]);
                    JarEntry entry = new JarEntry(p.toString() + "/");
                    target.putNextEntry(entry);
                    target.closeEntry();
                }
            } else {
                p = p.resolve(ss[i]);
                JarEntry entry = new JarEntry(p.toString());
                target.putNextEntry(entry);
                copy(resource.getBytes(), target);
                target.closeEntry();
            }
        }
    }

    public static File createNewFile(File root, String module, String extension) {
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

    public static class JLinkResult {
        private final int exitCode;
        private final String message;
        private final File imageFile;

        public JLinkResult(int exitCode, String message, File imageFile) {
            this.exitCode = exitCode;
            this.message = message;
            this.imageFile = imageFile;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getMessage() {
            return message;
        }

        public File getImageFile() {
            return imageFile;
        }
    }

    public static class InMemoryFile {
        private final String path;
        private final byte[] bytes;

        public String getPath() {
            return path;
        }

        public InputStream getBytes() {
            return new ByteArrayInputStream(bytes);
        }

        public InMemoryFile(String path, byte[] bytes) {
            this.path = path;
            this.bytes = bytes;
        }

        public InMemoryFile(String path, InputStream is) throws IOException {
            this(path, readAllBytes(is));
        }
    }

    public static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (true) {
            int n = is.read(buf);
            if (n < 0) {
                break;
            }
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }
}
