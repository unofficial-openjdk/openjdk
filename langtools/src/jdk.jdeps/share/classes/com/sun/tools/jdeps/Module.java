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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.sun.tools.classfile.Dependency;
import com.sun.tools.jdeps.ClassFileReader.ModuleClassReader;

/**
 * JDeps internal representation of module for dependency analysis.
 */
final class Module extends Archive {
    private final String moduleName;
    private final Map<String, Boolean> requires;
    private final Map<String, Set<String>> exports;
    private final Set<String> packages;

    private Module(ClassFileReader reader, String name,
                   Map<String, Boolean> requires,
                   Map<String, Set<String>> exports,
                   Set<String> packages) {
        super(name, reader);
        this.moduleName = name;
        this.requires = Collections.unmodifiableMap(requires);
        this.exports = Collections.unmodifiableMap(exports);
        this.packages = Collections.unmodifiableSet(packages);
    }

    public String name() {
        return moduleName;
    }

    public Map<String, Boolean> requires() {
        return requires;
    }

    public Map<String, Set<String>> exports() {
        return exports;
    }

    public Set<String> packages() {
        return packages;
    }

    /**
     * Tests if this module can read m
     */
    public boolean canRead(Module m) {
        // ## TODO: handle "re-exported=true"
        // all JDK modules require all modules containing its direct dependences
        // should not be an issue
        return requires.containsKey(m.name());
    }

    /**
     * Tests if a given fully-qualified name is an exported type.
     */
    public boolean isExported(String cn) {
        int i = cn.lastIndexOf('.');
        String pn = i > 0 ? cn.substring(0, i) : "";

        return isExportedPackage(pn);
    }

    /**
     * Tests if a given package name is exported.
     */
    public boolean isExportedPackage(String pn) {
        return exports.containsKey(pn) ? exports.get(pn).isEmpty() : false;
    }

    /**
     * Tests if the given classname is accessible to module m
     */
    public boolean isAccessibleTo(String classname, Module m) {
        int i = classname.lastIndexOf('.');
        String pn = i > 0 ? classname.substring(0, i) : "";
        if (!packages.contains(pn)) {
            throw new IllegalArgumentException(classname + " is not a member of module " + name());
        }

        if (m != null && !m.canRead(this)) {
            trace("%s not readable by %s%n", this.name(), m.name());
            return false;
        }

        // exported API
        Set<String> ms = exports().get(pn);
        String mname = m != null ? m.name() : "unnamed";
        if (ms == null) {
            trace("%s not exported in %s%n", classname, this.name());
        } else if (!(ms.isEmpty() || ms.contains(mname))) {
            trace("%s not permit to %s %s%n", classname, mname, ms);
        }
        return ms != null && (ms.isEmpty() || ms.contains(mname));
    }

    private static final boolean traceOn = Boolean.getBoolean("jdeps.debug");
    private void trace(String fmt, Object... args) {
        if (traceOn) {
            System.err.format(fmt, args);
        }
    }

    @Override
    public boolean equals(Object ob) {
        if (!(ob instanceof Module))
            return false;
        Module that = (Module)ob;
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
        return name();
    }

    public final static class Builder {
        String name;
        ClassFileReader reader;
        final Map<String, Boolean> requires = new HashMap<>();
        final Map<String, Set<String>> exports = new HashMap<>();
        final Set<String> packages = new HashSet<>();

        public Builder() {
        }

        public Builder name(String n) {
            name = n;
            return this;
        }

        public Builder require(String d, boolean reexport) {
            requires.put(d, reexport);
            return this;
        }

        public Builder packages(Set<String> pkgs) {
            packages.addAll(pkgs);
            return this;
        }

        public Builder export(String p, Set<String> ms) {
            Objects.requireNonNull(p);
            Objects.requireNonNull(ms);
            exports.put(p, new HashSet<>(ms));
            return this;
        }
        public Builder classes(ModuleClassReader reader) {
            this.reader = reader;
            return this;
        }

        public Module build() {
            Module m = new Module(reader, name, requires, exports, packages);
            return m;
        }
    }

    /**
     * Test if the given archive is part of the JDK
     */
    public static boolean isJDKModule(Archive archive) {
        return Module.class.isInstance(archive);
    }

    public static class JarFileToModule extends Archive {
        private static final String SERVICES_PREFIX = "META-INF/services/";
        private final Map<Archive, Boolean> requires;
        private final Map<String, Set<String>> provides;
        private final Set<String> packages;
        private final Archive archive;
        private final JarFile jarfile;
        JarFileToModule(Archive archive, Map<Archive, Boolean> requires) {
            super(deriveModuleName(archive));
                this.archive = archive;
            try {
                this.jarfile = new JarFile(archive.path().toFile(), false);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
                this.provides = providers(jarfile);
            this.packages = archive.getClasses().stream()
                                   .map(Dependency.Location::getPackageName)
                                   .collect(Collectors.toSet());
            this.requires = requires;
        }

        public Map<Archive, Boolean> requires() {
            return requires;
        }

        public Set<String> packages() {
            return packages;
        }

        public Map<String, Set<String>> provides() {
            return provides;
        }

        @Override
        public void addClass(Dependency.Location origin) {
            throw new UnsupportedOperationException();
        }
        @Override
        public void addClass(Dependency.Location origin, Dependency.Location target) {
            throw new UnsupportedOperationException();
        }
        @Override
        public Set<Dependency.Location> getClasses() {
            return archive.getClasses();
        }
        @Override
        public void visitDependences(Visitor v) {
            archive.visitDependences(v);
        }
        @Override
        public boolean isEmpty() {
            return archive.isEmpty();
        }
        @Override
        public String getPathName() {
            return archive.getPathName();
        }
        @Override
        public String toString() {
            return archive.toString();
        }
        @Override
        public Path path() {
            return archive.path();
        }

        // Derive module name from JAR file name
        private static String deriveModuleName(Archive archive) {
            String jarFileName = archive.getName();
            // drop .jar
            String mn = jarFileName.substring(0, jarFileName.length() - 4);

            // find first occurrence of -${NUMBER}. or -${NUMBER}$
            Matcher matcher = Pattern.compile("-(\\d+(\\.|$))").matcher(mn);
            if (matcher.find()) {
                int start = matcher.start();
                mn = mn.substring(0, start);
            }

            // finally clean up the module name
            return mn.replaceAll("[^A-Za-z0-9]", ".")  // replace non-alphanumeric
                    .replaceAll("(\\.)(\\1)+", ".")   // collapse repeating dots
                    .replaceAll("^\\.", "")           // drop leading dots
                    .replaceAll("\\.$", "");          // drop trailing dots
        }

        private Map<String, Set<String>> providers(JarFile jf) {
            Map<String, Set<String>> provides = new HashMap<>();
                // map names of service configuration files to service names
                Set<String> serviceNames =  jf.stream()
                        .map(e -> e.getName())
                        .filter(e -> e.startsWith(SERVICES_PREFIX))
                        .distinct()
                        .map(this::toServiceName)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toSet());

                // parse each service configuration file
                for (String sn : serviceNames) {
                    JarEntry entry = jf.getJarEntry(SERVICES_PREFIX + sn);
                    Set<String> providerClasses = new HashSet<>();
                    try (InputStream in = jf.getInputStream(entry)) {
                        BufferedReader reader
                                = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                        String cn;
                        while ((cn = nextLine(reader)) != null) {
                            if (isJavaIdentifier(cn)) {
                                providerClasses.add(cn);
                            }
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    if (!providerClasses.isEmpty())
                        provides.put(sn, providerClasses);
                }

            return provides;
        }

        /**
         * Returns a container with the service type corresponding to the name of
         * a services configuration file.
         *
         * For example, if called with "META-INF/services/p.S" then this method
         * returns a container with the value "p.S".
         */
        private Optional<String> toServiceName(String cf) {
            assert cf.startsWith(SERVICES_PREFIX);
            int index = cf.lastIndexOf("/") + 1;
            if (index < cf.length()) {
                String prefix = cf.substring(0, index);
                if (prefix.equals(SERVICES_PREFIX)) {
                    String sn = cf.substring(index);
                    if (isJavaIdentifier(sn))
                        return Optional.of(sn);
                }
            }
            return Optional.empty();
        }

        /**
         * Reads the next line from the given reader and trims it of comments and
         * leading/trailing white space.
         *
         * Returns null if the reader is at EOF.
         */
        private String nextLine(BufferedReader reader) throws IOException {
            String ln = reader.readLine();
            if (ln != null) {
                int ci = ln.indexOf('#');
                if (ci >= 0)
                    ln = ln.substring(0, ci);
                ln = ln.trim();
            }
            return ln;
        }

        /**
         * Returns {@code true} if the given identifier is a legal Java identifier.
         */
        private static boolean isJavaIdentifier(String id) {
            int n = id.length();
            if (n == 0)
                return false;
            if (!Character.isJavaIdentifierStart(id.codePointAt(0)))
                return false;
            int cp = id.codePointAt(0);
            int i = Character.charCount(cp);
            for (; i < n; i += Character.charCount(cp)) {
                cp = id.codePointAt(i);
                if (!Character.isJavaIdentifierPart(cp) && id.charAt(i) != '.')
                    return false;
            }
            if (cp == '.')
                return false;

            return true;
        }
    }
}
