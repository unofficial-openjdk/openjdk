/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.FiberScope.TerminationQueue;
import java.lang.StackWalker.StackFrame;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Exchanger;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import jdk.internal.misc.InnocuousThread;
import jdk.internal.misc.Unsafe;
import sun.security.action.GetPropertyAction;

import static java.lang.StackWalker.Option.RETAIN_CLASS_REFERENCE;
import static java.lang.StackWalker.Option.SHOW_REFLECT_FRAMES;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * A <i>user mode</i> thread to execute a task that is scheduled by the Java
 * virtual machine rather than the operating system.
 *
 * <p> A {@code Fiber} is created and scheduled in a {@link FiberScope fiber
 * scope} to execute a task by invoking one of the {@link FiberScope#schedule(Runnable)
 * schedule} methods of that class. A fiber terminates when the task completes
 * execution, either normally or with a exception or error. The {@linkplain
 * #awaitTermination() awaitTermination} method can be used to wait for a fiber
 * to terminate. The {@linkplain #join() join} method also waits for a fiber to
 * terminate, returning the result of the task or throwing {@linkplain
 * CompletionException} when the task terminates with an exception. The {@linkplain
 * #toFuture() toFuture} method can be used to obtain a {@linkplain CompletableFuture}
 * to interoperate with code that uses a {@linkplain Future} or uses its
 * cancellation semantics.
 *
 * <p> Unless otherwise noted, passing a {@code null} argument will cause a
 * {@linkplain NullPointerException} to be thrown.
 *
 * @param <V> the task result type
 */

public class Fiber<V> {
    private static final ContinuationScope FIBER_SCOPE = new ContinuationScope("Fibers");
    private static final Executor DEFAULT_SCHEDULER = defaultScheduler();
    private static final ScheduledExecutorService UNPARKER = delayedTaskScheduler();
    private static final boolean EMULATE_CURRENT_THREAD = emulateCurrentThreadValue();
    private static final int TRACE_PINNING_MODE = tracePinningMode();

    private static final VarHandle STATE;
    private static final VarHandle PARK_PERMIT;
    private static final VarHandle RESULT;
    private static final VarHandle FUTURE;
    private static final VarHandle CANCELLED;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            STATE = l.findVarHandle(Fiber.class, "state", short.class);
            PARK_PERMIT = l.findVarHandle(Fiber.class, "parkPermit", boolean.class);
            RESULT = l.findVarHandle(Fiber.class, "result", Object.class);
            FUTURE = l.findVarHandle(Fiber.class, "future", CompletableFuture.class);
            CANCELLED = l.findVarHandle(Fiber.class, "cancelled", boolean.class);
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

    // current scope and termination queue
    private volatile FiberScope scope;
    private volatile TerminationQueue<? super V> terminationQueue;

    // fiber state
    private static final short ST_NEW      = 0;
    private static final short ST_STARTED  = 1;
    private static final short ST_RUNNABLE = 2;
    private static final short ST_PARKING  = 3;
    private static final short ST_PARKED   = 4;
    private static final short ST_PINNED   = 5;
    private static final short ST_YIELDED  = 6;
    private static final short ST_WALKINGSTACK = 51;  // Thread.getStackTrace
    private static final short ST_TERMINATED   = 99;
    private volatile short state;

    // park/unpark and await support
    private final ReentrantLock lock = new ReentrantLock();
    private Condition parking;            // created lazily
    private Condition termination;        // created lazily
    private volatile boolean parkPermit;
    private volatile Object parkBlocker;  // used by LockSupport

    // task result
    private volatile Object result;

    // CompletableFuture integration
    private volatile CompletableFuture<V> future;

    // cancellation
    private volatile boolean cancelled;

    // task to execute after continue (for capturing stack trace and self-suspend)
    private volatile Runnable afterContinueTask;

    // java.lang.Thread integration
    private volatile ShadowThread shadowThread;  // created lazily
    private volatile InheritableThreadContext inheritableThreadContext;

    /**
     * Creates a new {@code Fiber} to run the given task with the given scheduler.
     *
     * @param scheduler the scheduler
     * @param task the task to execute
     */
    private Fiber(Executor scheduler, Object task) {
        Objects.requireNonNull(scheduler);
        Objects.requireNonNull(task);

        Runnable target = () -> {
            try {
                if (task instanceof Callable) {
                    @SuppressWarnings("unchecked")
                    V value = ((Callable<? extends V>) task).call();
                    complete(value);
                } else {
                    ((Runnable) task).run();
                    complete(null);
                }
            } catch (Throwable exc) {
                completeExceptionally(exc);
            }
        };

        this.scheduler = scheduler;
        this.cont = new Continuation(FIBER_SCOPE, target) {
            @Override
            protected void onPinned(Continuation.Pinned reason) {
                if (TRACE_PINNING_MODE > 0) {
                    boolean printAll = (TRACE_PINNING_MODE == 1);
                    PinnedThreadPrinter.printStackTrace(printAll);
                }
                yieldFailed();
            }
        };
        this.runContinuation = this::runContinuation;

        if (EMULATE_CURRENT_THREAD) {
            // Inheritable context from creating thread or fiber
            InheritableThreadContext ctxt = null;
            Thread parentThread = Thread.currentCarrierThread();
            Fiber<?> parentFiber = parentThread.getFiber();
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
    }

    static Fiber<?> newFiber(Runnable task) {
        return new Fiber<>(DEFAULT_SCHEDULER, task);
    }

    static Fiber<?> newFiber(Executor scheduler, Runnable task) {
        return new Fiber<>(scheduler, task);
    }

    static <V> Fiber<V> newFiber(Callable<? extends V> task) {
        return new Fiber<>(DEFAULT_SCHEDULER, task);
    }

    static <V> Fiber<V> newFiber(Executor scheduler, Callable<? extends V> task) {
        return new Fiber<>(scheduler, task);
    }

    /**
     * Creates and schedules a new {@code Fiber} to run the given task with the
     * default scheduler.
     *
     * @apiNote
     * For now, this method schedules the fiber in the detached scope. This will
     * be re-visited once there is more experience gained using fiber scopes.
     *
     * @param task the task to execute
     * @return the fiber
     */
    public static Fiber<?> schedule(Runnable task) {
        return FiberScope.detached().schedule(DEFAULT_SCHEDULER, task);
    }

    /**
     * Creates and schedules a new {@code Fiber} to run the given task with the
     * given scheduler.
     *
     * @apiNote
     * For now, this method schedules the fiber in the detached scope. This will
     * be re-visited once there is more experience gained using fiber scopes.
     *
     * @param scheduler the scheduler
     * @param task the task to execute
     * @return the fiber
     * @throws RejectedExecutionException if the scheduler cannot accept a task
     */
    public static Fiber<?> schedule(Executor scheduler, Runnable task) {
        return FiberScope.detached().schedule(scheduler, task);
    }

    /**
     * Creates and schedules a new {@code Fiber} to run the given value-returning
     * task. The {@code Fiber} is scheduled with the default scheduler.
     *
     * @apiNote
     * For now, this method schedules the fiber in the detached scope. This will
     * be re-visited once there is more experience gained using fiber scopes.
     *
     * @param task the task to execute
     * @param <V> the task's result type
     * @return the fiber
     */
    public static <V> Fiber<V> schedule(Callable<? extends V> task) {
        return FiberScope.detached().schedule(task);
    }

    /**
     * Creates and schedules a new {@code Fiber} to run the given value-returning
     * task. The {@code Fiber} is scheduled with the given scheduler.
     *
     * @apiNote
     * For now, this method schedules the fiber in the detached scope. This will
     * be re-visited once there is more experience gained using fiber scopes.
     *
     * @param scheduler the scheduler
     * @param task the task to execute
     * @param <V> the task's result type
     * @return the fiber
     * @throws RejectedExecutionException if the scheduler cannot accept a task
     */
    public static <V> Fiber<V> schedule(Executor scheduler, Callable<? extends V> task) {
        return FiberScope.detached().schedule(scheduler, task);
    }

    /**
     * Returns the current {@code Fiber}.
     *
     * @return Returns the current fiber or an empty {@code Optional} if not
     *         called from a fiber
     */
    public static Optional<Fiber<?>> current() {
        return Optional.ofNullable(currentFiber());
    }

    static Fiber<?> currentFiber() {
        return Thread.currentCarrierThread().getFiber();
    }

    FiberScope scope() {
        assert currentFiber() == this;
        return scope;
    }

    void setScope(FiberScope scope) {
        assert currentFiber() == this;
        this.scope = scope;
    }

    /**
     * Schedules this {@code Fiber} to execute in the given scope.
     *
     * @return this fiber
     * @throws IllegalStateException if the fiber has already been scheduled
     * @throws IllegalCallerException if the caller thread or fiber is not
     *         executing in the scope
     * @throws RejectedExecutionException if the scheduler cannot accept a task
     */
    Fiber<V> schedule(FiberScope scope, TerminationQueue<? super V> terminationQueue) {
        Objects.requireNonNull(scope);

        if (!stateCompareAndSet(ST_NEW, ST_STARTED))
            throw new IllegalStateException("Fiber already scheduled");

        this.scope = scope;
        if (terminationQueue != null)
            this.terminationQueue = terminationQueue;
        scope.onSchedule(this);

        // switch to carrier thread when submitting task. Revisit this when
        // ForkJoinPool is updated to reduce use of Thread.currentThread.
        Thread thread = Thread.currentCarrierThread();
        Fiber<?> parentFiber = thread.getFiber();
        if (parentFiber != null) thread.setFiber(null);
        boolean scheduled = false;
        try {
            scheduler.execute(runContinuation);
            scheduled = true;
        } finally {
            if (!scheduled) {
                completeExceptionally(new IllegalStateException("stillborn"));
                afterTerminate(false);
            }
            if (parentFiber != null) thread.setFiber(parentFiber);
        }

        return this;
    }

    /**
     * Runs or continues execution of the continuation on the current thread.
     */
    private void runContinuation() {
        assert Thread.currentCarrierThread().getFiber() == null;

        // set state to ST_RUNNING
        boolean firstRun = stateCompareAndSet(ST_STARTED, ST_RUNNABLE);
        if (!firstRun) {
            // continue on this carrier thread if fiber was parked or it yielded
            if (stateCompareAndSet(ST_PARKED, ST_RUNNABLE)) {
                parkPermitGetAndSet(false);  // consume parking permit
            } else if (!stateCompareAndSet(ST_YIELDED, ST_RUNNABLE)) {
                return;
            }
        }

        mount(firstRun);
        try {
            cont.run();
        } finally {
            unmount();
            if (cont.isDone()) {
                afterTerminate(true);
            } else {
                afterYield();
            }
        }
    }

    /**
     * Mounts this fiber. This method must be invoked before the continuation
     * is run or continued. It binds the fiber to the current carrier thread.
     */
    private void mount(boolean firstRun) {
        Thread thread = Thread.currentCarrierThread();

        // sets the carrier thread
        carrierThread = thread;

        // notify the shadow thread so that the interrupt status can be forwarded
        ShadowThread st = this.shadowThread;
        if (st != null) st.onMount(thread);

        // set the fiber so that Thread.currentThread() returns the Fiber object
        assert thread.getFiber() == null;
        thread.setFiber(this);

        if (firstRun && notifyJvmtiEvents) {
            notifyFiberStarted(thread, this);
            notifyFiberMount(thread, this);
        }
    }

    /**
     * Unmounts this fiber. This method must be invoked after the continuation
     * yields or terminates. It unbinds this fiber from the carrier thread.
     */
    private void unmount() {
        Thread thread = Thread.currentCarrierThread();

        if (notifyJvmtiEvents) {
            notifyFiberUnmount(thread, this);
        }

        // drop connection between this fiber and the carrier thread
        thread.setFiber(null);
        carrierThread = null;

        // notify shadow thread so that interrupt status is cleared
        ShadowThread st = this.shadowThread;
        if (st != null) st.onUnmount(thread);
    }

    /**
     * Invoke after yielding. If parking, sets the state to ST_PARKED and notifies
     * anyone waiting for the fiber to park.
     */
    private void afterYield() {
        int s = stateGet();
        if (s == ST_PARKING) {
            // signal anyone waiting for this fiber to park
            stateGetAndSet(ST_PARKED);
            signalParking();
        } else if (s == ST_RUNNABLE) {
            // Thread.yield, submit task to continue
            assert Thread.currentCarrierThread().getFiber() == null;
            stateGetAndSet(ST_YIELDED);
            scheduler.execute(runContinuation);
        } else {
            throw new InternalError();
        }
    }

    /**
     * Invokes when the fiber terminates to set the state to ST_TERMINATED
     * and notify anyone waiting for the fiber to terminate.
     *
     * @param notifyAgents true to notify JVMTI agents
     */
    private void afterTerminate(boolean notifyAgents) {
        assert result != null;
        int oldState = stateGetAndSet(ST_TERMINATED);
        assert oldState == ST_STARTED || oldState == ST_RUNNABLE;

        if (notifyAgents && notifyJvmtiEvents) {
            Thread thread = Thread.currentCarrierThread();
            notifyFiberTerminated(thread, this);
        }

        // notify termination queue
        TerminationQueue<? super V> queue = this.terminationQueue;
        if (queue != null) {
            queue.put(this);  // can fail with OOME
        }

        // notify scope
        scope.onTerminate(this);  // can fail with OOME

        // notify anyone waiting for this fiber to terminate
        signalTermination();
    }

    /**
     * Invoked by onPinned when the continuation cannot yield due to a
     * synchronized or native frame on the continuation stack. If the fiber is
     * parking then its state is changed to ST_PINNED and carrier thread parks.
     */
    private void yieldFailed() {
        if (stateGet() == ST_RUNNABLE) {
            // nothing to do
            return;
        }

        // switch to carrier thread
        Thread thread = Thread.currentCarrierThread();
        thread.setFiber(null);

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
            thread.setFiber(this);
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
     * dormant until it is {@linkplain #unpark() unparked} or the thread is
     * {@link Thread#interrupt() interrupted}.
     *
     * @throws IllegalCallerException if not called from a fiber
     */
    static void park() {
        Fiber<?> fiber = Thread.currentCarrierThread().getFiber();
        if (fiber == null)
            throw new IllegalCallerException("not a fiber");
        fiber.maybePark();
    }

    /**
     * Disables the current fiber for scheduling purposes for up to the
     * given waiting time.
     *
     * <p> If this fiber has already been {@link #unpark() unparked} then the
     * parking permit is consumed and this method completes immediately;
     * otherwise if the time to wait is greater than zero then the current fiber
     * is disabled for scheduling purposes and lies dormant until it is {@link
     * #unpark unparked}, the waiting time elapses or the thread is
     * {@linkplain Thread#interrupt() interrupted}.
     *
     * @param nanos the maximum number of nanoseconds to wait.
     *
     * @throws IllegalCallerException if not called from a fiber
     */
    static void parkNanos(long nanos) {
        Thread thread = Thread.currentCarrierThread();
        Fiber<?> fiber = thread.getFiber();
        if (fiber == null)
            throw new IllegalCallerException("not a fiber");
        if (nanos > 0) {
            // switch to carrier thread when submitting task to unpark
            thread.setFiber(null);
            Future<?> unparker;
            try {
                unparker = UNPARKER.schedule(fiber::unpark, nanos, NANOSECONDS);
            } finally {
                thread.setFiber(fiber);
            }
            // now park
            try {
                fiber.maybePark();
            } finally {
                unparker.cancel(false);
            }
        } else {
            // consume permit when not parking
            fiber.yield();
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

        // yield until continued on a carrier thread
        boolean yielded = false;
        boolean retry;
        do {
            if (Continuation.yield(FIBER_SCOPE)) {
                yielded = true;
            }
            if (retry = (carrierThread == null)) {
                Runnable hook = this.afterContinueTask;
                if (hook != null) hook.run();
            }
        } while (retry);

        // continued
        assert stateGet() == ST_RUNNABLE;

        // notify JVMTI mount event here so that stack is available to agents
        if (yielded && notifyJvmtiEvents) {
            notifyFiberMount(Thread.currentCarrierThread(), this);
        }
    }

    /**
     * Re-enables this fiber for scheduling. If the fiber was {@link #park()
     * parked} then it will be unblocked, otherwise its next call to {@code park}
     * or {@linkplain #parkNanos(long) parkNanos} is guaranteed not to block.
     *
     * @throws RejectedExecutionException if using a scheduler and it cannot
     *         accept a task
     * @return this fiber
     */
    Fiber<V> unpark() {
        Thread thread = Thread.currentCarrierThread();
        Fiber<?> fiber = thread.getFiber();
        if (!parkPermitGetAndSet(true) && fiber != this) {
            int s = waitIfParking();
            if (s == ST_PARKED) {
                // switch to carrier thread when submitting task to continue
                if (fiber != null) thread.setFiber(null);
                try {
                    scheduler.execute(runContinuation);
                } finally {
                    if (fiber != null) thread.setFiber(fiber);
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
            Thread thread = Thread.currentCarrierThread();
            Fiber<?> f = thread.getFiber();
            if (f != null) thread.setFiber(null);
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
                if (f != null) thread.setFiber(f);
            }
            if (parkInterrupted)
                Thread.currentThread().interrupt();
        }
        return s;
    }

    /**
     * For use by Thread.yield to yield on the current carrier thread. A no-op
     * if the continuation is pinned.
     */
    void yield() {
        assert Thread.currentCarrierThread().getFiber() == this
                && stateGet() == ST_RUNNABLE;
        Continuation.yield(FIBER_SCOPE);
        assert stateGet() == ST_RUNNABLE;
    }

    /**
     * Waits for this fiber to terminate.
     *
     * <p> If the current thread is interrupted while waiting then it will
     * continue to wait. When the thread does return from this method then its
     * interrupt status will be set. This will be re-visited once all the
     * details of cancellation are worked out.
     *
     * @apiNote TBD if we need both awaitTermination and join methods.
     */
    public void awaitTermination() {
        boolean joinInterrupted = false;
        boolean terminated = false;
        while (!terminated) {
            try {
                terminated = awaitInterruptibly(0);
            } catch (InterruptedException e) {
                joinInterrupted = true;
            }
        }
        if (joinInterrupted)
            Thread.currentThread().interrupt();
    }

    /**
     * Waits for this fiber to terminate for up to the given waiting duration.
     * This method does not wait if the duration to wait is less than or equal
     * to zero.
     *
     * <p> If the current thread is interrupted while waiting then it will
     * continue to wait. When the thread does return from this method then its
     * interrupt status will be set. This will be re-visited once all the
     * details of cancellation are worked out.
     *
     * @param duration the maximum duration to wait
     * @return {@code true} if the fiber has terminated
     */
    public boolean awaitTermination(Duration duration) {
        long nanos = TimeUnit.NANOSECONDS.convert(duration);
        boolean terminated = false;
        if (nanos > 0) {
            boolean interrupted = false;

            // wait until the fiber terminates or timeout elapses
            long startTime = System.nanoTime();
            long remaining = nanos;
            while (!terminated && remaining > 0) {
                try {
                    terminated = awaitInterruptibly(nanos);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                remaining = nanos - (System.nanoTime() - startTime);
            }

            // restore interrupt status
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (terminated || stateGet() == ST_TERMINATED) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Waits up to {@code nanos} nanoseconds for this fiber to terminate.
     * A timeout of {@code 0} means to wait forever.
     *
     * @throws IllegalArgumentException if nanos is negative
     * @throws InterruptedException if the shadow thread is interrupted while waiting
     * @throws IllegalStateException if the fiber has not been scheduled
     * @return true if the fiber has terminated
     */
    boolean awaitInterruptibly(long nanos) throws InterruptedException {
        if (nanos < 0) {
            throw new IllegalArgumentException("timeout is negative");
        }
        short s = stateGet();
        if (s == ST_TERMINATED) {
            return true;
        }
        lock.lock();
        try {
            s = stateGet();
            if (s == ST_NEW)
                throw new IllegalStateException("fiber not scheduled");
            if (s == ST_TERMINATED)
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
     * Tests if this {@code Fiber} is alive. A thread is alive if it has not yet
     * terminated.
     *
     * @return {@code true} if this fiber is alive; {@code false} otherwise.
     */
    public boolean isAlive() {
        short s = stateGet();
        return (s != ST_NEW) && (s != ST_TERMINATED);
    }

    /**
     * Sets this fiber's cancel status if not already set. If the fiber hasn't
     * terminated then it is also {@link
     * java.util.concurrent.locks.LockSupport#unpark(Object) unparked}.
     *
     * <p> This method has no effect on a {@linkplain CompletableFuture} obtained
     * via the {@linkplain #toFuture()} method, its {@linkplain
     * CompletableFuture#cancel(boolean) cancel(boolean)} method is not invoked.
     *
     * @return {@code true} if the fiber's cancel status was set by this method
     * @throws RejectedExecutionException if using a scheduler and it cannot
     *         accept a task
     */
    public boolean cancel() {
        boolean changed = !cancelled && CANCELLED.compareAndSet(this, false, true);
        if (stateGet() != ST_TERMINATED) {
            FiberScope scope = this.scope;
            // scope is null before initially scheduled
            if (scope != null) {
                scope.onCancel(this);
            }
            unpark();
        }
        return changed;
    }

    /**
     * Sets this fiber's cancel status if not already set and optionally unpark
     * the fiber. Its FiberScope is not notified by this method.
     */
    void cancel(boolean unpark) {
        if (!cancelled) {
            CANCELLED.set(this, true);
            if (unpark && stateGet() != ST_TERMINATED) {
                unpark();
            }
        }
    }

    /**
     * Returns the fiber's cancel status.
     *
     * @return {@code true} if the fiber's cancel status is set
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Return {@code true} if the current fiber's cancel status is set and it
     * is in executing in a {@link FiberScope#isCancellable() cancellable}
     * scope. This method always returns {@code false} when invoked from a thread.
     *
     * @apiNote This method is intended to be used by blocking or compute bound
     * operations that check cooperatively for cancellation.
     *
     * @return {@code true} if the current fiber has been cancelled
     */
    public static boolean cancelled() {
        Fiber<?> fiber = currentFiber();
        if (fiber != null && fiber.cancelled) {
            FiberScope scope = fiber.scope;
            if (scope != null) {
                return scope.isCancellable();
            } else {
                // emulate the global scope for now
                return true;
            }
        }
        return false;
    }

    /**
     * Waits for this fiber to terminate and returns the result of its task. If
     * the task completed with an exception then {@linkplain CompletionException}
     * is thrown with the exception as its cause.
     *
     * <p> If the current thread is interrupted while waiting then it will
     * continue to wait. When the thread does return from this method then its
     * interrupt status will be set. This will be re-visited once all the
     * details of cancellation are worked out.
     *
     * @apiNote TBD if we need both awaitTermination and join methods.
     *
     * @return the result or {@code null} if the fiber was created with a Runnable
     * @throws CompletionException if the task completed with an exception
     */
    public V join() {
        awaitTermination();
        @SuppressWarnings("unchecked")
        V r = (V) resultOrThrow();
        return r;
    }

    /**
     * Waits for this fiber to terminate for up to the given waiting duration and
     * returns the result of its task. If the task completed with an exception
     * then {@linkplain CompletionException} is thrown with the exception as its
     * cause. This method does not wait if the duration to wait is less than or
     * equal to zero.
     *
     * <p> If the current thread is interrupted while waiting then it will
     * continue to wait. When the thread does return from this method then its
     * interrupt status will be set.T his will be re-visited once all the
     * details of cancellation are worked out.
     *
     * @param duration the maximum duration to wait
     * @return {@code true} if the fiber has terminated
     * @return the result or {@code null} if the fiber was created with a Runnable
     * @throws CompletionException if the task completed with an exception
     * @throws TimeoutException if the wait timed out
     */
    public V join(Duration duration) throws TimeoutException {
        boolean terminated = awaitTermination(duration);
        if (terminated) {
            @SuppressWarnings("unchecked")
            V r = (V) resultOrThrow();
            return r;
        } else {
            throw new TimeoutException();
        }
    }

    /**
     * Returns the task result or throws CompletionException with the exception
     * when the task terminates with an exception.
     */
    private Object resultOrThrow() {
        Object r = result;
        assert r != null;
        if (r instanceof ExceptionHolder) {
            Throwable ex = ((ExceptionHolder) r).exception();
            throw new CompletionException(ex);
        } else {
            return (r != NULL_RESULT) ? r : null;
        }
    }

    /**
     * Returns a {@code CompletableFuture} to represent the result of the task.
     * The {@code CompletableFuture} can be used with code that uses a {@linkplain
     * java.util.concurrent.Future Future} to wait on a task and retrieve its
     * result. It can also be used with code that needs a {@linkplain
     * java.util.concurrent.CompletionStage} or other operations defined by
     * {@code CompletableFuture}.
     *
     * <p> Invoking the {@code CompletableFuture}'s {@linkplain CompletableFuture#complete
     * complete} or {@linkplain CompletableFuture#completeExceptionally
     * completeExceptionally} methods to explicitly complete the result has no
     * effort on the result returned by the {@linkplain #join()} and {@linkplain
     * #join(Duration)} methods defined here. The {@code CompletableFuture}'s
     * {@linkplain CompletableFuture#cancel(boolean) cancel} completes the {@code
     * CompletableFuture} with a {@linkplain java.util.concurrent.CancellationException}
     * and also sets the fiber's cancelled status as if by calling the {@linkplain
     * #cancel() cancel} method. Once cancelled, the {@code CompletableFuture}'s
     * {@linkplain CompletableFuture#isDone() isDone} method will return {@code
     * true}. The fiber's {@link #isAlive() isAlive} method will continue
     * to return {@code true} until the fiber terminates.
     *
     * <p> The first invocation of this method creates the {@code CompletableFuture},
     * subsequent calls to this method return the same object.
     *
     * @return a CompletableFuture to represent the result of the task
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<V> toFuture() {
        CompletableFuture<V> future = this.future;
        if (future == null) {
            future = new CompletableFuture<>() {
                @Override
                public boolean cancel(boolean ignored) {
                    boolean cancelled = super.cancel(ignored);
                    Fiber.this.cancel();
                    return cancelled;
                }
            };
            Object previous = FUTURE.compareAndExchange(this, null, future);
            if (previous != null) {
                future = (CompletableFuture<V>) previous;
            } else {
                Object r = this.result;
                if (r != null) {
                    if (r instanceof ExceptionHolder) {
                        Throwable ex = ((ExceptionHolder) r).exception();
                        future.completeExceptionally(ex);
                    } else {
                        future.complete((r != NULL_RESULT) ? (V) r : null);
                    }
                }
            }
        }
        return future;
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
        assert Thread.currentCarrierThread() == carrierThread;
        if (!EMULATE_CURRENT_THREAD) {
            throw new UnsupportedOperationException(
                "currentThread() cannot be used in the context of a fiber");
        }
        ShadowThread thread = shadowThread;
        if (thread== null) {
            shadowThread = thread = new ShadowThread(this, inheritableThreadContext);

            // allow context to be GC'ed.
            inheritableThreadContext = null;
        }
        return thread;
    }

    /**
     * Returns the state of the fiber state as a thread state.
     */
    Thread.State getState() {
        switch (stateGet()) {
            case ST_NEW:
                return Thread.State.NEW;
            case ST_STARTED:
            case ST_RUNNABLE:
            case ST_YIELDED:
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
            case ST_WALKINGSTACK:
                return Thread.State.WAITING;
            case ST_TERMINATED:
                return Thread.State.TERMINATED;
            default:
                throw new InternalError();
        }
    }

    @Override
    public String toString() {
        String prefix = "Fiber@" + Integer.toHexString(hashCode()) + "[";
        StringBuilder sb = new StringBuilder(prefix);
        Thread t = carrierThread;
        if (t != null) {
            sb.append(t.getName());
            ThreadGroup g = t.getThreadGroup();
            if (g != null) {
                sb.append(",");
                sb.append(g.getName());
            }
        } else {
            if (stateGet() == ST_TERMINATED) {
                sb.append("<terminated>");
            } else {
                sb.append("<no carrier thread>");
            }
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
     * Signal the Condition object for termination
     */
    private void signalTermination() {
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

    // -- task result --

    private static final Object NULL_RESULT = new Object();

    private static class ExceptionHolder {
        final Throwable exc;
        ExceptionHolder(Throwable exc) { this.exc = exc; }
        Throwable exception() { return exc; }
    }

    @SuppressWarnings("unchecked")
    private void complete(V value) {
        Object r = (value == null) ? NULL_RESULT : value;
        if (RESULT.compareAndSet(this, null, r)) {
            CompletableFuture<V> future = this.future;
            if (future != null) {
                future.complete((V) r);
            }
        }
    }

    private void completeExceptionally(Throwable exc) {
        if (RESULT.compareAndSet(this, null, new ExceptionHolder(exc))) {
            CompletableFuture<V> future = this.future;
            if (future != null) {
                future.completeExceptionally(exc);
            }
        }
    }

    // -- stack trace support --

    private static final StackWalker STACK_WALKER = StackWalker.getInstance(FIBER_SCOPE);
    private static final StackTraceElement[] EMPTY_STACK = new StackTraceElement[0];

    /**
     * Returns an array of stack trace elements representing the stack trace
     * of this fiber.
     */
    StackTraceElement[] getStackTrace() {
        if (Thread.currentCarrierThread().getFiber() == this) {
            return STACK_WALKER
                    .walk(s -> s.map(StackFrame::toStackTraceElement)
                    .toArray(StackTraceElement[]::new));
        } else {
            // target fiber may be mounted or unmounted
            StackTraceElement[] stackTrace;
            do {
                Thread carrier = carrierThread;
                if (carrier != null) {
                    // mounted
                    stackTrace = tryGetStackTrace(carrier);
                } else {
                    // not mounted
                    stackTrace = tryGetStackTrace();
                }
                if (stackTrace == null) {
                    Thread.onSpinWait();
                }
            } while (stackTrace == null);
            return stackTrace;
        }
    }

    /**
     * Returns the stack trace for this fiber if it mounted on the given carrier
     * thread. If the fiber parks or is re-scheduled to another thread then
     * null is returned.
     */
    private StackTraceElement[] tryGetStackTrace(Thread carrier) {
        assert carrier != Thread.currentCarrierThread();

        StackTraceElement[] stackTrace;
        carrier.suspendThread();
        try {
            // get stack trace if fiber is still mounted on the suspended
            // carrier thread. Skip if the fiber is parking as the
            // continuation frames may or may not be on the thread stack.
            if (carrierThread == carrier && stateGet() != ST_PARKING) {
                PrivilegedAction<StackTraceElement[]> pa = carrier::getStackTrace;
                stackTrace = AccessController.doPrivileged(pa);
            } else {
                stackTrace = null;
            }
        } finally {
            carrier.resumeThread();
        }

        if (stackTrace != null) {
            // return stack trace elements up to Fiber.runContinuation frame
            int index = 0;
            int runMethod = -1;
            while (index < stackTrace.length && runMethod < 0) {
                StackTraceElement e = stackTrace[index];
                if ("java.base".equals(e.getModuleName())
                        && "java.lang.Fiber".equals(e.getClassName())
                        && "runContinuation".equals(e.getMethodName())) {
                    runMethod = index;
                } else {
                    index++;
                }
            }
            if (runMethod >= 0) {
                stackTrace = Arrays.copyOf(stackTrace, runMethod + 1);
            }
        }

        return stackTrace;
    }

    /**
     * Returns the stack trace for this fiber if it parked (not mounted) or
     * null if not in the parked state.
     */
    private StackTraceElement[] tryGetStackTrace() {
        if (stateCompareAndSet(ST_PARKED, ST_WALKINGSTACK)) {
            try {
                return cont.stackWalker()
                        .walk(s -> s.map(StackFrame::toStackTraceElement)
                                    .toArray(StackTraceElement[]::new));
            } finally {
                int oldState = stateGetAndSet(ST_PARKED);
                assert oldState == ST_WALKINGSTACK;

                // fiber may have been unparked while obtaining the stack so we
                // unpark to avoid a lost unpark. This will appear as a spurious
                // (but harmless) wakeup
                unpark();
            }
        } else {
            short state = stateGet();
            if (state == ST_NEW || state == ST_TERMINATED) {
                return EMPTY_STACK;
            } else {
                return null;
            }
        }
    }

    /**
     * Continues a parked fiber on the current thread to execute the given task.
     * The task is executed without mounting the fiber. Returns true if the task
     * was executed, false if the task could not be executed because the fiber
     * is not parked.
     *
     * @throws IllegalCallerException if called from a fiber
     */
    private boolean tryRun(Runnable task) {
        if (Thread.currentCarrierThread().getFiber() != null) {
            throw new IllegalCallerException();
        }
        if (stateCompareAndSet(ST_PARKED, ST_RUNNABLE)) {
            assert carrierThread == null && afterContinueTask == null;
            afterContinueTask = task;
            try {
                cont.run();
            } finally {
                afterContinueTask = null;
                int oldState = stateGetAndSet(ST_PARKED);
                assert carrierThread == null;
                assert oldState == ST_RUNNABLE;
                assert !cont.isDone();
            }

            // fiber may have been unparked while running on this thread so we
            // unpark to avoid a lost unpark. This will appear as a spurious
            // (but harmless) wakeup
            unpark();

            return true;
        } else {
            return false;
        }
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

    // -- JVM TI support --

    /**
     * Returns a thread with the fiber's stack mounted. The thread is suspended
     * or close to suspending itself. Returns {@code null} if the fiber is not
     * parked or cannot be mounted.
     *
     * @apiNote This method is for use by JVM TI and debugging operations only
     */
    Thread tryMountAndSuspend() {
        var exchanger = new Exchanger<Boolean>();
        var thread = InnocuousThread.newThread(() -> {
            boolean continued = tryRun(() -> {
                exchangeUninterruptibly(exchanger, true);
                Thread.currentCarrierThread().suspendThread();
            });
            if (!continued) {
                exchangeUninterruptibly(exchanger, false);
            }
        });
        thread.setDaemon(true);
        thread.start();
        boolean continued = exchangeUninterruptibly(exchanger, true);
        if (continued) {
            return thread;
        } else {
            joinUninterruptibly(thread);
            return null;
        }
    }

    /**
     * Returns true if the fiber is mounted on a carrier thread with the
     * continuation stack.
     *
     * @apiNote This method is for use by JVM TI and debugging operations only
     */
    boolean isMountedWithStack() {
        return (carrierThread != null) && (stateGet() != ST_PARKING);
    }

    private static <V> V exchangeUninterruptibly(Exchanger<V> exchanger, V x) {
        V y = null;
        boolean interrupted = false;
        while (y == null) {
            try {
                y = exchanger.exchange(x);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return y;
    }

    private static void joinUninterruptibly(Thread thread) {
        boolean interrupted = false;
        for (;;) {
            try {
                thread.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static volatile boolean notifyJvmtiEvents;  // set by VM
    private static native void notifyFiberStarted(Thread thread, Fiber<?> fiber);
    private static native void notifyFiberTerminated(Thread thread, Fiber<?> fiber);
    private static native void notifyFiberMount(Thread thread, Fiber<?> fiber);
    private static native void notifyFiberUnmount(Thread thread, Fiber<?> fiber);
    private static native void registerNatives();
    static {
        registerNatives();
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
            // use FIFO as default
            s = System.getProperty("jdk.defaultScheduler.lifo");
            boolean asyncMode = (s == null) || s.equalsIgnoreCase("false");
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
            UNSAFE.putReference(this, CONTEXTCLASSLOADER, ClassLoader.getSystemClassLoader());
            UNSAFE.putReference(this, INHERITABLETHREADLOCALS, null);
            UNSAFE.putReferenceRelease(this, INHERITEDACCESSCONTROLCONTEXT, INNOCUOUS_ACC);
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

    /**
     * Helper class to print the fiber stack trace when a carrier thread is
     * pinned.
     */
    private static class PinnedThreadPrinter {
        static final StackWalker INSTANCE;
        static {
            var options = Set.of(SHOW_REFLECT_FRAMES, RETAIN_CLASS_REFERENCE);
            PrivilegedAction<StackWalker> pa = () -> LiveStackFrame.getStackWalker(options, FIBER_SCOPE);
            INSTANCE = AccessController.doPrivileged(pa);
        }
        /**
         * Prints a stack trace of the current fiber to the standard output stream.
         * This method is synchronized to reduce interference in the output.
         * @param printAll true to print all stack frames, false to only print the
         *        frames that are native or holding a monitor
         */
        static synchronized void printStackTrace(boolean printAll) {
            System.out.println(Fiber.currentFiber());
            INSTANCE.forEach(f -> {
                if (f.getDeclaringClass() != PinnedThreadPrinter.class) {
                    var ste = f.toStackTraceElement();
                    int monitorCount = ((LiveStackFrame) f).getMonitors().length;
                    if (monitorCount > 0 || f.isNativeMethod()) {
                        System.out.format("    %s <== monitors:%d%n", ste, monitorCount);
                    } else if (printAll) {
                        System.out.format("    %s%n", ste);
                    }
                }
            });
        }
    }

    /**
     * Returns true if the Thread API is emulated when running in a fiber
     */
    static boolean emulateCurrentThread() {
        return EMULATE_CURRENT_THREAD;
    }

    /**
     * Reads the value of the jdk.emulateCurrentThread property to determine
     * if the Thread API is emulated when running in a fiber.
     */
    private static boolean emulateCurrentThreadValue() {
        String value = GetPropertyAction.privilegedGetProperty("jdk.emulateCurrentThread");
        return (value == null) || !value.equalsIgnoreCase("false");
    }

    /**
     * Reads the value of the jdk.tracePinning property to determine if stack
     * traces should be printed when a carrier thread is pinned when a fiber
     * attempts to park.
     */
    private static int tracePinningMode() {
        String value = GetPropertyAction.privilegedGetProperty("jdk.tracePinnedThreads");
        if (value != null) {
            if (value.length() == 0 || "full".equalsIgnoreCase(value))
                return 1;
            if ("short".equalsIgnoreCase(value))
                return 2;
        }
        return 0;
    }
}
