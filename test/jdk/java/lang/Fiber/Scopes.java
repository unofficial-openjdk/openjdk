/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/**
 * @test
 * @run testng Scopes
 * @summary Basic tests for java.lang.FiberScope
 */

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;
import static org.testng.Assert.*;

@Test
public class Scopes {

    // -- detached scope --

    // fibers scheduled in the detached scope can be cancelled
    public void testDetached1() {
        runInFiber(FiberScope.DETACHED, () -> {
            assertFalse(Fiber.cancelled());
            Fiber.current().map(Fiber::cancel);
            assertTrue(Fiber.cancelled());
        });
    }

    // the detached scope does not support a termination queue
    @Test(expectedExceptions = { UnsupportedOperationException.class })
    public void testDetached2() {
        FiberScope.DETACHED.terminationQueue();
    }

    // The hasRemaining method always returns false for the detached scope
    public void testDetached3() {
        FiberScope scope = FiberScope.DETACHED;
        assertFalse(scope.hasRemaining());
        var fiber = Fiber.schedule(scope, () -> LockSupport.park());
        try {
            assertFalse(scope.hasRemaining());
        } finally {
            LockSupport.unpark(fiber);
        }
    }

    // The fibers method always returns an empty stream for the detached scope
    public void testDetached4() {
        FiberScope scope = FiberScope.DETACHED;
        assertEmpty(scope.fibers());
        var fiber = Fiber.schedule(scope, () -> LockSupport.park());
        try {
            assertEmpty(scope.fibers());
        } finally {
            LockSupport.unpark(fiber);
        }
    }

    // the detached scope cannot be exited
    @Test(expectedExceptions = { IllegalCallerException.class })
    public void testDetached5() {
        FiberScope.DETACHED.close();
    }

    // -- cancellable scope --

    // close waits until all fibers scheduled in a scope to terminate
    public void testCancellable1() {
        var ref = new AtomicReference<Fiber<?>>();
        try (var scope = FiberScope.cancellable()) {
            var fiber = Fiber.schedule(scope, () -> {
                Thread.sleep(2000);
                return null;
            });
            ref.set(fiber);
        }
        Fiber<?> fiber = ref.get();
        assertFalse(fiber.isAlive());
    }

    // cancel a fiber in a cancellable scope
    public void testCancellable2() {
        runInFiber(() -> {
            try (var scope = FiberScope.cancellable()) {
                assertFalse(Fiber.cancelled());
                Fiber.current().map(Fiber::cancel);
                assertTrue(Fiber.cancelled());
            }
        });
    }

    // cancel a fiber in a cancellable scope propagates cancellation
    public void testCancellable3() {
        runInFiber(() -> {
            try (var scope = FiberScope.cancellable()) {
                var child = Fiber.schedule(scope, () -> {
                    try {
                        Thread.sleep(60*1000);
                        assertTrue(false);
                    } catch (InterruptedException e) {
                        assertTrue(Fiber.cancelled());
                    }
                    return null;
                });
                Fiber.current().map(Fiber::cancel);
                child.join();
            }
        });
    }

    // FiberScope::fibers includes an element for running fibers
    public void testCancellable4() {
        try (var scope = FiberScope.cancellable()) {
            assertEmpty(scope.fibers());
            var fiber = Fiber.schedule(scope, () -> LockSupport.park());
            try {
                assertTrue(scope.fibers().filter(f -> f == fiber).findAny().isPresent());
            } finally {
                LockSupport.unpark(fiber);
                fiber.join();
            }
            assertEmpty(scope.fibers());
        }
    }

    // test termination queue in cancellable scope
    public void testCancellable5() throws Exception {
        try (var scope = FiberScope.cancellable()) {
            var queue = scope.terminationQueue();
            assertFalse(scope.hasRemaining());
            assertTrue(queue.poll() == null);
            var child = Fiber.schedule(scope, () -> { });
            assertTrue(scope.hasRemaining());
            var fiber = queue.take();
            assertTrue(fiber == child);
            assertFalse(fiber.isAlive());
            assertFalse(scope.hasRemaining());
            assertTrue(queue.poll() == null);
        }
    }


    // -- non-cancellable scope --

    // close waits until all fibers scheduled in a scope to terminate
    public void testNonCancellable1() {
        var ref = new AtomicReference<Fiber<?>>();
        try (var scope = FiberScope.notCancellable()) {
            var fiber = Fiber.schedule(scope, () -> {
                Thread.sleep(2000);
                return null;
            });
            ref.set(fiber);
        }
        Fiber<?> fiber = ref.get();
        assertFalse(fiber.isAlive());
    }

    // cancel a fiber in a non-cancellable scope
    public void testNonCancellable2() {
        runInFiber(() -> {
            try (var scope = FiberScope.notCancellable()) {
                assertFalse(Fiber.cancelled());
                Fiber.current().map(Fiber::cancel);
                assertFalse(Fiber.cancelled());
            }
        });
    }

    // cancel a fiber in a non-cancellable scope
    public void testNonCancellable3() {
        runInFiber(() -> {
            try (var scope = FiberScope.notCancellable()) {
                var child = Fiber.schedule(scope, () -> {
                    Thread.sleep(1000);
                    assertFalse(Fiber.cancelled());
                    return null;
                });
                Fiber.current().map(Fiber::cancel);
                assertFalse(Fiber.cancelled());
                child.join();
            }
        });
    }

    // FiberScope::fibers includes an element for running fibers
    public void testNonCancellable4() {
        try (var scope = FiberScope.notCancellable()) {
            assertEmpty(scope.fibers());
            var fiber = Fiber.schedule(scope, () -> LockSupport.park());
            try {
                assertTrue(scope.fibers().filter(f -> f == fiber).findAny().isPresent());
            } finally {
                LockSupport.unpark(fiber);
                fiber.join();
            }
            assertEmpty(scope.fibers());
        }
    }

    // test termination queue in non-cancellable scope
    public void testNonCancellable5() throws Exception {
        try (var scope = FiberScope.cancellable()) {
            var queue = scope.terminationQueue();
            assertFalse(scope.hasRemaining());
            assertTrue(queue.poll() == null);
            var child = Fiber.schedule(scope, () -> { });
            assertTrue(scope.hasRemaining());
            var fiber = queue.take();
            assertTrue(fiber == child);
            assertFalse(fiber.isAlive());
            assertFalse(scope.hasRemaining());
            assertTrue(queue.poll() == null);
        }
    }


    // -- withDeadline --

    // close waits until all fibers scheduled in a scope to terminate
    public void testWithDeadline1() {
        var ref = new AtomicReference<Fiber<?>>();
        var deadline = Instant.now().plusSeconds(60);
        try (var scope = FiberScope.withDeadline(deadline)) {
            assertTrue(FiberScope.currentDeadline().orElseThrow().equals(deadline));
            var fiber = Fiber.schedule(scope, () -> {
                Thread.sleep(2000);
                return null;
            });
            ref.set(fiber);
        }
        Fiber<?> fiber = ref.get();
        assertFalse(fiber.isAlive());
    }

    // cancel a fiber in a cancellable scope
    public void testWithDeadline2() {
        runInFiber(() -> {
            var deadline = Instant.now().plusSeconds(60);
            try (var scope = FiberScope.withDeadline(deadline)) {
                assertTrue(FiberScope.currentDeadline().orElseThrow().equals(deadline));
                assertFalse(Fiber.cancelled());
                Fiber.current().map(Fiber::cancel);
                assertTrue(Fiber.cancelled());
            }
        });
    }

    // cancel a fiber in a cancellable scope propagates cancellation
    public void testWithDeadline3() {
        runInFiber(() -> {
            var deadline = Instant.now().plusSeconds(60);
            try (var scope = FiberScope.withDeadline(deadline)) {
                assertTrue(FiberScope.currentDeadline().orElseThrow().equals(deadline));
                var child = Fiber.schedule(scope, () -> {
                    try {
                        Thread.sleep(60*1000);
                        assertTrue(false);
                    } catch (InterruptedException e) {
                        assertTrue(Fiber.cancelled());
                    }
                    return null;
                });
                Fiber.current().map(Fiber::cancel);
                child.join();
            }
        });
    }

    // FiberScope::fibers includes an element for running fibers
    public void testWithDeadline4() {
        var deadline = Instant.now().plusSeconds(60);
        try (var scope = FiberScope.withDeadline(deadline)) {
            assertTrue(FiberScope.currentDeadline().orElseThrow().equals(deadline));
            assertEmpty(scope.fibers());
            var fiber = Fiber.schedule(scope, () -> LockSupport.park());
            try {
                assertTrue(scope.fibers().filter(f -> f == fiber).findAny().isPresent());
            } finally {
                LockSupport.unpark(fiber);
                fiber.join();
            }
            assertEmpty(scope.fibers());
        }
    }

    // test termination queue in cancellable scope
    public void testWithDeadline5() throws Exception {
        var deadline = Instant.now().plusSeconds(60);
        try (var scope = FiberScope.withDeadline(deadline)) {
            assertTrue(FiberScope.currentDeadline().orElseThrow().equals(deadline));
            var queue = scope.terminationQueue();
            assertFalse(scope.hasRemaining());
            assertTrue(queue.poll() == null);
            var child = Fiber.schedule(scope, () -> { });
            assertTrue(scope.hasRemaining());
            var fiber = queue.take();
            assertTrue(fiber == child);
            assertFalse(fiber.isAlive());
            assertFalse(scope.hasRemaining());
            assertTrue(queue.poll() == null);
        }
    }

    // deadline expires
    @Test(expectedExceptions = { CancellationException.class })
    public void testWithDeadline6() {
        var deadline = Instant.now().plusSeconds(1);
        try (var scope = FiberScope.withDeadline(deadline)) {
            Fiber.schedule(scope, () -> {
                Thread.sleep(60 * 1000);
                return null;
            });
        }
    }

    // deadline in the past
    @Test(expectedExceptions = { CancellationException.class })
    public void testWithDeadline7() {
        var deadline = Instant.now().minusSeconds(1);
        try (var scope = FiberScope.withDeadline(deadline)) { }
    }

    // deadline in the past
    @Test(expectedExceptions = { CancellationException.class })
    public void testWithDeadline8() {
        var deadline = Instant.now().minusSeconds(1);
        try (var scope = FiberScope.withDeadline(deadline)) {
            try {
                Fiber.schedule(() -> assertTrue(Fiber.cancelled())).join();
                assertTrue(false);
            } catch (CompletionException expected) { }
        }
    }


    // -- withTimeout --

    // TBD


    // -- nesting and cancellation propagtion --

    // exiting to an outer scope when cancelled should cancel fibers in the outer scope
    public void testNesting1() {
        runInFiber(() -> {
            try (var outer = FiberScope.cancellable()) {
                var child = Fiber.schedule(outer, () -> LockSupport.park());
                try (var inner = FiberScope.cancellable()) {
                    assertFalse(child.isCancelled());
                    Fiber.current().map(Fiber::cancel);
                    assertFalse(child.isCancelled());
                }
                assertTrue(child.isCancelled());
            }
        });
    }


    // currentDeadline with nested scopes
    public void testNesting2() {
        runInFiber(() -> {
            Instant deadline = Instant.now().plusSeconds(500);
            try (var outer = FiberScope.withDeadline(deadline)) {
                assertTrue(FiberScope.currentDeadline().orElseThrow().equals(deadline));
                Instant closerDeadline = deadline.minusSeconds(1);
                try (var inner = FiberScope.withDeadline(closerDeadline)) {
                    assertTrue(FiberScope.currentDeadline().orElseThrow().equals(closerDeadline));
                }
                assertTrue(FiberScope.currentDeadline().orElseThrow().equals(deadline));
            }
        });
    }

    public void testPropagate1() {
        runInFiber(() -> {
            try (var outer = FiberScope.cancellable()) {
                var ref = new AtomicReference<Fiber<?>>();
                Fiber<?> child = Fiber.schedule(outer, () -> {
                    try (var inner = FiberScope.cancellable()) {
                        ref.set(Fiber.schedule(inner, () -> LockSupport.park()));
                    }
                });
                Fiber<?> grandchild = waitForValue(ref);

                // cancel self
                Fiber.current().map(Fiber::cancel);

                // cancel status on both child and grandchild should be set
                try {
                    assertTrue(child.isCancelled());
                    assertTrue(grandchild.isCancelled());
                } finally {
                    LockSupport.unpark(grandchild);
                }
            }
        });
    }

    public void testPropagate2() {
        runInFiber(() -> {
            try (var outer = FiberScope.cancellable()) {
                var ref = new AtomicReference<Fiber<?>>();
                Fiber<?> child = Fiber.schedule(outer, () -> {
                    try (var inner = FiberScope.notCancellable()) {
                        ref.set(Fiber.schedule(inner, () -> LockSupport.park()));
                    }
                });
                Fiber<?> grandchild = waitForValue(ref);

                // cancel self
                Fiber.current().map(Fiber::cancel);

                // cancel status on child should be set
                try {
                    assertTrue(child.isCancelled());
                    assertFalse(grandchild.isCancelled());
                } finally {
                    LockSupport.unpark(grandchild);
                }
            }
        });
    }


    // -- nulls --

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull1() {
        FiberScope.withDeadline(null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull2() {
        FiberScope.withTimeout(null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull3() throws Exception {
        try (var scope = FiberScope.cancellable()) {
            scope.terminationQueue().poll(null);
        }
    }


    // -- supporting code --

    void runInFiber(Runnable task) {
        Fiber.schedule(task).join();
    }


    void runInFiber(FiberScope scope, Runnable task) {
        Fiber.schedule(scope, task).join();
    }

    <T> T waitForValue(AtomicReference<T> ref) {
        T obj;
        boolean interrupted = false;
        while ((obj = ref.get()) == null) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();
        return obj;
    }

    static void assertEmpty(Stream<?> stream) {
        assertFalse(stream.findAny().isPresent());
    }

}
