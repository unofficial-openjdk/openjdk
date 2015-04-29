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
import java.lang.module.ModuleArtifact;
import java.lang.module.ModuleArtifactFinder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// Utility class to set up a ModuleArtifactFinder containing all the modules
// for a given Layer. This utility class is helpful because a ModuleArtifactFinder
// usually locates modules on the file system by searching a sequence of
// directories containing module artifacts.  We want to set up our own modules
// and have a ModuleArtifactLibrary find those module artifacts within a test.

public class ModuleArtifactLibrary implements ModuleArtifactFinder {
 private final Map<String, ModuleArtifact> namesToArtifact = new HashMap<>();

 ModuleArtifactLibrary(ModuleArtifact... artifacts) {
     for (ModuleArtifact artifact: artifacts) {
         ModuleDescriptor emd = artifact.descriptor();
         String name = emd.name();
         namesToArtifact.put(name, artifact);
     }
 }

 @Override
 public ModuleArtifact find(String name) {
     return namesToArtifact.get(name);
 }

 @Override
 public Set<ModuleArtifact> allModules() {
     return new HashSet<>(namesToArtifact.values());
 }
}
