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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * An extended module descriptor.
 *
 * @apiNote This class will eventually define methods to support versions
 * and version constraints, OS/architecture, maybe entry points and maybe
 * a hash of the module descriptor and its contents.
 */

@SuppressWarnings("serial")             // serialVersionUID intentionally omitted
public class ExtendedModuleDescriptor
    extends ModuleDescriptor
{
    private final ModuleId id;

    ExtendedModuleDescriptor(ModuleId id,
                             Set<ModuleDependence> moduleDeps,
                             Set<ServiceDependence> serviceDeps,
                             Set<ModuleExport> exports,
                             Map<String, Set<String>> services)
    {
        super(id.name(), moduleDeps, serviceDeps, exports, services);
        this.id = id;
    }

    /**
     * The module identifier.
     */
    public ModuleId id() {
        return id;
    }

    @Override
    public int compareTo(ModuleDescriptor that) {
        if (that instanceof ExtendedModuleDescriptor)
            return this.id.compareTo(((ExtendedModuleDescriptor)that).id);
        return -1;
    }

    @Override
    public boolean equals(Object ob) {
        if (!(ob instanceof ExtendedModuleDescriptor))
            return false;
        ExtendedModuleDescriptor that = (ExtendedModuleDescriptor)ob;
        return (id.equals(that.id) && super.equals(that));
    }

    @Override
    public int hashCode() {
        return id.hashCode() + 43 * super.hashCode();
    }

    /**
     * A builder used for building {@code ExtendedModuleDescriptor} objects.
     */
    public static class Builder extends ModuleDescriptor.Builder {
        private final ModuleId id;

        /**
         * Initializes a new builder.
         */
        public Builder(String id) {
            this.id = ModuleId.parse(id);
        }

        /**
         * Initializes a new builder.
         */
        public Builder(ModuleId id) {
            this.id = id;
        }

        public Builder requires(ModuleDependence md) {
            super.requires(md);
            return this;
        }

        public Builder requires(ServiceDependence sd) {
            super.requires(sd);
            return this;
        }

        public Builder export(ModuleExport e) {
            super.export(e);
            return this;
        }

        public Builder export(String p) {
            super.export(p);
            return this;
        }

        public Builder export(String p, String m) {
            super.export(p, m);
            return this;
        }

        public Builder service(String s, String p) {
            super.service(s, p);
            return this;
        }

        /**
         * Builds an {@code ExtendedModuleDescriptor} from the components.
         */
        public ExtendedModuleDescriptor build() {
            return new ExtendedModuleDescriptor(id,
                                                moduleDeps,
                                                serviceDeps,
                                                exports,
                                                services);
        }
    }
}
