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

package build.tools.module;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * GenJdepsModulesXml augments the input modules.xml file(s)
 * to include the module membership from the given path to
 * the JDK exploded image.  The output file is used by jdeps
 * to analyze dependencies and enforce module boundaries.
 *
 * The input modules.xml file defines the modular structure of
 * the JDK as described in JEP 200: The Modular JDK
 * (http://openjdk.java.net/jeps/200).
 *
 * $ java build.tools.module.GenJdepsModulesXml \
 *        -o com/sun/tools/jdeps/resources/modules.xml \
 *        -mp $OUTPUTDIR/modules \
 *        top/modules.xml
 */
public final class GenJdepsModulesXml {
    private final static String USAGE =
        "Usage: GenJdepsModulesXml -o <output file> path-to-modules-xml";

    public static void main(String[] args) throws Exception {
        Path outfile = null;
        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            if (arg.equals("-o")) {
                outfile = Paths.get(args[i + 1]);
                i = i + 2;
            } else {
                break;
            }
        }
        if (outfile == null || i >= args.length) {
            System.err.println(USAGE);
            System.exit(-1);
        }

        Set<Module> modules = new HashSet<>();
        for (; i < args.length; i++) {
            Path p = Paths.get(args[i]);
            modules.addAll(ModulesXmlReader.readModules(p));
        }

        Files.createDirectories(outfile.getParent());
        ModulesXmlWriter.writeModules(modules, outfile);
    }
}
