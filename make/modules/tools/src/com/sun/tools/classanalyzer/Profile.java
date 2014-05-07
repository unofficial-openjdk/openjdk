/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.classanalyzer;


/**
 * Build the profile information.
 *
 * Note: this currently depends on modules.properties to list
 * the exported packages for each profile.
 *
 * The current mechanism such as ct.sym and makefiles/profile-rt-include.txt
 * does not contain complete information for all JDK classes for all platforms
 * since they are target for different use (e.g. ct.sym contains entry for
 * rt.jar only but not security providers for example.)
 */
public enum Profile {
    COMPACT1(1),
    COMPACT2(2),
    COMPACT3(3),
    FULL_JRE("Full JRE", 4, "jdk.runtime"),
    JDK("JDK", 5, "jdk");

    final String name;
    final int profile;
    final String modulename;
    private Profile(int profile) {
        this("compact" + profile, profile, "java.compact" + profile);
    }

    private Profile(String name, int profile, String modulename) {
        this.name = name;
        this.profile = profile;
        this.modulename = modulename;
    }

    @Override
    public String toString() {
        return name;
    }

    public boolean requires(Profile p) {
        return p != null && p.profile < this.profile;
    }

    public static Profile profileForModule(String modulename) {
        for (Profile profile : Profile.values()) {
            if (modulename.equals(profile.modulename)) {
                return profile;
            }
        }
        return null;
    }
}
