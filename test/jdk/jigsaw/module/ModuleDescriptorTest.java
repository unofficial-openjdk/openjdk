/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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
 * @run testng ModuleDescriptorTest
 * @summary Basic test for java.lang.module.ModuleDescriptor and its builder
 */

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Builder;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor.Provides;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import java.lang.module.Version;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static java.lang.module.ModuleDescriptor.Requires.Modifier.*;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class ModuleDescriptorTest {

    @DataProvider(name = "invalidjavaidentifiers")
    public Object[][] invalidJavaIdentifiers() {
        return new Object[][]{

            { null,         null },
            { ".foo",       null },
            { "foo.",       null },
            { "[foo]",      null },

        };
    }


    // requires

    private Requires requires(Set<Modifier> mods, String mn) {
        return new Builder("m")
            .requires(mods, mn)
            .build()
            .requires()
            .iterator()
            .next();
    }

    public void testRequiresWithNullModifiers() {
        Requires r = requires(null, "foo");
        assertEquals(r, r);
        assertTrue(r.compareTo(r) == 0);
        assertTrue(r.modifiers().isEmpty());
        assertEquals(r.name(), "foo");
    }

    public void testRequiresWithNoModifiers() {
        Requires r = requires(EnumSet.noneOf(Requires.Modifier.class), "foo");
        assertEquals(r, r);
        assertTrue(r.compareTo(r) == 0);
        assertTrue(r.modifiers().isEmpty());
        assertEquals(r.name(), "foo");
    }

    public void testRequiresWithOneModifier() {
        Requires r = requires(EnumSet.of(PUBLIC), "foo");
        assertEquals(r, r);
        assertTrue(r.compareTo(r) == 0);
        assertEquals(r.modifiers(), EnumSet.of(PUBLIC));
        assertEquals(r.name(), "foo");
    }

    public void testRequiresWithTwoModifiers() {
        Requires r = requires(EnumSet.of(PUBLIC, SYNTHETIC), "foo");
        assertEquals(r, r);
        assertTrue(r.compareTo(r) == 0);
        assertEquals(r.modifiers(), EnumSet.of(PUBLIC, SYNTHETIC));
        assertEquals(r.name(), "foo");
    }

    public void testRequiresWithAllModifiers() {
        Requires r = requires(EnumSet.allOf(Modifier.class), "foo");
        assertEquals(r, r);
        assertTrue(r.compareTo(r) == 0);
        assertEquals(r.modifiers(), EnumSet.allOf(Modifier.class));
        assertEquals(r.name(), "foo");
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testRequiresWithBadModuleName(String mn, String ignore) {
        requires(EnumSet.noneOf(Modifier.class), mn);
    }

    public void testRequiresCompare() {
        Requires r1 = requires(EnumSet.noneOf(Modifier.class), "foo");
        Requires r2 = requires(EnumSet.noneOf(Modifier.class), "bar");
        int n = "foo".compareTo("bar");
        assertTrue(r1.compareTo(r2) == n);
        assertTrue(r2.compareTo(r1) == -n);
    }


    // exports

    public void testExportsToAll() {
        Exports e
            = new Builder("foo")
                .exports("p")
                .build()
                .exports()
                .iterator()
                .next();
        assertEquals(e, e);
        assertEquals(e.source(), "p");
        assertFalse(e.targets().isPresent());
    }

    public void testExportsToTarget() {
        Exports e
            = new Builder("foo")
                .exports("p", "bar")
                .build()
                .exports()
                .iterator()
                .next();
        assertEquals(e, e);
        assertEquals(e.source(), "p");
        assertTrue(e.targets().isPresent());
        assertTrue(e.targets().get().size() == 1);
        assertTrue(e.targets().get().contains("bar"));
    }

    public void testExportsToTargets() {
        Set<String> targets = new HashSet<>();
        targets.add("bar");
        targets.add("gus");
        Exports e
            = new Builder("foo")
                .exports("p", targets)
                .build()
                .exports()
                .iterator()
                .next();
        assertEquals(e, e);
        assertEquals(e.source(), "p");
        assertTrue(e.targets().isPresent());
        assertTrue(e.targets().get().size() == 2);
        assertTrue(e.targets().get().contains("bar"));
        assertTrue(e.targets().get().contains("gus"));
    }


    @Test(expectedExceptions = IllegalArgumentException.class )
    public void testExportsWithDuplicate() {
        new Builder("foo").exports("p").exports("p");
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testExportsWithBadName(String pn, String ignore) {
        new Builder("foo").exports(pn);
    }

    @Test(expectedExceptions = IllegalArgumentException.class )
    public void testExportsWithNullTarget() {
        new Builder("foo").exports("p", (String) null);
    }

    @Test(expectedExceptions = NullPointerException.class )
    public void testExportsWithNullTargets() {
        new Builder("foo").exports("p", (Set<String>) null);
    }


    // uses

    public void testUses() {
        Set<String> uses
            = new Builder("foo")
                .uses("p.S")
                .uses("q.S")
                .build()
                .uses();
        assertTrue(uses.size() == 2);
        assertTrue(uses.contains("p.S"));
        assertTrue(uses.contains("q.S"));
    }

    @Test(expectedExceptions = IllegalArgumentException.class )
    public void testUsesWithDuplicate() {
        new Builder("foo").uses("p.S").uses("p.S");
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testUsesWithBadName(String service, String ignore) {
        new Builder("foo").uses(service);
    }


    // provides

    public void testProvides() {
        Set<String> pns = new HashSet<>();
        pns.add("q.P1");
        pns.add("q.P2");

        Map<String, Provides> map
            = new Builder("foo")
                .provides("p.S", pns)
                .build()
                .provides();
        assertTrue(map.size() == 1);

        Provides p = map.values().iterator().next();
        assertEquals(p, p);
        assertTrue(p.providers().size() == 2);
        assertTrue(p.providers().contains("q.P1"));
        assertTrue(p.providers().contains("q.P2"));
    }


    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testProvidesWithBadService(String service, String ignore) {
        new Builder("foo").provides(service, "p.Provider");
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testProvidesWithBadProvider(String provider, String ignore) {
        new Builder("foo").provides("p.Service", provider);
    }

    @Test(expectedExceptions = NullPointerException.class )
    public void testProvidesWithNullProviders() {
        new Builder("foo").provides("p.S", (Set<String>) null);
    }


    // conceals

    public void testConceals() {
        Set<String> conceals
            = new Builder("foo").conceals("p").conceals("q").build().conceals();
        assertTrue(conceals.size() == 2);
        assertTrue(conceals.contains("p"));
        assertTrue(conceals.contains("q"));
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testConcealsWithBadName(String pn, String ignore) {
        new Builder("foo").conceals(pn);
    }


    // packages

    public void testPackages() {
        Set<String> packages
            = new Builder("foo").exports("p").conceals("q").build().packages();
        assertTrue(packages.size() == 2);
        assertTrue(packages.contains("p"));
        assertTrue(packages.contains("q"));
    }


    // name

    public void testModuleName() {
        String mn = new Builder("foo").build().name();
        assertEquals(mn, "foo");
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testBadModuleName(String mn, String ignore) {
        new Builder(mn);
    }


    // version

    public void testVersion() {
        Version v = new Builder("foo").version("1.0").build().version().get();
        assertEquals(v, Version.parse("1.0"));
    }

    @Test(expectedExceptions = IllegalArgumentException.class )
    public void testNullVersion() {
        new Builder("foo").version(null);
    }


    // toNameAndVersion

    public void testToNameAndVersion() {
        ModuleDescriptor md1 = new Builder("foo").build();
        assertEquals(md1.toNameAndVersion(), "foo");

        ModuleDescriptor md2 = new Builder("foo").version("1.0").build();
        assertEquals(md2.toNameAndVersion(), "foo@1.0");
    }


    // mainClass

    public void testMainClass() {
        String mainClass
            = new Builder("foo").mainClass("p.Main").build().mainClass().get();
        assertEquals(mainClass, "p.Main");
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testMainClassWithBadName(String mainClass, String ignore) {
        Builder builder = new Builder("foo");
        builder.mainClass(mainClass);
    }


    // equals/hashCode/compareTo

    public void testEqualsAndHashCode() {
        ModuleDescriptor md1 = new Builder("foo").build();
        ModuleDescriptor md2 = new Builder("foo").build();
        assertEquals(md1, md1);
        assertEquals(md1.hashCode(), md2.hashCode());
    }

    public void testCompare() {
        ModuleDescriptor md1 = new Builder("foo").build();
        ModuleDescriptor md2 = new Builder("bar").build();
        int n = "foo".compareTo("bar");
        assertTrue(md1.compareTo(md2) == n);
        assertTrue(md2.compareTo(md1) == -n);
    }

}
