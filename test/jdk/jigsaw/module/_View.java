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
import static java.lang.System.out;

import jdk.jigsaw.module.*;

import org.testng.annotations.*;
import static org.testng.Assert.*;


@Test
public class _View {

    private static View build(Consumer<View.Builder> c) {
        try {
            View.Builder vb = new View.Builder().id("foo");
            c.accept(vb);
            View v = vb.build();
            out.println(v);
            return v;
        } catch (RuntimeException x) {
            out.println(x);
            throw x;
        }
    }

    public void id() {
        View v = build(b -> { });
        assertEquals(v.id(), ViewId.parse("foo"));
    }

    @Test(expectedExceptions = { IllegalStateException.class })
    public void idOnce() {
        build(b -> b.id("bar"));
    }

    public void alias() {
        View v = build(b -> b.alias("bar"));
        assertEquals(v.aliases(),
                     new HashSet<>(Arrays.asList(ViewId.parse("bar"))));
    }

    public void aliases() {
        View v = build(b -> b.alias("bar").alias("baz"));
        assertEquals(v.aliases(),
                     new HashSet<>(Arrays.asList(ViewId.parse("bar"),
                                                 ViewId.parse("baz"))));
    }

    public void permit() {
        View v = build(b -> b.permit("bar"));
        assertEquals(v.permits(), Arrays.asList("bar"));
    }

    public void permits() {
        View v = build(b -> b.permit("bar").permit("baz"));
        assertEquals(v.permits(),
                     new HashSet<>(Arrays.asList("bar", "baz")));
    }

    public void export() {
        View v = build(b -> b.export("java.lang"));
        assertEquals(v.exports(),
                     new HashSet<>(Arrays.asList("java.lang")));
    }

    public void exports() {
        View v = build(b -> b.export("java.lang").export("java.util"));
        assertEquals(v.exports(),
                     new HashSet<>(Arrays.asList("java.lang", "java.util")));
    }

    public void services() {
        View v = build(b -> b
                       .service("foo.Bar", "baz.Gorp")
                       .service("foo.Bar", "baz.Quux"));
        TreeMap<String,Set<String>> svcs = new TreeMap<>();
        svcs.put("foo.Bar",
                 new HashSet<>(Arrays.asList("baz.Gorp", "baz.Quux")));
        assertEquals(v.services(), svcs);
    }

    @Test(expectedExceptions = { IllegalStateException.class })
    public void mainClassOnce() {
        build(b -> b.mainClass("hello.World").mainClass("hello.Universe"));
    }

}
