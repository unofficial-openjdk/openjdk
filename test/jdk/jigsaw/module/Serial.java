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

import java.io.*;
import java.util.*;
import static java.lang.System.out;

import jdk.jigsaw.module.*;

import org.testng.annotations.*;
import static org.testng.Assert.*;


@Test
public class Serial {

    private static boolean test(Serializable ob) throws Exception {
        out.println(ob);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ObjectOutputStream oo = new ObjectOutputStream(bo)) {
            oo.writeObject(ob);
        }
        Object nob;
        ByteArrayInputStream bi = new ByteArrayInputStream(bo.toByteArray());
        try (ObjectInputStream oi = new ObjectInputStream(bi)) {
            nob = oi.readObject();
        }
        assertEquals(ob, nob);
        return true;
    }

    public void go() throws Exception {

        test(Version.parse("1.3-alpha9"));
        test(VersionQuery.parse(">=1.3"));
        test(ModuleId.parse("foo@1.1"));
        test(ModuleIdQuery.parse("foo@=1.1"));

        ModuleDescriptor descriptor =
            (new ModuleDescriptor.Builder("foo")
                    .requires(new ModuleDependence(EnumSet.of(ModuleDependence.Modifier.PUBLIC),
                                                   "bar@>=2.3.0"))
                    .requires(new ServiceDependence(EnumSet.of(ServiceDependence.Modifier.OPTIONAL),
                                                    "baz.Finder"))
                    .export("p.one")
                    .export("p.two")
                    .service("alpha.Beta", "gamma.Delta")
                    .build());
        test(descriptor);

    }

}
