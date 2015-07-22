/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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


package sun.lwawt;

import sun.awt.CGraphicsConfig;

import java.awt.BufferCapabilities;
import java.awt.BufferCapabilities.FlipContents;
import java.awt.Canvas;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.image.VolatileImage;
import java.awt.peer.CanvasPeer;

import javax.swing.JComponent;

final class LWCanvasPeer extends LWComponentPeer<Component, JComponent>
        implements CanvasPeer {

    /**
     * The back buffer provide user with a BufferStrategy.
     */
    private VolatileImage backBuffer;

    LWCanvasPeer(final Canvas target,
                 final PlatformComponent platformComponent) {
        super(target, platformComponent);
    }

    @Override
    public void createBuffers(final int numBuffers,
                              final BufferCapabilities caps) {
        //TODO parameters should be used.
        final CGraphicsConfig gc = (CGraphicsConfig) getGraphicsConfiguration();
        final VolatileImage buffer = gc.createBackBufferImage(getTarget(), 0);
        synchronized (getStateLock()) {
            backBuffer = buffer;
        }
    }

    @Override
    public Image getBackBuffer() {
        synchronized (getStateLock()) {
            return backBuffer;
        }
    }

    @Override
    public void flip(final int x1, final int y1, final int x2, final int y2,
                     final FlipContents flipAction) {
        final VolatileImage buffer = (VolatileImage) getBackBuffer();
        if (buffer == null) {
            throw new IllegalStateException("Buffers have not been created");
        }
        final Graphics g = getGraphics();
        try {
            g.drawImage(buffer, x1, y1, x2, y2, x1, y1, x2, y2, null);
        } finally {
            g.dispose();
        }
        if (flipAction == FlipContents.BACKGROUND) {
            final Graphics2D bg = (Graphics2D) buffer.getGraphics();
            try {
                bg.setBackground(getBackground());
                bg.clearRect(0, 0, buffer.getWidth(), buffer.getHeight());
            } finally {
                bg.dispose();
            }
        }
    }

    @Override
    public void destroyBuffers() {
        final Image buffer = getBackBuffer();
        if (buffer != null) {
            synchronized (getStateLock()) {
                if (buffer == backBuffer) {
                    backBuffer = null;
                }
            }
            buffer.flush();
        }
    }

    @Override
    public GraphicsConfiguration getAppropriateGraphicsConfiguration(
            GraphicsConfiguration gc)
    {
        // TODO
        return gc;
    }
}
