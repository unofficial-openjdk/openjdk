/*
 * Copyright (c) 2009, 2013, Oracle and/or its affiliates. All rights reserved.
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
 *
 */

package com.sun.tools.classanalyzer;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import com.sun.tools.classfile.AccessFlags;
import com.sun.tools.classfile.Dependency.Location;
import java.util.Collections;

/**
 *
 */
public class Klass implements Comparable<Klass> {
    private final String classname;
    private final String packagename;
    private Module module;
    private boolean isJavaLangObject;
    private String[] paths;
    private AccessFlags accessFlags;
    private long filesize;

    private Set<Location> deps;
    public Klass(String cn, AccessFlags accessFlags, long bytelength) {
        this.classname = cn.replace('/', '.');
        this.paths = cn.replace('.', '/').split("/");
        this.isJavaLangObject = this.classname.equals("java.lang.Object");
        this.deps = new HashSet<>();
        this.accessFlags = accessFlags;
        this.filesize = bytelength;

        int pos = this.classname.lastIndexOf('.');
        this.packagename = (pos > 0) ? this.classname.substring(0, pos) : "<unnamed>";
    }

    String getBasename() {
        return paths[paths.length - 1];
    }

    String getClassName() {
        return classname;
    }

    String getClassFilePathname() {
        StringBuilder sb = new StringBuilder(paths[0]);
        for (int i = 1; i < paths.length; i++) {
            String p = paths[i];
            sb.append(File.separator).append(p);
        }
        return sb.append(".class").toString();
    }

    String getPackageName() {
        return packagename;
    }

    boolean isPublic() {
        return accessFlags == null || accessFlags.is(AccessFlags.ACC_PUBLIC);
    }

    Module getModule() {
        return module;
    }

    void setModule(Module m) {
        if (module != null) {
            throw new RuntimeException("Module for " + this + " already set");
        }
        this.module = m;
    }

    long getFileSize() {
        return this.filesize;
    }

    boolean exists() {
        return filesize > 0;
    }

    boolean skip(Klass k) {
        // skip if either class is a root or same class
        return k.isJavaLangObject || this == k || k.classname.equals(classname);
    }

    public void reference(Location target) {
        deps.add(target);
    }

    Set<Location> getDeps() {
        return deps;
    }

    @Override
    public String toString() {
        return classname;
    }

    @Override
    public int compareTo(Klass o) {
        return classname.compareTo(o.classname);
    }
}
