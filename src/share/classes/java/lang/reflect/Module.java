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

package java.lang.reflect;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides information and access to a Java Module.
 *
 * @since 1.9
 * @see java.lang.Class#getModule
 */

public final class Module {

    // immutable
    private final String name;
    private final Set<String> packages;

    // mutable to allow "construction" of a graph of modules without needing
    // them to be created in topological order.
    //
    private final Map<Module, Object> requires;  // used as a set
    private final Map<String, Set<Module>> exports;

    Module(String name, Set<String> packages) {
        this.name = name;
        this.packages = Collections.unmodifiableSet(packages);
        this.requires = new ConcurrentHashMap<>();
        this.exports = new ConcurrentHashMap<>();
    }

    /**
     * Returns the module name.
     */
    public String name() {
        return name;
    }

    /**
     * Returns the set of API packages in this module.
     */
    public Set<String> packages() {
        return packages;
    }

    /**
     * Returns the set of modules that this module requires. The set includes
     * the set of modules that are transitively required through
     * "requires public".
     */
    public Set<Module> requires() {
        return Collections.unmodifiableSet(requires.keySet());
    }

    /**
     * Returns a map of the APIs exported by this module. The map key is the
     * package name, the value the set of modules that the API package is
     * exported to (and will be empty if exported without restrictions).
     */
    public Map<String, Set<Module>> exports() {
        return Collections.unmodifiableMap(exports);
    }

    /**
     * Return the string representation of the module.
     */
    public String toString() {
        return "module " + name();
    }

    static {
        sun.misc.SharedSecrets.setJavaLangReflectAccess(
            new sun.misc.JavaLangReflectAccess() {
                @Override
                public Module defineModule(String name, Set<String> packages) {
                    return new Module(name, packages);
                }
                @Override
                public void addRequires(Module m1, Module m2) {
                    m1.requires.put(m2, Boolean.TRUE);
                }
                @Override
                public void addExport(Module m, String pkg, Set<Module> who) {
                    if (m.exports.put(pkg, Collections.unmodifiableSet(who)) != null)
                        throw new InternalError(pkg + " already exported by " + m);
                }
            });
    }
}
