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


@SuppressWarnings("serial")             // serialVersionUID intentionally omitted
public final class ModuleIdQuery
    implements Serializable
{

    private String name;
    private VersionQuery versionQuery;

    public ModuleIdQuery(String name, VersionQuery versionQuery) {
        this.name = ModuleId.checkModuleName(name);
        this.versionQuery = versionQuery;
    }

    public static ModuleIdQuery parse(String midq) {
        int i = midq.indexOf('@');
        String mn;
        VersionQuery vq = null;
        if (i < 0) {
            mn = midq;
        } else {
            mn = midq.substring(0, i);
            vq = VersionQuery.parse(midq.substring(i + 1));
        }
        return new ModuleIdQuery(mn, vq);
    }

    public String name() { return name; }

    public VersionQuery versionQuery() { return versionQuery; }

    public boolean matches(ModuleId mid) {
        if (!name.equals(mid.name()))
            return false;
        if (versionQuery == null)
            return true;
        if (mid.version() == null)
            return false;
        return versionQuery.matches(mid.version());
    }

    @Override
    public boolean equals(Object ob) {
        if (!(ob instanceof ModuleIdQuery))
            return false;
        ModuleIdQuery that = (ModuleIdQuery)ob;
        if (!this.name.equals(that.name))
            return false;
        if (versionQuery == that.versionQuery)
            return true;
        if (versionQuery == null || that.versionQuery == null)
            return false;
        return this.versionQuery.equals(that.versionQuery);
    }

    @Override
    public int hashCode() {
        return (name.hashCode() * 43
                + ((versionQuery == null) ? 7919 : versionQuery.hashCode()));
    }

    @Override
    public String toString() {
        return (versionQuery == null ? name : name + "@" + versionQuery);
    }

}
