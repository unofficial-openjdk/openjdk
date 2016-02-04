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

import java.io.UTFDataFormatException;
import java.nio.ByteBuffer;

public class ImageStringsReader implements ImageStrings {
    public static final int HASH_MULTIPLIER = 0x01000193;
    private final BasicImageReader reader;

    ImageStringsReader(BasicImageReader reader) {
        this.reader = reader;
    }

    @Override
    public String get(int offset) {
        return reader.getString(offset);
    }

    @Override
    public int add(final String string) {
        throw new InternalError("Can not add strings at runtime");
    }

    private static int hashCode(byte[] bytes, int offset, int count, int seed) {
        for (int i = offset, limit = offset + count; i < limit; i++) {
            seed = (seed * HASH_MULTIPLIER) ^ (bytes[i] & 0xFF);
        }

        return seed & 0x7FFFFFFF;
    }

    public static int hashCode(byte[] bytes, int seed) {
        return hashCode(bytes, 0, bytes.length, seed);
    }

    public static int hashCode(byte[] bytes) {
        return hashCode(bytes, 0, bytes.length, HASH_MULTIPLIER);
    }

    public static int hashCode(String string, int seed) {
        return hashCode(mutf8FromString(string), seed);
    }

    public static int hashCode(String string) {
        return hashCode(mutf8FromString(string), HASH_MULTIPLIER);
    }

    static int charsFromMUTF8Length(byte[] bytes, int offset, int count) {
        int length = 0;

        for (int i = offset; i < offset + count; i++) {
            byte ch = bytes[i];

            if (ch == 0) {
                break;
            }

            if ((ch & 0xC0) != 0x80) {
                length++;
            }
        }

        return length;
    }

    static void charsFromMUTF8(char[] chars, byte[] bytes, int offset, int count) throws UTFDataFormatException {
        int j = 0;

        for (int i = offset; i < offset + count; i++) {
            byte ch = bytes[i];

            if (ch == 0) {
                break;
            }

            boolean is_unicode = (ch & 0x80) != 0;
            int uch = ch & 0x7F;

            if (is_unicode) {
                int mask = 0x40;

                while ((uch & mask) != 0) {
                    ch = bytes[++i];

                    if ((ch & 0xC0) != 0x80) {
                        throw new UTFDataFormatException("bad continuation 0x" + Integer.toHexString(ch));
                    }

                    uch = ((uch & ~mask) << 6) | (ch & 0x3F);
                    mask <<= 6 - 1;
                }

                if ((uch & 0xFFFF) != uch) {
                    throw new UTFDataFormatException("character out of range \\u" + Integer.toHexString(uch));
                }
            }

            chars[j++] = (char)uch;
        }
    }

    public static String stringFromMUTF8(byte[] bytes, int offset, int count) {
        int length = charsFromMUTF8Length(bytes, offset, count);
        char[] chars = new char[length];

        try {
            charsFromMUTF8(chars, bytes, offset, count);
        } catch (UTFDataFormatException ex) {
            throw new InternalError("Attempt to convert non modified UTF-8 byte sequence");
        }

        return new String(chars);
    }

    public static String stringFromMUTF8(byte[] bytes) {
        return stringFromMUTF8(bytes, 0, bytes.length);
    }

    static int charsFromByteBufferLength(ByteBuffer buffer) {
        int length = 0;

        while(buffer.hasRemaining()) {
            byte ch = buffer.get();

            if (ch == 0) {
                return length;
            }

            if ((ch & 0xC0) != 0x80) {
                length++;
            }
        }

        assert true : "No terminating zero byte";
        return length;
    }

    static void charsFromByteBuffer(char chars[], ByteBuffer buffer) {
        int j = 0;

        while(buffer.hasRemaining()) {
            byte ch = buffer.get();

            if (ch == 0) {
                return;
            }

            boolean is_unicode = (ch & 0x80) != 0;
            int uch = ch & 0x7F;

            if (is_unicode) {
                int mask = 0x40;

                while ((uch & mask) != 0) {
                    ch = buffer.get();
                    assert (ch & 0xC0) == 0x80 : "error in unicode";
                    uch = ((uch & ~mask) << 6) | (ch & 0x3F);
                    mask <<= 6 - 1;
                }
            }

            assert (uch & 0xFFFF) == uch : "error in unicode)";

            chars[j++] = (char)uch;
        }

        assert true : "No terminating zero byte";
    }

    public static String stringFromByteBuffer(ByteBuffer buffer) {
        int length = charsFromByteBufferLength(buffer);
        buffer.rewind();
        char[] chars = new char[length];
        charsFromByteBuffer(chars, buffer);

        return new String(chars);
    }

    static int mutf8FromCharsLength(char chars[]) {
        int length = 0;

        for (char ch : chars) {
            int uch = ch & 0xFFFF;

            if ((uch & ~0x7F) != 0) {
                int mask = ~0x3F;
                int n = 0;

                do {
                    n++;
                    uch >>= 6;
                    mask >>= 1;
                } while ((uch & mask) != 0);

                length += n + 1;
            } else if (uch == 0) {
                length += 2;
            } else {
                length++;
            }
        }

        return length;
    }

    static void mutf8FromChars(byte[] bytes, int offset, char chars[]) {
        int j = offset;

        for (char ch : chars) {
            int uch = ch & 0xFFFF;

            if ((uch & ~0x7F) != 0) {
                byte[] buffer = new byte[8];
                int mask = ~0x3F;
                int n = 0;

                do {
                    buffer[n++] = (byte)(0x80 | (uch & 0x3F));
                    uch >>= 6;
                    mask >>= 1;
                } while ((uch & mask) != 0);

                buffer[n] = (byte)((mask << 1) | uch);

                do {
                    bytes[j++] = buffer[n--];
                } while (0 <= n);
            } else if (uch == 0) {
                bytes[j++] = (byte)0xC0;
                bytes[j++] = (byte)0x80;
            } else {
                bytes[j++] = (byte)uch;
            }
        }
    }

    static int mutf8FromStringLength(String string) {
        char[] chars = string.toCharArray();

        return mutf8FromCharsLength(chars);
    }

    public static byte[] mutf8FromString(String string) {
        char[] chars = string.toCharArray();
        int length = mutf8FromCharsLength(chars);
        byte[] bytes = new byte[length];
        mutf8FromChars(bytes, 0, chars);

        return bytes;
    }
}
