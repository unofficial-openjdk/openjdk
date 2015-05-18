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
 * @summary class p1.c1 defined in m1 tries to access p2.c2 defined in the unnamed module.
 *          Access allowed since any module can read the unnamed module. p2's exportability not relevant.
 * @compile p2/c2.java
 * @compile p1/c1.java
 * @build NmodNpkg_UmodNpkg
 * @run main/othervm -Xbootclasspath/a:. NmodNpkg_UmodNpkg
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
// ClassLoader1 --> defines m1 --> packages p1, m1_pinternal
//
// package p1 in m1 is exported unqualifiedly
//
// class p1.c1 defined in m1 tries to access p2.c2 defined in
// in the unnamed module.
// Access allowed since any module can read the unnamed module..
//
public class NmodNpkg_UmodNpkg {

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
                     .requires("java.base")
                     .exports("p1")
                     .conceals("m1_pinternal")
                     .build();
     ModuleReference mref_m1 = MyModuleReference.newModuleReference(descriptor_m1);

     // Set up a ModuleFinder containing all modules for this layer.
     ModuleFinder finder =
             new ModuleLibrary(mref_m1);

     // Resolves a module named "m1" that results in a configuration.  It
     // then augments that configuration with additional modules (and edges) induced
     // by service-use relationships.
     Configuration cf = Configuration.resolve(finder,
                                              Layer.boot(),
                                              ModuleFinder.empty(),
                                              "m1");

     // map each module to the same class loader for this test
     Map<ModuleReference, ClassLoader> map = new HashMap<>();
     map.put(mref_m1, MySameClassLoader.loader1);

     // Create Layer that contains m1 & m2
     Layer layer = Layer.create(cf, map::get);

     // now use loader1 to load class p1.c1 and p2.c2
     Class p1_c1_class = MySameClassLoader.loader1.loadClass("p1.c1");
     try {
         p1_c1_class.newInstance();
     } catch (IllegalAccessError e) {
       throw new RuntimeException("Test Failed, module m1 should be able to access public type p2.c2 defined in the unnamed module");
     }
 }

 public static void main(String args[]) throws Throwable {
   NmodNpkg_UmodNpkg test = new NmodNpkg_UmodNpkg();
   test.createLayerOnBoot();
 }
}
