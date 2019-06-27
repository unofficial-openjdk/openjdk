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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import jdk.internal.misc.Strands;

/**
 * A scope in which {@link Fiber fibers} are scheduled.
 *
 * <p> A {@code FiberScope} is opened and <em>entered</em> by creating an instance,
 * usually by means of one of the static {@linkplain #open(Option...) open} methods
 * defined here. The thread or fiber that opens the scope is the <i>owner</i> of
 * the scope. The scope is closed and <em>exited</em> by invoking its {@linkplain
 * #close() close} method. A scope can only be exited when all fibers scheduled
 * in the scope have terminated; the {@code close} method blocks until all fibers
 * scheduled in the scope have terminated. As a special case, fibers can be
 * scheduled in the <em>{@linkplain #background() background} scope</em> for cases
 * where fibers are <em>unmanaged</em> or need to <em>outlive</em> the thread or
 * fiber that schedules them.
 *
 * <p> {@code FiberScope} implements {@linkplain AutoCloseable} so that the
 * try-with-resources statement can be used to ensure that a scope is closed.
 * The following example schedules two fibers in a scope. The try-with-resources
 * statement completes when the block completes and both fibers scheduled in the
 * cope terminate.
 *
 * <pre>{@code
 *     try (var scope = FiberScope.open()) {
 *         var fiber1 = scope.schedule(() -> "one");
 *         var fiber2 = scope.schedule(() -> "two");
 *     });
 * }</pre>
 *
 * <p> A {@code FiberScope} can be created to track the fibers scheduled in the
 * scope so that they can be cancelled in bulk when the owner is {@link Fiber#cancel()
 * cancelled}, the owner closes the scope, a deadline is reached, or a timeout
 * expires. Alternatively, a {@code FiberScope} can be created to shield the owner
 * from cancellation, useful for recovery or cleanup that needs to complete without
 * cancellation.
 *
 * <p> Fiber scopes may be nested. A thread or fiber executing in a scope may open
 * and enter another (inner) scope. Any fibers scheduled in the inner scope must
 * terminate to allow the thread or fiber exit back to the parent/enclosing scope.
 *
 * <p> Unless otherwise noted, passing a {@code null} argument will cause a
 * {@linkplain NullPointerException} to be thrown.
 *
 * @apiNote For now, {@code FiberScope}s can only be created with the {@code open}
 * methods defined here. A future revision of this API may support a builder or
 * other ways to compose scopes that make use of the <em>lifecycle events</em> that
 * are not currently exposed. A future revision may also add a type parameter.
 */

public class FiberScope implements AutoCloseable {
    private static final VarHandle COUNT;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            COUNT = l.findVarHandle(FiberScope.class, "count", int.class);
        } catch (Exception e) {
            throw new InternalError(e);
        }
    }
    private volatile int count;

    private final boolean cancellable;
    private final Object owner;
    private final FiberScope parent;

    // close support
    private volatile boolean closed;
    private final ReentrantLock closeLock = new ReentrantLock();
    private volatile Condition closeCondition;  // created lazily

    // background scope
    private static final FiberScope BACKGROUND = new BackgroundFiberScope();

    /**
     * Creates and enters a new scope.
     */
    FiberScope(boolean cancellable) {
        this.cancellable = cancellable;
        this.owner = Strands.currentStrand();
        if (owner instanceof Thread) {
            Thread thread = (Thread) owner;
            this.parent = thread.scope();
            thread.setScope(this);
        } else {
            Fiber<?> fiber = (Fiber<?>) owner;
            this.parent = fiber.scope();
            fiber.setScope(this);
        }
    }

    /**
     * Constructor for the background scope.
     */
    FiberScope(Void ignore, boolean cancellable) {
        this.cancellable = cancellable;
        this.owner = null;
        this.parent = null;
    }

    /**
     * Returns true if this scope is cancellable.
     */
    boolean isCancellable() {
        return cancellable;
    }

    /**
     * Returns true if this scope propagates cancellation to nested scopes.
     */
    boolean propagatesCancel() {
        return false;
    }

    /**
     * Return the scope owner.
     */
    Object owner() {
        return owner;
    }

    /**
     * Returns the parent scope or {@code null} if there is no parent.
     */
    FiberScope parent() {
        return parent;
    }

    /**
     * Returns the current fiber scope. If invoked on a thread that is not in a
     * scope then the background scope is returned (to make it look like threads
     * start out in the background scope).
     */
    static FiberScope current() {
        Object strand = Strands.currentStrand();
        if (strand instanceof Thread) {
            Thread thread = (Thread) strand;
            FiberScope scope = thread.scope();
            return (scope != null) ? scope : BACKGROUND;
        } else {
            FiberScope scope = ((Fiber<?>) strand).scope();
            assert scope != null;
            return scope;
        }
    }

    /**
     * Returns the scope to schedule fibers in the <em>background</em>. A fiber
     * scheduled in the <em>background</em> scope can outlive the thread or fiber
     * that schedules it. The scope cannot be closed; its {@link #close() close}
     * method always fails.
     *
     * @return the background scope
     */
    @SuppressWarnings("unchecked")
    public static FiberScope  background() {
        return BACKGROUND;
    }

    /**
     * Defines options to specify when opening a fiber scope.
     *
     * @see FiberScope#open(Option...)
     */
    public enum Option {
        /**
         * Fibers in the scope are <em>shielded</em> from cancellation. This
         * option is intended to be used by cleanup and recovery operations that
         * cannot be cancelled.
         */
        IGNORE_CANCEL,
        /**
         * Cancel all fibers remaining in the scope when the owner closes the scope.
         */
        CANCEL_AT_CLOSE,
        /**
         * Cancel all fibers in the scope when the owner is cancelled.
         */
        PROPAGATE_CANCEL,
    }

    /**
     * Creates and enters a new scope. The current {@link Thread#currentThread()
     * thread} or {@link Fiber#current() fiber} is the <em>owner</em> of the scope,
     * only the owner can exit the scope with the {@linkplain #close() close}
     * method.
     *
     * <p> Options can be used to configure the scope:
     *
     * <ul>
     *   <li><p> {@linkplain Option#IGNORE_CANCEL IGNORE_CANCEL}: The owner is
     *   <em>shielded</em> from cancellation when executing in this scope.
     *   Fibers scheduled in the scope are also shielded.
     *   This option cannot be specified with any other option. </p></li>
     *
     *   <li><p> {@linkplain Option#CANCEL_AT_CLOSE CANCEL_AT_CLOSE}: A scope
     *   created with this option cancels all fibers scheduled in the scope
     *   when the owner {@link #close() closes} the scope. </p></li>
     *
     *   <li><p> {@linkplain Option#PROPAGATE_CANCEL PROPAGATE_CANCEL}: If
     *   the owner is {@link Fiber#cancel() cancelled}  then the cancellation is
     *   propagated to fibers scheduled in the scope.
     *   Once the owner is cancelled, scheduling additional fibers in the scope
     *   will schedule the fibers with their <em>cancel status</em> set. </p></li>
     * </ul>
     *
     * <p> The {@code IGNORE_CANCEL} and {@code PROPAGATE_CANCEL} options are
     * inherited the from parent/enclosing scope when no options are specified.
     *
     * @param options options to configure the scope
     * @return a new scope
     * @throws IllegalArgumentException if an illegal combination of options is
     *         specified
     */
    public static FiberScope open(Option... options) {
        FiberScope current = current();
        boolean cancellable = true;
        boolean cancelAtClose = false;
        boolean propagateCancel = false;

        if (options.length > 0) {
            for (Option option : options) {
                Objects.requireNonNull(option);
                switch (option) {
                    case IGNORE_CANCEL:
                        cancellable = false;
                        break;
                    case CANCEL_AT_CLOSE:
                        cancelAtClose = true;
                        break;
                    case PROPAGATE_CANCEL:
                        propagateCancel = true;
                        break;
                }
            }

            // check for invalidate combinations of options
            if (!cancellable && (cancelAtClose || propagateCancel)) {
                throw new IllegalArgumentException("IGNORE_CANCEL specified with other options");
            }
        } else {
            // inherit IGNORE_CANCEL when no options specified
            cancellable = current().isCancellable();
        }

        // inherit PROPAGATE_CANCEL when new scope is cancellable
        propagateCancel |= (cancellable && current.propagatesCancel());

        // Threads don't support cancellation so create TimedFiberScope scope when
        // the parent scope has a deadline
        if (propagateCancel
                && Fiber.currentFiber() == null
                && current instanceof TimedFiberScope
                && current.owner() instanceof TimedFiberScope) {
            Instant deadline = ((TimedFiberScope) current).deadline();
            return new TimedFiberScope(deadline, cancelAtClose);
        }

        if (cancellable && (cancelAtClose || propagateCancel)) {
            return new CancellingFiberScope(cancelAtClose, propagateCancel);
        } else {
            return new FiberScope(cancellable);
        }
    }

    /**
     * Creates and enters a new scope. This method is equivalent to creating the
     * scope with the {@linkplain #open(Option...)} method with the {@linkplain
     * Option#PROPAGATE_CANCEL PROPAGATE_CANCEL} option.
     *
     * <p> If the deadline is reached before the owner has exited the scope then
     * all fibers scheduled in the scope (that haven't terminated) are cancelled.
     * Furthermore, if the owner is not waiting in the {@code close} method when
     * the deadline is reached then it is {@link Fiber#cancel() cancelled} (if
     * it's a fiber) or {@link Thread#interrupt() interrupted} (if it's a thread).
     *
     * @param deadline the deadline
     * @param options options to configure the scope
     * @return a new scope
     * @throws IllegalArgumentException if {@code IGNORE_CANCEL} is specified
     */
    public static FiberScope open(Instant deadline, Option... options) {
        Objects.requireNonNull(deadline);
        boolean cancelAtClose = false;
        for (Option option : options) {
            Objects.requireNonNull(option);
            if (option == Option.IGNORE_CANCEL) {
                throw new IllegalArgumentException("IGNORE_CANCEL not allowed");
            }
            if (option == Option.CANCEL_AT_CLOSE) {
                cancelAtClose = true;;
            }
        }
        return new TimedFiberScope(deadline, cancelAtClose);
    }

    /**
     * Creates and enters a new scope. This method is equivalent to creating the
     * scope with the {@linkplain #open(Option...)} method with the {@linkplain
     * Option#PROPAGATE_CANCEL PROPAGATE_CANCEL} option.
     *
     * <p> If the timeout expires before the owner has exited the scope then
     * all fibers scheduled in the scope (that haven't terminated) are cancelled.
     * Furthermore, if the owner is not waiting in the {@code close} method when
     * the timeout expires then it is {@link Fiber#cancel() cancelled} (if
     * it's a fiber) or {@link Thread#interrupt() interrupted} (if it's a thread).
     *
     * @param timeout the timeout
     * @param options options to configure the scope
     * @return a new scope
     * @throws IllegalArgumentException if {@code IGNORE_CANCEL} is specified
     */
    public static FiberScope open(Duration timeout, Option... options) {
        return open(Instant.now().plus(timeout), options);
    }

    /**
     * Invoked when a fiber is initially scheduled.
     *
     * <p> The method is invoked on the thread or the fiber in the scope that
     * created the given fiber. The method is invoked before the given fiber is
     * initially scheduled.
     *
     * @param fiber the newly created, but not scheduled, fiber
     */
    void onSchedule(Fiber<?> fiber) { }

    <V> Fiber<V> schedule(Fiber<V> fiber) {
        FiberScope scope = current();
        assert scope != null;
        while (scope != this) {
            scope = scope.parent;
            if (scope == null) {
                throw new IllegalCallerException("Caller not in fiber scope");
            }
        }

        onSchedule(fiber);

        // onSchedule may have closed the scope
        if (closed) {
            throw new IllegalStateException("Scope is closed");
        }

        COUNT.getAndAdd(this, 1);
        return fiber.schedule(this);
    }

    /**
     * Creates and schedules a new {@link Fiber fiber} to run the given task in
     * this scope. The fiber is scheduled in this scope with the default scheduler.
     *
     * <p> If the scope owner is {@link Fiber#isCancelled() cancelled} and this
     * scope was created with (or inherited) the {@linkplain Option#PROPAGATE_CANCEL
     * PROPAGATE_CANCEL} option then the fiber is scheduled with its cancel status
     * set.
     *
     * @param task the task to execute
     * @param <V> the result type
     * @return the fiber
     * @throws IllegalCallerException if the caller thread or fiber is not
     *         executing in the scope
     */
    public final <V> Fiber<V> schedule(Runnable task) {
        return schedule(new Fiber<>(task));
    }

    /**
     * Creates and schedules a new {@link Fiber fiber} to run the given task in
     * this scope. The fiber is scheduled in this scope with the default scheduler.
     *
     * @param task the task to execute
     * @param <V> the result type
     * @return the fiber
     * @throws IllegalCallerException if the caller thread or fiber is not
     *         executing in the scope
     */
    public final <V> Fiber<V> schedule(Callable<? extends V> task) {
        return schedule(new Fiber<V>(task));
    }

    /**
     * Creates and schedules a new {@link Fiber fiber} to run the given task in
     * this scope. The fiber is scheduled in this scope with the given scheduler.
     *
     * @param scheduler the scheduler
     * @param task the task to execute
     * @param <V> the result type
     * @return the fiber
     * @throws IllegalCallerException if the caller thread or fiber is not
     *         executing in the scope
     */
    public final <V> Fiber<V> schedule(Executor scheduler, Runnable task) {
        return schedule(new Fiber<>(scheduler, task));
    }

    /**
     * Creates and schedules a new {@link Fiber fiber} to run the given task in
     * this scope. The fiber is scheduled in this scope with the given scheduler.
     *
     * @param scheduler the scheduler
     * @param task the task to execute
     * @param <V> the result type
     * @return the fiber
     * @throws IllegalCallerException if the caller thread or fiber is not
     *         executing in the scope
     */
    public final <V> Fiber<V> schedule(Executor scheduler, Callable<? extends V> task) {
        return schedule(new Fiber<>(scheduler, task));
    }

    /**
     * Invoked by a fiber when the task completes.
     *
     * @param result the task result
     * @param exc the exception
     */
    void onComplete(Object result, Throwable exc) { }

    /**
     * Invoked by the fiber when the task completes.
     */
    final void afterTask(Object result, Throwable exc) {
        onComplete(result, exc);
    }

    void onCancel() { }

    /**
     * Invoked when the owner fiber is cancelled
     */
    final void afterCancel() {
        if (cancellable) {
            onCancel();
        }
    }

    /**
     * Invoked on a carrier thread when a fiber terminates.
     */
    void afterTerminate() {
        COUNT.getAndAdd(this, -1);
        if (closed && count == 0) {
            closeLock.lock();
            try {
                Condition condition = closeCondition;
                if (condition != null) {
                    condition.signalAll();
                }
            } finally {
                closeLock.unlock();
            }
        }
    }

    /**
     * Returns true the scope is open.
     */
    final boolean isOpen() {
        return !closed;
    }

    /**
     * Invoked by the owner thread/fiber when close is invoked.
     */
    void onClose() { }

    void onAfterClose() { }

    /**
     * Closes and exits the scope. This method waits for all fibers scheduled in
     * the scope to terminate.
     *
     * <p> If this scope was created with the {@linkplain Option#CANCEL_AT_CLOSE
     * CANCEL_AT_CLOSE} option then all fibers scheduled in the scope that haven't
     * terminated are {@link Fiber#cancel() cancelled}.
     *
     * <p> Once exited, the current thread or fiber will return to the parent
     * scope that this thread or fiber was in before it entered this scope. If
     * the current fiber is cancelled and is the owner of the parent scope then
     * all fibers in the parent scope are cancelled.
     *
     * <p> If this scope is already closed then the ownner invoking this metho
     * has no effect.
     *
     * @throws IllegalCallerException if the caller is not the owner
     * @throws IllegalStateException if the caller is in a nested scope
     */
    @Override
    public final void close() {
        if (Strands.currentStrand() != owner)
            throw new IllegalCallerException();
        if (closed)
            return;
        if (current() != this)
            throw new IllegalStateException();
        closed = true;

        try {
            onClose();
        } finally {
            finishClose();
        }
        onAfterClose();
    }

    private void finishClose() {
        boolean interrupted = false;
        while (count > 0) {
            closeLock.lock();
            try {
                Condition condition = this.closeCondition;
                if (count > 0) {
                    if (condition == null) {
                        this.closeCondition = condition = closeLock.newCondition();
                    }
                    try {
                        condition.await();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            } finally {
                closeLock.unlock();
            }
            if (interrupted) {
                onCancel();
            }
        }

        if (interrupted) {
            Strands.interruptSelf();
        }

        // restore to parent scope
        if (owner instanceof Thread) {
            Thread thread = (Thread) owner;
            assert thread.scope() == this;
            thread.setScope(parent);
        } else {
            Fiber<?> fiber = (Fiber<?>) owner;
            assert fiber.scope() == this;
            fiber.setScope(parent);
        }

        // propagate cancel to parent scope
        if (parent != null && parent.owner() == owner && Fiber.cancelled()) {
            parent.afterCancel();
        }
    }
}

/**
 * The background (top-most) FiberScope that does not track fibers scheduled in
 * the scope.
 */
class BackgroundFiberScope extends FiberScope {
    BackgroundFiberScope() {
        super(null, true);
    }

    @Override
    <V> Fiber<V> schedule(Fiber<V> fiber) {
        return fiber.schedule(this);
    }

    @Override
    void afterTerminate() {
        // do nothing
    }
}

/**
 * A FiberScope that supports cancelling fibers scheduled in the scope. The
 * fibers can be cancelled when the owner is cancelled and/or when the owner
 * closes the scope.
 */
class CancellingFiberScope extends FiberScope {
    private final Set<Fiber<?>> fibers = ConcurrentHashMap.newKeySet();
    private final boolean cancelAtClose;
    private final boolean propagateCancel;
    private volatile boolean cancelled;

    CancellingFiberScope(boolean cancelAtClose, boolean propagateCancel) {
        super(true);
        this.cancelAtClose = cancelAtClose;
        this.propagateCancel = propagateCancel;
    }

    @Override
    boolean propagatesCancel() {
        return propagateCancel;
    }

    /**
     * Invoked on the thread or fiber in the scope when scheduling a newly
     * created fiber to execute.
     */
    @Override
    void onSchedule(Fiber<?> fiber) {
        assert fiber.getState() == Thread.State.NEW;
        fibers.add(fiber);
        if (cancelled) {
            fiber.cancel();
        }
    }

    /**
     * Invoked by the fiber when the task completes.
     */
    @Override
    void onComplete(Object result, Throwable exc) {
        Fiber<?> fiber = Fiber.currentFiber();
        assert fiber != null;
        fibers.remove(fiber);
    }

    /**
     * Invoked on the (potentially arbitrary) thread or fiber when it cancels
     * the scope owner.
     */
    @Override
    void onCancel() {
        cancelled = true;
        if (propagateCancel) {
            fibers.forEach(Fiber::cancel);
        }
    }

    /**
     * Invoked by the owner when it closes the scope.
     */
    @Override
    void onClose() {
        if (cancelAtClose) {
            cancelled = true;
            fibers.forEach(Fiber::cancel);
        }
    }
}

/**
 * A FiberScope that supports cancelling all fibers scheduled in the scope when
 * a deadline is reached.
 */
class TimedFiberScope extends CancellingFiberScope {
    private final Instant deadline;
    private final Future<?> timer;

    TimedFiberScope(Instant deadline, boolean cancelAtClose) {
        super(cancelAtClose, true);
        Duration timeout = Duration.between(Instant.now(), deadline);
        long nanos = TimeUnit.NANOSECONDS.convert(timeout);
        this.deadline = deadline;
        this.timer = timeoutScheduler.schedule(this::timeoutExpired, nanos, TimeUnit.NANOSECONDS);
    }

    Instant deadline() {
        return deadline;
    }

    /**
     * Invoked when the deadline is reached before the timer task is cancelled.
     */
    private void timeoutExpired() {
        // interrupt or cancel owner if it hasn't invoked close
        if (isOpen()) {
            Object owner = owner();
            if (owner instanceof Fiber) {
                ((Fiber<?>) owner).cancel();
            } else {
                ((Thread) owner).interrupt();
            }
        }

        // cancels the fibers scheduled in the scope
        onCancel();
    }

    /**
     * Invoked on the thread or fiber in the scope when scheduling a newly
     * created fiber to execute.
     */
    @Override
    void onSchedule(Fiber<?> fiber) {
        super.onSchedule(fiber);

        // cancel the fiber if the deadline has been reached
        if (timer.isDone()) {
            fiber.cancel();
        }
    }

    /**
     * Invoked by the owner thread/fiber after the scope is closed.
     */
    @Override
    void onAfterClose() {
        timer.cancel(false);
    }

    private static final ScheduledExecutorService timeoutScheduler = timeoutScheduler();
    private static ScheduledExecutorService timeoutScheduler() {
        ScheduledThreadPoolExecutor stpe = (ScheduledThreadPoolExecutor)
            Executors.newScheduledThreadPool(1, r ->
                AccessController.doPrivileged(new PrivilegedAction<>() {
                    public Thread run() {
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        return t;
                    }
                }));
        stpe.setRemoveOnCancelPolicy(true);
        return stpe;
    }
}
