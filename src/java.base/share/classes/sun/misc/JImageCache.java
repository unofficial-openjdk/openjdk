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

package sun.misc;

import java.net.URL;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;
import jdk.jigsaw.module.internal.ImageReader;

/**
 * A simple cache of open jimage files used to support URL connections
 * to jimages on the module path.
 */

public class JImageCache {
    private JImageCache() { }

    private static final Map<URL, ImageReader> jimages = new ConcurrentHashMap<>();

    /**
     * Returns a {@code ImageReader} that can be used to read entries from a
     * jimage file that is located by the given URL. The {@code ImageReader}
     * instances may be shared with other threads so care should be taken
     * not to close the file.
     * @param url JImage URL to locate.
     * @return ImageReader for found handler or null.
     * @throws java.io.IOException
     */
    public static ImageReader get(URL url) throws IOException {
        ImageReader jimage = jimages.get(url);
        if (jimage != null)
            return jimage;

        // not in cache so need to open it
        String s = url.toString();
        if (!s.startsWith("jimage:"))
            throw new IOException("not a jimage URL");
        s = "file" + s.substring(6);
        jimage = new ImageReader(URI.create(s).getPath());
        jimage.open();

        // potential race with other threads opening the same URL
        ImageReader previous = jimages.putIfAbsent(url, jimage);
        if (previous == null) {
            return jimage;
        } else {
            jimage.close();
            return previous;
        }
    }

    /**
     * Removes an entry from the cache.
     * @param url URL to remove from cache.
     * @return Cache entry or null.
     */
    public static ImageReader remove(URL url) {
        ImageReader jimage = jimages.remove(url);
        if (jimage != null) {
            try {
                jimage.close();
            } catch (IOException ex) {
                // Ignore
            }
        }
        return jimage;
    }
}
