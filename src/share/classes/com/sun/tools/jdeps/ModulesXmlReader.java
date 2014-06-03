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
package com.sun.tools.jdeps;

import com.sun.tools.jdeps.PlatformClassPath.LegacyImageHelper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.XMLEvent;

public class ModulesXmlReader {
    private final Path[] mpaths;
    private final LegacyImageHelper helper;

    ModulesXmlReader(Path... mpaths) {
        this.mpaths = mpaths;
        this.helper = null;
    }
    ModulesXmlReader(LegacyImageHelper helper) {
        this.mpaths = new Path[0];
        this.helper = helper;
    }

    private static final String MODULES   = "modules";
    private static final String MODULE    = "module";
    private static final String NAME      = "name";
    private static final String DEPEND    = "depend";
    private static final String EXPORT    = "export";
    private static final String TO        = "to";
    private static final String INCLUDE   = "include";
    private static final QName  REEXPORTS = new QName("re-exports");
    public Set<Module> load(InputStream in) throws XMLStreamException, IOException {
        Set<Module> modules = new HashSet<>();
        if (in == null) {
            System.err.println("modules.xml doesn't exist");
            return modules;
        }
        XMLInputFactory factory = XMLInputFactory.newInstance();
        XMLEventReader reader = factory.createXMLEventReader(in, "UTF-8");
        Module.Builder mb = null;
        String modulename = null;
        String exportedPackage = null;
        Set<String> permits = new HashSet<>();
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                String startTag = event.asStartElement().getName().getLocalPart();
                switch (startTag) {
                    case MODULES:
                        break;
                    case MODULE:
                        if (mb != null) {
                            throw new RuntimeException("end tag for module is missing");
                        }
                        modulename = getNextTag(reader, NAME);
                        mb = new Module.Builder();
                        mb.name(modulename);
                        break;
                    case NAME:
                        throw new RuntimeException(event.toString());
                    case DEPEND:
                        boolean reexports = false;
                        Attribute attr = event.asStartElement().getAttributeByName(REEXPORTS);
                        if (attr != null) {
                            String value = attr.getValue();
                            if (value.equals("true") || value.equals("false")) {
                                reexports = Boolean.parseBoolean(value);
                            } else {
                                throw new RuntimeException("unexpected attribute " + attr.toString());
                            }
                        }
                        mb.require(getData(reader), reexports);
                        break;
                    case INCLUDE:
                        mb.include(getData(reader));
                        break;
                    case EXPORT:
                        exportedPackage = getNextTag(reader, NAME);
                        break;
                    case TO:
                        permits.add(getData(reader));
                        break;
                    default:
                        // System.err.println(event);
                }
            } else if (event.isEndElement()) {
                String endTag = event.asEndElement().getName().getLocalPart();
                switch (endTag) {
                    case MODULE:
                        setModuleClassReader(modulename, mb);
                        modules.add(mb.build());
                        mb = null;
                        break;
                    case EXPORT:
                        if (exportedPackage == null) {
                            throw new RuntimeException("export's name is missing");
                        }
                        mb.export(exportedPackage, permits);
                        exportedPackage = null;
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

    private void setModuleClassReader(String modulename, Module.Builder mb) throws IOException {
        ClassFileReader cfr = null;
        if (helper != null) {
            cfr = helper.getClassReader(modulename, mb.packages);
        }
        for (Path p : mpaths) {
            Path mdir = p.resolve(modulename);
            if (Files.exists(mdir) && Files.isDirectory(mdir)) {
                Path mclasses = mdir.resolve("classes");
                cfr = Files.exists(mclasses)
                        ? ClassFileReader.newInstance(mclasses)
                        : ClassFileReader.newInstance(mdir);
                break;
            }
        }
        if (cfr == null) {
            throw new Error("can't find module " + modulename);
        }
        mb.classes(cfr);
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
}
