/*
 * Copyright (c) 2013, 2016, Oracle and/or its affiliates. All rights reserved.
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
 * @modules java.base/java.lang.module:private
 *          java.base/jdk.internal.module
 * @run testng ModuleDescriptorTest
 * @summary Basic test for java.lang.module.ModuleDescriptor and its builder
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.InvalidModuleDescriptorException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Builder;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor.Provides;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import java.lang.module.ModuleDescriptor.Version;
import java.lang.reflect.Constructor;
import java.lang.reflect.Module;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.lang.module.ModuleDescriptor.Requires.Modifier.*;

import jdk.internal.module.ModuleInfoWriter;
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
        return ModuleDescriptor.module("m")
            .requires(mods, mn)
            .build()
            .requires()
            .iterator()
            .next();
    }

    private Requires requires(String mn) {
        return requires(Collections.emptySet(), mn);
    }

    public void testRequiresWithRequires() {
        Requires r1 = requires("foo");
        ModuleDescriptor descriptor = ModuleDescriptor.module("m").requires(r1).build();
        Requires r2 = descriptor.requires().iterator().next();
        assertEquals(r1, r2);
    }

    public void testRequiresWithNoModifiers() {
        Requires r = requires(EnumSet.noneOf(Requires.Modifier.class), "foo");
        assertEquals(r, r);
        assertTrue(r.compareTo(r) == 0);
        assertTrue(r.modifiers().isEmpty());
        assertEquals(r.name(), "foo");
    }

    public void testRequiresWithOneModifier() {
        Requires r = requires(EnumSet.of(TRANSITIVE), "foo");
        assertEquals(r, r);
        assertTrue(r.compareTo(r) == 0);
        assertEquals(r.modifiers(), EnumSet.of(TRANSITIVE));
        assertEquals(r.name(), "foo");
    }

    public void testRequiresWithTwoModifiers() {
        Requires r = requires(EnumSet.of(TRANSITIVE, SYNTHETIC), "foo");
        assertEquals(r, r);
        assertTrue(r.compareTo(r) == 0);
        assertEquals(r.modifiers(), EnumSet.of(TRANSITIVE, SYNTHETIC));
        assertEquals(r.name(), "foo");
    }

    public void testRequiresWithAllModifiers() {
        Requires r = requires(EnumSet.allOf(Modifier.class), "foo");
        assertEquals(r, r);
        assertTrue(r.compareTo(r) == 0);
        assertEquals(r.modifiers(), EnumSet.of(TRANSITIVE, STATIC, SYNTHETIC, MANDATED));
        assertEquals(r.name(), "foo");
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testRequiresWithDuplicatesRequires() {
        Requires r = requires("foo");
        ModuleDescriptor.module("m").requires(r).requires(r);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testRequiresSelfWithRequires() {
        Requires r = requires("foo");
        ModuleDescriptor.module("foo").requires(r);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testRequiresSelfWithNoModifier() {
        ModuleDescriptor.module("m").requires("m");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testRequiresSelfWithOneModifier() {
        ModuleDescriptor.module("m").requires(Set.of(TRANSITIVE), "m");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testRequiresSelfWithAllModifiers() {
        ModuleDescriptor.module("m").requires(EnumSet.allOf(Modifier.class), "m");
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testRequiresWithBadModuleName(String mn, String ignore) {
        requires(EnumSet.noneOf(Modifier.class), mn);
    }

    @Test(expectedExceptions = NullPointerException.class)
    public void testRequiresWithNullRequires() {
        ModuleDescriptor.module("m").requires((Requires) null);
    }

    public void testRequiresCompare() {
        Requires r1 = requires(EnumSet.noneOf(Modifier.class), "foo");
        Requires r2 = requires(EnumSet.noneOf(Modifier.class), "bar");
        int n = "foo".compareTo("bar");
        assertTrue(r1.compareTo(r2) == n);
        assertTrue(r2.compareTo(r1) == -n);
    }

    public void testRequiresCompareWithDifferentModifiers() {
        Requires r1 = requires(EnumSet.of(TRANSITIVE), "foo");
        Requires r2 = requires(EnumSet.of(SYNTHETIC), "foo");
        int n = Integer.compare(1 << TRANSITIVE.ordinal(), 1 << SYNTHETIC.ordinal());
        assertTrue(r1.compareTo(r2) == n);
        assertTrue(r2.compareTo(r1) == -n);
    }

    public void testRequiresCompareWithSameModifiers() {
        Requires r1 = requires(EnumSet.of(SYNTHETIC), "foo");
        Requires r2 = requires(EnumSet.of(SYNTHETIC), "foo");
        assertTrue(r1.compareTo(r2) == 0);
        assertTrue(r2.compareTo(r1) == 0);
    }

    public void testRequiresEqualsAndHashCode() {
        Requires r1 = requires("foo");
        Requires r2 = requires("foo");
        assertEquals(r1, r2);
        assertTrue(r1.hashCode() == r2.hashCode());

        r1 = requires(EnumSet.allOf(Requires.Modifier.class), "foo");
        r2 = requires(EnumSet.allOf(Requires.Modifier.class), "foo");
        assertEquals(r1, r2);
        assertTrue(r1.hashCode() == r2.hashCode());

        r1 = requires("foo");
        r2 = requires("bar");
        assertNotEquals(r1, r2);

        r1 = requires(EnumSet.allOf(Requires.Modifier.class), "foo");
        r2 = requires(Set.of(), "foo");
        assertNotEquals(r1, r2);
    }

    public void testRequiresToString() {
        Requires r = requires(EnumSet.noneOf(Modifier.class), "foo");
        assertTrue(r.toString().contains("foo"));
    }


    // exports

    private Set<Exports.Modifier> asModifiers(Set<String> modifiers) {
        Set<Exports.Modifier> ms = new HashSet<>();
        for (String modifier : modifiers) {
            switch (modifier) {
                case "private" :
                    ms.add(Exports.Modifier.PRIVATE);
                    break;
                default:
                    throw new Error();

            }
        }
        return ms;
    }

    private Exports exports(String pn) {
        return ModuleDescriptor.module("foo")
            .exports(pn)
            .build()
            .exports()
            .iterator()
            .next();
    }

    private Exports exports(Set<String> mods, String pn) {
        Set<Exports.Modifier> ms = asModifiers(mods);
        return ModuleDescriptor.module("foo")
                .exports(ms, pn)
                .build()
                .exports()
                .iterator()
                .next();
    }

    private Exports exports(String pn, String target) {
        return ModuleDescriptor.module("foo")
            .exports(pn, Set.of(target))
            .build()
            .exports()
            .iterator()
            .next();
    }

    private Exports exports(Set<String> mods, String pn, Set<String> targets) {
        Set<Exports.Modifier> ms = asModifiers(mods);
        return ModuleDescriptor.module("foo")
                .exports(ms, pn, targets)
                .build()
                .exports()
                .iterator()
                .next();
    }

    private Exports exports(Set<String> mods, String pn, String target) {
        Set<Exports.Modifier> ms = asModifiers(mods);
        return ModuleDescriptor.module("foo")
                .exports(ms, pn, Set.of(target))
                .build()
                .exports()
                .iterator()
                .next();
    }


    public void testExportsExports() {
        Exports e1 = exports("p");
        ModuleDescriptor descriptor = ModuleDescriptor.module("m").exports(e1).build();
        Exports e2 = descriptor.exports().iterator().next();
        assertEquals(e1, e2);
    }

    public void testExportsToAll() {
        Exports e = exports("p");
        assertEquals(e, e);
        assertTrue(e.modifiers().isEmpty());
        assertEquals(e.source(), "p");
        assertFalse(e.isQualified());
        assertTrue(e.targets().isEmpty());
    }

    public void testExportsToTarget() {
        Exports e = exports("p", "bar");
        assertEquals(e, e);
        assertTrue(e.modifiers().isEmpty());
        assertEquals(e.source(), "p");
        assertTrue(e.isQualified());
        assertTrue(e.targets().size() == 1);
        assertTrue(e.targets().contains("bar"));
    }

    public void testExportsToTargets() {
        Set<String> targets = new HashSet<>();
        targets.add("bar");
        targets.add("gus");
        Exports e
            = ModuleDescriptor.module("foo")
                .exports("p", targets)
                .build()
                .exports()
                .iterator()
                .next();
        assertEquals(e, e);
        assertTrue(e.modifiers().isEmpty());
        assertEquals(e.source(), "p");
        assertTrue(e.isQualified());
        assertTrue(e.targets().size() == 2);
        assertTrue(e.targets().contains("bar"));
        assertTrue(e.targets().contains("gus"));
    }

    public void testExportsToAllWithModifier() {
        Exports e = exports(Set.of("private"), "p");
        assertEquals(e, e);
        assertTrue(e.modifiers().size() == 1);
        assertTrue(e.modifiers().contains(Exports.Modifier.PRIVATE));
        assertEquals(e.source(), "p");
        assertFalse(e.isQualified());
        assertTrue(e.targets().isEmpty());
    }

    public void testExportsToTargetWithModifier() {
        Exports e = exports(Set.of("private"), "p", Set.of("bar"));
        assertEquals(e, e);
        assertTrue(e.modifiers().size() == 1);
        assertTrue(e.modifiers().contains(Exports.Modifier.PRIVATE));
        assertEquals(e.source(), "p");
        assertTrue(e.isQualified());
        assertTrue(e.targets().size() == 1);
        assertTrue(e.targets().contains("bar"));
    }


    @Test(expectedExceptions = IllegalStateException.class)
    public void testExportsWithDuplicate1() {
        Exports e = exports("p");
        ModuleDescriptor.module("foo").exports(e).exports(e);
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testExportsWithDuplicate2() {
        ModuleDescriptor.module("foo").exports("p").exports("p");
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testExportsOnContainedPackage() {
        ModuleDescriptor.module("foo").contains("p").exports("p");
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testExportsToTargetOnContainedPackage() {
        ModuleDescriptor.module("foo").contains("p").exports("p", Set.of("bar"));
    }

    @Test(expectedExceptions = IllegalArgumentException.class )
    public void testExportsWithEmptySet() {
        ModuleDescriptor.module("foo").exports("p", Collections.emptySet());
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testExportsWithBadName(String pn, String ignore) {
        ModuleDescriptor.module("foo").exports(pn);
    }

    @Test(expectedExceptions = NullPointerException.class )
    public void testExportsWithNullExports() {
        ModuleDescriptor.module("foo").exports((Exports) null);
    }

    @Test(expectedExceptions = NullPointerException.class )
    public void testExportsWithNullTargets() {
        ModuleDescriptor.module("foo").exports("p", (Set<String>) null);
    }

    public void testExportsEqualsAndHashCode() {
        Exports e1, e2;

        e1 = exports("p");
        e2 = exports("p");
        assertEquals(e1, e2);
        assertTrue(e1.hashCode() == e2.hashCode());

        e1 = exports(Set.of("private"), "p");
        e2 = exports(Set.of("private"), "p");
        assertEquals(e1, e2);
        assertTrue(e1.hashCode() == e2.hashCode());

        e1 = exports("p");
        e2 = exports("q");
        assertNotEquals(e1, e2);

        e1 = exports(Set.of("private"), "p");
        e2 = exports(Set.of(), "p");
        assertNotEquals(e1, e2);
    }

    public void testExportsToString() {
        String s = ModuleDescriptor.module("foo")
            .exports("p1", Set.of("bar"))
            .build()
            .exports()
            .iterator()
            .next()
            .toString();
        assertTrue(s.contains("p1"));
        assertTrue(s.contains("bar"));
    }


    // overlapping exports

    @DataProvider(name = "exportsAllowed")
    public Object[][] exportsAllowed() {
        return new Object[][]{

            { exports("p"), exports(Set.of("private"), "p", "m") },

            { exports("p", "m1"), exports(Set.of("private"), "p", "m2") },

        };
    }

    @DataProvider(name = "exportsNotAllowed")
    public Object[][] exportsNotAllowed() {
        return new Object[][]{

            { exports("p"), exports("p") },
            { exports("p"), exports("p", "m") },

            { exports(Set.of("private"), "p"), exports(Set.of("private"), "p") },
            { exports(Set.of("private"), "p"), exports(Set.of("private"), "p", "m") },

            { exports("p", "m"), exports("p", "m") },
            { exports("p", "m"), exports(Set.of("private"), "p", "m")},

            { exports(Set.of("private"), "p", "m"), exports(Set.of("private"), "p", "m")},

            { exports("p", "m1"), exports("p", "m2") },

            { exports(Set.of("private"), "p", "m1"), exports(Set.of("private"), "p", "m2")},

        };
    }

    @Test(dataProvider = "exportsAllowed")
    public void testOverlappingExports1(Exports e1, Exports e2) {
        assertEquals(e1.source(), e2.source());

        Set<Exports> exports = ModuleDescriptor.module("foo")
                .exports(e1)
                .exports(e2)
                .build()
                .exports();

        assertTrue(exports.size() == 2);
        Iterator<Exports> iterator = exports.iterator();
        Exports e3 = iterator.next();
        Exports e4 = iterator.next();
        assertEquals(e3.source(), e1.source());
        assertEquals(e4.source(), e1.source());
    }

    @Test(dataProvider = "exportsAllowed")
    public void testOverlappingExports2(Exports e1, Exports e2) {
        // reverse
        testOverlappingExports1(e2, e1);
    }

    @Test(dataProvider = "exportsNotAllowed",
          expectedExceptions = IllegalStateException.class )
    public void testOverlappingExports3(Exports e1, Exports e2) {
        assertEquals(e1.source(), e2.source());
        ModuleDescriptor.module("foo").exports(e1).exports(e2);
    }

    @Test(dataProvider = "exportsNotAllowed",
          expectedExceptions = IllegalStateException.class )
    public void testOverlappingExports4(Exports e1, Exports e2) {
        // reverse
        testOverlappingExports3(e2, e1);
    }


    // uses

    public void testUses() {
        Set<String> uses
            = ModuleDescriptor.module("foo")
                .uses("p.S")
                .uses("q.S")
                .build()
                .uses();
        assertTrue(uses.size() == 2);
        assertTrue(uses.contains("p.S"));
        assertTrue(uses.contains("q.S"));
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testUsesWithDuplicate() {
        ModuleDescriptor.module("foo").uses("p.S").uses("p.S");
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testUsesWithBadName(String service, String ignore) {
        ModuleDescriptor.module("foo").uses(service);
    }


    // provides

    private Provides provides(String st, String pc) {
        return ModuleDescriptor.module("foo")
            .provides(st, pc)
            .build()
            .provides()
            .values()
            .iterator()
            .next();
    }

    public void testProvidesWithProvides() {
        Provides p1 = provides("p.S", "q.S1");
        ModuleDescriptor descriptor = ModuleDescriptor.module("m")
                .provides(p1)
                .build();
        Provides p2 = descriptor.provides().get("p.S");
        assertEquals(p1, p2);
    }

    public void testProvides() {
        Set<String> pns = new HashSet<>();
        pns.add("q.P1");
        pns.add("q.P2");

        Map<String, Provides> map
            = ModuleDescriptor.module("foo")
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

    @Test(expectedExceptions = IllegalStateException.class )
    public void testProvidesWithDuplicateProvides() {
        Provides p = provides("p.S", "q.S2");
        ModuleDescriptor.module("m").provides("p.S", "q.S1").provides(p);
    }

    @Test(expectedExceptions = IllegalArgumentException.class )
    public void testProvidesWithEmptySet() {
        ModuleDescriptor.module("foo").provides("p.Service", Collections.emptySet());
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testProvidesWithBadService(String service, String ignore) {
        ModuleDescriptor.module("foo").provides(service, "p.Provider");
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testProvidesWithBadProvider(String provider, String ignore) {
        ModuleDescriptor.module("foo").provides("p.Service", provider);
    }

    @Test(expectedExceptions = NullPointerException.class )
    public void testProvidesWithNullProvides() {
        ModuleDescriptor.module("foo").provides((Provides) null);
    }

    @Test(expectedExceptions = NullPointerException.class )
    public void testProvidesWithNullProviders() {
        ModuleDescriptor.module("foo").provides("p.S", (Set<String>) null);
    }

    public void testProvidesEqualsAndHashCode() {
        Provides p1, p2;

        p1 = provides("p.S", "q.S1");
        p2 = provides("p.S", "q.S1");
        assertEquals(p1, p2);
        assertTrue(p1.hashCode() == p2.hashCode());

        p1 = provides("p.S", "q.S1");
        p2 = provides("p.S", "q.S2");
        assertNotEquals(p1, p2);

        p1 = provides("p.S", "q.S1");
        p2 = provides("p.S2", "q.S1");
        assertNotEquals(p1, p2);
    }

    // contains

    public void testContains() {
        Set<String> packages = ModuleDescriptor.module("foo")
                .contains("p")
                .contains("q")
                .build()
                .packages();
        assertTrue(packages.size() == 2);
        assertTrue(packages.contains("p"));
        assertTrue(packages.contains("q"));
    }

    public void testContainsWithEmptySet() {
        Set<String> packages = ModuleDescriptor.module("foo")
                .contains(Collections.emptySet())
                .build()
                .packages();
        assertTrue(packages.size() == 0);
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testContainsWithDuplicate() {
        ModuleDescriptor.module("foo").contains("p").contains("p");
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testContainsWithExportedPackage() {
        ModuleDescriptor.module("foo").exports("p").contains("p");
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testContainsWithBadName(String pn, String ignore) {
        ModuleDescriptor.module("foo").contains(pn);
    }


    // packages

    public void testPackages() {
        Set<String> packages = ModuleDescriptor.module("foo")
                .exports("p")
                .contains("q")
                .build()
                .packages();
        assertTrue(packages.size() == 2);
        assertTrue(packages.contains("p"));
        assertTrue(packages.contains("q"));
    }


    // name

    public void testModuleName() {
        String mn = ModuleDescriptor.module("foo").build().name();
        assertEquals(mn, "foo");
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testBadModuleName(String mn, String ignore) {
        ModuleDescriptor.module(mn);
    }


    // version

    public void testVersion1() {
        Version v1 = Version.parse("1.0");
        Version v2 = ModuleDescriptor.module("foo")
                .version(v1)
                .build()
                .version()
                .get();
        assertEquals(v1, v2);
    }

    public void testVersion2() {
        String vs = "1.0";
        Version v1 = ModuleDescriptor.module("foo")
                .version(vs)
                .build()
                .version()
                .get();
        Version v2 = Version.parse(vs);
        assertEquals(v1, v2);
    }

    @Test(expectedExceptions = NullPointerException.class )
    public void testNullVersion1() {
        ModuleDescriptor.module("foo").version((Version) null);
    }

    @Test(expectedExceptions = IllegalArgumentException.class )
    public void testNullVersion2() {
        ModuleDescriptor.module("foo").version((String) null);
    }

    @Test(expectedExceptions = IllegalArgumentException.class )
    public void testEmptyVersion() {
        ModuleDescriptor.module("foo").version("");
    }


    // toNameAndVersion

    public void testToNameAndVersion() {
        ModuleDescriptor md1 = ModuleDescriptor.module("foo").build();
        assertEquals(md1.toNameAndVersion(), "foo");

        ModuleDescriptor md2 = ModuleDescriptor.module("foo").version("1.0").build();
        assertEquals(md2.toNameAndVersion(), "foo@1.0");
    }


    // weak modules

    public void testWeakModules() {
        ModuleDescriptor descriptor = ModuleDescriptor.weakModule("m")
                .requires("java.base")
                .contains("p")
                .build();
        assertTrue(descriptor.isWeak());
        assertTrue(descriptor.packages().size() == 1);
        assertTrue(descriptor.packages().contains("p"));
        assertTrue(descriptor.exports().isEmpty());
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testExportsOnWeakModule1() {
        ModuleDescriptor.weakModule("foo").exports("p");
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testExportsOnWeakModule2() {
        ModuleDescriptor.weakModule("foo").exports("p", Set.of("bar"));
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testExportsOnWeakModule3() {
        Exports e = exports("p");
        ModuleDescriptor.weakModule("foo").exports(e);
    }

    public void testIsWeak() {
        assertFalse(ModuleDescriptor.module("m").build().isWeak());
        assertFalse(ModuleDescriptor.automaticModule("m").build().isWeak());
        assertTrue(ModuleDescriptor.weakModule("m").build().isWeak());
    }


    // automatic modules

    public void testIsAutomatic() {
        ModuleDescriptor descriptor1 = ModuleDescriptor.module("foo").build();
        assertFalse(descriptor1.isAutomatic());

        ModuleDescriptor descriptor2 = ModuleDescriptor.weakModule("foo").build();
        assertFalse(descriptor2.isAutomatic());

        ModuleDescriptor descriptor3 = ModuleDescriptor.automaticModule("foo").build();
        assertTrue(descriptor3.isAutomatic());
    }

    // isSynthetic
    public void testIsSynthetic() {
        assertFalse(Object.class.getModule().getDescriptor().isSynthetic());

        ModuleDescriptor descriptor1 = ModuleDescriptor.module("foo").build();
        assertFalse(descriptor1.isSynthetic());

        ModuleDescriptor descriptor2 = ModuleDescriptor.weakModule("foo").build();
        assertFalse(descriptor2.isSynthetic());

        ModuleDescriptor descriptor3 = ModuleDescriptor.automaticModule("foo").build();
        assertFalse(descriptor3.isSynthetic());
    }


    // mainClass

    public void testMainClass() {
        String mainClass
            = ModuleDescriptor.module("foo").mainClass("p.Main").build().mainClass().get();
        assertEquals(mainClass, "p.Main");
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testMainClassWithBadName(String mainClass, String ignore) {
        Builder builder = ModuleDescriptor.module("foo");
        builder.mainClass(mainClass);
    }


    // osName

    public void testOsName() {
        String osName = ModuleDescriptor.module("foo").osName("Linux").build().osName().get();
        assertEquals(osName, "Linux");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testNullOsName() {
        ModuleDescriptor.module("foo").osName(null);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testEmptyOsName() {
        ModuleDescriptor.module("foo").osName("");
    }


    // osArch

    public void testOsArch() {
        String osArch = ModuleDescriptor.module("foo").osName("arm").build().osName().get();
        assertEquals(osArch, "arm");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testNullOsArch() {
        ModuleDescriptor.module("foo").osArch(null);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testEmptyOsArch() {
        ModuleDescriptor.module("foo").osArch("");
    }


    // osVersion

    public void testOsVersion() {
        String osVersion = ModuleDescriptor.module("foo").osName("11.2").build().osName().get();
        assertEquals(osVersion, "11.2");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testNullOsVersion() {
        ModuleDescriptor.module("foo").osVersion(null);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testEmptyOsVersion() {
        ModuleDescriptor.module("foo").osVersion("");
    }

    // reads

    private static InputStream EMPTY_INPUT_STREAM = new InputStream() {
        @Override
        public int read() {
            return -1;
        }
    };

    private static InputStream FAILING_INPUT_STREAM = new InputStream() {
        @Override
        public int read() throws IOException {
            throw new IOException();
        }
    };

    // basic test reading module-info.class
    public void testRead1() throws Exception {
        Module base = Object.class.getModule();

        try (InputStream in = base.getResourceAsStream("module-info.class")) {
            ModuleDescriptor descriptor = ModuleDescriptor.read(in);
            assertTrue(in.read() == -1); // all bytes read
            assertEquals(descriptor.name(), "java.base");
        }

        try (InputStream in = base.getResourceAsStream("module-info.class")) {
            ByteBuffer bb = ByteBuffer.wrap(in.readAllBytes());
            ModuleDescriptor descriptor = ModuleDescriptor.read(bb);
            assertFalse(bb.hasRemaining()); // no more remaining bytes
            assertEquals(descriptor.name(), "java.base");
        }
    }

    /**
     * Test reading a module-info.class that has a module name, requires,
     * and qualified exports with module names that are not supported in the
     * Java Language.
     */
    public void testRead2() throws Exception {
        // use non-public constructor to create a Builder that is not strict
        Constructor<?> ctor = Builder.class.getDeclaredConstructor(String.class, boolean.class);
        ctor.setAccessible(true);

        Builder builder = (ModuleDescriptor.Builder) ctor.newInstance("m?1", false);
        ModuleDescriptor descriptor = builder
                .requires("java.base")
                .requires("-m1")
                .exports("p", Set.of("m2-"))
                .build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ModuleInfoWriter.write(descriptor, baos);
        ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());

        descriptor = ModuleDescriptor.read(bb);
        assertEquals(descriptor.name(), "m?1");

        Set<String> requires = descriptor.requires()
                .stream()
                .map(Requires::name)
                .collect(Collectors.toSet());
        assertTrue(requires.size() == 2);
        assertTrue(requires.contains("java.base"));
        assertTrue(requires.contains("-m1"));

        assertTrue(descriptor.exports().size() == 1);
        Exports e = descriptor.exports().iterator().next();
        assertTrue(e.targets().size() == 1);
        assertTrue(e.targets().contains("m2-"));
    }

    /**
     * Test ModuleDescriptor with a packager finder
     */
    public void testReadsWithPackageFinder() throws Exception {
        ModuleDescriptor descriptor = ModuleDescriptor.module("foo")
                .requires("java.base")
                .build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ModuleInfoWriter.write(descriptor, baos);
        ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());

        descriptor = ModuleDescriptor.read(bb, () -> Set.of("p", "q"));

        assertTrue(descriptor.packages().size() == 2);
        assertTrue(descriptor.packages().contains("p"));
        assertTrue(descriptor.packages().contains("q"));
    }

    /**
     * Test ModuleDescriptor with a packager finder that doesn't return the
     * complete set of packages.
     */
    @Test(expectedExceptions = InvalidModuleDescriptorException.class)
    public void testReadsWithBadPackageFinder() throws Exception {
        ModuleDescriptor descriptor = ModuleDescriptor.module("foo")
                .requires("java.base")
                .exports("p")
                .build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ModuleInfoWriter.write(descriptor, baos);
        ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());

        // package finder returns a set that doesn't include p
        ModuleDescriptor.read(bb, () -> Set.of("q"));
    }

    @Test(expectedExceptions = InvalidModuleDescriptorException.class)
    public void testReadFromEmptyInputStream() throws Exception {
        ModuleDescriptor.read(EMPTY_INPUT_STREAM);
    }

    @Test(expectedExceptions = IOException.class)
    public void testReadFromFailingInputStream() throws Exception {
        ModuleDescriptor.read(FAILING_INPUT_STREAM);
    }

    @Test(expectedExceptions = InvalidModuleDescriptorException.class)
    public void testReadFromEmptyBuffer() {
        ByteBuffer bb = ByteBuffer.allocate(0);
        ModuleDescriptor.read(bb);
    }

    // The requires table for java.base must be 0 length
    @Test(expectedExceptions = InvalidModuleDescriptorException.class)
    public void testReadOfJavaBaseWithRequires() {
        ModuleDescriptor descriptor
            = ModuleDescriptor.module("java.base")
                .requires("other")
                .build();
        ByteBuffer bb = ModuleInfoWriter.toByteBuffer(descriptor);
        ModuleDescriptor.read(bb);
    }

    // The requires table must have an entry for java.base
    @Test(expectedExceptions = InvalidModuleDescriptorException.class)
    public void testReadWithEmptyRequires() {
        ModuleDescriptor descriptor = ModuleDescriptor.module("m1").build();
        ByteBuffer bb = ModuleInfoWriter.toByteBuffer(descriptor);
        ModuleDescriptor.read(bb);
    }

    // The requires table must have an entry for java.base
    @Test(expectedExceptions = InvalidModuleDescriptorException.class)
    public void testReadWithNoRequiresBase() {
        ModuleDescriptor descriptor
            = ModuleDescriptor.module("m1")
                .requires("m2")
                .build();
        ByteBuffer bb = ModuleInfoWriter.toByteBuffer(descriptor);
        ModuleDescriptor.read(bb);
    }

    public void testReadWithNull() throws Exception {
        Module base = Object.class.getModule();

        try {
            ModuleDescriptor.read((InputStream)null);
            assertTrue(false);
        } catch (NullPointerException expected) { }


        try (InputStream in = base.getResourceAsStream("module-info.class")) {
            try {
                ModuleDescriptor.read(in, null);
                assertTrue(false);
            } catch (NullPointerException expected) { }
        }

        try {
            ModuleDescriptor.read((ByteBuffer)null);
            assertTrue(false);
        } catch (NullPointerException expected) { }


        try (InputStream in = base.getResourceAsStream("module-info.class")) {
            ByteBuffer bb = ByteBuffer.wrap(in.readAllBytes());
            try {
                ModuleDescriptor.read(bb, null);
                assertTrue(false);
            } catch (NullPointerException expected) { }
        }
    }


    // equals/hashCode/compareTo/toString

    public void testEqualsAndHashCode() {
        ModuleDescriptor md1 = ModuleDescriptor.module("foo").build();
        ModuleDescriptor md2 = ModuleDescriptor.module("foo").build();
        assertEquals(md1, md1);
        assertEquals(md1.hashCode(), md2.hashCode());
    }

    public void testCompare() {
        ModuleDescriptor md1 = ModuleDescriptor.module("foo").build();
        ModuleDescriptor md2 = ModuleDescriptor.module("bar").build();
        int n = "foo".compareTo("bar");
        assertTrue(md1.compareTo(md2) == n);
        assertTrue(md2.compareTo(md1) == -n);
    }

    public void testToString() {
        String s = ModuleDescriptor.module("m1").requires("m2").exports("p1").build().toString();
        assertTrue(s.contains("m1"));
        assertTrue(s.contains("m2"));
        assertTrue(s.contains("p1"));
    }

}
