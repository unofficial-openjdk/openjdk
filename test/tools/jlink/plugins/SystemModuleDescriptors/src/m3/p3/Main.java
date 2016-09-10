/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
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

package p3;

import p4.Foo;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Module;

import static java.lang.module.ModuleDescriptor.Exports.Modifier.*;

/**
 * Test if m4 is a weak module and p4 is private export that
 * m3 can access.
 */
public class Main {
    public static void main(String... args) throws Exception {
        Module m4 = Foo.class.getModule();
        if (!m4.isExportedPrivate("p4")) {
            throw new RuntimeException("m3 can't access p4");
        }

        // Test if it can access a private field
        Foo foo = Foo.create("foo");

        Field field = Foo.class.getDeclaredField("name");
        field.setAccessible(true);
        String name = (String) field.get(foo);
        if (!name.equals("foo")) {
            throw new RuntimeException("unexpected Foo::name value = " + name);
        }

        checkWeakModule();
    }

    // check the module descriptor of the weak module m4
    static void checkWeakModule() {
        ModuleDescriptor md = Foo.class.getModule().getDescriptor();
        System.out.println(md);

        if (!md.isWeak()) {
            throw new RuntimeException("m4 is a weak module");
        }

        if (md.packages().size() != 1 || !md.packages().contains("p4")) {
            throw new RuntimeException("unexpected m4 packages: " + md.packages());
        }

        if (md.exports().size() != 1) {
            throw new RuntimeException("unexpected m4 export: " + md.exports());
        }

        ModuleDescriptor.Exports exp = md.exports().stream()
            .filter(e -> e.source().equals("p4"))
            .findAny()
            .orElseThrow(() -> new RuntimeException("p4 is not exported by m4"));

        if (exp.isQualified() || !exp.modifiers().contains(PRIVATE)) {
            throw new RuntimeException("unexpected m4 exports: " + exp);
        }
    }

}
