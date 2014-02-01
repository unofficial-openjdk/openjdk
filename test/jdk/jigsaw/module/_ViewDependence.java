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
import jdk.jigsaw.module.ViewDependence.Modifier;
import static jdk.jigsaw.module.ViewDependence.Modifier.*;

import org.testng.annotations.*;
import static org.testng.Assert.*;


@Test
public class _ViewDependence {

    private static ViewDependence build(Set<Modifier> mods, String vq) {
        ViewDependence vd = new ViewDependence(mods,
                                               ViewIdQuery.parse(vq));
        out.println(vd);
        return vd;
    }

    public void none() {
        ViewDependence vd = build(null, "foo@1.1");
        assertTrue(vd.modifiers().isEmpty());
        assertEquals(vd.query(), ViewIdQuery.parse("foo@1.1"));
    }

    public void one() {
        ViewDependence vd = build(EnumSet.of(OPTIONAL), "foo@1.1");
        assertEquals(vd.modifiers(), EnumSet.of(OPTIONAL));
        assertEquals(vd.query(), ViewIdQuery.parse("foo@1.1"));
    }

    public void two() {
        ViewDependence vd = build(EnumSet.of(OPTIONAL, PUBLIC), "foo@1.1");
        assertEquals(vd.modifiers(), EnumSet.of(OPTIONAL, PUBLIC));
    }

}
