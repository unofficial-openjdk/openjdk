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
 * @summary Test file replacer plugin
 * @author Andrei Eremeev
 * @modules jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jlink.internal.plugins
 * @run main FileReplacerPluginTest
 */

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jdk.tools.jlink.internal.ImageFilePoolImpl;
import jdk.tools.jlink.internal.plugins.FileReplacerProvider;
import jdk.tools.jlink.plugins.CmdPluginProvider;
import jdk.tools.jlink.plugins.ImageFilePlugin;
import jdk.tools.jlink.plugins.ImageFilePool;

public class FileReplacerPluginTest {
    public static void main(String[] args) throws Exception {
        new FileReplacerPluginTest().test();
    }

    private void createFile(String fileName) throws IOException {
        Path path = Paths.get(fileName);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write(fileName);
        }
    }

    public void test() throws Exception {
        String[] files = {"toto/Main", "toto/com/foo/bar/X", "toto/com/foo/bar/Y"};
        for (String file : files) {
            createFile(file);
        }
        try {
            testReplacement(new Replacement(files[0], "UNKNOWN"));
            throw new AssertionError("Exception is not thrown, when the file does not exist");
        } catch (RuntimeException e) {
            if (!e.getMessage().matches("Replacement file UNKNOWN doesn't exist.")) {
                throw new AssertionError("Error expected: file not found");
            }
        }
        testReplacement(new Replacement("UNKNOWN", files[0]));
        testReplacement(new Replacement(files[0], files[1]), new Replacement(files[1], files[2]));
        testReplacement(new Replacement(files[0], files[0]));
        testReplacement(new Replacement(files[0], files[1]));
        try {
            testReplacement("ArrayIndexOutOfBounds");
            throw new AssertionError("Expected exception");
        } catch (RuntimeException e) {
            if (!e.getMessage().matches("Replacing file, invalid number of arguments")) {
                throw new AssertionError("Error expected: invalid number of arguments");
            }
        }
    }

    private String createArguments(Replacement... replacements) {
        return Stream.of(replacements)
                .map(p -> "/java.base/" + p.first + "," + p.second)
                .collect(Collectors.joining(","));
    }

    private void testReplacement(Replacement... replacements) throws Exception {
        testReplacement(createArguments(replacements), replacements);
    }

    private void testReplacement(String arguments, Replacement... replacements) throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY, arguments);
        FileReplacerProvider provider = new FileReplacerProvider();
        ImageFilePlugin replacerPlugin = (ImageFilePlugin) provider.newPlugins(p)[0];
        ImageFilePool input = new ImageFilePoolImpl();
        ImageFilePool output = new ImageFilePoolImpl();
        for (Replacement replacement : replacements) {
            input.addFile(new CustomImageFile(replacement.first));
        }
        replacerPlugin.visit(input, output);
        for (Replacement replacement : replacements) {
            ImageFilePool.ImageFile main = output.getFile(replacement.first);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(main.stream()))) {
                String content = reader.lines().collect(Collectors.joining("\n"));
                if (!Objects.equals(content, replacement.second)) {
                    throw new AssertionError("Resource: " + replacement.first +
                            ", expected: " + content + ", " + replacement.second);
                }
            }
        }
    }

    private static class Replacement {
        public final String first;
        public final String second;

        private Replacement(String first, String second) {
            this.first = first;
            this.second = second;
        }
    }

    private static class CustomImageFile extends ImageFilePool.ImageFile {

        private final String path;

        public CustomImageFile(String path) {
            super("java.base", path, path, ImageFilePool.ImageFile.ImageFileType.CONFIG);
            this.path = path;
        }

        @Override
        public long size() {
            try {
                return Files.size(Paths.get(path));
            } catch (IOException e) {
                return -1;
            }
        }

        @Override
        public InputStream stream() throws IOException {
            return Files.newInputStream(Paths.get(path));
        }
    }
}
