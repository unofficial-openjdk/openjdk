/*
 * Copyright (c) 2009, 2015, Oracle and/or its affiliates. All rights reserved.
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
import java.util.Objects;
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

    private final Version version;
    private final String mainClass;
    private final DependencyHashes hashes;

    ExtendedModuleDescriptor(String name,
                             Version version,
                             String mainClass,
                             DependencyHashes hashes,
                             Set<ModuleDependence> moduleDeps,
                             Set<String> serviceDeps,
                             Set<ModuleExport> exports,
                             Map<String, Set<String>> services)
    {
        super(name, moduleDeps, serviceDeps, exports, services);
        this.version = version;
        this.mainClass = mainClass;
        this.hashes = hashes;
    }

    /**
     * Returns this module's version, or {@code null} if it does not have a
     * version.
     */
    public Version version() {
        return version;
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
    public int compareTo(ModuleDescriptor md) {
        if (!(md instanceof ExtendedModuleDescriptor))
            return -1;
        ExtendedModuleDescriptor that = (ExtendedModuleDescriptor)md;
        int c = name().compareTo(that.name());
        if (c != 0) return c;
        if (version == null) {
            if (that.version == null)
                return 0;
            return -1;
        }
        if (that.version == null)
            return +1;
        return version.compareTo(that.version);
    }

    /**
     * Returns a string containing this module's name and, if present, its
     * version.
     */
    public String toNameAndVersion() {
        if (version == null)
            return name();
        return name() + "@" + version.toString();
    }

    @Override
    public boolean equals(Object ob) {
        if (!(ob instanceof ExtendedModuleDescriptor))
            return false;
        ExtendedModuleDescriptor that = (ExtendedModuleDescriptor)ob;
        return (super.equals(that)
                && Objects.equals(version, that.version)
                && Objects.equals(mainClass, that.mainClass)
                && Objects.equals(hashes, that.hashes));
    }

    @Override
    public int hashCode() {
        return (super.hashCode() * 43
                + Objects.hashCode(version) * 43
                + Objects.hashCode(mainClass) * 43
                + Objects.hashCode(hashes));
    }

    /**
     * A builder used for building {@code ExtendedModuleDescriptor} objects.
     */
    public static class Builder extends ModuleDescriptor.Builder {

        private final String name;
        private final Version version;
        private String mainClass;

        /**
         * Initializes a new builder.
         */
        public Builder(String name) {
            this.name = ModuleName.check(name);
            this.version = null;
        }

        public Builder(String name, Version version) {
            this.name = ModuleName.check(name);
            this.version = version;
        }

        public Builder(String name, String version) {
            this(name, Version.parse(version));
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
            return new ExtendedModuleDescriptor(name,
                                                version,
                                                mainClass,
                                                null,
                                                moduleDeps,
                                                serviceDeps,
                                                exports,
                                                services);
        }
    }

}
