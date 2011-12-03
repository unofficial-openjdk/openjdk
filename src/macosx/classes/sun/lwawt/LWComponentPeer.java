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

import java.awt.dnd.DropTarget;
import java.awt.dnd.peer.DropTargetPeer;
import java.awt.event.*;

import java.awt.geom.Area;
import java.awt.image.ColorModel;
import java.awt.image.ImageObserver;
import java.awt.image.ImageProducer;
import java.awt.image.VolatileImage;

import java.awt.peer.ComponentPeer;
import java.awt.peer.ContainerPeer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.beans.Transient;
import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedAction;

import sun.awt.*;

import sun.awt.event.IgnorePaintEvent;

import sun.awt.image.SunVolatileImage;
import sun.awt.image.ToolkitImage;

import sun.java2d.SunGraphics2D;
import sun.java2d.pipe.Region;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.RepaintManager;

import sun.java2d.pipe.SpanIterator;
import sun.lwawt.macosx.CDropTarget;

import com.sun.java.swing.SwingUtilities3;

public abstract class LWComponentPeer<T extends Component, D extends JComponent>
        implements ComponentPeer, DropTargetPeer {
    // State lock is to be used for modifications to this
    // peer's fields (e.g. bounds, background, font, etc.)
    // It should be the last lock in the lock chain
    private final Object stateLock =
            new StringBuilder("LWComponentPeer.stateLock");

    // The lock to operate with the peers hierarchy. AWT tree
    // lock is not used as there are many peers related ops
    // to be done on the toolkit thread, and we don't want to
    // depend on a public lock on this thread
    private final static Object peerTreeLock =
            new StringBuilder("LWComponentPeer.peerTreeLock");

    /**
     * A custom tree-lock used for the hierarchy of the delegate Swing
     * components.
     * The lock synchronizes access to the delegate
     * internal state. Think of it as a 'virtual EDT'.
     */
//    private final Object delegateTreeLock =
//        new StringBuilder("LWComponentPeer.delegateTreeLock");

    private T target;

    // Container peer. It may not be the peer of the target's direct
    // parent, for example, in the case of hw/lw mixing. However,
    // let's skip this scenario for the time being. We also assume
    // the container peer is not null, which might also be false if
    // addNotify() is called for a component outside of the hierarchy.
    // The exception is LWWindowPeers: their parents are always null
    private LWContainerPeer containerPeer;

    // Handy reference to the top-level window peer. Window peer is
    // borrowed from the containerPeer in constructor, and should also
    // be updated when the component is reparented to another container
    private LWWindowPeer windowPeer;

    private AtomicBoolean disposed = new AtomicBoolean(false);

    // Bounds are relative to parent peer
    private Rectangle bounds = new Rectangle();
    private Region shape;
    private Shape clip;

    // Component state. Should be accessed under the state lock
    private boolean visible = false;
    private boolean enabled = true;

    // Paint area to coalesce all the paint events and store
    // the target dirty area
    private RepaintArea targetPaintArea;

    //   private volatile boolean paintPending;
    private volatile boolean isLayouting;

    private D delegate = null;
    private Container delegateContainer;
    private Component delegateDropTarget;

    private int fNumDropTargets = 0;
    private CDropTarget fDropTarget = null;

    private PlatformComponent platformComponent;

    private class DelegateContainer extends Container {
        {
            enableEvents(0xFFFFFFFF);
        }

        @Override
        public boolean isLightweight() {
            return false;
        }

        public Point getLocation() {
            return getLocationOnScreen();
        }

        public Point getLocationOnScreen() {
            return LWComponentPeer.this.getLocationOnScreen();
        }

        public int getX() {
            return getLocation().x;
        }

        public int getY() {
            return getLocation().y;
        }

        @Transient
        public Color getBackground() {
            return getTarget().getBackground();
        }

        @Transient
        public Color getForeground() {
            return getTarget().getForeground();
        }

        @Transient
        public Font getFont() {
            return getTarget().getFont();
        }

    }

    public LWComponentPeer(T target, PlatformComponent platformComponent) {
        this.target = target;
        this.platformComponent = platformComponent;

        initializeContainerPeer();
        // Container peer is always null for LWWindowPeers, so
        // windowPeer is always null for them as well. On the other
        // hand, LWWindowPeer shouldn't use windowPeer at all
        if (containerPeer != null) {
            windowPeer = containerPeer.getWindowPeerOrSelf();
        }
        // don't bother about z-order here as updateZOrder()
        // will be called from addNotify() later anyway
        if (containerPeer != null) {
            containerPeer.addChildPeer(this);
        }

        // the delegate must be created after the target is set
        AWTEventListener toolkitListener = null;
        synchronized (Toolkit.getDefaultToolkit()) {
            try {
                toolkitListener = getToolkitAWTEventListener();
                setToolkitAWTEventListener(null);

                synchronized (getDelegateLock()) {
                    delegate = createDelegate();
                    if (delegate != null) {
                        delegateContainer = new DelegateContainer();
                        delegateContainer.add(delegate);
                        delegateContainer.addNotify();
                        delegate.addNotify();
                    } else {
                        return;
                    }
                }

            } finally {
                setToolkitAWTEventListener(toolkitListener);
            }

            // todo swing: later on we will probably have one global RM
            SwingUtilities3.setDelegateRepaintManager(delegate, new RepaintManager() {
                @Override
                public void addDirtyRegion(final JComponent c, final int x, final int y, final int w, final int h) {
                    // Repainting in Swing is asynchronous, so it is emulated here by using invokeLater()
                    // to extract the painting call from the event's processing routine
                    //TODO: so why exactly do we have to emulate this in lwawt? We use a back-buffer anyway,
                    //why not paint the components synchronously into the buffer?
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            if (isShowing()) {
                                Rectangle res = SwingUtilities.convertRectangle(
                                        c, new Rectangle(x, y, w, h), getDelegate());
                                LWComponentPeer.this.repaintPeer(
                                        res.x, res.y, res.width, res.height);
                            }
                        }
                    });
                }
            });
        }
    }

    /**
     * This method must be called under Toolkit.getDefaultToolkit() lock
     * and followed by setToolkitAWTEventListener()
     */
    protected final AWTEventListener getToolkitAWTEventListener() {
        return AccessController.doPrivileged(new PrivilegedAction<AWTEventListener>() {
            public AWTEventListener run() {
                Toolkit toolkit = Toolkit.getDefaultToolkit();
                try {
                    Field field = Toolkit.class.getDeclaredField("eventListener");
                    field.setAccessible(true);
                    return (AWTEventListener) field.get(toolkit);
                } catch (Exception e) {
                    throw new InternalError(e.toString());
                }
            }
        });
    }

    protected final void setToolkitAWTEventListener(final AWTEventListener listener) {
        AccessController.doPrivileged(new PrivilegedAction<Void>() {
            public Void run() {
                Toolkit toolkit = Toolkit.getDefaultToolkit();
                try {
                    Field field = Toolkit.class.getDeclaredField("eventListener");
                    field.setAccessible(true);
                    field.set(toolkit, listener);
                } catch (Exception e) {
                    throw new InternalError(e.toString());
                }
                return null;
            }
        });
    }

    /**
     * This method is called under getDelegateLock().
     * Overridden in subclasses.
     */
    protected D createDelegate() {
        return null;
    }

    protected final D getDelegate() {
        synchronized (getStateLock()) {
            return delegate;
        }
    }

    protected Component getDelegateFocusOwner() {
        return getDelegate();
    }

    /*
     * Initializes this peer by fetching all the properties from the target.
     * The call to initialize() is not placed to LWComponentPeer ctor to
     * let the subclass ctor to finish completely first. Instead, it's the
     * LWToolkit object who is responsible for initialization.
     */
    public void initialize() {
        platformComponent.initialize(target, this, getPlatformWindow());
        targetPaintArea = new RepaintArea();
        if (target.isBackgroundSet()) {
            setBackground(target.getBackground());
        }
        if (target.isForegroundSet()) {
            setForeground(target.getForeground());
        }
        if (target.isFontSet()) {
            setFont(target.getFont());
        }
        setBounds(target.getBounds());
        setEnabled(target.isEnabled());
        setVisible(target.isVisible());
        synchronized (getDelegateLock()) {
            if (getDelegate() != null) {
                resetColorsAndFont(delegate);
                // we must explicitly set the font here
                // see Component.getFont_NoClientCode() for details
                delegateContainer.setFont(target.getFont());
            }
        }
    }

    private void resetColorsAndFont(Container c) {
        c.setBackground(null);
        c.setForeground(null);
        c.setFont(null);
        for (int i = 0; i < c.getComponentCount(); i++) {
            resetColorsAndFont((Container) c.getComponent(i));
        }
    }

    final Object getStateLock() {
        return stateLock;
    }

    // Synchronize all operations with the Swing delegates under
    // AWT tree lock, using a new separate lock to synchronize
    // access to delegates may lead deadlocks
    final Object getDelegateLock() {
        //return delegateTreeLock;
        return getTarget().getTreeLock();
    }

    protected final static Object getPeerTreeLock() {
        return peerTreeLock;
    }

    final T getTarget() {
        return target;
    }

    // Just a helper method
    // Returns the window peer or null if this is a window peer
    protected final LWWindowPeer getWindowPeer() {
        return windowPeer;
    }

    // Returns the window peer or 'this' if this is a window peer
    protected LWWindowPeer getWindowPeerOrSelf() {
        return getWindowPeer();
    }

    // Just a helper method
    protected final LWContainerPeer getContainerPeer() {
        return containerPeer;
    }

    // Just a helper method
    // Overriden in LWWindowPeer to skip containerPeer initialization
    protected void initializeContainerPeer() {
        Container parent = LWToolkit.getNativeContainer(target);
        if (parent != null) {
            containerPeer = (LWContainerPeer) LWToolkit.targetToPeer(parent);
        }
    }

    public PlatformWindow getPlatformWindow() {
        LWWindowPeer windowPeer = getWindowPeer();
        return windowPeer.getPlatformWindow();
    }

    // ---- PEER METHODS ---- //

    @Override
    public Toolkit getToolkit() {
        return LWToolkit.getLWToolkit();
    }

    // Just a helper method
    public LWToolkit getLWToolkit() {
        return LWToolkit.getLWToolkit();
    }

    @Override
    public void dispose() {
        if (disposed.compareAndSet(false, true)) {
            disposeImpl();
        }
    }

    protected void disposeImpl() {
        LWContainerPeer cp = getContainerPeer();
        if (cp != null) {
            cp.removeChildPeer(this);
        }
        platformComponent.dispose();
        LWToolkit.targetDisposedPeer(getTarget(), this);
    }

    public final boolean isDisposed() {
        return disposed.get();
    }

    /*
     * GraphicsConfiguration is borrowed from the parent peer. The
     * return value must not be null.
     *
     * Overridden in LWWindowPeer.
     */
    @Override
    public GraphicsConfiguration getGraphicsConfiguration() {
        // Don't check windowPeer for null as it can only happen
        // for windows, but this method is overridden in
        // LWWindowPeer and doesn't call super()
        return getWindowPeer().getGraphicsConfiguration();
    }

    /*
     * Overridden in LWWindowPeer to replace its surface
     * data and back buffer.
     */
    @Override
    public boolean updateGraphicsData(GraphicsConfiguration gc) {
        // TODO: not implemented
//        throw new RuntimeException("Has not been implemented yet.");
        return false;
    }

    /*
     * Peer Graphics is borrowed from the parent peer, while
     * foreground and background colors and font are specific to
     * this peer.
     */
    @Override
    public Graphics getGraphics() {
        // getGraphics() is executed outside of the synchronized block
        // as the state lock should be the last one in the chain
        return getGraphics(getForeground(), getBackground(), getFont());
    }

    /*
     * To be overridden in LWWindowPeer to get a Graphics from
     * the delegate.
     */
    protected Graphics getGraphics(Color fg, Color bg, Font f) {
        LWContainerPeer cp = getContainerPeer();
        // Don't check containerPeer for null as it can only happen
        // for windows, but this method is overridden in
        // LWWindowPeer and doesn't call super()
        SunGraphics2D g = (SunGraphics2D) cp.getGraphics(fg, bg, f);
        if (g != null) {
            final Rectangle r = getBounds();
            final Rectangle constr = r.intersection(cp.getContentSize());
            g.constrain(constr.x, constr.y, constr.width, constr.height);
            g.translate(r.x - constr.x, r.y - constr.y);
            if (clip != null) {
                g.clip(clip);
            }
        }
        return g;
    }

    public Graphics getOffscreenGraphics() {
        return getOffscreenGraphics(getForeground(), getBackground(),
                getFont());
    }

    /**
     * This method returns a back buffer Graphics to render all the peers to.
     * After the peer is painted, the back buffer contents should be flushed to
     * the screen. All the target painting (Component.paint() method) should be
     * done directly to the screen.
     */
    protected Graphics getOffscreenGraphics(final Color fg, final Color bg,
                                            final Font f) {
        final LWContainerPeer cp = getContainerPeer();
        // Don't check containerPeer for null as it can only happen
        // for windows, but this method is overridden in
        // LWWindowPeer and doesn't call super()
        SunGraphics2D g = (SunGraphics2D) cp.getOffscreenGraphics(fg, bg, f);
        if (g != null) {
            final Rectangle r = getBounds();
            final Rectangle constr = r.intersection(cp.getContentSize());
            g.constrain(constr.x, constr.y, constr.width, constr.height);
            g.translate(r.x - constr.x, r.y - constr.y);
            if (clip != null) {
                g.clip(clip);
            }
        }
        return g;
    }

    @Override
    public ColorModel getColorModel() {
        // Is it a correct implementation?
        return getGraphicsConfiguration().getColorModel();
    }

    @Override
    public void createBuffers(int numBuffers, BufferCapabilities caps)
            throws AWTException {
        throw new AWTException("Back buffers are only supported for " +
                "Window or Canvas components.");
    }

    /*
     * To be overridden in LWWindowPeer and LWCanvasPeer.
     */
    @Override
    public Image getBackBuffer() {
        // Return null or throw AWTException?
        return null;
    }

    @Override
    public void flip(int x1, int y1, int x2, int y2,
                     BufferCapabilities.FlipContents flipAction) {
        // Skip silently or throw AWTException?
    }

    @Override
    public void destroyBuffers() {
        // Do nothing
    }

    // Helper method
    public void setBounds(Rectangle r) {
        setBounds(r.x, r.y, r.width, r.height, SET_BOUNDS);
    }

    /*
    * This method could be called on the toolkit thread.
    */
    @Override
    public void setBounds(int x, int y, int w, int h, int op) {
        setBounds(x, y, w, h, op, true);
    }

    protected void setBounds(int x, int y, int w, int h, int op, boolean notify) {
        Rectangle oldBounds;
        synchronized (getStateLock()) {
            oldBounds = new Rectangle(bounds);
            if ((op & (SET_LOCATION | SET_BOUNDS)) != 0) {
                bounds.x = x;
                bounds.y = y;
            }
            if ((op & (SET_SIZE | SET_BOUNDS)) != 0) {
                bounds.width = w;
                bounds.height = h;
            }
        }
        if (notify) {
            boolean moved = (oldBounds.x != x) || (oldBounds.y != y);
            boolean resized = (oldBounds.width != w) || (oldBounds.height != h);
//            paintPending = !resized;
            if (moved) {
                handleMove(oldBounds.x, oldBounds.y, x, y);
            }
            if (resized) {
                handleResize(oldBounds.width, oldBounds.height, w, h);
            }
        }
        Point locationInWindow = localToWindow(0, 0);
        platformComponent.setBounds(locationInWindow.x, locationInWindow.y,
                bounds.width, bounds.height);
    }

    public Rectangle getBounds() {
        synchronized (getStateLock()) {
            // Return a copy to prevent subsequent modifications
            return new Rectangle(bounds);
        }
    }

    @Override
    public Point getLocationOnScreen() {
        Point windowLocation = getWindowPeer().getLocationOnScreen();
        Point locationInWindow = localToWindow(0, 0);
        return new Point(windowLocation.x + locationInWindow.x,
                windowLocation.y + locationInWindow.y);
    }

    @Override
    public void setBackground(Color c) {
        synchronized (getDelegateLock()) {
            D delegate = getDelegate();
            if (delegate != null) {
                // delegate will repaint the target
                delegate.setBackground(c);
            }
        }
    }

    protected Color getBackground() {
        synchronized (getDelegateLock()) {
            D delegate = getDelegate();
            if (delegate != null) {
                return delegate.getBackground();
            }
        }
        return null;
    }

    @Override
    public void setForeground(Color c) {
        synchronized (getDelegateLock()) {
            D delegate = getDelegate();
            if (delegate != null) {
                // delegate will repaint the target
                delegate.setForeground(c);
            }
        }
    }

    protected Color getForeground() {
        synchronized (getDelegateLock()) {
            D delegate = getDelegate();
            if (delegate != null) {
                return delegate.getForeground();
            }
        }
        return null;
    }

    @Override
    public void setFont(Font f) {
        synchronized (getDelegateLock()) {
            D delegate = getDelegate();
            if (delegate != null) {
                // delegate will repaint the target
                delegate.setFont(f);
                if (f == null) {
                    // we must explicitly set the font here
                    // see Component.getFont_NoClientCode() for details
                    delegateContainer.setFont(getTarget().getFont());
                }
            }
        }
    }

    protected Font getFont() {
        synchronized (getDelegateLock()) {
            D delegate = getDelegate();
            if (delegate != null) {
                return delegate.getFont();
            }
        }
        return null;
    }

    @Override
    public FontMetrics getFontMetrics(Font f) {
        // Borrow the metrics from the top-level window
//        return getWindowPeer().getFontMetrics(f);
        // Obtain the metrics from the offscreen window where this peer is
        // mostly drawn to.
        // TODO: check for "use platform metrics" settings
        Graphics g = getWindowPeer().getOffscreenGraphics();
        try {
            if (g != null) {
                return g.getFontMetrics(f);
            } else {
                synchronized (getDelegateLock()) {
                    return delegateContainer.getFontMetrics(f);
                }
            }
        } finally {
            if (g != null) {
                g.dispose();
            }
        }
    }

    @Override
    public void setEnabled(final boolean e) {
        boolean status = e;
        final LWComponentPeer cp = getContainerPeer();
        if (cp != null) {
            status &= cp.isEnabled();
        }
        synchronized (getStateLock()) {
            if (enabled == status) {
                return;
            }
            enabled = status;
        }

        final D delegate = getDelegate();

        if (delegate != null) {
            synchronized (getDelegateLock()) {
                delegate.setEnabled(status);
            }
        } else {
            repaintPeer();
        }
    }

    // Helper method
    public boolean isEnabled() {
        synchronized (getStateLock()) {
            return enabled;
        }
    }

    @Override
    public void setVisible(boolean v) {
        synchronized (getStateLock()) {
            if (visible == v) {
                return;
            }
            visible = v;
        }

        final D delegate = getDelegate();

        if (delegate != null) {
            synchronized (getDelegateLock()) {
                delegate.setVisible(v);
            }
        }

        LWContainerPeer cp = getContainerPeer();
        if (cp != null) {
            Rectangle r = getBounds();
            cp.repaintPeer(r.x, r.y, r.width, r.height);
        }
        repaintPeer();
    }

    // Helper method
    public boolean isVisible() {
        synchronized (getStateLock()) {
            return visible;
        }
    }

    @Override
    public void paint(Graphics g) {
        // For some unknown reasons, all the platforms just call
        // to target.paint() here and don't paint the peer itself
        getTarget().paint(g);
    }

    @Override
    public void print(final Graphics g) {
        getTarget().print(g);
    }

    @Override
    public void reparent(ContainerPeer newContainer) {
        // TODO: not implemented
        throw new UnsupportedOperationException("ComponentPeer.reparent()");
    }

    @Override
    public boolean isReparentSupported() {
        // TODO: not implemented
        return false;
    }

    @Override
    public void setZOrder(ComponentPeer above) {
        LWContainerPeer cp = getContainerPeer();
        // Don't check containerPeer for null as it can only happen
        // for windows, but this method is overridden in
        // LWWindowPeer and doesn't call super()
        cp.setChildPeerZOrder(this, (LWComponentPeer) above);
    }

    @Override
    public void coalescePaintEvent(PaintEvent e) {
        if (!(e instanceof IgnorePaintEvent)) {
            Rectangle r = e.getUpdateRect();
            if ((r != null) && !r.isEmpty()) {
                targetPaintArea.add(r, e.getID());
            }
        }
    }

    /*
     * Should be overridden in subclasses which use complex Swing components.
     */
    @Override
    public void layout() {
        // TODO: not implemented
    }

    @Override
    public boolean isObscured() {
        // TODO: not implemented
        return false;
    }

    @Override
    public boolean canDetermineObscurity() {
        // TODO: not implemented
        return false;
    }

    /*
     * Should be overriden in subclasses to forward the request
     * to the Swing helper component, if required.
     */
    @Override
    public Dimension getPreferredSize() {
        // It looks like a default implementation for all toolkits
        return getMinimumSize();
    }

    /*
     * Should be overridden in subclasses to forward the request
     * to the Swing helper component.
     */
    @Override
    public Dimension getMinimumSize() {
        D delegate = getDelegate();

        if (delegate == null) {
            // Is it a correct default value?
            return getBounds().getSize();
        } else {
            synchronized (getDelegateLock()) {
                return delegate.getMinimumSize();
            }
        }
    }

    @Override
    public void updateCursorImmediately() {
        getLWToolkit().getCursorManager().updateCursor();
    }

    @Override
    public boolean isFocusable() {
        // Overridden in focusable subclasses like buttons
        return false;
    }

    @Override
    public boolean requestFocus(Component lightweightChild, boolean temporary,
                                boolean focusedWindowChangeAllowed, long time,
                                CausedFocusEvent.Cause cause) {
        if (getLWToolkit().getKeyboardFocusManagerPeer().
                processSynchronousLightweightTransfer(getTarget(), lightweightChild, temporary,
                        focusedWindowChangeAllowed, time)) {
            return true;
        }

        int result = getLWToolkit().getKeyboardFocusManagerPeer().
                shouldNativelyFocusHeavyweight(getTarget(), lightweightChild, temporary,
                        focusedWindowChangeAllowed, time, cause);
        switch (result) {
            case LWKeyboardFocusManagerPeer.SNFH_FAILURE:
                return false;
            case LWKeyboardFocusManagerPeer.SNFH_SUCCESS_PROCEED:
                Window parentWindow = SunToolkit.getContainingWindow(getTarget());
                if (parentWindow == null) {
                    LWKeyboardFocusManagerPeer.removeLastFocusRequest(getTarget());
                    return false;
                }
                LWWindowPeer parentPeer = (LWWindowPeer) parentWindow.getPeer();
                if (parentPeer == null) {
                    LWKeyboardFocusManagerPeer.removeLastFocusRequest(getTarget());
                    return false;
                }

                boolean res = parentPeer.requestWindowFocus(cause);
                // If parent window can be made focused and has been made focused (synchronously)
                // then we can proceed with children, otherwise we retreat
                if (!res || !parentWindow.isFocused()) {
                    LWKeyboardFocusManagerPeer.removeLastFocusRequest(getTarget());
                    return false;
                }

                LWComponentPeer focusOwnerPeer =
                        getLWToolkit().getKeyboardFocusManagerPeer().getFocusOwner();
                Component focusOwner = (focusOwnerPeer != null) ? focusOwnerPeer.getTarget() : null;
                return LWKeyboardFocusManagerPeer.deliverFocus(lightweightChild,
                        getTarget(), temporary,
                        focusedWindowChangeAllowed,
                        time, cause, focusOwner);
            case LWKeyboardFocusManagerPeer.SNFH_SUCCESS_HANDLED:
                return true;
        }

        return false;
    }

    @Override
    public Image createImage(ImageProducer producer) {
        return new ToolkitImage(producer);
    }

    @Override
    public Image createImage(int w, int h) {
        // TODO: accelerated image
        return getGraphicsConfiguration().createCompatibleImage(w, h);
    }

    @Override
    public VolatileImage createVolatileImage(int w, int h) {
        // TODO: is it a right/complete implementation?
        return new SunVolatileImage(getTarget(), w, h);
    }

    @Override
    public boolean prepareImage(Image img, int w, int h, ImageObserver o) {
        // TODO: is it a right/complete implementation?
        return getToolkit().prepareImage(img, w, h, o);
    }

    @Override
    public int checkImage(Image img, int w, int h, ImageObserver o) {
        // TODO: is it a right/complete implementation?
        return getToolkit().checkImage(img, w, h, o);
    }

    @Override
    public boolean handlesWheelScrolling() {
        // TODO: not implemented
        return false;
    }

    @Override
    public void applyShape(final Region shape) {
        Area area = null;
        if (shape != null) {
            area = new Area();
            final int[] span = new int[4];
            final SpanIterator si = shape.getSpanIterator();
            while (si.nextSpan(span)) {
                area.add(new Area(new Rectangle(span[0], span[1], span[2],
                        span[3])));
            }
        }
        synchronized (getStateLock()) {
            clip = area;
            this.shape = shape;
        }
        LWContainerPeer cp = getContainerPeer();
        if (cp != null) {
            Rectangle r = getBounds();
            cp.repaintPeer(r.x, r.y, r.width, r.height);
        }
        repaintPeer();
    }

    private Region getShape() {
        synchronized (getStateLock()) {
            return shape;
        }
    }

    protected Shape getClip() {
        synchronized (getStateLock()) {
            return clip;
        }
    }

    // DropTargetPeer Method
    public synchronized void addDropTarget(DropTarget dt) {
        // 10-14-02 VL: Windows WComponentPeer would add (or remove) the drop target only
        // if it's the first (or last) one for the component. Otherwise this call is a no-op.
        if (++fNumDropTargets == 1) {
            // Having a non-null drop target would be an error but let's check just in case:
            if (fDropTarget != null)
                System.err.println("CComponent.addDropTarget(): current drop target is non-null.");

            // Create a new drop target:
            fDropTarget = CDropTarget.createDropTarget(dt, target, this);
        }
    }

    // DropTargetPeer Method
    public synchronized void removeDropTarget(DropTarget dt) {
        // 10-14-02 VL: Windows WComponentPeer would add (or remove) the drop target only
        // if it's the first (or last) one for the component. Otherwise this call is a no-op.
        if (--fNumDropTargets == 0) {
            // Having a null drop target would be an error but let's check just in case:
            if (fDropTarget != null) {
                // Dispose of the drop target:
                fDropTarget.dispose();
                fDropTarget = null;
            } else
                System.err.println("CComponent.removeDropTarget(): current drop target is null.");
        }
    }

    // ---- PEER NOTIFICATIONS ---- //

    /*
     * Called when this peer's location has been changed either as a result
     * of target.setLocation() or as a result of user actions (window is
     * dragged with mouse).
     *
     * To be overridden in LWWindowPeer to update its GraphicsConfig.
     *
     * This method could be called on the toolkit thread.
     */

    protected void handleMove(int oldX, int oldY, int newX, int newY) {
        postEvent(new ComponentEvent(getTarget(), ComponentEvent.COMPONENT_MOVED));
        LWContainerPeer cp = getContainerPeer();
        if (cp != null) {
            // Repaint unobscured part of the parent
            Rectangle r = getBounds();
            cp.repaintPeer(oldX, oldY, r.width, r.height);
            cp.repaintPeer(newX, newY, r.width, r.height);
        }
    }

    /*
     * Called when this peer's size has been changed either as a result of
     * target.setSize() or as a result of user actions (window is resized).
     *
     * To be overridden in LWWindowPeer to update its SurfaceData and
     * GraphicsConfig.
     *
     * This method could be called on the toolkit thread.
     */
    protected void handleResize(int oldW, int oldH, int newW, int newH) {
        postEvent(new ComponentEvent(getTarget(), ComponentEvent.COMPONENT_RESIZED));
        LWContainerPeer cp = getContainerPeer();
        if (cp != null) {
            // Repaint unobscured part of the parent
            Rectangle r = getBounds();
            cp.repaintPeer(r.x, r.y, oldW, oldH);
            cp.repaintPeer(r.x, r.y, newW, newH);
        }
        repaintPeer(0, 0, newW, newH);

        D delegate = getDelegate();
        if (delegate != null) {
            synchronized (getDelegateLock()) {
                delegateContainer.setBounds(0, 0, newW, newH);
                delegate.setBounds(delegateContainer.getBounds());
                // TODO: the following means that the delegateContainer NEVER gets validated. That's WRONG!
                delegate.validate();
            }
        }
    }

    /*
     * Called by the container when any part of this peer or child
     * peers should be repainted.
     *
     * This method is called on the toolkit thread.
     */
    public void handleExpose(int x, int y, int w, int h) {
        postPaintEvent(x, y, w, h);
    }

    // ---- EVENTS ---- //

    /*
     * Post an event to the proper Java EDT.
     */

    public void postEvent(AWTEvent event) {
        SunToolkit.postEvent(SunToolkit.targetToAppContext(getTarget()), event);
    }

    // Just a helper method
    protected void postPaintEvent() {
        Rectangle r = getBounds();
        postPaintEvent(0, 0, r.width, r.height);
    }

    protected void postPaintEvent(int x, int y, int w, int h) {
        // TODO: call getIgnoreRepaint() directly with the right ACC
        if (AWTAccessor.getComponentAccessor().getIgnoreRepaint(target)) {
            return;
        }
        PaintEvent event = PaintEventDispatcher.getPaintEventDispatcher().
                createPaintEvent(getTarget(), x, y, w, h);
        if (event != null) {
            postEvent(event);
        }
    }

    /*
     * Gives a chance for the peer to handle the event after it's been
     * processed by the target.
     */
    @Override
    public void handleEvent(AWTEvent e) {
        if ((e instanceof InputEvent) && ((InputEvent) e).isConsumed()) {
            return;
        }
        switch (e.getID()) {
            case FocusEvent.FOCUS_GAINED:
            case FocusEvent.FOCUS_LOST:
                handleJavaFocusEvent((FocusEvent) e);
                break;
            case PaintEvent.PAINT:
                // Got a native paint event
//                paintPending = false;
                // fall through to the next statement
            case PaintEvent.UPDATE:
                handleJavaPaintEvent((PaintEvent) e);
                break;
            case MouseEvent.MOUSE_PRESSED:
                Component target = getTarget();
                if ((e.getSource() == target) && !target.isFocusOwner() &&
                        LWKeyboardFocusManagerPeer.shouldFocusOnClick(target)) {
                    LWKeyboardFocusManagerPeer.requestFocusFor(target,
                            CausedFocusEvent.Cause.MOUSE_EVENT);
                }
                break;
        }

        sendEventToDelegate(e);
    }

    private void sendEventToDelegate(final AWTEvent e) {
        synchronized (getDelegateLock()) {
            if (getDelegate() == null || !isShowing() || !isEnabled()) {
                return;
            }
            AWTEvent delegateEvent = createDelegateEvent(e);
            if (delegateEvent != null) {
                AWTAccessor.getComponentAccessor()
                        .processEvent((Component) delegateEvent.getSource(),
                                delegateEvent);
                if (delegateEvent instanceof KeyEvent) {
                    KeyEvent ke = (KeyEvent) delegateEvent;
                    SwingUtilities.processKeyBindings(ke);
                }
            }
        }
    }

    protected AWTEvent createDelegateEvent(AWTEvent e) {
        AWTEvent delegateEvent = null;
        if (e instanceof MouseWheelEvent) {
            MouseWheelEvent me = (MouseWheelEvent) e;
            delegateEvent = new MouseWheelEvent(
                    delegate, me.getID(), me.getWhen(),
                    me.getModifiers(),
                    me.getX(), me.getY(),
                    me.getClickCount(),
                    false,                       // popupTrigger
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    3, // TODO: wheel scroll amount
                    me.getWheelRotation());
        } else if (e instanceof MouseEvent) {
            MouseEvent me = (MouseEvent) e;

            Component eventTarget = SwingUtilities.getDeepestComponentAt(delegate, me.getX(), me.getY());

            if (me.getID() == MouseEvent.MOUSE_DRAGGED) {
                if (delegateDropTarget == null) {
                    delegateDropTarget = eventTarget;
                } else {
                    eventTarget = delegateDropTarget;
                }
            }
            if (me.getID() == MouseEvent.MOUSE_RELEASED && delegateDropTarget != null) {
                eventTarget = delegateDropTarget;
                delegateDropTarget = null;
            }
            if (eventTarget == null) {
                eventTarget = delegate;
            }
            delegateEvent = SwingUtilities.convertMouseEvent(getTarget(), me, eventTarget);
        } else if (e instanceof KeyEvent) {
            KeyEvent ke = (KeyEvent) e;
            delegateEvent = new KeyEvent(getDelegateFocusOwner(), ke.getID(), ke.getWhen(),
                    ke.getModifiers(), ke.getKeyCode(), ke.getKeyChar(), ke.getKeyLocation());
        } else if (e instanceof FocusEvent) {
            FocusEvent fe = (FocusEvent) e;
            delegateEvent = new FocusEvent(getDelegateFocusOwner(), fe.getID(), fe.isTemporary());
        }
        return delegateEvent;
    }

    /*
    * Handler for FocusEvents.
    */
    protected void handleJavaFocusEvent(FocusEvent e) {
        // Note that the peer receives all the FocusEvents from
        // its lightweight children as well
        getLWToolkit().getKeyboardFocusManagerPeer().
                setFocusOwner(e.getID() == FocusEvent.FOCUS_GAINED ? this : null);
    }

    /*
     * Handler for PAINT and UPDATE PaintEvents.
     */
    protected void handleJavaPaintEvent(PaintEvent e) {
        // Skip all painting while layouting and all UPDATEs
        // while waiting for native paint
//        if (!isLayouting && !paintPending) {
        if (!isLayouting) {
            targetPaintArea.paint(getTarget(), false);
        }
    }

    // ---- UTILITY METHODS ---- //

    /**
     * Finds a top-most visible component for the given point. The location is
     * specified relative to the peer's parent.
     */
    public LWComponentPeer findPeerAt(final int x, final int y) {
        final Rectangle r = getBounds();
        final Region sh = getShape();
        final boolean found = isVisible() && ((sh == null)
                ? r.contains(x, y)
                : sh.contains(x - r.x, y - r.y));
        return found ? this : null;
    }

    /*
     * Translated the given point in Window coordinates to the point in
     * coordinates local to this component. The given window peer must be
     * the window where this component is in.
     */
    public Point windowToLocal(int x, int y, LWWindowPeer wp) {
        return windowToLocal(new Point(x, y), wp);
    }

    public Point windowToLocal(Point p, LWWindowPeer wp) {
        LWComponentPeer cp = this;
        while (cp != wp) {
            Rectangle cpb = cp.getBounds();
            p.x -= cpb.x;
            p.y -= cpb.y;
            cp = cp.getContainerPeer();
        }
        // Return a copy to prevent subsequent modifications
        return new Point(p);
    }

    public Rectangle windowToLocal(Rectangle r, LWWindowPeer wp) {
        Point p = windowToLocal(r.getLocation(), wp);
        return new Rectangle(p, r.getSize());
    }

    public Point localToWindow(int x, int y) {
        return localToWindow(new Point(x, y));
    }

    public Point localToWindow(Point p) {
        LWComponentPeer cp = getContainerPeer();
        Rectangle r = getBounds();
        while (cp != null) {
            p.x += r.x;
            p.y += r.y;
            r = cp.getBounds();
            cp = cp.getContainerPeer();
        }
        // Return a copy to prevent subsequent modifications
        return new Point(p);
    }

    public Rectangle localToWindow(Rectangle r) {
        Point p = localToWindow(r.getLocation());
        return new Rectangle(p, r.getSize());
    }

    // Just a helper method, thus final
    protected final void paintPeerDirtyRect() {
        Rectangle r = getBounds();
        paintPeerDirtyRect(new Rectangle(0, 0, r.width, r.height));
    }

    /*
     * Repaints the given rectangle of the back buffer and flushes it
     * to the screen. This method should only be called on EDT.
     */
    protected void paintPeerDirtyRect(final Rectangle r) {
        if ((r == null) || r.isEmpty() || !isShowing()) {
            return;
        }

        final Graphics g = getOffscreenGraphics();
        if (g != null) {
            try {
                peerPaint(g, r);
            } finally {
                g.dispose();
            }
            flushOffscreenGraphics(r);
        }
    }


    // TODO: rename to repaintPeer()?
    protected void paintPeerDirtyRectOnEDT(final Rectangle dirty) {
        if (LWToolkit.isDispatchThreadForAppContext(getTarget())) {
            paintPeerDirtyRect(dirty);
        } else {
            Runnable r = new Runnable() {
                public void run() {
                    paintPeerDirtyRect(dirty);
                }
            };
            // TODO: high-priority invocation event
            LWToolkit.executeOnEventHandlerThread(getTarget(), r);
        }
    }

    // todo swing: think about making both methods private
    // and leaving repaintPeer() only
    public void repaintPeer() {
        repaintPeer(0, 0, getBounds().width, getBounds().height);
    }

    public void repaintPeer(final int x, final int y, final int width,
                            final int height) {
        paintPeerDirtyRectOnEDT(new Rectangle(x, y, width, height));
        postPaintEvent(x, y, width, height);
    }

    public void restorePeer() {
        if (isShowing() && !getBounds().isEmpty()) {
            flushOffscreenGraphics();
            postPaintEvent();
        }
    }

    /**
     * Determines whether this peer is showing on screen. This means that the
     * peer must be visible, and it must be in a container that is visible and
     * showing.
     *
     * @see #isVisible()
     */
    protected boolean isShowing() {
        synchronized (getPeerTreeLock()) {
            if (isVisible()) {
                final LWContainerPeer container = getContainerPeer();
                return (container == null) || container.isShowing();
            }
        }
        return false;
    }

    /*
    * Paints the peer into the given graphics, mostly originated
    * from the window's back buffer.
    *
    * Overridden in LWContainerPeer to paint all the children.
    */
    protected void peerPaint(Graphics g, Rectangle r) {
        Rectangle b = getBounds();
        if (!isShowing() || r.isEmpty() ||
                !r.intersects(new Rectangle(0, 0, b.width, b.height))) {
            return;
        }
        g.clipRect(r.x, r.y, r.width, r.height);
        peerPaintSelf(g, r);
    }

    /*
     * Paints the peer. Overriden in subclasses to delegate the actual
     * painting to Swing components.
     */
    protected void peerPaintSelf(Graphics g, Rectangle r) {
        D delegate = getDelegate();

        // By default, just fill the entire bounds with a bg color
        // TODO: sun.awt.noerasebackground
        if (delegate == null) {
            g.clearRect(r.x, r.y, r.width, r.height);
        } else {
            if (!SwingUtilities.isEventDispatchThread()) {
                throw new InternalError("Painting must be done on EDT");
            }
            //LW delegates does not fill whole bounds.
            LWComponentPeer cp = getContainerPeer();
            if (cp != null) {
                Color c = g.getColor();
                g.setColor(cp.getBackground());
                g.fillRect(r.x, r.y, r.width, r.height);
                g.setColor(c);
            }
            synchronized (getDelegateLock()) {
                // JComponent.print() is guaranteed to not affect the double buffer
                delegate.print(g);
            }
        }
    }

    // Just a helper method, thus final
    protected final void flushOffscreenGraphics() {
        Rectangle r = getBounds();
        flushOffscreenGraphics(new Rectangle(0, 0, r.width, r.height));
    }

    /*
     * Flushes the given rectangle from the back buffer to the screen.
     */
    protected void flushOffscreenGraphics(Rectangle r) {
        flushOffscreenGraphics(r.x, r.y, r.width, r.height);
    }

    private void flushOffscreenGraphics(int x, int y, int width, int height) {
        Image bb = getWindowPeerOrSelf().getBackBuffer();
        if (bb != null) {
            // g is a screen Graphics from the delegate
            final Graphics g = getGraphics();
            if (g != null) {
                try {
                    Point p = localToWindow(new Point(0, 0));
                    g.drawImage(bb, x, y, x + width, y + height, p.x + x,
                            p.y + y, p.x + x + width, p.y + y + height,
                            null);
                } finally {
                    g.dispose();
                }
            }
        }
    }


    /*
    * Used by ContainerPeer to skip all the paint events during layout.
    */
    protected void setLayouting(boolean l) {
        isLayouting = l;
    }
}
