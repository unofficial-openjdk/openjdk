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
import java.io.File;
import java.io.FilePermission;
import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.MalformedURLException;
import java.security.Permission;

import jdk.internal.jimage.ImageReader;
import jdk.internal.jimage.ImageLocation;

/**
 * JDK internal (and unsupported) protocol handler for accessing jimage files
 * on the file system.
 */

public class Handler extends URLStreamHandler {
    public Handler() { }

    @Override
    protected URLConnection openConnection(URL url) throws IOException {
        return new JImageURLConnection(url);
    }
}

class JImageURLConnection extends URLConnection {
    private final URL base;
    private final ImageReader jimage;
    private final ImageLocation location;

    // set lazily if needed
    private volatile Permission permission;

    JImageURLConnection(URL url) throws IOException {
        super(url);

        String s = url.toString();
        int index = s.indexOf("!/");
        if (index == -1)
            throw new MalformedURLException("no !/ found in url spec:" + s);
        URL base = new URL(s.substring(0, index++));

        // use jimage cache to open or get existing connection to jimage file
        ImageReader jimage = sun.misc.JImageCache.get(base);
        if (jimage == null)
            throw new IOException("cannot open " + base);

        String entry = sun.net.www.ParseUtil.decode(s.substring(index+1));
        ImageLocation location = jimage.findLocation(entry);
        if (location == null)
            throw new IOException(entry + " not found");

        this.base = base;
        this.jimage = jimage;
        this.location = location;
    }

    @Override
    public void connect() { }

    @Override
    public InputStream getInputStream() throws IOException {
        byte[] resource = jimage.getResource(location);
        return new ByteArrayInputStream(resource);
    }
    @Override
    public long getContentLengthLong() {
        return location.getUncompressedSize();
    }

    @Override
    public Permission getPermission() throws IOException {
        Permission p = this.permission;
        if (p == null) {
            assert base.getProtocol().equalsIgnoreCase("jimage");
            URL fileURL = new URL("file" + base.toString().substring(6));
            String decodedPath = sun.net.www.ParseUtil.decode(fileURL.getPath());
            if (File.separatorChar != '/')
                decodedPath = decodedPath.replace('/',File.separatorChar);
            p = new FilePermission(decodedPath, "read");
            this.permission = p;
        }
        return p;
    }
}
