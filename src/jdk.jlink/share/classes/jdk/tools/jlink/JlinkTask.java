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

package jdk.tools.jlink;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.module.Configuration;
import java.lang.module.Layer;
import java.lang.module.ModuleArtifact;
import java.lang.module.ModuleArtifactFinder;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jdk.internal.jimage.Archive;
import jdk.tools.jlink.TaskHelper.BadArgs;
import jdk.tools.jlink.TaskHelper.HiddenOption;
import jdk.tools.jlink.TaskHelper.Option;
import jdk.tools.jlink.TaskHelper.OptionsHelper;
import jdk.tools.jlink.internal.ModularJarArchive;
import jdk.tools.jlink.internal.JmodArchive;
import jdk.tools.jlink.internal.ImageFileCreator;
import jdk.tools.jlink.internal.ImagePluginConfiguration;
import jdk.tools.jlink.internal.ImagePluginStack;


/**
 * Implementation for the jlink tool.
 *
 * ## Should use jdk.joptsimple some day.
 */
class JlinkTask {

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

    static Option<?>[] recognizedOptions = {
        new Option<JlinkTask>(false, "--help") {
            @Override
            protected void process(JlinkTask task, String opt, String arg) {
                task.options.help = true;
            }
        },
        new Option<JlinkTask>(true, "--modulepath", "--mp") {
            @Override
            protected void process(JlinkTask task, String opt, String arg) {
                String[] dirs = arg.split(File.pathSeparator);
                Path[] paths = new Path[dirs.length];
                int i = 0;
                for (String dir: dirs) {
                    paths[i++] = Paths.get(dir);
                }
                task.options.moduleFinder = ModuleArtifactFinder.ofDirectories(paths);
            }
        },
        new Option<JlinkTask>(true, "--addmods") {
            @Override
            protected void process(JlinkTask task, String opt, String arg)
                    throws BadArgs {
                for (String mn : arg.split(",")) {
                    if (mn.isEmpty())
                        throw taskHelper.newBadArgs("err.jmod.not.found", mn);
                    task.options.jmods.add(mn);
                }
            }
        },
        new Option<JlinkTask>(true, "--output") {
            @Override
            protected void process(JlinkTask task, String opt, String arg)
                    throws BadArgs {
                Path path = Paths.get(arg);
                task.options.output = path;
            }
        },
        new Option<JlinkTask>(false, "--version") {
            @Override
            protected void process(JlinkTask task, String opt, String arg) {
                task.options.version = true;
            }
        },
        new HiddenOption<JlinkTask>(false, "--fullversion") {
            @Override
            protected void process(JlinkTask task, String opt, String arg) {
                task.options.fullVersion = true;
            }
        },
    };

    private static final String PROGNAME = "jlink";
    private final OptionsValues options = new OptionsValues();
    private static final TaskHelper taskHelper =
            new TaskHelper("jdk.tools.jlink.resources.jlink");
    private static final OptionsHelper<JlinkTask> optionsHelper =
            taskHelper.newOptionsHelper(JlinkTask.class, recognizedOptions);
    private PrintWriter log;
    void setLog(PrintWriter out) {
        log = out;
        taskHelper.setLog(log);
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

    static class OptionsValues {
        boolean help;
        boolean version;
        boolean fullVersion;
        ModuleArtifactFinder moduleFinder;
        Set<String> jmods = new TreeSet<>();
        boolean compress = false;
        Path output;
    }

    int run(String[] args) {
        if (log == null) {
            setLog(new PrintWriter(System.out));
        }
        try {
            optionsHelper.handleOptions(this, args);
            if (options.help) {
                optionsHelper.showHelp(PROGNAME, "jimage creation only options:");
                return EXIT_OK;
            }
            if (options.version || options.fullVersion) {
                taskHelper.showVersion(options.fullVersion);
                return EXIT_OK;
            }
            if(optionsHelper.listPlugins()) {
                 optionsHelper.showPlugins(log);
                 return EXIT_OK;
            }
            if (options.moduleFinder == null)
                throw taskHelper.newBadArgs("err.modulepath.must.be.specified").showUsage(true);

            Path output = options.output;
            if (output == null)
                throw taskHelper.newBadArgs("err.output.must.be.specified").showUsage(true);
            Files.createDirectories(output);
            if (Files.list(output).findFirst().isPresent())
                throw taskHelper.newBadArgs("err.dir.not.empty", output);

            if (options.jmods.isEmpty())  // ## default to jdk.base ??
                throw taskHelper.newBadArgs("err.mods.must.be.specified").showUsage(true);

            // additional option combination validation

            boolean ok = run();
            return ok ? EXIT_OK : EXIT_ERROR;
        } catch (BadArgs e) {
            taskHelper.reportError(e.key, e.args);
            if (e.showUsage) {
                log.println(taskHelper.getMessage("main.usage.summary", PROGNAME));
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
        createImage();
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

            // jspawnhelper is in lib or lib/<arch>
            Path lib = output.resolve("lib");
            Files.find(lib, 2, (path, attrs) -> {
                return path.getFileName().toString().equals("jspawnhelper");
            }).forEach(this::setExecutable);
        }

        // generate launch scripts for the modules with a main class
        for (Map.Entry<String, Path> entry : mods.entrySet()) {
            String module = entry.getKey();
            Path jmodpath = entry.getValue();

            Optional<String> mainClass = Optional.empty();

            try (ZipFile zf = new ZipFile(jmodpath.toString())) {
                String e = Section.CLASSES.jmodDir() + "/" + MODULE_INFO;
                ZipEntry ze = zf.getEntry(e);
                if (ze != null) {
                    try (InputStream in = zf.getInputStream(ze)) {
                        mainClass = ModuleDescriptor.read(in).mainClass();
                    }
                }
            }

            if (mainClass.isPresent()) {
                Path cmd = output.resolve("bin").resolve(module);
                if (!Files.exists(cmd)) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("#!/bin/sh")
                      .append("\n");
                    sb.append("DIR=`dirname $0`")
                      .append("\n");
                    sb.append("$DIR/java -m ")
                      .append(module).append('/')
                      .append(mainClass.get())
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
        final Map<String, Path> modsPaths;

        ImageFileHelper(Configuration cf, Map<String, Path> modsPaths) throws IOException {
            this.modules = cf.descriptors();
            this.modsPaths = modsPaths;
        }

        void createModularImage(Path output) throws IOException {
            Set<Archive> archives = modsPaths.entrySet().stream()
                    .map(e -> newArchive(e.getKey(), e.getValue()))
                    .collect(Collectors.toSet());
            ImagePluginStack pc = ImagePluginConfiguration.
                    parseConfiguration(taskHelper.getPluginsProperties());
            ImageFileCreator.create(output, archives, pc);
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
}
