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

import java.io.*;
import java.text.MessageFormat;
import java.util.*;

/**
 * An abstract class for simple command-line tool to extend.
 */
public abstract class Task {
    private final String name;
    protected boolean showHelp;
    public Task(String name) {
        this.name = name;
    }

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

    static abstract class Option<T extends Task> {
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

        abstract void process(T task, String opt, String arg) throws BadArgs;
        final boolean hasArg;
        final String[] aliases;
    }

    static abstract class HiddenOption extends Option {
        HiddenOption(boolean hasArg, String... aliases) {
            super(hasArg, aliases);
        }

        @Override
        boolean isHidden() {
            return true;
        }
    }

    private PrintWriter log;
    protected void setLog(PrintWriter out) {
        log = out;
    }

    /**
     * Result codes.
     */
    static final int EXIT_OK = 0,      // Completed with no errors.
                     EXIT_ERROR = 1,   // Completed but reported errors.
                     EXIT_CMDERR = 2,  // Bad command-line arguments
                     EXIT_SYSERR = 3,  // System error or resource exhaustion.
                     EXIT_ABNORMAL = 4;// terminated abnormally

    protected abstract boolean run() throws IOException;
    /**
     * Returns true if all options are valid.
     */
    protected abstract boolean validateOptions();
    /**
     * Returns the list of supported options.
     */
    protected abstract List<Option<? extends Task>> options();

    public int run(String[] args) {
        if (log == null) {
            log = new PrintWriter(System.out);
        }
        try {
            handleOptions(args);
            if (showHelp) {
                printHelpMessage();
            }
            if (!validateOptions()) {
                if (showHelp) {
                    return EXIT_OK;
                } else {
                    printHelpMessage();
                    return EXIT_CMDERR;
                }
            }
            boolean ok = run();
            return ok ? EXIT_OK : EXIT_ERROR;
        } catch (BadArgs e) {
            reportError(e.key, e.args);
            if (e.showUsage) {
                log.println(getMessage("main.usage.summary", name));
            }
            return EXIT_CMDERR;
        } catch (IOException e) {
            e.printStackTrace();
            return EXIT_ABNORMAL;
        } finally {
            log.flush();
        }
    }

    private void handleOptions(String[] args) throws BadArgs {
        // process options
        for (int i=0; i < args.length; i++) {
            if (args[i].charAt(0) == '-') {
                String name = args[i];
                Option option = getOption(name);
                String param = null;
                if (option.hasArg) {
                    if (name.indexOf('=') > 0) {
                        param = name.substring(name.indexOf('=') + 1, name.length()).trim();
                    } else if (i + 1 < args.length) {
                        param = args[++i].trim();
                    }
                    if (param == null || (param.length() > 0 && param.charAt(0) == '-')) {
                        throw new BadArgs("err.missing.arg", name).showUsage(true);
                    }
                }
                option.process(this, name, param);
                if (option.ignoreRest()) {
                    i = args.length;
                }
            } else {
                // non-option argument not supported for now.
                throw new IllegalArgumentException("Invalid argument: " +
                    Arrays.copyOfRange(args, i, args.length));
            }
        }
    }

    private Option getOption(String name) throws BadArgs {
        for (Option o : options()) {
            if (o.matches(name)) {
                return o;
            }
        }
        throw new BadArgs("err.unknown.option", name).showUsage(true);
    }

    protected void reportError(String key, Object... args) {
        log.println(getMessage("error.prefix") + " " + getMessage(key, args));
    }

    protected void warning(String key, Object... args) {
        log.println(getMessage("warn.prefix") + " " + getMessage(key, args));
    }

    private void printHelpMessage() {
        log.println(getMessage("main.usage", name));
        for (Option o : options()) {
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
}
