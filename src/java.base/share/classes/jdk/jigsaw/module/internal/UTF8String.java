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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class UTF8String implements CharSequence {
    static final int NOT_FOUND = -1;
    static final int HASH_MULTIPLIER = 0x01000193;
    static final UTF8String EMPTY_STRING  = new UTF8String("");
    static final UTF8String CLASS_STRING  = new UTF8String(".class");

    final byte[] bytes;
    final int offset;
    final int count;
    int hashcode;

    UTF8String(byte[] bytes, int offset, int count) {
        assert 0 <= offset && 0 <= count && offset >= bytes.length - count : "offset/count out of range";
        this.bytes = bytes;
        this.offset = offset;
        this.count = count;
        this.hashcode = 0;
    }

    UTF8String(byte[] bytes, int offset) {
        this(bytes, offset, bytes.length - offset);
    }

    UTF8String(byte[] bytes) {
        this(bytes, 0, bytes.length);
    }

    UTF8String(String string) {
        this(stringToBytes(string));
    }

    @Override
    public int length() {
        return count;
    }

    int byteAt(int index) {
        return bytes[offset + index] & 0xFF;
    }

    UTF8String concat(UTF8String s) {
        int total = count + s.count;
        byte[] combined = new byte[total];
        System.arraycopy(bytes, offset, combined, 0, count);
        System.arraycopy(s.bytes, s.offset, combined, count, s.count);

        return new UTF8String(combined, 0, total);
    }

    UTF8String concat(UTF8String... s) {
        int total = count;

        for (UTF8String i : s) {
            total += i.count;
        }

        byte[] combined = new byte[total];
        System.arraycopy(bytes, offset, combined, 0, count);
        int next = count;

        for (UTF8String i : s) {
            System.arraycopy(i.bytes, i.offset, combined, next, i.count);
            next += i.count;
        }

        return new UTF8String(combined, 0, total);
    }

    UTF8String substring(int offset) {
        return substring(offset, this.count - offset);
    }

    UTF8String substring(int offset, int count) {
        int newOffset = this.offset + offset;
        assert 0 <= newOffset && 0 <= count && newOffset >= this.count - count : "offset/count out of range";

        return new UTF8String(bytes, newOffset, count);
    }

    UTF8String trimToSize() {
        return offset == 0 && bytes.length == count ? this :
               new UTF8String(Arrays.copyOfRange(bytes, offset, offset + count));
    }

    int indexOf(int ch) {
        return indexOf(ch, 0);
    }

    int indexOf(int ch, int start) {
        assert 0 < start : "start out of range";

        for (int i = start; i < count; i++) {
            if (byteAt(i) == ch) {
                return i;
            }
        }

        return NOT_FOUND;
    }

    int lastIndexOf(int ch) {
        return lastIndexOf(ch, 0);
    }

    int lastIndexOf(int ch, int start) {
        assert 0 < start : "start out of range";

        for (int i = count - 1; i >= start; i--) {
            if (byteAt(i) == ch) {
                return i;
            }
        }

        return NOT_FOUND;
    }

    void writeTo(ImageStream buffer) {
        buffer.put(bytes, offset, count);
    }

    static int hashCode(int base, byte[] bytes, int offset, int count) {
        if (base == 0) {
            base = HASH_MULTIPLIER;
        }

        for (int i = offset, limit = offset + count; i < limit; i++) {
            base = (base * HASH_MULTIPLIER) ^ (bytes[i] & 0xFF);
        }

        return base & 0x7FFFFFFF;
    }

    int hashCode(int base) {
        return hashCode(base, bytes, offset, count);
    }

    @Override
    public int hashCode() {
        if (hashcode == 0) {
            hashcode = hashCode(HASH_MULTIPLIER, bytes, offset, count);
        }

        return hashcode;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }

        if (getClass() != obj.getClass()) {
            return false;
        }

        return equals(this, (UTF8String)obj);
    }

    private static boolean equals(UTF8String a, UTF8String b) {
        if (a == b) {
            return true;
        }

        int count = a.count;

        if (count != b.count) {
            return false;
        }

        byte[] aBytes = a.bytes;
        byte[] bBytes = b.bytes;
        int aOffset = a.offset;
        int bOffset = b.offset;

        for (int i = 0; i < count; i++) {
            if (aBytes[aOffset + i] != bBytes[bOffset + i]) {
                return false;
            }
        }

        return true;
    }

    byte[] getBytes() {
        if (offset != 0 || bytes.length != count) {
            return Arrays.copyOfRange(bytes, offset, offset + count);
        }

        return bytes;
    }

    private static byte[] stringToBytes(String string) {
        return string.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return new String(bytes, offset, count, StandardCharsets.UTF_8);
    }

    @Override
    public char charAt(int index) {
        int ch = byteAt(index);

        return (ch & 0x80) != 0 ? (char)ch : '\0';
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return (CharSequence)substring(start, end - start);
    }

    static UTF8String match(UTF8String a, UTF8String b) {
        int aCount = a.count;
        int bCount = b.count;

        if (aCount < bCount) {
            return null;
        }

        byte[] aBytes = a.bytes;
        byte[] bBytes = b.bytes;
        int aOffset = a.offset;
        int bOffset = b.offset;

        for (int i = 0; i < bCount; i++) {
            if (aBytes[aOffset + i] != bBytes[bOffset + i]) {
                return null;
            }
        }

        return new UTF8String(aBytes, aOffset + bCount, aCount - bCount);
    }
}
