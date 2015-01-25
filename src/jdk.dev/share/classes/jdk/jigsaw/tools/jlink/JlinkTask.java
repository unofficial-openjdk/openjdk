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

package jdk.jigsaw.tools.jlink;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import jdk.internal.jimage.Archive;
import jdk.internal.jimage.ImageFile;
import jdk.internal.jimage.ImageModules;
import jdk.internal.jimage.JmodArchive;
import jdk.internal.jimage.ModularJarArchive;
import jdk.jigsaw.module.Configuration;
import jdk.jigsaw.module.Layer;
import jdk.jigsaw.module.ModuleArtifact;
import jdk.jigsaw.module.ModuleArtifactFinder;
import jdk.jigsaw.module.ModuleDependence;
import jdk.jigsaw.module.ModuleDescriptor;
import jdk.jigsaw.module.ModuleId;
import jdk.jigsaw.module.internal.ControlFile;
import jdk.jigsaw.module.internal.Hasher;
import jdk.jigsaw.module.internal.ModuleInfo;


/**
 * Implementation for the jlink tool.
 *
 * ## Should use jdk.joptsimple some day.
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

    private static Path CWD = Paths.get("");

    private static List<Path> splitPath(String arg, String separator)
        throws BadArgs
    {
        List<Path> paths = new ArrayList<>();
        for (String p: arg.split(separator)) {
            if (p.length() > 0) {
                try {
                    Path path = CWD.resolve(p);
                    if (Files.notExists(path)) {
                        throw new BadArgs("err.path.not.found", path);
                    }
                    paths.add(path);
                } catch (InvalidPathException x) {
                    throw new BadArgs("err.path.not.valid", p);
                }
            }
        }
        return paths;
    }

    static <T extends Throwable> void fail(Class<T> type,
                                           String format,
                                           Object... args) throws T {
        String msg = new Formatter().format(format, args).toString();
        try {
            T t = type.getConstructor(String.class).newInstance(msg);
            throw t;
        } catch (InstantiationException |
                 InvocationTargetException |
                 NoSuchMethodException |
                 IllegalAccessException e) {
            throw new InternalError("Unable to create an instance of " + type, e);
        }
    }

    static Option[] recognizedOptions = {
        new Option(true, "--class-path") {
            void process(JlinkTask task, String opt, String arg) throws BadArgs {
                task.options.classpath = splitPath(arg, File.pathSeparator);
            }
        },
        new Option(true, "--cmds") {
            void process(JlinkTask task, String opt, String arg) throws BadArgs {
                task.options.cmds = splitPath(arg, File.pathSeparator);
            }
        },
        new Option(true, "--config") {
            void process(JlinkTask task, String opt, String arg) throws BadArgs {
                task.options.configs = splitPath(arg, File.pathSeparator);
            }
        },
        new Option(true, "--format") {
            void process(JlinkTask task, String opt, String arg) throws BadArgs {
                try {
                    task.options.format = Enum.valueOf(Format.class, arg.toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new BadArgs("err.invalid.arg.for.option", opt).showUsage(true);
                }
            }
        },
        new Option(false, "--compress") {
            void process(JlinkTask task, String opt, String arg) throws BadArgs {
                task.options.compress = true;
            }
        },
        new Option(false, "--help") {
            void process(JlinkTask task, String opt, String arg) {
                task.options.help = true;
            }
        },
        new Option(true, "--modulepath", "--mp") {
            void process(JlinkTask task, String opt, String arg) {
                String[] dirs = arg.split(File.pathSeparator);
                Path[] paths = new Path[dirs.length];
                int i = 0;
                for (String dir: dirs) {
                    paths[i++] = Paths.get(dir);
                }
                task.options.moduleFinder = ModuleArtifactFinder.ofDirectories(paths);
            }
        },
        new Option(true, "--libs") {
            void process(JlinkTask task, String opt, String arg) throws BadArgs {
                task.options.libs = splitPath(arg, File.pathSeparator);
            }
        },
        new Option(true, "--main-class") {
            void process(JlinkTask task, String opt, String arg) throws BadArgs {
                task.options.mainClass = arg;
            }
        },
        new Option(true, "--mid") {
            void process(JlinkTask task, String opt, String arg) throws BadArgs {
                task.options.moduleId = arg;
            }
        },
        new Option(true, "--hash-dependences") {
            void process(JlinkTask task, String opt, String arg) throws BadArgs {
                try {
                    task.options.dependencesToHash = Pattern.compile(arg);
                } catch (PatternSyntaxException e) {
                    throw new BadArgs("err.badpattern", arg);
                }
            }
        },
        new Option(true, "--addmods") {
            void process(JlinkTask task, String opt, String arg) throws BadArgs {
                for (String mn : arg.split(",")) {
                    if (mn.isEmpty())
                        throw new BadArgs("err.jmod.not.found", mn);
                    task.options.jmods.add(mn);
                }
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

    /** Module list files, in java.base */
    private static final String BOOT_MODULES = "boot.modules";
    private static final String EXT_MODULES = "ext.modules";
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
        JIMAGE;
    }

    static class Options {
        boolean help;
        boolean version;
        boolean fullVersion;
        List<Path> classpath;
        List<Path> cmds;
        List<Path> configs;
        List<Path> libs;
        ModuleArtifactFinder moduleFinder;
        Set<String> jmods = new TreeSet<>();
        Format format = null;
        boolean compress = false;
        Path output;
        String moduleId;
        String mainClass;
        Pattern dependencesToHash;
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
            } else if (options.format == Format.JIMAGE) {
                if (options.moduleFinder == null)
                    throw new BadArgs("err.modulepath.must.be.specified").showUsage(true);

                Path output = options.output;
                if (output == null)
                    throw new BadArgs("err.output.must.be.specified").showUsage(true);
                Files.createDirectories(output);
                if (Files.list(output).findFirst().isPresent())
                    throw new BadArgs("err.dir.not.empty", output);

                if (options.jmods.isEmpty())  // ## default to jdk.base ??
                    throw new BadArgs("err.mods.must.be.specified").showUsage(true);

            } else if (options.format.equals(Format.JMOD)) {
                Path path = options.output;
                if (path == null)
                    throw new BadArgs("err.output.must.be.specified").showUsage(true);
                if (Files.exists(path))
                    throw new BadArgs("err.file.already.exists", path);

                // if storing hashes of dependences then the module path is required
                if (options.dependencesToHash != null && options.moduleFinder == null)
                    throw new BadArgs("err.modulepath.must.be.specified").showUsage(true);
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
        if (options.format == Format.JIMAGE)
            createImage();
        else if (Format.JMOD.equals(options.format))
            createJmod();
        else
            throw new InternalError("should never reach here");

        return true;
    }

    private Map<String, Path> modulesToPath(Set<ModuleDescriptor> modules) {
        ModuleArtifactFinder finder = options.moduleFinder;

        Map<String,Path> modPaths = new HashMap<>();
        for (ModuleDescriptor m : modules) {
            String name = m.name();

            ModuleArtifact artifact = finder.find(name);
            if (artifact == null) {
                // this should not happen, module path bug?
                fail(InternalError.class,
                     "Selected module %s not on module path",
                     name);
            }

            URI location = artifact.location();
            String scheme = location.getScheme();
            if (!scheme.equalsIgnoreCase("jmod") && !scheme.equalsIgnoreCase("jar")) {
                fail(RuntimeException.class,
                     "Selected module %s (%s) not in jmod or modular jar format",
                     name,
                     location);
            }

            // convert to file URIs
            URI fileURI;
            if (scheme.equalsIgnoreCase("jmod")) {
                // jmod:/home/duke/duke.jmod -> file:/home/duke/duke.jmod
                fileURI = URI.create("file" + location.toString().substring(4));
            } else {
                // jar:file:/home/duke/duke.jar!/ -> file:/home/duke/duke.jar
                String s = location.toString();
                fileURI = URI.create(s.substring(4, s.length()-2));
            }

            modPaths.put(name, Paths.get(fileURI));
        }
        return modPaths;
    }

    /**
     * chmod ugo+x file
     */
    private void setExecutable(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }

    private void createImage() throws IOException {
        final Path output = options.output;

        Configuration cf = Configuration.resolve(options.moduleFinder,
                Layer.emptyLayer(),
                ModuleArtifactFinder.nullFinder(),
                options.jmods);

        Map<String, Path> mods = modulesToPath(cf.descriptors());

        ImageFileHelper imageHelper = new ImageFileHelper(cf, mods);
        imageHelper.createModularImage(output);

        // launchers in the bin directory need execute permission
        Path bin = output.resolve("bin");
        if (Files.getFileStore(bin).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.list(bin)
                 .filter(f -> !f.toString().endsWith(".diz"))
                 .filter(f -> Files.isRegularFile(f))
                 .forEach(this::setExecutable);

            // jspawnhelper
            Path jspawnhelper = output.resolve("lib").resolve("jspawnhelper");
            if (Files.exists(jspawnhelper))
                setExecutable(jspawnhelper);
        }

        // generate launch scripts for the modules with a main class
        for (Map.Entry<String, Path> entry : mods.entrySet()) {
            String module = entry.getKey();
            Path jmodpath = entry.getValue();

            String mainClass = null;
            try (ZipFile zf = new ZipFile(jmodpath.toString())) {
                ZipEntry ze = zf.getEntry(ControlFile.CONTROL_FILE);
                if (ze != null) {
                    try (InputStream in = zf.getInputStream(ze)) {
                        mainClass = ControlFile.parse(in).mainClass();
                    }
                }
            }

            if (mainClass != null) {
                Path cmd = output.resolve("bin").resolve(module);
                if (!Files.exists(cmd)) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("#!/bin/sh")
                      .append("\n");
                    sb.append("DIR=`dirname $0`")
                      .append("\n");
                    sb.append("$DIR/java -m ")
                      .append(module).append('/')
                      .append(mainClass)
                      .append(" $@\n");

                    try (BufferedWriter writer = Files.newBufferedWriter(cmd,
                            StandardCharsets.ISO_8859_1,
                            StandardOpenOption.CREATE_NEW)) {
                        writer.write(sb.toString());
                    }
                    if (Files.getFileStore(bin)
                             .supportsFileAttributeView(PosixFileAttributeView.class)) {
                        setExecutable(cmd);
                    }
                }
            }
        }
    }

    private class ImageFileHelper {
        final Set<ModuleDescriptor> modules;
        final Set<ModuleDescriptor> bootModules;
        final Set<ModuleDescriptor> extModules;
        final Set<ModuleDescriptor> appModules;
        final Map<String,Path> modsPaths;

        ImageFileHelper(Configuration cf, Map<String, Path> modsPaths) throws IOException {
            this.modules = cf.descriptors();
            this.modsPaths = modsPaths;
            Map<String, ModuleDescriptor> mods = new HashMap<>();
            for (ModuleDescriptor m : modules) {
                mods.put(m.name(), m);
            }

            Path baseJmod = modsPaths.get("java.base");
            if (baseJmod == null)
                fail(RuntimeException.class, "java.base not found on modulepath");
            if (!baseJmod.toString().endsWith(".jmod"))
                fail(RuntimeException.class, "java.base not a jmod");

            this.bootModules = readConfFile(baseJmod, BOOT_MODULES)
                    .stream()
                    .filter(mods::containsKey)
                    .map(mods::get)
                    .collect(Collectors.toSet());
            this.extModules = readConfFile(baseJmod, EXT_MODULES)
                    .stream()
                    .filter(mods::containsKey)
                    .map(mods::get)
                    .collect(Collectors.toSet());
            this.appModules = modules.stream()
                    .filter(m -> !bootModules.contains(m) && !extModules.contains(m))
                    .collect(Collectors.toSet());
        }

        void createModularImage(Path output) throws IOException {
            Set<Archive> archives = modsPaths.entrySet().stream()
                    .map(e -> newArchive(e.getKey(), e.getValue()))
                    .collect(Collectors.toSet());
            Set<String> boot = bootModules.stream()
                                          .map(ModuleDescriptor::name)
                                          .collect(Collectors.toSet());
            Set<String> ext = extModules.stream()
                                        .map(ModuleDescriptor::name)
                                        .collect(Collectors.toSet());
            Set<String> app = appModules.stream()
                                        .map(ModuleDescriptor::name)
                                        .collect(Collectors.toSet());

            ImageModules imf = new ImageModules(boot, ext, app);
            ImageFile.create(output, archives, imf, options.compress);
            writeModulesLists(output);
        }

        private Archive newArchive(String module, Path path) {
            if (path.toString().endsWith(".jmod")) {
                return new JmodArchive(module, path);
            } else {
                if (path.toString().endsWith(".jar")) {
                    return new ModularJarArchive(module, path);
                } else {
                    fail(RuntimeException.class,
                     "Selected module %s (%s) not in jmod or modular jar format",
                     module,
                     path);
                }
            }
            return null;
        }

        /**
         * Replace lib/*.modules with the actual list of modules linked in.
         */
        private void writeModulesLists(Path output) throws IOException {
            Path lib = output.resolve("lib");
            writeModuleList(lib.resolve(BOOT_MODULES), bootModules);
            writeModuleList(lib.resolve(EXT_MODULES), extModules);
        }

        private void writeModuleList(Path file, Set<ModuleDescriptor> modules)
            throws IOException
        {
            List<String> list = modules.stream()
                                       .map(ModuleDescriptor::name)
                                       .sorted()
                                       .collect(Collectors.toList());
            Files.write(file, list, StandardCharsets.UTF_8);
        }

        /**
         * Read all lines from a text file in the native section of jmod.
         */
        private List<String> readConfFile(Path jmod, String name) throws IOException {
            List<String> result = new ArrayList<>();
            try (ZipFile zf = new ZipFile(jmod.toString())) {
                String e = Section.NATIVE_LIBS.jmodDir() + "/" + name;
                ZipEntry ze = zf.getEntry(e);
                if (ze == null)
                    throw new IOException(e + " not found in " + jmod);
                try (InputStream in = zf.getInputStream(ze)) {
                    BufferedReader reader =
                        new BufferedReader(new InputStreamReader(in, "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        result.add(line);
                    }
                }
            }
            return result;
        }

    }

    private void createJmod() throws IOException {
        JmodFileWriter jmod = new JmodFileWriter();

        // create jmod with temporary name to avoid it being examined
        // when scanning the module path
        Path target = options.output;
        Path tempTarget = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tempTarget)) {
            jmod.write(out);
        }
        Files.move(tempTarget, target);
    }

    private class JmodFileWriter {
        final List<Path> cmds = options.cmds;
        final List<Path> libs = options.libs;
        final List<Path> configs = options.configs;
        final List<Path> classpath = options.classpath;
        final String moduleId = options.moduleId;
        final String mainClass = options.mainClass;
        final ModuleArtifactFinder moduleFinder = options.moduleFinder;
        final Pattern dependencesToHash = options.dependencesToHash;

        JmodFileWriter() { }

        /**
         * Examines the module dependences of the given module
         * and computes the hash of any module that matches the
         * pattern {@code dependencesToHash}. Returns a string
         * of the hashes.
         */
        String hashDependences(ModuleInfo mi) throws IOException {
            Set<ModuleDescriptor> descriptors = new HashSet<>();
            for (ModuleDependence md: mi.moduleDependences()) {
                String dn = md.id().name();
                if (dependencesToHash.matcher(dn).find()) {
                    ModuleArtifact artifact = moduleFinder.find(dn);
                    if (artifact == null) {
                        throw new RuntimeException("Hashing module " + mi.name()
                            + " dependences, unable to find module " + dn
                            + " on module path");
                    }
                    descriptors.add(artifact.descriptor());
                }
            }

            return Hasher.generateMD5(modulesToPath(descriptors));
        }

        /**
         * Writes the jmod control file with the meta-data for the extended
         * module descriptor.
         */
        void writeControlFile(ZipOutputStream zos) throws IOException {
            ControlFile cf = new ControlFile();

            // main-class
            if (mainClass != null)
                cf.mainClass(mainClass);

            // need module-info.class if if --mid or --hash-dependences
            if (moduleId != null || dependencesToHash != null) {
                Optional<Path> miClass = classpath.stream()
                        .map(cp -> cp.resolve("module-info.class"))
                        .filter(Files::exists)
                        .findFirst();
                if (!miClass.isPresent()) {
                    throw new RuntimeException("module-info.class not found");
                }
                ModuleInfo mi;
                try (InputStream in = Files.newInputStream(miClass.get())) {
                    mi = ModuleInfo.read(in);
                }

                // if --mid is specified then check for a matching module name
                if (moduleId != null) {
                    ModuleId id = ModuleId.parse(moduleId);
                    if (!id.name().equals(mi.name())) {
                        throw new RuntimeException("module name in specified to --mid" +
                                " does not match module name: " + mi.name());
                    }
                    if (id.version() != null)
                        cf.version(id.version().toString());
                }

                // generate the string with the MD5 hashes
                if (dependencesToHash != null) {
                    String s = hashDependences(mi);
                    cf.dependencyHashes(s);
                }
            }

            ZipEntry ze = new ZipEntry(ControlFile.CONTROL_FILE);
            zos.putNextEntry(ze);
            cf.write(zos);
            zos.closeEntry();
        }

        void write(OutputStream out) throws IOException {
            try (ZipOutputStream zos = new ZipOutputStream(out)) {
                // control file
                writeControlFile(zos);

                // classes
                processClasses(zos, classpath);

                processSection(zos, Section.NATIVE_CMDS, cmds);
                processSection(zos, Section.NATIVE_LIBS, libs);
                processSection(zos, Section.CONFIG, configs);
            }
        }

        void processClasses(ZipOutputStream zos, List<Path> classpaths)
            throws IOException
        {
            if (classpaths == null) {
                return;
            }
            for (Path p : classpaths) {
                if (Files.isDirectory(p)) {
                    processSection(zos, Section.CLASSES, p);
                } else if (Files.exists(p) && p.toString().endsWith(".jar")) {
                    JarFile jf = new JarFile(p.toFile());
                    JarEntryConsumer jec = new JarEntryConsumer(zos, jf);
                    jf.stream().filter(jec).forEach(jec);
                }
            }
        }

        void processSection(ZipOutputStream zos, Section section, List<Path> paths)
            throws IOException
        {
            if (paths == null) {
                return;
            }
            for (Path p : paths) {
                processSection(zos, section, p);
            }
        }

        void processSection(ZipOutputStream zos, final Section section, Path p)
            throws IOException
        {
            final String pathPrefix = p.toString();
            final int pathPrefixLength = pathPrefix.length();
            Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file,
                                                 BasicFileAttributes attrs)
                        throws IOException
                {
                    assert file.toString().startsWith(pathPrefix);
                    String f = file.toString().substring(pathPrefixLength + 1);
                    String prefix = section.jmodDir();
                    byte[] data = Files.readAllBytes(file);
                    writeZipEntry(zos, data, prefix, f);
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        void writeZipEntry(ZipOutputStream zos, byte[] data, String prefix, String other)
            throws IOException
        {
            String name = Paths.get(prefix, other).toString()
                               .replace(File.separatorChar, '/');
            ZipEntry ze = new ZipEntry(name);
            zos.putNextEntry(ze);
            zos.write(data, 0, data.length);
            zos.closeEntry();
        }

        class JarEntryConsumer implements Consumer<JarEntry>, Predicate<JarEntry> {
            final ZipOutputStream zos;
            final JarFile jarfile;
            JarEntryConsumer(ZipOutputStream zos, JarFile jarfile) {
                this.zos = zos;
                this.jarfile = jarfile;
            }
            @Override
            public void accept(JarEntry je) {
                try (InputStream in = jarfile.getInputStream(je);
                     DataInputStream din = new DataInputStream(in)) {
                    int size = (int)je.getSize();
                    byte[] data = new byte[size];
                    din.readFully(data);
                    writeZipEntry(zos, data, Section.CLASSES.jmodDir(), je.getName());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            @Override
            public boolean test(JarEntry je) {
                String name = je.getName();
                return !je.isDirectory();
            }
        }
    }

    private static enum Section {
        NATIVE_LIBS("native", nativeDir()),
        NATIVE_CMDS("bin", "bin"),
        CLASSES("classes", "classes"),
        CONFIG("conf", "conf"),
        UNKNOWN("unknown", "unknown");

        private static String nativeDir() {
            if (System.getProperty("os.name").startsWith("Windows")) {
                return "bin";
            } else {
                return "lib";
            }
        }

        private final String jmodDir;
        private final String imageDir;

        Section(String jmodDir, String imageDir) {
            this.jmodDir = jmodDir;
            this.imageDir = imageDir;
        }

        String imageDir() { return imageDir; }
        String jmodDir() { return jmodDir; }

        boolean matches(String path) {
            return path.startsWith(jmodDir);
        }

        static Section getSectionFromName(String dir) {
            if (Section.NATIVE_LIBS.matches(dir))
                return Section.NATIVE_LIBS;
            else if (Section.NATIVE_CMDS.matches(dir))
                return Section.NATIVE_CMDS;
            else if (Section.CLASSES.matches(dir))
                return Section.CLASSES;
            else if (Section.CONFIG.matches(dir))
                return Section.CONFIG;
            else
                return Section.UNKNOWN;
        }
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
        // ## removed version.properties-template as jlink now moved to jdk repo
        return System.getProperty("java.version");
    }

    static String getMessage(String key, Object... args) {
        try {
            return MessageFormat.format(ResourceBundleHelper.bundle.getString(key), args);
        } catch (MissingResourceException e) {
            throw new InternalError("Missing message: " + key);
        }
    }

    private static class ResourceBundleHelper {
        static final ResourceBundle bundle;

        static {
            Locale locale = Locale.getDefault();
            try {
                bundle = ResourceBundle.getBundle("jdk.jigsaw.tools.jlink.resources.jlink", locale);
            } catch (MissingResourceException e) {
                throw new InternalError("Cannot find jlink resource bundle for locale " + locale);
            }
        }
    }
}
