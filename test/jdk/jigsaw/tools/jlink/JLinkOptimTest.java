
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.TryCatchBlockNode;
import jdk.tools.jlink.internal.ImagePluginProviderRepository;
import jdk.tools.jlink.internal.ResourcePoolImpl;
import jdk.tools.jlink.internal.plugins.OptimizationProvider;
import jdk.tools.jlink.internal.plugins.asm.AsmModulePool;
import jdk.tools.jlink.internal.plugins.asm.AsmPlugin;
import jdk.tools.jlink.internal.plugins.asm.AsmPools;
import jdk.tools.jlink.internal.plugins.optim.ControlFlow;
import jdk.tools.jlink.internal.plugins.optim.ControlFlow.Block;
import jdk.tools.jlink.plugins.CmdResourcePluginProvider;
import jdk.tools.jlink.plugins.PluginProvider;
import jdk.tools.jlink.plugins.ResourcePlugin;
import jdk.tools.jlink.plugins.ResourcePool;
import jdk.tools.jlink.plugins.ResourcePool.Resource;
import jdk.tools.jlink.plugins.StringTable;

import tests.Helper;
import tests.JImageGenerator;
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
 * @summary Test image creation with class optimization
 * @author Jean-Francois Denise
 * @library ../lib
 * @modules java.base/jdk.internal.jimage
 *          jdk.jdeps/com.sun.tools.classfile
 *          jdk.jlink/jdk.tools.jlink
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jmod
 *          jdk.jlink/jdk.tools.jimage
 *          jdk.jlink/jdk.tools.jimage
 *          jdk.jlink/jdk.tools.jlink.internal.plugins
 *          jdk.jlink/jdk.tools.jlink.internal.plugins.asm
 *          jdk.jlink/jdk.tools.jlink.internal.plugins.optim
 *          java.base/jdk.internal.org.objectweb.asm
 *          java.base/jdk.internal.org.objectweb.asm.tree
 *          java.base/jdk.internal.org.objectweb.asm.util
 * @build tests.*
 * @run main JLinkOptimTest
 */
public class JLinkOptimTest {

    private static final String EXPECTED = "expected";
    private static Helper helper;

    private static class ControlFlowProvider extends CmdResourcePluginProvider {

        private boolean called;
        private int numMethods;
        private int numBlocks;

        private class ControlFlowPlugin extends AsmPlugin {

            private ControlFlowPlugin() {
            }

            @Override
            public void visit(AsmPools pools, StringTable strings) throws IOException {
                called = true;
                for (AsmModulePool p : pools.getModulePools()) {

                    p.visitClassReaders((reader) -> {
                        ClassNode cn = new ClassNode();
                        if ((reader.getAccess() & Opcodes.ACC_INTERFACE) == 0) {
                            reader.accept(cn, ClassReader.EXPAND_FRAMES);
                            for (MethodNode m : cn.methods) {
                                if ((m.access & Opcodes.ACC_ABSTRACT) == 0
                                        && (m.access & Opcodes.ACC_NATIVE) == 0) {
                                    numMethods += 1;
                                    try {
                                        ControlFlow f
                                                = ControlFlow.createControlFlow(cn.name, m);
                                        for (Block b : f.getBlocks()) {
                                            numBlocks += 1;
                                            f.getClosure(b);
                                        }
                                    } catch (Throwable ex) {
                                        //ex.printStackTrace();
                                        throw new RuntimeException("Exception in "
                                                + cn.name + "." + m.name, ex);
                                    }
                                }
                            }
                        }
                        return null;
                    });
                }
            }

            @Override
            public String getName() {
                return NAME;
            }

        }

        private static final String NAME = "test-optim";

        ControlFlowProvider() {
            super(NAME, "");
        }

        @Override
        public ResourcePlugin[] newPlugins(String[] argument,
                Map<String, String> options) throws IOException {
            return new ResourcePlugin[]{new ControlFlowPlugin()};
        }

        @Override
        public String getCategory() {
            return PluginProvider.TRANSFORMER;
        }

        @Override
        public String getToolArgument() {
            return null;
        }

        @Override
        public String getToolOption() {
            return NAME;
        }

        @Override
        public Map<String, String> getAdditionalOptions() {
            return null;
        }
    }

    private static void testForName() throws Exception {
        String moduleName = "optimplugin";
        Path src = Paths.get(System.getProperty("test.src")).resolve(moduleName);
        Path classes = helper.getJmodClassesDir().resolve(moduleName);
        JImageGenerator.compile(src, classes);

        FileSystem fs = FileSystems.getFileSystem(URI.create("jrt:/"));
        Path root = fs.getPath("/modules/java.base");
        // Access module-info.class to be reused as fake module-info.class
        List<Resource> javabaseResources = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Iterator<Path> iterator = stream.iterator(); iterator.hasNext();) {
                Path p = iterator.next();
                if (Files.isRegularFile(p)) {
                    try {
                        javabaseResources.add(
                                new Resource(p.toString().substring("/modules".length()),
                                        ByteBuffer.wrap(Files.readAllBytes(p))));
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }

        //forName folding
        ResourcePool pool = new ResourcePoolImpl(ByteOrder.nativeOrder());
        byte[] content = Files.readAllBytes(classes.
                resolve("optim").resolve("ForNameTestCase.class"));
        byte[] content2 = Files.readAllBytes(classes.
                resolve("optim").resolve("AType.class"));
        byte[] mcontent = Files.readAllBytes(classes.resolve("module-info.class"));

        pool.addResource(new ResourcePool.Resource("/optimplugin/optim/ForNameTestCase.class", ByteBuffer.wrap(content)));
        pool.addResource(new ResourcePool.Resource("/optimplugin/optim/AType.class", ByteBuffer.wrap(content2)));
        pool.addResource(new ResourcePool.Resource("/optimplugin/module-info.class",
                ByteBuffer.wrap(mcontent)));

        for (Resource r : javabaseResources) {
            pool.addResource(r);
        }

        OptimizationProvider prov = new OptimizationProvider();
        String[] a = {OptimizationProvider.FORNAME_REMOVAL};
        Map<String, String> optional = new HashMap<>();
        optional.put(OptimizationProvider.LOG_FILE, "forName.log");
        ResourcePlugin plug = prov.newPlugins(a, optional)[0];
        ResourcePool out = new ResourcePoolImpl(ByteOrder.nativeOrder());
        plug.visit(pool, out, new StringTable() {

            @Override
            public int addString(String str) {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public String getString(int id) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        });

        Resource result = out.getResources().iterator().next();

        ClassReader optimReader = new ClassReader(result.getByteArray());
        ClassNode optimClass = new ClassNode();
        optimReader.accept(optimClass, ClassReader.EXPAND_FRAMES);

        if (!optimClass.name.equals("optim/ForNameTestCase")) {
            throw new Exception("Invalid class " + optimClass.name);
        }
        if (optimClass.methods.size() < 2) {
            throw new Exception("Not enough methods in new class");
        }
        for (MethodNode mn : optimClass.methods) {
            if (!mn.name.contains("forName") && !mn.name.contains("<clinit>")) {
                continue;
            }
            if (mn.name.startsWith("negative")) {
                checkForName(mn);
            } else {
                checkNoForName(mn);
            }
        }
        Map<String, byte[]> newClasses = new HashMap<>();
        newClasses.put("optim.ForNameTestCase", result.getByteArray());
        newClasses.put("optim.AType", content2);
        MemClassLoader loader = new MemClassLoader(newClasses);
        Class<?> loaded = loader.loadClass("optim.ForNameTestCase");
        if (loaded.getDeclaredMethods().length < 2) {
            throw new Exception("Not enough methods in new class");
        }
        for (Method m : loaded.getDeclaredMethods()) {
            if (m.getName().contains("Exception")) {
                try {
                    m.invoke(null);
                } catch (Exception ex) {
                    //ex.getCause().printStackTrace();
                    if (!ex.getCause().getMessage().equals(EXPECTED)) {
                        throw new Exception("Unexpected exception " + ex);
                    }
                }
            } else {
                if (!m.getName().startsWith("negative")) {
                    Class<?> clazz = (Class<?>) m.invoke(null);
                    if (clazz != String.class && clazz != loader.findClass("optim.AType")) {
                        throw new Exception("Invalid class " + clazz);
                    }
                }
            }
        }
    }

    private static void checkNoForName(MethodNode m) throws Exception {
        Iterator<AbstractInsnNode> it = m.instructions.iterator();
        while (it.hasNext()) {
            AbstractInsnNode n = it.next();
            if (n instanceof MethodInsnNode) {
                MethodInsnNode met = (MethodInsnNode) n;
                if (met.name.equals("forName")
                        && met.owner.equals("java/lang/Class")
                        && met.desc.equals("(Ljava/lang/String;)Ljava/lang/Class;")) {
                    throw new Exception("forName not removed in " + m.name);
                }
            }
        }
        for (TryCatchBlockNode tcb : m.tryCatchBlocks) {
            if (tcb.type.equals(ClassNotFoundException.class.getName().replaceAll("\\.", "/"))) {
                throw new Exception("ClassNotFoundException Block not removed for " + m.name);
            }
        }
    }

    private static void checkForName(MethodNode m) throws Exception {
        Iterator<AbstractInsnNode> it = m.instructions.iterator();
        boolean found = false;
        while (it.hasNext()) {
            AbstractInsnNode n = it.next();
            if (n instanceof MethodInsnNode) {
                MethodInsnNode met = (MethodInsnNode) n;
                if (met.name.equals("forName")
                        && met.owner.equals("java/lang/Class")
                        && met.desc.equals("(Ljava/lang/String;)Ljava/lang/Class;")) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            throw new Exception("forName removed but shouldn't have");
        }
        found = false;
        for (TryCatchBlockNode tcb : m.tryCatchBlocks) {
            if (tcb.type.equals(ClassNotFoundException.class.getName().replaceAll("\\.", "/"))) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new Exception("tryCatchBlocks removed but shouldn't have");
        }
    }

    static class MemClassLoader extends ClassLoader {

        private final Map<String, byte[]> classes;
        private final Map<String, Class<?>> cache = new HashMap<>();
        MemClassLoader(Map<String, byte[]> classes) {
            super(null);
            this.classes = classes;
        }

        @Override
        public Class findClass(String name) throws ClassNotFoundException {
            Class<?> clazz = cache.get(name);
            if (clazz == null) {
                byte[] b = classes.get(name);
                if (b == null) {
                    return super.findClass(name);
                } else {
                    clazz = defineClass(name, b, 0, b.length);
                    cache.put(name, clazz);
                }
            }
            return clazz;
        }
    }

    public static void main(String[] args) throws Exception {
        helper = Helper.newHelper();
        if (helper == null) {
            System.err.println("Test not run");
            return;
        }

        testForName();

        helper.generateDefaultModules();
        helper.generateDefaultJModule("optim1", "java.se");
        {
            String[] userOptions = {"--class-optim", "all",
                "--class-optim-log-file", "./class-optim-log.txt"};

            Path imageDir = helper.generateDefaultImage(userOptions, "optim1").assertSuccess();
            helper.checkImage(imageDir, "optim1", null, null);
        }

        /*{
         Path dir = Paths.get("dir.log");
         Files.createDirectory(dir);
         String[] userOptions = {"--class-optim", "all", "--class-optim-log-file", dir.toString()};
         helper.generateDefaultImage(userOptions, "optim1")
         .assertFailure("java.io.FileNotFoundException: dir.log (Is a directory)");
         }*/
        /*{
         String[] userOptions = {"--class-optim", "UNKNOWN"};
         helper.generateDefaultImage(userOptions, "optim1").assertFailure("Unknown optimization");
         }*/
        {
            String[] userOptions = {"--class-optim", "forName-folding",
                "--class-optim-log-file", "./class-optim-log.txt"};
            Path imageDir = helper.generateDefaultImage(userOptions, "optim1").assertSuccess();
            helper.checkImage(imageDir, "optim1", null, null);
        }

        {
            ControlFlowProvider provider = new ControlFlowProvider();
            ImagePluginProviderRepository.registerPluginProvider(provider);
            String[] userOptions = {"--test-optim"};
            Path imageDir = helper.generateDefaultImage(userOptions, "optim1").assertSuccess();
            helper.checkImage(imageDir, "optim1", null, null);
            //System.out.println("Num methods analyzed " + provider.numMethods
            //        + "num blocks " + provider.numBlocks);
            if (!provider.called) {
                throw new Exception("Plugin not called");
            }
            if (provider.numMethods < 1000) {
                throw new Exception("Not enough method called,  should be "
                        + "around 10000 but is " + provider.numMethods);
            }
            if (provider.numBlocks < 100000) {
                throw new Exception("Not enough blocks,  should be "
                        + "around 640000 but is " + provider.numMethods);
            }
        }
    }

}
