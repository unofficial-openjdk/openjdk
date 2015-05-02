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

import java.io.*;
import java.util.*;
import static java.lang.System.out;

import java.lang.module.*;
import static java.lang.module.ModuleDescriptor.Requires.Modifier.*;

import org.testng.annotations.*;
import static org.testng.Assert.*;

@Test
public class JdkModules {

    private static ModuleDescriptor base
        = new ModuleDescriptor.Builder("java.base").build();

    private static ModuleDescriptor compact2
        = new ModuleDescriptor.Builder("java.compact1")
            .requires(MANDATED, "java.base")
            .requires(PUBLIC, "java.compact1")
            .requires(PUBLIC, "java.rmi")
            .requires(PUBLIC, "java.sql")
            .requires(PUBLIC, "java.xml")
            .build();

    private void check(ModuleDescriptor md, ModuleDescriptor ref) {
        assertTrue(md.requires().size() == ref.requires().size());
        assertTrue(md.requires().containsAll(ref.requires()));
    }

    public void go() {
        Set<ModuleArtifact> artifacts
            = ModuleArtifactFinder.installedModules().allModules();
        artifacts.stream().map(ModuleArtifact::descriptor).forEach(md -> {
            switch (md.name()) {
                case "java.base":
                    check(md, base); break;
                case "java.compact2":
                    check(md, compact2); break;
            }
        });
    }

}
