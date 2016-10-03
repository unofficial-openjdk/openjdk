/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
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

/**
 * @test
 * @library /lib/testlibrary
 * @modules jdk.compiler
 * @build CompilerUtils
 * @run testng/othervm FactoryTest
 * @summary Basic test of of provider factories
 */

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Layer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.Set;
import java.util.stream.Collectors;

import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;
import static org.testng.Assert.*;

/**
 * Basic test of `provides S with PF` where PF is a provider factory that defines
 * a public static "provider" method.
 */

public class FactoryTest {

    private static final String TEST_SRC = System.getProperty("test.src");

    private static final Path USER_DIR   = Paths.get(System.getProperty("user.dir"));
    private static final Path SRC_DIR    = Paths.get(TEST_SRC, "modules");

    private static final Path BADFACTORIES_DIR = Paths.get(TEST_SRC, "badfactories");

    private static final String TEST_MODULE = "test";
    private static final String TEST_SERVICE = "p.Service";

    /**
     * Compiles module test, returning a module path with the compiled module.
     */
    private Path compileTest() throws Exception {
        Path dir = Files.createTempDirectory(USER_DIR, "mods");
        Path output = Files.createDirectory(dir.resolve(TEST_MODULE));
        boolean compiled = CompilerUtils.compile(SRC_DIR.resolve(TEST_MODULE), output);
        assertTrue(compiled);
        return dir;
    }

    /**
     * Resolves the test module and loads it into its own layer. ServiceLoader
     * is then used to load all providers.
     */
    private List<Provider> loadProviders(Path mp) throws Exception {
        ModuleFinder finder = ModuleFinder.of(mp);

        Layer bootLayer = Layer.boot();

        Configuration cf = bootLayer.configuration()
                .resolveRequiresAndUses(finder, ModuleFinder.of(), Set.of(TEST_MODULE));

        ClassLoader scl = ClassLoader.getSystemClassLoader();

        Layer layer = Layer.boot().defineModulesWithOneLoader(cf, scl);

        Class<?> service = layer.findLoader(TEST_MODULE).loadClass(TEST_SERVICE);

        return ServiceLoader.load(layer, service)
                .stream()
                .collect(Collectors.toList());
    }

    @Test
    public void testBasic() throws Exception {
        Path mods = compileTest();
        List<Provider> list = loadProviders(mods);
        assertTrue(list.size() == 1);

        // the provider is a singleton, enforced by the provider factory
        Object p1 = list.get(0).get();
        Object p2 = list.get(0).get();
        assertTrue(p1 != null);
        assertTrue(p1 == p2);
    }


    @DataProvider(name = "badfactories")
    public Object[][] createtestBadFactories() {
        return new Object[][] {
                { "classnotpublic",     null },
                { "methodnotpublic",    null },
                { "badreturntype",      null },
                { "returnsnull",        null },
                { "throwsexception",    null },
        };
    }


    @Test(dataProvider = "badfactories",
          expectedExceptions = ServiceConfigurationError.class)
    public void testBadFactory(String testName, String ignore) throws Exception {
        Path mods = compileTest();

        // compile the bad factory
        Path source = BADFACTORIES_DIR.resolve(testName);
        Path output = Files.createTempDirectory(USER_DIR, "tmp");
        boolean compiled = CompilerUtils.compile(source, output);
        assertTrue(compiled);

        // copy the compiled class into the module
        Path classFile = Paths.get("p", "ProviderFactory.class");
        Files.copy(output.resolve(classFile),
                   mods.resolve(TEST_MODULE).resolve(classFile),
                   StandardCopyOption.REPLACE_EXISTING);

        // load providers and instanitate each one
        loadProviders(mods).forEach(Provider::get);
    }

}

