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

package sun.net.www.protocol.jimage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.MalformedURLException;

import jdk.jigsaw.module.internal.ImageReader;
import jdk.jigsaw.module.internal.ImageLocation;

/**
 * Experimental protocol handler for accessing jimages on the module path.
 *
 * ##FIXME: No permission checking or support for jimages not on the module path.
 */

public class Handler extends URLStreamHandler {
    public Handler() { }

    @Override
    protected URLConnection openConnection(URL url) throws IOException {
        String s = url.toString();
        int index = s.indexOf("!/");
        if (index == -1)
            throw new MalformedURLException("no !/ found in url spec:" + s);
        URL base = new URL(s.substring(0, index++));
        final ImageReader jimage = sun.misc.JImageCache.get(base);
        if (jimage == null)
            throw new IOException("jimage not in cache");
        String entry = sun.net.www.ParseUtil.decode(s.substring(index+1));
        final ImageLocation location = jimage.findLocation(entry);
        if (location == null)
            throw new IOException(entry + " not found");
        return new URLConnection(url) {
            @Override
            public void connect() { }
            @Override
            public InputStream getInputStream() throws IOException {
                long offset = location.getContentOffset();
                long size = location.getUncompressedSize();
                long compressedSize = location.getCompressedSize();
                byte[] resource;
                if (compressedSize != 0) {
                    // TODO - handle compression.
                    resource = jimage.getResource(offset, compressedSize);
                    // resource = decompress(resource);
                } else {
                    resource = jimage.getResource(offset, size);
                }

                return new ByteArrayInputStream(resource);
            }
        };
    }
}
