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

import com.sun.tools.classfile.AccessFlags;
import com.sun.tools.classfile.ClassFile;
import com.sun.tools.classfile.ConstantPoolException;
import com.sun.tools.classfile.Dependencies;
import com.sun.tools.classfile.Dependencies.ClassFileError;
import com.sun.tools.classfile.Dependency;
import com.sun.tools.jdeps.Analyzer.Pair;
import static com.sun.tools.jdeps.Analyzer.Type.*;
import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Implementation for the jdeps tool for static class dependency analysis.
 */
class JdepsTask {
    static class BadArgs extends Exception {
        static final long serialVersionUID = 8765093759964640721L;
        BadArgs(String key, Object... args) {
            super(JdepsTask.getMessage(key, args));
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
        Option(boolean hasArg, String... aliases) {
            this.hasArg = hasArg;
            this.aliases = aliases;
        }

        boolean isHidden() {
            return false;
        }

        boolean matches(String opt) {
            for (String a : aliases) {
                if (a.equals(opt))
                    return true;
                if (hasArg && opt.startsWith(a + "="))
                    return true;
            }
            return false;
        }

        boolean ignoreRest() {
            return false;
        }

        abstract void process(JdepsTask task, String opt, String arg) throws BadArgs;
        final boolean hasArg;
        final String[] aliases;
    }

    static abstract class HiddenOption extends Option {
        HiddenOption(boolean hasArg, String... aliases) {
            super(hasArg, aliases);
        }

        boolean isHidden() {
            return true;
        }
    }

    static Option[] recognizedOptions = {
        new Option(false, "-h", "-?", "-help") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.help = true;
            }
        },
        new Option(true, "-dotoutput") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                Path p = Paths.get(arg);
                if (Files.exists(p) && (!Files.isDirectory(p) || !Files.isWritable(p))) {
                    throw new BadArgs("err.invalid.path", arg);
                }
                task.options.dotOutputDir = arg;
            }
        },
        new Option(false, "-s", "-summary") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.showSummary = true;
                task.options.verbose = SUMMARY;
            }
        },
        new Option(false, "-v", "-verbose",
                          "-verbose:package",
                          "-verbose:class")
        {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                switch (opt) {
                    case "-v":
                    case "-verbose":
                        task.options.verbose = VERBOSE;
                        break;
                    case "-verbose:package":
                            task.options.verbose = PACKAGE;
                            break;
                    case "-verbose:class":
                            task.options.verbose = CLASS;
                            break;
                    default:
                        throw new BadArgs("err.invalid.arg.for.option", opt);
                }
            }
        },
        new Option(true, "-cp", "-classpath") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.classpath = arg;
            }
        },
        new Option(true, "-p", "-package") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.packageNames.add(arg);
            }
        },
        new Option(true, "-e", "-regex") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.regex = arg;
            }
        },
        new Option(true, "-include") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                task.options.includePattern = Pattern.compile(arg);
            }
        },
        new Option(false, "-P", "-profile") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                task.options.showProfile = true;
            }
        },
        new Option(false, "-apionly") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.apiOnly = true;
            }
        },
        new Option(false, "-R", "-recursive") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.depth = 0;
            }
        },
        new Option(false, "-jdkinternals") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.findJDKInternals = true;
                task.options.verbose = CLASS;
                if (task.options.includePattern == null) {
                    task.options.includePattern = Pattern.compile(".*");
                }
            }
        },
        new Option(false, "-version") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.version = true;
            }
        },
        new HiddenOption(false, "-fullversion") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.fullVersion = true;
            }
        },
        new HiddenOption(false, "-showlabel") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.showLabel = true;
            }
        },
        new HiddenOption(true, "-depth") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                try {
                    task.options.depth = Integer.parseInt(arg);
                } catch (NumberFormatException e) {
                    throw new BadArgs("err.invalid.arg.for.option", opt);
                }
            }
        },
        new HiddenOption(false, "-verify:access") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.verifyAccess = true;
                task.options.verbose = VERBOSE;
            }
        },
        new HiddenOption(true, "-mp") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                task.options.mpath = Paths.get(arg);
                if (!Files.isDirectory(task.options.mpath)) {
                    throw new BadArgs("err.invalid.path", arg);
                }
                if (task.options.includePattern == null) {
                    task.options.includePattern = Pattern.compile(".*");
                }
            }
        },
    };

    private static final String PROGNAME = "jdeps";
    private final Options options = new Options();
    private final List<String> classes = new ArrayList<>();

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
            if (classes.isEmpty() && options.includePattern == null) {
                if (options.help || options.version || options.fullVersion) {
                    return EXIT_OK;
                } else {
                    showHelp();
                    return EXIT_CMDERR;
                }
            }
            if (options.regex != null && options.packageNames.size() > 0) {
                showHelp();
                return EXIT_CMDERR;
            }
            if ((options.findJDKInternals || options.verifyAccess) &&
                   (options.regex != null || options.packageNames.size() > 0 || options.showSummary)) {
                showHelp();
                return EXIT_CMDERR;
            }
            if (options.showSummary && options.verbose != SUMMARY) {
                showHelp();
                return EXIT_CMDERR;
            }
            if (options.verifyAccess && options.verbose != VERBOSE) {
                showHelp();
                return EXIT_CMDERR;
            }
            boolean ok = run();
            return ok ? EXIT_OK : EXIT_ERROR;
        } catch (BadArgs e) {
            reportError(e.key, e.args);
            if (e.showUsage) {
                log.println(getMessage("main.usage.summary", PROGNAME));
            }
            return EXIT_CMDERR;
        } catch (IOException e) {
            return EXIT_ABNORMAL;
        } finally {
            log.flush();
        }
    }

    private final List<Archive> sourceLocations = new ArrayList<>();
    private boolean run() throws IOException {
        findDependencies();
        Analyzer analyzer = new Analyzer(options.verbose, options.findJDKInternals);
        boolean rc = true;
        if (options.verifyAccess) {
            rc = analyzer.verify(sourceLocations);
        } else {
            analyzer.run(sourceLocations);
        }
        if (options.dotOutputDir != null) {
            Path dir = Paths.get(options.dotOutputDir);
            Files.createDirectories(dir);
            generateDotFiles(dir, analyzer);
        } else {
            printRawOutput(log, analyzer);
        }
        return rc;
    }

    private void generateDotFiles(Path dir, Analyzer analyzer) throws IOException {
        // output individual .dot file for each archive
        if (options.verbose != SUMMARY) {
            for (Archive archive : sourceLocations) {
                if (analyzer.hasDependences(archive)) {
                    Path dotfile = dir.resolve(archive.getName() + ".dot");
                    try (PrintWriter pw = new PrintWriter(Files.newOutputStream(dotfile));
                        DotFileFormatter formatter = new DotFileFormatter(pw, archive)) {
                        analyzer.visitDependences(archive, formatter);
                    }
                }
            }
        }
        // generate summary dot file
        generateSummaryDotFile(dir, analyzer);
    }

    private void generateSummaryDotFile(Path dir, Analyzer analyzer) throws IOException {
        Path summary = dir.resolve("summary.dot");
        try (PrintWriter sw = new PrintWriter(Files.newOutputStream(summary));
            SummaryDotFile dotfile = new SummaryDotFile(sw)) {
            for (Archive archive : sourceLocations) {
                if (!archive.isEmpty()) {
                // If verbose mode (-v or -verbose option),
                    // the summary.dot file shows package-level dependencies.
                    if (options.verbose == PACKAGE || options.verbose == SUMMARY) {
                        if (options.showLabel) {
                            analyzer.visitDependences(archive, dotfile.labelBuilder());
                        }
                        analyzer.visitDependences(archive, dotfile, SUMMARY);
                    } else {
                        analyzer.visitDependences(archive, dotfile, PACKAGE);
                    }
                }
            }
        }
    }

    private void printRawOutput(PrintWriter writer, Analyzer analyzer) {
        for (Archive archive : sourceLocations) {
            RawOutputFormatter formatter = new RawOutputFormatter(writer);
            if (!archive.isEmpty()) {
                analyzer.visitDependences(archive, formatter.summaryVisitor, SUMMARY);
                if (analyzer.hasDependences(archive) && options.verbose != SUMMARY) {
                    analyzer.visitDependences(archive, formatter.depVisitor);
                }
            }
        }
    }

    private boolean isValidClassName(String name) {
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i=1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != '.'  && !Character.isJavaIdentifierPart(c)) {
                return false;
            }
        }
        return true;
    }

    private Dependency.Filter getDependencyFilter() {
         if (options.regex != null) {
            return Dependencies.getRegexFilter(Pattern.compile(options.regex));
        } else if (options.packageNames.size() > 0) {
            return Dependencies.getPackageFilter(options.packageNames, false);
        } else {
            return new Dependency.Filter() {
                @Override
                public boolean accepts(Dependency dependency) {
                    return !dependency.getOrigin().equals(dependency.getTarget());
                }
            };
        }
    }

    private boolean matches(String classname, AccessFlags flags) {
        if (options.apiOnly && !flags.is(AccessFlags.ACC_PUBLIC)) {
            return false;
        } else if (options.includePattern != null) {
            return options.includePattern.matcher(classname.replace('/', '.')).matches();
        } else {
            return true;
        }
    }

    private void findDependencies() throws IOException {
        Dependency.Finder finder =
            options.apiOnly ? Dependencies.getAPIFinder(AccessFlags.ACC_PROTECTED)
                            : Dependencies.getClassDependencyFinder();
        Dependency.Filter filter = getDependencyFilter();

        List<Archive> archives = new ArrayList<>();
        Deque<String> roots = new LinkedList<>();
        for (String s : classes) {
           Path p = Paths.get(s);
           if (Files.exists(p)) {
               archives.add(Archive.getInstance(p));
           } else {
               if (isValidClassName(s)) {
                   roots.add(s);
               } else {
                   warning("warn.invalid.arg", s);
               }
           }
        }
        sourceLocations.addAll(archives);

        List<Archive> classpaths = new ArrayList<>(); // for class file lookup
        classpaths.addAll(getClassPathArchives(options.classpath));
        // include classpath in the first pass iterating all classes
        if (options.includePattern != null) {
            archives.addAll(classpaths);
        }
        classpaths.addAll(PlatformClassPath.getArchives(options.mpath));
        if (options.mpath != null) {
            archives.addAll(PlatformClassPath.getArchives(options.mpath));
        }

        // add all classpath archives to the source locations for reporting
        sourceLocations.addAll(classpaths);

        // Work queue of names of classfiles to be searched.
        // Entries will be unique, and for classes that do not yet have
        // dependencies in the results map.
        Deque<String> deque = new LinkedList<>();
        Set<String> doneClasses = new HashSet<>();

        // get the immediate dependencies of the input files
        for (Archive a : archives) {
            for (ClassFile cf : a.reader().getClassFiles()) {
                String classFileName;
                try {
                    classFileName = cf.getName();
                } catch (ConstantPoolException e) {
                    throw new ClassFileError(e);
                }

                if (matches(classFileName, cf.access_flags)) {
                    if (!doneClasses.contains(classFileName)) {
                        doneClasses.add(classFileName);
                    }
                    for (Dependency d : finder.findDependencies(cf)) {
                        if (filter.accepts(d)) {
                            String cn = d.getTarget().getName();
                            if (!doneClasses.contains(cn) && !deque.contains(cn)) {
                                deque.add(cn);
                            }
                            a.addClass(d.getOrigin(), d.getTarget());
                        }
                    }
                }
            }
        }

        // add Archive for looking up classes from the classpath
        // for transitive dependency analysis
        Deque<String> unresolved = roots;
        int depth = options.depth > 0 ? options.depth : Integer.MAX_VALUE;
        do {
            String name;
            while ((name = unresolved.poll()) != null) {
                if (doneClasses.contains(name)) {
                    continue;
                }
                ClassFile cf = null;
                for (Archive a : classpaths) {
                    cf = a.reader().getClassFile(name);
                    if (cf != null) {
                        String classFileName;
                        try {
                            classFileName = cf.getName();
                        } catch (ConstantPoolException e) {
                            throw new ClassFileError(e);
                        }
                        if (!doneClasses.contains(classFileName)) {
                            // if name is a fully-qualified class name specified
                            // from command-line, this class might already be parsed
                            doneClasses.add(classFileName);
                            for (Dependency d : finder.findDependencies(cf)) {
                                if (depth == 0) {
                                    // ignore the dependency
                                    a.addClass(d.getOrigin());
                                    break;
                                } else if (filter.accepts(d)) {
                                    a.addClass(d.getOrigin(), d.getTarget());
                                    String cn = d.getTarget().getName();
                                    if (!doneClasses.contains(cn) && !deque.contains(cn)) {
                                        deque.add(cn);
                                    }
                                }
                            }
                        }
                        break;
                    }
                }
                if (cf == null) {
                    doneClasses.add(name);
                }
            }
            unresolved = deque;
            deque = new LinkedList<>();
        } while (!unresolved.isEmpty() && depth-- > 0);
    }

    public void handleOptions(String[] args) throws BadArgs {
        // process options
        for (int i=0; i < args.length; i++) {
            if (args[i].charAt(0) == '-') {
                String name = args[i];
                Option option = getOption(name);
                String param = null;
                if (option.hasArg) {
                    if (name.startsWith("-") && name.indexOf('=') > 0) {
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
                for (; i < args.length; i++) {
                    String name = args[i];
                    if (name.charAt(0) == '-') {
                        throw new BadArgs("err.option.after.class", name).showUsage(true);
                    }
                    classes.add(name);
                }
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
        // key=version:  mm.nn.oo[-milestone]
        // key=full:     mm.mm.oo[-milestone]-build
        if (ResourceBundleHelper.versionRB == null) {
            return System.getProperty("java.version");
        }
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

    private static class Options {
        boolean help;
        boolean version;
        boolean fullVersion;
        boolean showProfile;
        boolean showSummary;
        boolean wildcard;
        boolean apiOnly;
        boolean showLabel;
        boolean findJDKInternals;
        boolean verifyAccess;
        Path mpath;
        String dotOutputDir;
        String classpath = "";
        int depth = 1;
        Analyzer.Type verbose = PACKAGE;
        Set<String> packageNames = new HashSet<>();
        String regex;             // apply to the dependences
        Pattern includePattern;   // apply to classes
    }
    private static class ResourceBundleHelper {
        static final ResourceBundle versionRB;
        static final ResourceBundle bundle;

        static {
            Locale locale = Locale.getDefault();
            try {
                bundle = ResourceBundle.getBundle("com.sun.tools.jdeps.resources.jdeps", locale);
            } catch (MissingResourceException e) {
                throw new InternalError("Cannot find jdeps resource bundle for locale " + locale);
            }
            try {
                versionRB = ResourceBundle.getBundle("com.sun.tools.jdeps.resources.version");
            } catch (MissingResourceException e) {
                throw new InternalError("version.resource.missing");
            }
        }
    }

    private List<Archive> getClassPathArchives(String paths) throws IOException {
        List<Archive> result = new ArrayList<>();
        if (paths.isEmpty()) {
            return result;
        }
        for (String p : paths.split(File.pathSeparator)) {
            if (p.length() > 0) {
                List<Path> files = new ArrayList<>();
                // wildcard to parse all JAR files e.g. -classpath dir/*
                int i = p.lastIndexOf(".*");
                if (i > 0) {
                    Path dir = Paths.get(p.substring(0, i));
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
                        for (Path entry : stream) {
                            files.add(entry);
                        }
                    }
                } else {
                    files.add(Paths.get(p));
                }
                for (Path f : files) {
                    if (Files.exists(f)) {
                        result.add(Archive.getInstance(f));
                    }
                }
            }
        }
        return result;
    }

    class RawOutputFormatter {
        private final PrintWriter writer;
        RawOutputFormatter(PrintWriter writer) {
            this.writer = writer;
        }

        Analyzer.Visitor depVisitor =  new Analyzer.Visitor() {
            private String pkg = "";
            @Override
            public void visitDependence(String origin, Archive originArchive,
                                        String target, Archive targetArchive) {
                String tag = toTag(target, targetArchive);
                if (options.verbose == VERBOSE) {
                    writer.format("   %-50s -> %-50s %s%n",
                                  origin, target, tag);
                } else {
                    if (!origin.equals(pkg)) {
                        pkg = origin;
                        writer.format("   %s (%s)%n", origin, originArchive.getName());
                    }
                    writer.format("      -> %-50s %s%n", target, tag);
                }
            }
        };
        Analyzer.Visitor summaryVisitor = new Analyzer.Visitor() {
            @Override
            public void visitDependence(String origin, Archive originArchive,
                                        String target, Archive targetArchive) {
                writer.format("%s -> %s", originArchive.toString(), targetArchive.toString());
                if (options.showProfile && isJDKModule(targetArchive)) {
                    Module module = Module.class.isInstance(targetArchive) ? (Module)targetArchive : null;
                    Profile p = Profile.getProfile(module);
                    if (p != null) {
                        writer.format(" (%s)", p.profileName());
                    }
                }
                writer.format("%n");
            }
        };
    }
    class DotFileFormatter implements Analyzer.Visitor, AutoCloseable {
        private final PrintWriter writer;
        private final String name;
        DotFileFormatter(PrintWriter writer, Archive archive) {
            this.writer = writer;
            this.name = archive.getName();
            writer.format("digraph \"%s\" {%n", name);
            writer.format("    // Path: %s%n", archive.getPathName());
        }

        @Override
        public void close() {
            writer.println("}");
        }

        @Override
        public void visitDependence(String origin, Archive originArchive,
                                    String target, Archive targetArchive) {
            String tag = toTag(target, targetArchive);

            // if -P option is specified, package name -> profile will
            // be shown and filter out multiple same edges.
            writeEdge(origin, target, tag, "");
        }

        public void writeEdge(String origin, String target, String tag, String label) {
            writer.format("   %-50s -> \"%s\"%s;%n",
                          String.format("\"%s\"", origin),
                          tag.isEmpty() ? target
                                        : String.format("%s (%s)", target, tag),
                          label);
        }
    }

    class SummaryDotFile implements Analyzer.Visitor, AutoCloseable {
        private final PrintWriter writer;
        private final Map<Pair<Archive,Archive>, StringBuilder> edges = new HashMap<>();
        SummaryDotFile(PrintWriter writer) {
            this.writer = writer;
            writer.format("digraph \"summary\" {%n");
        }

        @Override
        public void close() {
            writer.println("}");
        }

        @Override
        public void visitDependence(String origin, Archive originArchive,
                                    String target, Archive targetArchive) {
            String targetName = target;
            if (options.showProfile && isJDKModule(targetArchive)) {
                Module module = Module.class.isInstance(targetArchive) ? (Module) targetArchive : null;
                Profile p = Profile.getProfile(module);
                if (p != null) {
                    targetName = p.profileName();
                }
            }
            String label = getLabel(originArchive, targetArchive);
            writer.format("  %-50s -> \"%s\"%s;%n",
                          String.format("\"%s\"", origin), targetName, label);
        }
        String getLabel(Archive origin, Archive target) {
            if (edges.isEmpty())
                return "";

            StringBuilder label = edges.get(new Pair<Archive,Archive>(origin, target));
            return label == null ? "" : String.format(" [label=\"%s\",fontsize=9]", label.toString());
        }

        Analyzer.Visitor labelBuilder() {
            // show the package-level dependencies as labels in the dot graph
            return new Analyzer.Visitor() {
                @Override
                public void visitDependence(String origin, Archive originArchive, String target, Archive targetArchive) {
                    Pair<Archive,Archive> edge = new Pair<>(originArchive, targetArchive);
                    StringBuilder sb = edges.get(edge);
                    if (sb == null) {
                        edges.put(edge, sb = new StringBuilder());
                    }
                    String tag = toTag(target, targetArchive);
                    addLabel(sb, origin, target, tag);
                }

                void addLabel(StringBuilder label, String origin, String target, String tag) {
                    label.append(origin).append(" -> ").append(target);
                    if (!tag.isEmpty()) {
                        label.append(" (" + tag + ")");
                    }
                    label.append("\\n");
                }
            };
        }
    }

    Module getModule(Archive archive) {
        if (Module.class.isInstance(archive)) {
            return (Module) archive;
        } else {
            return null;
        }
    }

    private boolean isJDKModule(Archive source) {
        Module module = Module.class.isInstance(source) ? (Module)source : null;
        return module != null && Profile.JDK.contains(module);
    }
    /**
     * If the given archive is JDK archive, this method returns the profile name
     * only if -profile option is specified; it accesses a private JDK API and
     * the returned value will have "JDK internal API" prefix
     *
     * For non-JDK archives, this method returns the file name of the archive.
     */
    private String toTag(String name, Archive source) {
        if (!isJDKModule(source)) {
            return source.getName();
        }

        Module module = Module.class.isInstance(source) ? (Module)source : null;
        Profile p = Profile.getProfile(module);
        String pn = name;
        if (options.verbose == CLASS || options.verbose == VERBOSE) {
            int i = name.lastIndexOf('.');
            pn = i > 0 ? name.substring(0, i) : "<unnamed>";
        }
        Set<String> permits = module.exports().get(pn);
        String tag = options.showProfile && p != null ? p.profileName() : module.name();
        if (permits != null && permits.isEmpty()) {
            // exported API
            return tag;
        } else {
            return "JDK internal API (" + tag +")";
        }
    }
}
