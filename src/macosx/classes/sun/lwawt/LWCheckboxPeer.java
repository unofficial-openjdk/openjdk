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
import java.awt.event.ItemListener;
import java.awt.event.ItemEvent;
import java.awt.event.AWTEventListener;
import java.awt.peer.CheckboxPeer;
import java.beans.Transient;

import javax.swing.*;

class LWCheckboxPeer
        extends LWComponentPeer<Checkbox, LWCheckboxPeer.CheckboxDelegate>
        implements CheckboxPeer, ItemListener {
    LWCheckboxPeer(Checkbox target, PlatformComponent platformComponent) {
        super(target, platformComponent);
    }

    @Override
    protected CheckboxDelegate createDelegate() {
        return new CheckboxDelegate();
    }

    @Override
    protected Component getDelegateFocusOwner() {
        return getDelegate().getCurrentButton();
    }

    public void initialize() {
        super.initialize();
        getDelegate().setRadioButton(getTarget().getCheckboxGroup() != null);
        getDelegate().getCurrentButton().setText(getTarget().getLabel());
        getDelegate().getCurrentButton().setSelected(getTarget().getState());
        getDelegate().getCurrentButton().addItemListener(this);
    }


    public void itemStateChanged(final ItemEvent e) {
        // group.setSelectedCheckbox() will repaint the component
        // to let LWCheckboxPeer correctly handle it we should call it
        // after the current event is processed
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                boolean postEvent = true;
                CheckboxGroup group = getTarget().getCheckboxGroup();
                if (group != null) {
                    if (e.getStateChange() == ItemEvent.SELECTED) {
                        if (group.getSelectedCheckbox() != getTarget()) {
                            group.setSelectedCheckbox(getTarget());
                        } else {
                            postEvent = false;
                        }
                    } else {
                        postEvent = false;
                        if (group.getSelectedCheckbox() == getTarget()) {
                            // don't want to leave the group with no selected checkbox
                            getTarget().setState(true);
                        }
                    }
                } else {
                    getTarget().setState(e.getStateChange() == ItemEvent.SELECTED);
                }
                if(postEvent) {
                    postEvent(new ItemEvent(getTarget(), ItemEvent.ITEM_STATE_CHANGED,
                            getTarget().getLabel(), e.getStateChange()));
                }
            }
        });
    }

    public void setCheckboxGroup(CheckboxGroup g) {
        synchronized (getDelegateLock()) {
            initialize();
        }
        repaintPeer();
    }

    public void setLabel(String label) {
        synchronized (getDelegateLock()) {
            getDelegate().getCurrentButton().setText(label);
        }
        repaintPeer();
    }

    public void setState(boolean state) {
        synchronized (getDelegateLock()) {
            getDelegate().getCurrentButton().setSelected(state);
        }
        repaintPeer();
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    final class CheckboxDelegate extends JComponent {
        private JCheckBox cb;
        private JRadioButton rb;

        CheckboxDelegate() {
            cb = new JCheckBox() {
                public boolean hasFocus() {
                    return getTarget().hasFocus();
                }
            };
            cb.setOpaque(false);
            rb = new JRadioButton() {
                public boolean hasFocus() {
                    return getTarget().hasFocus();
                }
            };
            rb.setOpaque(false);
            add(cb);
        }

        public boolean isRadioButton() {
            return getCurrentButton() == rb;
        }

        public void setRadioButton(boolean b) {
            AWTEventListener toolkitListener = getToolkitAWTEventListener();
            synchronized (Toolkit.getDefaultToolkit()) {
                try {
                    remove(getCurrentButton());
                    add(b ? rb : cb);
                } finally {
                    setToolkitAWTEventListener(toolkitListener);
                }
            }
        }

        private JToggleButton getCurrentButton() {
            return (JToggleButton) getComponent(0);
        }

        @Override
        public void setEnabled(final boolean enabled) {
            rb.setEnabled(enabled);
            cb.setEnabled(enabled);
        }

        @Deprecated
        public void reshape(int x, int y, int w, int h) {
            super.reshape(x, y, w, h);
            getCurrentButton().setBounds(0, 0, w, h);
        }

        @Transient
        public Dimension getMinimumSize() {
            return getCurrentButton().getMinimumSize();
        }
    }
}

