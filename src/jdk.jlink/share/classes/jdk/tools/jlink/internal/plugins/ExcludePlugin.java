/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
package jdk.tools.jlink.internal.plugins;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.tools.jlink.plugins.Plugin;
import jdk.tools.jlink.plugins.ResourcePool;
import jdk.tools.jlink.plugins.StringTable;

/**
 *
 * Exclude resources plugin
 */
final class ExcludePlugin implements Plugin {

    private final List<Pattern> patterns;

    ExcludePlugin(List<Pattern> patterns) {
        this.patterns = patterns;
    }

    @Override
    public String getName() {
        return ExcludeProvider.NAME;
    }

    @Override
    public void visit(ResourcePool inResources, ResourcePool outResources, StringTable strings)
            throws Exception {
        inResources.visit((resource, order,  str) -> {
            for (Pattern p : patterns) {
                Matcher m = p.matcher(resource.getPath());
                if (m.matches()) {
                    System.out.println("Excluding file " + resource.getPath());
                    return null;
                }
            }
            return resource;
        }, outResources, strings);
    }
}
