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

public final class ImageLocation {
    final static int ATTRIBUTE_END = 0;
    final static int ATTRIBUTE_MODULE = 1;
    final static int ATTRIBUTE_PARENT = 2;
    final static int ATTRIBUTE_BASE = 3;
    final static int ATTRIBUTE_EXTENSION = 4;
    final static int ATTRIBUTE_OFFSET = 5;
    final static int ATTRIBUTE_COMPRESSED = 6;
    final static int ATTRIBUTE_UNCOMPRESSED = 7;
    final static int ATTRIBUTE_COUNT = 8;

    private int locationOffset;
    private long[] attributes;
    private byte[] bytes;
    private final ImageStrings strings;

    private ImageLocation(ImageStrings strings) {
        this.strings = strings;
    }

    void writeTo(ImageStream stream) {
        compress();
        locationOffset = stream.getPosition();
        stream.put(bytes, 0, bytes.length);
    }

    static ImageLocation readFrom(ByteBuffer locationsBuffer, int offset,
            ImageStrings strings) {
        final long[] attributes = new long[ATTRIBUTE_COUNT];

        for (int i = offset; true; ) {
            int data = locationsBuffer.get(i++) & 0xFF;
            int kind = attributeKind(data);
            assert ATTRIBUTE_END <= kind && kind < ATTRIBUTE_COUNT : "Invalid attribute kind";

            if (kind == ATTRIBUTE_END) {
                break;
            }

            int length = attributeLength(data);
            long value = 0;

            for (int j = 0; j < length; j++) {
                value <<= 8;
                value |= locationsBuffer.get(i++) & 0xFF;
            }

            attributes[kind] = value;
        }

        ImageLocation location =  new ImageLocation(strings);
        location.attributes = attributes;

        return location;
    }

    private static int attributeLength(int data) {
        return (data & 0x7) + 1;
    }

    private static int attributeKind(int data) {
        return data >>> 3;
    }

    public boolean verify(UTF8String name) {
        return UTF8String.equals(getFullName(), name);
    }

    long getAttribute(int kind) {
        assert ATTRIBUTE_END < kind && kind < ATTRIBUTE_COUNT : "Invalid attribute kind";
        decompress();

        return attributes[kind];
    }

    UTF8String getAttributeUTF8String(int kind) {
        assert ATTRIBUTE_END < kind && kind < ATTRIBUTE_COUNT : "Invalid attribute kind";
        decompress();

        return strings.get((int)attributes[kind]);
    }

    String getAttributeString(int kind) {
        return getAttributeUTF8String(kind).toString();
    }

    ImageLocation addAttribute(int kind, long value) {
        assert ATTRIBUTE_END < kind && kind < ATTRIBUTE_COUNT : "Invalid attribute kind";
        decompress();
        attributes[kind] = value;
        return this;
    }

    ImageLocation addAttribute(int kind, UTF8String value) {
        return addAttribute(kind, strings.add(value));
    }

    private void decompress() {
        if (attributes == null) {
            attributes = new long[ATTRIBUTE_COUNT];
        }

        if (bytes != null) {
            for (int i = 0; i < bytes.length; ) {
                int data = bytes[i++] & 0xFF;
                int kind = attributeKind(data);

                if (kind == ATTRIBUTE_END) {
                    break;
                }

                assert ATTRIBUTE_END < kind && kind < ATTRIBUTE_COUNT : "Invalid attribute kind";
                int length = attributeLength(data);
                long value = 0;

                for (int j = 0; j < length; j++) {
                    value <<= 8;
                    value |= bytes[i++] & 0xFF;
                }

                 attributes[kind] = value;
            }

            bytes = null;
        }
    }

    private void compress() {
        if (bytes == null) {
            ImageStream stream = new ImageStream(16);

            for (int kind = ATTRIBUTE_END + 1; kind < ATTRIBUTE_COUNT; kind++) {
                long value = attributes[kind];

                if (value != 0) {
                    int n = (63 - Long.numberOfLeadingZeros(value)) >> 3;
                    stream.put((kind << 3) | n);

                    for (int i = n; i >= 0; i--) {
                        stream.put((int)(value >> (i << 3)));
                    }
                }
            }

            stream.put(ATTRIBUTE_END << 3);
            bytes = stream.toArray();
            attributes = null;
        }
    }

    static ImageLocation newLocation(UTF8String fullName, ImageStrings strings,
            long contentOffset, long compressedSize, long uncompressedSize) {
        UTF8String moduleName = UTF8String.EMPTY_STRING;
        UTF8String parentName = UTF8String.EMPTY_STRING;
        UTF8String baseName;
        UTF8String extensionName = UTF8String.EMPTY_STRING;

        int offset = fullName.indexOf('/', 1);
        if (fullName.length() >= 2 && fullName.charAt(0) == '/' && offset != -1) {
            moduleName = fullName.substring(1, offset - 1);
            fullName = fullName.substring(offset + 1);
        }

        offset = fullName.lastIndexOf('/');
        if (offset != -1) {
            parentName = fullName.substring(0, offset);
            fullName = fullName.substring(offset + 1);
        }

        offset = fullName.lastIndexOf('.');
        if (offset != -1) {
            baseName = fullName.substring(0, offset);
            extensionName = fullName.substring(offset + 1);
        } else {
            baseName = fullName;
        }

        return new ImageLocation(strings)
               .addAttribute(ATTRIBUTE_MODULE, moduleName)
               .addAttribute(ATTRIBUTE_PARENT, parentName)
               .addAttribute(ATTRIBUTE_BASE, baseName)
               .addAttribute(ATTRIBUTE_EXTENSION, extensionName)
               .addAttribute(ATTRIBUTE_OFFSET, contentOffset)
               .addAttribute(ATTRIBUTE_COMPRESSED, compressedSize)
               .addAttribute(ATTRIBUTE_UNCOMPRESSED, uncompressedSize);
    }

    @Override
    public int hashCode() {
        return hashCode(UTF8String.HASH_MULTIPLIER);
    }

    int hashCode(int seed) {
        int hash = seed;

        if (getModuleOffset() != 0) {
            hash = UTF8String.SLASH_STRING.hashCode(hash);
            hash = getModule().hashCode(hash);
            hash = UTF8String.SLASH_STRING.hashCode(hash);
        }

        if (getParentOffset() != 0) {
            hash = getParent().hashCode(hash);
            hash = UTF8String.SLASH_STRING.hashCode(hash);
        }

        hash = getBase().hashCode(hash);

        if (getExtensionOffset() != 0) {
            hash = UTF8String.DOT_STRING.hashCode(hash);
            hash = getExtension().hashCode(hash);
        }

        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof ImageLocation)) {
            return false;
        }

        ImageLocation other = (ImageLocation)obj;

        return getModuleOffset() == other.getModuleOffset() &&
               getParentOffset() == other.getParentOffset() &&
               getBaseOffset() == other.getBaseOffset() &&
               getExtensionOffset() == other.getExtensionOffset();
    }

    int getLocationOffset() {
        return locationOffset;
    }

    UTF8String getModule() {
        return getAttributeUTF8String(ATTRIBUTE_MODULE);
    }

    public String getModuleString() {
        return getModule().toString();
    }

    int getModuleOffset() {
        return (int)getAttribute(ATTRIBUTE_MODULE);
    }

    UTF8String getBase() {
        return getAttributeUTF8String(ATTRIBUTE_BASE);
    }

    public String getBaseString() {
        return  getBase().toString();
    }

    int getBaseOffset() {
        return (int)getAttribute(ATTRIBUTE_BASE);
    }

    UTF8String getParent() {
        return getAttributeUTF8String(ATTRIBUTE_PARENT);
    }

    public String getParentString() {
        return getParent().toString();
    }

    int getParentOffset() {
        return (int)getAttribute(ATTRIBUTE_PARENT);
    }

    UTF8String getExtension() {
        return getAttributeUTF8String(ATTRIBUTE_EXTENSION);
    }

    public String getExtensionString() {
        return getExtension().toString();
    }

    int getExtensionOffset() {
        return (int)getAttribute(ATTRIBUTE_EXTENSION);
    }

    UTF8String getFullName() {
        return getFullName(false);
    }

    UTF8String getFullName(boolean modulesPrefix) {
        // Note: Consider a UTF8StringBuilder.
        UTF8String fullName = UTF8String.EMPTY_STRING;

        if (getModuleOffset() != 0) {
            fullName = fullName.concat(
                modulesPrefix? UTF8String.MODULES_STRING : UTF8String.EMPTY_STRING,
                UTF8String.SLASH_STRING,
                getModule(),
                UTF8String.SLASH_STRING);
        }

        if (getParentOffset() != 0) {
            fullName = fullName.concat(getParent(),
                                       UTF8String.SLASH_STRING);
        }

        fullName = fullName.concat(getBase());

        if (getExtensionOffset() != 0) {
                fullName = fullName.concat(UTF8String.DOT_STRING,
                                           getExtension());
        }

        return fullName;
    }

    UTF8String buildName(boolean includeModule, boolean includeParent,
            boolean includeName) {
        // Note: Consider a UTF8StringBuilder.
        UTF8String name = UTF8String.EMPTY_STRING;

        if (includeModule && getModuleOffset() != 0) {
            name = name.concat(UTF8String.MODULES_STRING,
                               UTF8String.SLASH_STRING,
                               getModule());
        }

        if (includeParent && getParentOffset() != 0) {
            name = name.concat(UTF8String.SLASH_STRING,
                                       getParent());
        }

        if (includeName) {
            if (includeModule || includeParent) {
                name = name.concat(UTF8String.SLASH_STRING);
            }

            name = name.concat(getBase());

            if (getExtensionOffset() != 0) {
                name = name.concat(UTF8String.DOT_STRING,
                                           getExtension());
            }
        }

        return name;
    }

    String getFullNameString() {
        return getFullName().toString();
    }

    public long getContentOffset() {
        return getAttribute(ATTRIBUTE_OFFSET);
    }

    public long getCompressedSize() {
        return getAttribute(ATTRIBUTE_COMPRESSED);
    }

    public long getUncompressedSize() {
        return getAttribute(ATTRIBUTE_UNCOMPRESSED);
    }

    @Override
    public String toString() {
        decompress();
        final StringBuilder sb = new StringBuilder();

        sb.append("Name: ");
        sb.append(getFullNameString());
        sb.append("; ");

        sb.append("Offset: ");
        sb.append(getContentOffset());
        sb.append("; ");

        sb.append("Compressed: ");
        sb.append(getCompressedSize());
        sb.append("; ");

        sb.append("Uncompressed: ");
        sb.append(getUncompressedSize());
        sb.append("; ");

        return sb.toString();
    }
}
