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

import jdk.internal.vm.annotation.ForceInline;

/**
 * A sequence of computer instructions executed sequentially, either a {@code
 * Thread} or a light weight {@code Fiber}.
 *
 * @apiNote This is a class rather than an interface for now. The constructor is
 * not public (or protected) so that it can't be extended outside of the java.lang
 * package.
 */

public class Strand {
    Strand() { }

    // thread or fiber locals, maintained by ThreadLocal
    ThreadLocal.ThreadLocalMap locals;

    /**
     * Returns the currently executing strand. If executed from a running fiber
     * then the {@link Fiber} object will be returned, otherwise the {@code
     * Thread} object.
     *
     * @return  the currently executing strand
     */
    @ForceInline
    public static Strand currentStrand() {
        Thread thread = Thread.currentCarrierThread();
        Fiber fiber = thread.getFiber();
        return (fiber != null) ? fiber : thread;
    }
}
