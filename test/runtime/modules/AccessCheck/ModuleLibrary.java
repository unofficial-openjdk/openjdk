/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleFinder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


// Utility class to set up a ModuleFinder containing all the modules
// for a given Layer. This utility class is helpful because a ModuleFinder
// usually locates modules on the file system by searching a sequence of
// directories containing module mrefs.  We want to set up our own modules
// and have a ModuleLibrary find those module mrefs within a test.

public class ModuleLibrary implements ModuleFinder {

    private final Map<String, ModuleReference> namesToReference = new HashMap<>();

    ModuleLibrary(ModuleReference... mrefs) {
        for (ModuleReference mref: mrefs) {
            ModuleDescriptor emd = mref.descriptor();
            String name = emd.name();
            namesToReference.put(name, mref);
        }
    }

    @Override
    public Optional<ModuleReference> find(String name) {
        return Optional.ofNullable(namesToReference.get(name));
    }

    @Override
    public Set<ModuleReference> findAll() {
        return new HashSet<>(namesToReference.values());
    }

}
