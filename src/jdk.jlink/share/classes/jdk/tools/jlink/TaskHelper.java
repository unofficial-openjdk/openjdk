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

import jdk.tools.jlink.internal.ImagePluginProviderRepository;
import jdk.tools.jlink.internal.ImagePluginConfiguration;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.NoSuchFileException;
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
import jdk.tools.jlink.plugins.PluginProvider;

/**
 *
 * JLink and JImage tools shared helper.
 */
public final class TaskHelper {

    private static final String DEFAULTS_PROPERTY = "jdk.jlink.defaults";
    private static final String CONFIGURATION = "configuration";

    public class BadArgs extends Exception {

        static final long serialVersionUID = 8765093759964640721L;  // ## re-generate

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

    public static abstract class Option<T> {

        final boolean hasArg;
        final String[] aliases;

        public Option(boolean hasArg, String... aliases) {
            this.hasArg = hasArg;
            this.aliases = aliases;
        }

        public boolean isHidden() {
            return false;
        }

        public boolean matches(String opt) {
            for (String a : aliases) {
                if (a.equals(opt)) {
                    return true;
                } else if (opt.startsWith("--") &&
                        (hasArg && opt.startsWith(a + "="))) {
                    return true;
                }
            }
            return false;
        }

        public boolean ignoreRest() {
            return false;
        }

        protected abstract void process(T task, String opt, String arg)
                throws BadArgs;
    }

    private abstract class PluginOption extends Option<PluginsOptions> {

        public PluginOption(boolean hasArg, String... aliases) {
            super(hasArg, aliases);
        }
    }

    private abstract class HiddenPluginOption extends PluginOption {

        public HiddenPluginOption(boolean hasArg, String... aliases) {
            super(hasArg, aliases);
        }

        @Override
        public boolean isHidden() {
            return true;
        }
    }

    private class PluginsOptions {

        private String pluginsProperties;
        private boolean listPlugins;
        private final Map<PluginProvider, Map<String, String>> plugins = new HashMap<>();
        private final List<PluginOption> pluginsOptions = new ArrayList<>();

        private PluginsOptions() {

            for (PluginProvider provider : ImagePluginProviderRepository.getImageWriterProviders(null)) {
                if (provider.getToolOption() != null) {
                    PluginOption option
                            = new PluginOption(provider.getToolArgument() != null,
                                    "--" + provider.getToolOption()) {
                                @Override
                                protected void process(PluginsOptions task,
                                        String opt,
                                        String arg) throws BadArgs {
                                    Map<String, String> m = plugins.get(provider);
                                    if (m == null) {
                                        m = new HashMap<>();
                                        plugins.put(provider, m);
                                    }
                                    m.put(PluginProvider.TOOL_ARGUMENT_PROPERTY, arg);
                                }
                            };
                    pluginsOptions.add(option);
                    if (provider.getAdditionalOptions() != null) {
                        for (String other : provider.getAdditionalOptions().keySet()) {
                            PluginOption otherOption = new PluginOption(true,
                                    "--" + other) {
                                        @Override
                                        protected void process(PluginsOptions task,
                                                String opt,
                                                String arg) throws BadArgs {
                                            Map<String, String> m = plugins.get(provider);
                                            if (m == null) {
                                                m = new HashMap<>();
                                                plugins.put(provider, m);
                                            }
                                            m.put(other, arg);
                                        }
                                    };
                            pluginsOptions.add(otherOption);
                        }
                    }
                }
            }
            pluginsOptions.add(new HiddenPluginOption(true,
                    "--plugins-configuration") {
                @Override
                protected void process(PluginsOptions task, String opt,
                        String arg) throws BadArgs {
                    pluginsProperties = arg;
                }
            });
            pluginsOptions.add(new HiddenPluginOption(false, "--list-plugins") {
                @Override
                protected void process(PluginsOptions task, String opt, String arg) {
                    listPlugins = true;
                }
            });
        }

        private PluginOption getOption(String name) throws BadArgs {
            for (PluginOption o : pluginsOptions) {
                if (o.matches(name)) {
                    return o;
                }
            }
            return null;
        }

        private Properties getPluginsProperties() throws IOException {
            Properties props = new Properties();
            if (pluginsProperties != null) {
                try (FileInputStream stream =
                        new FileInputStream(pluginsProperties);) {
                    props.load(stream);
                } catch (FileNotFoundException ex) {
                    throw new IOException(bundleHelper.
                            getMessage("err.path.not.valid") +
                            " " + pluginsProperties);
                }
            }
            for (Entry<PluginProvider, Map<String, String>> entry : plugins.entrySet()) {
                PluginProvider provider = entry.getKey();
                ImagePluginConfiguration.addPluginProperty(props, provider);
                if (entry.getValue() != null) {
                    for(Entry<String, String> opts : entry.getValue().entrySet()) {
                        if (opts.getValue() != null) {
                            props.setProperty(provider.getName() + "." +
                                opts.getKey(), opts.getValue());
                        }
                    }
                }
            }
            return props;
        }
    }

    public static abstract class HiddenOption<T> extends Option<T> {

        public HiddenOption(boolean hasArg, String... aliases) {
            super(hasArg, aliases);
        }

        @Override
        public boolean isHidden() {
            return true;
        }
    }

    private class ResourceBundleHelper {

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

    public class OptionsHelper<T> {

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
                        int overIndex = override.indexOf(arg);
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
            try {
                args = CommandLine.parse(args);
            } catch (FileNotFoundException | NoSuchFileException e) {
                throw new BadArgs("err.file.not.found", e.getMessage());
            } catch (IOException ex) {
                throw new BadArgs("err.file.error", ex.getMessage());
            }
            // Unit tests can call Task multiple time in same JVM.
            pluginOptions = new PluginsOptions();
            command = args;
            // Handle defaults.
            args = handleDefaults(args);

            List<String> rest = new ArrayList<>();
            // process options
            for (int i = 0; i < args.length; i++) {
                if (args[i].charAt(0) == '-') {
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
                        if (param == null || param.isEmpty() ||
                                param.charAt(0) == '-') {
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

        public void showHelp(String progName, String pluginsHeader) {
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
            log.println(bundleHelper.getMessage("main.command.files"));

            log.println("\n" + pluginsHeader);
            for (PluginProvider provider : ImagePluginProviderRepository.getImageWriterProviders(null)) {
                if (provider.getToolOption() != null) {
                    StringBuilder line = new StringBuilder();
                    line.append(" --").append(provider.getToolOption());
                    if (provider.getToolArgument() != null) {
                        line.append(" ").append(provider.getToolArgument());
                    }
                    line.append("\n ").append(provider.getDescription());
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

        public void showPlugins(PrintWriter log) {
            for (PluginProvider fact : ImagePluginProviderRepository.getImageWriterProviders(null)) {
                log.println("\n" + bundleHelper.getMessage("main.plugin.name")
                        + ": " + fact.getName());
                Integer[] range = ImagePluginConfiguration.getRange(fact);
                String cat = range == null ? fact.getCategory() : fact.getCategory() +
                        ". " + bundleHelper.getMessage("main.plugin.range.from") +
                        " " + range[0] + " " + bundleHelper.
                                getMessage("main.plugin.range.to") + " " +
                        range[1] + ".";
                log.println(bundleHelper.getMessage("main.plugin.category")
                        + ": " + cat);
                log.println(bundleHelper.getMessage("main.plugin.description")
                        + ": " + fact.getDescription());
                log.println(bundleHelper.getMessage("main.plugin.argument")
                            + ": " + (fact.getToolArgument() == null
                                    ? bundleHelper.getMessage("main.plugin.no.value")
                                    : fact.getToolArgument()));
                String additionalOptions = bundleHelper.getMessage("main.plugin.no.value");
                if(fact.getAdditionalOptions() != null) {
                    StringBuilder builder = new StringBuilder();
                    for(Entry<String, String> entry : fact.getAdditionalOptions().entrySet()) {
                        builder.append(entry.getKey()).append(" ").
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

        String getPluginsConfig() throws IOException {
            String ret = null;
            if (pluginOptions.pluginsProperties != null) {
                Properties props = new Properties();
                try (FileInputStream stream
                        = new FileInputStream(pluginOptions.pluginsProperties)) {
                    props.load(stream);
                } catch (FileNotFoundException ex) {
                    throw new IOException(bundleHelper.
                            getMessage("err.path.not.valid")
                            + " " + pluginOptions.pluginsProperties);
                }
                StringBuilder sb = new StringBuilder();
                for (String str : props.stringPropertyNames()) {
                    sb.append(str).append(" = ").append(props.getProperty(str)).
                            append("\n");
                }
                ret = sb.toString();
            }
            return ret;
        }
    }

    private PluginsOptions pluginOptions;
    private PrintWriter log;
    private final ResourceBundleHelper bundleHelper;

    public TaskHelper(String path) {
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
        log.println(bundleHelper.getMessage("error.prefix") + " " +
                bundleHelper.getMessage(key, args));
    }

    public void warning(String key, Object... args) {
        log.println(bundleHelper.getMessage("warn.prefix") + " " +
                bundleHelper.getMessage(key, args));
    }

    public Properties getPluginsProperties() throws IOException {
        return pluginOptions.getPluginsProperties();
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
}
