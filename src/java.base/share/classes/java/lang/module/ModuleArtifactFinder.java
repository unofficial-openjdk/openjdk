/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

package java.lang.module;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A finder of module artifacts.
 *
 * <p> An important property is that a {@code ModuleArtifactFinder} admits to
 * at most one module with a given name. A {@code ModuleArtifactFinder} that
 * finds modules in sequence of directories for example, will locate the first
 * occurrence of a module and ignores other modules of that name that appear in
 * directories later in the sequence. </p>
 *
 * <p> Example usage: </p>
 *
 * <pre>{@code
 *     Path dir1, dir2, dir3;
 *
 *     ModuleArtifactFinder finder =
 *         ModuleArtifactFinder.ofDirectories(dir1, dir2, dir3);
 *
 *     ModuleArtifact artifact = finder.find("jdk.foo");
 * }</pre>
 *
 * @apiNote The eventual API will need to define how errors are handled, say
 * for example find lazily searching the module path and finding two modules of
 * the same name in the same directory.
 *
 * @since 1.9
 */

public interface ModuleArtifactFinder {

    /**
     * Finds a module artifact where the module has the given name.
     * Returns {@code null} if not found.
     *
     * <p> A {@code ModuleArtifactFinder} provides a consistent view of the
     * modules that it locates. If {@code find} is invoked several times to
     * locate the same module (by name) then it will return the same result
     * each time. If a module is located then it is guaranteed to be a member
     * of the set of modules returned by the {@link #allModules allModules}
     * method.
     */
    public ModuleArtifact find(String name);

    /**
     * Returns the set of all module artifacts that this finder can locate.
     *
     * <p> A {@code ModuleArtifactFinder} provides a consistent view of the
     * modules that it locates. If {@link #allModules allModules} is invoked
     * several times then it will return the same (equals) result each time.
     * For each {@code ModuleArtifact} element of the returned set then it is
     * guaranteed that that {@link #find find} will locate that {@code
     * ModuleArtifact} if invoked with the module name.
     *
     * @apiNote This is important to have for methods such as {@link
     * Configuration#bind} that need to scan the module path to find
     * modules that provide a specific service.
     */
    public Set<ModuleArtifact> allModules();

    /**
     * Returns a module finder for modules that are linked into the run-time
     * image.
     *
     * @apiNote What about non-JDK modules that are linked into the run-time
     * image but are intended to be loaded by custom loaders. They are observable
     * but there should be way to restrict this so that they don't end up in the
     * boot layer. In that context, should this method be renamed to systemModules?
     *
     * @apiNote Need to decide if this method needs a permission check.
     */
    public static ModuleArtifactFinder installedModules() {
        if (InstalledModuleFinder.isModularImage()) {
            return new InstalledModuleFinder();
        } else {
            String home = System.getProperty("java.home");
            Path mlib = Paths.get(home, "modules");
            if (Files.isDirectory(mlib)) {
                return ofDirectories(mlib);
            } else {
                System.err.println("WARNING: " + mlib.toString() +
                        " not found or not a directory");
                return ofDirectories(new Path[0]);
            }
        }
    }

    /**
     * Creates a finder that locates modules on the file system by searching a
     * sequence of directories for module artifacts. This method will locate
     * modules that are packaged as modular JAR files or modules that are
     * exploded on the file system. It may also locate modules that are
     * packaged in other implementation specific formats.
     *
     * @apiNote This method needs to define how the returned finder handles
     * I/O and other errors (a ClassFormatError when parsing a module-info.class
     * for example).
     */
    public static ModuleArtifactFinder ofDirectories(Path... dirs) {
        return new ModulePath(dirs);
    }

    /**
     * Returns a finder that is the equivalent to concatenating the given
     * finders. The resulting finder will locate modules artifacts using {@code
     * first}; if not found then it will attempt to locate module artifacts
     * using {@code second}.
     */
    public static ModuleArtifactFinder concat(ModuleArtifactFinder first,
                                              ModuleArtifactFinder second)
    {
        return new ModuleArtifactFinder() {
            Set<ModuleArtifact> allModules;

            @Override
            public ModuleArtifact find(String name) {
                ModuleArtifact m = first.find(name);
                if (m == null)
                    m = second.find(name);
                return m;
            }
            @Override
            public Set<ModuleArtifact> allModules() {
                if (allModules == null) {
                    allModules = Stream.concat(first.allModules().stream(),
                                               second.allModules().stream())
                                       .map(a -> a.descriptor().name())
                                       .distinct()
                                       .map(this::find)
                                       .collect(Collectors.toSet());
                }
                return allModules;
            }
        };
    }

    /**
     * Returns a <em>null</em> finder. The null finder does not find any
     * modules.
     *
     * @apiNote This is useful when using methods such as {@link
     * Configuration#resolve resolve} where two finders are specified.
     */
    public static ModuleArtifactFinder nullFinder() {
        return new ModuleArtifactFinder() {
            @Override public ModuleArtifact find(String name) {
                return null;
            }
            @Override public Set<ModuleArtifact> allModules() {
                return Collections.emptySet();
            }
        };
    }
}