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

import sun.awt.SunGraphicsCallback;

import java.awt.Container;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.PaintEvent;
import java.awt.peer.ContainerPeer;
import java.util.LinkedList;
import java.util.List;

import javax.swing.JComponent;

abstract class LWContainerPeer<T extends Container, D extends JComponent>
    extends LWComponentPeer<T, D>
    implements ContainerPeer
{
    // List of child peers sorted by z-order from bottom-most
    // to top-most
    private List<LWComponentPeer> childPeers =
        new LinkedList<LWComponentPeer>();

    LWContainerPeer(T target, PlatformComponent platformComponent) {
        super(target, platformComponent);
    }

    void addChildPeer(LWComponentPeer child) {
        synchronized (getPeerTreeLock()) {
            addChildPeer(child, childPeers.size());
        }
    }

    void addChildPeer(LWComponentPeer child, int index) {
        synchronized (getPeerTreeLock()) {
            childPeers.add(index, child);
        }
        // TODO: repaint
    }

    void removeChildPeer(LWComponentPeer child) {
        synchronized (getPeerTreeLock()) {
            childPeers.remove(child);
        }
        // TODO: repaint
    }

    // Used by LWComponentPeer.setZOrder()
    void setChildPeerZOrder(LWComponentPeer peer, LWComponentPeer above) {
        synchronized (getPeerTreeLock()) {
            childPeers.remove(peer);
            int index = (above != null) ? childPeers.indexOf(above) : childPeers.size();
            if (index >= 0) {
                childPeers.add(index, peer);
            } else {
                // TODO: log
            }
        }
        // TODO: repaint
    }

    // ---- PEER METHODS ---- //

    /*
     * Overridden in LWWindowPeer.
     */
    @Override
    public Insets getInsets() {
        return new Insets(0, 0, 0, 0);
    }

    @Override
    public void beginValidate() {
        // TODO: it seems that begin/endValidate() is only useful
        // for heavyweight windows, when a batch movement for
        // child windows  occurs. That's why no-op
    }
    @Override
    public void endValidate() {
        // TODO: it seems that begin/endValidate() is only useful
        // for heavyweight windows, when a batch movement for
        // child windows  occurs. That's why no-op
    }

    @Override
    public void beginLayout() {
        // Skip all painting till endLayout()
        setLayouting(true);
    }
    @Override
    public void endLayout() {
        setLayouting(false);

        // Post an empty event to flush all the pending target paints
        postPaintEvent(0, 0, 0, 0);
    }

    // ---- PEER NOTIFICATIONS ---- //

    /*
     * Returns a copy of the childPeer collection.
     */
    protected List<LWComponentPeer> getChildren() {
        synchronized (getPeerTreeLock()) {
            Object copy = ((LinkedList)childPeers).clone();
            return (List<LWComponentPeer>)copy;
        }
    }

    /*
     * Called by the container when any part of this peer or child
     * peers should be repainted
     */
    @Override
    public void handleExpose(int x, int y, int w, int h) {
        // First, post the PaintEvent for this peer
        super.handleExpose(x, y, w, h);

        // Second, handle all the children
        // Use the straight order of children, so the bottom
        // ones are painted first
        for (LWComponentPeer child : getChildren()) {
            Rectangle r = child.getBounds();
            int paintX = Math.max(0, x - r.x);
            int paintY = Math.max(0, y - r.y);
            int paintW = Math.min(w, r.width - paintX);
            int paintH = Math.min(h, r.height - paintY);
            child.handleExpose(paintX, paintY, paintW, paintH);
        }
    }

    /*
     * Handler for PAINT and UPDATE PaintEvents.
     */
    protected void handleJavaPaintEvent(PaintEvent e) {
        super.handleJavaPaintEvent(e);
        // Now this peer and its target are painted. However, when
        // the target painting may have corrupted some children, so
        // we should restore them from the window back buffer.
        for (LWComponentPeer child : getChildren()) {
            child.restorePeer();
        }
    }

    // ---- UTILITY METHODS ---- //

    /**
     * Finds a top-most visible component for the given point. The location is
     * specified relative to the peer's parent.
     */
    @Override
    public final LWComponentPeer findPeerAt(int x, int y) {
        LWComponentPeer peer = super.findPeerAt(x, y);
        final Rectangle r = getBounds();
        // Translate to this container's coordinates to pass to children
        x -= r.x;
        y -= r.y;
        if (peer != null && getContentSize().contains(x, y)) {
            synchronized (getPeerTreeLock()) {
                for (int i = childPeers.size() - 1; i >= 0; --i) {
                    LWComponentPeer p = childPeers.get(i).findPeerAt(x, y);
                    if (p != null) {
                        peer = p;
                        break;
                    }
                }
            }
        }
        return peer;
    }

    /*
     * Overrides peerPaint() to paint all the children.
     */
    @Override
    protected void peerPaint(Graphics g, Rectangle r) {
        Rectangle b = getBounds();
        if (!isShowing() || r.isEmpty() ||
                !r.intersects(new Rectangle(0, 0, b.width, b.height))) {
            return;
        }
        // First, paint myself
        super.peerPaint(g, r);
        // Second, paint all the children
        peerPaintChildren(r);
    }

    /*
     * Paints all the child peers in the straight z-order, so the
     * bottom-most ones are painted first.
     */
    protected void peerPaintChildren(Rectangle r) {
        for (LWComponentPeer child : getChildren()) {
            Graphics cg = child.getOffscreenGraphics();
            try {
                Rectangle toPaint = new Rectangle(r);
                Rectangle childBounds = child.getBounds();
                toPaint = toPaint.intersection(childBounds);
                toPaint = toPaint.intersection(getContentSize());
                toPaint.translate(-childBounds.x, -childBounds.y);
                child.peerPaint(cg, toPaint);
            } finally {
                cg.dispose();
            }
        }
    }

    protected Rectangle getContentSize() {
        Rectangle r = getBounds();
        return new Rectangle(r.width, r.height);
    }

    @Override
    public void setEnabled(final boolean e) {
        super.setEnabled(e);
        for (final LWComponentPeer child : getChildren()) {
            child.setEnabled(e && child.getTarget().isEnabled());
        }
    }

    @Override
    public void paint(final Graphics g) {
        super.paint(g);
        SunGraphicsCallback.PaintHeavyweightComponentsCallback.getInstance().
            runComponents(getTarget().getComponents(), g,
                          SunGraphicsCallback.LIGHTWEIGHTS
                          | SunGraphicsCallback.HEAVYWEIGHTS);
    }

    @Override
    public void print(final Graphics g) {
        super.print(g);
        SunGraphicsCallback.PrintHeavyweightComponentsCallback.getInstance().
            runComponents(getTarget().getComponents(), g,
                          SunGraphicsCallback.LIGHTWEIGHTS
                          | SunGraphicsCallback.HEAVYWEIGHTS);
    }
}
