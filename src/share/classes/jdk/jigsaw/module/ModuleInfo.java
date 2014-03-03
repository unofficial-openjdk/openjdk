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

package jdk.jigsaw.module;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import jdk.jigsaw.module.ViewDependence.Modifier;

/**
 * Information about a module, as found in a {@code module-info.java} source
 * file or a {@code module-info.class} class file.
 *
 * For now this class treats the module-info.class as a properties file. This
 * is for testing purposes only and will be replaced with a class file reader
 * once javac is compiling module-info source files.
 */
public final class ModuleInfo {
    private final Properties props;
    private ModuleInfo(Properties props) {
        this.props = props;
    }

    /**
     * Reads a {@code module-info.class} from the given input stream.
     */
    public static ModuleInfo read(InputStream in) throws IOException {
        Properties props = new Properties();
        props.load(in);
        ModuleInfo mi = new ModuleInfo(props);
        if (mi.name() == null)
            throw new IOException("Module information incomplete, no module name");
        return mi;
    }

    /**
     * Returns the module name.
     */
    public String name() {
        return props.getProperty("name");
    }

    /**
     * Returns the set of the module names that are required. The returned set does
     * not include the names of the modules that are required with the
     * {@code ACC_PUBLIC} flag set.
     */
    public Set<String> requires() {
        return split("requires");
    }

    /**
     *  Returns the set of the module names that are required with the
     *  {@code ACC_PUBLIC} flag set.
     */
    public Set<String> requiresPublic() {
        return split("requires-public");
    }

    /**
     * Returns the set of package names that are exported. The returned set does
     * the package names that are exported so that they can only be accessed by
     * particular modules.
     */
    public Set<String> exports() {
        return split("exports");
    }

    /**
     * Creates a module definition from the module information and the given
     * content.
     */
    public Module makeModule(Iterable<String> packages) {
        View.Builder viewBuilder = new View.Builder().id(name());
        exports().forEach(export -> viewBuilder.export(export));

        View mainView = viewBuilder.build();
        Module.Builder builder = new Module.Builder().main(mainView);

        // contents
        packages.forEach(pkg -> builder.include(pkg));

        // requires
        Set<String> requires = requires();
        if (requires != null) {
            requires.forEach( dn ->
                builder.requires(new ViewDependence(null, ViewIdQuery.parse(dn))) );
        }
        builder.requires(new ViewDependence(null, ViewIdQuery.parse("java.base")));

        // requires public
        Set<String> requiresPublic = requiresPublic();
        if (requiresPublic != null) {
            Set<Modifier> mods = EnumSet.of(Modifier.PUBLIC);
            requiresPublic.forEach( dn ->
                builder.requires(new ViewDependence(mods, ViewIdQuery.parse(dn))));
        }

        // TBD, need qualified exports and permits

        return builder.build();
    }

    private Set<String> split(String prop) {
        String values = props.getProperty(prop);
        if (values == null) {
            return Collections.emptySet();
        } else {
            Set<String> result = new HashSet<>();
            for (String value: values.split(",")) {
                result.add(value.trim());
            }
            return result;
        }
    }
}
