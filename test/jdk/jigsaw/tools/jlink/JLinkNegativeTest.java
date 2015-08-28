/**
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
 * @summary Negative tests for jlink
 * @bug 8130861
 * @author Andrei Eremeev
 * @library ../lib
 * @modules java.base/jdk.internal.jimage
 *          jdk.jdeps/com.sun.tools.classfile
 *          jdk.jlink/jdk.tools.jlink
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jmod
 *          jdk.jlink/jdk.tools.jimage
 * @build tests.*
 * @run testng JLinkNegativeTest
 */

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;

import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import tests.Helper;
import tests.JImageGenerator;
import tests.JImageGenerator.InMemoryFile;
import tests.Result;

@Test
public class JLinkNegativeTest {

    private Helper helper;

    @BeforeMethod
    public void setUp() throws IOException {
        helper = Helper.newHelper();
        if (helper == null) {
            throw new SkipException("Not run");
        }
        helper.generateDefaultModules();
    }

    @AfterMethod
    public void cleanUp() throws IOException {
        if (helper != null) {
            deleteDirectory(helper.getJmodDir());
            deleteDirectory(helper.getJarDir());
            deleteDirectory(helper.getImageDir());
            deleteDirectory(helper.getExtractedDir());
            deleteDirectory(helper.getRecreatedDir());
            helper = null;
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public void testModuleNotExist() {
        helper.generateDefaultImage("failure1").assertFailure("Error: Module failure1 not found");
    }

    public void testNotExistInAddMods() {
        // cannot find jmod from --addmods
        JImageGenerator.getJLinkTask()
                .modulePath(".")
                .addMods("not_exist")
                .output(helper.getImageDir().resolve("failure2"))
                .call().assertFailure("Error: Module not_exist not found");
    }

    public void test() throws IOException {
        helper.generateDefaultJModule("failure3");
        Path image = helper.generateDefaultImage("failure3").assertSuccess();
        JImageGenerator.getJLinkTask()
                .modulePath(helper.defaultModulePath())
                .output(image)
                .call().assertFailure("Error: not empty: .*failure3.image\n");
    }

    public void testOutputIsFile() throws IOException {
        // output == file
        Path image = helper.createNewImageDir("failure4");
        Files.createFile(image);
        JImageGenerator.getJLinkTask()
                .modulePath(helper.defaultModulePath())
                .output(image)
                .call().assertFailure("Error: file already exists: .*failure4.image(\n|\r|.)*");
    }

    public void testModuleNotFound() {
        // limit module is not found
        Path imageFile = helper.createNewImageDir("test");
        JImageGenerator.getJLinkTask()
                .output(imageFile)
                .addMods("leaf1")
                .limitMods("leaf1")
                .limitMods("failure5")
                .modulePath(helper.defaultModulePath())
                .call().assertFailure("Error: Module failure5 not found");
    }

    public void testAddedModuleIsFiltered() {
        // added module is filtered out
        Path imageFile = helper.createNewImageDir("test");
        JImageGenerator.getJLinkTask()
                .output(imageFile)
                .addMods("leaf1")
                .limitMods("leaf2")
                .modulePath(helper.defaultModulePath())
                .call().assertFailure("Error: Module leaf1 not found");
    }

    public void testJmodIsDir() throws IOException {
        Path imageFile = helper.createNewImageDir("test");
        Path dirJmod = helper.createNewJmodFile("dir");
        Files.createDirectory(dirJmod);
        JImageGenerator.getJLinkTask()
                .output(imageFile)
                .addMods("dir")
                .modulePath(helper.defaultModulePath())
                .call().assertFailure("Error: Module dir not found");
    }

    public void testJarIsDir() throws IOException {
        Path imageFile = helper.createNewImageDir("test");
        Path dirJar = helper.createNewJarFile("dir");
        Files.createDirectory(dirJar);
        JImageGenerator.getJLinkTask()
                .output(imageFile)
                .addMods("dir")
                .modulePath(helper.defaultModulePath())
                .call().assertFailure("Error: Module dir not found");
    }

    public void testMalformedJar() throws IOException {
        Path imageFile = helper.createNewImageDir("test");
        Path jar = helper.createNewJarFile("not_zip");
        Files.createFile(jar);
        JImageGenerator.getJLinkTask()
                .output(imageFile)
                .addMods("not_zip")
                .modulePath(helper.defaultModulePath())
                .call().assertFailure("Error: java.util.zip.ZipException: zip file is empty");
    }

    public void testMalformedJmod() throws IOException {
        Path imageFile = helper.createNewImageDir("test");
        Path jmod = helper.createNewJmodFile("not_zip");
        Files.createFile(jmod);
        JImageGenerator.getJLinkTask()
                .output(imageFile)
                .addMods("not_zip")
                .modulePath(helper.defaultModulePath())
                .call().assertFailure("Error: java.util.zip.ZipException: zip file is empty");
    }

    public void testAddDefaultPackage() throws IOException {
        String moduleName = "hacked1";
        Path module = helper.generateModuleCompiledClasses(helper.getJmodSrcDir(), helper.getJmodClassesDir(),
                moduleName, Arrays.asList("hacked1.Main", "A", "B"), "leaf1");
        JImageGenerator
                .getJModTask()
                .addClassPath(module)
                .output(helper.getJmodDir().resolve(moduleName + ".jmod"))
                .create().assertSuccess();
        Path image = helper.generateDefaultImage(moduleName).assertSuccess();
        helper.checkImage(image, moduleName, null, null);
    }

    public void testAddSomeTopLevelFiles() throws IOException {
        String moduleName = "hacked2";
        Path module = helper.generateModuleCompiledClasses(helper.getJmodSrcDir(), helper.getJmodClassesDir(),
                moduleName, null);
        Files.createFile(module.resolve("top-level-file"));
        JImageGenerator
                .getJModTask()
                .addClassPath(module)
                .output(helper.getJmodDir().resolve(moduleName + ".jmod"))
                .create().assertSuccess();
        Path image = helper.generateDefaultImage(moduleName).assertSuccess();
        helper.checkImage(image, moduleName, null, null);
    }

    public void testAddNonStandardSection() throws IOException {
        String moduleName = "hacked3";
        Path module = helper.generateDefaultJModule(moduleName).assertSuccess();
        JImageGenerator.addFiles(module, new InMemoryFile("unknown/A.class", new byte[0]));
        Result result = helper.generateDefaultImage(moduleName);
        if (result.getExitCode() != 4) {
            throw new AssertionError("Crash expected");
        }
        if (!result.getMessage().contains("java.lang.InternalError: unexpected entry: unknown")) {
            System.err.println(result.getMessage());
            throw new AssertionError("InternalError expected");
        }
    }
}
