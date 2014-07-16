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

import jdk.jigsaw.module.Module;
import jdk.jigsaw.module.ModuleDependence;
import jdk.jigsaw.module.ModuleDependence.Modifier;
import jdk.jigsaw.module.ModuleGraph;
import jdk.jigsaw.module.ModuleIdQuery;
import jdk.jigsaw.module.ModulePath;
import jdk.jigsaw.module.Resolver;
import jdk.jigsaw.module.ResolveException;
import jdk.jigsaw.module.ServiceDependence;

import java.util.HashSet;
import java.util.Set;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class ModuleGraphTest {

    /**
     * Basic test of binding services
     */
    public void testBasicBinding() {
        Module m1 = new Module.Builder().id("m1")
                                        .requires(md("m2"))
                                        .requires(sd("S")).build();
        Module m2 = new Module.Builder().id("m2").build();

        // service provider
        Module m3 = new Module.Builder().id("m3")
                                        .requires(md("m1"))
                                        .include("p")
                                        .service("S", "p.S1").build();

        // unused module on module path
        Module m4 = new Module.Builder().id("m4").build();

        ModulePath mp = new ModuleLibrary(m1, m2, m3, m4);

        ModuleGraph g1 = new Resolver(mp).resolve("m1");

        // m3 and m4 should be not be in module graph
        assertTrue(g1.modules().size() == 2);
        assertTrue(g1.modules().contains(m1));
        assertTrue(g1.modules().contains(m2));

        // bind services, should augment graph with m3
        ModuleGraph g2 = g1.bindServices();

        assertTrue(g2.initialModuleGraph().isEmpty());

        assertTrue(g2.minus(g1).size() == 1);
        assertTrue(g2.minus(g1).contains(m3));
        assertTrue(g2.readDependences(m3).contains(m1));
    }

    /**
     * Basic test of binding failing because of a module dependence
     */
    public void testBindingFailure() {
        Module m1 = new Module.Builder().id("m1")
                                        .requires(md("m2"))
                                        .requires(sd("S")).build();
        Module m2 = new Module.Builder().id("m2").build();

        // service provider requires m100
        Module m3 = new Module.Builder().id("m3")
                                        .requires(md("m100"))
                                        .include("p")
                                        .service("S", "p.S1").build();

        ModulePath mp = new ModuleLibrary(m1, m2, m3);

        ModuleGraph g1 = new Resolver(mp).resolve("m1");

        try {
            g1.bindServices();
            throw new RuntimeException("Expected ResolveException");
        } catch (ResolveException e) { }
    }

    /**
     * Baisc test of ModuleGraph.getSystemModuleGraph
     */
    public void testGetSystemModuleGraph() {
        ModuleGraph g = ModuleGraph.getSystemModuleGraph();
        Module base = g.findModule("java.base");
        assertTrue(base.packages().contains("java.lang"));
    }

    /**
     * Baisc test of ModuleGraph.setSystemModuleGraph
     */
    @Test(expectedExceptions = { IllegalStateException.class })
    public void testSetSystemModuleGraph() {
        ModuleGraph g = ModuleGraph.getSystemModuleGraph();
        assertTrue(g != null);
        ModuleGraph.setSystemModuleGraph(g);
    }

    static ModuleDependence md(String dn, Modifier... mods) {
        Set<Modifier> set = new HashSet<>();
        for (Modifier mod: mods)
            set.add(mod);
        return new ModuleDependence(set, ModuleIdQuery.parse(dn));
    }

    static ServiceDependence sd(String sn) {
        return new ServiceDependence(null, sn);
    }
}

