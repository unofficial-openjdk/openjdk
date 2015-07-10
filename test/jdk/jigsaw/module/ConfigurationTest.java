/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.io.OutputStream;
import java.lang.module.Configuration;
import java.lang.module.Layer;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ResolutionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import static java.lang.module.Layer.*;
import static java.lang.module.ModuleFinder.*;
import static java.lang.module.ModuleFinder.empty;
import static java.util.jar.JarFile.MANIFEST_NAME;

import org.testng.annotations.Test;
import static org.testng.Assert.*;


/**
 * Basic tests for java.lang.module.Configuration
 */

@Test
public class ConfigurationTest {

    /**
     * Basic test of resolver
     */
    public void testBasic() {
        ModuleDescriptor descriptor1 =
                new ModuleDescriptor.Builder("m1")
                        .requires("m2")
                        .build();

        ModuleDescriptor descriptor2 =
                new ModuleDescriptor.Builder("m2")
                        .requires("m3")
                        .build();

        ModuleDescriptor descriptor3 =
                new ModuleDescriptor.Builder("m3")
                        .build();

        ModuleFinder finder =
                ModuleLibrary.of(descriptor1, descriptor2, descriptor3);

        Configuration cf = Configuration.resolve(finder, boot(), empty(), "m1");

        assertTrue(cf.descriptors().size() == 3);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2));
        assertTrue(cf.descriptors().contains(descriptor3));

        assertEquals(cf.references().stream()
                     .map(ModuleReference::descriptor)
                     .collect(Collectors.toSet()),
                     cf.descriptors());

        // m1 reads m2
        assertTrue(cf.reads(descriptor1).size() == 1);
        assertTrue(cf.reads(descriptor1).contains(descriptor2));

        // m2 reads m3
        assertTrue(cf.reads(descriptor2).size() == 1);
        assertTrue(cf.reads(descriptor2).contains(descriptor3));

        // m3 reads nothing
        assertTrue(cf.reads(descriptor3).size() == 0);

        // toString
        assertTrue(cf.toString().contains("m1"));
        assertTrue(cf.toString().contains("m2"));
        assertTrue(cf.toString().contains("m3"));
    }

    /**
     * Basic test of "requires public"
     */
    public void testRequiresPublic() {
        // m1 requires m2, m2 requires public m3
        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                    .requires("m2")
                    .build();

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                    .requires(Modifier.PUBLIC, "m3")
                    .build();

        ModuleDescriptor descriptor3
            = new ModuleDescriptor.Builder("m3")
                    .build();

        ModuleFinder finder
            = ModuleLibrary.of(descriptor1, descriptor2, descriptor3);

        Configuration cf
            = Configuration.resolve(finder, boot(), empty(), "m1");

        assertTrue(cf.descriptors().size() == 3);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2));
        assertTrue(cf.descriptors().contains(descriptor3));

        // m1 reads m2 and m3
        assertTrue(cf.reads(descriptor1).size() == 2);
        assertTrue(cf.reads(descriptor1).contains(descriptor2));
        assertTrue(cf.reads(descriptor1).contains(descriptor3));

        // m2 reads m3
        assertTrue(cf.reads(descriptor2).size() == 1);
        assertTrue(cf.reads(descriptor2).contains(descriptor3));

        // m3 reads nothing
        assertTrue(cf.reads(descriptor3).size() == 0);
    }

    /**
     * Basic test of binding services
     */
    public void testBasicBinding() {

        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .requires("m2")
                .uses("S")
                .build();

        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m2")
                .provides("S", "p.S2")
                .build();

        // service provider
        ModuleDescriptor descriptor3
            = new ModuleDescriptor.Builder("m3")
                .requires("m1")
                .provides("S", "q.S3").build();

        // unused module
        ModuleDescriptor descriptor4
            = new ModuleDescriptor.Builder("m4").build();

        ModuleFinder finder
            = ModuleLibrary.of(descriptor1, descriptor2, descriptor3, descriptor4);

        Configuration cf
            = Configuration.resolve(finder, boot(), empty(), "m1");

        // only m1 and m2 in the configuration
        assertTrue(cf.descriptors().size() == 2);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2));

        assertTrue(cf.reads(descriptor1).size() == 1);
        assertTrue(cf.reads(descriptor1).contains(descriptor2));

        assertTrue(cf.reads(descriptor2).size() == 0);

        assertTrue(cf.provides("S").isEmpty());

        // bind services, should augment graph with m3
        cf = cf.bind();

        assertTrue(cf.descriptors().size() == 3);
        assertTrue(cf.descriptors().contains(descriptor1));
        assertTrue(cf.descriptors().contains(descriptor2));
        assertTrue(cf.descriptors().contains(descriptor3));

        assertTrue(cf.reads(descriptor1).size() == 1);
        assertTrue(cf.reads(descriptor1).contains(descriptor2));

        assertTrue(cf.reads(descriptor2).size() == 0);

        assertTrue(cf.reads(descriptor3).size() == 1);
        assertTrue(cf.reads(descriptor3).contains(descriptor1));

        assertTrue(cf.provides("S").size() == 2);
        assertTrue(cf.provides("S").contains(descriptor2));
        assertTrue(cf.provides("S").contains(descriptor3));

        assertTrue(cf.toString().contains("m1"));
        assertTrue(cf.toString().contains("m2"));
        assertTrue(cf.toString().contains("m3"));
    }

    /**
     * Basic test of a configuration created with automatic modules.
     */
    public void testAutomaticModules() throws IOException {
        ModuleDescriptor m1
            =  new ModuleDescriptor.Builder("m1")
                .requires("m2")
                .requires("m3")
                .requires("java.base")
                .build();

        // m2 and m3 are simple JAR files
        Path dir = Files.createTempDirectory("configtest");
        createDummyJarFile(dir.resolve("m2.jar"), "p/T.class");
        createDummyJarFile(dir.resolve("m3.jar"), "q/T.class");

        // module finder locates m1 and the modules in the directory
        ModuleFinder finder
            = ModuleFinder.concat(ModuleLibrary.of(m1), ModuleFinder.of(dir));

        Configuration cf
            = Configuration.resolve(finder, boot(), empty(), "m1");

        assertTrue(cf.descriptors().size() == 3);
        assertTrue(cf.findDescriptor("m1").isPresent());
        assertTrue(cf.findDescriptor("m2").isPresent());
        assertTrue(cf.findDescriptor("m3").isPresent());

        ModuleDescriptor m2 = cf.findDescriptor("m2").get();
        ModuleDescriptor m3 = cf.findDescriptor("m3").get();

        // m2 && m3 only require java.base
        assertTrue(m2.requires().size() == 1);
        assertTrue(m3.requires().size() == 1);

        // the descriptors for the modules in the boot Layer
        Set<ModuleDescriptor> bootModules
            = Layer.boot().configuration().get().descriptors();

        // As m2 and m3 are automatic modules then they read all modules
        // As m1 requires m2 & m3 then it needs to read all modules that
        // m2 and m3 read.

        //assertFalse(cf.reads(m1).contains(m1));
        assertTrue(cf.reads(m1).contains(m2));
        assertTrue(cf.reads(m1).contains(m3));
        assertTrue(cf.reads(m1).containsAll(bootModules));

        //assertFalse(cf.reads(m2).contains(m2));
        assertTrue(cf.reads(m2).contains(m1));
        assertTrue(cf.reads(m2).contains(m3));
        assertTrue(cf.reads(m2).containsAll(bootModules));

        //assertFalse(cf.reads(m3).contains(m3));
        assertTrue(cf.reads(m3).contains(m1));
        assertTrue(cf.reads(m3).contains(m2));
        assertTrue(cf.reads(m3).containsAll(bootModules));
    }

    /**
     * Basic test of an automatic module with a Main-Class attribute
     * in the JAR manifest.
     */
    public void testAutomaticMainModule() throws IOException {

        String mainClass = "p.Main";

        Manifest man = new Manifest();
        Attributes attrs = man.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0.0");
        attrs.put(Attributes.Name.MAIN_CLASS, mainClass);

        Path dir = Files.createTempDirectory("configtest");
        createDummyJarFile(dir.resolve("m1.jar"), man, "p/Main.class");

        ModuleFinder finder = ModuleFinder.of(dir);

        Configuration cf
            = Configuration.resolve(finder, boot(), empty(), "m1");

        ModuleDescriptor m1 = cf.findDescriptor("m1").get();

        assertTrue(m1.mainClass().isPresent());
        assertEquals(m1.mainClass().get(), mainClass);
    }

    /**
     * Root module not found
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testRootNotFound() {
        Configuration.resolve(empty(), boot(), empty(), "m1");
    }

    /**
     * Direct dependency not found
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testDirectDependencyNotFound() {
        ModuleDescriptor descriptor1 =
                new ModuleDescriptor.Builder("m1").requires("m2").build();
        ModuleFinder finder = ModuleLibrary.of(descriptor1);

        Configuration.resolve(finder, boot(), empty(), "m1");
    }

    /**
     * Transitive dependency not found
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testTransitiveDependencyNotFound() {
        ModuleDescriptor descriptor1 =
                new ModuleDescriptor.Builder("m1").requires("m2").build();
        ModuleDescriptor descriptor2 =
                new ModuleDescriptor.Builder("m2").requires("m3").build();
        ModuleFinder finder = ModuleLibrary.of(descriptor1, descriptor2);

        Configuration.resolve(finder, boot(), empty(), "m1");
    }

    /**
     * Service provider dependency not found
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testServiceProviderDependencyNotFound() {

        // service provider dependency (on m3) not found
        ModuleDescriptor descriptor1  = new ModuleDescriptor.Builder("m1").build();
        ModuleDescriptor descriptor2 =
                new ModuleDescriptor.Builder("m2")
                    .requires("m3")
                    .provides("java.security.Provider", "p.CryptoProvder")
                    .build();
        ModuleFinder finder = ModuleLibrary.of(descriptor1, descriptor2);

        Configuration cf;
        try {
            cf = Configuration.resolve(finder, boot(), empty(), "m1");
            assertTrue(cf.descriptors().size() == 1);
            assertTrue(cf.provides("java.security.Provider").isEmpty());
        } catch (ResolutionException e) {
            throw new RuntimeException(e);
        }

        // should throw ResolutionException because m3 is not found
        cf.bind();
    }

    /**
     * Simple cycle.
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testSimpleCycle() {
        ModuleDescriptor descriptor1 =
                new ModuleDescriptor.Builder("m1").requires("m2").build();
        ModuleDescriptor descriptor2 =
                new ModuleDescriptor.Builder("m2").requires("m3").build();
        ModuleDescriptor descriptor3 =
                new ModuleDescriptor.Builder("m3").requires("m1").build();
        ModuleFinder finder =
                ModuleLibrary.of(descriptor1, descriptor2, descriptor3);

        Configuration.resolve(finder, boot(), empty(), "m1");
    }

    /**
     * Basic test for detecting cycles involving a service provider module
     */
    @Test(expectedExceptions = { ResolutionException.class })
    public void testCycleInProvider() {
        ModuleDescriptor descriptor1 =
                new ModuleDescriptor.Builder("m1").uses("p.Service").build();
        ModuleDescriptor descriptor2 =
                new ModuleDescriptor.Builder("m2")
                        .requires("m3")
                        .provides("p.Service", "q.ServiceImpl").build();
        ModuleDescriptor descriptor3 =
                new ModuleDescriptor.Builder("m3").requires("m2").build();
        ModuleFinder finder =
                ModuleLibrary.of(descriptor1, descriptor2, descriptor3);

        Configuration cf;
        try {
            cf = Configuration.resolve(finder, boot(), empty(), "m1");
            assertTrue(cf.findDescriptor("m1").get() == descriptor1);
            assertTrue(cf.descriptors().size() == 1);
        } catch (ResolutionException e) {
            throw new RuntimeException(e);
        }

        // should throw ResolutionException because of the m2 <--> m3 cycle
        cf.bind();
    }

    /**
     * Creates a JAR file, optionally with a manifest, and with the given
     * entries. The entries will be empty in the resulting JAR file.
     */
    private static void createDummyJarFile(Path file, Manifest man, String... entries)
        throws IOException
    {
        try (OutputStream out = Files.newOutputStream(file)) {
            try (JarOutputStream jos = new JarOutputStream(out)) {
                if (man != null) {
                    JarEntry je = new JarEntry(MANIFEST_NAME);
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

    private static void createDummyJarFile(Path file, String... entries)
        throws IOException
    {
        createDummyJarFile(file, null, entries);
    }

}
