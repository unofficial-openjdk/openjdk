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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import jdk.internal.jimage.decompressor.ResourceDecompressor;
import jdk.internal.jimage.decompressor.ZipDecompressorFactory;
import jdk.jigsaw.tools.jlink.plugins.ResourcePool;
import jdk.jigsaw.tools.jlink.plugins.ResourcePool.Resource;
import jdk.jigsaw.tools.jlink.internal.ImagePluginConfiguration;
import jdk.internal.jimage.decompressor.CompressedResourceHeader;
import jdk.jigsaw.tools.jlink.plugins.Plugin;
import jdk.jigsaw.tools.jlink.plugins.PluginProvider;
import jdk.jigsaw.tools.jlink.internal.ImagePluginProviderRepository;
import jdk.jigsaw.tools.jlink.internal.ImagePluginStack;
import jdk.jigsaw.tools.jlink.internal.ResourcePoolImpl;
import jdk.jigsaw.tools.jlink.plugins.ResourcePool.CompressedResource;
import jdk.jigsaw.tools.jlink.plugins.StringTable;
import jdk.jigsaw.tools.jlink.internal.plugins.ZipCompressProvider;
import jdk.jigsaw.tools.jlink.internal.plugins.ExcludeProvider;
import jdk.jigsaw.tools.jlink.internal.plugins.StripDebugProvider;
import tests.JImageGenerator;

/*
 * @test
 * @summary Test jlink plugins
 * @author Jean-Francois Denise
 * @library /lib/testlibrary/jlink
 * @modules java.base/jdk.internal.jimage
 *          java.base/jdk.internal.jimage.decompressor
 *          jdk.compiler/com.sun.tools.classfile
 *          jdk.jlink/jdk.jigsaw.tools.jlink
 *          jdk.jlink/jdk.jigsaw.tools.jlink.internal
 *          jdk.jlink/jdk.jigsaw.tools.jlink.internal.plugins
 *          jdk.jlink/jdk.jigsaw.tools.jmod
 *          jdk.jlink/jdk.tools.jimage
 * @run build PluginsTest
 * @run build tests.*
 * @run main PluginsTest
 */
public class PluginsTest {

    private static final List<String> order = new ArrayList<>();

    private static class PluginTrap implements Plugin {

        private final String name;

        private PluginTrap(String name) {
            this.name = name;
        }

        @Override
        public void visit(ResourcePool resources, ResourcePool output,
                StringTable strings)
                throws Exception {
            order.add(name);
            output.addResource(new Resource("/module/com/foo/bar/X.st",
                    ByteBuffer.allocate(0)));
        }

        @Override
        public String getName() {
            return name;
        }
    }

    private static class PProvider extends PluginProvider {

        PProvider(String name) {
            super(name, "");
        }

        @Override
        public Plugin newPlugin(Properties properties) throws IOException {
            return new PluginTrap(getName());
        }

        @Override
        public String getCategory() {
            return null;
        }

        @Override
        public String getConfiguration() {
            return null;
        }

        @Override
        public String getToolOption() {
            return null;
        }

    }

    private static class SorterPlugin implements Plugin {

        private final String name;
        private final String starts;

        private SorterPlugin(String name, String starts) {
            this.name = name;
            this.starts = starts;
        }

        @Override
        public void visit(ResourcePool resources, ResourcePool output,
                StringTable strings)
                throws Exception {
            List<Resource> paths = new ArrayList<>();
            for (Resource res : resources.getResources()) {
                if (res.getPath().startsWith(starts)) {
                    paths.add(0, res);
                } else {
                    paths.add(res);
                }
            }

            for (Resource r : paths) {
                output.addResource(r);
            }
        }

        @Override
        public String getName() {
            return name;
        }
    }

    private static class SorterProvider extends PluginProvider {

        SorterProvider(String name) {
            super(name, "");
        }

        @Override
        public Plugin newPlugin(Properties properties) throws IOException {
            return new SorterPlugin(getName(),
                    properties.getProperty(CONFIGURATION_PROPERTY));
        }

        @Override
        public String getCategory() {
            return null;
        }

        @Override
        public String getConfiguration() {
            return null;
        }

        @Override
        public String getToolOption() {
            return null;
        }

    }

    public static void main(String[] args) throws Exception {
        File jdkHome = new File(System.getProperty("test.jdk"));
        // JPRT not yet ready for jmods
        if (JImageGenerator.getJModsDir(jdkHome) == null) {
            System.err.println("Test not run, NO jmods directory");
            return;
        }

        //Order of plugins
        checkOrder();

        //Last plugin that can sort resources
        checkLastSorter();

        //Resources class
        checkResources();

        // Exclude files plugin
        check("*.jcov", "/num/toto.jcov", true);
        check("*.jcov", "//toto.jcov", true);
        check("*.jcov", "/toto.jcov/tutu/tata", false);
        check("/java.base/*.jcov", "/java.base/toto.jcov", true);
        check("/java.base/toto.jcov", "t/java.base/iti.jcov", false);
        check("/java.base/*/toto.jcov", "/java.base/toto.jcov", false);
        check("/java.base/*/toto.jcov", "/java.base/tutu/toto.jcov", true);
        check("*/java.base/*/toto.jcov", "/tutu/java.base/tutu/toto.jcov", true);

        check("*/META-INF/*", "/META-INF/services/  MyProvider ", false);
        check("*/META-INF/*", "/META-INF/services/MyProvider", false);
        check("*/META-INF", " /META-INF/services/MyProvider", false);
        check("*/META-INF/*", "/java.base//META-INF/services/MyProvider", true);
        check("/java.base/*/Toto$Titi.class", "/java.base/tutu/Toto$Titi.class",
                true);
        check("/*$*.class", "/java.base/tutu/Toto$Titi.class", true);
        check("*$*.class", "/java.base/tutu/Toto$Titi.class", true);

        FileSystem fs = FileSystems.getFileSystem(URI.create("jrt:/"));
        Path javabase = fs.getPath("/modules/java.base");

        // ZIP
        List<Path> covered = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(javabase)) {
            stream.forEach((p) -> {
                if (Files.isRegularFile(p)) {
                    try {
                        byte[] content = readAllBytes(Files.newInputStream(p));
                        check(p, content);
                        covered.add(p);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }
            });
        }
        if (covered.isEmpty()) {
            throw new Exception("No class to compress");
        } else {
            System.out.println("zipped/unzipped " + covered.size() + " classes");
        }

        // Strip debug
        List<Path> covered2 = new ArrayList<>();
        JImageGenerator helper = new JImageGenerator(new File("."), jdkHome);
        String[] classes = {"toto.Main", "toto.com.foo.bar.X"};
        File moduleFile = helper.generateModuleCompiledClasses("leaf1", classes);
        // Classes have been compiled in debug.
        try (java.util.stream.Stream<Path> stream = Files.walk(moduleFile.toPath())) {
            stream.forEach((p) -> {
                if (Files.isRegularFile(p) && p.toString().endsWith(".class")) {
                    try {
                        byte[] content = readAllBytes(Files.newInputStream(p));
                        String path = "/leaf1/";
                        if (p.endsWith("toto/Main.class")) {
                            path += "toto/Main.class";
                        } else {
                            if (p.endsWith("module-info.class")) {
                                path += "module-info.class";
                            } else {
                                if (p.endsWith("toto/com/foo/bar/X.class")) {
                                    path += "toto/com/foo/bar/X.class";
                                }
                            }
                        }
                        check(path, content);
                        covered2.add(p);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }
            });
        }
        if (covered2.isEmpty()) {
            throw new Exception("No class to compress");
        } else {
            System.out.println("removed debug attributes from "
                    + covered2.size() + " classes");
        }
    }

    private static void checkResources() throws Exception {
        ResourcePool resources = new ResourcePoolImpl(ByteOrder.nativeOrder());
        Resource res1 = new Resource("/module1/toto1", ByteBuffer.allocate(0));
        Resource res2 = new Resource("/module2/toto1", ByteBuffer.allocate(0));
        resources.addResource(res1);
        resources.addResource(res2);

        checkResources(resources, res1, res2);
        ResourcePool resources2 = new ResourcePoolImpl(resources.getByteOrder());
        resources2.addTransformedResource(res2, ByteBuffer.allocate(7));
        resources2.addResource(CompressedResource.newCompressedResource(res1,
                ByteBuffer.allocate(7), "zip", null, new StringTable() {

                    @Override
                    public int addString(String str) {
                        return -1;
                    }

                    @Override
                    public String getString(int id) {
                        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
                    }
                }, ByteOrder.nativeOrder()));
        checkResources(resources2, res1, res2);
    }

    private static void checkResources(ResourcePool resources, Resource... expected)
            throws Exception {
        for (Resource res : expected) {
            if (!resources.contains(res)) {
                throw new Exception("Resources not found");
            }

            if (resources.getResource(res.getPath()) == null) {
                throw new Exception("Resources not found");
            }

//            Set<String> modules = resources.getModulePackages();
//            if (!modules.contains(res.getModule())) {
//                throw new Exception("Module not found");
//            }
            if (!resources.getResources().contains(res)) {
                throw new Exception("Resources not found");
            }

            boolean failed = false;
            try {
                resources.addResource(res);
            } catch (Exception ex) {
                failed = true;
            }
            if (!failed) {
                throw new Exception("Should have failed");
            }
        }

        if (resources.isReadOnly()) {
            throw new Exception("ReadOnly resources");
        }

        ((ResourcePoolImpl) resources).setReadOnly();
        boolean failed = false;
        try {
            resources.addResource(new Resource("module2/toto1",
                    ByteBuffer.allocate(0)));
        } catch (Exception ex) {
            failed = true;
        }
        if (!failed) {
            throw new Exception("Should have failed");
        }
    }

    private static void checkLastSorter() throws Exception {

        ImagePluginProviderRepository.registerPluginProvider(new SorterProvider("sorterplugin1"));
        ImagePluginProviderRepository.registerPluginProvider(new SorterProvider("sorterplugin2"));
        ImagePluginProviderRepository.registerPluginProvider(new SorterProvider("sorterplugin3"));
        ImagePluginProviderRepository.registerPluginProvider(new SorterProvider("sorterplugin4"));
        ImagePluginProviderRepository.registerPluginProvider(new SorterProvider("sorterplugin5"));

        //check unknown plugin
        {
            Properties props = new Properties();
            props.setProperty(ImagePluginConfiguration.FILTER_PROPERTY, "sorterplugin1");
            props.setProperty(ImagePluginConfiguration.TRANSFORMER_PROPERTY, "sorterplugin2");
            props.setProperty(ImagePluginConfiguration.SORTER_PROPERTY, "sorterplugin3");
            props.setProperty(ImagePluginConfiguration.COMPRESSOR_PROPERTY, "sorterplugin4");

            props.setProperty(ImagePluginConfiguration.LAST_SORTER_PROPERTY, "sorterplugin5");
            boolean failed = true;
            try {
                ImagePluginStack stack = ImagePluginConfiguration.parseConfiguration(props);
                failed = false;
            } catch (Exception ex) {
                // XXX OK expected
            }
            if (!failed) {
                throw new Exception("Unknown plugin should have failed.");
            }

        }
        {
            Properties props = new Properties();
            // plugin3 is the last one...sorter4 should fail...
            props.setProperty(ImagePluginConfiguration.FILTER_PROPERTY, "sorterplugin1");
            props.setProperty(ImagePluginConfiguration.TRANSFORMER_PROPERTY, "sorterplugin2");
            props.setProperty(ImagePluginConfiguration.SORTER_PROPERTY, "sorterplugin3");
            props.setProperty(ImagePluginConfiguration.COMPRESSOR_PROPERTY, "sorterplugin4");

            props.setProperty("sorterplugin1." + PluginProvider.CONFIGURATION_PROPERTY, "/c");
            props.setProperty("sorterplugin2." + PluginProvider.CONFIGURATION_PROPERTY, "/b");
            props.setProperty("sorterplugin3." + PluginProvider.CONFIGURATION_PROPERTY, "/a");
            props.setProperty("sorterplugin4." + PluginProvider.CONFIGURATION_PROPERTY, "/d");

            props.setProperty(ImagePluginConfiguration.LAST_SORTER_PROPERTY, "sorterplugin3");

            ImagePluginStack stack = ImagePluginConfiguration.parseConfiguration(props);

            // check order
            ResourcePoolImpl res = new ResourcePoolImpl(ByteOrder.nativeOrder());
            res.addResource(new Resource("/eee/bbb/res1.class", ByteBuffer.allocate(90)));
            res.addResource(new Resource("/aaaa/bbb/res2.class", ByteBuffer.allocate(90)));
            res.addResource(new Resource("/bbb/aa/res1.class", ByteBuffer.allocate(90)));
            res.addResource(new Resource("/aaaa/bbb/res3.class", ByteBuffer.allocate(90)));
            res.addResource(new Resource("/bbb/aa/res2.class", ByteBuffer.allocate(90)));
            res.addResource(new Resource("/fff/bbb/res1.class", ByteBuffer.allocate(90)));
            res.addResource(new Resource("/aaaa/bbb/res1.class", ByteBuffer.allocate(90)));
            res.addResource(new Resource("/bbb/aa/res3.class", ByteBuffer.allocate(90)));
            res.addResource(new Resource("/ccc/bbb/res1.class", ByteBuffer.allocate(90)));
            res.addResource(new Resource("/ddd/bbb/res1.class", ByteBuffer.allocate(90)));
            boolean fail = true;
            try {
                stack.visit(res, new StringTable() {

                    @Override
                    public int addString(String str) {
                        return -1;
                    }

                    @Override
                    public String getString(int id) {
                        return null;
                    }
                });
                fail = false;
            } catch (Exception ex) {
                // XXX OK expected
            }
            if (!fail) {
                throw new Exception("Should have failed");
            }
        }
        {
            Properties props = new Properties();
            // plugin3 is the last one
            props.setProperty(ImagePluginConfiguration.FILTER_PROPERTY, "sorterplugin1");
            props.setProperty(ImagePluginConfiguration.TRANSFORMER_PROPERTY, "sorterplugin2");
            props.setProperty(ImagePluginConfiguration.SORTER_PROPERTY, "sorterplugin3");

            props.setProperty("sorterplugin1." + PluginProvider.CONFIGURATION_PROPERTY, "/c");
            props.setProperty("sorterplugin2." + PluginProvider.CONFIGURATION_PROPERTY, "/b");
            props.setProperty("sorterplugin3." + PluginProvider.CONFIGURATION_PROPERTY, "/a");

            props.setProperty(ImagePluginConfiguration.LAST_SORTER_PROPERTY, "sorterplugin3");

            ImagePluginStack stack = ImagePluginConfiguration.parseConfiguration(props);

            // check order
            ResourcePoolImpl res = new ResourcePoolImpl(ByteOrder.nativeOrder());
            res.addResource(new Resource("/eee/bbb/res1.class", ByteBuffer.allocate(90)));
            res.addResource(new Resource("/aaaa/bbb/res2.class", ByteBuffer.allocate(90)));
            res.addResource(new Resource("/bbb/aa/res1.class", ByteBuffer.allocate(90)));
            res.addResource(new Resource("/aaaa/bbb/res3.class", ByteBuffer.allocate(90)));
            res.addResource(new Resource("/bbb/aa/res2.class", ByteBuffer.allocate(90)));
            res.addResource(new Resource("/fff/bbb/res1.class", ByteBuffer.allocate(90)));
            res.addResource(new Resource("/aaaa/bbb/res1.class", ByteBuffer.allocate(90)));
            res.addResource(new Resource("/bbb/aa/res3.class", ByteBuffer.allocate(90)));
            res.addResource(new Resource("/ccc/bbb/res1.class", ByteBuffer.allocate(90)));
            res.addResource(new Resource("/ddd/bbb/res1.class", ByteBuffer.allocate(90)));

            stack.visit(res, new StringTable() {

                @Override
                public int addString(String str) {
                    return -1;
                }

                @Override
                public String getString(int id) {
                    return null;
                }
            });
        }

    }

    private static void checkOrder() throws Exception {

        // Check next index computation
        Properties props = new Properties();
        props.setProperty(ImagePluginConfiguration.FILTER_PROPERTY, "plugin1");
        props.setProperty(ImagePluginConfiguration.TRANSFORMER_PROPERTY, "plugin2");
        props.setProperty(ImagePluginConfiguration.SORTER_PROPERTY, "plugin3");
        props.setProperty(ImagePluginConfiguration.COMPRESSOR_PROPERTY, "plugin4");
        int i = ImagePluginConfiguration.getNextIndex(props,
                ImagePluginConfiguration.FILTER);
        if (i != ImagePluginConfiguration.getRange(ImagePluginConfiguration.FILTER)[0] + 1) {
            throw new Exception("Unexpected index " + i);
        }
        i = ImagePluginConfiguration.getNextIndex(props,
                ImagePluginConfiguration.TRANSFORMER);
        if (i != ImagePluginConfiguration.getRange(ImagePluginConfiguration.TRANSFORMER)[0] + 1) {
            throw new Exception("Unexpected index " + i);
        }
        i = ImagePluginConfiguration.getNextIndex(props,
                ImagePluginConfiguration.SORTER);
        if (i != ImagePluginConfiguration.getRange(ImagePluginConfiguration.SORTER)[0] + 1) {
            throw new Exception("Unexpected index " + i);
        }
        i = ImagePluginConfiguration.getNextIndex(props,
                ImagePluginConfiguration.COMPRESSOR);
        if (i != ImagePluginConfiguration.getRange(ImagePluginConfiguration.COMPRESSOR)[0] + 1) {
            throw new Exception("Unexpected index " + i);
        }

        props = new Properties();
        props.setProperty(ImagePluginConfiguration.FILTER_PROPERTY + ".500",
                "plugin1");
        i = ImagePluginConfiguration.getNextIndex(props,
                ImagePluginConfiguration.FILTER);
        if (i != ImagePluginConfiguration.getRange(ImagePluginConfiguration.FILTER)[0] + 501) {
            throw new Exception("Unexpected index " + i);
        }

        ImagePluginProviderRepository.registerPluginProvider(new PProvider("plugin1"));
        ImagePluginProviderRepository.registerPluginProvider(new PProvider("plugin2"));
        ImagePluginProviderRepository.registerPluginProvider(new PProvider("plugin3"));
        ImagePluginProviderRepository.registerPluginProvider(new PProvider("plugin4"));
        ImagePluginProviderRepository.registerPluginProvider(new PProvider("plugin5"));
        ImagePluginProviderRepository.registerPluginProvider(new PProvider("plugin6"));
        ImagePluginProviderRepository.registerPluginProvider(new PProvider("plugin7"));
        ImagePluginProviderRepository.registerPluginProvider(new PProvider("plugin8"));

        props = new Properties();
        props.setProperty(ImagePluginConfiguration.FILTER_PROPERTY, "plugin1");
        props.setProperty(ImagePluginConfiguration.TRANSFORMER_PROPERTY, "plugin2");
        props.setProperty(ImagePluginConfiguration.SORTER_PROPERTY, "plugin3");
        props.setProperty(ImagePluginConfiguration.COMPRESSOR_PROPERTY, "plugin4");

        List<String> expected = new ArrayList<>();
        expected.add("plugin1");
        expected.add("plugin2");
        expected.add("plugin3");
        expected.add("plugin4");

        check(props, expected);

        props = new Properties();
        props.setProperty(ImagePluginConfiguration.FILTER_PROPERTY + ".250",
                "plugin4");
        props.setProperty(ImagePluginConfiguration.TRANSFORMER_PROPERTY + ".100",
                "plugin3");
        props.setProperty(ImagePluginConfiguration.SORTER_PROPERTY + ".50",
                "plugin2");
        props.setProperty(ImagePluginConfiguration.COMPRESSOR_PROPERTY + ".10",
                "plugin1");

        expected = new ArrayList<>();
        expected.add("plugin4");
        expected.add("plugin3");
        expected.add("plugin2");
        expected.add("plugin1");

        check(props, expected);

        props = new Properties();
        props.setProperty(ImagePluginConfiguration.FILTER_PROPERTY + ".0",
                "plugin2");
        props.setProperty(ImagePluginConfiguration.FILTER_PROPERTY + ".1",
                "plugin3");
        props.setProperty(ImagePluginConfiguration.FILTER_PROPERTY + ".2",
                "plugin4");
        props.setProperty(ImagePluginConfiguration.FILTER_PROPERTY + ".3",
                "plugin1");

        expected = new ArrayList<>();
        expected.add("plugin2");
        expected.add("plugin3");
        expected.add("plugin4");
        expected.add("plugin1");

        check(props, expected);

        props = new Properties();
        props.setProperty(ImagePluginConfiguration.TRANSFORMER_PROPERTY + ".0",
                "plugin2");
        props.setProperty(ImagePluginConfiguration.TRANSFORMER_PROPERTY + ".1",
                "plugin3");
        props.setProperty(ImagePluginConfiguration.TRANSFORMER_PROPERTY + ".2",
                "plugin4");
        props.setProperty(ImagePluginConfiguration.TRANSFORMER_PROPERTY + ".3",
                "plugin1");

        expected = new ArrayList<>();
        expected.add("plugin2");
        expected.add("plugin3");
        expected.add("plugin4");
        expected.add("plugin1");

        check(props, expected);

        props = new Properties();
        props.setProperty(ImagePluginConfiguration.SORTER_PROPERTY + ".0",
                "plugin2");
        props.setProperty(ImagePluginConfiguration.SORTER_PROPERTY + ".1",
                "plugin3");
        props.setProperty(ImagePluginConfiguration.SORTER_PROPERTY + ".2",
                "plugin4");
        props.setProperty(ImagePluginConfiguration.SORTER_PROPERTY + ".3",
                "plugin1");

        expected = new ArrayList<>();
        expected.add("plugin2");
        expected.add("plugin3");
        expected.add("plugin4");
        expected.add("plugin1");

        check(props, expected);

        props = new Properties();
        props.setProperty(ImagePluginConfiguration.COMPRESSOR_PROPERTY + ".0",
                "plugin2");
        props.setProperty(ImagePluginConfiguration.COMPRESSOR_PROPERTY + ".1",
                "plugin3");
        props.setProperty(ImagePluginConfiguration.COMPRESSOR_PROPERTY + ".2",
                "plugin4");
        props.setProperty(ImagePluginConfiguration.COMPRESSOR_PROPERTY + ".3",
                "plugin1");

        expected = new ArrayList<>();
        expected.add("plugin2");
        expected.add("plugin3");
        expected.add("plugin4");
        expected.add("plugin1");

        check(props, expected);

        props = new Properties();
        props.setProperty(ImagePluginConfiguration.RADICAL_PROPERTY
                + ImagePluginConfiguration.getRange(ImagePluginConfiguration.FILTER)[0],
                "plugin1");
        props.setProperty(ImagePluginConfiguration.RADICAL_PROPERTY
                + ImagePluginConfiguration.getRange(ImagePluginConfiguration.TRANSFORMER)[0],
                "plugin3");
        props.setProperty(ImagePluginConfiguration.RADICAL_PROPERTY
                + ImagePluginConfiguration.getRange(ImagePluginConfiguration.SORTER)[0],
                "plugin2");
        props.setProperty(ImagePluginConfiguration.RADICAL_PROPERTY
                + ImagePluginConfiguration.getRange(ImagePluginConfiguration.COMPRESSOR)[0],
                "plugin4");

        expected = new ArrayList<>();
        expected.add("plugin1");
        expected.add("plugin3");
        expected.add("plugin2");
        expected.add("plugin4");

        check(props, expected);

        props = new Properties();
        props.setProperty(ImagePluginConfiguration.FILTER_PROPERTY, "plugin1");
        props.setProperty(ImagePluginConfiguration.TRANSFORMER_PROPERTY, "plugin2");
        props.setProperty(ImagePluginConfiguration.SORTER_PROPERTY, "plugin3");
        props.setProperty(ImagePluginConfiguration.COMPRESSOR_PROPERTY, "plugin4");
        props.setProperty(ImagePluginConfiguration.RADICAL_PROPERTY
                + (ImagePluginConfiguration.getRange(ImagePluginConfiguration.FILTER)[0] + 1),
                "plugin5");
        props.setProperty(ImagePluginConfiguration.RADICAL_PROPERTY
                + (ImagePluginConfiguration.getRange(ImagePluginConfiguration.TRANSFORMER)[0] + 1),
                "plugin6");
        props.setProperty(ImagePluginConfiguration.RADICAL_PROPERTY
                + (ImagePluginConfiguration.getRange(ImagePluginConfiguration.SORTER)[0] + 1),
                "plugin7");
        props.setProperty(ImagePluginConfiguration.RADICAL_PROPERTY
                + (ImagePluginConfiguration.getRange(ImagePluginConfiguration.COMPRESSOR)[0] + 1),
                "plugin8");

        expected = new ArrayList<>();
        expected.add("plugin1");
        expected.add("plugin5");
        expected.add("plugin2");
        expected.add("plugin6");
        expected.add("plugin3");
        expected.add("plugin7");
        expected.add("plugin4");
        expected.add("plugin8");

        check(props, expected);

        // Now invalid properties
        boolean failed = false;
        try {
            props = new Properties();
            props.setProperty(ImagePluginConfiguration.FILTER_PROPERTY + ".0.90",
                    "plugin1");
            ImagePluginStack plugins
                    = ImagePluginConfiguration.parseConfiguration(props);
        } catch (Exception ex) {
            failed = true;
        }
        if (!failed) {
            throw new Exception("Test case should have failed");
        }

        try {
            props = new Properties();
            props.setProperty(ImagePluginConfiguration.RADICAL_PROPERTY + "90.23",
                    "plugin1");
            ImagePluginStack plugins
                    = ImagePluginConfiguration.parseConfiguration(props);
        } catch (Exception ex) {
            failed = true;
        }
        if (!failed) {
            throw new Exception("Test case should have failed");
        }

        try {
            props = new Properties();
            props.setProperty(ImagePluginConfiguration.RADICAL_PROPERTY,
                    "plugin1");
            ImagePluginStack plugins
                    = ImagePluginConfiguration.parseConfiguration(props);
        } catch (Exception ex) {
            failed = true;
        }
        if (!failed) {
            throw new Exception("Test case should have failed");
        }

        try {
            props = new Properties();
            props.setProperty(ImagePluginConfiguration.FILTER_PROPERTY, "plugin1");
            props.setProperty(ImagePluginConfiguration.RADICAL_PROPERTY
                    + ImagePluginConfiguration.getRange(ImagePluginConfiguration.FILTER)[0],
                    "plugin5");
            ImagePluginStack plugins = ImagePluginConfiguration.
                    parseConfiguration(props);
        } catch (Exception ex) {
            failed = true;
        }
        if (!failed) {
            throw new Exception("Test case should have failed");
        }

    }

    private static void check(Properties props, List<String> expected)
            throws Exception {
        order.clear();
        ImagePluginStack plugins = ImagePluginConfiguration.parseConfiguration(props);
        ResourcePoolImpl pool = new ResourcePoolImpl(ByteOrder.nativeOrder());
        pool.addResource(new Resource("/mod/com/foo/bar/A.somthing",
                ByteBuffer.allocate(0)));
        plugins.visit(pool, new StringTable() {

            @Override
            public int addString(String str) {
                return -1;
            }

            @Override
            public String getString(int id) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        });
        if (!order.equals(expected)) {
            throw new Exception("plugins not called in right order. Expected "
                    + expected + " actual " + order);
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
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

    private static void check(String path, byte[] content) throws Exception {
        StripDebugProvider prov = new StripDebugProvider();
        Plugin debug = (Plugin) prov.newPlugin(new Properties());
        ResourcePool resources = new ResourcePoolImpl(ByteOrder.nativeOrder());
        Resource res = new Resource(path, ByteBuffer.wrap(content));
        resources.addResource(res);
        ResourcePool results = new ResourcePoolImpl(resources.getByteOrder());
        debug.visit(resources, results, new StringTable() {

            @Override
            public int addString(String str) {
                return -1;
            }

            @Override
            public String getString(int id) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        });
        Resource result = results.getResource(res.getPath());
        if (result.getLength() >= content.length) {
            if (!path.endsWith("module-info.class")) {
                throw new Exception("Class size not reduced, debug info not "
                        + "removed for " + path);
            }
        }
        byte[] strip = result.getByteArray();
        ResourcePool resources2 = new ResourcePoolImpl(ByteOrder.nativeOrder());
        resources2.addResource(result);
        ResourcePool results2 = new ResourcePoolImpl(resources.getByteOrder());
        debug.visit(resources2, results2, new StringTable() {

            @Override
            public int addString(String str) {
                return -1;
            }

            @Override
            public String getString(int id) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        });
        Resource result2 = results2.getResource(res.getPath());
        if (strip.length != result2.getLength()) {
            throw new Exception("removing debug info twice reduces class size of "
                    + path);
        }
    }

    private static void check(Path p, byte[] content) throws Exception {
        ZipCompressProvider prov = new ZipCompressProvider();
        Plugin compressor = (Plugin) prov.newPlugin(new Properties());
        ResourcePool resources = new ResourcePoolImpl(ByteOrder.nativeOrder());
        Resource res = new Resource("/toto/com.foo.Bar", ByteBuffer.wrap(content));
        resources.addResource(res);
        ResourcePool results = new ResourcePoolImpl(ByteOrder.nativeOrder());
        compressor.visit(resources, results, new StringTable() {

            @Override
            public int addString(String str) {
                return 999;
            }

            @Override
            public String getString(int id) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        });
        Resource result = results.getResource(res.getPath());
        CompressedResourceHeader header
                = CompressedResourceHeader.readFromResource(ByteOrder.nativeOrder(),
                        result.getByteArray());
        if (!header.isTerminal()) {
            throw new Exception("Not terminal plugin");
        }
        if (header.getDecompressorNameOffset() != 999) {
            throw new Exception("Invalid plugin offset "
                    + header.getDecompressorNameOffset());
        }
        if (header.getUncompressedSize() != content.length) {
            throw new Exception("Invalid uncompressed size "
                    + header.getUncompressedSize());
        }
        if (header.getResourceSize() <= 0) {
            throw new Exception("Invalid compressed size "
                    + header.getResourceSize());
        }

        ZipDecompressorFactory prov2 = new ZipDecompressorFactory();
        ResourceDecompressor decompressor = (ResourceDecompressor) prov2.
                newDecompressor(new Properties());
        byte[] decompressed = decompressor.decompress(null, result.getByteArray(),
                CompressedResourceHeader.getSize(), content.length);
        if (decompressed.length != content.length) {
            throw new Exception("NOT decompressed");
        }

        for (int i = 0; i < decompressed.length; i++) {
            if (decompressed[i] != content[i]) {
                throw new Exception("Decompressed and original differ at index "
                        + i);
            }
        }
    }

    private static void check(String s, String sample, boolean exclude)
            throws Exception {
        Properties p = new Properties();
        p.setProperty(PluginProvider.CONFIGURATION_PROPERTY, s);
        ExcludeProvider provider = new ExcludeProvider();
        Plugin plug = (Plugin) provider.newPlugin(p);
        ResourcePool resources = new ResourcePoolImpl(ByteOrder.nativeOrder());
        Resource resource = new Resource(sample, ByteBuffer.allocate(0));
        resources.addResource(resource);
        ResourcePool result = new ResourcePoolImpl(ByteOrder.nativeOrder());
        plug.visit(resources, result, new StringTable() {

            @Override
            public int addString(String str) {
                return -1;
            }

            @Override
            public String getString(int id) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        });
        if (exclude) {
            if (result.getResources().contains(resource)) {
                throw new Exception(sample + " should be excluded by " + s);
            }
        } else {
            if (!result.getResources().contains(resource)) {
                throw new Exception(sample + " shouldn't be excluded by " + s);
            }
        }
    }
}
