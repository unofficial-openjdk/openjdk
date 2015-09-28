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
 * @summary Test zip compressor
 * @author Jean-Francois Denise
 * @modules java.base/jdk.internal.jimage.decompressor
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jlink.internal.plugins
 * @run main CompressorPluginTest
 */

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jdk.internal.jimage.decompressor.CompressedResourceHeader;
import jdk.internal.jimage.decompressor.ResourceDecompressor;
import jdk.internal.jimage.decompressor.ResourceDecompressorFactory;
import jdk.internal.jimage.decompressor.StringSharingDecompressorFactory;
import jdk.internal.jimage.decompressor.ZipDecompressorFactory;
import jdk.tools.jlink.internal.ImagePluginConfiguration;
import jdk.tools.jlink.internal.ResourcePoolImpl;
import jdk.tools.jlink.internal.plugins.DefaultCompressProvider;
import jdk.tools.jlink.internal.plugins.StringSharingProvider;
import jdk.tools.jlink.internal.plugins.ZipCompressProvider;
import jdk.tools.jlink.plugins.CmdPluginProvider;
import jdk.tools.jlink.plugins.Plugin;
import jdk.tools.jlink.plugins.PluginProvider;
import jdk.tools.jlink.plugins.ResourcePlugin;
import jdk.tools.jlink.plugins.ResourcePool;
import jdk.tools.jlink.plugins.ResourcePool.Resource;
import jdk.tools.jlink.plugins.StringTable;

public class CompressorPluginTest {

    private static int strID = 1;

    public static void main(String[] args) throws Exception {
        new CompressorPluginTest().test();
    }

    public void test() throws Exception {
        FileSystem fs;
        try {
            fs = FileSystems.getFileSystem(URI.create("jrt:/"));
        } catch (ProviderNotFoundException | FileSystemNotFoundException e) {
            System.err.println("Not an image build, test skipped.");
            return;
        }
        Path javabase = fs.getPath("/modules/java.base");

        checkCompress(gatherResources(javabase), new ZipCompressProvider(), null,
                new ResourceDecompressorFactory[]{
                        new ZipDecompressorFactory()
                });

        ResourcePool classes = gatherClasses(javabase);
        // compress = String sharing
        checkCompress(classes, new StringSharingProvider(), null,
                new ResourceDecompressorFactory[]{
                        new StringSharingDecompressorFactory()});

        // compress == ZIP + String sharing
        Properties options = new Properties();
        options.setProperty(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY,
                ImagePluginConfiguration.ON_ARGUMENT);
        checkCompress(classes, new DefaultCompressProvider(), options,
                new ResourceDecompressorFactory[]{
                        new ZipDecompressorFactory(),
                        new StringSharingDecompressorFactory()
                });

        // compress == ZIP + String sharing + filter
        options.setProperty(DefaultCompressProvider.FILTER_OPTION,
                "*Exception.class,^*IOException.class");
        checkCompress(classes, new DefaultCompressProvider(), options,
                new ResourceDecompressorFactory[]{
                        new ZipDecompressorFactory(),
                        new StringSharingDecompressorFactory()
                }, Collections.singletonList(".*Exception.class"),
                Collections.singletonList(".*IOException.class"));

        // compress level 1 == ZIP
        Properties options1 = new Properties();
        options1.setProperty(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY,
                ImagePluginConfiguration.ON_ARGUMENT);
        options1.setProperty(DefaultCompressProvider.LEVEL_OPTION, "1");
        checkCompress(classes, new DefaultCompressProvider(),
                options1,
                new ResourceDecompressorFactory[]{
                    new ZipDecompressorFactory()
                });

        // compress level 1 == ZIP
        options1.setProperty(DefaultCompressProvider.FILTER_OPTION,
                "*Exception.class,^*IOException.class");
        checkCompress(classes, new DefaultCompressProvider(),
                options1,
                new ResourceDecompressorFactory[]{
                    new ZipDecompressorFactory()
                }, Collections.singletonList(".*Exception.class"),
                Collections.singletonList(".*IOException.class"));

        // compress level 2 == ZIP + String sharing
        Properties options2 = new Properties();
        options2.setProperty(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY,
                ImagePluginConfiguration.ON_ARGUMENT);
        options2.setProperty(DefaultCompressProvider.LEVEL_OPTION, "2");
        checkCompress(classes, new DefaultCompressProvider(),
                options2,
                new ResourceDecompressorFactory[]{
                        new ZipDecompressorFactory(),
                        new StringSharingDecompressorFactory()
                });

        // compress level 2 == ZIP + String sharing + filter
        options2.setProperty(DefaultCompressProvider.FILTER_OPTION,
                "*Exception.class,^*IOException.class");
        checkCompress(classes, new DefaultCompressProvider(),
                options2,
                new ResourceDecompressorFactory[]{
                        new ZipDecompressorFactory(),
                        new StringSharingDecompressorFactory()
                }, Collections.singletonList(".*Exception.class"),
                Collections.singletonList(".*IOException.class"));

        // compress level 0 == String sharing
        Properties options0 = new Properties();
        options0.setProperty(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY,
                ImagePluginConfiguration.ON_ARGUMENT);
        options0.setProperty(DefaultCompressProvider.LEVEL_OPTION, "0");
        checkCompress(classes, new DefaultCompressProvider(),
                options0,
                new ResourceDecompressorFactory[]{
                        new StringSharingDecompressorFactory()
                });

        // compress level 0 == String sharing + filter
        options0.setProperty(DefaultCompressProvider.FILTER_OPTION,
                "*Exception.class,^*IOException.class");
        checkCompress(classes, new DefaultCompressProvider(),
                options0,
                new ResourceDecompressorFactory[]{
                        new StringSharingDecompressorFactory()
                }, Collections.singletonList(".*Exception.class"),
                Collections.singletonList(".*IOException.class"));
    }

    private ResourcePool gatherResources(Path module) throws Exception {
        ResourcePool pool = new ResourcePoolImpl(ByteOrder.nativeOrder());
        try (Stream<Path> stream = Files.walk(module)) {
            for (Iterator<Path> iterator = stream.iterator(); iterator.hasNext(); ) {
                Path p = iterator.next();
                if (Files.isRegularFile(p)) {
                    byte[] content = Files.readAllBytes(p);
                    pool.addResource(new Resource(p.toString(), ByteBuffer.wrap(content)));
                }
            }
        }
        return pool;
    }

    private ResourcePool gatherClasses(Path module) throws Exception {
        ResourcePool pool = new ResourcePoolImpl(ByteOrder.nativeOrder());
        try (Stream<Path> stream = Files.walk(module)) {
            for (Iterator<Path> iterator = stream.iterator(); iterator.hasNext(); ) {
                Path p = iterator.next();
                if (Files.isRegularFile(p) && p.toString().endsWith(".class")) {
                    byte[] content = Files.readAllBytes(p);
                    pool.addResource(new Resource(p.toString(), ByteBuffer.wrap(content)));
                }
            }
        }
        return pool;
    }

    private void checkCompress(ResourcePool resources, PluginProvider prov,
                               Properties config,
                               ResourceDecompressorFactory[] factories) throws Exception {
        checkCompress(resources, prov, config, factories, Collections.emptyList(), Collections.emptyList());
    }

    private void checkCompress(ResourcePool resources, PluginProvider prov,
                                      Properties config,
                                      ResourceDecompressorFactory[] factories,
                               List<String> includes,
                               List<String> excludes) throws Exception {
        long original = 0;
        long compressed = 0;
        for (Resource resource : resources.getResources()) {
            List<Pattern> includesPatterns = includes.stream()
                    .map(Pattern::compile)
                    .collect(Collectors.toList());
            List<Pattern> excludesPatterns = excludes.stream()
                    .map(Pattern::compile)
                    .collect(Collectors.toList());

            Map<String, Object> props = new HashMap<>();
            if (config != null) {
                for (String p : config.stringPropertyNames()) {
                    props.put(p, config.getProperty(p));
                }
            }
            Plugin[] compressors = prov.newPlugins(props);
            ResourcePool inputResources = new ResourcePoolImpl(ByteOrder.nativeOrder());
            inputResources.addResource(resource);
            Map<Integer, String> strings = new HashMap<>();
            ResourcePool compressedResources = applyCompressors(compressors, inputResources, resource, strings, includesPatterns, excludesPatterns);
            original += resource.getLength();
            compressed += compressedResources.getResource(resource.getPath()).getLength();
            applyDecompressors(factories, inputResources, compressedResources, strings, includesPatterns, excludesPatterns);
        }
        String compressors = Stream.of(factories)
                .map(Object::getClass)
                .map(Class::getSimpleName)
                .collect(Collectors.joining(", "));
        String size = "Compressed size: " + compressed + ", original size: " + original;
        System.out.println("Used " + compressors + ". " + size);
        if (original <= compressed) {
            throw new AssertionError("java.base not compressed.");
        }
    }

    private ResourcePool applyCompressors(Plugin[] compressors,
                                          ResourcePool inputResources,
                                          ResourcePool.Resource res, final Map<Integer, String> strings,
                                          List<Pattern> includesPatterns,
                                          List<Pattern> excludesPatterns) throws Exception {
        ResourcePool input = inputResources;
        for (int i = 0; i < compressors.length; i++) {
            ResourcePlugin compressor = (ResourcePlugin) compressors[i];
            ResourcePool compressedPool = new ResourcePoolImpl(ByteOrder.nativeOrder());
            compressor.visit(input, compressedPool, new StringTable() {
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
            String path = res.getPath();
            ResourcePool.Resource compressed = compressedPool.getResource(path);
            CompressedResourceHeader header
                    = CompressedResourceHeader.readFromResource(ByteOrder.nativeOrder(), compressed.getByteArray());
            if (isIncluded(includesPatterns, excludesPatterns, path)) {
                if (header == null) {
                    throw new AssertionError("Path should be compressed: " + path);
                }
            } else {
                if (header != null) {
                    throw new AssertionError("Path should not be compressed: " + path);
                }
                continue;
            }
            if (!header.isTerminal()) {
                if (i != compressors.length - 1) {
                    throw new AssertionError("Wrong not terminal resource at " + i);
                }
            } else if (i != 0) {
                throw new AssertionError("Wrong terminal resource at " + i);
            }
            if (header.getDecompressorNameOffset() == 0) {
                throw new AssertionError("Invalid plugin offset "
                        + header.getDecompressorNameOffset());
            }
            if (header.getResourceSize() <= 0) {
                throw new AssertionError("Invalid compressed size "
                        + header.getResourceSize());
            }
            input = compressedPool;
        }
        return input;
    }

    private void applyDecompressors(ResourceDecompressorFactory[] decompressors,
                                    ResourcePool inputResources,
                                    ResourcePool compressedResources,
                                    Map<Integer, String> strings,
                                    List<Pattern> includesPatterns,
                                    List<Pattern> excludesPatterns) throws Exception {
        for (ResourcePool.Resource compressed : compressedResources.getResources()) {
            CompressedResourceHeader header = CompressedResourceHeader.readFromResource(
                    ByteOrder.nativeOrder(), compressed.getByteArray());
            String path = compressed.getPath();
            ResourcePool.Resource orig = inputResources.getResource(path);
            if (!isIncluded(includesPatterns, excludesPatterns, path)) {
                continue;
            }
            byte[] decompressed = compressed.getByteArray();
            for (ResourceDecompressorFactory factory : decompressors) {
                ResourceDecompressor decompressor = factory.newDecompressor(new Properties());
                decompressed = decompressor.decompress(
                        strings::get, decompressed,
                        CompressedResourceHeader.getSize(), header.getUncompressedSize());
            }

            if (decompressed.length != orig.getLength()) {
                throw new AssertionError("Invalid uncompressed size "
                        + header.getUncompressedSize());
            }
            byte[] origContent = orig.getByteArray();
            for (int i = 0; i < decompressed.length; i++) {
                if (decompressed[i] != origContent[i]) {
                    throw new AssertionError("Decompressed and original differ at index " + i);
                }
            }
        }
    }

    private boolean isIncluded(List<Pattern> includesPatterns, List<Pattern> excludesPatterns, String path) {
        return !excludesPatterns.stream().anyMatch((pattern) -> pattern.matcher(path).matches()) &&
                (includesPatterns.isEmpty() ||
                        includesPatterns.stream().anyMatch((pattern) -> pattern.matcher(path).matches()));
    }
}
