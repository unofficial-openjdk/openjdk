/*
 * Copyright (c) 2009, 2015, Oracle and/or its affiliates. All rights reserved.
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

package java.lang.module;


final class ModuleName {

    private ModuleName() { }

    private static void fail(String nm, int i) {
        throw new IllegalArgumentException(nm
                                           + ": Invalid module name: "
                                           + " Illegal character"
                                           + " at index " + i);
    }

    // Module names must be legal Java identifiers
    //
    static String check(String nm) {
        if (nm == null)
            throw new IllegalArgumentException("Null module name");
        int n = nm.length();
        if (n == 0)
            throw new IllegalArgumentException("Empty module name");
        if (!Character.isJavaIdentifierStart(nm.codePointAt(0)))
            fail(nm, 0);
        int cp = nm.codePointAt(0);
        for (int i = Character.charCount(cp);
                i < n;
                i += Character.charCount(cp)) {
            cp = nm.codePointAt(i);
            if (!Character.isJavaIdentifierPart(cp) && nm.charAt(i) != '.')
                fail(nm, i);
        }
        return nm;
    }

}
