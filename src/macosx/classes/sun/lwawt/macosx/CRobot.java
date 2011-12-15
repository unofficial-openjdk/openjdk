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
import java.awt.peer.*;

import sun.awt.CGraphicsDevice;

class CRobot implements RobotPeer {

    private static final int MOUSE_LOCATION_UNKNOWN      = -1;
    private static final int BUTTON_STATE_UNKNOWN        = 0;
    private static final int BUTTON_STATE_DOWN           = 1;
    private static final int BUTTON_STATE_UP             = 2;
    private static final int MODIFIER_PREVIOUSLY_UNKNOWN = 0;
    private static final int MODIFIER_PREVIOUSLY_UP      = 1;
    private static final int MODIFIER_PREVIOUSLY_DOWN    = 2;

    private final CGraphicsDevice fDevice;
    private int fMouseLastX             = MOUSE_LOCATION_UNKNOWN;
    private int fMouseLastY             = MOUSE_LOCATION_UNKNOWN;
    private int fMouse1DesiredState     = BUTTON_STATE_UNKNOWN;
    private int fMouse2DesiredState     = BUTTON_STATE_UNKNOWN;
    private int fMouse3DesiredState     = BUTTON_STATE_UNKNOWN;
    private int fMouse2KeyModifierState = MODIFIER_PREVIOUSLY_UNKNOWN;
    private int fMouse3KeyModifierState = MODIFIER_PREVIOUSLY_UNKNOWN;
    private boolean fMouseMoveAction;

    /**
     * Uses the given GraphicsDevice as the coordinate system for subsequent
     * coordinate calls.
     */
    public CRobot(Robot r, CGraphicsDevice d) {
        fDevice = d;
        initRobot();
    }

    @Override
    public void dispose() {
    }

    /**
     * Moves mouse pointer to given screen coordinates.
     * @param x X position
     * @param y Y position
     */
    @Override
    public void mouseMove(int x, int y) {
        fMouseLastX = x;
        fMouseLastY = y;
        fMouseMoveAction = true;
        mouseEvent(fDevice.getCoreGraphicsScreen(), fMouseLastX, fMouseLastY,
                   fMouse1DesiredState, fMouse2DesiredState,
                   fMouse3DesiredState, fMouseMoveAction);
    }

    /**
     * Presses one or more mouse buttons.
     *
     * @param buttons the button mask (combination of
     * <code>InputEvent.BUTTON1/2/3_MASK</code>)
     */
    @Override
    public void mousePress(int buttons) {
        if ((buttons & InputEvent.BUTTON1_MASK) != 0 ||
                (buttons & InputEvent.BUTTON1_DOWN_MASK) != 0) {
            fMouse1DesiredState = BUTTON_STATE_DOWN;
        } else {
            fMouse1DesiredState = BUTTON_STATE_UNKNOWN;
        }

        if ((buttons & InputEvent.BUTTON2_MASK) != 0 ||
                (buttons & InputEvent.BUTTON2_DOWN_MASK) != 0) {
            fMouse2DesiredState = BUTTON_STATE_DOWN;
        } else {
            fMouse2DesiredState = BUTTON_STATE_UNKNOWN;
        }

        if ((buttons & InputEvent.BUTTON3_MASK) != 0 ||
                (buttons & InputEvent.BUTTON3_DOWN_MASK) != 0) {
            fMouse3DesiredState = BUTTON_STATE_DOWN;
        } else {
            fMouse3DesiredState = BUTTON_STATE_UNKNOWN;
        }

        fMouseMoveAction = false;

        mouseEvent(fDevice.getCoreGraphicsScreen(), fMouseLastX, fMouseLastY,
                   fMouse1DesiredState, fMouse2DesiredState,
                   fMouse3DesiredState, fMouseMoveAction);
    }

    /**
     * Releases one or more mouse buttons.
     *
     * @param buttons the button mask (combination of
     * <code>InputEvent.BUTTON1/2/3_MASK</code>)
     */
    @Override
    public void mouseRelease(int buttons) {
        if ((buttons & InputEvent.BUTTON1_MASK) != 0 ||
                (buttons & InputEvent.BUTTON1_DOWN_MASK) != 0) {
            fMouse1DesiredState = BUTTON_STATE_UP;
        } else {
            fMouse1DesiredState = BUTTON_STATE_UNKNOWN;
        }

        if ((buttons & InputEvent.BUTTON2_MASK) != 0 ||
                (buttons & InputEvent.BUTTON2_DOWN_MASK) != 0) {
            fMouse2DesiredState = BUTTON_STATE_UP;
        } else {
            fMouse2DesiredState = BUTTON_STATE_UNKNOWN;
        }

        if ((buttons & InputEvent.BUTTON3_MASK) != 0 ||
                (buttons & InputEvent.BUTTON3_DOWN_MASK) != 0) {
            fMouse3DesiredState = BUTTON_STATE_UP;
        } else {
            fMouse3DesiredState = BUTTON_STATE_UNKNOWN;
        }

        fMouseMoveAction = false;

        mouseEvent(fDevice.getCoreGraphicsScreen(), fMouseLastX, fMouseLastY,
                   fMouse1DesiredState, fMouse2DesiredState,
                   fMouse3DesiredState, fMouseMoveAction);
    }

    @Override
    public native void mouseWheel(int wheelAmt);

    /**
     * Presses a given key.
     * <p>
     * Key codes that have more than one physical key associated with them
     * (e.g. <code>KeyEvent.VK_SHIFT</code> could mean either the
     * left or right shift key) will map to the left key.
     * <p>
     * Assumes that the
     * peer implementations will throw an exception for other bogus
     * values e.g. -1, 999999
     *
     * @param keyCode the key to press (e.g. <code>KeyEvent.VK_A</code>)
     */
    @Override
    public void keyPress(int keycode) {
        keyEvent(keycode, true);
    }

    /**
     * Releases a given key.
     * <p>
     * Key codes that have more than one physical key associated with them
     * (e.g. <code>KeyEvent.VK_SHIFT</code> could mean either the
     * left or right shift key) will map to the left key.
     * <p>
     * Assumes that the
     * peer implementations will throw an exception for other bogus
     * values e.g. -1, 999999
     *
     * @param keyCode the key to release (e.g. <code>KeyEvent.VK_A</code>)
     */
    @Override
    public void keyRelease(int keycode) {
        keyEvent(keycode, false);
    }

    /**
     * Returns the color of a pixel at the given screen coordinates.
     * @param x X position of pixel
     * @param y Y position of pixel
     * @return color of the pixel
     */
    @Override
    public int getRGBPixel(int x, int y) {
        int c[] = new int[1];
        getScreenPixels(new Rectangle(x, y, 1, 1), c);
        return c[0];
    }

    /**
     * Creates an image containing pixels read from the screen.
     * @param screenRect the rect to capture in screen coordinates
     * @return the array of pixels
     */
    @Override
    public int [] getRGBPixels(Rectangle bounds) {
        Rectangle screenBounds = fDevice.getDefaultConfiguration().getBounds();
        // screenBounds is in the coordinates of the primary device
        // but bounds is in the coordinates of fDevice
        // so we fix screenbounds at 0,0 origin
        screenBounds.x = screenBounds.y = 0;
        Rectangle intersection = screenBounds.intersection(bounds);

        int c[] = new int[intersection.width * intersection.height];
        getScreenPixels(intersection, c);

        if (!intersection.equals(bounds)) {
            // Since we are returning a smaller array than the code expects,
            // we have to copy our existing array into an array of the
            // "correct" size
            int c2[] = new int[bounds.width * bounds.height];
            for (int h=0; h<bounds.height; h++) {
                int boundsRow = h+bounds.y;
                if (boundsRow>=intersection.y && boundsRow<intersection.height) {
                    int srcPos = (boundsRow-intersection.y)*intersection.width;
                    int destPos = (h*bounds.width) + (intersection.x-bounds.x);
                    System.arraycopy(c, srcPos, c2, destPos, intersection.width);
                }
            }
            c = c2;
        }

        return c;
    }

    private native void initRobot();
    private native void mouseEvent(int screen, int lastX, int lastY,
                                   int button1DesiredState,
                                   int button2DesiredState,
                                   int button3DesiredState,
                                   boolean moveAction);
    private native void keyEvent(int javaKeyCode, boolean keydown);
    private void getScreenPixels(Rectangle r, int[] pixels){
        nativeGetScreenPixels(r.x, r.y, r.width, r.height, pixels);
    }
    private native void nativeGetScreenPixels(int x, int y, int width, int height, int[] pixels);
}
