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

    public void testExecute1() {
        var executed = new AtomicBoolean();
        Fiber.schedule(() -> executed.set(true)).awaitTermination();
        assertTrue(executed.get());
    }

    public void testExecute2() {
        String s = Fiber.schedule(() -> "foo").join();
        assertTrue("foo".endsWith(s));
    }

    // throw uncaught exception
    public void testUncaughtException1() {
        var executed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            executed.set(true);
            throw new RuntimeException();
        });
        fiber.awaitTermination();
        assertTrue(executed.get());
    }

    public void testUncaughtException2() {
        try {
            Fiber.schedule(() -> { throw new RuntimeException(); }).join();
        } catch (CompletionException e) {
            assertTrue(e.getCause() instanceof RuntimeException);
        }
    }

    // throw uncaught error
    public void testUncaughtError1() {
        var executed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            executed.set(true);
            throw new Error();
        });
        fiber.awaitTermination();
        assertTrue(executed.get());
    }

    public void testUncaughtError2() {
        try {
            Fiber.schedule(() -> { throw new Error(); }).join();
        } catch (CompletionException e) {
            assertTrue(e.getCause() instanceof Error);
        }
    }

    // -- parking --

    // fiber parks, unparked by thread
    public void testPark1() throws Exception {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            LockSupport.park();
            completed.set(true);
        });
        Thread.sleep(1000); // give time for fiber to park
        LockSupport.unpark(fiber);
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // fiber parks, unparked by another fiber
    public void testPark2() throws Exception {
        var completed = new AtomicInteger();
        var fiber1 = Fiber.schedule(() -> {
            LockSupport.park();
            completed.incrementAndGet();
        });
        Thread.sleep(1000); // give time for fiber to park
        var fiber2 = Fiber.schedule(() -> {
            LockSupport.unpark(fiber1);
            completed.incrementAndGet();
        });
        fiber1.awaitTermination();
        fiber2.awaitTermination();
        assertTrue(completed.get() == 2);
    }

    // park while holding monitor
    public void testPark3() throws Exception {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            var lock = new Object();
            synchronized (lock) {
                LockSupport.park();
            }
            completed.set(true);
        });
        Thread.sleep(1000); // give time for fiber to park
        LockSupport.unpark(fiber);
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // park with native thread on the stack
    public void testPark4() throws Exception {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            try {
                Method m = Basic.class.getDeclaredMethod("doPark");
                m.invoke(null);
                completed.set(true);
            } catch (Exception e) {
                assertTrue(false);
            }
        });
        Thread.sleep(1000); // give time for fiber to park
        LockSupport.unpark(fiber);
        fiber.awaitTermination();
        assertTrue(completed.get());
    }
    static void doPark() {
        LockSupport.park();
    }

    // unpark before park
    public void testPark5() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            LockSupport.unpark(Fiber.current().orElseThrow());
            LockSupport.park();
            completed.set(true);
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // 2 x unpark before park
    public void testPark6() throws Exception {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            Fiber me = Fiber.current().orElseThrow();
            LockSupport.unpark(me);
            LockSupport.unpark(me);
            LockSupport.park();
            LockSupport.park();  // should park
            completed.set(true);
        });
        Thread.sleep(1000); // give time for fiber to park
        LockSupport.unpark(fiber);
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // 2 x park
    public void testPark7() throws Exception {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            LockSupport.park();
            LockSupport.park();
            completed.set(true);
        });

        Thread.sleep(1000); // give time for fiber to park

        // unpark, fiber should park again
        LockSupport.unpark(fiber);
        Thread.sleep(1000);

        // let it terminate
        LockSupport.unpark(fiber);
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // interrupt before park
    public void testPark8() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            Thread t = Thread.currentThread();
            t.interrupt();
            LockSupport.park();
            assertTrue(t.isInterrupted());
            completed.set(true);
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // interrupt while parked
    public void testPark9() throws Exception {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            Thread t = Thread.currentThread();
            Interrupter.schedule(t, 1000);
            LockSupport.park();
            assertTrue(t.isInterrupted());
            completed.set(true);
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // interrupt before park (pinned park)
    public void testPark10() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            Thread t = Thread.currentThread();
            t.interrupt();
            Object lock = new Object();
            synchronized (lock) {
                LockSupport.park();
            }
            assertTrue(t.isInterrupted());
            completed.set(true);
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // interrupt while parked (pinned park)
    public void testPark11() throws Exception {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            Thread t = Thread.currentThread();
            Interrupter.schedule(t, 1000);
            Object lock = new Object();
            synchronized (lock) {
                LockSupport.park();
            }
            assertTrue(t.isInterrupted());
            completed.set(true);
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // parkNanos(-1) completes immediately
    public void testParkNanos1() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            LockSupport.parkNanos(-1);
            completed.set(true);
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // parkNanos(0) completes immediately
    public void testParkNanos2() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            LockSupport.parkNanos(0);
            completed.set(true);
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // parkNanos(1000ms) completes quickly
    public void testParkNanos3() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            // park for 1000ms
            long nanos = TimeUnit.NANOSECONDS.convert(1000, TimeUnit.MILLISECONDS);
            long start = System.nanoTime();
            LockSupport.parkNanos(nanos);

            // check that fiber parks for >= 900ms
            long elapsed = TimeUnit.MILLISECONDS.convert(System.nanoTime() - start,
                                                         TimeUnit.NANOSECONDS);
            assertTrue(elapsed >= 900);
            completed.set(true);
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // fiber parks, unparked by thread
    public void testParkNanos4() throws Exception {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            long nanos = TimeUnit.NANOSECONDS.convert(30, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
            completed.set(true);
        });
        Thread.sleep(1000); // give time for fiber to park
        LockSupport.unpark(fiber);
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // fiber parks, unparked by another fiber
    public void testParkNanos5() throws Exception {
        var completed = new AtomicInteger();
        var fiber1 = Fiber.schedule(() -> {
            long nanos = TimeUnit.NANOSECONDS.convert(30, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
            completed.incrementAndGet();
        });
        Thread.sleep(1000);  // give time for fiber to park
        var fiber2 = Fiber.schedule(() -> {
            LockSupport.unpark(fiber1);
            completed.incrementAndGet();
        });
        fiber1.awaitTermination();
        fiber2.awaitTermination();
        assertTrue(completed.get() == 2);
    }

    // unpark before parkNanos
    public void testParkNanos6() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            LockSupport.unpark(Fiber.current().orElseThrow());
            long nanos = TimeUnit.NANOSECONDS.convert(30, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
            completed.set(true);
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // unpark before parkNanos(0), should consume permit
    public void testParkNanos7() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            LockSupport.unpark(Fiber.current().orElseThrow());
            LockSupport.parkNanos(0);
            LockSupport.park(); // should block
            completed.set(true);
        });
        fiber.awaitTermination(Duration.ofSeconds(3));
        LockSupport.unpark(fiber);
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // -- awaitTermination --

    // awaitTermination short lived fiber
    public void testAwaitTermination1() {
        var fiber = Fiber.schedule(DO_NOTHING);
        fiber.awaitTermination();
    }

    // awaitTermination long lived fiber
    public void testAwaitTermination2() {
        var fiber = Fiber.schedule(() -> {
            try {
                Thread.sleep(2*1000);
            } catch (InterruptedException e) { }
        });
        fiber.awaitTermination();
    }

    // awaitTermination after terminated
    public void testAwaitTermination3() throws Exception {
        var fiber = Fiber.schedule(DO_NOTHING);
        fiber.awaitTermination();
        fiber.awaitTermination();
    }

    // thread interrupted while awaitTermination-ing
    public void testAwaitTermination4() {
        long nanos = TimeUnit.NANOSECONDS.convert(5, TimeUnit.SECONDS);
        var fiber = Fiber.schedule(() -> LockSupport.parkNanos(nanos));
        Interrupter.schedule(Thread.currentThread(), 500);
        try {
            fiber.awaitTermination();
            assertTrue(Thread.interrupted());
        } finally {
            Thread.interrupted(); // make sure interrupt status is cleared
            LockSupport.unpark(fiber);
        }
    }

    // fiber interrupted while awaitTermination-ing
    public void testAwaitTermination5() {
        var fiber1 = Fiber.schedule(() -> {
            long nanos = TimeUnit.NANOSECONDS.convert(5, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
        });
        var completed = new AtomicBoolean();
        var fiber2 = Fiber.schedule(() -> {
            Interrupter.schedule(Thread.currentThread(), 500);
            fiber1.awaitTermination();
            completed.set(true);
        });
        try {
            fiber2.awaitTermination();
            assertTrue(completed.get());
        } finally {
            LockSupport.unpark(fiber1);
        }
    }

    // awaitTermination short lived fiber
    public void testAwaitTermination6() {
        var fiber = Fiber.schedule(DO_NOTHING);
        boolean terminated = fiber.awaitTermination(Duration.ofSeconds(5));
        assertTrue(terminated);
    }

    // awaitTermination long lived fiber
    public void testAwaitTermination7() {
        var fiber = Fiber.schedule(() -> LockSupport.park());
        try {
            boolean terminated = fiber.awaitTermination(Duration.ofSeconds(2));
            assertFalse(terminated);
        } finally {
            LockSupport.unpark(fiber);
        }
    }

    // thread interrupted while awaitTermination-ing, fiber terminates
    public void testAwaitTermination8() {
        var fiber = Fiber.schedule(() -> {
            long nanos = TimeUnit.NANOSECONDS.convert(5, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
        });
        Interrupter.schedule(Thread.currentThread(), 1000);
        try {
            boolean terminated = fiber.awaitTermination(Duration.ofSeconds(60));
            assertTrue(terminated);
            assertTrue(Thread.interrupted());
        } finally {
            Thread.interrupted(); // make sure interrupt status is cleared
            LockSupport.unpark(fiber);
        }
    }

    // thread interrupted while awaitTermination-ing, fiber does not terminate
    public void testAwaitTermination9() {
        var fiber = Fiber.schedule(() -> LockSupport.park());
        Interrupter.schedule(Thread.currentThread(), 1000);
        try {
            boolean terminated = fiber.awaitTermination(Duration.ofSeconds(3));
            assertFalse(terminated);
            assertTrue(Thread.interrupted());
        } finally {
            Thread.interrupted(); // make sure interrupt status is cleared
            LockSupport.unpark(fiber);
        }
    }

    // fiber interrupted while awaitTermination-ing, other fiber terminates
    public void testAwaitTermination10() {
        var fiber1 = Fiber.schedule(() -> {
            long nanos = TimeUnit.NANOSECONDS.convert(5, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
        });
        var completed = new AtomicBoolean();
        var fiber2 = Fiber.schedule(() -> {
            Interrupter.schedule(Thread.currentThread(), 1000);
            boolean terminated = fiber1.awaitTermination(Duration.ofSeconds(60));
            assertTrue(terminated);
            assertTrue(Thread.interrupted());
            completed.set(true);
        });
        try {
            fiber2.awaitTermination();
            assertTrue(completed.get());
        } finally {
            LockSupport.unpark(fiber1);
        }
    }

    // fiber interrupted while awaitTermination-ing, other fiber does not terminate
    public void testAwaitTermination11() {
        var fiber1 = Fiber.schedule(() -> LockSupport.park());
        var completed = new AtomicBoolean();
        var fiber2 = Fiber.schedule(() -> {
            Interrupter.schedule(Thread.currentThread(), 1000);
            boolean terminated = fiber1.awaitTermination(Duration.ofSeconds(3));
            assertFalse(terminated);
            assertTrue(Thread.interrupted());
            completed.set(true);
        });
        try {
            fiber2.awaitTermination();
            assertTrue(completed.get());
        } finally {
            LockSupport.unpark(fiber1);
        }
    }

    // awaitTermination zero duration
    public void testAwaitTermination12() {
        var fiber = Fiber.schedule(() -> LockSupport.park());
        fiber.awaitTermination(Duration.ofSeconds(0));
        LockSupport.unpark(fiber);
        fiber.awaitTermination();
    }

    // awaitTermination negative duration
    public void testAwaitTermination13() {
        var fiber = Fiber.schedule(() -> LockSupport.park());
        fiber.awaitTermination(Duration.ofSeconds(-1));
        LockSupport.unpark(fiber);
        fiber.awaitTermination();
    }


    // -- join --

    // join short lived fiber
    public void testJoin1() {
        String s = Fiber.schedule(() -> "foo").join();
        assertEquals(s, "foo");
    }

    // join long lived fiber
    public void testJoin2() {
        Fiber<String> fiber = Fiber.schedule(() -> {
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
        Fiber<String> fiber = Fiber.schedule(() -> "foo");
        fiber.awaitTermination();
        String s = fiber.join();
        assertEquals(s, "foo");
    }

    // thread interrupted while join-ing
    public void testJoin4() {
        long nanos = TimeUnit.NANOSECONDS.convert(5, TimeUnit.SECONDS);
        Fiber<String> fiber = Fiber.schedule(() -> {
            LockSupport.parkNanos(nanos);
            return "foo";
        });
        Interrupter.schedule(Thread.currentThread(), 500);
        try {
            String s = fiber.join();
            assertEquals(s, "foo");
            assertTrue(Thread.interrupted());
        } finally {
            Thread.interrupted(); // make sure interrupt status is cleared
            LockSupport.unpark(fiber);
        }
    }

    // fiber interrupted while join-ing
    public void testJoin5() {
        var fiber1 = Fiber.schedule(() -> {
            long nanos = TimeUnit.NANOSECONDS.convert(5, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
            return "foo";
        });
        var completed = new AtomicBoolean();
        var fiber2 = Fiber.schedule(() -> {
            Interrupter.schedule(Thread.currentThread(), 500);
            String s = fiber1.join();
            assertEquals(s, "foo");
            completed.set(true);
        });
        try {
            fiber2.awaitTermination();
            assertTrue(completed.get());
        } finally {
            LockSupport.unpark(fiber1);
        }
    }

    // join short lived fiber
    public void testJoin6() throws Exception {
        String s = Fiber.schedule(() -> "foo").join(Duration.ofSeconds(5));
        assertEquals(s, "foo");
    }

    // join long lived fiber
    public void testJoin7() {
        var fiber = Fiber.schedule(() -> LockSupport.park());
        try {
            fiber.join(Duration.ofSeconds(2));
            assertTrue(false);
        } catch (TimeoutException e) {
            // expected
        } finally {
            LockSupport.unpark(fiber);
        }
    }

    // thread interrupted while join-ing, fiber terminates
    public void testJoin8() throws Exception {
        var fiber = Fiber.schedule(() -> {
            long nanos = TimeUnit.NANOSECONDS.convert(5, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
            return "foo";
        });
        Interrupter.schedule(Thread.currentThread(), 1000);
        try {
            String s = fiber.join(Duration.ofSeconds(60));
            assertEquals(s, "foo");
            assertTrue(Thread.interrupted());
        } finally {
            Thread.interrupted(); // make sure interrupt status is cleared
            LockSupport.unpark(fiber);
        }
    }

    // thread interrupted while join-ing, fiber does not terminate
    public void testJoin9() {
        var fiber = Fiber.schedule(() -> LockSupport.park());
        Interrupter.schedule(Thread.currentThread(), 1000);
        try {
            fiber.join(Duration.ofSeconds(3));
            assertTrue(false);
        } catch (TimeoutException e) {
            assertTrue(Thread.interrupted());
        } finally {
            Thread.interrupted(); // make sure interrupt status is cleared
            LockSupport.unpark(fiber);
        }
    }

    // fiber interrupted while join-ing, other fiber terminates
    public void testJoin10() throws Exception {
        var fiber1 = Fiber.schedule(() -> {
            long nanos = TimeUnit.NANOSECONDS.convert(5, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
            return "foo";
        });
        var fiber2 = Fiber.schedule(() -> {
            Interrupter.schedule(Thread.currentThread(), 1000);
            String s = fiber1.join(Duration.ofSeconds(60));
            assertEquals(s, "foo");
            assertTrue(Thread.interrupted());
            return null;
        });
        try {
            fiber2.join();
        } finally {
            LockSupport.unpark(fiber1);
        }
    }

    // fiber interrupted while join-ing, other fiber does not terminate
    public void testJoin11() {
        var fiber1 = Fiber.schedule(() -> LockSupport.park());
        var completed = new AtomicBoolean();
        var fiber2 = Fiber.schedule(() -> {
            Interrupter.schedule(Thread.currentThread(), 1000);
            try {
                fiber1.join(Duration.ofSeconds(3));
                assertTrue(false);
            } catch (TimeoutException e) {
                // expected
            }
            assertTrue(Thread.interrupted());
            completed.set(true);
        });
        try {
            fiber2.awaitTermination();
            assertTrue(completed.get());
        } finally {
            LockSupport.unpark(fiber1);
        }
    }

    // join zero duration
    @Test(expectedExceptions = { TimeoutException.class })
    public void testThreadJoin12() throws Exception {
        var fiber = Fiber.schedule(() -> LockSupport.park());
        try {
            fiber.join(Duration.ofSeconds(0));
        } finally {
            LockSupport.unpark(fiber);
        }
    }

    // join negative duration
    @Test(expectedExceptions = { TimeoutException.class })
    public void testThreadJoin13() throws Exception {
        var fiber = Fiber.schedule(() -> LockSupport.park());
        try {
            fiber.join(Duration.ofSeconds(-1));
        } finally {
            LockSupport.unpark(fiber);
        }
    }


    // -- current --

    public void testCurrent() {
        var ref = new AtomicReference<Fiber>();
        var fiber = Fiber.schedule(() -> ref.set(Fiber.current().orElse(null)));
        fiber.awaitTermination();
        assertTrue(ref.get() == fiber);
    }


    // -- cancellation --

    public void testCancel1() {
        var fiber = Fiber.schedule(() -> LockSupport.park());
        assertFalse(fiber.isCancelled());
        fiber.cancel();
        assertTrue(fiber.isCancelled());
        LockSupport.unpark(fiber);
        fiber.awaitTermination();
        assertTrue(fiber.isCancelled());
    }

    public void testCancel2() {
        var fiber = Fiber.schedule(() -> LockSupport.park());
        assertFalse(fiber.isCancelled());
        fiber.cancel();
        assertTrue(fiber.isCancelled());
        fiber.awaitTermination();
        assertTrue(fiber.isCancelled());
    }

    // -- isAlive --

    public void testIsAlive() {
        var fiber = Fiber.schedule(() -> LockSupport.park());
        assertTrue(fiber.isAlive());
        LockSupport.unpark(fiber);
        fiber.awaitTermination();
        assertFalse(fiber.isAlive());
    }

    // -- toFuture --

    public void testToFuture1() throws Exception {
        Future<String> result = Fiber.schedule(() -> "foo").toFuture();
        String s = result.get();
        assertEquals(s, "foo");
    }

    public void testToFuture2() throws Exception {
        Future<?> future = Fiber.schedule(() -> {
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
        Fiber<String> fiber = Fiber.schedule(() -> {
            LockSupport.park();
            return "foo";
        });
        Future<?> result = fiber.toFuture();

        // sets cancel status and unpark fiber
        result.cancel(false);
        assertTrue(fiber.isCancelled());

        try {
            result.get();
            assertTrue(false);
        } catch (CancellationException expected) { }

        // fiber returns to completion
        assertEquals(fiber.join(), "foo");
    }

    // Fiber.toFuture should return the same object is called several times
    public void testToFuture4() {
        var fiber = Fiber.schedule(() -> "foo");
        var result1 = fiber.toFuture();
        var result2 = fiber.toFuture();
        assertTrue(result1 == result2);
    }

    // -- nulls --

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull1() {
        Fiber.schedule((Runnable) null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull2() {
        Fiber.schedule((Callable<?>) null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull3() {
        Runnable task = () -> { };
        Fiber.schedule((Executor)null, task);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull4() {
        Callable<String> task = () -> "foo";
        Fiber.schedule((Executor)null, task);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull5() {
        ExecutorService scheduler = Executors.newCachedThreadPool();
        try {
            Fiber.schedule(scheduler, (Runnable) null);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull6() {
        ExecutorService scheduler = Executors.newCachedThreadPool();
        try {
            Fiber.schedule(scheduler, (Callable<?>) null);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull7() {
        Fiber.schedule(() -> { }).awaitTermination(null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull8() throws Exception {
        Fiber.schedule(() -> { }).join(null);
    }

    // -- Thread.currentThread --

    //  Thread.currentThread before/after park
    public void testCurrentThread1() throws Exception {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            Thread t = Thread.currentThread();  // before park
            LockSupport.park();
            assertTrue(Thread.currentThread() == t);  // after park
            completed.set(true);
        });
        Thread.sleep(1000); // give time for fiber to park
        LockSupport.unpark(fiber);
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    //  Thread.currentThread before/after synchronized block
    public void testCurrentThread2() throws Exception {
        var completed = new AtomicBoolean();
        var lock = new Object();
        Fiber<?> fiber;
        synchronized (lock) {
            fiber = Fiber.schedule(() -> {
                Thread t = Thread.currentThread();  // before synchronized
                synchronized (lock) { }
                assertTrue(Thread.currentThread() == t);  // after synchronized
                completed.set(true);
            });
            Thread.sleep(200); // give time for fiber to block
        }
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    //  Thread.currentThread before/after lock
    public void testCurrentThread3() throws Exception {
        var completed = new AtomicBoolean();
        var lock = new ReentrantLock();
        Fiber<?> fiber;
        lock.lock();
        try {
            fiber = Fiber.schedule(() -> {
                Thread t = Thread.currentThread();  // before lock
                lock.lock();
                lock.unlock();
                assertTrue(Thread.currentThread() == t);  // after lock
                completed.set(true);
            });
            Thread.sleep(200); // give time for fiber to block
        } finally {
            lock.unlock();
        }
        fiber.awaitTermination();
        assertTrue(completed.get());
    }


    // -- Thread.start/stop/suspend/resume --

    public void testThreadStart() {
        var completed = new AtomicBoolean();
        Fiber.schedule(() -> {
            Thread t = Thread.currentThread();
            try {
                t.start();
                assertTrue(false);
            } catch (IllegalStateException expected) {
                completed.set(true);
            }
        }).awaitTermination();
        assertTrue(completed.get());
    }

    public void testThreadStop() {
        var completed = new AtomicBoolean();
        Fiber.schedule(() -> {
            Thread t = Thread.currentThread();
            try {
                t.stop();
                assertTrue(false);
            } catch (UnsupportedOperationException expected) {
                completed.set(true);
            }
        }).awaitTermination();
        assertTrue(completed.get());
    }

    public void testThreadSuspend() {
        var completed = new AtomicBoolean();
        Fiber.schedule(() -> {
            Thread t = Thread.currentThread();
            try {
                t.suspend();
                assertTrue(false);
            } catch (UnsupportedOperationException expected) {
                completed.set(true);
            }
        }).awaitTermination();
        assertTrue(completed.get());
    }

    public void testThreadResume() {
        var completed = new AtomicBoolean();
        Fiber.schedule(() -> {
            Thread t = Thread.currentThread();
            try {
                t.resume();
                assertTrue(false);
            } catch (UnsupportedOperationException expected) {
                completed.set(true);
            }
        }).awaitTermination();
        assertTrue(completed.get());
    }


    // -- Thread.join --

    // thread invokes join to wait for fiber to terminate
    public void testThreadJoin1() throws Exception {
        var ref = new AtomicReference<Thread>();
        Fiber.schedule(() -> ref.set(Thread.currentThread()));
        Thread t = waitForValue(ref);
        t.join();
    }

    // fiber invokes join to wait for another fiber to terminate
    public void testThreadJoin2() {
        var ref = new AtomicReference<Thread>();
        Fiber.schedule(() -> ref.set(Thread.currentThread()));
        var completed = new AtomicBoolean();
        var fiber2 = Fiber.schedule(() -> {
            try {
                waitForValue(ref).join();
                completed.set(true);
            } catch (InterruptedException e) { }
        });
        fiber2.awaitTermination();
        assertTrue(completed.get());
    }

    // thread invokes join(millis) to wait for fiber to terminate
    public void testThreadJoin3() throws Exception {
        var ref = new AtomicReference<Thread>();
        Fiber.schedule(() -> ref.set(Thread.currentThread()));
        Thread t = waitForValue(ref);
        t.join(10*1000);
    }

    // fiber invokes join(millis) to wait for another fiber to terminate
    public void testThreadJoin4() {
        var ref = new AtomicReference<Thread>();
        Fiber.schedule(() -> ref.set(Thread.currentThread()));
        var completed = new AtomicBoolean();
        var fiber2 = Fiber.schedule(() -> {
            try {
                Thread t = waitForValue(ref);
                t.join(10*1000);
                completed.set(true);
            } catch (InterruptedException e) { }
        });
        fiber2.awaitTermination();
        assertTrue(completed.get());
    }

    // thread invokes join(millis), fiber does not terminate
    public void testThreadJoin5() throws Exception {
        var ref = new AtomicReference<Thread>();
        var fiber = Fiber.schedule(() -> {
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
    public void testThreadJoin6() {
        var ref = new AtomicReference<Thread>();
        var fiber1 = Fiber.schedule(() -> {
            ref.set(Thread.currentThread());
            LockSupport.park();
        });
        var completed = new AtomicBoolean();
        var fiber2 = Fiber.schedule(() -> {
            try {
                Thread t = waitForValue(ref);
                t.join(2*1000);
                completed.set(true);
            } catch (InterruptedException e) { }
        });
        try {
            fiber2.awaitTermination();
            assertTrue(completed.get());
        } finally {
            LockSupport.unpark(fiber1);
        }
    }

    // interrupt before Thread.join main thread
    public void testThreadJoin7() {
        Thread mainThread = Thread.currentThread();
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            Thread t = Thread.currentThread();
            t.interrupt();
            try {
                mainThread.join();
                assertTrue(false);
            } catch (InterruptedException e) {
                // interrupt status should be cleared
                assertFalse(t.isInterrupted());
                completed.set(true);
            }
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // interrupt before Thread.join current thread
    public void testThreadJoin8() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            Thread t = Thread.currentThread();
            t.interrupt();
            try {
                t.join();
                assertTrue(false);
            } catch (InterruptedException e) {
                // interrupt status should be cleared
                assertFalse(t.isInterrupted());
                completed.set(true);
            }
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // interrupt while in Thread.join
    public void testThreadJoin9() throws Exception {
        Thread mainThread = Thread.currentThread();
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            Thread t = Thread.currentThread();
            Interrupter.schedule(t, 1000);
            try {
                mainThread.join();
                assertTrue(false);
            } catch (InterruptedException e) {
                // interrupt status should be cleared
                assertFalse(t.isInterrupted());
                completed.set(true);
            }
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // interrupt while in Thread.join current thread
    public void testThreadJoin10() throws Exception {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            Thread t = Thread.currentThread();
            try {
                Interrupter.schedule(t, 1000);
                t.join();
                assertTrue(false);
            } catch (InterruptedException e) {
                // interrupt status should be cleared
                assertFalse(Thread.currentThread().isInterrupted());
                completed.set(true);
            }
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // join with negative timeout
    @Test(expectedExceptions = { IllegalArgumentException.class })
    public void testThreadJoin11() throws Exception {
        var ref = new AtomicReference<Thread>();
        Fiber.schedule(() -> ref.set(Thread.currentThread()));
        Thread t = waitForValue(ref);
        t.join(-1);
    }


    // -- Thread.yield --

    public void testThreadYield1() {
        var list = new CopyOnWriteArrayList<String>();
        ExecutorService scheduler = Executors.newFixedThreadPool(1);
        try {
            Fiber.schedule(scheduler, () -> {
                list.add("A");
                Fiber<?> child = Fiber.schedule(scheduler, () -> {
                    list.add("B");
                    Thread.yield();
                    list.add("B");
                });
                Thread.yield();
                list.add("A");
                child.join();
            }).join();
        } finally {
            scheduler.shutdown();
        }
        assertEquals(list, List.of("A", "B", "A", "B"));
    }

    public void testThreadYield2() {
        var list = new CopyOnWriteArrayList<String>();
        ExecutorService scheduler = Executors.newFixedThreadPool(1);
        try {
            Fiber.schedule(scheduler, () -> {
                list.add("A");
                Fiber<?> child = Fiber.schedule(scheduler, () -> {
                    list.add("B");
                });
                Object lock = new Object();
                synchronized (lock) {
                    Thread.yield();   // pinned so will be a no-op
                    list.add("A");
                }
                child.join();
            }).join();
        } finally {
            scheduler.shutdown();
        }
        assertEquals(list, List.of("A", "A", "B"));
    }

    // -- Thread.sleep --

    // Thread.sleep(-1)
    public void testThreadSleep1() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            try {
                Thread.sleep(-1);
                assertTrue(false);
            } catch (IllegalArgumentException e) {
                completed.set(true);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // Thread.sleep(0)
    public void testThreadSleep2() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            try {
                Thread.sleep(0);
                completed.set(true);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // Thread.sleep(2000)
    public void testThreadSleep3() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            try {
                long start = System.currentTimeMillis();
                Thread.sleep(2000);
                long elapsed = System.currentTimeMillis() - start;
                assertTrue(elapsed > 1900);
                completed.set(true);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // Thread.sleep with interrupt status set
    public void testThreadSleep4() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            try {
                Thread.currentThread().interrupt();
                Thread.sleep(1000);
                assertTrue(false);
            } catch (InterruptedException e) {
                completed.set(true);
            }
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // Thread.sleep interrupted while sleeping
    public void testThreadSleep5() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            Thread t = Thread.currentThread();
            try {
                Interrupter.schedule(t, 2000);
                Thread.sleep(20*1000);
                assertTrue(false);
            } catch (InterruptedException e) {
                // interrupt status should be clearer
                assertFalse(t.isInterrupted());
                completed.set(true);
            }
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // Thread.sleep should not be disrupted by unparking fiber
    public void testThreadSleep6() throws Exception {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            try {
                long start = System.currentTimeMillis();
                Thread.sleep(2000);
                long elapsed = System.currentTimeMillis() - start;
                assertTrue(elapsed > 1900);
                completed.set(true);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        // attempt to disrupt sleep
        for (int i=0; i<5; i++) {
            Thread.sleep(20);
            LockSupport.unpark(fiber);
        }
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // interrupt before Thread.sleep
    public void testThreadSleep7() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            Thread t = Thread.currentThread();
            t.interrupt();
            try {
                Thread.sleep(1000);
                assertTrue(false);
            } catch (InterruptedException e) {
                // interrupt status should be cleared
                assertFalse(t.isInterrupted());
                completed.set(true);
            }
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // interrupt during Thread.sleep
    public void testThreadSleep8() throws Exception {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            Thread t = Thread.currentThread();
            Interrupter.schedule(t, 1000);
            try {
                Thread.sleep(60*1000);
                assertTrue(false);
            } catch (InterruptedException e) {
                // interrupt status should be cleared
                assertFalse(t.isInterrupted());
                completed.set(true);
            }
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }


    // -- ThreadLocal --

    static final ThreadLocal<Object> LOCAL = new ThreadLocal<>();
    static final ThreadLocal<Object> INHERITED_LOCAL = new InheritableThreadLocal<>();

    public void testThreadLocal1() {
        for (int i = 0; i < 10; i++) {
            var completed = new AtomicBoolean();
            Fiber.schedule(() -> {
                assertTrue(LOCAL.get() == null);
                Object obj = new Object();
                LOCAL.set(obj);
                assertTrue(LOCAL.get() == obj);
                completed.set(true);
            }).awaitTermination();
            assertTrue(completed.get());
        }
    }

    public void testThreadLocal2() {
        var completed = new AtomicBoolean();
        Fiber.schedule(() -> {
            assertTrue(LOCAL.get() == null);
            Object obj = new Object();
            LOCAL.set(obj);
            try { Thread.sleep(100); } catch (InterruptedException e) { }
            assertTrue(LOCAL.get() == obj);
            completed.set(true);
        }).awaitTermination();
        assertTrue(completed.get());
    }

    public void testInheritedThreadLocal1() {
        assertTrue(INHERITED_LOCAL.get() == null);

        for (int i = 0; i < 10; i++) {
            var completed = new AtomicBoolean();
            Fiber.schedule(() -> {
                assertTrue(INHERITED_LOCAL.get() == null);
                Object obj = new Object();
                INHERITED_LOCAL.set(obj);
                assertTrue(INHERITED_LOCAL.get() == obj);
                completed.set(true);
            }).awaitTermination();
            assertTrue(completed.get());
        }

        assertTrue(INHERITED_LOCAL.get() == null);
    }

    // inherit thread local from creating thread
    public void testInheritedThreadLocal2() {
        assertTrue(INHERITED_LOCAL.get() == null);

        var obj = new Object();
        var ref = new AtomicReference<Object>();

        INHERITED_LOCAL.set(obj);
        try {
            Fiber.schedule(() -> ref.set(INHERITED_LOCAL.get())).awaitTermination();
        } finally {
            INHERITED_LOCAL.remove();
        }

        assertTrue(ref.get() == obj);
    }

    // inherit thread local from creating fiber
    public void testInheritedThreadLocal3() {
        assertTrue(INHERITED_LOCAL.get() == null);

        var obj = new Object();
        var ref = new AtomicReference<Object>();

        Fiber.schedule(() -> {
            INHERITED_LOCAL.set(obj);
            Fiber.schedule(() -> ref.set(INHERITED_LOCAL.get())).awaitTermination();
        }).awaitTermination();

        assertTrue(ref.get() == obj);
        assertTrue(INHERITED_LOCAL.get() == null);
    }

    // inherit context class loader from creating fiber
    public void testInheritedThreadLocal4() {
        assertTrue(INHERITED_LOCAL.get() == null);

        var obj = new Object();
        var ref = new AtomicReference<Object>();

        INHERITED_LOCAL.set(obj);
        try {
            Fiber.schedule(() -> {
                Fiber.schedule(() -> ref.set(INHERITED_LOCAL.get())).awaitTermination();
            }).awaitTermination();
        } finally {
            INHERITED_LOCAL.remove();
        }

        assertTrue(ref.get() == obj);
    }


    // -- Thread.set/getContextClassLoader --

    public void testThreadContextClassLoader1() {
        ClassLoader loader = new ClassLoader() { };
        var ref = new AtomicReference<ClassLoader>();

        Fiber.schedule(() -> {
            Thread t = Thread.currentThread();
            t.setContextClassLoader(loader);
            ref.set(t.getContextClassLoader());
        }).awaitTermination();

        assertTrue(ref.get() == loader);
        assertTrue(Thread.currentThread().getContextClassLoader() != loader);
    }

    // inherit context class loader from creating thread
    public void testThreadContextClassLoader2() {
        ClassLoader loader = new ClassLoader() { };
        var ref = new AtomicReference<ClassLoader>();

        Thread t = Thread.currentThread();
        ClassLoader savedLoader = t.getContextClassLoader();
        t.setContextClassLoader(loader);
        try {
            Fiber.schedule(() -> {
                ref.set(Thread.currentThread().getContextClassLoader());
            }).awaitTermination();
        } finally {
            t.setContextClassLoader(savedLoader);
        }

        assertTrue(ref.get() == loader);
    }

    // inherit context class loader from creating fiber
    public void testThreadContextClassLoader3() {
        ClassLoader loader = new ClassLoader() { };
        var ref = new AtomicReference<ClassLoader>();

        Fiber.schedule(() -> {
            Thread.currentThread().setContextClassLoader(loader);
            Fiber.schedule(() -> {
                ref.set(Thread.currentThread().getContextClassLoader());
            }).awaitTermination();
        }).awaitTermination();

        assertTrue(ref.get() == loader);
    }

    // inherit context class loader from creating fiber
    public void testThreadContextClassLoader4() {
        ClassLoader loader = new ClassLoader() { };
        var ref = new AtomicReference<ClassLoader>();

        Thread t = Thread.currentThread();
        ClassLoader savedLoader = t.getContextClassLoader();
        t.setContextClassLoader(loader);
        try {
            Fiber.schedule(() -> {
                Fiber.schedule(() -> {
                    ref.set(Thread.currentThread().getContextClassLoader());
                }).awaitTermination();
            }).awaitTermination();
        } finally {
            t.setContextClassLoader(savedLoader);
        }

        assertTrue(ref.get() == loader);
    }


    // -- Thread.setUncaughtExceptionHandler --

    public void testThreadUncaughtExceptionHandler1() {
        var completed = new AtomicBoolean();
        Fiber.schedule(() -> {
            try {
                Thread t = Thread.currentThread();
                t.setUncaughtExceptionHandler((_t, _e) -> { });
            } catch (UnsupportedOperationException e) {
                completed.set(true);
            }
        }).awaitTermination();
        assertTrue(completed.get());
    }

    @Test(expectedExceptions = { UnsupportedOperationException.class })
    public void testThreadUncaughtExceptionHandler2() {
        var ref = new AtomicReference<Thread>();
        Fiber.schedule(() -> ref.set(Thread.currentThread())).awaitTermination();
        Thread t = ref.get();
        t.setUncaughtExceptionHandler((_t, _e) -> { });
    }


    // -- Thread.getId --

    public void testThreadGetId() throws Exception {
        var ref1 = new AtomicReference<Long>();
        var ref2 = new AtomicReference<Long>();
        Fiber.schedule(() -> ref1.set(Thread.currentThread().getId()));
        Fiber.schedule(() -> ref2.set(Thread.currentThread().getId()));
        long id1 = waitForValue(ref1);
        long id2 = waitForValue(ref2);
        long id3 = Thread.currentThread().getId();
        assertTrue(id1 != id2);
        assertTrue(id1 != id3);
        assertTrue(id2 != id3);
    }


    // -- Thread.getState --

    // RUNNABLE
    public void testThreadGetState1() {
        var completed = new AtomicBoolean();
        Fiber.schedule(() -> {
            Thread.State state = Thread.currentThread().getState();
            assertTrue(state == Thread.State.RUNNABLE);
            completed.set(true);
        }).awaitTermination();
        assertTrue(completed.get());
    }

    // WAITING when parked
    public void testThreadGetState2() throws Exception {
        var ref = new AtomicReference<Thread>();
        var fiber = Fiber.schedule(() -> {
            ref.set(Thread.currentThread());
            LockSupport.park();
        });
        Thread t = waitForValue(ref);
        while (t.getState() != Thread.State.WAITING) {
            Thread.sleep(20);
        }
        LockSupport.unpark(fiber);
        fiber.awaitTermination();
    }

    // WAITING when parked and pinned
    public void testThreadGetState3() throws Exception {
        var ref = new AtomicReference<Thread>();
        var fiber = Fiber.schedule(() -> {
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
        fiber.awaitTermination();
    }

    // WAITING when blocked in Object.wait
    public void testThreadGetState4() throws Exception {
        var ref = new AtomicReference<Thread>();
        var lock = new Object();
        var fiber = Fiber.schedule(() -> {
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
        fiber.awaitTermination();
    }

    // TERMINATED
    public void testThreadGetState5() {
        var ref = new AtomicReference<Thread>();
        Fiber.schedule(() -> ref.set(Thread.currentThread())).awaitTermination();
        Thread t = ref.get();
        assertTrue(t.getState() == Thread.State.TERMINATED);
    }


    // -- Thread.holdsLock --

    public void testThreadHoldsLock1() {
        var completed = new AtomicBoolean();
        final var lock = new Object();
        var fiber = Fiber.schedule(() -> {
            assertFalse(Thread.holdsLock(lock));
            completed.set(true);
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    public void testThreadHoldsLock2() {
        var completed = new AtomicBoolean();
        final var lock = new Object();
        var fiber = Fiber.schedule(() -> {
            synchronized (lock) {
                assertTrue(Thread.holdsLock(lock));
            }
            completed.set(true);
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }


    // -- Thread.getStackTrace --

    // runnable (mounted)
    public void testThreadGetStackTrace1() throws Exception {
        var ref = new AtomicReference<Thread>();
        var sel = Selector.open();
        Fiber.schedule(() -> doSelect(ref, sel));
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
        Fiber.schedule(() -> {
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
        var fiber = Fiber.schedule(() -> {
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
    public void testThreadGetStackTrace4() {
        var ref = new AtomicReference<Thread>();
        var fiber = Fiber.schedule(() -> ref.set(Thread.currentThread()));
        fiber.awaitTermination();
        StackTraceElement[] stack = ref.get().getStackTrace();
        assertTrue(stack.length == 0);
    }

    private boolean contains(StackTraceElement[] stack, String expected) {
        return Stream.of(stack)
                .map(Object::toString)
                .anyMatch(s -> s.contains(expected));
    }

    // -- ThreadGroup --

    // ThreadGroup.enumerate should not enumerate fiber Thread obects
    public void testThreadGroup1() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            Thread current = Thread.currentThread();

            ThreadGroup g = Thread.currentThread().getThreadGroup();
            Thread[] threads = new Thread[100];
            g.enumerate(threads);
            Stream.of(threads)
                    .filter(t -> t == current)
                    .forEach(t -> assertTrue(false));
            completed.set(true);
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // ThreadGroup.interrupt should not interrupt fiber Thread obects
    public void testThreadGroup2() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            Thread t = Thread.currentThread();
            t.getThreadGroup().interrupt();
            assertFalse(t.isInterrupted());
            completed.set(true);
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // -- Object.wait/notify --

    // fiber waits, notified by thread
    public void testWaitNotify1() throws Exception {
        var ref = new AtomicReference<Thread>();
        var lock = new Object();
        var fiber = Fiber.schedule(() -> {
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
        fiber.awaitTermination();
    }

    // thread waits, notified by fiber
    public void testWaitNotify2() throws Exception {
        var lock = new Object();
        Fiber.schedule(() -> {
            synchronized (lock) {
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            lock.wait();
        }
    }

    // fiber waits, notified by other fiber
    public void testWaitNotify3() throws Exception {
        var lock = new Object();
        var fiber1 = Fiber.schedule(() -> {
            synchronized (lock) {
                try {
                    lock.wait();
                } catch (InterruptedException e) { }
            }
        });
        var fiber2 = Fiber.schedule(() -> {
            synchronized (lock) {
               lock.notifyAll();
            }
        });
        fiber1.awaitTermination();
        fiber2.awaitTermination();
    }

    // interrupt before Object.wait
    public void testWaitNotify4() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
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
                    completed.set(true);
                }
            }
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // interrupt while waiting in Object.wait
    public void testWaitNotify5() throws Exception {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
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
                    completed.set(true);
                }
            }
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }


    // -- ReentrantLock --

    // lock/unlock
    public void testReentrantLock1() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            ReentrantLock lock = new ReentrantLock();
            assertFalse(lock.isHeldByCurrentThread());
            lock.lock();
            assertTrue(lock.isHeldByCurrentThread());
            lock.unlock();
            assertFalse(lock.isHeldByCurrentThread());
            completed.set(true);
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // tryLock/unlock
    public void testReentrantLock2() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
            ReentrantLock lock = new ReentrantLock();
            assertFalse(lock.isHeldByCurrentThread());
            boolean acquired = lock.tryLock();
            assertTrue(acquired);
            assertTrue(lock.isHeldByCurrentThread());
            lock.unlock();
            assertFalse(lock.isHeldByCurrentThread());
            completed.set(true);
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // lock/lock/unlock/unlock
    public void testReentrantLock3() {
        var completed = new AtomicBoolean();
        var fiber = Fiber.schedule(() -> {
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
            completed.set(true);
        });
        fiber.awaitTermination();
        assertTrue(completed.get());
    }

    // locked by thread, fiber tries to lock
    public void testReentrantLock4() throws Exception {
        Fiber<?> fiber;
        ReentrantLock lock = new ReentrantLock();
        var holdsLock = new AtomicBoolean();

        // thread acquires lock
        lock.lock();
        try {
            fiber = Fiber.schedule(() -> {
                lock.lock();  // should block
                holdsLock.set(true);
                LockSupport.park();
                lock.unlock();
                holdsLock.set(false);
            });
            // give time for fiber to block
            fiber.awaitTermination(Duration.ofSeconds(1));
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
        var fiber = Fiber.schedule(() -> {
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

            fiber.awaitTermination();
        }
    }

    // lock by fiber, another fiber tries to lock
    public void testReentrantLock6() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        var f1HoldsLock = new AtomicBoolean();
        var fiber1 = Fiber.schedule(() -> {
            lock.lock();
            try {
                f1HoldsLock.set(true);
                LockSupport.park();
            } finally {
                lock.unlock();
            }
        });

        // wat for fiber to acquire lock
        while (!f1HoldsLock.get()) {
            Thread.sleep(20);
        }

        var f2HoldsLock = new AtomicBoolean();
        var fiber2 = Fiber.schedule(() -> {
            lock.lock();
            f2HoldsLock.set(true);
            LockSupport.park();
            lock.unlock();
            f2HoldsLock.set(false);
        });

        // f2 should block
        fiber2.awaitTermination(Duration.ofSeconds(1));
        assertFalse(f2HoldsLock.get());

        // unpark f1, f2 should acquire lock
        LockSupport.unpark(fiber1);
        while (!f2HoldsLock.get()) {
            Thread.sleep(20);
        }

        // unpark f2, f2 should release lock
        LockSupport.unpark(fiber2);
        while (f2HoldsLock.get()) {
            Thread.sleep(20);
        }
    }

    // -- GC --

    // ensure that a Fiber can be GC"ed
    public void testGC1() {
        waitUntilObjectGCed(Fiber.schedule(DO_NOTHING));
    }

    // ensure that a parked Fiber can be GC'ed
    public void testGC2() {
        waitUntilObjectGCed(Fiber.schedule(() -> LockSupport.park()));
    }

    // ensure that a terminated Fiber can be GC'ed
    public void testGC3() {
        var fiber = Fiber.schedule(DO_NOTHING);
        fiber.awaitTermination();
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
