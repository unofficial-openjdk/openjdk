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

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.BlockingSource;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import jdk.internal.misc.Strands;

/**
 * A scope in which {@link Fiber}s are scheduled with support for cancellation,
 * deadlines, and reaping of terminated fibers.
 *
 * <p> A {@code FiberScope} is created and <em>entered</em> by invoking one of
 * the static methods defined by this class. It is <em>exited</em> by invoking
 * its {@linkplain #close() close} method. A scope can only be exited when all
 * fibers scheduled in the scope have terminated and thus the {@code close}
 * method blocks until all fibers scheduled in the scope have terminated.
 * As a special case, fibers can be scheduled in the {@linkplain #detached()
 * detached} scope for cases where fibers are <em>unmanaged</em> or need to
 * <em>outlive</em> the thread or fiber that schedules them.
 *
 * <p> {@code FiberScope} implements {@linkplain AutoCloseable} so that the
 * try-with-resources statement can be used to ensure that a scope is exited.
 * The following example schedules two fibers in a scope. The try-with-resources
 * statement completes when the block completes and both fibers scheduled in the
 * scope terminate.
 *
 * <pre>{@code
 *     try (var scope = FiberScope.cancellable()) {
 *         scope.schedule(taskOne);
 *         scope.schedule(taskTwo);
 *     });
 * }</pre>
 *
 * <p> Fiber scopes support cancellation. Fibers test for cancellation by invoking
 * the {@linkplain Fiber#cancelled()} method. If a {@code Fiber} executing in
 * a scope is cancelled then all fibers scheduled in the scope are cancelled.
 * As a special case for cleanup and recovery operations, the {@linkplain
 * #notCancellable} method creates and enters a scope that <em>shields</em> a
 * fiber from cancellation. {@code Fiber.cancelled()} always returns {@code false}
 * when running in a <em>non-cancelable</em> scope.
 *
 * <p> Fiber scopes support deadlines and timeouts. The {@linkplain
 * #withDeadline(Instant) withDeadline} method enters a fiber scope that cancels
 * the fiber in the scope, and all fibers scheduled in the scope, when the deadline
 * expires. The {@linkplain #withTimeout(Duration) withTimeout} is similar for
 * cases where a timeout is used.
 *
 * <p> Fiber scopes may be nested. A thread or fiber executing in a scope may
 * enter another nested scope. Any fibers scheduled in the inner scope must
 * terminate to allow the thread or fiber exit back to the outer scope. Cancellation
 * propagates when cancellable scopes are nested. Fiber scopes using deadlines
 * and timeouts may also be nested.
 *
 * <p> {@code FiberScope} defines {@code schedule} methods that allow a {@link
 * TerminationQueue termination queue} to be specified. A fiber scheduled with
 * one of these methods is queued to the termination queue when it terminates.
 * In conjunction with the {@linkplain Fiber#join() join} method, this provides
 * an easy way to collect the result of tasks executed by fibers (or exceptions
 * in cases cases where the task terminates with an exception).
 *
 * <p> Unless otherwise noted, passing a {@code null} argument will cause a
 * {@linkplain NullPointerException} to be thrown.
 *
 * @apiNote
 * The following example is a method that schedules a fiber for each task
 * specified to the method. It returns the result of the first task that completes,
 * cancelling and waiting for any outstanding fibers to terminate before it
 * returns.
 *
 * <pre>{@code
 *     <V> V anyOf(Callable<? extends V>[] tasks) throws Throwable {
 *         var queue = new FiberScope.TerminationQueue<V>();
 *         try (var scope = FiberScope.cancellable()) {
 *             Arrays.stream(tasks).forEach(task -> scope.schedule(task, queue));
 *             try {
 *                 return queue.take().join();
 *             } catch (CompletionException e) {
 *                 throw e.getCause();
 *             } finally {
 *                 // cancel any fibers that are still running
 *                 scope.fibers().forEach(Fiber::cancel);
 *             }
 *         }
 *     }
 * }</pre>
 * An alternative for this example would be to use {@link Fiber#toFuture()} and
 * {@link java.util.concurrent.CompletableFuture#anyOf CompletableFuture.anyOf(...)}
 * to wait for one of the tasks to complete.
 *
 * <p> The following extends this example to return the result of the first
 * <em>successful</em> task. If no task succeeds then it throws the exception
 * from the first unsuccessful task to complete. This method creates and enters
 * the scope with a deadline so that all fibers are cancelled if the deadline
 * expires before a result is returned.
 *
 * <pre>{@code
 *     <V> V anySuccessful(Callable<? extends V>[] tasks, Instant deadline) throws Throwable {
 *         try (var scope = FiberScope.withDeadline(deadline)) {
 *             var queue = new FiberScope.TerminationQueue<V>();
 *             Arrays.stream(tasks).forEach(task -> scope.schedule(task, queue));
 *             Throwable firstException = null;
 *             int remaining = tasks.length;
 *             while (remaining > 0) {
 *                 try {
 *                     V result = queue.take().join();
 *                     // cancel any fibers that are still running
 *                     scope.fibers().forEach(Fiber::cancel);
 *                     return result;
 *                 } catch (CompletionException e) {
 *                     if (firstException == null) {
 *                         firstException = e.getCause();
 *                     }
 *                 }
 *                 remaining--;
 *             }
 *             throw firstException;
 *         }
 *     }
 * }</pre>
 *
 * <p> The following is a more complicated example. The method is called with
 * an array of socket addresses and returns a SocketChannel connected to one
 * of the addresses. The connection attempts are staggered. A fiber is scheduled
 * to connect to the first socket address and if the connection is not
 * established within a certain time then another fiber is scheduled to try to
 * connect to the next socket address. The staggered attempts continue until a
 * connection is established, none of the connections succeed, or the deadline
 * is reached. In the event that several connections are established then all
 * but one are closed so that the method does not leak resources.
 * <pre>{@code
 *     SocketChannel connectAny(SocketAddress[] addresses,
 *                              Instant deadline,
 *                              Duration staggerInterval) throws Exception {
 *         assert addresses.length > 0;
 *
 *         SocketChannel channel = null;
 *         Exception exception = null;
 *         var queue = new FiberScope.TerminationQueue<SocketChannel>();
 *
 *         try (var scope = FiberScope.withDeadline(deadline)) {
 *
 *             // schedule a fiber to connect to the first address
 *             scope.schedule(() -> SocketChannel.open(addresses[0]), queue);
 *
 *             var realDeadline = FiberScope.currentDeadline().orElseThrow();
 *             Duration waitTime = staggerInterval;
 *
 *             int remaining = 1;
 *             int next = 1; // index of next address to try
 *             while (remaining > 0) {
 *
 *                 // wait for a timeout or a fiber to terminate
 *                 Fiber<SocketChannel> fiber = queue.poll(waitTime);
 *                 if (fiber != null) {
 *                     remaining--;
 *                     try {
 *                         SocketChannel ch = fiber.join();
 *                         if (channel == null) {
 *                             // first successful connection, cancel other attempts
 *                             channel = ch;
 *                             scope.fibers().forEach(Fiber::cancel);
 *                         } else {
 *                             // another connection is established, it's not needed
 *                             ch.close();
 *                         }
 *                     } catch (CompletionException e) {
 *                         // connect failed, remember first exception
 *                         if (channel == null && exception == null) {
 *                             exception = (Exception) e.getCause();
 *                         }
 *                     }
 *                 }
 *
 *                 // if no connection has been established, the deadline hasn't been
 *                 // reached, and there are further addresses to try, then schedule
 *                 // a fiber to connect to the next address
 *                 boolean expired = realDeadline.compareTo(Instant.now()) <= 0;
 *                 if (channel == null && !expired && next < addresses.length) {
 *                     var address = addresses[next++];
 *                     scope.schedule(() -> SocketChannel.open(address), queue);
 *                     remaining++;
 *                 }
 *
 *                 // if the deadline has been reached or there are no more addresses
 *                 // to try then bump the waiting time to avoid needless wakeup
 *                 if (expired || next >= addresses.length) {
 *                     waitTime = Duration.ofSeconds(Long.MAX_VALUE);
 *                 }
 *             }
 *         }
 *
 *         assert channel != null || exception != null;
 *         if (channel != null) {
 *             return channel;
 *         } else {
 *             throw exception;
 *         }
 *     }
 * }</pre>
 */

public class FiberScope implements AutoCloseable {
    private static final FiberScope DETACHED = new DetachedFiberScope();

    FiberScope() { }

    /**
     * A termination queue to specify when scheduling a fiber. The fiber is queued
     * when it terminates. A {@code TerminationQueue} does not define methods to
     * insert elements, it only define methods to retrieve terminated fibers.
     *
     * @apiNote TDB if the FiberScope API should allow arbitrary queues to be
     * specified. Disallowing arbitrary queues eliminates several concerns that
     * would otherwise arise with notifications on the carrier thread.
     *
     * @param <V> the task result type
     */
    public static final class TerminationQueue<V> implements BlockingSource<Fiber<V>> {
        private final BlockingQueue<Fiber<? extends V>> queue;

        /**
         * Creates a termination queue.
         */
        public TerminationQueue() {
            this.queue = new LinkedBlockingQueue<>();
        }

        void put(Fiber<? extends V> fiber) {
            boolean interrupted = false;
            boolean done = false;
            while (!done) {
                try {
                    queue.put(fiber);
                    done = true;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public Fiber<V> take() throws InterruptedException {
            return (Fiber<V>) queue.take();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Fiber<V> poll() {
            return (Fiber<V>) queue.poll();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Fiber<V> poll(Duration duration) throws InterruptedException {
            long nanos = TimeUnit.NANOSECONDS.convert(duration);
            return (Fiber<V>) queue.poll(nanos, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Returns the <em>detached</em> scope. This scope cannot be exited, its
     * {@link #close() close} method always fails. Fibers scheduled in this scope
     * can be {@link Fiber#cancel() cancelled}.
     *
     * @apiNote Naming is an issue here, suggestions include "global" and
     * "primordial".
     *
     * @return the detached scope
     */
    public static FiberScope detached() {
        return DETACHED;
    }

    /**
     * Creates and enters a <em>cancellable</em> scope. The current {@link
     * Thread#currentThread() thread} or {@link Fiber#current() fiber} is the
     * <em>owner</em> of the scope, only the owner can exit the scope with the
     * {@linkplain #close() close} method. If the owner is a fiber and it is
     * {@link Fiber#cancel() cancelled} then all fibers scheduled in the
     * scope are also cancelled.
     *
     * @return a <em>cancellable</em> scope.
     */
    public static FiberScope cancellable() {
        return new FiberScopeImpl(true, null);
    }

    /**
     * Creates and enters a <em>non-cancellable</em> scope. The current {@link
     * Thread#currentThread() thread} or {@link Fiber#current() fiber} is the
     * <em>owner</em> of the scope, only the owner can exit the scope with the
     * {@linkplain #close() close} method. If the owner is a fiber and it is
     * {@link Fiber#cancel() cancelled} then the cancellation is not propagated
     * to fibers scheduled in the scope. Non-cancellable scopes are intended for
     * cleanup and recovery operations that need to be shielded from cancellation.
     *
     * @return a <em>non-cancellable</em> scope.
     */
    public static FiberScope notCancellable() {
        return new FiberScopeImpl(false, null);
    }

    /**
     * Creates and enters a <em>cancellable</em> scope. The current {@link
     * Thread#currentThread() thread} or {@link Fiber#current() fiber} is the
     * <em>owner</em> of the scope, only the owner can exit the scope with the
     * {@linkplain #close() close} method. If the deadline is reached before the
     * scope is exited then all fibers scheduled in the scope are cancelled,
     * along with the owner when it is a fiber.
     *
     * @param deadline the deadline
     * @return a <em>cancellable</em> scope that cancels fibers when the deadline
     *         is reached
     */
    public static FiberScope withDeadline(Instant deadline) {
        return new FiberScopeImpl(true, Objects.requireNonNull(deadline));
    }

    /**
     * Creates and enters a <em>cancellable</em> scope. The current {@link
     * Thread#currentThread() thread} or {@link Fiber#current() fiber} is the
     * <em>owner</em> of the scope, only the owner can exit the scope with the
     * {@linkplain #close() close} method. If the timeout expires before the
     * scope is exited then all fibers scheduled in the scope are cancelled,
     * along with the owner when it is a fiber.
     *
     * @param timeout the timeout
     * @return a <em>cancellable</em> scope that cancels fibers when the timeout
     *         expires
     */
    public static FiberScope withTimeout(Duration timeout) {
        return withDeadline(Instant.now().plus(timeout));
    }

    /**
     * Creates and schedules a new {@link Fiber fiber} to run the given task.
     * The fiber is scheduled in this scope with the default scheduler.
     *
     * @param task the task to execute
     * @return the fiber
     * @throws IllegalCallerException if the caller thread or fiber is not
     *         executing in the scope
     */
    public Fiber<?> schedule(Runnable task) {
        Fiber<?> fiber = Fiber.newFiber(task);
        fiber.schedule(this, null);
        return fiber;
    }

    /**
     * Creates and schedules a new {@link Fiber fiber} to run the given task.
     * The fiber is scheduled in this scope with the default scheduler and
     * queued to the given termination queue when it terminates.
     *
     * @param task the task to execute
     * @param queue the termination queue
     * @return the fiber
     * @throws IllegalCallerException if the caller thread or fiber is not
     *         executing in the scope
     */
    @SuppressWarnings("unchecked")
    public Fiber<?> schedule(Runnable task, TerminationQueue<?> queue) {
        Objects.requireNonNull(queue);
        Fiber<?> fiber = Fiber.newFiber(task);
        fiber.schedule(this, (TerminationQueue<Object>) queue);
        return fiber;
    }

    /**
     * Creates and schedules a new {@link Fiber fiber} to run the given task.
     * The fiber is scheduled in this scope with the given scheduler.
     *
     * @param scheduler the schedule
     * @param task the task to execute
     * @return the fiber
     * @throws IllegalCallerException if the caller thread or fiber is not
     *         executing in the scope
     * @throws RejectedExecutionException if the scheduler cannot accept a task
     */
    public Fiber<?> schedule(Executor scheduler, Runnable task) {
        Fiber<?> fiber = Fiber.newFiber(scheduler, task);
        fiber.schedule(this, null);
        return fiber;
    }

    /**
     * Creates and schedules a new {@link Fiber fiber} to run the given task.
     * The fiber is scheduled in this scope with the given scheduler and
     * queued to the given termination queue when it terminates.
     *
     * @param scheduler the schedule
     * @param task the task to execute
     * @param queue the termination queue
     * @return the fiber
     * @throws IllegalCallerException if the caller thread or fiber is not
     *         executing in the scope
     * @throws RejectedExecutionException if the scheduler cannot accept a task
     */
    @SuppressWarnings("unchecked")
    public Fiber<?> schedule(Executor scheduler, Runnable task, TerminationQueue<?> queue) {
        Objects.requireNonNull(queue);
        Fiber<?> fiber = Fiber.newFiber(scheduler, task);
        fiber.schedule(this, (TerminationQueue<Object>) queue);
        return fiber;
    }

    /**
     * Creates and schedules a new {@link Fiber fiber} to run the given task.
     * The fiber is scheduled in this scope with the default scheduler.
     *
     * @param task the task to execute
     * @param <V> the task's result type
     * @return the fiber
     * @throws IllegalCallerException if the caller thread or fiber is not
     *         executing in the scope
     */
    public <V> Fiber<V> schedule(Callable<? extends V> task) {
        Fiber<V> fiber = Fiber.newFiber(task);
        fiber.schedule(this, null);
        return fiber;
    }

    /**
     * Creates and schedules a new {@link Fiber fiber} to run the given task.
     * The fiber is scheduled in this scope with the default scheduler and
     * queued to the given termination queue when it terminates.
     *
     * @param task the task to execute
     * @param queue the termination queue
     * @param <V> the task's result type
     * @return the fiber
     * @throws IllegalCallerException if the caller thread or fiber is not
     *         executing in the scope
     */
    public <V> Fiber<V> schedule(Callable<? extends V> task, TerminationQueue<? super V> queue) {
        Objects.requireNonNull(queue);
        Fiber<V> fiber = Fiber.newFiber(task);
        fiber.schedule(this, queue);
        return fiber;
    }

    /**
     * Creates and schedules a new {@link Fiber fiber} to run the given task.
     * The fiber is scheduled in this scope with the given scheduler.
     *
     * @param scheduler the schedule
     * @param task the task to execute
     * @param <V> the task's result type
     * @return the fiber
     * @throws IllegalCallerException if the caller thread or fiber is not
     *         executing in the scope
     * @throws RejectedExecutionException if the scheduler cannot accept a task
     */
    public <V> Fiber<V> schedule(Executor scheduler, Callable<? extends V> task) {
        Fiber<V> fiber = Fiber.newFiber(scheduler, task);
        fiber.schedule(this, null);
        return fiber;
    }

    /**
     * Creates and schedules a new {@link Fiber fiber} to run the given task.
     * The fiber is scheduled in this scope with the given scheduler and
     * queued to the given termination queue when it terminates.
     *
     * @param scheduler the schedule
     * @param task the task to execute
     * @param queue the termination queue
     * @param <V> the task's result type
     * @return the fiber
     * @throws IllegalCallerException if the caller thread or fiber is not
     *         executing in the scope
     * @throws RejectedExecutionException if the scheduler cannot accept a task
     */
    public <V> Fiber<V> schedule(Executor scheduler, Callable<? extends V> task,
                                 TerminationQueue<? super V> queue) {
        Objects.requireNonNull(queue);
        Fiber<V> fiber = Fiber.newFiber(scheduler, task);
        fiber.schedule(this, queue);
        return fiber;
    }

    /**
     * Closes/exits this scope. This method waits until all fibers scheduled in
     * the scope have terminated. If the {@link #currentDeadline() current deadline}
     * has expired then {@code CancellationException} is thrown after all fibers
     * scheduled in the scope have terminated.
     *
     * <p> If this scope is already closed then invoking this method has no
     * effect.
     *
     * @throws IllegalCallerException if not called from the thread or fiber
     *         that created and entered the scope
     * @throws CancellationException if the deadline has expired
     */
    @Override
    public void close() {
        throw new RuntimeException("not implemented");
    }

    /**
     * Returns a {@code Stream} of the fibers scheduled in this scope that are
     * still {@link Fiber#isAlive() alive}. This method returns an empty
     * stream for the {@linkplain #detached()} detached} scope.
     *
     * @return a stream of the active fibers in the scope
     */
    public Stream<Fiber<?>> fibers() {
        throw new RuntimeException("not implemented");
    }

    /**
     * Returns the <em>current deadline</em>, if any. The current deadline may
     * be the deadline of an enclosing scope. Enclosing scopes up to, and
     * excluding, the closest enclosing non-cancellable scope are considered.
     *
     * @return the current deadline or an empty {@code Optional} if there is no
     *         deadline
     */
    public static Optional<Instant> currentDeadline() {
        Object me = Strands.currentStrand();
        FiberScope scope;
        if (me instanceof Thread) {
            scope = ((Thread) me).scope();
        } else {
            scope = ((Fiber) me).scope();
        }
        Instant deadline = null;
        while ((scope instanceof FiberScopeImpl) && scope.isCancellable()) {
            Instant d = ((FiberScopeImpl) scope).deadline();
            if (d != null && (deadline == null || deadline.compareTo(d) > 0)) {
                deadline = d;
            }
            scope = scope.previous();
        }
        return Optional.ofNullable(deadline);
    }

    /**
     * Returns the previous scope when nested.
     */
    FiberScope previous() {
        throw new RuntimeException("not implemented");
    }

    /**
     * Returns true if this scope supports cancellation.
     */
    boolean isCancellable() {
        throw new RuntimeException("not implemented");
    }

    /**
     * Invoked when a fiber is scheduled to add the fiber to this scope
     */
    void onSchedule(Fiber<?> child) { }

    /**
     * Invoked when a fiber terminates
     */
    void onTerminate(Fiber<?> fiber) { }

    /**
     * Invoked when a fiber is cancelled
     */
    void onCancel(Fiber<?> fiber) { }
}

class DetachedFiberScope extends FiberScope {
    @Override
    public void close() {
        throw new IllegalCallerException("not the owner");
    }
    @Override
    public Stream<Fiber<?>> fibers() {
        return Stream.empty();
    }
    @Override
    FiberScope previous() {
        return null;
    }
    @Override
    boolean isCancellable() {
        return true;
    }
}

class FiberScopeImpl extends FiberScope {
    private final boolean cancellable;
    private final Instant deadline;
    private final Object owner;
    private final FiberScope previous;

    // the active set of fibers scheduled in the scope
    private final Set<Fiber<?>> fibers;

    // cancellation and deadline support
    private volatile boolean cancelled;
    private volatile boolean expired;
    private final Future<?> canceller;

    // close/exit support
    private volatile boolean closed;
    private final ReentrantLock closeLock;
    private final Condition closeCondition;

    FiberScopeImpl(boolean cancellable, Instant deadline) {
        Object owner = Strands.currentStrand();

        this.cancellable = cancellable;
        this.deadline = deadline;
        this.owner = owner;
        this.fibers = ConcurrentHashMap.newKeySet();

        if (deadline != null) {
            Duration timeout = Duration.between(Instant.now(), deadline);
            if (timeout.isZero() || timeout.isNegative()) {
                // deadline has already expired
                this.cancelled = true;
                this.expired = true;
                this.canceller = null;
                if (owner instanceof Fiber) {
                    ((Fiber<?>) owner).cancel(/*unpark*/false);
                }
            } else {
                // schedule timer task
                long nanos = TimeUnit.NANOSECONDS.convert(timeout);
                this.canceller = timeoutScheduler.schedule(this::deadlineExpired,
                                                           nanos,
                                                           TimeUnit.NANOSECONDS);
            }
        } else {
            this.canceller = null;
        }

        if (owner instanceof Thread) {
            Thread thread = (Thread) owner;
            this.previous = thread.scope();
            thread.setScope(this);
        } else {
            Fiber<?> fiber = (Fiber<?>) owner;
            this.previous = fiber.scope();
            fiber.setScope(this);

            // invoked by fiber with its cancel status set
            if (cancellable && fiber.isCancelled()) {
                this.cancelled = true;
            }
        }

        this.closeLock = new ReentrantLock();
        this.closeCondition = closeLock.newCondition();
    }

    /**
     * Invoked by the timer task when deadlines expires
     */
    private void deadlineExpired() {
        cancelled = true;
        expired = true;
        fibers.forEach(Fiber::cancel);
        if (owner instanceof Fiber) {
            ((Fiber<?>) owner).cancel(/*unpark*/true);
        }
    }

    @Override
    public void close() {
        if (Strands.currentStrand() != owner)
            throw new IllegalCallerException();
        if (closed)
            return;
        closed = true;

        try {

            // wait for all fibers to terminate
            closeLock.lock();
            try {
                while (!fibers.isEmpty()) {
                    closeCondition.awaitUninterruptibly();
                }
            } finally {
                closeLock.unlock();
            }

            // deadline expired
            if (expired) {
                throw new CancellationException("Deadline expired");
            } else if (canceller != null) {
                // cancel timer task
                canceller.cancel(false);
            }

        } finally {
            // restore to previous scope
            if (owner instanceof Thread) {
                Thread thread = (Thread) owner;
                if (thread.scope() != this)
                    throw new InternalError();
                thread.setScope(previous);
            } else {
                Fiber<?> fiber = (Fiber<?>) owner;
                if (fiber.scope() != this)
                    throw new InternalError();
                fiber.setScope(previous);

                // propagate cancellation to previous scope
                if (fiber.isCancelled() && previous != null) {
                    previous.onCancel(fiber);
                }
            }
        }
    }

    @Override
    public Stream<Fiber<?>> fibers() {
        return fibers.stream();
    }

    @Override
    FiberScope previous() {
        return previous;
    }

    @Override
    boolean isCancellable() {
        return cancellable;
    }

    Instant deadline() {
        return deadline;
    }

    @Override
    void onSchedule(Fiber<?> child) {
        // check called from a thread or fiber in the scope
        Object me = Strands.currentStrand();
        FiberScope scope;
        if (me instanceof Thread) {
            scope = ((Thread) me).scope();
            if (scope == null) {
                throw new IllegalCallerException("caller not in fiber scope");
            }
        } else {
            scope = ((Fiber) me).scope();
            assert scope != null;
        }

        while (scope != this) {
            scope = scope.previous();
            if (scope == null) {
                throw new IllegalCallerException("caller not in fiber scope");
            }
        }

        // add to set; okay to do this when the scope is closed as the current
        // thread or fiber is in the scope
        fibers.add(child);

        // forward cancel status to child if scope has been cancelled.
        if (cancelled) {
            child.cancel(/*unpark*/false);
        }
    }

    @Override
    void onTerminate(Fiber<?> fiber) {
        boolean removed = fibers.remove(fiber);
        assert removed;

        // notify owner if waiting to exit scope
        if (closed && fibers.isEmpty()) {
            closeLock.lock();
            try {
                closeCondition.signalAll();
            } finally {
                closeLock.unlock();
            }
        }
    }

    @Override
    void onCancel(Fiber<?> fiber) {
        if ((fiber == owner) && cancellable) {
            cancelled = true;
            fibers.forEach(Fiber::cancel);
        }
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
                    }}));
        stpe.setRemoveOnCancelPolicy(true);
        return stpe;
    }
}