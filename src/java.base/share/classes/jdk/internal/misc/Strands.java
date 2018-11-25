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

package jdk.internal.misc;

import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;

/**
 * This class consists exclusively of static methods to support execution in
 * the context of a Fiber.
 */

public final class Strands {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();
    private Strands() { }

    /**
     * Returns the currently executing strand. If executed from a running fiber
     * then the {@link Fiber} object will be returned, otherwise the {@code
     * Thread} object.
     */
    public static Object currentStrand() {
        return JLA.currentStrand();
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
