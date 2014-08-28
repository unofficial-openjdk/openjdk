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

package jdk.jigsaw.module.internal;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ImageReader {
    private String imagePath;
    private RandomAccessFile file;
    private FileChannel channel;
    private ByteOrder byteOrder;
    private ImageHeader header;
    private int indexSize;
    private IntBuffer redirectBuffer;
    private IntBuffer offsetsBuffer;
    private ByteBuffer locationsBuffer;
    private ByteBuffer stringsBuffer;
    private ImageStrings strings;

    public ImageReader(String imagePath) {
        this(imagePath, ByteOrder.nativeOrder());
    }

    public ImageReader(String imagePath, ByteOrder byteOrder) {
        this.imagePath = imagePath;
        this.byteOrder = byteOrder;
    }

    public void open() throws IOException {
        this.file = new RandomAccessFile(imagePath, "r");
        this.channel = file.getChannel();
        header = ImageHeader.readFrom(byteOrder, getIntBuffer(0, ImageHeader.getHeaderSize()));
        indexSize = header.getIndexSize();
        redirectBuffer = getIntBuffer(header.getRedirectOffset(), header.getRedirectSize());
        offsetsBuffer = getIntBuffer(header.getOffsetsOffset(), header.getOffsetsSize());
        locationsBuffer = getByteBuffer(header.getLocationsOffset(), header.getLocationsSize());
        stringsBuffer = getByteBuffer(header.getStringsOffset(), header.getStringsSize());
        strings = new ImageStrings(new ImageStream(stringsBuffer));
    }

    public void close() throws IOException {
        channel.close();
        file.close();

        channel = null;
        file = null;
    }

    public ImageHeader getHeader() {
        return header;
    }

    public ImageLocation findLocation(String name) {
        return findLocation(new UTF8String(name));
    }

    public ImageLocation findLocation(UTF8String name) {
        int count = header.getLocationCount();
        int hash = name.hashCode() % count;
        int redirect = getRedirect(hash);

        if (redirect == 0) {
            return null;
        }

        int index;

        if (redirect < 0) {
            // If no collision.
            index = -redirect - 1;
        } else {
            // If collision, recompute hash code.
            index = name.hashCode(redirect) % count;
        }

        int offset = getOffset(index);
        ImageLocation location = getLocation(offset);

        return location.verify(name) ? location : null;
    }

    public String[] getEntryNames() {
        return getEntryNames(true);
    }

    public String[] getEntryNames(boolean sorted) {
        int count = header.getLocationCount();
        List<String> list = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            int offset = offsetsBuffer.get(i);
            ImageLocation location = ImageLocation.readFrom(locationsBuffer, offset, strings);
            list.add(location.getFullnameString());
        }

        String[] array = list.toArray(new String[0]);

        if (sorted) {
            Arrays.sort(array);
        }

        return array;
    }

    private IntBuffer getIntBuffer(long offset, long size) throws IOException {
        MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, offset, size);
        buffer.order(byteOrder);

        return buffer.asIntBuffer();
    }

    private ByteBuffer getByteBuffer(long offset, long size) throws IOException {
        MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, offset, size);
        buffer.order(byteOrder);

        return buffer.asReadOnlyBuffer();
    }

    private int getRedirect(int index) {
        return redirectBuffer.get(index);
    }

    private int getOffset(int index) {
        return offsetsBuffer.get(index);
    }

    private ImageLocation getLocation(int offset) {
        return ImageLocation.readFrom(locationsBuffer, offset, strings);
    }

    synchronized public byte[] getResource(long offset, long size) throws IOException {
        byte[] bytes = new byte[(int)size];
        file.seek(indexSize + offset);
        file.read(bytes);

        return bytes;
    }
}
