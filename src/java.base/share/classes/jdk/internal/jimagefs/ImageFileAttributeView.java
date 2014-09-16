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

package jdk.internal.jimagefs;

import java.nio.file.attribute.*;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

final class ImageFileAttributeView implements BasicFileAttributeView
{
    private static enum AttrID {
        size,
        creationTime,
        lastAccessTime,
        lastModifiedTime,
        isDirectory,
        isRegularFile,
        isSymbolicLink,
        isOther,
        fileKey,
        compressedSize,
        extension,
        contentOffset
    };

    private final ImagePath path;
    private final boolean isImageView;

    private ImageFileAttributeView(ImagePath path, boolean isImageView) {
        this.path = path;
        this.isImageView = isImageView;
    }

    @SuppressWarnings("unchecked") // Cast to V
    static <V extends FileAttributeView> V get(ImagePath path, Class<V> type) {
        if (type == null)
            throw new NullPointerException();
        if (type == BasicFileAttributeView.class)
            return (V)new ImageFileAttributeView(path, false);
        if (type == ImageFileAttributeView.class)
            return (V)new ImageFileAttributeView(path, true);
        return null;
    }

    static ImageFileAttributeView get(ImagePath path, String type) {
        if (type == null)
            throw new NullPointerException();
        if (type.equals("basic"))
            return new ImageFileAttributeView(path, false);
        if (type.equals("jimage"))
            return new ImageFileAttributeView(path, true);
        return null;
    }

    @Override
    public String name() {
        return isImageView ? "jimage" : "basic";
    }

    @Override
    public ImageFileAttributes readAttributes() throws IOException
    {
        return path.getAttributes();
    }

    @Override
    public void setTimes(FileTime lastModifiedTime,
                         FileTime lastAccessTime,
                         FileTime createTime)
        throws IOException
    {
        path.setTimes(lastModifiedTime, lastAccessTime, createTime);
    }

    void setAttribute(String attribute, Object value)
        throws IOException
    {
        try {
            if (AttrID.valueOf(attribute) == AttrID.lastModifiedTime)
                setTimes ((FileTime)value, null, null);
            if (AttrID.valueOf(attribute) == AttrID.lastAccessTime)
                setTimes (null, (FileTime)value, null);
            if (AttrID.valueOf(attribute) == AttrID.creationTime)
                setTimes (null, null, (FileTime)value);
            return;
        } catch (IllegalArgumentException x) {}
        throw new UnsupportedOperationException("'" + attribute +
            "' is unknown or read-only attribute");
    }

    Map<String, Object> readAttributes(String attributes)
        throws IOException
    {
        ImageFileAttributes imagefas = readAttributes();
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        if ("*".equals(attributes)) {
            for (AttrID id : AttrID.values()) {
                try {
                    map.put(id.name(), attribute(id, imagefas));
                } catch (IllegalArgumentException x) {}
            }
        } else {
            String[] as = attributes.split(",");
            for (String a : as) {
                try {
                    map.put(a, attribute(AttrID.valueOf(a), imagefas));
                } catch (IllegalArgumentException x) {}
            }
        }
        return map;
    }

    Object attribute(AttrID id, ImageFileAttributes imagefas) {
        switch (id) {
        case size:
            return imagefas.size();
        case creationTime:
            return imagefas.creationTime();
        case lastAccessTime:
            return imagefas.lastAccessTime();
        case lastModifiedTime:
            return imagefas.lastModifiedTime();
        case isDirectory:
            return imagefas.isDirectory();
        case isRegularFile:
            return imagefas.isRegularFile();
        case isSymbolicLink:
            return imagefas.isSymbolicLink();
        case isOther:
            return imagefas.isOther();
        case fileKey:
            return imagefas.fileKey();
        case compressedSize:
            if (isImageView)
                return imagefas.compressedSize();
            break;
        case extension:
            if (isImageView) {
                return imagefas.extension();
            }
            break;
        case contentOffset:
            if (isImageView) {
                return imagefas.contentOffset();
            }
            break;
        }
        return null;
    }
}
