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

/*
 * @test
 * @summary Test that if module1 can read module2, AND package p2 in module2 is
 *          exported unqualifiedly, then class p1.c1 in module1 can read p2.c2 in module2.
 * @compile p2/c2.java
 * @compile p1/c1.java
 * @modules java.base/sun.misc
 * @build NmodNpkg_PkgExpUnqual
 * @run main/othervm -Xbootclasspath/a:. NmodNpkg_PkgExpUnqual
 */

import java.io.*;
import java.net.URI;
import java.lang.reflect.Module;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.lang.module.Configuration;
import java.lang.module.ExtendedModuleDescriptor;
import java.lang.module.Layer;
import java.lang.module.ModuleArtifact;
import java.lang.module.ModuleArtifactFinder;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleReader;

public class NmodNpkg_PkgExpUnqual {

    // Create a Layer over the boot layer.
    // Define modules within this layer to test access between
    // publically defined classes within packages of those modules.
    public void createLayerOnBoot() throws Throwable {

        // Define module:     m1
        // Can read:          module m2 and java.base
        // Packages:          p1, m1_pinternal
        // Packages exported: p1 is exported unqualifiedly
        ExtendedModuleDescriptor descriptor_m1 =
                new ExtendedModuleDescriptor.Builder("m1")
                        .requires(md("m2"))
                        .requires(md("java.base"))
                        .export("p1")
                        .build();
        URI location_m1 = URI.create("module:/" + descriptor_m1.name());
        Set<String> packages_m1 = Stream.of("p1", "m1_pinternal").collect(Collectors.toSet());
        ModuleArtifact artifact_m1 =
            new ModuleArtifact(descriptor_m1, packages_m1, location_m1) {
            @Override
            public ModuleReader open() throws IOException {
                throw new IOException("No module reader for: " + location_m1);
            }
        };

        // Define module:     m2
        // Can read:          java.base
        // Packages:          p2, m2_pinternal
        // Packages exported: p2 is exported unqualifiedly
        ExtendedModuleDescriptor descriptor_m2 =
                new ExtendedModuleDescriptor.Builder("m2")
                        .requires(md("java.base"))
                        .export("p2")
                        .build();
        URI location_m2 = URI.create("module:/" + descriptor_m2.name());
        Set<String> packages_m2 = Stream.of("p2", "m2_pinternal").collect(Collectors.toSet());
        ModuleArtifact artifact_m2 =
            new ModuleArtifact(descriptor_m2, packages_m2, location_m2) {
            @Override
            public ModuleReader open() throws IOException {
                throw new IOException("No module reader for: " + location_m2);
            }
        };

        // Set up a ModuleArtifactFinder containing all modules for this layer.
        ModuleArtifactFinder finder =
                new ModuleArtifactLibrary(artifact_m1, artifact_m2);

        // Resolves a module named "m1" that results in a configuration.  It
        // then augments that configuration with additional modules (and edges) induced
        // by service-use relationships.
        Configuration cf = Configuration.resolve(finder,
                                                 Layer.bootLayer(),
                                                 ModuleArtifactFinder.nullFinder(),
                                                 "m1");

        // map each module to the same class loader for this test
        ClassLoader loader = new ClassLoader() { };
        Map<ModuleArtifact, ClassLoader> map = new HashMap<>();
        map.put(artifact_m1, loader);
        map.put(artifact_m2, loader);

        // Create Layer that contains m1 & m2
        Layer layer = Layer.create(cf, map::get);

        // now use the same loader to load class p1.c1
        Class p1_c1_class = loader.loadClass("p1.c1");
        p1_c1_class.newInstance();
    }

    static Requires md(String dn, Modifier... mods) {
        Set<Modifier> set = new HashSet<>();
        for (Modifier mod: mods)
            set.add(mod);
        return new Requires(set, dn);
    }

    public static void main(String args[]) throws Throwable {
      NmodNpkg_PkgExpUnqual test = new NmodNpkg_PkgExpUnqual();
      test.createLayerOnBoot();
    }
}
