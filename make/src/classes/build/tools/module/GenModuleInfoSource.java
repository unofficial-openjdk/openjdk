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
package build.tools.module;


import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A build tool to extend the module-info.java in the source tree for
 * platform-specific exports, opens, uses, and provides and write to
 * the specified output file.
 *
 * Injecting platform-specific requires is not supported.
 *
 * The extra exports, opens, uses, provides can be specified in module-info.java.extra
 * files and GenModuleInfoSource will be invoked for each module that has
 * module-info.java.extra in the source directory.
 */
public class GenModuleInfoSource {
    private final static String USAGE =
        "Usage: GenModuleInfoSource -o <output file> \n" +
        "  --source-file <module-info-java>\n" +
        "  --modules <module-name>[,<module-name>...]\n" +
        "  <module-info.java.extra> ...\n";

    public static void main(String... args) throws Exception {
        Path outfile = null;
        Path moduleInfoJava = null;
        Set<String> modules = Collections.emptySet();
        List<Path> extras = new ArrayList<>();
        // validate input arguments
        for (int i = 0; i < args.length; i++){
            String option = args[i];
            String arg = i+1 < args.length ? args[i+1] : null;
            switch (option) {
                case "-o":
                    outfile = Paths.get(arg);
                    i++;
                    break;
                case "--source-file":
                    moduleInfoJava = Paths.get(arg);
                    if (Files.notExists(moduleInfoJava)) {
                        throw new IllegalArgumentException(moduleInfoJava + " not exist");
                    }
                    i++;
                    break;
                case "--modules":
                    modules = Arrays.stream(arg.split(","))
                                    .collect(Collectors.toSet());
                    i++;
                    break;
                default:
                    Path file = Paths.get(option);
                    if (Files.notExists(file)) {
                        throw new IllegalArgumentException(file + " not exist");
                    }
                    extras.add(file);
            }
        }

        if (moduleInfoJava == null || outfile == null ||
                modules.isEmpty() || extras.isEmpty()) {
            System.err.println(USAGE);
            System.exit(-1);
        }

        GenModuleInfoSource genModuleInfo =
            new GenModuleInfoSource(moduleInfoJava, modules);
        for (Path extraFile : extras) {
            genModuleInfo.addExtra(extraFile);
        }

        // generate new module-info.java
        genModuleInfo.generate(outfile);
    }

    final Path sourceFile;
    final ModuleInfo extraFiles;
    final Set<String> modules;
    final ModuleInfo moduleInfo;
    GenModuleInfoSource(Path sourceFile, Set<String> modules)
        throws IOException
    {
        this.sourceFile = sourceFile;
        this.extraFiles = new ModuleInfo();
        this.modules = modules;
        this.moduleInfo = new ModuleInfo();
        this.moduleInfo.parse(sourceFile);
    }

    void addExtra(Path extra) throws IOException {
        extraFiles.parse(extra);
    }

    void generate(Path output) throws IOException {
        // merge with module-info.java.extra
        merge();

        List<String> lines = Files.readAllLines(sourceFile);
        try (BufferedWriter bw = Files.newBufferedWriter(output);
             PrintWriter writer = new PrintWriter(bw)) {
            // write the copyright header and lines up to module declaration
            for (String l : lines) {
                writer.println(l);
                if (l.trim().startsWith("module ")) {
                    break;
                }
            }

            // requires
            for (String l : lines) {
                if (l.trim().startsWith("requires"))
                    writer.println(l);
            }

            // write exports, opens, uses,
            moduleInfo.print(writer);

            // close
            writer.println("}");
        }
    }

    private void merge() {
        // exports
        extraFiles.exports.keySet()
            .stream()
            .filter(pn -> moduleInfo.exports.containsKey(pn))
            .forEach(pn -> mergeExportsOrOpens(pn,  moduleInfo.exports.get(pn),
                                               extraFiles.exports.get(pn)));
        extraFiles.exports.keySet()
            .stream()
            .filter(pn -> !moduleInfo.exports.containsKey(pn))
            .forEach(pn -> moduleInfo.exports.put(pn, extraFiles.exports.get(pn)));

        // opens
        extraFiles.opens.keySet()
            .stream()
            .filter(pn -> moduleInfo.opens.containsKey(pn))
            .forEach(pn -> mergeExportsOrOpens(pn,  moduleInfo.opens.get(pn),
                                               extraFiles.opens.get(pn)));
        extraFiles.opens.keySet()
            .stream()
            .filter(pn -> !moduleInfo.opens.containsKey(pn))
            .forEach(pn -> moduleInfo.opens.put(pn, extraFiles.exports.get(pn)));

        // provides
        extraFiles.provides.keySet()
            .stream()
            .filter(service -> moduleInfo.provides.containsKey(service))
            .forEach(service -> mergeProvides(service, extraFiles.provides.get(service)));
        extraFiles.provides.keySet()
            .stream()
            .filter(service -> !moduleInfo.provides.containsKey(service))
            .forEach(service -> moduleInfo.provides.put(service, extraFiles.provides.get(service)));

        // uses
        extraFiles.uses.keySet()
            .stream()
            .filter(service -> !moduleInfo.uses.containsKey(service))
            .forEach(service -> moduleInfo.uses.put(service, extraFiles.uses.get(service)));
    }

    private void mergeExportsOrOpens(String pn, Statement statement, Statement extra) {
        if (statement.isUnqualified() && extra.isQualified()) {
            throw new RuntimeException("can't add qualified exports to " +
                "unqualified exports " + pn);
        }

        Set<String> mods = extra.targets.stream()
            .filter(mn -> statement.targets.contains(mn))
            .collect(Collectors.toSet());
        if (mods.size() > 0) {
            throw new RuntimeException("qualified exports " + pn + " to " +
                mods.toString() + " already declared in " + sourceFile);
        }

        // add qualified exports or opens to known modules only
        extra.targets.stream()
             .filter(mn -> modules.contains(mn))
             .forEach(mn -> statement.addTarget(mn));
    }


    private void mergeProvides(String service, Statement extra) {
        Statement provides = moduleInfo.provides.get(service);

        Set<String> mods = extra.targets.stream()
            .filter(mn -> provides.targets.contains(mn))
            .collect(Collectors.toSet());

        if (mods.size() > 0) {
            throw new RuntimeException("qualified exports " + service + " to " +
                mods.toString() + " already declared in " + sourceFile);
        }

        extra.targets.stream()
             .forEach(mn -> provides.addTarget(mn));
    }


    class ModuleInfo {
        private final Map<String, Statement> exports = new HashMap<>();
        private final Map<String, Statement> opens = new HashMap<>();
        private final Map<String, Statement> uses = new HashMap<>();
        private final Map<String, Statement> provides = new HashMap<>();

        Statement getStatement(String directive, String name) {
            switch (directive) {
                case "exports":
                    if (moduleInfo.exports.containsKey(name) &&
                        moduleInfo.exports.get(name).isUnqualified()) {
                        throw new IllegalArgumentException(sourceFile + " already has "
                            + directive + " " + name);
                    }
                    return exports.computeIfAbsent(name,
                        _n -> new Statement("exports", "to", name));
                case "opens":
                    if (moduleInfo.opens.containsKey(name) &&
                        moduleInfo.opens.get(name).isUnqualified()) {
                        throw new IllegalArgumentException(sourceFile + " already has "
                            + directive + " " + name);
                    }

                    if (moduleInfo.opens.containsKey(name)) {
                        throw new IllegalArgumentException(sourceFile + " already has "
                            + directive + " " + name);
                    }
                    return opens.computeIfAbsent(name,
                        _n -> new Statement("opens", "to", name));
                case "uses":
                    return uses.computeIfAbsent(name,
                        _n -> new Statement("uses", "", name));
                case "provides":
                    return provides.computeIfAbsent(name,
                        _n -> new Statement("provides", "with", name, true));
                default:
                    throw new IllegalArgumentException(directive);
            }

        }

        void print(PrintWriter writer) {
            // print unqualified exports
            exports.entrySet().stream()
                .filter(e -> e.getValue().targets.isEmpty())
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> writer.println(e.getValue()));

            exports.entrySet().stream()
                .filter(e -> !e.getValue().targets.isEmpty())
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> writer.println(e.getValue()));

            opens.entrySet().stream()
                .filter(e -> e.getValue().targets.isEmpty())
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> writer.println(e.getValue()));

            opens.entrySet().stream()
                .filter(e -> !e.getValue().targets.isEmpty())
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> writer.println(e.getValue()));

            writer.println();
            uses.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> writer.println(e.getValue()));
            provides.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> writer.println(e.getValue()));
        }

        private void parse(Path sourcefile) throws IOException {
            List<String> lines = Files.readAllLines(sourcefile);
            Statement statement = null;
            boolean hasTargets = false;

            for (int lineNumber = 0; lineNumber < lines.size(); ) {
                String l = lines.get(lineNumber).trim();
                int index = 0;

                if (l.isEmpty()) {
                    lineNumber++;
                    continue;
                }

                // comment block starts
                if (l.startsWith("/*")) {
                    while (l.indexOf("*/") == -1) { // end comment block
                        l = lines.get(++lineNumber).trim();
                    }
                    index = l.indexOf("*/") + 2;
                    if (index >= l.length()) {
                        lineNumber++;
                        continue;
                    } else {
                        // rest of the line
                        l = l.substring(index, l.length());
                        index = 0;
                    }
                }

                // skip comment and annotations
                if (l.startsWith("//") || l.startsWith("@")) {
                    lineNumber++;
                    continue;
                }

                while (index < l.length()) {
                    String[] s = l.trim().split("\\s+");
                    String keyword = s[0].trim();
                    String name = s.length > 1 ? s[1].trim() : null;
                    // System.out.format("%d: %s index=%d len=%d%n", lineNumber, l, index, l.length());
                    switch (keyword) {
                        case "module":
                        case "requires":
                        case "}":
                            index = l.length();  // skip to the end
                            continue;

                        case "exports":
                        case "opens":
                        case "provides":
                        case "uses":
                            // assume name immediately after exports, opens, provides, uses
                            statement = getStatement(keyword, name);
                            hasTargets = false;

                            int i = l.indexOf(name, index) + name.length() + 1;
                            l = i < l.length() ? l.substring(i, l.length()).trim() : "";
                            index = 0;

                            if (s.length >= 3) {
                                if (!s[2].trim().equals(statement.qualifier)) {
                                    throw new RuntimeException(sourcefile + ", line " +
                                        lineNumber + ", is malformed: " + s[2]);
                                }
                            }


                            break;

                        case "to":
                        case "with":
                            if (statement == null) {
                                throw new RuntimeException(sourcefile + ", line " +
                                    lineNumber + ", is malformed");
                            }

                            hasTargets = true;
                            String qualifier = statement.qualifier;
                            i = l.indexOf(qualifier, index) + qualifier.length() + 1;
                            l = i < l.length() ? l.substring(i, l.length()).trim() : "";
                            index = 0;
                            break;
                    }

                    // comment block starts
                    if (l.startsWith("/*")) {
                        while (l.indexOf("*/") == -1) { // end comment block
                            l = lines.get(++lineNumber).trim();
                        }
                        index = l.indexOf("*/") + 2;
                        if (index >= l.length()) {
                            continue;
                        } else {
                            // rest of the line
                            l = l.substring(index, l.length());
                            index = 0;
                        }
                    }

                    if (l.startsWith("//")) {
                        index = l.length();
                        continue;
                    }

                    if (!hasTargets) {
                        continue;
                    }

                    // parse the qualifiers
                    int j = l.indexOf(';', index);
                    Statement stmt = statement;
                    String rhs = j != -1 ? l.substring(index, j) : l;
                    index += rhs.length();
                    // System.out.format("rhs: index=%d %s line: %s%n", index, rhs, l);
                    String[] targets = rhs.split(",");
                    for (String t : targets) {
                        String n = t.trim();
                        if (n.length() > 0)
                            stmt.addTarget(n);
                    }
                    if (j != -1) {
                        statement = null;
                        hasTargets = false;
                        index = j + 1;
                    }
                    l = index < l.length() ? l.substring(index, l.length()).trim() : "";
                    index = 0;
                }

                lineNumber++;
            }
        }
    }

    static class Statement {
        final String directive;
        final String qualifier;
        final String name;
        final Set<String> targets = new LinkedHashSet<>();
        final boolean ordered;

        Statement(String directive, String qualifier, String name) {
            this(directive, qualifier, name, false);
        }

        Statement(String directive, String qualifier, String name, boolean ordered) {
            this.directive = directive;
            this.qualifier = qualifier;
            this.name = name;
            this.ordered = ordered;
        }

        Statement addTarget(String mn) {
            targets.add(mn);
            return this;
        }

        boolean isQualified() {
            return targets.size() > 0;
        }

        boolean isUnqualified() {
            return targets.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("    ");
            sb.append(directive).append(" ").append(name);
            if (targets.size() == 1) {
                sb.append(" ").append(qualifier)
                    .append(orderedTargets()
                        .collect(Collectors.joining(",", " ", ";")));
            } else if (!targets.isEmpty()) {
                sb.append(" ").append(qualifier)
                    .append(orderedTargets()
                        .map(target -> String.format("        %s", target))
                        .collect(Collectors.joining(",\n", "\n", ";")));
            }
            return sb.toString();
        }

        public Stream<String> orderedTargets() {
            return ordered ? targets.stream()
                : targets.stream().sorted();
        }
    }
}
