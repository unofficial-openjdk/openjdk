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

import java.awt.AWTEvent;
import java.awt.Scrollbar;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.peer.ScrollbarPeer;

import javax.swing.JScrollBar;

public class LWScrollBarPeer
    extends LWComponentPeer<Scrollbar, JScrollBar>
    implements ScrollbarPeer, AdjustmentListener
{
    //JScrollBar fires two changes with firePropertyChange (one for old value
    // and one for new one.
    // We save the last value and don't fire event if not changed.
    private int currentValue;

    public LWScrollBarPeer(Scrollbar target, PlatformComponent platformComponent) {
        super(target, platformComponent);
        this.currentValue = target.getValue();

        synchronized (getDelegateLock()) {
            getDelegate().setOrientation(
                            target.getOrientation() == Scrollbar.HORIZONTAL ? JScrollBar.HORIZONTAL
                                    : JScrollBar.VERTICAL);
            getDelegate().setValue(target.getValue());
            getDelegate().setMinimum(target.getMinimum());
            getDelegate().setMaximum(target.getMaximum());
            getDelegate().setVisibleAmount(target.getVisibleAmount());
            getDelegate().addAdjustmentListener(this);
        }
    }

    @Override
    protected JScrollBar createDelegate() {
        JScrollBar delegate = new JScrollBar();
        delegate.setBackground(getTarget().getBackground());
        delegate.setForeground(getTarget().getForeground());
        return delegate;
    }

    public void setValues(int value, int visible, int minimum, int maximum) {
        synchronized (getDelegateLock()) {
            this.currentValue = value;
            getDelegate().setValue(value);
            getDelegate().setVisibleAmount(visible);
            getDelegate().setMinimum(minimum);
            getDelegate().setMaximum(maximum);
        }
    }

    public void setLineIncrement(int l) {
        synchronized (getDelegateLock()) {
            getDelegate().setUnitIncrement(l);
        }
    }

    public void setPageIncrement(int l) {
        synchronized (getDelegateLock()) {
            getDelegate().setBlockIncrement(l);
        }
    }

    // Peer also registered as a listener for ComponentDelegate component
    @Override
    public void adjustmentValueChanged(AdjustmentEvent e) {
        if (this.currentValue == e.getValue()) {
            return;
        }
        this.currentValue = e.getValue();

        // TODO: we always get event with the TRACK adj. type.
        // Could we check if we are over the ArrowButton and send event there?
        postEvent(new AdjustmentEvent(getTarget(), e.getID(),
                                      e.getAdjustmentType(), e.getValue(),
                                      e.getValueIsAdjusting()));
    }

}
