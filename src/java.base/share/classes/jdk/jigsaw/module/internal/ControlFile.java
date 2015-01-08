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

package jdk.jigsaw.module.internal;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Properties;

/**
 * Represents a Debian-like <em>control file</em> for packaging information
 * that augments a module descriptor.
 *
 * The control file is temporary until the representation of the extended
 * module descriptor is decided.
 */

public final class ControlFile {
    private final Properties props;

    public static String CONTROL_FILE = "module/control";

    /**
     * Returns an empty {@code ControlFile}.
     */
    public ControlFile() {
        this.props = new Properties();
    }

    /**
     * Parses the input stream to create a {@code ControlFile}.
     */
    public static ControlFile parse(InputStream in) throws IOException {
        ControlFile controlFile = new ControlFile();
        controlFile.props.load(in);
        return controlFile;
    }

    /**
     * Writes the control file to the given output stream.
     */
    public ControlFile write(OutputStream out) throws IOException {
        props.store(out, "");
        return this;
    }

    /**
     * Returns the module name or {@code null} if not present. The module name
     * should match the module name in the module descriptor.
     */
    public String name() {
        return (String) props.get("Name");
    }

    public ControlFile name(String name) {
        props.put("Name", name);
        return this;
    }

    /**
     * Returns the module version or {@code null} if not present.
     */
    public String version() {
        return (String) props.get("Version");
    }

    /**
     * Sets the module version.
     */
    public ControlFile version(String version) {
        props.put("Version", version);
        return this;
    }

    /**
     * Returns the module main class or {@code null} if not present.
     */
    public String mainClass() {
        return (String) props.get("Main-Class");
    }

    /**
     * Sets the module main class.
     */
    public ControlFile mainClass(String mainClass) {
        props.put("Main-Class", mainClass);
        return this;
    }

    /**
     * Returns a comma delimited string of the version constraints, {@code null} if
     * not present.
     */
    public String depends() {
        return (String) props.get("Depends");
    }

    /**
     * Sets the version constraints without any validation.
     */
    public ControlFile depends(String depends) {
        props.put("Depends", depends);
        return this;
    }
}
