/*
 * Copyright (c) 1997, 2003, Oracle and/or its affiliates. All rights reserved.
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

package sun.awt.motif;

import sun.awt.motif.MComponentPeer;
import sun.awt.motif.MInputMethod;

/**
 * An interface for controlling containment hierarchy configuration to
 * keep track of existence of any TextArea or TextField and to manage
 * input method status area.
 *
 * @auther      JavaSoft International
 */
interface MInputMethodControl {

    /**
     * Informs Frame or Dialog that a text component has been added to
     * the hierarchy.
     * @param   textComponentPeer       peer of the text component
     */
    void addTextComponent(MComponentPeer textComponentPeer);

    /**
     * Informs Frame or Dialog that a text component has been removed
     * from the hierarchy.
     * @param textComponentPeer peer of the text component
     */
    void removeTextComponent(MComponentPeer textComponentPeer);

    /**
     * Returns a text component peer in the containment hierarchy
     * to obtain the Motif status area information
     */
    MComponentPeer getTextComponent();

    /**
     * Inform Frame or Dialog that an MInputMethod has been
     * constructed so that Frame and Dialog can invoke the method in
     * MInputMethod to reconfigure XICs.
     * @param   inputMethod     an MInputMethod instance
     */
    void addInputMethod(MInputMethod inputMethod);

    /**
     * Inform Frame or Dialog that an X11InputMethod is being destroyed.
     * @param   inputMethod     an X11InputMethod instance
     */
    void removeInputMethod(MInputMethod inputMethod);
}
