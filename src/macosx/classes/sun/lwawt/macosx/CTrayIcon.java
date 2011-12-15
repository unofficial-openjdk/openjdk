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
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.peer.TrayIconPeer;

public class CTrayIcon extends CFRetainedResource implements TrayIconPeer {
    private TrayIcon target;
    private PopupMenu popup;

    CTrayIcon(TrayIcon target) {
        super(0, true);

        this.target = target;
        this.popup = target.getPopupMenu();
        setPtr(createModel());

        //if no one else is creating the peer.
        checkAndCreatePopupPeer();
        updateImage();
    }

    private CPopupMenu checkAndCreatePopupPeer() {
        CPopupMenu menuPeer = null;
        if (popup != null) {
            try {
                menuPeer = (CPopupMenu)popup.getPeer();
                if (menuPeer == null) {
                    popup.addNotify();
                }
            }catch (Exception e){
                e.printStackTrace();
            }
        }
        return menuPeer;
    }

    private long createModel() {
//        final NSStatusBarClass statusBarClass = JObjC.getInstance().AppKit().NSStatusBar();
        return nativeCreate();
    }

    private long getModel() {
        return ptr;
    }

    private native long nativeCreate();

    //invocation from the AWTTrayIcon.m
    public long getPopupMenuModel(){
        if(popup == null) {
            return 0L;
        }
        return checkAndCreatePopupPeer().getModel();
    }

    @Override
    public void displayMessage(String caption, String text, String messageType) {
        throw new UnsupportedOperationException("MacOS TODO. TrayIcon.displayMessage()");
    }

    @Override
    public void dispose() {
        LWCToolkit.targetDisposedPeer(target, this);
        target = null;

        super.dispose();
    }

    @Override
    public void setToolTip(String tooltip) {
        nativeSetToolTip(getModel(), tooltip);
    }

    //adds tooltip to the NSStatusBar's NSButton.
    private native void nativeSetToolTip(long trayIconModel, String tooltip);

    @Override
    public void showPopupMenu(int x, int y) {
        //Not used. The popupmenu is shown from the native code.
    }

    @Override
    public void updateImage() {
        Image image = target.getImage();
        if (image == null) return;

        MediaTracker tracker = new MediaTracker(new Button(""));
        tracker.addImage(image, 0);
        try {
            tracker.waitForAll();
        } catch (InterruptedException ignore) { }

        if (image.getWidth(null) <= 0 ||
            image.getHeight(null) <= 0)
        {
            return;
        }

        CImage cimage = CImage.getCreator().createFromImage(image);
        setNativeImage(getModel(), cimage.ptr, target.isImageAutoSize());
    }

    private native void setNativeImage(final long model, final long nsimage, final boolean autosize);

    //invocation from the AWTTrayIcon.m
    public void performAction() {
        ActionEvent evt = new ActionEvent(target, 0, "ACTION_TRIGGERED_BY_PEER");
        for(ActionListener listener:target.getActionListeners()) {
            listener.actionPerformed(evt);
        }
    }
}

