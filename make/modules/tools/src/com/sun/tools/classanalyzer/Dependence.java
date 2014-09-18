/*
 * Copyright (c) 2011, 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.tools.classanalyzer;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class Dependence implements Comparable<Dependence> {
    enum Identifier { PUBLIC, SERVICE };

    private final String name;
    private final Set<Identifier> identifiers;

    public Dependence(String name, Set<Identifier> ids) {
        this.name = name;
        this.identifiers = new HashSet<>(ids);
        if (identifiers.contains(Identifier.SERVICE) &&
                identifiers.contains(Identifier.PUBLIC)) {
            throw new IllegalArgumentException("Can't have requires service public");
        }
    }

    public String name() {
        return name;
    }

    public void setRequiresPublic() {
        identifiers.add(Identifier.PUBLIC);
    }
    public Set<Identifier> mods() {
        return Collections.unmodifiableSet(identifiers);
    }

    public boolean requiresService() {
        return identifiers.contains(Identifier.SERVICE);
    }

    public boolean requiresPublic() {
        return identifiers.contains(Identifier.PUBLIC);
    }

    @Override
    public int compareTo(Dependence d) {
        if (this.equals(d)) {
            return 0;
        }
        return name.compareTo(d.name);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Dependence)) {
            return false;
        }
        if (this == obj) {
            return true;
        }

        Dependence d = (Dependence) obj;
        return this.name.equals(d.name) && identifiers.equals(d.identifiers);
    }

    @Override
    public int hashCode() {
        int hash = 3;
        hash = 19 * hash + this.name.hashCode();
        return hash;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (identifiers.contains(Identifier.SERVICE)) {
            sb.append("uses").append(" ").append(name).append(";");
        } else {
            sb.append("requires");
            for (Dependence.Identifier id : identifiers) {
                sb.append(" ").append(id.name().toLowerCase());
            }
            sb.append(" ").append(name).append(";");
        }
        return sb.toString();
    }
}
