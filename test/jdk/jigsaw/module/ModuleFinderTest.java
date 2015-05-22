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

import java.lang.module.ModuleReference;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

/**
 * Basic tests for ModuleFinder
 */
@Test
public class ModuleFinderTest {
    /*
     * Verifies number and names of the module references available in a finder.
     */
    private static void assertModules(ModuleFinder finder, String... modules) {
        assertEquals(finder.findAll().size(), modules.length);
        for (String m : modules) {
            assertTrue(finder.find(m) != null);
        }
    }

    /*
     * Create a descriptor for testing with a given name.
     */
    private static ModuleDescriptor build(String name) {
        return new ModuleDescriptor.Builder(name).build();
    }

    /**
     * One finder used as left and also as right for a concatenation.
     */
    public void testDuplicateSame() {
        ModuleFinder finder = new ModuleLibrary(build("m1"));
        ModuleFinder concat = ModuleFinder.concat(finder, finder);
        assertModules(concat, "m1");
    }

    /**
     * Module references with the same name are available from both left and right
     * inner finders.
     */
    public void testDuplicateDifferent() {
        ModuleDescriptor descriptor1 = build("m1");
        ModuleFinder finder1 = new ModuleLibrary(descriptor1);
        ModuleFinder finder2 = new ModuleLibrary(build("m1"));
        ModuleFinder concat = ModuleFinder.concat(finder1, finder2);
        assertModules(concat, "m1");
        assertSame(concat.find("m1").get().descriptor(), descriptor1);
    }

    /**
     * Concatenates two reasonably big finders with uniquely named descriptors.
     */
    public void testReasonablyBig() {
        final int BIG_NUMBER_OF_MODULES = 0x400;
        List<ModuleDescriptor> leftFinders = new ArrayList<>(BIG_NUMBER_OF_MODULES);
        List<ModuleDescriptor> rightFinders = new ArrayList<>(BIG_NUMBER_OF_MODULES);
        for (int i = 0; i < BIG_NUMBER_OF_MODULES; i++) {
            leftFinders.add(build("m" + i*2));
            rightFinders.add(build("m" + (i*2 + 1)));
        }
        ModuleLibrary left = new ModuleLibrary(
            leftFinders.toArray(new ModuleDescriptor[BIG_NUMBER_OF_MODULES]));
        ModuleLibrary right = new ModuleLibrary(
            rightFinders.toArray(new ModuleDescriptor[BIG_NUMBER_OF_MODULES]));
        ModuleFinder concat = ModuleFinder.concat(left, right);
        assertEquals(concat.findAll().size(), BIG_NUMBER_OF_MODULES*2);
        for (int i = 0; i < BIG_NUMBER_OF_MODULES*2; i++) {
            assertNotNull(concat.find("m" + i), String.format("%x'th module", i));
        }
    }

    /**
     * Makes an inner finder to throw an exception, verifies an exception is thrown
     * from the concatenation.
     */
    public void testException() {
        final String ALL_MODULES_MSG = "from findAll";
        final String FIND_MSG = "from find";
        class BrokenFinder implements ModuleFinder {
            @Override
            public Set<ModuleReference> findAll() {
                throw new RuntimeException(ALL_MODULES_MSG);
            }
            @Override
            public Optional<ModuleReference> find(String name) {
                throw new RuntimeException(FIND_MSG);
            }
        }
        ModuleFinder concat =
            ModuleFinder.concat(ModuleFinder.empty(),
                                        new BrokenFinder());
        try {
            concat.findAll();
            fail("No exception from findAll()");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains(ALL_MODULES_MSG));
        }
        try {
            concat.find("inexistant module");
            fail("No exception from find(String)");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains(FIND_MSG));
        }
    }

    /**
     * Concatenates two empty finders, checks that both are used properly.
     */
    public void testEmpty() {
        class CountingFinder implements ModuleFinder {
            final AtomicInteger findAllCallCount = new AtomicInteger(0);
            final Vector findCalls = new Vector();
            final ModuleFinder inner;
            CountingFinder(ModuleFinder inner) {
                this.inner = inner;
            }
            @Override
            public Set<ModuleReference> findAll() {
                findAllCallCount.incrementAndGet();
                return inner.findAll();
            }
            @Override
            public Optional<ModuleReference> find(String name) {
                findCalls.add(name);
                return inner.find(name);
            }
        }
        CountingFinder empty1 = new CountingFinder(ModuleFinder.empty());
        CountingFinder empty2 = new CountingFinder(ModuleFinder.empty());
        ModuleFinder concat = ModuleFinder.concat(empty1, empty2);
        assertModules(concat);
        assertEquals(empty1.findAllCallCount.get(), 1);
        assertEquals(empty2.findAllCallCount.get(), 1);
        assertEquals(empty1.findCalls.size(), 0);
        assertEquals(empty2.findCalls.size(), 0);
    }
}

