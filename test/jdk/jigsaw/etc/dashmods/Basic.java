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

/* @test
 * @summary Basic test to exercise -mods (need to use internal properties
 *    as jtreg doesn't allow -mods due to the option using spaces)
 * @run main/othervm -Djdk.launcher.modules=java.base Basic expect-fail
 * @run main/othervm -Djdk.launcher.modules=java.desktop Basic expect-pass
 */

public class Basic {
    public static void main(String[] args) {
        boolean expectFail = args[0].equals("expect-fail");
        boolean expectPass = args[0].equals("expect-pass");
        if (expectFail == expectPass)
            throw new RuntimeException("Need to run with expect-* argument");

        try {
            Class<?> c = java.awt.Component.class;
            if (expectFail) throw new RuntimeException("No Error thrown");
        } catch (Error e) {
            // exact Error is TBD, will likely be NoClassDefFoundError as
            // class should not be observable
            if (expectPass) throw new RuntimeException(e);
        }
    }
}
