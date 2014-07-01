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
import jdk.jigsaw.module.ModuleDependence.Modifier;
import static jdk.jigsaw.module.ModuleDependence.Modifier.*;

import org.testng.annotations.*;
import static org.testng.Assert.*;

@Test
public class JdkModules {
    private void base(Module m) throws Exception {
        ModuleDependence[] deps = new ModuleDependence[] {};
        assertEqualsNoOrder(m.moduleDependences().toArray(new ModuleDependence[0]), deps);
    }

    private void jdk(Module m) throws Exception {
        ModuleDependence[] deps = new ModuleDependence[] {
            moduleDep("jdk.runtime"),
            moduleDep("jdk.dev"),
            moduleDep("jdk.svc"),
            moduleDep("jdk.debug")
        };
        assertEqualsNoOrder(m.moduleDependences().toArray(new ModuleDependence[0]), deps);
    }

    public void go() throws Exception {
        Set<Module> modules = ModulePath.installedModules().allModules();

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

    private static ModuleDependence moduleDep(Set<Modifier> mods, String mq) {
        return new ModuleDependence(mods, ModuleIdQuery.parse(mq));
    }

    private static ModuleDependence moduleDep(String mq) {
        return new ModuleDependence(EnumSet.noneOf(Modifier.class), ModuleIdQuery.parse(mq));
    }
}
