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
 * @summary Test custom image builder
 * @author Andrei Eremeev
 * @library ../lib
 * @modules java.base/jdk.internal.jimage
 *          java.base/jdk.internal.module
 *          jdk.jdeps/com.sun.tools.classfile
 *          jdk.jlink/jdk.tools.jlink
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jmod
 *          jdk.jlink/jdk.tools.jimage
 * @build tests.*
 * @run testng/othervm CustomImageBuilderTest
 */

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import java.util.Set;

import jdk.internal.module.ModuleInfoWriter;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import tests.Helper;
import tests.JImageGenerator;
import tests.JImageGenerator.JLinkTask;
import tests.Result;

@Test
public class CustomImageBuilderTest {

    private static Helper helper;
    private static Path pluginModulePath;
    private static Path customPluginJmod;
    private static Path classes;
    private static final List<String> options =
            Arrays.asList("custom-image-option-1", "custom-image-option-2");

    @BeforeClass
    public static void registerServices() throws IOException {
        helper = Helper.newHelper();
        if (helper == null) {
            throw new SkipException("Not run");
        }
        helper.generateDefaultModules();
        String moduleName = "customplugin";
        Path src = Paths.get(System.getProperty("test.src")).resolve(moduleName);
        classes = helper.getJmodClassesDir().resolve(moduleName);
        JImageGenerator.compile(src, classes, "-XaddExports:jdk.jlink/jdk.tools.jlink.internal=customplugin");
        customPluginJmod = JImageGenerator.getJModTask()
                .addClassPath(classes)
                .jmod(helper.getJmodDir().resolve(moduleName + ".jmod"))
                .create().assertSuccess();
        pluginModulePath = customPluginJmod.getParent();
    }

    private JLinkTask getJLinkTask() {
        return JImageGenerator.getJLinkTask()
                .option("--image-builder")
                .option("custom-image-builder");
    }

    public void testHelp() {
        Result result = JImageGenerator.getJLinkTask()
                .option("--help")
                .pluginModulePath(pluginModulePath)
                .call();
        result.assertSuccess();
        String message = result.getMessage();
        if (!message.contains("Image Builder Name: custom-image-builder\n" +
                " --custom-image-option-1 custom-image-option-description")) {
            System.err.println(result.getMessage());
            throw new AssertionError("Custom image builder not found");
        }
    }

    public void testCustomImageBuilder() throws IOException {
        Path image = getJLinkTask()
                .modulePath(helper.defaultModulePath())
                .output(helper.createNewImageDir("testCustomImageBuilder"))
                .addMods("leaf1")
                .pluginModulePath(pluginModulePath)
                .call().assertSuccess();
        checkImageBuilder(image);
    }

    public void testFirstOption() throws IOException {
        Path image = getJLinkTask()
                .modulePath(helper.defaultModulePath())
                .output(helper.createNewImageDir("testFirstOption"))
                .addMods("leaf1")
                .pluginModulePath(pluginModulePath)
                .option("--" + options.get(0))
                .option("AAAA")
                .call().assertSuccess();
        checkImageBuilder(image, Collections.singletonList(option(options.get(0), "AAAA")));
    }

    public void testFirstOptionNoArgs() throws IOException {
        getJLinkTask()
                .modulePath(helper.defaultModulePath())
                .output(helper.createNewImageDir("testFirstOptionNoArgs"))
                .addMods("leaf1")
                .pluginModulePath(pluginModulePath)
                .option("--" + options.get(0))
                .call().assertFailure("Error: no value given for --custom-image-option-1");
    }

    public void testSecondOption() throws IOException {
        Path image = getJLinkTask()
                .modulePath(helper.defaultModulePath())
                .output(helper.createNewImageDir("testSecondOption"))
                .addMods("leaf1")
                .pluginModulePath(pluginModulePath)
                .option("--" + options.get(1))
                .call().assertSuccess();
        checkImageBuilder(image, Collections.singletonList(option(options.get(1))));
    }

    public void testTwoImageBuildersInModule() throws IOException {
        Result result = JImageGenerator.getJLinkTask()
                .modulePath(helper.defaultModulePath())
                .pluginModulePath(pluginModulePath)
                .option("--list-plugins")
                .call();
        result.assertSuccess();
        String message = result.getMessage();
        if (!message.contains("Image Builder Name: custom-image-builder")) {
            System.err.println(message);
            throw new AssertionError("List-plugins does not contain custom-image-builder");
        }
        if (!message.contains("Image Builder Name: second-image-builder")) {
            System.err.println(message);
            throw new AssertionError("List-plugins does not contain second-image-builder");
        }
    }

    @Test(enabled = false, priority = Integer.MAX_VALUE - 1)
    public void testTwoPackages() throws IOException {
        String moduleName = "customplugin_1";
        Path src = helper.getJmodSrcDir().resolve(moduleName);
        copyModule(src);
        Path jmod = buildModule(moduleName);

        try {
            JImageGenerator.getJLinkTask()
                    .pluginModulePath(pluginModulePath)
                    .option("--list-plugins")
                    .call().assertFailure("Modules customplugin_1 and customplugin both contain package plugin");
        } finally {
            Files.delete(jmod);
        }
    }

    private Path buildModule(String moduleName) throws IOException {
        Path src = helper.getJmodSrcDir().resolve(moduleName);
        Path classes = helper.getJmodClassesDir().resolve(moduleName);
        JImageGenerator.compile(src, classes, "-XaddExports:jdk.jlink/jdk.tools.jlink.internal=customplugin");

        try (OutputStream out = Files.newOutputStream(classes.resolve("module-info.class"))) {
            ModuleDescriptor md = new ModuleDescriptor.Builder(moduleName)
                    .requires("java.base")
                    .provides("jdk.tools.jlink.plugins.ImageBuilderProvider",
                            "plugin.CustomImageBuilderProvider").build();
            ModuleInfoWriter.write(md, out);
        }
        return JImageGenerator.getJModTask()
                .addClassPath(classes)
                .jmod(helper.getJmodDir().resolve(moduleName + ".jmod"))
                .create().assertSuccess();
    }

    private void copyModule(Path src) throws IOException {
        Path customplugin = Paths.get(System.getProperty("test.src")).resolve("customplugin");
        Files.walk(customplugin).forEach(p -> {
            try {
                Path path = customplugin.relativize(p);
                Files.copy(p, src.resolve(path));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private void testMalformedModule(String moduleName, ModuleDescriptor md, String expectedError) throws IOException {
        try (OutputStream out = Files.newOutputStream(classes.resolve("module-info.class"))) {
            ModuleInfoWriter.write(md, out);
        }
        Path jmod = JImageGenerator.getJModTask()
                .addClassPath(classes)
                .jmod(helper.getJmodDir().resolve(moduleName + ".jmod"))
                .create().assertSuccess();
        Files.move(jmod, customPluginJmod, StandardCopyOption.REPLACE_EXISTING);
        getJLinkTask()
                .modulePath(helper.defaultModulePath())
                .output(helper.createNewImageDir(moduleName))
                .addMods(moduleName)
                .pluginModulePath(pluginModulePath)
                .call().assertFailure(expectedError);
    }

    @Test(enabled = false, priority = Integer.MAX_VALUE) // run last
    public void testIllegalAccessError() throws IOException {
        String moduleName = "testIllegalAccessError";
        ModuleDescriptor md = new ModuleDescriptor.Builder(moduleName)
                .requires("java.base")
                .provides("jdk.tools.jlink.plugins.ImageBuilderProvider",
                        new HashSet<>(Arrays.asList(
                                "plugin.SameNamedImageBuilderProvider",
                                "plugin.CustomImageBuilderProvider"))).build();
        testMalformedModule(moduleName, md, "Error: Multiple ImageBuilderProvider for the name custom-image-builder");
    }

    @Test(enabled = false, priority = Integer.MAX_VALUE) // run last
    public void testTwoImageBuilderWithTheSameName() throws IOException {
        String moduleName = "testTwoImageBuilderWithTheSameName";
        ModuleDescriptor md = new ModuleDescriptor.Builder(moduleName)
                .requires("java.base")
                .requires("jdk.jlink")
                .provides("jdk.tools.jlink.plugins.ImageBuilderProvider",
                        new HashSet<>(Arrays.asList(
                                "plugin.SameNamedImageBuilderProvider",
                                "plugin.CustomImageBuilderProvider"))).build();
        testMalformedModule(moduleName, md,
                "cannot access class jdk.tools.jlink.plugins.ImageBuilderProvider (in module: jdk.jlink)");
    }

    private void checkImageBuilder(Path image) throws IOException {
        checkImageBuilder(image, Collections.emptyList());
    }

    private void checkImageBuilder(Path image, List<Option> includes) throws IOException {
        if (!Files.exists(image.resolve("image.jimage"))) {
            throw new AssertionError("getJImageOutputStream was not called");
        }
        if (!Files.exists(image.resolve("files.txt"))) {
            throw new AssertionError("storeFiles was not called");
        }
        Set<String> otherOptions = new HashSet<>(options);
        for (Option o : includes) {
            otherOptions.remove(o.option);

            Path file = image.resolve(o.option);
            Assert.assertTrue(Files.exists(file), "Option was not handled: " + o.option);
            String content = new String(Files.readAllBytes(file));
            Assert.assertEquals(content, o.value, "Option: " + o.option);
        }
        for (String o : otherOptions) {
            Path file = image.resolve(o);
            Assert.assertTrue(!Files.exists(file), "Option presented in config: " + o);
        }
    }

    private static class Option {
        public final String option;
        public final String value;

        private Option(String option, String value) {
            this.option = option;
            this.value = value;
        }
    }

    private static Option option(String option) {
        return option(option, "");
    }

    private static Option option(String option, String value) {
        return new Option(option, value);
    }
}
