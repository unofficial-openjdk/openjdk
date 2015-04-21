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

import java.lang.module.ExtendedModuleDescriptor;
import java.lang.module.ModuleArtifact;
import java.lang.module.ModuleArtifactFinder;
import java.lang.module.ModuleDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Vector;

import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

/**
 * Basic tests for ModuleArtifactFinder
 */
@Test
public class ModuleArtifactFinderTest {
    /*
     * Verifies number and names of the module artifacts available in a finder.
     */
    private static void assertModules(ModuleArtifactFinder finder, String... modules) {
        assertEquals(finder.allModules().size(), modules.length);
        for(String m : modules) {
            assertTrue(finder.find(m) != null);
        }
    }

    /*
     * Create a descriptor for testing with a given name.
     */
    private static ExtendedModuleDescriptor build(String name) {
        return new ExtendedModuleDescriptor.Builder(name).build();
    }

    /**
     * One finder used as left and also as right for a concatination.
     */
    public void testDuplicateSame() {
        ModuleArtifactFinder finder =
            new ModuleArtifactLibrary(build("m1"));
        ModuleArtifactFinder concat = ModuleArtifactFinder.concat(finder, finder);
        assertModules(concat, "m1");
    }

    /**
     * Module artifacts with the same name are available from both left and right
     * inner finders.
     */
    public void testDuplicateDifferent() {
        ExtendedModuleDescriptor descriptor1 = build("m1");
        ModuleArtifactFinder finder1 =
            new ModuleArtifactLibrary(descriptor1);
        ModuleArtifactFinder finder2 =
            new ModuleArtifactLibrary(build("m1"));
        ModuleArtifactFinder concat = ModuleArtifactFinder.concat(finder1, finder2);
        assertModules(concat, "m1");
        assertSame(concat.find("m1").descriptor(), descriptor1);
    }

    /**
     * Creates a couple of finders, concatenate them.
     * Changes content of both finders, verifies content of the concatination.
     * TODO: the behavior is undefined, fix and uncomment later
     */
/*
    public void testChanging() {
        ExtendedModuleDescriptor left_m1 = build("m1");
        ExtendedModuleDescriptor left_m2 = build("m2");
        ModuleArtifactLibrary left =
            new ModuleArtifactLibrary(left_m1, left_m2);
        ExtendedModuleDescriptor right_m3 = build("m3");
        ModuleArtifactLibrary right =
            new ModuleArtifactLibrary(right_m3, build("m4"));
        ModuleArtifactFinder concat = ModuleArtifactFinder.concat(left, right);
        //check the content
        assertModules(concat, "m1", "m2", "m3", "m4");
        assertSame(concat.find("m2").descriptor(), left_m2);
        assertSame(concat.find("m3").descriptor(), right_m3);
        //add to the left
        ExtendedModuleDescriptor left_m3 = build("m3");
        left.addAll(build("m0"), left_m3);
        //remove from the left
        left.remove("m2");
        //add to the right
        ExtendedModuleDescriptor right_m2 = build("m2");
        right.addAll(build("m1"),
            right_m2, build("m5"));
        assertModules(concat, "m0", "m1", "m2", "m3", "m4", "m5");
        assertSame(concat.find("m1").descriptor(), left_m1);
        assertSame(concat.find("m2").descriptor(), right_m2);
        assertSame(concat.find("m3").descriptor(), left_m3);
    }
*/

    /**
     * Concatenates two reasonably big finders with uniquely named descriptors.
     */
    public void testReasonablyBig() {
        final int BIG_NUMBER_OF_MODULES = 0x400;
        List<ExtendedModuleDescriptor> leftFinders = new ArrayList<>(BIG_NUMBER_OF_MODULES),
            rightFinders = new ArrayList<>(BIG_NUMBER_OF_MODULES);
        for(int i = 0; i < BIG_NUMBER_OF_MODULES; i++) {
            leftFinders.add(build("m" + i*2));
            rightFinders.add(build("m" + (i*2 + 1)));
        }
        ModuleArtifactLibrary left = new ModuleArtifactLibrary(
            leftFinders.toArray(new ExtendedModuleDescriptor[BIG_NUMBER_OF_MODULES]));
        ModuleArtifactLibrary right = new ModuleArtifactLibrary(
            rightFinders.toArray(new ExtendedModuleDescriptor[BIG_NUMBER_OF_MODULES]));
        ModuleArtifactFinder concat = ModuleArtifactFinder.concat(left, right);
        assertEquals(concat.allModules().size(), BIG_NUMBER_OF_MODULES*2);
        for(int i = 0; i < BIG_NUMBER_OF_MODULES*2; i++) {
            assertNotNull(concat.find("m" + i), String.format("%x'th module", i));
        }
    }

    /**
     * Makes an inner finder to throw an exception, verifies an exception is thrown
     * from the concatenation.
     */
    public void testException() {
        final String ALL_MODULES_MSG = "from allModules";
        final String FIND_MSG = "from find";
        class BrokenFinder implements ModuleArtifactFinder {
            public Set<ModuleArtifact> allModules() {
                throw new RuntimeException(ALL_MODULES_MSG);
            }
            public ModuleArtifact find(String name) {
                throw new RuntimeException(FIND_MSG);
            }
        }
        ModuleArtifactFinder concat = ModuleArtifactFinder.concat(ModuleArtifactFinder.nullFinder(),
            new BrokenFinder());
        try {
            concat.allModules();
            fail("No exception from allModules()");
        } catch(RuntimeException e) {
            assertTrue(e.getMessage().contains(ALL_MODULES_MSG));
        }
        try {
            concat.find("inexistant module");
            fail("No exception from find(String)");
        } catch(RuntimeException e) {
            assertTrue(e.getMessage().contains(FIND_MSG));
        }
    }

    /**
     * Concatenates two empty finders, checks that both are used properly.
     */
    public void testEmpty() {
        class CountingFinder implements ModuleArtifactFinder {
            final AtomicInteger allModulesCallCount = new AtomicInteger(0);
            final Vector findCalls = new Vector();
            final ModuleArtifactFinder inner;
            CountingFinder(ModuleArtifactFinder inner) {
                this.inner = inner;
            }
            public Set<ModuleArtifact> allModules() {
                allModulesCallCount.incrementAndGet();
                return inner.allModules();
            }
            public ModuleArtifact find(String name) {
                findCalls.add(name);
                return inner.find(name);
            }
        }
        CountingFinder empty1 = new CountingFinder(ModuleArtifactFinder.nullFinder());
        CountingFinder empty2 = new CountingFinder(ModuleArtifactFinder.nullFinder());
        ModuleArtifactFinder concat = ModuleArtifactFinder.concat(empty1, empty2);
        assertModules(concat);
        assertEquals(empty1.allModulesCallCount.get(), 1);
        assertEquals(empty2.allModulesCallCount.get(), 1);
        assertEquals(empty1.findCalls.size(), 0);
        assertEquals(empty2.findCalls.size(), 0);
    }
}

