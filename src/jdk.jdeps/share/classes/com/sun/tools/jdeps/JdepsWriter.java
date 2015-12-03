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
package com.sun.tools.jdeps;

import static com.sun.tools.jdeps.Module.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static com.sun.tools.jdeps.Analyzer.Type.*;

public abstract class JdepsWriter {
    final Analyzer.Type type;
    final boolean showProfile;
    final boolean showModule;

    JdepsWriter(Analyzer.Type type, boolean showProfile, boolean showModule) {
        this.type = type;
        this.showProfile = showProfile;
        this.showModule = showModule;
    }

    abstract void generateOutput(Iterable<Archive> archives, Analyzer analyzer) throws IOException;

    public static class DotFileWriter extends JdepsWriter {
        final boolean showLabel;
        final Path outputDir;
        DotFileWriter(Path dir, Analyzer.Type type,
                      boolean showProfile, boolean showModule, boolean showLabel) {
            super(type, showProfile, showModule);
            this.showLabel = showLabel;
            this.outputDir = dir;
        }

        @Override
        void generateOutput(Iterable<Archive> archives, Analyzer analyzer) throws IOException {
            // output individual .dot file for each archive
            if (type != SUMMARY) {
                for (Archive archive : archives) {
                    if (analyzer.hasDependences(archive)) {
                        Path dotfile = outputDir.resolve(archive.getName() + ".dot");
                        try (PrintWriter pw = new PrintWriter(Files.newOutputStream(dotfile));
                             DotFileFormatter formatter = new DotFileFormatter(pw, archive)) {
                            analyzer.visitDependences(archive, formatter);
                        }
                    }
                }
            }
            // generate summary dot file
            generateSummaryDotFile(archives, analyzer);
        }

        private void generateSummaryDotFile(Iterable<Archive> archives, Analyzer analyzer) throws IOException {
            // If verbose mode (-v or -verbose option),
            // the summary.dot file shows package-level dependencies.
            Analyzer.Type summaryType =
                (type == PACKAGE || type == SUMMARY) ? SUMMARY : PACKAGE;
            Path summary = outputDir.resolve("summary.dot");
            try (PrintWriter sw = new PrintWriter(Files.newOutputStream(summary));
                 SummaryDotFile dotfile = new SummaryDotFile(sw, summaryType)) {
                for (Archive archive : archives) {
                    if (!archive.isEmpty()) {
                        if (type == PACKAGE || type == SUMMARY) {
                            if (showLabel) {
                                // build labels listing package-level dependencies
                                analyzer.visitDependences(archive, dotfile.labelBuilder(), PACKAGE);
                            }
                        }
                        analyzer.visitDependences(archive, dotfile, summaryType);
                    }
                }
            }
        }

        class DotFileFormatter implements Analyzer.Visitor, AutoCloseable {
            private final PrintWriter writer;
            private final String name;
            DotFileFormatter(PrintWriter writer, Archive archive) {
                this.writer = writer;
                this.name = archive.getName();
                writer.format("digraph \"%s\" {%n", name);
                writer.format("    // Path: %s%n", archive.getPathName());
            }

            @Override
            public void close() {
                writer.println("}");
            }

            @Override
            public void visitDependence(String origin, Archive originArchive,
                                        String target, Archive targetArchive) {
                String tag = toTag(target, targetArchive);
                writer.format("   %-50s -> \"%s\";%n",
                              String.format("\"%s\"", origin),
                              tag.isEmpty() ? target
                                            : String.format("%s (%s)", target, tag));
            }
        }

        class SummaryDotFile implements Analyzer.Visitor, AutoCloseable {
            private final PrintWriter writer;
            private final Analyzer.Type type;
            private final Map<Archive, Map<Archive,StringBuilder>> edges = new HashMap<>();
            SummaryDotFile(PrintWriter writer, Analyzer.Type type) {
                this.writer = writer;
                this.type = type;
                writer.format("digraph \"summary\" {%n");
            }

            @Override
            public void close() {
                writer.println("}");
            }

            @Override
            public void visitDependence(String origin, Archive originArchive,
                                        String target, Archive targetArchive) {
                String targetName = type == PACKAGE ? target : targetArchive.getName();
                if (isJDKModule(targetArchive)) {
                    Module m = (Module)targetArchive;
                    String n = showProfileOrModule(m);
                    if (!n.isEmpty()) {
                        targetName += " (" + n + ")";
                    }
                } else if (type == PACKAGE) {
                    targetName += " (" + targetArchive.getName() + ")";
                }
                String label = getLabel(originArchive, targetArchive);
                writer.format("  %-50s -> \"%s\"%s;%n",
                        String.format("\"%s\"", origin), targetName, label);
            }

            String getLabel(Archive origin, Archive target) {
                if (edges.isEmpty())
                    return "";

                StringBuilder label = edges.get(origin).get(target);
                return label == null ? "" : String.format(" [label=\"%s\",fontsize=9]", label.toString());
            }

            Analyzer.Visitor labelBuilder() {
                // show the package-level dependencies as labels in the dot graph
                return new Analyzer.Visitor() {
                    @Override
                    public void visitDependence(String origin, Archive originArchive,
                                                String target, Archive targetArchive)
                    {
                        edges.putIfAbsent(originArchive, new HashMap<>());
                        edges.get(originArchive).putIfAbsent(targetArchive, new StringBuilder());
                        StringBuilder sb = edges.get(originArchive).get(targetArchive);
                        String tag = toTag(target, targetArchive);
                        addLabel(sb, origin, target, tag);
                    }

                    void addLabel(StringBuilder label, String origin, String target, String tag) {
                        label.append(origin).append(" -> ").append(target);
                        if (!tag.isEmpty()) {
                            label.append(" (" + tag + ")");
                        }
                        label.append("\\n");
                    }
                };
            }
        }
    }

    static class SimpleWriter extends JdepsWriter {
        final PrintWriter writer;
        SimpleWriter(PrintWriter writer, Analyzer.Type type, boolean showProfile, boolean showModule) {
            super(type, showProfile, showModule);
            this.writer = writer;
        }

        @Override
        void generateOutput(Iterable<Archive> archives, Analyzer analyzer) {
            RawOutputFormatter depFormatter = new RawOutputFormatter(writer);
            RawSummaryFormatter summaryFormatter = new RawSummaryFormatter(writer);
            for (Archive archive : archives) {
                if (!archive.isEmpty()) {
                    analyzer.visitDependences(archive, summaryFormatter, SUMMARY);
                    if (analyzer.hasDependences(archive) && type != SUMMARY) {
                        analyzer.visitDependences(archive, depFormatter);
                    }
                }
            }
        }

        class RawOutputFormatter implements Analyzer.Visitor {
            private final PrintWriter writer;
            private String pkg = "";

            RawOutputFormatter(PrintWriter writer) {
                this.writer = writer;
            }

            @Override
            public void visitDependence(String origin, Archive originArchive,
                                        String target, Archive targetArchive) {
                String tag = toTag(target, targetArchive);
                if (type == VERBOSE) {
                    writer.format("   %-50s -> %-50s %s%n", origin, target, tag);
                } else {
                    if (!origin.equals(pkg)) {
                        pkg = origin;
                        writer.format("   %s (%s)%n", origin, originArchive.getName());
                    }
                    writer.format("      -> %-50s %s%n", target, tag);
                }
            }
        }

        class RawSummaryFormatter implements Analyzer.Visitor {
            private final PrintWriter writer;

            RawSummaryFormatter(PrintWriter writer) {
                this.writer = writer;
            }

            @Override
            public void visitDependence(String origin, Archive originArchive,
                                        String target, Archive targetArchive) {
                String targetName = targetArchive.getPathName();
                if (isJDKModule(targetArchive)) {
                    targetName = ((Module) targetArchive).name();
                }
                writer.format("%s -> %s", originArchive.getName(), targetName);
                if (showProfile && isJDKModule(targetArchive)) {
                    writer.format(" (%s)", target);
                }
                writer.format("%n");
            }
        }
    }

    /**
     * If the given archive is JDK archive, this method returns the profile name
     * only if -profile option is specified; it accesses a private JDK API and
     * the returned value will have "JDK internal API" prefix
     *
     * For non-JDK archives, this method returns the file name of the archive.
     */
    String toTag(String name, Archive source) {
        if (!isJDKModule(source)) {
            return source.getName();
        }

        Module module = (Module)source;
        boolean isExported = false;
        if (type == CLASS || type == VERBOSE) {
            isExported = module.isExported(name);
        } else {
            isExported = module.isExportedPackage(name);
        }
        if (isExported) {
            // exported API
            return showProfileOrModule(module);
        } else {
            return "JDK internal API (" + source.getName() + ")";
        }
    }

    String showProfileOrModule(Module m) {
        String tag = "";
        if (showProfile) {
            Profile p = Profile.getProfile(m);
            if (p != null) {
                tag = p.profileName();
            }
        } else if (showModule) {
            tag = m.name();
        }
        return tag;
    }

    Profile getProfile(String name) {
        String pn = name;
        if (type == CLASS || type == VERBOSE) {
            int i = name.lastIndexOf('.');
            pn = i > 0 ? name.substring(0, i) : "";
        }
        return Profile.getProfile(pn);
    }

}
