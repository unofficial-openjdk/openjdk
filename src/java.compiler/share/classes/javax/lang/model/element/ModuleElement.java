/*
 * Copyright (c) 2005, 2015, Oracle and/or its affiliates. All rights reserved.
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

package javax.lang.model.element;

import java.util.List;

/**
 * Represents a module program element.  Provides access to information
 * about the module and its members.
 *
 * @see javax.lang.model.util.Elements#getModuleOf
 * @since 9
 */  // TODO: add @jls to module section
public interface ModuleElement extends Element, QualifiedNameable {

    /**
     * Returns the fully qualified name of this module.
     *
     * @return the qualified name of this module, or an
     * empty name if this is an unnamed module
     */
    @Override
    Name getQualifiedName();

    /**
     * Returns the simple name of this module.  For an unnamed
     * module, an empty name is returned.
     *
     * @return the simple name of this module or an empty name if
     * this is an unnamed package
     */
    @Override
    Name getSimpleName();

    /**
     * Returns the packages within this module.
     * @return the packages within this module
     */
    @Override
    List<? extends Element> getEnclosedElements();

    /**
     * Returns {@code true} if this is an unnamed module and {@code
     * false} otherwise.
     *
     * @return {@code true} if this is an unnamed module and {@code
     * false} otherwise
     */ // TODO: add @jls to unnamed module section
    boolean isUnnamed();

    /**
     * Returns {@code null} since a module is not enclosed by another
     * element.
     *
     * @return {@code null}
     */
    @Override
    Element getEnclosingElement();

    List<? extends Directive> getDirectives();

    /**
     * @since 9
     */
    enum DirectiveKind {
        /** A "requires [public] module-name" directive. */
        REQUIRES,
        /** An "exports package-name [to module-name-list]" directive. */
        EXPORTS,
        /** A "uses service-name" directive. */
        USES,
        /** A "provides service-name with implementation-name" directive. */
        PROVIDES
    };

    /**
     * @since 9
     */
    interface Directive {
        DirectiveKind getKind();
    }

    /**
     * A dependency of a module.
     * @since 9
     */
    interface RequiresDirective extends Directive {
        /**
         * Returns whether or not this is a public dependency.
         * @return whether or not this is a public dependency
         */
        boolean isPublic();

        /**
         * Returns the module that is required
         * @return the module that is required
         */
        ModuleElement getDependency();
    }

//    /**
//     * Returns the list of dependencies of this module.
//     * @return the list of dependencies of this module
//     */
//    List<? extends RequiresDirective> getRequiresDirectives();

    /**
     * An exported package of a module.
     * @since 9
     */
    interface ExportsDirective extends Directive {
        /**
         * Returns the package being exported.
         * @return the package being exported
         */
        PackageElement getPackage();

        /**
         * Returns the specific modules to which the package is being exported,
         * or null, if the package is exported to all modules which
         * have readability to this module.
         * @return the specific modules to which the package is being exported
         */
        List<? extends ModuleElement> getTargetModules();
    }

//    /**
//     * Returns the list of exports made available by this module.
//     * @return the list of exports made available by this module
//     */
//    List<? extends ExportsDirective> getExportsDirectives();

    /**
     * An implementation of a service provided by a module.
     * @since 9
     */
    interface ProvidesDirective extends Directive {
        /**
         * Returns the service being provided.
         * @return the service being provided
         */
        TypeElement getService();

        /**
         * Returns the implementation of the service being provided.
         * @return the implementation of the service being provided
         */
        TypeElement getImplementation();
    }

//    /**
//     * Returns the list of services provided by this module.
//     * @return the list of services provided by this module
//     */
//    List<? extends ProvidesDirective> getProvidesDirectives();

    /**
     * A reference to a service used by a module.
     * @since 9
     */
    interface UsesDirective extends Directive {
        /**
         * Returns the service that is used.
         * @return the service that is used
         */
        TypeElement getService();
    }

//    /**
//     * Returns the list of services used by this module.
//     * @return the list of services used by this module
//     */
//    List<? extends UsesDirective> getUsesDirectives();
}
