/*
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

package jdk.test;

import java.net.URL;
import java.awt.Component;
import java.lang.reflect.Module;

public class UseAWT {
    public static void main(String[] args) {
        boolean expectFail = args[0].equals("expect-fail");
        boolean expectPass = args[0].equals("expect-pass");
        if (expectFail == expectPass)
            throw new RuntimeException("Need to run with expect-* argument");

        // test class loading
        try {
            Class<?> c = Component.class;
            if (expectFail) throw new RuntimeException("No Error thrown");
        } catch (NoClassDefFoundError e) {
            if (expectPass) throw new RuntimeException(e);
        }

        // resources
        URL url = ClassLoader.getSystemResource("java/awt/Component.class");
        if (url != null && expectFail)
            throw new RuntimeException("Resource was found");
        if (url == null && expectPass)
            throw new RuntimeException("Resource was not found");
    }
}
