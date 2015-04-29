/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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

package jdk.internal.module;

import java.io.IOException;
import java.lang.module.ModuleArtifact;
import java.lang.module.ModuleArtifactFinder;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Interposes on a {@code ModuleArtifactFinder} to augment module descriptors
 * with additional requires or exports.
 *
 * This class is intended for use with the VM options AddModuleRequires and
 * AddModuleExports to augment module descriptors with additional requires
 * and exports. Example usages are:
 *
 * <pre>{@code
 *     -XX:AddModuleRequires:jtreg=java.base
 *
 *     -XX:AddModuleExports=java.base/sun.misc=jlib,java.base/sun.reflect
 * }</pre>
 */

class ArtifactInterposer implements ModuleArtifactFinder {

    // the underlying finder
    private final ModuleArtifactFinder finder;

    // module name -> module dependence
    private final Map<String, Set<String>> requiresAdditions;

    // module name -> package name -> set of export additions
    private final Map<String, Map<String, Set<String>>> exportAdditions;

    // Unique set to represent unqualified exports
    private static final Set<String> ALL
        = Collections.unmodifiableSet(new HashSet<>());

    // module name -> artifact
    private final Map<String, ModuleArtifact> artifacts = new HashMap<>();

    // true if all modules have been cached
    private boolean haveAllModules;

    private ArtifactInterposer(ModuleArtifactFinder finder,
                               Map<String, Set<String>> requiresAdditions,
                               Map<String, Map<String, Set<String>>> exportAdditions)
    {
        this.finder = finder;
        this.requiresAdditions = requiresAdditions;
        this.exportAdditions = exportAdditions;
    }

    /**
     * Returns a finder that interposes on the given finder.
     *
     * @throws IllegalArgumentException if expressions specified to
     *     {@code addModuleRequiresValue} or {@code addModuleExportsValue}
     *     cannot be parsed
     */
    static ModuleArtifactFinder interpose(ModuleArtifactFinder finder,
                                          String addModuleRequiresValue,
                                          String addModuleExportsValue)
    {
        Map<String, Set<String>> requiresAdditions = new HashMap<>();
        if (addModuleRequiresValue != null) {
            // parse value of AddModuleRequires
            for (String expr: addModuleRequiresValue.split(",")) {
                String[] s = expr.split("=");
                if (s.length != 2)
                    throw parseFailure(expr);
                String m1 = s[0];
                String m2 = s[1];
                requiresAdditions.computeIfAbsent(m1, k -> new HashSet<>()).add(m2);
            }
        }

        Map<String, Map<String, Set<String>>> exportAdditions = new HashMap<>();
        if (addModuleExportsValue != null) {
            // parse value of AddModuleExports
            for (String expr: addModuleExportsValue.split(",")) {
                String[] s = expr.split("/");
                if (s.length != 2)
                    throw parseFailure(expr);
                String module = s[0];
                s = s[1].split("=");
                String pkg = s[0];
                Map<String, Set<String>> pkgToAdds
                    = exportAdditions.computeIfAbsent(module,
                                                      k -> new HashMap<>());
                if (s.length == 1) {
                    pkgToAdds.put(pkg, ALL);
                } else if (s.length == 2) {
                    pkgToAdds.computeIfAbsent(pkg, k -> new HashSet<>())
                        .add(s[1]);
                } else {
                    throw parseFailure(expr);
                }
            }
        }

        return new ArtifactInterposer(finder, requiresAdditions, exportAdditions);
    }

    @Override
    public ModuleArtifact find(String name) {
        ModuleArtifact artifact = artifacts.get(name);
        if (artifact != null)
            return artifact;

        artifact = finder.find(name);
        if (artifact == null)
            return null;

        artifact = replaceIfNeeded(artifact);
        artifacts.put(name, artifact);
        return artifact;
    }

    @Override
    public Set<ModuleArtifact> allModules() {
        if (!haveAllModules) {
            for (ModuleArtifact artifact: finder.allModules()) {
                String name = artifact.descriptor().name();
                artifacts.computeIfAbsent(name, k -> replaceIfNeeded(artifact));
            }
            haveAllModules = true;
        }
        return new HashSet<>(artifacts.values());
    }

    /**
     * Returns the given module artifact or a replacement with an updated
     * module descriptor with additional requires or widened exports.
     */
    private ModuleArtifact replaceIfNeeded(ModuleArtifact artifact) {
        ModuleDescriptor descriptor = artifact.descriptor();
        String name = descriptor.name();

        Set<String> requires = requiresAdditions.get(name);
        Map<String, Set<String>> exportAdds = exportAdditions.get(name);

        if (requires == null && exportAdds == null)
            return artifact;  // no changes requested

        // create a new set of module dependences if needed
        Set<Requires> newRequires;
        if (requires == null) {
            newRequires = descriptor.requires();
        } else {
            // updated module dependences
            newRequires = new HashSet<>(descriptor.requires());
            for (String dn: requires) {
                newRequires.add(new Requires(null, dn));
            }
        }

        // Create a new set of module exports if needed.  If AddModuleExports
        // specifies an unqualified export then any qualified exports of that
        // package are dropped.  If a package already has an unqualified export
        // and AddModuleExports specifies a qualified export then the qualified
        // export is ignored.

        Set<Exports> newExports;
        if (exportAdds == null) {
            newExports = descriptor.exports();
        } else {

            newExports = new HashSet<>();

            // Process existing exports
            for (Exports export : descriptor.exports()) {
                String pkg = export.source();
                Set<String> adds = exportAdds.get(pkg);
                if (adds != null) {
                    if (!export.targets().isPresent()) {
                        // Already exported to all, so any requested changes to
                        // the exporting of the package should be ignored
                        newExports.add(export);
                    } else {
                        // Already has a qualified export
                        if (adds == ALL) {
                            // Convert this to an unqualified export
                            newExports.add(new Exports(pkg));
                        } else {
                            // Append to this qualified export
                            HashSet<String> ts
                                = new HashSet<>(export.targets().get());
                            ts.addAll(adds);
                            newExports.add(new Exports(pkg, ts));
                        }
                    }
                    exportAdds.remove(pkg);
                } else {
                    newExports.add(export);
                }
            }

            // Process remaining additions
            for (Map.Entry<String, Set<String>> add : exportAdds.entrySet()) {
                String pkg = add.getKey();
                Set<String> adds = add.getValue();
                if (adds == ALL)
                    newExports.add(new Exports(pkg));
                else
                    newExports.add(new Exports(pkg, new HashSet<>(adds)));
            }

        }

        // create a new ModuleDescriptor with the updated module
        // definition
        // ## Need a better way to edit an existing descriptor
        ModuleDescriptor.Builder builder =
            new ModuleDescriptor.Builder(descriptor.name());
        descriptor.version().ifPresent(v -> builder.version(v.toString())); // ##
        newRequires.forEach(builder::requires);
        descriptor.uses().forEach(builder::uses);
        newExports.forEach(builder::export);
        descriptor.provides().values()
            .forEach(p -> builder.provides(p.service(), p.providers()));
        ModuleDescriptor newDescriptor = builder.build();

        // Return a new ModuleArtifact with the new module descriptor
        Set<String> packages = artifact.packages();
        URI location = artifact.location();
        return new ModuleArtifact(newDescriptor, packages, location) {
            @Override
            public ModuleReader open() throws IOException {
                return artifact.open();
            }
        };
    }

    private static IllegalArgumentException parseFailure(String expr) {
        return new IllegalArgumentException("'" + expr + "' cannot be parsed");
    }

}
