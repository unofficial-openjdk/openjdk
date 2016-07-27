/*
 * Copyright (c) 2015, 2016, Oracle and/or its affiliates. All rights reserved.
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
import java.lang.module.ResolvedModule;
import java.lang.reflect.Layer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Basic test of ClassLoader getResource and getResourceAsStream when
 * invoked from code in named modules.
 */

public class Main {

    static final String NAME = "myresource";

    public static void main(String[] args) throws IOException {

        // create resources in m1
        createResource("m1", Paths.get("."));
        createResource("m1", Paths.get("p1"));
        createResource("m1", Paths.get("p1", "impl"));

        // create resources in m2
        createResource("m2", Paths.get("."));
        createResource("m2", Paths.get("p2"));
        createResource("m2", Paths.get("p2", "impl"));


        // invoke ClassLoader getResource from the unnamed module
        ClassLoader thisLoader = Main.class.getClassLoader();
        assertNotNull(thisLoader.getResource("/" + NAME));
        assertNotNull(thisLoader.getResource("/p1/" + NAME));
        assertNull(thisLoader.getResource("/p1/impl" + NAME));
        assertNotNull(thisLoader.getResource("/p2/" + NAME));
        assertNull(thisLoader.getResource("/p2/impl" + NAME));


        // invoke ClassLoader getResource from modules m1
        assertNotNull(p1.Main.getResourceInClassLoader("/" + NAME));
        assertNotNull(p1.Main.getResourceInClassLoader("/p1/" + NAME));
        assertNull(p1.Main.getResourceInClassLoader("/p1/impl" + NAME));
        assertNotNull(p1.Main.getResourceInClassLoader("/p2/" + NAME));
        assertNull(p1.Main.getResourceInClassLoader("/p2/impl" + NAME));

        // invoke ClassLoader getResource from modules m2
        assertNotNull(p2.Main.getResourceInClassLoader("/" + NAME));
        assertNotNull(p2.Main.getResourceInClassLoader("/p1/" + NAME));
        assertNull(p2.Main.getResourceInClassLoader("/p1/impl" + NAME));
        assertNotNull(p2.Main.getResourceInClassLoader("/p2/" + NAME));
        assertNull(p2.Main.getResourceInClassLoader("/p2/impl" + NAME));


        // invoke ClassLoader getResourceAsStream from the unnamed module
        assertNotNull(thisLoader.getResourceAsStream("/" + NAME));
        assertNotNull(thisLoader.getResourceAsStream("/p1/" + NAME));
        assertNull(thisLoader.getResourceAsStream("/p1/impl" + NAME));
        assertNotNull(thisLoader.getResourceAsStream("/p2/" + NAME));
        assertNull(thisLoader.getResourceAsStream("/p2/impl" + NAME));


        // invoke ClassLoader getResource from modules m1
        assertNotNull(p1.Main.getResourceAsStreamInClassLoader("/" + NAME));
        assertNotNull(p1.Main.getResourceAsStreamInClassLoader("/p1/" + NAME));
        assertNull(p1.Main.getResourceAsStreamInClassLoader("/p1/impl" + NAME));
        assertNotNull(p1.Main.getResourceAsStreamInClassLoader("/p2/" + NAME));
        assertNull(p1.Main.getResourceAsStreamInClassLoader("/p2/impl" + NAME));


        // invoke ClassLoader getResource from modules m2
        assertNotNull(p2.Main.getResourceAsStreamInClassLoader("/" + NAME));
        assertNotNull(p2.Main.getResourceAsStreamInClassLoader("/p1/" + NAME));
        assertNull(p2.Main.getResourceAsStreamInClassLoader("/p1/impl" + NAME));
        assertNotNull(p2.Main.getResourceAsStreamInClassLoader("/p2/" + NAME));
        assertNull(p2.Main.getResourceAsStreamInClassLoader("/p2/impl" + NAME));


        // SecurityManager case
        System.setSecurityManager(new SecurityManager());

        assertNull(Main.class.getClassLoader().getResource("/" + NAME));
        assertNull(p1.Main.getResourceInClassLoader("/" + NAME));
        assertNull(p2.Main.getResourceInClassLoader("/" + NAME));

        assertNull(Main.class.getClassLoader().getResourceAsStream("/" + NAME));
        assertNull(p1.Main.getResourceAsStreamInClassLoader("/" + NAME));
        assertNull(p2.Main.getResourceAsStreamInClassLoader("/" + NAME));

        System.out.println("Success!");
    }

    /**
     * Create a resource in the sub-directory of the given exploded module
     */
    static void createResource(String mn, Path subdir) throws IOException {
        Path dir = directoryFor(mn).resolve(subdir);
        Path file = dir.resolve(NAME);
        Files.write(file, mn.getBytes("UTF-8"));
    }

    /**
     * Returns the directory for the given module (by name).
     */
    static Path directoryFor(String mn) {
        Configuration cf = Layer.boot().configuration();
        ResolvedModule resolvedModule = cf.findModule(mn).orElse(null);
        if (resolvedModule == null)
            throw new RuntimeException("not found: " + mn);
        Path dir = Paths.get(resolvedModule.reference().location().get());
        if (!Files.isDirectory(dir))
            throw new RuntimeException("not a directory: " + dir);
        return dir;
    }

    static void assertTrue(boolean condition) {
        if (!condition) throw new RuntimeException();
    }

    static void assertNull(Object o) {
        assertTrue(o == null);
    }

    static void assertNotNull(Object o) {
        assertTrue(o != null);
    }
}

