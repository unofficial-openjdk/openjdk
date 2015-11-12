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
package com.sun.tools.jdeps;

import com.sun.tools.classfile.AccessFlags;
import com.sun.tools.classfile.ClassFile;
import com.sun.tools.classfile.ConstantPoolException;
import com.sun.tools.classfile.Dependencies;
import com.sun.tools.classfile.Dependency;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public class DependencyFinder {
    /*
     * Dep Filter configured based on the input jdeps option
     * 1. -p and -regex to match target dependencies
     * 2. -filter:package to filter out same-package dependencies
     *
     * This filter is applied when jdeps parses the class files
     * and filtered dependencies are not stored in the Analyzer.
     *
     * -filter:archive is applied later in the Analyzer as the
     * containing archive of a target class may not be known until
     * the entire archive
     */
    static class DependencyFilter implements Dependency.Filter {
        final Dependency.Filter filter;
        final Pattern filterPattern;
        final boolean filterSamePackage;

        DependencyFilter(boolean filterSamePackage) {
            this((Dependency.Filter)null, null, filterSamePackage);
        }
        DependencyFilter(Pattern filterPattern, boolean filterSamePackage) {
            this((Dependency.Filter)null, filterPattern, filterSamePackage);
        }
        DependencyFilter(Pattern regex, Pattern filterPattern, boolean filterSamePackage) {
            this(Dependencies.getRegexFilter(regex), filterPattern, filterSamePackage);
        }

        DependencyFilter(Set<String> packageNames, Pattern filterPattern, boolean filterSamePackage) {
            this(Dependencies.getPackageFilter(packageNames, false), filterPattern, filterSamePackage);
        }
        private DependencyFilter(Dependency.Filter filter, Pattern filterPattern, boolean filterSamePackage) {
            this.filter = filter;
            this.filterPattern = filterPattern;
            this.filterSamePackage = filterSamePackage;
        }

        @Override
        public boolean accepts(Dependency d) {
            if (d.getOrigin().equals(d.getTarget())) {
                return false;
            }
            String pn = d.getTarget().getPackageName();
            if (filterSamePackage && d.getOrigin().getPackageName().equals(pn)) {
                return false;
            }

            if (filterPattern != null && filterPattern.matcher(pn).matches()) {
                return false;
            }

            return filter != null ? filter.accepts(d) : true;
        }
    }

    private final Pattern includePattern;
    private final List<String> roots = new ArrayList<>();
    private final List<Archive> initialArchives = new ArrayList<>();
    private final List<Archive> classpaths = new ArrayList<>();
    private final List<Module> systemModules;
    private final boolean compileTimeView;
    private final boolean includeSystemModules;

    DependencyFinder(Pattern includePattern,
                     boolean compileTimeView,
                     boolean includeSystemModules) {
        this.includePattern = includePattern;
        this.systemModules = ModulePath.getSystemModules();
        this.compileTimeView = compileTimeView;
        this.includeSystemModules = includeSystemModules;
    }

    /*
     * Adds a class name to the root set
     */
    void addRoot(String cn) {
        roots.add(cn);
    }

    /*
     * Adds an initial archive of the given path
     */
    Archive addArchive(Path path) {
        Archive archive = Archive.getInstance(path);
        addArchive(archive);
        return archive;
    }

    /*
     * Adds an initial archive
     */
    void addArchive(Archive archive) {
        Objects.requireNonNull(archive);
        initialArchives.add(archive);
    }

    /**
     * Add an archive specified in the classpath if it's not listed
     * in the initial archive list.
     */
    Archive addClassPathArchive(Path path) {
        Optional<Archive> archive = initialArchives.stream()
                .filter(a -> isSameFile(path, a.path()))
                .findAny();
        if (archive.isPresent())
            return archive.get();

        Archive cpArchive = Archive.getInstance(path);
        addClassPathArchive(cpArchive);
        return cpArchive;
    }

    /**
     * Add an archive specified in the classpath if it's not listed
     * in the initial archive list.
     */
    void addClassPathArchive(Archive archive) {
        Objects.requireNonNull(archive);
        classpaths.add(archive);
    }

    /**
     * Add an archive specified in the modulepath.
     */
    void addModuleArchive(Module m) {
        Objects.requireNonNull(m);
        classpaths.add(m);
    }

    List<Archive> initialArchives() {
        return initialArchives;
    }

    List<Archive> getArchives() {
        List<Archive> archives = new ArrayList<>(initialArchives);
        archives.addAll(classpaths);
        archives.addAll(systemModules);
        return Collections.unmodifiableList(archives);
    }

    /**
     * Finds dependencies
     * @param apiOnly  API only
     * @param maxDepth    depth of transitive dependency analysis; zero indicates
     * @throws IOException
     */
    void findDependencies(DependencyFilter dependencyFilter, boolean apiOnly, int maxDepth)
            throws IOException
    {
        Dependency.Finder finder =
                apiOnly ? Dependencies.getAPIFinder(AccessFlags.ACC_PROTECTED)
                        : Dependencies.getClassDependencyFinder();

        int depth = compileTimeView ? 1 :
                (maxDepth > 0 ? maxDepth : Integer.MAX_VALUE);

        List<Archive> archives = new ArrayList<>(initialArchives);
        if (includePattern != null || compileTimeView) {
            // start with all archives
            archives.addAll(classpaths);
        }

        if (compileTimeView && includeSystemModules) {
            archives.addAll(systemModules);
        }

        // We should probably avoid analyzing JDK modules.  JDK classes are not analyzed.
        // Instead, module dependences will be shown
        // if (compileTimeView) {
        //    archives.addAll(systemModules);
        // }

        // Work queue of names of classfiles to be searched.
        // Entries will be unique, and for classes that do not yet have
        // dependencies in the results map.
        Deque<String> deque = new LinkedList<>();
        Set<String> doneClasses = new HashSet<>();

        // get the immediate dependencies of the input files
        for (Archive a : archives) {
            for (ClassFile cf : a.reader().getClassFiles()) {
                String classFileName;
                try {
                    classFileName = cf.getName();
                } catch (ConstantPoolException e) {
                    throw new Dependencies.ClassFileError(e);
                }

                // tests if this class matches the -include or -apiOnly option if specified
                if (!matches(classFileName) || (apiOnly && !cf.access_flags.is(AccessFlags.ACC_PUBLIC))) {
                    continue;
                }

                if (!doneClasses.contains(classFileName)) {
                    doneClasses.add(classFileName);
                }

                for (Dependency d : finder.findDependencies(cf)) {
                    if (dependencyFilter.accepts(d)) {
                        String cn = d.getTarget().getName();
                        if (!doneClasses.contains(cn) && !deque.contains(cn)) {
                            deque.add(cn);
                        }
                        a.addClass(d.getOrigin(), d.getTarget());
                    } else {
                        // ensure that the parsed class is added the archive
                        a.addClass(d.getOrigin());
                    }
                }
            }
        }

        // add Archive for looking up classes from the classpath
        // for transitive dependency analysis

        Deque<String> unresolved = new LinkedList<>(this.roots);
        do {
            String name;
            while ((name = unresolved.poll()) != null) {
                if (doneClasses.contains(name)) {
                    continue;
                }
                ClassFile cf = null;
                for (Archive a : getArchives()) {
                    cf = a.reader().getClassFile(name);

                    if (cf != null) {
                        String classFileName;
                        try {
                            classFileName = cf.getName();
                        } catch (ConstantPoolException e) {
                            throw new Dependencies.ClassFileError(e);
                        }
                        if (!doneClasses.contains(classFileName)) {
                            // if name is a fully-qualified class name specified
                            // from command-line, this class might already be parsed
                            doneClasses.add(classFileName);
                            for (Dependency d : finder.findDependencies(cf)) {
                                if (depth == 0) {
                                    // ignore the dependency
                                    a.addClass(d.getOrigin());
                                    break;
                                } else if (dependencyFilter.accepts(d) &&
                                              // skip analyze transitive dependency on JDK class if needed
                                              (includeSystemModules || !isSystemModule(a))) {
                                    a.addClass(d.getOrigin(), d.getTarget());
                                    String cn = d.getTarget().getName();
                                    if (!doneClasses.contains(cn) && !deque.contains(cn)) {
                                        deque.add(cn);
                                    }
                                } else {
                                    // ensure that the parsed class is added the archive
                                    a.addClass(d.getOrigin());
                                }
                            }
                        }
                        break;
                    }
                }
                if (cf == null) {
                    doneClasses.add(name);
                }
            }
            unresolved = deque;
            deque = new LinkedList<>();
        } while (!unresolved.isEmpty() && depth-- > 0);
    }

    private boolean isSystemModule(Archive archive) {
        if (Module.class.isInstance(archive)) {
            return systemModules.contains((Module) archive);
        }
        return false;
    }

    /**
     * Tests if the given class matches the pattern given in the -include option
     */
    private boolean matches(String classname) {
        if (includePattern != null) {
            return includePattern.matcher(classname.replace('/', '.')).matches();
        } else {
            return true;
        }
    }

    private boolean isSameFile(Path p1, Path p2) {
        try {
            return Files.isSameFile(p1, p2);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
