/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.hotspot.replacements;

import org.graalvm.compiler.api.replacements.ClassSubstitution;
import org.graalvm.compiler.api.replacements.MethodSubstitution;
import org.graalvm.compiler.core.common.spi.ForeignCallDescriptor;
import org.graalvm.compiler.graph.Node.ConstantNodeParameter;
import org.graalvm.compiler.graph.Node.NodeIntrinsic;
import org.graalvm.compiler.hotspot.nodes.CurrentStackPointerNode;
import org.graalvm.compiler.hotspot.nodes.StubForeignCallNode;
import org.graalvm.compiler.nodes.extended.ForeignCallNode;
import org.graalvm.compiler.word.Word;

@ClassSubstitution(value = java.lang.Continuation.class)
public class ContinuationSubstitutions {
    @MethodSubstitution(isStatic = true)
    public static long getSP() {
        Word stackPointer = CurrentStackPointerNode.get();
        return stackPointer.rawValue();
    }

    @MethodSubstitution(isStatic = true)
    public static int runLevel() {
        return 3;
    }

    public static final ForeignCallDescriptor CONTINUATION_DO_CONTINUE = new ForeignCallDescriptor("contomiaton_do_continue", int.class, Continuation.class);
    public static final ForeignCallDescriptor CONTINUATION_YIELD = new ForeignCallDescriptor("continuation_do_flibble", int.class, int.class);
}
