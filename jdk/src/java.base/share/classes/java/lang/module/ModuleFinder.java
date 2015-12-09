/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A finder of module references.
 *
 * <p> A {@code ModuleFinder} admits to at most one module with a given name.
 * A {@code ModuleFinder} that finds modules in a sequence of directories for
 * example, will locate the first occurrence of a module and ignores other
 * modules of that name that appear in directories later in the sequence. </p>
 *
 * <p> Example usage: </p>
 *
 * <pre>{@code
 *     Path dir1, dir2, dir3;
 *
 *     ModuleFinder finder = ModuleFinder.of(dir1, dir2, dir3);
 *
 *     Optional<ModuleReference> result = finder.find("jdk.foo");
 *     if (result.isPresent()) { ... }
 *
 * }</pre>
 *
 * <p> The {@link #find(String) find} and {@link #findAll() findAll} methods
 * defined here can fail for several reasons. These include include I/O errors,
 * errors detected parsing a module descriptor ({@code module-info.class}), or
 * in the case of {@code ModuleFinder} based on a sequence of directories,
 * that two or more versions of the module are found in the same directory.
 * When an error is detected then these methods throw {@link FindException
 * FindException} with a {@link Throwable#getCause cause} where appropriate.
 * The behavior of a {@code ModuleFinder} after a {@code FindException} is
 * thrown is undefined. It is recommended that the {@code ModuleFinder} be
 * discarded after an exception is thrown. </p>
 *
 * <p> A {@code ModuleFinder} is not required to be thread safe. </p>
 *
 * @since 9
 */

public interface ModuleFinder {

    /**
     * Finds a reference to a module of a given name.
     *
     * <p> A {@code ModuleFinder} provides a consistent view of the
     * modules that it locates. If {@code find} is invoked several times to
     * locate the same module (by name) then it will return the same result
     * each time. If a module is located then it is guaranteed to be a member
     * of the set of modules returned by the {@link #findAll() findAll}
     * method. </p>
     *
     * @param  name
     *         The name of the module to find
     *
     * @return A reference to a module with the given name or an empty
     *         {@code Optional} if not found
     *
     * @throws FindException
     *         If an error occurs finding the module
     *
     * @throws SecurityException
     *         If denied by the security manager
     */
    public Optional<ModuleReference> find(String name);

    /**
     * Returns the set of all module references that this finder can locate.
     *
     * <p> A {@code ModuleFinder} provides a consistent view of the
     * modules that it locates. If {@link #findAll() findAll} is invoked
     * several times then it will return the same (equals) result each time.
     * For each {@code ModuleReference} element of the returned set then it is
     * guaranteed that that {@link #find find} will locate that {@code
     * ModuleReference} if invoked with the module name. </p>
     *
     * @apiNote This is important to have for methods such as {@link
     * Configuration#bind} that need to scan the module path to find
     * modules that provide a specific service.
     *
     * @return The set of all module references that this finder locates
     *
     * @throws FindException
     *         If an error occurs finding all modules
     *
     * @throws SecurityException
     *         If denied by the security manager
     */
    public Set<ModuleReference> findAll();

    /**
     * Returns a module finder for modules that are linked into the run-time
     * image.
     *
     * @apiNote What about non-JDK modules that are linked into the run-time
     * image but are intended to be loaded by custom loaders. They are observable
     * but there should be way to restrict this so that they don't end up in the
     * boot layer. In that context, should this method be renamed to systemModules?
     * Also need to decide if this method needs a permission check.
     *
     * @return A {@code ModuleFinder} that locate all modules in the
     *         run-time image
     */
    public static ModuleFinder ofInstalled() {
        String home = System.getProperty("java.home");
        Path libModules = Paths.get(home, "lib", "modules");
        if (Files.isDirectory(libModules)) {
            return new InstalledModuleFinder();
        } else {
            Path mlib = Paths.get(home, "modules");
            if (Files.isDirectory(mlib)) {
                return of(mlib);
            } else {
                throw new InternalError("Unable to detect the run-time image");
            }
        }
    }

    /**
     * Creates a finder that locates modules on the file system by searching a
     * sequence of zero or more directories for module references. This method
     * will locate modules that are packaged as modular JAR files or modules
     * that are exploded on the file system. It may also locate modules that
     * are packaged in other implementation specific formats.
     *
     * <p> Finders created by this method are lazy and do not eagerly check
     * that the given file paths are directories. A call to the {@code find}
     * or {@code findAll} methods may fail as a result. </p>
     *
     * @param dirs
     *        The possibly-empty array of directories
     *
     * @return A {@code ModuleFinder} that locates modules on the file system
     */
    public static ModuleFinder of(Path... dirs) {
        return new ModulePath(dirs);
    }

    /**
     * Returns a finder that is the equivalent to concatenating the given
     * finders. The resulting finder will locate modules references using
     * {@code first}; if not found then it will attempt to locate module
     * references using {@code second}.
     *
     * <p> The {@link #findAll() findAll} method of the resulting module finder
     * will locates all modules located by the first module finder. It will
     * also locate all modules located by the second module finder that are not
     * located by the first module finder. </p>
     *
     * @param first
     *        The first module finder
     * @param second
     *        The second module finder
     *
     * @return A {@code ModuleFinder} that concatenates two module finders
     */
    public static ModuleFinder concat(ModuleFinder first, ModuleFinder second) {
        Objects.requireNonNull(first);
        Objects.requireNonNull(second);

        return new ModuleFinder() {
            Set<ModuleReference> allModules;

            @Override
            public Optional<ModuleReference> find(String name) {
                Optional<ModuleReference> om = first.find(name);
                if (!om.isPresent())
                    om = second.find(name);
                return om;
            }
            @Override
            public Set<ModuleReference> findAll() {
                if (allModules == null) {
                    allModules = Stream.concat(first.findAll().stream(),
                                               second.findAll().stream())
                                       .map(a -> a.descriptor().name())
                                       .distinct()
                                       .map(this::find)
                                       .map(Optional::get)
                                       .collect(Collectors.toSet());
                }
                return allModules;
            }
        };
    }

    /**
     * Returns an empty finder.  The empty finder does not find any modules.
     *
     * @apiNote This is useful when using methods such as {@link
     * Configuration#resolve resolve} where two finders are specified.
     *
     * @return A {@code ModuleFinder} that does not find any modules
     */
    public static ModuleFinder empty() {
        // an alternative implementation of ModuleFinder.of()
        return new ModuleFinder() {
            @Override public Optional<ModuleReference> find(String name) {
                Objects.requireNonNull(name);
                return Optional.empty();
            }
            @Override public Set<ModuleReference> findAll() {
                return Collections.emptySet();
            }
        };
    }

}
