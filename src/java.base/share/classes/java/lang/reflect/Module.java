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

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jdk.jigsaw.module.ModuleArtifact;
import jdk.jigsaw.module.ModuleDescriptor;
import jdk.jigsaw.module.Version;

import sun.misc.ServicesCatalog;
import sun.misc.SharedSecrets;
import sun.misc.VM;

/**
 * Represents a runtime module.
 *
 * <p> {@code Module} does not define a public constructor. Instead {@code
 * Module} objects are constructed by the Java Virtual Machine when a
 * {@link jdk.jigsaw.module.Configuration Configuration} is reified by means
 * of creating a {@link jdk.jigsaw.module.Layer Layer}. </p>
 *
 * @since 1.9
 * @see java.lang.Class#getModule
 */
public final class Module {

    // no <clinit> as this class is initialized very early in the startup

    // module name and loader
    private final String name;
    private final ClassLoader loader;

    // initialized lazily for modules defined by VM during bootstrapping
    private volatile ModuleDescriptor descriptor;
    private volatile Set<String> packages;

    // indicates whether the Module is fully defined - will be used later
    // by snapshot APIs (JVM TI for example)
    private volatile boolean defined;

    // called by VM during startup for at least java.base
    Module(ClassLoader loader, String name) {
        this.loader = loader;
        this.name = name;
    }

    static Module defineModule(ClassLoader loader,
                               ModuleDescriptor descriptor,
                               Version version,
                               URI location,
                               Set<String> packages)
    {

       Module m;

        // define modules, except java.base as it is defined by VM
        String name = descriptor.name();
        if (loader == null && name.equals("java.base")) {
            m = Object.class.getModule();
            assert m != null;
        } else {
            int n = packages.size();
            String[] array = new String[n];
            int i = 0;
            for (String pkg: packages) {
                array[i++] = pkg.replace('.', '/');
            }

            String vs = (version != null) ? version.toString() : null;
            String uris = (location != null) ? location.toString() : null;
            m = VM.defineModule(name, vs, uris, loader, array);
        }

        // set fields as these are not set by the VM
        m.descriptor = descriptor;
        m.packages = packages;

        // register this Module in the service catalog if it provides services
        Map<String, Set<String>> services = descriptor.services();
        if (!services.isEmpty()) {
            ServicesCatalog catalog;
            if (loader == null) {
                catalog = ServicesCatalog.getSystemServicesCatalog();
            } else {
                catalog = SharedSecrets.getJavaLangAccess().getServicesCatalog(loader);
            }
            catalog.register(m);
        }

        return m;
    }

    /**
     * Returns the module name.
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the {@code ClassLoader} for this module.
     */
    public ClassLoader getClassLoader() {
        return loader;
    }

    /**
     * Returns the module descriptor for this module.
     */
    public ModuleDescriptor getDescriptor() {
        return descriptor;
    }

    /**
     * Returns an array of the package names of the packages in this module.
     *
     * <p> The package names are the fully-qualified names of the packages as
     * defined in section 6.5.3 of <cite>The Java&trade; Language Specification
     * </cite>, for example, {@code "java.lang"}. </p>
     *
     * <p> The returned array contains an element for each package in the
     * module when it was initially created. It may contain elements
     * corresponding to packages added tothe  module after it was created
     * (packages added to support dynamic proxy classes for example). A package
     * name appears at most once in the returned array. </p>
     *
     * @return an array of the package names of the packages in this module
     */
    public String[] getPackages() {
        return packages.toArray(new String[0]);
    }

    /**
     * Makes the given {@code Module} readable to this module. This method
     * is no-op if {@code target} is {@code null} or {@code this} (all modules
     * can read the unnamed module or themselves).
     *
     * <p> If there is a security manager then its {@code checkPermission}
     * method if first called with a {@code ReflectPermission("addReadsModule")}
     * permission to check that the caller is allowed to change the
     * readability graph. </p>
     *
     * @throws SecurityException if denied by the security manager
     *
     * @see #canRead
     */
    public void addReads(Module target) {
        if (target != null && target != this) {
            SecurityManager sm = System.getSecurityManager();
            if (sm != null) {
                ReflectPermission perm = new ReflectPermission("addReadsModule");
                sm.checkPermission(perm);
            }
            implAddReads(target);
        }
    }

    /**
     * Indicates if this {@code Module} reads the given {@code Module}.
     *
     * <p> Returns {@code true} if {@code m} is {@code null} (the unnamed
     * readable is readable to all modules), or {@code m} is this module (a
     * module can read itself). </p>
     *
     * @see #addReads
     */
    public boolean canRead(Module target) {
        if (target == null || target == this)
            return true;
        return VM.canReadModule(this, target);
    }

    /**
     * Returns the string representation.
     */
    @Override
    public String toString() {
        return "module " + name;
    }


    /**
     * Makes the given {@code Module} readable to this module.
     */
    void implAddReads(Module target) {
        VM.addReadsModule(this, target);
    }

    /**
     * Exports the given package name to the given module.
     */
    void implAddExports(String pkg, Module who) {
        VM.addModuleExports(this, pkg.replace('.', '/'), who);
    }

    /**
     * Returns {@code true} if the given package name is exported to the
     * given module.
     */
    boolean isExported(String pkg, Module who) {
        return VM.isExportedToModule(this, pkg.replace('.', '/'), who);
    }

    /**
     * Dynamically adds a package to this module. This is used for dynamic
     * proxies (for example).
     */
    void addPackage(String pkg) {
        sun.misc.VM.addModulePackage(this, pkg.replace('.', '/'));
        Set<String> pkgs = new HashSet<>(this.packages);
        pkgs.add(pkg);
        this.packages = pkgs;
    }

    static {
        sun.misc.SharedSecrets.setJavaLangReflectAccess(
            new sun.misc.JavaLangReflectAccess() {
                @Override
                public Module defineModule(ClassLoader loader, ModuleArtifact artifact) {
                    Version version = artifact.descriptor().id().version();
                    return Module.defineModule(loader,
                                               artifact.descriptor(),
                                               version,
                                               artifact.location(),
                                               artifact.packages());
                }
                @Override
                public Module defineModule(ClassLoader loader, ModuleDescriptor descriptor,
                                           Set<String> packages) {
                    return Module.defineModule(loader, descriptor, null, null, packages);
                }

                @Override
                public void setDefined(Module m) {
                    m.defined = true;
                }
                @Override
                public void addReadsModule(Module m1, Module m2) {
                   m1.implAddReads(m2);
                }
                @Override
                public void addExports(Module m, String pkg, Module who) {
                    m.implAddExports(pkg, who);
                }
                @Override
                public boolean isExported(Module m, String pkg, Module who) {
                    return m.isExported(pkg, who);
                }
                @Override
                public boolean uses(Module m, String sn) {
                    return m.getDescriptor().serviceDependences().contains(sn);
                }
                @Override
                public Set<String> provides(Module m, String sn) {
                    Set<String> provides = m.getDescriptor().services().get(sn);
                    if (provides == null) {
                        return Collections.emptySet();
                    } else {
                        return provides;
                    }
                }
                @Override
                public void addPackage(Module m, String pkg) {
                    m.addPackage(pkg);
                }
            });
    }
}
