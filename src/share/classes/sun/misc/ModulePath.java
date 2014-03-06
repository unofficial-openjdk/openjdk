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

package sun.misc;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jdk.jigsaw.module.Module;
import jdk.jigsaw.module.ModuleInfo;
import jdk.jigsaw.module.ModuleLibrary;

/**
 * A module path implementation of {@code ModuleLibrary}. A module path
 * is essentially a PATH of directories containing exploded modules or jmod
 * files. The directories on the PATH are scanned lazily as modules are
 * located via {@code findLocalModule}.
 *
 * @apiNote This class is currently not safe for use by multiple threads.
 */

public class ModulePath extends ModuleLibrary {
    private static final String MODULE_INFO = "module-info.class";

    // the directories on this module path
    private final String[] dirs;
    private int next;

    // the module name to Module map of modules already located
    private final Map<String, Module> cachedModules = new HashMap<>();

    // the module to URL map of modules already located
    private final Map<Module, URL> urls = new HashMap<>();


    public ModulePath(String path, ModuleLibrary parent) {
        super(parent);
        this.dirs = path.split(File.pathSeparator);
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

    /**
     * Returns {@code true} if the module of the given name is already known
     * to the module library.
     */
    private boolean isKnownModule(String name) {
        ModuleLibrary parent = parent();
        if (parent != null && parent.findModule(name) != null)
            return true;
        return cachedModules.containsKey(name);
    }

    /**
     * Returns the URL for the purposes of class and resource loading.
     */
    public URL toURL(Module m) {
        return urls.get(m);
    }

    @Override
    public Set<Module> localModules() {
        // need to ensure that all directories have been scanned
        while (hasNextDirectory()) {
            scanNextDirectory();
        }
        return Collections.unmodifiableSet(urls.keySet());
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
        Set<String> localModules = new HashSet<>();

        Path dirPath = Paths.get(dir);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath)) {
            for (Path entry: stream) {
                ModuleInfo mi = null;
                URL url = null;
                boolean jmod = false;

                BasicFileAttributes attrs =
                    Files.readAttributes(entry, BasicFileAttributes.class);
                if (attrs.isRegularFile() && entry.toString().endsWith(".jmod")) {
                    String s = entry.toUri().toURL().toString();
                    url = new URL("jmod" + s.substring(4));
                    mi = readModuleInfo(url);
                    jmod = true;
                } else if (attrs.isDirectory()) {
                    url = entry.toUri().toURL();
                    mi = readModuleInfo(entry);
                }

                // module-info found
                if (mi != null) {

                    // check that there is only one version of the module
                    // in this directory
                    String name = mi.name();
                    if (localModules.contains(name)) {
                        throw new RuntimeException(dir +
                            " contains more than one version of " + name);
                    }
                    localModules.add(name);

                    // module already in cache (either parent module library
                    // or a previous directory on the path).
                    if (isKnownModule(name))
                        continue;

                    // enumerate the contents and add the Module to the cache
                    List<String> packages =
                        (jmod) ? jmodPackageList(url) : explodedPackageList(entry);
                    Module m = mi.makeModule(packages);
                    cachedModules.put(name, m);
                    urls.put(m, url);
                }

            }
        } catch (IOException | UncheckedIOException ioe) {
            // warn for now, needs to be re-examined
            System.err.println(ioe);
        }

    }

    private ModuleInfo readModuleInfo(URL url) throws IOException {
        assert url.getProtocol().equals("jmod");

        ZipFile zf = JModCache.get(url);
        ZipEntry entry = zf.getEntry("classes/" + MODULE_INFO);
        if (entry != null) {
            try (InputStream in = zf.getInputStream(entry)) {
                return ModuleInfo.read(in);
            }
        } else {
            return null;
        }
    }

    private ModuleInfo readModuleInfo(Path dir) throws IOException {
        Path mi = dir.resolve(MODULE_INFO);
        if (Files.exists(mi)) {
            try (InputStream in = Files.newInputStream(mi)) {
                return ModuleInfo.read(in);
            }
        } else {
            return null;
        }
    }

    private List<String> jmodPackageList(URL url) throws IOException {
        ZipFile zf = JModCache.get(url);
        return zf.stream()
                 .filter(entry -> entry.getName().startsWith("classes/") &&
                         entry.getName().endsWith(".class"))
                 .map(entry -> toPackageName(entry))
                 .filter(pkg -> pkg.length() > 0)   // module-info
                 .distinct()
                 .collect(Collectors.toList());
    }

    /**
     * @throws java.io.IOException
     * @throws java.io.UncheckedIOException
     */
    private List<String> explodedPackageList(Path top) throws IOException {
        return Files.find(top, Integer.MAX_VALUE,
                        ((path, attrs) -> attrs.isRegularFile() &&
                            path.toString().endsWith(".class")))
                    .map(path -> toPackageName(top.relativize(path)))
                    .filter(pkg -> pkg.length() > 0)   // module-info
                    .distinct()
                    .collect(Collectors.toList());
    }

    private String toPackageName(ZipEntry entry) {
        String name = entry.getName();
        assert name.startsWith("classes/") && name.endsWith(".class");
        int index = name.lastIndexOf("/");
        if (index > 7) {
            return name.substring(8, index).replace('/', '.');
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
