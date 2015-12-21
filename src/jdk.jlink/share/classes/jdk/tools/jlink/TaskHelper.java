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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Layer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.ResourceBundle;

import jdk.internal.module.ConfigurableModuleFinder;
import jdk.internal.module.ConfigurableModuleFinder.Phase;
import jdk.tools.jlink.internal.PluginRepository;
import jdk.tools.jlink.internal.ImagePluginConfiguration;
import jdk.tools.jlink.api.plugin.builder.DefaultImageBuilderProvider;
import jdk.tools.jlink.api.Jlink;
import jdk.tools.jlink.api.Jlink.OrderedPlugin;
import jdk.tools.jlink.api.Jlink.PluginsConfiguration;
import jdk.tools.jlink.api.plugin.Plugin;
import jdk.tools.jlink.api.plugin.Plugin.PluginOption;
import jdk.tools.jlink.api.plugin.Plugin.PluginOption.Builder;
import jdk.tools.jlink.api.plugin.Plugin.CATEGORY;
import jdk.tools.jlink.api.plugin.Plugin.ORDER;
import jdk.tools.jlink.api.plugin.builder.DefaultImageBuilder;
import jdk.tools.jlink.api.plugin.builder.ImageBuilder;
import jdk.tools.jlink.internal.Utils;

/**
 *
 * JLink and JImage tools shared helper.
 */
public final class TaskHelper {

    public static final String JLINK_BUNDLE = "jdk.tools.jlink.resources.jlink";
    public static final String JIMAGE_BUNDLE = "jdk.tools.jimage.resources.jimage";

    private static final String DEFAULTS_PROPERTY = "jdk.jlink.defaults";
    private static final String CONFIGURATION = "configuration";

    public final class BadArgs extends Exception {

        static final long serialVersionUID = 8765093759964640721L;

        private BadArgs(String key, Object... args) {
            super(bundleHelper.getMessage(key, args));
            this.key = key;
            this.args = args;
        }

        public BadArgs showUsage(boolean b) {
            showUsage = b;
            return this;
        }
        public final String key;
        public final Object[] args;
        public boolean showUsage;
    }

    public static class Option<T> {

        public interface Processing<T> {

            void process(T task, String opt, String arg) throws BadArgs;
        }

        final boolean hasArg;
        final String[] aliases;
        final Processing<T> processing;

        public Option(boolean hasArg, Processing<T> processing, String... aliases) {
            this.hasArg = hasArg;
            this.processing = processing;
            this.aliases = aliases;
        }

        public boolean isHidden() {
            return false;
        }

        public boolean matches(String opt) {
            for (String a : aliases) {
                if (a.equals(opt)) {
                    return true;
                } else if (opt.startsWith("--")
                        && (hasArg && opt.startsWith(a + "="))) {
                    return true;
                }
            }
            return false;
        }

        public boolean ignoreRest() {
            return false;
        }

        void process(T task, String opt, String arg) throws BadArgs {
            processing.process(task, opt, arg);
        }
    }

    private static class PlugOption extends Option<PluginsOptions> {

        public PlugOption(boolean hasArg,
                Processing<PluginsOptions> processing, String... aliases) {
            super(hasArg, processing, aliases);
        }

        @Override
        public boolean matches(String opt) {
            return super.matches(removeIndex(opt));
        }
    }

    private int getIndex(String opt) throws BadArgs {
        String orig = opt;
        int i = opt.indexOf(":");
        int index = -1;
        if (i != -1) {
            opt = opt.substring(i + 1);
            if (opt.equals(Plugin.ORDER.FIRST.getName())) {
                index = 0;
            } else if (opt.equals(Plugin.ORDER.LAST.getName())) {
                index = Integer.MAX_VALUE;
            } else {
                try {
                    index = Integer.parseInt(opt);
                } catch (NumberFormatException ex) {
                    throw newBadArgs("err.invalid.index", orig);
                }
            }
        }
        return index;
    }

    private static String removeIndex(String opt) {
        //has index? remove it
        int i = opt.indexOf(":");
        if (i != -1) {
            opt = opt.substring(0, i);
        }
        return opt;
    }

    private static class HiddenPluginOption extends PlugOption {

        public HiddenPluginOption(boolean hasArg,
                Processing<PluginsOptions> processing, String... aliases) {
            super(hasArg, processing, aliases);
        }

        @Override
        public boolean isHidden() {
            return true;
        }
    }

    private final class PluginsOptions {

        private static final String PLUGINS_PATH = "--plugins-modulepath";

        private Layer pluginsLayer = Layer.boot();
        private String lastSorter;
        private boolean listPlugins;
        private final Map<Plugin, Map<PluginOption, String>> plugins = new HashMap<>();
        private final Map<PluginOption, String> imgBuilder = new HashMap<>();
        private final List<PlugOption> pluginsOptions = new ArrayList<>();

        // The order in which options are declared is the stack order.
        // Order is within plugin category
        private final Map<CATEGORY, List<Plugin>> pluginsOrder = new HashMap<>();
        private final Map<Plugin, Integer> pluginsIndexes = new HashMap<>();

        private PluginsOptions(String pp) throws BadArgs {

            if (pp != null) {
                String[] dirs = pp.split(File.pathSeparator);
                List<Path> paths = new ArrayList<>(dirs.length);
                for (String dir : dirs) {
                    paths.add(Paths.get(dir));
                }

                pluginsLayer = createPluginsLayer(paths);
            }

            Map<String, List<String>> seen = new HashMap<>();
            for (Plugin plugin : PluginRepository.
                    getPlugins(pluginsLayer)) {
                addOrderedPuginOptions(plugin, seen);
            }
            pluginsOptions.add(new PlugOption(true,
                    (task, opt, arg) -> {
                        lastSorter = arg;
                    },
                    "--resources-last-sorter "));
            pluginsOptions.add(new HiddenPluginOption(false,
                    (task, opt, arg) -> {
                        listPlugins = true;
                    },
                    "--list-plugins"));

            DefaultImageBuilderProvider provider = new DefaultImageBuilderProvider();
            List<PluginOption> options = provider.getAdditionalOptions();
            if (options != null && !options.isEmpty()) {
                for (PluginOption o : options) {
                    PlugOption option
                            = new PlugOption(o.getArgumentDescription() != null,
                                    (task, opt, arg) -> {
                                        imgBuilder.put(o, arg);
                                    },
                                    "--" + o.getName());
                    pluginsOptions.add(option);
                }
            }
        }

        private void addOrderedPuginOptions(Plugin plugin,
                Map<String, List<String>> seen) throws BadArgs {
            PluginOption option = plugin.getOption();
            if (option != null) {
                for (Entry<String, List<String>> entry : seen.entrySet()) {
                    if (entry.getKey().equals(option.getName())
                            || entry.getValue().contains(option.getName())) {
                        throw new BadArgs("err.plugin.mutiple.options",
                                option.getName());
                    }
                }
                List<String> optional = new ArrayList<>();
                seen.put(option.getName(), optional);
                PlugOption plugOption
                        = new PlugOption(option.getArgumentDescription() != null,
                                (task, opt, arg) -> {
                                    if (!Utils.isFunctional(plugin)) {
                                        throw newBadArgs("err.provider.not.functional",
                                                option.getName());
                                    }
                                    Map<PluginOption, String> m = plugins.get(plugin);
                                    if (m == null) {
                                        m = new HashMap<>();
                                        plugins.put(plugin, m);
                                    }
                                    m.put(option, arg);
                                    int index = computeIndex(plugin);
                                    // Overriden index?
                                    if (index == -1) {
                                        index = getIndex(opt);
                                    }
                                    pluginsIndexes.put(plugin, index);
                                },
                                "--" + option.getName());
                pluginsOptions.add(plugOption);
                if (plugin.getAdditionalOptions() != null && !plugin.getAdditionalOptions().isEmpty()) {
                    for (PluginOption other : plugin.getAdditionalOptions()) {
                        optional.add(other.getName());
                        PlugOption otherOption = new PlugOption(true,
                                (task, opt, arg) -> {
                                    Map<PluginOption, String> m = plugins.get(plugin);
                                    if (m == null) {
                                        m = new HashMap<>();
                                        plugins.put(plugin, m);
                                    }
                                    m.put(other, arg);
                                },
                                "--" + other.getName());
                        pluginsOptions.add(otherOption);
                    }
                }
                // On/Off enabled by default plugin
                // Command line option can override it
                if (option.hasOnOffArgument()) {
                    boolean edefault = Utils.isEnabled(plugin)
                            && Utils.isFunctional(plugin) && option.isEnabled();
                    if (edefault) {
                        Map<PluginOption, String> m = new HashMap<>();
                        m.put(option, Builder.ON_ARGUMENT);
                        plugins.put(plugin, m);
                    }
                }
            }
        }

        private int computeIndex(Plugin plugin) {
            CATEGORY category = Utils.getCategory(plugin);
            List<Plugin> order = pluginsOrder.get(category);
            if (order == null) {
                order = new ArrayList<>();
                pluginsOrder.put(category, order);
            }
            order.add(plugin);
            int index = -1;
            ORDER defaultOrder = Utils.getOrder(plugin);
            if (defaultOrder == ORDER.FIRST) {
                index = 0;
            } else if (defaultOrder == ORDER.LAST) {
                index = Integer.MAX_VALUE;
            }
            return index;
        }

        private PlugOption getOption(String name) throws BadArgs {
            for (PlugOption o : pluginsOptions) {
                if (o.matches(name)) {
                    return o;
                }
            }
            return null;
        }

        private PluginsConfiguration getPluginsConfig(Path output) throws IOException {
            List<OrderedPlugin> pluginsList = new ArrayList<>();
            for (Entry<Plugin, Map<PluginOption, String>> entry : plugins.entrySet()) {
                Plugin plugin = entry.getKey();
                if (plugin.getOption() != null) {
                    PluginOption opt = plugin.getOption();
                    if (opt.hasOnOffArgument()) {
                        Object val = entry.getValue().get(opt);
                        if (Builder.OFF_ARGUMENT.equals(val)) {
                            // Disabled plugin, no need to add it.
                            continue;
                        }
                    }
                }
                // User defined index?
                Integer i = pluginsIndexes.get(plugin);
                if (i == null) {
                    // Enabled by default plugin. Find it an index.
                    i = computeIndex(plugin);
                }
                boolean absolute = false;
                if (i == -1) {
                    CATEGORY category = Utils.getCategory(plugin);
                    if (category == null) {
                        absolute = true;
                    }
                    List<Plugin> lstPlugins
                            = pluginsOrder.get(category);
                    i = lstPlugins.indexOf(plugin);
                }
                if (i == -1) {
                    throw new IllegalArgumentException("Invalid index " + i);
                }
                Map<PluginOption, String> config = new HashMap<>();
                config.putAll(entry.getValue());
                plugin.configure(config);
                OrderedPlugin conf
                        = new Jlink.OrderedPlugin(plugin,
                                i, absolute, Utils.getCategory(plugin));
                pluginsList.add(conf);
            }

            // recreate or postprocessing don't require an output directory.
            ImageBuilder builder = null;
            if (output != null) {
                Map<PluginOption, String> builderConfig = new HashMap<>();
                builderConfig.put(DefaultImageBuilderProvider.IMAGE_PATH_OPTION, output.toString());
                builderConfig.putAll(imgBuilder);
                builder = new DefaultImageBuilder(builderConfig, output);
            }
            return new Jlink.PluginsConfiguration(pluginsList,
                    builder, lastSorter);
        }
    }

    public static class HiddenOption<T> extends Option<T> {

        public HiddenOption(boolean hasArg, Processing<T> processing,
                String... aliases) {
            super(hasArg, processing, aliases);
        }

        @Override
        public boolean isHidden() {
            return true;
        }
    }

    private static final class ResourceBundleHelper {

        private final ResourceBundle bundle;
        private final ResourceBundle pluginBundle;

        ResourceBundleHelper(String path) {
            Locale locale = Locale.getDefault();
            try {
                bundle = ResourceBundle.getBundle(path, locale);
                pluginBundle = ResourceBundle.getBundle("jdk.tools.jlink.resources.plugins", locale);
            } catch (MissingResourceException e) {
                throw new InternalError("Cannot find jlink resource bundle for locale " + locale);
            }
        }

        String getMessage(String key, Object... args) {
            String val;
            try {
                val = bundle.getString(key);
            } catch (MissingResourceException e) {
                // XXX OK, check in plugin bundle
                val = pluginBundle.getString(key);
            }
            return MessageFormat.format(val, args);
        }

    }

    public final class OptionsHelper<T> {

        private final List<Option<T>> options;
        private String[] expandedCommand;
        private String[] command;
        private String defaults;

        OptionsHelper(List<Option<T>> options) {
            this.options = options;
        }

        private boolean hasArgument(String optionName) throws BadArgs {
            Option<?> opt = getOption(optionName);
            if (opt == null) {
                opt = pluginOptions.getOption(optionName);
                if (opt == null) {
                    throw new BadArgs("err.unknown.option", optionName).
                            showUsage(true);
                }
            }
            return opt.hasArg;
        }

        private String[] handleDefaults(String[] args) throws BadArgs {
            String[] ret = args;
            List<String> defArgs = null;

            List<String> override = new ArrayList<>();
            boolean expanded = false;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--" + CONFIGURATION)) {
                    i++;
                    String path = args[i];
                    Properties p = new Properties();
                    try (FileInputStream fs = new FileInputStream(path)) {
                        p.load(fs);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                    defaults = p.getProperty(DEFAULTS_PROPERTY);
                    if (defaults == null) {
                        throw new BadArgs("err.config.defaults",
                                DEFAULTS_PROPERTY);
                    }
                    try {
                        defArgs = parseDefaults(defaults);
                    } catch (Exception ex) {
                        throw new BadArgs("err.config.defaults", defaults);
                    }
                    expanded = true;
                } else {
                    override.add(args[i]);
                }
            }
            if (defArgs != null) {
                List<String> output = new ArrayList<>();
                for (int i = 0; i < defArgs.size(); i++) {
                    String arg = defArgs.get(i);
                    if (arg.charAt(0) == '-') {
                        output.add(arg);
                        boolean hasArgument = hasArgument(arg);
                        String a = null;
                        if (hasArgument) {
                            i++;
                            a = defArgs.get(i);
                        }

                        int overIndex = -1;
                        // Retrieve the command line option
                        // compare by erasing possible index
                        for (int j = 0; j < override.size(); j++) {
                            if (removeIndex(override.get(j)).equals(removeIndex(arg))) {
                                overIndex = j;
                                break;
                            }
                        }

                        if (overIndex >= 0) {
                            if (hasArgument) {
                                a = override.get(overIndex + 1);
                                override.remove(a);
                            }
                            override.remove(arg);
                        }
                        if (hasArgument) {
                            if (a == null) {
                                throw newBadArgs("err.unknown.option", arg).
                                        showUsage(true);
                            }
                            output.add(a);
                        }
                    } else {
                        throw newBadArgs("err.unknown.option", arg).
                                showUsage(true);
                    }
                }
                //Add remaining
                output.addAll(override);
                ret = new String[output.size()];
                output.toArray(ret);
            }
            if (expanded) {
                expandedCommand = ret;
            }
            return ret;
        }

        private List<String> getDefaults(String[] args) throws BadArgs {
            List<String> defArgs = null;

            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--" + CONFIGURATION)) {
                    i++;
                    String path = args[i];
                    Properties p = new Properties();
                    try (FileInputStream fs = new FileInputStream(path)) {
                        p.load(fs);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                    defaults = p.getProperty(DEFAULTS_PROPERTY);
                    if (defaults == null) {
                        throw new BadArgs("err.config.defaults",
                                DEFAULTS_PROPERTY);
                    }
                    try {
                        defArgs = parseDefaults(defaults);
                    } catch (Exception ex) {
                        throw new BadArgs("err.config.defaults", defaults);
                    }
                    break;
                }
            }
            return defArgs;
        }

        private String getPluginsPath(String[] args) throws BadArgs {
            String pp = null;
            List<String> defaults = getDefaults(args);
            if (defaults != null) {
                int ppIndex = defaults.indexOf(PluginsOptions.PLUGINS_PATH);
                if (ppIndex > 0) {
                    if (ppIndex == defaults.size() - 1) {
                        throw new BadArgs("err.no.plugins.path").showUsage(true);
                    }
                    pp = defaults.get(ppIndex + 1);
                    if (!pp.isEmpty() && pp.charAt(0) == '-') {
                        throw new BadArgs("err.no.plugins.path").showUsage(true);
                    }
                }
            }
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals(PluginsOptions.PLUGINS_PATH)) {
                    if (i == args.length - 1) {
                        throw new BadArgs("err.no.plugins.path").showUsage(true);
                    } else {
                        i += 1;
                        pp = args[i];
                        if (!pp.isEmpty() && pp.charAt(0) == '-') {
                            throw new BadArgs("err.no.plugins.path").showUsage(true);
                        }
                        break;
                    }
                }
            }
            return pp;
        }

        public List<String> handleOptions(T task, String[] args) throws BadArgs {
            // findbugs warning, copy instead of keeping a reference.
            command = Arrays.copyOf(args, args.length);

            // The plugins path can be set in defaults or in args.
            // Must extract it prior to do any option analysis.
            // Required to interpret custom plugin options.
            // Unit tests can call Task multiple time in same JVM.
            pluginOptions = new PluginsOptions(getPluginsPath(args));

            args = handleDefaults(args);

            // First extract plugins path if any
            String pp = null;
            List<String> filteredArgs = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals(PluginsOptions.PLUGINS_PATH)) {
                    if (i == args.length - 1) {
                        throw new BadArgs("err.no.plugins.path").showUsage(true);
                    } else {
                        warning("warn.thirdparty.plugins.enabled");
                        log.println(bundleHelper.getMessage("warn.thirdparty.plugins"));
                        i += 1;
                        String arg = args[i];
                        if (!arg.isEmpty() && arg.charAt(0) == '-') {
                            throw new BadArgs("err.no.plugins.path").showUsage(true);
                        }
                        pp = args[i];
                    }
                } else {
                    filteredArgs.add(args[i]);
                }
            }
            String[] arr = new String[filteredArgs.size()];
            args = filteredArgs.toArray(arr);

            List<String> rest = new ArrayList<>();
            // process options
            for (int i = 0; i < args.length; i++) {
                if (!args[i].isEmpty() && args[i].charAt(0) == '-') {
                    String name = args[i];
                    PlugOption pluginOption = null;
                    Option<T> option = getOption(name);
                    if (option == null) {
                        pluginOption = pluginOptions.getOption(name);
                        if (pluginOption == null) {

                            throw new BadArgs("err.unknown.option", name).
                                    showUsage(true);
                        }
                    }
                    Option<?> opt = pluginOption == null ? option : pluginOption;
                    String param = null;
                    if (opt.hasArg) {
                        if (name.startsWith("--") && name.indexOf('=') > 0) {
                            param = name.substring(name.indexOf('=') + 1,
                                    name.length());
                        } else if (i + 1 < args.length) {
                            param = args[++i];
                        }
                        if (param == null || param.isEmpty()
                                || (param.length() >= 2 && param.charAt(0) == '-'
                                && param.charAt(1) == '-')) {
                            throw new BadArgs("err.missing.arg", name).
                                    showUsage(true);
                        }
                    }
                    if (pluginOption != null) {
                        pluginOption.process(pluginOptions, name, param);
                    } else {
                        option.process(task, name, param);
                    }
                    if (opt.ignoreRest()) {
                        i = args.length;
                    }
                } else {
                    rest.add(args[i]);
                }
            }
            return rest;
        }

        private Option<T> getOption(String name) {
            for (Option<T> o : options) {
                if (o.matches(name)) {
                    return o;
                }
            }
            return null;
        }

        public void showHelp(String progName, String pluginsHeader,
                boolean showsImageBuilder) {
            log.println(bundleHelper.getMessage("main.usage", progName));
            // First configuration
            log.println(bundleHelper.getMessage("main.opt." + CONFIGURATION));
            for (Option<?> o : options) {
                String name = o.aliases[0].substring(1); // there must always be at least one name
                name = name.charAt(0) == '-' ? name.substring(1) : name;
                if (o.isHidden() || name.equals("h")) {
                    continue;
                }
                log.println(bundleHelper.getMessage("main.opt." + name));
            }
            log.println(bundleHelper.getMessage("main.plugins-modulepath")
                    + ". " + bundleHelper.getMessage("warn.prefix") + " "
                    + bundleHelper.getMessage("warn.thirdparty.plugins"));
            log.println(bundleHelper.getMessage("main.command.files"));

            log.println("\n" + pluginsHeader + "\n");
            DefaultImageBuilderProvider imgBuilder = new DefaultImageBuilderProvider();
            logBuilderOptions(imgBuilder.getAdditionalOptions());
            List<Plugin> pluginList = PluginRepository.
                    getPlugins(pluginOptions.pluginsLayer);
            for (Plugin plugin : Utils.getPreProcessors(pluginList)) {
                printHelp(plugin, showsImageBuilder);
            }
            if (showsImageBuilder) {
                List<Plugin> post = Utils.getPostProcessors(PluginRepository.
                        getPlugins(pluginOptions.pluginsLayer));
                if (post.size() > 0) {
                    log.println(bundleHelper.getMessage("main.plugin.post.processors"));
                    for (Plugin plugin : post) {
                        printHelp(plugin, showsImageBuilder);
                    }
                }
            }
        }

        private void printHelp(Plugin plugin, boolean showsImageBuilder) {
            if (showsPlugin(plugin, showsImageBuilder)) {
                PluginOption opt = plugin.getOption();
                if (opt != null) {
                    StringBuilder line = new StringBuilder();
                    line.append(" --").append(opt.getName());
                    if (opt.getArgumentDescription() != null) {
                        line.append(" ").append(opt.getArgumentDescription());
                    }
                    line.append("\n ").append(plugin.getDescription());
                    line.append("\n ").append(bundleHelper.getMessage("main.plugin.state")).
                            append(": ").append(plugin.getStateDescription());
                    if (plugin.getAdditionalOptions() != null && !plugin.getAdditionalOptions().isEmpty()) {
                        line.append("\n ").append(bundleHelper.
                                getMessage("main.plugin.additional.options")).
                                append(": ");
                        for (PluginOption option : plugin.getAdditionalOptions()) {
                            line.append(" --").append(option.getName()).append(" ").
                                    append(option.getDescription());
                        }
                    }
                    log.println(line.toString() + "\n");
                }
            }
        }

        public void showPlugins(PrintWriter log, boolean showsImageBuilder) {
            for (Plugin plugin : Utils.
                    getPreProcessors(PluginRepository.
                            getPlugins(getPluginsLayer()))) {
                showPlugin(plugin, log, showsImageBuilder);
            }

            if (showsImageBuilder) {
                for (Plugin plugin : Utils.
                        getPostProcessors(PluginRepository.
                                getPlugins(getPluginsLayer()))) {
                    showPlugin(plugin, log, showsImageBuilder);
                }
            }
        }

        private void showPlugin(Plugin plugin, PrintWriter log, boolean showsImageBuilder) {
            if (showsPlugin(plugin, showsImageBuilder)) {
                log.println("\n" + bundleHelper.getMessage("main.plugin.name")
                        + ": " + plugin.getName());
                CATEGORY category = Utils.getCategory(plugin);
                Integer[] range = ImagePluginConfiguration.getRange(category);
                String cat = range == null ? category.getName() : category.getName()
                        + ". " + bundleHelper.getMessage("main.plugin.range.from")
                        + " " + range[0] + " " + bundleHelper.
                        getMessage("main.plugin.range.to") + " "
                        + range[1] + ".";
                log.println(bundleHelper.getMessage("main.plugin.category")
                        + ": " + cat);
                log.println(bundleHelper.getMessage("main.plugin.description")
                        + ": " + plugin.getDescription());
                PluginOption opt = plugin.getOption();
                String desc = opt == null ? null : opt.getArgumentDescription();
                log.println(bundleHelper.getMessage("main.plugin.argument")
                        + ": " + (desc == null
                                ? bundleHelper.getMessage("main.plugin.no.value")
                                : desc));
                if (opt != null) {
                    log.println(bundleHelper.getMessage("main.plugin.option")
                            + ": --" + opt.getName());
                }
                if (plugin.getAdditionalOptions() != null && !plugin.getAdditionalOptions().isEmpty()) {
                    StringBuilder builder = new StringBuilder();
                    for (PluginOption o : plugin.getAdditionalOptions()) {
                        builder.append("--").append(o.getName()).append(" ").
                                append(o.getArgumentDescription() != null
                                        ? bundleHelper.getMessage("main.plugin.no.value")
                                        : o.getArgumentDescription());
                    }
                    log.println(bundleHelper.getMessage("main.plugin.additional.options")
                            + ": " + builder.toString());
                }
                log.println(bundleHelper.getMessage("main.plugin.state")
                        + ": " + plugin.getStateDescription());
            }
        }

        private void logBuilderOptions(List<PluginOption> options) {
            if (options != null && !options.isEmpty()) {
                for (PluginOption opt : options) {
                    log.println(opt.getDescription());
                    log.println(" --" + opt.getName() + " " + (opt.getArgumentDescription() == null
                            ? bundleHelper.getMessage("main.plugin.no.value")
                            : opt.getArgumentDescription()) + "\n");
                }
            }
        }

        public boolean listPlugins() {
            return pluginOptions.listPlugins;
        }

        String[] getExpandedCommand() {
            return expandedCommand;
        }

        String[] getInputCommand() {
            return command;
        }

        String getDefaults() {
            return defaults;
        }

        public Layer getPluginsLayer() {
            return pluginOptions.pluginsLayer;
        }
    }

    private PluginsOptions pluginOptions;
    private PrintWriter log;
    private final ResourceBundleHelper bundleHelper;

    public TaskHelper(String path) {
        if (!JLINK_BUNDLE.equals(path) && !JIMAGE_BUNDLE.equals(path)) {
            throw new IllegalArgumentException("Invalid Bundle");
        }
        this.bundleHelper = new ResourceBundleHelper(path);
    }

    public <T> OptionsHelper<T> newOptionsHelper(Class<T> clazz,
            Option<?>[] options) {
        List<Option<T>> optionsList = new ArrayList<>();
        for (Option<?> o : options) {
            @SuppressWarnings("unchecked")
            Option<T> opt = (Option<T>) o;
            optionsList.add(opt);
        }
        return new OptionsHelper<>(optionsList);
    }

    public BadArgs newBadArgs(String key, Object... args) {
        return new BadArgs(key, args);
    }

    public String getMessage(String key, Object... args) {
        return bundleHelper.getMessage(key, args);
    }

    public void setLog(PrintWriter log) {
        this.log = log;
    }

    public void reportError(String key, Object... args) {
        log.println(bundleHelper.getMessage("error.prefix") + " "
                + bundleHelper.getMessage(key, args));
    }

    public void reportUnknownError(String message) {
        log.println(bundleHelper.getMessage("error.prefix") + " " + message);
    }

    public void warning(String key, Object... args) {
        log.println(bundleHelper.getMessage("warn.prefix") + " "
                + bundleHelper.getMessage(key, args));
    }

    public PluginsConfiguration getPluginsConfig(Path output) throws IOException {
        return pluginOptions.getPluginsConfig(output);
    }

    public void showVersion(boolean full) {
        log.println(version(full ? "full" : "release"));
    }

    public String version(String key) {
        return System.getProperty("java.version");
    }

    // public for testing purpose
    public static List<String> parseDefaults(String defaults) throws Exception {
        List<String> arguments = new ArrayList<>();
        while (!defaults.isEmpty()) {
            int start = defaults.indexOf("--");
            if (start < 0) {
                throw new Exception("Invalid defaults " + defaults);
            }
            defaults = defaults.substring(start);
            start = 0;
            int end = defaults.indexOf(" ");
            String remaining;

            if (end < 0) {
                arguments.add(defaults);
                remaining = "";
            } else {
                String option = defaults.substring(start, end);
                arguments.add(option);
                defaults = defaults.substring(end);
                int nextOption = defaults.indexOf("--");
                int argEnd = nextOption < 0 ? defaults.length() : nextOption;
                String arg = defaults.substring(0, argEnd);
                arg = arg.replaceAll(" ", "");
                if (!arg.isEmpty()) {
                    arguments.add(arg);
                }
                remaining = defaults.substring(argEnd);
            }

            defaults = remaining;
        }
        return arguments;
    }

    static Layer createPluginsLayer(List<Path> paths) {
        Path[] arr = new Path[paths.size()];
        paths.toArray(arr);
        ModuleFinder finder = ModuleFinder.of(arr);

        // jmods are located at link-time
        if (finder instanceof ConfigurableModuleFinder) {
            ((ConfigurableModuleFinder) finder).configurePhase(Phase.LINK_TIME);
        }

        Configuration bootConfiguration = Layer.boot().configuration();

        Configuration cf
                = Configuration.resolve(ModuleFinder.empty(), bootConfiguration, finder);

        cf = cf.bind();

        ClassLoader scl = ClassLoader.getSystemClassLoader();
        return Layer.createWithOneLoader(cf, Layer.boot(), scl);
    }

    // Display all plugins or resource only.
    private static boolean showsPlugin(Plugin plugin, boolean showsImageBuilder) {
        if (Utils.isEnabled(plugin) && plugin.getOption() != null && showsImageBuilder) {
            return true;
        }
        return false;
    }
}
