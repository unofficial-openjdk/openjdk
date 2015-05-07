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
 * @build NmodNpkg_PkgExpUnqual
 * @run main/othervm -Xbootclasspath/a:. NmodNpkg_PkgExpUnqual
 */

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.Layer;
import java.lang.module.ModuleArtifact;
import java.lang.module.ModuleArtifactFinder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class NmodNpkg_PkgExpUnqual {

    // Create a Layer over the boot layer.
    // Define modules within this layer to test access between
    // publically defined classes within packages of those modules.
    public void createLayerOnBoot() throws Throwable {

        // Define module:     m1
        // Can read:          module m2 and java.base
        // Packages:          p1, m1_pinternal
        // Packages exported: p1 is exported unqualifiedly
        ModuleDescriptor descriptor_m1 =
                new ModuleDescriptor.Builder("m1")
                        .requires("m2")
                        .requires("java.base")
                        .exports("p1")
                        .build();
        Set<String> packages_m1 = Stream.of("p1", "m1_pinternal").collect(Collectors.toSet());
        ModuleArtifact artifact_m1 = MyModuleArtifact.newModuleArtifact(descriptor_m1, packages_m1);

        // Define module:     m2
        // Can read:          java.base
        // Packages:          p2, m2_pinternal
        // Packages exported: p2 is exported unqualifiedly
        ModuleDescriptor descriptor_m2 =
                new ModuleDescriptor.Builder("m2")
                        .requires("java.base")
                        .exports("p2")
                        .build();
        Set<String> packages_m2 = Stream.of("p2", "m2_pinternal").collect(Collectors.toSet());
        ModuleArtifact artifact_m2 = MyModuleArtifact.newModuleArtifact(descriptor_m2, packages_m2);

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

    public static void main(String args[]) throws Throwable {
      NmodNpkg_PkgExpUnqual test = new NmodNpkg_PkgExpUnqual();
      test.createLayerOnBoot();
    }
}
