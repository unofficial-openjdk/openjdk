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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A build tool to extend the module-info.java in the source tree for
 * platform-specific exports, uses, and provides and write to the specified
 * output file. Injecting platform-specific requires is not supported.
 *
 * The extra exports, uses, provides can be specified in module-info.java.extra
 * files and GenModuleInfoSource will be invoked for each module that has
 * module-info.java.extra in the source directory.
 */
public class GenModuleInfoSource {
    private final static String USAGE =
        "Usage: GenModuleInfoSource [option] -o <output file> <module-info-java>\n" +
        "Options are:\n" +
        "  --exports  <package-name>[/<target-modules>]\n" +
        "  --opens    <package-name>[/<target-modules>]\n" +
        "  --uses     <service>\n" +
        "  --provides <service>/<provider-impl-classname>\n";

    public static void main(String... args) throws Exception {
        Path outfile = null;
        Path moduleInfoJava = null;
        GenModuleInfoSource genModuleInfo = new GenModuleInfoSource();

        // validate input arguments
        for (int i = 0; i < args.length; i++){
            String option = args[i];
            if (option.startsWith("-")) {
                String arg = args[++i];
                if (option.equals("--exports")) {
                    int index = arg.indexOf('/');
                    if (index > 0) {
                        String pn = arg.substring(0, index);
                        Set<String> targets =
                            targets(arg.substring(index + 1, arg.length()));
                        if (targets.isEmpty()) {
                            throw new IllegalArgumentException("empty target: " +
                                option + " " + arg);
                        }
                        genModuleInfo.exportsTo(pn, targets);
                    } else {
                        genModuleInfo.exports(arg);
                    }
                } else if (option.equals("--opens")) {
                    int index = arg.indexOf('/');
                    if (index > 0) {
                        String pn = arg.substring(0, index);
                        Set<String> targets =
                            targets(arg.substring(index + 1, arg.length()));
                        if (targets.isEmpty()) {
                            throw new IllegalArgumentException("empty target: " +
                                option + " " + arg);
                        }
                        genModuleInfo.opensTo(pn, targets);
                    } else {
                        genModuleInfo.opens(arg);
                    }
                } else if (option.equals("--uses")) {
                    genModuleInfo.use(arg);
                } else if (option.equals("--provides")) {
                        int index = arg.indexOf('/');
                        if (index <= 0) {
                            throw new IllegalArgumentException("invalid -provide argument: " + arg);
                        }
                        String service = arg.substring(0, index);
                        String impl = arg.substring(index + 1, arg.length());
                        genModuleInfo.provide(service, impl);
                } else if (option.equals("-o")) {
                    outfile = Paths.get(arg);
                } else {
                    throw new IllegalArgumentException("invalid option: " + option);
                }
            } else if (moduleInfoJava != null) {
                throw new IllegalArgumentException("more than one module-info.java");
            } else {
                moduleInfoJava = Paths.get(option);
                if (Files.notExists(moduleInfoJava)) {
                    throw new IllegalArgumentException(option + " not exist");
                }
            }
        }

        if (moduleInfoJava == null || outfile == null) {
            System.err.println(USAGE);
            System.exit(-1);
        }

        // generate new module-info.java
        genModuleInfo.generate(moduleInfoJava, outfile);
    }


    private static Set<String> targets(String rhs) {
        return Arrays.stream(rhs.split(","))
                     .map(String::trim)
                     .filter(mn -> mn.length() > 0)
                     .collect(Collectors.toSet());
    }

    private final ExportsOrOpens exports;
    private final ExportsOrOpens opens;

    private final Set<String> uses = new HashSet<>();
    private final Map<String, Set<String>> provides = new HashMap<>();
    GenModuleInfoSource() {
        this.exports = new ExportsOrOpens("exports");
        this.opens = new ExportsOrOpens("opens");

    }

    private void exports(String p) {
        exports.add(p);
    }

    private void exportsTo(String p, Set<String> targets) {
        exports.add(p, targets);
    }

    private void opens(String p) {
        opens.add(p);
    }

    private void opensTo(String p, Set<String> targets) {
        opens.add(p, targets);
    }

    private void use(String service) {
        uses.add(service);
    }

    private void provide(String s, String impl) {
        // keep the order
        provides.computeIfAbsent(s, _k -> new LinkedHashSet<>()).add(impl);
    }

    private void doAugments(PrintWriter writer) {
        if (exports.isEmpty() && opens.isEmpty() &&
            (uses.size() + provides.size()) == 0)
            return;

        writer.println();
        writer.println("    // augmented from module-info.java.extra");

        exports.writeTo(writer);
        opens.writeTo(writer);

        uses.stream().sorted()
            .forEach(s -> writer.format("    uses %s;%n", s));

        toStream(provides, "provides", "with")
            .forEach(writer::println);
    }

    static class ExportsOrOpens {
        final Set<String> unqualified = new HashSet<>();
        final Map<String, Set<String>> qualified = new HashMap<>();
        final String directive;
        ExportsOrOpens(String name) {
            this.directive = name;
        }
        void add(String pn) {
            Objects.requireNonNull(pn);
            if (unqualified.contains(pn) || qualified.containsKey(pn)) {
                throw new RuntimeException("duplicated " +
                    directive + ": " + pn);
            }
            unqualified.add(pn);
        }

        void add(String pn, Set<String> targets) {
            Objects.requireNonNull(pn);
            if (unqualified.contains(pn)) {
                throw new RuntimeException("unqualified " +
                    directive + " already exists: " + pn);
            }
            qualified.computeIfAbsent(pn, _k -> new HashSet<>()).addAll(targets);
        }

        Set<String> removeIfPresent(String pn) {
            return qualified.remove(pn);
        }

        boolean isEmpty() {
            return unqualified.size() + qualified.size() == 0;
        }

        void writeTo(PrintWriter writer) {
            unqualified.stream()
                .sorted()
                .forEach(e -> writer.format("    %s %s;%n", directive, e));

            // remaining injected qualified exports
            toStream(qualified, directive, "to")
                .forEach(writer::println);
        }
    }


    private void generate(Path sourcefile, Path outfile) throws IOException {
        Path parent = outfile.getParent();
        if (parent != null)
            Files.createDirectories(parent);

        List<String> lines = Files.readAllLines(sourcefile);
        try (BufferedWriter bw = Files.newBufferedWriter(outfile);
             PrintWriter writer = new PrintWriter(bw)) {
            int lineNumber = 0;
            boolean inExportsTo = false;
            boolean inOpensTo = false;
            String name = null;
            ExportsOrOpens exportsOrOpens = null;
            for (String l : lines) {
                lineNumber++;
                String[] s = l.trim().split("\\s+");
                String keyword = s[0].trim();
                int nextIndex = keyword.length();
                int n = l.length();
                switch (keyword) {
                    case "exports":
                        // assume package name immediately after exports
                        name = s[1].trim();
                        exportsOrOpens = exports;
                        if (s.length >= 3) {
                            nextIndex = l.indexOf(name, nextIndex) + name.length();
                            if (s[2].trim().equals("to")) {
                                inExportsTo = true;
                                n = l.indexOf("to", nextIndex) + "to".length();
                            } else {
                                throw new RuntimeException(sourcefile + ", line " +
                                    lineNumber + ", is malformed: " + s[2]);
                            }
                        }

                        // inject the extra targets after "to"
                        if (inExportsTo) {
                            Set<String> extras = exports.removeIfPresent(name);
                            writer.println(injectTargets(l, n, extras));
                        } else {
                            writer.println(l);
                        }
                        break;

                    case "opens":
                        // assume package name immediately after opens
                        name = s[1].trim();
                        exportsOrOpens = opens;
                        if (s.length >= 3) {
                            nextIndex = l.indexOf(name, nextIndex) + name.length();
                            if (s[2].trim().equals("to")) {
                                inOpensTo = true;
                                n = l.indexOf("to", nextIndex) + "to".length();
                            } else {
                                throw new RuntimeException(sourcefile + ", line " +
                                    lineNumber + ", is malformed: " + s[2]);
                            }
                        }

                        // inject the extra targets after "to"
                        if (inOpensTo) {
                            Set<String> extras = opens.removeIfPresent(name);
                            writer.println(injectTargets(l, n, extras));
                        } else {
                            writer.println(l);
                        }
                        break;

                    case "to":
                        if (name == null) {
                            throw new RuntimeException(sourcefile + ", line " +
                                lineNumber + ", is malformed");
                        }
                        n = l.indexOf("to", nextIndex) + "to".length();
                        writer.println(injectTargets(l, n, exportsOrOpens.removeIfPresent(name)));
                        break;

                    case "provides":
                        boolean hasWith = false;
                        // assume service type name immediately after provides
                        name = s[1].trim();
                        if (s.length >= 3) {
                            nextIndex = l.indexOf(name, nextIndex) + name.length();
                            if (s[2].trim().equals("with")) {
                                hasWith = true;
                                n = l.indexOf("with", nextIndex) + "with".length();
                            } else {
                                throw new RuntimeException(sourcefile + ", line " +
                                    lineNumber + ", is malformed: " + s[2]);
                            }
                        }

                        // inject the extra provider classes after "with"
                        if (hasWith) {
                            Set<String> extras = provides.remove(name);
                            writer.println(injectTargets(l, n, extras));
                        } else {
                            writer.println(l);
                        }
                        break;

                    case "with":
                        if (name == null) {
                            throw new RuntimeException(sourcefile + ", line " +
                                lineNumber + ", is malformed");
                        }
                        n = l.indexOf("with", nextIndex) + "with".length();
                        writer.println(injectTargets(l, n, provides.remove(name)));
                        break;

                    case "}":
                        doAugments(writer);
                        // fall through
                    default:
                        writer.println(l);
                        // reset
                        name = null;
                        inExportsTo = inOpensTo = false;
                        exportsOrOpens = null;
                }
            }
        }
    }


    /*
     * Injects the targets after the given position
     */
    private String injectTargets(String line, int pos, Set<String> extras) {
        if (extras != null) {
            StringBuilder sb = new StringBuilder();
            // inject the extra targets after the given pos
            sb.append(line.substring(0, pos))
              .append("\n\t")
              .append(extras.stream()
                            .collect(Collectors.joining(",", "", ",")))
              .append(" /* injected */");
            if (pos < line.length()) {
                // print the remaining statement followed "to"
                sb.append("\n\t")
                  .append(line.substring(pos+1, line.length()));
            }
            return sb.toString();
        } else {
            return line;
        }
    }

    private static Stream<String> toStream(Map<String, Set<String>> map,
                                           String directive, String verb) {
        return map.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> String.format("    %s %s %s%n%s;",
                                    directive, e.getKey(), verb,
                                    e.getValue().stream().sorted()
                                        .map(target -> String.format("        %s", target))
                                        .collect(Collectors.joining(",\n"))));
    }
}
