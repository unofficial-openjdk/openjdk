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
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.lang.FiberScope.Option;
import static java.lang.FiberScope.Option.*;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class Scopes {

    // -- background scope --

    @Test(expectedExceptions = { IllegalCallerException.class })
    public void testBackground1() {
        FiberScope.background().close();
    }

    // Fibers scheduled in the background scope can be cancelled
    public void testBackground2() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            assertFalse(Fiber.cancelled());
            Fiber.current().map(Fiber::cancel);
            assertTrue(Fiber.cancelled());
        });
        fiber.join();
    }

    // Cancellation is not propagated to fibers in the background scope
    public void testBackground3() throws Exception {
        try (var scope = FiberScope.open(PROPAGATE_CANCEL)) {
            scope.schedule(() -> {
                var fiber = FiberScope.background().schedule(() -> LockSupport.park());
                Fiber.current().map(Fiber::cancel);
                assertFalse(fiber.isCancelled());
            }).join();
        }
    }

    // Cancellation is not propagated to fibers in the background scope
    public void testBackground4() throws Exception {
        runInFiber(() -> testBackground3());
    }

    // test that close waits
    public void testBasic1() throws Exception {
        Fiber<?> fiber;
        try (var scope = FiberScope.open()) {
            fiber = scope.schedule(() -> {
                TimeUnit.SECONDS.sleep(1);
                return null;
            });
        }
        assertFalse(fiber.isAlive());
        assertTrue(fiber.join() == null);
    }

    // test that close waits when owner thread is interrupted
    public void testBasic2() throws Exception {
        Fiber<?> fiber;
        try (var scope = FiberScope.open()) {
            fiber = scope.schedule(() -> {
                TimeUnit.SECONDS.sleep(1);
                return null;
            });
            Thread.currentThread().interrupt();
        } finally {
            assertTrue(Thread.interrupted());
        }
        assertFalse(fiber.isAlive());
        assertTrue(fiber.join() == null);
    }

    // test that close waits when owner fiber is interrupted
    public void testBasic3() throws Exception {
        runInFiber(() -> testBasic2());
    }

    // test that close waits when owner fiber is cancelled
    public void testBasic4() throws Exception {
        runInFiber(() -> {
            Fiber.current().map(Fiber::cancel);
            Fiber<?> fiber;
            try (var scope = FiberScope.open()) {
                fiber = scope.schedule(() -> {
                    TimeUnit.SECONDS.sleep(1);
                    return null;
                });
            }
            assertFalse(fiber.isAlive());
            Thread.interrupted(); // clear interrupt status
            assertTrue(fiber.join() == null);
        });
    }

    // test scheduling a fiber in an enclosing scope
    public void testBasic5() {
        try (var scope1 = FiberScope.open()) {
            try (var scope2 = FiberScope.open()) {
                scope1.schedule(() -> "foo");
            }
        }
    }

    // test scheduling a fiber when not in the scope
    @Test(expectedExceptions = { IllegalCallerException.class })
    public void testBasic6() {
        try (var scope1 = FiberScope.open()) {
            var scope2 = FiberScope.open();
            scope2.close();
            scope2.schedule(() -> "foo");
        }
    }


    // test that CANCEL_AT_CLOSE cancels all fibers at close
    public void testCancelAtClose1() {
        Fiber<?> fiber;
        try (var scope = FiberScope.open(CANCEL_AT_CLOSE)) {
            fiber = scope.schedule(() -> LockSupport.park());
        }
        assertFalse(fiber.isAlive());
        assertTrue(fiber.isCancelled());
    }

    public void testCancelAtClose2() throws Exception {
        runInFiber(() -> testCancelAtClose1());
    }

    // test Fiber.cancelled in a IGNORE_CANCEL scope
    public void testIgnoreCancel1() throws Exception {
        runInFiber(() -> {
            Fiber.current().map(Fiber::cancel);
            assertTrue(Fiber.cancelled());
            try (var scope = FiberScope.open(IGNORE_CANCEL)) {
                assertFalse(Fiber.cancelled());
            }
            assertTrue(Fiber.cancelled());
        });
    }

    // test Fiber.cancelled when enclosing scope is IGNORE_CANCEL
    public void testIgnoreCancel2() throws Exception {
        runInFiber(() -> {
            Fiber.current().map(Fiber::cancel);
            assertTrue(Fiber.cancelled());
            try (var scope1 = FiberScope.open(IGNORE_CANCEL)) {
                assertFalse(Fiber.cancelled());
                // IGNORE_CANCEL should be inherited
                try (var scope2 = FiberScope.open()) {
                    assertFalse(Fiber.cancelled());
                }
                assertFalse(Fiber.cancelled());
            }
            assertTrue(Fiber.cancelled());
        });
    }

    // test Fiber.cancelled when enclosing scope is IGNORE_CANCEL
    public void testIgnoreCancel3() throws Exception {
        runInFiber(() -> {
            Fiber.current().map(Fiber::cancel);
            assertTrue(Fiber.cancelled());
            try (var scope1 = FiberScope.open(IGNORE_CANCEL)) {
                assertFalse(Fiber.cancelled());
                Instant deadline = Instant.now().plusSeconds(2);
                try (var scope2 = FiberScope.open(deadline)) {
                    assertTrue(Fiber.cancelled());
                }
                assertFalse(Fiber.cancelled());
            }
            assertTrue(Fiber.cancelled());
        });
    }

    // test that cancellation is propagated to fibers scheduled in a scope
    public void testPropagateCancel1() throws Exception {
        runInFiber(() -> {
            try (var scope = FiberScope.open(PROPAGATE_CANCEL)) {
                var fiber = scope.schedule(() -> {
                    LockSupport.park();
                    assertTrue(Fiber.cancelled());
                });

                // give fiber time to park
                Thread.sleep(500);
                Fiber.current().map(Fiber::cancel);

                Thread.interrupted(); // clear interrupt status
                fiber.join();
            }
        });
    }

    // test scheduling a new fiber when the scope owner has been cancelled
    public void testPropagateCancel2() throws Exception {
        runInFiber(() -> {
            try (var scope = FiberScope.open(PROPAGATE_CANCEL)) {
                Fiber.current().map(Fiber::cancel);

                // fiber should be scheduled with the cancel (and interrupt) status set
                var fiber = scope.schedule(() -> LockSupport.park());
                assertTrue(fiber.isCancelled());

                Thread.interrupted(); // clear interrupt status
                fiber.join();
            }
        });
    }

    // check cancellation with a nested scope
    public void testPropagateCancel3() throws Exception {
        runInFiber(() -> {
            try (var scope1 = FiberScope.open(PROPAGATE_CANCEL)) {
                Fiber<?> top = Fiber.current().orElseThrow();

                var child = scope1.schedule(() -> {
                    try (var scope2 = FiberScope.open()) {
                        assertFalse(Fiber.cancelled());
                        top.cancel();
                        assertTrue(Fiber.cancelled());
                    }
                });
                joinUninterruptibly(child);
            }
        });
    }

    // check cancellation with a deeply nested scope
    public void testPropagateCancel4() throws Exception {
        runInFiber(() -> {
            try (var scope1 = FiberScope.open(PROPAGATE_CANCEL)) {
                Fiber<?> top = Fiber.current().orElseThrow();
                var child = scope1.schedule(() -> {
                    try (var scope2 = FiberScope.open()) {
                        var grandchild = scope2.schedule(() -> {
                            assertFalse(Fiber.cancelled());
                            top.cancel();
                            assertTrue(Fiber.cancelled());
                        });
                        joinUninterruptibly(grandchild);
                        return null;
                    }
                });
                joinUninterruptibly(child);
            }
        });
    }

    // -- deadlines --

    // thread owner
    public void testDeadline1() throws Exception {
        Instant deadline = Instant.now().plusSeconds(2);
        Fiber<?> fiber;
        try (var scope = FiberScope.open(deadline)) {
            fiber = scope.schedule(() -> {
                LockSupport.park();
                assertTrue(Fiber.cancelled());
            });
        }
        fiber.join();
    }

    // fiber owner
    public void testDeadline2() throws Exception {
        runInFiber(() -> testDeadline1());
    }

    // thread owner, outer scope has deadline, schedule fiber in inner scope
    public void testDeadline3() {
        Instant deadline = Instant.now().plusSeconds(2);
        Fiber<?> fiber;
        try (var scope1 = FiberScope.open(deadline)) {
            try (var scope2 = FiberScope.open()) {
                fiber = scope2.schedule(() -> {
                    LockSupport.park();
                    assertTrue(Fiber.cancelled());
                });
            }
        }
        joinUninterruptibly(fiber);
        Thread.interrupted();
    }

    // fiber owner, outer scope has deadline, schedule fiber in inner scope
    public void testDeadline4() throws Exception {
        runInFiber(() -> testDeadline3());
    }

    // thread owner, outer scope has deadline, inner scope has later deadline
    public void testDeadline5() {
        Instant start = Instant.now();
        Instant deadline1 = start.plusSeconds(2);
        Instant deadline2 = start.plusSeconds(60);
        Fiber<?> fiber;
        try (var scope1 = FiberScope.open(deadline1)) {
            try (var scope2 = FiberScope.open(deadline2)) {
                fiber = scope2.schedule(() -> {
                    LockSupport.park();
                    assertTrue(Fiber.cancelled());
                });
            }
        }
        joinUninterruptibly(fiber);
        Thread.interrupted();
        long seconds = Duration.between(start, Instant.now()).toSeconds();
        assertTrue(seconds >= 2 && seconds <= 5);
    }

    // fiber owner, outer scope has deadline, inner scope has later deadline
    public void testDeadline6() throws Exception {
        runInFiber(() -> testDeadline5());
    }

    // thread owner, outer scope has deadline, deeply nested inner scope has later deadline
    public void testDeadline7() {
        Instant start = Instant.now();
        Instant deadline1 = start.plusSeconds(2);
        Instant deadline2 = start.plusSeconds(60);
        Fiber<?> fiber;
        try (var scope1 = FiberScope.open(deadline1)) {
            try (var scope2 = FiberScope.open(/*do deadline*/)) {
                try (var scope3 = FiberScope.open(deadline2)) {
                    fiber = scope3.schedule(() -> {
                        LockSupport.park();
                        assertTrue(Fiber.cancelled());
                    });
                }
            }
        }
        joinUninterruptibly(fiber);
        Thread.interrupted();
        long seconds = Duration.between(start, Instant.now()).toSeconds();
        assertTrue(seconds >= 2 && seconds <= 5);
    }

    // fiber owner, outer scope has deadline, deeply nested inner scope has later deadline
    public void testDeadline8() throws Exception {
        runInFiber(() -> testDeadline7());
    }

    // deadline in the past
    public void testDeadline9() throws Exception {
        Instant deadline = Instant.now().minusMillis(1);
        Fiber<?> fiber;
        try (var scope = FiberScope.open(deadline)) {
            fiber = scope.schedule(() -> {
                LockSupport.park();
                assertTrue(Fiber.cancelled());
            });
        }
        joinUninterruptibly(fiber);
        Thread.interrupted();
    }

    // thread owner, outer scope has deadline, schedule fiber in inner ignore-cancel scope
    public void testDeadline10() throws Exception {
        Instant start = Instant.now();
        Instant deadline = start.plusSeconds(1);

        Fiber<?> fiber;
        try (var scope1 = FiberScope.open(deadline)) {
            try (var scope2 = FiberScope.open(IGNORE_CANCEL)) {
                fiber = scope2.schedule(() -> {
                    LockSupport.park();
                    assertFalse(Fiber.cancelled());
                });

                // unpark fiber after a delay
                Unparker.schedule(fiber, Duration.ofSeconds(3));
            }
        }
        joinUninterruptibly(fiber);
        Thread.interrupted();

        long seconds = Duration.between(start, Instant.now()).toSeconds();
        assertTrue(seconds >= 3);
    }

    // fiber owner, outer scope has deadline, schedule fiber in inner ignore-cancel scope
    public void testDeadline11() throws Exception {
        runInFiber(() -> testDeadline10());
    }

    // -- timeouts --

    // thread owner
    public void testTimeout1() throws Exception {
        Duration timeout = Duration.ofSeconds(2);
        Fiber<?> fiber;
        try (var scope = FiberScope.open(timeout)) {
            fiber = scope.schedule(() -> {
                LockSupport.park();
                assertTrue(Fiber.cancelled());
            });
        }
        fiber.join();
    }

    // fiber owner
    public void testTimeout2() throws Exception {
        runInFiber(() -> testTimeout1());
    }

    // zero timeout
    public void testTimeout3() throws Exception {
        Duration timeout = Duration.ofSeconds(0);
        Fiber<?> fiber;
        try (var scope = FiberScope.open(timeout)) {
            fiber = scope.schedule(() -> {
                LockSupport.park();
                assertTrue(Fiber.cancelled());
            });
        }
        joinUninterruptibly(fiber);
        Thread.interrupted();
    }

    public void testTimeout4() throws Exception {
        runInFiber(() -> testTimeout3());
    }


    // -- nulls and other exceptions --

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull1() {
        FiberScope.background().schedule((Runnable) null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull2() {
        FiberScope.background().schedule((Callable<Void>) null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull3() {
        Runnable task = () -> { };
        FiberScope.background().schedule((Executor)null, task);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull4() {
        Callable<String> task = () -> "foo";
        FiberScope.background().schedule((Executor)null, task);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull5() {
        ExecutorService scheduler = Executors.newCachedThreadPool();
        try {
            FiberScope.background().schedule(scheduler, (Runnable) null);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull6() {
        ExecutorService scheduler = Executors.newCachedThreadPool();
        try {
            FiberScope.background().schedule(scheduler, (Callable<Void>) null);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testBadOptions1() {
        FiberScope.open((Option) null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testBadOptions2() {
        FiberScope.open(new Option[] { null });
    }

    @Test(expectedExceptions = { IllegalArgumentException.class })
    public void testBadOptions3() {
        FiberScope.open(IGNORE_CANCEL, CANCEL_AT_CLOSE);
    }

    @Test(expectedExceptions = { IllegalArgumentException.class })
    public void testBadOptions4() {
        FiberScope.open(IGNORE_CANCEL, PROPAGATE_CANCEL);
    }


    // -- supporting code --

    interface ThrowableTask {
        void run() throws Exception;
    }

    void runInFiber(ThrowableTask task) throws InterruptedException {
        var fiber = FiberScope.background().schedule(() -> {
            task.run();
            return null;
        });
        joinUninterruptibly(fiber);
        Thread.interrupted(); // clear interrupt
    }

    <V> V joinUninterruptibly(Fiber<V> fiber) {
        boolean interrupted = false;
        V result;
        while (true) {
            try {
                result = fiber.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    static class Unparker implements Runnable {
        final Fiber<?> fiber;
        final Duration delay;

        Unparker(Fiber<?> fiber, Duration delay) {
            this.fiber = fiber;
            this.delay = delay;
        }

        static void schedule(Fiber<?> fiber, Duration delay) {
            Unparker task  = new Unparker(fiber, delay);
            new Thread(task).start();
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay.toMillis());
                LockSupport.unpark(fiber);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
