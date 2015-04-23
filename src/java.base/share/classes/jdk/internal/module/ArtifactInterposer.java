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
import java.lang.module.ExtendedModuleDescriptor;
import java.lang.module.ModuleArtifact;
import java.lang.module.ModuleArtifactFinder;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleReader;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
    private final Map<String, Set<String>> extraRequires;

    // module name -> exports
    private final Map<String, Set<Exports>> extraExports;

    // module name -> artifact
    private final Map<String, ModuleArtifact> artifacts = new HashMap<>();

    // true if all modules have been cached
    private boolean haveAllModules;

    private ArtifactInterposer(ModuleArtifactFinder finder,
                               Map<String, Set<String>> extraRequires,
                               Map<String, Set<Exports>> extraExports)
    {
        this.finder = finder;
        this.extraRequires = extraRequires;
        this.extraExports = extraExports;
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
        Map<String, Set<String>> extraRequires = new HashMap<>();
        if (addModuleRequiresValue != null) {
            // parse value of AddModuleRequires
            for (String expr: addModuleRequiresValue.split(",")) {
                String[] s = expr.split("=");
                if (s.length != 2)
                    parseFail(expr);
                String m1 = s[0];
                String m2 = s[1];
                extraRequires.computeIfAbsent(m1, k -> new HashSet<>()).add(m2);
            }
        }

        Map<String, Set<Exports>> extraExports = new HashMap<>();
        if (addModuleExportsValue != null) {
            // parse value of AddModuleExports
            for (String expr: addModuleExportsValue.split(",")) {
                String[] s = expr.split("/");
                if (s.length != 2)
                    parseFail(expr);
                String module = s[0];
                Exports export = null;
                s = s[1].split("=");
                if (s.length == 1) {
                    export = new Exports(s[0]);
                } else if (s.length == 2) {
                    export = new Exports(s[0], s[1]);
                } else {
                    parseFail(expr);
                }
                extraExports.computeIfAbsent(module, k -> new HashSet<>()).add(export);
            }
        }

        return new ArtifactInterposer(finder, extraRequires, extraExports);
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
        ExtendedModuleDescriptor descriptor = artifact.descriptor();
        String name = descriptor.name();

        Set<String> requires = extraRequires.get(name);
        Set<Exports> exports = extraExports.get(name);

        if (requires == null && exports == null)
            return artifact;  // no changes requested

        // create a new set of module dependences if needed.
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

        // create a new set of module exports if needed. If AddModuleExports
        // specifies an unqualified export then any qualified exports of
        // that package are dropped. If a package is already has an
        // unqualified export and AddModuleExports specifies a qualified
        // export then the qualified export is ignored.
        Set<Exports> newExports;
        if (exports == null) {
            newExports = descriptor.exports();
        } else {
            // package -> exports
            Map<String, Set<Exports>> pkgToExports = new HashMap<>();
            for (Exports export: exports) {
                String pkg = export.source();
                pkgToExports.computeIfAbsent(pkg, k -> new HashSet<>()).add(export);
            }

            // exports to add to descriptor
            Set<Exports> needToAdd = new HashSet<>(exports);

            // exports to remove from descriptor
            Set<Exports> needToRemove = new HashSet<>();

            for (Exports export: descriptor.exports()) {
                String pkg = export.source();

                Set<Exports> additions = pkgToExports.get(pkg);
                if (additions != null) {
                    String who = export.permit();
                    if (who == null) {
                        // already exported to all so any requested changes to the
                        // exporting of the package should be ignored
                        needToAdd.removeAll(additions);
                    } else {
                        // already has a qualified export, this needs to be dropped when
                        // the requested changes include making it an unqualified export
                        Exports e = new Exports(pkg);
                        if (needToAdd.contains(e))
                            needToRemove.add(export);
                    }
                }
            }

            // updated exports
            newExports = new HashSet<>(descriptor.exports());
            newExports.removeAll(needToRemove);
            newExports.addAll(needToAdd);
        }


        // create a new ExtendedModuleDescriptor with the updated module
        // definition
        ExtendedModuleDescriptor.Builder builder =
            new ExtendedModuleDescriptor.Builder(descriptor.name(),
                                                 descriptor.version());
        newRequires.forEach(builder::requires);
        descriptor.serviceDependences().forEach(builder::uses);
        newExports.forEach(builder::export);
        for (Map.Entry<String, Set<String>> entry : descriptor.services().entrySet()) {
            String s = entry.getKey();
            entry.getValue().forEach(p -> builder.service(s, p));
        }
        ExtendedModuleDescriptor newDescriptor = builder.build();

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

    private static void parseFail(String expr) {
        throw new IllegalArgumentException("'" + expr + "' cannot be parsed");
    }
}
