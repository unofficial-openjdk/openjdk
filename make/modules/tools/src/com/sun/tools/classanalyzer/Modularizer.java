/*
 * Copyright (c) 2010, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

import com.sun.tools.classanalyzer.ClassPath.Archive;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Modularize classes and resources from a JDK build or image
 * and create jmod files.
 */
class Modularizer extends Task {
    private final Options options = new Options();
    Modularizer() {
        super("Modularizer");
    }

    private static List<Option<? extends Task>> recognizedOptions = Arrays.asList(
        new Option<Modularizer>(false, "-h", "-?", "--help") {
            void process(Modularizer task, String opt, String arg) {
                task.showHelp = true;
            }
        },
        new Option<Modularizer>(false, "-z", "--compress") {
            void process(Modularizer task, String opt, String arg) {
                task.options.compression = true;
            }
        },
        new Option<Modularizer>(true, "-j", "--javahome") {
            void process(Modularizer task, String opt, String arg) {
                task.options.javahome = arg;
            }
        },
        new Option<Modularizer>(true, "-c", "--classlist") {
            void process(Modularizer task, String opt, String arg) {
                task.options.classlistDir = arg;
            }
        },
        new Option<Modularizer>(true, "-b", "--cmds") {
            void process(Modularizer task, String opt, String arg) {
                Path dir = Paths.get(task.options.javahome).resolve("bin");
                for (String s : arg.split("\\s+")) {
                    if (s.isEmpty())
                        continue;
                    try {
                        Path p = dir.relativize(Paths.get(s));
                        task.options.module.cmds.put(p.toString(), s);
                    } catch (IllegalArgumentException e) {
                        System.err.format("%s \"%s\"%n", task.options.module.modulename, s);
                        throw e;
                    }
                }
            }
        },
        new Option<Modularizer>(true, "-g", "--graph") {
            void process(Modularizer task, String opt, String arg) {
                task.options.moduleGraphPath = arg;
            }
        },
        new Option<Modularizer>(true, "-d", "--jmods") {
            void process(Modularizer task, String opt, String arg) {
                task.options.jmodsDir = arg;
            }
        },
        new Option<Modularizer>(true, "-m", "--module") {
            void process(Modularizer task, String opt, String arg) {
                task.options.module = task.newModuleContent(arg);
            }
        },
        new Option<Modularizer>(true, "-n", "--native") {
            void process(Modularizer task, String opt, String arg) {
                Path dir = Paths.get(task.options.javahome).resolve("lib");
                for (String s : arg.split("\\s+")) {
                    if (s.isEmpty())
                        continue;
                    try {
                        Path p = dir.relativize(Paths.get(s));
                        task.options.module.natives.put(p.toString(), s);
                    } catch (IllegalArgumentException e) {
                        System.err.format("%s \"%s\"%n", task.options.module.modulename, s);
                        throw e;
                    }
                }
            }
        },
        new Option<Modularizer>(true, "-f", "--conf") {
            void process(Modularizer task, String opt, String arg) {
                Path dir = Paths.get(task.options.javahome).resolve("lib");
                for (String s : arg.split("\\s+")) {
                    if (s.isEmpty())
                        continue;
                    try {
                        Path p = dir.relativize(Paths.get(s));
                        task.options.module.confs.put(p.toString(), s);
                    } catch (IllegalArgumentException e) {
                        System.err.format("%s \"%s\"%n", task.options.module.modulename, s);
                        throw e;
                    }
                }
            }
        }
    );

    protected List<Option<? extends Task>> options() {
        return recognizedOptions;
    }

    protected boolean validateOptions() {
        return options.classlistDir != null && options.moduleGraphPath != null;
    }

    private Map<String, ModuleContent> modules = new TreeMap<>();
    private List<Archive> archives;
    ModuleContent newModuleContent(String name) {
        ModuleContent mc = modules.get(name);
        if (mc == null) {
            modules.put(name, mc = new ModuleContent(name));
        }
        return mc;
    }

    protected boolean run() throws IOException {
        archives = ClassPath.getArchives(Paths.get(options.javahome));

        // load the module graph
        JigsawModules jmodules = new JigsawModules();
        try (FileInputStream in = new FileInputStream(options.moduleGraphPath)) {
            jmodules.load(in);
        }

        for (String name : jmodules.moduleNames()) {
            ModuleContent mc = modules.get(name);
            if (mc == null) {
                modules.put(name, mc = new ModuleContent(name));
            }

            // need to retain the order of the providers
            mc.services.putAll(jmodules.get(name).services());
        }

        Path dst = Paths.get(options.jmodsDir);
        dst.toFile().mkdirs();
        Path dir = Paths.get(options.classlistDir);

        // Create a jmod file for each module that has <modulename>.classlist
        // and <modulename>.resources files listing its classes and resources
        // respectively
        for (Path p : Files.newDirectoryStream(dir, "*.summary")) {
            String name = p.getFileName().toString();
            name = name.substring(0, name.indexOf(".summary"));
            ModuleContent mc = modules.get(name);
            if (mc == null) {
                throw new RuntimeException("module " + name + " not in the graph");
            }
            Path path = null;
            // read classlist and resources
            if (Files.exists(path = dir.resolve(name + ".classlist"))) {
                mc.classes.addAll(readFile(path));
            }
            if (Files.exists(path = dir.resolve(name + ".resources"))) {
                mc.classes.addAll(readFile(path));
            }
            if (name.equals("jdk.base")) {
                // add module graph to the base module
                mc.classes.add(JigsawModules.MODULE_GRAPH);
            }
            Path jmodfile = options.compression ? dst.resolve(name + ".jmod.gz")
                                                : dst.resolve(name + ".jmod");
            mc.store(jmodfile, options.compression);
        }
        return true;
    }

    private Set<String> readFile(Path p) throws IOException {
        return Files.lines(p, StandardCharsets.US_ASCII).collect(Collectors.<String>toSet());
    }

    private class ModuleContent {
        final String modulename;
        final Map<String,String> natives = new TreeMap<>();
        final Map<String,String> cmds = new TreeMap<>();
        final Map<String,String> confs = new TreeMap<>();
        final Set<String> classes = new TreeSet<>();
        final Map<String,Set<String>> services = new TreeMap<>();
        ModuleContent(String name) {
            this.modulename = name;
        }

        public void store(Path dst, boolean compression) throws IOException {
            if (compression) {
                store(ByteArrayHolder.INSTANCE);
                try (GZIPOutputStream gzos = new GZIPOutputStream(Files.newOutputStream(dst))) {
                    ByteArrayHolder.INSTANCE.writeTo(gzos);
                }
                ByteArrayHolder.INSTANCE.reset();
            } else {
                store(Files.newOutputStream(dst));
            }
        }

        private void store(OutputStream os) throws IOException {
            try (ZipOutputStream zos = new ZipOutputStream(os)) {
                // jmod file layout
                //   classes/            All the module's classes and resources
                //   bin/                native commands
                //   native/             .so files
                //   module/name         A file "name" containing the module's name
                //   module/services/    Like META-INF/services
                //   conf/               Properties etc.
                //
                String mn = modulename + "\n";
                writeZipEntry(zos, "module/name", mn.getBytes());
                writeServices(zos);

                for (String n : classes) {
                    String name = n.replace(File.separatorChar, '/');
                    for (Archive a : archives) {
                        byte[] data = a.readBytes(name);
                        if (data != null) {
                            writeZipEntry(zos, "classes/" + name, data);
                            break;
                        }
                    }
                }

                for (Map.Entry<String,String> e : cmds.entrySet()) {
                    Path p = Paths.get(e.getValue());
                    byte[] data = Files.readAllBytes(p);
                    Path entry = Paths.get("bin", e.getKey());
                    writeZipEntry(zos, entry.toString(), data);
                }
                for (Map.Entry<String,String> e : natives.entrySet()) {
                    Path p = Paths.get(e.getValue());
                    byte[] data = Files.readAllBytes(p);
                    Path entry = Paths.get("native", e.getKey());
                    writeZipEntry(zos, entry.toString(), data);
                }
                for (Map.Entry<String,String> e : confs.entrySet()) {
                    Path p = Paths.get(e.getValue());
                    byte[] data = Files.readAllBytes(p);
                    Path entry = Paths.get("conf", e.getKey());
                    writeZipEntry(zos, entry.toString(), data);
                }
            }
        }

        private void writeServices(ZipOutputStream zos) throws IOException {
            for (Map.Entry<String,Set<String>> e : services.entrySet()) {
                String service = "module/services/" + e.getKey();
                StringBuilder sb = new StringBuilder();
                sb.append("# ").append(modulename).append(" service providers for ")
                    .append(e.getKey()).append("\n");
                e.getValue().stream()
                    .forEachOrdered(impl -> sb.append(impl).append("\n"));
                writeZipEntry(zos, service, sb.toString().getBytes());
            }

        }
        private void writeZipEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
            String n = name.replace(File.separatorChar, '/');
            ZipEntry ze = new ZipEntry(n);
            zos.putNextEntry(ze);
            zos.write(data, 0, data.length);
            zos.closeEntry();
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("module ").append(modulename);
            for (String s : cmds.values()) {
                sb.append("\n").append("  -cmds ").append(s);
            }
            for (String s : natives.values()) {
                sb.append("\n").append("  -native ").append(s);
            }
            for (String s : confs.values()) {
                sb.append("\n").append("  -conf ").append(s);
            }
            return sb.toString();
        }
    }

    private static class ByteArrayHolder extends ByteArrayOutputStream {
        final static ByteArrayHolder INSTANCE = new ByteArrayHolder();

        @Override
        public byte[] toByteArray() {
            return buf;
        }
    }

    private static class Options {
        boolean compression = false;
        String javahome = System.getProperty("java.home");
        String classlistDir;
        String moduleGraphPath;
        String jmodsDir = ".";
        ModuleContent module;
    }

    public static void main(String... args) throws Exception {
        Modularizer modularizer = new Modularizer();
        int rc = modularizer.run(args);
        System.exit(rc);
    }
}
