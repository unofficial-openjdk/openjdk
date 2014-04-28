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

package build.tools.generatemodulesxml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.xml.stream.*;
import javax.xml.stream.events.XMLEvent;

/**
 * This tool is used to generate com/sun/tools/jdeps/resources/modules.xml
 * for jdeps to analyze dependencies and enforce module boundaries.
 *
 * Run GenerateModulesXml <output file> build/modules
 * on jdk-module-image to generate modules.xml to be integrated
 * in JDK 9 jdk/make/data/checkdeps/modules.xml that includes
 * dependences, exports, and qualified-exports.
 *
 * For JDK 9 legacy build, this takes jdk/make/data/checkdeps/modules.xml
 * and augment it with module membership and output a resource file
 * com/sun/tools/jdeps/resources/modules.xml
 */
public final class GenerateModulesXml {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: GenerateModulesXml [-xml modules.xml] <output file> build/modules");
            System.exit(-1);
        }
        Path infile = null;
        int i=0;
        if (args[0].equals("-xml")) {
            infile = Paths.get(args[1]);
            i = 2;
            if (Files.notExists(infile)) {
                System.err.println(infile + " doesn't exist");
                System.exit(1);
            }
        }
        if (i != 0 && args.length != 4) {
            System.err.println("Usage: GenerateModulesXml [-xml modules.xml] <output file> build/modules");
            System.exit(-1);
        }

        Path outfile = Paths.get(args[i++]);
        Path modulepath = Paths.get(args[i++]);

        if (!Files.isDirectory(modulepath)) {
            System.err.println(modulepath + " is not a directory");
            System.exit(1);
        }
        GenerateModulesXml gentool =
            new GenerateModulesXml(modulepath);
        Set<Module> modules;
        if (infile != null) {
            try (InputStream in = Files.newInputStream(infile)) {
                modules = gentool.load(in);
            }
        } else {
            modules = JigsawModules.load(gentool);
        }

        Files.createDirectories(outfile.getParent());
        gentool.writeXML(modules, outfile);
    }

    final Path modulepath;
    public GenerateModulesXml(Path modulepath) {
        this.modulepath = modulepath;
    }

    private static final String MODULES    = "modules";
    private static final String MODULE     = "module";
    private static final String NAME       = "name";
    private static final String DEPENDENCE = "dependence";
    private static final String EXPORT     = "export";
    private static final String EXPORT_TO  = "export-to";
    private static final String PERMIT     = "permit";
    private static final String INCLUDE    = "include";
    private Set<Module> load(InputStream in) throws XMLStreamException, IOException {
        Set<Module> modules = new HashSet<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        XMLEventReader stream = factory.createXMLEventReader(in);
        Module.Builder mb = null;
        String modulename = null;
        String pkg = null;
        Set<String> permits = new HashSet<>();
        while (stream.hasNext()) {
            XMLEvent event = stream.nextEvent();
            if (event.isStartElement()) {
                String startTag = event.asStartElement().getName().getLocalPart();
                switch (startTag) {
                    case MODULES:
                        break;
                    case MODULE:
                        if (mb != null) {
                            throw new RuntimeException("end tag for module is missing");
                        }
                        modulename = getNextTag(stream, NAME);
                        mb = new Module.Builder();
                        mb.name(modulename);
                        break;
                    case NAME:
                        throw new RuntimeException(event.toString());
                    case DEPENDENCE:
                        mb.require(getData(stream));
                        break;
                    case EXPORT:
                        mb.export(getData(stream));
                        break;
                    case INCLUDE:
                        throw new RuntimeException("unexpected " + event);
                    case EXPORT_TO:
                        pkg = getNextTag(stream, NAME);
                        break;
                    case PERMIT:
                        permits.add(getData(stream));
                        break;
                    default:
                }
            } else if (event.isEndElement()) {
                String endTag = event.asEndElement().getName().getLocalPart();
                switch (endTag) {
                    case MODULE:
                        buildIncludes(mb, modulename);
                        modules.add(mb.build());
                        mb = null;
                        break;
                    case EXPORT_TO:
                        if (pkg == null || permits.isEmpty()) {
                            throw new RuntimeException("export-to is malformed");
                        }
                        mb.exportTo(pkg, permits);
                        permits.clear();
                        break;
                    default:
                }
            } else if (event.isCharacters()) {
                String s = event.asCharacters().getData();
                if (!s.trim().isEmpty()) {
                    throw new RuntimeException("export-to is malformed");
                }
            }
        }
        return modules;
    }

    private String getData(XMLEventReader reader) throws XMLStreamException {
        XMLEvent e = reader.nextEvent();
        if (e.isCharacters()) {
            return e.asCharacters().getData();
        }
        throw new RuntimeException(e.toString());
    }

    private String getNextTag(XMLEventReader reader, String tag) throws XMLStreamException {
        XMLEvent e = reader.nextTag();
        if (e.isStartElement()) {
            String t = e.asStartElement().getName().getLocalPart();
            if (!tag.equals(t)) {
                throw new RuntimeException(e + " expected: " + tag);
            }
            return getData(reader);
        }
        throw new RuntimeException("export-to name is missing:" + e);
    }
    private void writeXML(Set<Module> modules, Path path)
            throws IOException, XMLStreamException
    {
        System.out.println("Writing to " + path.toString());
        XMLOutputFactory xof = XMLOutputFactory.newInstance();
        try (OutputStream out = Files.newOutputStream(path)) {
            int depth = 0;
            XMLStreamWriter xtw = xof.createXMLStreamWriter(out, "UTF-8");
            xtw.writeStartDocument("utf-8","1.0");
            writeStartElement(xtw, MODULES, depth);
            modules.stream()
                   .sorted(Comparator.comparing(Module::name))
                   .forEach(m -> writeModuleElement(xtw, m, depth+1));
            writeEndElement(xtw, depth);
            xtw.writeCharacters("\n");
            xtw.writeEndDocument();
            xtw.flush();
            xtw.close();
        }
    }
    private void writeElement(XMLStreamWriter xtw, String element, String value, int depth) {
        try {
            writeStartElement(xtw, element, depth);
            xtw.writeCharacters(value);
            xtw.writeEndElement();
        } catch (XMLStreamException e) {
            throw new RuntimeException(e);
        }
    }
    private void writeExportsToElement(XMLStreamWriter xtw, String pkg,
                                              Set<String> permits, int depth) {
        try {
            writeStartElement(xtw, EXPORT_TO, depth);
            writeElement(xtw, NAME, pkg, depth+1);
            permits.stream().sorted()
                   .forEach(m -> writeElement(xtw, PERMIT, m, depth+1));
            writeEndElement(xtw, depth);
        } catch (XMLStreamException e) {
            throw new RuntimeException(e);
        }
    }
    private void writeModuleElement(XMLStreamWriter xtw, Module m, int depth) {
        try {
            writeStartElement(xtw, MODULE, depth);
            writeElement(xtw, NAME, m.name(), depth+1);
            m.requires().stream().sorted()
                        .forEach(d -> writeElement(xtw, DEPENDENCE, d, depth+1));
            m.exports().keySet().stream()
                       .filter(pn -> m.exports().get(pn).isEmpty())
                       .sorted()
                       .forEach(pn -> writeElement(xtw, EXPORT, pn, depth+1));
            m.exports().entrySet().stream()
                       .filter(e -> !e.getValue().isEmpty())
                       .sorted(Map.Entry.comparingByKey())
                       .forEach(e -> writeExportsToElement(xtw, e.getKey(), e.getValue(), depth+1));
            m.packages().stream().sorted()
                        .forEach(p -> writeElement(xtw, INCLUDE, p, depth+1));
            writeEndElement(xtw, depth);
        } catch (XMLStreamException e) {
            throw new RuntimeException(e);

        }
    }

    /** Two spaces; the default indentation. */
    public static final String DEFAULT_INDENT = "  ";

    /** stack[depth] indicates what's been written into the current scope. */
    private static String[] stack = new String[] { "\n",
        "\n" + DEFAULT_INDENT,
        "\n" + DEFAULT_INDENT + DEFAULT_INDENT,
        "\n" + DEFAULT_INDENT + DEFAULT_INDENT + DEFAULT_INDENT};

    private void writeStartElement(XMLStreamWriter xtw, String name, int depth)
            throws XMLStreamException
    {
        xtw.writeCharacters(stack[depth]);
        xtw.writeStartElement(name);
    }

    private void writeEndElement(XMLStreamWriter xtw, int depth) throws XMLStreamException {
        xtw.writeCharacters(stack[depth]);
        xtw.writeEndElement();
    }

    private String packageName(Path p) {
        return packageName(p.toString().replace(File.pathSeparatorChar, '/'));
    }
    private String packageName(String name) {
        int i = name.lastIndexOf('/');
        return (i > 0) ? name.substring(0, i).replace('/', '.') : "";
    }

    public void buildIncludes(Module.Builder mb, String modulename) throws IOException {
        Path mclasses = modulepath.resolve(modulename);
        if (Files.exists(mclasses.resolve("classes"))) {
            // zip file
            try (JarFile jf = new JarFile(mclasses.resolve("classes").toFile())) {
                jf.stream().filter(je -> je.getName().endsWith(".class"))
                        .map(JarEntry::getName).map(this::packageName)
                        .forEach(mb::include);
            }
        } else {
            try {
                Files.find(mclasses, Integer.MAX_VALUE, (Path p, BasicFileAttributes attr)
                                -> p.getFileName().toString().endsWith(".class"))
                     .map(p -> packageName(mclasses.relativize(p)))
                     .forEach(mb::include);
            } catch (NoSuchFileException e) {
                // aggregate module may not have class
            }
        }
    }

    static class Module {
        private final String moduleName;
        private final Set<String> requires;
        private final Map<String, Set<String>> exports;
        private final Set<String> packages;

        private Module(String name,
                Set<String> requires,
                Map<String, Set<String>> exports,
                Set<String> packages) {
            this.moduleName = name;
            this.requires = Collections.unmodifiableSet(requires);
            this.exports = Collections.unmodifiableMap(exports);
            this.packages = Collections.unmodifiableSet(packages);
        }

        public String name() {
            return moduleName;
        }

        public Set<String> requires() {
            return requires;
        }

        public Map<String, Set<String>> exports() {
            return exports;
        }

        public Set<String> packages() {
            return packages;
        }

        @Override
        public boolean equals(Object ob) {
            if (!(ob instanceof Module)) {
                return false;
            }
            Module that = (Module) ob;
            return (moduleName.equals(that.moduleName)
                    && requires.equals(that.requires)
                    && exports.equals(that.exports)
                    && packages.equals(that.packages));
        }

        @Override
        public int hashCode() {
            int hc = moduleName.hashCode();
            hc = hc * 43 + requires.hashCode();
            hc = hc * 43 + exports.hashCode();
            hc = hc * 43 + packages.hashCode();
            return hc;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("module ").append(moduleName).append(" {").append("\n");
            requires.stream().sorted().forEach(d -> sb.append(String.format("   requires %s%n", d)));
            exports.entrySet().stream().filter(e -> e.getValue().isEmpty())
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append(String.format("   exports %s%n", e.getKey())));
            exports.entrySet().stream().filter(e -> !e.getValue().isEmpty())
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append(String.format("   exports %s to %s%n", e.getKey(), e.getValue())));
            packages.stream().sorted().forEach(pn -> sb.append(String.format("   includes %s%n", pn)));
            sb.append("}");
            return sb.toString();
        }

        static class Builder {
            private String name;
            private final Set<String> requires = new HashSet<>();
            private final Map<String, Set<String>> exports = new HashMap<>();
            private final Set<String> packages = new HashSet<>();

            public Builder() {
            }

            public Builder name(String n) {
                name = n;
                return this;
            }

            public Builder require(String d) {
                requires.add(d);
                return this;
            }

            public Builder include(String p) {
                packages.add(p);
                return this;
            }

            public Builder export(String p) {
                Objects.requireNonNull(p);
                if (exports.containsKey(p)) {
                    throw new RuntimeException(name + " already exports " + p);
                }
                exports.put(p, new HashSet<>());
                return this;
            }

            public Builder exportTo(String p, Set<String> ms) {
                Objects.requireNonNull(p);
                Objects.requireNonNull(ms);
                exports.put(p, new HashSet<>(ms));
                return this;
            }

            public Module build() {
                Module m = new Module(name, requires, exports, packages);
                return m;
            }
        }
    }
}
