/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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

package jdk.tools.jmod;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.module.ModuleArtifact;
import java.lang.module.ModuleArtifactFinder;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor;
import java.lang.module.Version;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import jdk.internal.module.Hasher;
import jdk.internal.module.Hasher.DependencyHashes;
import jdk.internal.module.ModuleInfoExtender;


/**
 * Implementation for the jmod tool.
 */
class JmodTask {
    static class BadArgs extends Exception {
        static final long serialVersionUID = 0L;  // ## re-generate
        BadArgs(String key, Object... args) {
            super(JmodTask.getMessage(key, args));
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

        abstract void process(JmodTask task, String opt, String arg) throws BadArgs;
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
            void process(JmodTask task, String opt, String arg) throws BadArgs {
                task.options.classpath = splitPath(arg, File.pathSeparator);
            }
        },
        new Option(true, "--cmds") {
            void process(JmodTask task, String opt, String arg) throws BadArgs {
                task.options.cmds = splitPath(arg, File.pathSeparator);
            }
        },
        new Option(true, "--config") {
            void process(JmodTask task, String opt, String arg) throws BadArgs {
                task.options.configs = splitPath(arg, File.pathSeparator);
            }
        },
        new Option(false, "--help") {
            void process(JmodTask task, String opt, String arg) {
                task.options.help = true;
            }
        },
        new Option(true, "--modulepath", "--mp") {
            void process(JmodTask task, String opt, String arg) {
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
            void process(JmodTask task, String opt, String arg) throws BadArgs {
                task.options.libs = splitPath(arg, File.pathSeparator);
            }
        },
        new Option(true, "--main-class") {
            void process(JmodTask task, String opt, String arg) throws BadArgs {
                task.options.mainClass = arg;
            }
        },
        new Option(true, "--module-version") {
            void process(JmodTask task, String opt, String arg) throws BadArgs {
                task.options.moduleVersion = Version.parse(arg);
            }
        },
        new Option(true, "--hash-dependences") {
            void process(JmodTask task, String opt, String arg) throws BadArgs {
                try {
                    task.options.dependencesToHash = Pattern.compile(arg);
                } catch (PatternSyntaxException e) {
                    throw new BadArgs("err.badpattern", arg);
                }
            }
        },
        new Option(false, "--version") {
            void process(JmodTask task, String opt, String arg) {
                task.options.version = true;
            }
        },
        new HiddenOption(false, "--fullversion") {
            void process(JmodTask task, String opt, String arg) {
                task.options.fullVersion = true;
            }
        },
    };

    private static final String PROGNAME = "jmod";
    private final Options options = new Options();

    private PrintWriter log;
    void setLog(PrintWriter out) {
        log = out;
    }

    private static final String MODULE_INFO = "module-info.class";


    /**
     * Result codes.
     */
    static final int EXIT_OK = 0, // Completed with no errors.
                     EXIT_ERROR = 1, // Completed but reported errors.
                     EXIT_CMDERR = 2, // Bad command-line arguments
                     EXIT_SYSERR = 3, // System error or resource exhaustion.
                     EXIT_ABNORMAL = 4;// terminated abnormally

    static class Options {
        Task task;
        Path jmodFile;
        boolean help;
        boolean version;
        boolean fullVersion;
        List<Path> classpath;
        List<Path> cmds;
        List<Path> configs;
        List<Path> libs;
        ModuleArtifactFinder moduleFinder;
        Version moduleVersion;
        String mainClass;
        Pattern dependencesToHash;
    }

    enum Task {
        CREATE,
        LIST
    };

    int run(String[] args) {
        if (log == null) {
            log = new PrintWriter(System.out);
        }
        try {
            handleOptions(args);
            if (options.help) {
                showHelp();
                return EXIT_OK;
            }
            if (options.version || options.fullVersion) {
                showVersion(options.fullVersion);
                return EXIT_OK;
            }

            boolean ok;
            switch (options.task) {
                case CREATE:
                    ok = create(options);
                    break;
                case LIST:
                    ok = list(options);
                    break;
                default:
                    throw new BadArgs("err.invalid.task", options.task.name()).showUsage(true);
            }

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

    private boolean create(Options options) throws IOException, BadArgs {
        Path path = options.jmodFile;
        if (path == null)
            throw new BadArgs("err.jmod.must.be.specified").showUsage(true);
        if (Files.exists(path))
            throw new BadArgs("err.file.already.exists", path);

        // if storing hashes of dependences then the module path is required
        if (options.dependencesToHash != null && options.moduleFinder == null)
            throw new BadArgs("err.modulepath.must.be.specified").showUsage(true);

        createJmod();
        return true;
    }

    private boolean list(Options options) throws IOException, BadArgs {
        Path path = options.jmodFile;
        if (path == null)
            throw new BadArgs("err.output.must.be.specified").showUsage(true);
        if (Files.notExists(path))
            throw new BadArgs("err.jmod.not.found", path);

        listJmod(path);
        return true;
    }

    private void listJmod(Path path) throws IOException {
        ZipFile zip = new ZipFile(path.toFile());

        // Trivially print the archive entries for now, pending a more complete implementation
        zip.stream().forEach(e -> log.println(e.getName()));
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
                // jmod:file:/home/duke/duke.jmod!/ -> file:/home/duke/duke.jmod
                String s = location.toString();
                fileURI = URI.create(s.substring(5, s.length()-2));
            } else {
                // jar:file:/home/duke/duke.jar!/ -> file:/home/duke/duke.jar
                String s = location.toString();
                fileURI = URI.create(s.substring(4, s.length()-2));
            }

            modPaths.put(name, Paths.get(fileURI));
        }
        return modPaths;
    }

    private void createJmod() throws IOException {
        JmodFileWriter jmod = new JmodFileWriter();

        // create jmod with temporary name to avoid it being examined
        // when scanning the module path
        Path target = options.jmodFile;
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
        final Version moduleVersion = options.moduleVersion;
        final String mainClass = options.mainClass;
        final ModuleArtifactFinder moduleFinder = options.moduleFinder;
        final Pattern dependencesToHash = options.dependencesToHash;

        JmodFileWriter() { }

        /**
         * Writes the jmod to the given output stream.
         */
        void write(OutputStream out) throws IOException {
            try (ZipOutputStream zos = new ZipOutputStream(out)) {

                // module-info.class
                writeModuleInfo(zos);

                // classes
                processClasses(zos, classpath);

                processSection(zos, Section.NATIVE_CMDS, cmds);
                processSection(zos, Section.NATIVE_LIBS, libs);
                processSection(zos, Section.CONFIG, configs);
            }
        }

        /**
         * Returns a supplier of an input stream to the module-info.class
         * on the class path of directories and JAR files.
         */
        Supplier<InputStream> newModuleInfoSupplier() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (Path e: classpath) {
                if (Files.isDirectory(e)) {
                    Path mi = e.resolve(MODULE_INFO);
                    if (Files.isRegularFile(mi)) {
                        Files.copy(mi, baos);
                        break;
                    }
                } else if (Files.isRegularFile(e) && e.toString().endsWith(".jar")) {
                    try (JarFile jf = new JarFile(e.toFile())) {
                        ZipEntry entry = jf.getEntry(MODULE_INFO);
                        if (entry != null) {
                            jf.getInputStream(entry).transferTo(baos);
                            break;
                        }
                    }
                }
            }
            if (baos.size() == 0) {
                return null;
            } else {
                byte[] bytes = baos.toByteArray();
                return () -> new ByteArrayInputStream(bytes);
            }
        }

        /**
         * Writes the module-info.class to the given ZIP output stream.
         *
         * If --module-version, --main-class, or other options were provided
         * then the corresponding class file attributes are added to the
         * module-info here.
         */
        void writeModuleInfo(ZipOutputStream zos) throws IOException {

            Supplier<InputStream> miSupplier = newModuleInfoSupplier();
            if (miSupplier == null) {
                throw new IOException(MODULE_INFO + " not found");
            }

            // if --hash-dependences  is specified then we need the module name
            // and dependences from the Module attribute
            String name = null;
            Set<Requires> dependences = null;
            if (dependencesToHash != null) {
                try (InputStream in = miSupplier.get()) {
                    ModuleDescriptor md = ModuleDescriptor.read(in);
                    name = md.name();
                    dependences = md.requires();
                }
            }

            // copy the module-info.class into the jmod with the additional
            // attributes for the version, main class and other meta data
            try (InputStream in = miSupplier.get()) {
                ModuleInfoExtender extender = ModuleInfoExtender.newExtender(in);

                // --main-class
                if (mainClass != null)
                    extender.mainClass(mainClass);

                // --module-version
                if (moduleVersion != null)
                    extender.version(moduleVersion);

                // --hash-dependences
                if (dependencesToHash != null)
                    extender.hashes(hashDependences(name, dependences));

                // write the (possibly extended or modified) module-info.class
                String e = Section.CLASSES.jmodDir() + "/" + MODULE_INFO;
                ZipEntry ze = new ZipEntry(e);
                zos.putNextEntry(ze);
                extender.write(zos);
                zos.closeEntry();
            }
        }

        /**
         * Examines the module dependences of the given module
         * and computes the hash of any module that matches the
         * pattern {@code dependencesToHash}.
         */
        DependencyHashes hashDependences(String name, Set<Requires> moduleDependences)
            throws IOException
        {
            Set<ModuleDescriptor> descriptors = new HashSet<>();
            for (Requires md: moduleDependences) {
                String dn = md.name();
                if (dependencesToHash.matcher(dn).find()) {
                    ModuleArtifact artifact = moduleFinder.find(dn);
                    if (artifact == null) {
                        throw new RuntimeException("Hashing module " + name
                            + " dependences, unable to find module " + dn
                            + " on module path");
                    }
                    descriptors.add(artifact.descriptor());
                }
            }

            Map<String, Path> map = modulesToPath(descriptors);
            if (map.size() == 0) {
                return null;
            } else {
                // use SHA-256 for now, easy to make this configurable if needed
                return Hasher.generate(map, "SHA-256");
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
                } else if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) {
                    try (JarFile jf = new JarFile(p.toFile())) {
                        JarEntryConsumer jec = new JarEntryConsumer(zos, jf);
                        jf.stream().filter(jec).forEach(jec);
                    }
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

        void processSection(ZipOutputStream zos, Section section, Path top)
            throws IOException
        {
            Files.walkFileTree(top, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException
                {
                    if (!file.getFileName().toString().equals(MODULE_INFO)) {
                        Path relPath = top.relativize(file);
                        String prefix = section.jmodDir();
                        try (InputStream in = Files.newInputStream(file)) {
                            writeZipEntry(zos, in, prefix, relPath.toString());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        void writeZipEntry(ZipOutputStream zos, InputStream in, String prefix, String other)
            throws IOException
        {
            String name = Paths.get(prefix, other).toString()
                               .replace(File.separatorChar, '/');
            ZipEntry ze = new ZipEntry(name);
            zos.putNextEntry(ze);
            in.transferTo(zos);
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
                try (InputStream in = jarfile.getInputStream(je)) {
                    writeZipEntry(zos, in, Section.CLASSES.jmodDir(), je.getName());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            @Override
            public boolean test(JarEntry je) {
                String name = je.getName();
                return !name.endsWith(MODULE_INFO) && !je.isDirectory();
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
        int count = 0;
        if (args.length == 0) {
            options.help = true;
            return;
        }

        String arg = args[count];
        if (arg.startsWith("-")) {
            options.help = true;
            return;
        }

        try {
            options.task = Enum.valueOf(Task.class, arg.toUpperCase());
            count++;
        } catch (IllegalArgumentException e) {
            throw new BadArgs("err.invalid.task", arg).showUsage(true);
        }

        // process options
        for (; count < args.length; count++) {
            if (args[count].charAt(0) != '-')
                break;

            String name = args[count];
            Option option = getOption(name);
            String param = null;
            if (option.hasArg) {
                if (name.startsWith("--") && name.indexOf('=') > 0) {
                    param = name.substring(name.indexOf('=') + 1, name.length());
                } else if (count + 1 < args.length) {
                    param = args[++count];
                }
                if (param == null || param.isEmpty() || param.charAt(0) == '-') {
                    throw new BadArgs("err.missing.arg", name).showUsage(true);
                }
            }
            option.process(this, name, param);
            if (option.ignoreRest()) {
                count = args.length;
            }
        }
        try {
            options.jmodFile = Paths.get(args[count]);
            count++;
        } catch (IndexOutOfBoundsException e) {
            throw new BadArgs("err.jmod.must.be.specified", arg).showUsage(true);
        }
        if (args.length > count) {
            throw new BadArgs("err.unknown.option", args[count+1]).showUsage(true);
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
        // ## removed version.properties-template as jmod now moved to jdk repo
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
                bundle = ResourceBundle.getBundle("jdk.tools.jmod.resources.jmod", locale);
            } catch (MissingResourceException e) {
                throw new InternalError("Cannot find jmod resource bundle for locale " + locale);
            }
        }
    }
}
