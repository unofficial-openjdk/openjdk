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
import java.io.ObjectInputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
 * A module path for locating modules.
 *
 * <p> A {@code ModulePath} is conceptually a set of modules or a sequence
 * of sets. An important property is that a {@code ModulePath} admits to at most
 * one module with a given name. A {@code ModulePath} that is a sequence of
 * directories for example, will locate the first occurrence of a module and
 * ignores other modules of that name that appear in directories later in the
 * sequence. </p>
 *
 * <p> A {@code ModulePath} is a concrete implementation of class. In addition,
 * this class defines a static method to obtain a module path of the {@link
 * #installedModules() installed-modules} and a static method to create a
 * module path that is backed by a sequence of directories on the file system
 * containing module artifacts. </p>
 *
 * <pre>{@code
 *     ModulePath mp = ModulePath.ofDirectories("dir1", "dir2", "dir3");
 * }</pre>
 *
 * @apiNote The eventual API will need to define how errors are handled, say
 * for example findModule lazily searching the module path and finding two
 * modules of the same name in the same directory.
 */

public abstract class ModulePath {
    /**
     * Initializes a new instance of this class.
     */
    protected ModulePath() { }

    /**
     * Locates a module of the given name in this module path.
     *
     * @return the module or {@code null} if not found.
     */
    public abstract Module findModule(String name);

    /**
     * Returns the set of all modules in this module path.
     */
    public abstract Set<Module> allModules();

    /**
     * Returns a {@code URL} to locate the given {@code Module} in this module
     * path.
     *
     * @return the {@code URL} or {@code null} if not found.
     */
    public abstract URL locationOf(Module m);

    /**
     * Creates a new {@code ModulePath} that is the equivalent to joining the
     * given module path to end of this module path. In order words, searching
     * the resulting module path for a module will first search this module path;
     * if not found then the module path specified by {@code tail} will be
     * searched.
     *
     * @return the new module path
     */
    public final ModulePath join(ModulePath tail) {
        ModulePath head = this;
        return new ModulePath() {
            @Override
            public Module findModule(String name) {
                Module m = head.findModule(name);
                if (m == null)
                    m = tail.findModule(name);
                return m;
            }
            @Override
            public Set<Module> allModules() {
                Set<Module> result = new HashSet<>();
                // order is important as head overrides tail
                result.addAll(tail.allModules());
                result.addAll(head.allModules());
                return result;
            }
            @Override
            public URL locationOf(Module m) {
                URL u = head.locationOf(m);
                if (u == null)
                    u = tail.locationOf(m);
                return u;
            }
        };
    }

    /**
     * Returns a module-path of the installed modules, these are the modules
     * that are linked into the runtime image.
     *
     * @implNote For now, the module-path is backed by the contents of {@code
     * modules.jimage}, or in the case a developer build then it is backed by
     * the module descriptors found in the {@code lib/modules/$m/*}.
     */
    public static ModulePath installedModules() {
        if (SystemModulePath.exists()) {
            return new SystemModulePath();
        } else {
            String home = System.getProperty("java.home");
            Path mlib = Paths.get(home, "modules");
            if (Files.isDirectory(mlib)) {
                return ModulePath.ofDirectories(mlib.toString());
            } else {
                System.err.println("WARNING: " + mlib.toString() +
                    " not found or not a directory");
                return ModulePath.ofDirectories();
            }
        }
    }

    /**
     * Creates a {@code ModulePath} that locates modules on the file system by
     * searching a sequence of directories containing module artifacts
     * ({@code jmod}, modular JAR, exploded modules).
     *
     * @apiNote This method needs to define how the returned {@code ModulePath}
     * handles I/O and other errors (a ClassFormatError when parsing a
     * module-info.class for example).
     */
    public static ModulePath ofDirectories(String... dirs) {
        return new FileSystemModulePath(dirs);
    }
}

/**
 * System module path of a modular image
 */
class SystemModulePath extends ModulePath {
    private final Set<Module> modules;
    private final Map<String, Module> namesToModules = new HashMap<>();

    SystemModulePath() {
        this.modules = modules();
        for (Module m : modules) {
            String name = m.id().name();
            namesToModules.putIfAbsent(name, m);
        }
    }

    private static Path imageModulesPath() {
        String home = System.getProperty("java.home");
        return Paths.get(home, "lib", "modules", ImageModules.FILE);
    }

    private Set<Module> modules() {
        try (InputStream in = Files.newInputStream(imageModulesPath())) {
            return ImageModules.load(in).modules();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean exists() {
        return Files.isRegularFile(imageModulesPath());
    }

    @Override
    public Module findModule(String name) {
        return namesToModules.get(name);
    }

    @Override
    public Set<Module> allModules() {
        return Collections.unmodifiableSet(modules);
    }

    @Override
    public URL locationOf(Module m) {
        if (!modules.contains(m))
            return null;
        try {
            return URI.create("module:///" + m.id()).toURL();
        } catch (MalformedURLException e) {
            throw new InternalError(e);
        }
    }
}

/**
 * A {@code ModulePath} implementation that locates modules on the file system
 * by searching a sequence of directories for jmod, modular JAR or exploded
 * modules.
 *
 * @apiNote This class is currently not safe for use by multiple threads.
 */
class FileSystemModulePath extends ModulePath {
    private static final String MODULE_INFO = "module-info.class";

    // module id in extended module descriptor
    private static final String EXTENDED_MODULE_DESCRIPTOR_MID = "module/id";

    // the directories on this module path
    private final String[] dirs;
    private int next;

    // the module name to Module map of modules already located
    private final Map<String, Module> cachedModules = new HashMap<>();

    // the module to URL map of modules already located
    private final Map<Module, URL> urls = new HashMap<>();

    public FileSystemModulePath(String... dirs) {
        this.dirs = dirs; // no need to clone
    }

    @Override
    public Module findModule(String name) {
        // try cached modules
        Module m = cachedModules.get(name);
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
    public Set<Module> allModules() {
        // need to ensure that all directories have been scanned
        while (hasNextDirectory()) {
            scanNextDirectory();
        }
        return Collections.unmodifiableSet(urls.keySet());
    }

    @Override
    public URL locationOf(Module m) {
        URL url = urls.get(m);
        if (url != null)
            return url;

        // the module may be in directories that we haven't scanned yet
        while (hasNextDirectory()) {
            scanNextDirectory();
            url = urls.get(m);
            if (url != null)
                return url;
        }
        return null;
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
            String dir = dirs[next++];
            scan(dir);
        }
    }

    /**
     * Scans the given directory for jmod or exploded modules. For each module
     * found then it enumerates its contents and creates a {@code Module} and
     * adds it (and its URL) to the cache.
     */
    private void scan(String dir) {
        // the set of module names found in this directory
        Set<String> namesInThisDirectory = new HashSet<>();

        Path dirPath = Paths.get(dir);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath)) {
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
                    String name = artifact.moduleName();
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
                    Module m = artifact.makeModule();
                    cachedModules.put(name, m);
                    urls.put(m, artifact.url());
                }

            }
        } catch (IOException | UncheckedIOException ioe) {
            // warn for now, needs to be re-examined
            System.err.println(ioe);
        }

    }

    /**
     * A module artifact on the file system
     */
    private static class ModuleArtifact {
        final URL url;
        final String id;
        final ModuleInfo mi;
        final Collection<String> packages;

        ModuleArtifact(URL url, String id, ModuleInfo mi, Collection<String> packages) {
            this.url = url;
            this.id = id;
            this.mi = mi;
            this.packages = packages;
        }

        ModuleArtifact(URL url, ModuleInfo mi, Collection<String> packages) {
            this(url, mi.name(), mi, packages);
        }

        URL url() { return url; }
        String moduleName() { return mi.name(); }
        Iterable<String> packages() { return packages; }
        Module makeModule() { return mi.makeModule(id, packages); }
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

        List<String> packages =
            zf.stream()
              .filter(e -> e.getName().startsWith("classes/") &&
                      e.getName().endsWith(".class"))
              .map(e -> toPackageName(e))
              .filter(pkg -> pkg.length() > 0)   // module-info
              .distinct()
              .collect(Collectors.toList());

        return new ModuleArtifact(url, id, mi, packages);
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

            List<String> packages =
                jf.stream()
                  .filter(e -> e.getName().endsWith(".class"))
                  .map(e -> toPackageName(e))
                  .filter(pkg -> pkg.length() > 0)   // module-info
                  .distinct()
                  .collect(Collectors.toList());

            return new ModuleArtifact(url, mi, packages);
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

        List<String> packages =
            Files.find(dir, Integer.MAX_VALUE,
                        ((path, attrs) -> attrs.isRegularFile() &&
                            path.toString().endsWith(".class")))
                  .map(path -> toPackageName(dir.relativize(path)))
                  .filter(pkg -> pkg.length() > 0)   // module-info
                  .distinct()
                  .collect(Collectors.toList());

        return new ModuleArtifact(url, mi, packages);
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
