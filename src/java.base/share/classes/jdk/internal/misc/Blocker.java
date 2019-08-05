/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * Supports the execution of tasks with a ManagedBlocker.
 */

public class Blocker {
    private Blocker() { }

    private static final Unsafe U = Unsafe.getUnsafe();

    public interface BlockingRunnable<X extends Throwable> {
        void run() throws X;
    }

    public interface BlockingCallable<V, X extends Throwable> {
        V call() throws X;
    }

    /**
     * Runs the given task with a ManagedBlocker when invoked in the context of
     * a fiber and the carrier thread is a ForkJoinWorkerThread.
     */
    public static <X extends Throwable> void run(BlockingRunnable<X> task) throws X {
        BlockingCallable<Void, X> wrapper = () -> {
            task.run();
            return null;
        };
        run(wrapper);
    }

    /**
     * Runs the given task with a ManagedBlocker when invoked in the context of
     * a fiber and the carrier thread is a ForkJoinWorkerThread.
     */
    public static <V, X extends Throwable> V run(BlockingCallable<V, X> task) throws X {
        if (!(Strands.currentCarrierThread() instanceof ForkJoinWorkerThread)
                || Fiber.current().isEmpty()) {
            return task.call();
        }

        var blocker = new ForkJoinPool.ManagedBlocker() {
            V result;
            Throwable exception;
            boolean done;
            @Override
            public boolean block() {
                if (!done) {
                    try {
                        result = task.call();
                    } catch (Throwable e) {
                        exception = e;
                    } finally {
                        done = true;
                    }
                }
                return true;
            }
            @Override
            public boolean isReleasable() {
                return done;
            }
        };

        try {
            ForkJoinPool.managedBlock(blocker);
        } catch (InterruptedException e) {
            U.throwException(e);
        }

        Throwable e = blocker.exception;
        if (e != null) {
            U.throwException(e);
        }
        return blocker.result;
    }
}
