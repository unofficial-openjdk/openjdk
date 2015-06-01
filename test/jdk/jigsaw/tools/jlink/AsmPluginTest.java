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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.Map.Entry;
import jdk.tools.jlink.internal.plugins.asm.AsmModulePool;
import jdk.tools.jlink.internal.plugins.asm.AsmPool.Sorter;
import jdk.tools.jlink.internal.plugins.asm.AsmPools;
import jdk.tools.jlink.internal.plugins.asm.AsmPlugin;
import jdk.tools.jlink.internal.ResourcePoolImpl;
import jdk.tools.jlink.plugins.ResourcePool;
import jdk.tools.jlink.plugins.ResourcePool.Resource;
import jdk.tools.jlink.plugins.StringTable;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.tools.jlink.internal.plugins.asm.AsmPool.ResourceFile;

/*
 * Asm plugin testing.
 * @test
 * @summary Test plugins
 * @author Jean-Francois Denise
 * @library /lib/testlibrary/jlink
 * @modules java.base/jdk.internal.jimage
 *          java.base/jdk.internal.jimage.decompressor
 *          java.base/jdk.internal.org.objectweb.asm
 *          jdk.compiler/com.sun.tools.classfile
 *          jdk.jlink/jdk.tools.jlink
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jlink.internal.plugins.asm
 *          jdk.jlink/jdk.tools.jmod
 *          jdk.jlink/jdk.tools.jimage
 * @run build AsmPluginTest
 * @run build tests.*
 * @run main/othervm AsmPluginTest
*/
public class AsmPluginTest {

    private static class Test1 extends AsmPlugin {

        private List<String> expected;
        private boolean visitCalled;

        @Override
        public void visit(AsmPools pools, StringTable strings) throws IOException {
            visitCalled = true;
            for (String m : MODULES.keySet()) {
                AsmModulePool pool = pools.getModulePool(m);
                if (pool == null) {
                    throw new IOException(m + " pool not found");
                }
                if(!pool.getModuleName().equals(m)) {
                    throw new IOException("Invalid module name " +
                            pool.getModuleName() + " should be "+ m);
                }
                if(pool.getClassReaders().length == 0 && !m.equals(TEST_MODULE)) {
                    throw new IOException("Empty pool " + m);
                }
                pool.addPackage("toto");
                if(pool.getTransformedClasses().getClassReaders().length != 0) {
                    throw new IOException("Should be empty");
                }
                for(String res : MODULES.get(m)) {
                    ResourceFile resFile = pool.getResourceFile(res);
                    if(resFile == null) {
                        throw new IOException("No resource file for " + res);
                    }
                }
            }
            boolean failed = true;
            try {
                pools.getModulePool(null);
                failed = false;
            } catch (Exception ex) {
                //XXX OK
            }
            if (!failed) {
                throw new IOException("Should have failed");
            }
            if (expected != null) {
                {
                    List<String> remain = new ArrayList<>();
                    remain.addAll(expected);
                    for (ClassReader reader : pools.getGlobalPool().getClassReaders()) {
                        if (!expected.contains(reader.getClassName())) {
                            throw new IOException("Class is not expected " +
                                    reader.getClassName() + " expected " + expected);
                        }
                        if (pools.getGlobalPool().getClassReader(reader.getClassName()) == null) {
                            throw new IOException("Class " +
                                    reader.getClassName() + " not found in pool ");
                        }
                        // Check the module pool
                        boolean found = false;
                        for(AsmModulePool mp : pools.getModulePools()) {
                            if(mp.getClassReader(reader.getClassName()) != null) {
                                found = true;
                                break;
                            }
                        }
                        if(!found) {
                            throw new IOException("No modular pool for " +
                                    reader.getClassName());
                        }
                        remain.remove(reader.getClassName());
                    }
                    if (!remain.isEmpty()) {
                        throw new IOException("Remaining classes " + remain);
                    }
                }
                // Check visitor
                {
                    List<String> seen = new ArrayList<>();
                    pools.getGlobalPool().visitClassReaders((ClassReader reader) -> {
                        if (!expected.contains(reader.getClassName())) {
                            throw new RuntimeException("Class is not expected " +
                                    reader.getClassName());
                        }
                        try {
                            if (pools.getGlobalPool().
                                    getClassReader(reader.getClassName()) == null) {
                                throw new RuntimeException("Class not found in pool");
                            }
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                        seen.add(reader.getClassName());
                        return null;
                    });

                    if (!seen.equals(expected)) {
                        throw new IOException("Expected and seen are not equal");
                    }
                }
            }
        }

        @Override
        public String getName() {
            return "test1";
        }
    }

    private static class Test2 extends AsmPlugin {

        private static class IdentityClassVisitor extends ClassVisitor {

            public IdentityClassVisitor(ClassWriter cv) {
                super(Opcodes.ASM5, cv);
            }

            @Override
            public void visit(int version, int access, String name, String signature,
                    String superName, String[] interfaces) {
                super.visit(version, access, name, signature, superName, interfaces);
            }

        }

        private AsmPools pools;

        @Override
        public void visit(AsmPools pools, StringTable strings) throws IOException {
            this.pools = pools;

            for (ClassReader reader : pools.getGlobalPool().getClassReaders()) {
                ClassWriter writer = new ClassWriter(reader,
                        ClassWriter.COMPUTE_FRAMES);
                IdentityClassVisitor visitor = new IdentityClassVisitor(writer);
                reader.accept(visitor, ClassReader.EXPAND_FRAMES);
                pools.getGlobalPool().getTransformedClasses().addClass(writer);
            }

        }

        @Override
        public String getName() {
            return "test2";
        }
    }

    private static class Test3 extends AsmPlugin {

        private static final String END = "HELLOWORLD";

        private static class RenamerClassVisitor extends ClassVisitor {

            public RenamerClassVisitor(ClassWriter cv) {
                super(Opcodes.ASM5, cv);
            }

            @Override
            public void visit(int version, int access, String name,
                    String signature, String superName, String[] interfaces) {
                super.visit(version, access, name + END, signature, superName,
                        interfaces);
            }

        }

        private AsmPools pools;

        @Override
        public void visit(AsmPools pools, StringTable strings)
                throws IOException {
            this.pools = pools;

            for (ClassReader reader : pools.getGlobalPool().getClassReaders()) {
                ClassWriter writer = new ClassWriter(reader,
                        ClassWriter.COMPUTE_FRAMES);
                RenamerClassVisitor visitor = new RenamerClassVisitor(writer);
                reader.accept(visitor, ClassReader.EXPAND_FRAMES);
                try {
                    pools.getGlobalPool().getTransformedClasses().
                            forgetClass(reader.getClassName());
                } catch (Exception ex) {
                    throw new IOException(ex);
                }
                pools.getGlobalPool().getTransformedClasses().addClass(writer);
            }
            // Rename the resource Files
            for (Entry<String, List<String>> mod : MODULES.entrySet()) {
                for (ResourceFile resFile : pools.getModulePool(mod.getKey()).getResourceFiles()) {
                    if (resFile.getPath().startsWith("META-INF/services/")) {
                        ByteBuffer content = resFile.getContent();
                        content.rewind();
                        byte[] array = new byte[content.remaining()];
                        content.get(array);
                        String className = new String(array);
                        pools.getModulePool(mod.getKey()).getTransformedResourceFiles().
                                addResourceFile(new ResourceFile(resFile.getPath(),
                                                ByteBuffer.wrap((className + END).getBytes())));
                    }
                }
            }
        }

        @Override
        public String getName() {
            return "test3";
        }
    }

    private static class Test4 extends AsmPlugin {

        private List<String> expected;
        private AsmPools pools;

        @Override
        public void visit(AsmPools pools, StringTable strings) throws IOException {
            this.pools = pools;
            Sorter sorter = (ResourcePool resources) -> {
                List<String> ret = new ArrayList<>();
                for (String str : expected) {
                    Resource res = resources.getResource(str);
                    ret.add(res.getPath());
                }
                return ret;
            };
            pools.getGlobalPool().setSorter(sorter);
        }

        @Override
        public String getName() {
            return "test4";
        }
    }

    private static final Map<String, List<String>> MODULES = new HashMap<>();
    private static final String TEST_MODULE = "jlink.test";
    static {
        MODULES.put("jdk.localedata", new ArrayList<>());
        MODULES.put("java.base", new ArrayList<>());
        MODULES.put(TEST_MODULE, new ArrayList<>());
    }

    public static void main(String[] args) throws Exception {
        FileSystem fs;
        try {
            fs = FileSystems.getFileSystem(URI.create("jrt:/"));
        } catch (ProviderNotFoundException | FileSystemNotFoundException e) {
            System.out.println("Not an image build, test skipped.");
            return;
        }
        Path root = fs.getPath("/modules");

        checkNull();

        List<String> expected = new ArrayList<>();
        List<String> allResources = new ArrayList<>();
        ResourcePool pool = new ResourcePoolImpl(ByteOrder.nativeOrder());
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.forEach((p) -> {
                if (Files.isRegularFile(p)) {
                    String module = p.toString().substring("/modules/".length());
                    module = module.substring(0, module.indexOf("/"));
                    if (MODULES.keySet().contains(module)) {
                        try {
                            boolean isModuleInfo = p.endsWith("module-info.class");
                            // Module info is not properly handled by Asm,
                            // java.base/module-info ==> java/lang/module-info
                            if (isModuleInfo) {
                                return;
                            }
                            byte[] content = readAllBytes(Files.newInputStream(p));
                            if (p.toString().endsWith(".class") && !isModuleInfo) {
                                expected.add(toClassName(p));
                            } else {
                                MODULES.get(module).add(toResourceFile(p));
                            }
                            allResources.add(toPath(p.toString()));
                            Resource res = new Resource(toPath(p.toString()),
                                    ByteBuffer.wrap(content));
                            pool.addResource(res);
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                }
            });
        }
        // There is more than 10 classes in java.base...
        if (expected.size() < 10 || pool.getResources().size() < 10) {
            throw new Exception("Not expected resource or class number");
        }

        //Add a fake resource file
        String content = "java.lang.Object";
        String path = "META-INF/services/com.foo.BarProvider";
        Resource resFile = new Resource("/" + TEST_MODULE + "/" +
                path, ByteBuffer.wrap(content.getBytes()));
        pool.addResource(resFile);
        MODULES.get(TEST_MODULE).add(path);
        for(Entry<String, List<String>> entry : MODULES.entrySet()) {
            if(entry.getValue().isEmpty()) {
                throw new Exception("No resource file for " + entry.getKey());
            }
        }

        Test1 testAsm = new Test1();
        testAsm.expected = expected;
        ResourcePool res = new ResourcePoolImpl(ByteOrder.nativeOrder());
        testAsm.visit(pool, res, new StringTable() {

            @Override
            public int addString(String str) {
                return -1;
            }

            @Override
            public String getString(int id) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        });
        if (!testAsm.visitCalled) {
            throw new Exception("Resources not visited");
        }
        if (pool.getResources().size() != res.getResources().size()) {
            throw new Exception("Input size " + pool.getResources().size() +
                    " != to " + res.getResources().size());
        }
        List<String> defaultResourceOrder = new ArrayList<>();
        for (Resource r : res.getResources()) {
            if (!pool.getResources().contains(r)) {
                throw new Exception("Resource " + r.getPath() + " not in result pool");
            }
            defaultResourceOrder.add(r.getPath());
        }

        // Identity
        Test2 testAsm2 = new Test2();
        ResourcePool res2 = new ResourcePoolImpl(ByteOrder.nativeOrder());
        testAsm2.visit(pool, res2, new StringTable() {

            @Override
            public int addString(String str) {
                return -1;
            }

            @Override
            public String getString(int id) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        });
        if (res2.isEmpty()) {
            throw new Exception("Empty result");
        }
        if (testAsm2.pools == null) {
            throw new Exception("Resources not visited");
        }
        if (testAsm2.pools.getGlobalPool().getTransformedClasses().
                getClassReaders().length != expected.size()) {
            throw new Exception("Number of transformed classes not equal to expected");
        }
        for (String className : expected) {
            if (testAsm2.pools.getGlobalPool().getTransformedClasses().
                    getClassReader(className) == null) {
                throw new Exception("Class not transformed " + className);
            }
        }
        for (Resource r : res2.getResources()) {
            if (r.getPath().endsWith(".class") &&
                    !r.getPath().endsWith("module-info.calss")) {
                ClassReader reader = new ClassReader(new ByteArrayInputStream(r.getByteArray()));
                ClassWriter w = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
                reader.accept(w, ClassReader.EXPAND_FRAMES);
            }
        }

        // Rename class name
        Test3 testAsm3 = new Test3();
        ResourcePool res3 = new ResourcePoolImpl(ByteOrder.nativeOrder());
        testAsm3.visit(pool, res3, new StringTable() {

            @Override
            public int addString(String str) {
                return -1;
            }

            @Override
            public String getString(int id) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        });
        if (testAsm3.pools == null) {
            throw new Exception("Resources not visited");
        }
        if (testAsm3.pools.getGlobalPool().getTransformedClasses().
                getClassReaders().length != expected.size()) {
            throw new Exception("Number of transformed classes not equal to expected");
        }
        // Check that only renamed classes and resource files are in the result.
        for (Resource r : res3.getResources()) {
            if (r.getPath().endsWith(".class") && !r.getPath().
                    endsWith("module-info.class")) {
                if (!r.getPath().endsWith(Test3.END + ".class")) {
                    throw new Exception("Class not renamed " + r.getPath());
                }
            } else {
                if(r.getPath().contains("META-INF/services/") && MODULES.containsKey(r.getModule())) {
                    String newClassName = new String(r.getByteArray());
                    if(!newClassName.endsWith(Test3.END)) {
                        throw new Exception("Resource file not renamed " + r.getPath());
                    }
                }
            }
        }


        // Classes sorting
        List<String> sorted = new ArrayList<>();
        sorted.addAll(allResources);
        sorted.sort(null);

        Test4 testAsm4 = new Test4();
        testAsm4.expected = sorted;
        ResourcePool res4 = new ResourcePoolImpl(ByteOrder.nativeOrder());
        testAsm4.visit(pool, res4, new StringTable() {

            @Override
            public int addString(String str) {
                return -1;
            }

            @Override
            public String getString(int id) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        });
        if (testAsm4.pools == null) {
            throw new Exception("Resources not visited");
        }
        List<String> sortedResourcePaths = new ArrayList<>();
        for (Resource r : res4.getResources()) {
            sortedResourcePaths.add(r.getPath());
        }
        // Check that default sorting is not equal to sorted one
        if (defaultResourceOrder.equals(sortedResourcePaths)) {
            throw new Exception("Sorting not applied, default ordering");
        }
        //Check that sorted is equal to result.
        if (!sorted.equals(sortedResourcePaths)) {
            throw new Exception("Sorting not properly applied");
        }
    }

    private static void checkNull() throws Exception {
        Test1 t = new Test1();
        boolean failed = false;
        try {
            t.visit((ResourcePool) null, (ResourcePool) null, null);
        } catch (Exception ex) {
            failed = true;
        }
        if (!failed) {
            throw new Exception("Should have failed");
        }
        try {
            t.visit(new ResourcePoolImpl(ByteOrder.BIG_ENDIAN),
                    (ResourcePool) null, null);
        } catch (Exception ex) {
            failed = true;
        }
        if (!failed) {
            throw new Exception("Should have failed");
        }
        try {
            t.visit(new ResourcePoolImpl(ByteOrder.BIG_ENDIAN),
                    (ResourcePool) null, new StringTable() {

            @Override
            public int addString(String str) {
                return -1;
            }

            @Override
            public String getString(int id) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        });
        } catch (Exception ex) {
            failed = true;
        }
        if (!failed) {
            throw new Exception("Should have failed");
        }
    }

    private static String toPath(String p) {
        return p.substring("/modules".length());
    }

    private static String toClassName(Path p) {
        String path = p.toString();
        path = path.substring("/modules/".length());
        // remove module
        if (!path.endsWith("module-info.class")) {
            path = path.substring(path.indexOf("/") + 1);
        }
        path = path.substring(0, path.length() - ".class".length());

        return path;
    }

    private static String toResourceFile(Path p) {
        String path = p.toString();
        path = path.substring("/modules/".length());
        // remove module
        path = path.substring(path.indexOf("/") + 1);

        return path;
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

}
