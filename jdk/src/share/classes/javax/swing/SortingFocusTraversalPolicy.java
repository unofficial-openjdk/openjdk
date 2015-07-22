/*
 * Copyright (c) 2000, 2006, Oracle and/or its affiliates. All rights reserved.
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
package javax.swing;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.util.*;
import java.awt.FocusTraversalPolicy;
import java.util.logging.*;

/**
 * A FocusTraversalPolicy that determines traversal order by sorting the
 * Components of a focus traversal cycle based on a given Comparator. Portions
 * of the Component hierarchy that are not visible and displayable will not be
 * included.
 * <p>
 * By default, SortingFocusTraversalPolicy implicitly transfers focus down-
 * cycle. That is, during normal focus traversal, the Component
 * traversed after a focus cycle root will be the focus-cycle-root's default
 * Component to focus. This behavior can be disabled using the
 * <code>setImplicitDownCycleTraversal</code> method.
 * <p>
 * By default, methods of this class with return a Component only if it is
 * visible, displayable, enabled, and focusable. Subclasses can modify this
 * behavior by overriding the <code>accept</code> method.
 * <p>
 * This policy takes into account <a
 * href="../../java/awt/doc-files/FocusSpec.html#FocusTraversalPolicyProviders">focus traversal
 * policy providers</a>.  When searching for first/last/next/previous Component,
 * if a focus traversal policy provider is encountered, its focus traversal
 * policy is used to perform the search operation.
 *
 * @author David Mendenhall
 *
 * @see java.util.Comparator
 * @since 1.4
 */
public class SortingFocusTraversalPolicy
    extends InternalFrameFocusTraversalPolicy
{
    private Comparator<? super Component> comparator;
    private boolean implicitDownCycleTraversal = true;

    private Logger log = Logger.getLogger("javax.swing.SortingFocusTraversalPolicy");

    /**
     * Used by getComponentAfter and getComponentBefore for efficiency. In
     * order to maintain compliance with the specification of
     * FocusTraversalPolicy, if traversal wraps, we should invoke
     * getFirstComponent or getLastComponent. These methods may be overriden in
     * subclasses to behave in a non-generic way. However, in the generic case,
     * these methods will simply return the first or last Components of the
     * sorted list, respectively. Since getComponentAfter and
     * getComponentBefore have already built the sorted list before determining
     * that they need to invoke getFirstComponent or getLastComponent, the
     * sorted list should be reused if possible.
     */
    private Container cachedRoot;
    private List cachedCycle;

    // Delegate our fitness test to ContainerOrder so that we only have to
    // code the algorithm once.
    private static final SwingContainerOrderFocusTraversalPolicy
        fitnessTestPolicy = new SwingContainerOrderFocusTraversalPolicy();

    /**
     * Constructs a SortingFocusTraversalPolicy without a Comparator.
     * Subclasses must set the Comparator using <code>setComparator</code>
     * before installing this FocusTraversalPolicy on a focus cycle root or
     * KeyboardFocusManager.
     */
    protected SortingFocusTraversalPolicy() {
    }

    /**
     * Constructs a SortingFocusTraversalPolicy with the specified Comparator.
     */
    public SortingFocusTraversalPolicy(Comparator<? super Component> comparator) {
        this.comparator = comparator;
    }

    private void enumerateAndSortCycle(Container focusCycleRoot,
                                       List cycle, Map defaults) {
        List defaultRoots = null;

        if (!focusCycleRoot.isShowing()) {
            return;
        }

        enumerateCycle(focusCycleRoot, cycle);

        boolean addDefaultComponents =
            (defaults != null && getImplicitDownCycleTraversal());

        if (log.isLoggable(Level.FINE)) log.fine("### Will add defaults: " + addDefaultComponents);

        // Create a list of all default Components which should be added
        // to the list
        if (addDefaultComponents) {
            defaultRoots = new ArrayList();
            for (Iterator iter = cycle.iterator(); iter.hasNext(); ) {
                Component comp = (Component)iter.next();
                if ((comp instanceof Container) &&
                    ((Container)comp).isFocusCycleRoot())
                {
                    defaultRoots.add(comp);
                }
            }
            Collections.sort(defaultRoots, comparator);
        }

        // Sort the Components in the cycle
        Collections.sort(cycle, comparator);

        // Find all of the roots in the cycle and place their default
        // Components after them. Note that the roots may have been removed
        // from the list because they were unfit. In that case, insert the
        // default Components as though the roots were still in the list.
        if (addDefaultComponents) {
            for (ListIterator defaultRootsIter =
                     defaultRoots.listIterator(defaultRoots.size());
                 defaultRootsIter.hasPrevious(); )
            {
                Container root = (Container)defaultRootsIter.previous();
                Component defComp =
                    root.getFocusTraversalPolicy().getDefaultComponent(root);

                if (defComp != null && defComp.isShowing()) {
                    int index = Collections.binarySearch(cycle, root,
                                                         comparator);
                    if (index < 0) {
                        // If root is not in the list, then binarySearch
                        // returns (-(insertion point) - 1). defComp follows
                        // the index one less than the insertion point.

                        index = -index - 2;
                    }

                    defaults.put(new Integer(index), defComp);
                }
            }
        }
    }

    private void enumerateCycle(Container container, List cycle) {
        if (!(container.isVisible() && container.isDisplayable())) {
            return;
        }

        cycle.add(container);

        Component[] components = container.getComponents();
        for (int i = 0; i < components.length; i++) {
            Component comp = components[i];
            if ((comp instanceof Container)
                && !((Container)comp).isFocusTraversalPolicyProvider()
                && !((Container)comp).isFocusCycleRoot()
                && !((comp instanceof JComponent)
                     && ((JComponent)comp).isManagingFocus()))
            {
                enumerateCycle((Container)comp, cycle);
            } else {
                cycle.add(comp);
            }
        }
    }

    Container getTopmostProvider(Container focusCycleRoot, Component aComponent) {
        Container aCont = aComponent.getParent();
        Container ftp = null;
        while (aCont  != focusCycleRoot && aCont != null) {
            if (aCont.isFocusTraversalPolicyProvider()) {
                ftp = aCont;
            }
            aCont = aCont.getParent();
        }
        if (aCont == null) {
            return null;
        }
        return ftp;
    }

    /**
     * Returns the Component that should receive the focus after aComponent.
     * aContainer must be a focus cycle root of aComponent or a focus traversal policy provider.
     * <p>
     * By default, SortingFocusTraversalPolicy implicitly transfers focus down-
     * cycle. That is, during normal focus traversal, the Component
     * traversed after a focus cycle root will be the focus-cycle-root's
     * default Component to focus. This behavior can be disabled using the
     * <code>setImplicitDownCycleTraversal</code> method.
     * <p>
     * If aContainer is <a href="../../java/awt/doc-files/FocusSpec.html#FocusTraversalPolicyProviders">focus
     * traversal policy provider</a>, the focus is always transferred down-cycle.
     *
     * @param aContainer a focus cycle root of aComponent or a focus traversal policy provider
     * @param aComponent a (possibly indirect) child of aContainer, or
     *        aContainer itself
     * @return the Component that should receive the focus after aComponent, or
     *         null if no suitable Component can be found
     * @throws IllegalArgumentException if aContainer is not a focus cycle
     *         root of aComponent or a focus traversal policy provider, or if either aContainer or
     *         aComponent is null
     */
    public Component getComponentAfter(Container aContainer,
                                       Component aComponent) {
        if (log.isLoggable(Level.FINE)) log.fine("### Searching in " + aContainer.getName() + " for component after " + aComponent.getName());

        if (aContainer == null || aComponent == null) {
            throw new IllegalArgumentException("aContainer and aComponent cannot be null");
        }
        if (!aContainer.isFocusTraversalPolicyProvider() && !aContainer.isFocusCycleRoot()) {
            throw new IllegalArgumentException("aContainer should be focus cycle root or focus traversal policy provider");
        } else if (aContainer.isFocusCycleRoot() && !aComponent.isFocusCycleRoot(aContainer)) {
            throw new IllegalArgumentException("aContainer is not a focus cycle root of aComponent");
        }

        // See if the component is inside of policy provider
        Container ftp = getTopmostProvider(aContainer, aComponent);
        if (ftp != null) {
            if (log.isLoggable(Level.FINE)) log.fine("### Asking FTP " + ftp.getName() + " for component after " + aComponent.getName());
            // FTP knows how to find component after the given. We don't.
            FocusTraversalPolicy policy = ftp.getFocusTraversalPolicy();
            Component retval = policy.getComponentAfter(ftp, aComponent);
            if (retval == policy.getFirstComponent(ftp)) {
                retval = null;
            }

            if (retval != null) {
                if (log.isLoggable(Level.FINE)) log.fine("### FTP returned " + retval.getName());
                return retval;
        }
            aComponent = ftp;
        }

        List cycle = new ArrayList();
        Map defaults = new HashMap();
        enumerateAndSortCycle(aContainer, cycle, defaults);

        int index;
        try {
            index = Collections.binarySearch(cycle, aComponent, comparator);
        } catch (ClassCastException e) {
            if (log.isLoggable(Level.FINE)) log.fine("### Didn't find component " + aComponent.getName() + " in a cycle " + aContainer.getName());
            return getFirstComponent(aContainer);
        }

        if (index < 0) {
            // Fix for 5070991.
            // A workaround for a transitivity problem caused by ROW_TOLERANCE,
            // because of that the component may be missed in the binary search.
            // Try to search it again directly.
            int i = cycle.indexOf(aComponent);
            if (i >= 0) {
                index = i;
            } else {
                // If we're not in the cycle, then binarySearch returns
                // (-(insertion point) - 1). The next element is our insertion
                // point.

                index = -index - 2;
            }
        }

        Component defComp = (Component)defaults.get(new Integer(index));
        if (defComp != null) {
            return defComp;
        }

        do {
        index++;

        if (index >= cycle.size()) {
                if (aContainer.isFocusCycleRoot()) {
                    this.cachedRoot = aContainer;
            this.cachedCycle = cycle;

                    Component retval = getFirstComponent(aContainer);

            this.cachedRoot = null;
            this.cachedCycle = null;

            return retval;
        } else {
                    return null;
                }
            } else {
                Component comp = (Component)cycle.get(index);
                if (accept(comp)) {
                    return comp;
                } else if (comp instanceof Container && ((Container)comp).isFocusTraversalPolicyProvider()) {
                    return ((Container)comp).getFocusTraversalPolicy().getDefaultComponent((Container)comp);
                }
        }
        } while (true);
    }

    /**
     * Returns the Component that should receive the focus before aComponent.
     * aContainer must be a focus cycle root of aComponent or a focus traversal policy provider.
     * <p>
     * By default, SortingFocusTraversalPolicy implicitly transfers focus down-
     * cycle. That is, during normal focus traversal, the Component
     * traversed after a focus cycle root will be the focus-cycle-root's
     * default Component to focus. This behavior can be disabled using the
     * <code>setImplicitDownCycleTraversal</code> method.
     * <p>
     * If aContainer is <a href="../../java/awt/doc-files/FocusSpec.html#FocusTraversalPolicyProviders">focus
     * traversal policy provider</a>, the focus is always transferred down-cycle.
     *
     * @param aContainer a focus cycle root of aComponent or a focus traversal policy provider
     * @param aComponent a (possibly indirect) child of aContainer, or
     *        aContainer itself
     * @return the Component that should receive the focus before aComponent,
     *         or null if no suitable Component can be found
     * @throws IllegalArgumentException if aContainer is not a focus cycle
     *         root of aComponent or a focus traversal policy provider, or if either aContainer or
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

        // See if the component is inside of policy provider
        Container ftp = getTopmostProvider(aContainer, aComponent);
        if (ftp != null) {
            if (log.isLoggable(Level.FINE)) log.fine("### Asking FTP " + ftp.getName() + " for component after " + aComponent.getName());
            // FTP knows how to find component after the given. We don't.
            FocusTraversalPolicy policy = ftp.getFocusTraversalPolicy();
            Component retval = policy.getComponentBefore(ftp, aComponent);
            if (retval == policy.getLastComponent(ftp)) {
                retval = null;
            }
            if (retval != null) {
                if (log.isLoggable(Level.FINE)) log.fine("### FTP returned " + retval.getName());
                return retval;
        }
            aComponent = ftp;
        }


        List cycle = new ArrayList();
        Map defaults = new HashMap();
        enumerateAndSortCycle(aContainer, cycle, defaults);

        if (log.isLoggable(Level.FINE)) log.fine("### Cycle is " + cycle + ", component is " + aComponent);

        int index;
        try {
            index = Collections.binarySearch(cycle, aComponent, comparator);
        } catch (ClassCastException e) {
            return getLastComponent(aContainer);
        }

        if (index < 0) {
            // If we're not in the cycle, then binarySearch returns
            // (-(insertion point) - 1). The previous element is our insertion
            // point - 1.

            index = -index - 2;
        } else {
            index--;
        }

        if (log.isLoggable(Level.FINE)) log.fine("### Index is " + index);

        if (index >= 0) {
            Component defComp = (Component)defaults.get(new Integer(index));
            if (defComp != null && cycle.get(index) != aContainer) {
                if (log.isLoggable(Level.FINE)) log.fine("### Returning default " + defComp.getName() + " at " + index);
                return defComp;
            }
        }

        do {
        if (index < 0) {
                this.cachedRoot = aContainer;
            this.cachedCycle = cycle;

                Component retval = getLastComponent(aContainer);

            this.cachedRoot = null;
            this.cachedCycle = null;

            return retval;
        } else {
                Component comp = (Component)cycle.get(index);
                if (accept(comp)) {
                    return comp;
                } else if (comp instanceof Container && ((Container)comp).isFocusTraversalPolicyProvider()) {
                    return ((Container)comp).getFocusTraversalPolicy().getLastComponent((Container)comp);
            }
        }
            index--;
        } while (true);
    }

    /**
     * Returns the first Component in the traversal cycle. This method is used
     * to determine the next Component to focus when traversal wraps in the
     * forward direction.
     *
     * @param aContainer a focus cycle root of aComponent or a focus traversal policy provider whose
     *        first Component is to be returned
     * @return the first Component in the traversal cycle of aContainer,
     *         or null if no suitable Component can be found
     * @throws IllegalArgumentException if aContainer is null
     */
    public Component getFirstComponent(Container aContainer) {
        List cycle;

        if (log.isLoggable(Level.FINE)) log.fine("### Getting first component in " + aContainer.getName());
        if (aContainer == null) {
            throw new IllegalArgumentException("aContainer cannot be null");
        }

        if (this.cachedRoot == aContainer) {
            cycle = this.cachedCycle;
        } else {
            cycle = new ArrayList();
            enumerateAndSortCycle(aContainer, cycle, null);
        }

        int size = cycle.size();
        if (size == 0) {
            return null;
        }

        for (int i= 0; i < cycle.size(); i++) {
            Component comp = (Component)cycle.get(i);
            if (accept(comp)) {
                return comp;
            } else if (comp instanceof Container && !(comp == aContainer) && ((Container)comp).isFocusTraversalPolicyProvider()) {
                return ((Container)comp).getFocusTraversalPolicy().getDefaultComponent((Container)comp);
            }
        }
        return null;
    }

    /**
     * Returns the last Component in the traversal cycle. This method is used
     * to determine the next Component to focus when traversal wraps in the
     * reverse direction.
     *
     * @param aContainer a focus cycle root of aComponent or a focus traversal policy provider whose
     *        last Component is to be returned
     * @return the last Component in the traversal cycle of aContainer,
     *         or null if no suitable Component can be found
     * @throws IllegalArgumentException if aContainer is null
     */
    public Component getLastComponent(Container aContainer) {
        List cycle;
        if (log.isLoggable(Level.FINE)) log.fine("### Getting last component in " + aContainer.getName());

        if (aContainer == null) {
            throw new IllegalArgumentException("aContainer cannot be null");
        }

        if (this.cachedRoot == aContainer) {
            cycle = this.cachedCycle;
        } else {
            cycle = new ArrayList();
            enumerateAndSortCycle(aContainer, cycle, null);
        }

        int size = cycle.size();
        if (size == 0) {
            if (log.isLoggable(Level.FINE)) log.fine("### Cycle is empty");
            return null;
        }
        if (log.isLoggable(Level.FINE)) log.fine("### Cycle is " + cycle);

        for (int i= cycle.size()-1; i >= 0; i--) {
            Component comp = (Component)cycle.get(i);
            if (accept(comp)) {
                return comp;
            } else if (comp instanceof Container && !(comp == aContainer) && ((Container)comp).isFocusTraversalPolicyProvider()) {
                return ((Container)comp).getFocusTraversalPolicy().getLastComponent((Container)comp);
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
     * @param aContainer a focus cycle root of aComponent or a focus traversal policy provider whose
     *        default Component is to be returned
     * @return the default Component in the traversal cycle of aContainer,
     *         or null if no suitable Component can be found
     * @see #getFirstComponent
     * @throws IllegalArgumentException if aContainer is null
     */
    public Component getDefaultComponent(Container aContainer) {
        return getFirstComponent(aContainer);
    }

    /**
     * Sets whether this SortingFocusTraversalPolicy transfers focus down-cycle
     * implicitly. If <code>true</code>, during normal focus traversal,
     * the Component traversed after a focus cycle root will be the focus-
     * cycle-root's default Component to focus. If <code>false</code>, the
     * next Component in the focus traversal cycle rooted at the specified
     * focus cycle root will be traversed instead. The default value for this
     * property is <code>true</code>.
     *
     * @param implicitDownCycleTraversal whether this
     *        SortingFocusTraversalPolicy transfers focus down-cycle implicitly
     * @see #getImplicitDownCycleTraversal
     * @see #getFirstComponent
     */
    public void setImplicitDownCycleTraversal(boolean
                                              implicitDownCycleTraversal) {
        this.implicitDownCycleTraversal = implicitDownCycleTraversal;
    }

    /**
     * Returns whether this SortingFocusTraversalPolicy transfers focus down-
     * cycle implicitly. If <code>true</code>, during normal focus
     * traversal, the Component traversed after a focus cycle root will be the
     * focus-cycle-root's default Component to focus. If <code>false</code>,
     * the next Component in the focus traversal cycle rooted at the specified
     * focus cycle root will be traversed instead.
     *
     * @return whether this SortingFocusTraversalPolicy transfers focus down-
     *         cycle implicitly
     * @see #setImplicitDownCycleTraversal
     * @see #getFirstComponent
     */
    public boolean getImplicitDownCycleTraversal() {
        return implicitDownCycleTraversal;
    }

    /**
     * Sets the Comparator which will be used to sort the Components in a
     * focus traversal cycle.
     *
     * @param comparator the Comparator which will be used for sorting
     */
    protected void setComparator(Comparator<? super Component> comparator) {
        this.comparator = comparator;
    }

    /**
     * Returns the Comparator which will be used to sort the Components in a
     * focus traversal cycle.
     *
     * @return the Comparator which will be used for sorting
     */
    protected Comparator<? super Component> getComparator() {
        return comparator;
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
        return fitnessTestPolicy.accept(aComponent);
    }
}

// Create our own subclass and change accept to public so that we can call
// accept.
class SwingContainerOrderFocusTraversalPolicy
    extends java.awt.ContainerOrderFocusTraversalPolicy
{
    public boolean accept(Component aComponent) {
        return super.accept(aComponent);
    }
}
