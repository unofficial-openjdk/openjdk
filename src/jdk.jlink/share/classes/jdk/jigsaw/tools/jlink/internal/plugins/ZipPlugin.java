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
package jdk.jigsaw.tools.jlink.internal.plugins;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.Deflater;
import jdk.jigsaw.tools.jlink.plugins.Plugin;
import jdk.jigsaw.tools.jlink.plugins.ResourcePool;
import jdk.jigsaw.tools.jlink.plugins.StringTable;

/**
 *
 * ZIP Compression plugin
 */
final class ZipPlugin implements Plugin {

    @Override
    public String getName() {
        return ZipCompressProvider.NAME;
    }

    static byte[] compress(byte[] bytesIn) {
        Deflater deflater = new Deflater();
        deflater.setInput(bytesIn);
        ByteArrayOutputStream stream = new ByteArrayOutputStream(bytesIn.length);
        byte[] buffer = new byte[1024];

        deflater.finish();
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            stream.write(buffer, 0, count);
        }

        try {
            stream.close();
        } catch (IOException ex) {
            return bytesIn;
        }

        byte[] bytesOut = stream.toByteArray();
        deflater.end();

        return bytesOut;
    }

    @Override
    public void visit(ResourcePool resources, ResourcePool output, StringTable strings)
            throws Exception {
        resources.visit((resource, order, str) -> {
            byte[] compressed = compress(resource.getByteArray());
            return ResourcePool.CompressedResource.newCompressedResource(resource,
                    ByteBuffer.wrap(compressed), getName(), null, str, order);
        }, output, strings);
    }
}
