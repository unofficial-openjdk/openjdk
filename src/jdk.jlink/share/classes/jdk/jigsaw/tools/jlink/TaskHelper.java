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
package jdk.jigsaw.tools.jlink;

import jdk.jigsaw.tools.jlink.internal.ImagePluginProviderRepository;
import jdk.jigsaw.tools.jlink.internal.ImagePluginConfiguration;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
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
import jdk.jigsaw.tools.jlink.plugins.PluginProvider;

/**
 *
 * JLink and JImage tools shared helper.
 */
public final class TaskHelper {

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
                } else if (opt.startsWith("--") && hasArg && opt.startsWith(a + "=")) {
                    return true;
                }
            }
            return false;
        }

        public boolean ignoreRest() {
            return false;
        }

        protected abstract void process(T task, String opt, String arg) throws BadArgs;
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
        private final Map<PluginProvider, String> plugins = new HashMap<>();
        private final List<PluginOption> pluginsOptions = new ArrayList<>();

        private PluginsOptions() {

            for (PluginProvider provider : ImagePluginProviderRepository.getImageWriterProviders(null)) {
                if (provider.getToolOption() != null) {
                    boolean requireOptions = provider.getConfiguration() != null;
                    PluginOption option = new PluginOption(requireOptions, "--" + provider.getToolOption()) {
                        @Override
                        protected void process(PluginsOptions task, String opt, String arg) throws BadArgs {
                            plugins.put(provider, arg);
                        }
                    };
                    pluginsOptions.add(option);
                }
            }
            pluginsOptions.add(new HiddenPluginOption(true, "--plugins-configuration") {
                @Override
                protected void process(PluginsOptions task, String opt, String arg) throws BadArgs {
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
                try (FileInputStream stream = new FileInputStream(pluginsProperties);) {
                    props.load(stream);
                } catch (FileNotFoundException ex) {
                    throw new IOException(bundleHelper.getMessage("err.path.not.valid") + " " + pluginsProperties);
                }
            }
            for (Entry<PluginProvider, String> entry : plugins.entrySet()) {
                PluginProvider provider = entry.getKey();
                int index = ImagePluginConfiguration.getNextIndex(props, provider.getCategory());
                props.setProperty(ImagePluginConfiguration.RADICAL_PROPERTY + provider.getCategory() + "." + index, provider.getName());
                if (entry.getValue() != null) {
                    props.setProperty(provider.getName() + "." + PluginProvider.CONFIGURATION_PROPERTY,
                            entry.getValue());
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
                pluginBundle = ResourceBundle.getBundle("jdk.jigsaw.tools.jlink.resources.plugins", locale);
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

        OptionsHelper(List<Option<T>> options) {
            this.options = options;
        }

        public List<String> handleOptions(T task, String[] args) throws BadArgs {
            // Unit tests can call Task multiple time in same JVM.
            pluginOptions = new PluginsOptions();

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
                            throw new BadArgs("err.unknown.option", name).showUsage(true);
                        }
                    }
                    Option<?> opt = pluginOption == null ? option : pluginOption;
                    String param = null;
                    if (opt.hasArg) {
                        if (name.startsWith("--") && name.indexOf('=') > 0) {
                            param = name.substring(name.indexOf('=') + 1, name.length());
                        } else if (i + 1 < args.length) {
                            param = args[++i];
                        }
                        if (param == null || param.isEmpty() || param.charAt(0) == '-') {
                            throw new BadArgs("err.missing.arg", name).showUsage(true);
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

        private Option<T> getOption(String name) throws BadArgs {
            for (Option<T> o : options) {
                if (o.matches(name)) {
                    return o;
                }
            }
            return null;
        }

        public void showHelp(String progName, String pluginsHeader) {
            log.println(bundleHelper.getMessage("main.usage", progName));
            for (Option<?> o : options) {
                String name = o.aliases[0].substring(1); // there must always be at least one name
                name = name.charAt(0) == '-' ? name.substring(1) : name;
                if (o.isHidden() || name.equals("h")) {
                    continue;
                }
                log.println(bundleHelper.getMessage("main.opt." + name));
            }

            log.println(pluginsHeader);
            for (Option<?> o : pluginOptions.pluginsOptions) {
                String name = o.aliases[0].substring(1); // there must always be at least one name
                name = name.charAt(0) == '-' ? name.substring(1) : name;
                if (o.isHidden() || name.equals("h")) {
                    continue;
                }
                log.println(bundleHelper.getMessage("main.opt." + name));
            }
        }

        public void showPlugins(PrintWriter log) {
            for (PluginProvider fact : ImagePluginProviderRepository.getImageWriterProviders(null)) {
                log.println("\n" + bundleHelper.getMessage("main.plugin.name")
                        + ": " + fact.getName());
                Integer[] range = ImagePluginConfiguration.getRange(fact.getCategory());
                String cat = range == null ? fact.getCategory() : fact.getCategory() + ". " +
                        bundleHelper.getMessage("main.plugin.range.from") + " " + range[0] + " " +
                        bundleHelper.getMessage("main.plugin.range.to") + " " + range[1] + ".";
                log.println(bundleHelper.getMessage("main.plugin.category")
                        + ": " + cat);
                log.println(bundleHelper.getMessage("main.plugin.description")
                        + ": " + fact.getDescription());
                log.println(bundleHelper.getMessage("main.plugin.configuration")
                        + ": " + (fact.getConfiguration() == null
                                ? bundleHelper.getMessage("main.plugin.no.configuration") : fact.getConfiguration()));
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
    }

    private PluginsOptions pluginOptions;
    private PrintWriter log;
    private final ResourceBundleHelper bundleHelper;

    public TaskHelper(String path) {
        this.bundleHelper = new ResourceBundleHelper(path);
    }

    public <T> OptionsHelper<T> newOptionsHelper(Class<T> clazz, Option<?>[] options) {
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
        log.println(bundleHelper.getMessage("error.prefix") + " " + bundleHelper.getMessage(key, args));
    }

    public void warning(String key, Object... args) {
        log.println(bundleHelper.getMessage("warn.prefix") + " " + bundleHelper.getMessage(key, args));
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

}
