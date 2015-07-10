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
 * @summary Exercise ModuleDescriptor.Builder with invalid identifiers
 */

import java.lang.module.ModuleDescriptor.Builder;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class BadIdentifiers {

    @DataProvider(name = "invalidjavaidentifiers")
    public Object[][] invalidJavaIdentifiers() {
        return new Object[][]{

            { null,         null },
            { ".foo",       null },
            { "foo.",       null },
            { "[foo]",      null },

        };
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testCreateBuilder(String mn, String ignore) {
        new Builder(mn);
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testRequires(String mn, String ignore) {
        Builder builder = new Builder("mymod");
        builder.requires(mn);
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testUses(String service, String ignore) {
        Builder builder = new Builder("mymod");
        builder.uses(service);
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testExports(String pn, String ignore) {
        Builder builder = new Builder("mymod");
        builder.exports(pn);
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testProvides1(String service, String ignore) {
        Builder builder = new Builder("mymod");
        builder.provides(service, "impl.MyProvider");
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testProvides2(String provider, String ignore) {
        Builder builder = new Builder("mymod");
        builder.provides("p.Service", provider);
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testConceals(String pn, String ignore) {
        Builder builder = new Builder("mymod");
        builder.conceals(pn);
    }

    @Test(dataProvider = "invalidjavaidentifiers",
          expectedExceptions = IllegalArgumentException.class )
    public void testMainClass(String mainClass, String ignore) {
        Builder builder = new Builder("mymod");
        builder.mainClass(mainClass);
    }

}
