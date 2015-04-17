/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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

import java.util.EnumSet;
import java.lang.module.ModuleDependence;
import java.lang.module.ModuleDependence.Modifier;
import static java.lang.module.ModuleDependence.Modifier.*;

import org.testng.annotations.*;
import static org.testng.Assert.*;


@Test
public class _ModuleDependence {

    public void nullModifiers() {
        ModuleDependence md = new ModuleDependence(null, "foo");
        assertEquals(md, md);
        assertTrue(md.compareTo(md) == 0);
        assertTrue(md.modifiers().isEmpty());
        assertEquals(md.name(), "foo");
    }

    public void noModifiers() {
        ModuleDependence md = new ModuleDependence(EnumSet.noneOf(Modifier.class), "foo");
        assertEquals(md, md);
        assertTrue(md.compareTo(md) == 0);
        assertTrue(md.modifiers().isEmpty());
        assertEquals(md.name(), "foo");
    }

    public void oneModifier() {
        ModuleDependence md = new ModuleDependence(EnumSet.of(PUBLIC), "foo");
        assertEquals(md, md);
        assertTrue(md.compareTo(md) == 0);
        assertEquals(md.modifiers(), EnumSet.of(PUBLIC));
        assertEquals(md.name(), "foo");
    }

    public void twoModifiers() {
        ModuleDependence md = new ModuleDependence(EnumSet.of(PUBLIC, SYNTHETIC), "foo");
        assertEquals(md, md);
        assertTrue(md.compareTo(md) == 0);
        assertEquals(md.modifiers(), EnumSet.of(PUBLIC, SYNTHETIC));
        assertEquals(md.name(), "foo");
    }

    public void allModifiers() {
        ModuleDependence md = new ModuleDependence(EnumSet.allOf(Modifier.class), "foo");
        assertEquals(md, md);
        assertTrue(md.compareTo(md) == 0);
        assertEquals(md.modifiers(), EnumSet.allOf(Modifier.class));
        assertEquals(md.name(), "foo");
    }

    public void compare() {
        ModuleDependence md1 = new ModuleDependence(EnumSet.noneOf(Modifier.class), "foo");
        ModuleDependence md2 = new ModuleDependence(EnumSet.noneOf(Modifier.class), "bar");
        int n = "foo".compareTo("bar");
        assertTrue(md1.compareTo(md2) == n);
        assertTrue(md2.compareTo(md1) == -n);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void nullName() {
        new ModuleDependence(EnumSet.noneOf(Modifier.class), null);
    }

    // TODO: ModuleDependence throwing IllegalArgumentException

}
