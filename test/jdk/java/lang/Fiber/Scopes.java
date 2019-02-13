/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.FiberScope.TerminationQueue;
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

    public void testDetached1() throws Exception {
        var fiber = FiberScope.detached().schedule(() -> "foo");
        assertTrue(fiber.join().equals("foo"));
    }

    public void testDetached2() throws Exception {
        var queue = new TerminationQueue<String>();
        var fiber = FiberScope.detached().schedule(() -> "foo", queue);
        assertTrue(queue.take() == fiber);
        assertTrue(fiber.join().equals("foo"));
    }

    // the detached scope cannot be exited
    @Test(expectedExceptions = { IllegalCallerException.class })
    public void testDetached3() {
        FiberScope.detached().close();
    }

    // fibers scheduled in the detached scope can be cancelled
    public void testDetached4() {
        runInFiber(FiberScope.detached(), () -> {
            assertFalse(Fiber.cancelled());
            Fiber.current().map(Fiber::cancel);
            assertTrue(Fiber.cancelled());
        });
    }

    // The fibers method always returns an empty stream for the detached scope
    public void testDetached5() {
        FiberScope scope = FiberScope.detached();
        assertEmpty(scope.fibers());
        var fiber = scope.schedule(() -> LockSupport.park());
        try {
            assertEmpty(scope.fibers());
        } finally {
            LockSupport.unpark(fiber);
        }
    }


    // -- cancellable scope --

    public void testCancellable1() throws Exception {
        try (var scope = FiberScope.cancellable()) {
            var fiber = FiberScope.detached().schedule(() -> "foo");
            assertTrue(fiber.join().equals("foo"));
        }
    }

    public void testCancellable2() throws Exception {
        var queue = new TerminationQueue<String>();
        try (var scope = FiberScope.cancellable()) {
            var fiber = FiberScope.detached().schedule(() -> "foo", queue);
            assertTrue(queue.take() == fiber);
            assertFalse(fiber.isAlive());
            assertTrue(fiber.join().equals("foo"));
        }
    }

    // close waits until all fibers scheduled in a scope to terminate
    public void testCancellable3() {
        var ref = new AtomicReference<Fiber<?>>();
        try (var scope = FiberScope.cancellable()) {
            var fiber = scope.schedule(() -> {
                Thread.sleep(2000);
                return null;
            });
            ref.set(fiber);
        }
        Fiber<?> fiber = ref.get();
        assertFalse(fiber.isAlive());
    }

    // cancel a fiber in a cancellable scope
    public void testCancellable4() {
        runInFiber(() -> {
            try (var scope = FiberScope.cancellable()) {
                assertFalse(Fiber.cancelled());
                Fiber.current().map(Fiber::cancel);
                assertTrue(Fiber.cancelled());
            }
        });
    }

    // cancel a fiber in a cancellable scope propagates cancellation
    public void testCancellable5() {
        runInFiber(() -> {
            try (var scope = FiberScope.cancellable()) {
                var child = scope.schedule(() -> {
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
    public void testCancellable6() {
        try (var scope = FiberScope.cancellable()) {
            assertEmpty(scope.fibers());
            var fiber = scope.schedule(() -> LockSupport.park());
            try {
                assertTrue(scope.fibers().filter(f -> f == fiber).findAny().isPresent());
            } finally {
                LockSupport.unpark(fiber);
                fiber.join();
            }
            assertEmpty(scope.fibers());
        }
    }


    // -- non-cancellable scope --

    public void testNonCancellable1() throws Exception {
        try (var scope = FiberScope.notCancellable()) {
            var fiber = FiberScope.detached().schedule(() -> "foo");
            assertTrue(fiber.join().equals("foo"));
        }
    }

    public void testNonCancellable2() throws Exception {
        var queue = new TerminationQueue<String>();
        try (var scope = FiberScope.notCancellable()) {
            var fiber = FiberScope.detached().schedule(() -> "foo", queue);
            assertTrue(queue.take() == fiber);
            assertFalse(fiber.isAlive());
            assertTrue(fiber.join().equals("foo"));
        }
    }

    // close waits until all fibers scheduled in a scope to terminate
    public void testNonCancellable3() {
        var ref = new AtomicReference<Fiber<?>>();
        try (var scope = FiberScope.notCancellable()) {
            var fiber = scope.schedule(() -> {
                Thread.sleep(2000);
                return null;
            });
            ref.set(fiber);
        }
        Fiber<?> fiber = ref.get();
        assertFalse(fiber.isAlive());
    }

    // cancel a fiber in a non-cancellable scope
    public void testNonCancellable4() {
        runInFiber(() -> {
            try (var scope = FiberScope.notCancellable()) {
                assertFalse(Fiber.cancelled());
                Fiber.current().map(Fiber::cancel);
                assertFalse(Fiber.cancelled());
            }
        });
    }
    // cancel a fiber in a non-cancellable scope
    public void testNonCancellable5() {
        runInFiber(() -> {
            try (var scope = FiberScope.notCancellable()) {
                var child = scope.schedule(() -> {
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
    public void testNonCancellable6() {
        try (var scope = FiberScope.notCancellable()) {
            assertEmpty(scope.fibers());
            var fiber = scope.schedule(() -> LockSupport.park());
            try {
                assertTrue(scope.fibers().filter(f -> f == fiber).findAny().isPresent());
            } finally {
                LockSupport.unpark(fiber);
                fiber.join();
            }
            assertEmpty(scope.fibers());
        }
    }


    // -- withDeadline --

    // close waits until all fibers scheduled in a scope to terminate
    public void testWithDeadline1() {
        var ref = new AtomicReference<Fiber<?>>();
        var deadline = Instant.now().plusSeconds(60);
        try (var scope = FiberScope.withDeadline(deadline)) {
            assertTrue(FiberScope.currentDeadline().orElseThrow().equals(deadline));
            var fiber = scope.schedule(() -> {
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
                var child = scope.schedule(() -> {
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
            var fiber = scope.schedule(() -> LockSupport.park());
            try {
                assertTrue(scope.fibers().filter(f -> f == fiber).findAny().isPresent());
            } finally {
                LockSupport.unpark(fiber);
                fiber.join();
            }
            assertEmpty(scope.fibers());
        }
    }

    // deadline expires
    @Test(expectedExceptions = { CancellationException.class })
    public void testWithDeadline5() {
        var deadline = Instant.now().plusSeconds(1);
        try (var scope = FiberScope.withDeadline(deadline)) {
            scope.schedule(() -> {
                Thread.sleep(60 * 1000);
                return null;
            });
        }
    }

    // deadline in the past
    @Test(expectedExceptions = { CancellationException.class })
    public void testWithDeadline6() {
        var deadline = Instant.now().minusSeconds(1);
        try (var scope = FiberScope.withDeadline(deadline)) { }
    }

    // deadline in the past
    @Test(expectedExceptions = { CancellationException.class })
    public void testWithDeadline7() {
        var deadline = Instant.now().minusSeconds(1);
        try (var scope = FiberScope.withDeadline(deadline)) {
            scope.schedule(() -> assertTrue(Fiber.cancelled())).join();
        }
    }


    // -- withTimeout --

    // TBD


    // -- nesting and cancellation propagtion --

    // exiting to an outer scope when cancelled should cancel fibers in the outer scope
    public void testNesting1() {
        runInFiber(() -> {
            try (var outer = FiberScope.cancellable()) {
                var child = outer.schedule(() -> LockSupport.park());
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
                Fiber<?> child = outer.schedule(() -> {
                    try (var inner = FiberScope.cancellable()) {
                        ref.set(inner.schedule(() -> LockSupport.park()));
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
                Fiber<?> child = outer.schedule(() -> {
                    try (var inner = FiberScope.notCancellable()) {
                        ref.set(inner.schedule(() -> LockSupport.park()));
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
    public void testNull1() throws Exception {
        try (var scope = FiberScope.cancellable()) {
            scope.schedule((Runnable) null);
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull2() throws Exception {
        try (var scope = FiberScope.cancellable()) {
            var queue = new TerminationQueue<Object>();
            scope.schedule((Runnable) null, queue);
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull3() throws Exception {
        try (var scope = FiberScope.cancellable()) {
            scope.schedule(() -> { }, (TerminationQueue) null);
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull4() throws Exception {
        try (var scope = FiberScope.cancellable()) {
            scope.schedule((Callable<?>) null);
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull5() throws Exception {
        try (var scope = FiberScope.cancellable()) {
            var queue = new TerminationQueue<Object>();
            scope.schedule((Callable<?>) null, queue);
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull6() throws Exception {
        try (var scope = FiberScope.cancellable()) {
            scope.schedule(() -> "foo", (TerminationQueue) null);
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull7() throws Exception {
        try (var scope = FiberScope.cancellable()) {
            scope.schedule((Executor) null, () -> { });
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull8() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (var scope = FiberScope.cancellable()) {
            scope.schedule(pool, (Runnable) null);
        } finally {
            pool.shutdown();
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull9() throws Exception {
        try (var scope = FiberScope.cancellable()) {
            var queue = new TerminationQueue<Object>();
            scope.schedule((Executor) null, () -> { }, queue);
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull10() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (var scope = FiberScope.cancellable()) {
            var queue = new TerminationQueue<Object>();
            scope.schedule(pool, (Runnable) null, queue);
        } finally {
            pool.shutdown();
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull11() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (var scope = FiberScope.cancellable()) {
            scope.schedule(pool, () -> { }, (TerminationQueue<Object>) null);
        } finally {
            pool.shutdown();
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull12() throws Exception {
        try (var scope = FiberScope.cancellable()) {
            scope.schedule((Executor) null, () -> "foo");
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull13() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (var scope = FiberScope.cancellable()) {
            scope.schedule(pool, (Callable<?>) null);
        } finally {
            pool.shutdown();
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull14() throws Exception {
        try (var scope = FiberScope.cancellable()) {
            var queue = new TerminationQueue<Object>();
            scope.schedule((Executor) null, () -> "foo", queue);
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull15() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (var scope = FiberScope.cancellable()) {
            var queue = new TerminationQueue<Object>();
            scope.schedule(pool, (Callable<?>) null, queue);
        } finally {
            pool.shutdown();
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull16() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (var scope = FiberScope.cancellable()) {
            scope.schedule(pool, () -> "foo", (TerminationQueue<Object>) null);
        } finally {
            pool.shutdown();
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull17() {
        FiberScope.withDeadline(null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull18() {
        FiberScope.withTimeout(null);
    }

    // -- supporting code --

    void runInFiber(Runnable task) {
        Fiber.schedule(task).join();
    }


    void runInFiber(FiberScope scope, Runnable task) {
        scope.schedule(task).join();
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
