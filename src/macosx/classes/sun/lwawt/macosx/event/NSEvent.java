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

package sun.lwawt.macosx.event;

import sun.lwawt.macosx.CocoaConstants;

import java.awt.event.*;

/*
 * A class representing Cocoa NSEvent class with
 * the fields only necessary for JDK functionality.
 */

public final class NSEvent {
    private int type;

    /*
     * Java-level modifiers
     */
    private int modifiers;
    private int clickCount;
    private int button;
    private int x;
    private int y;
    private double scrollDeltaY;
    private double scrollDeltaX;
    private int absX;
    private int absY;

    public NSEvent(int type, int modifiers, int clickCount, int button,
            int x, int y,
            int absX, int absY,
            double scrollDeltaY, double scrollDeltaX) {
        this.type = type;
        this.modifiers = modifiers;
        this.clickCount = clickCount;
        this.button = button;
        this.x = x;
        this.y = y;
        this.absX = absX;
        this.absY = absY;
        this.scrollDeltaY = scrollDeltaY;
        this.scrollDeltaX = scrollDeltaX;
    }

    public int getType() {
        return type;
    }

    public int getModifiers() {
        return modifiers;
    }

    public int getClickCount() {
        return clickCount;
    }

    public int getButton() {
        return button;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public double getScrollDeltaY() {
        return scrollDeltaY;
    }

    public double getScrollDeltaX() {
        return scrollDeltaX;
    }

    public int getAbsX() {
        return absX;
    }

    public int getAbsY() {
        return absY;
    }

    @Override
    public String toString() {
        return "NSEvent[" + getType() + " ," + getModifiers() + " ,"
                + getClickCount() + " ," + getButton() + " ," + getX() + " ,"
                + getY() + " ," + absX + " ," + absY+ "]";
    }

    /*
     * Converts an NSEvent button number to a MouseEvent constant.
     */
    public static int nsButtonToJavaButton(NSEvent event) {
        int nsButtonNumber = event.getButton();
        int jbutton = MouseEvent.NOBUTTON;

        if (event.getType() != CocoaConstants.NSMouseMoved) {
            if (nsButtonNumber == 0) { // left
                jbutton = MouseEvent.BUTTON1;
            } else if (nsButtonNumber == 1) { // right
                jbutton = MouseEvent.BUTTON3;
            } else if (nsButtonNumber == 2) { // middle
                jbutton = MouseEvent.BUTTON2;
            }
        }

        return jbutton;
    }

    // utility methods

    public boolean isPopupTrigger() {
        final int mods = getModifiers();
        final boolean isRightButtonDown = ((mods & InputEvent.BUTTON3_DOWN_MASK) != 0);
        final boolean isLeftButtonDown = ((mods & InputEvent.BUTTON1_DOWN_MASK) != 0);
        final boolean isControlDown = ((mods & InputEvent.CTRL_DOWN_MASK) != 0);
        return isRightButtonDown || (isControlDown && isLeftButtonDown);
    }
}
