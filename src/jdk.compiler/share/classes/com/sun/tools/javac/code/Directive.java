/*
 * Copyright (c) 2009, 2014, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.javac.code;

import java.util.EnumSet;
import java.util.Set;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.ModuleSymbol;
import com.sun.tools.javac.code.Symbol.PackageSymbol;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;


/**
 *  Root class for the directives that may appear in module compilation units.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public abstract class Directive {
    public enum Kind {
        EXPORTS,
        PERMITS,
        PROVIDES,
        REQUIRES,
        USES
    }

    /** Flags for RequiresModuleDirective and RequiresServiceDirective. */
    public enum RequiresFlag {
        PUBLIC(0x0020),
        SYNTHETIC(0x1000),
        MANDATED(0x8000);

        // overkill? move to ClassWriter?
        public static int value(Set<RequiresFlag> s) {
            int v = 0;
            for (RequiresFlag f: s)
                v |= f.value;
            return v;
        }

        RequiresFlag(int value) {
            this.value = value;
        }

        public final int value;
    }

    public abstract Kind getKind();

    abstract <R, P> R accept(Visitor<R, P> visitor, P data);

    static <T extends Directive> List<T> filter(List<Directive> directives, Kind kind, Class<T> clazz) {
        ListBuffer<T> list = new ListBuffer<>();
        for (Directive d: directives) {
            if (d.getKind() == kind)
                list.add(clazz.cast(d));
        }
        return list.toList();
    }

    /**
     * 'exports' Package ';'
     */
    public static class ExportsDirective extends Directive {
        public final PackageSymbol sym;
        public final List<Name> moduleNames; // maybe ModuleSymbol

        public ExportsDirective(PackageSymbol sym, List<Name> moduleNames) {
            this.sym = sym;
            this.moduleNames = moduleNames;
        }

        @Override
        public Kind getKind() {
            return Kind.EXPORTS;
        }

        @Override
        public String toString() {
            if (moduleNames == null)
                return "Exports[" + sym + "]";
            else
                return "Exports[" + sym + ":" + moduleNames + "]";
        }

        @Override
        public <R, P> R accept(Visitor<R, P> visitor, P data) {
            return visitor.visitExports(this, data);
        }
    }

    /**
     * 'permits' ModuleName ';'
     */
    public static class PermitsDirective extends Directive {
        public final Name moduleName;  // may eventually be ModuleSymbol

        public PermitsDirective(Name moduleName) {
            this.moduleName = moduleName;
        }

        @Override
        public Kind getKind() {
            return Kind.PERMITS;
        }

        @Override
        public String toString() {
            return "Permits[" + moduleName + "]";
        }

        @Override
        public <R, P> R accept(Visitor<R, P> visitor, P data) {
            return visitor.visitPermits(this, data);
        }
    }

    /**
     * 'provides' ServiceName 'with' QualifiedIdentifer ';'
     */
    public static class ProvidesDirective extends Directive {
        public final ClassSymbol service;
        public final ClassSymbol impl;

        public ProvidesDirective(ClassSymbol service, ClassSymbol impl) {
            this.service = service;
            this.impl = impl;
        }

        @Override
        public Kind getKind() {
            return Kind.PROVIDES;
        }

        @Override
        public String toString() {
            return "Provides[" + service + "," + impl + "]";
        }

        @Override
        public <R, P> R accept(Visitor<R, P> visitor, P data) {
            return visitor.visitProvides(this, data);
        }
    }

    /**
     * 'requires' ['public'] ViewName ';'
     */
    public static class RequiresDirective extends Directive {
        public final Name moduleName;  // may eventually be ModuleSymbol
        public final Set<RequiresFlag> flags;

        public RequiresDirective(Name moduleName) {
            this(moduleName, EnumSet.noneOf(RequiresFlag.class));
        }

        public RequiresDirective(Name moduleName, Set<RequiresFlag> flags) {
            this.moduleName = moduleName;
            this.flags = flags;
        }

        @Override
        public Kind getKind() {
            return Kind.REQUIRES;
        }

        @Override
        public String toString() {
            return "Requires[" + flags + "," + moduleName + "]";
        }

        @Override
        public <R, P> R accept(Visitor<R, P> visitor, P data) {
            return visitor.visitRequires(this, data);
        }
    }

    /**
     * 'uses' ServiceName ';'
     */
    public static class UsesDirective extends Directive {
        public final ClassSymbol service;

        public UsesDirective(ClassSymbol service) {
            this.service = service;
        }

        @Override
        public Kind getKind() {
            return Kind.USES;
        }

        @Override
        public String toString() {
            return "Uses[" + service + "]";
        }

        @Override
        public <R, P> R accept(Visitor<R, P> visitor, P data) {
            return visitor.visitUses(this, data);
        }
    }

    public static interface Visitor<R, P> {
        R visitRequires(RequiresDirective d, P p);
        R visitExports(ExportsDirective d, P p);
        R visitPermits(PermitsDirective d, P p);
        R visitProvides(ProvidesDirective d, P p);
        R visitUses(UsesDirective d, P p);
    }

    public static class SimpleVisitor<R, P> implements Visitor<R, P> {
        protected final R DEFAULT_VALUE;

        protected SimpleVisitor() {
            DEFAULT_VALUE = null;
        }

        protected SimpleVisitor(R defaultValue) {
            DEFAULT_VALUE = defaultValue;
        }

        protected R defaultAction(Directive d, P p) {
            return DEFAULT_VALUE;
        }

        public final R visit(Directive d, P p) {
            return (d == null) ? null : d.accept(this, p);
        }

        public final R visit(Iterable<? extends Directive> ds, P p) {
            R r = null;
            if (ds != null)
                for (Directive d : ds)
                    r = visit(d, p);
            return r;
        }

        @Override
        public R visitExports(ExportsDirective d, P p) {
            return defaultAction(d, p);
        }

        @Override
        public R visitPermits(PermitsDirective d, P p) {
            return defaultAction(d, p);
        }

        @Override
        public R visitProvides(ProvidesDirective d, P p) {
            return defaultAction(d, p);
        }

        @Override
        public R visitRequires(RequiresDirective d, P p) {
            return defaultAction(d, p);
        }

        @Override
        public R visitUses(UsesDirective d, P p) {
            return defaultAction(d, p);
        }
    }

    public static class Scanner<R, P> implements Visitor<R, P> {


        /** Scan a single node.
         */
        public R scan(Directive d, P p) {
            return (d == null) ? null : d.accept(this, p);
        }

        private R scanAndReduce(Directive d, P p, R r) {
            return reduce(scan(d, p), r);
        }

        /** Scan a list of nodes.
         */
        public R scan(Iterable<? extends Directive> ds, P p) {
            R r = null;
            if (ds != null) {
                boolean first = true;
                for (Directive d : ds) {
                    r = (first ? scan(d, p) : scanAndReduce(d, p, r));
                    first = false;
                }
            }
            return r;
        }

        /**
         * Reduces two results into a combined result.
         * The default implementation is to return the first parameter.
         * The general contract of the method is that it may take any action whatsoever.
         */
        public R reduce(R r1, R r2) {
            return r1;
        }

        public R visitExports(ExportsDirective d, P p) {
            return null;
        }

        public R visitPermits(PermitsDirective d, P p) {
            return null;
        }

        public R visitProvides(ProvidesDirective d, P p) {
            return null;
        }

        public R visitRequires(RequiresDirective d, P p) {
            return null;
        }

        public R visitUses(UsesDirective d, P p) {
            return null;
        }

    }
}
