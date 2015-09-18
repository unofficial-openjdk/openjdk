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

/**
 * @test
 * @library ../../lib
 * @build LayerTest ModuleUtils
 * @compile layertest/Test.java
 * @run testng LayerTest
 * @summary Basic tests for java.lang.reflect.Layer
 */

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Layer;
import java.lang.reflect.LayerInstantiationException;
import java.lang.reflect.Module;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class LayerTest {

    /**
     * Exercise Layer.boot()
     */
    public void testBoot() {
        Layer bootLayer = Layer.boot();

        // configuration
        Configuration cf = bootLayer.configuration().get();
        assertTrue(cf.findDescriptor("java.base").get().exports()
                   .stream().anyMatch(e -> (e.source().equals("java.lang")
                                            && !e.targets().isPresent())));

        // modules
        Set<Module> modules = bootLayer.modules();
        assertTrue(modules.contains(Object.class.getModule()));
        int count = (int) modules.stream().map(Module::getName).count();
        assertEquals(count, modules.size()); // module names are unique

        // findModule
        Module base = Object.class.getModule();
        assertTrue(bootLayer.findModule("java.base").get() == base);
        assertTrue(base.getLayer() == bootLayer);

        // findLoader
        assertTrue(bootLayer.findLoader("java.base") == null);

        // parent
        assertTrue(bootLayer.parent().get() == Layer.empty());
    }


    /**
     * Exercise Layer.empty()
     */
    public void testEmpty() {
        Layer emptyLayer = Layer.empty();

        // configuration
        assertFalse(emptyLayer.configuration().isPresent());

        // modules
        assertTrue(emptyLayer.modules().isEmpty());

        // findModule
        assertFalse(emptyLayer.findModule("java.base").isPresent());

        // findLoader
        try {
            ClassLoader loader = emptyLayer.findLoader("java.base");
            assertTrue(false);
        } catch (IllegalArgumentException ignore) { }

        // parent
        assertTrue(!emptyLayer.parent().isPresent());
    }


    /**
     * Exercise Layer.create, created on an empty layer
     */
    public void testLayerOnEmpty() {
        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .requires("m2")
                .exports("p1")
                .build();

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .requires("m3")
                .build();

        ModuleDescriptor descriptor3
            = new ModuleDescriptor.Builder("m3")
                .build();

        ModuleFinder finder
            = ModuleUtils.finderOf(descriptor1, descriptor2, descriptor3);

        Configuration cf = Configuration.resolve(finder,
                                                 Layer.empty(),
                                                 ModuleFinder.empty(),
                                                 "m1");

        // map each module to its own class loader for this test
        ClassLoader loader1 = new ClassLoader() { };
        ClassLoader loader2 = new ClassLoader() { };
        ClassLoader loader3 = new ClassLoader() { };
        Map<String, ClassLoader> map = new HashMap<>();
        map.put("m1", loader1);
        map.put("m2", loader2);
        map.put("m3", loader3);

        Layer layer = Layer.create(cf, map::get);

        // configuration
        assertTrue(layer.configuration().get() == cf);
        assertTrue(layer.configuration().get().descriptors().size() == 3);

        // modules
        Set<Module> modules = layer.modules();
        assertTrue(modules.size() == 3);
        Set<String> names = modules.stream()
            .map(Module::getName)
            .collect(Collectors.toSet());
        assertTrue(names.contains("m1"));
        assertTrue(names.contains("m2"));
        assertTrue(names.contains("m3"));

        // findModule
        Module m1 = layer.findModule("m1").get();
        Module m2 = layer.findModule("m2").get();
        Module m3 = layer.findModule("m3").get();
        assertEquals(m1.getName(), "m1");
        assertEquals(m2.getName(), "m2");
        assertEquals(m3.getName(), "m3");
        assertTrue(m1.getLayer() == layer);
        assertTrue(m2.getLayer() == layer);
        assertTrue(m3.getLayer() == layer);
        assertTrue(modules.contains(m1));
        assertTrue(modules.contains(m2));
        assertTrue(modules.contains(m3));
        assertFalse(layer.findModule("godot").isPresent());

        // findLoader
        assertTrue(layer.findLoader("m1") == loader1);
        assertTrue(layer.findLoader("m2") == loader2);
        assertTrue(layer.findLoader("m3") == loader3);
        try {
            ClassLoader loader = layer.findLoader("godot");
            assertTrue(false);
        } catch (IllegalArgumentException ignore) { }

        // parent
        assertTrue(layer.parent().get() == Layer.empty());
    }


    /**
     * Exercise Layer.create, created over the boot layer
     */
    public void testLayerOnBoot() {
        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .requires("m2")
                .requires("java.base")
                .exports("p1")
                .build();

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .requires("java.base")
                .build();

        ModuleFinder finder
            = ModuleUtils.finderOf(descriptor1, descriptor2);

        Configuration cf = Configuration.resolve(finder,
                                                 Layer.boot(),
                                                 ModuleFinder.empty(),
                                                 "m1");

        ClassLoader loader = new ClassLoader() { };

        Layer layer = Layer.create(cf, mn -> loader);

        // configuration
        assertTrue(layer.configuration().get() == cf);
        assertTrue(layer.configuration().get().descriptors().size() == 2);

        // modules
        Set<Module> modules = layer.modules();
        assertTrue(modules.size() == 2);
        Set<String> names = modules.stream()
            .map(Module::getName)
            .collect(Collectors.toSet());
        assertTrue(names.contains("m1"));
        assertTrue(names.contains("m2"));

        // findModule
        Module m1 = layer.findModule("m1").get();
        Module m2 = layer.findModule("m2").get();
        assertEquals(m1.getName(), "m1");
        assertEquals(m2.getName(), "m2");
        assertTrue(m1.getLayer() == layer);
        assertTrue(m2.getLayer() == layer);
        assertTrue(modules.contains(m1));
        assertTrue(modules.contains(m2));
        assertTrue(layer.findModule("java.base").get() == Object.class.getModule());
        assertFalse(layer.findModule("godot").isPresent());

        // findLoader
        assertTrue(layer.findLoader("m1") == loader);
        assertTrue(layer.findLoader("m2") == loader);
        assertTrue(layer.findLoader("java.base") == null);

        // parent
        assertTrue(layer.parent().get() == Layer.boot());
    }


    /**
     * Layer.create with a configuration of two modules that have the same
     * module-private package.
     */
    public void testSameConcealedPackage() {
        ModuleDescriptor descriptor1
            =  new ModuleDescriptor.Builder("m1")
                .requires("m2")
                .conceals("p")
                .build();

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .conceals("p")
                .build();

        ModuleFinder finder
            = ModuleUtils.finderOf(descriptor1, descriptor2);

        Configuration cf
            = Configuration.resolve(finder, Layer.empty(), ModuleFinder.empty(), "m1");
        assertTrue(cf.descriptors().size() == 2);

        // one loader per module, should be okay
        Layer.create(cf, mn -> new ClassLoader() { });

        // same class loader
        try {
            ClassLoader loader = new ClassLoader() { };
            Layer.create(cf, mn -> loader);
            assertTrue(false);
        } catch (LayerInstantiationException expected) { }
    }


    /**
     * Layer.create with a configuration with a partitioned graph. The same
     * package is exported in both partitions.
     */
    public void testSameExportInPartitionedGraph() {

        // m1 reads m2, m2 exports p to m1
        ModuleDescriptor descriptor1
            =  new ModuleDescriptor.Builder("m1")
                .requires("m2")
                .build();
        ModuleDescriptor descriptor2
            =  new ModuleDescriptor.Builder("m2")
                .exports("p", "m1")
                .build();

        // m3 reads m4, m4 exports p to m3
        ModuleDescriptor descriptor3
            =  new ModuleDescriptor.Builder("m3")
                .requires("m4")
                .build();
        ModuleDescriptor descriptor4
            =  new ModuleDescriptor.Builder("m4")
                .exports("p", "m3")
                .build();

        ModuleFinder finder
            = ModuleUtils.finderOf(descriptor1,
                                   descriptor2,
                                   descriptor3,
                                   descriptor4);

        Configuration cf
            = Configuration.resolve(finder, Layer.empty(), ModuleFinder.empty(),
                                    "m1", "m3");
        assertTrue(cf.descriptors().size() == 4);

        // one loader per module
        Layer.create(cf, mn -> new ClassLoader() {} );

        // m1 & m2 in one loader, m3 & m4 in another loader
        ClassLoader loader1 = new ClassLoader() { };
        ClassLoader loader2 = new ClassLoader() { };
        Map<String, ClassLoader> map = new HashMap<>();
        map.put("m1", loader1);
        map.put("m2", loader1);
        map.put("m3", loader2);
        map.put("m3", loader2);
        Layer.create(cf, map::get);

        // same loader
        try {
            ClassLoader loader = new ClassLoader() { };
            Layer.create(cf, mn -> loader);
            assertTrue(false);
        } catch (LayerInstantiationException expected) { }
    }


    /**
     * Layer.create with a configuration that contains a module that has a
     * concealed package that is the same name as a non-exported package
     * in a parent layer.
     */
    public void testConcealSamePackageAsBootLayer() {
        ModuleDescriptor descriptor
            = new ModuleDescriptor.Builder("m1")
               .requires("java.base")
               .conceals("sun.launcher")
               .build();

        ModuleFinder finder = ModuleUtils.finderOf(descriptor);

        Configuration cf
            = Configuration.resolve(finder, Layer.boot(), ModuleFinder.empty(), "m1");
        assertTrue(cf.descriptors().size() == 1);

        ClassLoader loader = new ClassLoader() { };
        Layer layer = Layer.create(cf, mn -> loader);
        assertTrue(layer.modules().size() == 1);
   }


    /**
     * Attempt to use Layer.create to create a layer with a module defined to a
     * class loader that already has a module of the same name defined to the
     * class loader.
     */
    @Test(expectedExceptions = { LayerInstantiationException.class })
    public void testModuleAlreadyDefinedToLoader() {

        ModuleDescriptor md
            = new ModuleDescriptor.Builder("m")
                .requires("java.base")
                .build();

        ModuleFinder finder = ModuleUtils.finderOf(md);

        Configuration cf
            = Configuration.resolve(finder,
                Layer.boot(),
                ModuleFinder.empty(),
                "m");

        ClassLoader loader = new ClassLoader() { };

        Layer.create(cf, mn -> loader);

        // should throw LayerInstantiationException as m1 already defined to loader
        Layer.create(cf, mn -> loader);

    }


    /**
     * Attempt to use Layer.create to create a Layer with a module containing
     * package {@code p} where the class loader already has a module defined
     * to it containing package {@code p}.
     */
    @Test(expectedExceptions = { LayerInstantiationException.class })
    public void testPackageAlreadyInNamedModule() {

        ModuleDescriptor md1
            = new ModuleDescriptor.Builder("m1")
                .conceals("p")
                .requires("java.base")
                .build();

        ModuleDescriptor md2
            = new ModuleDescriptor.Builder("m2")
                .conceals("p")
                .requires("java.base")
                .build();

        ModuleFinder finder = ModuleUtils.finderOf(md1, md2);

        ClassLoader loader = new ClassLoader() { };

        // define m1 containing package p to class loader

        Configuration cf1
            = Configuration.resolve(finder, Layer.boot(), ModuleFinder.empty(), "m1");

        Layer layer1 = Layer.create(cf1, mn -> loader);

        // attempt to define m2 containing package p to class loader

        Configuration cf2
            = Configuration.resolve(finder, Layer.boot(), ModuleFinder.empty(), "m2");

        // should throw exception because p already in m1
        Layer layer2 = Layer.create(cf2, mn -> loader);

    }


    /**
     * Attempt to use Layer.create to create a Layer with a module containing
     * a package in which a type is already loaded by the class loader.
     */
    @Test(expectedExceptions = { LayerInstantiationException.class })
    public void testPackageAlreadyInUnnamedModule() throws Exception {

        Class<?> c = layertest.Test.class;
        assertFalse(c.getModule().isNamed());  // in unnamed module

        ModuleDescriptor md
            = new ModuleDescriptor.Builder("m")
                .conceals(packageName(c))
                .requires("java.base")
                .build();

        ModuleFinder finder = ModuleUtils.finderOf(md);

        Configuration cf
            = Configuration.resolve(finder,
                Layer.boot(),
                ModuleFinder.empty(),
                "m");

        Layer.create(cf, mn -> c.getClassLoader());
    }

    private static String packageName(Class<?> c) {
        String cn = c.getName();
        int dot = cn.lastIndexOf('.');
        return cn.substring(0, dot);
    }

}
