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
 * @library ../lib
 * @build AutomaticModulesTest ModuleUtils
 * @run testng AutomaticModulesTest
 * @summary Basic tests for automatic modules
 */

import java.io.IOException;
import java.io.OutputStream;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.Layer;
import java.lang.reflect.Module;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class AutomaticModulesTest {

    private static final Path USER_DIR
         = Paths.get(System.getProperty("user.dir"));

    private final Configuration BOOT_CONFIGURATION = Layer.boot().configuration();

    @DataProvider(name = "names")
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

    /**
     * Test mapping of JAR file names to module names
     */
    @Test(dataProvider = "names")
    public void testNames(String fn, String mid) throws IOException {

        String[] s = mid.split("/");
        String mn = s[0];
        String vs = (s.length == 2) ? s[1] : null;

        Path dir = Files.createTempDirectory(USER_DIR, "mods");
        Path jf = dir.resolve(fn);

        // create empty JAR file
        createJarFile(jf);

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

    }


    /**
     * Test all packages are exported
     */
    public void testExports() throws IOException {
        Path dir = Files.createTempDirectory(USER_DIR, "mods");
        createJarFile(dir.resolve("m1.jar"),
                      "p/C1.class", "p/C2.class", "q/C1.class");

        ModuleFinder finder = ModuleFinder.of(dir);

        Configuration cf
            = Configuration.resolve(finder,
                                    BOOT_CONFIGURATION,
                                    ModuleFinder.empty(),
                                    "m1");

        ModuleDescriptor m1 = cf.findDescriptor("m1").get();

        Set<String> exports
            = m1.exports().stream().map(Exports::source).collect(Collectors.toSet());

        assertTrue(exports.size() == 2);
        assertTrue(exports.contains("p"));
        assertTrue(exports.contains("q"));
        assertTrue(m1.conceals().isEmpty());
    }


    /**
     * Test that a JAR file with a Main-Class attribute results
     * in a module with a main class.
     */
    public void testMainClass() throws IOException {
        String mainClass = "p.Main";

        Manifest man = new Manifest();
        Attributes attrs = man.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0.0");
        attrs.put(Attributes.Name.MAIN_CLASS, mainClass);

        Path dir = Files.createTempDirectory(USER_DIR, "mods");
        createJarFile(dir.resolve("m1.jar"), man);

        ModuleFinder finder = ModuleFinder.of(dir);

        Configuration cf
            = Configuration.resolve(finder,
                                    BOOT_CONFIGURATION,
                                    ModuleFinder.empty(),
                                    "m1");

        ModuleDescriptor m1 = cf.findDescriptor("m1").get();

        assertTrue(m1.mainClass().isPresent());
        assertEquals(m1.mainClass().get(), mainClass);
    }


    /**
     * Basic test of a configuration created with automatic modules.
     *   m1 requires m2*
     *   m1 requires m3*
     *   m2*
     *   m3*
     */
    public void testConfiguration1() throws Exception {
        ModuleDescriptor m1
            = new ModuleDescriptor.Builder("m1")
                .requires("m2")
                .requires("m3")
                .requires("java.base")
                .build();

        // m2 and m3 are automatic modules
        Path dir = Files.createTempDirectory(USER_DIR, "mods");
        createJarFile(dir.resolve("m2.jar"), "p/T.class");
        createJarFile(dir.resolve("m3.jar"), "q/T.class");

        // module finder locates m1 and the modules in the directory
        ModuleFinder finder
            = ModuleFinder.concat(ModuleUtils.finderOf(m1),
                ModuleFinder.of(dir));

        Configuration cf
            = Configuration.resolve(finder,
                BOOT_CONFIGURATION,
                ModuleFinder.empty(),
                "m1");

        assertTrue(cf.descriptors().size() == 3);
        assertTrue(cf.findDescriptor("m1").isPresent());
        assertTrue(cf.findDescriptor("m2").isPresent());
        assertTrue(cf.findDescriptor("m3").isPresent());

        ModuleDescriptor m2 = cf.findDescriptor("m2").get();
        ModuleDescriptor m3 = cf.findDescriptor("m3").get();

        // the modules in the boot Layer
        Set<String> bootModules = Layer.boot().modules().stream()
                .map(Module::getName)
                .collect(Collectors.toSet());

        // m2 && m3 only require java.base
        assertTrue(m2.requires().size() == 1);
        assertTrue(m3.requires().size() == 1);

        // readability

        assertTrue(cf.reads(m1).size() == 3);
        assertTrue(reads(cf, "m1", "java.base"));
        assertTrue(reads(cf, "m1", "m2"));
        assertTrue(reads(cf, "m1", "m3"));

        assertTrue(reads(cf, "m2", "m1"));
        assertTrue(reads(cf, "m2", "m3"));
        testReadAllBootModules(cf, "m2");  // m2 reads all modules in boot layer

        assertTrue(reads(cf, "m3", "m1"));
        assertTrue(reads(cf, "m3", "m2"));
        testReadAllBootModules(cf, "m3");  // m3 reads all modules in boot layer

    }

    /**
     * Basic test of a configuration created with automatic modules
     *   m1 requires m2
     *   m2 requires m3*
     *   m3*
     *   m4*
     */
    public void testInConfiguration2() throws IOException {
        ModuleDescriptor m1
            =  new ModuleDescriptor.Builder("m1")
                .requires("m2")
                .requires("java.base")
                .build();

        ModuleDescriptor m2
            =  new ModuleDescriptor.Builder("m2")
                .requires("m3")
                .requires("java.base")
                .build();

        // m3 and m4 are automatic modules
        Path dir = Files.createTempDirectory(USER_DIR, "mods");
        createJarFile(dir.resolve("m3.jar"), "p/T.class");
        createJarFile(dir.resolve("m4.jar"), "q/T.class");

        // module finder locates m1 and the modules in the directory
        ModuleFinder finder
            = ModuleFinder.concat(ModuleUtils.finderOf(m1, m2),
                                  ModuleFinder.of(dir));

        Configuration cf
            = Configuration.resolve(finder,
                                    BOOT_CONFIGURATION,
                                    ModuleFinder.empty(),
                                    "m1", "m4");

        assertTrue(cf.descriptors().size() == 4);
        assertTrue(cf.findDescriptor("m1").isPresent());
        assertTrue(cf.findDescriptor("m2").isPresent());
        assertTrue(cf.findDescriptor("m3").isPresent());
        assertTrue(cf.findDescriptor("m4").isPresent());


        ModuleDescriptor m3 = cf.findDescriptor("m3").get();
        ModuleDescriptor m4 = cf.findDescriptor("m4").get();

        // m3 && m4 should only require java.base
        assertTrue(m3.requires().size() == 1);
        assertTrue(m4.requires().size() == 1);

        // readability

        assertTrue(cf.reads(m1).size() == 2);
        assertTrue(reads(cf, "m1", "m2"));
        assertTrue(reads(cf, "m1", "java.base"));

        assertTrue(cf.reads(m2).size() == 3);
        assertTrue(reads(cf, "m2", "m3"));
        assertTrue(reads(cf, "m2", "m4"));
        assertTrue(reads(cf, "m2", "java.base"));

        assertTrue(reads(cf, "m3", "m1"));
        assertTrue(reads(cf, "m3", "m2"));
        assertTrue(reads(cf, "m3", "m4"));
        testReadAllBootModules(cf, "m3");  // m3 reads all modules in boot layer

        assertTrue(reads(cf, "m4", "m1"));
        assertTrue(reads(cf, "m4", "m2"));
        assertTrue(reads(cf, "m4", "m3"));
        testReadAllBootModules(cf, "m4");  // m4 reads all modules in boot layer

    }


    /**
     * Basic test of a configuration created with automatic modules
     *   m1 requires m2
     *   m2 requires public m3*
     *   m3*
     *   m4*
     */
    public void testInConfiguration3() throws IOException {
        ModuleDescriptor m1
            =  new ModuleDescriptor.Builder("m1")
                .requires("m2")
                .requires("java.base")
                .build();

        ModuleDescriptor m2
            =  new ModuleDescriptor.Builder("m2")
                .requires(Modifier.PUBLIC, "m3")
                .requires("java.base")
                .build();

        // m3 and m4 are automatic modules
        Path dir = Files.createTempDirectory(USER_DIR, "mods");
        createJarFile(dir.resolve("m3.jar"), "p/T.class");
        createJarFile(dir.resolve("m4.jar"), "q/T.class");

        // module finder locates m1 and the modules in the directory
        ModuleFinder finder
                = ModuleFinder.concat(ModuleUtils.finderOf(m1, m2),
                ModuleFinder.of(dir));

        Configuration cf
            = Configuration.resolve(finder,
                BOOT_CONFIGURATION,
                ModuleFinder.empty(),
                "m1", "m4");

        assertTrue(cf.descriptors().size() == 4);
        assertTrue(cf.findDescriptor("m1").isPresent());
        assertTrue(cf.findDescriptor("m2").isPresent());
        assertTrue(cf.findDescriptor("m3").isPresent());
        assertTrue(cf.findDescriptor("m4").isPresent());

        ModuleDescriptor m3 = cf.findDescriptor("m3").get();
        ModuleDescriptor m4 = cf.findDescriptor("m4").get();

        // m3 && m4 should only require java.base
        assertTrue(m3.requires().size() == 1);
        assertTrue(m4.requires().size() == 1);

        // readability

        assertTrue(cf.reads(m1).size() == 4);
        assertTrue(reads(cf, "m1", "m2"));
        assertTrue(reads(cf, "m1", "m3"));
        assertTrue(reads(cf, "m1", "m4"));
        assertTrue(reads(cf, "m1", "java.base"));

        assertTrue(cf.reads(m2).size() == 3);
        assertTrue(reads(cf, "m2", "m3"));
        assertTrue(reads(cf, "m2", "m4"));
        assertTrue(reads(cf, "m2", "java.base"));

        assertTrue(reads(cf, "m3", "m1"));
        assertTrue(reads(cf, "m3", "m2"));
        assertTrue(reads(cf, "m3", "m4"));
        testReadAllBootModules(cf, "m3");  // m3 reads all modules in boot layer

        assertTrue(reads(cf, "m4", "m1"));
        assertTrue(reads(cf, "m4", "m2"));
        assertTrue(reads(cf, "m4", "m3"));
        testReadAllBootModules(cf, "m4");  // m4 reads all modules in boot layer
    }


    /**
     * Basic test of Layer containing automatic modules
     */
    public void testInLayer() throws IOException {
        ModuleDescriptor descriptor
            =  new ModuleDescriptor.Builder("m1")
                .requires("m2")
                .requires("m3")
                .build();

        // m2 and m3 are simple JAR files
        Path dir = Files.createTempDirectory(USER_DIR, "mods");
        createJarFile(dir.resolve("m2.jar"), "p/T.class");
        createJarFile(dir.resolve("m3.jar"), "q/T2.class");

        // module finder locates m1 and the modules in the directory
        ModuleFinder finder
            = ModuleFinder.concat(ModuleUtils.finderOf(descriptor),
                ModuleFinder.of(dir));

        Configuration cf
            = Configuration.resolve(finder,
                                    BOOT_CONFIGURATION,
                                    ModuleFinder.empty(),
                                    "m1");
        assertTrue(cf.descriptors().size() == 3);

        // each module gets its own loader
        Layer layer = Layer.create(cf, Layer.boot(), mn -> new ClassLoader(){});

        Module m2 = layer.findModule("m2").get();
        assertTrue(m2.isNamed());
        assertTrue(m2.canRead(null));
        testsReadsAll(m2, layer);

        Module m3 = layer.findModule("m3").get();
        assertTrue(m3.isNamed());
        assertTrue(m3.canRead(null));
        testsReadsAll(m3, layer);
    }


    /**
     * Test that a module in a configuration reads all modules in the boot
     * configuration.
     */
    static void testReadAllBootModules(Configuration cf, String mn) {

        Set<String> bootModules = Layer.boot().modules().stream()
                .map(Module::getName)
                .collect(Collectors.toSet());

        bootModules.forEach(other -> assertTrue(reads(cf, mn, other)));

    }

    /**
     * Test that the given Module reads all module in the given Layer
     * and its parent Layers.
     */
    static void testsReadsAll(Module m, Layer layer) {
        while (layer != Layer.empty()) {

            // check that m reads all module in the layer
            layer.configuration().descriptors().stream()
                .map(ModuleDescriptor::name)
                .map(layer::findModule)
                .map(Optional::get)
                .forEach(m2 -> assertTrue(m.canRead(m2)));

            layer = layer.parent().get();
        }
    }

    /**
     * Returns {@code true} if the configuration contains module mn1
     * that reads module mn2.
     */
    static boolean reads(Configuration cf, String mn1, String mn2) {

        Optional<ModuleDescriptor> omd1 = cf.findDescriptor(mn1);
        if (!omd1.isPresent())
            return false;

        ModuleDescriptor md1 = omd1.get();
        return cf.reads(md1).stream()
                .map(Configuration.ReadDependence::descriptor)
                .map(ModuleDescriptor::name)
                .anyMatch(mn2::equals);
    }

    /**
     * Creates a JAR file, optionally with a manifest, and with the given
     * entries. The entries will be empty in the resulting JAR file.
     */
    static void createJarFile(Path file, Manifest man, String... entries)
        throws IOException
    {
        try (OutputStream out = Files.newOutputStream(file)) {
            try (JarOutputStream jos = new JarOutputStream(out)) {

                if (man != null) {
                    JarEntry je = new JarEntry(JarFile.MANIFEST_NAME);
                    jos.putNextEntry(je);
                    man.write(jos);
                    jos.closeEntry();
                }

                for (String entry : entries) {
                    JarEntry je = new JarEntry(entry);
                    jos.putNextEntry(je);
                    jos.closeEntry();
                }
            }
        }
    }

    /**
     * Creates a JAR file and with the given entries. The entries will be empty
     * in the resulting JAR file.
     */
    static void createJarFile(Path file, String... entries)
        throws IOException
    {
        createJarFile(file, null, entries);
    }

}
