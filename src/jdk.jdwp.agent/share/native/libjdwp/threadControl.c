/*
 * Copyright (c) 1998, 2019, Oracle and/or its affiliates. All rights reserved.
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

#include "util.h"
#include "eventHandler.h"
#include "threadControl.h"
#include "commonRef.h"
#include "eventHelper.h"
#include "stepControl.h"
#include "invoker.h"
#include "bag.h"

#define HANDLING_EVENT(node) ((node)->current_ei != 0)

/*
 * Collection of info for properly handling co-located events.
 * If the ei field is non-zero, then one of the possible
 * co-located events has been posted and the other fields describe
 * the event's location.
 *
 * See comment above deferEventReport() for an explanation of co-located events.
 */
typedef struct CoLocatedEventInfo_ {
    EventIndex ei;
    jclass    clazz;
    jmethodID method;
    jlocation location;
} CoLocatedEventInfo;

/**
 * The main data structure in threadControl is the ThreadNode.
 * This is a per-thread structure that is allocated on the
 * first event that occurs in a thread. It is freed after the
 * thread's thread end event has completed processing. The
 * structure contains state information on its thread including
 * suspend counts. It also acts as a repository for other
 * per-thread state such as the current method invocation or
 * current step.
 *
 * suspendCount is the number of outstanding suspends
 * from the debugger. suspends from the app itself are
 * not included in this count.
 */
typedef struct ThreadNode {
    jthread thread;
    unsigned int toBeResumed : 1;      /* true if this thread was successfully suspended. */
    unsigned int pendingInterrupt : 1; /* true if thread is interrupted while handling an event. */
    unsigned int isDebugThread : 1;    /* true if this is one of our debug agent threads. */
    unsigned int suspendOnStart : 1;   /* true for new threads if we are currently in a VM.suspend(). */
    unsigned int isStarted : 1;        /* THREAD_START or FIBER_SCHEDULED event received. */
    unsigned int is_fiber : 1;
    unsigned int popFrameEvent : 1;
    unsigned int popFrameProceed : 1;
    unsigned int popFrameThread : 1;
    EventIndex current_ei; /* Used to determine if we are currently handling an event on this thread. */
    jobject pendingStop;   /* Object we are throwing to stop the thread (ThreadReferenceImpl.stop). */
    jint suspendCount;
    jint resumeFrameDepth; /* !=0 => This thread is in a call to Thread.resume() */
    jvmtiEventMode instructionStepMode;
    StepRequest currentStep;
    InvokeRequest currentInvoke;
    struct bag *eventBag;       /* Accumulation of JDWP events to be sent as a reply. */
    CoLocatedEventInfo cleInfo; /* See comment above deferEventReport() for an explanation. */
    jthread fiberHelperThread;  /* Temporary thread created for mounting fiber on to get stack trace
                                 * or to support suspending an unmounted fiber. */
    jboolean isTrackedSuspendedFiber; /* true if we are tracking the suspendCount of this fiber. */
    struct ThreadNode *nextTrackedSuspendedFiber;
    struct ThreadNode *prevTrackedSuspendedFiber;
    struct ThreadNode *next;
    struct ThreadNode *prev;
    jlong frameGeneration;    /* used to generate a unique frameID. Incremented whenever existing frameID
                                 needs to be invalidated, such as when the thread is resumed. */
    struct ThreadList *list;  /* Tells us what list this thread is in. */
#ifdef DEBUG_THREADNAME
    char name[256];
#endif
} ThreadNode;

static jint suspendAllCount;

struct ThreadNode *trackedSuspendedFibers = NULL;

typedef struct ThreadList {
    ThreadNode *first;
} ThreadList;

/*
 * popFrameEventLock is used to notify that the event has been received
 */
static jrawMonitorID popFrameEventLock = NULL;

/*
 * popFrameProceedLock is used to assure that the event thread is
 * re-suspended immediately after the event is acknowledged.
 */
static jrawMonitorID popFrameProceedLock = NULL;

static jrawMonitorID threadLock;
static jlocation resumeLocation;
static HandlerNode *breakpointHandlerNode;
static HandlerNode *framePopHandlerNode;
static HandlerNode *catchHandlerNode;

static jvmtiError threadControl_removeDebugThread(jthread thread);

/*
 * Threads which have issued thread start events and not yet issued thread
 * end events are maintained in the "runningThreads" list. All other threads known
 * to this module are kept in the "otherThreads" list.
 */
static ThreadList runningThreads;
static ThreadList otherThreads;
static ThreadList runningFibers; /* Fibers we have seen. */

#define MAX_DEBUG_THREADS 10
static int debugThreadCount;
static jthread debugThreads[MAX_DEBUG_THREADS];

typedef struct DeferredEventMode {
    EventIndex ei;
    jvmtiEventMode mode;
    jthread thread;
    struct DeferredEventMode *next;
} DeferredEventMode;

typedef struct {
    DeferredEventMode *first;
    DeferredEventMode *last;
} DeferredEventModeList;

static DeferredEventModeList deferredEventModes;

static jint
getStackDepth(jthread thread)
{
    jint count = 0;
    jvmtiError error;

    error = JVMTI_FUNC_PTR(gdata->jvmti,GetFrameCount)
                        (gdata->jvmti, thread, &count);
    if (error != JVMTI_ERROR_NONE) {
        EXIT_ERROR(error, "getting frame count");
    }
    return count;
}

/* Get the state of the thread direct from JVMTI */
static jvmtiError
threadState(jthread thread, jint *pstate)
{
    *pstate = 0;
    return JVMTI_FUNC_PTR(gdata->jvmti,GetThreadState)
                        (gdata->jvmti, thread, pstate);
}

/* Set TLS on a specific jthread to the ThreadNode* */
static void
setThreadLocalStorage(jthread thread, ThreadNode *node)
{
    jvmtiError  error;

    error = JVMTI_FUNC_PTR(gdata->jvmti,SetThreadLocalStorage)
            (gdata->jvmti, thread, (void*)node);
    if ( error == JVMTI_ERROR_THREAD_NOT_ALIVE ) {
        /* Just return, thread hasn't started yet */
        return;
    } else if ( error != JVMTI_ERROR_NONE ) {
        /* The jthread object must be valid, so this must be a fatal error */
        EXIT_ERROR(error, "cannot set thread local storage");
    }
}

/* Get TLS on a specific jthread, which is the ThreadNode* */
static ThreadNode *
getThreadLocalStorage(jthread thread)
{
    jvmtiError  error;
    ThreadNode *node;

    node = NULL;
    error = JVMTI_FUNC_PTR(gdata->jvmti,GetThreadLocalStorage)
            (gdata->jvmti, thread, (void**)&node);
    if ( error == JVMTI_ERROR_THREAD_NOT_ALIVE ) {
        /* Just return NULL, thread hasn't started yet */
        return NULL;
    } else if ( error != JVMTI_ERROR_NONE ) {
        /* The jthread object must be valid, so this must be a fatal error */
        EXIT_ERROR(error, "cannot get thread local storage");
    }
    return node;
}

/* Search list for nodes that don't have TLS set and match this thread.
 *   It assumed that this logic is never dealing with terminated threads,
 *   since the ThreadEnd events always delete the ThreadNode while the
 *   jthread is still alive.  So we can only look at the ThreadNode's that
 *   have never had their TLS set, making the search much faster.
 *   But keep in mind, this kind of search should rarely be needed.
 */
static ThreadNode *
nonTlsSearch(JNIEnv *env, ThreadList *list, jthread thread)
{
    ThreadNode *node;

    for (node = list->first; node != NULL; node = node->next) {
        if (isSameObject(env, node->thread, thread)) {
            break;
        }
    }
    return node;
}

/*
 * These functions maintain the linked list of currently running threads and fibers.
 * All assume that the threadLock is held before calling.
 */


/*
 * Search for a thread on the list. If list==NULL, search all lists.
 */
static ThreadNode *
findThread(ThreadList *list, jthread thread)
{
    ThreadNode *node;
    JNIEnv *env = getEnv();

    if (list == NULL || list == &runningFibers) {
        /*
         * Search for a fiber.
         * fiber fixme: this needs to be done a lot faster. Maybe some sort of TLS for fibers is needed.
         * Otherwise we'll need something like a hashlist front end to the runningFibers list so
         * we can do quick lookups.
         */
        ThreadNode *node = nonTlsSearch(env, &runningFibers, thread);
        if (node != NULL || list == &runningFibers) {
            return node;
        }
    }    

    /* Get thread local storage for quick thread -> node access */
    node = getThreadLocalStorage(thread);

    /* In some rare cases we might get NULL, so we check the list manually for
     *   any threads that we could match.
     */
    if ( node == NULL ) {
        if ( list != NULL ) {
            node = nonTlsSearch(env, list, thread);
        } else {
            node = nonTlsSearch(env, &runningThreads, thread);
            if ( node == NULL ) {
                node = nonTlsSearch(env, &otherThreads, thread);
            }
        }
        if ( node != NULL ) {
            /* Here we make another attempt to set TLS, it's ok if this fails */
            setThreadLocalStorage(thread, (void*)node);
        }
    }

    /* If a list is supplied, only return ones in this list */
    if ( node != NULL && list != NULL && node->list != list ) {
        return NULL;
    }
    return node;
}

/* Remove a ThreadNode from a ThreadList */
static void
removeNode(ThreadList *list, ThreadNode *node)
{
    ThreadNode *prev;
    ThreadNode *next;

    prev = node->prev;
    next = node->next;
    if ( prev != NULL ) {
        prev->next = next;
    }
    if ( next != NULL ) {
        next->prev = prev;
    }
    if ( prev == NULL ) {
        list->first = next;
    }
    node->next = NULL;
    node->prev = NULL;
    node->list = NULL;
}

/* Add a ThreadNode to a ThreadList */
static void
addNode(ThreadList *list, ThreadNode *node)
{
    node->next = NULL;
    node->prev = NULL;
    node->list = NULL;
    if ( list->first == NULL ) {
        list->first = node;
    } else {
        list->first->prev = node;
        node->next = list->first;
        list->first = node;
    }
    node->list = list;
}

static ThreadNode *
insertThread(JNIEnv *env, ThreadList *list, jthread thread)
{
    ThreadNode *node;
    struct bag *eventBag;
    jboolean is_fiber = (list == &runningFibers);

    node = findThread(list, thread);
    if (node == NULL) {
        node = jvmtiAllocate(sizeof(*node));
        if (node == NULL) {
            EXIT_ERROR(AGENT_ERROR_OUT_OF_MEMORY,"thread table entry");
            return NULL;
        }
        (void)memset(node, 0, sizeof(*node));
        eventBag = eventHelper_createEventBag();
        if (eventBag == NULL) {
            jvmtiDeallocate(node);
            EXIT_ERROR(AGENT_ERROR_OUT_OF_MEMORY,"thread table entry");
            return NULL;
        }

        /*
         * Init all flags false, all refs NULL, all counts 0
         */

        saveGlobalRef(env, thread, &(node->thread));
        if (node->thread == NULL) {
            jvmtiDeallocate(node);
            bagDestroyBag(eventBag);
            EXIT_ERROR(AGENT_ERROR_OUT_OF_MEMORY,"thread table entry");
            return NULL;
        }
        /*
         * Remember if it is a debug thread
         */
        if (!is_fiber && threadControl_isDebugThread(node->thread)) {
            node->isDebugThread = JNI_TRUE;
        } else if (suspendAllCount > 0){
            /*
             * If there is a pending suspendAll, all new threads should
             * be initialized as if they were suspended by the suspendAll,
             * and the thread will need to be suspended when it starts.
             */
            node->suspendCount = suspendAllCount;
            node->suspendOnStart = JNI_TRUE;
        }
        node->current_ei = 0;
        node->is_fiber = is_fiber;
        node->instructionStepMode = JVMTI_DISABLE;
        node->eventBag = eventBag;
        addNode(list, node);

        /* Set thread local storage for quick thread -> node access.
         *   Some threads may not be in a state that allows setting of TLS,
         *   which is ok, see findThread, it deals with threads without TLS set.
         */
        if (!is_fiber) {
            setThreadLocalStorage(node->thread, (void*)node);
        }

        if (is_fiber) {
            node->isStarted = JNI_TRUE; /* Fibers are considered started by default. */
        }
    }

    return node;
}

static void
clearThread(JNIEnv *env, ThreadNode *node)
{
    if (node->pendingStop != NULL) {
        tossGlobalRef(env, &(node->pendingStop));
    }
    stepControl_clearRequest(node->thread, &node->currentStep);
    if (node->isDebugThread) {
        (void)threadControl_removeDebugThread(node->thread);
    }
    /* Clear out TLS on this thread (just a cleanup action) */
    if (!node->is_fiber) {
        setThreadLocalStorage(node->thread, NULL);
    }
    tossGlobalRef(env, &(node->thread));
    bagDestroyBag(node->eventBag);
    jvmtiDeallocate(node);
}

static void
removeThread(JNIEnv *env, ThreadList *list, jthread thread)
{
    ThreadNode *node;

    node = findThread(list, thread);
    if (node != NULL) {
        removeNode(list, node);
        clearThread(env, node);
    }
}

static void
removeResumed(JNIEnv *env, ThreadList *list)
{
    ThreadNode *node;

    node = list->first;
    while (node != NULL) {
        ThreadNode *temp = node->next;
        if (node->suspendCount == 0) {
            removeThread(env, list, node->thread);
        }
        node = temp;
    }
}

static void
moveNode(ThreadList *source, ThreadList *dest, ThreadNode *node)
{
    removeNode(source, node);
    JDI_ASSERT(findThread(dest, node->thread) == NULL);
    addNode(dest, node);
}

typedef jvmtiError (*ThreadEnumerateFunction)(JNIEnv *, ThreadNode *, void *);

static jvmtiError
enumerateOverThreadList(JNIEnv *env, ThreadList *list,
                        ThreadEnumerateFunction function, void *arg)
{
    ThreadNode *node;
    jvmtiError error = JVMTI_ERROR_NONE;

    for (node = list->first; node != NULL; node = node->next) {
        error = (*function)(env, node, arg);
        if ( error != JVMTI_ERROR_NONE ) {
            break;
        }
    }
    return error;
}

static void
insertEventMode(DeferredEventModeList *list, DeferredEventMode *eventMode)
{
    if (list->last != NULL) {
        list->last->next = eventMode;
    } else {
        list->first = eventMode;
    }
    list->last = eventMode;
}

static void
removeEventMode(DeferredEventModeList *list, DeferredEventMode *eventMode, DeferredEventMode *prev)
{
    if (prev == NULL) {
        list->first = eventMode->next;
    } else {
        prev->next = eventMode->next;
    }
    if (eventMode->next == NULL) {
        list->last = prev;
    }
}

static jvmtiError
addDeferredEventMode(JNIEnv *env, jvmtiEventMode mode, EventIndex ei, jthread thread)
{
    DeferredEventMode *eventMode;

    /*LINTED*/
    eventMode = jvmtiAllocate((jint)sizeof(DeferredEventMode));
    if (eventMode == NULL) {
        return AGENT_ERROR_OUT_OF_MEMORY;
    }
    eventMode->thread = NULL;
    saveGlobalRef(env, thread, &(eventMode->thread));
    eventMode->mode = mode;
    eventMode->ei = ei;
    eventMode->next = NULL;
    insertEventMode(&deferredEventModes, eventMode);
    return JVMTI_ERROR_NONE;
}

static void
freeDeferredEventModes(JNIEnv *env)
{
    DeferredEventMode *eventMode;
    eventMode = deferredEventModes.first;
    while (eventMode != NULL) {
        DeferredEventMode *next;
        next = eventMode->next;
        tossGlobalRef(env, &(eventMode->thread));
        jvmtiDeallocate(eventMode);
        eventMode = next;
    }
    deferredEventModes.first = NULL;
    deferredEventModes.last = NULL;
}

static jvmtiError
threadSetEventNotificationMode(ThreadNode *node,
        jvmtiEventMode mode, EventIndex ei, jthread thread)
{
    jvmtiError error;

    /* record single step mode */
    if (ei == EI_SINGLE_STEP) {
        node->instructionStepMode = mode;
    }
    error = JVMTI_FUNC_PTR(gdata->jvmti,SetEventNotificationMode)
        (gdata->jvmti, mode, eventIndex2jvmti(ei), thread);
    return error;
}

static void
processDeferredEventModes(JNIEnv *env, jthread thread, ThreadNode *node)
{
    jvmtiError error;
    DeferredEventMode *eventMode;
    DeferredEventMode *prev;

    prev = NULL;
    eventMode = deferredEventModes.first;
    while (eventMode != NULL) {
        DeferredEventMode *next = eventMode->next;
        if (isSameObject(env, thread, eventMode->thread)) {
            error = threadSetEventNotificationMode(node,
                    eventMode->mode, eventMode->ei, eventMode->thread);
            if (error != JVMTI_ERROR_NONE) {
                EXIT_ERROR(error, "cannot process deferred thread event notifications at thread start");
            }
            removeEventMode(&deferredEventModes, eventMode, prev);
            tossGlobalRef(env, &(eventMode->thread));
            jvmtiDeallocate(eventMode);
        } else {
            prev = eventMode;
        }
        eventMode = next;
    }
}

static void
getLocks(void)
{
    /*
     * Anything which might be locked as part of the handling of
     * a JVMTI event (which means: might be locked by an application
     * thread) needs to be grabbed here. This allows thread control
     * code to safely suspend and resume the application threads
     * while ensuring they don't hold a critical lock.
     */

    eventHandler_lock();
    invoker_lock();
    eventHelper_lock();
    stepControl_lock();
    commonRef_lock();
    debugMonitorEnter(threadLock);

}

static void
releaseLocks(void)
{
    debugMonitorExit(threadLock);
    commonRef_unlock();
    stepControl_unlock();
    eventHelper_unlock();
    invoker_unlock();
    eventHandler_unlock();
}

void
threadControl_initialize(void)
{
    jlocation unused;
    jvmtiError error;

    suspendAllCount = 0;
    runningThreads.first = NULL;
    otherThreads.first = NULL;
    runningFibers.first = NULL;
    debugThreadCount = 0;
    threadLock = debugMonitorCreate("JDWP Thread Lock");
    if (gdata->threadClass==NULL) {
        EXIT_ERROR(AGENT_ERROR_NULL_POINTER, "no java.lang.thread class");
    }
    if (gdata->threadResume==0) {
        EXIT_ERROR(AGENT_ERROR_NULL_POINTER, "cannot resume thread");
    }
    /* Get the java.lang.Thread.resume() method beginning location */
    error = methodLocation(gdata->threadResume, &resumeLocation, &unused);
    if (error != JVMTI_ERROR_NONE) {
        EXIT_ERROR(error, "getting method location");
    }
}

static jthread
getResumee(jthread resumingThread)
{
    jthread resumee = NULL;
    jvmtiError error;
    jobject object;
    FrameNumber fnum = 0;

    error = JVMTI_FUNC_PTR(gdata->jvmti,GetLocalObject)
                    (gdata->jvmti, resumingThread, fnum, 0, &object);
    if (error == JVMTI_ERROR_NONE) {
        resumee = object;
    }
    return resumee;
}


static jboolean
pendingAppResume(jboolean includeSuspended)
{
    ThreadList *list;
    ThreadNode *node;

    list = &runningThreads;
    node = list->first;
    while (node != NULL) {
        if (node->resumeFrameDepth > 0) {
            if (includeSuspended) {
                return JNI_TRUE;
            } else {
                jvmtiError error;
                jint       state;

                error = threadState(node->thread, &state);
                if (error != JVMTI_ERROR_NONE) {
                    EXIT_ERROR(error, "getting thread state");
                }
                if (!(state & JVMTI_THREAD_STATE_SUSPENDED)) {
                    return JNI_TRUE;
                }
            }
        }
        node = node->next;
    }
    return JNI_FALSE;
}

static void
notifyAppResumeComplete(void)
{
    debugMonitorNotifyAll(threadLock);
    if (!pendingAppResume(JNI_TRUE)) {
        if (framePopHandlerNode != NULL) {
            (void)eventHandler_free(framePopHandlerNode);
            framePopHandlerNode = NULL;
        }
        if (catchHandlerNode != NULL) {
            (void)eventHandler_free(catchHandlerNode);
            catchHandlerNode = NULL;
        }
    }
}

static void
handleAppResumeCompletion(JNIEnv *env, EventInfo *evinfo,
                          HandlerNode *handlerNode,
                          struct bag *eventBag)
{
    ThreadNode *node;
    jthread     thread;

    /* fiber fixme: it's unclear how this is used and if anything special needs to be done for fibers. */
    JDI_ASSERT(!evinfo->matchesFiber);

    thread = evinfo->thread;

    debugMonitorEnter(threadLock);

    node = findThread(&runningThreads, thread);
    if (node != NULL) {
        if (node->resumeFrameDepth > 0) {
            jint compareDepth = getStackDepth(thread);
            if (evinfo->ei == EI_FRAME_POP) {
                compareDepth--;
            }
            if (compareDepth < node->resumeFrameDepth) {
                node->resumeFrameDepth = 0;
                notifyAppResumeComplete();
            }
        }
    }

    debugMonitorExit(threadLock);
}

static void
blockOnDebuggerSuspend(jthread thread)
{
    ThreadNode *node;

    node = findThread(NULL, thread);
    if (node != NULL) {
        while (node && node->suspendCount > 0) {
            debugMonitorWait(threadLock);
            node = findThread(NULL, thread);
        }
    }
}

static void
trackAppResume(jthread thread)
{
    jvmtiError  error;
    FrameNumber fnum;
    ThreadNode *node;

    fnum = 0;
    node = findThread(&runningThreads, thread);
    if (node != NULL) {
        JDI_ASSERT(node->resumeFrameDepth == 0);
        error = JVMTI_FUNC_PTR(gdata->jvmti,NotifyFramePop)
                        (gdata->jvmti, thread, fnum);
        if (error == JVMTI_ERROR_NONE) {
            jint frameDepth = getStackDepth(thread);
            if ((frameDepth > 0) && (framePopHandlerNode == NULL)) {
                framePopHandlerNode = eventHandler_createInternalThreadOnly(
                                           EI_FRAME_POP,
                                           handleAppResumeCompletion,
                                           thread);
                catchHandlerNode = eventHandler_createInternalThreadOnly(
                                           EI_EXCEPTION_CATCH,
                                           handleAppResumeCompletion,
                                           thread);
                if ((framePopHandlerNode == NULL) ||
                    (catchHandlerNode == NULL)) {
                    (void)eventHandler_free(framePopHandlerNode);
                    framePopHandlerNode = NULL;
                    (void)eventHandler_free(catchHandlerNode);
                    catchHandlerNode = NULL;
                }
            }
            if ((framePopHandlerNode != NULL) &&
                (catchHandlerNode != NULL) &&
                (frameDepth > 0)) {
                node->resumeFrameDepth = frameDepth;
            }
        }
    }
}

static void
handleAppResumeBreakpoint(JNIEnv *env, EventInfo *evinfo,
                          HandlerNode *handlerNode,
                          struct bag *eventBag)
{
    /* fiber fixme: it's unclear how this is used and if anything special needs to be done for fibers. */
    JDI_ASSERT(!evinfo->matchesFiber);

    jthread resumer = evinfo->thread;
    jthread resumee = getResumee(resumer);

    debugMonitorEnter(threadLock);
    if (resumee != NULL) {
        /*
         * Hold up any attempt to resume as long as the debugger
         * has suspended the resumee.
         */
        blockOnDebuggerSuspend(resumee);
    }

    if (resumer != NULL) {
        /*
         * Track the resuming thread by marking it as being within
         * a resume and by setting up for notification on
         * a frame pop or exception. We won't allow the debugger
         * to suspend threads while any thread is within a
         * call to resume. This (along with the block above)
         * ensures that when the debugger
         * suspends a thread it will remain suspended.
         */
        trackAppResume(resumer);
    }

    debugMonitorExit(threadLock);
}

void
threadControl_onConnect(void)
{
    breakpointHandlerNode = eventHandler_createInternalBreakpoint(
                 handleAppResumeBreakpoint, NULL,
                 gdata->threadClass, gdata->threadResume, resumeLocation);
}

void
threadControl_onDisconnect(void)
{
    if (breakpointHandlerNode != NULL) {
        (void)eventHandler_free(breakpointHandlerNode);
        breakpointHandlerNode = NULL;
    }
    if (framePopHandlerNode != NULL) {
        (void)eventHandler_free(framePopHandlerNode);
        framePopHandlerNode = NULL;
    }
    if (catchHandlerNode != NULL) {
        (void)eventHandler_free(catchHandlerNode);
        catchHandlerNode = NULL;
    }
}

void
threadControl_onHook(void)
{
    /*
     * As soon as the event hook is in place, we need to initialize
     * the thread list with already-existing threads. The threadLock
     * has been held since initialize, so we don't need to worry about
     * insertions or deletions from the event handlers while we do this
     */
    JNIEnv *env;

    env = getEnv();

    /*
     * Prevent any event processing until OnHook has been called
     */
    debugMonitorEnter(threadLock);

    WITH_LOCAL_REFS(env, 1) {

        jint threadCount;
        jthread *threads;

        threads = allThreads(&threadCount);
        if (threads == NULL) {
            EXIT_ERROR(AGENT_ERROR_OUT_OF_MEMORY,"thread table");
        } else {

            int i;

            for (i = 0; i < threadCount; i++) {
                ThreadNode *node;
                jthread thread = threads[i];
                node = insertThread(env, &runningThreads, thread);

                /*
                 * This is a tiny bit risky. We have to assume that the
                 * pre-existing threads have been started because we
                 * can't rely on a thread start event for them. The chances
                 * of a problem related to this are pretty slim though, and
                 * there's really no choice because without setting this flag
                 * there is no way to enable stepping and other events on
                 * the threads that already exist (e.g. the finalizer thread).
                 */
                node->isStarted = JNI_TRUE;
            }
        }

    } END_WITH_LOCAL_REFS(env)

    debugMonitorExit(threadLock);
}


static jvmtiError
resumeFiberHelperThread(JNIEnv *env, ThreadNode *node, void *ignored)
{
    jvmtiError error = JVMTI_ERROR_NONE;
    if (node->fiberHelperThread != NULL) {
        error = JVMTI_FUNC_PTR(gdata->jvmti,ResumeThread)
            (gdata->jvmti, node->fiberHelperThread);
        tossGlobalRef(env, &node->fiberHelperThread);
    }
    return error;
}

static void
startTrackingSuspendedFiber(ThreadNode *fiberNode)
{
    /* Add fiberNode to the start of the list. */
    fiberNode->prevTrackedSuspendedFiber = NULL;
    fiberNode->nextTrackedSuspendedFiber = trackedSuspendedFibers;
    trackedSuspendedFibers = fiberNode;

    /* Since we didn't previously increment suspendCount for each suspendAll(), do that now. */
    fiberNode->suspendCount = suspendAllCount;

    fiberNode->isTrackedSuspendedFiber = JNI_TRUE;
}


static void
stopTrackingSuspendedFiber(ThreadNode *fiberNode)
{
    /* Remove fiberNode from the list. */
    if (fiberNode->prevTrackedSuspendedFiber == NULL) {
        /* Node is at the start of the list. */
        trackedSuspendedFibers = fiberNode->nextTrackedSuspendedFiber;
    } else {
        fiberNode->prevTrackedSuspendedFiber->nextTrackedSuspendedFiber =
            fiberNode->nextTrackedSuspendedFiber;
    }
    if (fiberNode->nextTrackedSuspendedFiber != NULL) {
        fiberNode->nextTrackedSuspendedFiber->prevTrackedSuspendedFiber =
            fiberNode->prevTrackedSuspendedFiber;
    }

    /* If this fiber has a helper thread, we no longer need or want it. */
    if (fiberNode->fiberHelperThread != NULL) {
        resumeFiberHelperThread(getEnv(), fiberNode, NULL);
    }

    fiberNode->isTrackedSuspendedFiber = JNI_FALSE;
}

static jthread
getFiberHelperThread(jthread fiber)
{
    JNIEnv *env;
    ThreadNode *fiberNode;
    jthread helperThread;

    fiberNode = findThread(&runningFibers, fiber);
    if (fiberNode->fiberHelperThread != NULL) {
        return fiberNode->fiberHelperThread;
    }

    env = getEnv();

    /*
     * We need to mount the fiber on a helper thread. This is done by calling
     * Fiber.tryMountAndSuspend(), which will create a helper thread for us,
     * mount the fiber on the thread, suspend the thread, and then return the thread.
     *
     * This helper thread is disposed of by resumeFiberHelperThread() when it is 
     * determined that the helper thread is no longer need (the fiber was resumed,
     * and we are no longer tracking it).
     *
     * Disable all event handling while doing this, since we don't want to deal
     * with any incoming THREAD_START event.
     *
     * Also release the threadLock, or a deadlock will occur when the 
     * CONTINUATION_RUN event arrives on the helper thread.
     * fiber fixme: this might not be safe to do.
     */
    debugMonitorExit(threadLock);    
    gdata->ignoreEvents = JNI_TRUE;
    helperThread = JNI_FUNC_PTR(env,CallObjectMethod)
        (env, fiber, gdata->fiberTryMountAndSuspend);
    gdata->ignoreEvents = JNI_FALSE;
    debugMonitorEnter(threadLock);


    if (JNI_FUNC_PTR(env,ExceptionOccurred)(env)) {
        JNI_FUNC_PTR(env,ExceptionClear)(env);
        helperThread = NULL;
    }

    if (helperThread != NULL) {
        saveGlobalRef(env, helperThread, &(fiberNode->fiberHelperThread));
        /* Start tracking this fiber as a suspended one. */
        startTrackingSuspendedFiber(fiberNode);
    }

    return fiberNode->fiberHelperThread;
}

static jvmtiError
commonSuspendByNode(ThreadNode *node)
{
    jvmtiError error;

    LOG_MISC(("thread=%p suspended", node->thread));
    error = JVMTI_FUNC_PTR(gdata->jvmti,SuspendThread)
                (gdata->jvmti, node->thread);

    /*
     * Mark for resume only if suspend succeeded
     */
    if (error == JVMTI_ERROR_NONE) {
        node->toBeResumed = JNI_TRUE;
    }

    /*
     * If the thread was suspended by another app thread,
     * do nothing and report no error (we won't resume it later).
     */
     if (error == JVMTI_ERROR_THREAD_SUSPENDED) {
        error = JVMTI_ERROR_NONE;
     }

     return error;
}

/*
 * Deferred suspends happen when the suspend is attempted on a thread
 * that is not started. Bookkeeping (suspendCount,etc.)
 * is handled by the original request, and once the thread actually
 * starts, an actual suspend is attempted. This function does the
 * deferred suspend without changing the bookkeeping that is already
 * in place.
 */
static jint
deferredSuspendThreadByNode(ThreadNode *node)
{
    jvmtiError error;

    error = JVMTI_ERROR_NONE;
    if (node->isDebugThread) {
        /* Ignore requests for suspending debugger threads */
        return JVMTI_ERROR_NONE;
    }

    /*
     * Do the actual suspend only if a subsequent resume hasn't
     * made it irrelevant.
     */
    if (node->suspendCount > 0) {
        error = commonSuspendByNode(node);

        /*
         * Attempt to clean up from any error by decrementing the
         * suspend count. This compensates for the increment that
         * happens when suspendOnStart is set to true.
         */
        if (error != JVMTI_ERROR_NONE) {
          node->suspendCount--;
        }
    }

    node->suspendOnStart = JNI_FALSE;

    debugMonitorNotifyAll(threadLock);

    return error;
}

static jvmtiError
suspendThreadByNode(ThreadNode *node)
{
    jvmtiError error = JVMTI_ERROR_NONE;
    if (node->isDebugThread) {
        /* Ignore requests for suspending debugger threads */
        return JVMTI_ERROR_NONE;
    }

    /*
     * Just increment the suspend count if we are waiting
     * for a deferred suspend.
     */
    if (node->suspendOnStart) {
        node->suspendCount++;
        return JVMTI_ERROR_NONE;
    }

    if (node->suspendCount == 0) {
        error = commonSuspendByNode(node);

        if (error == JVMTI_ERROR_THREAD_NOT_ALIVE) {
            /*
             * This error means that the thread is either a zombie or not yet
             * started. In either case, we ignore the error. If the thread
             * is a zombie, suspend/resume are no-ops. If the thread is not
             * started, it will be suspended for real during the processing
             * of its thread start event.
             */
            node->suspendOnStart = JNI_TRUE;
            error = JVMTI_ERROR_NONE;
        }
    }

    if (error == JVMTI_ERROR_NONE) {
        node->suspendCount++;
        if (gdata->fibersSupported) {
            /*
             * If this is a carrier thread with a mounted fiber, and the fiber
             * is being tracked, bump the fiber's suspendCount also.
             */
            jthread fiber = getThreadFiber(node->thread);
            if (fiber != NULL) {
                ThreadNode *fiberNode = findThread(&runningFibers, fiber);
                if (fiberNode != NULL && fiberNode->isTrackedSuspendedFiber) {
                    /* If tracking, bump the fiber suspendCount also. */
                    fiberNode->suspendCount++;
                }
            }
        }
    }

    debugMonitorNotifyAll(threadLock);

    return error;
}

static jvmtiError
resumeThreadByNode(ThreadNode *node)
{
    jvmtiError error = JVMTI_ERROR_NONE;

    if (node->isDebugThread) {
        /* never suspended by debugger => don't ever try to resume */
        return JVMTI_ERROR_NONE;
    }
    if (node->suspendCount > 0) {
        if (gdata->fibersSupported) {
            /*
             * If this is a carrier thread with a mounted fiber, and the fiber
             * is being tracked, decrement the fiber's suspendCount also.
             */
            jthread fiber = getThreadFiber(node->thread);
            if (fiber != NULL) {
                ThreadNode *fiberNode = findThread(&runningFibers, fiber);
                if (fiberNode != NULL && fiberNode->isTrackedSuspendedFiber) {
                    /* If tracking, decrement the fiber suspendCount also. */
                    if (fiberNode->suspendCount > 0) {
                        fiberNode->suspendCount--;
                    }
                }
            }
        }
        node->suspendCount--;
        debugMonitorNotifyAll(threadLock);
        if ((node->suspendCount == 0) && node->toBeResumed &&
            !node->suspendOnStart) {
            LOG_MISC(("thread=%p resumed", node->thread));
            error = JVMTI_FUNC_PTR(gdata->jvmti,ResumeThread)
                        (gdata->jvmti, node->thread);
            node->frameGeneration++; /* Increment on each resume */
            node->toBeResumed = JNI_FALSE;
            if (error == JVMTI_ERROR_THREAD_NOT_ALIVE && !node->isStarted) {
                /*
                 * We successfully "suspended" this thread, but
                 * we never received a THREAD_START event for it.
                 * Since the thread never ran, we can ignore our
                 * failure to resume the thread.
                 */
                error = JVMTI_ERROR_NONE;
            }
        }
    }

    return error;
}

/*
 * Functions which respond to user requests to suspend/resume
 * threads.
 * Suspends and resumes add and subtract from a count respectively.
 * The thread is only suspended when the count goes from 0 to 1 and
 * resumed only when the count goes from 1 to 0.
 *
 * These functions suspend and resume application threads
 * without changing the
 * state of threads that were already suspended beforehand.
 * They must not be called from an application thread because
 * that thread may be suspended somewhere in the  middle of things.
 */
static void
preSuspend(void)
{
    getLocks();                     /* Avoid debugger deadlocks */

    /*
     * Delay any suspend while a call to java.lang.Thread.resume is in
     * progress (not including those in suspended threads). The wait is
     * timed because the threads suspended through
     * java.lang.Thread.suspend won't result in a notify even though
     * it may change the result of pendingAppResume()
     */
    while (pendingAppResume(JNI_FALSE)) {
        /*
         * This is ugly but we need to release the locks from getLocks
         * or else the notify will never happen. The locks must be
         * released and reacquired in the right order. else deadlocks
         * can happen. It is possible that, during this dance, the
         * notify will be missed, but since the wait needs to be timed
         * anyway, it won't be a disaster. Note that this code will
         * execute only on very rare occasions anyway.
         */
        releaseLocks();

        debugMonitorEnter(threadLock);
        debugMonitorTimedWait(threadLock, 1000);
        debugMonitorExit(threadLock);

        getLocks();
    }
}

static void
postSuspend(void)
{
    releaseLocks();
}

/*
 * This function must be called after preSuspend and before postSuspend.
 */
static jvmtiError
commonSuspend(JNIEnv *env, jthread thread, jboolean deferred)
{
    ThreadNode *node;

    if (isFiber(thread)) {
        jvmtiError error = JVMTI_ERROR_NONE;
        while (JNI_TRUE) {
            jthread carrier_thread = getFiberThread(thread);
            if (carrier_thread != NULL) {
                /* Fiber is mounted. Suspend the carrier thread. */
                node = findThread(&runningThreads, carrier_thread);
                error = suspendThreadByNode(node);
                if (error != JVMTI_ERROR_NONE) {
                    LOG_MISC(("commonSuspend: failed to suspend carrier thread(%p)", carrier_thread));
                    return error;
                }
                if (isSameObject(env, carrier_thread, getFiberThread(thread))) {
                    /* Successfully suspended and still mounted on same carrier thread. */
                    break;
                }
                /* Fiber moved to new carrier thread before it was suspended. Undo and retry. */
                resumeThreadByNode(node);
                LOG_MISC(("commonSuspend: fiber mounted on different carrier thread(%p)", carrier_thread));
            } else {
                /* Fiber is not mounted. Get a suspended helper thread for it. */
                ThreadNode *fiberNode = findThread(&runningFibers, thread);
                if (getFiberHelperThread(thread) == NULL) {
                    /* fiber fixme: Sometimes the fiber is in a bad state and we can't create a
                     * helper thread for it. For now we just fail. */
                    LOG_MISC(("commonSuspend: failed to get fiber helper thread."));
                    return JVMTI_ERROR_INTERNAL;
                }
                fiberNode->suspendCount++;
                break;
            }
        }
        return error;
    }

    /*
     * If the thread is not between its start and end events, we should
     * still suspend it. To keep track of things, add the thread
     * to a separate list of threads so that we'll resume it later.
     */
    node = findThread(&runningThreads, thread);
#if 0
    tty_message("commonSuspend: node(%p) suspendCount(%d) %s", node, node->suspendCount, node->name);
#endif
    if (node == NULL) {
        node = insertThread(env, &otherThreads, thread);
    }

    if ( deferred ) {
        return deferredSuspendThreadByNode(node);
    } else {
        return suspendThreadByNode(node);
    }
}


static jvmtiError
resumeCopyHelper(JNIEnv *env, ThreadNode *node, void *arg)
{
    if (node->isDebugThread) {
        /* never suspended by debugger => don't ever try to resume */
        return JVMTI_ERROR_NONE;
    }

    if (node->suspendCount > 1) {
        node->suspendCount--;
        /* nested suspend so just undo one level */
        return JVMTI_ERROR_NONE;
    }

    /*
     * This thread was marked for suspension since its THREAD_START
     * event came in during a suspendAll, but the helper hasn't
     * completed the job yet. We decrement the count so the helper
     * won't suspend this thread after we are done with the resumeAll.
     * Another case to be handled here is when the debugger suspends
     * the thread while the app has it suspended. In this case,
     * the toBeResumed flag has been cleared indicating that
     * the thread should not be resumed when the debugger does a resume.
     * In this case, we also have to decrement the suspend count.
     * If we don't then when the app resumes the thread and our Thread.resume
     * bkpt handler is called, blockOnDebuggerSuspend will not resume
     * the thread because suspendCount will be 1 meaning that the
     * debugger has the thread suspended.  See bug 6224859.
     */
    if (node->suspendCount == 1 && (!node->toBeResumed || node->suspendOnStart)) {
        node->suspendCount--;
        return JVMTI_ERROR_NONE;
    }

    if (arg == NULL) {
        /* nothing to hard resume so we're done */
        return JVMTI_ERROR_NONE;
    }

    /*
     * This is tricky. A suspendCount of 1 and toBeResumed means that
     * JVM/DI SuspendThread() or JVM/DI SuspendThreadList() was called
     * on this thread. The check for !suspendOnStart is paranoia that
     * we inherited from resumeThreadByNode().
     */
    if (node->suspendCount == 1 && node->toBeResumed && !node->suspendOnStart) {
        jthread **listPtr = (jthread **)arg;

        **listPtr = node->thread;
        (*listPtr)++;
    }
    return JVMTI_ERROR_NONE;
}


static jvmtiError
resumeCountHelper(JNIEnv *env, ThreadNode *node, void *arg)
{
    if (node->isDebugThread) {
        /* never suspended by debugger => don't ever try to resume */
        return JVMTI_ERROR_NONE;
    }

    /*
     * This is tricky. A suspendCount of 1 and toBeResumed means that
     * JVM/DI SuspendThread() or JVM/DI SuspendThreadList() was called
     * on this thread. The check for !suspendOnStart is paranoia that
     * we inherited from resumeThreadByNode().
     */
    if (node->suspendCount == 1 && node->toBeResumed && !node->suspendOnStart) {
        jint *counter = (jint *)arg;

        (*counter)++;
    }
    return JVMTI_ERROR_NONE;
}

static void *
newArray(jint length, size_t nbytes)
{
    void *ptr;
    ptr = jvmtiAllocate(length*(jint)nbytes);
    if ( ptr != NULL ) {
        (void)memset(ptr, 0, length*nbytes);
    }
    return ptr;
}

static void
deleteArray(void *ptr)
{
    jvmtiDeallocate(ptr);
}

/*
 * This function must be called with the threadLock held.
 *
 * Two facts conspire to make this routine complicated:
 *
 * 1) the VM doesn't support nested external suspend
 * 2) the original resumeAll code structure doesn't retrieve the
 *    entire thread list from JVMTI so we use the runningThreads
 *    list and two helpers to get the job done.
 *
 * Because we hold the threadLock, state seen by resumeCountHelper()
 * is the same state seen in resumeCopyHelper(). resumeCountHelper()
 * just counts up the number of threads to be hard resumed.
 * resumeCopyHelper() does the accounting for nested suspends and
 * special cases and, finally, populates the list of hard resume
 * threads to be passed to ResumeThreadList().
 *
 * At first glance, you might think that the accounting could be done
 * in resumeCountHelper(), but then resumeCopyHelper() would see
 * "post-resume" state in the accounting values (suspendCount and
 * toBeResumed) and would not be able to distinguish between a thread
 * that needs a hard resume versus a thread that is already running.
 */
static jvmtiError
commonResumeList(JNIEnv *env)
{
    jvmtiError   error;
    jint         i;
    jint         reqCnt;
    jthread     *reqList;
    jthread     *reqPtr;
    jvmtiError  *results;

    reqCnt = 0;

    /* count number of threads to hard resume */
    (void) enumerateOverThreadList(env, &runningThreads, resumeCountHelper,
                                   &reqCnt);
    if (reqCnt == 0) {
        /* nothing to hard resume so do just the accounting part */
        (void) enumerateOverThreadList(env, &runningThreads, resumeCopyHelper,
                                       NULL);
        return JVMTI_ERROR_NONE;
    }

    /*LINTED*/
    reqList = newArray(reqCnt, sizeof(jthread));
    if (reqList == NULL) {
        EXIT_ERROR(AGENT_ERROR_OUT_OF_MEMORY,"resume request list");
    }
    /*LINTED*/
    results = newArray(reqCnt, sizeof(jvmtiError));
    if (results == NULL) {
        EXIT_ERROR(AGENT_ERROR_OUT_OF_MEMORY,"resume list");
    }

    /* copy the jthread values for threads to hard resume */
    reqPtr = reqList;
    (void) enumerateOverThreadList(env, &runningThreads, resumeCopyHelper,
                                   &reqPtr);

    error = JVMTI_FUNC_PTR(gdata->jvmti,ResumeThreadList)
                (gdata->jvmti, reqCnt, reqList, results);
    for (i = 0; i < reqCnt; i++) {
        ThreadNode *node;

        node = findThread(&runningThreads, reqList[i]);
        if (node == NULL) {
            EXIT_ERROR(AGENT_ERROR_INVALID_THREAD,"missing entry in running thread table");
        }
        LOG_MISC(("thread=%p resumed as part of list", node->thread));

        /*
         * resumeThreadByNode() assumes that JVM/DI ResumeThread()
         * always works and does all the accounting updates. We do
         * the same here. We also don't clear the error.
         */
        node->suspendCount--;
        node->toBeResumed = JNI_FALSE;
        node->frameGeneration++; /* Increment on each resume */
    }
    deleteArray(results);
    deleteArray(reqList);

    debugMonitorNotifyAll(threadLock);

    return error;
}


/*
 * This function must be called after preSuspend and before postSuspend.
 */
static jvmtiError
commonSuspendList(JNIEnv *env, jint initCount, jthread *initList)
{
    jvmtiError  error;
    jint        i;
    jint        reqCnt;
    jthread    *reqList;

    error   = JVMTI_ERROR_NONE;
    reqCnt  = 0;
    reqList = newArray(initCount, sizeof(jthread));
    if (reqList == NULL) {
        EXIT_ERROR(AGENT_ERROR_OUT_OF_MEMORY,"request list");
    }

    /*
     * Go through the initial list and see if we have anything to suspend.
     */
    for (i = 0; i < initCount; i++) {
        ThreadNode *node;

        /*
         * If the thread is not between its start and end events, we should
         * still suspend it. To keep track of things, add the thread
         * to a separate list of threads so that we'll resume it later.
         */
        node = findThread(&runningThreads, initList[i]);
        if (node == NULL) {
            node = insertThread(env, &otherThreads, initList[i]);
        }

        if (node->isDebugThread) {
            /* Ignore requests for suspending debugger threads */
            continue;
        }

        /*
         * Just increment the suspend count if we are waiting
         * for a deferred suspend or if this is a nested suspend.
         */
        if (node->suspendOnStart || node->suspendCount > 0) {
            node->suspendCount++;
            continue;
        }

        if (node->suspendCount == 0) {
            /* thread is not suspended yet so put it on the request list */
            reqList[reqCnt++] = initList[i];
        }
    }

    if (reqCnt > 0) {
        jvmtiError *results = newArray(reqCnt, sizeof(jvmtiError));

        if (results == NULL) {
            EXIT_ERROR(AGENT_ERROR_OUT_OF_MEMORY,"suspend list results");
        }

        /*
         * We have something to suspend so try to do it.
         */
        error = JVMTI_FUNC_PTR(gdata->jvmti,SuspendThreadList)
                        (gdata->jvmti, reqCnt, reqList, results);
        for (i = 0; i < reqCnt; i++) {
            ThreadNode *node;

            node = findThread(NULL, reqList[i]);
            if (node == NULL) {
                EXIT_ERROR(AGENT_ERROR_INVALID_THREAD,"missing entry in thread tables");
            }
            LOG_MISC(("thread=%p suspended as part of list", node->thread));

            if (results[i] == JVMTI_ERROR_NONE) {
                /* thread was suspended as requested */
                node->toBeResumed = JNI_TRUE;
            } else if (results[i] == JVMTI_ERROR_THREAD_SUSPENDED) {
                /*
                 * If the thread was suspended by another app thread,
                 * do nothing and report no error (we won't resume it later).
                 */
                results[i] = JVMTI_ERROR_NONE;
            } else if (results[i] == JVMTI_ERROR_THREAD_NOT_ALIVE) {
                /*
                 * This error means that the suspend request failed
                 * because the thread is either a zombie or not yet
                 * started. In either case, we ignore the error. If the
                 * thread is a zombie, suspend/resume are no-ops. If the
                 * thread is not started, it will be suspended for real
                 * during the processing of its thread start event.
                 */
                node->suspendOnStart = JNI_TRUE;
                results[i] = JVMTI_ERROR_NONE;
            }

            /* count real, app and deferred (suspendOnStart) suspensions */
            if (results[i] == JVMTI_ERROR_NONE) {
                node->suspendCount++;
            }
        }
        deleteArray(results);
    }
    deleteArray(reqList);

    debugMonitorNotifyAll(threadLock);

    return error;
}

static jvmtiError
commonResume(jthread thread)
{
    jvmtiError  error;
    ThreadNode *node;

    if (isFiber(thread)) {
        jthread carrier_thread = getFiberThread(thread);
        ThreadNode *fiberNode = findThread(&runningFibers, thread);
        if (carrier_thread == NULL) {
            /*
             * Fiber is not mounted on a carrier thread. We may already be tracking this fiber as a
             * suspended fiber at this point. We would not be if a suspendAll was done, and there was
             * no suspend of just this fiber. If we are not tracking it, then we need to.
             */
            if (fiberNode->isTrackedSuspendedFiber) {
                if (fiberNode->suspendCount > 0) {
                    fiberNode->suspendCount--;
                    /*
                     * Note, if suspendCount == 0 but suspendAllCount does not, eventually
                     * threadControl_resumeAll() will be responsible for calling
                     * stopTrackingSuspendedFiber()
                     */
                    if (fiberNode->suspendCount == 0 && suspendAllCount == 0) {
                        stopTrackingSuspendedFiber(fiberNode);
                    }
                }
            } else {
                if (suspendAllCount > 0) {
                    startTrackingSuspendedFiber(fiberNode);
                    fiberNode->suspendCount--;
                }
            }
            return JVMTI_ERROR_NONE;
        } else {
            /*
             * This is a mounted fiber. If the fiber is being tracked, and the suspendCount
             * of the carrier thread is 0, then decrement the fiber's suspendCount here
             * since it cannot be done by resumeThreadByNode because we'll have no way to
             * get the fiber if the carrier thread is not suspended (getThreadFiber() will
             * produce a fatal error).
             */
            if (fiberNode->isTrackedSuspendedFiber) {
                if (fiberNode->suspendCount > 0) {
                    ThreadNode *threadNode = findThread(NULL, thread);
                    if (threadNode->suspendCount == 0) {
                        fiberNode->suspendCount--;
                    }
                }
            }
            /* Fiber is mounted on a carrier thread. Fall through to code below to resume
             * the carrier thread. */
            thread = carrier_thread;
        }
    }

    /*
     * The thread is normally between its start and end events, but if
     * not, check the auxiliary list used by threadControl_suspendThread.
     */
    node = findThread(NULL, thread);
#if 0
    tty_message("commonResume: node(%p) suspendCount(%d) %s", node, node->suspendCount, node->name);
#endif

    /*
     * If the node is in neither list, the debugger never suspended
     * this thread, so do nothing.
     */
    error = JVMTI_ERROR_NONE;
    if (node != NULL) {
        error = resumeThreadByNode(node);
    }

    return error;
}


jvmtiError
threadControl_suspendThread(jthread thread, jboolean deferred)
{
    jvmtiError error;
    JNIEnv    *env;

    env = getEnv();

    log_debugee_location("threadControl_suspendThread()", thread, NULL, 0);

    preSuspend();
    error = commonSuspend(env, thread, deferred);
    postSuspend();

    return error;
}

jvmtiError
threadControl_resumeThread(jthread thread, jboolean do_unblock)
{
    jvmtiError error;
    JNIEnv    *env;

    env = getEnv();

    log_debugee_location("threadControl_resumeThread()", thread, NULL, 0);

    eventHandler_lock(); /* for proper lock order */
    debugMonitorEnter(threadLock);
    error = commonResume(thread);
    removeResumed(env, &otherThreads);
    debugMonitorExit(threadLock);
    eventHandler_unlock();

    if (do_unblock) {
        /* let eventHelper.c: commandLoop() know we resumed one thread */
        unblockCommandLoop();
    }

    return error;
}

jvmtiError
threadControl_suspendCount(jthread thread, jint *count)
{
    jvmtiError  error;
    ThreadNode *node;
    jboolean is_fiber = isFiber(thread);

    debugMonitorEnter(threadLock);

    if (is_fiber) {
        node = findThread(&runningFibers, thread);
    } else {
        node = findThread(&runningThreads, thread);
        if (node == NULL) {
            node = findThread(&otherThreads, thread);
        }
    }

    error = JVMTI_ERROR_NONE;
    if (node != NULL) {
        if (!is_fiber) {
            *count = node->suspendCount;
        } else {
            jthread carrier_thread = getFiberThread(thread);
            if (carrier_thread == NULL) {
                if (node->isTrackedSuspendedFiber) {
                    /* Already tracking this fiber, so fiber node owns its suspendCount. */
                    *count = node->suspendCount;
                } else {
                    /* Not tacking this fiber yet, so use suspendAllCount. */
                    *count = suspendAllCount;
                }
            } else {
                /* It's a mounted fiber, so the carrier thread tracks the suspend count. */
                node = findThread(&runningThreads, carrier_thread);
                JDI_ASSERT(node != NULL);
                *count = node->suspendCount;
            }
        }
    } else {
        /*
         * If the node is in neither list, the debugger never suspended
         * this thread, so the suspend count is 0.
         */
        *count = 0;
    }

    debugMonitorExit(threadLock);

    return error;
}

static jboolean
contains(JNIEnv *env, jthread *list, jint count, jthread item)
{
    int i;

    for (i = 0; i < count; i++) {
        if (isSameObject(env, list[i], item)) {
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}


typedef struct {
    jthread *list;
    jint count;
} SuspendAllArg;

static jvmtiError
suspendAllHelper(JNIEnv *env, ThreadNode *node, void *arg)
{
    SuspendAllArg *saArg = (SuspendAllArg *)arg;
    jvmtiError error = JVMTI_ERROR_NONE;
    jthread *list = saArg->list;
    jint count = saArg->count;
    if (!contains(env, list, count, node->thread)) {
        error = commonSuspend(env, node->thread, JNI_FALSE);
    }
    return error;
}

jvmtiError
threadControl_suspendAll(void)
{
    jvmtiError error;
    JNIEnv    *env;
#if 0
    tty_message("threadControl_suspendAll: suspendAllCount(%d)", suspendAllCount);
#endif

    env = getEnv();

    log_debugee_location("threadControl_suspendAll()", NULL, NULL, 0);

    preSuspend();

    /*
     * Get a list of all threads and suspend them.
     */
    WITH_LOCAL_REFS(env, 1) {

        jthread *threads;
        jint count;

        threads = allThreads(&count);
        if (threads == NULL) {
            error = AGENT_ERROR_OUT_OF_MEMORY;
            goto err;
        }
        if (canSuspendResumeThreadLists()) {
            error = commonSuspendList(env, count, threads);
            if (error != JVMTI_ERROR_NONE) {
                goto err;
            }
        } else {
            int i;
            for (i = 0; i < count; i++) {
                error = commonSuspend(env, threads[i], JNI_FALSE);

                if (error != JVMTI_ERROR_NONE) {
                    goto err;
                }
            }
        }

        /*
         * Update the suspend count of any threads not yet (or no longer)
         * in the thread list above.
         */
        {
            SuspendAllArg arg;
            arg.list = threads;
            arg.count = count;
            error = enumerateOverThreadList(env, &otherThreads,
                                            suspendAllHelper, &arg);
        }

        /*
         * Update the suspend count of any fiber that was explicitly suspended
         * and had a helper thread created for that purpose. These are known
         * as "tracked" suspended fibers.
         */
        debugMonitorEnter(threadLock);
        {
            ThreadNode *trackedSuspendedFiber = trackedSuspendedFibers;
            while (trackedSuspendedFiber != NULL) {
                trackedSuspendedFiber->suspendCount++;
                trackedSuspendedFiber = trackedSuspendedFiber->nextTrackedSuspendedFiber;
            }
        }
        debugMonitorExit(threadLock);

        if (error == JVMTI_ERROR_NONE) {
            suspendAllCount++;
        }

    err: ;

    } END_WITH_LOCAL_REFS(env)

    postSuspend();

    return error;
}

static jvmtiError
resumeHelper(JNIEnv *env, ThreadNode *node, void *ignored)
{
    /*
     * Since this helper is called with the threadLock held, we
     * don't need to recheck to see if the node is still on one
     * of the two thread lists.
     */
    return resumeThreadByNode(node);
}

jvmtiError
threadControl_resumeAll(void)
{
    jvmtiError error;
    JNIEnv    *env;
#if 0
    tty_message("threadControl_resumeAll: suspendAllCount(%d)", suspendAllCount);
#endif

    env = getEnv();

    log_debugee_location("threadControl_resumeAll()", NULL, NULL, 0);

    eventHandler_lock(); /* for proper lock order */
    debugMonitorEnter(threadLock);

    /*
     * Resume only those threads that the debugger has suspended. All
     * such threads must have a node in one of the thread lists, so there's
     * no need to get the whole thread list from JVMTI (unlike
     * suspendAll).
     */
    if (canSuspendResumeThreadLists()) {
        error = commonResumeList(env);
    } else {
        error = enumerateOverThreadList(env, &runningThreads,
                                        resumeHelper, NULL);
    }
    if ((error == JVMTI_ERROR_NONE) && (otherThreads.first != NULL)) {
        error = enumerateOverThreadList(env, &otherThreads,
                                        resumeHelper, NULL);
        removeResumed(env, &otherThreads);
    }

    if (suspendAllCount > 0) {
        suspendAllCount--;
    }

    /*
     * Update the suspend count of any fiber that is being tracked. If it is being
     * tracked, that means that either it was explicitly suspended and had a helper
     * thread created for helping to suspend it, or it had helper thread created for
     * the purpose of getting its stack. If the count reaches zero, then stop tracking the fiber.
     */
    {
        ThreadNode *trackedSuspendedFiber = trackedSuspendedFibers;
        while (trackedSuspendedFiber != NULL) {
            ThreadNode *fiberNode = trackedSuspendedFiber;
            trackedSuspendedFiber = trackedSuspendedFiber->nextTrackedSuspendedFiber;
            if (fiberNode->suspendCount > 0) {
                fiberNode->suspendCount--;
            }
            if (fiberNode->suspendCount == 0 && suspendAllCount == 0) {
                stopTrackingSuspendedFiber(fiberNode);
            }
        }
    }

    debugMonitorExit(threadLock);
    eventHandler_unlock();
    /* let eventHelper.c: commandLoop() know we are resuming */
    unblockCommandLoop();

    return error;
}


StepRequest *
threadControl_getStepRequest(jthread thread)
{
    ThreadNode  *node;
    StepRequest *step;

    step = NULL;

    debugMonitorEnter(threadLock);

    node = findThread(&runningThreads, thread);
    if (node != NULL) {
        step = &node->currentStep;
    }

    debugMonitorExit(threadLock);

    return step;
}

InvokeRequest *
threadControl_getInvokeRequest(jthread thread)
{
    ThreadNode    *node;
    InvokeRequest *request;

    request = NULL;

    debugMonitorEnter(threadLock);

    node = findThread(&runningThreads, thread);
    if (node != NULL) {
         request = &node->currentInvoke;
    }

    debugMonitorExit(threadLock);

    return request;
}

jvmtiError
threadControl_addDebugThread(jthread thread)
{
    jvmtiError error;

    debugMonitorEnter(threadLock);
    if (debugThreadCount >= MAX_DEBUG_THREADS) {
        error = AGENT_ERROR_OUT_OF_MEMORY;
    } else {
        JNIEnv    *env;

        env = getEnv();
        debugThreads[debugThreadCount] = NULL;
        saveGlobalRef(env, thread, &(debugThreads[debugThreadCount]));
        if (debugThreads[debugThreadCount] == NULL) {
            error = AGENT_ERROR_OUT_OF_MEMORY;
        } else {
            debugThreadCount++;
            error = JVMTI_ERROR_NONE;
        }
    }
    debugMonitorExit(threadLock);
    return error;
}

static jvmtiError
threadControl_removeDebugThread(jthread thread)
{
    jvmtiError error;
    JNIEnv    *env;
    int        i;

    error = AGENT_ERROR_INVALID_THREAD;
    env   = getEnv();

    debugMonitorEnter(threadLock);
    for (i = 0; i< debugThreadCount; i++) {
        if (isSameObject(env, thread, debugThreads[i])) {
            int j;

            tossGlobalRef(env, &(debugThreads[i]));
            for (j = i+1; j < debugThreadCount; j++) {
                debugThreads[j-1] = debugThreads[j];
            }
            debugThreadCount--;
            error = JVMTI_ERROR_NONE;
            break;
        }
    }
    debugMonitorExit(threadLock);
    return error;
}

jboolean
threadControl_isDebugThread(jthread thread)
{
    int      i;
    jboolean rc;
    JNIEnv  *env;

    rc  = JNI_FALSE;
    env = getEnv();

    debugMonitorEnter(threadLock);
    for (i = 0; i < debugThreadCount; i++) {
        if (isSameObject(env, thread, debugThreads[i])) {
            rc = JNI_TRUE;
            break;
        }
    }
    debugMonitorExit(threadLock);
    return rc;
}

static void
initLocks(void)
{
    if (popFrameEventLock == NULL) {
        popFrameEventLock = debugMonitorCreate("JDWP PopFrame Event Lock");
        popFrameProceedLock = debugMonitorCreate("JDWP PopFrame Proceed Lock");
    }
}

static jboolean
getPopFrameThread(jthread thread)
{
    jboolean popFrameThread;

    debugMonitorEnter(threadLock);
    {
        ThreadNode *node;

        node = findThread(NULL, thread);
        if (node == NULL) {
            popFrameThread = JNI_FALSE;
        } else {
            popFrameThread = node->popFrameThread;
        }
    }
    debugMonitorExit(threadLock);

    return popFrameThread;
}

static void
setPopFrameThread(jthread thread, jboolean value)
{
    debugMonitorEnter(threadLock);
    {
        ThreadNode *node;

        node = findThread(NULL, thread);
        if (node == NULL) {
            EXIT_ERROR(AGENT_ERROR_NULL_POINTER,"entry in thread table");
        } else {
            node->popFrameThread = value;
        }
    }
    debugMonitorExit(threadLock);
}

static jboolean
getPopFrameEvent(jthread thread)
{
    jboolean popFrameEvent;

    debugMonitorEnter(threadLock);
    {
        ThreadNode *node;

        node = findThread(NULL, thread);
        if (node == NULL) {
            popFrameEvent = JNI_FALSE;
            EXIT_ERROR(AGENT_ERROR_NULL_POINTER,"entry in thread table");
        } else {
            popFrameEvent = node->popFrameEvent;
        }
    }
    debugMonitorExit(threadLock);

    return popFrameEvent;
}

static void
setPopFrameEvent(jthread thread, jboolean value)
{
    debugMonitorEnter(threadLock);
    {
        ThreadNode *node;

        node = findThread(NULL, thread);
        if (node == NULL) {
            EXIT_ERROR(AGENT_ERROR_NULL_POINTER,"entry in thread table");
        } else {
            node->popFrameEvent = value;
            node->frameGeneration++; /* Increment on each resume */
        }
    }
    debugMonitorExit(threadLock);
}

static jboolean
getPopFrameProceed(jthread thread)
{
    jboolean popFrameProceed;

    debugMonitorEnter(threadLock);
    {
        ThreadNode *node;

        node = findThread(NULL, thread);
        if (node == NULL) {
            popFrameProceed = JNI_FALSE;
            EXIT_ERROR(AGENT_ERROR_NULL_POINTER,"entry in thread table");
        } else {
            popFrameProceed = node->popFrameProceed;
        }
    }
    debugMonitorExit(threadLock);

    return popFrameProceed;
}

static void
setPopFrameProceed(jthread thread, jboolean value)
{
    debugMonitorEnter(threadLock);
    {
        ThreadNode *node;

        node = findThread(NULL, thread);
        if (node == NULL) {
            EXIT_ERROR(AGENT_ERROR_NULL_POINTER,"entry in thread table");
        } else {
            node->popFrameProceed = value;
        }
    }
    debugMonitorExit(threadLock);
}

/**
 * Special event handler for events on the popped thread
 * that occur during the pop operation.
 */
static void
popFrameCompleteEvent(jthread thread)
{
      debugMonitorEnter(popFrameProceedLock);
      {
          /* notify that we got the event */
          debugMonitorEnter(popFrameEventLock);
          {
              setPopFrameEvent(thread, JNI_TRUE);
              debugMonitorNotify(popFrameEventLock);
          }
          debugMonitorExit(popFrameEventLock);

          /* make sure we get suspended again */
          setPopFrameProceed(thread, JNI_FALSE);
          while (getPopFrameProceed(thread) == JNI_FALSE) {
              debugMonitorWait(popFrameProceedLock);
          }
      }
      debugMonitorExit(popFrameProceedLock);
}

/**
 * Pop one frame off the stack of thread.
 * popFrameEventLock is already held
 */
static jvmtiError
popOneFrame(jthread thread)
{
    jvmtiError error;

    error = JVMTI_FUNC_PTR(gdata->jvmti,PopFrame)(gdata->jvmti, thread);
    if (error != JVMTI_ERROR_NONE) {
        return error;
    }

    /* resume the popped thread so that the pop occurs and so we */
    /* will get the event (step or method entry) after the pop */
    LOG_MISC(("thread=%p resumed in popOneFrame", thread));
    error = JVMTI_FUNC_PTR(gdata->jvmti,ResumeThread)(gdata->jvmti, thread);
    if (error != JVMTI_ERROR_NONE) {
        return error;
    }

    /* wait for the event to occur */
    setPopFrameEvent(thread, JNI_FALSE);
    while (getPopFrameEvent(thread) == JNI_FALSE) {
        debugMonitorWait(popFrameEventLock);
    }

    /* make sure not to suspend until the popped thread is on the wait */
    debugMonitorEnter(popFrameProceedLock);
    {
        /* return popped thread to suspended state */
        LOG_MISC(("thread=%p suspended in popOneFrame", thread));
        error = JVMTI_FUNC_PTR(gdata->jvmti,SuspendThread)(gdata->jvmti, thread);

        /* notify popped thread so it can proceed when resumed */
        setPopFrameProceed(thread, JNI_TRUE);
        debugMonitorNotify(popFrameProceedLock);
    }
    debugMonitorExit(popFrameProceedLock);

    return error;
}

/**
 * pop frames of the stack of 'thread' until 'frame' is popped.
 */
jvmtiError
threadControl_popFrames(jthread thread, FrameNumber fnum)
{
    jvmtiError error;
    jvmtiEventMode prevStepMode;
    jint framesPopped = 0;
    jint popCount;
    jboolean prevInvokeRequestMode;

    log_debugee_location("threadControl_popFrames()", thread, NULL, 0);

    initLocks();

    /* compute the number of frames to pop */
    popCount = fnum+1;
    if (popCount < 1) {
        return AGENT_ERROR_NO_MORE_FRAMES;
    }

    /* enable instruction level single step, but first note prev value */
    prevStepMode = threadControl_getInstructionStepMode(thread);

    /*
     * Fix bug 6517249.  The pop processing will disable invokes,
     * so remember if invokes are enabled now and restore
     * that state after we finish popping.
     */
    prevInvokeRequestMode = invoker_isEnabled(thread);

    error = threadControl_setEventMode(JVMTI_ENABLE,
                                       EI_SINGLE_STEP, thread);
    if (error != JVMTI_ERROR_NONE) {
        return error;
    }

    /* Inform eventHandler logic we are in a popFrame for this thread */
    debugMonitorEnter(popFrameEventLock);
    {
        setPopFrameThread(thread, JNI_TRUE);
        /* pop frames using single step */
        while (framesPopped++ < popCount) {
            error = popOneFrame(thread);
            if (error != JVMTI_ERROR_NONE) {
                break;
            }
        }
        setPopFrameThread(thread, JNI_FALSE);
    }
    debugMonitorExit(popFrameEventLock);

    /*  Reset StepRequest info (fromLine and stackDepth) after popframes
     *  only if stepping is enabled.
     */
    if (prevStepMode == JVMTI_ENABLE) {
        stepControl_resetRequest(thread);
    }

    if (prevInvokeRequestMode) {
        invoker_enableInvokeRequests(thread);
    }

    /* restore state */
    (void)threadControl_setEventMode(prevStepMode,
                               EI_SINGLE_STEP, thread);

    return error;
}

/* Check to see if any events are being consumed by a popFrame(). */
static jboolean
checkForPopFrameEvents(JNIEnv *env, EventIndex ei, jthread thread)
{
    if ( getPopFrameThread(thread) ) {
        switch (ei) {
            case EI_THREAD_START:
                /* Excuse me? */
                EXIT_ERROR(AGENT_ERROR_INTERNAL, "thread start during pop frame");
                break;
            case EI_THREAD_END:
                /* Thread wants to end? let it. */
                setPopFrameThread(thread, JNI_FALSE);
                popFrameCompleteEvent(thread);
                break;
            case EI_SINGLE_STEP:
                /* This is an event we requested to mark the */
                /*        completion of the pop frame */
                popFrameCompleteEvent(thread);
                return JNI_TRUE;
            case EI_BREAKPOINT:
            case EI_EXCEPTION:
            case EI_FIELD_ACCESS:
            case EI_FIELD_MODIFICATION:
            case EI_METHOD_ENTRY:
            case EI_METHOD_EXIT:
                /* Tell event handler to assume event has been consumed. */
                return JNI_TRUE;
            default:
                break;
        }
    }
    /* Pretend we were never called */
    return JNI_FALSE;
}

struct bag *
threadControl_onEventHandlerEntry(jbyte sessionID, EventInfo *evinfo, jobject currentException)
{
    ThreadNode *node;
    JNIEnv     *env;
    struct bag *eventBag;
    jthread     threadToSuspend;
    jboolean    consumed;
    EventIndex  ei = evinfo->ei;
    jthread     thread = evinfo->thread;

    env             = getEnv();
    threadToSuspend = NULL;

    log_debugee_location("threadControl_onEventHandlerEntry()", thread, NULL, 0);

    /* Events during pop commands may need to be ignored here. */
    consumed = checkForPopFrameEvents(env, ei, thread);
    if ( consumed ) {
        /* Always restore any exception (see below). */
        if (currentException != NULL) {
            JNI_FUNC_PTR(env,Throw)(env, currentException);
        } else {
            JNI_FUNC_PTR(env,ExceptionClear)(env);
        }
        return NULL;
    }

    debugMonitorEnter(threadLock);

    /*
     * Check the list of unknown threads maintained by suspend
     * and resume. If this thread is currently present in the
     * list, it should be
     * moved to the runningThreads list, since it is a
     * well-known thread now.
     */
    node = findThread(&otherThreads, thread);
    if (node != NULL) {
        moveNode(&otherThreads, &runningThreads, node);
    } else {
        /*
         * Get a thread node for the reporting thread. For thread start
         * events, or if this event precedes a thread start event,
         * the thread node may need to be created.
         *
         * It is possible for certain events (notably method entry/exit)
         * to precede thread start for some VM implementations.
         */
        node = insertThread(env, &runningThreads, thread);
    }

    if (ei == EI_THREAD_START) {
        node->isStarted = JNI_TRUE;
        processDeferredEventModes(env, thread, node);
    }

    node->current_ei = ei;
    eventBag = node->eventBag;
    if (node->suspendOnStart) {
        threadToSuspend = node->thread;
    }
    debugMonitorExit(threadLock);

    if (threadToSuspend != NULL) {
        /*
         * An attempt was made to suspend this thread before it started.
         * We must suspend it now, before it starts to run. This must
         * be done with no locks held.
         */
        eventHelper_suspendThread(sessionID, threadToSuspend);
    }

    return eventBag;
}

static void
doPendingTasks(JNIEnv *env, ThreadNode *node)
{
    /*
     * Take care of any pending interrupts/stops, and clear out
     * info on pending interrupts/stops.
     */
    if (node->pendingInterrupt) {
        JVMTI_FUNC_PTR(gdata->jvmti,InterruptThread)
                        (gdata->jvmti, node->thread);
        /*
         * TO DO: Log error
         */
        node->pendingInterrupt = JNI_FALSE;
    }

    if (node->pendingStop != NULL) {
        JVMTI_FUNC_PTR(gdata->jvmti,StopThread)
                        (gdata->jvmti, node->thread, node->pendingStop);
        /*
         * TO DO: Log error
         */
        tossGlobalRef(env, &(node->pendingStop));
    }
}

void
threadControl_onEventHandlerExit(EventIndex ei, jthread thread,
                                 struct bag *eventBag)
{
    ThreadNode *node;

    log_debugee_location("threadControl_onEventHandlerExit()", thread, NULL, 0);

    if (ei == EI_THREAD_END) {
        eventHandler_lock(); /* for proper lock order */
    }
    debugMonitorEnter(threadLock);

    node = findThread(&runningThreads, thread);
    if (node == NULL) {
        EXIT_ERROR(AGENT_ERROR_NULL_POINTER,"thread list corrupted");
    } else {
        JNIEnv *env;

        env = getEnv();
        if (ei == EI_THREAD_END) {
            jboolean inResume = (node->resumeFrameDepth > 0);
            removeThread(env, &runningThreads, thread);
            node = NULL;   /* has been freed */

            /*
             * Clean up mechanism used to detect end of
             * resume.
             */
            if (inResume) {
                notifyAppResumeComplete();
            }
        } else {
            /* No point in doing this if the thread is about to die.*/
            doPendingTasks(env, node);
            node->eventBag = eventBag;
            node->current_ei = 0;
        }
    }

    debugMonitorExit(threadLock);
    if (ei == EI_THREAD_END) {
        eventHandler_unlock();
    }
}

void
threadControl_setName(jthread thread, const char *name)
{
#ifdef DEBUG_THREADNAME
    ThreadNode *node = findThread(NULL, thread);
    if (node != NULL) {
        strncpy(node->name, name, sizeof(node->name) - 1);
    }
#endif
}

/* Returns JDWP flavored status and status flags. */
jvmtiError
threadControl_applicationThreadStatus(jthread thread,
                        jdwpThreadStatus *pstatus, jint *statusFlags)
{
    ThreadNode *node = NULL;
    jvmtiError  error;
    jint        state;
    jboolean    is_fiber = isFiber(thread);

    log_debugee_location("threadControl_applicationThreadStatus()", thread, NULL, 0);

    debugMonitorEnter(threadLock);

    if (!is_fiber) {
        error = threadState(thread, &state);
        *pstatus = map2jdwpThreadStatus(state);
        *statusFlags = map2jdwpSuspendStatus(state);
        node = findThread(&runningThreads, thread);

        if (error == JVMTI_ERROR_NONE) {
            if ((node != NULL) && HANDLING_EVENT(node)) {
                /*
                 * While processing an event, an application thread is always
                 * considered to be running even if its handler happens to be
                 * cond waiting on an internal debugger monitor, etc.
                 *
                 * Leave suspend status untouched since it is not possible
                 * to distinguish debugger suspends from app suspends.
                 */
                *pstatus = JDWP_THREAD_STATUS(RUNNING);
            }
        }
#if 0
        tty_message("status thread: node(%p) suspendCount(%d) %d %d %s",
                    node, node->suspendCount, *pstatus, *statusFlags, node->name);
#endif
    } else { /* It's a fiber */
        int suspendCount;
        error = JVMTI_ERROR_NONE;
        *pstatus = JDWP_THREAD_STATUS(RUNNING);
        *statusFlags = 0;
        node = findThread(&runningFibers, thread);
        if (node->isTrackedSuspendedFiber) {
            /* Already tracking this fiber, so fiber node owns its suspendCount. */
            suspendCount = node->suspendCount;
        } else {
            /* Not tacking this fiber yet, so use suspendAllCount. */
            suspendCount = suspendAllCount;
        }
        if (suspendCount > 0) {
            *statusFlags = JDWP_SUSPEND_STATUS(SUSPENDED);
        } else {
            /* If the fiber was not suspended, maybe it's carrier thread was. */
            thread = getFiberThread(thread);
            if (thread != NULL) {
                node = findThread(&runningThreads, thread);
                if (node->suspendCount > 0) {
                    *statusFlags = JDWP_SUSPEND_STATUS(SUSPENDED);
                }
            }
        }
#if 0
        tty_message("status thread: fiber(%p) suspendCount(%d) %d %d %s",
                    node, node->suspendCount, *pstatus, *statusFlags, node->name);
#endif
    }

    debugMonitorExit(threadLock);

    return error;
}

jvmtiError
threadControl_interrupt(jthread thread)
{
    ThreadNode *node;
    jvmtiError  error;

    error = JVMTI_ERROR_NONE;

    log_debugee_location("threadControl_interrupt()", thread, NULL, 0);

    debugMonitorEnter(threadLock);

    node = findThread(&runningThreads, thread);
    if ((node == NULL) || !HANDLING_EVENT(node)) {
        error = JVMTI_FUNC_PTR(gdata->jvmti,InterruptThread)
                        (gdata->jvmti, thread);
    } else {
        /*
         * Hold any interrupts until after the event is processed.
         */
        node->pendingInterrupt = JNI_TRUE;
    }

    debugMonitorExit(threadLock);

    return error;
}

void
threadControl_clearCLEInfo(JNIEnv *env, jthread thread)
{
    ThreadNode *node;

    debugMonitorEnter(threadLock);

    node = findThread(&runningThreads, thread);
    if (node != NULL) {
        node->cleInfo.ei = 0;
        if (node->cleInfo.clazz != NULL) {
            tossGlobalRef(env, &(node->cleInfo.clazz));
        }
    }

    debugMonitorExit(threadLock);
}

jboolean
threadControl_cmpCLEInfo(JNIEnv *env, jthread thread, jclass clazz,
                         jmethodID method, jlocation location)
{
    ThreadNode *node;
    jboolean    result;

    result = JNI_FALSE;

    debugMonitorEnter(threadLock);

    node = findThread(&runningThreads, thread);
    if (node != NULL && node->cleInfo.ei != 0 &&
        node->cleInfo.method == method &&
        node->cleInfo.location == location &&
        (isSameObject(env, node->cleInfo.clazz, clazz))) {
        result = JNI_TRUE; /* we have a match */
    }

    debugMonitorExit(threadLock);

    return result;
}

void
threadControl_saveCLEInfo(JNIEnv *env, jthread thread, EventIndex ei,
                          jclass clazz, jmethodID method, jlocation location)
{
    ThreadNode *node;

    debugMonitorEnter(threadLock);

    node = findThread(&runningThreads, thread);
    if (node != NULL) {
        node->cleInfo.ei = ei;
        /* Create a class ref that will live beyond */
        /* the end of this call */
        saveGlobalRef(env, clazz, &(node->cleInfo.clazz));
        /* if returned clazz is NULL, we just won't match */
        node->cleInfo.method    = method;
        node->cleInfo.location  = location;
    }

    debugMonitorExit(threadLock);
}

void
threadControl_setPendingInterrupt(jthread thread)
{
    ThreadNode *node;

    debugMonitorEnter(threadLock);

    node = findThread(&runningThreads, thread);
    if (node != NULL) {
        node->pendingInterrupt = JNI_TRUE;
    }

    debugMonitorExit(threadLock);
}

jvmtiError
threadControl_stop(jthread thread, jobject throwable)
{
    ThreadNode *node;
    jvmtiError  error;

    error = JVMTI_ERROR_NONE;

    log_debugee_location("threadControl_stop()", thread, NULL, 0);

    debugMonitorEnter(threadLock);

    node = findThread(&runningThreads, thread);
    if ((node == NULL) || !HANDLING_EVENT(node)) {
        error = JVMTI_FUNC_PTR(gdata->jvmti,StopThread)
                        (gdata->jvmti, thread, throwable);
    } else {
        JNIEnv *env;

        /*
         * Hold any stops until after the event is processed.
         */
        env = getEnv();
        saveGlobalRef(env, throwable, &(node->pendingStop));
    }

    debugMonitorExit(threadLock);

    return error;
}

static jvmtiError
detachHelper(JNIEnv *env, ThreadNode *node, void *arg)
{
    invoker_detach(&node->currentInvoke);
    return JVMTI_ERROR_NONE;
}

void
threadControl_detachInvokes(void)
{
    JNIEnv *env;

    env = getEnv();
    invoker_lock(); /* for proper lock order */
    debugMonitorEnter(threadLock);
    (void)enumerateOverThreadList(env, &runningThreads, detachHelper, NULL);
    debugMonitorExit(threadLock);
    invoker_unlock();
}

static jvmtiError
resetHelper(JNIEnv *env, ThreadNode *node, void *arg)
{
    if (node->toBeResumed) {
        LOG_MISC(("thread=%p resumed", node->thread));
        (void)JVMTI_FUNC_PTR(gdata->jvmti,ResumeThread)(gdata->jvmti, node->thread);
        node->frameGeneration++; /* Increment on each resume */
    }
    stepControl_clearRequest(node->thread, &node->currentStep);
    node->toBeResumed = JNI_FALSE;
    node->suspendCount = 0;
    node->suspendOnStart = JNI_FALSE;

    return JVMTI_ERROR_NONE;
}

void
threadControl_reset(void)
{
    JNIEnv *env;

    env = getEnv();
    eventHandler_lock(); /* for proper lock order */
    debugMonitorEnter(threadLock);
    (void)enumerateOverThreadList(env, &runningThreads, resetHelper, NULL);
    (void)enumerateOverThreadList(env, &otherThreads, resetHelper, NULL);
    (void)enumerateOverThreadList(env, &runningFibers, resetHelper, NULL);

    removeResumed(env, &otherThreads);

    freeDeferredEventModes(env);

    suspendAllCount = 0;

    /* Everything should have been resumed */
    JDI_ASSERT(otherThreads.first == NULL);

    debugMonitorExit(threadLock);
    eventHandler_unlock();
}

jvmtiEventMode
threadControl_getInstructionStepMode(jthread thread)
{
    ThreadNode    *node;
    jvmtiEventMode mode;

    mode = JVMTI_DISABLE;

    debugMonitorEnter(threadLock);
    node = findThread(&runningThreads, thread);
    if (node != NULL) {
        mode = node->instructionStepMode;
    }
    debugMonitorExit(threadLock);
    return mode;
}

jvmtiError
threadControl_setEventMode(jvmtiEventMode mode, EventIndex ei, jthread thread)
{
    jvmtiError error;

    /* Global event */
    if ( thread == NULL ) {
        error = JVMTI_FUNC_PTR(gdata->jvmti,SetEventNotificationMode)
                    (gdata->jvmti, mode, eventIndex2jvmti(ei), thread);
    } else {
        /* Thread event */
        ThreadNode *node;

        debugMonitorEnter(threadLock);
        {
            if (isFiber(thread)) {
                /* fiber fixme: Getting the carrier thread here is just a hack. It does not work if
                 * the fiber is not mounted, and even if mounted, does not result in the correct
                 * behaviour if the fiber changes carrier threads. If the carrier thread is
                 * NULL we need to defer all the code below, most notably
                 * threadSetEventNotificationMode(), until after the fiber is mounted. We also need
                 * to call threadSetEventNotificationMode() each time there is an unmount or mount
                 * since the thread that needs notifications will change as the fiber moves
                 * between carrier threads. The best way to manage this might be to move
                 * HandlerNodes for unmounted fibers onto a linked list hanging off the fiber's
                 * ThreadNode. But that also complicates finding HandlerNodes. For example,
                 * when a breakpoint is cleared, we call eventHandler_freeByID(), which would
                 * need to also search every fiber for the handler. The other choice is to
                 * keep handlers where they are now (off the array of handler chains), but
                 * for every mount/unmount, search all the handlers in all the chains for
                 * ones that are for the mounting/unmounting fiber. This could be slow,
                 * although generally speaking we don't have many HandlerNodes because
                 * they are generated indirectly by the debugger as users do things
                 * like set breakpoints.
                 * A hybrid approach might be best. Keep the handler chains as they are now,
                 * but also have each fiber maintain a list of its handler nodes for faster
                 * handling during mount/unmount.
                 *
                 * And it should also be noted here that if the carrier thread is null, the
                 * findThread() call ends up returning the current thread, and then 
                 * threadSetEventNotificationMode() is called with a NULL thread, resulting
                 * in the event being enabled on all threads. This bug actually has the 
                 * desireable affect of making breakpoints that are filtered on an unmounted
                 * fiber work as expected, because all the carrier threads get the breakpoint
                 * event enabled. However, for some odd reason it also works as expected if
                 * the fiber is already mounted. I expected that the breakpoint event would only
                 * be enabled on the carrier thread in that case, and therefore if the fiber
                 * was moved to a different carrier thread, you would stop getting breakpoints
                 * until it moved back to the original carrier thread. That's not the case for some
                 * reason, and I'm see the breakpoints no matter what carrier thread the fiber
                 * runs on. It turns out that the agent installs a global breakpoint for
                 * Thread.resume(), so global breakpoints are always enabled.
                 * See handleAppResumeBreakpoint.
                 *
                 * It also should be noted that this does not cause a problem for single stepping
                 * because:
                 *  - There is at most one single step HandlerNode per thread.
                 *  - Fiber mount/unmount events result explicitly dooing the proper
                 *    enabling/disabling of the JVMTI single step event on the carrier thread.
                 * There is a potential issue with initiating a StepRequest on and unmounted
                 * fiber. See the fixme comment in stepControl_beginStep.
                 */ 
                thread = getFiberThread(thread);
            }
            node = findThread(&runningThreads, thread);
            if ((node == NULL) || (!node->isStarted)) {
                JNIEnv *env;

                env = getEnv();
                error = addDeferredEventMode(env, mode, ei, thread);
            } else {
                error = threadSetEventNotificationMode(node,
                        mode, ei, thread);
            }
        }
        debugMonitorExit(threadLock);

    }
    return error;
}

/*
 * Returns the current thread, if the thread has generated at least
 * one event, and has not generated a thread end event.
 */
jthread
threadControl_currentThread(void)
{
    jthread thread;

    debugMonitorEnter(threadLock);
    {
        ThreadNode *node;

        node = findThread(&runningThreads, NULL);
        thread = (node == NULL) ? NULL : node->thread;
    }
    debugMonitorExit(threadLock);

    return thread;
}

jlong
threadControl_getFrameGeneration(jthread thread)
{
    jlong frameGeneration = -1;

    debugMonitorEnter(threadLock);
    {
        ThreadNode *node;

        node = findThread(NULL, thread);

        if (node != NULL) {
            frameGeneration = node->frameGeneration;
        }
    }
    debugMonitorExit(threadLock);

    return frameGeneration;
}

jthread
threadControl_getFiberCarrierOrHelperThread(jthread fiber)
{
    /* Get the carrier thread that the fiber is running on */
    jthread carrier_thread = getFiberThread(fiber);
    if (carrier_thread != NULL) {
        return carrier_thread;
    } else {
        jthread helperThread;
        debugMonitorEnter(threadLock);
        helperThread = getFiberHelperThread(fiber);
        debugMonitorExit(threadLock);
        if (helperThread == NULL) {
            /* fiber fixme: we failed to get the helper thread, probably because the fiber
             * is currently in the PARKING state. Still need a solution for this. Fix
             * all callers too.
             */
            LOG_MISC(("threadControl_getFiberCarrierOrHelperThread: getFiberHelperThread() failed"));
        }
        return helperThread;
    }
}

jthread *
threadControl_allFibers(jint *numFibers)
{
    JNIEnv *env;
    ThreadNode *node;
    jthread* fibers;

    env = getEnv();
    debugMonitorEnter(threadLock);

    /* Count the number of fibers */
    /* fiber fixme: we should keep a running total so no counting is needed. */
    *numFibers = 0;
    for (node = runningFibers.first; node != NULL; node = node->next) {
        (*numFibers)++;
    }

    /* Allocate and fill in the fibers array. */
    fibers = jvmtiAllocate(*numFibers * sizeof(jthread*));
    if (fibers != NULL) {
        int i = 0;
        for (node = runningFibers.first; node != NULL;  node = node->next) {
            fibers[i++] = node->thread;
        }
    }

    debugMonitorExit(threadLock);

    return fibers;
}

jboolean threadControl_isKnownFiber(jthread fiber) {
    ThreadNode *fiberNode;
    debugMonitorEnter(threadLock);
    fiberNode = findThread(&runningFibers, fiber);
    debugMonitorExit(threadLock);
    return fiberNode != NULL;
}

void
threadControl_addFiber(jthread fiber)
{
    ThreadNode *fiberNode;
    debugMonitorEnter(threadLock);
    fiberNode = insertThread(getEnv(), &runningFibers, fiber);
    debugMonitorExit(threadLock);
}

void
threadControl_mountFiber(jthread fiber, jthread thread, jbyte sessionID) {
    /* fiber fixme: this funciton no longer serves any purpose now that we rely on
     * continuation events instead. remove.
     */
}


void
threadControl_unmountFiber(jthread fiber, jthread thread)
{
    /* fiber fixme: this funciton no longer serves any purpose now that we rely on
     * continuation events instead. remove.
     */
}

void
threadControl_continuationRun(jthread thread, jint continuation_frame_count)
{
    debugMonitorEnter(threadLock);
    {
        JNIEnv *env = getEnv();
        ThreadNode *threadNode;
        ThreadNode *fiberNode;
        jthread fiber;

        threadNode = findThread(&runningThreads, thread);

        /*
         * fiber fixme: For now, NULL implies that this is a helper thread created by
         * getFiberHelperThread(). We should actually verify that, but for now just
         * assume it is the case and ignore the event. The need for helper threads will
         * hopefully go away, in which case the assert can be re-added.
         */
        //JDI_ASSERT(threadNode != NULL);
        if (threadNode == NULL) {
            debugMonitorExit(threadLock);
            return;
        }

        JDI_ASSERT(threadNode->isStarted);
        JDI_ASSERT(bagSize(threadNode->eventBag) == 0);

        if (threadNode->currentStep.pending) {
            /*
             * If we are doing a STEP_INTO and are doing class filtering (usually library
             * classes), we are relying on METHOD_ENTRY events to tell us if we've stepped
             * back into user code. We won't get this event if when we resume the
             * continuation, so we need to let the stepControl now that we got a
             * CONTINUATION_RUN event so it can do the right thing in absense of
             * the METHOD_ENTRY event. There's also a FramePop setup situation that
             * stepControl needs to deal with, which is another reason it needs to
             * know about CONTINUATION_RUN events.
             */
            stepControl_handleContinuationRun(env, thread, &threadNode->currentStep);
        }

        fiber = getThreadFiber(threadNode->thread);
        if (fiber == NULL) {
            debugMonitorExit(threadLock);
            return; /* Nothing more to do if thread is not executing a fiber. */
        }

        fiberNode = findThread(&runningFibers, fiber);
        if (!gdata->notifyDebuggerOfAllFibers && fiberNode == NULL) {
            /* This is not a fiber we are tracking, so nothing to do. */
            debugMonitorExit(threadLock);
            return;
        }

        JDI_ASSERT(fiberNode != NULL);
        JDI_ASSERT(fiberNode->isStarted);
        JDI_ASSERT(bagSize(fiberNode->eventBag) == 0);

        /* If we are not single stepping in this fiber then there is nothing to do. */
        if (!fiberNode->currentStep.pending) {
            debugMonitorExit(threadLock);
            return;
        }
        JDI_ASSERT(fiberNode->currentStep.is_fiber);

        /*
         * Move the single step state from the fiberNode to threadNode, but only if we aren't
         * already single stepping on the carrier thread.
         */
        if (!threadNode->currentStep.pending) {
            /* Copy fiber currentStep struct to carrier thread. */
            memcpy(&threadNode->currentStep, &fiberNode->currentStep, sizeof(fiberNode->currentStep));

            /* Enable JVMTI single step on the carrier thread if necessary. */
            if (fiberNode->instructionStepMode == JVMTI_ENABLE) {
                stepControl_enableStepping(thread);
                threadNode->instructionStepMode = JVMTI_ENABLE;
            }

            /* Restore the NotifyFramePop that was in place when this Fiber yielded. */
            {
                jvmtiError error;
                jint depth;
                /* NotifyFramePop was originally called with a depth of 0 to indicate the current
                 * frame. However, frames have been pushed since then, so we need to adjust the
                 * depth to get to the right frame.
                 *
                 * fromStackDepth represents the number of frames on the stack when the STEP_OVER
                 * was started. NotifyFramePop was called on the method that was entered, which is
                 * one frame below (fromStackDepth + 1). To account for new frames pushed since
                 * then, we subtract fromStackDepth from the current number of frames. This
                 * represents the frame where the STEP_OVER was done, but since we want one
                 * frame below this point, we also subtract one.
                 */
                depth = getThreadFrameCount(thread) - fiberNode->currentStep.fromStackDepth;
                depth--; /* We actually want the frame one below the adjusted fromStackDepth. */
                if (depth >= 0) {
                    error = JVMTI_FUNC_PTR(gdata->jvmti,NotifyFramePop)(gdata->jvmti, thread, depth);
                    if (error == JVMTI_ERROR_DUPLICATE) {
                      error = JVMTI_ERROR_NONE;
                      /* Already being notified, continue without error */
                    } else if (error != JVMTI_ERROR_NONE) {
                      EXIT_ERROR(error, "NotifyFramePop failed during mountFiber");
                    }
                } else {
                    /*
                     * If the less than 0, then that means we were single stepping over
                     * the Continuation.doYield() call. In this case NotifyFramePop is not going to work
                     * since there was never one setup (doYield() was never actually entered). So
                     * all that needs to be done is to restore single stepping, and we'll stop
                     * on the next bytecode after the doYield() call.
                     */
                    JDI_ASSERT(depth == -1);
                    if (fiberNode->instructionStepMode == JVMTI_DISABLE) {
                      stepControl_enableStepping(thread);
                      threadNode->instructionStepMode = JVMTI_ENABLE;
                    }
                }
            }
   
            /* Enable events */
            threadControl_setEventMode(JVMTI_ENABLE, EI_EXCEPTION_CATCH, thread);
            threadControl_setEventMode(JVMTI_ENABLE, EI_FRAME_POP, thread);
            if (threadNode->currentStep.methodEnterHandlerNode != NULL) {
                threadControl_setEventMode(JVMTI_ENABLE, EI_METHOD_ENTRY, thread);
            }
        }

        /* Always clear the fiber single step state, regardless of what we've done above. */
        fiberNode->instructionStepMode = JVMTI_DISABLE;
        memset(&fiberNode->currentStep, 0, sizeof(fiberNode->currentStep));

        /*
         * If for any reason we are tracking this fiber, then that must mean during a 
         * suspendAll there was a resume done on this fiber. So we started tracking it
         * and decremented its suspendCount (which normally would put it at 0).
         */
        if (fiberNode->isTrackedSuspendedFiber) {
            JDI_ASSERT(suspendAllCount > 0 && fiberNode->suspendCount == 0);
        }
        if (suspendAllCount > 0) {
            /*
             * If there is an outstanding suspendAll, then we suspend the carrier thread. The
             * way this typically ends up happening is if initially all threads were suspended
             * (perhaps when a breakpoing was hit), and then the debugger user decides to resume
             * the fiber or carrier thread. This could allow a new fiber to be mounted on the
             * carrier thread, but the fiber is implied to be suspended because suspendAllCount
             * is >0. In order to keep the fiber from running we must suspened the carrier thread.
             */
            /* fiber fixme XXX: disable this feature for now. */
            //eventHelper_suspendThread(sessionID, thread);
        }
    }
    debugMonitorExit(threadLock);
}

void
threadControl_continuationYield(jthread thread, jint continuation_frame_count)
{
    /* fiber fixme: need to figure out what to do with these 4 ThreadNode fields:
       unsigned int popFrameEvent : 1;
       unsigned int popFrameProceed : 1;
       unsigned int popFrameThread : 1;
       InvokeRequest currentInvoke;
    */
    debugMonitorEnter(threadLock);
    {
        JNIEnv *env = getEnv();
        ThreadNode *threadNode;
        jint total_frame_count;
        jint fromDepth;

        threadNode = findThread(&runningThreads, thread);

        /*
         * fiber fixme: For now, NULL implies that this is a helper thread created by
         * getFiberHelperThread(). We should actually verify that, but for now just
         * assume it is the case and ignore the event. The need for helper threads will
         * hopefully go away, in which case the assert can be re-added.
         */
        //JDI_ASSERT(threadNode != NULL);
        if (threadNode == NULL) {
            debugMonitorExit(threadLock);
            return; /* Nothing to do if thread is not known */
        }

        JDI_ASSERT(threadNode->isStarted);
        JDI_ASSERT(bagSize(threadNode->eventBag) == 0);

        /*
         * If we are not single stepping in this thread, then there is nothing to do.
         */
        if (!threadNode->currentStep.pending) {
            debugMonitorExit(threadLock);
            return; /* Nothing to do. */
        }

        /* At what depth were we single stepping. */
        fromDepth = threadNode->currentStep.fromStackDepth;

        /*
         * Note the continuation has already been unmounted, so total_frame_count will not
         * include the continuation frames.
         */
        total_frame_count = getThreadFrameCount(thread);

        if (threadNode->currentStep.depth == JDWP_STEP_DEPTH(OVER) &&
            total_frame_count == fromDepth) {
            /*
             * We were stepping over Continuation.doContinue() in Continuation.run(). This
             * is a special case. Before the continuation was unmounted do to the yield, the
             * stack looked like:
             *    java.lang.Continuation.yield0
             *    java.lang.Continuation.yield
             *    <fiber frames>  <-- if Fiber, otherwise just additional continuation frames
             *    java.lang.Continuation.enter  <-- bottommost continuation frame
             *    java.lang.Continuation.run    <-- doContinue() call jumps into continuation
             *    java.lang.Fiber.runContinuation  <-- if Fiber, otherwise will be different
             *    <scheduler frames>
             * All frames above run(), starting with enter(), are continuation frames. The
             * correct thing to do here is just enable single stepping. This will resume single
             * stepping in Continuation.run() right after the Continuation.doContinue() call.
             */
            JDI_ASSERT(threadNode->instructionStepMode == JVMTI_DISABLE);
            {
                stepControl_enableStepping(thread);
                threadNode->instructionStepMode = JVMTI_ENABLE;
            }
        } else if (!threadNode->currentStep.is_fiber) {
            /* We were single stepping, but not in a fiber. */
            if (total_frame_count < fromDepth) { /* Check if fromDepth is in the continuation. */
                /*
                 * This means the frame we were single stepping in was part of the set of
                 * frames that will were frozen when this continuation yielded. Because of that
                 * we need to re-enable single stepping because we won't ever be getting
                 * the FRAME_POP event for returning to that frame. This will resume single
                 * stepping in Continuation.run() right after the Continuation.enter() call.
                 */
                if (threadNode->instructionStepMode == JVMTI_DISABLE) {
                    stepControl_enableStepping(thread);
                    threadNode->instructionStepMode = JVMTI_ENABLE;
                }
            } else {
                /*
                 * We are not single stepping in the continuation, and from the earlier check we
                 * know we are not single stepping in Continuation.run(), because that would imply
                 * we were single stepping over the doContinue() call, and we already checked
                 * for that. There is nothing to do in this case. A NotifyFramePop is already setup
                 * for a frame further up the stack.
                 */
            }
        } else {
            /*
             * We are single stepping the fiber, not the carrier thread. Move the single step
             * state to the fiberNode.
             */
            jthread fiber = getThreadFiber(thread);
            ThreadNode *fiberNode;
            JDI_ASSERT(fiber != NULL);

            fiberNode = findThread(&runningFibers, fiber);
            if (!gdata->notifyDebuggerOfAllFibers && fiberNode == NULL) {
                /* This is not a fiber we are tracking. */
                debugMonitorExit(threadLock);
                return;
            }

            JDI_ASSERT(fiberNode != NULL);
            JDI_ASSERT(fiberNode->isStarted);
            JDI_ASSERT(bagSize(fiberNode->eventBag) == 0);

            if (threadNode->currentStep.depth == JDWP_STEP_DEPTH(INTO) &&
                (total_frame_count + continuation_frame_count == fromDepth)) {
                /* We are stepping into Continuation.doYield(), so leave single stepping enabled.
                 * This will resume single stepping in Continuation.run() right after the
                 * Continuation.enter() call.
                 */
            } else if (total_frame_count >= fromDepth) { /* Check if fromDepth is NOT in the continuation. */
                /*
                 * This means the single stepping was initiated stepping in a fiber, but in that small
                 * window after Thread.setFiber(this) has been called, and before the fiber's
                 * continuation was actually mounted. An example of this is stepping over the cont.run()
                 * call in Fiber.runContinuation(). In this case we just leave the carrier thread's
                 * single step state in place. We should eventually get a FramePop event to 
                 * enable single stepping again.
                 */
                JDI_ASSERT(threadNode->currentStep.depth == JDWP_STEP_DEPTH(OVER));
            } else {
                /*
                 * We were single stepping in the fiber, and now we need to stop doing that since
                 * we are leaving the fiber. We will copy our single step state from the carrier
                 * thread to the fiber so we can later restore it when the fiber is mounted again
                 * and we get a CONTINUATION_RUN event.
                 */

                /* Clean up JVMTI SINGLE_STEP state. */
                if (threadNode->instructionStepMode == JVMTI_ENABLE) {
                    stepControl_disableStepping(thread);
                    threadNode->instructionStepMode = JVMTI_DISABLE;
                    fiberNode->instructionStepMode = JVMTI_ENABLE;
                }
   
                /* Disable events */
                threadControl_setEventMode(JVMTI_DISABLE, EI_EXCEPTION_CATCH, thread);
                threadControl_setEventMode(JVMTI_DISABLE, EI_FRAME_POP, thread);
                if (threadNode->currentStep.methodEnterHandlerNode != NULL) {
                    threadControl_setEventMode(JVMTI_DISABLE, EI_METHOD_ENTRY, thread);
                }

                /* Copy currentStep struct from the threadNode to the fiberNode and then zero out the threadNode. */
                memcpy(&fiberNode->currentStep, &threadNode->currentStep, sizeof(threadNode->currentStep));
                memset(&threadNode->currentStep, 0, sizeof(threadNode->currentStep));
            }
        }
    }
    debugMonitorExit(threadLock);
}
