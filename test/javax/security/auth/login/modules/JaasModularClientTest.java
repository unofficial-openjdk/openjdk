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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.io.IOException;
import java.io.OutputStream;
import java.io.File;
import java.lang.module.ModuleDescriptor;
import jdk.testlibrary.ProcessTools;
import jdk.testlibrary.OutputAnalyzer;
import jdk.internal.module.ModuleInfoWriter;

/**
 * @test
 * @bug 8078813
 * @library /jdk/jigsaw/lib
 * @library /lib/testlibrary
 * @library /java/security/modules
 * @summary Test custom JAAS module with all possible modular option. The test
 *          includes different combination of JAAS client/login modules
 *          interaction with or without service description. The different
 *          module types used here are,
 *          EXPLICIT - Modules have module descriptor(module-info.java) defining
 *          the module.
 *          AUTO - Are regular jar files but provided in MODULE_PATH instead
 *          of CLASS_PATH.
 *          UNNAMED - Are regular jar but provided through CLASS_PATH.
 * @run main/othervm -Duser.language=en -Duser.region=US JaasModularClientTest
 */
public class JaasModularClientTest extends JigsawSecurityUtils {

    private static final String TEST_SRC = System.getProperty("test.src");
    private static final Path SRC_DIR = Paths.get(TEST_SRC, "src");
    private static final String DESCRIPTOR = "metaservice";
    private static final String MODULAR = "modular";
    private static final String AUTO = "auto";
    private static final String JAR_EXTN = ".jar";

    private static final String SERVICE_MODULE_NAME = "jaasloginmodule";
    private static final Path SERVICE_SRC_DIR
            = SRC_DIR.resolve(SERVICE_MODULE_NAME);
    private static final String SERVICE_PKG = "login";
    private static final String SERVICE_JAR_NAME = SERVICE_PKG + JAR_EXTN;
    private static final String SERVICE_DESCRIPTOR_JAR_NAME
            = SERVICE_PKG + DESCRIPTOR + JAR_EXTN;
    private static final String MODULAR_SERVICE_JAR_NAME
            = MODULAR + SERVICE_PKG + JAR_EXTN;
    private static final String MODULAR_SERVICE_DESCRIPTOR_JAR_NAME
            = MODULAR + SERVICE_PKG + DESCRIPTOR + JAR_EXTN;

    private static final String CLIENT_MODULE_NAME = "jaasclientmodule";
    private static final Path CLIENT_SRC_DIR
            = SRC_DIR.resolve(CLIENT_MODULE_NAME);
    private static final String CLIENT_PKG = "client";
    private static final String CLIENT_JAR_NAME = CLIENT_PKG + JAR_EXTN;
    private static final String MODULAR_CLIENT_AUTO_DEPEND_JAR_NAME
            = MODULAR + CLIENT_PKG + AUTO + JAR_EXTN;
    private static final String MODULAR_CLIENT_JAR_NAME
            = MODULAR + CLIENT_PKG + JAR_EXTN;

    private static final Path BUILD_DIR = Paths.get(".").resolve("build");
    private static final Path COMPILE_DIR = BUILD_DIR.resolve("bin");
    private static final Path SERVICE_BUILD_DIR
            = COMPILE_DIR.resolve(SERVICE_PKG);
    private static final Path SERVICE_META_BUILD_DIR
            = COMPILE_DIR.resolve(SERVICE_PKG + DESCRIPTOR);
    private static final Path CLIENT_BUILD_DIR
            = COMPILE_DIR.resolve(CLIENT_PKG);

    private static final String EXPLICIT_MODULE_NAME = "jarmodule";
    private static final Path EXPLICIT_MODULE_BASE_PATH
            = BUILD_DIR.resolve(EXPLICIT_MODULE_NAME);

    private static final Path ARTIFACTS_DIR = BUILD_DIR.resolve("artifacts");
    private static final Path SERVICE_ARTIFACTS_DIR
            = ARTIFACTS_DIR.resolve(SERVICE_PKG);
    private static final Path REGULAR_SERVICE_JAR
            = SERVICE_ARTIFACTS_DIR.resolve(SERVICE_JAR_NAME);
    private static final Path REGULAR_SERVICE_WITH_DESCRIPTOR_JAR
            = SERVICE_ARTIFACTS_DIR.resolve(SERVICE_DESCRIPTOR_JAR_NAME);
    private static final Path MODULAR_SERVICE_JAR
            = SERVICE_ARTIFACTS_DIR.resolve(MODULAR_SERVICE_JAR_NAME);
    private static final Path MODULAR_SERVICE_WITH_DESCRIPTOR_JAR
            = SERVICE_ARTIFACTS_DIR.resolve(MODULAR_SERVICE_DESCRIPTOR_JAR_NAME);

    private static final Path CLIENT_ARTIFACTS_DIR
            = ARTIFACTS_DIR.resolve(CLIENT_PKG);
    private static final Path REGULAR_CLIENT_JAR
            = CLIENT_ARTIFACTS_DIR.resolve(CLIENT_JAR_NAME);
    private static final Path MODULAR_CLIENT_JAR
            = CLIENT_ARTIFACTS_DIR.resolve(MODULAR_CLIENT_JAR_NAME);
    private static final Path MODULAR_CLIENT_AUTO_DEPEND_JAR
            = CLIENT_ARTIFACTS_DIR.resolve(MODULAR_CLIENT_AUTO_DEPEND_JAR_NAME);

    private static final String MAIN_CLASS = CLIENT_PKG + ".JaasClient";
    private static final String LOGIN_SERVICE_INTERFACE
            = "javax.security.auth.spi.LoginModule";
    private static final String SERVICE_IMPL = SERVICE_PKG + ".TestLoginModule";
    private static final List<String> REQUIRED_MODULES
            = Arrays.asList("java.base", "jdk.security.auth");
    private static final Path META_DESCRIPTOR = Paths.get("META-INF")
            .resolve("services").resolve(LOGIN_SERVICE_INTERFACE);
    private static final Path META_SERVICE_DESCRIPTOR
            = SERVICE_META_BUILD_DIR.resolve(META_DESCRIPTOR);

    private static final boolean WITH_SERVICE_DESCRIPTOR = true;
    private static final boolean WITHOUT_SERVICE_DESCRIPTOR = false;
    private static final boolean PASS = true;
    private static final String EXPECTED_FAILURE = "No LoginModule found";
    private static final String NO_FAILURE = null;
    private static final Map<String, String> VM_ARGS = new LinkedHashMap<>();

    public static void main(String[] args) {

        boolean success = true;
        boolean ready = createArtifacts();
        if (!ready) {
            throw new RuntimeException("Unable to prepare to run this test.");
        }

        //PARAMETER ORDERS -
        //client Module Type, Service Module Type,
        //Service META Descriptor Required, Expected Result
        success &= runTest(MODULE_TYPE.EXPLICIT, MODULE_TYPE.EXPLICIT,
                WITH_SERVICE_DESCRIPTOR, PASS, NO_FAILURE);
        success &= runTest(MODULE_TYPE.EXPLICIT, MODULE_TYPE.EXPLICIT,
                WITHOUT_SERVICE_DESCRIPTOR, PASS, NO_FAILURE);
        success &= runTest(MODULE_TYPE.EXPLICIT, MODULE_TYPE.AUTO,
                WITH_SERVICE_DESCRIPTOR, PASS, NO_FAILURE);
        success &= runTest(MODULE_TYPE.EXPLICIT, MODULE_TYPE.AUTO,
                WITHOUT_SERVICE_DESCRIPTOR, PASS, EXPECTED_FAILURE);
        success &= runTest(MODULE_TYPE.EXPLICIT, MODULE_TYPE.UNNAMED,
                WITH_SERVICE_DESCRIPTOR, PASS, NO_FAILURE);
        success &= runTest(MODULE_TYPE.EXPLICIT, MODULE_TYPE.UNNAMED,
                WITHOUT_SERVICE_DESCRIPTOR, PASS, NO_FAILURE);

        success &= runTest(MODULE_TYPE.AUTO, MODULE_TYPE.EXPLICIT,
                WITH_SERVICE_DESCRIPTOR, PASS, NO_FAILURE);
        success &= runTest(MODULE_TYPE.AUTO, MODULE_TYPE.EXPLICIT,
                WITHOUT_SERVICE_DESCRIPTOR, PASS, NO_FAILURE);
        success &= runTest(MODULE_TYPE.AUTO, MODULE_TYPE.AUTO,
                WITH_SERVICE_DESCRIPTOR, PASS, NO_FAILURE);
        success &= runTest(MODULE_TYPE.AUTO, MODULE_TYPE.AUTO,
                WITHOUT_SERVICE_DESCRIPTOR, PASS, EXPECTED_FAILURE);
        success &= runTest(MODULE_TYPE.AUTO, MODULE_TYPE.UNNAMED,
                WITH_SERVICE_DESCRIPTOR, PASS, NO_FAILURE);
        success &= runTest(MODULE_TYPE.AUTO, MODULE_TYPE.UNNAMED,
                WITHOUT_SERVICE_DESCRIPTOR, PASS, NO_FAILURE);

        success &= runTest(MODULE_TYPE.UNNAMED, MODULE_TYPE.EXPLICIT,
                WITH_SERVICE_DESCRIPTOR, PASS, NO_FAILURE);
        success &= runTest(MODULE_TYPE.UNNAMED, MODULE_TYPE.EXPLICIT,
                WITHOUT_SERVICE_DESCRIPTOR, PASS, NO_FAILURE);
        success &= runTest(MODULE_TYPE.UNNAMED, MODULE_TYPE.AUTO,
                WITH_SERVICE_DESCRIPTOR, PASS, NO_FAILURE);
        success &= runTest(MODULE_TYPE.UNNAMED, MODULE_TYPE.AUTO,
                WITHOUT_SERVICE_DESCRIPTOR, PASS, EXPECTED_FAILURE);
        success &= runTest(MODULE_TYPE.UNNAMED, MODULE_TYPE.UNNAMED,
                WITH_SERVICE_DESCRIPTOR, PASS, NO_FAILURE);
        success &= runTest(MODULE_TYPE.UNNAMED, MODULE_TYPE.UNNAMED,
                WITHOUT_SERVICE_DESCRIPTOR, PASS, NO_FAILURE);

        if (!success) {
            throw new RuntimeException("Atleast one test failed.");
        }
    }

    public static boolean runTest(MODULE_TYPE clientModuleType,
            MODULE_TYPE serviceModuletype, boolean addMetaInfDescriptor,
            boolean expectedResult, String expectedFailure) {

        boolean result = true;
        try {

            String testName = (clientModuleType + "_")
                    + (serviceModuletype + "_")
                    + ((addMetaInfDescriptor) ? "DESCRIPTOR" : "NO_DESCRIPTOR");

            System.out.println(String.format(
                    "Starting Test case: '%s'", testName));
            Path clientJarPath = findJarPath(false, clientModuleType, false,
                    (serviceModuletype == MODULE_TYPE.EXPLICIT));
            Path serviceJarPath = findJarPath(
                    true, serviceModuletype, addMetaInfDescriptor, false);
            System.out.println(String.format(
                    "Client jar path : %s ", clientJarPath));
            System.out.println(String.format(
                    "Service jar path : %s ", serviceJarPath));
            //For automated/explicit module type copy the corresponding
            //jars to module base folder, which will be considered as
            //module base path during execution.
            if (!(clientModuleType == MODULE_TYPE.UNNAMED
                    && serviceModuletype == MODULE_TYPE.UNNAMED)) {
                copyJarsToModuleBase(clientModuleType, clientJarPath,
                        serviceModuletype, serviceJarPath);
            }

            System.out.println("Started executing java client with required"
                    + " custom service in class/module path.");
            String moduleName
                    = getClientModuleName(clientModuleType, clientJarPath);
            Path moduleBasePath = (clientModuleType != MODULE_TYPE.UNNAMED
                    || serviceModuletype != MODULE_TYPE.UNNAMED)
                            ? EXPLICIT_MODULE_BASE_PATH : null;
            StringBuilder classPath
                    = getClassPath(clientModuleType, clientJarPath,
                            serviceModuletype, serviceJarPath);
            OutputAnalyzer output = ProcessTools.executeTestJava(
                    getJavaCommand(moduleBasePath, classPath, moduleName,
                            MAIN_CLASS, VM_ARGS))
                    .outputTo(System.out)
                    .errorTo(System.out);

            if (output.getExitValue() != 0) {
                if (expectedFailure != null
                        && output.getOutput().contains(expectedFailure)) {
                    System.out.println("PASS: Test is expected to fail here.");
                    System.out.println("------------------------------------");
                } else {
                    throw new RuntimeException("Unexpected failure occured.");
                }
            }

        } catch (Exception e) {
            e.printStackTrace(System.out);
            result = false;
        } finally {
            //clean module path so that the modulepath can only hold
            //the required jars for next run.
            cleanModuleBasePath();
        }

        return (expectedResult == result);
    }

    //Decide the pre-generated client/service jar path for a given module type.
    private static Path findJarPath(boolean service, MODULE_TYPE moduleType,
            boolean addMetaInfDescriptor, boolean dependsOnServiceModule) {
        if (service) {
            if (moduleType == MODULE_TYPE.EXPLICIT) {
                if (addMetaInfDescriptor) {
                    return MODULAR_SERVICE_WITH_DESCRIPTOR_JAR;
                } else {
                    return MODULAR_SERVICE_JAR;
                }
            } else {
                if (addMetaInfDescriptor) {
                    return REGULAR_SERVICE_WITH_DESCRIPTOR_JAR;
                } else {
                    return REGULAR_SERVICE_JAR;
                }
            }
        } else {
            if (moduleType == MODULE_TYPE.EXPLICIT) {
                if (dependsOnServiceModule) {
                    return MODULAR_CLIENT_JAR;
                } else {
                    return MODULAR_CLIENT_AUTO_DEPEND_JAR;
                }
            } else {
                return REGULAR_CLIENT_JAR;
            }
        }
    }

    //Copy pre-generated jar files to the base module path based on module type.
    private static void copyJarsToModuleBase(MODULE_TYPE clientModuleType,
            Path modularClientJarPath, MODULE_TYPE serviceModuletype,
            Path modularServiceJarPath) throws IOException {

        if (EXPLICIT_MODULE_BASE_PATH != null) {
            Files.createDirectories(EXPLICIT_MODULE_BASE_PATH);
        }
        if (clientModuleType != MODULE_TYPE.UNNAMED) {
            Path clientArtifactName = EXPLICIT_MODULE_BASE_PATH.resolve(
                    modularClientJarPath.getFileName());
            System.out.println(String.format("Copy client jar path: '%s'"
                    + " to module base path: %s", modularClientJarPath,
                    clientArtifactName));
            Files.copy(modularClientJarPath, clientArtifactName);
        }
        if (serviceModuletype != MODULE_TYPE.UNNAMED) {
            Path serviceArtifactName = EXPLICIT_MODULE_BASE_PATH.resolve(
                    modularServiceJarPath.getFileName());
            System.out.println(String.format("Copy service jar path: '%s'"
                    + " to module base path: %s", modularServiceJarPath,
                    serviceArtifactName));
            Files.copy(modularServiceJarPath, serviceArtifactName);
        }
    }

    //Pre-compile and generate the jar files required to run this test.
    private static boolean createArtifacts() {

        boolean done = true;
        try {
            VM_ARGS.put("-Duser.language=", "en");
            VM_ARGS.put("-Duser.region", "US");
            VM_ARGS.put("-Djava.security.auth.login.config=",
                    (CLIENT_SRC_DIR.resolve(CLIENT_PKG).resolve("jaas.conf")
                    .toRealPath().toString()));

            done = CompilerUtils.compile(SERVICE_SRC_DIR, SERVICE_BUILD_DIR);
            done &= CompilerUtils.compile(SERVICE_SRC_DIR,
                    SERVICE_META_BUILD_DIR);
            done &= CompilerUtils.compile(CLIENT_SRC_DIR, CLIENT_BUILD_DIR);
            done &= createMetaInfServiceDescriptor(META_SERVICE_DESCRIPTOR,
                    SERVICE_IMPL);
            //Generate regular/modular jars with(out) META-INF
            //service descriptor
            generateJar(true, MODULE_TYPE.EXPLICIT, MODULAR_SERVICE_JAR,
                    SERVICE_BUILD_DIR, false);
            generateJar(true, MODULE_TYPE.EXPLICIT,
                    MODULAR_SERVICE_WITH_DESCRIPTOR_JAR,
                    SERVICE_META_BUILD_DIR, false);
            generateJar(true, MODULE_TYPE.UNNAMED, REGULAR_SERVICE_JAR,
                    SERVICE_BUILD_DIR, false);
            generateJar(true, MODULE_TYPE.UNNAMED,
                    REGULAR_SERVICE_WITH_DESCRIPTOR_JAR,
                    SERVICE_META_BUILD_DIR, false);
            //Generate regular/modular(depends on explicit/auto service)
            //jars for client
            generateJar(false, MODULE_TYPE.EXPLICIT, MODULAR_CLIENT_JAR,
                    CLIENT_BUILD_DIR, true);
            generateJar(false, MODULE_TYPE.EXPLICIT,
                    MODULAR_CLIENT_AUTO_DEPEND_JAR, CLIENT_BUILD_DIR, false);
            generateJar(false, MODULE_TYPE.UNNAMED,
                    REGULAR_CLIENT_JAR, CLIENT_BUILD_DIR, false);

            System.out.println(String.format(
                    "Artifacts generated successfully? '%s'", done));
        } catch (IOException e) {
            e.printStackTrace(System.out);
            done = false;
        }
        return done;
    }

    //Generate modular/regular jar based on module type.
    private static void generateJar(boolean service, MODULE_TYPE moduleType,
            Path jarFile, Path compilePath, boolean depends)
            throws IOException {

        ModuleDescriptor moduleDescriptor = null;
        if (service) {
            moduleDescriptor = generateModuleDescriptor(service, moduleType,
                    SERVICE_MODULE_NAME, SERVICE_PKG,
                    LOGIN_SERVICE_INTERFACE, SERVICE_IMPL, null,
                    REQUIRED_MODULES, depends);
        } else {
            moduleDescriptor = generateModuleDescriptor(service,
                    moduleType, CLIENT_MODULE_NAME, CLIENT_PKG,
                    LOGIN_SERVICE_INTERFACE, null, SERVICE_MODULE_NAME,
                    REQUIRED_MODULES, depends);
        }
        if (moduleDescriptor != null) {
            System.out.println(String.format(
                    "Creating Modular jar file '%s'", jarFile));
        } else {
            System.out.println(String.format(
                    "Creating regular jar file '%s'", jarFile));
        }
        JarUtils.createJarFile(jarFile, compilePath);
        if (moduleDescriptor != null) {
            Path dir = Files.createTempDirectory("tmp");
            Path mi = dir.resolve("module-info.class");
            try (OutputStream out = Files.newOutputStream(mi)) {
                ModuleInfoWriter.write(moduleDescriptor, out);
            }
            JarUtils.updateJarFile(jarFile, dir);
        }

    }

    //Construct class path argument value.
    private static StringBuilder getClassPath(MODULE_TYPE clientModuleType,
            Path clientJarPath, MODULE_TYPE serviceModuletype,
            Path serviceJarPath) throws IOException {

        StringBuilder classPath = new StringBuilder();
        classPath.append((clientModuleType == MODULE_TYPE.UNNAMED)
                ? (clientJarPath.toRealPath().toString()
                + File.pathSeparatorChar) : "");
        classPath.append((serviceModuletype == MODULE_TYPE.UNNAMED)
                ? serviceJarPath.toRealPath().toString() : "");
        return classPath;
    }

    //Construct client modulename to run the client. It is fixed for explicit
    //module type while it is same as jar file name for automated module type.
    private static String getClientModuleName(MODULE_TYPE clientModuleType,
            Path clientJarPath) {

        String jarFileName = clientJarPath.toFile().getName();
        return (clientModuleType == MODULE_TYPE.EXPLICIT)
                ? CLIENT_MODULE_NAME
                : ((clientModuleType == MODULE_TYPE.AUTO)
                        ? jarFileName.substring(
                                0, jarFileName.indexOf(JAR_EXTN)) : "");
    }

    //Delete all the files inside the base module path.
    private static void cleanModuleBasePath() {

        Arrays.asList(EXPLICIT_MODULE_BASE_PATH.toFile().listFiles())
                .forEach(f -> {
                    System.out.println("delete " + f);
                    f.delete();
                });
    }

}
