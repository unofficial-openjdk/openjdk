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
 * @summary Test if package p2 in module m2 is not exported, then class p1.c1
 *          in the unnamed module can not access p2.c2 in module m2.
 * @compile p2/c2.java
 * @compile p1/c1.java
 * @build UmodNpkg_PkgNotExp
 * @run main/othervm -Xbootclasspath/a:. UmodNpkg_PkgNotExp
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

// ClassLoader1 --> defines m1 --> packages m1_pinternal
//                  defines m2 --> packages p2, m2_pinternal
//
// m1 can read m2
// package p2 in m2 is not exported
//
// class p1.c1 defined in the unnamed module tries to access p2.c2 defined in m2
// Access denied since p2 is not exported.
//
public class UmodNpkg_PkgNotExp {

    // Create a Layer over the boot layer.
    // Define modules within this layer to test access between
    // publically defined classes within packages of those modules.
    public void createLayerOnBoot() throws Throwable {

        // Define module:     m1
        // Can read:          module m2 and java.base
        // Packages:          m1_pinternal
        // Packages exported: none
        ModuleDescriptor descriptor_m1 =
                new ModuleDescriptor.Builder("m1")
                        .requires("m2")
                        .requires("java.base")
                        .build();
        Set<String> packages_m1 = Stream.of("m1_pinternal").collect(Collectors.toSet());
        ModuleArtifact artifact_m1 = MyModuleArtifact.newModuleArtifact(descriptor_m1, packages_m1);

        // Define module:     m2
        // Can read:          java.base
        // Packages:          p2, m2_pinternal
        // Packages exported: none
        ModuleDescriptor descriptor_m2 =
                new ModuleDescriptor.Builder("m2")
                        .requires("java.base")
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
        Map<ModuleArtifact, ClassLoader> map = new HashMap<>();
        map.put(artifact_m1, MySameClassLoader.loader1);
        map.put(artifact_m2, MySameClassLoader.loader1);

        // Create Layer that contains m1 and m2
        Layer layer = Layer.create(cf, map::get);

        // now use the same loader to load class p1.c1
        Class p1_c1_class = MySameClassLoader.loader1.loadClass("p1.c1");
        try {
            p1_c1_class.newInstance();
            throw new RuntimeException("Failed to get IAE (p2 in m2 is not exported)");
        } catch (IllegalAccessError e) {
          System.out.println(e.getMessage());
          if (!e.getMessage().contains("not exported")) {
              throw new RuntimeException("Wrong message: " + e.getMessage());
          }
        }
    }

    public static void main(String args[]) throws Throwable {
      UmodNpkg_PkgNotExp test = new UmodNpkg_PkgNotExp();
      test.createLayerOnBoot();
    }
}
