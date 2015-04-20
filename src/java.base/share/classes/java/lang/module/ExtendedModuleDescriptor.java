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

package java.lang.module;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import jdk.jigsaw.module.internal.Hasher.DependencyHashes;

/**
 * An extended module descriptor.
 *
 * @apiNote This class will eventually define methods to support OS/architecture,
 * license, maintainer email, and other meta data.
 */

public class ExtendedModuleDescriptor
    extends ModuleDescriptor
{
    private final ModuleId id;
    private final String mainClass;
    private final DependencyHashes hashes;

    ExtendedModuleDescriptor(ModuleId id,
                             String mainClass,
                             DependencyHashes hashes,
                             Set<ModuleDependence> moduleDeps,
                             Set<String> serviceDeps,
                             Set<ModuleExport> exports,
                             Map<String, Set<String>> services)
    {
        super(id.name(), moduleDeps, serviceDeps, exports, services);
        this.id = id;
        this.mainClass = mainClass;
        this.hashes = hashes;
    }

    /**
     * Returns the module identifier.
     */
    public ModuleId id() {
        return id;
    }

    /**
     * Returns the module's main class or {@code null} if there isn't
     * a main class.
     */
    public String mainClass() {
        return mainClass;
    }

    /**
     * Returns the object with the hashes of the dependences or {@code null}
     * if there are no hashes recorded.
     */
    DependencyHashes hashes() {
        return hashes;
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
        private String mainClass;

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

        public Builder uses(String s) {
            super.uses(s);
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

        public Builder mainClass(String mainClass) {
            this.mainClass = mainClass;
            return this;
        }

        /**
         * Builds an {@code ExtendedModuleDescriptor} from the components.
         *
         * @apiNote Hashes are dropped
         */
        public ExtendedModuleDescriptor build() {
            return new ExtendedModuleDescriptor(id,
                                                mainClass,
                                                null,
                                                moduleDeps,
                                                serviceDeps,
                                                exports,
                                                services);
        }
    }
}
