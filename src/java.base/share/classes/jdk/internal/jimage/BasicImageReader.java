/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Comparator;
import java.util.stream.IntStream;
import jdk.internal.jimage.decompressor.Decompressor;

public class BasicImageReader implements AutoCloseable {
    static private final boolean is64Bit = AccessController.doPrivileged(
        new PrivilegedAction<Boolean>() {
            public Boolean run() {
                return "64".equals(System.getProperty("sun.arch.data.model"));
            }
        });

    private final ByteOrder byteOrder;
    private ByteBuffer map;
    private final File imageFile;
    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final long size;
    private final ImageHeader header;
    private final long indexSize;
    private final IntBuffer redirect;
    private final IntBuffer offsets;
    private final ByteBuffer locations;
    private final ByteBuffer strings;
    private final ImageStringsReader stringsReader;
    private final Decompressor decompressor;

    private native static ByteBuffer getNativeMap(String imagePath);

    protected BasicImageReader(String imagePath, ByteOrder byteOrder)
            throws IOException {
        this.byteOrder = byteOrder;

        try {
            this.map = getNativeMap(imagePath);
        } catch (UnsatisfiedLinkError ex) {
            this.map = null;
        }

        int headerSize = ImageHeader.getHeaderSize();

        if (this.map == null || !is64Bit) {
            this.imageFile = new File(imagePath);
            this.raf = new RandomAccessFile(this.imageFile, "r");
            this.channel = this.raf.getChannel();
            this.size = this.channel.size();
            ByteBuffer buffer = ByteBuffer.allocate(headerSize);
            this.channel.read(buffer, 0L);
            buffer.rewind();
            this.header = readHeader(intBuffer(buffer, 0, headerSize));
            this.indexSize = this.header.getIndexSize();
            this.map = channel.map(FileChannel.MapMode.READ_ONLY, 0, is64Bit ? size : indexSize);
        } else {
            this.imageFile = null;
            this.raf = null;
            this.channel = null;
            this.size = this.map.capacity();
            this.header = readHeader(intBuffer(this.map, 0, headerSize));
            this.indexSize = this.header.getIndexSize();
        }

        this.redirect = intBuffer(this.map, this.header.getRedirectOffset(), this.header.getRedirectSize());
        this.offsets = intBuffer(this.map, this.header.getOffsetsOffset(), this.header.getOffsetsSize());
        this.locations = slice(this.map, this.header.getLocationsOffset(), this.header.getLocationsSize());
        this.strings = slice(this.map, this.header.getStringsOffset(), this.header.getStringsSize());

        this.stringsReader = new ImageStringsReader(this);
        this.decompressor = new Decompressor();
    }

    protected BasicImageReader(String imagePath) throws IOException {
        this(imagePath, ByteOrder.nativeOrder());
    }

    public static BasicImageReader open(String imagePath) throws IOException {
        return new BasicImageReader(imagePath, ByteOrder.nativeOrder());
    }

    public ImageHeader getHeader() {
        return header;
    }

    private ImageHeader readHeader(IntBuffer buffer) throws IOException {
        ImageHeader result = ImageHeader.readFrom(buffer);

        if (result.getMagic() != ImageHeader.MAGIC ||
                result.getMajorVersion() != ImageHeader.MAJOR_VERSION ||
                result.getMinorVersion() != ImageHeader.MINOR_VERSION) {
            throw new IOException("Image not found \"" + imageFile + "\"");
        }

        return result;
    }

    private ByteBuffer slice(ByteBuffer buffer, int position, int capacity) {
        synchronized(buffer) {
            buffer.limit(position + capacity);
            buffer.position(position);
            return buffer.slice();
        }
    }

    private IntBuffer intBuffer(ByteBuffer buffer, int offset, int size) {
        return slice(buffer, offset, size).order(byteOrder).asIntBuffer();
    }

    public static void releaseByteBuffer(ByteBuffer buffer) {
        ImageBufferCache.releaseBuffer(buffer);
    }

    public ByteOrder getByteOrder() {
        return byteOrder;
    }

    public File getImageFile() {
        return imageFile;
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }

    public ImageStringsReader getStrings() {
        return stringsReader;
    }

    public ImageLocation findLocation(String mn, String rn) {
        return findLocation("/" + mn + "/" + rn);
    }

    public synchronized ImageLocation findLocation(String name) {
        byte[] bytes = ImageStringsReader.mutf8FromString(name);
        int count = header.getTableLength();
        int index = redirect.get(ImageStringsReader.hashCode(bytes) % count);

        if (index < 0) {
            index = -index - 1;
        } else {
            index = ImageStringsReader.hashCode(bytes, index) % count;
        }

        long[] attributes = getAttributes(offsets.get(index));

        ImageLocation imageLocation = new ImageLocation(attributes, stringsReader);

        if (!imageLocation.verify(name)) {
            return null;
        }

        return imageLocation;
    }

    public String[] getEntryNames() {
        int[] attributeOffsets = new int[offsets.capacity()];
        offsets.get(attributeOffsets);
        return IntStream.of(attributeOffsets)
                        .filter(o -> o != 0)
                        .mapToObj(o -> ImageLocation.readFrom(this, o).getFullName())
                        .sorted()
                        .toArray(String[]::new);
    }

    protected ImageLocation[] getAllLocations(boolean sorted) {
        int[] attributeOffsets = new int[offsets.capacity()];
        offsets.get(attributeOffsets);
        return IntStream.of(attributeOffsets)
                        .filter(o -> o != 0)
                        .mapToObj(o -> ImageLocation.readFrom(this, o))
                        .sorted(Comparator.comparing(ImageLocation::getFullName))
                        .toArray(ImageLocation[]::new);
    }

    ImageLocation getLocation(int offset) {
        return ImageLocation.readFrom(this, offset);
    }

    public long[] getAttributes(int offset) {
        ByteBuffer buffer = slice(locations, offset, locations.limit() - offset);
        return ImageLocationBase.decompress(buffer);
    }

    public String getString(int offset) {
        ByteBuffer buffer = slice(strings, offset, strings.limit() - offset);
        return ImageStringsReader.stringFromByteBuffer(buffer);
    }

    private byte[] getBufferBytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.limit()];
        buffer.get(bytes);

        return bytes;
    }

    private ByteBuffer readBuffer(long offset, long size) {
        assert offset < Integer.MAX_VALUE;
        assert size < Integer.MAX_VALUE;

        if (is64Bit) {
            ByteBuffer buffer = slice(map, (int)offset, (int)size);
            buffer.order(ByteOrder.BIG_ENDIAN);

            return buffer;
        } else {
            ByteBuffer buffer = ImageBufferCache.getBuffer(size);
            int read = 0;

            try {
                assert channel != null: "Image file channel not open";
                read = channel.read(buffer, offset);
                buffer.rewind();
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }

            if (read != size) {
                ImageBufferCache.releaseBuffer(buffer);
            }

            return buffer;
        }
    }

    public byte[] getResource(String name) {
        ImageLocation location = findLocation(name);

        return location != null ? getResource(location) : null;
    }

    public byte[] getResource(ImageLocation loc) {
        ByteBuffer buffer = getResourceBuffer(loc);

        if (buffer != null) {
            byte[] bytes = getBufferBytes(buffer);
            ImageBufferCache.releaseBuffer(buffer);

            return bytes;
        }

        return null;
    }

    public ByteBuffer getResourceBuffer(ImageLocation loc) {
        long offset = loc.getContentOffset() + indexSize;
        long compressedSize = loc.getCompressedSize();
        long uncompressedSize = loc.getUncompressedSize();
        assert compressedSize < Integer.MAX_VALUE;
        assert uncompressedSize < Integer.MAX_VALUE;

        if (compressedSize == 0) {
            return readBuffer(offset, uncompressedSize);
        } else {
            ByteBuffer buffer = readBuffer(offset, compressedSize);

            if (buffer != null) {
                byte[] bytesIn = getBufferBytes(buffer);
                ImageBufferCache.releaseBuffer(buffer);
                byte[] bytesOut;

                try {
                    bytesOut = decompressor.decompressResource(byteOrder, (int strOffset) -> getString(strOffset), bytesIn);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }

                return ByteBuffer.wrap(bytesOut);
            }
        }

        return null;
    }

    public ByteBuffer getResourceBuffer(String name) {
        ImageLocation location = findLocation(name);

        return location != null ? getResourceBuffer(location) : null;
    }

    public InputStream getResourceStream(ImageLocation loc) {
        byte[] bytes = getResource(loc);

        return new ByteArrayInputStream(bytes);
    }

    public InputStream getResourceStream(String name) {
        ImageLocation location = findLocation(name);

        return location != null ? getResourceStream(location) : null;
    }
}
