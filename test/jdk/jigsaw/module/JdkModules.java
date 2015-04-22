/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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

import java.io.*;
import java.util.*;
import static java.lang.System.out;

import java.lang.module.*;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import static java.lang.module.ModuleDescriptor.Requires.Modifier.*;

import org.testng.annotations.*;
import static org.testng.Assert.*;

@Test
public class JdkModules {
    private void base(ModuleDescriptor m) {
        Requires[] deps = new Requires[] {};
        assertEqualsNoOrder(m.requires().toArray(new Requires[0]), deps);
    }

    private void compact2(ModuleDescriptor m) {
        Requires[] deps = new Requires[] {
            moduleMandatedDep("java.base"),
            modulePublicDep("java.compact1"),
            modulePublicDep("java.rmi"),
            modulePublicDep("java.sql"),
            modulePublicDep("java.xml")
        };
        assertEqualsNoOrder(m.requires().toArray(new Requires[0]), deps);
    }

    public void go() {
        Set<ModuleArtifact> artifacts = ModuleArtifactFinder.installedModules().allModules();

        // do sanity test for the base module for now
        artifacts.stream().map(ModuleArtifact::descriptor).forEach(md -> {
            switch (md.name()) {
                case "java.base":
                    base(md); break;
                case "java.compact2":
                    compact2(md); break;
            }
        });
    }

    private static Requires moduleDep(Set<Modifier> mods, String dn) {
        return new Requires(mods, dn);
    }

    private static Requires modulePublicDep(String dn) {
        return new Requires(EnumSet.of(Modifier.PUBLIC), dn);
    }

    private static Requires moduleMandatedDep(String dn) {
        return new Requires(EnumSet.of(Modifier.MANDATED), dn);
    }

    private static Requires moduleDep(String dn) {
        return new Requires(EnumSet.noneOf(Modifier.class), dn);
    }
}
