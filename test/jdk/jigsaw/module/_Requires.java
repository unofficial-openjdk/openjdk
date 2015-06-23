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

import java.util.EnumSet;
import java.util.Set;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import static java.lang.module.ModuleDescriptor.Requires.Modifier.*;

import org.testng.annotations.*;
import static org.testng.Assert.*;


@Test
public class _Requires {

    private Requires requires(Set<Modifier> mods, String mn) {
        return new ModuleDescriptor.Builder(mn).requires(mods, mn).build()
            .requires().iterator().next();
    }

    public void nullModifiers() {
        Requires md = requires(null, "foo");
        assertEquals(md, md);
        assertTrue(md.compareTo(md) == 0);
        assertTrue(md.modifiers().isEmpty());
        assertEquals(md.name(), "foo");
    }

    public void noModifiers() {
        Requires md = requires(EnumSet.noneOf(Modifier.class), "foo");
        assertEquals(md, md);
        assertTrue(md.compareTo(md) == 0);
        assertTrue(md.modifiers().isEmpty());
        assertEquals(md.name(), "foo");
    }

    public void oneModifier() {
        Requires md = requires(EnumSet.of(PUBLIC), "foo");
        assertEquals(md, md);
        assertTrue(md.compareTo(md) == 0);
        assertEquals(md.modifiers(), EnumSet.of(PUBLIC));
        assertEquals(md.name(), "foo");
    }

    public void twoModifiers() {
        Requires md = requires(EnumSet.of(PUBLIC, SYNTHETIC), "foo");
        assertEquals(md, md);
        assertTrue(md.compareTo(md) == 0);
        assertEquals(md.modifiers(), EnumSet.of(PUBLIC, SYNTHETIC));
        assertEquals(md.name(), "foo");
    }

    public void allModifiers() {
        Requires md = requires(EnumSet.allOf(Modifier.class), "foo");
        assertEquals(md, md);
        assertTrue(md.compareTo(md) == 0);
        assertEquals(md.modifiers(), EnumSet.allOf(Modifier.class));
        assertEquals(md.name(), "foo");
    }

    public void compare() {
        Requires md1 = requires(EnumSet.noneOf(Modifier.class), "foo");
        Requires md2 = requires(EnumSet.noneOf(Modifier.class), "bar");
        int n = "foo".compareTo("bar");
        assertTrue(md1.compareTo(md2) == n);
        assertTrue(md2.compareTo(md1) == -n);
    }

    @Test(expectedExceptions = { IllegalArgumentException.class })
    public void nullName() {
        requires(EnumSet.noneOf(Modifier.class), null);
    }

    // TODO: Requires throwing IllegalArgumentException

}
