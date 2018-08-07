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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import jdk.internal.misc.Unsafe;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * A lightweight strand.  A Fiber is a <i>user mode</i> strand, it is always
 * scheduled by the Java virtual machine rather than the operating system.
 */

public final class Fiber extends Strand {
    private static final ContinuationScope FIBER_SCOPE = new ContinuationScope() { };
    private static final Executor DEFAULT_SCHEDULER = defaultScheduler();
    private static final ScheduledExecutorService UNPARKER = delayedTaskScheduler();

    private static final VarHandle STATE;
    private static final VarHandle PARK_PERMIT;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            STATE = l.findVarHandle(Fiber.class, "state", short.class);
            PARK_PERMIT = l.findVarHandle(Fiber.class, "parkPermit", boolean.class);
        } catch (Exception e) {
            throw new InternalError(e);
        }
    }

    // scheduler and continuation
    private final Executor scheduler;
    private final Continuation cont;
    private final Runnable runContinuation;

    // carrier thread when mounted
    private volatile Thread carrierThread;

    // fiber state
    private static final short ST_NEW      = 0;
    private static final short ST_RUNNABLE = 1;
    private static final short ST_PARKING  = 2;
    private static final short ST_PARKED   = 3;
    private static final short ST_PINNED   = 4;
    private static final short ST_TERMINATED = 99;
    private volatile short state;

    // park/unpark and await support
    private final ReentrantLock lock = new ReentrantLock();
    private Condition parking;            // created lazily
    private Condition termination;        // created lazily
    private volatile boolean parkPermit;
    private volatile Object parkBlocker;  // used by LockSupport

    // java.lang.Thread integration
    private volatile ShadowThread shadowThread;  // created lazily
    private volatile InheritableThreadContext inheritableThreadContext;

    /**
     * Creates a new {@code Fiber} to run the given task with the given scheduler.
     *
     * @param scheduler the scheduler
     * @param task the task to execute
     * @throws SecurityManager if a security manager is set and it denies
     *         {@link RuntimePermission}{@code ("fiberScheduler")}
     * @throws NullPointerException if the scheduler or task is {@code null}
     */
    private Fiber(Executor scheduler, Runnable task) {
        Objects.requireNonNull(scheduler);
        Objects.requireNonNull(task);

        SecurityManager sm;
        if (scheduler != DEFAULT_SCHEDULER && (sm = System.getSecurityManager()) != null) {
            sm.checkPermission(new RuntimePermission("fiberScheduler"));
        }

        this.scheduler = scheduler;
        this.cont = new Continuation(FIBER_SCOPE, task) {
            @Override
            protected void onPinned(int reason) { yieldFailed(); }
        };
        this.runContinuation = this::runContinuation;

        // Inheritable context from creating thread or fiber
        InheritableThreadContext ctxt = null;
        Thread parentThread = Thread.currentCarrierThread();
        Fiber parentFiber = parentThread.getFiber();
        if (parentFiber != null) {
            parentThread = parentFiber.shadowThreadOrNull();
            if (parentThread == null) {
                ctxt = parentFiber.inheritableThreadContext;
                if (ctxt == null) {
                    // context has been cleared by creating the shadow thread
                    parentThread = parentFiber.shadowThreadOrNull();
                    assert parentThread != null;
                }
            }
        }
        if (parentThread != null) {
            this.inheritableThreadContext = new InheritableThreadContext(parentThread);
        } else {
            assert ctxt != null;
            this.inheritableThreadContext = ctxt;
        }
    }

    /**
     * Creates a new {@code Fiber} to run the given task with the default
     * scheduler and starts its execution.
     *
     * @param task the task to execute
     * @return the fiber
     * @throws NullPointerException if task is {@code null}
     */
    public static Fiber execute(Runnable task) {
        return execute(DEFAULT_SCHEDULER, task);
    }

    /**
     * Creates a new {@code Fiber} to run the given task with the given
     * scheduler and starts its execution.
     *
     * @param scheduler the scheduler
     * @param task the task to execute
     * @return the fiber
     * @throws RejectedExecutionException if the scheduler cannot accept a task
     * @throws SecurityManager if a security manager is set and it denies
     *         {@link RuntimePermission}{@code ("fiberScheduler")}
     * @throws NullPointerException if the scheduler or task is {@code null}
     */
    public static Fiber execute(Executor scheduler, Runnable task) {
        Fiber f = new Fiber(scheduler, task);

        // switch to carrier thread when submitting task. Revisit this when
        // ForkJoinPool is updated to reduce use of Thread.currentThread.
        Thread t = Thread.currentCarrierThread();
        Fiber fiber = t.getFiber();
        if (fiber != null) t.setFiber(null);
        try {
            scheduler.execute(f.runContinuation);
        } finally {
            if (fiber != null) t.setFiber(fiber);
        }

        return f;
    }

    /**
     * Runs or continues execution of the continuation on the current thread.
     */
    private void runContinuation() {
        assert Thread.currentCarrierThread().getFiber() == null;

        // set state to ST_RUNNING
        if (!stateCompareAndSet(ST_NEW, ST_RUNNABLE)) {
            // continue on this carrier thread if fiber was parked
            if (stateCompareAndSet(ST_PARKED, ST_RUNNABLE)) {
                parkPermitGetAndSet(false);  // consume parking permit
            } else {
                return;
            }
        }

        mount();
        try {
            cont.run();
        } finally {
            unmount();
            if (cont.isDone()) {
                afterTerminate();
            } else {
                afterYield();
            }
        }
    }

    /**
     * Mounts this fiber. This method must be invoked before the continuation
     * is run or continued. It binds the fiber to the current carrier thread.
     */
    private void mount() {
        Thread t = Thread.currentCarrierThread();

        // sets the carrier thread
        carrierThread = t;

        // notify the shadow thread so that the interrupt status can be forwarded
        ShadowThread st = this.shadowThread;
        if (st != null) st.onMount(t);

        // set the fiber so that Thread.currentThread() returns the Fiber object
        t.setFiber(this);
    }

    /**
     * Unmounts this fiber. This method must be invoked after the continuation
     * yields or terminates. It unbinds this fiber from the carrier thread.
     */
    private void unmount() {
        Thread t = Thread.currentCarrierThread();

        // drop connection between this fiber and the carrier thread
        t.setFiber(null);
        carrierThread = null;

        // notify shadow thread so that interrupt status is cleared
        ShadowThread st = this.shadowThread;
        if (st != null) st.onUnmount(t);
    }

    /**
     * Invoke after yielding to set the state to ST_PARKED and notify any
     * threads waiting for the fiber to park.
     */
    private void afterYield() {
        int oldState = stateGetAndSet(ST_PARKED);
        assert oldState == ST_PARKING;

        // signal anyone waiting for this fiber to park
        signalParking();
    }

    /**
     * Invoke after the continuation completes to set the state to ST_TERMINATED
     * and notify anyone waiting for the fiber to terminate.
     */
    private void afterTerminate() {
        int oldState = stateGetAndSet(ST_TERMINATED);
        assert oldState == ST_RUNNABLE;

        // notify anyone waiting for this fiber to terminate
        lock.lock();
        try {
            Condition termination = this.termination;
            if (termination != null) {
                termination.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Invoked by onPinned when the continuation cannot yield due to a
     * synchronized or native frame on the continuation stack. This method sets
     * the fiber state to ST_PINNED and parks the carrier thread.
     */
    private void yieldFailed() {
        // switch to carrier thread
        Thread t = Thread.currentCarrierThread();
        t.setFiber(null);

        boolean parkInterrupted = false;
        lock.lock();
        try {
            if (!stateCompareAndSet(ST_PARKING, ST_PINNED))
                throw new InternalError();

            Condition parking = parkingCondition();

            // signal anyone waiting for this fiber to park
            parking.signalAll();

            // and wait to be unparked (may be interrupted)
            parkingCondition().await();

        } catch (InterruptedException e) {
            parkInterrupted = true;
        } finally {
            lock.unlock();

            // continue running on the carrier thread
            if (!stateCompareAndSet(ST_PINNED, ST_RUNNABLE))
                throw new InternalError();

            // consume parking permit
            parkPermitGetAndSet(false);

            // switch back to fiber
            t.setFiber(this);
        }

        // restore interrupt status
        if (parkInterrupted)
            Thread.currentThread().interrupt();
    }

    /**
     * Disables the current fiber for scheduling purposes.
     *
     * <p> If this fiber has already been {@link #unpark() unparked} then the
     * parking permit is consumed and this method completes immediately;
     * otherwise the current fiber is disabled for scheduling purposes and lies
     * dormant until it is {@link #unpark() unparked} or the thread is
     * {@link Thread#interrupt() interrupted}.
     *
     * @throws IllegalCallerException if not called from a fiber
     */
    public static void park() {
        Fiber fiber = Thread.currentCarrierThread().getFiber();
        if (fiber == null)
            throw new IllegalCallerException();
        fiber.maybePark();
    }

    /**
     * Disables the current fiber for scheduling purposes for up to the
     * specified waiting time.
     *
     * <p> If this fiber has already been {@link #unpark() unparked} then the
     * parking permit is consumed and this method completes immediately;
     * otherwise if the time to wait is greater than zero then the current fiber
     * is disabled for scheduling purposes and lies dormant until it is {@link
     * #unpark unparked}, the waiting time elapses or the thread is
     * {@link Thread#interrupt() interrupted}.
     *
     * @param nanos the maximum number of nanoseconds to wait.
     *
     * @throws IllegalCallerException if not called from a fiber
     */
    public static void parkNanos(long nanos) {
        Thread t = Thread.currentCarrierThread();
        Fiber fiber = t.getFiber();
        if (fiber == null)
            throw new IllegalCallerException("not a fiber");
        if (nanos > 0) {
            // switch to carrier thread when submitting task to unpark
            t.setFiber(null);
            Future<?> unparker;
            try {
                unparker = UNPARKER.schedule(fiber::unpark, nanos, NANOSECONDS);
            } finally {
                t.setFiber(fiber);
            }
            // now park
            try {
                fiber.maybePark();
            } finally {
                unparker.cancel(false);
            }
        } else {
            // consume permit when not parking
            fiber.parkPermitGetAndSet(false);
        }
    }

    /**
     * Park or complete immediately.
     *
     * <p> If this fiber has already been unparked or the Thread's interrupt
     * status is set then this method completes immediately; otherwise it yields.
     */
    private void maybePark() {
        assert Thread.currentCarrierThread().getFiber() == this;

        // prepare to park; important to do this before consuming the parking permit
        if (!stateCompareAndSet(ST_RUNNABLE, ST_PARKING))
            throw new InternalError();

        // consume permit if available, and continue rather than park
        Thread st = this.shadowThread;
        boolean interrupted = (st != null) && st.isInterrupted();
        if (parkPermitGetAndSet(false) || interrupted) {
            if (!stateCompareAndSet(ST_PARKING, ST_RUNNABLE))
                throw new InternalError();

            // signal anyone waiting for this fiber to park
            signalParking();
            return;
        }

        Continuation.yield(FIBER_SCOPE);

        // continued
        assert stateGet() == ST_RUNNABLE;
    }

    /**
     * Re-enables this fiber for scheduling. If the fiber was {@link #park()
     * parked} then it will be unblocked, otherwise its next call to {@code park}
     * or {@link #parkNanos(long) parkNanos} is guaranteed not to block.
     *
     * @throws RejectedExecutionException if using a scheduler and it cannot
     *         accept a task
     * @return this fiber
     */
    public Fiber unpark() {
        Thread t = Thread.currentCarrierThread();
        Fiber fiber = t.getFiber();
        if (!parkPermitGetAndSet(true) && fiber != this) {
            int s = waitIfParking();
            if (s == ST_PARKED) {
                // switch to carrier thread when submitting task to continue
                if (fiber != null) t.setFiber(null);
                try {
                    scheduler.execute(runContinuation);
                } finally {
                    if (fiber != null) t.setFiber(fiber);
                }
            }
        }
        return this;
    }

    /**
     * If this fiber is parking then wait for it to exit the ST_PARKING state.
     * If the fiber is pinned then signal it to continue on the original carrier
     * thread.
     *
     * @return the fiber state
     */
    private int waitIfParking() {
        int s;
        int spins = 0;
        while (((s = stateGet()) == ST_PARKING) && (spins < 32)) {
            Thread.onSpinWait();
            spins++;
        }
        if (s == ST_PARKING || s == ST_PINNED) {
            boolean parkInterrupted = false;
            Thread t = Thread.currentCarrierThread();
            Fiber f = t.getFiber();
            if (f != null) t.setFiber(null);
            lock.lock();
            try {
                while ((s = stateGet()) == ST_PARKING) {
                    try {
                        parkingCondition().await();
                    } catch (InterruptedException e) {
                        parkInterrupted = true;
                    }
                }
                if (s == ST_PINNED) {
                    // signal so that execution continues on original thread
                    parkingCondition().signalAll();
                }
            } finally {
                lock.unlock();
                if (f != null) t.setFiber(f);
            }
            if (parkInterrupted)
                Thread.currentThread().interrupt();
        }
        return s;
    }

    /**
     * Waits for this fiber to terminate.
     *
     * <p> If the current thread is interrupted while waiting then it will
     * continue to wait. When the thread does return from this method then its
     * interrupt status will be set.
     *
     * @return this fiber
     */
    public Fiber await() {
        boolean joinInterrupted = false;
        boolean terminated = false;
        while (!terminated) {
            try {
                terminated = awaitNanosInterruptibly(0);
            } catch (InterruptedException e) {
                joinInterrupted = true;
            }
        }
        if (joinInterrupted)
            Thread.currentThread().interrupt();
        return this;
    }

    /**
     * Waits for this fiber to terminate. This method does not wait if the time
     * to wait is less than or equal to zero.
     *
     * <p> If the current thread is interrupted while waiting then it will
     * continue to wait. When the thread does return from this method then its
     * interrupt status will be set.
     *
     * @param nanos the maximum time to wait, in nanoseconds
     * @return this fiber
     */
    public Fiber awaitNanos(long nanos) {
        if (nanos > 0) {
            boolean joinInterrupted = false;
            boolean terminated = false;

            // wait until the fiber terminates or timeout elapses
            while (!terminated && nanos > 0) {
                long startTime = System.nanoTime();
                try {
                    terminated = awaitNanosInterruptibly(nanos);
                } catch (InterruptedException e) {
                    joinInterrupted = true;
                }
                nanos -= (System.nanoTime() - startTime);
            }

            // restore interrupt status
            if (joinInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return this;
    }

    /**
     * Waits up to {@code nanos} nanoseconds for this fiber to terminate.
     * A timeout of {@code 0} means to wait forever.
     *
     * @throws InterruptedException if the shadow thread is interrupted while waiting
     * @throws IllegalArgumentException if nanos is negative
     * @return true if the fiber has terminated
     */
    boolean awaitNanosInterruptibly(long nanos) throws InterruptedException {
        if (nanos < 0) {
            throw new IllegalArgumentException();
        }
        lock.lock();
        try {
            // check if already terminated
            if (stateGet() == ST_TERMINATED)
                return true;

            // wait
            if (nanos == 0) {
                terminationCondition().await();
            } else {
                terminationCondition().await(nanos, NANOSECONDS);
            }

        } finally {
            lock.unlock();
        }
        return (stateGet() == ST_TERMINATED);
    }

    /**
     * Returns the current carrier thread, null if not mounted
     */
    Thread carrierThread() {
        return carrierThread;
    }

    /**
     * Return the ShadowThread for this fiber or null if it does not exist.
     */
    Thread shadowThreadOrNull() {
        return shadowThread;
    }

    /**
     * Return the ShadowThread for this fiber, creating if it does not already
     * exist.
     */
    Thread shadowThread() {
        ShadowThread t = shadowThread;
        if (t == null) {
            synchronized (this) {
                if (t == null) {
                    shadowThread = t = new ShadowThread(this, inheritableThreadContext);
                    // allow context to be GC'ed.
                    inheritableThreadContext = null;
                }
            }
        }
        return t;
    }

    /**
     * Returns the state of the fiber state as a thread state.
     */
    Thread.State getState() {
        switch (stateGet()) {
            case ST_NEW:
                return Thread.State.NEW;
            case ST_RUNNABLE:
                Thread t = carrierThread;
                if (t != null) {
                    // if mounted then return state of carrier thread (although
                    // it may not be correct if the fiber is rescheduled to the
                    // same carrier thread)
                    Thread.State s = t.getState();
                    if (carrierThread == t) {
                        return s;
                    }
                }
                return Thread.State.RUNNABLE;
            case ST_PARKING:
                return Thread.State.RUNNABLE;  // not yet waiting
            case ST_PARKED:
            case ST_PINNED:
                return Thread.State.WAITING;
            case ST_TERMINATED:
                return Thread.State.TERMINATED;
            default:
                throw new InternalError();
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Fiber[");
        Thread t = carrierThread;
        if (t != null) {
            sb.append(t.getName());
            ThreadGroup g = t.getThreadGroup();
            if (g != null) {
                sb.append(",");
                sb.append(g.getName());
            }
        } else {
            sb.append("<no carrier thread>");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Signal the Condition object for parking
     */
    private void signalParking() {
        Thread t = Thread.currentCarrierThread();
        boolean inFiber = t.getFiber() != null;
        if (inFiber) t.setFiber(null);
        lock.lock();
        try {
            Condition parking = this.parking;
            if (parking != null) {
                parking.signalAll();
            }
        } finally {
            lock.unlock();
            if (inFiber) t.setFiber(this);
        }
    }

    /**
     * Returns the Condition object for parking, creating it if needed.
     */
    private Condition parkingCondition() {
        assert lock.isHeldByCurrentThread();
        Condition parking = this.parking;
        if (parking == null) {
            this.parking = parking = lock.newCondition();
        }
        return parking;
    }

    /**
     * Returns the Condition object for termination, creating it if needed.
     */
    private Condition terminationCondition() {
        assert lock.isHeldByCurrentThread();
        Condition termination = this.termination;
        if (termination == null) {
            this.termination = termination = lock.newCondition();
        }
        return termination;
    }

    // -- wrappers for VarHandle methods --

    private short stateGet() {
        return (short) STATE.get(this);
    }

    private short stateGetAndSet(short newValue) {
        return (short) STATE.getAndSet(this, newValue);
    }

    private boolean stateCompareAndSet(short expectedValue, short newValue) {
        return STATE.compareAndSet(this, expectedValue, newValue);
    }

    private boolean parkPermitGetAndSet(boolean newValue) {
        return (boolean) PARK_PERMIT.getAndSet(this, newValue);
    }

    /**
     * Creates the default scheduler as ForkJoinPool.
     */
    private static Executor defaultScheduler() {
        ForkJoinWorkerThreadFactory factory = pool -> {
            PrivilegedAction<ForkJoinWorkerThread> pa = () -> new CarrierThread(pool);
            return AccessController.doPrivileged(pa);
        };
        PrivilegedAction<Executor> pa = () -> {
            int parallelism;
            String s = System.getProperty("jdk.defaultScheduler.parallelism");
            if (s != null) {
                parallelism = Integer.parseInt(s);
            } else {
                parallelism = Runtime.getRuntime().availableProcessors();
            }
            Thread.UncaughtExceptionHandler ueh = (t, e) -> { };
            boolean asyncMode = Boolean.getBoolean("jdk.defaultScheduler.asyncMode");
            return new ForkJoinPool(parallelism, factory, ueh, asyncMode);
        };
        return AccessController.doPrivileged(pa);
    }

    /**
     * A thread in the ForkJoinPool created by the default scheduler.
     */
    private static class CarrierThread extends ForkJoinWorkerThread {
        private static final ThreadGroup CARRIER_THREADGROUP = carrierThreadGroup();
        private static final AccessControlContext INNOCUOUS_ACC = innocuousACC();

        private static final Unsafe UNSAFE;
        private static final long CONTEXTCLASSLOADER;
        private static final long INHERITABLETHREADLOCALS;
        private static final long INHERITEDACCESSCONTROLCONTEXT;

        CarrierThread(ForkJoinPool pool) {
            super(CARRIER_THREADGROUP, pool);
            UNSAFE.putObject(this, CONTEXTCLASSLOADER, ClassLoader.getSystemClassLoader());
            UNSAFE.putObject(this, INHERITABLETHREADLOCALS, null);
            UNSAFE.putObjectRelease(this, INHERITEDACCESSCONTROLCONTEXT, INNOCUOUS_ACC);
        }

        @Override
        public void setUncaughtExceptionHandler(UncaughtExceptionHandler ueh) { }

        @Override
        public void setContextClassLoader(ClassLoader cl) {
            throw new SecurityException("setContextClassLoader");
        }

        /**
         * The thread group for the carrier threads.
         */
        private static final ThreadGroup carrierThreadGroup() {
            return AccessController.doPrivileged(new PrivilegedAction<ThreadGroup>() {
                public ThreadGroup run() {
                    ThreadGroup group = Thread.currentCarrierThread().getThreadGroup();
                    for (ThreadGroup p; (p = group.getParent()) != null; )
                        group = p;
                    return new ThreadGroup(group, "CarrierThreads");
                }
            });
        }

        /**
         * Return an AccessControlContext that doesn't support any permissions.
         */
        private static AccessControlContext innocuousACC() {
            return new AccessControlContext(new ProtectionDomain[] {
                    new ProtectionDomain(null, null)
            });
        }

        static {
            UNSAFE = Unsafe.getUnsafe();
            CONTEXTCLASSLOADER = UNSAFE.objectFieldOffset(Thread.class,
                    "contextClassLoader");
            INHERITABLETHREADLOCALS = UNSAFE.objectFieldOffset(Thread.class,
                    "inheritableThreadLocals");
            INHERITEDACCESSCONTROLCONTEXT = UNSAFE.objectFieldOffset(Thread.class,
                    "inheritedAccessControlContext");
        }
    }

    /**
     * Creates the ScheduledThreadPoolExecutor used to schedule unparking.
     */
    private static ScheduledExecutorService delayedTaskScheduler() {
        ScheduledThreadPoolExecutor stpe = (ScheduledThreadPoolExecutor)
            Executors.newScheduledThreadPool(1, r ->
                AccessController.doPrivileged(new PrivilegedAction<>() {
                    public Thread run() {
                        Thread t = new Thread(r);
                        t.setName("FiberUnparker");
                        t.setDaemon(true);
                        return t;
                    }}));
        stpe.setRemoveOnCancelPolicy(true);
        return stpe;
    }
}
