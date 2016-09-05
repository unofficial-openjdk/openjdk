/*
 * Copyright (c) 2015, 2016, Oracle and/or its affiliates. All rights reserved.
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
        "  --exports  <package-name>\n" +
        "  --exports  <package-name>[/<module-name>]\n" +
        "  --exports-private  <package-name>\n" +
        "  --exports-private  <package-name>[/<module-name>]\n" +
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
                if (option.equals("--exports") ||
                    option.equals("--exports-private")) {
                    Set<Exports.Modifier> modifiers = toModifiers(option);
                    int index = arg.indexOf('/');
                    if (index > 0) {
                        String pn = arg.substring(0, index);
                        String mn = arg.substring(index + 1, arg.length());
                        genModuleInfo.exportTo(modifiers, pn, mn);
                    } else {
                        genModuleInfo.export(modifiers, arg);
                    }
                } else if (option.equals("--uses")) {
                    genModuleInfo.use(arg);
                } else if (option.equals("--provides")) {
                        int index = arg.indexOf('/');
                        if (index <= 0) {
                            throw new IllegalArgumentException("invalid --provide argument: " + arg);
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

    static class Exports {
        public static enum Modifier {
            DYNAMIC,
            PRIVATE,
        }

        final Set<Modifier> mods;
        final String source;
        final Set<String> targets;  // empty if unqualified export

        Exports(String source) {
            this(Collections.emptySet(), source, Collections.emptySet());
        }

        Exports(Set<Modifier> ms, String source) {
            this(ms, source, Collections.emptySet());
        }

        Exports(Set<Modifier> ms, String source, Set<String> targets) {
            this.mods = ms;
            this.source = source;
            this.targets = targets;
        }

        String source() {
            return source;
        }

        Set<String> targets() {
            return targets;
        }

        boolean isQualified() {
            return !targets.isEmpty();
        }
    }


    static Set<Exports.Modifier> toModifiers(String option) {
        switch (option) {
            case "--exports":
                return Collections.emptySet();
            case "--exports-private":
                return Collections.singleton(Exports.Modifier.PRIVATE);
            default:
                throw new IllegalArgumentException(option);
        }
    }

    private final Map<String, Set<Exports>> exports = new HashMap<>();
    private final Map<String, Set<Exports>> exportsTo = new HashMap<>();
    private final Set<String> uses = new HashSet<>();
    private final Map<String, Set<String>> provides = new HashMap<>();
    GenModuleInfoSource() {
    }

    private void export(Set<Exports.Modifier> mods, String p) {
        Objects.requireNonNull(p);
        Set<Exports> exps = exports.computeIfAbsent(p, _k -> new HashSet<>());
        Optional<Exports> oe = exps.stream()
            .filter(e -> e.mods.equals(mods))
            .findFirst();
        if (oe.isPresent())
            throw new IllegalArgumentException("duplicate exports " +
                        toString(mods, p));
         exps.add(new Exports(mods, p));
    }

    private void exportTo(Set<Exports.Modifier> mods, String p, String mn) {
        Objects.requireNonNull(p);
        Objects.requireNonNull(mn);
        Set<Exports> exps = exportsTo.computeIfAbsent(p, _k -> new HashSet<>());
        Optional<Exports> oe = exps.stream()
            .filter(e -> e.mods.equals(mods))
            .findFirst();
        if (oe.isPresent()) {
            oe.get().targets.add(mn);
        } else {
            Set<String> targets = new HashSet<>();
            targets.add(mn);
            exps.add(new Exports(mods, p, targets));
        }
    }

    private void use(String service) {
        uses.add(service);
    }

    private void provide(String s, String impl) {
        provides.computeIfAbsent(s, _k -> new HashSet<>()).add(impl);
    }

    private void generate(Path sourcefile, Path outfile) throws IOException {
        Path parent = outfile.getParent();
        if (parent != null)
            Files.createDirectories(parent);

        List<String> lines = Files.readAllLines(sourcefile);
        try (BufferedWriter bw = Files.newBufferedWriter(outfile);
             PrintWriter writer = new PrintWriter(bw)) {
            int lineNumber = 0;
            String pn = null;
            Set<Exports.Modifier> mods = null;
            for (String l : lines) {
                lineNumber++;
                String[] s = l.trim().split("\\s+");
                String keyword = s[0].trim();
                int nextIndex = keyword.length();
                int n = l.length();
                switch (keyword) {
                    case "exports":
                        boolean inExportsTo = false;
                        // exports <package-name> [to <target-module>]
                        // exports private <package-name> [to <target-module>]

                        String token = s[1].trim();
                        int nextTokenPos = 2;
                        pn = null;
                        mods = Collections.emptySet();

                        if (token.equals("private")) {
                            mods = Collections.singleton(Exports.Modifier.valueOf(token.toUpperCase()));
                            pn = s.length >= 3 ? s[2].trim() : null;
                            nextTokenPos = 3;
                        } else {
                            pn = token;
                        }

                        if (s.length > nextTokenPos) {
                            nextIndex = l.indexOf(pn, nextIndex) + pn.length();
                            if (s[nextTokenPos].trim().equals("to")) {
                                inExportsTo = true;
                                n = l.indexOf("to", nextIndex) + "to".length();
                            } else {
                                throw new RuntimeException(sourcefile + ", line " +
                                    lineNumber + ", is malformed: " + s[2]);
                            }
                        }

                        // inject the extra targets after "to"
                        if (inExportsTo) {
                            writer.println(injectExportTargets(mods, pn, l, n));
                        } else {
                            writer.println(l);
                        }
                        break;
                    case "to":
                        if (pn == null) {
                            throw new RuntimeException(sourcefile + ", line " +
                                lineNumber + ", is malformed");
                        }
                        n = l.indexOf("to", nextIndex) + "to".length();
                        writer.println(injectExportTargets(mods, pn, l, n));
                        break;
                    case "}":
                        doAugments(writer);
                        // fall through
                    default:
                        writer.println(l);
                        // reset exports
                        pn = null;
                }
            }
        }
    }

    private String injectExportTargets(Set<Exports.Modifier> mods, String pn,
                                       String exports, int pos) {
        if (exportsTo.containsKey(pn)) {
            Optional<Exports> oe = exportsTo.get(pn).stream()
                .filter(e -> e.mods.equals(mods))
                .findFirst();
            if (oe.isPresent()) {
                Exports exp = oe.get();
                exportsTo.get(pn).remove(exp);
                StringBuilder sb = new StringBuilder();
                // inject the extra targets after the given pos
                sb.append(exports.substring(0, pos))
                    .append("\n\t")
                    .append(exp.targets.stream()
                        .collect(Collectors.joining(", ", "", ",")))
                    .append(" /* injected */");
                if (pos < exports.length()) {
                    // print the remaining statement followed "to"
                    sb.append("\n\t")
                      .append(exports.substring(pos + 1, exports.length()));
                }
                return sb.toString();
            }
        }
        return exports;
    }

    private void doAugments(PrintWriter writer) {
        long exps = exports.values().stream()
                           .flatMap(Set::stream).count() +
                    exportsTo.values().stream()
                           .flatMap(Set::stream).count();
        if ((exps + uses.size() + provides.size()) == 0)
            return;

        writer.println();
        writer.println("    // augmented from module-info.java.extra");
        exports.values().stream()
            .flatMap(Set::stream)
            .sorted(Comparator.comparing(Exports::source))
            .forEach(e -> writer.format("    exports %s;%n",
                                        toString(e.mods, e.source)));
        // remaining injected qualified exports
        exportsTo.values().stream()
            .flatMap(Set::stream)
            .sorted(Comparator.comparing(Exports::source))
            .map(e -> String.format("    exports %s to%n%s;",
                                    toString(e.mods, e.source),
                                    e.targets().stream().sorted()
                                        .map(mn -> String.format("        %s", mn))
                                        .collect(Collectors.joining(",\n"))))
            .forEach(writer::println);
        uses.stream().sorted()
            .forEach(s -> writer.format("    uses %s;%n", s));
        provides.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .flatMap(e -> e.getValue().stream().sorted()
                           .map(impl -> String.format("    provides %s with %s;",
                                                      e.getKey(), impl)))
            .forEach(writer::println);
    }

    static <T> Stream<String> toStringStream(Set<T> s) {
        return s.stream().map(e -> e.toString().toLowerCase());
    }

    static <M> String toString(Set<M> mods, String what) {
        return (Stream.concat(toStringStream(mods), Stream.of(what)))
            .collect(Collectors.joining(" "));
    }

}
