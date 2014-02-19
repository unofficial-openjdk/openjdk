/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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

import static com.sun.xml.internal.ws.org.objectweb.asm.Opcodes.ACC_FINAL;
import static com.sun.xml.internal.ws.org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static com.sun.xml.internal.ws.org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static com.sun.xml.internal.ws.org.objectweb.asm.Opcodes.ACC_STATIC;
import static com.sun.xml.internal.ws.org.objectweb.asm.Opcodes.ACC_SUPER;
import static com.sun.xml.internal.ws.org.objectweb.asm.Opcodes.ILOAD;
import static com.sun.xml.internal.ws.org.objectweb.asm.Opcodes.ALOAD;
import static com.sun.xml.internal.ws.org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static com.sun.xml.internal.ws.org.objectweb.asm.Opcodes.INVOKESTATIC;
import static com.sun.xml.internal.ws.org.objectweb.asm.Opcodes.IRETURN;
import static com.sun.xml.internal.ws.org.objectweb.asm.Opcodes.RETURN;
import static com.sun.xml.internal.ws.org.objectweb.asm.Opcodes.V1_6;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;

import com.sun.xml.internal.ws.org.objectweb.asm.ClassWriter;
import com.sun.xml.internal.ws.org.objectweb.asm.MethodVisitor;
import com.sun.xml.internal.ws.org.objectweb.asm.Type;
import sun.misc.IoTrace;

public class IoTraceAgent {

    private static IoTraceListener listener;

    public static void setListener(IoTraceListener l) {
        listener = l;
    }

    public static Object socketReadBegin() {
        IoTraceListener l = listener;
        if (l != null) {
            return l.socketReadBegin();
        }
        return null;
    }

    public static void socketReadEnd(Object context, InetAddress address, int port,
                                     int timeout, long bytesRead) {
        IoTraceListener l = listener;
        if (l != null) {
            l.socketReadEnd(context, address, port, timeout, bytesRead);
        }
    }

    public static Object socketWriteBegin() {
        IoTraceListener l = listener;
        if (l != null) {
            return l.socketWriteBegin();
        }
        return null;
    }

    public static void socketWriteEnd(Object context, InetAddress address, int port,
                                      long bytesWritten) {
        IoTraceListener l = listener;
        if (l != null) {
            l.socketWriteEnd(context, address, port, bytesWritten);
        }
    }

    public static Object fileReadBegin(String path) {
        IoTraceListener l = listener;
        if (l != null) {
            return l.fileReadBegin(path);
        }
        return null;
    }

    public static void fileReadEnd(Object context, long bytesRead) {
        IoTraceListener l = listener;
        if (l != null) {
            l.fileReadEnd(context, bytesRead);
        }
    }

    public static Object fileWriteBegin(String path) {
        IoTraceListener l = listener;
        if (l != null) {
            return l.fileWriteBegin(path);
        }
        return null;
    }

    public static void fileWriteEnd(Object context, long bytesWritten) {
        IoTraceListener l = listener;
        if (l != null) {
            l.fileWriteEnd(context, bytesWritten);
        }
    }

    public static void premain(String agentArgs, Instrumentation inst)
            throws Exception {
        ClassDefinition cd = new ClassDefinition(IoTrace.class,
                generateClassAsm());
        inst.redefineClasses(cd);
    }

    private static byte[] generateClassAsm() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_6, ACC_PUBLIC | ACC_SUPER | ACC_FINAL, "sun/misc/IoTrace",
                null, "java/lang/Object", null);

        // for all methods in the existing IoTrace class
        // we want to create a method in the new version of it which call
        // IoTraceAgent
        //
        // 0: aload_0
        // 1: iload_1
        // 2: iload_2
        // 3: invokestatic #16 // Method
        // IoTraceAgent.socketReadBegin:(II)Ljava/lang/Object;
        // 6: areturn

        for (Method om : IoTrace.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(om.getModifiers())) {
                continue;
            }

            // create a method with the same signature as the
            // original method
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                    om.getName(), Type.getMethodDescriptor(om), null, null);
            mv.visitCode();

            // get the return type and argument list types
            Type[] argTypes = Type.getArgumentTypes(om);
            Type retType = Type.getReturnType(om);

            // load all the arguments
            int i = 0;
            for (Type t : argTypes) {
                mv.visitVarInsn(t.getOpcode(ILOAD), i++);
            }

            // call a method with the same signature (but in a different class)
            // with all the arguments
            mv.visitMethodInsn(INVOKESTATIC, "IoTraceAgent", om.getName(),
                    Type.getMethodDescriptor(om));

            // return the value from the called method
            mv.visitInsn(retType.getOpcode(IRETURN));
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        // empty private constructor
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE, "<init>", "()V", null,
                null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
