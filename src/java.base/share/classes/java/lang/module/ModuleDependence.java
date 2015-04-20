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

package java.lang.module;

import java.io.Serializable;
import java.util.Collections;
import java.util.Set;


/**
 * <p> A dependence upon a module </p>
 *
 * @since 1.9
 */

public final class ModuleDependence
    implements Comparable<ModuleDependence>
{

    /**
     * A modifier on a module dependence.
     *
     * @since 1.9
     */
    public static enum Modifier {
        /**
         * The dependence causes any module which depends on the <i>current
         * module</i> to have an implicitly declared dependence on the module
         * named by the {@code ModuleDependence}.
         */
        PUBLIC,
        /**
         * The dependence was not explicitly or implicitly declared in the
         * source of the module declaration.
         */
        SYNTHETIC,
        /**
         * The dependence was implicitly declared in the source of the module
         * declaration.
         */
        MANDATED;
    }

    private final Set<Modifier> mods;
    private final String name;

    /**
     * Constructs a new instance of this class.
     *
     * @param ms the set of modifiers; {@code null} for no modifiers
     * @param mn the module name
     *
     * @throws IllegalArgumentException
     *         If the module name is not a legal Java identifier
     */
    public ModuleDependence(Set<Modifier> ms, String mn) {
        if (ms == null) {
            mods = Collections.emptySet();
        } else {
            mods = Collections.unmodifiableSet(ms);
        }
        this.name = ModuleName.check(mn);
    }

    /**
     * Returns the possibly empty set of modifiers. The set is immutable.
     */
    public Set<Modifier> modifiers() {
        return mods;
    }

    /**
     * Return the module name.
     */
    public String name() {
        return name;
    }

    /**
     * Compares this module dependence to another.
     *
     * <p> Two {@code ModuleDependence} objects are compared by comparing their
     * module name lexicographically.  Where the module names are equal then
     * the sets of modifiers are compared.
     *
     * @return A negative integer, zero, or a positive integer if this module
     *         dependence is less than, equal to, or greater than the given
     *         module dependence
     */
    @Override
    public int compareTo(ModuleDependence that) {
        int c = this.name().compareTo(that.name());
        if (c != 0)
            return c;
        // same name, compare by modifiers
        return Long.compare(this.modsValue(), that.modsValue());
    }

    /**
     * Return a value for the modifiers to allow sets of modifiers to be
     * compared.
     */
    private long modsValue() {
        return mods.stream()
                   .map(Modifier::ordinal)
                   .map(n -> 1 << n)
                   .reduce(0, (a, b) -> a + b);
    }


    @Override
    public boolean equals(Object ob) {
        if (!(ob instanceof ModuleDependence))
            return false;
        ModuleDependence that = (ModuleDependence)ob;
        return (name.equals(that.name) && mods.equals(that.mods));
    }

    @Override
    public int hashCode() {
        return name.hashCode() * 43 + mods.hashCode();
    }

    @Override
    public String toString() {
        return Dependence.toString(mods, name);
    }

}
