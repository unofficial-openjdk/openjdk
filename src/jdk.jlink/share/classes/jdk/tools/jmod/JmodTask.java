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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.module.FindException;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Version;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
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
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import jdk.internal.joptsimple.BuiltinHelpFormatter;
import jdk.internal.joptsimple.NonOptionArgumentSpec;
import jdk.internal.joptsimple.OptionDescriptor;
import jdk.internal.joptsimple.OptionException;
import jdk.internal.joptsimple.OptionParser;
import jdk.internal.joptsimple.OptionSet;
import jdk.internal.joptsimple.OptionSpec;
import jdk.internal.joptsimple.ValueConverter;
import jdk.internal.module.ConfigurableModuleFinder;
import jdk.internal.module.ConfigurableModuleFinder.Phase;
import jdk.internal.module.Hasher;
import jdk.internal.module.Hasher.DependencyHashes;
import jdk.internal.module.ModuleInfoExtender;

/**
 * Implementation for the jmod tool.
 */
public class JmodTask {

    static class CommandException extends RuntimeException {
        private static final long serialVersionUID = 0L;
        boolean showUsage;

        CommandException(String key, Object... args) {
            super(getMessageOrKey(key, args));
        }

        CommandException showUsage(boolean b) {
            showUsage = b;
            return this;
        }

        private static String getMessageOrKey(String key, Object... args) {
            try {
                return MessageFormat.format(ResourceBundleHelper.bundle.getString(key), args);
            } catch (MissingResourceException e) {
                return key;
            }
        }
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

    private static final String PROGNAME = "jmod";
    private static final String MODULE_INFO = "module-info.class";

    private Options options;
    private PrintStream out = System.out;
    void setLog(PrintStream out) {
        this.out = out;
    }

    /* Result codes. */
    static final int EXIT_OK = 0, // Completed with no errors.
                     EXIT_ERROR = 1, // Completed but reported errors.
                     EXIT_CMDERR = 2, // Bad command-line arguments
                     EXIT_SYSERR = 3, // System error or resource exhaustion.
                     EXIT_ABNORMAL = 4;// terminated abnormally

    enum Task {
        CREATE,
        LIST
    };

    static class Options {
        Task task;
        Path jmodFile;
        boolean help;
        boolean version;
        List<Path> classpath;
        List<Path> cmds;
        List<Path> configs;
        List<Path> libs;
        ModuleFinder moduleFinder;
        Version moduleVersion;
        String mainClass;
        Pattern dependenciesToHash;
        List<PathMatcher> excludes;
    }

    public int run(String[] args) {

        try {
            handleOptions(args);
            if (options == null) {
                showUsageSummary();
                return EXIT_CMDERR;
            }
            if (options.help) {
                showHelp();
                return EXIT_OK;
            }
            if (options.version) {
                showVersion();
                return EXIT_OK;
            }

            boolean ok;
            switch (options.task) {
                case CREATE:
                    ok = create();
                    break;
                case LIST:
                    ok = list();
                    break;
                default:
                    throw new AssertionError("Unknown task: " + options.task.name());
            }

            return ok ? EXIT_OK : EXIT_ERROR;
        } catch (CommandException e) {
            reportError(e.getMessage());
            if (e.showUsage)
                showUsageSummary();
            return EXIT_CMDERR;
        } catch (Exception x) {
            reportError(x.getMessage());
            x.printStackTrace();
            return EXIT_ABNORMAL;
        } finally {
            out.flush();
        }
    }

    private boolean list() throws IOException {
        ZipFile zip = null;
        try {
            try {
                zip = new ZipFile(options.jmodFile.toFile());
            } catch (IOException x) {
                throw new IOException("error opening jmod file", x);
            }

            // Trivially print the archive entries for now, pending a more complete implementation
            zip.stream().forEach(e -> out.println(e.getName()));
            return true;
        } finally {
            if (zip != null)
                zip.close();
        }
    }

    private Map<String, Path> modulesToPath(Set<ModuleDescriptor> modules) {
        ModuleFinder finder = options.moduleFinder;

        Map<String,Path> modPaths = new HashMap<>();
        for (ModuleDescriptor m : modules) {
            String name = m.name();

            Optional<ModuleReference> omref = finder.find(name);
            if (!omref.isPresent()) {
                // this should not happen, module path bug?
                fail(InternalError.class,
                     "Selected module %s not on module path",
                     name);
            }

            URI location = omref.get().location().get();
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

    private boolean create() throws IOException {
        JmodFileWriter jmod = new JmodFileWriter();

        // create jmod with temporary name to avoid it being examined
        // when scanning the module path
        Path target = options.jmodFile;
        Path tempTarget = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tempTarget)) {
            jmod.write(out);
        }
        Files.move(tempTarget, target);
        return true;
    }

    private class JmodFileWriter {
        final List<Path> cmds = options.cmds;
        final List<Path> libs = options.libs;
        final List<Path> configs = options.configs;
        final List<Path> classpath = options.classpath;
        final Version moduleVersion = options.moduleVersion;
        final String mainClass = options.mainClass;
        final ModuleFinder moduleFinder = options.moduleFinder;
        final Pattern dependenciesToHash = options.dependenciesToHash;
        final List<PathMatcher> excludes = options.excludes;

        JmodFileWriter() { }

        /**
         * Writes the jmod to the given output stream.
         */
        void write(OutputStream out) throws IOException {
            try (ZipOutputStream zos = new ZipOutputStream(out)) {

                // module-info.class
                writeModuleInfo(zos, findPackages(classpath));

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
                    } catch (ZipException x) {
                        // Skip. Do nothing. No packages will be added.
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
         * Writes the updated module-info.class to the ZIP output stream.
         *
         * The updated module-info.class will have a ConcealedPackages attribute
         * with the set of module-private/non-exported packages.
         *
         * If --module-version, --main-class, or other options were provided
         * then the corresponding class file attributes are added to the
         * module-info here.
         */
        void writeModuleInfo(ZipOutputStream zos, Set<String> packages)
            throws IOException
        {
            Supplier<InputStream> miSupplier = newModuleInfoSupplier();
            if (miSupplier == null) {
                throw new IOException(MODULE_INFO + " not found");
            }

            ModuleDescriptor descriptor;
            try (InputStream in = miSupplier.get()) {
                descriptor = ModuleDescriptor.read(in);
            }

            // copy the module-info.class into the jmod with the additional
            // attributes for the version, main class and other meta data
            try (InputStream in = miSupplier.get()) {
                ModuleInfoExtender extender = ModuleInfoExtender.newExtender(in);

                // Add (or replace) the ConcealedPackages attribute
                if (packages != null) {
                    Set<String> exported = descriptor.exports().stream()
                        .map(ModuleDescriptor.Exports::source)
                        .collect(Collectors.toSet());
                    Set<String> concealed = packages.stream()
                        .filter(p -> !exported.contains(p))
                        .collect(Collectors.toSet());
                    extender.conceals(concealed);
                }

                // --main-class
                if (mainClass != null)
                    extender.mainClass(mainClass);

                // --module-version
                if (moduleVersion != null)
                    extender.version(moduleVersion);

                // --hash-dependencies
                if (dependenciesToHash != null) {
                    String name = descriptor.name();
                    Set<Requires> dependences = descriptor.requires();
                    extender.hashes(hashDependences(name, dependences));
                }

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
         * pattern {@code dependenciesToHash}.
         */
        DependencyHashes hashDependences(String name, Set<Requires> moduleDependences)
            throws IOException
        {
            Set<ModuleDescriptor> descriptors = new HashSet<>();
            for (Requires md: moduleDependences) {
                String dn = md.name();
                if (dependenciesToHash.matcher(dn).find()) {
                    try {
                        Optional<ModuleReference> omref = moduleFinder.find(dn);
                        if (!omref.isPresent()) {
                            throw new RuntimeException("Hashing module " + name
                                + " dependencies, unable to find module " + dn
                                + " on module path");
                        }
                        descriptors.add(omref.get().descriptor());
                    } catch (FindException x) {
                        throw new IOException("error reading module path", x);
                    }
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

        /**
         * Returns the set of all packages on the given class path.
         */
        Set<String> findPackages(List<Path> classpath) {
            Set<String> packages = new HashSet<>();
            for (Path path : classpath) {
                if (Files.isDirectory(path)) {
                    packages.addAll(findPackages(path));
                } else if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
                    try (JarFile jf = new JarFile(path.toString())) {
                        packages.addAll(findPackages(jf));
                    } catch (ZipException x) {
                        // Skip. Do nothing. No packages will be added.
                    } catch (IOException ioe) {
                        throw new UncheckedIOException(ioe);
                    }
                }
            }
            return packages;
        }

        /**
         * Returns the set of packages in the given directory tree.
         */
        Set<String> findPackages(Path dir) {
            try {
                return Files.find(dir, Integer.MAX_VALUE,
                        ((path, attrs) -> attrs.isRegularFile() &&
                                path.toString().endsWith(".class")))
                        .map(path -> toPackageName(dir.relativize(path)))
                        .filter(pkg -> pkg.length() > 0)   // module-info
                        .distinct()
                        .collect(Collectors.toSet());
            } catch (IOException ioe) {
                throw new UncheckedIOException(ioe);
            }
        }

        /**
         * Returns the set of packages in the given JAR file.
         */
        Set<String> findPackages(JarFile jf) {
            return jf.stream()
                     .filter(e -> e.getName().endsWith(".class"))
                     .map(e -> toPackageName(e))
                     .filter(pkg -> pkg.length() > 0)   // module-info
                     .distinct()
                     .collect(Collectors.toSet());
        }

        String toPackageName(Path path) {
            String name = path.toString();
            assert name.endsWith(".class");
            int index = name.lastIndexOf(File.separatorChar);
            if (index != -1)
                return name.substring(0, index).replace(File.separatorChar, '.');
            else
                return "";
        }

        String toPackageName(ZipEntry entry) {
            String name = entry.getName();
            assert name.endsWith(".class");
            int index = name.lastIndexOf("/");
            if (index != -1)
                return name.substring(0, index).replace('/', '.');
            else
                return "";
        }

        void processClasses(ZipOutputStream zos, List<Path> classpaths)
            throws IOException
        {
            if (classpaths == null)
                return;

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
            if (paths == null)
                return;

            for (Path p : paths)
                processSection(zos, section, p);
        }

        void processSection(ZipOutputStream zos, Section section, Path top)
            throws IOException
        {
            final String prefix = section.jmodDir();

            Files.walkFileTree(top, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException
                {
                    Path relPath = top.relativize(file);
                    if (!relPath.toString().equals(MODULE_INFO)
                            && !matches(relPath, excludes)) {
                        try (InputStream in = Files.newInputStream(file)) {
                            writeZipEntry(zos, in, prefix, relPath.toString());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        boolean matches(Path path, List<PathMatcher> matchers) {
            if (matchers != null) {
                for (PathMatcher pm : matchers) {
                    if (pm.matches(path))
                        return true;
                }
            }
            return false;
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
                // ## no support for excludes. Is it really needed?
                return !name.endsWith(MODULE_INFO) && !je.isDirectory();
            }
        }
    }

    enum Section {
        NATIVE_LIBS("native"),
        NATIVE_CMDS("bin"),
        CLASSES("classes"),
        CONFIG("conf"),
        UNKNOWN("unknown");

        private final String jmodDir;

        Section(String jmodDir) {
            this.jmodDir = jmodDir;
        }

        String jmodDir() { return jmodDir; }
    }

    static class ClassPathConverter implements ValueConverter<Path> {
        static final ValueConverter<Path> INSTANCE = new ClassPathConverter();

        private static final Path CWD = Paths.get("");

        @Override
        public Path convert(String value) {
            try {
                Path path = CWD.resolve(value);
                if (Files.notExists(path))
                    throw new CommandException("err.path.not.found", path);
                if (! (Files.isDirectory(path) ||
                       (Files.isRegularFile(path) && path.toString().endsWith(".jar"))))
                    throw new CommandException("err.invalid.class.path.entry", path);
                return path;
            } catch (InvalidPathException x) {
                throw new CommandException("err.path.not.valid", value);
            }
        }

        @Override  public Class<Path> valueType() { return Path.class; }

        @Override  public String valuePattern() { return "path"; }
    }

    static class DirPathConverter implements ValueConverter<Path> {
        static final ValueConverter<Path> INSTANCE = new DirPathConverter();

        private static final Path CWD = Paths.get("");

        @Override
        public Path convert(String value) {
            try {
                Path path = CWD.resolve(value);
                if (Files.notExists(path))
                    throw new CommandException("err.path.not.found", path);
                if (!Files.isDirectory(path))
                    throw new CommandException("err.path.not.a.dir", path);
                return path;
            } catch (InvalidPathException x) {
                throw new CommandException("err.path.not.valid", value);
            }
        }

        @Override  public Class<Path> valueType() { return Path.class; }

        @Override  public String valuePattern() { return "path"; }
    }

    static class ModuleVersionConverter implements ValueConverter<Version> {
        @Override
        public Version convert(String value) {
            try {
                return Version.parse(value);
            } catch (IllegalArgumentException x) {
                throw new CommandException("err.invalid.version", x.getMessage());
            }
        }

        @Override public Class<Version> valueType() { return Version.class; }

        @Override public String valuePattern() { return "module-version"; }
    }

    static class PatternConverter implements ValueConverter<Pattern> {
        @Override
        public Pattern convert(String value) {
            try {
                return Pattern.compile(value);
            } catch (PatternSyntaxException e) {
                throw new CommandException("err.bad.pattern", value);
            }
        }

        @Override public Class<Pattern> valueType() { return Pattern.class; }

        @Override public String valuePattern() { return "pattern"; }
    }

    static class GlobConverter implements ValueConverter<PathMatcher> {
        @Override
        public PathMatcher convert(String pattern) {
            try {
                return FileSystems.getDefault()
                                  .getPathMatcher("glob:" + pattern);
            } catch (PatternSyntaxException e) {
                throw new CommandException("err.bad.pattern", pattern);
            }
        }

        @Override public Class<PathMatcher> valueType() { return PathMatcher.class; }

        @Override public String valuePattern() { return "pattern"; }
    }

    /* Support for @<file> in jmod help */
    private static final String CMD_FILENAME = "@<filename>";

    /**
     * This formatter is adding the @filename option and does the required
     * formatting.
     */
    private static final class JmodHelpFormatter extends BuiltinHelpFormatter {

        private JmodHelpFormatter() { super(80, 2); }

        @Override
        public String format(Map<String, ? extends OptionDescriptor> options) {
            Map<String, OptionDescriptor> all = new HashMap<>();
            all.putAll(options);
            all.put(CMD_FILENAME, new OptionDescriptor() {
                @Override
                public Collection<String> options() {
                    List<String> ret = new ArrayList<>();
                    ret.add(CMD_FILENAME);
                    return ret;
                }
                @Override
                public String description() { return getMessage("main.opt.cmdfile"); }
                @Override
                public List<?> defaultValues() { return Collections.emptyList(); }
                @Override
                public boolean isRequired() { return false; }
                @Override
                public boolean acceptsArguments() { return false; }
                @Override
                public boolean requiresArgument() { return false; }
                @Override
                public String argumentDescription() { return null; }
                @Override
                public String argumentTypeIndicator() { return null; }
                @Override
                public boolean representsNonOptions() { return false; }
            });
            String content = super.format(all);
            String[] lines = content.split("\n");
            StringBuilder builder = new StringBuilder();
            String cmdfile = null;
            for (String line : lines) {
                if (line.startsWith("--@")) {
                    cmdfile = line.replace("--" + CMD_FILENAME, CMD_FILENAME + "  ");
                } else {
                    builder.append(line).append("\n");
                }
            }
            if (cmdfile != null) {
                builder.append(cmdfile).append("\n");
            }
            return builder.toString();
        }
    }

    private final OptionParser parser = new OptionParser();

    private void handleOptions(String[] args) {
        parser.formatHelpWith(new JmodHelpFormatter());

        OptionSpec<Path> classPath
                = parser.accepts("class-path", getMessage("main.opt.class-path"))
                        .withRequiredArg()
                        .withValuesSeparatedBy(File.pathSeparatorChar)
                        .withValuesConvertedBy(ClassPathConverter.INSTANCE);

        OptionSpec<Path> cmds
                = parser.accepts("cmds", getMessage("main.opt.cmds"))
                        .withRequiredArg()
                        .withValuesSeparatedBy(File.pathSeparatorChar)
                        .withValuesConvertedBy(DirPathConverter.INSTANCE);

        OptionSpec<Path> config
                = parser.accepts("config", getMessage("main.opt.config"))
                        .withRequiredArg()
                        .withValuesSeparatedBy(File.pathSeparatorChar)
                        .withValuesConvertedBy(DirPathConverter.INSTANCE);

        OptionSpec<PathMatcher> excludes
                = parser.accepts("exclude", getMessage("main.opt.exclude"))
                        .withRequiredArg()
                        .withValuesConvertedBy(new GlobConverter());

        OptionSpec<Pattern> hashDependencies
                = parser.accepts("hash-dependencies", getMessage("main.opt.hash-dependencies"))
                        .withRequiredArg()
                        .withValuesConvertedBy(new PatternConverter());

        OptionSpec<Void> help
                = parser.accepts("help", getMessage("main.opt.help"))
                        .forHelp();

        OptionSpec<Path> libs
                = parser.accepts("libs", getMessage("main.opt.libs"))
                        .withRequiredArg()
                        .withValuesSeparatedBy(File.pathSeparatorChar)
                        .withValuesConvertedBy(DirPathConverter.INSTANCE);

        OptionSpec<String> mainClass
                = parser.accepts("main-class", getMessage("main.opt.main-class"))
                        .withRequiredArg()
                        .describedAs(getMessage("main.opt.main-class.arg"));

        OptionSpec<Path> modulePath  // TODO: short version of --mp ??
                = parser.acceptsAll(Arrays.asList("mp", "modulepath"),
                                    getMessage("main.opt.modulepath"))
                        .withRequiredArg()
                        .withValuesSeparatedBy(File.pathSeparatorChar)
                        .withValuesConvertedBy(DirPathConverter.INSTANCE);

        OptionSpec<Version> moduleVersion
                = parser.accepts("module-version", getMessage("main.opt.module-version"))
                        .withRequiredArg()
                        .withValuesConvertedBy(new ModuleVersionConverter());

        OptionSpec<Void> version
                = parser.accepts("version", getMessage("main.opt.version"));

        NonOptionArgumentSpec<String> nonOptions
                = parser.nonOptions(getMessage("main.non.opt.args"));

        try {
            OptionSet opts = parser.parse(args);

            if (opts.specs().isEmpty() && opts.valuesOf(nonOptions).isEmpty())
                return;  // usage summary will be shown

            options = new Options();
            options.help = opts.has(help);
            options.version = opts.has(version);
            if (opts.has(classPath))
                options.classpath = opts.valuesOf(classPath);
            if (opts.has(cmds))
                options.cmds = opts.valuesOf(cmds);
            if (opts.has(config))
                options.configs = opts.valuesOf(config);
            if (opts.has(excludes))
                options.excludes = opts.valuesOf(excludes);
            if (opts.has(libs))
                options.libs = opts.valuesOf(libs);
            if (opts.has(modulePath)) {
                Path[] dirs = opts.valuesOf(modulePath).toArray(new Path[0]);
                options.moduleFinder = ModuleFinder.of(dirs);
                if (options.moduleFinder instanceof ConfigurableModuleFinder)
                    ((ConfigurableModuleFinder)options.moduleFinder).configurePhase(Phase.LINK_TIME);
            }
            if (opts.has(moduleVersion))
                options.moduleVersion = opts.valueOf(moduleVersion);
            if (opts.has(mainClass))
                options.mainClass = opts.valueOf(mainClass);
            if (opts.has(hashDependencies)) {
                options.dependenciesToHash = opts.valueOf(hashDependencies);
                // if storing hashes of dependencies then the module path is required
                if (options.moduleFinder == null)
                    throw new CommandException("err.modulepath.must.be.specified").showUsage(true);
            }

            if (options.help || options.version)
                return;  // informational message will be shown

            List<String> words = opts.valuesOf(nonOptions);
            if (words.isEmpty())
                throw new CommandException("err.missing.task").showUsage(true);

            String verb = words.get(0);
            try {
                options.task = Enum.valueOf(Task.class, verb.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new CommandException("err.invalid.task", verb).showUsage(true);
            }

            if (words.size() == 1)
                throw new CommandException("err.jmod.must.be.specified").showUsage(true);
            Path path = Paths.get(words.get(1));
            if (options.task.equals(Task.CREATE) && Files.exists(path))
                throw new CommandException("err.file.already.exists", path);
            else if (options.task.equals(Task.LIST) && Files.notExists(path))
                throw new CommandException("err.jmod.not.found", path);
            options.jmodFile = path;

            if (words.size() > 2)
                throw new CommandException("err.unknown.option",
                        words.subList(2, words.size())).showUsage(true);

            if (options.task.equals(Task.CREATE) && options.classpath == null)
                throw new CommandException("err.classpath.must.be.specified").showUsage(true);
            if (options.mainClass != null && !isValidJavaIdentifier(options.mainClass))
                throw new CommandException("err.invalid.main-class", options.mainClass);
        } catch (OptionException e) {
             throw new CommandException(e.getMessage());
        }
    }

    /**
     * Returns true if, and only if, the given main class is a legal.
     */
    static boolean isValidJavaIdentifier(String mainClass) {
        if (mainClass.length() == 0)
            return false;

        if (!Character.isJavaIdentifierStart(mainClass.charAt(0)))
            return false;

        int n = mainClass.length();
        for (int i=1; i < n; i++) {
            char c = mainClass.charAt(i);
            if (!Character.isJavaIdentifierPart(c) && c != '.')
                return false;
        }
        if (mainClass.charAt(n-1) == '.')
            return false;

        return true;
    }

    private void reportError(String message) {
        out.println(getMessage("error.prefix") + " " + message);
    }

    private void warning(String key, Object... args) {
        out.println(getMessage("warn.prefix") + " " + getMessage(key, args));
    }

    private void showUsageSummary() {
        out.println(getMessage("main.usage.summary", PROGNAME));
    }

    private void showHelp() {
        out.println(getMessage("main.usage", PROGNAME));
        try {
            parser.printHelpOn(out);
        } catch (IOException x) {
            throw new AssertionError(x);
        }
    }

    private void showVersion() {
        out.println(version());
    }

    private String version() {
        return System.getProperty("java.version");
    }

    private static String getMessage(String key, Object... args) {
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
