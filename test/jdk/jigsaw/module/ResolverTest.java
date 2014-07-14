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
