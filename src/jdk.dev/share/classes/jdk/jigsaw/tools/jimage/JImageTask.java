/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

package jdk.jigsaw.tools.jimage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import jdk.jigsaw.module.internal.ImageLocation;
import jdk.jigsaw.module.internal.ImageReader;
import static sun.tools.javac.Main.*;

class JImageTask {
    static class BadArgs extends Exception {
        static final long serialVersionUID = 8765093759964640723L;  // ## re-generate
        final String key;
        final Object[] args;
        boolean showUsage;

        BadArgs(String key, Object... args) {
            super(JImageTask.getMessage(key, args));
            this.key = key;
            this.args = args;
        }

        BadArgs showUsage(boolean b) {
            showUsage = b;
            return this;
        }
    }

    static abstract class Option {
        final boolean hasArg;
        final String[] aliases;

        Option(boolean hasArg, String... aliases) {
            this.hasArg = hasArg;
            this.aliases = aliases;
        }

        boolean isHidden() {
            return false;
        }

        boolean matches(String opt) {
            for (String a : aliases) {
                if (a.equals(opt)) {
                    return true;
                } else if (opt.startsWith("--") && hasArg && opt.startsWith(a + "=")) {
                    return true;
                }
            }
            return false;
        }

        boolean ignoreRest() {
            return false;
        }

        abstract void process(JImageTask task, String opt, String arg) throws BadArgs;
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

    static Option[] recognizedOptions = {
        new Option(true, "--dir") {
            @Override
            void process(JImageTask task, String opt, String arg) throws BadArgs {
                 task.options.directory = arg;
            }
        },
        new HiddenOption(false, "--fullversion") {
            @Override
            void process(JImageTask task, String opt, String arg) {
                task.options.fullVersion = true;
            }
        },
        new Option(false, "--help") {
            @Override
            void process(JImageTask task, String opt, String arg) {
                task.options.help = true;
            }
        },
        new Option(false, "--verbose") {
            @Override
            void process(JImageTask task, String opt, String arg) throws BadArgs {
                 task.options.verbose = true;
            }
        },
        new Option(false, "--version") {
            @Override
            void process(JImageTask task, String opt, String arg) {
                task.options.version = true;
            }
        },
    };

    static class Options {
        Task task;
        String directory = ".";
        boolean fullVersion;
        boolean help;
        boolean verbose;
        boolean version;
        List<File> jimages = new LinkedList<>();
    }

    private static final String PROGNAME = "jimage";
    private final Options options = new Options();

    enum Task {
        EXPAND,
        LIST
    };

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
            boolean ok = run();
            return ok ? EXIT_OK : EXIT_ERROR;
        } catch (BadArgs e) {
            reportError(e.key, e.args);
            if (e.showUsage) {
                log.println(getMessage("main.usage.summary", PROGNAME));
            }
            return EXIT_CMDERR;
        } catch (Exception x) {
            x.printStackTrace();
            return EXIT_ABNORMAL;
        } finally {
            log.flush();
        }
    }

    private void expand() throws IOException, BadArgs {
        File directory = new File(options.directory);

        for (File file : options.jimages) {
            String path = file.getCanonicalPath();
            ImageReader reader = new ImageReader(path);
            reader.open();
            String[] entryNames = reader.getEntryNames(true);

            for (String entry : entryNames) {
                ImageLocation location = reader.findLocation(entry);
                long offset = location.getContentOffset();
                long size = location.getUncompressedSize();
                long compressedSize = location.getCompressedSize();
                boolean isCompressed = compressedSize != 0;

                byte[] bytes;

                if (isCompressed) {
                    bytes = reader.getResource(offset, compressedSize);
                    // TODO compression
                } else {
                    bytes = reader.getResource(offset, size);
                }

                File resource =  new File(directory, entry);
                File parent = resource.getParentFile();

                if (parent.exists()) {
                    if (!parent.isDirectory()) {
                        throw new JImageTask.BadArgs("err.cannot.create.dir", parent.getAbsolutePath());
                    }
                } else if (!parent.mkdirs()) {
                    throw new JImageTask.BadArgs("err.cannot.create.dir", parent.getAbsolutePath());
                }

                Files.write(resource.toPath(), bytes);
            }

            reader.close();
        }
    }

    private String pad(String string, int width, boolean justifyRight) {
        int length = string.length();

        if (length == width) {
            return string;
        }

        if (length > width) {
            return string.substring(0, width);
        }

        int padding = width - length;

        StringBuilder sb = new StringBuilder(width);
        if (justifyRight) {
            for (int i = 0; i < padding; i++) {
                sb.append(' ');
            }
        }

        sb.append(string);

        if (!justifyRight) {
            for (int i = 0; i < padding; i++) {
                sb.append(' ');
            }
        }

        return sb.toString();
    }

    private String pad(String string, int width) {
        return pad(string, width, false);
    }

    private String pad(long value, int width) {
        return pad(Long.toString(value), width, true);
    }

    private static final int NAME_WIDTH = 40;
    private static final int NUMBER_WIDTH = 12;
    private static final int OFFSET_WIDTH = NUMBER_WIDTH;
    private static final int SIZE_WIDTH = NUMBER_WIDTH;
    private static final int COMPRESSEDSIZE_WIDTH = NUMBER_WIDTH;

    private void print(String entry, ImageLocation location) {
        log.print(pad(location.getContentOffset(), OFFSET_WIDTH));
        log.print(pad(location.getUncompressedSize(), SIZE_WIDTH));
        log.print(pad(location.getCompressedSize(), COMPRESSEDSIZE_WIDTH));
        log.println(" " + entry);
    }

    private void print(ImageReader reader, String entry) {
        if (options.verbose) {
            print(entry, reader.findLocation(entry));
        } else {
            log.println(entry);
        }
    }

    private void list() throws IOException {
        for (File file : options.jimages) {
            String path = file.getCanonicalPath();
            ImageReader reader = new ImageReader(path);
            reader.open();
            String[] entryNames = reader.getEntryNames(true);

            if (options.jimages.size() != 1) {
                log.println("jimage: " + file.getName());
            }

            if (options.verbose) {
                log.print(pad("Offset", OFFSET_WIDTH));
                log.print(pad("Size", SIZE_WIDTH));
                log.print(pad("Compressed", COMPRESSEDSIZE_WIDTH));
                log.println(" Entry");
            }

            for (String entry : entryNames) {
                print(reader, entry);
            }

            reader.close();
        }
    }

    private boolean run() throws IOException, BadArgs {
        switch (options.task) {
            case EXPAND:
                expand();
                break;
            case LIST:
                list();
                break;
            default:
                throw new BadArgs("err.invalid.task", options.task.name()).showUsage(true);
        }
        return true;
    }

    private PrintWriter log;
    void setLog(PrintWriter out) {
        log = out;
    }
    public void handleOptions(String[] args) throws BadArgs {
        // process options

        if (args.length == 0) {
            options.task = Task.LIST;
        } else if (args.length > 1) {
            String arg = args[0];

            try {
                options.task = Enum.valueOf(Task.class, arg.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BadArgs("err.invalid.task", arg).showUsage(true);
            }
        }

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.charAt(0) == '-') {
                Option option = getOption(arg);
                String param = null;

                if (option.hasArg) {
                    if (arg.startsWith("--") && arg.indexOf('=') > 0) {
                        param = arg.substring(arg.indexOf('=') + 1, arg.length());
                    } else if (i + 1 < args.length) {
                        param = args[++i];
                    }

                    if (param == null || param.isEmpty() || param.charAt(0) == '-') {
                        throw new BadArgs("err.missing.arg", arg).showUsage(true);
                    }
                }

                option.process(this, arg, param);

                if (option.ignoreRest()) {
                    i = args.length;
                }
            } else {
                File file = new File(arg);

                if (file.exists() & file.isFile()) {
                    options.jimages.add(file);
                } else {
                    throw new BadArgs("err.not.a.jimage", arg).showUsage(true);
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
            if (o.isHidden() || name.equals("h")) {
                continue;
            }
            log.println(getMessage("main.opt." + name));
        }
    }

    private void showVersion(boolean full) {
        log.println(version(full ? "full" : "release"));
    }

    private String version(String key) {
        return System.getProperty("java.version");
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
                bundle = ResourceBundle.getBundle("jdk.jigsaw.tools.jimage.resources.jimage", locale);
            } catch (MissingResourceException e) {
                throw new InternalError("Cannot find jimage resource bundle for locale " + locale);
            }
        }
    }
}
