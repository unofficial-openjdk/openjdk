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

package java.lang.module;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jdk.internal.jimage.ImageLocation;
import jdk.internal.jimage.ImageModuleData;
import jdk.internal.jimage.ImageReader;
import jdk.internal.jimage.ImageReaderFactory;
import sun.misc.PerfCounter;

/**
 * A {@code ModuleFinder} that finds modules that are
 * linked into the modular image.
 */
class InstalledModuleFinder implements ModuleFinder {

    // the module name to reference map of modules already located
    private final Map<String, ModuleReference> cachedModules = new ConcurrentHashMap<>();
    private final Image bootImage;

    InstalledModuleFinder() {
        long t0 = System.nanoTime();
        bootImage = new Image();
        initTime.addElapsedTimeFrom(t0);
    }

    private ModuleReference toModuleReference(String name) {
        long t0 = System.nanoTime();
        try {
            ModuleDescriptor md = bootImage.readDescriptor(name);
            URI location = URI.create("jrt:/" + name);
            ModuleReference mref =
                ModuleReferences.newModuleReference(md, location, null);
            installedModulesCount.increment();
            installedModulesTime.addElapsedTimeFrom(t0);
            return mref;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean isModularImage() {
        String home = System.getProperty("java.home");
        Path libModules = Paths.get(home, "lib", "modules");
        return Files.isDirectory(libModules);
    }

    @Override
    public ModuleReference find(String name) {
        if (!bootImage.modules.contains(name)) {
            return null;
        }

        // try cached modules
        ModuleReference m = cachedModules.get(name);
        if (m != null)
            return m;

        // create ModuleReference from module descriptor
        m = toModuleReference(name);
        ModuleReference previous = cachedModules.putIfAbsent(name, m);
        if (previous == null) {
            return m;
        } else {
            return previous;
        }
    }

    @Override
    public Set<ModuleReference> allModules() {
        // ensure ModuleReference for all modules are created
        return bootImage.modules.stream()
                .map(this::find).collect(Collectors.toSet());
    }

    class Image {
        private static final String MODULE_INFO = "module-info.class";

        final ImageReader imageReader;
        final ImageModuleData mdata;
        final Set<String> modules;

        Image() {
            this.imageReader = ImageReaderFactory.getImageReader();
            this.mdata = new ImageModuleData(imageReader);
            this.modules = mdata.allModuleNames();
        }

        private Set<String> packages(String name) {
            return mdata.moduleToPackages(name).stream()
                .map(pn -> pn.replace('/', '.'))
                .collect(Collectors.toSet());
        }

        /**
         * Returns the module descriptor for the given module in the
         * image.
         */
        ModuleDescriptor readDescriptor(String name) throws IOException {
            String rn = "/" + name + "/" + MODULE_INFO;
            ImageLocation loc = imageReader.findLocation(rn);
            ByteBuffer bb = imageReader.getResourceBuffer(loc);
            try {
                return ModuleInfo.readIgnoringHashes(bb, () -> packages(name));
            } finally {
                ImageReader.releaseByteBuffer(bb);
            }
        }
    }

    private static final PerfCounter initTime =
            PerfCounter.newPerfCounter("jdk.module.installedModules.initTime");
    private static final PerfCounter installedModulesTime =
            PerfCounter.newPerfCounter("jdk.module.installedModules.initReferenceTime");
    private static final PerfCounter installedModulesCount =
            PerfCounter.newPerfCounter("jdk.module.installedModules.mrefs");

}
