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
import jdk.jigsaw.module.ResolveException;
import jdk.jigsaw.module.Resolver;
import jdk.jigsaw.module.ServiceDependence;

import java.util.HashSet;
import java.util.Set;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class ResolverTest {

    /**
     * Basic test of Resolver
     */
    public void testBasic() {
        Module m1 = new Module.Builder().id("m1").requires(md("m2")).build();
        Module m2 = new Module.Builder().id("m2").requires(md("m3")).build();
        Module m3 = new Module.Builder().id("m3").build();

        ModulePath mp = new ModuleLibrary(m1, m2, m3);

        ModuleGraph g = new Resolver(mp).resolve("m1");

        assertTrue(g.modulePath() == mp);
        assertTrue(g.initialModuleGraph().isEmpty());

        assertTrue(g.modules().contains(m1));
        assertTrue(g.modules().contains(m2));
        assertTrue(g.modules().contains(m3));

        assertTrue(g.findModule("m1") == m1);
        assertTrue(g.findModule("m2") == m2);
        assertTrue(g.findModule("m3") == m3);

        // m1 reads m2
        assertTrue(g.readDependences(m1).size() == 1);
        assertTrue(g.readDependences(m1).contains(m2));

        // m2 reads m3
        assertTrue(g.readDependences(m2).size() == 1);
        assertTrue(g.readDependences(m2).contains(m3));

        // m3 reads nothing
        assertTrue(g.readDependences(m3).size() == 0);
    }

    /**
     * Basic test of "requires public"
     */
    public void testRequiresPublic() {
        // m1 requires m2, m2 requires public m3
        Module m1 = new Module.Builder().id("m1").requires(md("m2")).build();
        Module m2 = new Module.Builder().id("m2").requires(md("m3", Modifier.PUBLIC)).build();
        Module m3 = new Module.Builder().id("m3").build();

        ModulePath mp = new ModuleLibrary(m1, m2, m3);

        Resolver r = new Resolver(mp);

        ModuleGraph g = r.resolve("m1");

        assertTrue(g.modulePath() == mp);
        assertTrue(g.initialModuleGraph().isEmpty());

        assertTrue(g.modules().contains(m1));
        assertTrue(g.modules().contains(m2));
        assertTrue(g.modules().contains(m3));

        // m1 reads m2 and m3
        assertTrue(g.readDependences(m1).size() == 2);
        assertTrue(g.readDependences(m1).contains(m2));
        assertTrue(g.readDependences(m1).contains(m3));

        // m2 reads m3
        assertTrue(g.readDependences(m2).size() == 1);
        assertTrue(g.readDependences(m2).contains(m3));

        // m3 reads nothing
        assertTrue(g.readDependences(m3).size() == 0);
    }

    /**
     * Basic test of module graph composition, resolution with an initial
     * module graph.
     */
    public void testBasicComposition() {
        // m1 -> m2 -> m3
        Module m1 = new Module.Builder().id("m1").requires(md("m2")).build();
        Module m2 = new Module.Builder().id("m2").requires(md("m3")).build();
        Module m3 = new Module.Builder().id("m3").build();

        ModulePath mp1 = new ModuleLibrary(m1, m2, m3);

        // initial module graph
        ModuleGraph g1 = new Resolver(mp1).resolve("m1");

        // m4 -> m1 & m2 & m5
        Module m4 = new Module.Builder().id("m4").requires(md("m1"))
                                                 .requires(md("m2"))
                                                 .requires(md("m5")).build();

        // m5 -> m3
        Module m5 = new Module.Builder().id("m5").requires(md("m3")).build();

        ModulePath mp2 = new ModuleLibrary(m4, m5);

        // new module graph
        ModuleGraph g2 = new Resolver(g1, mp2).resolve("m4");

        assertTrue(g2.initialModuleGraph() == g1);
        assertTrue(g2.modulePath() == mp2);

        assertTrue(g2.modules().size() == 5);
        assertTrue(g2.modules().contains(m1));
        assertTrue(g2.modules().contains(m2));
        assertTrue(g2.modules().contains(m3));
        assertTrue(g2.modules().contains(m4));
        assertTrue(g2.modules().contains(m5));

        Set<Module> selected = g2.minusInitialModuleGraph();
        assertTrue(selected.size() == 2);
        assertTrue(selected.contains(m4));
        assertTrue(selected.contains(m5));

        // readability graph should be a super-set
        g1.modules().forEach(m -> {
            assertTrue(g2.readDependences(m).containsAll(g1.readDependences(m)));
        });

        // m1, m2 and m3 read dependences should be unchanged
        assertTrue(g1.readDependences(m1).size() == 1);
        assertTrue(g1.readDependences(m2).size() == 1);
        assertTrue(g1.readDependences(m3).size() == 0);

        // check m4 and m5 read dependences
        assertTrue(g2.readDependences(m4).contains(m1));
        assertTrue(g2.readDependences(m4).contains(m2));
        assertTrue(g2.readDependences(m4).contains(m5));
        assertTrue(g2.readDependences(m5).contains(m3));
    }

    /**
     * Basic test for overriding a module version
     */
    public void testBasicOverride() {
        // m1 requires m2, m2 requires public m3
        Module m1 = new Module.Builder().id("m1").requires(md("m2")).build();
        Module m2 = new Module.Builder().id("m2").requires(md("m3", Modifier.PUBLIC)).build();
        Module m3 = new Module.Builder().id("m3").build();

        ModulePath mp1 = new ModuleLibrary(m1, m2, m3);

        ModuleGraph g1 = new Resolver(mp1).resolve("m1");

        // override m1 with alternative version
        Module m1_2 = new Module.Builder().id("m1@2.0").requires(md("m2")).build();
        Module m4 = new Module.Builder().id("m4").requires(md("m1")).build();

        ModulePath mp2 = new ModuleLibrary(m1_2, m4);

        // new module graph
        ModuleGraph g2 = new Resolver(g1, mp2).resolve("m4");

        assertTrue(g2.initialModuleGraph() == g1);
        assertTrue(g2.modulePath() == mp2);

        assertTrue(g2.modules().size() == 5);

        // graph should have 2 versions of m1
        assertTrue(g2.modules().contains(m1));
        assertTrue(g2.modules().contains(m1_2));
        assertTrue(g2.findModule("m1") == m1_2);
        assertTrue(g2.initialModuleGraph().findModule("m1") == m1);

        assertTrue(g2.modules().contains(m2));
        assertTrue(g2.modules().contains(m3));
        assertTrue(g2.modules().contains(m4));

        // check m4 reads the new m1
        assertTrue(g2.readDependences(m4).size() == 1);
        assertTrue(g2.readDependences(m4).contains(m1_2));
    }

    /**
     * Root module not found.
     */
    @Test(expectedExceptions = { ResolveException.class })
    public void testRootNotFound() {
        ModulePath mp = new ModuleLibrary(); // empty
        new Resolver(mp).resolve("m1");
    }

    /**
     * Module dependence not found.
     */
    @Test(expectedExceptions = { ResolveException.class })
    public void testDependenceNotFound() {
        Module m1 = new Module.Builder().id("m1").requires(md("m2")).build();
        ModulePath mp = new ModuleLibrary(m1);
        new Resolver(mp).resolve("m1");
    }

    /**
     * Permits violation.
     */
    @Test(expectedExceptions = { ResolveException.class })
    public void testPermits() {
        Module m1 = new Module.Builder().id("m1").requires(md("m2")).build();
        Module m2 = new Module.Builder().id("m2").permit("m3").build();
        ModulePath mp = new ModuleLibrary(m1, m2);
        new Resolver(mp).resolve("m1");
    }

    static ModuleDependence md(String dn, Modifier... mods) {
        Set<Modifier> set = new HashSet<>();
        for (Modifier mod: mods)
            set.add(mod);
        return new ModuleDependence(set, ModuleIdQuery.parse(dn));
    }
}
