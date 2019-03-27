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

import jdk.internal.HotSpotIntrinsicCandidate;
import jdk.internal.vm.annotation.DontInline;
import jdk.internal.vm.annotation.ForceInline;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * TBD
 */
public class Continuation {
    // private static final WhiteBox WB = sun.hotspot.WhiteBox.WhiteBox.getWhiteBox();
    private static final jdk.internal.misc.Unsafe unsafe = jdk.internal.misc.Unsafe.getUnsafe();

    private static final boolean TRACE = isEmptyOrTrue("java.lang.Continuation.trace");
    private static final boolean DEBUG = TRACE | isEmptyOrTrue("java.lang.Continuation.debug");

    private static final byte FLAG_LAST_FRAME_INTERPRETED = 1;
    private static final byte FLAG_SAFEPOINT_YIELD = 1 << 1;
    private static final int WATERMARK_THRESHOLD = 10;
    private static final VarHandle MOUNTED;

    /** Reason for pinning */
    public enum Pinned { 
        /** Native frame on stack */ NATIVE,
        /** Monitor held */          MONITOR,
        /** In critical section */   CRITICAL_SECTION }
    /** Preemption attempt result */
    public enum PreemptStatus { 
        /** Success */                                                      SUCCESS(null), 
        /** Permanent failure */                                            PERM_FAIL_UNSUPPORTED(null), 
        /** Permanent failure: continuation alreay yielding */              PERM_FAIL_YIELDING(null), 
        /** Permanent failure: continuation not mounted on the thread */    PERM_FAIL_NOT_MOUNTED(null), 
        /** Transient failure: continuation pinned due to a held CS */      TRANSIENT_FAIL_PINNED_CRITICAL_SECTION(Pinned.CRITICAL_SECTION),
        /** Transient failure: continuation pinned due to native frame */   TRANSIENT_FAIL_PINNED_NATIVE(Pinned.NATIVE), 
        /** Transient failure: continuation pinned due to a held monitor */ TRANSIENT_FAIL_PINNED_MONITOR(Pinned.MONITOR);

        final Pinned pinned;
        private PreemptStatus(Pinned reason) { this.pinned = reason; }
        /** 
         * TBD
         * @return TBD
         **/
        public Pinned pinned() { return pinned; }
    }

    private static PreemptStatus preemptStatus(int status) {
        switch (status) {
            case -5: return PreemptStatus.PERM_FAIL_UNSUPPORTED;
            case  0: return PreemptStatus.SUCCESS;
            case -1: return PreemptStatus.PERM_FAIL_NOT_MOUNTED;
            case -2: return PreemptStatus.PERM_FAIL_YIELDING;
            case  1: return PreemptStatus.TRANSIENT_FAIL_PINNED_CRITICAL_SECTION;
            case  2: return PreemptStatus.TRANSIENT_FAIL_PINNED_NATIVE;
            case  3: return PreemptStatus.TRANSIENT_FAIL_PINNED_MONITOR;
            default: throw new AssertionError("Unknown status: " + status);
        }
    }

    private static Pinned pinnedReason(int reason) {
        switch (reason) {
            case 1: return Pinned.CRITICAL_SECTION;
            case 2: return Pinned.NATIVE;
            case 3: return Pinned.MONITOR;
            default:
                throw new AssertionError("Unknown pinned reason: " + reason);
        }
    }

    private static Thread currentCarrierThread() {
        return Thread.currentCarrierThread();
    }

    static {
        try {
            registerNatives();

            MethodHandles.Lookup l = MethodHandles.lookup();
            MOUNTED = l.findVarHandle(Continuation.class, "mounted", boolean.class);
        } catch (Exception e) {
            throw new InternalError(e);
        }
    }

    private Runnable target;

    /* While the native JVM code is aware that every continuation has a scope, it is, for the most part,
     * oblivious to the continuation hierarchy. The only time this hierarchy is traversed in native code
     * is when a hierarchy of continuations is mounted on the native stack.
     */
    private final ContinuationScope scope;
    private Continuation parent; // null for native stack
    private Continuation child; // non-null when we're yielded in a child continuation

    // The content of the stack arrays is extremely security-sensitive. Writing can lead to arbitrary code execution, and reading can leak sensitive data
    private int[] stack = null; // grows down
    private Object[] refStack = null;

    private long fp = 0; // an index into the h-stack if the top frame is interpreted, otherwise, the value of rbp
    private int sp = -1; // index into the h-stack
    private long pc = 0;
    private int refSP;
    private int maxSize; // maximal stack size when unpacked
    private byte flags;
    private boolean done;
    private volatile boolean mounted = false;
    private Object yieldInfo;

    private short cs; // critical section semaphore

    private boolean reset = false; // perftest only

    // transient state
    // addresses into vstack. only valid when mounted
    private long entrySP = 0;
    private long entryFP = 0;
    private long entryPC = 0;

    // monitoring
    private short numFrames;
    private short numInterpretedFrames;

    private byte sizeCounter;
    private int stackWatermark;
    private int refStackWatermark;

    // private long[] nmethods = null; // grows up
    // private int numNmethods = 0;

    /**
     * TBD
     * @param scope TBD
     * @param target TBD
     */
    public Continuation(ContinuationScope scope, Runnable target) {
        this.scope = scope;
        this.target = target;
    }

    /**
     * TBD
     * @param scope TBD
     * @param target TBD
     * @param stackSize in bytes
     */
    public Continuation(ContinuationScope scope, int stackSize, Runnable target) {
        this(scope, target);
        getStacks(stackSize, stackSize, stackSize / 8);
    }

    @Override
    public String toString() {
        return super.toString() + " scope: " + scope;
    }

    ContinuationScope getScope() {
        return scope;
    }

    Continuation getParent() {
        return parent;
    }

    /**
     * TBD
     * @param scope TBD
     * @return TBD
     */
    public static Continuation getCurrentContinuation(ContinuationScope scope) {
        Continuation cont = currentCarrierThread().getContinuation();
        while (cont != null && cont.scope != scope)
            cont = cont.parent;
        return cont;
    }

    /**
     * TBD
     * @return TBD
     */
    public StackWalker stackWalker() {
        return stackWalker(EnumSet.noneOf(StackWalker.Option.class));
    }

    /**
     * TBD
     * @param option TBD
     * @return TBD
     */
    public StackWalker stackWalker(StackWalker.Option option) {
        return stackWalker(EnumSet.of(Objects.requireNonNull(option)));
    }

    /**
     * TBD
     * @param options TBD
     * @return TBD
     */
    public StackWalker stackWalker(Set<StackWalker.Option> options) {
        return stackWalker(options, this.scope);
    }

    /**
     * TBD
     * @param options TBD
     * @param scope TBD
     * @return TBD
     */
    public StackWalker stackWalker(Set<StackWalker.Option> options, ContinuationScope scope) {
        // if (scope != null) {
        //     // verify the given scope exists in this continuation
        //     Continuation c;
        //     for (c = innermost(); c != null; c = c.parent) {
        //         if (c.scope == scope)
        //             break;
        //     }
        //     if (c.scope != scope)
        //         scope = this.scope; // throw new IllegalArgumentException("Continuation " + this + " not in scope " + scope); -- don't throw exception to have the same behavior as no continuation
        // } else {
        //     scope = this.scope;
        // }
        return StackWalker.newInstance(options, null, scope, innermost());
    }

    /**
     * TBD
     * @return TBD
     * @throws IllegalStateException if the continuation is mounted
     */
    public StackTraceElement[] getStackTrace() {
        return stackWalker().walk(s -> s.map(StackWalker.StackFrame::toStackTraceElement).toArray(StackTraceElement[]::new));
    }

    /// Support for StackWalker
    static <R> R wrapWalk(Continuation inner, ContinuationScope scope, Supplier<R> walk) {
        try {
            for (Continuation c = inner; c != null && c.scope != scope; c = c.parent)
                c.mount();

            // if (!inner.isStarted())
            //     throw new IllegalStateException("Continuation not started");
                
            return walk.get();
        } finally {
            for (Continuation c = inner; c != null && c.scope != scope; c = c.parent)
                c.unmount();
        }
    }

    private Continuation innermost() {
        Continuation c = this;
        while (c.child != null)
            c = c.child;
        return c;
    }

    private void mount() {
        if (!compareAndSetMounted(false, true))
            throw new IllegalStateException("Mounted!!!!");
    }

    private void unmount() {
        setMounted(false);
    }
    
    /**
     * TBD
     */
    // @DontInline
    public final void run() {
        while (true) {
            if (TRACE) {
                System.out.println();
                System.out.println("++++++++++++++++++++++++++++++");
            }

            mount();

            if (done)
                throw new IllegalStateException("Continuation terminated");

            Thread t = currentCarrierThread();
            if (parent != null) {
                if (parent != t.getContinuation())
                    throw new IllegalStateException();
            } else
                this.parent = t.getContinuation();
            t.setContinuation(this);

            // if (TRACE) walkFrames();

            int origRefSP = refSP;

            int origSP = sp, origMaxSize = maxSize; long origFP = fp, origPC = pc; // perftest only (used only if reset is true)

            try {
                enter();
            } finally {
                try {
                if (TRACE) System.out.println("run (after) sp: " + sp + " refSP: " + refSP + " maxSize: " + maxSize);

                currentCarrierThread().setContinuation(this.parent);
                if (parent != null)
                    parent.child = null;

                if (reset) { maxSize = origMaxSize; sp = origSP; fp = origFP; pc = origPC; refSP = origRefSP; } // perftest only
                postYieldCleanup(origRefSP);

                this.entryPC = 0; // cannot be done in native code, as a safpoint on the transition back to Java may want to walk the stack (with the still-mounted continuation)

                unmount();
                } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
                assert !hasLeak() : "hasLeak1 " + "refSP: " + refSP + " refStack: " + Arrays.toString(refStack);
            }
            // we're now in the parent continuation

            assert yieldInfo == null || yieldInfo instanceof ContinuationScope;
            if (yieldInfo == null || yieldInfo == scope) {
                this.parent = null;
                this.yieldInfo = null;
                return;
            } else {
                parent.child = this;
                parent.yield0((ContinuationScope)yieldInfo, this);
                parent.child = null;
            }
        }
    }

    /**
     * TBD
     */
    @HotSpotIntrinsicCandidate
    @DontInline
    private void enter() {
        // This method runs in the "entry frame".
        // A yield jumps to this method's caller as if returning from this method.
        try {
            if (!isStarted()) { // is this the first run? (at this point we know !done)
                if (TRACE)
                    System.out.println("ENTERING " + id());
                this.entrySP = getSP();
                enter0(); // make this an invokevirtual rather than invokeinterface. Otherwise it freaks out the interpreter (currently solved by patching in native)
            } else
                doContinue(); // intrinsic. Jumps into yield, as a return from doYield
        } finally {
            done = true;
            assert reset || fence() && isStackEmpty() : "sp: " + sp + " stack.length: " + (stack != null ? stack.length : "null");
            // assert doneX;
            // System.out.println("-- done!  " + id());
            if (TRACE)
                System.out.println(">>>>>>>> DONE <<<<<<<<<<<<< " + id());
        }
    }

    @DontInline
    private void enter0() {
        target.run();
    }

    private boolean isStarted() {
        return stack != null && sp < stack.length;
    }

    /**
     * TBD
     * 
     * @param scope The {@link ContinuationScope} to yield
     * @return {@code true} for success; {@code false} for failure
     * @throws IllegalStateException if not currently in the given {@code scope},
     */
    // @DontInline
    public static boolean yield(ContinuationScope scope) {
        Continuation cont = currentCarrierThread().getContinuation();
        Continuation c;
        int scopes = 0;
        for (c = cont; c != null && c.scope != scope; c = c.parent) {
            scopes++;
        }
        if (c == null)
            throw new IllegalStateException("Not in scope " + scope);

        return cont.yield0(scope, null);
    }

    private boolean yield0(ContinuationScope scope, Continuation child) {
        if (TRACE) System.out.println(this + " yielding on scope " + scope + ". child: " + child);
        if (scope != this.scope)
            this.yieldInfo = scope;
        int res = doYield(0);
        unsafe.storeFence(); // needed to prevent certain transformations by the compiler
        if (TRACE) System.out.println(this + " awake on scope " + scope + " child: " + child + " res: " + res);

        try {
        assert scope != this.scope || yieldInfo == null : "scope: " + scope + " this.scope: " + this.scope + " yieldInfo: " + yieldInfo + " res: " + res;
        assert yieldInfo == null || scope == this.scope || yieldInfo instanceof Integer : "scope: " + scope + " this.scope: " + this.scope + " yieldInfo: " + yieldInfo + " res: " + res;
        if (child != null) { // TODO: ugly
            if (TRACE) System.out.println(this + " child != null: " + child);
            if (res != 0) {
                if (TRACE) System.out.println(this + " child.yieldInfo = " + res);
                child.yieldInfo = res;
            } else if (yieldInfo != null) {
                assert yieldInfo instanceof Integer;
                child.yieldInfo = yieldInfo;
                if (TRACE) System.out.println(this + " child.yieldInfo = " + yieldInfo);
            } else {
                if (TRACE) System.out.println(this + " child.yieldInfo = " + res);
                child.yieldInfo = res;
            }
            this.yieldInfo = null;
        } else {
            if (TRACE) System.out.println(this + " child == null");
            if (res == 0 && yieldInfo != null) {
                if (TRACE) System.out.println(this + " res = yieldInfo: " + yieldInfo);
                res = (Integer)yieldInfo;
            }
            if (TRACE) System.out.println(this + " res: " + res);
            this.yieldInfo = null;
            assert reset || !hasLeak() : "hasLeak2 " + "refSP: " + refSP + " refStack: " + Arrays.toString(refStack);
            if (res == 0)
                onContinue();
            else
                onPinned0(res);
        }
        assert yieldInfo == null;
        assert reset || !hasLeak() : "hasLeak3 " + "refSP: " + refSP + " refStack: " + Arrays.toString(refStack);
        return res == 0;
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        }
    }

    private void postYieldCleanup(int origRefSP) {
        if (done) {
            // TODO: The following are disabled just for some testing
            // this.stack = null;
            // this.sp = -1;
            // this.refStack = null;
            // this.refSP = -1;
        } else {
            if (TRACE && origRefSP < refSP)
                System.out.println("Nulling refs " + origRefSP + " (inclusive) - " + refSP + " (exclusive)");
            for (int i = origRefSP; i < refSP; i++)
                refStack[i] = null;

            maybeShrink();
        }

        // if (TRACE) walkFrames();
    }

    private void onPinned0(int reason) {
        if (TRACE) System.out.println("PINNED " + this + " reason: " + reason);
        onPinned(pinnedReason(reason));
    }

    /**
     * TBD
     * @param reason TBD
     */
    protected void onPinned(Pinned reason) {
        if (DEBUG)
            System.out.println("PINNED! " + reason);
        throw new IllegalStateException("Pinned: " + reason);
    }

    /**
     * TBD
     */
    protected void onContinue() {
        if (TRACE)
            System.out.println("On continue");
    }

    /**
     * TBD
     * @return TBD
     */
    public boolean isDone() {
        return done;
    }

    /**
     * TBD
     * @return TBD
     */
    public boolean isPreempted() {
        return isFlag(FLAG_SAFEPOINT_YIELD);
    }

    private boolean isFlag(byte flag) {
        return (flags & flag) != 0;
    }

    /**
     * Pins the current continuation (enters a critical section).
     * This increments an internal semaphore that, when greater than 0, pins the continuation.
     */
    public static void pin() {
        Continuation cont = currentCarrierThread().getContinuation();
        if (cont != null) {
            assert cont.cs >= 0;
            if (cont.cs == Short.MAX_VALUE)
                throw new IllegalStateException("Too many pins");
            cont.cs++;
        }
    }

    /**
     * Unpins the current continuation (exits a critical section).
     * This decrements an internal semaphore that, when equal 0, unpins the current continuation
     * if pinne with {@link #pin()}.
     */
    public static void unpin() {
        Continuation cont = currentCarrierThread().getContinuation();
        if (cont != null) {
            assert cont.cs >= 0;
            if (cont.cs == 0)
                throw new IllegalStateException("Not pinned");
            cont.cs--;
        }
    }

    /**
     * Tests whether the given scope is pinned. 
     * This method is slow.
     * 
     * @param scope the continuation scope
     * @return {@code} true if we're in the give scope and are pinned; {@code false otherwise}
     */
    public static boolean isPinned(ContinuationScope scope) {
        int res = isPinned0(scope);
        return res != 0;
    }

    static private native int isPinned0(ContinuationScope scope);

    private void clean() {
        if (!isStackEmpty())
            clean0();
    }

    private boolean fence() {
        unsafe.storeFence(); // needed to prevent certain transformations by the compiler
        return true;
    }

    // /**
    //  * TBD
    //  */
    // public void doneX() {
    //     // System.out.println("DONEX");
    //     this.doneX = true;
    // }

    private long readLong(int[] array, int index) {
        return (long)array[index] << 32 + array[index+1];
    }

    private void getStacks(int size, int oops, int frames) {
        try {
            boolean allocated = false;
            allocated |= getStack(size);
            allocated |= getRefStack(oops);
            assert allocated : "getStacks called for no good reason";
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        }
    }

    // size is the size in bytes needed for newly frozen frames
    private boolean getStack(int size) {
        size = size >> 2;
        if (DEBUG)
            System.out.println("-- getStack size: " + size + " cur: " + (stack != null ? stack.length : 0) + " sp: " + sp);

        if (this.stack == null) {
            this.stack = new int[size];
            this.sp = stack.length;
            if (DEBUG)
                System.out.println("-- getStack: allocated: " + stack.length + " sp: " + sp );
        } else {
            int oldLength = stack.length;
            int offset = sp >= 0 ? sp : oldLength;
            int minLength = (oldLength - offset) + size;
            if (minLength > oldLength) {
                int newLength = newCapacity(oldLength, minLength);
                int[] newStack = new int[newLength];
                int n = oldLength - offset;
                if (n > 0)
                    System.arraycopy(stack, offset, newStack, newLength - n, n);
                this.stack = newStack;
                // we need to preserve the same offset from the array's _end_
                this.sp = fixDecreasingIndexAfterResize(sp, oldLength, newLength);
                if (isFlag(FLAG_LAST_FRAME_INTERPRETED)) {
                    if (DEBUG) System.out.println("-- getStack CHANGING FP");
                    this.fp = fixDecreasingIndexAfterResize((int) fp, oldLength, newLength);
                }
                if (DEBUG)
                    System.out.println("-- getStack: allocated: " + newLength + " old: " + oldLength + " sp: " + sp + " fp: " + fp);
            } else
                return false;
        }
        if (TRACE) {
            // walkFrames();
            System.out.println("--- end of getStack sp: " + sp);
        }
        return true;
    }

    private boolean getRefStack(int size) {
        if (DEBUG)
            System.out.println("-- getRefStack: " + size);
        if (refStack == null) {
            this.refStack = new Object[size]; // TODO: nearest power of 2
            this.refSP = refStack.length;
            if (DEBUG)
                System.out.println("-- getRefStack: allocated: " + refStack.length + " refSP: " + refSP);
        } else if (refSP < size) {
            int oldLength = refStack.length;
            int newLength = newCapacity(oldLength, (oldLength - refSP) + size);
            Object[] newRefStack = new Object[newLength];
            int n = oldLength - refSP;
            System.arraycopy(refStack, refSP, newRefStack, newLength - n, n);
            this.refStack = newRefStack;
            this.refSP = fixDecreasingIndexAfterResize(refSP, oldLength, newLength);
            if (DEBUG)
                System.out.println("-- getRefStack: allocated: " + newLength + " old: " + oldLength + " refSP: " + refSP);
        } else
            return false;
        if (TRACE) {
            // walkFrames();
            System.out.println("--- end of getRefStack: " + refStack.length + " refSP: " + refSP);
        }
        return true;
    }

    void maybeShrink() {
        if (stack == null || refStack == null)
            return;

        int stackSize = stack.length - sp;
        int refStackSize = refStack.length - refSP;
        assert sp >= 0;
        assert stackSize >= 0;

        if (stackSize > stackWatermark || refStackSize > refStackWatermark) {
            this.stackWatermark    = Math.max(stackWatermark,    stackSize);
            this.refStackWatermark = Math.max(refStackWatermark, refStackSize);
            this.sizeCounter = 0;
            return;
        }

        sizeCounter++;

        if (sizeCounter >= WATERMARK_THRESHOLD) {
            if (stackWatermark < stack.length) resizeStack(stackWatermark);
            this.stackWatermark = 0;

            if (refStackWatermark < refStack.length) resizeRefStack(refStackWatermark);
            this.refStackWatermark = 0;
        }
    }

    void resizeStack(int newLength) {
        if (DEBUG)
            System.out.println("-- resizeStack0 length: " + stack.length + " sp: " + sp + " fp: " + fp + " newLength: " + newLength);
        int oldLength = stack.length;
        int offset = sp;
        int n = oldLength - offset;
        assert newLength >= n;
        int[] newStack = new int[newLength];
        System.arraycopy(stack, offset, newStack, newLength - n, n);
        this.stack = newStack;

        this.sp = fixDecreasingIndexAfterResize(sp, oldLength, newLength);
        if (isFlag(FLAG_LAST_FRAME_INTERPRETED)) {
            if (DEBUG) System.out.println("-- resizeStack CHANGING FP");
            this.fp = fixDecreasingIndexAfterResize((int) fp, oldLength, newLength);
        }
        if (DEBUG)
            System.out.println("-- resizeStack1 length: " + stack.length + " sp: " + sp + " fp: " + fp);
    }

    void resizeRefStack(int newLength) {
        if (DEBUG)
            System.out.println("-- resizeRefStack0 length: " + refStack.length + " refSP: " + refSP + " newLength: " + newLength);
        int oldLength = refStack.length;
        int n = oldLength - refSP;
        assert newLength >= n;
        Object[] newRefStack = new Object[newLength];
        System.arraycopy(refStack, refSP, newRefStack, newLength - n, n);
        this.refStack = newRefStack;

        this.refSP = fixDecreasingIndexAfterResize(refSP, oldLength, newLength);
        if (DEBUG)
            System.out.println("-- resizeRefStack1 length: " + refStack.length + " refSP: " + refSP);
    }

    private int fixDecreasingIndexAfterResize(int index, int oldLength, int newLength) {
        return newLength - (oldLength - index);
    }

    private int newCapacity(int oldCapacity, int minCapacity) {
        // overflow-conscious code
        int newCapacity = oldCapacity + (oldCapacity >> 1);
        if (newCapacity - minCapacity <= 0) {
            if (minCapacity < 0) // overflow
                throw new OutOfMemoryError();
            return minCapacity;
        }
        return newCapacity;
    }

    /**
     * temporary testing
     */
    public void something_something_1() {
        this.sp = stack.length;
        this.refSP = refStack.length;
        this.done = false;

        this.fp = 0;
        this.pc = 0;
        this.entrySP = 0;
        this.entryFP = 0;
        this.entryPC = 0;

        this.numFrames = 0;
        this.numInterpretedFrames = 0;
    
        this.sizeCounter = 0;
        this.stackWatermark = 0;
        this.refStackWatermark = 0;
    }

    /**
     * temporary testing
     */
    public void something_something_2() {
        reset = true;
    }

    /**
     * temporary testing
     */
    public void something_something_3() {
        this.done = false;
    }

    // private void pushNmethod(long nmethod) {
    //     if (nmethods == null) {
    //         nmethods = new long[8];
    //     } else {
    //         if (numNmethods == nmethods.length) {
    //             long[] newNmethods = new long[nmethods.length * 2];
    //             System.arraycopy(nmethods, 0, newNmethods, 0, numNmethods);
    //             this.nmethods = newNmethods;
    //         }
    //     }
    //     nmethods[numNmethods++] = nmethod;
    // }

    // private void popNmethod() {
    //     numNmethods--;
    // }

    private static Map<Long, Integer> liveNmethods = new ConcurrentHashMap<>();

    private void processNmethods(int before, int after) {

    }


    @HotSpotIntrinsicCandidate
    private static long getSP() { throw new Error("Intrinsic not installed"); };

    @HotSpotIntrinsicCandidate
    private static long getFP() { throw new Error("Intrinsic not installed"); };

    @HotSpotIntrinsicCandidate
    private static long getPC() { throw new Error("Intrinsic not installed"); };

    @HotSpotIntrinsicCandidate
    private void doContinue() { throw new Error("Intrinsic not installed"); };

    @HotSpotIntrinsicCandidate
    private static int doYield(int scopes) { throw new Error("Intrinsic not installed"); };

    @HotSpotIntrinsicCandidate
    private static void jump(long sp, long fp, long pc) { throw new Error("Intrinsic not installed"); };

    /**
     * TBD
     * @return TBD
     */
    public int getNumFrames() {
        return numFrames;
    }

    /**
     * TBD
     * @return TBD
     */
    public int getNumInterpretedFrames() {
        return numInterpretedFrames;
    }

    /**
     * TBD
     * @return TBD
     */
    public int getStackUsageInBytes() {
        return (stack != null ? stack.length - sp + 1 : 0) * 4;
    }

    /**
     * TBD
     * @return TBD
     */
    public int getNumRefs() {
        return (refStack != null ? refStack.length - refSP : 0);
    }

    /**
     * TBD
     * @return value
     */
    @HotSpotIntrinsicCandidate
    public static int runLevel() { return 0; }

    private boolean compareAndSetMounted(boolean expectedValue, boolean newValue) {
       boolean res = MOUNTED.compareAndSet(this, expectedValue, newValue);
    //    System.out.println("-- compareAndSetMounted:  ex: " + expectedValue + " -> " + newValue + " " + res + " " + id());
       return res;
     }

    private void setMounted(boolean newValue) {
        // System.out.println("-- setMounted:  " + newValue + " " + id());
        mounted = newValue;
        // MOUNTED.setVolatile(this, newValue);
    }

    private String id() {
        return Integer.toHexString(System.identityHashCode(this)) + " [" + currentCarrierThread().getId() + "]";
    }

    private native void clean0();

    /**
     * TBD
     * Subclasses may throw an {@link UnsupportedOperationException}, but this does not prevent
     * the continuation from being preempted on a parent scope.
     * 
     * @param thread TBD
     * @return TBD
     * @throws UnsupportedOperationException if this continuation does not support preemption
     */
    public PreemptStatus tryPreempt(Thread thread) {
        PreemptStatus res = preemptStatus(tryForceYield0(thread));
        if (res == PreemptStatus.PERM_FAIL_UNSUPPORTED) {
            throw new UnsupportedOperationException("Thread-local handshakes disabled");
        }
        return res;
    }

    private native int tryForceYield0(Thread thread);

    // native methods
    private static native void registerNatives();

    private boolean hasLeak() {
        assert refStack != null || refSP <= 0 : "refSP: " + refSP;
        for (int i = 0; i < refSP; i++) {
            if (refStack[i] != null)
                return true;
        }
        return false;
    }

    private boolean isStackEmpty() {
        return (stack == null) || (sp < 0) || (sp >= stack.length);
    }

    private void walkFrames() {
        System.out.println("--------------");
        System.out.println("walkFrames:");
        if (stack == null) {
            System.out.println("Empty stack.");
            return;
        }
        int sp = this.sp;
        System.out.println("stack.length = " + stack.length + " sp: " + sp);
        System.out.println("++++++++++++");
        if (refStack != null) {
            System.out.println("refStack.length : " + refStack.length);
            for (int i = refStack.length - 1; i >= refSP; i--) {
                Object obj = refStack[i];
                System.out.println(i + ": " + (obj == this ? "this" : obj));
            }
        }
        System.out.println("##############");
    }

    private void dump() {
        System.out.println("Continuation@" + Long.toHexString(System.identityHashCode(this)));
        System.out.println("\tparent: " + parent);
        System.out.println("\tstack.length: " + stack.length);
        for (int i = 1; i <= 10; i++) {
            int j = stack.length - i;
            System.out.println("\tarray[ " + j + "] = " + stack[j]);
        }
    }

    private static boolean isEmptyOrTrue(String property) {
        final String value = System.getProperty(property);
        if (value == null)
            return false;
        return value.isEmpty() || Boolean.parseBoolean(value);
    }
}
