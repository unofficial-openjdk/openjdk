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

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import jdk.jigsaw.module.Module;
import jdk.jigsaw.module.ModuleGraph;
import jdk.jigsaw.module.ModulePath;
import jdk.jigsaw.module.SimpleResolver;

public class ModuleUtils {
    private static final String MODULES_SER = "jdk/jigsaw/module/resources/modules.ser";
    public static Module[] readModules()
        throws IOException
    {
        InputStream stream = ClassLoader.getSystemResourceAsStream(MODULES_SER);
        if (stream == null) {
            System.err.format("WARNING: %s not found%n", MODULES_SER);
            return new Module[0];
        }
        try (InputStream in = stream) {
            return readModules(in);
        }
    }

    public static Module[] readModules(InputStream in)
        throws IOException
    {
        try (ObjectInputStream ois = new ObjectInputStream(in)) {
            return (Module[]) ois.readObject();
        } catch (ClassNotFoundException e) {
            throw new Error(e);
        }
    }

    public static ModuleGraph resolve(Set<Module> modules, Set<String> roots)
        throws IOException
    {
        return resolve(modules.toArray(new Module[0]), roots);
    }

    public static ModuleGraph resolve(Module[] modules, Set<String> roots)
        throws IOException
    {
        ModulePath mp = ModulePath.installed(modules);
        SimpleResolver resolver = new SimpleResolver(mp);

        for (String mn : roots) {
            Module m = mp.findLocalModule(mn);
            if (m == null) {
                throw new Error("module " + mn + " not found");
            }
        }

        return resolver.resolve(roots);
    }
}
