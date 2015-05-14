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

import java.lang.module.Configuration;
import java.lang.module.Layer;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ServiceLoader;
import javax.script.ScriptEngineFactory;

/**
 * Creates a module Layer containing a service provider module and checks that
 * ServiceLoader.load iterates over that service provider.
 */

public class CustomLayerTest {

    public static void main(String[] args) {

        String moduleName = args[0];
        String engineName = args[1];
        Path dir = Paths.get(args[2]);

        // create the Configuration
        ModuleFinder finder = ModuleFinder.of(dir);
        Configuration cf = Configuration.resolve(finder,
                Layer.bootLayer(),
                ModuleFinder.empty(),
                moduleName);

        // create the Layer with the module loaded by the system class loader
        ClassLoader scl = ClassLoader.getSystemClassLoader();
        ModuleReference mref = cf.findReference(moduleName).get();
        ((sun.misc.ModuleClassLoader)scl).defineModule(mref);
        Layer layer = Layer.create(cf, k -> scl);

        ServiceLoader<ScriptEngineFactory> sl;
        ScriptEngineFactory factory;

        // provider should be found in custom layer
        sl = ServiceLoader.load(layer, ScriptEngineFactory.class);
        factory = find(engineName, sl);
        if (factory == null)
            throw new RuntimeException(engineName + " not found");
        if (factory.getClass().getModule() != layer.findModule(moduleName))
            throw new RuntimeException(engineName + " not loaded by expected module");

        // provider should not be found in boot layer
        sl = ServiceLoader.load(Layer.bootLayer(), ScriptEngineFactory.class);
        factory = find(engineName, sl);
        if (factory != null)
            throw new RuntimeException(engineName + " found (not expected)");
    }

    static ScriptEngineFactory find(String name, ServiceLoader<ScriptEngineFactory> sl) {
        for (ScriptEngineFactory factory : sl) {
            if (factory.getEngineName().equals(name))
                return factory;
        }
        return null;
    }

}
