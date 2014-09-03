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

import java.lang.reflect.Module;

import jdk.jigsaw.module.ModuleExport;

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
        assertTrue(ModuleTest.class.getModule() == null);
    }

    @Test
    public void testBase() {
        Module base = Object.class.getModule();

        // classLoader
        assertTrue(base.classLoader() == null);

        // descriptor
        ModuleExport javaLang = new ModuleExport("java.lang");
        assertTrue(base.descriptor().exports().contains(javaLang));

        // name
        assertTrue(base.name().equals("java.base"));

        // packages
        assertTrue(base.packages().contains("java.lang"));

        // reads - can't test that is empty because it may have been
        // augmented at runtime
        assertTrue(!base.reads().contains("java.base"));
    }

    @Test
    public void testDesktop() {
        Module desktop = java.awt.Component.class.getModule();

        // classLoader
        assertTrue(desktop.classLoader() == null);

        // descriptor
        ModuleExport javaAWT = new ModuleExport("java.awt");
        assertTrue(desktop.descriptor().exports().contains(javaAWT));

        // name
        assertTrue(desktop.name().equals("java.desktop"));

        // packages
        assertTrue(desktop.packages().contains("java.awt"));
        assertTrue(desktop.packages().contains("javax.swing"));

        // reads
        Module base = Object.class.getModule();
        Module xml = javax.xml.XMLConstants.class.getModule();
        assertTrue(desktop.reads().contains(base));
        assertTrue(desktop.reads().contains(xml));
    }
}
