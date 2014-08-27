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

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jdk.jigsaw.module.ExtendedModuleDescriptor;
import jdk.jigsaw.module.ModuleArtifact;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

/**
 * Basic tests for jdk.jigsaw.module.ModuleArtifact
 */

@Test
public class ModuleArtifactTest {

    public void testBasic() throws Exception {
        ExtendedModuleDescriptor descriptor =
                new ExtendedModuleDescriptor.Builder("m")
                        .export("p")
                        .export("q")
                        .build();

        Set<String> packages =
            Stream.of("p", "q", "p.internal").collect(Collectors.toSet());

        URL url = URI.create("module://m").toURL();

        ModuleArtifact artifact = new ModuleArtifact(descriptor, packages, url);

        assertTrue(artifact.descriptor().equals(descriptor));
        assertTrue(artifact.packages().equals(packages));
        assertTrue(artifact.location().equals(url));
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNullDescriptor() throws Exception {
        Set<String> packages = Stream.of("p").collect(Collectors.toSet());
        URL url = URI.create("module://m").toURL();
        new ModuleArtifact(null, packages, url);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNullPackages() throws Exception {
        ExtendedModuleDescriptor descriptor =
                new ExtendedModuleDescriptor.Builder("m")
                        .export("p")
                        .export("q")
                        .build();
        URL url = URI.create("module://m").toURL();
        new ModuleArtifact(descriptor, null, url);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNullURL() throws Exception {
        ExtendedModuleDescriptor descriptor =
                new ExtendedModuleDescriptor.Builder("m")
                        .export("p")
                        .build();
        Set<String> packages = Stream.of("p").collect(Collectors.toSet());
        new ModuleArtifact(descriptor, packages, null);
    }

    @DataProvider(name = "badpackages")
    public Object[][] badPackages() {
        List<Set<String>> badContents = new ArrayList<>();

        badContents.add( Stream.of("p", null).collect(Collectors.toSet()) );
        badContents.add( Stream.of("p", "").collect(Collectors.toSet()) );
        badContents.add( Stream.of("q").collect(Collectors.toSet()) );

        Object[][] params = new Object[badContents.size()][];
        for (int i = 0; i < badContents.size(); i++) {
            Set<String> packages = badContents.get(i);
            params[i] = new Object[] { packages };
        }
        return params;
    }

    @Test(dataProvider = "badpackages",
          expectedExceptions = { IllegalArgumentException.class })
    public void testBadContents(Set<String> packages) throws Exception {
        ExtendedModuleDescriptor descriptor =
                new ExtendedModuleDescriptor.Builder("m")
                        .export("p")
                        .build();
        URL url = URI.create("module://m").toURL();

        // should throw IAE
        new ModuleArtifact(descriptor, packages, url);
    }
}