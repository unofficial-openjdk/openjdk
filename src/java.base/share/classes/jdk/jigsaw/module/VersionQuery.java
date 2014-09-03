/*
 * Copyright (c) 2009, 2013, Oracle and/or its affiliates. All rights reserved.
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
 * Vaguely Debian-like version queries, for now.
 * This will, eventually, change.
 *
 * @see <a href="http://www.debian.org/doc/debian-policy/ch-relationships.html#s-depsyntax">Debian
 * Policy Manual, Chapter 7: Declaring relationships between packages</a>
 */

@SuppressWarnings("serial")             // serialVersionUID intentionally omitted
public class VersionQuery
    implements Serializable
{

    private static enum Relation {

        LT("<"),
        LE("<="),
        EQ("="),
        GE(">="),
        GT(">");

        private String rep;

        private Relation(String s) {
            rep = s;
        }

        private static int parse(String s, VersionQuery vq) {
            char c = s.charAt(0);
            char d = (s.length() > 1) ? s.charAt(1) : '\0';
            if (c == '<') {
                if (d == '=') {
                    vq.relation = LE;
                    return 2;
                }
                vq.relation = LT;
                return 1;
            }
            if (c == '=') {
                vq.relation = EQ;
                return 1;
            }
            if (c == '>') {
                if (d == '=') {
                    vq.relation = GE;
                    return 2;
                }
                vq.relation = GT;
                return 1;
            }
            vq.relation = EQ;
            return 0;
        }

        @Override
        public String toString() {
            return rep;
        }

    }

    private Relation relation;
    private Version version;

    private VersionQuery(Relation r, Version v) {
        relation = r;
        version = v;
    }

    static VersionQuery fromVersion(Version v) {
        return new VersionQuery(Relation.EQ, v);
    }

    private static String VQS = " version-query string";

    private VersionQuery(String q) {
        if (q == null)
            throw new IllegalArgumentException("Null" + VQS);
        int n = q.length();
        if (n == 0)
            throw new IllegalArgumentException("Empty" + VQS);
        int i = Relation.parse(q, this);
        if (i >= n)
            throw new IllegalArgumentException(q + ": Incomplete" + VQS);
        version = Version.parse(q.substring(i));
    }

    public static VersionQuery parse(String vq) {
        if (vq == null)
            return null;
        return new VersionQuery(vq);
    }

    public boolean matches(Version v) {
        int c = version.compareTo(v);
        switch (relation) {
        case LT: return c > 0;
        case LE: return c >= 0;
        case EQ: return c == 0;
        case GE: return c <= 0;
        case GT: return c < 0;
        default:
            throw new AssertionError();
        }
    }

    @Override
    public boolean equals(Object ob) {
        if (!(ob instanceof VersionQuery))
            return false;
        VersionQuery that = (VersionQuery)ob;
        return (relation.equals(that.relation)
                && version.equals(that.version));
    }

    @Override
    public int hashCode() {
        return relation.hashCode() * 43 + version.hashCode();
    }

    @Override
    public String toString() {
        return "" + relation + version;
    }

}
