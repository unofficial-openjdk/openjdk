/*
 * Copyright (c) 2012, 2014, Oracle and/or its affiliates. All rights reserved.
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

import com.sun.tools.classfile.Dependency.Location;
import com.sun.tools.jdeps.DependencyFinder.*;
import static com.sun.tools.jdeps.Analyzer.Type.*;
import static com.sun.tools.jdeps.JdepsWriter.*;

import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeMap;
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
        new Option(true, "-genmoduleinfo") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                Path p = Paths.get(arg);
                if (Files.exists(p) && (!Files.isDirectory(p) || !Files.isWritable(p))) {
                    throw new BadArgs("err.invalid.path", arg);
                }
                task.options.genModuleInfo = arg;
                task.options.showModule = true;
            }
        },
        new Option(false, "-no-requires-public") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.showRequiresPublic = false;
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
                          "-verbose:class") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                switch (opt) {
                    case "-v":
                    case "-verbose":
                        task.options.verbose = VERBOSE;
                        task.options.filterSameArchive = false;
                        task.options.filterSamePackage = false;
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
                task.options.regex = Pattern.compile(arg);
            }
        },

        new Option(true, "-f", "-filter") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.filterRegex = Pattern.compile(arg);
            }
        },
        new Option(false, "-filter:package",
                          "-filter:archive",
                          "-filter:none") {
            void process(JdepsTask task, String opt, String arg) {
                switch (opt) {
                    case "-filter:package":
                        task.options.filterSamePackage = true;
                        task.options.filterSameArchive = false;
                        break;
                    case "-filter:archive":
                        task.options.filterSameArchive = true;
                        task.options.filterSamePackage = false;
                        break;
                    case "-filter:none":
                        task.options.filterSameArchive = false;
                        task.options.filterSamePackage = false;
                        break;
                }
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
                task.options.showModule = false;
            }
        },
        new Option(false, "-M", "-module") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                task.options.showModule = true;
                task.options.showProfile = false;
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
                // turn off filtering
                task.options.filterSameArchive = false;
                task.options.filterSamePackage = false;
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
        new Option(true, "-mp", "-modulepath") {
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
        new Option(false, "-q", "-quiet") {
            void process(JdepsTask task, String opt, String arg) {
                    task.options.nowarning = true;
                }
        },

        new HiddenOption(false, "-verify:access") {
            void process(JdepsTask task, String opt, String arg) {
                 task.options.verifyAccess = true;
                 task.options.verbose = VERBOSE;
                 task.options.filterSameArchive = false;
                 task.options.filterSamePackage = false;
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
            if (options.genModuleInfo != null &&
                    (options.dotOutputDir != null || !options.classpath.isEmpty())) {
                showHelp();
                return EXIT_CMDERR;
            }
            if (options.genModuleInfo != null &&
                    (options.regex != null || options.filterRegex != null || !options.packageNames.isEmpty())) {
                showHelp();
                return EXIT_CMDERR;
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

            boolean ok = run();
            return ok ? EXIT_OK : EXIT_ERROR;
        } catch (BadArgs e) {
            reportError(e.key, e.args);
            if (e.showUsage) {
                log.println(getMessage("main.usage.summary", PROGNAME));
            }
            return EXIT_CMDERR;
        } catch (IOException e) {
            e.printStackTrace();
            return EXIT_ABNORMAL;
        } finally {
            log.flush();
        }
    }

    // source locations for reporting
    private List<Archive> sourceLocations = new ArrayList<>();

    private boolean run() throws IOException {
        DependencyFinder dependencyFinder = new DependencyFinder(options.includePattern);
        buildArchive(dependencyFinder);

        if (options.verifyAccess) {
            return verifyModuleAccess(dependencyFinder);
        } else if (options.genModuleInfo != null) {
            return genModuleInfo(dependencyFinder);
        } else {
            return analyzeDeps(dependencyFinder);
        }
    }
    private void buildArchive(DependencyFinder dependencyFinder) throws IOException {
        for (String s : classes) {
            Path p = Paths.get(s);
            if (Files.exists(p)) {
                Archive archive = dependencyFinder.addArchive(p);
                sourceLocations.add(archive);
            } else {
                if (isValidClassName(s)) {
                    dependencyFinder.addRoot(s);
                } else {
                    warning("warn.invalid.arg", s);
                }
            }
        }

        for (Path p : getClassPaths(options.classpath)) {
            if (!Files.exists(p)) continue;
            Archive archive = dependencyFinder.addClassPathArchive(p);
            if (options.includePattern != null) {
                // add classpath to the initial archive list
                dependencyFinder.addArchive(archive);
            }
        }
        // add system modules first and then follow with modulepath
        for (Module m : ModulePath.getSystemModules()) {
            dependencyFinder.addModuleArchive(m);
        }
        if (options.mpath != null) {
            for (Module m : ModulePath.getModules(options.mpath)) {
                dependencyFinder.addArchive(m);
                dependencyFinder.addModuleArchive(m);
            }
        }

        // add all classpath archives to the source locations for reporting
        sourceLocations.addAll(dependencyFinder.classPathArchives());
    }

    private boolean analyzeDeps(DependencyFinder dependencyFinder) throws IOException {
        Analyzer analyzer = new Analyzer(options.verbose, new Analyzer.Filter() {
            @Override
            public boolean accepts(Location origin, Archive originArchive,
                                   Location target, Archive targetArchive)
            {
                if (options.findJDKInternals) {
                    // accepts target that is JDK class but not exported
                    return isJDKModule(targetArchive) &&
                              !((Module) targetArchive).isExported(target.getClassName());
                } else if (options.filterSameArchive) {
                    // accepts origin and target that from different archive
                    return originArchive != targetArchive;
                }
                return true;
            }
        });

        // parse classfiles and find all dependencies
        findDependencies(dependencyFinder, options.apiOnly);

        // analyze the dependencies
        analyzer.run(sourceLocations);

        // output result
        final JdepsWriter writer;
        if (options.dotOutputDir != null) {
            Path dir = Paths.get(options.dotOutputDir);
            Files.createDirectories(dir);
            writer = new DotFileWriter(dir, options.verbose,
                                       options.showProfile, options.showModule, options.showLabel);
        } else {
            writer = new SimpleWriter(log, options.verbose,
                                      options.showProfile, options.showModule);
        }
        writer.generateOutput(sourceLocations, analyzer);

        if (options.findJDKInternals && !options.nowarning) {
            showReplacements(analyzer);
        }
        return true;
    }

    private void findDependencies(DependencyFinder dependencyFinder, boolean apiOnly) throws IOException {
        final DependencyFilter filter;
        if (options.regex != null) {
            filter = new DependencyFilter(options.regex,
                                          options.filterRegex,
                                          options.filterSamePackage);
        } else if (!options.packageNames.isEmpty()) {
            filter = new DependencyFilter(options.packageNames,
                                          options.filterRegex,
                                          options.filterSamePackage);
        } else {
            filter = new DependencyFilter(options.filterRegex,
                                          options.filterSamePackage);
        }
        dependencyFinder.findDependencies(filter, apiOnly, options.depth);

        // print skipped entries, if any
        for (Archive a : dependencyFinder.initialArchives()) {
            for (String name : a.reader().skippedEntries()) {
                warning("warn.skipped.entry", name, a.getPathName());
            }
        }
    }

    private boolean verifyModuleAccess(DependencyFinder dependencyFinder) throws IOException {
        // two passes
        // 1. check API dependences where the types of dependences must be re-exported
        // 2. check all dependences where types must be accessible

        // pass 1
        findDependencies(dependencyFinder, true /* api only */);
        Analyzer analyzer = Analyzer.getExportedAPIsAnalyzer();
        boolean pass1 = analyzer.run(sourceLocations);
        if (!pass1) {
            System.out.println("ERROR: Failed API access verification");
        }
        // pass 2
        findDependencies(dependencyFinder, false);
        analyzer = Analyzer.getModuleAccessAnalyzer();
        boolean pass2 = analyzer.run(sourceLocations);
        if (!pass2) {
            System.out.println("ERROR: Failed module access verification");
        }
        if (pass1 & pass2) {
            System.out.println("Access verification succeeded.");
        }
        return pass1 & pass2;
    }

    private boolean genModuleInfo(DependencyFinder dependencyFinder) throws IOException {
        ModuleInfoBuilder builder = new ModuleInfoBuilder(dependencyFinder, sourceLocations);
        boolean result = builder.run(options.showRequiresPublic, options.verbose, options.nowarning);
        builder.build(Paths.get(options.genModuleInfo));
        return result;
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
            if (o.isHidden() || name.equals("h") || name.startsWith("filter:")) {
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
        boolean showModule;
        boolean showSummary;
        boolean apiOnly;
        boolean showLabel;
        boolean findJDKInternals;
        boolean nowarning = false;
        // default is to show package-level dependencies
        // and filter references from same package
        Analyzer.Type verbose = PACKAGE;
        boolean filterSamePackage = true;
        boolean filterSameArchive = false;
        Pattern filterRegex;
        String dotOutputDir;
        String genModuleInfo;
        String classpath = "";
        int depth = 1;
        Set<String> packageNames = new HashSet<>();
        Pattern regex;             // apply to the dependences
        Pattern includePattern;   // apply to classes
        // module boundary access check
        boolean verifyAccess;
        boolean showRequiresPublic = true;
        Path mpath;
    }
    private static class ResourceBundleHelper {
        static final ResourceBundle versionRB;
        static final ResourceBundle bundle;
        static final ResourceBundle jdkinternals;

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
            try {
                jdkinternals = ResourceBundle.getBundle("com.sun.tools.jdeps.resources.jdkinternals");
            } catch (MissingResourceException e) {
                throw new InternalError("Cannot find jdkinternals resource bundle");
            }
        }
    }

    /*
     * Returns the list of Archive specified in cpaths and not included
     * initialArchives
     */
    private List<Path> getClassPaths(String cpaths) throws IOException
    {
        if (cpaths.isEmpty()) {
            return Collections.emptyList();
        }
        List<Path> paths = new ArrayList<>();
        for (String p : cpaths.split(File.pathSeparator)) {
            if (p.length() > 0) {
                // wildcard to parse all JAR files e.g. -classpath dir/*
                int i = p.lastIndexOf(".*");
                if (i > 0) {
                    Path dir = Paths.get(p.substring(0, i));
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
                        for (Path entry : stream) {
                            paths.add(entry);
                        }
                    }
                } else {
                    paths.add(Paths.get(p));
                }
            }
        }
        return paths;
    }

    /**
     * Test if the given archive is part of the JDK
     */
    private boolean isJDKModule(Archive archive) {
        return Module.class.isInstance(archive);
    }

    /**
     * Returns the recommended replacement API for the given classname;
     * or return null if replacement API is not known.
     */
    private String replacementFor(String cn) {
        String name = cn;
        String value = null;
        while (value == null && name != null) {
            try {
                value = ResourceBundleHelper.jdkinternals.getString(name);
            } catch (MissingResourceException e) {
                // go up one subpackage level
                int i = name.lastIndexOf('.');
                name = i > 0 ? name.substring(0, i) : null;
            }
        }
        return value;
    };

    private void showReplacements(Analyzer analyzer) {
        Map<String,String> jdkinternals = new TreeMap<>();
        boolean useInternals = false;
        for (Archive source : sourceLocations) {
            useInternals = useInternals || analyzer.hasDependences(source);
            for (String cn : analyzer.dependences(source)) {
                String repl = replacementFor(cn);
                if (repl != null) {
                    jdkinternals.putIfAbsent(cn, repl);
                }
            }
        }
        if (useInternals) {
            log.println();
            warning("warn.replace.useJDKInternals", getMessage("jdeps.wiki.url"));
        }
        if (!jdkinternals.isEmpty()) {
            log.println();
            log.format("%-40s %s%n", "JDK Internal API", "Suggested Replacement");
            log.format("%-40s %s%n", "----------------", "---------------------");
            for (Map.Entry<String,String> e : jdkinternals.entrySet()) {
                log.format("%-40s %s%n", e.getKey(), e.getValue());
            }
        }

    }
}
