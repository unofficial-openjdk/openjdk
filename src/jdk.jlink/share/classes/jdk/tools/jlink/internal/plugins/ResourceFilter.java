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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.tools.jlink.plugins.ResourcePool.Resource;

/**
 *
 * Filter in or out a resource
 */
class ResourceFilter {

    private final List<Pattern> inPatterns;
    private final List<Pattern> outPatterns;

    static final String NEG = "^";

    ResourceFilter(String[] patterns) {
        this(patterns, false);
    }

    ResourceFilter(String[] patterns, boolean negateAll) {

        if (patterns != null && negateAll) {
            String[] excluded = new String[patterns.length];
            for (int i = 0; i < patterns.length; i++) {
                excluded[i] = ResourceFilter.NEG + patterns[i];
            }
            patterns = excluded;
        }

        List<Pattern> inPatterns = new ArrayList<>();
        List<Pattern> outPatterns = new ArrayList<>();
        if (patterns != null) {
            for (String p : patterns) {
                p = p.replaceAll(" ", "");
                List<Pattern> lst = p.startsWith(NEG) ? outPatterns : inPatterns;
                String pat = p.startsWith(NEG) ? p.substring(NEG.length()) : p;
                Pattern pattern = Pattern.compile(escape(pat));
                lst.add(pattern);
            }
        }
        this.inPatterns = Collections.unmodifiableList(inPatterns);
        this.outPatterns = Collections.unmodifiableList(outPatterns);
    }

    private static String escape(String s) {
        s = s.replaceAll(" ", "");
        s = s.replaceAll("\\$", Matcher.quoteReplacement("\\$"));
        s = s.replaceAll("\\.", Matcher.quoteReplacement("\\."));
        s = s.replaceAll("\\*", ".+");
        return s;
    }

    public boolean accept(String path)
            throws Exception {
        for (Pattern p : outPatterns) {
            Matcher m = p.matcher(path);
            if (m.matches()) {
                //System.out.println("Excluding file " + resource.getPath());
                return false;
            }
        }
        boolean accepted = false;
        // If the inPatterns is empty, means that all resources are accepted.
        if (inPatterns.isEmpty()) {
            accepted = true;
        } else {
            for (Pattern p : inPatterns) {
                Matcher m = p.matcher(path);
                if (m.matches()) {
                    //System.out.println("Including file " + resource.getPath());
                    accepted = true;
                    break;
                }
            }
        }
        return accepted;
    }
}
