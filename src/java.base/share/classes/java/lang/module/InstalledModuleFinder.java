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

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import jdk.internal.jimage.ImageLocation;
import jdk.internal.jimage.ImageModuleData;
import jdk.internal.jimage.ImageReader;
import jdk.internal.jimage.ImageReaderFactory;
import jdk.internal.module.InstalledModules;
import sun.misc.PerfCounter;

/**
 * A {@code ModuleFinder} that finds modules that are linked into the
 * run-time image.
 *
 * The modules linked into the run-time image are assumed to have the
 * ConcealedPackages attribute.
 */

class InstalledModuleFinder implements ModuleFinder {

    private static final PerfCounter initTime
        = PerfCounter.newPerfCounter("jdk.module.finder.jimage.initTime");
    private static final PerfCounter moduleCount
        = PerfCounter.newPerfCounter("jdk.module.finder.jimage.modules");
    private static final PerfCounter packageCount
        = PerfCounter.newPerfCounter("jdk.module.finder.jimage.packages");
    private static final PerfCounter exportsCount
        = PerfCounter.newPerfCounter("jdk.module.finder.jimage.exports");
    private static final String MODULE_INFO = "module-info.class";

    // the set of modules in the run-time image
    private static final Set<ModuleReference> modules;

    // maps module name to module reference
    private static final Map<String, ModuleReference> nameToModule;

    /**
     * For now, the module references are created eagerly on the assumption
     * that service binding will require all modules to be located.
     */
    static {
        long t0 = System.nanoTime();

        ImageReader imageReader = ImageReaderFactory.getImageReader();
        String[] moduleNames = null;
        boolean fastpath = false;;
        Map<String, ModuleDescriptor> descriptors = InstalledModules.modules();
        if (descriptors.isEmpty()) {
            // InstalledModules.MODULE_NAMES is generated at link time and
            // it's intended to replace ImageModuleData when it is enabled
            // when building jdk image.
            ImageModuleData mdata = new ImageModuleData(imageReader);
            moduleNames = mdata.allModuleNames().toArray(new String[0]);
        } else {
            moduleNames = InstalledModules.MODULE_NAMES;
            fastpath = true;
        }

        int n = moduleNames.length;
        moduleCount.add(n);

        Set<ModuleReference> mods = new HashSet<>(n);
        Map<String, ModuleReference> map = new HashMap<>(n);

        for (String mn : moduleNames) {
            ByteBuffer bb = null;
            try {
                // parse the module-info.class file
                final ModuleDescriptor md;
                if (fastpath) {
                    md = descriptors.get(mn);
                } else {
                    ImageLocation loc = imageReader.findLocation(mn, MODULE_INFO);
                    bb = imageReader.getResourceBuffer(loc);
                    md = ModuleInfo.readIgnoringHashes(bb, null);
                }

                if (!md.name().equals(mn))
                    throw new InternalError();

                URI uri = URI.create("jrt:/" + mn);
                ModuleReference mref
                    = ModuleReferences.newModuleReference(md, uri, null);
                mods.add(mref);
                map.put(mn, mref);

                // counters
                packageCount.add(md.packages().size());
                exportsCount.add(md.exports().size());

            } finally {
                if (bb != null)
                    ImageReader.releaseByteBuffer(bb);
            }
        }

        modules = Collections.unmodifiableSet(mods);
        nameToModule = map;

        initTime.addElapsedTimeFrom(t0);
    }

    InstalledModuleFinder() { }

    @Override
    public Optional<ModuleReference> find(String name) {
        Objects.requireNonNull(name);
        return Optional.ofNullable(nameToModule.get(name));
    }

    @Override
    public Set<ModuleReference> findAll() {
        return modules;
    }

}