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
 * @library ../lib
 * @build ConfigurationTest ModuleUtils
 * @run testng ConfigurationTest
 * @summary Basic tests for java.lang.module.Configuration
 */

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ResolutionException;
import java.lang.reflect.Layer;
import java.util.stream.Collectors;

import static java.lang.module.ModuleFinder.empty;
import static java.lang.reflect.Layer.*;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class ConfigurationTest {

    /**
     * Basic test of resolver
     */
    public void testBasic() {
        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                    .requires("m2")
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

        Configuration cf = Configuration.resolve(finder, boot(), empty(), "m1");

        assertTrue(cf.descriptors().size() == 3);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2));
        assertTrue(cf.descriptors().contains(descriptor3));

        assertEquals(cf.modules().stream()
                        .map(ModuleReference::descriptor)
                        .collect(Collectors.toSet()),
                     cf.descriptors());

        assertTrue(cf.findModule("m1").isPresent());
        assertTrue(cf.findModule("m2").isPresent());
        assertTrue(cf.findModule("m3").isPresent());


        // m1 reads m2
        assertTrue(cf.reads(descriptor1).size() == 1);
        assertTrue(cf.reads(descriptor1).contains(descriptor2));

        // m2 reads m3
        assertTrue(cf.reads(descriptor2).size() == 1);
        assertTrue(cf.reads(descriptor2).contains(descriptor3));

        // m3 reads nothing
        assertTrue(cf.reads(descriptor3).size() == 0);

        // toString
        assertTrue(cf.toString().contains("m1"));
        assertTrue(cf.toString().contains("m2"));
        assertTrue(cf.toString().contains("m3"));
    }


    /**
     * Basic test of "requires public"
     */
    public void testRequiresPublic() {
        // m1 requires m2, m2 requires public m3
        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .requires("m2")
                .build();

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .requires(Modifier.PUBLIC, "m3")
                .build();

        ModuleDescriptor descriptor3
            = new ModuleDescriptor.Builder("m3")
                .build();

        ModuleFinder finder
            = ModuleUtils.finderOf(descriptor1, descriptor2, descriptor3);

        Configuration cf
            = Configuration.resolve(finder, boot(), empty(), "m1");

        assertTrue(cf.descriptors().size() == 3);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2));
        assertTrue(cf.descriptors().contains(descriptor3));

        assertEquals(cf.modules().stream()
                        .map(ModuleReference::descriptor)
                        .collect(Collectors.toSet()),
                cf.descriptors());

        assertTrue(cf.findModule("m1").isPresent());
        assertTrue(cf.findModule("m2").isPresent());

        // m1 reads m2 and m3
        assertTrue(cf.reads(descriptor1).size() == 2);
        assertTrue(cf.reads(descriptor1).contains(descriptor2));
        assertTrue(cf.reads(descriptor1).contains(descriptor3));

        // m2 reads m3
        assertTrue(cf.reads(descriptor2).size() == 1);
        assertTrue(cf.reads(descriptor2).contains(descriptor3));

        // m3 reads nothing
        assertTrue(cf.reads(descriptor3).size() == 0);
    }


    /**
     * Basic test of "requires public" with layers.
     *
     * The test consists of two configurations cf1 and cf2. In configuration
     * cf1 then m1 requires public m2. In configuration cf2 then m3 requires
     * m1.
     */
    public void testRequiresPublicWithLayers1() {

        // cf1: m1 and m2, m1 requires public m2

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .requires(Modifier.PUBLIC, "m2")
                .build();

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .build();

        ModuleFinder finder1 = ModuleUtils.finderOf(descriptor1, descriptor2);

        Configuration cf1 = Configuration.resolve(finder1, boot(), empty(), "m1");

        ClassLoader cl1 = new ClassLoader() { };
        Layer layer1 = Layer.create(cf1, mn -> cl1);


        // cf2: m3, m3 requires m1

        ModuleDescriptor descriptor3
            = new ModuleDescriptor.Builder("m3")
                .requires("m1")
                .build();

        ModuleFinder finder2 = ModuleUtils.finderOf(descriptor3);

        Configuration cf2
            = Configuration.resolve(finder2, layer1, empty(), "m3");

        assertTrue(cf2.descriptors().size() == 1);
        assertTrue(cf2.descriptors().contains(descriptor3));

        assertTrue(cf2.reads(descriptor3).size() == 2);
        assertTrue(cf2.reads(descriptor3).contains(descriptor1));
        assertTrue(cf2.reads(descriptor3).contains(descriptor2));
    }


    /**
     * Basic test of "requires public" with layers
     *
     * The test consists of two configurations cf1 and cf2. In configuration
     * cf1 then m1 requires public m2. In configuration cf2 then m3 requires
     * public m1 and m4 requires m3.
     */
    public void testRequiresPublicWithLayers2() {

        // cf1: m1 and m2, m1 requires public m2

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .requires(Modifier.PUBLIC, "m2")
                .build();

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .build();

        ModuleFinder finder1 = ModuleUtils.finderOf(descriptor1, descriptor2);

        Configuration cf1 = Configuration.resolve(finder1, boot(), empty(), "m1");

        ClassLoader cl1 = new ClassLoader() { };
        Layer layer1 = Layer.create(cf1, mn -> cl1);


        // cf2: m3 and m4, m4 requires m3, m3 requires public m1

        ModuleDescriptor descriptor3
            = new ModuleDescriptor.Builder("m3")
                .requires(Modifier.PUBLIC, "m1")
                .build();

        ModuleDescriptor descriptor4
            = new ModuleDescriptor.Builder("m4")
                .requires("m3")
                .build();


        ModuleFinder finder2 = ModuleUtils.finderOf(descriptor3, descriptor4);

        Configuration cf2
            = Configuration.resolve(finder2, layer1, empty(),  "m3", "m4");

        assertTrue(cf2.descriptors().size() == 2);
        assertTrue(cf2.descriptors().contains(descriptor3));
        assertTrue(cf2.descriptors().contains(descriptor4));

        assertTrue(cf2.reads(descriptor3).size() == 2);
        assertTrue(cf2.reads(descriptor3).contains(descriptor1));
        assertTrue(cf2.reads(descriptor3).contains(descriptor2));

        assertTrue(cf2.reads(descriptor4).size() == 3);
        assertTrue(cf2.reads(descriptor4).contains(descriptor1));
        assertTrue(cf2.reads(descriptor4).contains(descriptor2));
        assertTrue(cf2.reads(descriptor4).contains(descriptor3));
    }


    /**
     * Basic test of "requires public" with layers
     *
     * The test consists of two configurations cf1 and cf2. In configuration
     * cf1 then m1 requires public m2. Configuration cf2 has m2@2 and m3 where
     * m3 requires m1. The presence of a m2 in the second configuration is to
     * ensure that m3 reads the correct version of m2.
     */
    public void testRequiresPublicWithLayers3() {

        // cf1: m1 and m2@1, m1 requires public m2

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .requires(Modifier.PUBLIC, "m2")
                .build();

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .version("1")
                .build();

        ModuleFinder finder1 = ModuleUtils.finderOf(descriptor1, descriptor2);

        Configuration cf1 = Configuration.resolve(finder1, boot(), empty(), "m1");

        ClassLoader cl1 = new ClassLoader() { };
        Layer layer1 = Layer.create(cf1, mn -> cl1);


        // cf2: m3 and m2@2, m3 requires m1

        ModuleDescriptor descriptor2_v2
            = new ModuleDescriptor.Builder("m2")
                .version("2")
                .build();

        ModuleDescriptor descriptor3
            = new ModuleDescriptor.Builder("m3")
                .requires("m1")
                .build();

        ModuleFinder finder2 = ModuleUtils.finderOf(descriptor2_v2, descriptor3);

        Configuration cf2 = Configuration.resolve(finder2, layer1, empty(), "m2", "m3");

        assertTrue(cf2.descriptors().size() == 2);
        assertTrue(cf2.descriptors().contains(descriptor2_v2));
        assertTrue(cf2.descriptors().contains(descriptor3));

        assertTrue(cf2.reads(descriptor2_v2).isEmpty());

        assertTrue(cf2.reads(descriptor3).size() == 2);
        assertTrue(cf2.reads(descriptor3).contains(descriptor1));
        assertTrue(cf2.reads(descriptor3).contains(descriptor2));
    }


    /**
     * Basic test of binding services
     */
    public void testBasicBinding() {

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .requires("m2")
                .uses("S")
                .build();

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .provides("S", "p.S2")
                .build();

        // service provider
        ModuleDescriptor descriptor3
            = new ModuleDescriptor.Builder("m3")
                .requires("m1")
                .provides("S", "q.S3").build();

        // unused module
        ModuleDescriptor descriptor4
            = new ModuleDescriptor.Builder("m4").build();

        ModuleFinder finder
            = ModuleUtils.finderOf(descriptor1,
                                   descriptor2,
                                   descriptor3,
                                   descriptor4);

        Configuration cf
            = Configuration.resolve(finder, boot(), empty(), "m1");

        // only m1 and m2 in the configuration
        assertTrue(cf.descriptors().size() == 2);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2));

        assertEquals(cf.modules().stream()
                        .map(ModuleReference::descriptor)
                        .collect(Collectors.toSet()),
                cf.descriptors());

        assertTrue(cf.findModule("m1").isPresent());
        assertTrue(cf.findModule("m2").isPresent());

        assertTrue(cf.reads(descriptor1).size() == 1);
        assertTrue(cf.reads(descriptor1).contains(descriptor2));

        assertTrue(cf.reads(descriptor2).size() == 0);

        assertTrue(cf.provides("S").isEmpty());

        // bind services, should augment graph with m3
        cf = cf.bind();

        assertTrue(cf.descriptors().size() == 3);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2));
        assertTrue(cf.descriptors().contains(descriptor3));

        assertEquals(cf.modules().stream()
                        .map(ModuleReference::descriptor)
                        .collect(Collectors.toSet()),
                cf.descriptors());

        assertTrue(cf.findModule("m1").isPresent());
        assertTrue(cf.findModule("m2").isPresent());
        assertTrue(cf.findModule("m3").isPresent());

        assertTrue(cf.reads(descriptor1).size() == 1);
        assertTrue(cf.reads(descriptor1).contains(descriptor2));

        assertTrue(cf.reads(descriptor2).size() == 0);

        assertTrue(cf.reads(descriptor3).size() == 1);
        assertTrue(cf.reads(descriptor3).contains(descriptor1));

        assertTrue(cf.provides("S").size() == 2);
        assertTrue(cf.provides("S").contains(descriptor2));
        assertTrue(cf.provides("S").contains(descriptor3));

        assertTrue(cf.toString().contains("m1"));
        assertTrue(cf.toString().contains("m2"));
        assertTrue(cf.toString().contains("m3"));
    }

    /**
     * Root module not found
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testRootNotFound() {
        Configuration.resolve(empty(), boot(), empty(), "m1");
    }


    /**
     * Direct dependency not found
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testDirectDependencyNotFound() {
        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1").requires("m2").build();
        ModuleFinder finder = ModuleUtils.finderOf(descriptor1);

        Configuration.resolve(finder, boot(), empty(), "m1");
    }


    /**
     * Transitive dependency not found
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testTransitiveDependencyNotFound() {
        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1").requires("m2").build();
        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2").requires("m3").build();
        ModuleFinder finder = ModuleUtils.finderOf(descriptor1, descriptor2);

        Configuration.resolve(finder, boot(), empty(), "m1");
    }


    /**
     * Service provider dependency not found
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testServiceProviderDependencyNotFound() {

        // service provider dependency (on m3) not found
        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1").build();
        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .requires("m3")
                .provides("java.security.Provider", "p.CryptoProvder")
                .build();
        ModuleFinder finder = ModuleUtils.finderOf(descriptor1, descriptor2);

        Configuration cf;
        try {
            cf = Configuration.resolve(finder, boot(), empty(), "m1");
            assertTrue(cf.descriptors().size() == 1);
            assertTrue(cf.provides("java.security.Provider").isEmpty());
        } catch (ResolutionException e) {
            throw new RuntimeException(e);
        }

        // should throw ResolutionException because m3 is not found
        cf.bind();
    }


    /**
     * Simple cycle.
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testSimpleCycle() {
        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1").requires("m2").build();
        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2").requires("m3").build();
        ModuleDescriptor descriptor3
            = new ModuleDescriptor.Builder("m3").requires("m1").build();
        ModuleFinder finder
            = ModuleUtils.finderOf(descriptor1, descriptor2, descriptor3);

        Configuration.resolve(finder, boot(), empty(), "m1");
    }


    /**
     * Basic test for detecting cycles involving a service provider module
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testCycleInProvider() {
        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .uses("p.Service")
                .build();
        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .requires("m3")
                .provides("p.Service", "q.ServiceImpl").build();
        ModuleDescriptor descriptor3
            = new ModuleDescriptor.Builder("m3")
                .requires("m2")
                .build();

        ModuleFinder finder
            = ModuleUtils.finderOf(descriptor1, descriptor2, descriptor3);

        Configuration cf;
        try {
            cf = Configuration.resolve(finder, boot(), empty(), "m1");
            assertTrue(cf.findDescriptor("m1").get() == descriptor1);
            assertTrue(cf.descriptors().size() == 1);
        } catch (ResolutionException e) {
            throw new RuntimeException(e);
        }

        // should throw ResolutionException because of the m2 <--> m3 cycle
        cf.bind();
    }


    /**
     * Test two modules exporting package p to a module that reads both.
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testPackageSuppliedByTwoOthers() {

        ModuleDescriptor descriptor1
            =  new ModuleDescriptor.Builder("m1")
                .requires("m2")
                .requires("m3")
                .build();

        ModuleDescriptor descriptor2
            =  new ModuleDescriptor.Builder("m2")
                .exports("p")
                .build();

        ModuleDescriptor descriptor3
            =  new ModuleDescriptor.Builder("m3")
                .exports("p", "m1")
                .build();

        ModuleFinder finder
            = ModuleUtils.finderOf(descriptor1, descriptor2, descriptor3);

        Configuration.resolve(finder, Layer.boot(), empty(), "m1");
    }


    /**
     * Test the scenario where a module has a concealed package p and reads
     * a module that exports package p.
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testPackageSuppliedBySelfAndOther() {

        ModuleDescriptor descriptor1
            =  new ModuleDescriptor.Builder("m1")
                .requires("m2")
                .conceals("p")
                .build();

        ModuleDescriptor descriptor2
            =  new ModuleDescriptor.Builder("m2")
                .exports("p")
                .build();

        ModuleFinder finder = ModuleUtils.finderOf(descriptor1, descriptor2);

        Configuration.resolve(finder, Layer.boot(), empty(), "m1");
    }


    /**
     * Test the scenario where a module has a concealed package p and reads
     * a module that also has a concealed package p.
     */
    public void testPackagePrivateToSelfAndOther() {
        ModuleDescriptor descriptor1
            =  new ModuleDescriptor.Builder("m1")
                .requires("m2")
                .conceals("p")
                .build();

        ModuleDescriptor descriptor2
            =  new ModuleDescriptor.Builder("m2")
                .conceals("p")
                .build();

        ModuleFinder finder = ModuleUtils.finderOf(descriptor1, descriptor2);

        Configuration cf
            = Configuration.resolve(finder, Layer.boot(), empty(), "m1");

        assertTrue(cf.descriptors().size() == 2);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2));

        // m1 reads m2
        assertTrue(cf.reads(descriptor1).contains(descriptor2));

        // m2 reads nothing
        assertTrue(cf.reads(descriptor2).isEmpty());
    }


    /**
     * Test the scenario where a module that exports a package that is also
     * exported by a module that it reads in a parent layer.
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testExportSamePackageAsBootLayer() {
        ModuleDescriptor descriptor
            =  new ModuleDescriptor.Builder("m1")
                .requires("java.base")
                .exports("java.lang")
                .build();

        ModuleFinder finder = ModuleUtils.finderOf(descriptor);

        Configuration.resolve(finder, Layer.boot(), empty(), "m1");
    }

}
