/*
 * Copyright (c) 2009, 2013, Oracle and/or its affiliates. All rights reserved.
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

import com.sun.tools.classanalyzer.Module.ModuleVisitor;
import java.io.*;
import java.text.MessageFormat;
import java.util.*;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Implementation for the ClassAnalyzer tool
 */
class ClassAnalyzer {
    static class BadArgs extends Exception {
        static final long serialVersionUID = 8765093759964640721L;
        BadArgs(String key, Object... args) {
            super(ClassAnalyzer.getMessage(key, args));
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
                if (hasArg && a.startsWith("--") && opt.startsWith(a + "="))
                    return true;
            }
            return false;
        }

        boolean ignoreRest() {
            return false;
        }

        abstract void process(ClassAnalyzer task, String opt, String arg) throws BadArgs;
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
        new Option(false, "-h", "-?", "--help") {
            void process(ClassAnalyzer task, String opt, String arg) {
                task.options.help = true;
            }
        },
        new Option(true, "-j", "--javahome") {
            void process(ClassAnalyzer task, String opt, String arg) {
                task.options.javahome = arg;
            }
        },
        new Option(true, "-v", "--version") {
            void process(ClassAnalyzer task, String opt, String arg) {
                task.options.version = arg;
            }
        },
        new Option(true, "-c", "--classlist") {
            void process(ClassAnalyzer task, String opt, String arg) {
                task.options.classlistDir = arg;
            }
        },
        new Option(true, "--moduleinfo") {
            void process(ClassAnalyzer task, String opt, String arg) {
                task.options.moduleInfoDir = arg;
            }
        },
        new Option(true, "-o", "--out") {
            void process(ClassAnalyzer task, String opt, String arg) {
                task.options.outFile = arg;
            }
        },
        new Option(true, "-f", "--config") {
            void process(ClassAnalyzer task, String opt, String arg) {
                task.options.configs.add(arg);
            }
        },
        new Option(true, "-p", "--properties") {
            void process(ClassAnalyzer task, String opt, String arg) {
                task.options.props = arg;
            }
        },
    };

    private static final String PROGNAME = "ClassAnalyzer";
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

    int run(String[] args) {
        if (log == null) {
            log = new PrintWriter(System.out);
        }
        try {
            handleOptions(args);
            if (options.help) {
                showHelp();
            }
            if (options.configs.isEmpty() || options.version == null) {
                if (options.help) {
                    return EXIT_OK;
                } else {
                    showHelp();
                    return EXIT_CMDERR;
                }
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

    private boolean run() throws IOException {
        if (options.props != null) {
            Module.setModuleProperties(options.props);
        }
        final ModuleBuilder builder = new ModuleBuilder(options.configs,
                                                  ClassPath.getArchives(options.javahome),
                                                  options.version);
        builder.run();

        final Path dir = Paths.get(options.classlistDir);
        dir.toFile().mkdirs();
        File list = dir.resolve("modules.list").toFile();
        int count;
        try (PrintWriter writer = new PrintWriter(list)) {
            builder.visit(new ModuleBuilder.Visitor<Void, Void>() {
                @Override
                public Void visitModule(Module m) throws IOException {
                    printModulePackages(m, dir);
                    printModuleGroup(m, writer);
                    printModuleSummary(m, dir, builder);
                    return null;
                }
            });
        }

        // write split packages
        Map<String, Set<Module>> modulesForPackage = builder.getPackages();
        File pkginfo = dir.resolve("modules.pkginfo").toFile();
        try (PrintWriter writer = new PrintWriter(pkginfo)) {
            // packages that are splitted among multiple modules
            writer.println("Packages splitted across modules:-\n");
            writer.format("%-60s  %s\n", "Package", "Module");
            for (Map.Entry<String, Set<Module>> e : modulesForPackage.entrySet()) {
                if (e.getValue().size() > 1) {
                    String pkgname = e.getKey();
                    writer.format("%-60s", pkgname);
                    for (Module m : e.getValue()) {
                        writer.format("  %s", m);
                    }
                    writer.println();
                }
            }
        }
        if (options.outFile != null) {
            Path p = Paths.get(options.outFile).getParent();
            if (p != null)
                p.toFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(options.outFile)) {
                builder.store(out);
            }

        }
        if (options.moduleInfoDir != null) {
            builder.printModuleInfos(options.moduleInfoDir);
        }
        return true;
    }

    private void printModuleSummary(Module m, Path dir, ModuleBuilder builder) throws IOException {
        if (!m.classes().isEmpty()) {
            File classlist = dir.resolve(m.name() + ".classlist").toFile();
            File depslist = dir.resolve(m.name() + ".dependencies").toFile();
            try (PrintWriter writer = new PrintWriter(classlist);
                    PrintWriter dwriter = new PrintWriter(depslist)) {
                Set<Klass> classes = new TreeSet<>(m.classes());
                for (Klass k : classes) {
                    writer.format("%s\n", k.getClassFilePathname());
                    for (Klass to : builder.getDeps(k)) {
                        dwriter.format("%-40s -> %s (%s)%n",
                                k.getClassName(), to, to.getModule().group());
                    }
                }
            }
        }

        if (!m.resources().isEmpty()) {
            File reslist = dir.resolve(m.name() + ".resources").toFile();
            try (PrintWriter writer = new PrintWriter(reslist)) {
                Set<Resource> resources = new TreeSet<Resource>(m.resources());
                for (Resource res : resources) {
                    writer.format("%s\n", res.getPathname());
                }
            }
        }
    }

    private void printModulePackages(Module m, Path dir) throws IOException {
        File summary = dir.resolve(m.name() + ".summary").toFile();
        try (PrintWriter writer = new PrintWriter(summary)) {
            long total = 0;
            int count = 0;
            long resBytes = 0;
            int resCount = 0;
            writer.format("%10s\t%10s\t%s%n", "Bytes", "Classes", "Package name");
            Set<Package> pkgs = new TreeSet<>(m.packages());
            for (Package p : pkgs) {
                writer.format("%10d\t%10d\t%s%n",
                        p.classBytes, p.classCount, p.name());
                total += p.classBytes;
                count += p.classCount;

            }
            for (Resource rf : m.resources()) {
                resCount++;
                resBytes += rf.getFileSize();
            }

            writer.format("%nTotal: %d bytes (uncompressed) %d classes "
                    + "%d bytes %d resources %n",
                    total, count, resBytes, resCount);
        }
    }

    private void printModuleGroup(Module group, PrintWriter writer) throws IOException {
        ModuleVisitor<Set<Module>> visitor = new ModuleVisitor<Set<Module>>() {
            public void preVisit(Module p, Set<Module> leafnodes) {
            }

            public void visited(Module p, Module m, Set<Module> leafnodes) {
                if (m.members().isEmpty()) {
                    leafnodes.add(m);
                }
            }

            public void postVisit(Module p, Set<Module> leafnodes) {
            }
        };

        Set<Module> visited = new TreeSet<>();
        Set<Module> members = new TreeSet<>();
        group.visitMembers(visited, visitor, members);

        // prints leaf members that are the modules defined in
        // the modules.config files
        writer.format("%s ", group);
        for (Module m : members) {
            writer.format("%s ", m);
        }
        writer.println();
    }

    public void handleOptions(String[] args) throws BadArgs {
        // process options
        for (int i=0; i < args.length; i++) {
            if (args[i].charAt(0) == '-') {
                String name = args[i];
                Option option = getOption(name);
                String param = null;
                if (option.hasArg) {
                    if (name.indexOf('=') > 0) {
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
                throw new IllegalArgumentException("Invalid arguments");
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

    static String getMessage(String key, Object... args) {
        try {
            return MessageFormat.format(ResourceBundleHelper.bundle.getString(key), args);
        } catch (MissingResourceException e) {
            throw new InternalError("Missing message: " + key);
        }
    }

    private static class Options {
        boolean help;
        String outFile;
        String javahome = System.getProperty("java.home");
        String props;
        String classlistDir;
        String moduleInfoDir;
        List<String> configs = new ArrayList<>();
        String version;
    }

    private static class ResourceBundleHelper {
        static final ResourceBundle bundle;
        static {
            Locale locale = Locale.getDefault();
            try {
                bundle = ResourceBundle.getBundle("com.sun.tools.classanalyzer.resources.classanalyzer", locale);
            } catch (MissingResourceException e) {
                throw new InternalError("Cannot find classanalyzer resource bundle for locale " + locale);
            }
        }
    }

    public static void main(String... args) throws Exception {
        ClassAnalyzer analyzer = new ClassAnalyzer();
        int rc = analyzer.run(args);
        System.exit(rc);
    }
}
