/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
 * @build ModuleFinderTest ModuleUtils
 * @run testng ModuleFinderTest
 * @summary Basic tests for java.lang.module.ModuleFinder
 */

import java.io.UncheckedIOException;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class ModuleFinderTest {

    /**
     * Test ModuleFinder.ofInstalled
     */
    public void testOfInstalled() {

        ModuleFinder finder = ModuleFinder.ofInstalled();

        assertTrue(finder.find("java.se").isPresent());
        assertTrue(finder.find("java.base").isPresent());
        assertFalse(finder.find("java.rhubarb").isPresent());

        Set<String> names = finder.findAll().stream()
            .map(ModuleReference::descriptor)
            .map(ModuleDescriptor::name)
            .collect(Collectors.toSet());
        assertTrue(names.contains("java.se"));
        assertTrue(names.contains("java.base"));
        assertFalse(names.contains("java.rhubarb"));
    }

    /**
     * Test ModuleFinder.of with zero directories
     */
    public void testZeroDirectories() {

        ModuleFinder finder = ModuleFinder.of();
        assertTrue(finder.findAll().isEmpty());
        assertFalse(finder.find("java.rhubarb").isPresent());
    }

    /**
     * Test ModuleFinder.of with one directory
     */
    public void testOneDirectory() throws Exception {

        Path dir = Files.createTempDirectory("mods");
        ModuleUtils.createExplodedModule(dir.resolve("m1"), "m1");
        ModuleUtils.createExplodedModule(dir.resolve("m2"), "m2");

        ModuleFinder finder = ModuleFinder.of(dir);
        assertTrue(finder.findAll().size() == 2);
        assertTrue(finder.find("m1").isPresent());
        assertTrue(finder.find("m2").isPresent());
        assertFalse(finder.find("java.rhubarb").isPresent());
    }

    /**
     * Test ModuleFinder.of with two directories
     */
    public void testTwoDirectories() throws Exception {

        Path dir1 = Files.createTempDirectory("mods1");
        ModuleUtils.createExplodedModule(dir1.resolve("m1"), "m1@1.0");
        ModuleUtils.createExplodedModule(dir1.resolve("m2"), "m2@1.0");

        Path dir2 = Files.createTempDirectory("mods2");
        ModuleUtils.createExplodedModule(dir2.resolve("m1"), "m1@2.0");
        ModuleUtils.createExplodedModule(dir2.resolve("m2"), "m2@2.0");
        ModuleUtils.createExplodedModule(dir2.resolve("m3"), "m3");
        ModuleUtils.createExplodedModule(dir2.resolve("m4"), "m4");

        ModuleFinder finder = ModuleFinder.of(dir1, dir2);
        assertTrue(finder.findAll().size() == 4);
        assertTrue(finder.find("m1").isPresent());
        assertTrue(finder.find("m2").isPresent());
        assertTrue(finder.find("m3").isPresent());
        assertTrue(finder.find("m4").isPresent());
        assertFalse(finder.find("java.rhubarb").isPresent());

        // check that m1@1.0 (and not m1@2.0) is found
        ModuleDescriptor m1 = finder.find("m1").get().descriptor();
        assertEquals(m1.version().get().toString(), "1.0");

        // check that m2@1.0 (and not m2@2.0) is found
        ModuleDescriptor m2 = finder.find("m2").get().descriptor();
        assertEquals(m2.version().get().toString(), "1.0");
    }

    /**
     * Test ModuleFinder.of with a directory that contains two
     * versions of the same module
     */
    public void testDuplicateModules() throws Exception {

        Path dir = Files.createTempDirectory("mods");
        ModuleUtils.createModularJar(dir.resolve("m1@1.0.jar"), "m1");
        ModuleUtils.createModularJar(dir.resolve("m1@2.0.jar"), "m1");

        ModuleFinder finder = ModuleFinder.of(dir);
        try {
            finder.find("m1");
            assertTrue(false);
        } catch (RuntimeException expected) {
            // expected exception is TBD
        }

        finder = ModuleFinder.of(dir);
        try {
            finder.findAll();
            assertTrue(false);
        } catch (RuntimeException expected) {
            // expected exception is TBD
        }
    }

    /**
     * Test ModuleFinder.of with a bad (does not exist) directory
     */
    public void testBadDirectory() throws Exception {
        Path dir = Files.createTempDirectory("mods");
        Files.delete(dir);

        ModuleFinder finder = ModuleFinder.of(dir);
        try {
            finder.find("java.rhubarb");
            assertTrue(false);
        } catch (UncheckedIOException expected) { }

        finder = ModuleFinder.of(dir);
        try {
            finder.findAll();
            assertTrue(false);
        } catch (UncheckedIOException expected) { }

    }

    /**
     * Test ModuleFinder.concat
     */
    public void testConcat() throws Exception {

        Path dir1 = Files.createTempDirectory("mods1");
        ModuleUtils.createExplodedModule(dir1.resolve("m1"), "m1@1.0");
        ModuleUtils.createExplodedModule(dir1.resolve("m2"), "m2@1.0");

        Path dir2 = Files.createTempDirectory("mods2");
        ModuleUtils.createExplodedModule(dir2.resolve("m1"), "m1@2.0");
        ModuleUtils.createExplodedModule(dir2.resolve("m2"), "m2@2.0");
        ModuleUtils.createExplodedModule(dir2.resolve("m3"), "m3");
        ModuleUtils.createExplodedModule(dir2.resolve("m4"), "m4");

        ModuleFinder finder1 = ModuleFinder.of(dir1);
        ModuleFinder finder2 = ModuleFinder.of(dir2);

        ModuleFinder finder = ModuleFinder.concat(finder1, finder2);
        assertTrue(finder.findAll().size() == 4);
        assertTrue(finder.find("m1").isPresent());
        assertTrue(finder.find("m2").isPresent());
        assertTrue(finder.find("m3").isPresent());
        assertTrue(finder.find("m4").isPresent());
        assertFalse(finder.find("java.rhubarb").isPresent());

        // check that m1@1.0 (and not m1@2.0) is found
        ModuleDescriptor m1 = finder.find("m1").get().descriptor();
        assertEquals(m1.version().get().toString(), "1.0");

        // check that m2@1.0 (and not m2@2.0) is found
        ModuleDescriptor m2 = finder.find("m2").get().descriptor();
        assertEquals(m2.version().get().toString(), "1.0");
    }

    /**
     * Test ModuleFinder.empty
     */
    public void testEmpty() {
        ModuleFinder finder = ModuleFinder.empty();
        assertTrue(finder.findAll().isEmpty());
        assertFalse(finder.find("java.rhubarb").isPresent());
    }

    /**
     * Test null handling
     */
    public void testNulls() {

        try {
            ModuleFinder.ofInstalled().find(null);
            assertTrue(false);
        } catch (NullPointerException expected) { }

        try {
            ModuleFinder.of().find(null);
            assertTrue(false);
        } catch (NullPointerException expected) { }

        try {
            ModuleFinder.empty().find(null);
            assertTrue(false);
        } catch (NullPointerException expected) { }

        try {
            ModuleFinder.of((Path[])null);
            assertTrue(false);
        } catch (NullPointerException expected) { }

        try {
            ModuleFinder.of((Path)null);
            assertTrue(false);
        } catch (NullPointerException expected) { }

        // concat
        ModuleFinder finder = ModuleFinder.of();
        try {
            ModuleFinder.concat(finder, null);
            assertTrue(false);
        } catch (NullPointerException expected) { }
        try {
            ModuleFinder.concat(null, finder);
            assertTrue(false);
        } catch (NullPointerException expected) { }

    }

}

