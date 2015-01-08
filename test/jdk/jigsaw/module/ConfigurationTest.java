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

import java.util.HashSet;
import java.util.Set;

import jdk.jigsaw.module.Configuration;
import jdk.jigsaw.module.ExtendedModuleDescriptor;
import jdk.jigsaw.module.Layer;
import jdk.jigsaw.module.ModuleArtifactFinder;
import jdk.jigsaw.module.ModuleDependence;
import jdk.jigsaw.module.ModuleDependence.Modifier;
import jdk.jigsaw.module.ModuleIdQuery;
import jdk.jigsaw.module.ServiceDependence;

import org.testng.annotations.Test;
import static org.testng.Assert.*;


@Test
public class ConfigurationTest {

    /**
     * Basic test of resolver
     */
    public void testBasic() {
        ExtendedModuleDescriptor descriptor1 =
                new ExtendedModuleDescriptor.Builder("m1")
                        .requires(md("m2"))
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
                Layer.bootLayer(),
                ModuleArtifactFinder.nullFinder(),
                "m1");

        assertTrue(cf.descriptors().size() == 3);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2));
        assertTrue(cf.descriptors().contains(descriptor3));

        assertTrue(cf.find("m1") == descriptor1);
        assertTrue(cf.find("m2") == descriptor2);
        assertTrue(cf.find("m3") == descriptor3);

        // m1 reads m2
        assertTrue(cf.readDependences(descriptor1).size() == 1);
        assertTrue(cf.readDependences(descriptor1).contains(descriptor2));

        // m2 reads m3
        assertTrue(cf.readDependences(descriptor2).size() == 1);
        assertTrue(cf.readDependences(descriptor2).contains(descriptor3));

        // m3 reads nothing
        assertTrue(cf.readDependences(descriptor3).size() == 0);
    }

    /**
     * Basic test of "requires public"
     */
    public void testRequiresPublic() {
        // m1 requires m2, m2 requires public m3
        ExtendedModuleDescriptor descriptor1 =
                new ExtendedModuleDescriptor.Builder("m1")
                        .requires(md("m2"))
                        .build();

        ExtendedModuleDescriptor descriptor2 =
                new ExtendedModuleDescriptor.Builder("m2")
                        .requires(md("m3", Modifier.PUBLIC))
                        .build();

        ExtendedModuleDescriptor descriptor3 =
                new ExtendedModuleDescriptor.Builder("m3")
                        .build();

        ModuleArtifactFinder finder =
                new ModuleArtifactLibrary(descriptor1, descriptor2, descriptor3);

        Configuration cf = Configuration.resolve(finder,
                Layer.bootLayer(),
                ModuleArtifactFinder.nullFinder(),
                "m1");

        assertTrue(cf.descriptors().size() == 3);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2));
        assertTrue(cf.descriptors().contains(descriptor3));

        assertTrue(cf.find("m1") == descriptor1);
        assertTrue(cf.find("m2") == descriptor2);
        assertTrue(cf.find("m3") == descriptor3);

        // m1 reads m2 and m3
        assertTrue(cf.readDependences(descriptor1).size() == 2);
        assertTrue(cf.readDependences(descriptor1).contains(descriptor2));
        assertTrue(cf.readDependences(descriptor1).contains(descriptor3));

        // m2 reads m3
        assertTrue(cf.readDependences(descriptor2).size() == 1);
        assertTrue(cf.readDependences(descriptor2).contains(descriptor3));

        // m3 reads nothing
        assertTrue(cf.readDependences(descriptor3).size() == 0);
    }

    /**
     * Basic test of binding services
     */
    public void testBasicBinding() {

        ExtendedModuleDescriptor descriptor1 =
                new ExtendedModuleDescriptor.Builder("m1")
                .requires(md("m2"))
                .requires(sd("S"))
                .build();

        ExtendedModuleDescriptor descriptor2 =
                new ExtendedModuleDescriptor.Builder("m2").build();

        // service provider
        ExtendedModuleDescriptor descriptor3 =
                new ExtendedModuleDescriptor.Builder("m3")
                .requires(md("m1"))
                .service("S", "p.S1").build();

        // unused module
        ExtendedModuleDescriptor descriptor4 =
                new ExtendedModuleDescriptor.Builder("m4").build();

        ModuleArtifactFinder finder =
                new ModuleArtifactLibrary(descriptor1, descriptor2, descriptor3, descriptor4);

        Configuration cf = Configuration.resolve(finder,
                Layer.bootLayer(),
                ModuleArtifactFinder.nullFinder(),
                "m1");

        // only m1 and m2 in the configuration
        assertTrue(cf.descriptors().size() == 2);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2));

        assertTrue(cf.readDependences(descriptor1).size() == 1);
        assertTrue(cf.readDependences(descriptor1).contains(descriptor2));

        assertTrue(cf.readDependences(descriptor2).size() == 0);

        // bind services, should augment graph with m3
        cf = cf.bind();

        assertTrue(cf.descriptors().size() == 3);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2));
        assertTrue(cf.descriptors().contains(descriptor3));

        assertTrue(cf.readDependences(descriptor1).size() == 1);
        assertTrue(cf.readDependences(descriptor1).contains(descriptor2));

        assertTrue(cf.readDependences(descriptor2).size() == 0);

        assertTrue(cf.readDependences(descriptor3).size() == 1);
        assertTrue(cf.readDependences(descriptor3).contains(descriptor1));
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
