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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import tests.JImageGenerator;
import tests.JImageValidator;

/**
 * JLink tests helper.
 */
public class Helper {

    private final Map<String, List<String>> moduleClassDependencies
            = new HashMap<>();
    private final Map<String, List<String>> moduleDependencies
            = new HashMap<>();

    private final List<String> bootClasses = new ArrayList<>();

    private final JImageGenerator generator;
    private static FileSystem fs;

    public static Helper newHelper() throws Exception {
        try {
            fs = FileSystems.getFileSystem(URI.create("jrt:/"));
        } catch (ProviderNotFoundException | FileSystemNotFoundException e) {
            System.out.println("Not an image build, test skipped.");
            return null;
        }

        File jdkHome = new File(System.getProperty("test.jdk"));
        // JPRT not yet ready for jmods
        if (JImageGenerator.getJModsDir(jdkHome) == null) {
            System.err.println("Test not run, NO jmods directory");
            return null;
        }
        return new Helper(fs, jdkHome);
    }

    private Helper(FileSystem fs, File jdkHome) throws Exception {
        Consumer<Path> c = (p) -> {
            // take only the .class resources.
            if (Files.isRegularFile(p) && p.toString().endsWith(".class")
                    && !p.toString().endsWith("module-info.class")) {
                String loc = p.toString().substring("/modules".length());
                bootClasses.add(loc);
            }
        };

        Path javabase = fs.getPath("/modules/java.base");
        try (Stream<Path> stream = Files.walk(javabase)) {
            stream.forEach(c);
        }

        if (bootClasses.isEmpty()) {
            throw new RuntimeException("No boot class to check against");
        }

        generator = new JImageGenerator(new File("."), jdkHome);

        generateJModule("leaf1");
        generateJModule("leaf2");
        generateJModule("leaf3");

        generateJarModule("leaf4");
        generateJarModule("leaf5");

        generateJarModule("composite1", "leaf1", "leaf2", "leaf4");
        generateJModule("composite2", "composite1", "leaf3", "leaf5",
                "java.management");
    }

    public final void generateJModule(String module,
            String... dependencies)
            throws Exception {
        List<String> deps = new ArrayList<>();
        for (String d : dependencies) {
            deps.add(d);
        }
        moduleDependencies.put(module, deps);
        generator.generateJModule(module, getClasses(module), dependencies);
    }

    public File generateImage(String[] options, String module) throws Exception {
        return generator.generateImage(options, module);
    }

    public final void generateJarModule(String module,
            String... dependencies)
            throws Exception {
        List<String> deps = new ArrayList<>();
        for (String d : dependencies) {
            deps.add(d);
        }
        moduleDependencies.put(module, deps);
        generator.generateJarModule(module, getClasses(module), dependencies);
    }

    public JImageGenerator getGenerator() {
        return generator;
    }

    private String[] getClasses(String module) {
        String[] classes = {module + ".Main", module + ".com.foo.bar.X"};
        List<String> appClasses = new ArrayList<>();
        for (String clazz : toLocation(module, classes)) {
            appClasses.add(clazz);
        }
        String moduleClazz = toLocation(module, "module-info");
        appClasses.add(moduleClazz);
        moduleClassDependencies.put(module, appClasses);
        return classes;
    }

    private static String toLocation(String module, String className) {
        return "/" + module + "/" + className.replaceAll("\\.", "/") + ".class";
    }

    public static List<String> toLocation(String module, String[] classNames) {
        List<String> classes = new ArrayList<>();
        for (String clazz : classNames) {
            classes.add(toLocation(module, clazz));
        }
        return classes;
    }

    public File checkImage(String module,
            String[] userOptions, String[] paths,
            String[] files)
            throws Exception {
        List<String> unexpectedPaths = new ArrayList<>();
        if (paths != null) {
            for (String un : paths) {
                unexpectedPaths.add(un);
            }
        }
        List<String> unexpectedFiles = new ArrayList<>();
        if (files != null) {
            for (String un : files) {
                unexpectedFiles.add(un);
            }
        }
        File image = generator.generateImage(userOptions, module);
        List<String> expectedLocations = new ArrayList<>();
        expectedLocations.addAll(bootClasses);

        Consumer<Path> c = (p) -> {
            // take only the .class resources.
            if (Files.isRegularFile(p) && p.toString().endsWith(".class")
                    && !p.toString().endsWith("module-info.class")) {
                String loc = p.toString().substring("/modules".length());
                expectedLocations.add(loc);
            }
        };
        List<String> modules = moduleDependencies.get(module);
        for (String dep : modules) {
            Path path = fs.getPath("/modules/" + dep);
            if (Files.exists(path)) {
                try (Stream<Path> stream = Files.walk(path)) {
                    stream.forEach(c);
                }
            }
        }

        List<String> appClasses = moduleClassDependencies.get(module);
        if (appClasses != null) {
            expectedLocations.addAll(appClasses);
        }
        JImageValidator validator = new JImageValidator(module, expectedLocations,
                image,
                unexpectedPaths,
                unexpectedFiles);
        System.out.println("*** Validate Image " + module);
        validator.validate();
        long moduleExecutionTime = validator.getModuleLauncherExecutionTime();
        if (moduleExecutionTime != 0) {
            System.out.println("Module launcher execution time " + moduleExecutionTime);
        }
        System.out.println("Java launcher execution time "
                + validator.getJavaLauncherExecutionTime());
        System.out.println("***");
        return image;
    }

    public static String getDebugSymbolsExtension() {
        String s = System.getProperty("os.name");
        if (s.startsWith("Mac OS")) {
            return ".diz";
        } else {
            System.out.println("WARNING no debug extension for OS, update test");
            return ".unknown";
        }
    }

    public static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (true) {
            int n = is.read(buf);
            if (n < 0) {
                break;
            }
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }
}
