/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

package build.tools.module;

import build.tools.module.GenerateModulesXml.Module;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import jdk.jigsaw.module.ModuleExport;

/**
 * Run on Jake modular image to generate build/data/checkdeps/modules.xml
 * for JDK 9 build to use.
 */
public class JigsawModules {
    final GenerateModulesXml gentool;
    final boolean skipPackages;
    JigsawModules(GenerateModulesXml gentool, boolean nopackages) {
        this.gentool = gentool;
        this.skipPackages = nopackages;
    }
    private static final String MODULES_SER = "jdk/jigsaw/module/resources/modules.ser";
    public Set<Module> load()
        throws IOException, ClassNotFoundException
    {
        jdk.jigsaw.module.Module[] mods = ModuleUtils.readModules();
        if (mods.length == 0) {
            System.err.format("ERROR: %s is empty%n", MODULES_SER);
            System.exit(1);
        }
        return Arrays.stream(mods)
            .map(m -> toModule(gentool, m))
            .collect(Collectors.toSet());
    }

    private Module toModule(GenerateModulesXml gentool, jdk.jigsaw.module.Module m) {
        Module.Builder mb = new Module.Builder();
        String modulename = m.id().name();
        mb.name(modulename);
        m.moduleDependences().stream()
            .map(d -> d.query().name())
            .sorted()
            .forEach(mb::require);
        m.exports().stream()
            .filter(e -> e.permit() == null)
            .map(ModuleExport::pkg)
            .sorted()
            .forEach(mb::export);
        Map<String, List<ModuleExport>> exportsTo = m.exports().stream()
            .filter(e -> e.permit() != null)
            .sorted(Comparator.comparing(ModuleExport::pkg))
            .collect(Collectors.groupingBy(ModuleExport::pkg));
        exportsTo.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> {
                 Set<String> permits = e.getValue()
                         .stream()
                         .map(ModuleExport::permit).collect(Collectors.toSet());
                 mb.exportTo(e.getKey(), permits);
            });
        if (!skipPackages) {
            try {
                gentool.buildIncludes(mb, modulename);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return mb.build();
    }
}
