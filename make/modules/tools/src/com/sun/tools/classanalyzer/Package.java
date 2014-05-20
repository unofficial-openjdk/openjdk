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

import java.util.HashMap;
import java.util.Map;

/**
 * Package Information
 */
public class Package implements Comparable<Package> {
    private final String pkgName;
    final Module module;
    boolean isExported;
    int  classCount;
    long classBytes;
    int  resourceCount;
    long resourceBytes;
    private int publicClassCount;
    Profile profile;
    Package(String name, Module m) {
        this.pkgName = name;
        this.module = m;
        this.isExported = isExportedPackage(name);
        this.profile = isExported ? exportedPackages.get(name) : null;
    }

    String name() {
        return pkgName;
    }

    Module module() {
        return module;
    }
    boolean hasClasses() {
        return classCount > 0;
    }

    boolean hasPublicClasses() {
        return publicClassCount > 0;
    }
    void addKlass(Klass k) {
        classCount++;
        classBytes += k.getFileSize();
        if (k.isPublic()) {
            publicClassCount++;
        }
    }

    void addResource(Resource r) {
        resourceCount++;
        resourceBytes += r.getFileSize();
    }

    void merge(Package p) {
        if (!pkgName.equals(p.pkgName) || module.group() != p.module.group()) {
            throw new IllegalArgumentException(this + " != " + p);
        }
        classCount += p.classCount;
        classBytes += p.classBytes;
        resourceCount += p.resourceCount;
        resourceBytes += p.resourceBytes;
        publicClassCount += p.publicClassCount;
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 59 * hash + (this.module != null ? this.module.hashCode() : 0);
        hash = 59 * hash + (this.pkgName != null ? this.pkgName.hashCode() : 0);
        return hash;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Package) {
            Package p = (Package) o;
            return p.module.equals(this.module) && p.pkgName.equals(this.pkgName);
        }
        return false;
    }

    @Override
    public int compareTo(Package p) {
        if (this.equals(p)) {
            return 0;
        } else if (pkgName.compareTo(p.pkgName) == 0) {
            return module.compareTo(p.module);
        } else {
            return pkgName.compareTo(p.pkgName);
        }
    }

    @Override
    public String toString() {
        return pkgName + " : " + module.name();
    }
    private final static Map<String, Profile> exportedPackages = new HashMap<>();
    static {
        // if exported.packages property is not set,
        // exports all packages
        for (Profile p : Profile.values()) {
            String apis = Module.getModuleProperty("profile_" + p.profile + "_exported.packages");
            if (apis != null) {
                for (String s : apis.split("\\s+")) {
                    String pn = s.trim();
                    exportedPackages.put(pn, p);
                }
            }
        }
    }

    static boolean isExportedPackage(String pkg) {
        return exportedPackages.isEmpty() || exportedPackages.containsKey(pkg);
    }
}
