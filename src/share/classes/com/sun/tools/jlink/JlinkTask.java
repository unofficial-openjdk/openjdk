/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.tools.jlink;


import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.spi.*;
import java.nio.file.attribute.*;;
import java.text.MessageFormat;
import java.util.*;

/**
 * Implementation for the jlink tool.
 *
 * ## this should belong to the jdk repo and use jdk.joptsimple some day.
 */
class JlinkTask {
    static class BadArgs extends Exception {
        static final long serialVersionUID = 8765093759964640721L;  // ## re-generate
        BadArgs(String key, Object... args) {
            super(JlinkTask.getMessage(key, args));
            this.key = key;
            this.args = args;
        }

        BadArgs showUsage(boolean b) {
            showUsage = b;
            return this;
        }
        final String key;
        final Object[] args;
        boolean showUsage;
    }

    static abstract class Option {
        final boolean hasArg;
        final String[] aliases;

        Option(boolean hasArg, String... aliases) {
            this.hasArg = hasArg;
            this.aliases = aliases;
        }

        boolean isHidden() {
            return false;
        }

        boolean matches(String opt) {
            for (String a : aliases) {
                if (a.equals(opt)) {
                    return true;
                } else if (opt.startsWith("--") && hasArg && opt.startsWith(a + "=")) {
                    return true;
                }
            }
            return false;
        }

        boolean ignoreRest() {
            return false;
        }

        abstract void process(JlinkTask task, String opt, String arg) throws BadArgs;
    }

    static abstract class HiddenOption extends Option {
        HiddenOption(boolean hasArg, String... aliases) {
            super(hasArg, aliases);
        }

        @Override
        boolean isHidden() {
            return true;
        }
    }

    private static final Path systemJmodPath = systemJmodPath();

    private static Path systemJmodPath() {
        Path p = Paths.get(System.getProperty("java.home"));
        if (p.endsWith("jre"))
            p = p.getParent();
        return p.resolve("jmods");
    }

    private static Path CWD = Paths.get("");

    private static List<Path> splitPath(String arg, String separator,
                                        boolean jmods)
        throws BadArgs
    {
        List<Path> paths = new ArrayList<>();
        for (String p: arg.split(separator)) {
            if (p.length() > 0) {
                try {
                    Path path = CWD.resolve(p);
                    if (Files.notExists(path)) {
                        if (!jmods)
                            throw new BadArgs("err.path.not.found", path);
                        path = systemJmodPath.resolve(p + ".jmod");
                        if (Files.notExists(path))
                            throw new BadArgs("err.jmod.not.found", path);
                    }
                    paths.add(path);
                } catch (InvalidPathException x) {
                    throw new BadArgs("err.path.not.valid", p);
                }
            }
        }
        return paths;
    }

    static Option[] recognizedOptions = {
        new Option(true, "--class-path") {
            void process(JlinkTask task, String opt, String arg) throws BadArgs {
                task.options.classpath = splitPath(arg, File.pathSeparator, false);
            }
        },
        new Option(true, "--cmds") {
            void process(JlinkTask task, String opt, String arg) throws BadArgs {
                task.options.cmds = splitPath(arg, File.pathSeparator, false);
            }
        },
        new Option(true, "--command") {
            void process(JlinkTask task, String opt, String arg) throws BadArgs {
                int index = arg.indexOf(':');
                if (index == -1)
                    throw new BadArgs("err.invalid.arg.for.option", opt);
                String name = arg.substring(0, index);
                String main = arg.substring(index+1, arg.length());
                task.options.launchers.put(name, main);
            }
        },
        new Option(true, "--config") {
            void process(JlinkTask task, String opt, String arg) throws BadArgs {
                task.options.configs = splitPath(arg, File.pathSeparator, false);
            }
        },
        new Option(true, "--format") {
            void process(JlinkTask task, String opt, String arg) throws BadArgs {
                if (Format.IMAGE.toString().equalsIgnoreCase(arg))
                    task.options.format = Format.IMAGE;
                else if (Format.JMOD.toString().equalsIgnoreCase(arg))
                    task.options.format = Format.JMOD;
                else
                    throw new BadArgs("err.invalid.arg.for.option", opt).showUsage(true);
            }
        },
        new Option(false, "--help") {
            void process(JlinkTask task, String opt, String arg) {
                task.options.help = true;
            }
        },
        new Option(true, "--libs") {
            void process(JlinkTask task, String opt, String arg) throws BadArgs {
                task.options.libs = splitPath(arg, File.pathSeparator, false);
            }
        },
        new Option(true, "--module") {
            void process(JlinkTask task, String opt, String arg) throws BadArgs {
                task.options.moduleName = arg;
            }
        },
        new Option(true, "--mods") {
            void process(JlinkTask task, String opt, String arg) throws BadArgs {
                task.options.jmods = splitPath(arg, ",", true);
            }
        },
        new Option(true, "--output") {
            void process(JlinkTask task, String opt, String arg) throws BadArgs {
                Path path = Paths.get(arg);
                task.options.output = path;
            }
        },
        new Option(false, "--version") {
            void process(JlinkTask task, String opt, String arg) {
                task.options.version = true;
            }
        },
        new HiddenOption(false, "--fullversion") {
            void process(JlinkTask task, String opt, String arg) {
                task.options.fullVersion = true;
            }
        },
    };

    private static final String PROGNAME = "jlink";
    private final Options options = new Options();

    private PrintWriter log;
    void setLog(PrintWriter out) {
        log = out;
    }

    /**
     * Result codes.
     */
    static final int EXIT_OK = 0, // Completed with no errors.
                     EXIT_ERROR = 1, // Completed but reported errors.
                     EXIT_CMDERR = 2, // Bad command-line arguments
                     EXIT_SYSERR = 3, // System error or resource exhaustion.
                     EXIT_ABNORMAL = 4;// terminated abnormally


    enum Format {
        JMOD,
        IMAGE;
    }

    static class Options {
        boolean help;
        boolean version;
        boolean fullVersion;
        List<Path> classpath;
        List<Path> cmds;
        List<Path> configs;
        List<Path> libs;
        List<Path> jmods;
        Format format;
        Path output;
        Map<String,String> launchers = new HashMap<>();
        String moduleName;
    }

    int run(String[] args) {
        if (log == null) {
            log = new PrintWriter(System.out);
        }
        try {
            handleOptions(args);
            if (options.help) {
                showHelp();
            }
            if (options.version || options.fullVersion) {
                showVersion(options.fullVersion);
            }
            if (options.format == null) {
                if (options.help || options.version || options.fullVersion) {
                    return EXIT_OK;
                } else {
                    throw new BadArgs("err.format.must.be.specified").showUsage(true);
                }
            } else if (options.format.equals(Format.IMAGE)) {
                Path path = options.output;
                if (path == null)
                    throw new BadArgs("err.output.must.be.specified").showUsage(true);
                if (Files.notExists(path))
                    // ## throw new BadArgs("err.dir.not.found", path);
                    Files.createDirectories(path);
                try {
                    BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                    if (!attrs.isDirectory())
                        throw new BadArgs("err.dir.not.directory", path);
                } catch (IOException x) {
                    throw new BadArgs("err.dir.not.directory", path);
                }
                if (path.toFile().list().length != 0)
                    throw new BadArgs("err.dir.not.empty", path);

                if (options.jmods == null)  // ## default to jdk.base ??
                    throw new BadArgs("err.mods.must.be.specified").showUsage(true);

                if (options.classpath != null) {
                    if (options.launchers.isEmpty())
                        throw new BadArgs("err.cmds.must.be.specified").showUsage(true);
                } else {
                    // support launchers with main entry point in the JDK
                    // if (options.commandName != null || options.commandClass != null)
                    //    throw new BadArgs("err.cp.must.be.specified").showUsage(true);

                }
            } else if (options.format.equals(Format.JMOD)) {
                Path path = options.output;
                if (path == null)
                    throw new BadArgs("err.output.must.be.specified").showUsage(true);
                if (Files.exists(path))
                    throw new BadArgs("err.file.already.exists", path);
                if (options.moduleName == null)
                    throw new BadArgs("err.module.must.be.specified").showUsage(true);
            }

            // additional option combination validation

            boolean ok = run();
            return ok ? EXIT_OK : EXIT_ERROR;
        } catch (BadArgs e) {
            reportError(e.key, e.args);
            if (e.showUsage) {
                log.println(getMessage("main.usage.summary", PROGNAME));
            }
            return EXIT_CMDERR;
        } catch (Exception x) {
            x.printStackTrace();
            return EXIT_ABNORMAL;
        } finally {
            log.flush();
        }
    }

    private boolean run() throws IOException {
        if (Format.IMAGE.equals(options.format))
            createImage();
        else if (Format.JMOD.equals(options.format))
            createJmod();
        else
            throw new InternalError("should never reach here");

        return true;
    }

    private static final String APP_DIR = "lib" + File.separator + "app";

    private void createImage() throws IOException {
        final List<Path> jmods = options.jmods;
        final List<Path> jars = options.classpath;
        final Path output = options.output;

        //assert hasZipFileProvider(): "Zip File System Provider not available";
        if (!hasZipFileProvider())
            throw new InternalError("Zip File System Provider not available");

        Path libPath = output.resolve("lib");
        Files.createDirectories(libPath);
        Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        Path classes = libPath.resolve(Section.CLASSES.imageDir());
        URI uri = URI.create("jar:file:" + classes.toUri().getPath());

        try (FileSystem classesfs = FileSystems.newFileSystem(uri, env)) {
            for (Path jmod : jmods)
                unzip(jmod, output, classesfs);
        }

        Path appJar = output.resolve(APP_DIR).resolve("app.jar");
        if (jars != null) {
            if (Files.notExists(appJar.getParent()))
                Files.createDirectory(appJar.getParent());
            //for (Path jar : jars)     // ## support multiple jars
            //    Files.copy(jar, appDir.resolve(jar.getFileName()));
            Files.copy(jars.get(0), appJar);
        }

        for (Map.Entry<String,String> e : options.launchers.entrySet()) {
            // create a script to launch main class to support multiple commands
            // before the proper launcher support is added.
            StringBuilder sb = new StringBuilder();
            sb.append("#!/bin/sh").append("\n");
            sb.append("DIR=`dirname $0`").append("\n");
            sb.append("$DIR/java -cp $DIR/../lib/app/app.jar ");
            sb.append(e.getValue()).append(" $@\n");
            Path cmd = output.resolve("bin").resolve(e.getKey());
            try (BufferedWriter writer = Files.newBufferedWriter(cmd,
                                                            StandardCharsets.ISO_8859_1,
                                                            StandardOpenOption.CREATE_NEW)) {
                writer.write(sb.toString());
            }
            Files.setPosixFilePermissions(cmd, PosixFilePermissions.fromString("r-xr-xr-x"));
        }
    }

    private void createJmod() throws IOException {
        final List<Path> cmds = options.cmds;
        final List<Path> libs = options.libs;
        final List<Path> configs = options.configs;
        final List<Path> classes = options.classpath;
        final Path output = options.output;
        final String moduleName = options.moduleName;

        //assert hasZipFileProvider(): "Zip File System Provider not available";
        if (!hasZipFileProvider())
            throw new InternalError("Zip File System Provider not available");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        URI uri = URI.create("jar:file:" + output.toUri().getPath());

        try (FileSystem jmodfs = FileSystems.newFileSystem(uri, env)) {
            // module name
            Path path = jmodfs.getPath("module");
            Files.createDirectory(path);
            path = path.resolve("name");
            try (BufferedWriter writer = Files.newBufferedWriter(path,
                                                                 StandardCharsets.ISO_8859_1,
                                                                 StandardOpenOption.CREATE_NEW)) {
                writer.write(moduleName, 0, moduleName.length());
            }
            // classes / services
            processSection(Section.CLASSES, classes, jmodfs);

            processSection(Section.NATIVE_CMDS, cmds, jmodfs);
            processSection(Section.NATIVE_LIBS, libs, jmodfs);
            processSection(Section.CONFIG, configs, jmodfs);
        }
    }

    static void processSection(Section section, List<Path> paths, FileSystem jmodfs)
        throws IOException
    {
        String prefix = section.jmodDir();
        if (paths == null)
            return;
        for (Path p : paths)
            Files.walkFileTree(p, new CopyFileVisitor(p.toString(),
                                                      jmodfs.getPath(prefix)));
    }

    private static String SERVICES = "META-INF/services";

    static class CopyFileVisitor extends SimpleFileVisitor<Path> {

        final String pathPrefix;
        final int pathPrefixLength;
        final Path jmodPrefix;

        CopyFileVisitor(String pathPrefix, Path jmodPrefix) {
            this.pathPrefix = pathPrefix;
            this.jmodPrefix = jmodPrefix;
            this.pathPrefixLength = pathPrefix.length();
        }

        @Override
        public FileVisitResult visitFile(Path file,
                                         BasicFileAttributes attrs)
            throws IOException
        {
            assert file.toString().startsWith(pathPrefix);
            String f = file.toString().substring(pathPrefixLength + 1);
            if (f.startsWith(SERVICES))
                f = "../" + JlinkTask.Section.MODULE_SERVICES.jmodDir()
                    + "/" + f.toString().substring(SERVICES.length() + 1);
            Path dstFile = jmodPrefix.resolve(f);
            Files.copy(file, dstFile);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir,
                                                 BasicFileAttributes attrs)
            throws IOException
        {
            Path dirToCreate;
            if (dir.toString().equals(pathPrefix)) {
                dirToCreate = jmodPrefix;
            } else {
                assert dir.toString().startsWith(pathPrefix);
                String d = dir.toString().substring(pathPrefixLength + 1);
                if (d.equals(SERVICES))
                    d = "../" + JlinkTask.Section.MODULE_SERVICES.jmodDir();
                else if (d.startsWith(SERVICES))
                    d = "../" + JlinkTask.Section.MODULE_SERVICES.jmodDir()
                        + d.toString().substring(SERVICES.length() + 1);
                dirToCreate = jmodPrefix.resolve(d);
            }
            if (Files.notExists(dirToCreate))
                Files.createDirectories(dirToCreate);
            return FileVisitResult.CONTINUE;
        }
    }

    private static final String CLASSES_JAR = "jake.jar";
    private static enum Section {
        NATIVE_LIBS("native", "lib"),
        NATIVE_CMDS("bin", "bin"),
        CLASSES("classes", CLASSES_JAR),
        CONFIG("conf", "lib"),
        MODULE_SERVICES("module/services", CLASSES_JAR),
        MODULE_NAME("module", "lib/module"),
        UNKNOWN("unknown", "unknown");

        private final String jmodDir;
        private final String imageDir;

        Section(String jmodDir, String imageDir) {
            this.jmodDir = jmodDir;
            this.imageDir = imageDir;
        }

        String imageDir() { return imageDir; }
        String jmodDir() { return jmodDir; }

        boolean matches(Path path) {
            return path.startsWith("/" + jmodDir);
        }

        static Section getSectionFromPath(Path dir) {
            if (Section.NATIVE_LIBS.matches(dir))
                return Section.NATIVE_LIBS;
            else if (Section.NATIVE_CMDS.matches(dir))
                return Section.NATIVE_CMDS;
            else if (Section.CLASSES.matches(dir))
                return Section.CLASSES;
            else if (Section.CONFIG.matches(dir))
                return Section.CONFIG;
            else if (Section.MODULE_SERVICES.matches(dir))
                return Section.MODULE_SERVICES;
            else if (Section.MODULE_NAME.matches(dir))
                return Section.MODULE_NAME;
            else
                return Section.UNKNOWN;
        }
    }

    static void unzip(Path zipFile, final Path dstDir, final FileSystem classes)
        throws IOException
    {
        try (FileSystem zipFs = FileSystems.newFileSystem(zipFile, null)){
            Files.walkFileTree(zipFs.getPath("/"), new SimpleFileVisitor<Path>(){
                @Override
                public FileVisitResult visitFile(Path file,
                                                 BasicFileAttributes attrs)
                    throws IOException
                {
                    if (file.toString().endsWith("module-info.class")) // ## hack remove
                        return FileVisitResult.CONTINUE;

                    Section section = Section.getSectionFromPath(file);
                    if (Section.UNKNOWN.equals(section))
                        return FileVisitResult.CONTINUE; // skip unknown data

                    Path dstFile;
                    String filename = file.subpath(1, file.getNameCount()).toString();
                    switch (section) {
                        case CLASSES:
                            dstFile = classes.getPath(filename);
                            break;
                        case MODULE_SERVICES:
                            dstFile = classes.getPath("META-INF").resolve(filename);
                            break;
                        default:
                            Path path = Paths.get(section.imageDir()).resolve(filename);
                            dstFile = dstDir.resolve(path);
                    }

                    if (Files.exists(dstFile) && Section.MODULE_NAME.equals(section))
                        append(dstFile, file);
                    else
                        Files.copy(file, dstFile);

                    setExecutable(section, dstFile);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir,
                                                         BasicFileAttributes attrs)
                    throws IOException
                {
                    Section section = Section.getSectionFromPath(dir);
                    if (Section.UNKNOWN.equals(section))
                        return FileVisitResult.CONTINUE; // skip unknown data

                    Path dirToCreate;
                    String subdir = dir.getNameCount() > 1
                                        ? dir.subpath(1, dir.getNameCount()).toString()
                                        : "";
                    switch (section) {
                        case CLASSES:
                            if (dir.getNameCount() <= 1)
                                return FileVisitResult.CONTINUE;
                            dirToCreate = classes.getPath(subdir);
                            break;
                        case MODULE_SERVICES:
                            if (dir.getNameCount() <= 1)
                                return FileVisitResult.CONTINUE;
                            dirToCreate = classes.getPath("META-INF").resolve(subdir);
                            break;
                        default:
                            Path path = Paths.get(section.imageDir());
                            if (dir.getNameCount() > 1)
                                path = path.resolve(subdir);
                            dirToCreate = dstDir.resolve(path);
                            break;
                    }
                    if (Files.notExists(dirToCreate))
                        Files.createDirectories(dirToCreate);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    static void append(Path dstFile, Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(dstFile, StandardOpenOption.APPEND)) {
            Files.copy(file, out);
        }
    }

    private static void setExecutable(Section section, Path path) {
        if (Section.NATIVE_CMDS.equals(section))
            path.toFile().setExecutable(true);
    }

    static boolean hasZipFileProvider() {
        for (FileSystemProvider provider: FileSystemProvider.installedProviders())
             if (provider.getScheme().equalsIgnoreCase("jar"))
                 return true;
        return false;
    }


    public void handleOptions(String[] args) throws BadArgs {
        // process options
        for (int i=0; i < args.length; i++) {
            if (args[i].charAt(0) == '-') {
                String name = args[i];
                Option option = getOption(name);
                String param = null;
                if (option.hasArg) {
                    if (name.startsWith("--") && name.indexOf('=') > 0) {
                        param = name.substring(name.indexOf('=') + 1, name.length());
                    } else if (i + 1 < args.length) {
                        param = args[++i];
                    }
                    if (param == null || param.isEmpty() || param.charAt(0) == '-') {
                        throw new BadArgs("err.missing.arg", name).showUsage(true);
                    }
                }
                option.process(this, name, param);
                if (option.ignoreRest()) {
                    i = args.length;
                }
            } else {
                // process rest of the input arguments
                // ## for now no additional args
            }
        }
    }

    private Option getOption(String name) throws BadArgs {
        for (Option o : recognizedOptions) {
            if (o.matches(name)) {
                return o;
            }
        }
        throw new BadArgs("err.unknown.option", name).showUsage(true);
    }

    private void reportError(String key, Object... args) {
        log.println(getMessage("error.prefix") + " " + getMessage(key, args));
    }

    private void warning(String key, Object... args) {
        log.println(getMessage("warn.prefix") + " " + getMessage(key, args));
    }

    private void showHelp() {
        log.println(getMessage("main.usage", PROGNAME));
        for (Option o : recognizedOptions) {
            String name = o.aliases[0].substring(1); // there must always be at least one name
            name = name.charAt(0) == '-' ? name.substring(1) : name;
            if (o.isHidden() || name.equals("h")) {
                continue;
            }
            log.println(getMessage("main.opt." + name));
        }
    }

    private void showVersion(boolean full) {
        log.println(version(full ? "full" : "release"));
    }

    private String version(String key) {
        if (ResourceBundleHelper.versionRB == null)
            return System.getProperty("java.version");

        try {
            return ResourceBundleHelper.versionRB.getString(key);
        } catch (MissingResourceException e) {
            return getMessage("version.unknown", System.getProperty("java.version"));
        }
    }

    static String getMessage(String key, Object... args) {
        try {
            return MessageFormat.format(ResourceBundleHelper.bundle.getString(key), args);
        } catch (MissingResourceException e) {
            throw new InternalError("Missing message: " + key);
        }
    }

    private static class ResourceBundleHelper {
        static final ResourceBundle versionRB;
        static final ResourceBundle bundle;

        static {
            Locale locale = Locale.getDefault();
            try {
                bundle = ResourceBundle.getBundle("com.sun.tools.jlink.resources.jlink", locale);
            } catch (MissingResourceException e) {
                throw new InternalError("Cannot find jlink resource bundle for locale " + locale);
            }
            try {
                versionRB = ResourceBundle.getBundle("com.sun.tools.jlink.resources.version");
            } catch (MissingResourceException e) {
                throw new InternalError("version.resource.missing");
            }
        }
    }
}
