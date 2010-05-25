/*
 * Copyright (c) 2003, 2007, Oracle and/or its affiliates. All rights reserved.
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
package sun.awt;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Window;

import java.awt.peer.KeyboardFocusManagerPeer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;


public class KeyboardFocusManagerPeerImpl implements KeyboardFocusManagerPeer {
    static native Window getNativeFocusedWindow();
    static native Component getNativeFocusOwner();
    static native void clearNativeGlobalFocusOwner(Window activeWindow);

    KeyboardFocusManagerPeerImpl(KeyboardFocusManager manager) {
    }

    public Window getCurrentFocusedWindow() {
        return getNativeFocusedWindow();
    }

    public void setCurrentFocusOwner(Component comp) {
    }

    public Component getCurrentFocusOwner() {
        return getNativeFocusOwner();
    }
    public void clearGlobalFocusOwner(Window activeWindow) {
        clearNativeGlobalFocusOwner(activeWindow);
    }

    static Method m_removeLastFocusRequest = null;
    public static void removeLastFocusRequest(Component heavyweight) {
        try {
            if (m_removeLastFocusRequest == null) {
                m_removeLastFocusRequest = SunToolkit.getMethod(KeyboardFocusManager.class, "removeLastFocusRequest",
                                                              new Class[] {Component.class});
            }
            m_removeLastFocusRequest.invoke(null, new Object[]{heavyweight});
        } catch (InvocationTargetException ite) {
            ite.printStackTrace();
        } catch (IllegalAccessException ex) {
            ex.printStackTrace();
        }
    }
}
