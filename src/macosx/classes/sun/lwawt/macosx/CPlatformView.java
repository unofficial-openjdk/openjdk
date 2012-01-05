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

package sun.lwawt.macosx;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.VolatileImage;

import sun.awt.CGraphicsConfig;
import sun.lwawt.LWWindowPeer;
import sun.lwawt.macosx.event.NSEvent;

import sun.java2d.SurfaceData;
import sun.java2d.opengl.CGLLayer;
import sun.java2d.opengl.CGLSurfaceData;

public class CPlatformView extends CFRetainedResource {
    private native long nativeCreateView(int x, int y, int width, int height, long windowLayerPtr);

    private LWWindowPeer peer;
    private SurfaceData surfaceData;
    private CGLLayer windowLayer;

    public CPlatformView() {
        super(0, true);
    }

    public void initialize(LWWindowPeer peer) {
        this.peer = peer;

        if (!LWCToolkit.getSunAwtDisableCALayers()) {
            this.windowLayer = new CGLLayer(peer);
        }
        setPtr(nativeCreateView(0, 0, 0, 0, getWindowLayerPtr()));
    }

    public long getAWTView() {
        return ptr;
    }

    public boolean isOpaque() {
        return peer.isOpaque();
    }

    /*
     * All coordinates passed to the method should be based on the origin being in the bottom-left corner (standard
     * Cocoa coordinates).
     */
    public void setBounds(int x, int y, int width, int height) {
        CWrapper.NSView.setFrame(ptr, x, y, width, height);
    }

    // REMIND: CGLSurfaceData expects top-level's size
    public Rectangle getBounds() {
        return peer.getBounds();
    }

    public Object getDestination() {
        return peer;
    }

    public void enterFullScreenMode(final long nsWindowPtr) {
        CWrapper.NSView.enterFullScreenMode(ptr);

        // REMIND: CGLSurfaceData expects top-level's size
        // and therefore we need to account insets before
        // recreating the surface data
        Insets insets = peer.getInsets();

        Rectangle screenBounds;
        final long screenPtr = CWrapper.NSWindow.screen(nsWindowPtr);
        try {
            screenBounds = CWrapper.NSScreen.frame(screenPtr).getBounds();
        } finally {
            CWrapper.NSObject.release(screenPtr);
        }

        // the move/size notification from the underlying system comes
        // but it contains a bounds smaller than the whole screen
        // and therefore we need to create the synthetic notifications
        peer.notifyReshape(screenBounds.x - insets.left,
                           screenBounds.y - insets.bottom,
                           screenBounds.width + insets.left + insets.right,
                           screenBounds.height + insets.top + insets.bottom);
    }

    public void exitFullScreenMode() {
        CWrapper.NSView.exitFullScreenMode(ptr);
    }

    // ----------------------------------------------------------------------
    // PAINTING METHODS
    // ----------------------------------------------------------------------

    public void drawImageOnPeer(VolatileImage xBackBuffer, int x1, int y1, int x2, int y2) {
        Graphics g = peer.getGraphics();
        try {
            g.drawImage(xBackBuffer, x1, y1, x2, y2, x1, y1, x2, y2, null);
        } finally {
            g.dispose();
        }
    }

    public Image createBackBuffer() {
        Rectangle r = peer.getBounds();
        Image im = null;
        if (!r.isEmpty()) {
            int transparency = (isOpaque() ? Transparency.OPAQUE : Transparency.TRANSLUCENT);
            im = peer.getGraphicsConfiguration().createCompatibleImage(r.width, r.height, transparency);
        }
        return im;
    }

    public SurfaceData replaceSurfaceData() {
        if (!LWCToolkit.getSunAwtDisableCALayers()) {
            surfaceData = windowLayer.replaceSurfaceData();
        } else {
            if (surfaceData == null) {
                CGraphicsConfig graphicsConfig = (CGraphicsConfig)peer.getGraphicsConfiguration();
                surfaceData = graphicsConfig.createSurfaceData(this);
            } else {
                validateSurface();
            }
        }
        return surfaceData;
    }

    private void validateSurface() {
        if (surfaceData != null) {
            ((CGLSurfaceData)surfaceData).validate();
        }
    }

    public GraphicsConfiguration getGraphicsConfiguration() {
        return peer.getGraphicsConfiguration();
    }

    public SurfaceData getSurfaceData() {
        return surfaceData;
    }

    @Override
    public void dispose() {
        if (!LWCToolkit.getSunAwtDisableCALayers()) {
            windowLayer.dispose();
        }
        super.dispose();
    }

    public long getWindowLayerPtr() {
        if (!LWCToolkit.getSunAwtDisableCALayers()) {
            return windowLayer.getPointer();
        } else {
            return 0;
        }
    }

    // ----------------------------------------------------------------------
    // NATIVE CALLBACKS
    // ----------------------------------------------------------------------

    // This code uses peer's API which is a no-no on the AppKit thread.
    // TODO: post onto the EDT.
    private void deliverMouseEvent(NSEvent event) {
        int jModifiers = event.getModifiers();
        int jX = event.getX();
        // a difference in coordinate systems
        int jY = getBounds().height - event.getY();
        int jAbsX = event.getAbsX();
        int jAbsY = event.getAbsY();
        int jButton = NSEvent.nsButtonToJavaButton(event);
        int jClickCount = event.getClickCount();
        double wheelDeltaY = event.getScrollDeltaY();
        double wheelDeltaX = event.getScrollDeltaX();
        boolean isPopupTrigger = event.isPopupTrigger();

        int jEventType;
        switch (event.getType()) {
            case CocoaConstants.NSLeftMouseDown:
            case CocoaConstants.NSRightMouseDown:
            case CocoaConstants.NSOtherMouseDown:
                jEventType = MouseEvent.MOUSE_PRESSED;
                break;
            case CocoaConstants.NSLeftMouseUp:
            case CocoaConstants.NSRightMouseUp:
            case CocoaConstants.NSOtherMouseUp:
                jEventType = MouseEvent.MOUSE_RELEASED;
                break;
            case CocoaConstants.NSMouseMoved:
                jEventType = MouseEvent.MOUSE_MOVED;
                break;

            case CocoaConstants.NSLeftMouseDragged:
            case CocoaConstants.NSRightMouseDragged:
            case CocoaConstants.NSOtherMouseDragged:
                jEventType = MouseEvent.MOUSE_DRAGGED;
                break;
            case CocoaConstants.NSMouseEntered:
                jEventType = MouseEvent.MOUSE_ENTERED;
                break;
            case CocoaConstants.NSMouseExited:
                jEventType = MouseEvent.MOUSE_EXITED;
                break;
            case CocoaConstants.NSScrollWheel:
                jEventType = MouseEvent.MOUSE_WHEEL;
                double wheelDelta = wheelDeltaY;

                // shift+vertical wheel scroll produces horizontal scroll
                // we convert it to vertical
                if ((jModifiers & InputEvent.SHIFT_DOWN_MASK) != 0) {
                    wheelDelta = wheelDeltaX;
                }

                // Wheel amount "oriented" inside out
                wheelDelta = -wheelDelta;
                peer.dispatchMouseWheelEvent(System.currentTimeMillis(), jX, jY, jModifiers, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, // WHEEL_SCROLL_AMOUNT
                        (int)wheelDelta, wheelDelta, null);
                return;
            default:
                return;
        }
        peer.dispatchMouseEvent(jEventType, System.currentTimeMillis(), jButton, jX, jY, jAbsX, jAbsY, jModifiers, jClickCount, isPopupTrigger, null);
    }

    private void deliverKeyEvent(final int javaKeyType, final int javaModifiers, final char testChar, final int javaKeyCode, final int javaKeyLocation) {
        // TODO: there is no focus owner installed now, get back to this once we assign it properly.
        java.awt.EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                peer.dispatchKeyEvent(javaKeyType, System.currentTimeMillis(), javaModifiers, javaKeyCode, testChar, javaKeyLocation);

                // That's the reaction on the PRESSED (not RELEASED) event as it comes to
                // appear in MacOSX.
                // Modifier keys (shift, etc) don't want to send TYPED events.
                // On the other hand we don't want to generate keyTyped events
                // for clipboard related shortcuts like Meta + [CVX]
                boolean isMetaDown = (javaModifiers & KeyEvent.META_DOWN_MASK) != 0;
                if (!isMetaDown && javaKeyType == KeyEvent.KEY_PRESSED && testChar != KeyEvent.CHAR_UNDEFINED) {
                    boolean isCtrlDown = (javaModifiers & KeyEvent.CTRL_DOWN_MASK) != 0;
                    boolean isShiftDown = (javaModifiers & KeyEvent.SHIFT_DOWN_MASK) != 0;
                    // emulating the codes from the ASCII table
                    int shift = isCtrlDown ? isShiftDown ? 64 : 96 : 0;
                    peer.dispatchKeyEvent(KeyEvent.KEY_TYPED, System.currentTimeMillis(), javaModifiers,
                            KeyEvent.VK_UNDEFINED, (char) (testChar - shift), KeyEvent.KEY_LOCATION_UNKNOWN);
                }
            }
        });
    }

    private void deliverWindowDidExposeEvent() {
        Rectangle r = peer.getBounds();
        peer.notifyExpose(0, 0, r.width, r.height);
    }

    private void deliverWindowDidExposeEvent(float x, float y, float w, float h) {
        peer.notifyExpose((int)x, (int)y, (int)w, (int)h);
    }
}
