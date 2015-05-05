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
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import jdk.internal.jimage.decompressor.CompressIndexes;
import jdk.internal.jimage.decompressor.ResourceDecompressor;
import jdk.internal.jimage.decompressor.ZipDecompressorFactory;
import jdk.tools.jlink.plugins.ResourcePool;
import jdk.tools.jlink.plugins.ResourcePool.Resource;
import jdk.tools.jlink.internal.ImagePluginConfiguration;
import jdk.internal.jimage.decompressor.CompressedResourceHeader;
import jdk.internal.jimage.decompressor.ResourceDecompressorFactory;
import jdk.internal.jimage.decompressor.SignatureParser;
import jdk.internal.jimage.decompressor.SignatureParser.ParseResult;
import jdk.internal.jimage.decompressor.StringSharingDecompressor;
import jdk.internal.jimage.decompressor.StringSharingDecompressorFactory;
import jdk.tools.jlink.internal.ImageFilePoolImpl;
import jdk.tools.jlink.plugins.ImageFilePlugin;
import jdk.tools.jlink.plugins.ImageFilePool;
import jdk.tools.jlink.plugins.ImageFilePool.ImageFile;
import jdk.tools.jlink.plugins.ResourcePlugin;
import jdk.tools.jlink.plugins.ResourcePluginProvider;
import jdk.tools.jlink.internal.plugins.DefaultCompressProvider;
import jdk.tools.jlink.internal.plugins.StringSharingProvider;
import jdk.tools.jlink.plugins.Plugin;
import jdk.tools.jlink.plugins.PluginProvider;
import jdk.tools.jlink.internal.ImagePluginProviderRepository;
import jdk.tools.jlink.internal.ImagePluginStack;
import jdk.tools.jlink.internal.ResourcePoolImpl;
import jdk.tools.jlink.internal.plugins.ExcludeFilesProvider;
import jdk.tools.jlink.plugins.ResourcePool.CompressedResource;
import jdk.tools.jlink.plugins.StringTable;
import jdk.tools.jlink.internal.plugins.ZipCompressProvider;
import jdk.tools.jlink.internal.plugins.ExcludeProvider;
import jdk.tools.jlink.internal.plugins.StripDebugProvider;
import tests.JImageGenerator;
import tests.JImageValidator;

/*
 * @test
 * @summary Test jlink plugins
 * @author Jean-Francois Denise
 * @library /lib/testlibrary/jlink
 * @modules java.base/jdk.internal.jimage
 *          java.base/jdk.internal.jimage.decompressor
 *          jdk.compiler/com.sun.tools.classfile
 *          jdk.jlink/jdk.tools.jlink
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jlink.internal.plugins
 *          jdk.jlink/jdk.tools.jmod
 *          jdk.jlink/jdk.tools.jimage
 * @run build PluginsTest
 * @run build tests.*
 * @run main PluginsTest
 */

public class PluginsTest {

    private static final List<String> order = new ArrayList<>();

    private static class PluginTrap implements ResourcePlugin {

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

    private static class CategoryProvider extends ResourcePluginProvider {

        private final String category;

        CategoryProvider(String category) {
            super("CategoryProvider", "");
            this.category = category;
        }

        @Override
        public ResourcePlugin[] newPlugins(String[] argument,
                Map<String, String> options) throws IOException {
            throw new IOException("Shouldn't be called");
        }

        @Override
        public String getCategory() {
            return category;
        }

        @Override
        public String getToolArgument() {
            throw new UnsupportedOperationException("Shouldn't be called");
        }

        @Override
        public String getToolOption() {
            throw new UnsupportedOperationException("Shouldn't be called");
        }

        @Override
        public Map<String, String> getAdditionalOptions() {
            throw new UnsupportedOperationException("Shouldn't be called");
        }
    }

    private static class PProvider extends PluginProvider {

        PProvider(String name) {
            super(name, "");
        }

        @Override
        public Plugin[] newPlugins(String[] argument, Map<String, String> options) throws IOException {
            return new PluginTrap[]{new PluginTrap(getName())};
        }

        @Override
        public String getCategory() {
            return null;
        }

        @Override
        public String getToolOption() {
            return null;
        }

        @Override
        public String getToolArgument() {
            return null;
        }

        @Override
        public Map<String, String> getAdditionalOptions() {
            return null;
        }

    }

    private static class SorterPlugin implements ResourcePlugin {

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
        public Plugin[] newPlugins(String[] arguments, Map<String, String> options)
                throws IOException {
            return new Plugin[]{new SorterPlugin(getName(),
                arguments == null ? null : arguments[0])};
        }

        @Override
        public String getCategory() {
            return null;
        }

        @Override
        public String getToolOption() {
            return null;
        }

        @Override
        public String getToolArgument() {
            return null;
        }

        @Override
        public Map<String, String> getAdditionalOptions() {
            return null;
        }

    }

    private static class OptionsProvider extends PluginProvider {

        private static final String[] OPTIONS = {"a", "nnn", "cccc"};
        private Map<String, String> options;

        OptionsProvider() {
            super("Config", "");
        }

        @Override
        public Plugin[] newPlugins(String[] argument, Map<String, String> options)
                throws IOException {
            if (options.size() != OPTIONS.length) {
                throw new IOException("Invalid options");
            }
            for (String o : OPTIONS) {
                if (!options.keySet().contains(o)) {
                    throw new IOException("Invalid option " + o);
                }
            }
            this.options = options;
            return null;
        }

        @Override
        public String getCategory() {
            return null;
        }

        @Override
        public String getToolOption() {
            return null;
        }

        @Override
        public String getToolArgument() {
            return null;
        }

        @Override
        public Map<String, String> getAdditionalOptions() {
            Map<String, String> m = new HashMap<>();
            for (String o : OPTIONS) {
                m.put(o, o);
            }
            return m;
        }

    }

    private static int strID = 1;

    public static void main(String[] args) throws Exception {
        File jdkHome = new File(System.getProperty("test.jdk"));
        // JPRT not yet ready for jmods
        if (JImageGenerator.getJModsDir(jdkHome) == null) {
            System.err.println("Test not run, NO jmods directory");
            return;
        }

        FileSystem fs;
        try {
            fs = FileSystems.getFileSystem(URI.create("jrt:/"));
        } catch (ProviderNotFoundException | FileSystemNotFoundException e) {
            System.out.println("Not an image build, test skipped.");
            return;
        }
        Path javabase = fs.getPath("/modules/java.base");

        //Order of plugins
        checkOrder();

        //Last plugin that can sort resources
        checkLastSorter();

        //Resources class
        checkResources();

        // Exclude resources plugin
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

        // Exclude files plugin
        checkFiles("*.jcov", "/num/toto.jcov", "", true);
        checkFiles("*.jcov", "//toto.jcov", "", true);
        checkFiles("*.jcov", "/toto.jcov/tutu/tata", "", false);
        checkFiles("/java.base/*.jcov", "/toto.jcov", "java.base", true);
        checkFiles("/java.base/toto.jcov", "/iti.jcov", "t/java.base", false);
        checkFiles("/java.base/*/toto.jcov", "/toto.jcov", "java.base", false);
        checkFiles("/java.base/*/toto.jcov", "/tutu/toto.jcov", "java.base", true);
        checkFiles("*/java.base/*/toto.jcov", "/java.base/tutu/toto.jcov", "/tutu", true);

        checkFiles("/*$*.properties", "/tutu/Toto$Titi.properties", "java.base", true);
        checkFiles("*$*.properties", "/tutu/Toto$Titi.properties", "java.base", true);

        // Provider config
        OptionsProvider prov = new OptionsProvider();
        Properties props = new Properties();
        for (String c : OptionsProvider.OPTIONS) {
            props.put(c, c);
        }
        prov.newPlugins(props);
        if (prov.options == null) {
            throw new Exception("Something wrong occured, no config");
        }

        // Compression
        List<Path> covered = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(javabase)) {
            stream.forEach((p) -> {
                if (Files.isRegularFile(p)) {
                    try {
                        byte[] content = readAllBytes(Files.newInputStream(p));
                        checkCompress(p, content, new ZipCompressProvider(), null,
                                new ResourceDecompressorFactory[]{
                                    new ZipDecompressorFactory()});

                        if (p.toString().endsWith(".class")) {
                            checkCompress(p, content, new StringSharingProvider(), null,
                                    new ResourceDecompressorFactory[]{
                                        new StringSharingDecompressorFactory()});
                        }

                        if (p.toString().endsWith(".class")) {
                            // compress == ZIP + Strings sharing
                            Properties options = new Properties();
                            options.setProperty(PluginProvider.TOOL_ARGUMENT_PROPERTY,
                                    ImagePluginConfiguration.ON_ARGUMENT);
                            checkCompress(p, content, new DefaultCompressProvider(), options,
                                    new ResourceDecompressorFactory[]{
                                        new ZipDecompressorFactory(),
                                        new StringSharingDecompressorFactory()
                                    });
                            // compress level 1 == ZIP + Strings sharing
                            Properties options1 = new Properties();
                            options1.setProperty(PluginProvider.TOOL_ARGUMENT_PROPERTY,
                                    ImagePluginConfiguration.ON_ARGUMENT);
                            options1.setProperty(DefaultCompressProvider.LEVEL_OPTION, "1");
                            checkCompress(p, content, new DefaultCompressProvider(),
                                    options1,
                                    new ResourceDecompressorFactory[]{
                                        new ZipDecompressorFactory(),
                                        new StringSharingDecompressorFactory()
                                    });
                            // compress level 0 == Strings sharing
                            Properties options0 = new Properties();
                            options0.setProperty(PluginProvider.TOOL_ARGUMENT_PROPERTY,
                                    ImagePluginConfiguration.ON_ARGUMENT);
                            options0.setProperty(DefaultCompressProvider.LEVEL_OPTION, "0");
                            checkCompress(p, content, new DefaultCompressProvider(),
                                    options0,
                                    new ResourceDecompressorFactory[]{
                                        new StringSharingDecompressorFactory()});
                        }

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

        // Compact Constant Pool
        ResourcePool resources = new ResourcePoolImpl(ByteOrder.nativeOrder());
        Consumer<Path> c = (p) -> {
            // take only the .class resources.
            if (Files.isRegularFile(p) && p.toString().endsWith(".class")
                    && !p.toString().endsWith("module-info.class")) {
                try {
                    byte[] content = readAllBytes(Files.newInputStream(p));
                    String path = p.toString();
                    path = path.substring("/modules".length());
                    Resource res = new Resource(path, ByteBuffer.wrap(content));
                    resources.addResource(res);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        };
        ResourcePlugin plugin = new StringSharingProvider().newPlugins(null, null)[0];
        Map<String, Integer> map = new HashMap<>();
        Map<Integer, String> reversedmap = new HashMap<>();
        ResourcePoolImpl result = new ResourcePoolImpl(resources.getByteOrder());
        plugin.visit(resources, result, new StringTable() {

            @Override
            public int addString(String str) {
                Integer id = map.get(str);
                if (id == null) {
                    id = strID;
                    map.put(str, id);
                    reversedmap.put(id, str);
                    strID += 1;
                }
                return id;
            }

            @Override
            public String getString(int id) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        });

        for (Resource res : result.getResources()) {
            if (res.getPath().endsWith(".class")) {
                byte[] uncompacted = StringSharingDecompressor.normalize((int offset) -> {
                    return reversedmap.get(offset);
                }, res.getByteArray(), 0);
                JImageValidator.readClass(uncompacted);
            }
        }

        // Compact constantpool Index compression
        checkCompressIndexes();

        //Compact constantpool Signature Parser
        checkSignatureParser();
    }

    private static void checkSignatureParser() throws Exception {
        System.out.println(parse("[Ljava/lang/String;"));
        System.out.println(parse("[[[[[[[[[[Ljava/lang/String;"));
        System.out.println(parse("<T:Ljava/lang/Object;:"
                + "Ljava/lang/Comparable<-TT;>;>(Ljava/lang/String;"
                + "Ljava/lang/Class<TT;>;TT;Ljava/lang/Comparable<-TT;>;"
                + "Ljava/lang/Comparable<-TT;>;ZZ)V"));
        System.out.println(parse("(Ljava/lang/String;Z"
                + "Ljava/util/EventListener;TTK;)V"));
        System.out.println(parse("<Y:Ljava/lang/String;>"));
        System.out.println(parse("<Y:Ljava/lang/String;Z::"
                + "Ljava/util/EventListener;>"));
        System.out.println(parse("<Y:Ljava/lang/String;Z::"
                + "Ljava/util/EventListener;O::Ljava/lang/Comparable<Ljava/lang/String;>;>"));
        System.out.println(parse("<Y:Ljava/lang/String;O::"
                + "Ljava/lang/Comparable<Ljava/lang/String;Ljava/lang/Float;>;>"));
        System.out.println(parse("<Y:Ljava/lang/String;O::"
                + "Ljava/lang/Comparable<Ljava/lang/String;Ljava/lang/Float<Ljava/lang/Object;>;>;>"));
        System.out.println(parse("Ljava/util/Set;"));
        System.out.println(parse("Ljavaapplication20/Titi"
                + "<[Ljava/lang/String;Ljava/lang/Integer;>;"));
        System.out.println(parse("Ljava/lang/Comparable<TK;>;"));
        System.out.println(parse("Ljava/io/Serializable;"
                + "Ljava/lang/Comparable<TK;>;"));
        System.out.println(parse("<Y:Ljava/lang/String;Z::"
                + "Ljava/util/EventListener;K::Ljava/util/EventListener;O::"
                + "Ljava/lang/Comparable<Ljava/lang/String;>;>"
                + "Ljavaapplication20/Titi<[Ljava/lang/String;Ljava/lang/Integer;TZ;>;"
                + "Ljava/io/Serializable;Ljava/lang/Comparable<TK;>;"));
        System.out.println(parse("<PO:Ljava/lang/Object;>"
                + "(Ljava/lang/Integer;TPO;)Ljava/lang/Integer;"));
        System.out.println(parse("<PO:Ljava/lang/Object;>"
                + "(Ljava/lang/Integer;TPO;)TPO;"));
        System.out.println(parse("<T::Ljava/util/EventListener;>"
                + "(Ljava/lang/Class<TT;>;)[TT;"));
        System.out.println(parse("<PO:LTiti;>(Ljava/lang/Integer;"
                + "ITPO;)Z"));
        System.out.println(parse("<K:Ljava/lang/Object;V:"
                + "Ljava/lang/Object;>Ljava/lang/Object;"));
        System.out.println(parse("Ljava/util/LinkedHashMap<TK;TV;>."
                + "LinkedHashIterator;Ljava/util/Iterator<TV;>;"));
        System.out.println(parse("LToto<Ljava/lang/String;>;"));
        System.out.println(parse("Ljavaapplication20/Titi"
                + "<[Ljava/lang/String;Ljava/lang/Integer<LToto;>;TZ;>;"));
        System.out.println(parse("LX<[LQ;LW<LToto;>;TZ;>;"));
        System.out.println(parse("Ljava/lang/String<*>;"));
        System.out.println(parse("Ljava/util/List<[B>;"));
        System.out.println(parse("<T:Ljava/lang/Object;T_NODE::"
                + "Ljava/util/stream/Node<TT;>;>Ljava/lang/Object;Ljava/util/stream/Node<TT;>;"));
        System.out.println(parse("Ljavaapplication20/Titi"
                + "<[Ljava/lang/String;>;"));
        System.out.println(parse("<A::Ljava/lang/annotation/Annotation;"
                + "W::Lcom/sun/codemodel/internal/JAnnotationWriter<TA;>;>"
                + "Ljava/lang/Object;Ljava/lang/reflect/InvocationHandler;"
                + "Lcom/sun/codemodel/internal/JAnnotationWriter<TA;>;"));
        System.out.println(parse("<W::Lcom/sun/codemodel/internal/"
                + "JAnnotationWriter<*>;>(Ljava/lang/Class<TW;>;Lcom/sun/codemodel/"
                + "internal/JAnnotatable;)TW;"));
        System.out.println(parse("Ljava/util/Set<Lcom/sun/tools/jdeps/"
                + "JdepsTask$DotGraph<TT;>.Edge;>;"));
        System.out.println(parse("<E::Lcom/sun/xml/internal/rngom/"
                + "ast/om/ParsedElementAnnotation;L::Lcom/sun/xml/internal/rngom/ast/"
                + "om/Location;CL::Lcom/sun/xml/internal/rngom/ast/builder/"
                + "CommentList<TL;>;>Ljava/lang/Object;"));
        System.out.println(parse("(Ljava/util/List<Lcom/sun/xml/"
                + "internal/rngom/nc/NameClass;>;TL;TA;)Lcom/sun/xml/internal/"
                + "rngom/nc/NameClass;"));
        System.out.println(parse("[Ljava/util/List;"));
        System.out.println(parse("[Ljava/util/List<+Lcom/sun/jdi/"
                + "request/EventRequest;>;"));
        System.out.println(parse("Lcom/sun/xml/internal/bind/v2/"
                + "util/QNameMap<TV;>.HashIterator<Lcom/sun/xml/internal/bind/v2/util/"
                + "QNameMap$Entry<TV;>;>;"));
        System.out.println(parse("[Ljava/lang/String;"));
        System.out.println(parse("[Ljava/lang/String<Ljava/lang/Toto"
                + "<Ljava/lang/Titi;>;>;"));
        System.out.println(parse("<T::Ljava/util/EventListener;"
                + "K:Ljava/util/BOO;>(ZCLjava/lang/Class<TT;>;IJS)[TT;"));
        System.out.println(parse("<T:Ljava/lang/Object;>"
                + "(TT;ILjava/lang/Long;)TT;"));
        System.out.println(parse("<T:Ljava/lang/Object;>"
                + "(TT;ILjava/lang/Long;)TT;^TT;"));
        System.out.println(parse("<T:Ljava/lang/Object;>"
                + "(TT;ILjava/lang/Long;)TT;^TT;^L/java/lang/Exception;"));
    }

    private static void check(String orig, ParseResult res) {
        String str = SignatureParser.reconstruct(res.formatted, res.types);

        System.out.println(orig);
        System.out.println(str);
        if (!orig.equals(str)) {
            throw new IllegalArgumentException("Not reconstructed");
        }
    }

    private static List<String> parse(String type) throws Exception {
        ParseResult res = SignatureParser.parseSignatureDescriptor(type);
        System.out.println(type);
        System.out.println(res.formatted);
        System.out.println(res.types);
        check(type, res);
        return res.types;
    }

    private static void checkCompressIndexes() {
        int totalLength = 0;
        List<byte[]> arrays = new ArrayList<>();

        arrays.add(check(0x00000000, 1));
        arrays.add(check(0x00000001, 1));
        arrays.add(check(0x0000000F, 1));
        arrays.add(check(0x0000001F, 1));

        arrays.add(check(0x0000002F, 2));
        arrays.add(check(0x00000100, 2));
        arrays.add(check(0x00001FFF, 2));

        arrays.add(check(0x00002FFF, 3));
        arrays.add(check(0x00010000, 3));
        arrays.add(check(0x001FFFFF, 3));

        arrays.add(check(0x00200000, 4));
        arrays.add(check(0x01000000, 4));
        arrays.add(check(Integer.MAX_VALUE, 4));
        for (byte[] b : arrays) {
            totalLength += b.length;
        }
        ByteBuffer all = ByteBuffer.allocate(totalLength);
        for (byte[] b : arrays) {
            all.put(b);
        }
        byte[] flow = all.array();
        check(flow, arrays);
        System.out.println(arrays.size() * 4 + " compressed in " + flow.length
                + " gain of " + (100 - ((flow.length * 100) / (arrays.size() * 4))) + "%");
    }

    private static void check(byte[] flow, List<byte[]> arrays) {
        List<Integer> d = CompressIndexes.decompressFlow(flow);
        List<Integer> dd = new ArrayList<>();
        for (byte[] b : arrays) {
            int i = CompressIndexes.decompress(b, 0);
            dd.add(i);
        }
        if (!d.equals(dd)) {
            System.err.println(dd);
            System.err.println(d);
            throw new RuntimeException("Invalid flow " + d);
        } else {
            System.out.println("OK for flow");
        }
    }

    private static byte[] check(int val, int size) {
        byte[] c = CompressIndexes.compress(val);
        if (c.length != size) {
            throw new RuntimeException("Invalid compression size " + c.length);
        }
        int d = CompressIndexes.decompress(c, 0);
        if (val != d) {
            throw new RuntimeException("Invalid " + d);
        } else {
            System.out.println("Ok for " + val);
        }
        return c;
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
                        throw new UnsupportedOperationException("Not supported yet.");
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
            props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY, "sorterplugin1");
            props.setProperty(ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY, "sorterplugin2");
            props.setProperty(ImagePluginConfiguration.RESOURCES_SORTER_PROPERTY, "sorterplugin3");
            props.setProperty(ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY, "sorterplugin4");

            props.setProperty(ImagePluginConfiguration.RESOURCES_LAST_SORTER_PROPERTY, "sorterplugin5");
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
            props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY, "sorterplugin1");
            props.setProperty(ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY, "sorterplugin2");
            props.setProperty(ImagePluginConfiguration.RESOURCES_SORTER_PROPERTY, "sorterplugin3");
            props.setProperty(ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY, "sorterplugin4");

            props.setProperty("sorterplugin1." + PluginProvider.TOOL_ARGUMENT_PROPERTY, "/c");
            props.setProperty("sorterplugin2." + PluginProvider.TOOL_ARGUMENT_PROPERTY, "/b");
            props.setProperty("sorterplugin3." + PluginProvider.TOOL_ARGUMENT_PROPERTY, "/a");
            props.setProperty("sorterplugin4." + PluginProvider.TOOL_ARGUMENT_PROPERTY, "/d");

            props.setProperty(ImagePluginConfiguration.RESOURCES_LAST_SORTER_PROPERTY, "sorterplugin3");

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
                stack.visitResources(res, new StringTable() {

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
            props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY, "sorterplugin1");
            props.setProperty(ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY, "sorterplugin2");
            props.setProperty(ImagePluginConfiguration.RESOURCES_SORTER_PROPERTY, "sorterplugin3");

            props.setProperty("sorterplugin1." + PluginProvider.TOOL_ARGUMENT_PROPERTY, "/c");
            props.setProperty("sorterplugin2." + PluginProvider.TOOL_ARGUMENT_PROPERTY, "/b");
            props.setProperty("sorterplugin3." + PluginProvider.TOOL_ARGUMENT_PROPERTY, "/a");

            props.setProperty(ImagePluginConfiguration.RESOURCES_LAST_SORTER_PROPERTY, "sorterplugin3");

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

            stack.visitResources(res, new StringTable() {

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

    private static int getNextIndex(Properties props, String category) {
        ImagePluginConfiguration.addPluginProperty(props,
                new CategoryProvider(category));
        int max = 0;
        for (String prop : props.stringPropertyNames()) {
            if (prop.startsWith(ImagePluginConfiguration.
                    RESOURCES_RADICAL_PROPERTY + category)) {
                int i = prop.lastIndexOf(".");
                String v = prop.substring(i + 1);
                try {
                    int index = Integer.valueOf(v);
                    if (index > max) {
                        max = index;
                    }
                } catch (NumberFormatException ex) {
                    // XXX OK, not a number
                }
            }
        }
        if (max == 0) {
            throw new RuntimeException("Next index not found");
        }
        return max;
    }

    private static void checkOrder() throws Exception {

        // Check next index computation
        Properties props = new Properties();
        props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY, "plugin1");
        props.setProperty(ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY, "plugin2");
        props.setProperty(ImagePluginConfiguration.RESOURCES_SORTER_PROPERTY, "plugin3");
        props.setProperty(ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY, "plugin4");
        int i = getNextIndex(props, ImagePluginConfiguration.FILTER);
        if (i != 1) {
            throw new Exception("Unexpected index " + i);
        }
        i = getNextIndex(props, ImagePluginConfiguration.TRANSFORMER);
        if (i != 1) {
            throw new Exception("Unexpected index " + i);
        }
        i = getNextIndex(props, ImagePluginConfiguration.SORTER);
        if (i != 1) {
            throw new Exception("Unexpected index " + i);
        }
        i = getNextIndex(props, ImagePluginConfiguration.COMPRESSOR);
        if (i != 1) {
            throw new Exception("Unexpected index " + i);
        }

        i = getNextIndex(props, ImagePluginConfiguration.TRANSFORMER);
        if (i != 2) {
            throw new Exception("Unexpected index " + i);
        }
        i = getNextIndex(props, ImagePluginConfiguration.SORTER);
        if (i != 2) {
            throw new Exception("Unexpected index " + i);
        }
        i = getNextIndex(props, ImagePluginConfiguration.COMPRESSOR);
        if (i != 2) {
            throw new Exception("Unexpected index " + i);
        }

        props = new Properties();
        props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY + ".500",
                "plugin1");
        i = getNextIndex(props, ImagePluginConfiguration.FILTER);
        if (i != 501) {
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
        props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY, "plugin1");
        props.setProperty(ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY, "plugin2");
        props.setProperty(ImagePluginConfiguration.RESOURCES_SORTER_PROPERTY, "plugin3");
        props.setProperty(ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY, "plugin4");

        List<String> expected = new ArrayList<>();
        expected.add("plugin1");
        expected.add("plugin2");
        expected.add("plugin3");
        expected.add("plugin4");

        check(props, expected);

        props = new Properties();
        props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY + ".250",
                "plugin4");
        props.setProperty(ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY + ".100",
                "plugin3");
        props.setProperty(ImagePluginConfiguration.RESOURCES_SORTER_PROPERTY + ".50",
                "plugin2");
        props.setProperty(ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY + ".10",
                "plugin1");

        expected = new ArrayList<>();
        expected.add("plugin4");
        expected.add("plugin3");
        expected.add("plugin2");
        expected.add("plugin1");

        check(props, expected);

        props = new Properties();
        props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY + ".0",
                "plugin2");
        props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY + ".1",
                "plugin3");
        props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY + ".2",
                "plugin4");
        props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY + ".3",
                "plugin1");

        expected = new ArrayList<>();
        expected.add("plugin2");
        expected.add("plugin3");
        expected.add("plugin4");
        expected.add("plugin1");

        check(props, expected);

        props = new Properties();
        props.setProperty(ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY + ".0",
                "plugin2");
        props.setProperty(ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY + ".1",
                "plugin3");
        props.setProperty(ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY + ".2",
                "plugin4");
        props.setProperty(ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY + ".3",
                "plugin1");

        expected = new ArrayList<>();
        expected.add("plugin2");
        expected.add("plugin3");
        expected.add("plugin4");
        expected.add("plugin1");

        check(props, expected);

        props = new Properties();
        props.setProperty(ImagePluginConfiguration.RESOURCES_SORTER_PROPERTY + ".0",
                "plugin2");
        props.setProperty(ImagePluginConfiguration.RESOURCES_SORTER_PROPERTY + ".1",
                "plugin3");
        props.setProperty(ImagePluginConfiguration.RESOURCES_SORTER_PROPERTY + ".2",
                "plugin4");
        props.setProperty(ImagePluginConfiguration.RESOURCES_SORTER_PROPERTY + ".3",
                "plugin1");

        expected = new ArrayList<>();
        expected.add("plugin2");
        expected.add("plugin3");
        expected.add("plugin4");
        expected.add("plugin1");

        check(props, expected);

        props = new Properties();
        props.setProperty(ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY + ".0",
                "plugin2");
        props.setProperty(ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY + ".1",
                "plugin3");
        props.setProperty(ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY + ".2",
                "plugin4");
        props.setProperty(ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY + ".3",
                "plugin1");

        expected = new ArrayList<>();
        expected.add("plugin2");
        expected.add("plugin3");
        expected.add("plugin4");
        expected.add("plugin1");

        check(props, expected);

        props = new Properties();
        props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY
                + ImagePluginConfiguration.
                getRange(new CategoryProvider(ImagePluginConfiguration.FILTER))[0],
                "plugin1");
        props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY
                + ImagePluginConfiguration.
                getRange(new CategoryProvider(ImagePluginConfiguration.TRANSFORMER))[0],
                "plugin3");
        props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY
                + ImagePluginConfiguration.
                getRange(new CategoryProvider(ImagePluginConfiguration.SORTER))[0],
                "plugin2");
        props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY
                + ImagePluginConfiguration.
                getRange(new CategoryProvider(ImagePluginConfiguration.COMPRESSOR))[0],
                "plugin4");

        expected = new ArrayList<>();
        expected.add("plugin1");
        expected.add("plugin3");
        expected.add("plugin2");
        expected.add("plugin4");

        check(props, expected);

        props = new Properties();
        props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY, "plugin1");
        props.setProperty(ImagePluginConfiguration.RESOURCES_TRANSFORMER_PROPERTY, "plugin2");
        props.setProperty(ImagePluginConfiguration.RESOURCES_SORTER_PROPERTY, "plugin3");
        props.setProperty(ImagePluginConfiguration.RESOURCES_COMPRESSOR_PROPERTY, "plugin4");
        props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY
                + (ImagePluginConfiguration.
                getRange(new CategoryProvider(ImagePluginConfiguration.FILTER))[0] + 1),
                "plugin5");
        props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY
                + (ImagePluginConfiguration.
                getRange(new CategoryProvider(ImagePluginConfiguration.TRANSFORMER))[0] + 1),
                "plugin6");
        props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY
                + (ImagePluginConfiguration.
                getRange(new CategoryProvider(ImagePluginConfiguration.SORTER))[0] + 1),
                "plugin7");
        props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY
                + (ImagePluginConfiguration.
                getRange(new CategoryProvider(ImagePluginConfiguration.COMPRESSOR))[0] + 1),
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
            props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY + ".0.90",
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
            props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY + "90.23",
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
            props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY,
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
            props.setProperty(ImagePluginConfiguration.RESOURCES_FILTER_PROPERTY, "plugin1");
            props.setProperty(ImagePluginConfiguration.RESOURCES_RADICAL_PROPERTY
                    + ImagePluginConfiguration.
                    getRange(new CategoryProvider(ImagePluginConfiguration.FILTER))[0],
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
        plugins.visitResources(pool, new StringTable() {

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

    private static void check(String path, byte[] content) throws Exception {
        StripDebugProvider prov = new StripDebugProvider();
        Properties options = new Properties();
        options.setProperty(PluginProvider.TOOL_ARGUMENT_PROPERTY,
                ImagePluginConfiguration.ON_ARGUMENT);
        ResourcePlugin debug = (ResourcePlugin) prov.newPlugins(options)[0];
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

    private static void checkCompress(Path p, byte[] content, PluginProvider prov,
            Properties config,
            ResourceDecompressorFactory[] factories) throws Exception {
        Properties props = new Properties();
        if (config != null) {
            props.putAll(config);
        }
        Plugin[] compressors = prov.newPlugins(props);
        ResourcePool inputResources = new ResourcePoolImpl(ByteOrder.nativeOrder());
        String name = p.toString().substring("/modules".length());
        Resource res = new Resource(name, ByteBuffer.wrap(content));
        inputResources.addResource(res);
        Map<Integer, String> strings = new HashMap<>();
        ResourcePool input = inputResources;
        for (int i = 0; i < compressors.length; i++) {
            ResourcePlugin compressor = (ResourcePlugin) compressors[i];
            ResourcePool results = new ResourcePoolImpl(ByteOrder.nativeOrder());
            compressor.visit(input, results, new StringTable() {

                @Override
                public int addString(String str) {
                    int id = strID;
                    strID += 1;
                    strings.put(id, str);
                    return id;
                }

                @Override
                public String getString(int id) {
                    return strings.get(id);
                }
            });
            input = results;
            Resource result = results.getResource(res.getPath());
            CompressedResourceHeader header
                    = CompressedResourceHeader.readFromResource(ByteOrder.nativeOrder(),
                            result.getByteArray());
            if (!header.isTerminal()) {
                if (i != compressors.length - 1) {
                    throw new Exception("Wrong not terminal resource at " + i);
                }
            } else {
                if (i != 0) {
                    throw new Exception("Wrong terminal resource at " + i);
                }
            }
            if (header.getDecompressorNameOffset() == 0) {
                throw new Exception("Invalid plugin offset "
                        + header.getDecompressorNameOffset());
            }
            if (header.getResourceSize() <= 0) {
                throw new Exception("Invalid compressed size "
                        + header.getResourceSize());
            }
        }
        for (Resource compressed : input.getResources()) {
            CompressedResourceHeader header
                    = CompressedResourceHeader.readFromResource(ByteOrder.nativeOrder(),
                            compressed.getByteArray());
            byte[] decompressed = compressed.getByteArray();
            for (ResourceDecompressorFactory factory : factories) {
                ResourceDecompressor decompressor = factory.newDecompressor(new Properties());
                decompressed = decompressor.decompress(
                        (int offset) -> strings.get(offset), decompressed,
                        CompressedResourceHeader.getSize(), header.getUncompressedSize());
            }
            Resource orig = inputResources.getResource(compressed.getPath());

            if (decompressed.length != orig.getLength()) {
                throw new Exception("Invalid uncompressed size "
                        + header.getUncompressedSize());
            }
            if (decompressed.length != orig.getLength()) {
                throw new Exception("NOT decompressed");
            }
            byte[] origContent = orig.getByteArray();
            for (int i = 0; i < decompressed.length; i++) {
                if (decompressed[i] != origContent[i]) {
                    throw new Exception("Decompressed and original differ at index "
                            + i);
                }
            }
        }
    }

    private static void check(String s, String sample, boolean exclude)
            throws Exception {
        Properties p = new Properties();
        p.setProperty(PluginProvider.TOOL_ARGUMENT_PROPERTY, s);
        ExcludeProvider provider = new ExcludeProvider();
        ResourcePlugin plug = (ResourcePlugin) provider.newPlugins(p)[0];
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

    private static void checkFiles(String s, String sample, String module, boolean exclude)
            throws Exception {
        Properties p2 = new Properties();
        p2.setProperty(PluginProvider.TOOL_ARGUMENT_PROPERTY, s);
        ExcludeFilesProvider fprovider = new ExcludeFilesProvider();
        ImageFilePlugin fplug = (ImageFilePlugin) fprovider.newPlugins(p2)[0];
        ImageFilePool files = new ImageFilePoolImpl();
        ImageFilePool fresult = new ImageFilePoolImpl();
        ImageFile f = new ImageFile(module, sample, sample, ImageFile.ImageFileType.CONFIG) {

            @Override
            public long size() {
                throw new UnsupportedOperationException("Shouldn't be called");
            }

            @Override
            public InputStream stream() throws IOException {
                throw new UnsupportedOperationException("Shouldn't be called");
            }
        };
        files.addFile(f);

        fplug.visit(files, fresult);

        if (exclude) {
            if (fresult.getFiles().contains(f)) {
                throw new Exception(sample + " should be excluded by " + s);
            }
        } else {
            if (!fresult.getFiles().contains(f)) {
                throw new Exception(sample + " shouldn't be excluded by " + s);
            }
        }
    }
}
