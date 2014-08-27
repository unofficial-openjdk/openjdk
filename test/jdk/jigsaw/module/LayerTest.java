/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.reflect.Module;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jdk.jigsaw.module.Configuration;
import jdk.jigsaw.module.ExtendedModuleDescriptor;
import jdk.jigsaw.module.Layer;
import jdk.jigsaw.module.ModuleArtifact;
import jdk.jigsaw.module.ModuleArtifactFinder;
import jdk.jigsaw.module.ModuleDependence;
import jdk.jigsaw.module.ModuleDependence.Modifier;
import jdk.jigsaw.module.ModuleExport;
import jdk.jigsaw.module.ModuleIdQuery;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

/**
 * Basic tests for jdk.jigsaw.module.Layer
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
        ModuleExport javaLang = new ModuleExport("java.lang");
        assertTrue(cf.find("java.base").exports().contains(javaLang));

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
        ExtendedModuleDescriptor descriptor1 =
                new ExtendedModuleDescriptor.Builder("m1")
                        .requires(md("m2"))
                        .export("p1")
                        .build();

        ExtendedModuleDescriptor descriptor2 =
                new ExtendedModuleDescriptor.Builder("m2")
                        .requires(md("m3"))
                        .build();

        ExtendedModuleDescriptor descriptor3 =
                new ExtendedModuleDescriptor.Builder("m3")
                        .build();

        ModuleArtifactFinder finder =
                new ModuleArtifactLibrary(descriptor1, descriptor2, descriptor3);

        Configuration cf = Configuration.resolve(finder,
                                                 Layer.emptyLayer(),
                                                 ModuleArtifactFinder.nullFinder(),
                                                 "m1");

        // map each module to its own class loader for this test
        ClassLoader loader1 = new ClassLoader() { };
        ClassLoader loader2 = new ClassLoader() { };
        ClassLoader loader3 = new ClassLoader() { };
        Map<ModuleArtifact, ClassLoader> map = new HashMap<>();
        map.put(cf.findArtifact("m1"), loader1);
        map.put(cf.findArtifact("m2"), loader2);
        map.put(cf.findArtifact("m3"), loader3);

        Layer layer = Layer.create(cf, map::get);

        // configuration
        assertTrue(layer.configuration() == cf);

        // findLoader
        assertTrue(layer.findLoader("m1") == loader1);
        assertTrue(layer.findLoader("m2") == loader2);
        assertTrue(layer.findLoader("m3") == loader3);
        try {
            ClassLoader loader = layer.findLoader("godot");
            assertTrue(false);
        } catch (IllegalArgumentException ignore) { }

        // findModule
        assertTrue(layer.findModule("m1").name().equals("m1"));
        assertTrue(layer.findModule("m2").name().equals("m2"));
        assertTrue(layer.findModule("m3").name().equals("m3"));
        assertTrue(layer.findModule("godot") == null);

        // parent
        assertTrue(layer.parent() == Layer.emptyLayer());
    }

    /**
     * Exercise Layer#create, created over the boot layer
     */
    public void testLayerOnBoot() {
        ExtendedModuleDescriptor descriptor1 =
                new ExtendedModuleDescriptor.Builder("m1")
                        .requires(md("m2"))
                        .requires(md("java.base"))
                        .export("p1")
                        .build();

        ExtendedModuleDescriptor descriptor2 =
                new ExtendedModuleDescriptor.Builder("m2")
                        .requires(md("java.base"))
                        .build();

        ModuleArtifactFinder finder =
                new ModuleArtifactLibrary(descriptor1, descriptor2);

        Configuration cf = Configuration.resolve(finder,
                                                 Layer.bootLayer(),
                                                 ModuleArtifactFinder.nullFinder(),
                                                 "m1");

        ClassLoader loader = new ClassLoader() { };

        Layer layer = Layer.create(cf, m -> loader);

        // configuration
        assertTrue(layer.configuration() == cf);

        // findLoader
        assertTrue(layer.findLoader("m1") == loader);
        assertTrue(layer.findLoader("m2") == loader);
        assertTrue(layer.findLoader("java.base") == null);

        // findModule
        assertTrue(layer.findModule("m1").name().equals("m1"));
        assertTrue(layer.findModule("m2").name().equals("m2"));
        assertTrue(layer.findModule("java.base") == Object.class.getModule());
        // parent

        assertTrue(layer.parent() == Layer.bootLayer());
    }

    public void testLayerOnLayer() {
        // TBD
    }

    static ModuleDependence md(String dn, Modifier... mods) {
        Set<Modifier> set = new HashSet<>();
        for (Modifier mod: mods)
            set.add(mod);
        return new ModuleDependence(set, ModuleIdQuery.parse(dn));
    }

}

