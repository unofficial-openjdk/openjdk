/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package jdk.tools.jlink.internal.plugins.optim;

import java.util.Iterator;
import java.util.Set;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.TryCatchBlockNode;

/**
 * Optimization utility methods
 */
public class Utils {

    public static boolean canThrowCheckedException(MethodNode m, TryCatchBlockNode bn) throws Exception {
        int istart = m.instructions.indexOf(bn.start);
        int iend = m.instructions.indexOf(bn.end);
        for (int i = istart; i < iend - 1; i++) {
            AbstractInsnNode instr = m.instructions.get(i);
            if (instr instanceof MethodInsnNode) {
                MethodInsnNode meth = (MethodInsnNode) instr;
                Class<?> clz = Class.forName(meth.owner.replaceAll("/", "."));
                for (java.lang.reflect.Method method : clz.getDeclaredMethods()) {
                    if (method.getName().equals(meth.name)) {
                        for (Class<?> e : method.getExceptionTypes()) {
                            if (e.getName().equals(bn.type.replaceAll("/", "."))) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    public static void suppressBlocks(MethodNode m, Set<ControlFlow.Block> toRemove) throws Exception {
        m.instructions.resetLabels();
        Iterator<AbstractInsnNode> it = m.instructions.iterator();
        while (it.hasNext()) {
            AbstractInsnNode n = it.next();
            Iterator<TryCatchBlockNode> handlers = m.tryCatchBlocks.iterator();
            boolean cont = false;
            // Do not delete instructions that are end of other try block.
            while (handlers.hasNext()) {
                TryCatchBlockNode handler = handlers.next();
                if (handler.end == n) {
                    cont = true;
                }
            }
            if (cont) {
                continue;
            }

            for (ControlFlow.Block b : toRemove) {
                for (ControlFlow.InstructionNode ins : b.getInstructions()) {
                    if (ins.getInstr() == n) {
                        it.remove();
                    }
                }
            }
        }
    }
}
