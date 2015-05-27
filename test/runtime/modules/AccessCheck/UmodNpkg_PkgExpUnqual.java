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
 * @summary Test if package p2 in module m2 is exported unqualifiedly,
 *          then class p1.c1 in the unnamed module can read p2.c2 in module m2.
 * @compile p2/c2.java
 * @compile p1/c1.java
 * @build UmodNpkg_PkgExpUnqual
 * @run main/othervm -Xbootclasspath/a:. UmodNpkg_PkgExpUnqual
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
//
// m1 can read m2
// package p2 in m2 is exported unqualifiedly
//
// class p1.c1 defined in the unnamed module tries to access p2.c2 defined in m2
// Access allowed, the unnamed module can read all modules and p2 in module
//           m2 which is exported unqualifiedly.

public class UmodNpkg_PkgExpUnqual {

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
                        .conceals("m1_pinternal")
                        .build();
        ModuleReference mref_m1 = MyModuleReference.newModuleReference(descriptor_m1);

        // Define module:     m2
        // Can read:          java.base
        // Packages:          p2, m2_pinternal
        // Packages exported: p2 is exported unqualifiedly
        ModuleDescriptor descriptor_m2 =
                new ModuleDescriptor.Builder("m2")
                        .requires("java.base")
                        .exports("p2")
                        .conceals("m2_pinternal")
                        .build();
        ModuleReference mref_m2 = MyModuleReference.newModuleReference(descriptor_m2);

        // Set up a ModuleFinder containing all modules for this layer.
        ModuleFinder finder =
                new ModuleLibrary(mref_m1, mref_m2);

        // Resolves a module named "m1" that results in a configuration.  It
        // then augments that configuration with additional modules (and edges) induced
        // by service-use relationships.
        Configuration cf = Configuration.resolve(finder,
                                                 Layer.boot(),
                                                 ModuleFinder.empty(),
                                                 "m1");

        // map each module to differing class loaders for this test
        Map<String, ClassLoader> map = new HashMap<>();
        map.put("m1", MySameClassLoader.loader1);
        map.put("m2", MySameClassLoader.loader1);

        // Create Layer that contains m1 & m2
        Layer layer = Layer.create(cf, map::get);

        // now use the same loader to load class p1.c1
        Class p1_c1_class = MySameClassLoader.loader1.loadClass("p1.c1");
        try {
            p1_c1_class.newInstance();
        } catch (IllegalAccessError e) {
            throw new RuntimeException("Test Failed, the unnamed module can access public type p2.c2 since it is exported unqualifiedly");
        }
    }

    public static void main(String args[]) throws Throwable {
      UmodNpkg_PkgExpUnqual test = new UmodNpkg_PkgExpUnqual();
      test.createLayerOnBoot();
    }
}
