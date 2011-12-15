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

import java.awt.Choice;
import java.awt.Point;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.peer.ChoicePeer;

import javax.swing.JComboBox;

final class LWChoicePeer extends LWComponentPeer<Choice, JComboBox<String>>
        implements ChoicePeer, ItemListener {

    LWChoicePeer(final Choice target, PlatformComponent platformComponent) {
        super(target, platformComponent);
    }

    @Override
    protected JComboBox<String> createDelegate() {
        return new JComboBoxDelegate();
    }

    @Override
    public void initialize() {
        super.initialize();
        final Choice choice = getTarget();
        final JComboBox<String> combo = getDelegate();
        synchronized (getDelegateLock()) {
            final int count = choice.getItemCount();
            for (int i = 0; i < count; ++i) {
                combo.addItem(choice.getItem(i));
            }
            select(choice.getSelectedIndex());

            // NOTE: the listener must be added at the very end, otherwise it
            // fires events upon initialization of the combo box.
            combo.addItemListener(this);
        }
    }

    @Override
    public void itemStateChanged(final ItemEvent event) {
        // AWT Choice sends SELECTED event only whereas JComboBox
        // sends both SELECTED and DESELECTED.
        if (event.getStateChange() == ItemEvent.SELECTED) {
            synchronized (getDelegateLock()) {
                getTarget().select(getDelegate().getSelectedIndex());
            }
            postEvent(new ItemEvent(getTarget(), ItemEvent.ITEM_STATE_CHANGED,
                                    event.getItem(), ItemEvent.SELECTED));
        }
    }

    @Override
    public void add(final String item, final int index) {
        synchronized (getDelegateLock()) {
            getDelegate().insertItemAt(item, index);
        }
    }

    @Override
    public void remove(final int index) {
        synchronized (getDelegateLock()) {
            getDelegate().removeItemAt(index);
        }
    }

    @Override
    public void removeAll() {
        synchronized (getDelegateLock()) {
            getDelegate().removeAllItems();
        }
    }

    @Override
    public void select(final int index) {
        synchronized (getDelegateLock()) {
            getDelegate().setSelectedIndex(index);
        }
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    private final class JComboBoxDelegate extends JComboBox<String> {

        // Empty non private constructor was added because access to this
        // class shouldn't be emulated by a synthetic accessor method.
        JComboBoxDelegate() {
            super();
        }

        @Override
        public boolean hasFocus() {
            return getTarget().hasFocus();
        }

        //Needed for proper popup menu location
        @Override
        public Point getLocationOnScreen() {
            return LWChoicePeer.this.getLocationOnScreen();
        }
    }
}
