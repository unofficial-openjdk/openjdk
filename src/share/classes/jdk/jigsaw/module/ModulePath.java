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
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.InputStream;
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

import sun.misc.JModCache;

/**
 * A module path used for locating modules. For example a module path may be
 * backed by a sequence of directories on the file system that contain
 * module artifacts.
 *
 * {@code ModulePath}s can be arranged in a sequence. When locating a module
 * that is not found then the next {@code ModulePath} in the sequence is
 * searched.
 */

public abstract class ModulePath {

    // the next module path, can be {@code null}
    private final ModulePath next;

    protected ModulePath(ModulePath next) {
        this.next = next;
    }

    protected ModulePath() {
        this(null);
    }

    /**
     * Returns the next module path, may be {@code null}.
     */
    public final ModulePath next() {
        return next;
    }

    /**
     * Locates a module of the given name in this module path. Returns
     * {@code null} if not found.
     */
    public abstract Module findLocalModule(String name);

    /**
     * Locates a module of the given name. If the module is not found in this
     * module path then the next module path is searched.
     */
    public final Module findModule(String name) {
        Module m = findLocalModule(name);
        if (m == null && next != null)
            m = next.findModule(name);
        return m;
    }

    /**
     * Returns the set of modules that are local to this module path.
     */
    public abstract Set<Module> localModules();

    /**
     * Returns the set of all modules in this module path and all modules
     * paths that is is chained to.
     */
    public final Set<Module> allModules() {
        if (next == null)
            return localModules();
        Set<Module> result = new HashSet<>();
        result.addAll(next.allModules());
        result.addAll(localModules());
        return result;
    }

    /**
     * Returns a {@code URL} to locate the given {@code Module} in this module
     * path. Returns {@code null} if not found.
     */
    public abstract URL localLocationOf(Module m);

    /**
     * Returns a {@code URL} to locate the given {@code Module}. if the module
     * is not found in this module path then the next module path is searched.
     */
    public final URL locationOf(Module m) {
        URL url = localLocationOf(m);
        if (url == null && next != null)
            url = next.localLocationOf(m);
        return url;
    }

    /**
     * Creates a new {@code ModulePath} that is the equivalent to joining the
     * given module path to tail of this module path. In order words, searching
     * the resulting module path for a module will first search this module path;
     * if not found then the module path specified by {@code tail} will be
     * searched.
     */
    public final ModulePath join(ModulePath tail) {
        ModulePath head = this;
        return new ModulePath(tail) {
            @Override
            public Module findLocalModule(String name) {
                return head.findModule(name);
            }
            @Override
            public Set<Module> localModules() {
                return head.allModules();
            }
            @Override
            public URL localLocationOf(Module m) {
                return head.locationOf(m);
            }
        };
    }

    /**
     * Creates a {@code ModulePath} to represent the module path of a runtime
     * that has the given modules linked-in into the runtime.
     */
    public static ModulePath installed(Module... mods) {
        return new InstalledModulePath(mods);
    }

    /**
     * Creates a {@code ModulePath} that locates modules on the file system by
     * searching a {@code PATH} that is a a sequence of directories containing
     * module artifacts ({@code jmod}, modular JAR, exploded modules).
     *
     * @param path The sequence of directories, separated by the system-dependent
     *             path-separator
     * @param next The next {@code ModulePath}, may be {@code null}
     */
    public static ModulePath fromPath(String path, ModulePath next) {
        String[] dirs = path.split(File.pathSeparator);
        return new FileSystemModulePath(dirs, next);
    }

    /**
     * Creates a {@code ModulePath} that locates modules on the file system by
     * searching a {@code PATH} that is a a sequence of directories containing
     * module artifacts.
     *
     * @param path The sequence of directories, separated by the system-dependent
     *             path-separator
     */
    public static ModulePath fromPath(String path) {
        return fromPath(path, null);
    }

    /**
     * Returns an empty {@code ModulePath}.
     */
    public static ModulePath emptyModulePath() {
        return new ModulePath(null) {
            @Override
            public Module findLocalModule(String name) { return null; }
            @Override
            public Set<Module> localModules() { return Collections.emptySet(); }
            @Override
            public URL localLocationOf(Module m) { return null; }
        };
    }
}

/**
 * A module path of the modules installed in the runtime image.
 */
class InstalledModulePath extends ModulePath {
    private final Set<Module> modules = new HashSet<>();
    private final Map<String, Module> namesToModules = new HashMap<>();

    InstalledModulePath(Module... mods) {
        for (Module m: mods) {
            modules.add(m);
            String name = m.id().name();
            if (namesToModules.containsKey(name))
                throw new IllegalArgumentException(name + ": more than one");
            namesToModules.put(name, m);
        }
    }

    @Override
    public Module findLocalModule(String name) {
        return namesToModules.get(name);
    }

    @Override
    public Set<Module> localModules() {
        return Collections.unmodifiableSet(modules);
    }

    @Override
    public URL localLocationOf(Module m) {
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

    public FileSystemModulePath(String[] dirs, ModulePath next) {
        super(next);
        this.dirs = dirs; // no need to clone
    }

    @Override
    public Module findLocalModule(String name) {
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
    public Set<Module> localModules() {
        // need to ensure that all directories have been scanned
        while (hasNextDirectory()) {
            scanNextDirectory();
        }
        return Collections.unmodifiableSet(urls.keySet());
    }

    @Override
    public URL localLocationOf(Module m) {
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
