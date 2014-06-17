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
package com.sun.tools.jdeps;

import com.sun.tools.classfile.Dependency.Location;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dependency Analyzer.
 */
public class Analyzer {
    /**
     * Type of the dependency analysis.  Appropriate level of data
     * will be stored.
     */
    public enum Type {
        SUMMARY,
        PACKAGE,
        CLASS,
        VERBOSE
    }

    private final Type type;
    private final boolean findJDKInternals;
    private final Map<Archive, ArchiveDeps> results = new ConcurrentHashMap<>();
    private final Map<Location, Archive> map = new ConcurrentHashMap<>();
    private final Archive NOT_FOUND
        = new Archive(JdepsTask.getMessage("artifact.not.found"), null);

    /**
     * Constructs an Analyzer instance.
     *
     * @param type Type of the dependency analysis
     */
    public Analyzer(Type type, boolean findJDKInternals) {
        this.type = type;
        this.findJDKInternals = findJDKInternals;
    }

    /**
     * Performs the dependency analysis on the given archives.
     */
    public void run(List<Archive> archives) {
        // build a map from Location to Archive
        buildLocationArchiveMap(archives);

        // traverse and analyze all dependencies
        for (Archive archive : archives) {
            ArchiveDeps deps = new ArchiveDeps(archive, type);
            archive.visitDependences(deps);
            results.put(archive, deps);
        }
    }

    /**
     * Verify module access
     */
    public boolean verify(List<Archive> archives) {
        // build a map from Location to Archive
        buildLocationArchiveMap(archives);

        // traverse and analyze all dependencies
        int count = 0;
        for (Archive archive : archives) {
            ArchiveDeps deps = new ModuleAccessChecker(archive);
            archive.visitDependences(deps);
            count += deps.dependencies().size();
            results.put(archive, deps);
        }
        return count == 0;
    }


    private void buildLocationArchiveMap(List<Archive> archives) {
        // build a map from Location to Archive
        for (Archive archive: archives) {
            for (Location l: archive.getClasses()) {
                if (!map.containsKey(l)) {
                    map.put(l, archive);
                } else {
                    // duplicated class warning?
                }
            }
        }
    }

    public boolean hasDependences(Archive source) {
        if (results.containsKey(source)) {
            return results.get(source).dependencies().size() > 0;
        }
        return false;
    }

    public interface Visitor {
        /**
         * Visits a recorded dependency from origin to target which can be
         * a fully-qualified classname, a package name, a module or
         * archive name depending on the Analyzer's type.
         */
        public void visitDependence(String origin, Archive originArchive,
                                    String target, Archive targetArchive);
    }

    public void visitDependences(Archive source, Visitor v, Type level) {
        ArchiveDeps result = results.get(source);
        if (level == type) {
            visit(result.dependencies(), v);
        } else if (level == Type.SUMMARY) {
            for (Archive d : result.requires()) {
                v.visitDependence(source.getName(), source, d.getName(), d);
            }
        } else {
            // requesting different level of analysis
            result = new ArchiveDeps(source, level);
            source.visitDependences(result);
            visit(result.dependencies(), v);
        }
    }

    private void visit(Set<Dependency> deps, Visitor v) {
        List<Dependency> ds = new ArrayList<>(deps);
        Collections.sort(ds);
        for (Dependency d : ds) {
            v.visitDependence(d.origin().getElement0(), d.origin().getElement1(),
                              d.target().getElement0(), d.target().getElement1());
        }
    }

    public void visitDependences(Archive source, Visitor v) {
        visitDependences(source, v, type);
    }

    /**
     * ArchiveDeps contains the dependencies for an Archive that can have one or
     * more classes.
     */
    class ArchiveDeps implements Archive.Visitor {
        protected final Archive archive;
        protected final Set<Archive> requires;
        protected final Set<Dependency> deps;
        protected final Type level;
        ArchiveDeps(Archive archive, Type level) {
            this.archive = archive;
            this.deps = new LinkedHashSet<>();
            this.requires = new HashSet<>();
            this.level = level;
        }

        Set<Dependency> dependencies() {
            return deps;
        }

        Set<Archive> requires() {
            return requires;
        }

        Module findModule(Archive archive) {
            if (Module.class.isInstance(archive)) {
                return (Module) archive;
            } else {
                return null;
            }
        }

        Archive findArchive(Location t) {
            Archive target = archive.getClasses().contains(t) ? archive : map.get(t);
            if (target == null) {
                map.put(t, target = NOT_FOUND);
            }
            return target;
        }

        protected boolean accept(Location o, Location t) {
            Archive target = findArchive(t);
            if (findJDKInternals) {
                Module from = findModule(archive);
                Module to = findModule(target);
                if (to == null || Profile.JDK.contains(to)) {
                    // non-JDK module
                    return false;
                }
                return !to.isAccessibleTo(o.getClassName(), from);
            } else {
                // filter intra-dependency unless in verbose mode
                return level == Type.VERBOSE || archive != target;
            }
        }

        // return classname or package name depedning on the level
        private String getLocationName(Location o) {
            if (level == Type.CLASS || level == Type.VERBOSE) {
                return o.getClassName();
            } else {
                String pkg = o.getPackageName();
                return pkg.isEmpty() ? "<unnamed>" : pkg;
            }
        }

        @Override
        public void visit(Location o, Location t) {
            if (accept(o, t)) {
                addEdge(o, t);
                Archive targetArchive = findArchive(t);
                if (!requires.contains(targetArchive)) {
                    requires.add(targetArchive);
                }
            }
        }

        private Dependency curEdge;
        protected Dependency addEdge(Location o, Location t) {
            String origin = getLocationName(o);
            String target = getLocationName(t);
            Archive targetArchive = findArchive(t);
            if (curEdge != null &&
                    curEdge.origin().getElement0().equals(origin) &&
                    curEdge.origin().getElement1() == archive &&
                    curEdge.target().getElement0().equals(target) &&
                    curEdge.target().getElement1() == targetArchive) {
                return curEdge;
            }

            Dependency e = new Dependency(new Pair<String,Archive>(origin, archive),
                                          new Pair<String,Archive>(target, targetArchive));
            if (deps.contains(e)) {
                for (Dependency e1 : deps) {
                    if (e.equals(e1)) {
                        curEdge = e1;
                    }
                }
            } else {
                deps.add(e);
                curEdge = e;
            }
            return curEdge;
        }
    }

    class ModuleAccessChecker extends ArchiveDeps {
        ModuleAccessChecker(Archive m) {
            super(m, type);
        }

        // returns true if t is not accessible
        protected boolean canAccess(Location o, Location t) {
            Archive targetArchive = findArchive(t);
            Module origin = findModule(archive);
            Module target = findModule(targetArchive);
            if (targetArchive == NOT_FOUND)
                return false;

            // unnamed module
            // ## should check public type?
            if (target == null)
                return true;

            // module-private
            if (origin == target)
                return true;

            return target.isAccessibleTo(t.getClassName(), origin);
        }

        @Override
        public void visit(Location o, Location t) {
            if (!canAccess(o, t)) {
                addEdge(o, t);
            }
            // include required archives
            Archive targetArchive = findArchive(t);
            if (targetArchive != archive && !requires.contains(targetArchive)) {
                requires.add(targetArchive);
            }
        }
    }

    /*
     * Class-level or package-level dependency
     */
    class Dependency implements Comparable<Dependency> {
        final Pair<String, Archive> origin;
        final Pair<String, Archive> target;

        Dependency(Pair<String, Archive> origin, Pair<String, Archive> target) {
            this.origin = origin;
            this.target = target;
        }

        Pair<String, Archive> origin() {
            return origin;
        }

        Pair<String, Archive> target() {
            return target;
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean equals(Object o) {
            if (o instanceof Dependency) {
                Dependency e = (Dependency) o;
                return this.origin.equals(e.origin)
                        && this.target.equals(e.target);
            }
            return false;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 67*hash + Objects.hashCode(this.origin)
                           + Objects.hashCode(this.target);
            return hash;
        }
        @Override
        public int compareTo(Dependency o) {
            if (this.origin.equals(o.origin)) {
                return compare(this.target, o.target);
            } else {
                return compare(this.origin, o.origin);
            }
        }
        private int compare(Pair<String,Archive> p1, Pair<String,Archive> p2) {
            if (p1.element0.equals(p2.element0)) {
                return p1.element1.getPathName().compareTo(p2.element1.getPathName());
            } else {
                return p1.element0.compareTo(p2.element0);
            }
        }
    }
    public static class Pair<K, V> {
        private final K element0;
        private final V element1;

        public Pair(K element0, V element1) {
            this.element0 = element0;
            this.element1 = element1;
        }

        public K getElement0() {
            return element0;
        }

        public V getElement1() {
            return element1;
        }

        @Override
        public int hashCode() {
            int hash = 5;
            hash = 29 * hash + Objects.hashCode(this.element0);
            hash = 29 * hash + Objects.hashCode(this.element1);
            return hash;
        }
        @Override
        public boolean equals(Object o) {
            if (o instanceof Pair<?, ?>) {
                Pair<?, ?> p = (Pair<?, ?>)o;
                return this.element0.equals(p.element0) &&
                       this.element1.equals(p.element1);
            }
            return false;
        }
    }
}
