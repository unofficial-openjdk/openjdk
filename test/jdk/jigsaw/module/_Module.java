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
import java.util.function.*;
import java.util.stream.*;
import static java.lang.System.out;

import jdk.jigsaw.module.*;

import org.testng.annotations.*;
import static org.testng.Assert.*;


@Test
public class _Module {

    private static Module build(Consumer<Module.Builder> c) {
        try {
            Module.Builder mb = (new Module.Builder()
                                 .main(new View.Builder().id("foo").build()));
            c.accept(mb);
            Module m = mb.build();
            out.println(m);
            return m;
        } catch (RuntimeException x) {
            out.println(x);
            throw x;
        }
    }

    public void main() {
        Module m = build(b -> { });
        assertEquals(m.id(), ViewId.parse("foo"));
    }

    @Test(expectedExceptions = { IllegalStateException.class })
    public void mainOnce() {
        build(b -> b.main(new View.Builder().id("bar").build()));
    }

    public void view() {
        Module m = build(b -> b.view(new View.Builder().id("bar").build()));
        assertEquals(m.views().stream()
                     .map(View::id).collect(Collectors.toCollection(TreeSet::new)),
                     new TreeSet<>(Arrays.asList(ViewId.parse("bar"),
                                                 ViewId.parse("foo"))));
    }

    public void includes() {
        Module m = build(b -> b.include("sun.misc").include("sun.reflect"));
        assertEquals(m.packages(),
                     new TreeSet<>(Arrays.asList("sun.misc", "sun.reflect")));
    }

    public void reqView() {
        ViewDependence vd = new ViewDependence(null, "baz@>=1.1");
        Module m = build(b -> b.requires(vd));
        assertEquals(m.viewDependences(),
                     new HashSet<>(Arrays.asList(vd)));
    }

    public void reqService() {
        ServiceDependence sd = new ServiceDependence(null, "foo.Bar");
        Module m = build(b -> b.requires(sd));
        assertEquals(m.serviceDependences(),
                     new HashSet<>(Arrays.asList(sd)));
    }

    @Test(expectedExceptions = { IllegalArgumentException.class })
    public void badExport() {
        build(b -> b.view(new View.Builder().id("bar").export("p.q").build()));
    }

}
