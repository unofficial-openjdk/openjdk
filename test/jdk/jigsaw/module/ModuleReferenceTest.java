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

/**
 * @test
 * @run testng ModuleReferenceTest
 * @summary Basic tests for java.lang.module.ModuleReference
 */

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class ModuleReferenceTest {

    private ModuleReference newModuleReference(ModuleDescriptor descriptor,
                                               URI location)
    {
        return new ModuleReference(descriptor, location) {
            @Override
            public ModuleReader open() throws IOException {
                throw new IOException("No reader for module "
                                      + descriptor().toNameAndVersion());
            }
        };
    }


    public void testBasic() throws Exception {
        ModuleDescriptor descriptor
            = new ModuleDescriptor.Builder("m")
                .exports("p")
                .exports("q")
                .conceals("p.internal")
                .build();

        URI location = URI.create("module:/m");

        ModuleReference mref = newModuleReference(descriptor, location);

        assertTrue(mref.descriptor().equals(descriptor));
        assertTrue(mref.location().get().equals(location));
    }


    @Test(expectedExceptions = { NullPointerException.class })
    public void testNullDescriptor() throws Exception {
        URI location = URI.create("module:/m");
        newModuleReference(null, location);
    }


    public void testNullLocation() {
        ModuleDescriptor descriptor
            = new ModuleDescriptor.Builder("m")
                    .exports("p")
                    .build();
        ModuleReference mref = newModuleReference(descriptor, null);
        assertTrue(!mref.location().isPresent());
    }


    public void testEqualsAndHashCode() {
        ModuleDescriptor descriptor1
            = new ModuleDescriptor.Builder("m1")
                .exports("p")
                .build();
        ModuleDescriptor descriptor2
            = new ModuleDescriptor.Builder("m1")
                .exports("p")
                .build();

        URI location = URI.create("module:/m1");
        ModuleReference mref1 = newModuleReference(descriptor1, location);
        ModuleReference mref2 = newModuleReference(descriptor2, location);
        ModuleReference mref3 = newModuleReference(descriptor1, null);

        assertTrue(mref1.equals(mref1));
        assertTrue(mref1.equals(mref1));
        assertTrue(mref2.equals(mref1));
        assertTrue(mref1.hashCode() == mref2.hashCode());

        assertTrue(mref3.equals(mref3));
        assertFalse(mref3.equals(mref1));
        assertFalse(mref1.equals(mref3));
    }


    public void testToString() {
        ModuleDescriptor descriptor = new ModuleDescriptor.Builder("m1").build();
        URI location = URI.create("module:/m1");
        ModuleReference mref = newModuleReference(descriptor, location);
        String s = mref.toString();
        assertTrue(s.contains("m1"));
        assertTrue(s.contains(location.toString()));
    }

}
