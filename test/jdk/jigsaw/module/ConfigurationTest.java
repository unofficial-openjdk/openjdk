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
import java.lang.module.Configuration.ReadDependence;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ResolutionException;
import java.lang.reflect.Layer;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.module.Configuration.empty;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class ConfigurationTest {

    /**
     * Returns {@code true} if the configuration contains module mn1
     * that reads module mn2.
     */
    static boolean reads(Configuration cf, String mn1, String mn2) {

        Optional<ModuleDescriptor> omd1 = cf.findDescriptor(mn1);
        if (!omd1.isPresent())
            return false;

        ModuleDescriptor md1 = omd1.get();
        return cf.reads(md1).stream()
                .map(ReadDependence::descriptor)
                .map(ModuleDescriptor::name)
                .anyMatch(mn2::equals);
    }


    /**
     * Basic test of resolver
     *     m1 requires m2, m2 requires m3
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

        Configuration cf
            = Configuration.resolve(finder, empty(), ModuleFinder.empty(), "m1");

        assertTrue(cf.descriptors().size() == 3);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2));
        assertTrue(cf.descriptors().contains(descriptor3));

        assertTrue(cf.parent().get() == empty());

        assertEquals(cf.modules().stream()
                        .map(ModuleReference::descriptor)
                        .collect(Collectors.toSet()),
                cf.descriptors());

        assertTrue(cf.findModule("m1").isPresent());
        assertTrue(cf.findModule("m2").isPresent());
        assertTrue(cf.findModule("m3").isPresent());


        // m1 reads m2
        assertTrue(cf.reads(descriptor1).size() == 1);
        assertTrue(reads(cf, "m1", "m2"));

        // m2 reads m3
        assertTrue(cf.reads(descriptor2).size() == 1);
        assertTrue(reads(cf, "m2", "m3"));

        // m3 reads nothing
        assertTrue(cf.reads(descriptor3).size() == 0);

        // toString
        assertTrue(cf.toString().contains("m1"));
        assertTrue(cf.toString().contains("m2"));
        assertTrue(cf.toString().contains("m3"));
    }


    /**
     * Basic test of "requires public":
     *     m1 requires m2, m2 requires public m3
     */
    public void testRequiresPublic1() {
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
            = Configuration.resolve(finder, empty(), ModuleFinder.empty(), "m1");

        assertTrue(cf.descriptors().size() == 3);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2));
        assertTrue(cf.descriptors().contains(descriptor3));

        assertTrue(cf.parent().get() == empty());

        assertEquals(cf.modules().stream()
                        .map(ModuleReference::descriptor)
                        .collect(Collectors.toSet()),
                cf.descriptors());

        assertTrue(cf.findModule("m1").isPresent());
        assertTrue(cf.findModule("m2").isPresent());

        // m1 reads m2 and m3
        assertTrue(cf.reads(descriptor1).size() == 2);
        assertTrue(reads(cf, "m1", "m2"));
        assertTrue(reads(cf, "m1", "m3"));

        // m2 reads m3
        assertTrue(cf.reads(descriptor2).size() == 1);
        assertTrue(reads(cf, "m2", "m3"));

        // m3 reads nothing
        assertTrue(cf.reads(descriptor3).size() == 0);
    }


    /**
     * Basic test of "requires public" with configurations.
     *
     * The test consists of three configurations:
     * - Configuration cf1: m1, m2 requires public m1
     * - Configuration cf2: m3 requires m1
     */
    public void testRequiresPublic2() {

        // cf1: m1 and m2, m2 requires public m1

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .build();

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .requires(Modifier.PUBLIC, "m1")
                .build();

        ModuleFinder finder1 = ModuleUtils.finderOf(descriptor1, descriptor2);

        Configuration cf1
            = Configuration.resolve(finder1, empty(), ModuleFinder.empty(), "m2");

        assertTrue(cf1.descriptors().size() == 2);
        assertTrue(cf1.descriptors().contains(descriptor1));
        assertTrue(cf1.descriptors().contains(descriptor2));
        assertTrue(cf1.parent().get() == empty());

        assertTrue(reads(cf1, "m2", "m1"));
        assertFalse(reads(cf1, "m1", "m2"));


        // cf2: m3, m3 requires m2

        ModuleDescriptor descriptor3
            = new ModuleDescriptor.Builder("m3")
                .requires("m2")
                .build();

        ModuleFinder finder2 = ModuleUtils.finderOf(descriptor3);

        Configuration cf2
            = Configuration.resolve(finder2, cf1, ModuleFinder.empty(), "m3");

        assertTrue(cf2.descriptors().size() == 1);
        assertTrue(cf2.descriptors().contains(descriptor3));
        assertTrue(cf2.parent().get() == cf1);

        assertTrue(cf2.reads(descriptor3).size() == 2);
        assertTrue(reads(cf2, "m3", "m1"));
        assertTrue(reads(cf2, "m3", "m2"));
    }


    /**
     * Basic test of "requires public" with configurations.
     *
     * The test consists of three configurations:
     * - Configuration cf1: m1
     * - Configuration cf2: m2 requires public m3, m3 requires m2
     */
    public void testRequiresPublic3() {

        // cf1: m1

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .build();

        ModuleFinder finder1 = ModuleUtils.finderOf(descriptor1);

        Configuration cf1
            = Configuration.resolve(finder1, empty(), ModuleFinder.empty(), "m1");

        assertTrue(cf1.descriptors().size() == 1);
        assertTrue(cf1.descriptors().contains(descriptor1));
        assertTrue(cf1.parent().get() == empty());


        // cf2: m2, m3: m2 requires public m1, m3 requires m2

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .requires(Modifier.PUBLIC, "m1")
                .build();

        ModuleDescriptor descriptor3
            = new ModuleDescriptor.Builder("m3")
                .requires("m2")
                .build();

        ModuleFinder finder2 = ModuleUtils.finderOf(descriptor2, descriptor3);

        Configuration cf2
            = Configuration.resolve(finder2, cf1, ModuleFinder.empty(), "m3");

        assertTrue(cf2.descriptors().size() == 2);
        assertTrue(cf2.descriptors().contains(descriptor2));
        assertTrue(cf2.descriptors().contains(descriptor3));
        assertTrue(cf2.parent().get() == cf1);

        assertTrue(cf2.reads(descriptor2).size() == 1);
        assertTrue(reads(cf2, "m2", "m1"));

        assertTrue(cf2.reads(descriptor3).size() == 2);
        assertTrue(reads(cf2, "m3", "m1"));
        assertTrue(reads(cf2, "m3", "m2"));
    }


    /**
     * Basic test of "requires public" with configurations.
     *
     * The test consists of three configurations:
     * - Configuration cf1: m1
     * - Configuration cf2: m2 requires public m1
     * - Configuraiton cf3: m3 requires m3
     */
    public void testRequiresPublic4() {

        // cf1: m1

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .build();

        ModuleFinder finder1 = ModuleUtils.finderOf(descriptor1);

        Configuration cf1
            = Configuration.resolve(finder1, empty(), ModuleFinder.empty(), "m1");

        assertTrue(cf1.descriptors().size() == 1);
        assertTrue(cf1.descriptors().contains(descriptor1));
        assertTrue(cf1.parent().get() == empty());

        // cf2: m2 requires public m1

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .requires(Modifier.PUBLIC, "m1")
                .build();

        ModuleFinder finder2 = ModuleUtils.finderOf(descriptor2);

        Configuration cf2
            = Configuration.resolve(finder2, cf1, ModuleFinder.empty(), "m2");

        assertTrue(cf2.descriptors().size() == 1);
        assertTrue(cf2.descriptors().contains(descriptor2));
        assertTrue(cf2.parent().get() == cf1);

        assertTrue(cf2.reads(descriptor2).size() == 1);
        assertTrue(reads(cf2, "m2", "m1"));


        // cf3: m3 requires m2

        ModuleDescriptor descriptor3
            = new ModuleDescriptor.Builder("m3")
                .requires("m2")
                .build();

        ModuleFinder finder3 = ModuleUtils.finderOf(descriptor3);

        Configuration cf3
            = Configuration.resolve(finder3, cf2, ModuleFinder.empty(), "m3");

        assertTrue(cf3.descriptors().size() == 1);
        assertTrue(cf3.descriptors().contains(descriptor3));
        assertTrue(cf3.parent().get() == cf2);

        assertTrue(cf3.reads(descriptor3).size() == 2);
        assertTrue(reads(cf3, "m3", "m1"));
        assertTrue(reads(cf3, "m3", "m2"));
    }


    /**
     * Basic test of "requires public" with configurations.
     *
     * The test consists of two configurations:
     * - Configuration cf1: m1, m2 requires public m1
     * - Configuration cf2: m3 requires public m2, m4 requires m3
     */
    public void testRequiresPublic5() {

        // cf1: m1, m2 requires public m1

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .build();

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .requires(Modifier.PUBLIC, "m1")
                .build();

        ModuleFinder finder1 = ModuleUtils.finderOf(descriptor1, descriptor2);

        Configuration cf1
            = Configuration.resolve(finder1, empty(), ModuleFinder.empty(), "m2");

        assertTrue(cf1.descriptors().size() == 2);
        assertTrue(cf1.descriptors().contains(descriptor1));
        assertTrue(cf1.descriptors().contains(descriptor2));
        assertTrue(cf1.parent().get() == empty());

        assertTrue(cf1.reads(descriptor2).size() == 1);
        assertTrue(reads(cf1, "m2", "m1"));


        // cf2: m3 requires public m2, m4 requires m3

        ModuleDescriptor descriptor3
            = new ModuleDescriptor.Builder("m3")
                .requires(Modifier.PUBLIC, "m2")
                .build();

        ModuleDescriptor descriptor4
            = new ModuleDescriptor.Builder("m4")
                .requires("m3")
                .build();


        ModuleFinder finder2 = ModuleUtils.finderOf(descriptor3, descriptor4);

        Configuration cf2
            = Configuration.resolve(finder2, cf1, ModuleFinder.empty(),  "m3", "m4");

        assertTrue(cf2.descriptors().size() == 2);
        assertTrue(cf2.descriptors().contains(descriptor3));
        assertTrue(cf2.descriptors().contains(descriptor4));

        assertTrue(cf2.parent().get() == cf1);

        assertTrue(cf2.reads(descriptor3).size() == 2);
        assertTrue(reads(cf2, "m3", "m1"));
        assertTrue(reads(cf2, "m3", "m2"));

        assertTrue(cf2.reads(descriptor4).size() == 3);
        assertTrue(reads(cf2, "m4", "m1"));
        assertTrue(reads(cf2, "m4", "m2"));
        assertTrue(reads(cf2, "m4", "m3"));
    }


    /**
     * Basic test of binding services
     */
    public void testServiceBinding1() {

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .uses("p.Service")
                .build();

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .provides("p.Service", "q.ServiceImpl")
                .build();

        ModuleFinder finder = ModuleUtils.finderOf(descriptor1, descriptor2);

        Configuration cf
            = Configuration.resolve(finder, empty(), ModuleFinder.empty(), "m1");

        // only m1 in configuration
        assertTrue(cf.descriptors().size() == 1);
        assertTrue(cf.findModule("m1").isPresent());

        assertTrue(cf.parent().get() == empty());

        assertEquals(cf.modules().stream()
                        .map(ModuleReference::descriptor)
                        .collect(Collectors.toSet()),
                cf.descriptors());

        assertTrue(cf.provides("p.Service").isEmpty());

        // bind services, should augment graph with m2
        cf = cf.bind();

        assertTrue(cf.parent().get() == empty());

        assertTrue(cf.descriptors().size() == 2);
        assertTrue(cf.findModule("m1").isPresent());
        assertTrue(cf.findModule("m2").isPresent());

        assertEquals(cf.modules().stream()
                        .map(ModuleReference::descriptor)
                        .collect(Collectors.toSet()),
                cf.descriptors());

        assertTrue(cf.provides("p.Service").size() == 1);
        assertTrue(cf.provides("p.Service").contains(descriptor2));
    }


    /**
     * Basic test of binding services with configurations.
     *
     * The test consists of two configurations:
     * - Configuration cf1: m1 uses p.Service
     * - Configuration cf2: m2 provides p.Service
     */
    public void testServiceBinding2() {

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .uses("p.Service")
                .build();

        ModuleFinder finder1 = ModuleUtils.finderOf(descriptor1);

        Configuration cf1
            = Configuration.resolve(finder1, empty(), ModuleFinder.empty(), "m1");

        assertTrue(cf1.descriptors().size() == 1);
        assertTrue(cf1.findModule("m1").isPresent());

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .provides("p.Service", "q.ServiceImpl")
                .build();

        ModuleFinder finder2 = ModuleUtils.finderOf(descriptor2);

        Configuration cf2 = Configuration.resolve(finder2, cf1, ModuleFinder.empty());

        assertTrue(cf2.descriptors().size() == 0);

        cf2 = cf2.bind();

        assertTrue(cf2.parent().get() == cf1);

        assertTrue(cf2.descriptors().size() == 1);
        assertTrue(cf2.findModule("m2").isPresent());

        assertTrue(cf2.provides("p.Service").size() == 1);
        assertTrue(cf2.provides("p.Service").contains(descriptor2));
    }


    /**
     * Basic test of binding services with configurations.
     *
     * The test consists of two configurations:
     * - Configuration cf1: m1 uses p.Service && provides p.Service,
     *                      m2 provides p.Service
     * - Configuration cf2: m3 provides p.Service
     *                      m4 provides p.Service
     */
    public void testServiceBinding3() {

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .uses("p.Service")
                .provides("p.Service", "m1.ServiceImpl")
                .build();

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .provides("p.Service", "m2.ServiceImpl")
                .build();

        ModuleFinder finder1 = ModuleUtils.finderOf(descriptor1, descriptor2);

        Configuration cf1
            = Configuration.resolve(finder1, empty(), ModuleFinder.empty(), "m1");

        assertTrue(cf1.descriptors().size() == 1);
        assertTrue(cf1.findModule("m1").isPresent());
        assertTrue(cf1.provides("p.Service").size() == 1);

        cf1 = cf1.bind();

        assertTrue(cf1.parent().get() == empty());

        assertTrue(cf1.descriptors().size() == 2);
        assertTrue(cf1.findModule("m1").isPresent());
        assertTrue(cf1.findModule("m2").isPresent());

        assertTrue(cf1.provides("p.Service").size() == 2);
        assertTrue(cf1.provides("p.Service").contains(descriptor1));
        assertTrue(cf1.provides("p.Service").contains(descriptor2));


        ModuleDescriptor descriptor3
            = new ModuleDescriptor.Builder("m3")
                .provides("p.Service", "m3.ServiceImpl")
                .build();

        ModuleDescriptor descriptor4
            = new ModuleDescriptor.Builder("m4")
                .provides("p.Service", "m4.ServiceImpl")
                .build();

        ModuleFinder finder2 = ModuleUtils.finderOf(descriptor3, descriptor4);

        Configuration cf2 = Configuration.resolve(finder2, cf1, ModuleFinder.empty());

        assertTrue(cf2.descriptors().size() == 0);

        cf2 = cf2.bind();

        assertTrue(cf2.parent().get() == cf1);

        assertTrue(cf2.descriptors().size() == 2);
        assertTrue(cf2.findModule("m3").isPresent());
        assertTrue(cf2.findModule("m4").isPresent());

        assertTrue(cf2.provides("p.Service").size() == 2);
        assertTrue(cf2.provides("p.Service").contains(descriptor3));
        assertTrue(cf2.provides("p.Service").contains(descriptor4));
    }


    /**
     * Basic test of binding services with configurations.
     *
     * Configuration cf1: p@1.0 provides p.Service
     * Test configuration cf2: m1 uses p.Service
     * Test configuration cf2: m1 uses p.Service, p@2.0 uses p.Service
     */
    public void testServiceBinding4() {

        ModuleDescriptor provider_v1
            = new ModuleDescriptor.Builder("p")
                .version("1.0")
                .provides("p.Service", "q.ServiceImpl")
                .build();

        ModuleFinder finder1 = ModuleUtils.finderOf(provider_v1);

        Configuration cf1
            = Configuration.resolve(finder1,
                                    empty(),
                                    ModuleFinder.empty(),
                                    "p");

        assertTrue(cf1.descriptors().size() == 1);
        assertTrue(cf1.descriptors().contains(provider_v1));
        assertTrue(cf1.provides("p.Service").contains(provider_v1));


        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .uses("p.Service")
                .build();

        ModuleDescriptor provider_v2
            = new ModuleDescriptor.Builder("p")
                .version("2.0")
                .provides("p.Service", "q.ServiceImpl")
                .build();

        ModuleFinder finder2 = ModuleUtils.finderOf(descriptor1, provider_v2);

        Configuration cf2;


        // finder2 is the before ModuleFinder and so p@2.0 should be located

        cf2 = Configuration.resolve(finder2, cf1, ModuleFinder.empty(), "m1");

        assertTrue(cf2.descriptors().size() == 1);
        assertTrue(cf2.descriptors().contains(descriptor1));

        cf2 = cf2.bind();

        assertTrue(cf2.descriptors().size() == 2);
        assertTrue(cf2.descriptors().contains(descriptor1));
        assertTrue(cf2.descriptors().contains(provider_v2));
        assertTrue(cf2.provides("p.Service").contains(provider_v2));


        // finder2 is the after ModuleFinder and so p@2.0 should not be located
        // as module p is in parent configuration.

        cf2 = Configuration.resolve(ModuleFinder.empty(), cf1, finder2, "m1");

        assertTrue(cf2.descriptors().size() == 1);
        assertTrue(cf2.descriptors().contains(descriptor1));

        cf2 = cf2.bind();

        assertTrue(cf2.descriptors().size() == 1);
        assertTrue(cf2.descriptors().contains(descriptor1));
        assertTrue(cf2.provides("p.Service").isEmpty());
    }


    /**
     * Basic test with two module finders.
     *
     * Module m2 can be found by both the before and after finders.
     */
    public void testWithTwoFinders1() {

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .requires("m2")
                .build();

        ModuleDescriptor descriptor2_v1
            = new ModuleDescriptor.Builder("m2")
                .version("1.0")
                .build();

        ModuleDescriptor descriptor2_v2
            = new ModuleDescriptor.Builder("m2")
                .version("2.0")
                .build();

        ModuleFinder finder1 = ModuleUtils.finderOf(descriptor2_v1);
        ModuleFinder finder2 = ModuleUtils.finderOf(descriptor1, descriptor2_v2);

        Configuration cf = Configuration.resolve(finder1, empty(), finder2, "m1");

        assertTrue(cf.descriptors().size() == 2);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2_v1));
    }


    /**
     * Basic test with two modules finders and service binding.
     *
     * The before and after ModuleFinders both locate a service provider module
     * named "m2" that provide implementations of the same service type.
     */
    public void testWithTwoFinders2() {

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .uses("p.Service")
                .build();

        ModuleDescriptor descriptor2_v1
            = new ModuleDescriptor.Builder("m2")
                .provides("p.Service", "q.ServiceImpl")
                .build();

        ModuleDescriptor descriptor2_v2
            = new ModuleDescriptor.Builder("m2")
                .provides("p.Service", "q.ServiceImpl")
                .build();

        ModuleFinder finder1 = ModuleUtils.finderOf(descriptor1, descriptor2_v1);
        ModuleFinder finder2 = ModuleUtils.finderOf(descriptor2_v2);

        Configuration cf = Configuration.resolve(finder1, empty(), finder2, "m1");

        assertTrue(cf.descriptors().size() == 1);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.provides("p.Service").isEmpty());

        cf = cf.bind();

        assertTrue(cf.descriptors().size() == 2);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2_v1));
        assertTrue(cf.provides("p.Service").contains(descriptor2_v1));

    }


    /**
     * Basic test for resolving a module that is located in the parent
     * configuration.
     */
    public void testResolvedInParent1() {

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .build();

        ModuleFinder finder1 = ModuleUtils.finderOf(descriptor1);

        Configuration cf1
            = Configuration.resolve(finder1, empty(), ModuleFinder.empty(), "m1");

        assertTrue(cf1.descriptors().size() == 1);
        assertTrue(cf1.descriptors().contains(descriptor1));


        Configuration cf2
            = Configuration.resolve(ModuleFinder.empty(), cf1, finder1, "m1");

        assertTrue(cf2.descriptors().size() == 0);
    }


    /**
     * Basic test for resolving a module that has a dependency on a module
     * in the parent configuration.
     */
    public void testResolvedInParent2() {

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .build();

        ModuleFinder finder1 = ModuleUtils.finderOf(descriptor1);

        Configuration cf1
            = Configuration.resolve(finder1, empty(), ModuleFinder.empty(), "m1");

        assertTrue(cf1.descriptors().size() == 1);
        assertTrue(cf1.descriptors().contains(descriptor1));


        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .requires("m1")
                .build();

        ModuleFinder finder2 = ModuleUtils.finderOf(descriptor2);

        Configuration cf2
            = Configuration.resolve(ModuleFinder.empty(), cf1, finder2, "m2");

        assertTrue(cf2.descriptors().size() == 1);
        assertTrue(cf2.descriptors().contains(descriptor2));

        Set<ReadDependence> reads = cf2.reads(descriptor2);
        assertTrue(reads.size() == 1);
        ReadDependence rd = reads.iterator().next();
        assertEquals(rd.configuration(), cf1);
        assertEquals(rd.descriptor(), descriptor1);
    }


    /**
     * Basic test of using the beforeFinder to override a module in the parent
     * configuration.
     */
    public void testOverriding1() {
        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .build();

        ModuleFinder finder = ModuleUtils.finderOf(descriptor1);

        Configuration cf1
            = Configuration.resolve(finder, empty(), ModuleFinder.empty(), "m1");
        assertTrue(cf1.descriptors().size() == 1);
        assertTrue(cf1.descriptors().contains(descriptor1));

        Configuration cf2
                = Configuration.resolve(finder, cf1, ModuleFinder.empty(), "m1");
        assertTrue(cf2.parent().get() == cf1);
        assertTrue(cf2.descriptors().size() == 1);
        assertTrue(cf2.descriptors().contains(descriptor1));
    }


    /**
     * Basic test of using the beforeFinder to override a module in the parent
     * configuration but where implied readability in the picture so that the
     * module in the parent is read.
     *
     * The test consists of two configurations:
     * - Configuration cf1: m1, m2 requires public m1
     * - Configuration cf2: m1, m3 requires m2
     */
    public void testOverriding2() {

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .build();

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .requires(Modifier.PUBLIC, "m1")
                .build();

        ModuleFinder finder1 = ModuleUtils.finderOf(descriptor1, descriptor2);

        Configuration cf1
            = Configuration.resolve(finder1, empty(), ModuleFinder.empty(), "m2");

        assertTrue(cf1.descriptors().size() == 2);
        assertTrue(cf1.descriptors().contains(descriptor1));
        assertTrue(cf1.descriptors().contains(descriptor2));
        assertTrue(cf1.parent().get() == empty());

        assertTrue(cf1.reads(descriptor2).size() == 1);
        assertTrue(reads(cf1, "m2", "m1"));

        // cf2: m3 requires m2, m1

        ModuleDescriptor descriptor3
            = new ModuleDescriptor.Builder("m3")
                .requires("m2")
                .build();

        ModuleFinder finder2 = ModuleUtils.finderOf(descriptor1, descriptor3);

        Configuration cf2
            = Configuration.resolve(finder2, cf1, ModuleFinder.empty(), "m1", "m3");

        assertTrue(cf2.descriptors().size() == 2);
        assertTrue(cf2.descriptors().contains(descriptor1));
        assertTrue(cf2.descriptors().contains(descriptor3));

        assertTrue(cf2.parent().get() == cf1);

        Set<ReadDependence> reads = cf2.reads(descriptor3);
        assertTrue(reads.size() == 2);
        assertTrue(reads(cf2, "m3", "m1"));
        assertTrue(reads(cf2, "m3", "m2"));

        // check that m3 reads cf1/m1
        ReadDependence rd = reads.stream()
                .filter(m -> m.descriptor().name().equals("m1"))
                .findFirst()
                .get();

        assertTrue(rd.configuration() == cf1);
    }


    /**
     * Root module not found
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testRootNotFound() {
        Configuration.resolve(ModuleFinder.empty(), empty(), ModuleFinder.empty(), "m1");
    }


    /**
     * Direct dependency not found
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testDirectDependencyNotFound() {
        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1").requires("m2").build();
        ModuleFinder finder = ModuleUtils.finderOf(descriptor1);

        Configuration.resolve(finder, empty(), ModuleFinder.empty(), "m1");
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

        Configuration.resolve(finder, empty(), ModuleFinder.empty(), "m1");
    }


    /**
     * Service provider dependency not found
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testServiceProviderDependencyNotFound() {

        // service provider dependency (on m3) not found

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .uses("p.Service")
                .build();

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .requires("m3")
                .provides("p.Service", "q.ServiceImpl")
                .build();

        ModuleFinder finder = ModuleUtils.finderOf(descriptor1, descriptor2);

        Configuration cf;
        try {
            cf = Configuration.resolve(finder, empty(), ModuleFinder.empty(), "m1");
            assertTrue(cf.descriptors().size() == 1);
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

        Configuration.resolve(finder, empty(), ModuleFinder.empty(), "m1");
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
                .provides("p.Service", "q.ServiceImpl")
                .build();
        ModuleDescriptor descriptor3
            = new ModuleDescriptor.Builder("m3")
                .requires("m2")
                .build();

        ModuleFinder finder
            = ModuleUtils.finderOf(descriptor1, descriptor2, descriptor3);

        Configuration cf;
        try {
            cf = Configuration.resolve(finder, empty(), ModuleFinder.empty(), "m1");
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

        Configuration.resolve(finder, empty(), ModuleFinder.empty(), "m1");
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

        Configuration.resolve(finder, empty(), ModuleFinder.empty(), "m1");
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
            = Configuration.resolve(finder, empty(), ModuleFinder.empty(), "m1");

        assertTrue(cf.descriptors().size() == 2);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2));

        // m1 reads m2
        assertTrue(reads(cf, "m1", "m2"));

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

        Configuration bootConfiguration = Layer.boot().configuration();

        Configuration.resolve(finder, bootConfiguration, ModuleFinder.empty(), "m1");
    }


    /**
     * Test the empty configuration.
     */
    public void testEmptyConfiguration() {
        Configuration cf = empty();

        assertFalse(cf.parent().isPresent());

        assertTrue(cf.descriptors().isEmpty());
        assertFalse(cf.findDescriptor("java.base").isPresent());

        assertTrue(cf.modules().isEmpty());
        assertFalse(cf.findModule("java.base").isPresent());

        assertTrue(cf.bind() == cf);
        assertTrue(cf.provides("java.security.Provider").isEmpty());
    }


    // null handling


    @Test(expectedExceptions = { NullPointerException.class })
    public void testResolveWithNull1() {
        ModuleFinder finder = ModuleFinder.empty();
        Configuration.resolve(null, empty(), finder);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testResolveWithNull2() {
        ModuleFinder finder = ModuleFinder.empty();
        Configuration.resolve(finder, null, finder);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testResolveWithNull3() {
        ModuleFinder finder = ModuleFinder.empty();
        Configuration.resolve(finder, empty(), null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testResolveWithNull4() {
        ModuleFinder finder = ModuleFinder.empty();
        Configuration.resolve(finder, empty(), finder, (Collection<String>)null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testResolveWithNull5() {
        ModuleFinder finder = ModuleFinder.empty();
        Configuration.resolve(finder, empty(), finder, (String[])null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testFindDescriptorWithNull() {
        Configuration.empty().findDescriptor(null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testFindModuleWithNull() {
        Configuration.empty().findModule(null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testReadsWithNull() {
        Configuration.empty().reads(null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testProvidesWithNull() {
        Configuration.empty().provides(null);
    }


    // immutable sets

    @Test(expectedExceptions = { UnsupportedOperationException.class })
    public void testImmutableSet1() {
        Configuration cf = Layer.boot().configuration();
        ModuleDescriptor descriptor = cf.findDescriptor("java.base").get();
        Layer.boot().configuration().descriptors().add(descriptor);
    }

    @Test(expectedExceptions = { UnsupportedOperationException.class })
    public void testImmutableSet2() {
        Configuration cf = Layer.boot().configuration();
        ModuleReference mref = cf.findModule("java.base").get();
        cf.modules().add(mref);
    }

    @Test(expectedExceptions = { UnsupportedOperationException.class })
    public void testImmutableSet3() {
        Configuration cf = Layer.boot().configuration();
        ModuleDescriptor descriptor = cf.findDescriptor("java.base").get();
        cf.provides("java.security.Provider").add(descriptor);
    }

}
