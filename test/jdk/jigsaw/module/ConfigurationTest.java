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
import static java.lang.module.Layer.*;
import java.lang.module.ResolutionException;
import java.lang.module.ModuleFinder;
import static java.lang.module.ModuleFinder.*;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Requires.Modifier;

import org.testng.annotations.Test;
import static org.testng.Assert.*;


@Test
public class ConfigurationTest {

    /**
     * Basic test of resolver
     */
    public void testBasic() {
        ModuleDescriptor descriptor1 =
                new ModuleDescriptor.Builder("m1")
                        .requires("m2")
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

        Configuration cf = Configuration.resolve(finder, bootLayer(), empty(), "m1");

        assertTrue(cf.descriptors().size() == 3);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2));
        assertTrue(cf.descriptors().contains(descriptor3));

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
     * Root module not found
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testRootNotFound() {
        Configuration.resolve(empty(), bootLayer(), empty(), "m1");
    }

    /**
     * Direct dependency not found
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testDirectDependencyNotFound() {
        ModuleDescriptor descriptor1 =
                new ModuleDescriptor.Builder("m1").requires("m2").build();
        ModuleFinder finder = new ModuleLibrary(descriptor1);

        Configuration.resolve(finder, bootLayer(), empty(), "m1");
    }

    /**
     * Transitive dependency not found
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testTransitiveDependencyNotFound() {
        ModuleDescriptor descriptor1 =
                new ModuleDescriptor.Builder("m1").requires("m2").build();
        ModuleDescriptor descriptor2 =
                new ModuleDescriptor.Builder("m2").requires("m3").build();
        ModuleFinder finder = new ModuleLibrary(descriptor1, descriptor2);

        Configuration.resolve(finder, bootLayer(), empty(), "m1");
    }

    /**
     * Service provider dependency not found
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testServiceProviderDependencyNotFound() {

        // service provider dependency (on m3) not found
        ModuleDescriptor descriptor1  = new ModuleDescriptor.Builder("m1").build();
        ModuleDescriptor descriptor2 =
                new ModuleDescriptor.Builder("m2")
                    .requires("m3")
                    .provides("java.security.Provider", "p.CryptoProvder")
                    .build();
        ModuleFinder finder = new ModuleLibrary(descriptor1, descriptor2);


        Configuration cf;
        try {
            cf = Configuration.resolve(finder, bootLayer(), empty(), "m1");
            assertTrue(cf.descriptors().size() == 1);
        } catch (ResolutionException e) {
            throw new RuntimeException(e);
        }

        // should throw ResolutionException because m3 is not found
        cf.bind();
    }

    /**
     * Basic test of "requires public"
     */
    public void testRequiresPublic() {
        // m1 requires m2, m2 requires public m3
        ModuleDescriptor descriptor1 =
                new ModuleDescriptor.Builder("m1")
                        .requires("m2")
                        .build();

        ModuleDescriptor descriptor2 =
                new ModuleDescriptor.Builder("m2")
                        .requires(Modifier.PUBLIC, "m3")
                        .build();

        ModuleDescriptor descriptor3 =
                new ModuleDescriptor.Builder("m3")
                        .build();

        ModuleFinder finder =
                new ModuleLibrary(descriptor1, descriptor2, descriptor3);

        Configuration cf = Configuration.resolve(finder, bootLayer(), empty(), "m1");

        assertTrue(cf.descriptors().size() == 3);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2));
        assertTrue(cf.descriptors().contains(descriptor3));

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

        ModuleDescriptor descriptor1 =
            new ModuleDescriptor.Builder("m1")
                    .requires("m2")
                    .uses("S")
                    .build();

        ModuleDescriptor descriptor2 =
            new ModuleDescriptor.Builder("m2").build();

        // service provider
        ModuleDescriptor descriptor3 =
            new ModuleDescriptor.Builder("m3")
                .requires("m1")
                .provides("S", "p.S1").build();

        // unused module
        ModuleDescriptor descriptor4 =
            new ModuleDescriptor.Builder("m4").build();

        ModuleFinder finder =
            new ModuleLibrary(descriptor1, descriptor2, descriptor3, descriptor4);

        Configuration cf = Configuration.resolve(finder, bootLayer(), empty(), "m1");

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

    /**
     * Simple cycle.
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testSimpleCycle() {
        ModuleDescriptor descriptor1 =
                new ModuleDescriptor.Builder("m1").requires("m2").build();
        ModuleDescriptor descriptor2 =
                new ModuleDescriptor.Builder("m2").requires("m3").build();
        ModuleDescriptor descriptor3 =
                new ModuleDescriptor.Builder("m3").requires("m1").build();
        ModuleFinder finder =
                new ModuleLibrary(descriptor1, descriptor2, descriptor3);

        Configuration.resolve(finder, bootLayer(), empty(), "m1");
    }

    /**
     * Basic test for detecting cycles involving a service provider module
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testCycleInProvider() {
        ModuleDescriptor descriptor1 =
                new ModuleDescriptor.Builder("m1").uses("p.Service").build();
        ModuleDescriptor descriptor2 =
                new ModuleDescriptor.Builder("m2")
                        .requires("m3")
                        .provides("p.Service", "q.ServiceImpl").build();
        ModuleDescriptor descriptor3 =
                new ModuleDescriptor.Builder("m3").requires("m2").build();
        ModuleFinder finder =
                new ModuleLibrary(descriptor1, descriptor2, descriptor3);

        Configuration cf;
        try {
            cf = Configuration.resolve(finder, bootLayer(), empty(), "m1");
            assertTrue(cf.findDescriptor("m1") == descriptor1);
            assertTrue(cf.descriptors().size() == 1);
        } catch (ResolutionException e) {
            throw new RuntimeException(e);
        }

        // should throw ResolutionException because of the m2 <--> m3 cycle
        cf.bind();
    }

}
