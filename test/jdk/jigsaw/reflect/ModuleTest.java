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

import java.lang.module.Layer;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.reflect.Module;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

/*
 * @test
 * @summary Basic test of java.lang.reflect.Module
 * @run testng ModuleTest
 */

public class ModuleTest {

    @Test
    public void testMe() {
        assertFalse(ModuleTest.class.getModule().isNamed());
    }

    @Test
    public void testLoader() {
        Module thisModule = ModuleTest.class.getModule();
        ClassLoader thisLoader = ModuleTest.class.getClassLoader();
        assertTrue(thisLoader == thisModule.getClassLoader());
        assertTrue(thisLoader.getUnnamedModule() == thisModule);
    }

    /**
     * Tests that the given module reads all modules in the boot Layer.
     */
    private void testReadsAllBootModules(Module m) {
        Layer bootLayer = Layer.bootLayer();
        bootLayer.configuration()
                .descriptors()
                .stream()
                .map(ModuleDescriptor::name)
                .map(bootLayer::findModule)
                .forEach(target -> assertTrue(m.canRead(target)));
    }

    @Test
    public void testUnnamedModule() {
        ClassLoader loader1 = ClassLoader.getSystemClassLoader();
        ClassLoader loader2 = loader1.getParent();

        Module m1 = loader1.getUnnamedModule();
        Module m2 = loader2.getUnnamedModule();

        assertTrue(m1 != m2);

        assertFalse(m1.isNamed());
        assertFalse(m2.isNamed());

        // unnamed module reads all modules
        assertTrue(m1.canRead(m2));
        assertTrue(m2.canRead(m1));

        testReadsAllBootModules(m1);
        testReadsAllBootModules(m2);
    }

    private Predicate<Exports> doesExport(String pn) {
        return e -> (e.source().equals(pn)
                     && !e.targets().isPresent());
    }

    @Test
    public void testBaseModule() {
        Module base = Object.class.getModule();

        // getClassLoader
        assertTrue(base.getClassLoader() == null);

        // descriptor
        assertTrue(base.getDescriptor().exports().stream()
                   .anyMatch(doesExport("java.lang")));

        // name
        assertTrue(base.getName().equals("java.base"));

        // packages
        assertTrue(contains(base.getPackages(), "java.lang"));

        // canRead
        Module me = ModuleTest.class.getModule();
        assertTrue(base.canRead(base));
    }

    @Test
    public void testDesktopModule() {
        Module desktop = java.awt.Component.class.getModule();

        // getClassLoader
        assertTrue(desktop.getClassLoader() == null);

        // descriptor
        assertTrue(desktop.getDescriptor().exports().stream()
                   .anyMatch(doesExport("java.awt")));

        // name
        assertTrue(desktop.getName().equals("java.desktop"));

        // packages
        assertTrue(contains(desktop.getPackages(), "java.awt"));
        assertTrue(contains(desktop.getPackages(), "javax.swing"));

        // reads
        Module base = Object.class.getModule();
        Module xml = javax.xml.XMLConstants.class.getModule();
        assertTrue(desktop.canRead(base));
        assertTrue(desktop.canRead(xml));
    }

    private <T> boolean contains(T[] array, T obj) {
        return Stream.of(array).anyMatch(obj::equals);
    }

}
