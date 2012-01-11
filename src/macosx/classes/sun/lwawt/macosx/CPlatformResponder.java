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

import sun.lwawt.LWWindowPeer;
import sun.lwawt.macosx.event.NSEvent;

import java.awt.event.MouseEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.KeyEvent;

/*
 * Translates NSEvents/NPCocoaEvents into AWT events.
 */
public class CPlatformResponder {

    private LWWindowPeer peer;
    private boolean isNpapiCallback;

    public CPlatformResponder(LWWindowPeer peer, boolean isNpapiCallback) {
        this.peer = peer;
        this.isNpapiCallback = isNpapiCallback;
    }

    /*
     * Handles mouse events.
     */
    public void handleMouseEvent(int eventType, int modifierFlags, int buttonNumber,
                                 int clickCount, int x, int y, int absoluteX, int absoluteY) {
        int jeventType = isNpapiCallback ? NSEvent.npEventTypeToJavaEventType(eventType) :
                                           NSEvent.nsEventTypeToJavaEventType(eventType);

        int jbuttonNumber = MouseEvent.NOBUTTON;
        int jclickCount = 0;

        if (jeventType != MouseEvent.MOUSE_MOVED &&
            jeventType != MouseEvent.MOUSE_ENTERED &&
            jeventType != MouseEvent.MOUSE_EXITED)
        {
            jbuttonNumber = NSEvent.nsButtonToJavaButton(buttonNumber);
            jclickCount = clickCount;
        }

        int jmodifiers = NSEvent.nsMouseModifiersToJavaMouseModifiers(buttonNumber, modifierFlags);
        boolean jpopupTrigger = NSEvent.isPopupTrigger(jmodifiers);

        peer.dispatchMouseEvent(jeventType, System.currentTimeMillis(), jbuttonNumber,
                                x, y, absoluteX, absoluteY, jmodifiers, jclickCount,
                                jpopupTrigger, null);
    }

    /*
     * Handles scroll events.
     */
    public void handleScrollEvent(int x, int y, int modifierFlags,
                                  double deltaX, double deltaY) {
        int buttonNumber = CocoaConstants.kCGMouseButtonCenter;
        int jmodifiers = NSEvent.nsMouseModifiersToJavaMouseModifiers(buttonNumber, modifierFlags);

        double wheelDelta = deltaY;

        // Shirt+vertical wheel scroll produces horizontal scroll
        if ((jmodifiers & InputEvent.SHIFT_DOWN_MASK) != 0) {
            wheelDelta = deltaX;
        }
        // Wheel amount "oriented" inside out
        wheelDelta = -wheelDelta;

        peer.dispatchMouseWheelEvent(System.currentTimeMillis(), x, y, jmodifiers,
                                     MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, // WHEEL_SCROLL_AMOUNT
                                     (int)wheelDelta, wheelDelta, null);
    }

    /*
     * Handles key events.
     */
    public void handleKeyEvent(int eventType, int modifierFlags, String charsIgnoringMods,
                               short keyCode) {
        boolean isFlagsChangedEvent =
            isNpapiCallback ? (eventType == CocoaConstants.NPCocoaEventFlagsChanged) :
                              (eventType == CocoaConstants.NSFlagsChanged);

        int jeventType = KeyEvent.KEY_PRESSED;
        int jkeyCode = KeyEvent.VK_UNDEFINED;
        int jkeyLocation = KeyEvent.KEY_LOCATION_UNKNOWN;
        boolean postsTyped = false;

        char testChar = KeyEvent.CHAR_UNDEFINED;
        char testDeadChar = 0;

        if (isFlagsChangedEvent) {
            int[] in = new int[] {modifierFlags, keyCode};
            int[] out = new int[3]; // [jkeyCode, jkeyLocation, jkeyType]

            NSEvent.nsKeyModifiersToJavaKeyInfo(in, out);

            jkeyCode = out[0];
            jkeyLocation = out[1];
            jeventType = out[2];
        } else {
            if (charsIgnoringMods != null && charsIgnoringMods.length() > 0) {
                testChar = charsIgnoringMods.charAt(0);
            }

            int[] in = new int[] {testChar, testDeadChar, modifierFlags, keyCode};
            int[] out = new int[2]; // [jkeyCode, jkeyLocation]

            postsTyped = NSEvent.nsKeyInfoToJavaKeyInfo(in, out);

            jkeyCode = out[0];
            jkeyLocation = out[1];
            jeventType = isNpapiCallback ? NSEvent.npEventTypeToJavaEventType(eventType) :
                                           NSEvent.nsEventTypeToJavaEventType(eventType);
        }

        int jmodifiers = NSEvent.nsKeyModifiersToJavaKeyModifiers(modifierFlags);

        peer.dispatchKeyEvent(jeventType, System.currentTimeMillis(), jmodifiers,
                              jkeyCode, testChar, jkeyLocation);

        // That's the reaction on the PRESSED (not RELEASED) event as it comes to
        // appear in MacOSX.
        // Modifier keys (shift, etc) don't want to send TYPED events.
        // On the other hand we don't want to generate keyTyped events
        // for clipboard related shortcuts like Meta + [CVX]
        boolean isMetaDown = (jmodifiers & KeyEvent.META_DOWN_MASK) != 0;
        if (jeventType == KeyEvent.KEY_PRESSED && postsTyped && !isMetaDown) {
            boolean isCtrlDown = (jmodifiers & KeyEvent.CTRL_DOWN_MASK) != 0;
            boolean isShiftDown = (jmodifiers & KeyEvent.SHIFT_DOWN_MASK) != 0;

            // emulating the codes from the ASCII table
            int shift = isCtrlDown ? isShiftDown ? 64 : 96 : 0;
            peer.dispatchKeyEvent(KeyEvent.KEY_TYPED, System.currentTimeMillis(), jmodifiers,
                                  KeyEvent.VK_UNDEFINED, (char) (testChar - shift),
                                  KeyEvent.KEY_LOCATION_UNKNOWN);
        }
    }
}
