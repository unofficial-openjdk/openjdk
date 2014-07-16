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

import java.lang.reflect.Method;

import jdk.jigsaw.module.Module;
import jdk.jigsaw.module.ModuleGraph;
import jdk.jigsaw.module.ModulePath;
import jdk.jigsaw.module.Resolver;

/**
 * A simple console application that accepts commands to start applications. It's
 * primary use is to help tease out issues for dynamic configurations.
 */

public class Main {

    public static void main(String[] args) throws Exception {

        System.out.println("Initial modules");
        ModuleGraph.getSystemModuleGraph()
                   .modules()
                   .stream()
                   .sorted()
                   .forEach(m -> System.out.format("  %s%n", m.id()));
        System.out.println();

        start("applib", "app1", "app1.Main", true);
        start("applib", "app1", "app1.Main", false);
        start("applib", "app2", "app2.Main", true);
        start("applib", "app2", "app2.Main", false);
    }

    static void start(String appModulePath,
                      String appModuleName,
                      String appMainClass,
                      boolean useSingleLoader) throws Exception {

        System.out.format("Starting %s/%s ...%n", appModuleName, appMainClass);

        ModuleGraph initialModuleGraph = ModuleGraph.getSystemModuleGraph();

        ModulePath modulePath = ModulePath.ofDirectories(appModulePath);

        Resolver resolver = new Resolver(initialModuleGraph, modulePath);

        ModuleGraph graph = resolver.resolve(appModuleName).bindServices();

        System.out.println("Resolved");
        graph.minus(initialModuleGraph)
             .forEach(m -> System.out.format("  %s%n", m.id()));

        // choose a class loader
        ClassLoader cl;
        if (useSingleLoader) {
            cl = new MultiModuleClassLoader(graph);
        } else {
            LoaderPool pool = new LoaderPool(graph);
            cl = pool.findLoader(appModuleName);
        }

        // invoke application main method
        Class<?> c = cl.loadClass(appMainClass);
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
