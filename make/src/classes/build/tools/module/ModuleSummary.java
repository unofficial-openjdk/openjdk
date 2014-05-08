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

package build.tools.module;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import jdk.jigsaw.module.Module;
import jdk.jigsaw.module.ModuleDependence;

public class ModuleSummary {
    private static final String USAGE = "Usage: ModuleSummary -mp <dir> -o <outputfile>";
    private static final String MODULES_SER = "jdk/jigsaw/module/resources/modules.ser";
    public static void main(String[] args) throws Exception {
        int i=0;
        Path modpath = null;
        Path outfile = null;
        while (i < args.length && args[i].startsWith("-")) {
            String arg = args[i++];
            switch (arg) {
                case "-mp":
                    modpath = Paths.get(args[i++]);
                    break;
                case "-o":
                    outfile = Paths.get(args[i++]);
                    break;
                default:
                    System.err.println(USAGE);
                    System.exit(-1);
            }
        }
        if (outfile == null || modpath == null) {
            System.err.println(USAGE);
            System.exit(1);
        }
        if (outfile.getParent() != null) {
            Files.createDirectories(outfile.getParent());
        }

        try (PrintStream out = new PrintStream(Files.newOutputStream(outfile))) {
            Module[] modules = readModules();
            writeHeader(out, modules.length);
            List<Module> mods =
                Arrays.stream(modules)
                      .sorted(Comparator.comparing(Module::id))
                      .collect(Collectors.toList());
            for (Module m: mods) {
                genSummary(out, m, modpath.resolve(m.id().name() + ".jmod"));
            }
            out.format("</table>");
            out.format("</body></html>%n");
        }
    }

    private static Module[] readModules() throws Exception {
        InputStream stream = ClassLoader.getSystemResourceAsStream(MODULES_SER);
        if (stream == null) {
            System.err.format("WARNING: %s not found%n", MODULES_SER);
            return new Module[0];
        }
        try (InputStream in = stream) {
            ObjectInputStream ois = new ObjectInputStream(in);
            Module[] mods = (Module[]) ois.readObject();
            if (mods.length == 0)
                System.err.format("WARNING: %s is empty%n", MODULES_SER);
            return mods;
        }
    }

    private static String toString(ModuleDependence d) {
        Stream<String> mods = d.modifiers().stream().map(e -> e.toString().toLowerCase());
        return (Stream.concat(Stream.of("requires"),
                              Stream.concat(mods,
                                            Stream.of(d.query().name())))
                .collect(Collectors.joining(" ")));
    }


    private static void genSummary(PrintStream out, Module m, Path jmod) throws IOException {
        JmodInfo jm = new JmodInfo(jmod);
        out.format("<tr>%n");
        out.format("<td><b>%s</b><br><br>%n", m.id().name());
                out.format("jmod file %12d bytes<br>%n", jm.filesize);
        out.format("uncompressed %12d bytes<br>%n", jm.size);
        out.format("  %8d %-12s %10d bytes<br>%n", jm.classCount, "classes", jm.classBytes);
        out.format("  %8d %-12s %10d bytes<br>%n", jm.resourceCount, "resources", jm.resourceBytes);
        out.format("  %8d %-12s %10d bytes<br>%n", jm.configCount, "config", jm.configBytes);
        long diz = jm.nativeLibs.entrySet().stream()
                .filter(e -> e.getKey().endsWith(".diz"))
                .mapToLong(e -> e.getValue().longValue()).sum();
        long dizCount = jm.nativeLibs.keySet().stream()
                .filter(n -> n.endsWith(".diz"))
                .count();
        out.format("  %8d %-12s %10d bytes<br>%n", jm.nativeLibs.size() - dizCount,
                   "native libs",
                   jm.nativeLibs.values().stream().mapToLong(l -> l.longValue()).sum() - diz);
        out.format("  %8d %-12s %10d bytes<br>%n", dizCount, "native diz", diz);
        out.format("  %8d %-12s %10d bytes<br>%n", jm.nativeCmds.size(), "launchers",
                   jm.nativeCmds.values().stream().mapToLong(l -> l.longValue()).sum());
        out.format("</td>%n");
        out.format("<td>%n");
        jm.nativeCmds.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.format("%s <br>%n", e.getKey()));
        out.format("</td>%n");
        String requires = m.moduleDependences().stream()
            .map(ModuleSummary::toString)
            .sorted()
            .collect(Collectors.joining("<br>\n"));
        out.format("<td>%s</td>%n", requires);
        String exports = m.exports().stream()
            .filter(e -> e.permit() == null)
            .map(e -> "exports " + e.pkg())
            .sorted()
            .collect(Collectors.joining("<br>\n"));
        out.format("<td>%s</td>%n", exports);
        String services = m.serviceDependences().stream()
            .map(d -> "uses " + d.service())
            .sorted()
            .collect(Collectors.joining("<br>\n"));
        out.format("<td>%s</td>%n", services);
        if (jm.nativeLibs.size() > 0) {
            String nativeLibs = jm.nativeLibs.keySet().stream()
                .sorted()
                .collect(Collectors.joining("<br>\n"));
            out.format("<td class=\"col1\">%s</td>%n", nativeLibs);
            String sizes = jm.nativeLibs.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getValue().toString())
                .collect(Collectors.joining("<br>\n"));
            out.format("<td class=\"col2\">%s</td>%n", sizes);
        }
        out.format("</td>%n");
        out.format("</tr>%n");
    }

    private static void writeHeader(PrintStream out, int numModules) {
        out.format("<html>%n");
        out.format("<head>%n<style>%n");
        out.format("table {font-family:monospace; font-size:80%% border: 1px solid black; border-collapse: collapse;}%n");
        out.format("td {vertical-align:text-top; border: 1px solid;}%n");
        out.format("td.col1 {border-right: none;}");
        out.format("td.col2 {border-left: none; text-align:right;}");
        out.format("th {border: 1px solid black;}%n");
        out.format("th.col1 {border-right: none;}");
        out.format("th.col2 {border-left: none; text-align:right;}");
        out.format("</style>%n</head>%n");
        out.format("Number of modules = %d%n", numModules);
        out.format("<table>");
        out.format("<tr>%n");
        out.format("<th>Module</th>%n");
        out.format("<th>Launchers</th>%n");
        out.format("<th>Dependences</th>%n");
        out.format("<th>Exports</th>%n");
        out.format("<th>Services</th>%n");
        out.format("<th class=\"col1\">Native libs</th>%n");
        out.format("<th class=\"col2\">Bytes</th>%n");
        out.format("</tr>%n");
    }

    static class JmodInfo {
        final long size;
        final long filesize;
        final int  classCount;
        final long classBytes;
        final int  resourceCount;
        final long resourceBytes;
        final int  configCount;
        final long configBytes;
        final Map<String,Long> nativeCmds = new HashMap<>();
        final Map<String,Long> nativeLibs = new HashMap<>();
        JmodInfo(Path jmod) throws IOException {
            this.filesize = jmod.toFile().length();
            long total = 0;
            int cCount = 0;
            long cBytes = 0;
            int rCount = 0;
            long rBytes = 0;
            int cfCount = 0;
            long cfBytes = 0;
            try (ZipFile zf = new ZipFile(jmod.toFile())) {
                for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements();) {
                    ZipEntry ze = e.nextElement();
                    String fn = ze.getName();
                    String dir = fn.substring(0, fn.indexOf('/'));
                    String filename = fn.substring(fn.lastIndexOf('/') + 1);
                    long len = ze.getSize();
                    total += len;
                    switch (dir) {
                        case NATIVE_LIBS:
                            nativeLibs.put(filename, len);
                            break;
                        case NATIVE_CMDS:
                            nativeCmds.put(filename, len);
                            break;
                        case CLASSES:
                            if (filename.endsWith(".class")) {
                                cCount++;
                                cBytes += len;
                            } else {
                                rCount++;
                                rBytes += len;
                            }
                            break;
                        case CONFIG:
                            cfCount++;
                            cfBytes += len;
                            break;
                        default:
                            break;
                    }
                }
                this.classCount = cCount;
                this.classBytes = cBytes;
                this.resourceCount = rCount;
                this.resourceBytes = rBytes;
                this.configCount = cfCount;
                this.configBytes = cfBytes;
                this.size = total;
            }
        }

        static final String NATIVE_LIBS = "native";
        static final String NATIVE_CMDS = "bin";
        static final String CLASSES = "classes";
        static final String CONFIG = "conf";
        static final String MODULE_SERVICES = "module/services";
        static final String MODULE_NAME = "module";
    }
}
