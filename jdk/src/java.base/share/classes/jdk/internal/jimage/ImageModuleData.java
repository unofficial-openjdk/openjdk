/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

package jdk.internal.jimage;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;


/*
 * Manage module meta data.
 *
 */
public final class ImageModuleData {
    public static final String MODULES_STRING = UTF8String.MODULES_STRING.toString();
    private final BasicImageReader reader;
    private static final int SIZE_OF_OFFSET = 4;
    public ImageModuleData(BasicImageReader reader) {
        this.reader = reader;
    }

    public Set<String> allModuleNames() {
        Set<String> modules = new HashSet<>();
        ImageLocation loc = reader.findLocation(MODULES_STRING);
        byte[] content = reader.getResource(loc);
        ByteBuffer buffer = ByteBuffer.wrap(content);
        buffer.order(reader.getByteOrder());
        IntBuffer intBuffer = buffer.asIntBuffer();
        // Resource path are of the form "/modules/<module name>"
        for (int i = 0; i < content.length / SIZE_OF_OFFSET; i++) {
            int offset = intBuffer.get(i);
            ImageLocation moduleLoc = reader.getLocation(offset);
            String path = moduleLoc.getFullNameString();
            int index = path.lastIndexOf("/");
            String module = path.substring(index + 1);
            modules.add(module);
        }
        return modules;
    }
}
