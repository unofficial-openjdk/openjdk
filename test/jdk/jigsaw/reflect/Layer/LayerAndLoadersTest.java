/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
 * @build LayerAndLoadersTest CompilerUtils ModuleUtils
 * @run testng LayerAndLoadersTest
 * @summary Tests for java.lang.reflect.Layer@createWithXXX methods
 */

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.Layer;
import java.lang.reflect.LayerInstantiationException;
import java.lang.reflect.Method;
import java.lang.reflect.Module;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Iterator;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class LayerAndLoadersTest {

    private static final String TEST_SRC = System.getProperty("test.src");

    private static final Path SRC_DIR = Paths.get(TEST_SRC, "src");
    private static final Path MODS_DIR = Paths.get("mods");

    @BeforeTest
    public void setup() throws Exception {

        // javac -d mods -modulesourcepath src src/**
        assertTrue(CompilerUtils.compile(SRC_DIR, MODS_DIR,
                "-modulesourcepath", SRC_DIR.toString()));
    }


    /**
     * Basic test of Layer.createWithOneLoader
     */
    public void testWithOneLoader() throws Exception {

        Configuration cf = resolve("m1");

        ClassLoader scl = ClassLoader.getSystemClassLoader();

        Layer layer = Layer.createWithOneLoader(cf, Layer.boot(), scl);

        checkLayer(layer, "m1", "m2");

        ClassLoader cl1 = layer.findLoader("m1");
        ClassLoader cl2 = layer.findLoader("m2");

        assertTrue(cl1.getParent() == scl);
        assertTrue(cl2 == cl1);

        invoke(layer, "m1", "p.Main");

    }


    /**
     * Basic test of Layer.createWithManyLoaders
     */
    public void testWithManyLoaders() throws Exception {

        Configuration cf = resolve("m1");

        ClassLoader scl = ClassLoader.getSystemClassLoader();

        Layer layer = Layer.createWithManyLoaders(cf, Layer.boot(), scl);

        checkLayer(layer, "m1", "m2");

        ClassLoader cl1 = layer.findLoader("m1");
        ClassLoader cl2 = layer.findLoader("m2");

        assertTrue(cl1.getParent() == scl);
        assertTrue(cl2.getParent() == scl);
        assertTrue(cl2 != cl1);

        invoke(layer, "m1", "p.Main");

    }


    /**
     * Basic test of Layer.createWithOneLoader where one of the modules
     * is a service provider module.
     */
    public void testServicesWithOneLoader() throws Exception {

        Configuration cf = resolve("m1").bind();

        ClassLoader scl = ClassLoader.getSystemClassLoader();

        Layer layer = Layer.createWithOneLoader(cf, Layer.boot(), scl);

        checkLayer(layer, "m1", "m2", "m3");

        ClassLoader cl1 = layer.findLoader("m1");
        ClassLoader cl2 = layer.findLoader("m2");
        ClassLoader cl3 = layer.findLoader("m3");

        assertTrue(cl1.getParent() == scl);
        assertTrue(cl2 == cl1);
        assertTrue(cl3 == cl1);

        Class<?> serviceType = cl1.loadClass("p.Service");
        assertTrue(serviceType.getClassLoader() == cl1);

        Iterator<?> iter = ServiceLoader.load(serviceType, cl1).iterator();
        Object provider = iter.next();
        assertTrue(serviceType.isInstance(provider));
        assertTrue(provider.getClass().getClassLoader() == cl1);
        assertFalse(iter.hasNext());

    }


    /**
     * Basic test of Layer.createWithManyLoaders where one of the modules
     * is a service provider module.
     */
    public void testServicesWithManyLoaders() throws Exception {

        Configuration cf = resolve("m1").bind();

        ClassLoader scl = ClassLoader.getSystemClassLoader();

        Layer layer = Layer.createWithManyLoaders(cf, Layer.boot(), scl);
        checkLayer(layer, "m1", "m2", "m3");

        ClassLoader cl1 = layer.findLoader("m1");
        ClassLoader cl2 = layer.findLoader("m2");
        ClassLoader cl3 = layer.findLoader("m3");

        assertTrue(cl1.getParent() == scl);
        assertTrue(cl2.getParent() == scl);
        assertTrue(cl3.getParent() == scl);
        assertTrue(cl2 != cl1);
        assertTrue(cl3 != cl1);

        Class<?> serviceType = cl1.loadClass("p.Service");
        assertTrue(serviceType.getClassLoader() == cl1);

        // Test that the service provider can be located via any of
        // the class loaders in the layer
        for (Module m : layer.modules()) {
            ClassLoader loader = m.getClassLoader();
            Iterator<?> iter = ServiceLoader.load(serviceType, loader).iterator();
            Object provider = iter.next();
            assertTrue(serviceType.isInstance(provider));
            assertTrue(provider.getClass().getClassLoader() == cl3);
            assertFalse(iter.hasNext());
        }

    }

    /**
     * Tests that the class loaders created by Layer.createWithXXX delegate
     * to the given parent class loader.
     */
    public void testDelegationToParent() throws Exception {

        Configuration cf = resolve("m1");

        ClassLoader parent = this.getClass().getClassLoader();
        String cn = this.getClass().getName();

        // one loader
        Layer layer = Layer.createWithOneLoader(cf, Layer.boot(), parent);
        testLoad(layer, cn);

         // one loader with boot loader as parent
        layer = Layer.createWithOneLoader(cf, Layer.boot(), null);
        testLoadFail(layer, cn);

        // many loaders
        layer = Layer.createWithManyLoaders(cf, Layer.boot(), parent);
        testLoad(layer, cn);

        // many loader with boot loader as parent
        layer = Layer.createWithManyLoaders(cf, Layer.boot(), null);
        testLoadFail(layer, cn);

    }


    /**
     * Test Layer.createWithXXX when modules that have overlapping packages.
     */
    public void testOverlappingPackages() {

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1").exports("p").build();

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2").exports("p").build();

        ModuleFinder finder = ModuleUtils.finderOf(descriptor1, descriptor2);

        Configuration cf
            = Configuration.resolve(finder,
                Layer.boot().configuration(),
                ModuleFinder.empty(),
                "m1", "m2");

        // cannot define both module m1 and m2 to the same class loader
        try {
            Layer.createWithOneLoader(cf, Layer.boot(), null);
            assertTrue(false);
        } catch (LayerInstantiationException expected) { }

        // should be okay to have one module per class loader
        Layer layer = Layer.createWithManyLoaders(cf, Layer.boot(), null);
        checkLayer(layer, "m1", "m2");

    }


    /**
     * Test Layer.createWithXXX with split delegation.
     *
     * layer1: m1 exports p, m2 exports p
     * layer2: m3 reads m1, m4 reads m2
     */
    public void testSplitDelegation() {

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1").exports("p").build();

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2").exports("p").build();

        ModuleFinder finder1 = ModuleUtils.finderOf(descriptor1, descriptor2);

        Configuration cf1
            = Configuration.resolve(finder1,
                Layer.boot().configuration(),
                ModuleFinder.empty(),
                "m1", "m2");

        Layer layer1 = Layer.createWithManyLoaders(cf1, Layer.boot(), null);
        checkLayer(layer1, "m1", "m2");


        ModuleDescriptor descriptor3
            = new ModuleDescriptor.Builder("m3").requires("m1").build();

        ModuleDescriptor descriptor4
            = new ModuleDescriptor.Builder("m4").requires("m2").build();

        ModuleFinder finder2 = ModuleUtils.finderOf(descriptor3, descriptor4);

        Configuration cf2
            = Configuration.resolve(finder2, cf1, ModuleFinder.empty(), "m3", "m4");

        // package p cannot be supplied by two class loaders
        try {
            Layer.createWithOneLoader(cf2, layer1, null);
            assertTrue(false);
        } catch (LayerInstantiationException expected) { }

        // no split delegation when modules have their own class loader
        Layer layer2 = Layer.createWithManyLoaders(cf2, layer1, null);
        checkLayer(layer2, "m3", "m4");

    }


    /**
     * Test Layer.createWithXXX when the modules that override same named
     * modules in the parent layer.
     *
     * layer1: m1, m2 => same loader
     * layer2: m1, m2 => same loader
     */
    public void testOverriding1() throws Exception {

        Configuration cf1 = resolve("m1");

        Layer layer1 = Layer.createWithOneLoader(cf1, Layer.boot(), null);
        checkLayer(layer1, "m1", "m2");

        ModuleFinder finder = ModuleFinder.of(MODS_DIR);
        Configuration cf2 = Configuration.resolve(finder, cf1, ModuleFinder.empty(), "m1");

        Layer layer2 = Layer.createWithOneLoader(cf2, layer1, null);
        checkLayer(layer2, "m1", "m2");
        invoke(layer1, "m1", "p.Main");

        ClassLoader loader1 = layer1.findLoader("m1");
        ClassLoader loader2 = layer1.findLoader("m2");
        ClassLoader loader3 = layer2.findLoader("m1");
        ClassLoader loader4 = layer2.findLoader("m2");

        assertTrue(loader1 == loader2);
        assertTrue(loader3 == loader4);
        assertTrue(loader3 != loader1);

        assertTrue(loader1.loadClass("p.Main").getClassLoader() == loader1);
        assertTrue(loader1.loadClass("q.Hello").getClassLoader() == loader1);

        assertTrue(loader3.loadClass("p.Main").getClassLoader() == loader3);
        assertTrue(loader3.loadClass("q.Hello").getClassLoader() == loader3);

    }


    /**
     * Test Layer.createWithXXX when the modules that override same named
     * modules in the parent layer.
     *
     * layer1: m1, m2 => loader pool
     * layer2: m1, m2 => loader pool
     */
    public void testOverriding2() throws Exception {

        Configuration cf1 = resolve("m1");

        Layer layer1 = Layer.createWithManyLoaders(cf1, Layer.boot(), null);
        checkLayer(layer1, "m1", "m2");

        ModuleFinder finder = ModuleFinder.of(MODS_DIR);
        Configuration cf2
            = Configuration.resolve(finder, cf1, ModuleFinder.empty(), "m1");

        Layer layer2 = Layer.createWithManyLoaders(cf2, layer1, null);
        checkLayer(layer2, "m1", "m2");
        invoke(layer1, "m1", "p.Main");

        ClassLoader loader1 = layer1.findLoader("m1");
        ClassLoader loader2 = layer1.findLoader("m2");
        ClassLoader loader3 = layer2.findLoader("m1");
        ClassLoader loader4 = layer2.findLoader("m2");

        assertTrue(loader1 != loader2);
        assertTrue(loader1 != loader3);
        assertTrue(loader1 != loader4);
        assertTrue(loader2 != loader3);
        assertTrue(loader2 != loader4);
        assertTrue(loader3 != loader4);

        assertTrue(loader1.loadClass("p.Main").getClassLoader() == loader1);
        assertTrue(loader1.loadClass("q.Hello").getClassLoader() == loader2);
        assertTrue(loader2.loadClass("q.Hello").getClassLoader() == loader2);

        // p.Main is not visible via loader2
        try {
            loader2.loadClass("p.Main");
            assertTrue(false);
        } catch (ClassNotFoundException expected) { }


        assertTrue(loader3.loadClass("p.Main").getClassLoader() == loader3);
        assertTrue(loader3.loadClass("q.Hello").getClassLoader() == loader4);
        assertTrue(loader4.loadClass("q.Hello").getClassLoader() == loader4);

        // p.Main is not visible via loader4
        try {
            loader2.loadClass("p.Main");
            assertTrue(false);
        } catch (ClassNotFoundException expected) { }

    }


    /**
     * Test Layer.createWithXXX when the modules that override same named
     * modules in the parent layer.
     *
     * layer1: m1, m2 => same loader
     * layer2: m1 => one loader
     */
    public void testOverriding3() throws Exception {

        Configuration cf1 = resolve("m1");

        Layer layer1 = Layer.createWithOneLoader(cf1, Layer.boot(), null);
        checkLayer(layer1, "m1", "m2");


        // ModuleFinder that only finds m1
        ModuleFinder finder = new ModuleFinder() {
            ModuleReference mref = ModuleFinder.of(MODS_DIR).find("m1").get();
            @Override
            public Optional<ModuleReference> find(String name) {
                if (name.equals("m1")) {
                    return Optional.of(mref);
                } else {
                    return Optional.empty();
                }
            }
            @Override
            public Set<ModuleReference> findAll() {
                return Collections.singleton(mref);
            }
        };

        Configuration cf2
            = Configuration.resolve(finder, cf1, ModuleFinder.empty(), "m1");

        Layer layer2 = Layer.createWithOneLoader(cf2, layer1, null);
        checkLayer(layer2, "m1");
        invoke(layer1, "m1", "p.Main");

        ClassLoader loader1 = layer1.findLoader("m1");
        ClassLoader loader2 = layer1.findLoader("m2");
        ClassLoader loader3 = layer2.findLoader("m1");

        assertTrue(loader1 == loader2);
        assertTrue(loader3 != loader1);

        assertTrue(loader1.loadClass("p.Main").getClassLoader() == loader1);
        assertTrue(loader1.loadClass("q.Hello").getClassLoader() == loader1);

        assertTrue(loader3.loadClass("p.Main").getClassLoader() == loader3);
        assertTrue(loader3.loadClass("q.Hello").getClassLoader() == loader1);

    }


    // test qualified exports?


    // -- supporting methods --


    /**
     * Resolve the given modules, by name, and return the resulting
     * Configuration.
     */
    private static Configuration resolve(String... roots) {
        ModuleFinder finder = ModuleFinder.of(MODS_DIR);

        return Configuration.resolve(finder,
                Layer.boot().configuration(),
                ModuleFinder.empty(),
                "m1");
    }


    /**
     * Invokes the static void main(String[]) method on the given class
     * in the given module.
     */
    private static void invoke(Layer layer, String mn, String mc) throws Exception {
        ClassLoader loader = layer.findLoader(mn);
        Class<?> c = loader.loadClass(mc);
        Method mainMethod = c.getMethod("main", String[].class);
        mainMethod.invoke(null, (Object)new String[0]);
    }


    /**
     * Checks that the given layer contains exactly the expected modules
     * (by name).
     */
    private void checkLayer(Layer layer, String ... expected) {
        Set<String> names = layer.modules().stream()
                .map(Module::getName)
                .collect(Collectors.toSet());
        assertTrue(names.size() == expected.length);
        for (String name : expected) {
            assertTrue(names.contains(name));
        }
    }


    /**
     * Test that a class can be loaded via the class loader of all modules
     * in the given layer.
     */
    static void testLoad(Layer layer, String cn) throws Exception {
        for (Module m : layer.modules()) {
            ClassLoader l = m.getClassLoader();
            l.loadClass(cn);
        }
    }


    /**
     * Test that a class cannot be loaded via any of the class loaders of
     * the modules in the given layer.
     */
    static void testLoadFail(Layer layer, String cn) throws Exception {
        for (Module m : layer.modules()) {
            ClassLoader l = m.getClassLoader();
            try {
                l.loadClass(cn);
                assertTrue(false);
            } catch (ClassNotFoundException expected) { }
        }
    }

}
