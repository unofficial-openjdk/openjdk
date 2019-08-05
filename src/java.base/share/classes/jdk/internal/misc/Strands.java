/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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

package jdk.internal.misc;

import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;

/**
 * This class defines static methods to support thread-implementation agnostic
 * code in java.base and to support the execution of code in the context of
 * fibers.
 */

public final class Strands {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();
    private Strands() { }

    /**
     * Returns the current strand.
     */
    public static Object currentStrand() {
        return JLA.currentStrand();
    }

    /**
     * Returns the current carrier thread.
     */
    public static Thread currentCarrierThread() {
        return JLA.currentCarrierThread();
    }

    /**
     * Interrupt the given strand.
     */
    public static void interrupt(Object strand) {
        JLA.interrupt(strand);
    }

    /**
     * Interrupt the current strand.
     */
    public static void interruptSelf() {
        interrupt(currentStrand());
    }

    /**
     * Tests whether the current strand has been interrupted. The <i>interrupted
     * status</i> of the strand is unaffected by this method.
     */
    public static boolean isInterrupted() {
        return JLA.isInterrupted();
    }

    /**
     * Tests whether the current strand has been interrupted.  The
     * <i>interrupted status</i> of the strand is cleared by this method.
     */
    public static boolean clearInterrupt() {
        return JLA.clearInterrupt();
    }

    /**
     * Disables the current fiber for scheduling purposes.
     */
    public static void parkFiber() {
        JLA.parkFiber();
    }

    /**
     * Disables the current fiber for scheduling purposes for up to the
     * given waiting time.
     */
    public static void parkFiber(long nanos) {
        JLA.parkFiber(nanos);
    }

    /**
     * Re-enables this fiber for scheduling.
     */
    public static void unparkFiber(Fiber<?> fiber) {
        JLA.unparkFiber(fiber);
    }

    /**
     * Returns the Fiber for the given shadow Thread object. Returns null if
     * the thread is not a shadow thread.
     */
    public static Fiber<?> getFiber(Thread thread) {
        return JLA.getFiber(thread);
    }

    /**
     * Returns the shadow thread for the given fiber or null if it does not
     * have a shadow thread.
     */
    public static Thread getShadowThread(Fiber<?> fiber) {
        return JLA.getShadowThread(fiber);
    }
}
