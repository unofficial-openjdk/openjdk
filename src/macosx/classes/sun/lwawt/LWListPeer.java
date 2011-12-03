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

import java.awt.*;
import java.awt.peer.ListPeer;

import javax.swing.*;

final class LWListPeer
        extends LWComponentPeer<List, LWListPeer.ScrollableJList>
        implements ListPeer {

    LWListPeer(List target, PlatformComponent platformComponent) {
        super(target, platformComponent);
    }

    protected ScrollableJList createDelegate() {
        return new ScrollableJList();
    }

    public boolean isFocusable() {
        return true;
    }

    protected Component getDelegateFocusOwner() {
        return getDelegate().getView();
    }

    public int[] getSelectedIndexes() {
        synchronized (getDelegateLock()) {
            return getDelegate().getView().getSelectedIndices();
        }
    }

    public void add(String item, int index) {
        synchronized (getDelegateLock()) {
            getDelegate().getModel().addElement(item);
        }
    }

    public void delItems(int start, int end) {
        synchronized (getDelegateLock()) {
            getDelegate().getModel().removeRange(start, end);
        }
    }

    public void removeAll() {
        synchronized (getDelegateLock()) {
            getDelegate().getModel().removeAllElements();
        }
    }

    public void select(int index) {
        synchronized (getDelegateLock()) {
            getDelegate().getView().setSelectedIndex(index);
        }
    }

    public void deselect(int index) {
        synchronized (getDelegateLock()) {
            getDelegate().getView().getSelectionModel().
                    removeSelectionInterval(index, index);
        }
    }

    public void makeVisible(int index) {
        synchronized (getDelegateLock()) {
            getDelegate().getView().ensureIndexIsVisible(index);
        }
    }

    public void setMultipleMode(boolean m) {
        synchronized (getDelegateLock()) {
            getDelegate().getView().setSelectionMode(m ?
                    ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
                    : ListSelectionModel.SINGLE_SELECTION);
        }
    }

    public Dimension getPreferredSize(int rows) {
        return getMinimumSize(rows);
    }

    public Dimension getMinimumSize(int rows) {
        synchronized (getDelegateLock()) {
            final int margin = 2;
            final int space = 1;

            // TODO: count ScrollPane's scrolling elements if any.
            FontMetrics fm = getFontMetrics(getFont());
            int itemHeight = (fm.getHeight() - fm.getLeading()) + (2 * space);

            return new Dimension(20 + (fm == null ? 10*15 : fm.stringWidth("0123456789abcde")),
                    (fm == null ? 10 : itemHeight) * rows + (2 * margin));
        }
    }

    final class ScrollableJList extends JScrollPane {

        private DefaultListModel<Object> model =
                new DefaultListModel<Object>() {
                    public void add(int index, Object element) {
                        if (index == -1) {
                            addElement(element);
                        } else {
                            super.add(index, element);
                        }
                    }
                };

        ScrollableJList() {
            getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
            JList<Object> list = new JList<Object>(model) {
                public boolean hasFocus() {
                    return getTarget().hasFocus();
                }
            };
            getViewport().setView(list);

            // Pull the items from the target.
            String[] items = getTarget().getItems();
            for (int i = 0; i < items.length; i++) {
                model.add(i, items[i]);
            }
        }

        public JList getView() {
            return (JList) getViewport().getView();
        }

        public DefaultListModel<Object> getModel() {
            return model;
        }

        @Override
        public void setEnabled(final boolean enabled) {
            getViewport().getView().setEnabled(enabled);
            super.setEnabled(enabled);
        }

        public void setBackground(Color bg) {
            super.setBackground(bg);
            if (getView() != null) {
                getView().setBackground(bg);
            }
        }

        public void setForeground(Color fg) {
            super.setForeground(fg);
            if (getView() != null) {
                getView().setForeground(fg);
            }
        }

        public void setFont(Font font) {
            super.setFont(font);
            if (getView() != null) {
                getView().setFont(font);
            }
        }
    }
}
