/*
 * Copyright (c) 2000, 2005, Oracle and/or its affiliates. All rights reserved.
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

package sun.java2d.windows;

import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Transparency;
import java.awt.image.ColorModel;
import sun.awt.Win32GraphicsDevice;
import sun.java2d.loops.SurfaceType;

public class WinBackBufferSurfaceData extends Win32OffScreenSurfaceData {

    private Win32SurfaceData parentData;

    /**
     * Private constructor.  Use createData() to create an object.
     */
    private WinBackBufferSurfaceData(int width, int height,
                                     SurfaceType sType, ColorModel cm,
                                     GraphicsConfiguration gc,
                                     Image image, int screen,
                                     Win32SurfaceData parentData)
    {
        super(width, height, sType, cm, gc, image, Transparency.OPAQUE);
        this.parentData = parentData;
        initSurface(cm.getPixelSize(), width, height, screen, parentData);
    }

    private native void initSurface(int depth, int width, int height,
                                    int screen, Win32SurfaceData parentData);

    public void restoreSurface() {
        parentData.restoreSurface();
    }

    public static WinBackBufferSurfaceData
        createData(int width, int height,
                   ColorModel cm, GraphicsConfiguration gc, Image image,
                   Win32SurfaceData parentData)
    {
        Win32GraphicsDevice gd = (Win32GraphicsDevice)gc.getDevice();
        SurfaceType sType = getSurfaceType(cm, Transparency.OPAQUE);
        return new WinBackBufferSurfaceData(width, height, sType,
                                            cm, gc, image,
                                            gd.getScreen(), parentData);
    }
}
