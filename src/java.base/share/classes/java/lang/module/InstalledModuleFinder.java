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
import sun.misc.PerfCounter;

/**
 * A {@code ModuleFinder} that finds modules that are linked into the
 * run-time image.
 *
 * The modules linked into the run-time image are assumed to have the
 * ConcealedPackages attribute.
 */

class InstalledModuleFinder implements ModuleFinder {

    private static final String MODULE_INFO = "module-info.class";

    // the set of modules in the run-time image
    private final Set<ModuleReference> modules;

    // maps module name to module reference
    private final Map<String, ModuleReference> nameToModule;

    /**
     * Creates the ModuleFinder. For now, the module references are created
     * eagerly on the assumption that service binding will require all
     * modules to be located.
     */
    InstalledModuleFinder() {
        long t0 = System.nanoTime();

        ImageReader imageReader = ImageReaderFactory.getImageReader();

        ImageModuleData mdata = new ImageModuleData(imageReader);
        Set<String> moduleNames = mdata.allModuleNames();

        int n = moduleNames.size();

        moduleCount.add(n);

        Set<ModuleReference> modules = new HashSet<>(n);
        Map<String, ModuleReference> nameToModule = new HashMap<>(n);

        try {
            for (String mn : moduleNames) {
                ImageLocation loc = imageReader.findLocation(mn, MODULE_INFO);
                ByteBuffer bb = imageReader.getResourceBuffer(loc);
                try {

                    // parse the module-info.class file and create the
                    // module reference.
                    ModuleDescriptor descriptor
                        = ModuleInfo.readIgnoringHashes(bb, null);
                    URI uri = URI.create("jrt:/" + mn);
                    ModuleReference mref
                        = ModuleReferences.newModuleReference(descriptor,
                                                              uri,
                                                              null);
                    modules.add(mref);
                    nameToModule.put(mn, mref);

                    // counters
                    packageCount.add(descriptor.packages().size());
                    exportsCount.add(descriptor.exports().size());

                } finally {
                    ImageReader.releaseByteBuffer(bb);
                }
            }
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }

        this.modules = Collections.unmodifiableSet(modules);
        this.nameToModule = nameToModule;

        initTime.addElapsedTimeFrom(t0);
    }

    @Override
    public Optional<ModuleReference> find(String name) {
        Objects.requireNonNull(name);
        return Optional.ofNullable(nameToModule.get(name));
    }

    @Override
    public Set<ModuleReference> findAll() {
        return modules;
    }

    private static final PerfCounter initTime
        = PerfCounter.newPerfCounter("jdk.module.finder.jimage.initTime");
    private static final PerfCounter moduleCount
        = PerfCounter.newPerfCounter("jdk.module.finder.jimage.modules");
    private static final PerfCounter packageCount
        = PerfCounter.newPerfCounter("jdk.module.finder.jimage.packages");
    private static final PerfCounter exportsCount
        = PerfCounter.newPerfCounter("jdk.module.finder.jimage.exports");
}