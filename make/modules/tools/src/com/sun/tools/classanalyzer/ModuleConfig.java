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
 */
package com.sun.tools.classanalyzer;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedHashSet;

/**
 * module config file parser
 */
public class ModuleConfig {
    private final Set<String> roots;
    private final Set<String> includes;
    private final Filter filter;
    private List<String> members;
    final String module;
    final String version;
    final Map<String, Dependence> requires;
    final Set<String> permits;
    final Map<String, Set<String>> providers;
    final Map<String, Set<String>> exportsTo;

    ModuleConfig(String name, String version) {
        assert name != null && version != null;

        this.module = name;
        this.version = version;
        this.roots = new TreeSet<>();
        this.includes = new TreeSet<>();
        this.requires = new LinkedHashMap<>();
        this.filter = new Filter(this);
        this.permits = new HashSet<>();
        this.providers = new HashMap<>();
        this.exportsTo = new HashMap<>();
    }

    static ModuleConfig moduleConfigForUnknownModule() {
        ModuleConfig mc = new ModuleConfig("unknown", "0");
        mc.includes.add("**");
        return mc;
    }

    List<String> members() {
        if (members == null) {
            members = new LinkedList<>();

            for (String s : includes) {
                if (!s.contains("*")) {
                    // this isn't necessarily a module.  Will determine later.
                    members.add(s);
                }
            }
        }
        return members;
    }

    Map<String, Dependence> requires() {
        return requires;
    }

    boolean matchesRoot(String name) {
        for (String pattern : roots) {
            if (matches(name, pattern)) {
                return true;
            }
        }
        return false;
    }

    boolean matchesIncludes(String name) {
        for (String pattern : includes) {
            if (matches(name, pattern)) {
                return true;
            }
        }
        return false;
    }

    boolean isExcluded(String name) {
        return filter.isExcluded(name);
    }

    boolean matchesPackage(String packageName, String pattern) {
        int pos = pattern.lastIndexOf('.');
        String pkg = pos > 0 ? pattern.substring(0, pos) : "<unnamed>";
        return packageName.equals(pkg);
    }

    boolean matches(String name, String pattern) {
        if (pattern.contains("**") && !pattern.endsWith("**")) {
            throw new UnsupportedOperationException("Not yet implemented");
        }

        String javaName = name;

        boolean isResourceFile = name.indexOf('/') >= 0;
        if (isResourceFile) {
            // it's a resource file; convert the name as a java
            javaName = name.replace('/', '.');
        }
        if (pattern.indexOf('/') < 0) {
            // if the pattern doesn't contain '/
            return matchesJavaName(javaName, pattern);
        } else {
            if (isResourceFile) {
                // the pattern is for matching resource file
                return matchesNameWithSlash(name, pattern);
            } else {
                return false;
            }
        }
    }

    boolean matchesJavaName(String name, String pattern) {
        int pos = name.lastIndexOf('.');
        String packageName = pos > 0 ? name.substring(0, pos) : "<unnamed>";
        if (pattern.endsWith("**")) {
            String p = pattern.substring(0, pattern.length() - 2);
            return name.startsWith(p);
        } else if (pattern.endsWith("*") && pattern.indexOf('*') == pattern.lastIndexOf('*')) {
            if (matchesPackage(packageName, pattern)) {
                // package name has to be exact match
                String p = pattern.substring(0, pattern.length() - 1);
                return name.startsWith(p);
            } else {
                return false;
            }
        } else if (pattern.contains("*")) {
            String basename = pos > 0 ? name.substring(pos + 1, name.length()) : name;
            pos = pattern.indexOf('*');
            String prefix = pattern.substring(0, pos);
            String suffix = pattern.substring(pos + 1, pattern.length());
            if (name.startsWith(prefix) && matchesPackage(packageName, prefix)) {
                // package name has to be exact match
                if (suffix.contains("*")) {
                    return name.matches(convertToRegex(pattern));
                } else {
                    return basename.endsWith(suffix);
                }
            } else {
                // we don't support wildcard be used in the package name
                return false;
            }
        } else {
            // exact match or inner class
            return name.equals(pattern) || name.startsWith(pattern + "$");
        }
    }

    boolean matchesNameWithSlash(String name, String pattern) {
        if (pattern.endsWith("**")) {
            String p = pattern.substring(0, pattern.length() - 2);
            return name.startsWith(p);
        } else if (pattern.contains("*")) {
            int pos = pattern.indexOf('*');
            String prefix = pattern.substring(0, pos);
            String suffix = pattern.substring(pos + 1, pattern.length());
            if (!name.startsWith(prefix)) {
                // prefix has to exact match
                return false;
            }

            String tail = name.substring(pos, name.length());
            if (pattern.indexOf('*') == pattern.lastIndexOf('*')) {
                // exact match prefix with no '/' in the tail string
                String wildcard = tail.substring(0, tail.length() - suffix.length());
                return tail.indexOf('/') < 0 && tail.endsWith(suffix);
            }

            if (suffix.contains("*")) {
                return matchesNameWithSlash(tail, suffix);
            } else {
                // tail ends with the suffix while no '/' in the wildcard matched string
                String any = tail.substring(0, tail.length() - suffix.length());
                return tail.endsWith(suffix) && any.indexOf('/') < 0;
            }
        } else {
            // exact match
            return name.equals(pattern);
        }
    }

    private String convertToRegex(String pattern) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int index = 0;
        int plen = pattern.length();
        while (i < plen) {
            char p = pattern.charAt(i);
            if (p == '*') {
                sb.append("(").append(pattern.substring(index, i)).append(")");
                if (i + 1 < plen && pattern.charAt(i + 1) == '*') {
                    sb.append(".*");
                    index = i + 2;
                } else {
                    sb.append("[^\\.]*");
                    index = i + 1;
                }
            }
            i++;
        }
        if (index < plen) {
            sb.append("(").append(pattern.substring(index, plen)).append(")");
        }
        return sb.toString();
    }

    static class Filter {

        final ModuleConfig config;
        final Set<String> exclude = new TreeSet<>();
        final Set<String> allow = new TreeSet<>();

        Filter(ModuleConfig config) {
            this.config = config;
        }

        Filter exclude(String pattern) {
            exclude.add(pattern);
            return this;
        }

        Filter allow(String pattern) {
            allow.add(pattern);
            return this;
        }

        String allowedBy(String name) {
            String allowedBy = null;
            for (String pattern : allow) {
                if (config.matches(name, pattern)) {
                    if (name.equals(pattern)) {
                        return pattern;  // exact match
                    }
                    if (allowedBy == null) {
                        allowedBy = pattern;
                    } else {
                        if (pattern.length() > allowedBy.length()) {
                            allowedBy = pattern;
                        }
                    }
                }
            }
            return allowedBy;
        }

        String excludedBy(String name) {
            String allowedBy = allowedBy(name);
            String excludedBy = null;

            if (allowedBy != null && name.equals(allowedBy)) {
                return null;  // exact match
            }
            for (String pattern : exclude) {
                if (config.matches(name, pattern)) {
                    // not matched by allowed rule or exact match
                    if (allowedBy == null || name.equals(pattern)) {
                        return pattern;
                    }
                    if (excludedBy == null) {
                        excludedBy = pattern;
                    } else {
                        if (pattern.length() > excludedBy.length()) {
                            excludedBy = pattern;
                        }
                    }
                }
            }
            return excludedBy;
        }

        boolean isExcluded(String name) {
            String allowedBy = allowedBy(name);
            String excludedBy = excludedBy(name);

            if (excludedBy == null) {
                return false;
            }
            // not matched by allowed rule or exact match
            if (allowedBy == null || name.equals(excludedBy)) {
                return true;
            }

            if (allowedBy == null) {
                return true;
            }
            if (allowedBy != null &&
                    excludedBy.length() > allowedBy.length()) {
                return true;
            }
            return false;
        }
    }

    private static String trimComment(String line) {
        StringBuilder sb = new StringBuilder();

        int pos = 0;
        while (pos >= 0 && pos < line.length()) {
            int c1 = line.indexOf("//", pos);
            if (c1 > 0 && !Character.isWhitespace(line.charAt(c1 - 1))) {
                // not a comment
                c1 = -1;
            }

            int c2 = line.indexOf("/*", pos);
            if (c2 > 0 && !Character.isWhitespace(line.charAt(c2 - 1))) {
                // not a comment
                c2 = -1;
            }

            int c = line.length();
            int n = line.length();
            if (c1 >= 0 || c2 >= 0) {
                if (c1 >= 0) {
                    c = c1;
                }
                if (c2 >= 0 && c2 < c) {
                    c = c2;
                }
                int c3 = line.indexOf("*/", c2 + 2);
                if (c == c2 && c3 > c2) {
                    n = c3 + 2;
                }
            }
            if (c > 0) {
                if (sb.length() > 0) {
                    // add a whitespace if multiple comments on one line
                    sb.append(" ");
                }
                sb.append(line.substring(pos, c));
            }
            pos = n;
        }
        return sb.toString();
    }

    private static boolean beginBlockComment(String line) {
        int pos = 0;
        while (pos >= 0 && pos < line.length()) {
            int c = line.indexOf("/*", pos);
            if (c < 0) {
                return false;
            }

            if (c > 0 && !Character.isWhitespace(line.charAt(c - 1))) {
                return false;
            }

            int c1 = line.indexOf("//", pos);
            if (c1 >= 0 && c1 < c) {
                return false;
            }

            int c2 = line.indexOf("*/", c + 2);
            if (c2 < 0) {
                return true;
            }
            pos = c + 2;
        }
        return false;
    }
    // TODO: we shall remove "-" from the regex once we define
    // the naming convention for the module names without dashes
    static final Pattern classNamePattern = Pattern.compile("[\\w\\.\\*_$-/]+");
    static List<ModuleConfig> readConfigurationFile(String file, String version) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            return readConfigurationFile(file, in, version);
        }
    }

    static List<ModuleConfig> readConfigurationFile(String file, InputStream in, String version) throws IOException {
        List<ModuleConfig> result = new ArrayList<>();
        // parse configuration file
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in)))
        {
            String line;
            int lineNumber = 0;
            boolean inRoots = false;
            boolean inIncludes = false;
            boolean inAllows = false;
            boolean inExcludes = false;
            boolean inPermits = false;
            boolean inRequires = false;
            boolean inProvides = false;
            boolean inUses = false;
            boolean inExports = false;
            String inExportsTo = null;
            Set<Dependence.Identifier> identifiers = new HashSet<>();

            boolean inBlockComment = false;
            ModuleConfig config = null;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                if (inBlockComment) {
                    int c = line.indexOf("*/");
                    if (c >= 0) {
                        line = line.substring(c + 2, line.length());
                        inBlockComment = false;
                    } else {
                        // skip lines until end of comment block
                        continue;
                    }
                }

                inBlockComment = beginBlockComment(line);

                line = trimComment(line).trim();
                // ignore empty lines
                if (line.length() == 0) {
                    continue;
                }

                String values;
                if (inRoots || inIncludes || inExcludes || inAllows ||
                    inPermits || inRequires || inExports ) {
                    values = line;
                } else {
                    String[] s = line.split("\\s+");
                    String keyword = s[0].trim();
                    int nextIndex = keyword.length();
                    if (keyword.equals("module")) {
                        if (s.length != 3 || !s[2].trim().equals("{")) {
                            throw new RuntimeException(file + ", line " +
                                    lineNumber + ", is malformed");
                        }
                        // use the given version
                        String name = s[1].trim();
                        config = new ModuleConfig(name, version);

                        result.add(config);
                        // switch to a new module; so reset the flags
                        inRoots = false;
                        inIncludes = false;
                        inExcludes = false;
                        inAllows = false;
                        inRequires = false;
                        inPermits = false;
                        inProvides = false;
                        inUses = false;
                        inExports = false;
                        inExportsTo = null;
                        continue;
                    } else if (keyword.equals("roots")) {
                        inRoots = true;
                    } else if (keyword.equals("include")) {
                        inIncludes = true;
                    } else if (keyword.equals("exclude")) {
                        inExcludes = true;
                    } else if (keyword.equals("allow")) {
                        inAllows = true;
                    } else if (keyword.equals("permits")) {
                        inPermits = true;
                    } else if (keyword.equals("provides")) {
                        inProvides = true;
                        if (s.length != 4 || !s[2].trim().equals("with")) {
                            throw new RuntimeException(file + ", line "
                                    + lineNumber + ", is malformed");
                        }
                    } else if (keyword.equals("exports")) {
                        inExports = true;
                        inExportsTo = null;
                        if (s.length < 2 || s.length > 2 && !s[2].trim().equals("to")) {
                            throw new RuntimeException(file + ", line "
                                    + lineNumber + ", is malformed");
                        }
                        if (s.length > 2) {
                            // skip past "to"
                            inExportsTo = s[1];
                            nextIndex = line.indexOf(inExportsTo) + inExportsTo.length();
                            nextIndex = line.indexOf("to", nextIndex) + "to".length();
                        }
                        // can't have both non-qualified and qualified exports declared
                        Set<String> tos = config.exportsTo.get(s[1]);
                        if (tos != null &&
                                ((inExportsTo != null && tos.isEmpty()) ||
                                 (inExportsTo == null && !tos.isEmpty()))) {
                            throw new RuntimeException(file + ", line "
                                    + lineNumber + " duplicated exports: \"" + s[1] + "\"");
                        }
                    } else if (keyword.equals("uses")) {
                        inUses = true;
                        identifiers.clear();
                        identifiers.add(Dependence.Identifier.SERVICE);
                    } else if (keyword.equals("requires")) {
                        inRequires = true;
                        identifiers.clear();
                        if (s.length >= 2) {
                            String ss = s[1].trim();
                            if (ss.equals("public")) {
                                    identifiers.add(Dependence.Identifier.PUBLIC);
                                    nextIndex = line.indexOf(ss) + ss.length();
                            }
                        }
                    } else if (keyword.equals("}")) {
                        if (config == null || s.length != 1) {
                            throw new RuntimeException(file + ", line " +
                                    lineNumber + ", is malformed");
                        } else {
                            // end of a module
                            config = null;
                        }
                         continue;
                    } else {
                        throw new RuntimeException(file + ", \"" + keyword + "\" on line " +
                                lineNumber + ", is not recognized");
                    }

                    values = line.substring(nextIndex, line.length()).trim();
                }

                if (config == null) {
                    throw new RuntimeException(file + ", module not specified");
                }

                int len = values.length();
                if (len == 0) {
                    continue;
                }
                char lastchar = values.charAt(len - 1);
                if (lastchar != ',' && lastchar != ';') {
                    throw new RuntimeException(file + ", line " +
                            lineNumber + ", is malformed:" +
                            " ',' or ';' is missing.");
                }

                values = values.substring(0, len - 1);
                // parse the values specified for a keyword specified
                for (String s : values.split(",")) {
                    s = s.trim();
                    if (s.length() > 0) {
                        if (!classNamePattern.matcher(s).matches() && !inProvides) {
                            throw new RuntimeException(file + ", line " +
                                    lineNumber + ", is malformed: \"" + s + "\"");
                        }
                        if (inRoots) {
                            config.roots.add(s);
                        } else if (inIncludes) {
                            config.includes.add(s);
                        } else if (inExcludes) {
                            config.filter.exclude(s);
                        } else if (inAllows) {
                            config.filter.allow(s);
                        } else if (inPermits) {
                            config.permits.add(s);
                        } else if (inProvides) {
                            String[] names = values.split("\\s+");
                            assert names.length == 3;
                            Set<String> providers = config.providers.get(names[0]);
                            if (providers == null) {
                                config.providers.put(names[0], providers = new LinkedHashSet<>());
                            }
                            providers.add(names[2]);
                        } else if (inExports) {
                            if (inExportsTo != null) {
                                Set<String> tos = config.exportsTo.get(inExportsTo);
                                if (tos == null) {
                                    config.exportsTo.put(inExportsTo, tos = new HashSet<>());
                                }
                                tos.add(s);
                            } else {
                                String pkg = s.equals("**") ? "*" : s;
                                config.exportsTo.put(pkg, new HashSet<>());
                            }
                        } else if (inUses || inRequires) {
                            if (config.requires.containsKey(s)) {
                                throw new RuntimeException(file + ", line "
                                        + lineNumber + " duplicated requires: \"" + s + "\"");
                            }
                            Dependence d = new Dependence(s, identifiers);
                            config.requires.put(s, d);
                        }
                    }
                }
                if (lastchar == ';') {
                    inRoots = false;
                    inIncludes = false;
                    inExcludes = false;
                    inAllows = false;
                    inPermits = false;
                    inProvides = false;
                    inUses = false;
                    inRequires = false;
                    inExports = false;
                }
            }

            if (inBlockComment) {
                throw new RuntimeException(file + ", line " +
                        lineNumber + ", missing \"*/\" to end a block comment");
            }
            if (config != null) {
                throw new RuntimeException(file + ", line " +
                        lineNumber + ", missing \"}\" to end module definition" +
                        " for \"" + config.module + "\"");
            }
        }

        return result;
    }

    private String formatExports(int level, String keyword, Map<String, Set<String>> exportsTo) {
        if (exportsTo.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        String format = level == 1 ? "%4s%-9s" : "%8s%-9s";
        String spaces = String.format(format, "", "");
        for (Map.Entry<String, Set<String>> e : exportsTo.entrySet()) {
            sb.append(String.format(format, "", keyword)).append(e.getKey());
            if (e.getValue().isEmpty()) {
                sb.append(";\n");
                continue;
            }
            String separator = " to ";
            for (String v : e.getValue()) {
                  sb.append(separator).append(v);
                  separator = ", \n";
            }
            sb.append(";\n");
        }
        return sb.toString();
    }


    private String formatServices(int level, String keyword, Map<String, Set<String>> services) {
        if (services.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        String format = level == 1 ? "%4s%-9s" : "%8s%-9s";
        String spaces = String.format(format, "", "");
        for (Map.Entry<String, Set<String>> e : services.entrySet()) {
            for (String v : e.getValue()) {
                sb.append(String.format(format, "", keyword))
                  .append(e.getKey()).append(" with ")
                  .append(v).append(";\n");
            }
        }
        return sb.toString();
    }

    private String format(int level, String keyword, Collection<String> values) {
        if (values.size() == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        String format = level == 1 ? "%4s%-9s" : "%8s%-9s";
        String spaces = String.format(format, "", "");
        sb.append(String.format(format, "", keyword));
        int count = 0;
        for (String s : values) {
            if (count > 0) {
                sb.append(",\n").append(spaces);
            } else if (count++ > 0) {
                sb.append(", ");
            }
            sb.append(s);
        }
        if (count > 0) {
            sb.append(";\n");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("module " + module).append(" {\n");
        sb.append(format(1, "include", includes));
        sb.append(format(1, "root", roots));
        sb.append(format(1, "allow", filter.allow));
        sb.append(format(1, "exclude", filter.exclude));
        Set<String> reqs = new TreeSet<>();
        for (Dependence rm : requires.values()) {
            sb.append("    ").append(rm.toString()).append("\n");
        }
        sb.append(format(1, "permits", permits));
        sb.append(formatExports(1, "exports", exportsTo));
        sb.append(formatServices(1, "provides", providers));
        sb.append("}\n");
        return sb.toString();
    }
}
