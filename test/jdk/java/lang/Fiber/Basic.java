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
 * @run testng Basic
 * @summary Basic tests for java.lang.Fiber
 */

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.stream.Stream;

import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;
import static org.testng.Assert.*;

@Test
public class Basic {

    static final Runnable DO_NOTHING = () -> { };


    // -- basic tests ---

    public void testExecute1() {
        var executed = new AtomicBoolean();
        new Fiber(() -> executed.set(true)).schedule().await();
        assertTrue(executed.get());
    }

    public void testExecute2() {
        String s = Fiber.schedule(() -> "foo").join();
        assertTrue("foo".endsWith(s));
    }

    // throw uncaught exception
    public void testUncaughtException1() {
        var executed = new AtomicBoolean();
        var fiber = new Fiber(() -> {
            executed.set(true);
            throw new RuntimeException();
        }).schedule();
        fiber.await();
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
        var fiber = new Fiber(() -> {
            executed.set(true);
            throw new Error();
        }).schedule();
        fiber.await();
        assertTrue(executed.get());
    }

    public void testUncaughtError2() {
        try {
            Fiber.schedule(() -> { throw new Error(); }).join();
        } catch (CompletionException e) {
            assertTrue(e.getCause() instanceof Error);
        }
    }

    @Test(expectedExceptions = { IllegalStateException.class })
    public void testSchedule() {
        new Fiber(DO_NOTHING).schedule().schedule();
    }


    // -- parking --

    // fiber parks, unparked by thread
    public void testPark1() throws Exception {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            LockSupport.park();
            completed.set(true);
        }).schedule();
        Thread.sleep(1000); // give time for fiber to park
        LockSupport.unpark(f);
        f.await();
        assertTrue(completed.get());
    }

    // fiber parks, unparked by another fiber
    public void testPark2() throws Exception {
        var completed = new AtomicInteger();
        Fiber f1 = new Fiber(() -> {
            LockSupport.park();
            completed.incrementAndGet();
        }).schedule();
        Thread.sleep(1000); // give time for fiber to park
        Fiber f2 = new Fiber(() -> {
            LockSupport.unpark(f1);
            completed.incrementAndGet();
        }).schedule();
        f1.await();
        f2.await();
        assertTrue(completed.get() == 2);
    }

    // park while holding monitor
    public void testPark3() throws Exception {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            var lock = new Object();
            synchronized (lock) {
                LockSupport.park();
            }
            completed.set(true);
        }).schedule();
        Thread.sleep(1000); // give time for fiber to park
        LockSupport.unpark(f);
        f.await();
        assertTrue(completed.get());
    }

    // park with native thread on the stack
    public void testPark4() throws Exception {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            try {
                Method m = Basic.class.getDeclaredMethod("doPark");
                m.invoke(null);
                completed.set(true);
            } catch (Exception e) {
                assertTrue(false);
            }
        }).schedule();
        Thread.sleep(1000); // give time for fiber to park
        LockSupport.unpark(f);
        f.await();
        assertTrue(completed.get());
    }
    static void doPark() {
        LockSupport.park();
    }

    // unpark before park
    public void testPark5() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            LockSupport.unpark(Fiber.current().orElseThrow());
            LockSupport.park();
            completed.set(true);
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // 2 x unpark before park
    public void testPark6() throws Exception {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            Fiber me = Fiber.current().orElseThrow();
            LockSupport.unpark(me);
            LockSupport.unpark(me);
            LockSupport.park();
            LockSupport.park();  // should park
            completed.set(true);
        }).schedule();
        Thread.sleep(1000); // give time for fiber to park
        LockSupport.unpark(f);
        f.await();
        assertTrue(completed.get());
    }

    // 2 x park
    public void testPark7() throws Exception {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            LockSupport.park();
            LockSupport.park();
            completed.set(true);
        }).schedule();

        Thread.sleep(1000); // give time for fiber to park

        // unpark, fiber should park again
        LockSupport.unpark(f);
        Thread.sleep(1000);

        // let it terminate
        LockSupport.unpark(f);
        f.await();
        assertTrue(completed.get());
    }

    // interrupt before park
    public void testPark8() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            Thread t = Thread.currentThread();
            t.interrupt();
            LockSupport.park();
            assertTrue(t.isInterrupted());
            completed.set(true);
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // interrupt while parked
    public void testPark9() throws Exception {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            Thread t = Thread.currentThread();
            Interrupter.schedule(t, 1000);
            LockSupport.park();
            assertTrue(t.isInterrupted());
            completed.set(true);
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // interrupt before park (pinned park)
    public void testPark10() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            Thread t = Thread.currentThread();
            t.interrupt();
            Object lock = new Object();
            synchronized (lock) {
                LockSupport.park();
            }
            assertTrue(t.isInterrupted());
            completed.set(true);
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // interrupt while parked (pinned park)
    public void testPark11() throws Exception {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            Thread t = Thread.currentThread();
            Interrupter.schedule(t, 1000);
            Object lock = new Object();
            synchronized (lock) {
                LockSupport.park();
            }
            assertTrue(t.isInterrupted());
            completed.set(true);
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // parkNanos(-1) completes immediately
    public void testParkNanos1() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            LockSupport.parkNanos(-1);
            completed.set(true);
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // parkNanos(0) completes immediately
    public void testParkNanos2() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            LockSupport.parkNanos(0);
            completed.set(true);
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // parkNanos(1000ms) completes quickly
    public void testParkNanos3() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            // park for 1000ms
            long nanos = TimeUnit.NANOSECONDS.convert(1000, TimeUnit.MILLISECONDS);
            long start = System.nanoTime();
            LockSupport.parkNanos(nanos);

            // check that fiber parks for >= 900ms
            long elapsed = TimeUnit.MILLISECONDS.convert(System.nanoTime() - start,
                                                         TimeUnit.NANOSECONDS);
            assertTrue(elapsed >= 900);
            completed.set(true);
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // fiber parks, unparked by thread
    public void testParkNanos4() throws Exception {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            long nanos = TimeUnit.NANOSECONDS.convert(30, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
            completed.set(true);
        }).schedule();
        Thread.sleep(1000); // give time for fiber to park
        LockSupport.unpark(f);
        f.await();
        assertTrue(completed.get());
    }

    // fiber parks, unparked by another fiber
    public void testParkNanos5() throws Exception {
        var completed = new AtomicInteger();
        Fiber f1 = new Fiber(() -> {
            long nanos = TimeUnit.NANOSECONDS.convert(30, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
            completed.incrementAndGet();
        }).schedule();
        Thread.sleep(1000);  // give time for fiber to park
        Fiber f2 = new Fiber(() -> {
            LockSupport.unpark(f1);
            completed.incrementAndGet();
        }).schedule();
        f1.await();
        f2.await();
        assertTrue(completed.get() == 2);
    }

    // unpark before parkNanos
    public void testParkNanos6() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            LockSupport.unpark(Fiber.current().orElseThrow());
            long nanos = TimeUnit.NANOSECONDS.convert(30, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
            completed.set(true);
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // unpark before parkNanos(0), should consume permit
    public void testParkNanos7() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            LockSupport.unpark(Fiber.current().orElseThrow());
            LockSupport.parkNanos(0);
            LockSupport.park(); // should block
            completed.set(true);
        }).schedule();
        f.await(Duration.ofSeconds(3));
        LockSupport.unpark(f);
        f.await();
        assertTrue(completed.get());
    }


    // -- await/awaitNanos

    // await short lived fiber
    public void testAwait1() {
        var fiber = new Fiber(DO_NOTHING).schedule();
        fiber.await();
    }

    // await long lived fiber
    public void testAwait2() {
        var fiber = new Fiber(() -> {
            try {
                Thread.sleep(2*1000);
            } catch (InterruptedException e) { }
        }).schedule();
        fiber.await();
    }

    // await after terminated
    public void testAwait3() throws Exception {
        var fiber = new Fiber(DO_NOTHING).schedule();
        fiber.await();
        fiber.await();
    }

    // thread interrupted while await-ing
    public void testAwait4() {
        long nanos = TimeUnit.NANOSECONDS.convert(5, TimeUnit.SECONDS);
        var fiber = new Fiber(() -> LockSupport.parkNanos(nanos)).schedule();
        Interrupter.schedule(Thread.currentThread(), 500);
        try {
            fiber.await();
            assertTrue(Thread.interrupted());
        } finally {
            Thread.interrupted(); // make sure interrupt status is cleared
            LockSupport.unpark(fiber);
        }
    }

    // fiber interrupted while await-ing
    public void testAwait5() {
        Fiber f1 = new Fiber(() -> {
            long nanos = TimeUnit.NANOSECONDS.convert(5, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
        }).schedule();
        var completed = new AtomicBoolean();
        Fiber f2 = new Fiber(() -> {
            Interrupter.schedule(Thread.currentThread(), 500);
            f1.await();
            completed.set(true);
        }).schedule();
        try {
            f2.await();
            assertTrue(completed.get());
        } finally {
            LockSupport.unpark(f1);
        }
    }

    // awaitNanos short lived fiber
    public void testAwait6() {
        Fiber f = new Fiber(DO_NOTHING).schedule();
        boolean terminated = f.await(Duration.ofSeconds(5));
        assertTrue(terminated);
    }

    // awaitNanos long lived fiber
    public void testAwait7() {
        Fiber f = new Fiber(LockSupport::park).schedule();
        try {
            boolean terminated = f.await(Duration.ofSeconds(2));
            assertFalse(terminated);
        } finally {
            LockSupport.unpark(f);
        }
    }

    // thread interrupted while await-ing, fiber terminates
    public void testAwait8() {
        Fiber f = new Fiber(() -> {
            long nanos = TimeUnit.NANOSECONDS.convert(5, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
        }).schedule();
        Interrupter.schedule(Thread.currentThread(), 1000);
        try {
            boolean terminated = f.await(Duration.ofSeconds(60));
            assertTrue(terminated);
            assertTrue(Thread.interrupted());
        } finally {
            Thread.interrupted(); // make sure interrupt status is cleared
            LockSupport.unpark(f);
        }
    }

    // thread interrupted while await-ing, fiber does not terminate
    public void testAwait9() {
        Fiber f = new Fiber(LockSupport::park).schedule();
        Interrupter.schedule(Thread.currentThread(), 1000);
        try {
            boolean terminated = f.await(Duration.ofSeconds(3));
            assertFalse(terminated);
            assertTrue(Thread.interrupted());
        } finally {
            Thread.interrupted(); // make sure interrupt status is cleared
            LockSupport.unpark(f);
        }
    }

    // fiber interrupted while await-ing, other fiber terminates
    public void testAwait10() {
        Fiber f1 = new Fiber(() -> {
            long nanos = TimeUnit.NANOSECONDS.convert(5, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
        }).schedule();
        var completed = new AtomicBoolean();
        Fiber f2 = new Fiber(() -> {
            Interrupter.schedule(Thread.currentThread(), 1000);
            boolean terminated = f1.await(Duration.ofSeconds(60));
            assertTrue(terminated);
            assertTrue(Thread.interrupted());
            completed.set(true);
        }).schedule();
        try {
            f2.await();
            assertTrue(completed.get());
        } finally {
            LockSupport.unpark(f1);
        }
    }

    // fiber interrupted while await-ing, other fiber does not terminate
    public void testAwait11() {
        Fiber f1 = new Fiber(LockSupport::park).schedule();
        var completed = new AtomicBoolean();
        Fiber f2 = new Fiber(() -> {
            Interrupter.schedule(Thread.currentThread(), 1000);
            boolean terminated = f1.await(Duration.ofSeconds(3));
            assertFalse(terminated);
            assertTrue(Thread.interrupted());
            completed.set(true);
        }).schedule();
        try {
            f2.await();
            assertTrue(completed.get());
        } finally {
            LockSupport.unpark(f1);
        }
    }

    // await zero duration
    public void testAwait14() {
        var fiber = new Fiber(LockSupport::park).schedule();
        fiber.await(Duration.ofSeconds(0));
        LockSupport.unpark(fiber);
        fiber.await();
    }

    // await negative duration
    public void testAwait15() {
        var fiber = new Fiber(LockSupport::park).schedule();
        fiber.await(Duration.ofSeconds(-1));
        LockSupport.unpark(fiber);
        fiber.await();
    }

    @Test(expectedExceptions = { IllegalStateException.class })
    public void testAwait12() {
        new Fiber(DO_NOTHING).await();
    }

    @Test(expectedExceptions = { IllegalStateException.class })
    public void testAwait13() {
        new Fiber(DO_NOTHING).await(Duration.ofSeconds(1));
    }


    // -- current --

    public void testCurrent() {
        var ref = new AtomicReference<Fiber>();
        var fiber = new Fiber(() -> ref.set(Fiber.current().orElse(null))).schedule();
        fiber.await();
        assertTrue(ref.get() == fiber);
    }


    // -- cancellation --

    public void testCancel1() {
        var fiber  = new Fiber(LockSupport::park);
        assertFalse(fiber.isCancelled());
        fiber.cancel();
        assertTrue(fiber.isCancelled());
        fiber.schedule();
        fiber.await();
        assertTrue(fiber.isCancelled());
    }

    public void testCancel2() {
        var fiber  = new Fiber(LockSupport::park).schedule();
        assertFalse(fiber.isCancelled());
        fiber.cancel();
        assertTrue(fiber.isCancelled());
        fiber.await();
        assertTrue(fiber.isCancelled());
    }

    // -- isAlive --

    public void testIsAlive() {
        var fiber = new Fiber(LockSupport::park);
        assertFalse(fiber.isAlive());
        fiber.schedule();
        assertTrue(fiber.isAlive());
        LockSupport.unpark(fiber);
        fiber.await();
        assertFalse(fiber.isAlive());
    }


    // -- nulls --

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull1() {
        new Fiber(null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull2() {
        new Fiber(null, () -> { });
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull3() {
        ExecutorService scheduler = Executors.newCachedThreadPool();
        try {
            new Fiber(scheduler, null);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull4() {
        Fiber.schedule(null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull5() {
        Fiber.schedule(null, () -> null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull6() {
        ExecutorService scheduler = Executors.newCachedThreadPool();
        try {
            Fiber.schedule(scheduler, null);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull7() {
        var fiber = new Fiber(() -> { }).schedule();
        fiber.await(null);
    }


    // -- Thread.currentThread --

    //  Thread.currentThread before/after park
    public void testCurrentThread1() throws Exception {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            Thread t = Thread.currentThread();  // before park
            LockSupport.park();
            assertTrue(Thread.currentThread() == t);  // after park
            completed.set(true);
        }).schedule();
        Thread.sleep(1000); // give time for fiber to park
        LockSupport.unpark(f);
        f.await();
        assertTrue(completed.get());
    }

    //  Thread.currentThread before/after synchronized block
    public void testCurrentThread2() throws Exception {
        var completed = new AtomicBoolean();
        var lock = new Object();
        Fiber f;
        synchronized (lock) {
            f = new Fiber(() -> {
                Thread t = Thread.currentThread();  // before synchronized
                synchronized (lock) { }
                assertTrue(Thread.currentThread() == t);  // after synchronized
                completed.set(true);
            }).schedule();
            Thread.sleep(200); // give time for fiber to block
        }
        f.await();
        assertTrue(completed.get());
    }

    //  Thread.currentThread before/after lock
    public void testCurrentThread3() throws Exception {
        var completed = new AtomicBoolean();
        var lock = new ReentrantLock();
        Fiber f;
        lock.lock();
        try {
            f = new Fiber(() -> {
                Thread t = Thread.currentThread();  // before lock
                lock.lock();
                lock.unlock();
                assertTrue(Thread.currentThread() == t);  // after lock
                completed.set(true);
            }).schedule();
            Thread.sleep(200); // give time for fiber to block
        } finally {
            lock.unlock();
        }
        f.await();
        assertTrue(completed.get());
    }


    // -- Thread.start/stop/suspend/resume --

    public void testStart() {
        var completed = new AtomicBoolean();
        new Fiber(() -> {
            Thread t = Thread.currentThread();
            try {
                t.start();
                assertTrue(false);
            } catch (IllegalStateException expected) {
                completed.set(true);
            }
        }).schedule().await();
        assertTrue(completed.get());
    }

    public void testStop() {
        var completed = new AtomicBoolean();
        new Fiber(() -> {
            Thread t = Thread.currentThread();
            try {
                t.stop();
                assertTrue(false);
            } catch (UnsupportedOperationException expected) {
                completed.set(true);
            }
        }).schedule().await();
        assertTrue(completed.get());
    }

    public void testSuspend() {
        var completed = new AtomicBoolean();
        new Fiber(() -> {
            Thread t = Thread.currentThread();
            try {
                t.suspend();
                assertTrue(false);
            } catch (UnsupportedOperationException expected) {
                completed.set(true);
            }
        }).schedule().await();
        assertTrue(completed.get());
    }

    public void testResume() {
        var completed = new AtomicBoolean();
        new Fiber(() -> {
            Thread t = Thread.currentThread();
            try {
                t.resume();
                assertTrue(false);
            } catch (UnsupportedOperationException expected) {
                completed.set(true);
            }
        }).schedule().await();
        assertTrue(completed.get());
    }


    // -- Thread.join --

    // thread invokes join to wait for fiber to terminate
    public void testJoin1() throws Exception {
        var ref = new AtomicReference<Thread>();
        new Fiber(() -> ref.set(Thread.currentThread())).schedule();
        Thread t = waitForValue(ref);
        t.join();
    }

    // fiber invokes join to wait for another fiber to terminate
    public void testJoin2() {
        var ref = new AtomicReference<Thread>();
        new Fiber(() -> ref.set(Thread.currentThread())).schedule();
        var completed = new AtomicBoolean();
        Fiber f2 = new Fiber(() -> {
            try {
                waitForValue(ref).join();
                completed.set(true);
            } catch (InterruptedException e) { }
        }).schedule();
        f2.await();
        assertTrue(completed.get());
    }

    // thread invokes join(millis) to wait for fiber to terminate
    public void testJoin3() throws Exception {
        var ref = new AtomicReference<Thread>();
        new Fiber(() -> ref.set(Thread.currentThread())).schedule();
        Thread t = waitForValue(ref);
        t.join(10*1000);
    }

    // fiber invokes join(millis) to wait for another fiber to terminate
    public void testJoin4() {
        var ref = new AtomicReference<Thread>();
        new Fiber(() -> ref.set(Thread.currentThread())).schedule();
        var completed = new AtomicBoolean();
        Fiber f2 = new Fiber(() -> {
            try {
                Thread t = waitForValue(ref);
                t.join(10*1000);
                completed.set(true);
            } catch (InterruptedException e) { }
        }).schedule();
        f2.await();
        assertTrue(completed.get());
    }

    // thread invokes join(millis), fiber does not terminate
    public void testJoin5() throws Exception {
        var ref = new AtomicReference<Thread>();
        Fiber f = new Fiber(() -> {
            ref.set(Thread.currentThread());
            LockSupport.park();
        }).schedule();
        Thread t = waitForValue(ref);
        try {
            t.join(2*1000);
        } finally {
            LockSupport.unpark(f);
        }
    }

    // fiber invokes join(millis) to wait for other fiber that does not terminate
    public void testJoin6() {
        var ref = new AtomicReference<Thread>();
        Fiber f1 = new Fiber(() -> {
            ref.set(Thread.currentThread());
            LockSupport.park();
        }).schedule();
        var completed = new AtomicBoolean();
        Fiber f2 = new Fiber(() -> {
            try {
                Thread t = waitForValue(ref);
                t.join(2*1000);
                completed.set(true);
            } catch (InterruptedException e) { }
        }).schedule();
        try {
            f2.await();
            assertTrue(completed.get());
        } finally {
            LockSupport.unpark(f1);
        }
    }

    // interrupt before Thread.join main thread
    public void testJoin7() {
        Thread mainThread = Thread.currentThread();
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
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
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // interrupt before Thread.join current thread
    public void testJoin8() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
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
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // interrupt while in Thread.join
    public void testJoin9() throws Exception {
        Thread mainThread = Thread.currentThread();
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
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
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // interrupt while in Thread.join current thread
    public void testJoin10() throws Exception {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
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
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // join with negative timeout
    @Test(expectedExceptions = { IllegalArgumentException.class })
    public void testJoin11() throws Exception {
        var ref = new AtomicReference<Thread>();
        new Fiber(() -> ref.set(Thread.currentThread())).schedule();
        Thread t = waitForValue(ref);
        t.join(-1);
    }


    // -- Thread.sleep --

    // Thread.sleep(-1)
    public void testSleep1() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            try {
                Thread.sleep(-1);
                assertTrue(false);
            } catch (IllegalArgumentException e) {
                completed.set(true);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // Thread.sleep(0)
    public void testSleep2() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            try {
                Thread.sleep(0);
                completed.set(true);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // Thread.sleep(2000)
    public void testSleep3() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            try {
                long start = System.currentTimeMillis();
                Thread.sleep(2000);
                long elapsed = System.currentTimeMillis() - start;
                assertTrue(elapsed > 1900);
                completed.set(true);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // Thread.sleep with interrupt status set
    public void testSleep4() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            try {
                Thread.currentThread().interrupt();
                Thread.sleep(1000);
                assertTrue(false);
            } catch (InterruptedException e) {
                completed.set(true);
            }
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // Thread.sleep interrupted while sleeping
    public void testSleep5() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
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
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // Thread.sleep should not be disrupted by unparking fiber
    public void testSleep6() throws Exception {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            try {
                long start = System.currentTimeMillis();
                Thread.sleep(2000);
                long elapsed = System.currentTimeMillis() - start;
                assertTrue(elapsed > 1900);
                completed.set(true);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).schedule();
        // attempt to disrupt sleep
        for (int i=0; i<5; i++) {
            Thread.sleep(20);
            LockSupport.unpark(f);
        }
        f.await();
        assertTrue(completed.get());
    }

    // interrupt before Thread.sleep
    public void testSleep7() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
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
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // interrupt during Thread.sleep
    public void testSleep8() throws Exception {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
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
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }


    // -- ThreadLocal --

    static final ThreadLocal<Object> LOCAL = new ThreadLocal<>();
    static final ThreadLocal<Object> INHERITED_LOCAL = new InheritableThreadLocal<>();

    public void testThreadLocal1() {
        for (int i = 0; i < 10; i++) {
            var completed = new AtomicBoolean();
            new Fiber(() -> {
                assertTrue(LOCAL.get() == null);
                Object obj = new Object();
                LOCAL.set(obj);
                assertTrue(LOCAL.get() == obj);
                completed.set(true);
            }).schedule().await();
            assertTrue(completed.get());
        }
    }

    public void testThreadLocal2() {
        var completed = new AtomicBoolean();
        new Fiber(() -> {
            assertTrue(LOCAL.get() == null);
            Object obj = new Object();
            LOCAL.set(obj);
            try { Thread.sleep(100); } catch (InterruptedException e) { }
            assertTrue(LOCAL.get() == obj);
            completed.set(true);
        }).schedule().await();
        assertTrue(completed.get());
    }

    public void testInheritedThreadLocal1() {
        assertTrue(INHERITED_LOCAL.get() == null);

        for (int i = 0; i < 10; i++) {
            var completed = new AtomicBoolean();
            new Fiber(() -> {
                assertTrue(INHERITED_LOCAL.get() == null);
                Object obj = new Object();
                INHERITED_LOCAL.set(obj);
                assertTrue(INHERITED_LOCAL.get() == obj);
                completed.set(true);
            }).schedule().await();
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
            new Fiber(() -> ref.set(INHERITED_LOCAL.get())).schedule().await();
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

        new Fiber(() -> {
            INHERITED_LOCAL.set(obj);
            new Fiber(() -> ref.set(INHERITED_LOCAL.get())).schedule().await();
        }).schedule().await();

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
            new Fiber(() -> {
                new Fiber(() -> ref.set(INHERITED_LOCAL.get())).schedule().await();
            }).schedule().await();
        } finally {
            INHERITED_LOCAL.remove();
        }

        assertTrue(ref.get() == obj);
    }


    // -- Thread.set/getContextClassLoader --

    public void testThreadContextClassLoader1() {
        ClassLoader loader = new ClassLoader() { };
        var ref = new AtomicReference<ClassLoader>();

        new Fiber(() -> {
            Thread t = Thread.currentThread();
            t.setContextClassLoader(loader);
            ref.set(t.getContextClassLoader());
        }).schedule().await();

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
            new Fiber(() -> {
                ref.set(Thread.currentThread().getContextClassLoader());
            }).schedule().await();
        } finally {
            t.setContextClassLoader(savedLoader);
        }

        assertTrue(ref.get() == loader);
    }

    // inherit context class loader from creating fiber
    public void testThreadContextClassLoader3() {
        ClassLoader loader = new ClassLoader() { };
        var ref = new AtomicReference<ClassLoader>();

        new Fiber(() -> {
            Thread.currentThread().setContextClassLoader(loader);
            new Fiber(() -> {
                ref.set(Thread.currentThread().getContextClassLoader());
            }).schedule().await();
        }).schedule().await();

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
            new Fiber(() -> {
                new Fiber(() -> {
                    ref.set(Thread.currentThread().getContextClassLoader());
                }).schedule().await();
            }).schedule().await();
        } finally {
            t.setContextClassLoader(savedLoader);
        }

        assertTrue(ref.get() == loader);
    }


    // -- Thread.setUncaughtExceptionHandler --

    public void testThreadUncaughtExceptionHandler1() {
        var completed = new AtomicBoolean();
        new Fiber(() -> {
            try {
                Thread t = Thread.currentThread();
                t.setUncaughtExceptionHandler((_t, _e) -> { });
            } catch (UnsupportedOperationException e) {
                completed.set(true);
            }
        }).schedule().await();
        assertTrue(completed.get());
    }

    @Test(expectedExceptions = { UnsupportedOperationException.class })
    public void testThreadUncaughtExceptionHandler2() {
        var ref = new AtomicReference<Thread>();
        new Fiber(() -> ref.set(Thread.currentThread())).schedule().await();
        Thread t = ref.get();
        t.setUncaughtExceptionHandler((_t, _e) -> { });
    }


    // -- Thread.getId --

    public void testGetId() throws Exception {
        var ref1 = new AtomicReference<Long>();
        var ref2 = new AtomicReference<Long>();
        new Fiber(() -> ref1.set(Thread.currentThread().getId())).schedule();
        new Fiber(() -> ref2.set(Thread.currentThread().getId())).schedule();
        long id1 = waitForValue(ref1);
        long id2 = waitForValue(ref2);
        long id3 = Thread.currentThread().getId();
        assertTrue(id1 != id2);
        assertTrue(id1 != id3);
        assertTrue(id2 != id3);
    }


    // -- Thread.getState --

    // RUNNABLE
    public void testGetState1() {
        var completed = new AtomicBoolean();
        new Fiber(() -> {
            Thread.State state = Thread.currentThread().getState();
            assertTrue(state == Thread.State.RUNNABLE);
            completed.set(true);
        }).schedule().await();
        assertTrue(completed.get());
    }

    // WAITING when parked
    public void testGetState2() throws Exception {
        var ref = new AtomicReference<Thread>();
        Fiber f = new Fiber(() -> {
            ref.set(Thread.currentThread());
            LockSupport.park();
        }).schedule();
        Thread t = waitForValue(ref);
        while (t.getState() != Thread.State.WAITING) {
            Thread.sleep(20);
        }
        LockSupport.unpark(f);
        f.await();
    }

    // WAITING when parked and pinned
    public void testGetState3() throws Exception {
        var ref = new AtomicReference<Thread>();
        Fiber f = new Fiber(() -> {
            ref.set(Thread.currentThread());
            var lock = new Object();
            synchronized (lock) {
                LockSupport.park();
            }
        }).schedule();
        Thread t = waitForValue(ref);
        while (t.getState() != Thread.State.WAITING) {
            Thread.sleep(20);
        }
        LockSupport.unpark(f);
        f.await();
    }

    // WAITING when blocked in Object.wait
    public void testGetState4() throws Exception {
        var ref = new AtomicReference<Thread>();
        var lock = new Object();
        Fiber f = new Fiber(() -> {
            ref.set(Thread.currentThread());
            synchronized (lock) {
                try { lock.wait(); } catch (InterruptedException e) { }
            }
        }).schedule();
        Thread t = waitForValue(ref);
        while (t.getState() != Thread.State.WAITING) {
            Thread.sleep(20);
        }
        t.interrupt();
        f.await();
    }

    // TERMINATED
    public void testGetState5() {
        var ref = new AtomicReference<Thread>();
        new Fiber(() -> ref.set(Thread.currentThread())).schedule().await();
        Thread t = ref.get();
        assertTrue(t.getState() == Thread.State.TERMINATED);
    }


    // -- Thread.holdsLock --

    public void testHoldsLock1() {
        var completed = new AtomicBoolean();
        final var lock = new Object();
        Fiber f = new Fiber(() -> {
            assertFalse(Thread.holdsLock(lock));
            completed.set(true);
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    public void testHoldsLock2() {
        var completed = new AtomicBoolean();
        final var lock = new Object();
        Fiber f = new Fiber(() -> {
            synchronized (lock) {
                assertTrue(Thread.holdsLock(lock));
            }
            completed.set(true);
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }


    // -- ThreadGroup --

    // ThreadGroup.enumerate should not enumerate fiber Thread obects
    public void testThreadGroup1() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            Thread current = Thread.currentThread();

            ThreadGroup g = Thread.currentThread().getThreadGroup();
            Thread[] threads = new Thread[100];
            g.enumerate(threads);
            Stream.of(threads)
                    .filter(t -> t == current)
                    .forEach(t -> assertTrue(false));
            completed.set(true);
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // ThreadGroup.interrupt should not interrupt fiber Thread obects
    public void testThreadGroup2() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            Thread t = Thread.currentThread();
            t.getThreadGroup().interrupt();
            assertFalse(t.isInterrupted());
            completed.set(true);
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }


    // -- Object.wait/notify --

    // fiber waits, notified by thread
    public void testWaitNotify1() throws Exception {
        var ref = new AtomicReference<Thread>();
        var lock = new Object();
        Fiber f = new Fiber(() -> {
            ref.set(Thread.currentThread());
            synchronized (lock) {
                try {
                    lock.wait();
                } catch (InterruptedException e) { }
            }
        }).schedule();

        // spin until the fiber waiting
        Thread t = waitForValue(ref);
        while (t.getState() != Thread.State.WAITING) {
            Thread.sleep(10);
        }

        // thread invokes notify
        synchronized (lock) {
            lock.notifyAll();
        }
        f.await();
    }

    // thread waits, notified by fiber
    public void testWaitNotify2() throws Exception {
        var lock = new Object();
        new Fiber(() -> {
            synchronized (lock) {
                lock.notifyAll();
            }
        }).schedule();
        synchronized (lock) {
            lock.wait();
        }
    }

    // fiber waits, notified by other fiber
    public void testWaitNotify3() throws Exception {
        var lock = new Object();
        Fiber f1 = new Fiber(() -> {
            synchronized (lock) {
                try {
                    lock.wait();
                } catch (InterruptedException e) { }
            }
        }).schedule();
        Fiber f2 = new Fiber(() -> {
            synchronized (lock) {
               lock.notifyAll();
            }
        }).schedule();
        f1.await();
        f2.await();
    }

    // interrupt before Object.wait
    public void testWaitNotify4() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
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
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // interrupt while waiting in Object.wait
    public void testWaitNotify5() throws Exception {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
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
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }


    // -- ReentrantLock --

    // lock/unlock
    public void testReentrantLock1() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            ReentrantLock lock = new ReentrantLock();
            assertFalse(lock.isHeldByCurrentThread());
            lock.lock();
            assertTrue(lock.isHeldByCurrentThread());
            lock.unlock();
            assertFalse(lock.isHeldByCurrentThread());
            completed.set(true);
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // tryLock/unlock
    public void testReentrantLock2() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
            ReentrantLock lock = new ReentrantLock();
            assertFalse(lock.isHeldByCurrentThread());
            boolean acquired = lock.tryLock();
            assertTrue(acquired);
            assertTrue(lock.isHeldByCurrentThread());
            lock.unlock();
            assertFalse(lock.isHeldByCurrentThread());
            completed.set(true);
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // lock/lock/unlock/unlock
    public void testReentrantLock3() {
        var completed = new AtomicBoolean();
        Fiber f = new Fiber(() -> {
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
        }).schedule();
        f.await();
        assertTrue(completed.get());
    }

    // locked by thread, fiber tries to lock
    public void testReentrantLock4() throws Exception {
        Fiber f;
        ReentrantLock lock = new ReentrantLock();
        var holdsLock = new AtomicBoolean();

        // thread acquires lock
        lock.lock();
        try {
            f = new Fiber(() -> {
                lock.lock();  // should block
                holdsLock.set(true);
                LockSupport.park();
                lock.unlock();
                holdsLock.set(false);
            }).schedule();
            // give time for fiber to block
            f.await(Duration.ofSeconds(1));
            assertFalse(holdsLock.get());
        } finally {
            lock.unlock();
        }

        // fiber should acquire lock, park, unpark, and then release lock
        while (!holdsLock.get()) {
            Thread.sleep(20);
        }
        LockSupport.unpark(f);
        while (holdsLock.get()) {
            Thread.sleep(20);
        }
    }

    // locked by fiber, thread tries to lock
    public void testReentrantLock5() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        Fiber f = new Fiber(() -> {
            lock.lock();
            try {
                LockSupport.park();
            } finally {
                lock.unlock();
            }
        }).schedule();

        // wat for fiber to acquire lock
        while (!lock.isLocked()) {
            Thread.sleep(20);
        }

        // thread cannot acquire lock
        try {
            assertFalse(lock.tryLock());
        } finally {
            // fiber should unlock
            LockSupport.unpark(f);

            // thread should be able to acquire lock
            lock.lock();
            lock.unlock();

            f.await();
        }
    }

    // lock by fiber, another fiber tries to lock
    public void testReentrantLock6() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        var f1HoldsLock = new AtomicBoolean();
        Fiber f1 = new Fiber(() -> {
            lock.lock();
            try {
                f1HoldsLock.set(true);
                LockSupport.park();
            } finally {
                lock.unlock();
            }
        }).schedule();

        // wat for fiber to acquire lock
        while (!f1HoldsLock.get()) {
            Thread.sleep(20);
        }

        var f2HoldsLock = new AtomicBoolean();
        Fiber f2 = new Fiber(() -> {
            lock.lock();
            f2HoldsLock.set(true);
            LockSupport.park();
            lock.unlock();
            f2HoldsLock.set(false);
        }).schedule();

        // f2 should block
        f2.await(Duration.ofSeconds(1));
        assertFalse(f2HoldsLock.get());

        // unpark f1, f2 should acquire lock
        LockSupport.unpark(f1);
        while (!f2HoldsLock.get()) {
            Thread.sleep(20);
        }

        // unpark f2, f2 should release lock
        LockSupport.unpark(f2);
        while (f2HoldsLock.get()) {
            Thread.sleep(20);
        }
    }

    // -- GC --

    // ensure that a Fiber can be GC"ed
    public void testGC1() {
        waitUntilObjectGCed(new Fiber(DO_NOTHING));
    }

    // ensure that a parked Fiber can be GC'ed
    public void testGC2() {
        waitUntilObjectGCed(new Fiber(LockSupport::park).schedule());
    }

    // ensure that a terminated Fiber can be GC'ed
    public void testGC3() {
        Fiber f = new Fiber(DO_NOTHING).schedule();
        f.await();
        var ref = new WeakReference<Fiber>(f);
        f = null;
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
