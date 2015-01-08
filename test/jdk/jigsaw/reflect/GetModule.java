/**
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

/*
 * @test
 * @summary Exercise Class#getModule
 * @run testng GetModule
 */

import java.awt.Component;
import java.lang.reflect.Module;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class GetModule {

    @DataProvider(name = "samples")
    public Object[][] sampleData() {
        return new Object[][] {
            { int.class,          null },
            { int[].class,        null },
            { GetModule.class,    null },
            { GetModule[].class,  null },
            { Object.class,       "java.base" },
            { Object[].class,     "java.base" },
            { Component.class,    "java.desktop" },
            { Component[].class,  "java.desktop" },

            // TBD - need to add test classes for exported classes that are
            // on the extensions or application class path.
        };
    }

    @Test(dataProvider = "samples")
    public void testGetModule(Class<?> type, String expected) {
        Module m = type.getModule();
        String name = (m != null) ? m.name() : null;
        assertEquals(name, expected);
    }
}
