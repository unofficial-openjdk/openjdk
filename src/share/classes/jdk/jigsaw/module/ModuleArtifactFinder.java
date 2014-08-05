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

package jdk.jigsaw.module;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jdk.jigsaw.module.internal.ImageModules;
import sun.misc.JModCache;

/**
 * A finder of module artifacts.
 *
 * <p> An important property is that a {@code ModuleArtifactFinder} admits to
 * at most one module with a given name. A {@code ModuleArtifactFinder} that
 * finds modules in sequence of directories for example, will locate the first
 * occurrence of a module and ignores other modules of that name that appear in
 * directories later in the sequence. </p>
 *
 * <p> Example usage: </p>
 *
 * <pre>{@code
 *     Path dir1, dir2, dir3;
 *
 *     ModuleArtifactFinder finder =
 *         ModuleArtifactFinder.ofDirectories(dir1, dir2, dir3);
 *
 *     ModuleArtifact artifact = finder.find("jdk.foo");
 * }</pre>
 *
 * @apiNote The eventual API will need to define how errors are handled, say
 * for example find lazily searching the module path and finding two modules of
 * the same name in the same directory.
 *
 * @apiNote Rename to {@code ModuleDefinition} as per Mark's sketch?
 */
public interface ModuleArtifactFinder {

    /**
     * Finds a module artifact where the module has the given name.
     * Returns {@code null} if not found.
     */
    public ModuleArtifact find(String name);

    /**
     * Returns the set of all module artifacts that this finder can
     * locate.
     *
     * @apiNote This is important to have for methods such as {@link
     * Configuration#bind} that need to scan the module path to find
     * modules that provide a specific service.
     */
    public Set<ModuleArtifact> allModules();

    /**
     * Returns a module finder for modules that are linked into the
     * runtime image.
     *
     * @implNote For now, the finder is backed by the contents of {@code
     * modules.jdata}, or in the case a developer build then it is backed by
     * the module descriptors and content found in the {@code lib/modules/$m/*}.
     */
    public static ModuleArtifactFinder installedModules() {
        if (InstalledModuleFinder.isModularImage()) {
            return new InstalledModuleFinder();
        } else {
            String home = System.getProperty("java.home");
            Path mlib = Paths.get(home, "modules");
            if (Files.isDirectory(mlib)) {
                return ofDirectories(mlib);
            } else {
                System.err.println("WARNING: " + mlib.toString() +
                        " not found or not a directory");
                return ofDirectories(new Path[0]);
            }
        }
    }

    /**
     * Creates a finder that locates modules on the file system by
     * searching a sequence of directories containing module artifacts
     * ({@code jmod}, modular JAR, exploded modules).
     *
     * @apiNote This method needs to define how the returned finder handles
     * I/O and other errors (a ClassFormatError when parsing a module-info.class
     * for example).
     */
    public static ModuleArtifactFinder ofDirectories(Path... dirs) {
        return new ModulePath(dirs);
    }

    /**
     * Returns a finder that is the equivalent to concatenating the given
     * finders. The resulting finder will locate modules artifacts using {@code
     * first}; if not found then it will attempt to locate module artifacts
     * using {@code second}.
     */
    public static ModuleArtifactFinder concat(ModuleArtifactFinder first,
                                              ModuleArtifactFinder second)
    {
        return new ModuleArtifactFinder() {
            @Override
            public ModuleArtifact find(String name) {
                ModuleArtifact m = first.find(name);
                if (m == null)
                    m = second.find(name);
                return m;
            }
            @Override
            public Set<ModuleArtifact> allModules() {
                Set<ModuleArtifact> result = new HashSet<>();
                // reverse order is important here
                result.addAll(second.allModules());
                result.addAll(first.allModules());
                return result;
            }
        };
    }

    /**
     * Returns a <em>null</em> finder. The null finder does not find any
     * modules.
     *
     * @apiNote This is useful when using methods such as {@link
     * Configuration#resolve} where two finders are specified.
     */
    public static ModuleArtifactFinder nullFinder() {
        return new ModuleArtifactFinder() {
            @Override public ModuleArtifact find(String name) {
                return null;
            }
            @Override public Set<ModuleArtifact> allModules() {
                return Collections.emptySet();
            }
        };
    }
}

/**
 * A {@code ModuleArtifactFinder} that finds modules that are
 * linked into the modular image.
 */
class InstalledModuleFinder implements ModuleArtifactFinder {
    private final Set<ModuleArtifact> artifacts;
    private final Map<String, ModuleArtifact> namesToArtifact= new HashMap<>();

    InstalledModuleFinder() {
        this.artifacts = modules();
        for (ModuleArtifact m : artifacts) {
            String name = m.descriptor().name();
            namesToArtifact.putIfAbsent(name, m);
        }
    }

    private static Path imageModulesPath() {
        String home = System.getProperty("java.home");
        return Paths.get(home, "lib", "modules", ImageModules.FILE);
    }

    private Set<ModuleArtifact> modules() {
        try (InputStream in = Files.newInputStream(imageModulesPath())) {

            ImageModules image = ImageModules.load(in);

            Set<ModuleDescriptor> descriptors = image.modules();
            Map<String, Set<String>> packageMap = image.packages();

            Set<ModuleArtifact> artifacts = new HashSet<>();
            for (ModuleDescriptor descriptor: descriptors) {
                String name = descriptor.name();
                URL url;
                try {
                    url = URI.create("module:///" + name).toURL();
                } catch (MalformedURLException e) {
                    throw new InternalError(e);
                }
                Set<String> packages = packageMap.get(name);
                if (packages == null)
                    packages = Collections.emptySet();
                ModuleArtifact artifact = new ModuleArtifact(extend(descriptor),
                                                             packages,
                                                             url);

                artifacts.add(artifact);
            }

            return artifacts;

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ExtendedModuleDescriptor extend(ModuleDescriptor d) {
        ExtendedModuleDescriptor.Builder b =
            new ExtendedModuleDescriptor.Builder(d.name());

        d.moduleDependences().forEach(b::requires);
        d.serviceDependences().forEach(b::requires);
        d.exports().forEach(b::export);

        Map<String, Set<String>> services = d.services();
        for (Map.Entry<String, Set<String>> entry: services.entrySet()) {
            String s = entry.getKey();
            entry.getValue().forEach(p -> b.service(s, p));
        }

        return b.build();
    }

    static boolean isModularImage() {
        return Files.isRegularFile(imageModulesPath());
    }

    @Override
    public ModuleArtifact find(String name) {
        return namesToArtifact.get(name);
    }

    @Override
    public Set<ModuleArtifact> allModules() {
        return Collections.unmodifiableSet(artifacts);
    }
}

/**
 * Locates module artifacts on the file system by searching a sequence of
 * directories for jmod, modular JAR or exploded
 * modules.
 *
 * @apiNote This class is currently not safe for use by multiple threads.
 */
class ModulePath implements ModuleArtifactFinder {
    private static final String MODULE_INFO = "module-info.class";

    // module id in extended module descriptor
    private static final String EXTENDED_MODULE_DESCRIPTOR_MID = "module/id";

    // the directories on this module path
    private final Path[] dirs;
    private int next;

    // the module name to artifact map of modules already located
    private final Map<String, ModuleArtifact> cachedModules = new HashMap<>();

    public ModulePath(Path... dirs) {
        this.dirs = dirs; // no need to clone
    }

    @Override
    public ModuleArtifact find(String name) {
        // try cached modules
        ModuleArtifact m = cachedModules.get(name);
        if (m != null)
            return m;

        // the module may be in directories that we haven't scanned yet
        while (hasNextDirectory()) {
            scanNextDirectory();
            m = cachedModules.get(name);
            if (m != null)
                return m;
        }
        return null;
    }

    @Override
    public Set<ModuleArtifact> allModules() {
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
     */
    private void scan(Path dir) {
        // the set of module names found in this directory
        Set<String> namesInThisDirectory = new HashSet<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry: stream) {
                ModuleArtifact artifact = null;

                BasicFileAttributes attrs =
                    Files.readAttributes(entry, BasicFileAttributes.class);
                if (attrs.isRegularFile()) {
                    if (entry.toString().endsWith(".jmod")) {
                        artifact = readJMod(entry);
                    } else if (entry.toString().endsWith(".jar")) {
                        artifact = readJar(entry);
                    }
                } else if (attrs.isDirectory()) {
                    artifact = readExploded(entry);
                }

                // module artifact found
                if (artifact != null) {
                    // check that there is only one version of the module
                    // in this directory
                    String name = artifact.descriptor().name();
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
                    cachedModules.put(name, artifact);
                }

            }
        } catch (IOException | UncheckedIOException ioe) {
            // warn for now, needs to be re-examined
            System.err.println(ioe);
        }

    }

    /**
     * Returns a {@code ModuleArtifact} to represent a jmod file on the
     * file system.
     */
    private ModuleArtifact readJMod(Path file) throws IOException {
        // file -> jmod URL
        String s = file.toUri().toURL().toString();
        URL url = new URL("jmod" + s.substring(4));

        ZipFile zf = JModCache.get(url);
        ZipEntry ze = zf.getEntry("classes/" + MODULE_INFO);
        if (ze == null) {
            // jmod without classes/module-info, ignore for now
            return null;
        }

        ModuleInfo mi;
        try (InputStream in = zf.getInputStream(ze)) {
            mi = ModuleInfo.read(in);
        }

        // read module id from extended module descriptor
        String id = mi.name();
        ze = zf.getEntry(EXTENDED_MODULE_DESCRIPTOR_MID);
        if (ze != null) {
            try (InputStream in = zf.getInputStream(ze)) {
                id = new BufferedReader(
                        new InputStreamReader(in, "UTF-8")).readLine();
            }
        }

        Set<String> packages =
                zf.stream()
                        .filter(e -> e.getName().startsWith("classes/") &&
                                e.getName().endsWith(".class"))
                        .map(e -> toPackageName(e))
                        .filter(pkg -> pkg.length() > 0)   // module-info
                        .distinct()
                        .collect(Collectors.toSet());
        // id not null
        return new ModuleArtifact(mi, id, packages, url);
    }

    /**
     * Returns a {@code ModuleArtifact} to represent a module jar on the
     * file system.
     */
    private ModuleArtifact readJar(Path file) throws IOException {
        try (JarFile jf = new JarFile(file.toString())) {
            JarEntry entry = jf.getJarEntry(MODULE_INFO);
            if (entry == null) {
                // not a modular jar
                return null;
            }

            URL url = file.toUri().toURL();

            ModuleInfo mi = ModuleInfo.read(jf.getInputStream(entry));

            Set<String> packages =
                    jf.stream()
                            .filter(e -> e.getName().endsWith(".class"))
                            .map(e -> toPackageName(e))
                            .filter(pkg -> pkg.length() > 0)   // module-info
                            .distinct()
                            .collect(Collectors.toSet());

            return new ModuleArtifact(mi, packages, url);
        }
    }

    /**
     * Returns a {@code ModuleArtifact} to represent an exploded module
     * on the file system.
     */
    private ModuleArtifact readExploded(Path dir) throws IOException {
        Path file = dir.resolve(MODULE_INFO);
        if (Files.notExists((file))) {
            // no module-info in directory
            return null;
        }

        URL url = dir.toUri().toURL();

        ModuleInfo mi;
        try (InputStream in = Files.newInputStream(file)) {
            mi = ModuleInfo.read(in);
        }

        Set<String> packages =
                Files.find(dir, Integer.MAX_VALUE,
                        ((path, attrs) -> attrs.isRegularFile() &&
                                path.toString().endsWith(".class")))
                        .map(path -> toPackageName(dir.relativize(path)))
                        .filter(pkg -> pkg.length() > 0)   // module-info
                        .distinct()
                        .collect(Collectors.toSet());

        return new ModuleArtifact(mi, packages, url);
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
}


