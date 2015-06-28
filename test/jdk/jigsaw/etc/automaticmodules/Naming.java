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

/**
 * @test
 * @summary Test module names derived from JAR file names
 * @run testng Naming
 */

import java.io.IOException;
import java.io.OutputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarOutputStream;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class Naming {

    @DataProvider(name = "jars")
    public Object[][] createNames() {

        return new Object[][] {

            // JAR file name                module-name[/version]

            { "foo.jar",                    "foo" },

            { "foo-1.jar",                  "foo/1" },
            { "foo-1.2.jar",                "foo/1.2" },
            { "foo-1.2.3.jar",              "foo/1.2.3" },
            { "foo-1.2.3.4.jar",            "foo/1.2.3.4" },

            { "foo-10.jar",                 "foo/10" },
            { "foo-10.20.jar",              "foo/10.20" },
            { "foo-10.20.30.jar",           "foo/10.20.30" },
            { "foo-10.20.30.40.jar",        "foo/10.20.30.40" },

            { "foo-bar.jar",                "foo.bar" },
            { "foo-bar-1.jar",              "foo.bar/1" },
            { "foo-bar-1.2.jar",            "foo.bar/1.2"},
            { "foo-bar-10.jar",             "foo.bar/10" },
            { "foo-bar-10.20.jar",          "foo.bar/10.20" },

            { "foo-1.2-SNAPSHOT.jar",       "foo/1.2-SNAPSHOT" },
            { "foo-bar-1.2-SNAPSHOT.jar",   "foo.bar/1.2-SNAPSHOT" },

            { "foo--bar-1.0.jar",           "foo.bar/1.0" },
            { "-foo-bar-1.0.jar",           "foo.bar/1.0" },
            { "foo-bar--1.0.jar",           "foo.bar/1.0" },

        };
    }

    @Test(dataProvider = "jars")
    public void testJar(String fn, String mid) throws IOException {

        String[] s = mid.split("/");
        String mn = s[0];
        String vs = (s.length == 2) ? s[1] : null;

        Path dir = Files.createTempDirectory("test");
        Path jf = dir.resolve(fn);
        try {

            // create empty JAR file
            try (OutputStream out = Files.newOutputStream(jf)) {
                try (JarOutputStream jos = new JarOutputStream(out)) {
                }
            }

            // create a ModuleFinder to find modules in the directory
            ModuleFinder finder = ModuleFinder.of(dir);

            // a module with the expected name should be found
            Optional<ModuleReference> mref = finder.find(mn);
            assertTrue(mref.isPresent(), mn + " not found");

            ModuleDescriptor descriptor = mref.get().descriptor();
            assertEquals(descriptor.name(), mn);
            if (vs == null) {
                assertFalse(descriptor.version().isPresent());
            } else {
                assertEquals(descriptor.version().get().toString(), vs);
            }

        } finally {
            Files.deleteIfExists(jf);
            Files.delete(dir);
        }
    }
}
