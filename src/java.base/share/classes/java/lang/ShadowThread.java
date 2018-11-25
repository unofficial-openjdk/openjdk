/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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
package java.lang;

import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import sun.security.util.SecurityConstants;

/**
 * Represents a Thread returned by Thread.currentThread() when invoked in the
 * context of a Fiber.
 *
 * A ShadowThread does not support all features of Thread.  In particular, a
 * ShadowThread is not an <i>active thread</i> in a thread group and so is not
 * enumerated or acted on by thread group operations. ShadowThread also do not
 * support setting an uncaught exception handler or operations such as stop,
 * suspend and resume.
 */

class ShadowThread extends Thread {
    private static final ThreadGroup SHADOW_THREAD_GROUP = shadowThreadGroup();
    private static final AccessControlContext INNOCUOUS_ACC = innocuousACC();

    // the Fiber that this object shadows
    private final Fiber<?> fiber;

    // Thread interrupt support
    private final Object interruptLock = new Object();
    private volatile boolean interrupted;

    /**
     * Creates a shadow thread for the given fiber using the given inheritable
     * context.
     *
     * All shadow threads are in the same thread group and use the same innocuous
     * access control context.
     */
    ShadowThread(Fiber<?> fiber, InheritableThreadContext ctxt) {
        super(SHADOW_THREAD_GROUP,
                "Fiber",
                ctxt.contextClassLoader(),
                ctxt.inheritableThreadLocals(),
                INNOCUOUS_ACC);
        this.fiber = fiber;
    }

    Fiber<?> fiber() {
        return fiber;
    }

    /**
     * Invoked when the fiber is mounted
     */
    void onMount(Thread carrierThread) {
        // forward interrupt status to carrier thread, no synchronization needed
        if (interrupted)
            carrierThread.interrupt();
    }

    /**
     * Invoked when the fiber is unmounted
     */
    void onUnmount(Thread carrierThread) {
        // clear carrier thread interrupt status. Need to synchronize with
        // Thread.interrupt to ensure that the carrier thread is not interrupted
        // after the fiber unmounts
        synchronized (interruptLock) {
            carrierThread.getAndClearInterrupt();
        }
    }

    @Override
    public void start() {
        throw new IllegalStateException();
    }

    /**
     * Interrupt the thread and the carrier thread if the fiber is mounted. Also
     * unpark the fiber.
     */
    @Override
    public void interrupt() {
        synchronized (interruptLock) {
            super.interrupt();

            // interrupt carrier thread
            Thread t = fiber.carrierThread();
            if (t != null) t.interrupt();
        }

        fiber.unpark();
    }

    @Override
    public boolean isInterrupted() {
        return interrupted;
    }

    @Override
    void doInterrupt() {
        interrupted = true;
    }

    /**
     * Clears the interrupt status and returns the old value. If set, this
     * method clears this thread's interrupt status and the interrupt status of
     * the carrier thread.
     */
    @Override
    boolean getAndClearInterrupt() {
        assert Thread.currentCarrierThread() == fiber.carrierThread();
        boolean oldValue = interrupted;
        if (oldValue) {
            synchronized (interruptLock) {
                interrupted = false;
                fiber.carrierThread().getAndClearInterrupt();
            }
        }
        return oldValue;
    }

    @Override
    public StackTraceElement[] getStackTrace() {
        SecurityManager sm;
        if (this != Thread.currentThread()
            && ((sm = System.getSecurityManager()) != null)) {
            sm.checkPermission(SecurityConstants.GET_STACK_TRACE_PERMISSION);
        }
        return fiber.getStackTrace();
    }

    @Override
    public Thread.State getState() {
        return fiber.getState();
    }

    /**
     * The thread group for the shadow threads.
     */
    private static ThreadGroup shadowThreadGroup() {
        return AccessController.doPrivileged(new PrivilegedAction<ThreadGroup>() {
            public ThreadGroup run() {
                ThreadGroup group = Thread.currentCarrierThread().getThreadGroup();
                for (ThreadGroup p; (p = group.getParent()) != null; )
                    group = p;
                return new ThreadGroup(group, "ShadowThreads");
            }});
    }

    /**
     * Return an AccessControlContext that doesn't support any permissions.
     */
    private static AccessControlContext innocuousACC() {
        return new AccessControlContext(new ProtectionDomain[] {
            new ProtectionDomain(null, null)
        });
    }
}
