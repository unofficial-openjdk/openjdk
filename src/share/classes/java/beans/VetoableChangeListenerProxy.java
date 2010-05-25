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

import java.util.EventListenerProxy;

/**
 * A class which extends the <code>EventListenerProxy</code> specifically
 * for associating a <code>VetoableChangeListener</code> with a "constrained"
 * property. Instances of this class can be added as a
 * <code>VetoableChangeListener</code> to a bean which supports firing
 * VetoableChange events.
 * <p>
 * If the object has a <code>getVetoableChangeListeners()</code>
 * method then the array returned could be a mixture of
 * <code>VetoableChangeListener</code> and
 * <code>VetoableChangeListenerProxy</code> objects.
 * <p>
 * @see java.util.EventListenerProxy
 * @see VetoableChangeListener
 * @see VetoableChangeSupport#getVetoableChangeListeners
 * @since 1.4
 */
public class VetoableChangeListenerProxy extends EventListenerProxy
        implements VetoableChangeListener {

    private String propertyName;

    /**
    * @param propertyName The name of the property to listen on.
    * @param listener The listener object
    */
    public VetoableChangeListenerProxy(String propertyName,
            VetoableChangeListener listener) {
        super(listener);
        this.propertyName = propertyName;
    }

    /**
    * Forwards the property change event to the listener delegate.
    *
    * @param evt the property change event
    *
    * @exception PropertyVetoException if the recipient wishes the property
    *              change to be rolled back.
    */
    public void vetoableChange(PropertyChangeEvent evt) throws
            PropertyVetoException{
        ((VetoableChangeListener)getListener()).vetoableChange(evt);
    }

    /**
    * Returns the name of the named property associated with the
    * listener.
    */
    public String getPropertyName() {
        return propertyName;
    }
}
