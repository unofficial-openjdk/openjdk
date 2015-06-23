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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.lang.module.Configuration;
import java.lang.module.Layer;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.Formatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    private static final TaskHelper taskHelper
            = new TaskHelper("jdk.tools.jlink.resources.jlink");

    static Option<?>[] recognizedOptions = {
        new Option<JlinkTask>(false, (task, opt, arg) -> {
            task.options.help = true;
        }, "--help"),
        new Option<JlinkTask>(false, (task, opt, arg) -> {
            task.options.genbom = true;
        }, "--genbom"),
        new Option<JlinkTask>(true, (task, opt, arg) -> {
            String[] dirs = arg.split(File.pathSeparator);
            Path[] paths = new Path[dirs.length];
            int i = 0;
            for (String dir : dirs) {
                paths[i++] = Paths.get(dir);
            }
            task.options.moduleFinder = ModuleFinder.of(paths);
        }, "--modulepath", "--mp"),
        new Option<JlinkTask>(true, (task, opt, arg) -> {
            for (String mn : arg.split(",")) {
                if (mn.isEmpty()) {
                    throw taskHelper.newBadArgs("err.mods.must.be.specified",
                                                "--limitmods");
                }
                task.options.limitMods.add(mn);
            }
        }, "--limitmods"),
        new Option<JlinkTask>(true, (task, opt, arg) -> {
            for (String mn : arg.split(",")) {
                if (mn.isEmpty()) {
                    throw taskHelper.newBadArgs("err.mods.must.be.specified",
                                                "--addmods");
                }
                task.options.addMods.add(mn);
            }
        }, "--addmods"),
        new Option<JlinkTask>(true, (task, opt, arg) -> {
            Path path = Paths.get(arg);
            task.options.output = path;
        }, "--output"),
        new Option<JlinkTask>(false, (task, opt, arg) -> {
            task.options.version = true;
        }, "--version"),
        new HiddenOption<JlinkTask>(false, (task, opt, arg) -> {
            task.options.fullVersion = true;
        }, "--fullversion"),
    };

    private static final String PROGNAME = "jlink";
    private final OptionsValues options = new OptionsValues();

    private static final OptionsHelper<JlinkTask> optionsHelper =
            taskHelper.newOptionsHelper(JlinkTask.class, recognizedOptions);
    private PrintWriter log;
    void setLog(PrintWriter out) {
        log = out;
        taskHelper.setLog(log);
    }

    /**
     * Result codes.
     */
    static final int EXIT_OK = 0, // Completed with no errors.
                     EXIT_ERROR = 1, // Completed but reported errors.
                     EXIT_CMDERR = 2, // Bad command-line arguments
                     EXIT_SYSERR = 3, // System error or resource exhaustion.
                     EXIT_ABNORMAL = 4;// terminated abnormally

    static class OptionsValues {
        boolean genbom;
        boolean help;
        boolean version;
        boolean fullVersion;
        ModuleFinder moduleFinder;
        Set<String> limitMods = new HashSet<>();
        Set<String> addMods = new HashSet<>();
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

            // --addmods and/or --limitmods must be specified
            if (options.addMods.isEmpty()) {
                if (options.limitMods.isEmpty())
                    throw taskHelper.newBadArgs("err.mods.must.be.specified", "--addmods")
                                    .showUsage(true);
                options.addMods = options.limitMods;
            }

            // additional option combination validation

            createImage();

            return EXIT_OK;
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

    private void createImage() throws IOException {
        final Path output = options.output;

        ModuleFinder finder = options.moduleFinder;

        // if --limitmods is specified then limit the universe
        if (!options.limitMods.isEmpty())
            finder = limitFinder(finder, options.limitMods);

        Configuration cf
            = Configuration.resolve(finder,
                Layer.empty(),
                ModuleFinder.empty(),
                options.addMods);

        cf = cf.bind();

        Map<String, Path> mods = modulesToPath(cf.descriptors());

        ImageFileHelper imageHelper = new ImageFileHelper(cf, mods);
        imageHelper.createModularImage(output, mods);

        if (options.genbom) {
            genBOM(options.output);
        }
    }

    /**
     * Returns a ModuleFinder that locates modules via the given ModuleFinder
     * but limits what can be found to the given modules and their transitive
     * dependences.
     */
    private ModuleFinder limitFinder(ModuleFinder finder, Set<String> mods) {
        Configuration cf
            = Configuration.resolve(finder,
                Layer.empty(),
                ModuleFinder.empty(),
                mods);

        // module name -> reference
        Map<String, ModuleReference> map = new HashMap<>();
        cf.descriptors().forEach(md -> {
            String name = md.name();
            map.put(name, finder.find(name).get());
        });

        Set<ModuleReference> mrefs = new HashSet<>(map.values());

        return new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String name) {
                return Optional.ofNullable(map.get(name));
            }
            @Override
            public Set<ModuleReference> findAll() {
                return mrefs;
            }
        };
    }

    private void genBOM(Path root) throws IOException {
        File bom = new File(root.toFile(), "bom");
        StringBuilder sb = new StringBuilder();
        sb.append("#").append(new Date()).append("\n");
        sb.append("#Please DO NOT Modify this file").append("\n");
        StringBuilder command = new StringBuilder();
        for (String c : optionsHelper.getInputCommand()) {
            command.append(c).append(" ");
        }
        sb.append("command").append(" = ").append(command);
        sb.append("\n");

        // Expanded command
        String[] expanded = optionsHelper.getExpandedCommand();
        if (expanded != null) {
            String defaults = optionsHelper.getDefaults();
            sb.append("\n").append("#Defaults").append("\n");
            sb.append("defaults = ").append(defaults).append("\n");

            StringBuilder builder = new StringBuilder();
            for (String c : expanded) {
                builder.append(c).append(" ");
            }
            sb.append("expanded command").append(" = ").append(builder);
            sb.append("\n");
        }

        String pluginsContent = optionsHelper.getPluginsConfig();
        if (pluginsContent != null) {
            sb.append("\n").append("# Plugins configuration\n");
            sb.append(pluginsContent);
        }
        try {
            createUtf8File(bom, sb.toString());
        } catch (IOException ex) {
            fail(RuntimeException.class, taskHelper.getMessage("err.bom.generation",
                    ex.toString()));
        }
    }

    private static void createUtf8File(File file, String content) throws IOException {
        try (OutputStream fout = new FileOutputStream(file);
             Writer output = new OutputStreamWriter(fout, "UTF-8")) {
            output.write(content);
        }
    }

    private class ImageFileHelper {
        final Set<ModuleDescriptor> modules;
        final Map<String, Path> modsPaths;

        ImageFileHelper(Configuration cf, Map<String, Path> modsPaths) throws IOException {
            this.modules = cf.descriptors();
            this.modsPaths = modsPaths;
        }

        void createModularImage(Path output, Map<String, Path> mods) throws IOException {
            Set<Archive> archives = modsPaths.entrySet().stream()
                    .map(e -> newArchive(e.getKey(), e.getValue()))
                    .collect(Collectors.toSet());
            ImagePluginStack pc = ImagePluginConfiguration.
                    parseConfiguration(output, mods,
                            taskHelper.getPluginsProperties());
            ImageFileCreator.create(archives, pc);
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
