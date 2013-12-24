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

import java.util.*;
import static java.lang.System.out;

import jdk.jigsaw.module.*;
import jdk.jigsaw.module.ServiceDependence.Modifier;
import static jdk.jigsaw.module.ServiceDependence.Modifier.*;

import org.testng.annotations.*;
import static org.testng.Assert.*;


@Test
public class _ServiceDependence {

    private static ServiceDependence build(Set<Modifier> mods, String sn) {
        ServiceDependence sd = new ServiceDependence(mods, sn);
        out.println(sd);
        return sd;
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void nl() {
        build(null, null);
    }

    public void none() {
        ServiceDependence sd = build(null, "foo.Bar");
        assertTrue(sd.modifiers().isEmpty());
        assertEquals(sd.service(), "foo.Bar");
    }

    public void one() {
        ServiceDependence sd = build(EnumSet.of(OPTIONAL), "foo.Bar");
        assertEquals(sd.modifiers(), EnumSet.of(OPTIONAL));
    }

    public void two() {
        ServiceDependence sd = build(EnumSet.of(OPTIONAL, SYNTHETIC),
                                     "foo.Bar");
        assertEquals(sd.modifiers(), EnumSet.of(OPTIONAL, SYNTHETIC));
    }

}
