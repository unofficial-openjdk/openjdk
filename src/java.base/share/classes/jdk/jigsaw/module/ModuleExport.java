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

package jdk.jigsaw.module;

import java.io.Serializable;
import java.util.Objects;

/**
 * <p> A module export, may be qualified or unqualified. </p>
 */

@SuppressWarnings("serial")             // serialVersionUID intentionally omitted
public class ModuleExport
    implements Serializable
{
    private final String pkg;
    private final String permit;

    /**
     * Constructs a {@code ModuleExport} to represent the exporting of package
     * {@code pkg} to module {@code who}.
     */
    public ModuleExport(String pkg, String who) {
        this.pkg = Objects.requireNonNull(pkg);
        this.permit = who;
    }

    /**
     * Constructs a {@code ModuleExport} to represent the exporting of package
     * {@code pkg}.
     */
    public ModuleExport(String pkg) {
        this(pkg, null);
    }

    /**
     * Returns the package name.
     */
    public String pkg() {
        return pkg;
    }

    /**
     * Returns the name of the module that the package is exported to,
     * or {@code null} if this is an unqualified export.
     */
    public String permit() {
        return permit;
    }

    public int hashCode() {
        return Objects.hash(pkg, permit);
    }

    public boolean equals(Object obj) {
        if (!(obj instanceof ModuleExport))
            return false;
        ModuleExport other = (ModuleExport)obj;
        return Objects.equals(this.pkg, other.pkg) &&
               Objects.equals(this.permit, other.permit);
    }

    public String toString() {
        if (permit == null)
            return pkg;
        return pkg + " to " + permit;
    }
}
