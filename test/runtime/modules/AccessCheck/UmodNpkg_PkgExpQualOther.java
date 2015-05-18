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
 * @summary Test that if package p2 in module m2 is exported to module m3,
 *          then class p1.c1 in the unnamed module can not read p2.c2 in module m2.
 * @compile p2/c2.java
 * @compile p1/c1.java
 * @build UmodNpkg_PkgExpQualOther
 * @run main/othervm -Xbootclasspath/a:. UmodNpkg_PkgExpQualOther
 */

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.Layer;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleFinder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

//
// ClassLoader1 --> defines m1 --> packages m1_pinternal
//                  defines m2 --> packages p2, m2_pinternal
//                  defines m3 --> packages p3, m3_pinternal
//
// m1 can read m2
// package p2 in m2 is exported to m3
//
// class p1.c1 defined in m1 tries to access p2.c2 defined in m2
// Access denied since although m1 can read m2, p2 is exported only to m3.
//
public class UmodNpkg_PkgExpQualOther {

    // Create a Layer over the boot layer.
    // Define modules within this layer to test access between
    // publically defined classes within packages of those modules.
    public void createLayerOnBoot() throws Throwable {

        // Define module:     m1 (need to define m1 to establish the Layer successfully)
        // Can read:          module m2 and java.base
        // Packages:          m1_pinternal
        // Packages exported: none
        ModuleDescriptor descriptor_m1 =
                new ModuleDescriptor.Builder("m1")
                        .requires("m2")
                        .requires("java.base")
                        .conceals("m1_pinternal")
                        .build();
        ModuleReference mref_m1 = MyModuleReference.newModuleReference(descriptor_m1);

        // Define module:     m2
        // Can read:          java.base
        // Packages:          p2, m2_pinternal
        // Packages exported: p2 is exported to m3
        ModuleDescriptor descriptor_m2 =
                new ModuleDescriptor.Builder("m2")
                        .requires("java.base")
                        .exports("p2", "m3")
                        .conceals("m2_pinternal")
                        .build();
        ModuleReference mref_m2 = MyModuleReference.newModuleReference(descriptor_m2);

        // Define module:     m3
        // Can read:          java.base
        // Packages:          p3, m3_pinternal
        // Packages exported: none
        ModuleDescriptor descriptor_m3 =
                new ModuleDescriptor.Builder("m3")
                        .requires("java.base")
                        .conceals("m3_pinternal")
                        .build();
        ModuleReference mref_m3 = MyModuleReference.newModuleReference(descriptor_m3);

        // Set up a ModuleFinder containing all modules for this layer.
        ModuleFinder finder =
                new ModuleLibrary(mref_m1, mref_m2, mref_m3);

        // Resolves a module named "m1" that results in a configuration.  It
        // then augments that configuration with additional modules (and edges) induced
        // by service-use relationships.
        Configuration cf = Configuration.resolve(finder,
                                                 Layer.boot(),
                                                 ModuleFinder.empty(),
                                                 "m1");

        // map each module to differing class loaders for this test
        Map<ModuleReference, ClassLoader> map = new HashMap<>();
        map.put(mref_m1, MySameClassLoader.loader1);
        map.put(mref_m2, MySameClassLoader.loader1);
        map.put(mref_m3, MySameClassLoader.loader1);

        // Create Layer that contains m1, m2 and m3
        Layer layer = Layer.create(cf, map::get);

        // now use the same loader to load class p1.c1
        Class p1_c1_class = MySameClassLoader.loader1.loadClass("p1.c1");
        try {
            p1_c1_class.newInstance();
            throw new RuntimeException("Failed to get IAE (p2 in m2 is exported to m3, not unqualifiedly to everyone)");
        } catch (IllegalAccessError e) {
            System.out.println(e.getMessage());
            if (!e.getMessage().contains("not exported")) {
                throw new RuntimeException("Wrong message: " + e.getMessage());
            }
        }
    }

    public static void main(String args[]) throws Throwable {
      UmodNpkg_PkgExpQualOther test = new UmodNpkg_PkgExpQualOther();
      test.createLayerOnBoot();
    }
}
