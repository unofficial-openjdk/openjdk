/*
 * Copyright (c) 2000, 2003, Oracle and/or its affiliates. All rights reserved.
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
package java.awt;

import java.util.logging.*;

/**
 * A FocusTraversalPolicy that determines traversal order based on the order
 * of child Components in a Container. From a particular focus cycle root, the
 * policy makes a pre-order traversal of the Component hierarchy, and traverses
 * a Container's children according to the ordering of the array returned by
 * <code>Container.getComponents()</code>. Portions of the hierarchy that are
 * not visible and displayable will not be searched.
 * <p>
 * By default, ContainerOrderFocusTraversalPolicy implicitly transfers focus
 * down-cycle. That is, during normal forward focus traversal, the Component
 * traversed after a focus cycle root will be the focus-cycle-root's default
 * Component to focus. This behavior can be disabled using the
 * <code>setImplicitDownCycleTraversal</code> method.
 * <p>
 * By default, methods of this class with return a Component only if it is
 * visible, displayable, enabled, and focusable. Subclasses can modify this
 * behavior by overriding the <code>accept</code> method.
 * <p>
 * This policy takes into account <a
 * href="doc-files/FocusSpec.html#FocusTraversalPolicyProviders">focus traversal
 * policy providers</a>.  When searching for first/last/next/previous Component,
 * if a focus traversal policy provider is encountered, its focus traversal
 * policy is used to perform the search operation.
 *
 * @author David Mendenhall
 *
 * @see Container#getComponents
 * @since 1.4
 */
public class ContainerOrderFocusTraversalPolicy extends FocusTraversalPolicy
    implements java.io.Serializable
{
    private static final MutableBoolean found = new MutableBoolean();

    private static final Logger log = Logger.getLogger("java.awt.ContainerOrderFocusTraversalPolicy");

    /*
     * JDK 1.4 serialVersionUID
     */
    private static final long serialVersionUID = 486933713763926351L;

    private boolean implicitDownCycleTraversal = true;

    /**
     * Returns the Component that should receive the focus after aComponent.
     * aContainer must be a focus cycle root of aComponent or a focus traversal policy provider.
     * <p>
     * By default, ContainerOrderFocusTraversalPolicy implicitly transfers
     * focus down-cycle. That is, during normal forward focus traversal, the
     * Component traversed after a focus cycle root will be the focus-cycle-
     * root's default Component to focus. This behavior can be disabled using
     * the <code>setImplicitDownCycleTraversal</code> method.
     * <p>
     * If aContainer is <a href="doc-files/FocusSpec.html#FocusTraversalPolicyProviders">focus
     * traversal policy provider</a>, the focus is always transferred down-cycle.
     *
     * @param aContainer a focus cycle root of aComponent or a focus traversal policy provider
     * @param aComponent a (possibly indirect) child of aContainer, or
     *        aContainer itself
     * @return the Component that should receive the focus after aComponent, or
     *         null if no suitable Component can be found
     * @throws IllegalArgumentException if aContainer is not a focus cycle
     *         root of aComponent or focus traversal policy provider, or if either aContainer or
     *         aComponent is null
     */
    public Component getComponentAfter(Container aContainer,
                                       Component aComponent) {
        if (log.isLoggable(Level.FINE)) log.fine("Looking for next component in " + aContainer  + " for " + aComponent);
        if (aContainer == null || aComponent == null) {
            throw new IllegalArgumentException("aContainer and aComponent cannot be null");
        }
        if (!aContainer.isFocusTraversalPolicyProvider() && !aContainer.isFocusCycleRoot()) {
            throw new IllegalArgumentException("aContainer should be focus cycle root or focus traversal policy provider");
        } else if (aContainer.isFocusCycleRoot() && !aComponent.isFocusCycleRoot(aContainer)) {
            throw new IllegalArgumentException("aContainer is not a focus cycle root of aComponent");
        }

        synchronized(aContainer.getTreeLock()) {
            found.value = false;
            Component retval = getComponentAfter(aContainer, aComponent,
                                                 found);
            if (retval != null) {
                if (log.isLoggable(Level.FINE)) log.fine("After component is " + retval);
                return retval;
            } else if (found.value) {
                if (log.isLoggable(Level.FINE)) log.fine("Didn't find next component in " + aContainer + " - falling back to the first ");
                return getFirstComponent(aContainer);
            } else {
                if (log.isLoggable(Level.FINE)) log.fine("After component is null");
                return null;
            }
        }
    }

    private Component getComponentAfter(Container aContainer,
                                        Component aComponent,
                                        MutableBoolean found) {
        if (!(aContainer.isVisible() && aContainer.isDisplayable())) {
            return null;
        }

        if (found.value) {
            if (accept(aContainer)) {
                return aContainer;
            }
        } else if (aContainer == aComponent) {
            found.value = true;
        }

        for (int i = 0; i < aContainer.ncomponents; i++) {
            Component comp = aContainer.component[i];
            if ((comp instanceof Container) &&
                !((Container)comp).isFocusCycleRoot()) {
                Component retval = null;
                if (((Container)comp).isFocusTraversalPolicyProvider()) {
                    if (log.isLoggable(Level.FINE)) log.fine("Entering FTP " + comp);
                    Container cont = (Container) comp;
                    FocusTraversalPolicy policy = cont.getFocusTraversalPolicy();
                    if (log.isLoggable(Level.FINE)) log.fine("FTP contains " + aComponent + ": " + cont.isAncestorOf(aComponent));
                    if (found.value) {
                        retval = policy.getDefaultComponent(cont);
                        if (log.isLoggable(Level.FINE)) log.fine("Used FTP for getting default component: " + retval);
                    } else {
                        found.value = cont.isAncestorOf(aComponent);
                        if (found.value)  {
                            if (aComponent == policy.getLastComponent(cont)) {
                            // Reached last component, going to wrap - should switch to next provider
                            retval = null;
                            } else {
                                retval = policy.getComponentAfter(cont, aComponent);
                                if (log.isLoggable(Level.FINE)) log.fine("FTP found next for the component : " + retval);
                            }
                        }
                    }
                } else {
                    retval = getComponentAfter((Container)comp,
                                                     aComponent,
                                                     found);
                }
                if (retval != null) {
                    return retval;
                }
            } else if (found.value) {
                if (accept(comp)) {
                    return comp;
                }
            } else if (comp == aComponent) {
                found.value = true;
            }

            if (found.value &&
                getImplicitDownCycleTraversal() &&
                (comp instanceof Container) &&
                ((Container)comp).isFocusCycleRoot())
            {
                Container cont = (Container)comp;
                Component retval = cont.getFocusTraversalPolicy().
                    getDefaultComponent(cont);
                if (retval != null) {
                    return retval;
                }
            }
        }

        return null;
    }

    /**
     * Returns the Component that should receive the focus before aComponent.
     * aContainer must be a focus cycle root of aComponent or a <a
     * href="doc-files/FocusSpec.html#FocusTraversalPolicyProviders">focus traversal policy
     * provider</a>.
     *
     * @param aContainer a focus cycle root of aComponent or focus traversal policy provider
     * @param aComponent a (possibly indirect) child of aContainer, or
     *        aContainer itself
     * @return the Component that should receive the focus before aComponent,
     *         or null if no suitable Component can be found
     * @throws IllegalArgumentException if aContainer is not a focus cycle
     *         root of aComponent or focus traversal policy provider, or if either aContainer or
     *         aComponent is null
     */
    public Component getComponentBefore(Container aContainer,
                                        Component aComponent) {
        if (aContainer == null || aComponent == null) {
            throw new IllegalArgumentException("aContainer and aComponent cannot be null");
        }
        if (!aContainer.isFocusTraversalPolicyProvider() && !aContainer.isFocusCycleRoot()) {
            throw new IllegalArgumentException("aContainer should be focus cycle root or focus traversal policy provider");
        } else if (aContainer.isFocusCycleRoot() && !aComponent.isFocusCycleRoot(aContainer)) {
            throw new IllegalArgumentException("aContainer is not a focus cycle root of aComponent");
        }
        synchronized(aContainer.getTreeLock()) {
            found.value = false;
            Component retval = getComponentBefore(aContainer, aComponent,
                                                  found);
            if (retval != null) {
                if (log.isLoggable(Level.FINE)) log.fine("Before component is " + retval);
                return retval;
            } else if (found.value) {
                if (log.isLoggable(Level.FINE)) log.fine("Didn't find before component in " + aContainer + " - falling back to the first ");
                return getLastComponent(aContainer);
            } else {
                if (log.isLoggable(Level.FINE)) log.fine("Before component is null");
                return null;
            }
        }
    }

    private Component getComponentBefore(Container aContainer,
                                         Component aComponent,
                                         MutableBoolean found) {
        if (!(aContainer.isVisible() && aContainer.isDisplayable())) {
            return null;
        }

        for (int i = aContainer.ncomponents - 1; i >= 0; i--) {
            Component comp = aContainer.component[i];
            if (comp == aComponent) {
                found.value = true;
            } else if ((comp instanceof Container) &&
                !((Container)comp).isFocusCycleRoot()) {
                Component retval = null;
                if (((Container)comp).isFocusTraversalPolicyProvider()) {
                    if (log.isLoggable(Level.FINE)) log.fine("Entering FTP " + comp);
                    Container cont = (Container) comp;
                    FocusTraversalPolicy policy = cont.getFocusTraversalPolicy();
                    if (log.isLoggable(Level.FINE)) log.fine("FTP contains " + aComponent + ": " + cont.isAncestorOf(aComponent));
                    if (found.value) {
                        retval = policy.getLastComponent(cont);
                        if (log.isLoggable(Level.FINE)) log.fine("Used FTP for getting last component: " + retval);
                    } else {
                        found.value = cont.isAncestorOf(aComponent);
                        if (found.value) {
                            if (aComponent == policy.getFirstComponent(cont)) {
                                retval = null;
                            } else {
                                retval = policy.getComponentBefore(cont, aComponent);
                                if (log.isLoggable(Level.FINE)) log.fine("FTP found previous for the component : " + retval);
                            }
                        }
                    }
                } else {
                    retval = getComponentBefore((Container)comp,
                                                      aComponent,
                                                      found);
                }
                if (retval != null) {
                    return retval;
                }
            } else if (found.value) {
                if (accept(comp)) {
                    return comp;
                }
            }
        }

        if (found.value) {
            if (accept(aContainer)) {
                return aContainer;
            }
        } else if (aContainer == aComponent) {
            found.value = true;
        }

        return null;
    }

    /**
     * Returns the first Component in the traversal cycle. This method is used
     * to determine the next Component to focus when traversal wraps in the
     * forward direction.
     *
     * @param aContainer the focus cycle root or focus traversal policy provider whose first
     *        Component is to be returned
     * @return the first Component in the traversal cycle of aContainer,
     *         or null if no suitable Component can be found
     * @throws IllegalArgumentException if aContainer is null
     */
    public Component getFirstComponent(Container aContainer) {
        if (aContainer == null) {
            throw new IllegalArgumentException("aContainer cannot be null");
        }

        synchronized(aContainer.getTreeLock()) {
            if (!(aContainer.isVisible() &&
                  aContainer.isDisplayable()))
            {
                return null;
            }

            if (accept(aContainer)) {
                return aContainer;
            }

            for (int i = 0; i < aContainer.ncomponents; i++) {
                Component comp = aContainer.component[i];
                if (comp instanceof Container &&
                    !((Container)comp).isFocusCycleRoot())
                {
                    Component retval = null;
                    Container cont = (Container)comp;
                    if (cont.isFocusTraversalPolicyProvider()) {
                        FocusTraversalPolicy policy = cont.getFocusTraversalPolicy();
                        retval = policy.getDefaultComponent(cont);
                    } else {
                        retval = getFirstComponent((Container)comp);
                    }
                    if (retval != null) {
                        return retval;
                    }
                } else if (accept(comp)) {
                    return comp;
                }
            }
        }

        return null;
    }

    /**
     * Returns the last Component in the traversal cycle. This method is used
     * to determine the next Component to focus when traversal wraps in the
     * reverse direction.
     *
     * @param aContainer the focus cycle root or focus traversal policy provider whose last
     *        Component is to be returned
     * @return the last Component in the traversal cycle of aContainer,
     *         or null if no suitable Component can be found
     * @throws IllegalArgumentException if aContainer is null
     */
    public Component getLastComponent(Container aContainer) {
        if (aContainer == null) {
            throw new IllegalArgumentException("aContainer cannot be null");
        }
        if (log.isLoggable(Level.FINE)) log.fine("Looking for the last component in " + aContainer);

        synchronized(aContainer.getTreeLock()) {
            if (!(aContainer.isVisible() &&
                  aContainer.isDisplayable()))
            {
                return null;
            }

            for (int i = aContainer.ncomponents - 1; i >= 0; i--) {
                Component comp = aContainer.component[i];
                if (comp instanceof Container &&
                    !((Container)comp).isFocusCycleRoot())
                {
                    Component retval = null;
                    Container cont = (Container)comp;
                    if (cont.isFocusTraversalPolicyProvider()) {
                        if (log.isLoggable(Level.FINE)) log.fine("\tEntering FTP " + cont);
                        FocusTraversalPolicy policy = cont.getFocusTraversalPolicy();
                        retval = policy.getLastComponent(cont);
                    } else {
                        if (log.isLoggable(Level.FINE)) log.fine("\tEntering sub-container");
                        retval = getLastComponent((Container)comp);
                    }
                    if (retval != null) {
                        if (log.isLoggable(Level.FINE)) log.fine("\tFound last component : " + retval);
                        return retval;
                    }
                } else if (accept(comp)) {
                    return comp;
                }
            }

            if (accept(aContainer)) {
                return aContainer;
            }
        }

        return null;
    }

    /**
     * Returns the default Component to focus. This Component will be the first
     * to receive focus when traversing down into a new focus traversal cycle
     * rooted at aContainer. The default implementation of this method
     * returns the same Component as <code>getFirstComponent</code>.
     *
     * @param aContainer the focus cycle root or focus traversal policy provider whose default
     *        Component is to be returned
     * @return the default Component in the traversal cycle of aContainer,
     *         or null if no suitable Component can be found
     * @see #getFirstComponent
     * @throws IllegalArgumentException if aContainer is null
     */
    public Component getDefaultComponent(Container aContainer) {
        return getFirstComponent(aContainer);
    }

    /**
     * Sets whether this ContainerOrderFocusTraversalPolicy transfers focus
     * down-cycle implicitly. If <code>true</code>, during normal forward focus
     * traversal, the Component traversed after a focus cycle root will be the
     * focus-cycle-root's default Component to focus. If <code>false</code>,
     * the next Component in the focus traversal cycle rooted at the specified
     * focus cycle root will be traversed instead. The default value for this
     * property is <code>true</code>.
     *
     * @param implicitDownCycleTraversal whether this
     *        ContainerOrderFocusTraversalPolicy transfers focus down-cycle
     *        implicitly
     * @see #getImplicitDownCycleTraversal
     * @see #getFirstComponent
     */
    public void setImplicitDownCycleTraversal(boolean
                                              implicitDownCycleTraversal) {
        this.implicitDownCycleTraversal = implicitDownCycleTraversal;
    }

    /**
     * Returns whether this ContainerOrderFocusTraversalPolicy transfers focus
     * down-cycle implicitly. If <code>true</code>, during normal forward focus
     * traversal, the Component traversed after a focus cycle root will be the
     * focus-cycle-root's default Component to focus. If <code>false</code>,
     * the next Component in the focus traversal cycle rooted at the specified
     * focus cycle root will be traversed instead.
     *
     * @return whether this ContainerOrderFocusTraversalPolicy transfers focus
     *         down-cycle implicitly
     * @see #setImplicitDownCycleTraversal
     * @see #getFirstComponent
     */
    public boolean getImplicitDownCycleTraversal() {
        return implicitDownCycleTraversal;
    }

    /**
     * Determines whether a Component is an acceptable choice as the new
     * focus owner. By default, this method will accept a Component if and
     * only if it is visible, displayable, enabled, and focusable.
     *
     * @param aComponent the Component whose fitness as a focus owner is to
     *        be tested
     * @return <code>true</code> if aComponent is visible, displayable,
     *         enabled, and focusable; <code>false</code> otherwise
     */
    protected boolean accept(Component aComponent) {
        if (!(aComponent.isVisible() && aComponent.isDisplayable() &&
              aComponent.isFocusable() && aComponent.isEnabled())) {
            return false;
        }

        // Verify that the Component is recursively enabled. Disabling a
        // heavyweight Container disables its children, whereas disabling
        // a lightweight Container does not.
        if (!(aComponent instanceof Window)) {
            for (Container enableTest = aComponent.getParent();
                 enableTest != null;
                 enableTest = enableTest.getParent())
            {
                if (!(enableTest.isEnabled() || enableTest.isLightweight())) {
                    return false;
                }
                if (enableTest instanceof Window) {
                    break;
                }
            }
        }

        return true;
    }

}


class MutableBoolean {
    boolean value = false;
}
