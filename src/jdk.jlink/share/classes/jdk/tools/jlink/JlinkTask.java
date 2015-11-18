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

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.module.Configuration;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ResolutionException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Layer;
import java.net.URI;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Formatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jdk.tools.jlink.internal.Archive;
import jdk.internal.module.ConfigurableModuleFinder;
import jdk.internal.module.ConfigurableModuleFinder.Phase;
import jdk.tools.jlink.TaskHelper.BadArgs;
import jdk.tools.jlink.TaskHelper.HiddenOption;
import static jdk.tools.jlink.TaskHelper.JLINK_BUNDLE;
import jdk.tools.jlink.TaskHelper.Option;
import jdk.tools.jlink.TaskHelper.OptionsHelper;
import jdk.tools.jlink.internal.ModularJarArchive;
import jdk.tools.jlink.internal.JmodArchive;
import jdk.tools.jlink.internal.DirArchive;
import jdk.tools.jlink.internal.ImageFileCreator;
import jdk.tools.jlink.internal.ImagePluginConfiguration;
import jdk.tools.jlink.internal.ImagePluginProviderRepository;
import jdk.tools.jlink.internal.ImagePluginStack;
import jdk.tools.jlink.internal.ImagePluginStack.ImageProvider;
import jdk.tools.jlink.plugins.ExecutableImage;
import jdk.tools.jlink.plugins.ImageBuilderProvider;
import jdk.tools.jlink.plugins.Jlink.JlinkConfiguration;
import jdk.tools.jlink.plugins.Jlink.PluginsConfiguration;


/**
 * Implementation for the jlink tool.
 *
 * ## Should use jdk.joptsimple some day.
 */
public class JlinkTask {

    private static <T extends Throwable> void fail(Class<T> type,
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
            = new TaskHelper(JLINK_BUNDLE);

    static Option<?>[] recognizedOptions = {
        new Option<JlinkTask>(false, (task, opt, arg) -> {
            task.options.help = true;
        }, "--help"),
        new Option<JlinkTask>(true, (task, opt, arg) -> {
            String[] dirs = arg.split(File.pathSeparator);
            task.options.modulePath = new Path[dirs.length];
            int i = 0;
            for (String dir : dirs) {
                task.options.modulePath[i++] = Paths.get(dir);
            }
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
        new Option<JlinkTask>(true, (task, opt, arg) -> {
            Path path = Paths.get(arg);
            if (!Files.exists(path) || !Files.isDirectory(path)) {
                throw taskHelper.newBadArgs("err.existing.image.must.exist");
            }
            task.options.existingImage = path;
        }, "--post-process-path"),
        new Option<JlinkTask>(true, (task, opt, arg) -> {
            if ("little".equals(arg)) {
                task.options.endian = ByteOrder.LITTLE_ENDIAN;
            } else {
                if ("big".equals(arg)) {
                    task.options.endian = ByteOrder.BIG_ENDIAN;
                } else {
                    throw taskHelper.newBadArgs("err.unknown.byte.order", arg);
                }
            }
        }, "--endian"),
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
        boolean help;
        boolean version;
        boolean fullVersion;
        Path[] modulePath;
        Set<String> limitMods = new HashSet<>();
        Set<String> addMods = new HashSet<>();
        Path output;
        Path existingImage;
        ByteOrder endian = ByteOrder.nativeOrder();
    }

    int run(String[] args) {
        if (log == null) {
            setLog(new PrintWriter(System.err));
        }
        try {
            optionsHelper.handleOptions(this, args);
            if (options.help) {
                optionsHelper.showHelp(PROGNAME, "jimage creation only options:", true);
                return EXIT_OK;
            }
            if (options.version || options.fullVersion) {
                taskHelper.showVersion(options.fullVersion);
                return EXIT_OK;
            }
            if(optionsHelper.listPlugins()) {
                optionsHelper.showPlugins(log, true);
                 return EXIT_OK;
            }
            if (options.existingImage == null) {
                if (options.modulePath == null || options.modulePath.length == 0) {
                    throw taskHelper.newBadArgs("err.modulepath.must.be.specified").showUsage(true);
                }
                createImage();
            } else {
                postProcessOnly();
            }

            return EXIT_OK;
        } catch (IOException | ResolutionException e) {
            log.println(taskHelper.getMessage("error.prefix") + " " + e.getMessage());
            log.println(taskHelper.getMessage("main.usage.summary", PROGNAME));
            return EXIT_ERROR;
        } catch (BadArgs e) {
            taskHelper.reportError(e.key, e.args);
            if (e.showUsage) {
                log.println(taskHelper.getMessage("main.usage.summary", PROGNAME));
            }
            return EXIT_CMDERR;
        } catch (Throwable x) {
            log.println(taskHelper.getMessage("main.msg.bug"));
            x.printStackTrace(log);
            return EXIT_ABNORMAL;
        } finally {
            log.flush();
        }
    }

    private static Map<String, Path> modulesToPath(ModuleFinder finder,
                                                   Set<ModuleDescriptor> modules)
    {
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
            if (!scheme.equalsIgnoreCase("jmod") && !scheme.equalsIgnoreCase("jar")
                    && !scheme.equalsIgnoreCase("file")) {
                fail(RuntimeException.class,
                        "Selected module %s (%s) not in jmod, modular jar or directory format",
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
                if (scheme.equalsIgnoreCase("jar")) {
                    // jar:file:/home/duke/duke.jar!/ -> file:/home/duke/duke.jar
                    String s = location.toString();
                    fileURI = URI.create(s.substring(4, s.length() - 2));
                } else {
                    fileURI = URI.create(location.toString());
                }
            }

            modPaths.put(name, Paths.get(fileURI));
        }
        return modPaths;
    }

    /*
     * Jlink API entry point.
     */
    public static void createImage(JlinkConfiguration config,
            PluginsConfiguration plugins)
            throws Exception
    {
        Objects.requireNonNull(config);
        Objects.requireNonNull(config.getOutput());
        plugins = plugins == null ? new PluginsConfiguration() : plugins;

        if (config.getModulepaths().isEmpty()) {
            throw new Exception("Empty module paths");
        }
        Path[] arr = new Path[config.getModulepaths().size()];
        arr = config.getModulepaths().toArray(arr);
        ModuleFinder finder
            = newModuleFinder(arr, config.getLimitmods(), config.getModules());

        Path[] pluginsPath = new Path[config.getPluginpaths().size()];
        pluginsPath = config.getPluginpaths().toArray(pluginsPath);

        // First create the image provider
        ImageProvider imageProvider
                = createImageProvider(config.getOutput(),
                        finder,
                        checkAddMods(config.getModules()),
                        config.getLimitmods(),
                        genBOMContent(config, plugins), config.getByteOrder());

        // Then create the Plugin Stack
        ImagePluginStack stack = ImagePluginConfiguration.
                parseConfiguration(config.getOutput(), plugins,
                        TaskHelper.createPluginsLayer(pluginsPath),
                        genBOMContent(config, plugins));

        //Ask the stack to proceed;
        stack.operate(imageProvider);
    }

    private class PostProcessingImageHelper implements ImageProvider {

        private ImageBuilderProvider provider;
        @Override
        public ExecutableImage retrieve(ImagePluginStack stack) throws IOException {
            ExecutableImage img = null;
            for (ImageBuilderProvider provider
                    : ImagePluginProviderRepository.
                    getImageBuilderProviders(optionsHelper.getPluginsLayer())) {
                img = provider.canExecute(options.existingImage);
                if (img != null) {
                    this.provider = provider;
                }
            }
            if (img == null) {
                throw new IOException("Image in " + options.existingImage + " is not recognized");
            }
            return img;
        }

        @Override
        public void storeLauncherArgs(ImagePluginStack stack, ExecutableImage image,
                List<String> args) throws IOException {
            provider.storeLauncherOptions(image, args);
        }

    }

    private void postProcessOnly() throws Exception {
        // Create the Plugin Stack
        ImagePluginStack stack = ImagePluginConfiguration.
                parseConfiguration(null, taskHelper.getPluginsConfig(),
                        optionsHelper.getPluginsLayer(),
                        genBOMContent());

        stack.operate(new PostProcessingImageHelper());
    }

    private void createImage() throws Exception {
        if (options.output == null) {
            throw taskHelper.newBadArgs("err.output.must.be.specified").showUsage(true);
        }
        ModuleFinder finder
            = newModuleFinder(options.modulePath, options.limitMods, options.addMods);
        try {
            options.addMods = checkAddMods(options.addMods);
        } catch (IllegalArgumentException ex) {
            throw taskHelper.newBadArgs("err.mods.must.be.specified", "--addmods")
                    .showUsage(true);
        }
        // First create the image provider
        ImageProvider imageProvider
                = createImageProvider(options.output, finder,
                        options.addMods,
                        options.limitMods,
                        genBOMContent(),
                        options.endian);

        // Then create the Plugin Stack
        ImagePluginStack stack = ImagePluginConfiguration.
                parseConfiguration(options.output, taskHelper.getPluginsConfig(),
                        optionsHelper.getPluginsLayer(),
                        genBOMContent());

        //Ask the stack to proceed
        stack.operate(imageProvider);
    }

    private static Set<String> checkAddMods(Set<String> addMods) {
        if (addMods.isEmpty()) {
            throw new IllegalArgumentException("no modules to add");
        }
        return addMods;
    }

    private static ModuleFinder newModuleFinder(Path[] paths,
                                                Set<String> limitMods,
                                                Set<String> addMods)
    {
        ModuleFinder finder = ModuleFinder.of(paths);

        // jmods are located at link-time
        if (finder instanceof ConfigurableModuleFinder)
            ((ConfigurableModuleFinder)finder).configurePhase(Phase.LINK_TIME);

        // if limitmods is specified then limit the universe
        if (!limitMods.isEmpty()) {
            finder = limitFinder(finder, limitMods, addMods);
        }
        return finder;
    }

    private static ImageProvider createImageProvider(Path output,
            ModuleFinder finder,
            Set<String> addMods,
            Set<String> limitMods,
            String bom,
            ByteOrder order)
            throws IOException {
        if (addMods.isEmpty()) {
            throw new IllegalArgumentException("empty modules and limitmods");
        }
        Configuration cf
                = Configuration.resolve(finder,
                        Layer.empty(),
                        ModuleFinder.empty(),
                        addMods);
        Map<String, Path> mods = modulesToPath(finder, cf.descriptors());
        return new ImageHelper(cf, mods, output, bom, order);
    }


    /**
     * Returns a ModuleFinder that limits observability to the given root
     * modules, their transitive dependences, plus a set of other modules.
     */
    private static ModuleFinder limitFinder(ModuleFinder finder,
                                            Set<String> roots,
                                            Set<String> otherMods)
    {
        // resolve all root modules
        Configuration cf = Configuration.resolve(finder,
                Layer.empty(),
                ModuleFinder.empty(),
                roots);

        // module name -> reference
        Map<String, ModuleReference> map = new HashMap<>();
        cf.descriptors().forEach(md -> {
            String name = md.name();
            map.put(name, finder.find(name).get());
        });

        // set of modules that are observable
        Set<ModuleReference> mrefs = new HashSet<>(map.values());

        // add the other modules
        for (String mod : otherMods) {
            Optional<ModuleReference> omref = finder.find(mod);
            if (omref.isPresent()) {
                ModuleReference mref = omref.get();
                map.putIfAbsent(mod, mref);
                mrefs.add(mref);
            } else {
                // no need to fail
            }
        }

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

    private static String getBomHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("#").append(new Date()).append("\n");
        sb.append("#Please DO NOT Modify this file").append("\n");
        return sb.toString();
    }

    private String genBOMContent() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(getBomHeader());
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

        return sb.toString();
    }

    private static String genBOMContent(JlinkConfiguration config,
                                        PluginsConfiguration plugins)
        throws IOException
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getBomHeader());
        sb.append(config);
        sb.append(plugins);
        return sb.toString();
    }

    private static class ImageHelper implements ImageProvider {
        final Path output;
        final Set<Archive> archives;
        final String bom;
        final ByteOrder order;

        ImageHelper(Configuration cf,
                Map<String, Path> modsPaths,
                Path output,
                String bom,
                ByteOrder order)
                throws IOException {
            this.output = output;
            this.bom = bom;
            archives = modsPaths.entrySet().stream()
                    .map(e -> newArchive(e.getKey(), e.getValue()))
                    .collect(Collectors.toSet());
            this.order = order;
        }

        private Archive newArchive(String module, Path path) {
            if (path.toString().endsWith(".jmod")) {
                return new JmodArchive(module, path);
            } else {
                if (path.toString().endsWith(".jar")) {
                    return new ModularJarArchive(module, path);
                } else {
                    if (Files.isDirectory(path)) {
                        return new DirArchive(path);
                    } else {
                        fail(RuntimeException.class,
                                "Selected module %s (%s) not in jmod or modular jar format",
                                module,
                                path);
                    }
                }
            }
            return null;
        }

        @Override
        public ExecutableImage retrieve(ImagePluginStack stack) throws IOException {
            return ImageFileCreator.create(archives, order, stack);
        }

        @Override
        public void storeLauncherArgs(ImagePluginStack stack, ExecutableImage image, List<String> args) throws IOException {
            stack.getImageBuilder().storeJavaLauncherOptions(image, args);
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
