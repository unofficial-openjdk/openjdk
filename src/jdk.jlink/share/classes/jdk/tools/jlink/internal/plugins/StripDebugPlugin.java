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
package jdk.tools.jlink.internal.plugins;

import java.io.IOException;
import jdk.tools.jlink.internal.plugins.asm.AsmPools;
import jdk.tools.jlink.plugins.StringTable;
import jdk.tools.jlink.internal.plugins.asm.AsmPlugin;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;

/**
 *
 * Strip debug attributes plugin
 */
final class StripDebugPlugin extends AsmPlugin {

    @Override
    public String getName() {
        return StripDebugProvider.NAME;
    }

    @Override
    public void visit(AsmPools pools, StringTable strings) throws IOException {
        pools.getGlobalPool().visitClassReaders((reader) -> {
            ClassWriter writer = null;
            if (reader.getClassName().contains("module-info")) {//eg: java.base/module-info
                // XXX. Do we have debug info? Is Asm ready for module-info?
            } else {
                writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                reader.accept(writer, ClassReader.SKIP_DEBUG);
            }
            return writer;
        });
    }
}
