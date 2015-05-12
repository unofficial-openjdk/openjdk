/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

import java.lang.module.Configuration;
import java.lang.module.Layer;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.Module;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

/**
 * Basic tests for java.lang.module.Layer
 */

@Test
public class LayerTest {

    /**
     * Exercise Layer#bootLayer
     */
    public void testBootLayer() {
        Layer bootLayer = Layer.bootLayer();

        // configuration
        Configuration cf = bootLayer.configuration();
        assertTrue(cf.findDescriptor("java.base").exports()
                   .stream().anyMatch(e -> (e.source().equals("java.lang")
                                            && !e.targets().isPresent())));

        // findLoader
        assertTrue(bootLayer.findLoader("java.base") == null);

        // findModule
        Module base = Object.class.getModule();
        assertTrue(bootLayer.findModule("java.base") == base);

        // parent
        assertTrue(bootLayer.parent() == Layer.emptyLayer());
    }

    /**
     * Exercise Layer#emptyLayer
     */
    public void testEmptyLayer() {
        Layer emptyLayer = Layer.emptyLayer();

        // configuration
        assertTrue(emptyLayer.configuration() == null);

        // findLoader
        try {
            ClassLoader loader = emptyLayer.findLoader("java.base");
            assertTrue(false);
        } catch (IllegalArgumentException ignore) { }

        // findModule
        assertTrue(emptyLayer.findModule("java.base") == null);

        // parent
        assertTrue(emptyLayer.parent() == null);
    }

    /**
     * Exercise Layer#create, created on an empty layer
     */
    public void testLayerOnEmpty() {
        ModuleDescriptor descriptor1 =
                new ModuleDescriptor.Builder("m1")
                        .requires("m2")
                        .exports("p1")
                        .build();

        ModuleDescriptor descriptor2 =
                new ModuleDescriptor.Builder("m2")
                        .requires("m3")
                        .build();

        ModuleDescriptor descriptor3 =
                new ModuleDescriptor.Builder("m3")
                        .build();

        ModuleFinder finder =
                new ModuleLibrary(descriptor1, descriptor2, descriptor3);

        Configuration cf = Configuration.resolve(finder,
                                                 Layer.emptyLayer(),
                                                 ModuleFinder.empty(),
                                                 "m1");

        // map each module to its own class loader for this test
        ClassLoader loader1 = new ClassLoader() { };
        ClassLoader loader2 = new ClassLoader() { };
        ClassLoader loader3 = new ClassLoader() { };
        Map<ModuleReference, ClassLoader> map = new HashMap<>();
        map.put(cf.findReference("m1"), loader1);
        map.put(cf.findReference("m2"), loader2);
        map.put(cf.findReference("m3"), loader3);

        Layer layer = Layer.create(cf, map::get);

        // configuration
        assertTrue(layer.configuration() == cf);
        assertTrue(layer.configuration().descriptors().size() == 3);

        // findLoader
        assertTrue(layer.findLoader("m1") == loader1);
        assertTrue(layer.findLoader("m2") == loader2);
        assertTrue(layer.findLoader("m3") == loader3);
        try {
            ClassLoader loader = layer.findLoader("godot");
            assertTrue(false);
        } catch (IllegalArgumentException ignore) { }

        // findModule
        assertTrue(layer.findModule("m1").getName().equals("m1"));
        assertTrue(layer.findModule("m2").getName().equals("m2"));
        assertTrue(layer.findModule("m3").getName().equals("m3"));
        assertTrue(layer.findModule("godot") == null);

        // parent
        assertTrue(layer.parent() == Layer.emptyLayer());
    }

    /**
     * Exercise Layer#create, created over the boot layer
     */
    public void testLayerOnBoot() {
        ModuleDescriptor descriptor1 =
                new ModuleDescriptor.Builder("m1")
                        .requires("m2")
                        .requires("java.base")
                        .exports("p1")
                        .build();

        ModuleDescriptor descriptor2 =
                new ModuleDescriptor.Builder("m2")
                        .requires("java.base")
                        .build();

        ModuleFinder finder =
                new ModuleLibrary(descriptor1, descriptor2);

        Configuration cf = Configuration.resolve(finder,
                                                 Layer.bootLayer(),
                                                 ModuleFinder.empty(),
                                                 "m1");

        ClassLoader loader = new ClassLoader() { };

        Layer layer = Layer.create(cf, m -> loader);

        // configuration
        assertTrue(layer.configuration() == cf);
        assertTrue(layer.configuration().descriptors().size() == 2);

        // findLoader
        assertTrue(layer.findLoader("m1") == loader);
        assertTrue(layer.findLoader("m2") == loader);
        assertTrue(layer.findLoader("java.base") == null);

        // findModule
        assertTrue(layer.findModule("m1").getName().equals("m1"));
        assertTrue(layer.findModule("m2").getName().equals("m2"));
        assertTrue(layer.findModule("java.base") == Object.class.getModule());
        // parent

        assertTrue(layer.parent() == Layer.bootLayer());
    }

    public void testLayerOnLayer() {
        // TBD
    }

}
