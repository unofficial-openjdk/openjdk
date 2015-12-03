/*
 * Copyright (c) 2012, 2014, Oracle and/or its affiliates. All rights reserved.
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

import com.sun.tools.classfile.Attribute;
import com.sun.tools.classfile.ClassFile;
import com.sun.tools.classfile.ConstantPoolException;
import com.sun.tools.classfile.Module_attribute;
import com.sun.tools.jdeps.ClassFileReader.ModuleClassReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ModulePath for Java SE and JDK
 */
class ModulePath {
    private final FileSystem fs;
    private final Path mpath;
    private final List<Module> modules;

    ModulePath(Path path) throws IOException {
        this(FileSystems.getDefault(), path);
    }

    ModulePath(FileSystem fs, Path path) throws IOException {
        this.fs = fs;
        this.mpath = path;
        this.modules = initModules();
    }

    List<Module> getModules() {
        return modules;
    }

    /**
     * Finds the module with the given name. Returns null
     * if such module doesn't exist.
     *
     * @param mn module name
     */
    Module findModule(String mn) {
        return modules.stream().map(Module.class::cast)
                .filter(m -> mn.equals(m.name()))
                .findFirst().orElse(null);
    }

    private List<Module> initModules() {
        try {
            return Files.find(mpath, 2, (Path p, BasicFileAttributes att) -> p.endsWith("module-info.class"))
                    .map(this::readModuleInfo)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);

        }
    }

    private Module readModuleInfo(Path minfo) {
        try {
            ClassFile cf = ClassFile.read(minfo);
            int index = cf.getName().lastIndexOf("/module-info");
            String modulename = cf.getName().substring(0, index).replace('/', '.');
            Module_attribute attr = (Module_attribute) cf.getAttribute(Attribute.Module);
            Module.Builder builder = new Module.Builder();
            builder.name(modulename);
            ModuleClassReader cfr = getModuleClassReader(modulename);
            builder.classes(cfr);
            builder.packages(cfr.packages());
            for (int i = 0; i < attr.requires_count; i++) {
                Module_attribute.RequiresEntry entry = attr.requires[i];
                boolean reexport = (entry.requires_flags & Module_attribute.ACC_PUBLIC) != 0;
                String target = entry.getRequires(i, cf.constant_pool).replace('/', '.');
                builder.require(target, reexport);
            }
            for (int i = 0; i < attr.exports_count; i++) {
                Module_attribute.ExportsEntry entry = attr.exports[i];
                String pn = cf.constant_pool.getUTF8Value(entry.exports_index)
                        .replace('/', '.');
                Set<String> targets = new HashSet<>();
                for (int j = 0; j < entry.exports_to_count; j++) {
                    String to = cf.constant_pool.getUTF8Value(entry.exports_to_index[j])
                            .replace('/', '.');
                    targets.add(to);
                }
                builder.export(pn, targets);
            }
            return builder.build();
        } catch (ConstantPoolException e) {
            throw new UncheckedIOException(new IOException(e));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns a ModuleClassReader that only reads classes for the given modulename.
     */
    public ModuleClassReader getModuleClassReader(String modulename)
            throws IOException {
        Path mp = mpath.resolve(modulename);
        if (Files.exists(mp) && Files.isDirectory(mp)) {
            return new ModuleClassReader(fs, modulename, mp);
        } else {
            // aggregator module or os-specific module in jdeps-modules.xml
            // mdir not exist
            return new NonExistModuleReader(fs, modulename, mp);
        }
    }

    static class NonExistModuleReader extends ModuleClassReader {
        private final List<ClassFile> classes = Collections.emptyList();

        private NonExistModuleReader(FileSystem fs, String mn, Path mpath)
                throws IOException {
            super(fs, mn, mpath);
        }

        public ClassFile getClassFile(String name) throws IOException {
            return null;
        }

        public Iterable<ClassFile> getClassFiles() throws IOException {
            return classes;
        }

        public Set<String> packages() {
            return Collections.emptySet();
        }
    }

    private static class SystemModulePath extends ModulePath {
        private static final ModulePath INSTANCE = getInstance();
        private static boolean isJrtAvailable() {
            try {
                FileSystems.getFileSystem(URI.create("jrt:/"));
                return true;
            } catch (ProviderNotFoundException | FileSystemNotFoundException e) {
                return false;
            }
        }

        private static ModulePath getInstance() {
            if (isJrtAvailable()) {
                try {
                    // jrt file system
                    FileSystem fs = FileSystems.getFileSystem(URI.create("jrt:/"));
                    return new SystemModulePath(fs, fs.getPath("/modules"));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                throw new InternalError("jrt file system not available");
            }
        }

        private SystemModulePath(FileSystem fs, Path path) throws IOException {
            super(fs, path);
        }
    }

    private static final List<Module> modulePathModules = new ArrayList<>();
    static List<Module> getModules(Path p) throws IOException {
        List<Module> mods = new ModulePath(p).getModules();
        modulePathModules.addAll(mods);
        return mods;
    }

    static List<Module> getSystemModules() {
        List<Module> mods = SystemModulePath.INSTANCE.getModules();
        Profile.ensureInitialized(SystemModulePath.INSTANCE);
        return mods;
    }
}
