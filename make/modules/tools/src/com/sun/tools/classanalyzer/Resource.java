/*
 * Copyright (c) 2009, 20013, Oracle and/or its affiliates. All rights reserved.
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
import java.io.InputStream;

/**
 *
 */
public class Resource implements Comparable<Resource> {
    private final String pathname;
    private final long filesize;
    protected final String name;
    Module module;
    Resource(String fname) {
        this(fname, 0);
    }

    Resource(String fname, long size) {
        this.pathname = fname.replace('/', File.separatorChar);
        this.name = fname.replace(File.separatorChar, '/');
        this.filesize = size;
    }

    String getName() {
        return name;
    }

    String getPathname() {
        return pathname;
    }

    long getFileSize() {
        return filesize;
    }

    boolean isService() {
        return false;
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

    @Override
    public String toString() {
        return name;
    }

    @Override
    public int compareTo(Resource o) {
        return name.compareTo(o.name);
    }

    static boolean isResource(String pathname) {
        // skip these files
        String name = pathname.replace(File.separatorChar, '/');
        if (name.endsWith("META-INF/MANIFEST.MF")) {
            return false;
        }
        if (name.contains("META-INF/JCE_RSA.")) {
            return false;
        }
        if (name.contains("META-INF/INDEX.LIST")) {
            return false;
        }
        if (name.contains("META-INF/") &&
                (name.endsWith(".RSA") || name.endsWith(".SF"))) {
            return false;
        }
        if (name.startsWith("_the.") || name.equals("source_tips")) {
            return false;
        }
        return true;
    }

    static Resource getResource(String fname, InputStream in, long size) {
        Resource res;
        fname = fname.replace(File.separatorChar, '/');
        if (fname.startsWith(Service.METAINF_SERVICES)) {
            res = Service.loadProviderConfig(fname, in, size);
        } else {
            res = new Resource(fname, size);
        }
        return res;
    }

}
