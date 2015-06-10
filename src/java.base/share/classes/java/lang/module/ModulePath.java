/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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

package java.lang.module;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jdk.internal.module.Hasher;
import jdk.internal.module.Hasher.HashSupplier;
import sun.misc.PerfCounter;


/**
 * A {@code ModuleFinder} that locates module references on the file
 * system by searching a sequence of directories for jmod, modular JAR or
 * exploded modules.
 *
 * @apiNote This class is currently not safe for use by multiple threads.
 */

class ModulePath implements ModuleFinder {
    private static final String MODULE_INFO = "module-info.class";

    // the directories on this module path
    private final Path[] dirs;
    private int next;

    // the module name to reference map of modules already located
    private final Map<String, ModuleReference> cachedModules = new HashMap<>();

    public ModulePath(Path... dirs) {
        this.dirs = dirs; // no need to clone
    }

    @Override
    public Optional<ModuleReference> find(String name) {
        // try cached modules
        ModuleReference m = cachedModules.get(name);
        if (m != null)
            return Optional.of(m);

        // the module may be in directories that we haven't scanned yet
        while (hasNextDirectory()) {
            scanNextDirectory();
            m = cachedModules.get(name);
            if (m != null)
                return Optional.of(m);
        }
        return Optional.empty();
    }

    @Override
    public Set<ModuleReference> findAll() {
        // need to ensure that all directories have been scanned
        while (hasNextDirectory()) {
            scanNextDirectory();
        }
        return cachedModules.values().stream().collect(Collectors.toSet());
    }

    /**
     * Returns {@code true} if there are additional directories to scan
     */
    private boolean hasNextDirectory() {
        return next < dirs.length;
    }

    /**
     * Scans the next directory on the module path. A no-op if all
     * directories have already been scanned.
     */
    private void scanNextDirectory() {
        if (hasNextDirectory()) {
            Path dir = dirs[next++];
            scan(dir);
        }
    }

    /**
     * Scans the given directory for jmod or exploded modules. For each module
     * found then it enumerates its contents and creates a {@code Module} and
     * adds it (and its URL) to the cache.
     *
     * @throws UncheckedIOException if an I/O error occurs
     * @throws RuntimeException if directory contains more than one version of
     * a module (need to decide on a better exception for this case).
     */
    private void scan(Path dir) {
        // the set of module names found in this directory
        Set<String> namesInThisDirectory = new HashSet<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry: stream) {
                ModuleReference mref = null;

                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                } catch (IOException ioe) {
                    // ignore for now
                    continue;
                }
                if (attrs.isRegularFile()) {
                    if (entry.toString().endsWith(".jmod")) {
                        mref = readJMod(entry);
                    } else if (entry.toString().endsWith(".jar")) {
                        mref = readJar(entry);
                    }
                } else if (attrs.isDirectory()) {
                    mref = readExploded(entry);
                }

                // module reference found
                if (mref != null) {
                    // check that there is only one version of the module
                    // in this directory
                    String name = mref.descriptor().name();
                    if (namesInThisDirectory.contains(name)) {
                        throw new RuntimeException(dir +
                            " contains more than one version of " + name);
                    }
                    namesInThisDirectory.add(name);

                    // a module of this name found in a previous location
                    // on the module path so ignore it
                    if (cachedModules.containsKey(name))
                        continue;

                    // add the module to the cache
                    cachedModules.put(name, mref);
                }
            }
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }


    // -- jmod files --

    private Set<String> jmodPackages(ZipFile zf) {
        return zf.stream()
            .filter(e -> e.getName().startsWith("classes/") &&
                    e.getName().endsWith(".class"))
            .map(e -> toPackageName(e))
            .filter(pkg -> pkg.length() > 0) // module-info
            .distinct()
            .collect(Collectors.toSet());
    }

    /**
     * Returns a {@code ModuleReference} to represent a jmod file on the
     * file system.
     */
    private ModuleReference readJMod(Path file) throws IOException {
        long t0 = System.nanoTime();
        try (ZipFile zf = new ZipFile(file.toString())) {
            ZipEntry ze = zf.getEntry("classes/" + MODULE_INFO);
            if (ze == null) {
                throw new IOException(MODULE_INFO + " is missing: " + file);
            }
            ModuleDescriptor md;
            try (InputStream in = zf.getInputStream(ze)) {
                md = ModuleDescriptor.read(in, () -> jmodPackages(zf));
            }
            // jmod URI - syntax not defined yet
            URI location = URI.create("jmod:" + file.toUri() + "!/");
            HashSupplier hasher = (algorithm) -> Hasher.generate(file, algorithm);
            ModuleReference mref = ModuleReferences.newModuleReference(md, location, hasher);
            mrefCount.increment();
            mrefInitTime.addElapsedTimeFrom(t0);
            return mref;
        }
    }


    // -- JAR files --

    private static final String SERVICES_PREFIX = "META-INF/services/";

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
     * Derive a module name from the given JAR file name.
     *
     * @apiNote This needs to move to somewhere where it can be used by tools,
     * maybe even a standard API if automatic modules are a Java SE feature.
     */
    private String deriveModuleNameFromJarName(String name) {
        // drop .jar
        name = name.substring(0, name.length()-4);

        // drop -${VERSION}
        int index = name.lastIndexOf('-');
        if (index > 0) {
            String tail = name.substring(index+1);
            if (tail.matches("^(\\d+\\.)?(\\d+\\.)?(\\*|\\d+)$")) {
                name = name.substring(0, index);
            }
        }

        // drop all non-alphanumeric chars
        return name.replaceAll("[^A-Za-z0-9]", "");
    }

    /**
     * Treat the given JAR file as a module as follows:
     *
     * 1. The module name is derived from the file name of the JAR file
     * 2. The packages of all .class files in the JAR file are exported
     * 3. It has no module-private/concealed packages
     * 4. The contents of any META-INF/services configuration files are mapped
     *    to "provides" declarations
     */
    private ModuleDescriptor deriveModuleDescriptor(JarFile jf)
        throws IOException
    {
        // module name
        String fn = jf.getName();
        int i = fn.lastIndexOf(File.separator);
        if (i != -1)
            fn = fn.substring(i+1);
        String mn = deriveModuleNameFromJarName(fn);

        // Builder throws IAE if module name is empty or invalid
        ModuleDescriptor.Builder builder
            = new ModuleDescriptor.Builder(mn, true).requires("java.base");

        // scan the entries in the JAR file to locate the .class and service
        // configuration file
        Stream<String> stream = jf.stream()
            .map(e -> e.getName())
            .filter(e -> (e.endsWith(".class") || e.startsWith(SERVICES_PREFIX)))
            .distinct();
        Map<Boolean, Set<String>> map
            = stream.collect(Collectors.partitioningBy(s -> s.endsWith(".class"),
                             Collectors.toSet()));
        Set<String> classFiles = map.get(Boolean.TRUE);
        Set<String> configFiles = map.get(Boolean.FALSE);

        // all packages are exported
        classFiles.stream()
            .map(c -> toPackageName(c))
            .distinct()
            .forEach(p -> builder.exports(p));

        // map names of service configuration files to service names
        Set<String> serviceNames = configFiles.stream()
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
                    if (cn.length() > 0) {
                        providerClasses.add(cn);
                    }
                }
            }
            if (!providerClasses.isEmpty())
                builder.provides(sn, providerClasses);
        }

        return builder.build();
    }

    private Set<String> jarPackages(JarFile jf) {
        return jf.stream()
            .filter(e -> e.getName().endsWith(".class"))
            .map(e -> toPackageName(e))
            .filter(pkg -> pkg.length() > 0)   // module-info
            .distinct()
            .collect(Collectors.toSet());
    }

    /**
     * Returns a {@code ModuleReference} to represent a modular JAR  on the
     * file system.
     */
    private ModuleReference readJar(Path file) throws IOException {
        long t0 = System.nanoTime();
        try (JarFile jf = new JarFile(file.toString())) {

            ModuleDescriptor md;
            JarEntry entry = jf.getJarEntry(MODULE_INFO);
            if (entry == null) {
                // no module-info.class so treat it as automatic module
                md = deriveModuleDescriptor(jf);
            } else {
                md = ModuleDescriptor.read(jf.getInputStream(entry),
                                           () -> jarPackages(jf));
            }

            URI location = URI.create("jar:" + file.toUri() + "!/");
            HashSupplier hasher = (algorithm) -> Hasher.generate(file, algorithm);

            ModuleReference mref = ModuleReferences.newModuleReference(md, location, hasher);
            mrefCount.increment();
            mrefInitTime.addElapsedTimeFrom(t0);
            return mref;
        }
    }


    // -- exploded directories --

    private Set<String> explodedPackages(Path dir) {
        try {
            return Files.find(dir, Integer.MAX_VALUE,
                              ((path, attrs) -> attrs.isRegularFile() &&
                               path.toString().endsWith(".class")))
                .map(path -> toPackageName(dir.relativize(path)))
                .filter(pkg -> pkg.length() > 0)   // module-info
                .distinct()
                .collect(Collectors.toSet());
        } catch (IOException x) {
            throw new UncheckedIOException(x);
        }
    }

    /**
     * Returns a {@code ModuleReference} to represent an exploded module
     * on the file system.
     */
    private ModuleReference readExploded(Path dir) throws IOException {
        long t0 = System.nanoTime();
        Path mi = dir.resolve(MODULE_INFO);
        if (Files.notExists(mi)) {
            // no module-info in directory
            return null;
        }
        URI location = dir.toUri();
        ModuleDescriptor md;
        try (InputStream in = Files.newInputStream(mi)) {
            md = ModuleDescriptor.read(new BufferedInputStream(in),
                                       () -> explodedPackages(dir));
        }
        ModuleReference mref = ModuleReferences.newModuleReference(md, location, null);
        mrefCount.increment();
        mrefInitTime.addElapsedTimeFrom(t0);
        return mref;
    }


    //

    // p/q/T.class => p.q
    private String toPackageName(String cn) {
        assert cn.endsWith(".class");
        int start = 0;
        int index = cn.lastIndexOf("/");
        if (index > start) {
            return cn.substring(start, index).replace('/', '.');
        } else {
            return "";
        }
    }

    private String toPackageName(ZipEntry entry) {
        String name = entry.getName();
        assert name.endsWith(".class");
        // jmod classes in classes/, jar in /
        int start = name.startsWith("classes/") ? 8 : 0;
        int index = name.lastIndexOf("/");
        if (index > start) {
            return name.substring(start, index).replace('/', '.');
        } else {
            return "";
        }
    }

    private String toPackageName(Path path) {
        String name = path.toString();
        assert name.endsWith(".class");
        int index = name.lastIndexOf(File.separatorChar);
        if (index != -1) {
            return name.substring(0, index).replace(File.separatorChar, '.');
        } else {
            return "";
        }
    }

    private static final PerfCounter initTime =
        PerfCounter.newPerfCounter("jdk.module.finder.modulepath.initTime");
    private static final PerfCounter mrefInitTime =
        PerfCounter.newPerfCounter("jdk.module.finder.modulepath.mrefsInitTime");
    private static final PerfCounter mrefCount =
        PerfCounter.newPerfCounter("jdk.module.finder.modulepath.mrefs");
}
