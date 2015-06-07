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

import java.io.IOException;
import java.io.OutputStream;
import java.lang.module.Configuration;
import java.lang.module.Layer;
import java.lang.module.LayerInstantiationException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.Module;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

/**
 * Basic tests for java.lang.module.Layer
 */

@Test
public class LayerTest {

    /**
     * No-op modular-capable ClassLoader
     */
    static class TestClassLoader
        extends ClassLoader implements ModuleCapableLoader
    {
        TestClassLoader() { }

        @Override
        public void register(ModuleReference mref) { }
    }

    /**
     * Exercise Layer.bootLayer
     */
    public void testBootLayer() {
        Layer bootLayer = Layer.boot();

        // configuration
        Configuration cf = bootLayer.configuration().get();
        assertTrue(cf.findDescriptor("java.base").get().exports()
                   .stream().anyMatch(e -> (e.source().equals("java.lang")
                                            && !e.targets().isPresent())));

        // findLoader
        assertTrue(bootLayer.findLoader("java.base") == null);

        // findModule
        Module base = Object.class.getModule();
        assertTrue(bootLayer.findModule("java.base").get() == base);

        // parent
        assertTrue(bootLayer.parent().get() == Layer.empty());
    }

    /**
     * Exercise Layer.emptyLayer
     */
    public void testEmptyLayer() {
        Layer emptyLayer = Layer.empty();

        // configuration
        assertTrue(!emptyLayer.configuration().isPresent());

        // findLoader
        try {
            ClassLoader loader = emptyLayer.findLoader("java.base");
            assertTrue(false);
        } catch (IllegalArgumentException ignore) { }

        // findModule
        assertTrue(!emptyLayer.findModule("java.base").isPresent());

        // parent
        assertTrue(!emptyLayer.parent().isPresent());
    }

    /**
     * Exercise Layer.create, created on an empty layer
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
                ModuleLibrary.of(descriptor1, descriptor2, descriptor3);

        Configuration cf = Configuration.resolve(finder,
                                                 Layer.empty(),
                                                 ModuleFinder.empty(),
                                                 "m1");

        // map each module to its own class loader for this test
        ClassLoader loader1 = new TestClassLoader();
        ClassLoader loader2 = new TestClassLoader();
        ClassLoader loader3 = new TestClassLoader();
        Map<String, ClassLoader> map = new HashMap<>();
        map.put("m1", loader1);
        map.put("m2", loader2);
        map.put("m3", loader3);

        Layer layer = Layer.create(cf, map::get);

        // configuration
        assertTrue(layer.configuration().get() == cf);
        assertTrue(layer.configuration().get().descriptors().size() == 3);

        // findLoader
        assertTrue(layer.findLoader("m1") == loader1);
        assertTrue(layer.findLoader("m2") == loader2);
        assertTrue(layer.findLoader("m3") == loader3);
        try {
            ClassLoader loader = layer.findLoader("godot");
            assertTrue(false);
        } catch (IllegalArgumentException ignore) { }

        // findModule
        assertTrue(layer.findModule("m1").get().getName().equals("m1"));
        assertTrue(layer.findModule("m2").get().getName().equals("m2"));
        assertTrue(layer.findModule("m3").get().getName().equals("m3"));
        assertTrue(!layer.findModule("godot").isPresent());

        // parent
        assertTrue(layer.parent().get() == Layer.empty());
    }

    /**
     * Exercise Layer.create, created over the boot layer
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
                ModuleLibrary.of(descriptor1, descriptor2);

        Configuration cf = Configuration.resolve(finder,
                                                 Layer.boot(),
                                                 ModuleFinder.empty(),
                                                 "m1");

        ClassLoader loader = new TestClassLoader();

        Layer layer = Layer.create(cf, m -> loader);

        // configuration
        assertTrue(layer.configuration().get() == cf);
        assertTrue(layer.configuration().get().descriptors().size() == 2);

        // findLoader
        assertTrue(layer.findLoader("m1") == loader);
        assertTrue(layer.findLoader("m2") == loader);
        assertTrue(layer.findLoader("java.base") == null);

        // findModule
        assertTrue(layer.findModule("m1").get().getName().equals("m1"));
        assertTrue(layer.findModule("m2").get().getName().equals("m2"));
        assertTrue(layer.findModule("java.base").get() == Object.class.getModule());
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

        ModuleFinder finder = ModuleLibrary.of(descriptor1, descriptor2);

        Configuration cf
            = Configuration.resolve(finder, Layer.empty(), ModuleFinder.empty(), "m1");
        assertTrue(cf.descriptors().size() == 2);

        // one loader per module, should be okay
        Layer.create(cf, m -> new TestClassLoader());

        // same class loader
        try {
            ClassLoader loader = new TestClassLoader();
            Layer.create(cf, m -> loader);
            assertTrue(false);
        } catch (LayerInstantiationException expected) { }
    }

    /**
     * Layer.create with a configuration of two modules that export the
     * same package.
     */
    public void testSameExportedPackage() {
        ModuleDescriptor descriptor1
            =  new ModuleDescriptor.Builder("m1")
                .requires("m2")
                .exports("p")
                .build();

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .exports("p")
                .build();

        ModuleFinder finder = ModuleLibrary.of(descriptor1, descriptor2);

        Configuration cf
            = Configuration.resolve(finder, Layer.empty(), ModuleFinder.empty(), "m1");
        assertTrue(cf.descriptors().size() == 2);

        // one loader per module
        try {
            Layer.create(cf, m -> new TestClassLoader());
            assertTrue(false);
        } catch (LayerInstantiationException expected) { }

        // same class loader
        try {
            ClassLoader loader = new TestClassLoader();
            Layer.create(cf, m -> loader);
            assertTrue(false);
        } catch (LayerInstantiationException expected) { }
    }

    /**
     * Layer.create with a configuration of two modules that export the
     * same package to another module (that reads both).
     */
    public void testSameExportToModule() {

        ModuleDescriptor descriptor1
            =  new ModuleDescriptor.Builder("m1")
                .requires("m2")
                .requires("m3")
                .build();

        // exports p
        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .exports("p")
                .build();

        // exports p to m1
        ModuleDescriptor descriptor3
            = new ModuleDescriptor.Builder("m3")
                .exports("p", "m1")
                .build();

        ModuleFinder finder
            = ModuleLibrary.of(descriptor1, descriptor2, descriptor3);

        Configuration cf
            = Configuration.resolve(finder, Layer.empty(), ModuleFinder.empty(), "m1");
        assertTrue(cf.descriptors().size() == 3);

        // one loader per module
        try {
            Layer.create(cf, m -> new TestClassLoader());
            assertTrue(false);
        } catch (LayerInstantiationException expected) { }

        // same class loader
        try {
            ClassLoader loader = new TestClassLoader();
            Layer.create(cf, m -> loader);
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
            =  ModuleLibrary.of(descriptor1, descriptor2, descriptor3, descriptor4);

        Configuration cf
            = Configuration.resolve(finder, Layer.empty(), ModuleFinder.empty(),
                                    "m1", "m3");
        assertTrue(cf.descriptors().size() == 4);

        // one loader per module
        Layer.create(cf, m -> new TestClassLoader());

        // m1 & m2 in one loader, m3 & m4 in another loader
        ClassLoader loader1 = new TestClassLoader();
        ClassLoader loader2 = new TestClassLoader();
        Map<String, ClassLoader> map = new HashMap<>();
        map.put("m1", loader1);
        map.put("m2", loader1);
        map.put("m3", loader2);
        map.put("m3", loader2);
        Layer.create(cf, map::get);

        // same loader
        try {
            ClassLoader loader = new TestClassLoader();
            Layer.create(cf, m -> loader);
            assertTrue(false);
        } catch (LayerInstantiationException expected) { }
    }

    /**
     * Layer.create with a configuration that contains a module that exports
     * the same package as java.base.
     */
    @Test(expectedExceptions = { LayerInstantiationException.class })
    public void testExportSamePackageAsBootLayer() {
        ModuleDescriptor descriptor
            =  new ModuleDescriptor.Builder("m1")
                .requires("java.base")
                .exports("java.lang")
                .build();

        ModuleFinder finder = ModuleLibrary.of(descriptor);

        Configuration cf
            = Configuration.resolve(finder, Layer.boot(), ModuleFinder.empty(), "m1");
        assertTrue(cf.descriptors().size() == 1);

        Layer.create(cf, m -> new TestClassLoader());
    }

    /**
     * Layer.create with a configuration that contains a module that has a
     * module-private package that is the same a concealed packaged in
     * java.base.
     */
    public void testConcealSamePackageAsBootLayer() {
        ModuleDescriptor descriptor
            =  new ModuleDescriptor.Builder("m1")
                .requires("java.base")
                .conceals("sun.misc")
                .build();

        ModuleFinder finder = ModuleLibrary.of(descriptor);

        Configuration cf
            = Configuration.resolve(finder, Layer.boot(), ModuleFinder.empty(), "m1");
        assertTrue(cf.descriptors().size() == 1);

        Layer.create(cf, m -> new TestClassLoader());
    }

    /**
     * Layer.create with a configuration that contains two automatic modules
     * mapped to different class loaders but containing the same package.
     */
    @Test(expectedExceptions = { LayerInstantiationException.class })
    public void testAutomaticModulesWithSamePackage() throws IOException {
        ModuleDescriptor descriptor
            =  new ModuleDescriptor.Builder("m1")
                .requires("m2")
                .requires("m3")
                .build();

        // m2 and m3 are simple JAR files
        Path dir = Files.createTempDirectory("layertest");
        createDummyJarFile(dir.resolve("m2.jar"), "p/T1.class");
        createDummyJarFile(dir.resolve("m3.jar"), "p/T2.class");

        // module finder locates m1 and the modules in the directory
        ModuleFinder finder
            = ModuleFinder.concat(ModuleLibrary.of(descriptor),
                                  ModuleFinder.of(dir));

        Configuration cf
            = Configuration.resolve(finder, Layer.boot(), ModuleFinder.empty(), "m1");
        assertTrue(cf.descriptors().size() == 3);

        // each module gets its own loader
        Layer.create(cf, m -> new TestClassLoader());
    }

    /**
     * Creates a JAR file containing the give entries. The entries will be
     * empty in the resulting JAR file.
     */
    private static void createDummyJarFile(Path file, String... entries)
        throws IOException
    {
        try (OutputStream out = Files.newOutputStream(file)) {
            try (JarOutputStream jos = new JarOutputStream(out)) {
                for (String entry : entries) {
                    JarEntry je = new JarEntry(entry);
                    jos.putNextEntry(je);
                }
            }
        }
    }
}
