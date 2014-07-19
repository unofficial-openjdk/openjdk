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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jdk.jigsaw.module.ServiceDependence;
import sun.reflect.CallerSensitive;
import sun.reflect.Reflection;

import jdk.jigsaw.module.ModuleGraph;

/**
 * Represents a runtime module.
 *
 * <p> {@code Module} does not define a public constructor. Instead {@code
 * Module} objects are constructed automatically by the Java Virtual Machine as
 * modules are defined by the {@link ClassLoader#defineModules defineModules} and
 * {@link ClassLoader#defineModule defineModule}. </p>
 *
 * @apiNote For now, this class defines {@link #getModuleGraph()} and {@link
 * #getModule()} to provide access to the original model. An alternative that
 * might be more consistent with other {@code java.lang.reflect} classes is to
 * have this class provide access to the original module definition.
 *
 * @since 1.9
 * @see java.lang.Class#getModule
 */
public final class Module {

    // the module name
    private final String name;

    // the original module graph and module, might be set lazily
    private volatile ModuleGraph graph;
    private volatile jdk.jigsaw.module.Module module;

    // the modules that this module reads
    private final Map<Module, Object> reads = new ConcurrentHashMap<>();

    // this module's exported, cached here for access checks
    private final Map<String, Set<Module>> exports = new ConcurrentHashMap<>();

    // used by VM to indicate that the module is fully defined
    private volatile boolean defined;


    // called by VM during startup
    Module(String name) {
        this.name = name;
    }

    Module(ModuleGraph g, jdk.jigsaw.module.Module m) {
        this.name = m.id().name();
        this.graph = g;
        this.module = m;
    }

    /**
     * Returns the {@code ModuleGraph} from which this runtime module was defined.
     */
    public ModuleGraph getModuleGraph() {
        ModuleGraph g = this.graph;
        if (g != null)
            return g;
        return ModuleGraph.getSystemModuleGraph();
    }

    /**
     * Returns the {@code Module} from which this runtime module was defined.
     */
    public jdk.jigsaw.module.Module getModule() {
        jdk.jigsaw.module.Module m = this.module;
        if (m != null)
            return m;

        ModuleGraph systemModuleGraph = ModuleGraph.getSystemModuleGraph();
        if (systemModuleGraph == null)
            return null;

        jdk.jigsaw.module.Module me = systemModuleGraph.findModule(name);
        if (me == null)
            throw new InternalError(name + " not in system module graph");

        this.module = me;
        return me;
    }

    /**
     * Makes this module readable to the module of the caller. This method
     * does nothing if the caller is this module, the caller already reads
     * this module, or the caller is in the <em>unnamed module</em> and this
     * module does not have a permits.
     *
     * @throws IllegalArgumentException if this module has a permits
     *
     * @implNote For now, the new read edge is only effective for access
     * checks done in Core Reflection. This anomaly will go away once the
     * implementation is further along and should make {@code setReadable}
     * more useful for dealing with optional dependencies, particularly the
     * case of a static dependency with a reflection guard.
     *
     * @see #canRead
     */
    @CallerSensitive
    public void setReadable() {
        Module caller = Reflection.getCallerClass().getModule();
        if (caller == this || (caller != null && caller.reads.containsKey(this)))
            return;

        Set<String> permits = getModule().permits();
        if (!permits.isEmpty())
            throw new IllegalArgumentException("module has a 'permits'");

        if (caller != null)
            caller.reads.putIfAbsent(this, Boolean.TRUE);
    }

    /**
     * Indicates if this {@code Module} reads the given {@code Module}.
     *
     * <p> Returns {@code true} if {@code m} is {@code null} (the unnamed
     * readable is readable to all modules, or {@code m} is this module (a
     * module can read itself). </p>
     *
     * @see #setReadable()
     */
    public boolean canRead(Module m) {
        return m == null || m == this || reads.containsKey(m);
    }

    /**
     * Returns the set of type names that are service interfaces that this
     * module uses.
     */
    Set<String> uses() {
        // already cached
        Set<String> uses = this.uses;
        if (uses != null)
            return uses;

        jdk.jigsaw.module.Module m = getModule();
        if (m == null) {
            return Collections.emptySet();
        } else {
            uses = m.serviceDependences()
                    .stream()
                    .map(ServiceDependence::service)
                    .collect(Collectors.toSet());
            uses = Collections.unmodifiableSet(uses);
            this.uses = uses;
            return uses;
        }
    }
    private volatile Set<String> uses;

    /**
      * Returns a map of the service providers that the module provides.
      * The map key is the type name of the service interface. The map key
      * is the set of type names for the service implementations.
      */
    Map<String, Set<String>> provides() {
        jdk.jigsaw.module.Module m = getModule();
        if (m == null) {
            return Collections.emptyMap();
        } else {
            return m.services();
        }
    }

    /**
     * Return the string representation of the module.
     */
    public String toString() {
        return "module " + name;
    }

    static {
        sun.misc.SharedSecrets.setJavaLangReflectAccess(
            new sun.misc.JavaLangReflectAccess() {
                @Override
                public Module defineUnnamedModule() {
                    return new Module("<unnamed>");
                }
                @Override
                public Module defineModule(ModuleGraph g, jdk.jigsaw.module.Module m) {
                    return new Module(g, m);
                }
                @Override
                public void setDefined(Module m) {
                    m.defined = true;
                }
                @Override
                public void addReadsModule(Module m1, Module m2) {
                    m1.reads.put(m2, Boolean.TRUE);
                }
                @Override
                public void addExport(Module m, String pkg, Module permit) {
                    Set<Module> permits = m.exports.computeIfAbsent(pkg, k -> new HashSet<>());
                    if (permit != null)
                        permits.add(permit);
                }
                @Override
                public Set<Module> exports(Module m, String pkg) {
                    // returns null if not exported
                    return m.exports.get(pkg);
                }
                @Override
                public boolean uses(Module m, String sn) {
                    return m.uses().contains(sn);
                }
                @Override
                public Set<String> provides(Module m, String sn) {
                    Set<String> provides = m.provides().get(sn);
                    if (provides == null) {
                        return Collections.emptySet();
                    } else {
                        return provides;
                    }
                }
            });
    }
}
