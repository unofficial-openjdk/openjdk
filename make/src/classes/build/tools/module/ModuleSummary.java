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

import com.sun.tools.classfile.AccessFlags;
import static com.sun.tools.classfile.AccessFlags.ACC_PROTECTED;
import com.sun.tools.classfile.ClassFile;
import com.sun.tools.classfile.ConstantPoolException;
import com.sun.tools.classfile.Dependencies;
import com.sun.tools.classfile.Dependency;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jdk.jigsaw.module.Configuration;
import jdk.jigsaw.module.Layer;
import jdk.jigsaw.module.ModuleArtifact;
import jdk.jigsaw.module.ModuleArtifactFinder;
import jdk.jigsaw.module.ModuleDependence;
import jdk.jigsaw.module.ModuleDescriptor;

public class ModuleSummary {
    private static final String USAGE = "Usage: ModuleSummary -mp <dir> -o <outfile>";

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
        Path dir = outfile.getParent() != null ? outfile.getParent() : Paths.get(".");
        Files.createDirectories(dir);
        ModuleSummary ms = new ModuleSummary(modpath);
        ms.genReport(outfile, ms.modules(), "JDK Module Summary");
        ms.genReport(dir.resolve("java.se.html"),
                     Collections.singleton("java.se"), "Java SE Modules");
        ms.genCSV(dir.resolve("jdk.csv"), ms.modules());
    }

    private final Map<String, ModuleArtifact> nameToArtifact = new HashMap<>();
    private final Map<String, JmodInfo> jmods = new HashMap<>();
    private final Map<String, Set<String>> deps = new HashMap<>();
    private final Map<String, ModuleDescriptor> packageMap = new HashMap<>();
    private final Path modpath;

    ModuleSummary(Path modpath) throws IOException, ConstantPoolException {
        this.modpath = modpath;

        Set<ModuleArtifact> artifacts = ModuleArtifactFinder.installedModules().allModules();
        artifacts.forEach(m -> nameToArtifact.put(m.descriptor().name(), m));

        // build package map for all modules for API dependency analysis
        artifacts.forEach(m -> m.packages().stream()
                 .forEach(p -> packageMap.put(p, m.descriptor())));

        for (ModuleArtifact artifact : artifacts) {
            String name = artifact.descriptor().name();
            Path jmod = modpath.resolve(name + ".jmod");
            jmods.put(name, new JmodInfo(jmod));
            deps.put(name, getAPIDependences(artifact, jmod));
        }
    }

    Set<String> modules() {
        return nameToArtifact.keySet();
    }

    public void genCSV(Path outfile, Set<String> roots) throws IOException {
        Configuration cf  = resolve(roots);
        Set<ModuleDescriptor> selectedModules = cf.descriptors();
        try (PrintStream out = new PrintStream(Files.newOutputStream(outfile))) {
            out.format("module,size,\"direct deps\",\"indirect deps\",total," +
                       "\"compressed size\",\"compressed direct deps\",\"compressed indirect deps\",total%n");
            selectedModules.stream()
                   .sorted(Comparator.comparing(ModuleDescriptor::name))
                   .forEach(m -> {
                        Set<ModuleDescriptor> deps = resolve(Collections.singleton(m.name())).descriptors();
                        long reqBytes = 0;
                        long reqJmodSize = 0;
                        long otherBytes = 0;
                        long otherJmodSize = 0;
                        Set<String> reqs = m.moduleDependences().stream()
                                                .map(d -> d.query().name())
                                                .collect(Collectors.toSet());
                        reqBytes = deps.stream()
                                        .filter(d -> reqs.contains(d.name()))
                                        .mapToLong(d -> jmods.get(d.name()).size).sum();
                        reqJmodSize = deps.stream()
                                        .filter(d -> reqs.contains(d.name()))
                                        .mapToLong(d -> jmods.get(d.name()).filesize).sum();
                        otherBytes = deps.stream()
                                        .filter(d -> !reqs.contains(d.name()))
                                        .mapToLong(d -> jmods.get(d.name()).size).sum();
                        otherJmodSize = deps.stream()
                                        .filter(d -> !reqs.contains(d.name()))
                                        .mapToLong(d -> jmods.get(d.name()).filesize).sum();

                        String name = m.name();
                        out.format("%s,%d,%d,%d,%d,%d,%d,%d,%d%n", name,
                                   jmods.get(name).size, reqBytes, otherBytes,
                                   jmods.get(name).size + reqBytes + otherBytes,
                                   jmods.get(name).filesize, reqJmodSize, otherJmodSize,
                                   jmods.get(name).filesize + reqJmodSize + otherJmodSize);
                   });
        }
    }

    public void genReport(Path outfile, Set<String> roots, String title) throws IOException {
        Configuration cf = resolve(roots);
        Set<ModuleDescriptor> selectedModules = cf.descriptors();
        try (PrintStream out = new PrintStream(Files.newOutputStream(outfile))) {
            long totalBytes = selectedModules.stream()
                                  .mapToLong(m -> jmods.get(m.name()).size).sum();
            writeHeader(out, title, selectedModules.size(), totalBytes);
            selectedModules.stream()
                   .sorted(Comparator.comparing(ModuleDescriptor::name))
                   .forEach(m -> {
                        try {
                            String name = m.name();
                            Set<ModuleDescriptor> deps =
                                resolve(Collections.singleton(name)).descriptors();
                            long reqBytes = jmods.get(name).size;
                            long reqJmodSize = jmods.get(name).filesize;
                            int reqCount = deps.size();
                            reqBytes += deps.stream()
                                        .mapToLong(d -> jmods.get(d.name()).size).sum();
                            reqJmodSize += deps.stream()
                                    .mapToLong(d -> jmods.get(d.name()).filesize).sum();
                            genSummary(out, m, jmods.get(name), reqCount, reqBytes, reqJmodSize);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
            out.format("</table>");
            out.format("</body></html>%n");
        }
    }

    private String toRequires(String from, ModuleDependence d) {
        String name = d.query().name();
        String ref = String.format("<a href=\"#%s\">%s</a>",
                                   name, name);
        Stream<String> mods = d.modifiers().stream().map(e -> e.toString().toLowerCase());
        String result = (Stream.concat(Stream.of("requires"),
                                       Stream.concat(mods, Stream.of(ref)))
                .collect(Collectors.joining(" ")));
        // API dependency: bold
        // aggregator module's require: italic
        ModuleArtifact artifact = nameToArtifact.get(from);
        boolean isEmpty = (artifact.packages().size() == 0);
        return deps.containsKey(from) && deps.get(from).contains(name)
                    ? String.format("<b>%s</b>", result)
                    : (isEmpty ? String.format("<em>%s</em>", result) : result);
    }

    private void genSummary(PrintStream out, ModuleDescriptor descriptor, JmodInfo jm,
                            int requireModules, long requireUncompressed, long requireJmodFileSize)
            throws IOException
    {
        String name = descriptor.name();
        out.format("<tr>%n");
        out.format("<td class=\"name\"><b><a name=\"%s\">%s</a></b><br><br>%n",
                   name, name);
        // statistic about module content
        out.format("jmod file<br>%n");
        out.format("uncompressed<br>%n");
        out.format("%8d %s<br>%n", jm.classCount, "classes");
        out.format("%8d %s<br>%n", jm.resourceCount, "resources");
        out.format("%8d %s<br>%n", jm.configCount, "config");
        out.format("%8d %s<br>%n", jm.nativeLibs.size() - jm.debugInfoLibCount, "native libs");
        out.format("%8d %s<br>%n", jm.debugInfoLibCount, "debugInfo libs");
        out.format("%8d %s<br>%n", jm.nativeCmds.size() - jm.debugInfoCmdCount, "launchers");
        out.format("%8d %s<br>%n", jm.debugInfoCmdCount, "debugInfo");
        out.format("<br>Transitive dependences<br>%n");
        out.format("Total uncompressed<br>%n");
        out.format("Approx compressed<br>%n");

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
        // total size due to the transitive dependences
        out.format("<br>%10d<br>%n", requireModules);
        out.format("%10d<br>%n", requireUncompressed);
        out.format("%10d<br>%n", requireJmodFileSize);

        out.format("</td>%n");

        out.format("<td>%n");
        jm.nativeCmds.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.format("%s <br>%n", e.getKey()));
        out.format("</td>%n");
        String requires = descriptor.moduleDependences().stream()
            .map(md -> toRequires(name, md))
            .sorted()
            .collect(Collectors.joining("<br>\n"));
        out.format("<td>%s</td>%n", requires);
        String exports = descriptor.exports().stream()
            .filter(e -> e.permit() == null)
            .map(e -> e.pkg())
            .sorted()
            .collect(Collectors.joining("<br>\n"));
        out.format("<td>%s</td>%n", exports);
        Stream<String> uses = descriptor.serviceDependences().stream()
            .map(d -> "uses " + d.service())
            .sorted();
        Stream<String> providers = descriptor.services().entrySet().stream()
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

    private void writeHeader(PrintStream out, String title, int numModules, long size) {
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
        out.format("Total Uncompressed Size = %,d bytes</h3>%n", size);
        out.format("(*) <b>bold</b> indicates dependence from exported APIs; <em>italic</em> indicates dependences from empty module<p>%n");
        out.format("<table>");
        out.format("<tr>%n");
        out.format("<th class=\"name\">Module</th>%n");
        out.format("<th class=\"num\">Bytes</th>%n");
        out.format("<th>Launchers</th>%n");
        out.format("<th>Dependences (*)</th>%n");
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

        static final String MODULE_ID = "module/id";
        static final String MODULE_MAIN_CLASS = "module/main-class";
    }

    Set<String> getAPIDependences(ModuleArtifact artifact, Path jmod)
        throws IOException, ConstantPoolException
    {
        ModuleDescriptor descriptor = artifact.descriptor();
        Dependency.Finder finder = Dependencies.getAPIFinder(ACC_PROTECTED);
        Dependency.Filter filter =
            (Dependency d) -> !artifact.packages().contains(d.getTarget().getPackageName());
        Set<String> exports = descriptor.exports().stream()
                    .map(e -> e.pkg())
                    .sorted()
                    .collect(Collectors.toSet());
        Set<String> deps = new HashSet<>();
        try (ZipFile zf = new ZipFile(jmod.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements();) {
                ZipEntry ze = e.nextElement();
                String fn = ze.getName();
                String dir = fn.substring(0, fn.indexOf('/'));
                if (JmodInfo.CLASSES.equals(dir) && fn.endsWith(".class")) {
                    if (fn.equals("classes/module-info.class"))
                        continue;   // skip module-info.class
                    String pn = fn.substring(fn.indexOf('/')+1, fn.lastIndexOf('/')).replace('/', '.');
                    if (exports.contains(pn)) {
                        // analyze only exported APIs
                        try (InputStream in = zf.getInputStream(ze)) {
                            ClassFile cf = ClassFile.read(in);
                            if (cf.access_flags.is(AccessFlags.ACC_PUBLIC)) {
                                for (Dependency d : finder.findDependencies(cf)) {
                                    if (filter.accepts(d)) {
                                       ModuleDescriptor a = packageMap.get(d.getTarget().getPackageName());
                                       if (a == null) {
                                           throw new Error(d.getOrigin() + " -> " +
                                                           d.getTarget() + " not found");
                                       }
                                       deps.add(a.name());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return deps;
    }

    static Configuration resolve(Collection<String> roots) {
        return Configuration.resolve(ModuleArtifactFinder.installedModules(),
                                     Layer.emptyLayer(),
                                     ModuleArtifactFinder.nullFinder(),
                                     roots);
    }

}
