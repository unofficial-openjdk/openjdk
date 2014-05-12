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
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        Path depSer = null;
        String title = "JDK Module Summary";
        while (i < args.length && args[i].startsWith("-")) {
            String arg = args[i++];
            switch (arg) {
                case "-mp":
                    modpath = Paths.get(args[i++]);
                    break;
                case "-o":
                    outfile = Paths.get(args[i++]);
                    break;
                case "-deps":
                    depSer = Paths.get(args[i++]);
                    break;
                case "-title":
                    title = args[i++];
                    break;
                default:
                    System.err.println(USAGE);
                    System.exit(-1);
            }
        }
        Set<String> roots = new HashSet<>();
        while (i < args.length) {
            roots.add(args[i++]);
        }
        if (outfile == null || modpath == null) {
            System.err.println(USAGE);
            System.exit(1);
        }
        if (outfile.getParent() != null) {
            Files.createDirectories(outfile.getParent());
        }

        // dependences to be highlighted
        Map<String, Set<String>> deps = new HashMap<>();
        if (depSer != null) {
            try (InputStream in = Files.newInputStream(depSer)) {
                Module[] mods = ModuleUtils.readModules(in);
                for (Module m : mods) {
                    deps.put(m.id().name(),
                             m.moduleDependences().stream()
                                 .map(d -> d.query().name())
                                 .collect(Collectors.toSet()));
                }
            }
        }

        ModuleSummary ms = new ModuleSummary(title, modpath, deps);
        ms.genReport(outfile, roots);
    }

    private final String title;
    private final Map<Module, JmodInfo> jmods = new HashMap<>();
    private final Module[] modules;
    private final Map<String,Set<String>> deps;
    ModuleSummary(String title, Path modpath, Map<String,Set<String>> deps) throws IOException {
        this.title = title;
        this.modules = ModuleUtils.readModules();
        for (Module m : modules) {
            jmods.put(m,
                      new JmodInfo(modpath.resolve(m.id().name() + ".jmod")));
        }
        this.deps = deps;
    }

    public void genReport(Path outfile, Set<String> roots) throws IOException {
        try (PrintStream out = new PrintStream(Files.newOutputStream(outfile))) {
            List<Module> mods;
            if (roots.isEmpty()) {
                mods = Arrays.stream(modules).sorted(Comparator.comparing(Module::id))
                           .collect(Collectors.toList());
            } else {
                mods = ModuleUtils.resolve(modules, roots).stream()
                           .sorted(Comparator.comparing(Module::id))
                           .collect(Collectors.toList());
            }

            long totalBytes = mods.stream().mapToLong(m -> jmods.get(m).size).sum();
            writeHeader(out, mods.size(), totalBytes);
            for (Module m: mods) {
                genSummary(out, m, jmods.get(m));
            }
            out.format("</table>");
            out.format("</body></html>%n");
        }
    }
    private String toRequires(Module from, ModuleDependence d) {
        String name = d.query().name();
        String ref = String.format("<a href=\"#%s\">%s</a>",
                                   name, name);
        Stream<String> mods = d.modifiers().stream().map(e -> e.toString().toLowerCase());
        String result = (Stream.concat(Stream.of("requires"),
                              Stream.concat(mods,
                                            Stream.of(ref)))
                .collect(Collectors.joining(" ")));
        String mn = from.id().name();
        return deps.containsKey(mn) && deps.get(mn).contains(name)
                    ? "<b>" + result + "</b>" : result;
    }

    private void genSummary(PrintStream out, Module m, JmodInfo jm) throws IOException {
        String modulename = m.id().name();
        out.format("<tr>%n");
        out.format("<td class=\"name\"><b><a name=\"%s\">%s</a></b><br><br>%n",
                   modulename, modulename);
        out.format("jmod file<br>%n");
        out.format("uncompressed<br>%n");
        out.format("%8d %s<br>%n", jm.classCount, "classes");
        out.format("%8d %s<br>%n", jm.resourceCount, "resources");
        out.format("%8d %s<br>%n", jm.configCount, "config");
        out.format("%8d %s<br>%n", jm.nativeLibs.size() - jm.debugInfoLibCount, "native libs");
        out.format("%8d %s<br>%n", jm.debugInfoLibCount, "debugInfo libs");
        out.format("%8d %s<br>%n", jm.nativeCmds.size() - jm.debugInfoCmdCount, "launchers");
        out.format("%8d %s<br>%n", jm.debugInfoCmdCount, "debugInfo");
        out.format("</td>%n");
        out.format("<td class=\"num\"><br><br>%n");
        out.format("%12d<br>%n", jm.filesize);
        out.format("%12d<br>%n", jm.size);
        out.format("%10d<br>%n", jm.classBytes);
        out.format("%10d<br>%n", jm.resourceBytes);
        out.format("%10d<br>%n", jm.configBytes);
        out.format("%10d<br>%n", jm.nativeLibs.values().stream()
                                   .mapToLong(l -> l.longValue()).sum() - jm.debugInfoLibBytes);
        out.format("%10d<br>%n", jm.debugInfoLibBytes);
        out.format("%10d<br>%n", jm.nativeCmds.values().stream()
                                   .mapToLong(l -> l.longValue()).sum());
        out.format("%10d<br>%n", jm.debugInfoCmdBytes);
        out.format("</td>%n");
        out.format("<td>%n");
        jm.nativeCmds.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.format("%s <br>%n", e.getKey()));
        out.format("</td>%n");
        String requires = m.moduleDependences().stream()
            .map(md -> toRequires(m, md))
            .sorted()
            .collect(Collectors.joining("<br>\n"));
        out.format("<td>%s</td>%n", requires);
        String exports = m.exports().stream()
            .filter(e -> e.permit() == null)
            .map(e -> e.pkg())
            .sorted()
            .collect(Collectors.joining("<br>\n"));
        out.format("<td>%s</td>%n", exports);
        Stream<String> uses = m.serviceDependences().stream()
            .map(d -> "uses " + d.service())
            .sorted();
        Stream<String> providers = m.services().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .flatMap(e -> e.getValue().stream().map(p ->
                String.format("prov %s<br>&nbsp; <em>w/ %s</em>", e.getKey(), p)));
        out.format("<td>%s</td>%n", Stream.concat(uses, providers)
                                          .collect(Collectors.joining("<br>\n")));
        if (jm.nativeLibs.size() > 0) {
            String nativeLibs = jm.nativeLibs.keySet().stream()
                .sorted()
                .collect(Collectors.joining("<br>\n"));
            out.format("<td class=\"name\">%s</td>%n", nativeLibs);
            String sizes = jm.nativeLibs.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getValue().toString())
                .collect(Collectors.joining("<br>\n"));
            out.format("<td class=\"num\">%s</td>%n", sizes);
        }
        out.format("</td>%n");
        out.format("</tr>%n");
    }

    private void writeHeader(PrintStream out, int numModules, long size) {
        out.format("<html>%n");
        out.format("<head>%n");
        out.format("<title>%s</title>%n", title);
        out.format("<style type=\"text/css\">%n");
        out.format("table {border: 1px solid black; border-collapse: collapse;}%n");
        out.format("td { font-family: monospace; padding: 3px 6px}%n");
        out.format("td {vertical-align:text-top; border: 1px solid;}%n");
        out.format("td.name {border-right: none;}");
        out.format("td.num {border-left: none; text-align:right;}");
        out.format("th {border: 1px solid black;}%n");
        out.format("th.name {border-right: none;}");
        out.format("th.num {border-left: none; text-align:right;}");
        out.format("</style>%n</head>%n");
        out.format("<h1>%s</h1>%n", title);
        out.format("<h3>Number of Modules = %d<br>%n", numModules);
        out.format("Total Uncompressed Size = %d</h3>%n", size);
        out.format("<table>");
        out.format("<tr>%n");
        out.format("<th class=\"name\">Module</th>%n");
        out.format("<th class=\"num\">Bytes</th>%n");
        out.format("<th>Launchers</th>%n");
        out.format("<th>Dependences</th>%n");
        out.format("<th>Exports</th>%n");
        out.format("<th>Services</th>%n");
        out.format("<th class=\"name\">Native libs</th>%n");
        out.format("<th class=\"num\">Bytes</th>%n");
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
        final int debugInfoLibCount;
        final long debugInfoLibBytes;
        final int debugInfoCmdCount;
        final long debugInfoCmdBytes;
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
            int dizLibCount = 0;
            long dizLibBytes = 0;
            int dizCmdCount = 0;
            long dizCmdBytes = 0;
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
                            if (filename.endsWith(".diz")) {
                                dizLibCount++;
                                dizLibBytes += len;
                            }
                            break;
                        case NATIVE_CMDS:
                            nativeCmds.put(filename, len);
                            if (filename.endsWith(".diz")) {
                                dizCmdCount++;
                                dizCmdBytes += len;
                            }
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
                this.debugInfoLibCount = dizLibCount;
                this.debugInfoLibBytes = dizLibBytes;
                this.debugInfoCmdCount = dizCmdCount;
                this.debugInfoCmdBytes = dizCmdBytes;
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
