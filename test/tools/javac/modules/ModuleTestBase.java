/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

import java.io.IOException;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for module tests.
 */
class ModuleTestBase {
    protected ToolBox tb;
    protected PrintStream out;
    private int errors;

    /** Marker annotation for test methods to be invoked by runTests. */
    @Retention(RetentionPolicy.RUNTIME)
    @interface Test { }

    /**
     * Run all methods annotated with @Test, and throw an exception if any
     * errors are reported..
     * @throws Exception if any errors occurred
     */
    void runTests() throws Exception {
        if (tb == null)
            tb = new ToolBox();
        out = System.err;

        for (Method m: getClass().getDeclaredMethods()) {
            Annotation a = m.getAnnotation(Test.class);
            if (a != null) {
                try {
                    out.println("Running test " + m.getName());
                    Path baseDir = Paths.get(m.getName());
                    m.invoke(this, new Object[] { baseDir });
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    error("Exception: " + e.getCause());
                    cause.printStackTrace(out);
                }
                out.println();
            }
        }
        if (errors > 0)
            throw new Exception(errors + " errors occurred");
    }

    // move to ToolBox?
    // change returntyp to List<Path> -- means updating ToolBox methods
    Path[] findJavaFiles(Path p) throws IOException {
        List<Path> list = new ArrayList<>();
        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (file.getFileName().toString().endsWith(".java")) {
                    list.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

        });
        return list.toArray(new Path[list.size()]);
    }

    void error(String message) {
        out.println("Error: " + message);
        errors++;
    }
}
