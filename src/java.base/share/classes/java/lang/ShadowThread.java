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
 */

class ShadowThread extends Thread {
    private static final ThreadGroup SHADOW_THREAD_GROUP = shadowThreadGroup();
    private static final AccessControlContext INNOCUOUS_ACC = innocuousACC();

    // the Fiber that this object shadows
    private final Fiber<?> fiber;

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

    @Override
    public void start() {
        throw new IllegalStateException();
    }

    @Override
    public void interrupt() {
        if (Thread.currentThread() != this) {
            checkAccess();
        }
        fiber.interrupt();
    }

    @Override
    public boolean isInterrupted() {
        return fiber.isInterrupted();
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
