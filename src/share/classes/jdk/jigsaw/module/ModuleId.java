/*
 * Copyright (c) 2009, 2013, Oracle and/or its affiliates. All rights reserved.
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

package jdk.jigsaw.module;

import java.io.*;


/**
 * <p> A module's identification, which consists of a name and, optionally, a
 * version. </p>
 */

@SuppressWarnings("serial")             // serialVersionUID intentionally omitted
public final class ModuleId
    implements Comparable<ModuleId>, Serializable
{

    private final String name;
    private final Version version;
    private final int hash;

    // Module names must be legal Java identifiers
    //
    static final String checkModuleName(String nm) {
        if (nm == null)
            throw new IllegalArgumentException();
        int n = nm.length();
        if (n == 0 || !Character.isJavaIdentifierStart(nm.codePointAt(0)))
            throw new IllegalArgumentException();
        int cp = nm.codePointAt(0);
        for (int i = Character.charCount(cp);
                i < n;
                i += Character.charCount(cp)) {
            cp = nm.codePointAt(i);
            if (!Character.isJavaIdentifierPart(cp) && nm.charAt(i) != '.') {
                throw new IllegalArgumentException(nm
                                                   + ": Illegal module-name"
                                                   + " character"
                                                   + " at index " + i);
            }
        }
        return nm;
    }

    public ModuleId(String name, Version version) {
        this.name = checkModuleName(name);
        this.version = version;
        hash = (43 * name.hashCode()
                + ((version != null) ? version.hashCode() : 0));
    }

    public static ModuleId parse(String nm, String v) {
        return new ModuleId(nm, Version.parse(v));
    }

    public static ModuleId parse(String s) {
        if (s == null)
            throw new IllegalArgumentException();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '@') break;
            i++;
        }
        if (i >= n)
            return new ModuleId(s, null);
        if (i == 0)
            throw new IllegalArgumentException();
        String nm = (i < n) ? s.substring(0, i) : s;
        while (i < n && s.charAt(i) == ' ')
            i++;
        if (i >= n || s.charAt(i) != '@')
            throw new IllegalArgumentException();
        i++;
        if (i >= n)
            throw new IllegalArgumentException();
        while (i < n && s.charAt(i) == ' ')
            i++;
        if (i >= n)
            throw new IllegalArgumentException();
        return parse(nm, s.substring(i));
    }

    public String name() { return name; }

    public Version version() { return version; }

    public ModuleIdQuery toQuery() {
        return new ModuleIdQuery(name, version.toQuery());
    }

    @Override
    public int compareTo(ModuleId that) {
        int c = name.compareTo(that.name);
        if (c != 0)
            return c;
        if (version == null) {
            if (that.version == null)
                return 0;
            return -1;
        }
        if (that.version == null)
            return +1;
        return version.compareTo(that.version);
    }

    @Override
    public boolean equals(Object ob) {
        if (!(ob instanceof ModuleId))
            return false;
        ModuleId that = (ModuleId)ob;
        if (!name.equals(that.name))
            return false;
        if (version == that.version)
            return true;
        if (version == null || that.version == null)
            return false;
        return version.equals(that.version());
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return (version == null ? name : name + "@" + version);
    }

}
