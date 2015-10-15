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
import jdk.tools.jlink.internal.ImagePluginProviderRepository;
import jdk.tools.jlink.internal.ImagePluginConfiguration;
import jdk.tools.jlink.plugins.OnOffPluginProvider;
import jdk.tools.jlink.plugins.CmdPluginProvider;
import jdk.tools.jlink.plugins.CmdResourcePluginProvider;
import jdk.tools.jlink.plugins.DefaultImageBuilderProvider;
import jdk.tools.jlink.plugins.ImageBuilderProvider;
import jdk.tools.jlink.plugins.Jlink;
import jdk.tools.jlink.plugins.Jlink.PluginsConfiguration;
import jdk.tools.jlink.plugins.Jlink.StackedPluginConfiguration;
import jdk.tools.jlink.plugins.OnOffImageFilePluginProvider;
import jdk.tools.jlink.plugins.OnOffPostProcessingPluginProvider;
import jdk.tools.jlink.plugins.OnOffResourcePluginProvider;
import jdk.tools.jlink.plugins.PluginProvider;
import jdk.tools.jlink.plugins.PluginProvider.ORDER;
import jdk.tools.jlink.plugins.PostProcessingPluginProvider;

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

    private static class PluginOption extends Option<PluginsOptions> {

        public PluginOption(boolean hasArg,
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
            if (opt.equals(CmdPluginProvider.FIRST)) {
                index = 0;
            } else {
                if (opt.equals(CmdPluginProvider.LAST)) {
                    index = Integer.MAX_VALUE;
                } else {
                    try {
                        index = Integer.valueOf(opt);
                    } catch (NumberFormatException ex) {
                        throw newBadArgs("err.invalid.index", orig);
                    }
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

    private static class HiddenPluginOption extends PluginOption {

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
        private static final String NO_CATEGORY = "jlink.no.category";

        private Layer pluginsLayer = Layer.boot();
        private String imgBuilder;
        private String lastSorter;
        private boolean listPlugins;
        private final Map<PluginProvider, Map<String, String>> plugins = new HashMap<>();
        private final Map<ImageBuilderProvider, Map<String, String>> builders = new HashMap<>();
        private final List<PluginOption> pluginsOptions = new ArrayList<>();

        // The order in which options are declared is the stack order.
        // Order is within provider category
        private final Map<String, List<PluginProvider>> providersOrder = new HashMap<>();
        private final Map<PluginProvider, Integer> providersIndexes = new HashMap<>();
        private PluginsOptions(String pp) throws BadArgs {

            if (pp != null) {
                String[] dirs = pp.split(File.pathSeparator);
                Path[] paths = new Path[dirs.length];
                int i = 0;
                for (String dir : dirs) {
                    paths[i++] = Paths.get(dir);
                }

                pluginsLayer = createPluginsLayer(paths);
            }

            Map<String, List<String>> seen = new HashMap<>();
            for (PluginProvider prov : ImagePluginProviderRepository.getPluginProviders(pluginsLayer)) {
                if (prov instanceof CmdPluginProvider) {
                    CmdPluginProvider<?> provider = (CmdPluginProvider<?>) prov;
                    if (provider.getToolOption() != null) {
                        for (Entry<String, List<String>> entry : seen.entrySet()) {
                            if (entry.getKey().equals(provider.getToolOption())
                                    || entry.getValue().contains(provider.getToolOption())) {
                                throw new BadArgs("err.plugin.mutiple.options",
                                        provider.getToolOption());
                            }
                        }
                        List<String> optional = new ArrayList<>();
                        seen.put(provider.getToolOption(), optional);
                        PluginOption option
                                = new PluginOption(provider.getToolArgument() != null,
                                        (task, opt, arg) -> {
                                            if (!prov.isFunctional()) {
                                                throw newBadArgs("err.provider.not.functional",
                                                        prov.getName());
                                            }
                                            Map<String, String> m = plugins.get(prov);
                                            if (m == null) {
                                                m = new HashMap<>();
                                                plugins.put(prov, m);
                                            }
                                            m.put(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY, arg);
                                            int index = computeIndex(prov);
                                            // Overriden index?
                                            if (index == -1) {
                                                index = getIndex(opt);
                                            }
                                            providersIndexes.put(prov, index);
                                        },
                                        "--" + provider.getToolOption());
                        pluginsOptions.add(option);
                        if (provider.getAdditionalOptions() != null) {
                            for (String other : provider.getAdditionalOptions().keySet()) {
                                optional.add(other);
                                PluginOption otherOption = new PluginOption(true,
                                        (task, opt, arg) -> {
                                            Map<String, String> m = plugins.get(prov);
                                            if (m == null) {
                                                m = new HashMap<>();
                                                plugins.put(prov, m);
                                            }
                                            m.put(other, arg);
                                        },
                                        "--" + other);
                                pluginsOptions.add(otherOption);
                            }
                        }
                        // On/Off enabled by default plugin
                        // Command line option can override it
                        boolean edefault = false;
                        if (provider instanceof OnOffPluginProvider) {
                            edefault = prov.isFunctional() && ((OnOffPluginProvider) provider).isEnabledByDefault();
                        }
                        if (edefault) {
                            Map<String, String> m = new HashMap<>();
                            m.put(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY,
                                    OnOffPluginProvider.ON_ARGUMENT);
                            plugins.put(prov, m);
                        }
                    }
                }
            }
            pluginsOptions.add(new PluginOption(true,
                    (task, opt, arg) -> {
                        imgBuilder = arg;
                    },
                    "--image-builder"));
            pluginsOptions.add(new PluginOption(true,
                    (task, opt, arg) -> {
                        lastSorter = arg;
                    },
                    "--resources-last-sorter "));
            pluginsOptions.add(new HiddenPluginOption(false,
                    (task, opt, arg) -> {
                        listPlugins = true;
                    },
                    "--list-plugins"));

            //Image Builder options
            for (ImageBuilderProvider provider
                    : ImagePluginProviderRepository.getImageBuilderProviders(pluginsLayer)) {
                Map<String, String> options = provider.getOptions();
                if (options != null && !options.isEmpty()) {
                    for (Entry<String, String> o : options.entrySet()) {
                        PluginOption option
                                = new PluginOption(provider.hasArgument(o.getKey()),
                                        (task, opt, arg) -> {
                                            Map<String, String> m = builders.get(provider);
                                            if (m == null) {
                                                m = new HashMap<>();
                                                builders.put(provider, m);
                                            }
                                            m.put(o.getKey(), arg);
                                        },
                                        "--" + o.getKey());
                        pluginsOptions.add(option);
                    }
                }
            }
        }

        private int computeIndex(PluginProvider prov) {
            String category = prov.getCategory() == null ? NO_CATEGORY : prov.getCategory();
            List<PluginProvider> order = providersOrder.get(category);
            if (order == null) {
                order = new ArrayList<>();
                providersOrder.put(category, order);
            }
            order.add(prov);
            int index = -1;
            ORDER defaultOrder = prov.getOrder();
            if (defaultOrder == ORDER.FIRST) {
                index = 0;
            } else {
                if (defaultOrder == ORDER.LAST) {
                    index = Integer.MAX_VALUE;
                }
            }
            return index;
        }

        private PluginOption getOption(String name) throws BadArgs {
            for (PluginOption o : pluginsOptions) {
                if (o.matches(name)) {
                    return o;
                }
            }
            return null;
        }

        private PluginsConfiguration getPluginsConfig() throws IOException {
            List<StackedPluginConfiguration> preProcessing = new ArrayList<>();
            List<StackedPluginConfiguration> postProcessing = new ArrayList<>();
            for (Entry<PluginProvider, Map<String, String>> entry : plugins.entrySet()) {
                PluginProvider provider = entry.getKey();
                // User defined index?
                Integer i = providersIndexes.get(provider);
                if (i == null) {
                    // Enabled by default provider. Find it an index.
                    i = computeIndex(provider);
                }
                boolean absolute = false;
                if (i == -1) {
                    String category = provider.getCategory();
                    if (provider.getCategory() == null) {
                        absolute = true;
                        category = NO_CATEGORY;
                    }
                    List<PluginProvider> lstProviders
                            = providersOrder.get(category);
                    i = lstProviders.indexOf(provider);
                }
                if (i == -1) {
                    throw new IllegalArgumentException("Invalid index " + i);
                }
                Map<String, Object> config = new HashMap<>();
                config.putAll(entry.getValue());
                StackedPluginConfiguration conf
                        = new Jlink.StackedPluginConfiguration(provider.getName(),
                                i, absolute, config);
                if (provider instanceof PostProcessingPluginProvider) {
                    postProcessing.add(conf);
                } else {
                    preProcessing.add(conf);
                }
            }

            imgBuilder = imgBuilder == null ? DefaultImageBuilderProvider.NAME : imgBuilder;
            Map<String, Object> builderConfig = new HashMap<>();
            if (!builders.isEmpty()) {
                Map<String, String> conf = builders.entrySet().iterator().next().getValue();
                if (conf != null) {
                    builderConfig.putAll(conf);
                }
            }
            return new Jlink.PluginsConfiguration(preProcessing, postProcessing,
                    new Jlink.PluginConfiguration(imgBuilder, builderConfig),
                    lastSorter);
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

        public List<String> handleOptions(T task, String[] args) throws BadArgs {
            command = args;
            // Handle defaults.
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

            // Unit tests can call Task multiple time in same JVM.
            pluginOptions = new PluginsOptions(pp);

            List<String> rest = new ArrayList<>();
            // process options
            for (int i = 0; i < args.length; i++) {
                if (!args[i].isEmpty() && args[i].charAt(0) == '-') {
                    String name = args[i];
                    PluginOption pluginOption = null;
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

            log.println("\n" + pluginsHeader);
            for (PluginProvider prov : ImagePluginProviderRepository.
                    getPluginProviders(pluginOptions.pluginsLayer)) {
                if (showsPlugin(prov, showsImageBuilder)) {
                    CmdPluginProvider<?> provider = (CmdPluginProvider<?>) prov;

                    if (provider.getToolOption() != null) {
                        StringBuilder line = new StringBuilder();
                        line.append(" --").append(provider.getToolOption());
                        if (provider.getToolArgument() != null) {
                            line.append(" ").append(provider.getToolArgument());
                        }
                        line.append("\n ").append(prov.getDescription());
                        line.append("\n").append(bundleHelper.getMessage("main.plugin.state")).
                                append(": ").append(prov.getFunctionalStateDescription(prov.isFunctional()));
                        if (provider.getAdditionalOptions() != null) {
                            line.append("\n").append(bundleHelper.
                                    getMessage("main.plugin.additional.options")).
                                    append(": ");
                            for (Entry<String, String> entry : provider.getAdditionalOptions().entrySet()) {
                                line.append(" --").append(entry.getKey()).append(" ").
                                        append(entry.getValue());
                            }
                        }
                        log.println(line.toString() + "\n");
                    }
                }
            }
            if (showsImageBuilder) {
                log.println(bundleHelper.getMessage("main.image.builders"));
                for (ImageBuilderProvider prov
                        : ImagePluginProviderRepository.getImageBuilderProviders(getPluginsLayer())) {
                    if (prov.isExposed()) {
                        log.println("\n" + bundleHelper.getMessage("main.image.builder.name")
                                + ": " + prov.getName());
                        log.println(bundleHelper.getMessage("main.plugin.state")
                                + ": " + prov.getFunctionalStateDescription(prov.isFunctional()));
                        logBuilderOptions(prov.getOptions());
                    }
                }
            }
        }

        public void showPlugins(PrintWriter log, boolean showsImageBuilder) {
            for (PluginProvider prov : ImagePluginProviderRepository.getPluginProviders(getPluginsLayer())) {
                if (showsPlugin(prov, showsImageBuilder)) {
                    CmdPluginProvider<?> fact = (CmdPluginProvider<?>) prov;
                    log.println("\n" + bundleHelper.getMessage("main.plugin.name")
                            + ": " + prov.getName());
                    Integer[] range = ImagePluginConfiguration.getRange(prov);
                    String cat = range == null ? prov.getCategory() : prov.getCategory()
                            + ". " + bundleHelper.getMessage("main.plugin.range.from")
                            + " " + range[0] + " " + bundleHelper.
                            getMessage("main.plugin.range.to") + " "
                            + range[1] + ".";
                    log.println(bundleHelper.getMessage("main.plugin.category")
                            + ": " + cat);
                    log.println(bundleHelper.getMessage("main.plugin.description")
                            + ": " + prov.getDescription());
                    log.println(bundleHelper.getMessage("main.plugin.argument")
                            + ": " + (fact.getToolArgument() == null
                                    ? bundleHelper.getMessage("main.plugin.no.value")
                                    : fact.getToolArgument()));
                    log.println(bundleHelper.getMessage("main.plugin.state")
                            + ": " + prov.getFunctionalStateDescription(prov.isFunctional()));
                    String additionalOptions = bundleHelper.getMessage("main.plugin.no.value");
                    if (fact.getAdditionalOptions() != null) {
                        StringBuilder builder = new StringBuilder();
                        for (Entry<String, String> entry : fact.getAdditionalOptions().entrySet()) {
                            builder.append("--").append(entry.getKey()).append(" ").
                                    append(entry.getValue());
                        }
                        additionalOptions = builder.toString();
                    }
                    log.println(bundleHelper.getMessage("main.plugin.additional.options")
                            + ": " + additionalOptions);
                    String option = fact.getToolOption();
                    if (option != null) {
                        log.println(bundleHelper.getMessage("main.plugin.option")
                                + ": --" + option);
                    }
                }
            }
            if (showsImageBuilder) {
                for (ImageBuilderProvider prov
                        : ImagePluginProviderRepository.getImageBuilderProviders(getPluginsLayer())) {
                    if (prov.isExposed()) {
                        log.println("\n" + bundleHelper.getMessage("main.image.builder.name")
                                + ": " + prov.getName());
                        log.println(bundleHelper.getMessage("main.image.builder.description")
                                + ": " + prov.getDescription());
                        log.println(bundleHelper.getMessage("main.plugin.state")
                                + ": " + prov.getFunctionalStateDescription(prov.isFunctional()));
                        logBuilderOptions(prov.getOptions());
                    }
                }
            }
        }

        private void logBuilderOptions(Map<String, String> options) {
            if (options != null && !options.isEmpty()) {
                for (Entry<String, String> opt : options.entrySet()) {
                    log.println(" --" + opt.getKey() + " " + opt.getValue() + "\n");
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

        Layer getPluginsLayer() {
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

    public PluginsConfiguration getPluginsConfig() throws IOException {
        return pluginOptions.getPluginsConfig();
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

    static Layer createPluginsLayer(Path[] paths) {
        ModuleFinder finder = ModuleFinder.of(paths);

        // jmods are located at link-time
        if (finder instanceof ConfigurableModuleFinder)
            ((ConfigurableModuleFinder)finder).configurePhase(Phase.LINK_TIME);

        Configuration cf
            = Configuration.resolve(ModuleFinder.empty(), Layer.boot(), finder);
        cf = cf.bind();
        // The creation of this classloader is done outside privileged block in purpose
        // If a security manager is set, then permission must be granted to jlink
        // codebase to create a classloader. This is the expected behavior.
        ClassLoader cl = new ModuleClassLoader(cf);
        return Layer.create(cf, mn -> cl);
    }

    // Display all plugins or resource only.
    private static boolean showsPlugin(PluginProvider prov, boolean showsImageBuilder) {
        return prov.isExposed() && ((prov instanceof CmdPluginProvider && showsImageBuilder)
                || (prov instanceof CmdResourcePluginProvider));
    }
}
