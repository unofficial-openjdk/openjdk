/*
 * Copyright (c) 2000, Oracle and/or its affiliates. All rights reserved.
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

package java.beans;

/**
 * A class which extends the <code>EventListenerProxy</code> specifically
 * for adding a named <code>PropertyChangeListener</code>. Instances of
 * this class can be added as <code>PropertyChangeListener</code> to
 * an object.
 * <p>
 * If the object has a <code>getPropertyChangeListeners()</code>
 * method then the array returned could be a mixture of
 * <code>PropertyChangeListener</code> and
 * <code>PropertyChangeListenerProxy</code> objects.
 *
 * @see java.util.EventListenerProxy
 * @since 1.4
 */
public class PropertyChangeListenerProxy extends java.util.EventListenerProxy
        implements PropertyChangeListener {

    private String propertyName;

    /**
     * Constructor which binds the PropertyChangeListener to a specific
     * property.
     *
     * @param listener The listener object
     * @param propertyName The name of the property to listen on.
     */
    public PropertyChangeListenerProxy(String propertyName,
            PropertyChangeListener listener) {
        // XXX - msd NOTE: I changed the order of the arguments so that it's
        // similar to PropertyChangeSupport.addPropertyChangeListener(String,
        // PropertyChangeListener);
        super(listener);
        this.propertyName = propertyName;
    }

    /**
     * Forwards the property change event to the listener delegate.
     *
     * @param evt the property change event
     */
    public void propertyChange(PropertyChangeEvent evt) {
        ((PropertyChangeListener)getListener()).propertyChange(evt);
    }

    /**
     * Returns the name of the named property associated with the
     * listener.
     */
    public String getPropertyName() {
        return propertyName;
    }
}
