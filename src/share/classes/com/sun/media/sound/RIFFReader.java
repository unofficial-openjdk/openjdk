/*
 * Copyright 2007 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
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
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */
package com.sun.media.sound;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Resource Interchange File Format (RIFF) stream decoder.
 *
 * @author Karl Helgason
 */
public class RIFFReader extends InputStream {

    private RIFFReader root;
    private long filepointer = 0;
    private String fourcc;
    private String riff_type = null;
    private long ckSize = 0;
    private InputStream stream;
    private long avail;
    private RIFFReader lastiterator = null;

    public RIFFReader(InputStream stream) throws IOException {

        if (stream instanceof RIFFReader)
            root = ((RIFFReader)stream).root;
        else
            root = this;

        this.stream = stream;
        avail = stream.available();
        ckSize = stream.available();

        // Check for RIFF null paddings,
        int b;
        while (true) {
            b = read();
            if (b == -1) {
                fourcc = ""; // don't put null value into fourcc,
                // because it is expected to
                // always contain a string value
                riff_type = null;
                avail = 0;
                return;
            }
            if (b != 0)
                break;
        }

        byte[] fourcc = new byte[4];
        fourcc[0] = (byte) b;
        read(fourcc, 1, 3);
        this.fourcc = new String(fourcc, "ascii");
        ckSize = readUnsignedInt();

        avail = this.ckSize;

        if (getFormat().equals("RIFF") || getFormat().equals("LIST")) {
            byte[] format = new byte[4];
            read(format);
            this.riff_type = new String(format, "ascii");
        }
    }

    public long getFilePointer() throws IOException {
        return root.filepointer;
    }

    public boolean hasNextChunk() throws IOException {
        if (lastiterator != null)
            lastiterator.finish();
        return avail != 0;
    }

    public RIFFReader nextChunk() throws IOException {
        if (lastiterator != null)
            lastiterator.finish();
        if (avail == 0)
            return null;
        lastiterator = new RIFFReader(this);
        return lastiterator;
    }

    public String getFormat() {
        return fourcc;
    }

    public String getType() {
        return riff_type;
    }

    public long getSize() {
        return ckSize;
    }

    public int read() throws IOException {
        if (avail == 0)
            return -1;
        int b = stream.read();
        if (b == -1)
            return -1;
        avail--;
        filepointer++;
        return b;
    }

    public int read(byte[] b, int offset, int len) throws IOException {
        if (avail == 0)
            return -1;
        if (len > avail) {
            int rlen = stream.read(b, offset, (int)avail);
            if (rlen != -1)
                filepointer += rlen;
            avail = 0;
            return rlen;
        } else {
            avail -= len;
            int ret = stream.read(b, offset, len);
            if (ret == -1)
                return -1;
            filepointer += ret;
            return ret;
        }
    }

    public long skip(long n) throws IOException {
        if (avail == 0)
            return -1;
        if (n > avail) {
            long len = stream.skip(avail);
            if (len != -1)
                filepointer += len;
            avail = 0;
            return len;
        } else {
            avail -= n;
            long ret = stream.skip(n);
            if (ret == -1)
                return -1;
            filepointer += ret;
            return ret;
        }
    }

    public int available() {
        return (int)avail;
    }

    public void finish() throws IOException {
        if (avail != 0) {
            long ret = stream.skip(avail);
            if (ret != -1)
                filepointer += ret;
            avail = 0;
        }
    }

    // Read ASCII chars from stream
    public String readString(int len) throws IOException {
        byte[] buff = new byte[len];
        read(buff);
        for (int i = 0; i < buff.length; i++) {
            if (buff[i] == 0) {
                return new String(buff, 0, i, "ascii");
            }
        }
        return new String(buff, "ascii");
    }

    // Read 8 bit signed integer from stream
    public byte readByte() throws IOException {
        int ch = read();
        if (ch < 0)
            throw new EOFException();
        return (byte) ch;
    }

    // Read 16 bit signed integer from stream
    public short readShort() throws IOException {
        int ch1 = read();
        int ch2 = read();
        if (ch1 < 0)
            throw new EOFException();
        if (ch2 < 0)
            throw new EOFException();
        return (short)(ch1 | (ch2 << 8));
    }

    // Read 32 bit signed integer from stream
    public int readInt() throws IOException {
        int ch1 = read();
        int ch2 = read();
        int ch3 = read();
        int ch4 = read();
        if (ch1 < 0)
            throw new EOFException();
        if (ch2 < 0)
            throw new EOFException();
        if (ch3 < 0)
            throw new EOFException();
        if (ch4 < 0)
            throw new EOFException();
        return ch1 + (ch2 << 8) | (ch3 << 16) | (ch4 << 24);
    }

    // Read 64 bit signed integer from stream
    public long readLong() throws IOException {
        long ch1 = read();
        long ch2 = read();
        long ch3 = read();
        long ch4 = read();
        long ch5 = read();
        long ch6 = read();
        long ch7 = read();
        long ch8 = read();
        if (ch1 < 0)
            throw new EOFException();
        if (ch2 < 0)
            throw new EOFException();
        if (ch3 < 0)
            throw new EOFException();
        if (ch4 < 0)
            throw new EOFException();
        if (ch5 < 0)
            throw new EOFException();
        if (ch6 < 0)
            throw new EOFException();
        if (ch7 < 0)
            throw new EOFException();
        if (ch8 < 0)
            throw new EOFException();
        return ch1 | (ch2 << 8) | (ch3 << 16) | (ch4 << 24)
                | (ch5 << 32) | (ch6 << 40) | (ch7 << 48) | (ch8 << 56);
    }

    // Read 8 bit unsigned integer from stream
    public int readUnsignedByte() throws IOException {
        int ch = read();
        if (ch < 0)
            throw new EOFException();
        return ch;
    }

    // Read 16 bit unsigned integer from stream
    public int readUnsignedShort() throws IOException {
        int ch1 = read();
        int ch2 = read();
        if (ch1 < 0)
            throw new EOFException();
        if (ch2 < 0)
            throw new EOFException();
        return ch1 | (ch2 << 8);
    }

    // Read 32 bit unsigned integer from stream
    public long readUnsignedInt() throws IOException {
        long ch1 = read();
        long ch2 = read();
        long ch3 = read();
        long ch4 = read();
        if (ch1 < 0)
            throw new EOFException();
        if (ch2 < 0)
            throw new EOFException();
        if (ch3 < 0)
            throw new EOFException();
        if (ch4 < 0)
            throw new EOFException();
        return ch1 + (ch2 << 8) | (ch3 << 16) | (ch4 << 24);
    }

    public void close() throws IOException {
        finish();
        if (this == root)
            stream.close();
        stream = null;
    }
}
