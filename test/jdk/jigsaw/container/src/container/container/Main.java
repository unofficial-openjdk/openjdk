/*
 * Copyright (c)  2014, Oracle and/or its affiliates. All rights reserved.
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

package container;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;

import jdk.jigsaw.module.Configuration;
import jdk.jigsaw.module.Layer;
import jdk.jigsaw.module.ModuleArtifactFinder;
import jdk.jigsaw.module.ModuleDescriptor;
import org.omg.DynamicAny._DynEnumStub;

/**
 * A simple console application that accepts commands to start applications. It's
 * primary use is to help tease out issues for dynamic configurations.
 */

public class Main {

    public static void main(String[] args) throws Exception {

        System.out.println("Boot layer");
        Layer.bootLayer()
             .configuration()
             .descriptors()
             .stream()
             .map(ModuleDescriptor::name)
             .forEach(System.out::println);

        // "start" two applications in their own layers
        start("applib", "app1", "app1.Main");
        start("applib", "app2", "app2.Main");
    }

    static void start(String appModulePath,
                      String appModuleName,
                      String appMainClass) throws Exception {

        System.out.format("Starting %s/%s ...%n", appModuleName, appMainClass);

        String[] dirs = appModulePath.split(File.pathSeparator);
        Path[] paths = new Path[dirs.length];
        int i = 0;
        for (String dir: dirs) {
            paths[i++] = Paths.get(dir);
        }

        Layer bootLayer = Layer.bootLayer();

        ModuleArtifactFinder finder = ModuleArtifactFinder.ofDirectories(paths);

        Configuration cf = Configuration.resolve(finder,
                bootLayer,
                ModuleArtifactFinder.nullFinder(),
                appModuleName);
        cf = cf.bind();

        System.out.println("Resolved");
        cf.descriptors()
          .forEach(md -> System.out.format("  %s%n", md.name()));

        // choose a class loader
        ClassLoader cl = new MultiModuleClassLoader(cf);

        // define modules
        Layer layer = Layer.create(cf, k -> cl);

        // invoke application main method
        Class<?> c = layer.findLoader(appModuleName).loadClass(appMainClass);
        Method mainMethod = c.getMethod("main", String[].class);
        mainMethod.setAccessible(true);

        // set TCCL as that is the EE thing to do
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(cl);
            mainMethod.invoke(null, (Object)new String[0]);
        } finally {
            Thread.currentThread().setContextClassLoader(tccl);
        }

        System.out.println();
    }
}
