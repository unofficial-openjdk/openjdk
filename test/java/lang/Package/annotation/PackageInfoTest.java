/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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

/*
 * @test
 * @summary Basic test of package-info in named module and duplicate
 *          package-info in unnamed module
 * @compile package-info.java PackageInfoTest.java
 *          org/w3c/dom/css/Fake.java org/w3c/dom/css/FakePackage.java
 *          org/w3c/dom/css/package-info.java
 * @run testng p.PackageInfoTest
 */

package p;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import static org.testng.Assert.*;

public class PackageInfoTest {
    @DataProvider(name = "jdkClasses")
    public Object[][] jdkClasses() {
        return new Object[][] {
                { java.awt.Button.class,                        0 },
                { java.lang.Object.class,                       0 },
                { java.lang.management.ManagementFactory.class, 0 },
                { com.sun.tools.attach.VirtualMachine.class,    1 },
                { com.sun.jdi.Accessible.class,                 1 },
                { org.w3c.dom.css.CSSRule.class,                0 },
        };
    }

    @Test(dataProvider = "jdkClasses")
    public void testPackageInfo(Class<?> type, int numAnnotations) {
        Package pkg = type.getPackage();
        assertTrue(pkg.isSealed());
        assertTrue(pkg.getDeclaredAnnotations().length == numAnnotations);
        if (numAnnotations > 0) {
            assertTrue(pkg.isAnnotationPresent(jdk.Exported.class));
        } else {
            assertFalse(pkg.isAnnotationPresent(jdk.Exported.class));
        }
    }

    @DataProvider(name = "classpathClasses")
    public Object[][] cpClasses() {
        return new Object[][] {
                { p.PackageInfoTest.class, Deprecated.class},
                { org.w3c.dom.css.Fake.class, org.w3c.dom.css.FakePackage.class},
        };
    }

    @Test(dataProvider = "classpathClasses")
    public void testClassPathPackage(Class<?> type, Class<? extends Annotation> annType) {
        Package pkg = type.getPackage();
        assertTrue(pkg.isSealed() == false);
        assertTrue(pkg.isAnnotationPresent(annType));
    }

    static final String[] otherClasses = new String[] {
            "p/package-info.class",
            "p/Duplicate.class",
            "p/Bar.class"
    };

    @Test
    public void testDuplicatePackage() throws Exception {
        // a custom class loader loading another package p annotated with @Duplicate
        Path classes = Paths.get(System.getProperty("test.classes", "."), "tmp");
        Files.createDirectories(classes);
        URLClassLoader loader = new URLClassLoader(new URL[] { classes.toUri().toURL() });

        // clean up before compiling classes
        Arrays.stream(otherClasses)
                .forEach(c -> {
                    try {
                        Files.deleteIfExists(classes.resolve(c));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

        Path src = Paths.get(System.getProperty("test.src", "."), "src", "p");
        compile(classes,
                src.resolve("package-info.java"),
                src.resolve("Duplicate.java"),
                src.resolve("Bar.java"));

        // verify if classes are present
        Arrays.stream(otherClasses)
                .forEach(c -> {
                if (Files.notExists(classes.resolve(c))) {
                    throw new RuntimeException(c + " not exist");
                }
        });

        Class<?> c = Class.forName("p.Bar", true, loader);
        assertTrue(c.getClassLoader() == loader);
        assertTrue(this.getClass().getClassLoader() != loader);

        // package p defined by the custom class loader
        Package pkg = c.getPackage();
        assertTrue(pkg.getName().equals("p"));
        assertTrue(pkg.isSealed() == false);

        // package p defined by the application class loader
        Package p = this.getClass().getPackage();
        assertTrue(p.getName().equals("p"));
        assertTrue(p != pkg);

        Arrays.stream(pkg.getDeclaredAnnotations())
              .forEach(ann -> System.out.format("%s @%s%n", pkg.getName(), ann));

        Arrays.stream(p.getDeclaredAnnotations())
              .forEach(ann -> System.out.format("%s @%s%n", p.getName(), ann));

        // local package p defined by loader
        Class<? extends Annotation> ann =
            (Class<? extends Annotation>)Class.forName("p.Duplicate", false, loader);
        assertTrue(pkg.isAnnotationPresent(ann));
    }

    private void compile(Path classOutDir, Path... files)
            throws IOException
    {
        System.err.println("compile...");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> fileObjects =
                fm.getJavaFileObjectsFromPaths(Arrays.asList(files));

            List<String> options = new ArrayList<>();
            if (classOutDir != null) {
                options.add("-d");
                options.add(classOutDir.toString());
            }
            options.add("-cp");
            options.add(System.getProperty("test.classes", "."));

            JavaCompiler.CompilationTask task =
                compiler.getTask(null, fm, null, options, null, fileObjects);
            if (!task.call())
                throw new AssertionError("compilation failed");
        }
    }

}
