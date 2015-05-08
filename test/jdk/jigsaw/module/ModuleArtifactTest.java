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
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import java.lang.module.ModuleArtifact;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

/**
 * Basic tests for java.lang.module.ModuleArtifact
 */

@Test
public class ModuleArtifactTest {

    private ModuleArtifact newModuleArtifact(ModuleDescriptor descriptor,
                                             URI location)
    {
        return new ModuleArtifact(descriptor, location) {
            @Override
            public ModuleReader open() throws IOException {
                throw new IOException("No module reader for: " + location);
            }
        };
    }


    public void testBasic() throws Exception {
        ModuleDescriptor descriptor =
                new ModuleDescriptor.Builder("m")
                        .exports("p")
                        .exports("q")
                        .conceals("p.internal")
                        .build();

        URI location = URI.create("module:/m");

        ModuleArtifact artifact = newModuleArtifact(descriptor, location);

        assertTrue(artifact.descriptor().equals(descriptor));
        assertTrue(artifact.location().equals(location));
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNullDescriptor() throws Exception {
        URI location = URI.create("module:/m");
        newModuleArtifact(null, location);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNullLocation() throws Exception {
        ModuleDescriptor descriptor =
                new ModuleDescriptor.Builder("m")
                        .exports("p")
                        .build();
        newModuleArtifact(descriptor, null);
    }

}
