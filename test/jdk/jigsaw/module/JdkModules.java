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
import jdk.jigsaw.module.ViewDependence.Modifier;
import static jdk.jigsaw.module.ViewDependence.Modifier.*;

import org.testng.annotations.*;
import static org.testng.Assert.*;

@Test
public class JdkModules {
    private static final String MODULES_SER = "jdk/jigsaw/module/resources/modules.ser";
    private void base(Module m) throws Exception {
        assertEquals(m.mainView().aliases(), Collections.singleton(ViewId.parse("java.base")));

        // requires jdk.tls.internal due to the sunjce security provider
        // - to be revisited
        ViewDependence[] deps = new ViewDependence[] {
            viewDep(EnumSet.of(OPTIONAL), "jdk.desktop"),
            viewDep(EnumSet.of(OPTIONAL), "jdk.tls"),
            viewDep(EnumSet.of(OPTIONAL), "jdk.tls.internal")
        };
        assertEqualsNoOrder(m.viewDependences().toArray(new ViewDependence[0]), deps);
    }

    private void jdk(Module m) throws Exception {
        ViewDependence[] deps = new ViewDependence[] {
            viewDep(EnumSet.of(PUBLIC), "jdk.jre"),
            viewDep(EnumSet.of(PUBLIC), "jdk.tools")
        };
        assertEqualsNoOrder(m.viewDependences().toArray(new ViewDependence[0]), deps);
    }

    public void go() throws Exception {
        Set<Module> modules = new HashSet<>();
        try (ObjectInputStream sin = new ObjectInputStream(
                 ClassLoader.getSystemResourceAsStream(MODULES_SER)))
        {
            modules.addAll(Arrays.asList((Module[]) sin.readObject()));
        }

        // do sanity test for the base module for now
        for (Module m : modules) {
            switch (m.id().name()) {
                case "jdk.base":
                    base(m); break;
                case "jdk":
                    jdk(m); break;
            }
        }
    }

    private static ViewDependence viewDep(Set<Modifier> mods, String vq) {
        return new ViewDependence(mods, ViewIdQuery.parse(vq));
    }

}
