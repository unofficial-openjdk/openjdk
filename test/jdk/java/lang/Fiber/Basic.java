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
 * @run testng Basic
 * @summary Basic tests for java.lang.Fiber
 */

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.stream.Stream;
import java.nio.channels.Selector;

import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;
import static org.testng.Assert.*;

@Test
public class Basic {

    static final Runnable DO_NOTHING = () -> { };

    // -- basic tests ---

    public void testExecute1() throws Exception {
        var executed = new AtomicBoolean();
        FiberScope.background().schedule(() -> executed.set(true)).join();
        assertTrue(executed.get());
    }

    public void testExecute2() throws Exception {
        String s = FiberScope.background().schedule(() -> "foo").join();
        assertTrue("foo".equals(s));
    }

    // throw uncaught exception
    public void testUncaughtException1() throws Exception {
        class FooException extends RuntimeException { }
        var fiber = FiberScope.background().schedule(() -> {
            throw new FooException();
        });
        try {
            fiber.join();
            assertTrue(false);
        } catch (CompletionException e) {
            assertTrue(e.getCause() instanceof FooException);
        }
    }

    // throw uncaught error
    public void testUncaughtError1() throws Exception {
        class FooError extends Error { }
        var fiber = FiberScope.background().schedule(() -> {
            throw new FooError();
        });
        try {
            fiber.join();
            assertTrue(false);
        } catch (CompletionException e) {
            assertTrue(e.getCause() instanceof FooError);
        }
    }


    // -- parking --

    // fiber parks, unparked by thread
    public void testPark1() throws Exception {
        var fiber = FiberScope.background().schedule(() -> LockSupport.park());
        Thread.sleep(1000); // give time for fiber to park
        LockSupport.unpark(fiber);
        fiber.join();
    }

    // fiber parks, unparked by another fiber
    public void testPark2() throws Exception {
        var fiber1 = FiberScope.background().schedule(() -> LockSupport.park());
        Thread.sleep(1000); // give time for fiber to park
        var fiber2 = FiberScope.background().schedule(() -> LockSupport.unpark(fiber1));
        fiber1.join();
        fiber2.join();
    }

    // park while holding monitor
    public void testPark3() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            var lock = new Object();
            synchronized (lock) {
                LockSupport.park();
            }
        });
        Thread.sleep(1000); // give time for fiber to park
        LockSupport.unpark(fiber);
        fiber.join();
    }

    // park with native frame on the stack
    public void testPark4() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            try {
                Method m = Basic.class.getDeclaredMethod("doPark");
                m.invoke(null);
            } catch (Exception e) {
                assertTrue(false);
            }
        });
        Thread.sleep(1000); // give time for fiber to park
        LockSupport.unpark(fiber);
        fiber.join();
    }
    static void doPark() {
        LockSupport.park();
    }

    // unpark before park
    public void testPark5() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            LockSupport.unpark(Fiber.current().orElseThrow());
            LockSupport.park();
        });
        fiber.join();
    }

    // 2 x unpark before park
    public void testPark6() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Fiber me = Fiber.current().orElseThrow();
            LockSupport.unpark(me);
            LockSupport.unpark(me);
            LockSupport.park();
            LockSupport.park();  // should park
        });
        Thread.sleep(1000); // give time for fiber to park
        LockSupport.unpark(fiber);
        fiber.join();
    }

    // 2 x park
    public void testPark7() throws Exception {
        var fiber =FiberScope. background().schedule(() -> {
            LockSupport.park();
            LockSupport.park();
        });

        Thread.sleep(1000); // give time for fiber to park

        // unpark, fiber should park again
        LockSupport.unpark(fiber);
        Thread.sleep(1000);

        // let it terminate
        LockSupport.unpark(fiber);
        fiber.join();
    }

    // interrupt before park
    public void testPark8() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Thread t = Thread.currentThread();
            t.interrupt();
            LockSupport.park();
            assertTrue(t.isInterrupted());
        });
        fiber.join();
    }

    // interrupt while parked
    public void testPark9() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Thread t = Thread.currentThread();
            Interrupter.schedule(t, 1000);
            LockSupport.park();
            assertTrue(t.isInterrupted());
        });
        fiber.join();
    }

    // interrupt before park (pinned park)
    public void testPark10() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Thread t = Thread.currentThread();
            t.interrupt();
            Object lock = new Object();
            synchronized (lock) {
                LockSupport.park();
            }
            assertTrue(t.isInterrupted());
        });
        fiber.join();
    }

    // interrupt while parked (pinned park)
    public void testPark11() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Thread t = Thread.currentThread();
            Interrupter.schedule(t, 1000);
            Object lock = new Object();
            synchronized (lock) {
                LockSupport.park();
            }
            assertTrue(t.isInterrupted());
        });
        fiber.join();
    }

    // parkNanos(-1) completes immediately
    public void testParkNanos1() throws Exception {
        var fiber = FiberScope.background().schedule(() -> LockSupport.parkNanos(-1));
        fiber.join();
    }

    // parkNanos(0) completes immediately
    public void testParkNanos2() throws Exception {
        var fiber = FiberScope.background().schedule(() -> LockSupport.parkNanos(0));
        fiber.join();
    }

    // parkNanos(1000ms) completes quickly
    public void testParkNanos3() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            // park for 1000ms
            long nanos = TimeUnit.NANOSECONDS.convert(1000, TimeUnit.MILLISECONDS);
            long start = System.nanoTime();
            LockSupport.parkNanos(nanos);

            // check that fiber parks for >= 900ms
            long elapsed = TimeUnit.MILLISECONDS.convert(System.nanoTime() - start,
                                                         TimeUnit.NANOSECONDS);
            assertTrue(elapsed >= 900);
        });
        fiber.join();
    }

    // fiber parks, unparked by thread
    public void testParkNanos4() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            long nanos = TimeUnit.NANOSECONDS.convert(30, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
        });
        Thread.sleep(1000); // give time for fiber to park
        LockSupport.unpark(fiber);
        fiber.join();
    }

    // fiber parks, unparked by another fiber
    public void testParkNanos5() throws Exception {
        var fiber1 = FiberScope.background().schedule(() -> {
            long nanos = TimeUnit.NANOSECONDS.convert(30, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
        });
        Thread.sleep(1000);  // give time for fiber to park
        var fiber2 = FiberScope.background().schedule(() -> {
            LockSupport.unpark(fiber1);
        });
        fiber1.join();
        fiber2.join();
    }

    // unpark before parkNanos
    public void testParkNanos6() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            LockSupport.unpark(Fiber.current().orElseThrow());
            long nanos = TimeUnit.NANOSECONDS.convert(30, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
        });
        fiber.join();
    }

    // unpark before parkNanos(0), should consume permit
    public void testParkNanos7() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            LockSupport.unpark(Fiber.current().orElseThrow());
            LockSupport.parkNanos(0);
            LockSupport.park(); // should block
        });
        fiber.awaitTermination(Duration.ofSeconds(2));
        LockSupport.unpark(fiber);
        fiber.join();
    }


    // -- join --

    // join short lived fiber
    public void testJoin1() throws Exception {
        String s = FiberScope.background().schedule(() -> "foo").join();
        assertEquals(s, "foo");
    }

    // join long lived fiber
    public void testJoin2() throws Exception {
        Fiber<String> fiber = FiberScope.background().schedule(() -> {
            try {
                Thread.sleep(2*1000);
            } catch (InterruptedException e) { }
            return "foo";
        });
        String s = fiber.join();
        assertEquals(s, "foo");
    }

    // join after terminated
    public void testJoin3() throws Exception {
        Fiber<String> fiber = FiberScope.background().schedule(() -> "foo");
        while (fiber.isAlive()) {
            Thread.sleep(10);
        }
        String s = fiber.join();
        assertEquals(s, "foo");
    }

    // thread interrupted before join
    public void testJoin4() throws Exception {
        Fiber<?> fiber = FiberScope.background().schedule(() -> LockSupport.park());
        Thread.currentThread().interrupt();
        try {
            fiber.join();
            assertTrue(false);
        } catch (InterruptedException e) {
            assertFalse(Thread.interrupted());
        } finally {
            LockSupport.unpark(fiber);
        }
    }

    // thread interrupted in join
    public void testJoin5() throws Exception {
        Fiber<?> fiber = FiberScope.background().schedule(() -> LockSupport.park());
        Interrupter.schedule(Thread.currentThread(), 500);
        try {
            fiber.join();
            assertTrue(false);
        } catch (InterruptedException e) {
            assertFalse(Thread.interrupted());
        } finally {
            LockSupport.unpark(fiber);
        }
    }

    // fiber interrupted before join
    public void testJoin6() throws Exception {
        var fiber1 = FiberScope.background().schedule(() -> LockSupport.park());
        var fiber2 = FiberScope.background().schedule(() -> {
            Thread.currentThread().interrupt();
            try {
                fiber1.join();
                assertTrue(false);
            } catch (InterruptedException e) {
                assertFalse(Thread.interrupted());
            }
        });
        try {
            fiber2.join();
        } finally {
            LockSupport.unpark(fiber1);
        }
    }


    // -- awaitTermination --


    public void testAwaitTermination1() throws Exception {
        var fiber = FiberScope.background().schedule(() -> "foo");
        boolean terminated = fiber.awaitTermination(Duration.ofSeconds(10));
        assertTrue(terminated);
    }

    public void testAwaitTermination2() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Thread.sleep(1000);
            return null;
        });
        boolean terminated = fiber.awaitTermination(Duration.ofSeconds(10));
        assertTrue(terminated);
    }

    public void testAwaitTermination3() throws Exception {
        var fiber = FiberScope.background().schedule(() -> LockSupport.park());
        boolean terminated = fiber.awaitTermination(Duration.ofSeconds(1));
        try {
            assertFalse(terminated);
        } finally {
            LockSupport.unpark(fiber);
        }
    }

    // awaitTermination after terminated
    public void testAwaitTermination4() throws Exception {
        var fiber = FiberScope.background().schedule(() -> "foo");
        fiber.join();
        boolean terminated = fiber.awaitTermination(Duration.ofSeconds(10));
        assertTrue(terminated);
    }

    // thread interrupted before awaitTermination
    public void testAwaitTermination5() throws Exception {
        Fiber<?> fiber = FiberScope.background().schedule(() -> LockSupport.park());
        Thread.currentThread().interrupt();
        try {
            fiber.awaitTermination(Duration.ofSeconds(10));
            assertTrue(false);
        } catch (InterruptedException e) {
            assertFalse(Thread.interrupted());
        } finally {
            LockSupport.unpark(fiber);
        }
    }

    // thread interrupted in awaitTermination
    public void testAwaitTermination6() throws Exception {
        Fiber<?> fiber = FiberScope.background().schedule(() -> LockSupport.park());
        Interrupter.schedule(Thread.currentThread(), 500);
        try {
            fiber.awaitTermination(Duration.ofSeconds(10));
            assertTrue(false);
        } catch (InterruptedException e) {
            assertFalse(Thread.interrupted());
        } finally {
            LockSupport.unpark(fiber);
        }
    }

    // fiber interrupted before awaitTermination
    public void testAwaitTermination7() throws Exception {
        var fiber1 = FiberScope.background().schedule(() -> LockSupport.park());
        var fiber2 = FiberScope.background().schedule(() -> {
            Thread.currentThread().interrupt();
            try {
                fiber1.awaitTermination(Duration.ofSeconds(10));
                assertTrue(false);
            } catch (InterruptedException e) {
                assertFalse(Thread.interrupted());
            }
        });
        try {
            fiber2.join();
        } finally {
            LockSupport.unpark(fiber1);
        }
    }

    // fiber interrupted in timed awaitTermination
    public void testAwaitTermination8() throws Exception {
        var fiber1 = FiberScope.background().schedule(() -> LockSupport.park());
        var fiber2 = FiberScope.background().schedule(() -> {
            Interrupter.schedule(Thread.currentThread(), 500);
            try {
                fiber1.awaitTermination(Duration.ofSeconds(10));
                assertTrue(false);
            } catch (InterruptedException e) {
                assertFalse(Thread.interrupted());
            }
        });
        try {
            fiber2.join();
        } finally {
            LockSupport.unpark(fiber1);
        }
    }

    // awaitTermination with zero duration
    public void testAwaitTermination9() throws Exception {
        var fiber = FiberScope.background().schedule(() -> "foo");
        fiber.join();
        boolean terminated = fiber.awaitTermination(Duration.ofSeconds(0));
        assertTrue(terminated);
    }

    public void testAwaitTermination10() throws Exception {
        var fiber = FiberScope.background().schedule(() -> LockSupport.park());
        boolean terminated = fiber.awaitTermination(Duration.ofSeconds(0));
        try {
            assertFalse(terminated);
        } finally {
            LockSupport.unpark(fiber);
        }
    }

    public void testAwaitTermination11() throws Exception {
        var fiber = FiberScope.background().schedule(() -> "foo");
        fiber.join();
        Thread.currentThread().interrupt();
        try {
            fiber.awaitTermination(Duration.ofSeconds(0));
            assertTrue(false);
        } catch (InterruptedException e) {
            assertFalse(Thread.interrupted());
        }
    }

    public void testAwaitTermination12() throws Exception {
        var fiber = FiberScope.background().schedule(() -> LockSupport.park());
        Thread.currentThread().interrupt();
        try {
            fiber.awaitTermination(Duration.ofSeconds(0));
            assertTrue(false);
        } catch (InterruptedException e) {
            LockSupport.unpark(fiber);
            assertFalse(Thread.interrupted());
        }
    }

    public void testAwaitTermination13() throws Exception {
        var fiber = FiberScope.background().schedule(() -> "foo");
        fiber.join();

        FiberScope.background().schedule(() -> {
            Thread.currentThread().interrupt();
            try {
                fiber.awaitTermination(Duration.ofSeconds(0));
                assertTrue(false);
            } catch (InterruptedException e) {
                assertFalse(Thread.interrupted());
            }
        }).join();
    }

    public void testAwaitTermination14() throws Exception {
        var fiber = FiberScope.background().schedule(() -> LockSupport.park());
        try {
            FiberScope.background().schedule(() -> {
                Thread.currentThread().interrupt();
                try {
                    fiber.awaitTermination(Duration.ofSeconds(0));
                    assertTrue(false);
                } catch (InterruptedException e) {
                    assertFalse(Thread.interrupted());
                }
            }).join();
        } finally {
            LockSupport.unpark(fiber);
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testAwaitTermination15() throws Exception {
        var fiber = FiberScope.background().schedule(() -> "foo");
        fiber.awaitTermination(null);
    }


    // -- Fiber.current --

    public void testCurrent1() throws Exception {
        assertTrue(Fiber.current().isEmpty());
    }

    public void testCurrent2() throws Exception {
        var fiber = FiberScope.background().schedule(() -> Fiber.current());
        var current = fiber.join().orElseThrow();
        assertTrue(current == fiber);
    }


    // -- cancellation --

    // sets cancel status
    public void testCancel1() throws Exception {
        FiberScope.background().schedule(() -> {
            var fiber = Fiber.current().orElseThrow();
            assertFalse(fiber.isCancelled());
            assertTrue(fiber.cancel());
            assertTrue(fiber.isCancelled());
            assertFalse(fiber.cancel());    // already set
            assertTrue(fiber.isCancelled());
        }).join();
    }

    // unparks and interrupts
    public void testCancel2() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            LockSupport.park();
            assertTrue(Thread.currentThread().isInterrupted());
        });
        Thread.sleep(50);
        assertTrue(fiber.cancel());
        fiber.join();
    }

    // cancel Future
    public void testCancel3() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            LockSupport.park();
        });
        var future = fiber.toFuture();
        fiber.cancel();
        assertTrue(future.isCancelled());
        try {
            future.join();
            assertTrue(false);
        } catch (CancellationException e) {
            // expected
        }
    }


    // -- isAlive --

    public void testIsAlive() throws Exception {
        var fiber = FiberScope.background().schedule(() -> LockSupport.park());
        assertTrue(fiber.isAlive());
        LockSupport.unpark(fiber);
        fiber.join();
        assertFalse(fiber.isAlive());
    }


    // -- toFuture --

    public void testToFuture1() throws Exception {
        Future<String> result = FiberScope.background().schedule(() -> "foo").toFuture();
        String s = result.get();
        assertEquals(s, "foo");
    }

    public void testToFuture2() throws Exception {
        Future<?> future = FiberScope.background().schedule(() -> {
            throw new RuntimeException();
        }).toFuture();
        try {
            future.get();
            assertTrue(false);
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof RuntimeException);
        }
    }

    // Future.cancel
    public void testFuture3() throws Exception {
        Fiber<Boolean> fiber = FiberScope.background().schedule(() -> {
            LockSupport.park();
            return Thread.currentThread().isInterrupted();
        });
        Future<?> result = fiber.toFuture();

        // sets cancel status and unpark fiber
        boolean x = result.cancel(true);
        System.out.println("x=" + x);

        try {
            result.get();
            assertTrue(false);
        } catch (CancellationException expected) { }

        // fiber returns interrupt status
        assertTrue(fiber.join() == true);
    }

    // Fiber.toFuture should return the same object is called several times
    public void testToFuture4() {
        var fiber = FiberScope.background().schedule(() -> "foo");
        var result1 = fiber.toFuture();
        var result2 = fiber.toFuture();
        assertTrue(result1 == result2);
    }


    // -- Thread.currentThread --

    //  Thread.currentThread before/after park
    public void testCurrentThread1() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Thread t = Thread.currentThread();  // before park
            LockSupport.park();
            assertTrue(Thread.currentThread() == t);  // after park
            return null;
        });
        Thread.sleep(1000); // give time for fiber to park
        LockSupport.unpark(fiber);
        fiber.join();
    }

    //  Thread.currentThread before/after synchronized block
    public void testCurrentThread2() throws Exception {
        var lock = new Object();
        Fiber<?> fiber;
        synchronized (lock) {
            fiber = FiberScope.background().schedule(() -> {
                Thread t = Thread.currentThread();  // before synchronized
                synchronized (lock) { }
                assertTrue(Thread.currentThread() == t);  // after synchronized
                return null;
            });
            Thread.sleep(200); // give time for fiber to block
        }
        fiber.join();
    }

    //  Thread.currentThread before/after lock
    public void testCurrentThread3() throws Exception {
        var lock = new ReentrantLock();
        Fiber<?> fiber;
        lock.lock();
        try {
            fiber = FiberScope.background().schedule(() -> {
                Thread t = Thread.currentThread();  // before lock
                lock.lock();
                lock.unlock();
                assertTrue(Thread.currentThread() == t);  // after lock
                return null;
            });
            Thread.sleep(200); // give time for fiber to block
        } finally {
            lock.unlock();
        }
        fiber.join();
    }


    // -- Thread.start/stop/suspend/resume --

    public void testThreadStart() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Thread t = Thread.currentThread();
            try {
                t.start();
                assertTrue(false);
            } catch (IllegalStateException e) {
                // expected
            }
        });
        fiber.join();
    }

    public void testThreadStop() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Thread t = Thread.currentThread();
            try {
                t.stop();
                assertTrue(false);
            } catch (UnsupportedOperationException e) {
                // expected
            }
        });
        fiber.join();
    }

    public void testThreadSuspend() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Thread t = Thread.currentThread();
            try {
                t.suspend();
                assertTrue(false);
            } catch (UnsupportedOperationException e) {
                // expected
            }
        });
        fiber.join();
    }

    public void testThreadResume() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Thread t = Thread.currentThread();
            try {
                t.resume();
                assertTrue(false);
            } catch (UnsupportedOperationException e) {
                // expected
            }
        });
        fiber.join();
    }


    // -- Thread.join --

    // thread invokes join to wait for fiber to terminate
    public void testThreadJoin1() throws Exception {
        Thread t = FiberScope.background().schedule(() -> Thread.currentThread()).join();
        t.join();
    }

    // fiber invokes join to wait for another fiber to terminate
    public void testThreadJoin2() throws Exception {
        Thread t = FiberScope.background().schedule(() -> Thread.currentThread()).join();
        var fiber2 = FiberScope.background().schedule(() -> {
            t.join();
            return null;
        });
        fiber2.join();
    }

    // thread invokes join(millis) to wait for fiber to terminate
    public void testThreadJoin3() throws Exception {
        Thread t = FiberScope.background().schedule(() -> Thread.currentThread()).join();
        t.join(10*1000);
    }

    // fiber invokes join(millis) to wait for another fiber to terminate
    public void testThreadJoin4() throws Exception {
        Thread t = FiberScope.background().schedule(() -> Thread.currentThread()).join();
        var fiber2 = FiberScope.background().schedule(() -> {
            t.join(10*1000);
            return null;
        });
        fiber2.join();
    }

    // thread invokes join(millis), fiber does not terminate
    public void testThreadJoin5() throws Exception {
        var ref = new AtomicReference<Thread>();
        var fiber = FiberScope.background().schedule(() -> {
            ref.set(Thread.currentThread());
            LockSupport.park();
        });
        Thread t = waitForValue(ref);
        try {
            t.join(2*1000);
        } finally {
            LockSupport.unpark(fiber);
        }
    }

    // fiber invokes join(millis) to wait for other fiber that does not terminate
    public void testThreadJoin6() throws Exception {
        var ref = new AtomicReference<Thread>();
        var fiber1 = FiberScope.background().schedule(() -> {
            ref.set(Thread.currentThread());
            LockSupport.park();
        });
        var fiber2 = FiberScope.background().schedule(() -> {
            Thread t = waitForValue(ref);
            t.join(2*1000);
            return null;
        });
        try {
            fiber2.join();
        } finally {
            LockSupport.unpark(fiber1);
        }
    }

    // interrupt before Thread.join main thread
    public void testThreadJoin7() throws Exception {
        Thread mainThread = Thread.currentThread();
        var fiber = FiberScope.background().schedule(() -> {
            Thread t = Thread.currentThread();
            t.interrupt();
            try {
                mainThread.join();
                assertTrue(false);
            } catch (InterruptedException e) {
                // interrupt status should be cleared
                assertFalse(t.isInterrupted());
            }
            return null;
        });
        fiber.join();
    }

    // interrupt before Thread.join current thread
    public void testThreadJoin8() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Thread t = Thread.currentThread();
            t.interrupt();
            try {
                t.join();
                assertTrue(false);
            } catch (InterruptedException e) {
                // interrupt status should be cleared
                assertFalse(t.isInterrupted());
            }
            return null;
        });
        fiber.join();
    }

    // interrupt while in Thread.join
    public void testThreadJoin9() throws Exception {
        Thread mainThread = Thread.currentThread();
        var fiber = FiberScope.background().schedule(() -> {
            Thread t = Thread.currentThread();
            Interrupter.schedule(t, 1000);
            try {
                mainThread.join();
                assertTrue(false);
            } catch (InterruptedException e) {
                // interrupt status should be cleared
                assertFalse(t.isInterrupted());
            }
            return null;
        });
        fiber.join();
    }

    // interrupt while in Thread.join current thread
    public void testThreadJoin10() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Thread t = Thread.currentThread();
            try {
                Interrupter.schedule(t, 1000);
                t.join();
                assertTrue(false);
            } catch (InterruptedException e) {
                // interrupt status should be cleared
                assertFalse(Thread.currentThread().isInterrupted());
            }
            return null;
        });
        fiber.join();
    }

    // join with negative timeout
    @Test(expectedExceptions = { IllegalArgumentException.class })
    public void testThreadJoin11() throws Exception {
        Thread t = FiberScope.background().schedule(() -> Thread.currentThread()).join();
        t.join(-1);
    }


    // -- Thread.yield --

    public void testThreadYield1() throws Exception {
        var list = new CopyOnWriteArrayList<String>();
        ExecutorService scheduler = Executors.newFixedThreadPool(1);
        try {
            FiberScope.background().schedule(scheduler, () -> {
                list.add("A");
                Fiber<?> child = FiberScope.background().schedule(scheduler, () -> {
                    list.add("B");
                    Thread.yield();
                    list.add("B");
                });
                Thread.yield();
                list.add("A");
                child.join();
                return null;
            }).join();
        } finally {
            scheduler.shutdown();
        }
        assertEquals(list, List.of("A", "B", "A", "B"));
    }

    public void testThreadYield2() throws Exception {
        var list = new CopyOnWriteArrayList<String>();
        ExecutorService scheduler = Executors.newFixedThreadPool(1);
        try {
            FiberScope.background().schedule(scheduler, () -> {
                list.add("A");
                Fiber<?> child = FiberScope.background().schedule(scheduler, () -> {
                    list.add("B");
                });
                Object lock = new Object();
                synchronized (lock) {
                    Thread.yield();   // pinned so will be a no-op
                    list.add("A");
                }
                child.join();
                return null;
            }).join();
        } finally {
            scheduler.shutdown();
        }
        assertEquals(list, List.of("A", "A", "B"));
    }


    // -- Thread.sleep --

    // Thread.sleep(-1)
    public void testThreadSleep1() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            try {
                Thread.sleep(-1);
                assertTrue(false);
            } catch (IllegalArgumentException e) {
                // expected
            }
            return null;
        });
        fiber.join();
    }

    // Thread.sleep(0)
    public void testThreadSleep2() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Thread.sleep(0);
            return null;
        });
        fiber.join();
    }

    // Thread.sleep(2000)
    public void testThreadSleep3() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            long start = System.currentTimeMillis();
            Thread.sleep(2000);
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed > 1900);
            return null;
        });
        fiber.join();
    }

    // Thread.sleep with interrupt status set
    public void testThreadSleep4() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Thread.currentThread().interrupt();
            try {
                Thread.sleep(1000);
                assertTrue(false);
            } catch (InterruptedException e) {
                // expected
            }
            return null;
        });
        fiber.join();
    }

    // Thread.sleep interrupted while sleeping
    public void testThreadSleep5() throws Exception {
        var completed = new AtomicBoolean();
        var fiber = FiberScope.background().schedule(() -> {
            Thread t = Thread.currentThread();
            Interrupter.schedule(t, 2000);
            try {
                Thread.sleep(20*1000);
                assertTrue(false);
            } catch (InterruptedException e) {
                // interrupt status should be clearer
                assertFalse(t.isInterrupted());
            }
            return null;
        });
        fiber.join();
    }

    // Thread.sleep should not be disrupted by unparking fiber
    public void testThreadSleep6() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            long start = System.currentTimeMillis();
            Thread.sleep(2000);
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed > 1900);
            return null;
        });
        // attempt to disrupt sleep
        for (int i=0; i<5; i++) {
            Thread.sleep(20);
            LockSupport.unpark(fiber);
        }
        fiber.join();
    }

    // -- ThreadLocal --

    static final ThreadLocal<Object> LOCAL = new ThreadLocal<>();
    static final ThreadLocal<Object> INHERITED_LOCAL = new InheritableThreadLocal<>();

    public void testThreadLocal1() throws Exception {
        for (int i = 0; i < 10; i++) {
            FiberScope.background().schedule(() -> {
                assertTrue(LOCAL.get() == null);
                Object obj = new Object();
                LOCAL.set(obj);
                assertTrue(LOCAL.get() == obj);
            }).join();
        }
    }

    public void testThreadLocal2() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            assertTrue(LOCAL.get() == null);
            Object obj = new Object();
            LOCAL.set(obj);
            try { Thread.sleep(100); } catch (InterruptedException e) { }
            assertTrue(LOCAL.get() == obj);
        });
        fiber.join();
    }

    public void testInheritedThreadLocal1() throws Exception {
        assertTrue(INHERITED_LOCAL.get() == null);

        for (int i = 0; i < 10; i++) {
            var fiber = FiberScope.background().schedule(() -> {
                assertTrue(INHERITED_LOCAL.get() == null);
                Object obj = new Object();
                INHERITED_LOCAL.set(obj);
                assertTrue(INHERITED_LOCAL.get() == obj);
            });
            fiber.join();
        }

        assertTrue(INHERITED_LOCAL.get() == null);
    }

    // inherit thread local from creating thread
    public void testInheritedThreadLocal2() throws Exception {
        assertTrue(INHERITED_LOCAL.get() == null);

        var obj = new Object();
        INHERITED_LOCAL.set(obj);
        try {
            var fiber = FiberScope.background().schedule(INHERITED_LOCAL::get);
            assert fiber.join() == obj;
        } finally {
            INHERITED_LOCAL.remove();
        }
    }

    // inherit thread local from creating fiber
    public void testInheritedThreadLocal3() throws Exception {
        assertTrue(INHERITED_LOCAL.get() == null);
        var fiber = FiberScope.background().schedule(() -> {
            var obj = new Object();
            INHERITED_LOCAL.set(obj);
            var inherited = FiberScope.background().schedule(INHERITED_LOCAL::get).join();
            assertTrue(inherited == obj);
            return null;
        });
        fiber.join();
    }


    // inherit context class loader from creating fiber
    public void testInheritedThreadLocal4() throws Exception {
        assertTrue(INHERITED_LOCAL.get() == null);
        var obj = new Object();
        INHERITED_LOCAL.set(obj);
        try {
            var fiber = FiberScope.background().schedule(() ->
                    FiberScope.background().schedule(INHERITED_LOCAL::get).join());
            var inherited = fiber.join();
            assertTrue(inherited == obj);
        } finally {
            INHERITED_LOCAL.remove();
        }
    }


    // -- Thread.set/getContextClassLoader --

    public void testThreadContextClassLoader1() throws Exception {
        ClassLoader loader = new ClassLoader() { };
        FiberScope.background().schedule(() -> {
            Thread t = Thread.currentThread();
            t.setContextClassLoader(loader);
            assertTrue(t.getContextClassLoader() == loader);
        }).join();
        assertTrue(Thread.currentThread().getContextClassLoader() != loader);
    }

    // inherit context class loader from creating thread
    public void testThreadContextClassLoader2() throws Exception {
        ClassLoader loader = new ClassLoader() { };
        Thread t = Thread.currentThread();
        ClassLoader savedLoader = t.getContextClassLoader();
        t.setContextClassLoader(loader);
        try {
            FiberScope.background().schedule(() -> {
                assertTrue(Thread.currentThread().getContextClassLoader() == loader);
            }).join();
        } finally {
            t.setContextClassLoader(savedLoader);
        }
    }

    // inherit context class loader from creating fiber
    public void testThreadContextClassLoader3() throws Exception {
        ClassLoader loader = new ClassLoader() { };
        FiberScope.background().schedule(() -> {
            Thread.currentThread().setContextClassLoader(loader);
            FiberScope.background().schedule(() -> {
                assertTrue(Thread.currentThread().getContextClassLoader() == loader);
            }).join();
            return null;
        }).join();
    }

    // inherit context class loader from creating fiber
    public void testThreadContextClassLoader4() throws Exception {
        ClassLoader loader = new ClassLoader() { };
        Thread t = Thread.currentThread();
        ClassLoader savedLoader = t.getContextClassLoader();
        t.setContextClassLoader(loader);
        try {
            FiberScope.background().schedule(() -> {
                FiberScope.background().schedule(() -> {
                    assertTrue(Thread.currentThread().getContextClassLoader() == loader);
                }).join();
                return null;
            }).join();
        } finally {
            t.setContextClassLoader(savedLoader);
        }
    }


    // -- Thread.setUncaughtExceptionHandler --

    public void testThreadUncaughtExceptionHandler1() throws Exception {
        class FooException extends RuntimeException { }
        var exception = new AtomicReference<Throwable>();
        Thread.UncaughtExceptionHandler handler = (thread, exc) -> {
            exception.set(exc);
        };
        var fiber = FiberScope.background().schedule(() -> {
            Thread.currentThread().setUncaughtExceptionHandler(handler);
            throw new FooException();
        });
        while (fiber.isAlive()) {
            Thread.sleep(10);
        }
        assertTrue(exception.get() instanceof FooException);
    }


    // -- Thread.getId --

    public void testThreadGetId() throws Exception {
        try (var scope = FiberScope.open()) {
            long id1 = scope.schedule(() -> Thread.currentThread().getId()).join();
            long id2 = scope.schedule(() -> Thread.currentThread().getId()).join();
            long id3 = Thread.currentThread().getId();
            assertTrue(id1 != id2);
            assertTrue(id1 != id3);
            assertTrue(id2 != id3);
        }
    }


    // -- Thread.getState --

    // RUNNABLE
    public void testThreadGetState1() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Thread.State state = Thread.currentThread().getState();
            assertTrue(state == Thread.State.RUNNABLE);
        });
        fiber.join();
    }

    // WAITING when parked
    public void testThreadGetState2() throws Exception {
        var ref = new AtomicReference<Thread>();
        var fiber = FiberScope.background().schedule(() -> {
            ref.set(Thread.currentThread());
            LockSupport.park();
        });
        Thread t = waitForValue(ref);
        while (t.getState() != Thread.State.WAITING) {
            Thread.sleep(20);
        }
        LockSupport.unpark(fiber);
        fiber.join();
    }

    // WAITING when parked and pinned
    public void testThreadGetState3() throws Exception {
        var ref = new AtomicReference<Thread>();
        var fiber = FiberScope.background().schedule(() -> {
            ref.set(Thread.currentThread());
            var lock = new Object();
            synchronized (lock) {
                LockSupport.park();
            }
        });
        Thread t = waitForValue(ref);
        while (t.getState() != Thread.State.WAITING) {
            Thread.sleep(20);
        }
        LockSupport.unpark(fiber);
        fiber.join();
    }

    // WAITING when blocked in Object.wait
    public void testThreadGetState4() throws Exception {
        var ref = new AtomicReference<Thread>();
        var lock = new Object();
        var fiber = FiberScope.background().schedule(() -> {
            ref.set(Thread.currentThread());
            synchronized (lock) {
                try { lock.wait(); } catch (InterruptedException e) { }
            }
        });
        Thread t = waitForValue(ref);
        while (t.getState() != Thread.State.WAITING) {
            Thread.sleep(20);
        }
        t.interrupt();
        fiber.join();
    }

    // TERMINATED
    public void testThreadGetState5() throws Exception {
        var fiber = FiberScope.background().schedule(() -> Thread.currentThread());
        Thread t = fiber.join();
        assertTrue(t.getState() == Thread.State.TERMINATED);
    }


    // -- Thread.holdsLock --

    public void testThreadHoldsLock1() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            var lock = new Object();
            assertFalse(Thread.holdsLock(lock));
        });
        fiber.join();
    }

    public void testThreadHoldsLock2() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            var lock = new Object();
            synchronized (lock) {
                assertTrue(Thread.holdsLock(lock));
            }
        });
        fiber.join();
    }


    // -- Thread.getStackTrace --

    // runnable (mounted)
    public void testThreadGetStackTrace1() throws Exception {
        var ref = new AtomicReference<Thread>();
        var sel = Selector.open();
        FiberScope.background().schedule(() -> doSelect(ref, sel));
        Thread thread = waitForValue(ref);
        try {
            assertTrue(thread.getState() == Thread.State.RUNNABLE);
            StackTraceElement[] stack = thread.getStackTrace();
            assertTrue(contains(stack, "doSelect"));
        } finally {
            sel.close();
        }
    }

    // block in Selector.select after recording current thread
    private void doSelect(AtomicReference<Thread> ref, Selector sel) {
        ref.set(Thread.currentThread());
        try { sel.select(); } catch (Exception e) { }
    }


    // waiting (mounted)
    public void testThreadGetStackTrace2() throws Exception {
        var lock = new Object();
        var ref = new AtomicReference<Thread>();
        FiberScope.background().schedule(() -> {
            synchronized (lock) {
                ref.set(Thread.currentThread());
                try { lock.wait(); } catch (InterruptedException e) { }
            }
        });

        // wait for carrier thread to block
        Thread thread = waitForValue(ref);
        while (thread.getState() != Thread.State.WAITING) {
            Thread.sleep(20);
        }

        try {
            StackTraceElement[] stack = thread.getStackTrace();
            assertTrue(contains(stack, "Object.wait"));
        } finally {
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    // parked (unmounted)
    public void testThreadGetStackTrace3() throws Exception {
        var ref = new AtomicReference<Thread>();
        var fiber = FiberScope.background().schedule(() -> {
            ref.set(Thread.currentThread());
            LockSupport.park();
        });

        // wait for fiber to park
        Thread thread = waitForValue(ref);
        while (thread.getState() != Thread.State.WAITING) {
            Thread.sleep(20);
        }

        try {
            StackTraceElement[] stack = thread.getStackTrace();
            assertTrue(contains(stack, "LockSupport.park"));
        } finally {
            LockSupport.unpark(fiber);
        }
    }

    // terminated
    public void testThreadGetStackTrace4() throws Exception {
        var fiber = FiberScope.background().schedule(() -> Thread.currentThread());
        var thread = fiber.join();
        StackTraceElement[] stack = thread.getStackTrace();
        assertTrue(stack.length == 0);
    }

    private boolean contains(StackTraceElement[] stack, String expected) {
        return Stream.of(stack)
                .map(Object::toString)
                .anyMatch(s -> s.contains(expected));
    }

    // -- ThreadGroup --

    // ThreadGroup.enumerate should not enumerate fiber Thread objects
    public void testThreadGroup1() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Thread current = Thread.currentThread();

            ThreadGroup g = Thread.currentThread().getThreadGroup();
            Thread[] threads = new Thread[100];
            g.enumerate(threads);
            Stream.of(threads)
                    .filter(t -> t == current)
                    .forEach(t -> assertTrue(false));
        });
        fiber.join();
    }

    // ThreadGroup.interrupt should not interrupt fiber Thread objects
    public void testThreadGroup2() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Thread t = Thread.currentThread();
            t.getThreadGroup().interrupt();
            assertFalse(t.isInterrupted());
        });
        fiber.join();
    }


    // -- Object.wait/notify --

    // fiber waits, notified by thread
    public void testWaitNotify1() throws Exception {
        var ref = new AtomicReference<Thread>();
        var lock = new Object();
        var fiber = FiberScope.background().schedule(() -> {
            ref.set(Thread.currentThread());
            synchronized (lock) {
                try {
                    lock.wait();
                } catch (InterruptedException e) { }
            }
        });

        // spin until the fiber waiting
        Thread t = waitForValue(ref);
        while (t.getState() != Thread.State.WAITING) {
            Thread.sleep(10);
        }

        // thread invokes notify
        synchronized (lock) {
            lock.notifyAll();
        }
        fiber.join();
    }

    // thread waits, notified by fiber
    public void testWaitNotify2() throws Exception {
        var lock = new Object();
        FiberScope.background().schedule(() -> {
            synchronized (lock) {
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            lock.wait();
        }
    }

    // fiber waits, notified by other fiber
    //@Test(enabled=false)
    public void testWaitNotify3() throws Exception {
        var lock = new Object();
        var fiber1 = FiberScope.background().schedule(() -> {
            synchronized (lock) {
                try {
                    lock.wait();
                } catch (InterruptedException e) { }
            }
        });
        var fiber2 = FiberScope.background().schedule(() -> {
            synchronized (lock) {
                lock.notifyAll();
            }
        });
        fiber1.join();
        fiber2.join();
    }

    // interrupt before Object.wait
    public void testWaitNotify4() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Thread t = Thread.currentThread();
            t.interrupt();
            Object lock = new Object();
            synchronized (lock) {
                try {
                    lock.wait();
                    assertTrue(false);
                } catch (InterruptedException e) {
                    // interrupt status should be cleared
                    assertFalse(t.isInterrupted());
                }
            }
        });
        fiber.join();
    }

    // interrupt while waiting in Object.wait
    public void testWaitNotify5() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            Thread t = Thread.currentThread();
            Interrupter.schedule(t, 1000);
            Object lock = new Object();
            synchronized (lock) {
                try {
                    lock.wait();
                    assertTrue(false);
                } catch (InterruptedException e) {
                    // interrupt status should be cleared
                    assertFalse(t.isInterrupted());
                }
            }
        });
        fiber.join();
    }


    // -- ReentrantLock --

    // lock/unlock
    public void testReentrantLock1() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            ReentrantLock lock = new ReentrantLock();
            assertFalse(lock.isHeldByCurrentThread());
            lock.lock();
            assertTrue(lock.isHeldByCurrentThread());
            lock.unlock();
            assertFalse(lock.isHeldByCurrentThread());
        });
        fiber.join();
    }

    // tryLock/unlock
    public void testReentrantLock2() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            ReentrantLock lock = new ReentrantLock();
            assertFalse(lock.isHeldByCurrentThread());
            boolean acquired = lock.tryLock();
            assertTrue(acquired);
            assertTrue(lock.isHeldByCurrentThread());
            lock.unlock();
            assertFalse(lock.isHeldByCurrentThread());
        });
        fiber.join();
    }

    // lock/lock/unlock/unlock
    public void testReentrantLock3() throws Exception {
        var fiber = FiberScope.background().schedule(() -> {
            ReentrantLock lock = new ReentrantLock();
            assertFalse(lock.isHeldByCurrentThread());
            assertTrue(lock.getHoldCount() == 0);
            lock.lock();
            assertTrue(lock.isHeldByCurrentThread());
            assertTrue(lock.getHoldCount() == 1);
            lock.lock();
            assertTrue(lock.isHeldByCurrentThread());
            assertTrue(lock.getHoldCount() == 2);
            lock.unlock();
            assertTrue(lock.isHeldByCurrentThread());
            assertTrue(lock.getHoldCount() == 1);
            lock.unlock();
            assertFalse(lock.isHeldByCurrentThread());
            assertTrue(lock.getHoldCount() == 0);
        });
        fiber.join();
    }

    // locked by thread, fiber tries to lock
    public void testReentrantLock4() throws Exception {
        Fiber<?> fiber;
        ReentrantLock lock = new ReentrantLock();
        var holdsLock = new AtomicBoolean();

        // thread acquires lock
        lock.lock();
        try {
            fiber = FiberScope.background().schedule(() -> {
                lock.lock();  // should block
                holdsLock.set(true);
                LockSupport.park();
                lock.unlock();
                holdsLock.set(false);
            });
            // give time for fiber to block
            Thread.sleep(1000);
            assertFalse(holdsLock.get());
        } finally {
            lock.unlock();
        }

        // fiber should acquire lock, park, unpark, and then release lock
        while (!holdsLock.get()) {
            Thread.sleep(20);
        }
        LockSupport.unpark(fiber);
        while (holdsLock.get()) {
            Thread.sleep(20);
        }
    }

    // locked by fiber, thread tries to lock
    public void testReentrantLock5() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        var fiber = FiberScope.background().schedule(() -> {
            lock.lock();
            try {
                LockSupport.park();
            } finally {
                lock.unlock();
            }
        });

        // wat for fiber to acquire lock
        while (!lock.isLocked()) {
            Thread.sleep(20);
        }

        // thread cannot acquire lock
        try {
            assertFalse(lock.tryLock());
        } finally {
            // fiber should unlock
            LockSupport.unpark(fiber);

            // thread should be able to acquire lock
            lock.lock();
            lock.unlock();

            fiber.join();
        }
    }

    // lock by fiber, another fiber tries to lock
    public void testReentrantLock6() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        var fiber1 = FiberScope.background().schedule(() -> {
            lock.lock();
            try {
                LockSupport.park();
            } finally {
                lock.unlock();
            }
        });

        // wat for fiber to acquire lock
        while (!lock.isLocked()) {
            Thread.sleep(10);
        }

        var holdsLock  = new AtomicBoolean();
        var fiber2 = FiberScope.background().schedule(() -> {
            lock.lock();
            holdsLock.set(true);
            LockSupport.park();
            lock.unlock();
            holdsLock.set(false);
        });

        // fiber2 should block
        Thread.sleep(1000);
        assertFalse(holdsLock.get());

        // unpark fiber1
        LockSupport.unpark(fiber1);

        // fiber2 should acquire lock
        while (!holdsLock.get()) {
            Thread.sleep(20);
        }
        // unpark fiber and it should release lock
        LockSupport.unpark(fiber2);
        while (holdsLock.get()) {
            Thread.sleep(20);
        }
    }


    // -- GC --

    // ensure that a Fiber can be GC"ed
    public void testGC1() {
        waitUntilObjectGCed(FiberScope.background().schedule(DO_NOTHING));
    }

    // ensure that a parked Fiber can be GC'ed
    public void testGC2() {
        waitUntilObjectGCed(FiberScope.background().schedule(() -> LockSupport.park()));
    }

    // ensure that a terminated Fiber can be GC'ed
    public void testGC3() throws Exception {
        var fiber = FiberScope.background().schedule(DO_NOTHING);
        fiber.join();
        var ref = new WeakReference<Fiber>(fiber);
        fiber = null;
        waitUntilObjectGCed(ref.get());
    }

    // waits for the given objecty to be GC'ed
    private static void waitUntilObjectGCed(Object obj) {
        var ref = new WeakReference<Object>(obj);
        obj = null;
        do {
            System.gc();
            try { Thread.sleep(50); } catch (InterruptedException e) { }
        } while (ref.get() != null);
    }

    // -- supporting code --

    private <T> T waitForValue(AtomicReference<T> ref) {
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

    static class Interrupter implements Runnable {
        final Thread thread;
        final long delay;

        Interrupter(Thread thread, long delay) {
            this.thread = thread;
            this.delay = delay;
        }

        static void schedule(Thread thread, long delay) {
            Interrupter task  = new Interrupter(thread, delay);
            new Thread(task).start();
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                thread.interrupt();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
