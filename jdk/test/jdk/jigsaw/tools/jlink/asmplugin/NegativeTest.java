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
 * Asm plugin testing.
 * @test
 * @summary Test basic functionality.
 * @author Andrei Eremeev
 * @modules java.base/jdk.internal.org.objectweb.asm
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jlink.internal.plugins.asm
 * @build AsmPluginTestBase
 * @run main NegativeTest
 */

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.tools.jlink.internal.ResourcePoolImpl;
import jdk.tools.jlink.internal.plugins.asm.AsmGlobalPool;
import jdk.tools.jlink.internal.plugins.asm.AsmModulePool;
import jdk.tools.jlink.internal.plugins.asm.AsmPlugin;
import jdk.tools.jlink.internal.plugins.asm.AsmPool.ResourceFile;
import jdk.tools.jlink.internal.plugins.asm.AsmPools;
import jdk.tools.jlink.plugins.ResourcePool;
import jdk.tools.jlink.plugins.StringTable;

public class NegativeTest extends AsmPluginTestBase {
    public static void main(String[] args) throws Exception {
        if (!isImageBuild()) {
            System.err.println("Test not run. Not image build.");
            return;
        }
        new NegativeTest().test();
    }

    @Override
    public void test() throws Exception {
        testNull();
        testUnknownPackage();
    }

    private void testUnknownPackage() throws Exception {
        AsmPlugin t = new AsmPlugin() {
            @Override
            public void visit(AsmPools pools, StringTable strings) throws IOException {
                AsmGlobalPool globalPool = pools.getGlobalPool();
                AsmModulePool javabase = pools.getModulePool("java.base");
                ClassReader cr = new ClassReader(NegativeTest.class.getResourceAsStream("NegativeTest.class"));
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
                cr.accept(new RenameClassVisitor(cw), ClassReader.EXPAND_FRAMES);
                action(() -> globalPool.getTransformedClasses().addClass(cw),
                        "Unknown package", IOException.class);
                action(() -> javabase.getTransformedClasses().addClass(cw),
                        "Unknown package", IOException.class);

                ResourceFile newResFile = new ResourceFile("java/aaa/file", ByteBuffer.allocate(0));
                action(() -> globalPool.getTransformedResourceFiles().addResourceFile(newResFile),
                        "Unknown package", IOException.class);
                action(() -> javabase.getTransformedResourceFiles().addResourceFile(newResFile),
                        "Unknown package", IOException.class);

                action(() -> globalPool.getTransformedClasses().forgetClass("java/aaa/file"),
                        "Unknown package", IOException.class);
                action(() -> javabase.getTransformedClasses().forgetClass("java/aaa/file"),
                        "Unknown package", IOException.class);
                action(() -> globalPool.getTransformedResourceFiles().forgetResourceFile("java/aaa/file"),
                        "Unknown package", IOException.class);
                action(() -> javabase.getTransformedResourceFiles().forgetResourceFile("java/aaa/file"),
                        "Unknown package", IOException.class);
            }

            @Override
            public String getName() {
                return null;
            }
        };
        ResourcePool resources = new ResourcePoolImpl(ByteOrder.BIG_ENDIAN);
        StringTable table = new StringTable() {
            @Override
            public int addString(String str) {
                return -1;
            }

            @Override
            public String getString(int id) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        };
        t.visit(getPool(), resources, table);
    }

    private static class RenameClassVisitor extends ClassVisitor {

        public RenameClassVisitor(ClassWriter cv) {
            super(Opcodes.ASM5, cv);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            super.visit(version, access, "RENAMED", signature, superName, interfaces);
        }
    }

    private void testNull() throws Exception {
        AsmPlugin t = new AsmPlugin() {
            @Override
            public void visit(AsmPools pools, StringTable strings) throws IOException {
                action(() -> pools.getModulePool(null), "Module name is null", NullPointerException.class);
                action(() -> pools.fillOutputResources(null), "Output resource is null", NullPointerException.class);
            }

            @Override
            public String getName() {
                return null;
            }
        };
        ResourcePool resources = new ResourcePoolImpl(ByteOrder.BIG_ENDIAN);
        StringTable table = new StringTable() {
            @Override
            public int addString(String str) {
                return -1;
            }

            @Override
            public String getString(int id) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        };
        action(() -> t.visit(null, resources, table), "Input resource is null", NullPointerException.class);
        action(() -> t.visit(resources, null, table), "Output resource is null", NullPointerException.class);
        action(() -> t.visit(resources, resources, null), "Table is null", NullPointerException.class);
        t.visit(resources, resources, table);
    }

    private void action(Action action, String message, Class<? extends Exception> expected) {
        try {
            System.err.println("Testing: " + message);
            action.call();
            throw new AssertionError(message + ": should have failed");
        } catch (Exception e) {
            if (!expected.isInstance(e)) {
                throw new RuntimeException(e);
            } else {
                System.err.println("Got exception as expected: " + e);
            }
        }
    }

    private interface Action {
        void call() throws Exception;
    }
}
