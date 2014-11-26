/*
 * Copyright (c) 2009, 2013 Oracle and/or its affiliates. All rights reserved.
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

package jdk.jigsaw.module;

import java.io.*;
import java.util.*;


/**
 * Vaguely Debian-like version strings, for now.
 * This will, eventually, change.
 *
 * @see <a href="http://www.debian.org/doc/debian-policy/ch-controlfields.html#s-f-Version">Debian
 * Policy Manual, Chapter 5: Control files and their fields<a>
 */

@SuppressWarnings("serial")             // serialVersionUID intentionally omitted
public final class Version
    implements Comparable<Version>, Serializable
{

    private String version;

    // If Java had disjunctive types then we'd write List<Integer|String> here
    //
    private List<Object> sequence = new ArrayList<Object>(4);
    private List<Object> branch = new ArrayList<Object>(2);

    // Take a numeric token starting at position i
    // Append it to the given list
    // Return the index of the first character not taken
    // Requires: s.charAt(i) is (decimal) numeric
    //
    private static int takeNumber(String s, int i, List<Object> acc) {
        char c = s.charAt(i);
        int d = (c - '0');
        int n = s.length();
        while (++i < n) {
            c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                d = d * 10 + (c - '0');
                continue;
            }
            break;
        }
        acc.add(d);
        return i;
    }

    // Take a string token starting at position i
    // Append it to the given list
    // Return the index of the first character not taken
    // Requires: s.charAt(i) is not '.'
    //
    private static int takeString(String s, int i, List<Object> acc) {
        int b = i;
        char c = s.charAt(i);
        int n = s.length();
        while (++i < n) {
            c = s.charAt(i);
            if (c != '.' && c != '-' && !(c >= '0' && c <= '9'))
                continue;
            break;
        }
        acc.add(s.substring(b, i));
        return i;
    }

    // Version syntax, for now: tok+ ( '-' tok+)?
    // First token string is sequence, second is branch
    // Tokens are delimited by '.', or by changes between alpha & numeric chars
    // Numeric tokens are compared as decimal numbers
    // Non-numeric tokens are compared lexicographically
    // Tokens in branch may contain '-'
    //
    private Version(String v) {

        if (v == null)
            throw new IllegalArgumentException("Null version string");
        int n = v.length();
        if (n == 0)
            throw new IllegalArgumentException("Empty version string");

        int i = 0;
        char c = v.charAt(i);
        if (!(c >= '0' && c <= '9'))
            throw new
                IllegalArgumentException(v
                                         + ": Version does not start"
                                         + " with a number");
        i = takeNumber(v, i, sequence);

        while (i < n) {
            c = v.charAt(i);
            if (c == '.') {
                i++;
                continue;
            }
            if (c == '-') {
                i++;
                break;
            }
            if (c >= '0' && c <= '9')
                i = takeNumber(v, i, sequence);
            else
                i = takeString(v, i, sequence);
        }

        if (c == '-' && i >= n)
            throw new IllegalArgumentException(v + ": Empty branch");

        while (i < n) {
            c = v.charAt(i);
            if (c >= '0' && c <= '9')
                i = takeNumber(v, i, branch);
            else
                i = takeString(v, i, branch);
            if (i >= n)
                break;
            c = v.charAt(i);
            if (c == '.') {
                i++;
                continue;
            }
        }

        version = v;
    }

    public static Version parse(String v) {
        if (v == null)
            return null;
        return new Version(v);
    }

    @SuppressWarnings("unchecked")
    private int cmp(Object o1, Object o2) {
        return ((Comparable)o1).compareTo((Comparable)o2);
    }

    private int compareTokens(List<Object> ts1, List<Object> ts2) {
        int n = Math.min(ts1.size(), ts2.size());
        for (int i = 0; i < n; i++) {
            Object o1 = ts1.get(i);
            Object o2 = ts2.get(i);
            if (   (o1 instanceof Integer && o2 instanceof Integer)
                || (o1 instanceof String && o2 instanceof String)) {
                int c = cmp(o1, o2);
                if (c == 0)
                    continue;
                return c;
            }
            // Types differ, so convert number to string form
            int c = o1.toString().compareTo(o2.toString());
            if (c == 0)
                continue;
            return c;
        }
        List<Object> rest = ts1.size() > ts2.size() ? ts1 : ts2;
        int e = rest.size();
        for (int i = n; i < e; i++) {
            Object o = rest.get(i);
            if (o instanceof Integer && ((Integer)o) == 0)
                continue;
            return ts1.size() - ts2.size();
        }
        return 0;
    }

    @Override
    public int compareTo(Version that) {
        int c = compareTokens(this.sequence, that.sequence);
        if (c != 0)
            return c;
        return compareTokens(this.branch, that.branch);
    }

    public VersionQuery toQuery() {
        return VersionQuery.fromVersion(this);
    }

    public String toDebugString() {
        return "v" + sequence + "-" + branch;
    }

    @Override
    public boolean equals(Object ob) {
        if (!(ob instanceof Version))
            return false;
        return compareTo((Version)ob) == 0;
    }

    @Override
    public int hashCode() {
        return version.hashCode();
    }

    @Override
    public String toString() {
        return version;
    }

}
