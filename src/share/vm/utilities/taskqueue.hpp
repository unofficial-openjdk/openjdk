#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)taskqueue.hpp	1.38 07/05/05 17:07:10 JVM"
#endif
/*
 * Copyright 2001-2006 Sun Microsystems, Inc.  All Rights Reserved.
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
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 *  
 */

class TaskQueueSuper: public CHeapObj {
  friend class ChunkTaskQueue;

protected:
  // The first free element after the last one pushed (mod _n).
  // (For now we'll assume only 32-bit CAS).
  volatile juint _bottom;

  // log2 of the size of the queue.
  enum SomeProtectedConstants {
    Log_n = 14
  };

  // Size of the queue.
  juint n() { return (1 << Log_n); }
  // For computing "x mod n" efficiently.
  juint n_mod_mask() { return n() - 1; }

  struct Age {
    jushort _top;
    jushort _tag;

    jushort tag() const { return _tag; }
    jushort top() const { return _top; }

    Age() { _tag = 0; _top = 0; }

    friend bool operator ==(const Age& a1, const Age& a2) {
      return a1.tag() == a2.tag() && a1.top() == a2.top();
    }

  };
  Age _age;
  // These make sure we do single atomic reads and writes.
  Age get_age() {
    jint res = *(volatile jint*)(&_age);
    return *(Age*)(&res);
  }
  void set_age(Age a) {
    *(volatile jint*)(&_age) = *(int*)(&a);
  }

  jushort get_top() {
    return get_age().top();
  }

  // These both operate mod _n.
  juint increment_index(juint ind) {
    return (ind + 1) & n_mod_mask();
  }
  juint decrement_index(juint ind) {
    return (ind - 1) & n_mod_mask();
  }

  // Returns a number in the range [0.._n).  If the result is "n-1", it
  // should be interpreted as 0.
  juint dirty_size(juint bot, juint top) {
    return ((jint)bot - (jint)top) & n_mod_mask();
  }

  // Returns the size corresponding to the given "bot" and "top".
  juint size(juint bot, juint top) {
    juint sz = dirty_size(bot, top);
    // Has the queue "wrapped", so that bottom is less than top?
    // There's a complicated special case here.  A pair of threads could
    // perform pop_local and pop_global operations concurrently, starting
    // from a state in which _bottom == _top+1.  The pop_local could
    // succeed in decrementing _bottom, and the pop_global in incrementing
    // _top (in which case the pop_global will be awarded the contested
    // queue element.)  The resulting state must be interpreted as an empty 
    // queue.  (We only need to worry about one such event: only the queue
    // owner performs pop_local's, and several concurrent threads
    // attempting to perform the pop_global will all perform the same CAS,
    // and only one can succeed.  Any stealing thread that reads after
    // either the increment or decrement will seen an empty queue, and will 
    // not join the competitors.  The "sz == -1 || sz == _n-1" state will
    // not be modified  by concurrent queues, so the owner thread can reset
    // the state to _bottom == top so subsequent pushes will be performed
    // normally.
    if (sz == (n()-1)) return 0;
    else return sz;
  }
    
public:
  TaskQueueSuper() : _bottom(0), _age() {}

  // Return "true" if the TaskQueue contains any tasks.
  bool peek();

  // Return an estimate of the number of elements in the queue.
  // The "careful" version admits the possibility of pop_local/pop_global
  // races.
  juint size() {
    return size(_bottom, get_top());
  }

  juint dirty_size() {
    return dirty_size(_bottom, get_top());
  }

  // Maximum number of elements allowed in the queue.  This is two less
  // than the actual queue size, for somewhat complicated reasons.
  juint max_elems() { return n() - 2; }

};

typedef oop Task;
class OopTaskQueue: public TaskQueueSuper {
private:
  // Slow paths for push, pop_local.  (pop_global has no fast path.)
  bool push_slow(Task t, juint dirty_n_elems);
  bool pop_local_slow(juint localBot, Age oldAge);

public:
  // Initializes the queue to empty.
  OopTaskQueue();

  void initialize();

  // Push the task "t" on the queue.  Returns "false" iff the queue is
  // full.
  inline bool push(Task t);

  // If succeeds in claiming a task (from the 'local' end, that is, the
  // most recently pushed task), returns "true" and sets "t" to that task.
  // Otherwise, the queue is empty and returns false.
  inline bool pop_local(Task& t);

  // If succeeds in claiming a task (from the 'global' end, that is, the
  // least recently pushed task), returns "true" and sets "t" to that task.
  // Otherwise, the queue is empty and returns false.
  bool pop_global(Task& t);

  // Delete any resource associated with the queue.
  ~OopTaskQueue();

private:
  // Element array.
  volatile Task* _elems;

};

typedef oop* StarTask;
class OopStarTaskQueue: public TaskQueueSuper {
private:
  // Slow paths for push, pop_local.  (pop_global has no fast path.)
  bool push_slow(StarTask t, juint dirty_n_elems);
  bool pop_local_slow(juint localBot, Age oldAge);

public:
  // Initializes the queue to empty.
  OopStarTaskQueue();

  void initialize();

  // Push the task "t" on the queue.  Returns "false" iff the queue is
  // full.
  inline bool push(StarTask t);

  // If succeeds in claiming a task (from the 'local' end, that is, the
  // most recently pushed task), returns "true" and sets "t" to that task.
  // Otherwise, the queue is empty and returns false.
  inline bool pop_local(StarTask& t);

  // If succeeds in claiming a task (from the 'global' end, that is, the
  // least recently pushed task), returns "true" and sets "t" to that task.
  // Otherwise, the queue is empty and returns false.
  bool pop_global(StarTask& t);

  // Delete any resource associated with the queue.
  ~OopStarTaskQueue();

private:
  // Element array.
  volatile StarTask* _elems;

};

// Clone of OopTaskQueue with GenTask instead of Task
typedef size_t GenTask;  // Generic task
class GenTaskQueue: public TaskQueueSuper {
private:
  // Slow paths for push, pop_local.  (pop_global has no fast path.)
  bool push_slow(GenTask t, juint dirty_n_elems);
  bool pop_local_slow(juint localBot, Age oldAge);

public:
  // Initializes the queue to empty.
  GenTaskQueue();

  void initialize();

  // Push the task "t" on the queue.  Returns "false" iff the queue is
  // full.
  inline bool push(GenTask t);

  // If succeeds in claiming a task (from the 'local' end, that is, the
  // most recently pushed task), returns "true" and sets "t" to that task.
  // Otherwise, the queue is empty and returns false.
  inline bool pop_local(GenTask& t);

  // If succeeds in claiming a task (from the 'global' end, that is, the
  // least recently pushed task), returns "true" and sets "t" to that task.
  // Otherwise, the queue is empty and returns false.
  bool pop_global(GenTask& t);

  // Delete any resource associated with the queue.
  ~GenTaskQueue();

private:
  // Element array.
  volatile GenTask* _elems;

};
// End clone of OopTaskQueue with GenTask instead of Task

// Inherits the typedef of "Task" from above.
class TaskQueueSetSuper: public CHeapObj {
protected:
  static int randomParkAndMiller(int* seed0);
public:
  // Returns "true" if some TaskQueue in the set contains a task.
  virtual bool peek() = 0;
};

class OopTaskQueueSet: public TaskQueueSetSuper {
private:
  int _n;
  OopTaskQueue** _queues;

  bool steal_1_random(int queue_num, int* seed, Task& t);
  bool steal_best_of_2(int queue_num, int* seed, Task& t);
  bool steal_best_of_all(int queue_num, int* seed, Task& t);
public:
  OopTaskQueueSet(int n) : _n(n) {
    typedef OopTaskQueue* OopTaskQueuePtr;
    _queues = NEW_C_HEAP_ARRAY(OopTaskQueuePtr, n);
    guarantee(_queues != NULL, "Allocation failure.");
    for (int i = 0; i < n; i++) _queues[i] = NULL;
  }

  void register_queue(int i, OopTaskQueue* q);

  OopTaskQueue* queue(int n);

  // The thread with queue number "queue_num" (and whose random number seed 
  // is at "seed") is trying to steal a task from some other queue.  (It
  // may try several queues, according to some configuration parameter.)
  // If some steal succeeds, returns "true" and sets "t" the stolen task,
  // otherwise returns false.
  bool steal(int queue_num, int* seed, Task& t);

  bool peek();
};

class OopStarTaskQueueSet: public TaskQueueSetSuper {
private:
  int _n;
  OopStarTaskQueue** _queues;

  bool steal_1_random(int queue_num, int* seed, StarTask& t);
  bool steal_best_of_2(int queue_num, int* seed, StarTask& t);
  bool steal_best_of_all(int queue_num, int* seed, StarTask& t);
public:
  OopStarTaskQueueSet(int n) : _n(n) {
    typedef OopStarTaskQueue* OopStarTaskQueuePtr;
    _queues = NEW_C_HEAP_ARRAY(OopStarTaskQueuePtr, n);
    guarantee(_queues != NULL, "Allocation failure.");
    for (int i = 0; i < n; i++) _queues[i] = NULL;
  }

  void register_queue(int i, OopStarTaskQueue* q);

  OopStarTaskQueue* queue(int n);

  // The thread with queue number "queue_num" (and whose random number seed 
  // is at "seed") is trying to steal a task from some other queue.  (It
  // may try several queues, according to some configuration parameter.)
  // If some steal succeeds, returns "true" and sets "t" the stolen task,
  // otherwise returns false.
  bool steal(int queue_num, int* seed, StarTask& t);

  bool peek();
};

// Clone of OopTaskQueueSet for GenTask
class GenTaskQueueSet: public TaskQueueSetSuper {
  friend class ChunkTaskQueueSet;

private:
  int _n;
  GenTaskQueue** _queues;

protected:
  bool steal_1_random(int queue_num, int* seed, GenTask& t);
  bool steal_best_of_2(int queue_num, int* seed, GenTask& t);
  bool steal_best_of_all(int queue_num, int* seed, GenTask& t);
public:
  GenTaskQueueSet(int n) : _n(n) {
    typedef GenTaskQueue* GenTaskQueuePtr;
    _queues = NEW_C_HEAP_ARRAY(GenTaskQueuePtr, n);
    guarantee(_queues != NULL, "Allocation failure.");
    for (int i = 0; i < n; i++) _queues[i] = NULL;
  }

  void register_queue(int i, GenTaskQueue* q);

  GenTaskQueue* queue(int n);

  // The thread with queue number "queue_num" (and whose random number seed 
  // is at "seed") is trying to steal a task from some other queue.  (It
  // may try several queues, according to some configuration parameter.)
  // If some steal succeeds, returns "true" and sets "t" the stolen task,
  // otherwise returns false.
  bool steal(int queue_num, int* seed, GenTask& t);

  bool peek();
};
// End clone of OopTaskQueueSet for GenTask

// A class to aid in the termination of a set of parallel tasks using
// TaskQueueSet's for work stealing.

class ParallelTaskTerminator: public StackObj {
private:
  int _n_threads;
  TaskQueueSetSuper* _queue_set;
  jint _offered_termination;
  jint _terminated;
  Monitor _term_monitor;

  bool peek_in_queue_set();
protected:
  virtual void yield();
  void sleep(uint millis);

public:

  // "n_threads" is the number of threads to be terminated.  "queue_set" is a 
  // queue sets of work queues of other threads.
  ParallelTaskTerminator(int n_threads, TaskQueueSetSuper* queue_set);

  // The current thread has no work, and is ready to terminate if everyone
  // else is.  If returns "true", all threads are terminated.  If returns
  // "false", available work has been observed in one of the task queues,
  // so the global task is not complete.
  bool offer_termination();

  // Reset the terminator, so that it may be reused again.
  // The caller is responsible for ensuring that this is done
  // in an MT-safe manner, once the previous round of use of
  // the terminator is finished.
  void reset_for_reuse();

};

#define SIMPLE_STACK 0

inline bool OopTaskQueue::push(Task t) {
#if SIMPLE_STACK
  juint localBot = _bottom;
  if (_bottom < max_elems()) {
    _elems[localBot] = t;
    _bottom = localBot + 1;
    return true;
  } else {
    return false;
  }
#else
  juint localBot = _bottom;
  assert((localBot >= 0) && (localBot < n()), "_bottom out of range.");
  jushort top = get_top();
  juint dirty_n_elems = dirty_size(localBot, top);
  assert((dirty_n_elems >= 0) && (dirty_n_elems < n()),
	 "n_elems out of range.");
  if (dirty_n_elems < max_elems()) {
    _elems[localBot] = t;
    _bottom = increment_index(localBot);
    return true;
  } else {
    return push_slow(t, dirty_n_elems);
  }
#endif
}

inline bool OopTaskQueue::pop_local(Task& t) {
#if SIMPLE_STACK
  juint localBot = _bottom;
  assert(localBot > 0, "precondition.");
  localBot--;
  t = _elems[localBot];
  _bottom = localBot;
  return true;
#else
  juint localBot = _bottom;
  // This value cannot be n-1.  That can only occur as a result of
  // the assignment to bottom in this method.  If it does, this method
  // resets the size( to 0 before the next call (which is sequential,
  // since this is pop_local.)
  juint dirty_n_elems = dirty_size(localBot, get_top());
  assert(dirty_n_elems != n() - 1, "Shouldn't be possible...");
  if (dirty_n_elems == 0) return false;
  localBot = decrement_index(localBot);
  _bottom = localBot;
  // This is necessary to prevent any read below from being reordered
  // before the store just above.
  OrderAccess::fence();
  t = _elems[localBot];
  // This is a second read of "age"; the "size()" above is the first.
  // If there's still at least one element in the queue, based on the
  // "_bottom" and "age" we've read, then there can be no interference with 
  // a "pop_global" operation, and we're done.
  juint tp = get_top();
  if (size(localBot, tp) > 0) {
    assert(dirty_size(localBot, tp) != n() - 1,
	   "Shouldn't be possible...");
    return true;
  } else {
    // Otherwise, the queue contained exactly one element; we take the slow
    // path.
    return pop_local_slow(localBot, get_age());
  }
#endif
}

inline bool OopStarTaskQueue::push(StarTask t) {
#if SIMPLE_STACK
  juint localBot = _bottom;
  if (_bottom < max_elems()) {
    _elems[localBot] = t;
    _bottom = localBot + 1;
    return true;
  } else {
    return false;
  }
#else
  juint localBot = _bottom;
  assert((localBot >= 0) && (localBot < n()), "_bottom out of range.");
  jushort top = get_top();
  juint dirty_n_elems = dirty_size(localBot, top);
  assert((dirty_n_elems >= 0) && (dirty_n_elems < n()),
	 "n_elems out of range.");
  if (dirty_n_elems < max_elems()) {
    _elems[localBot] = t;
    _bottom = increment_index(localBot);
    return true;
  } else {
    return push_slow(t, dirty_n_elems);
  }
#endif
}

inline bool OopStarTaskQueue::pop_local(StarTask& t) {
#if SIMPLE_STACK
  juint localBot = _bottom;
  assert(localBot > 0, "precondition.");
  localBot--;
  t = _elems[localBot];
  _bottom = localBot;
  return true;
#else
  juint localBot = _bottom;
  // This value cannot be n-1.  That can only occur as a result of
  // the assignment to bottom in this method.  If it does, this method
  // resets the size( to 0 before the next call (which is sequential,
  // since this is pop_local.)
  juint dirty_n_elems = dirty_size(localBot, get_top());
  assert(dirty_n_elems != n() - 1, "Shouldn't be possible...");
  if (dirty_n_elems == 0) return false;
  localBot = decrement_index(localBot);
  _bottom = localBot;
  // This is necessary to prevent any read below from being reordered
  // before the store just above.
  OrderAccess::fence();
  t = _elems[localBot];
  // This is a second read of "age"; the "size()" above is the first.
  // If there's still at least one element in the queue, based on the
  // "_bottom" and "age" we've read, then there can be no interference with 
  // a "pop_global" operation, and we're done.
  juint tp = get_top();
  if (size(localBot, tp) > 0) {
    assert(dirty_size(localBot, tp) != n() - 1,
	   "Shouldn't be possible...");
    return true;
  } else {
    // Otherwise, the queue contained exactly one element; we take the slow
    // path.
    return pop_local_slow(localBot, get_age());
  }
#endif
}

// Clone of OopTaskQueue for GenTask

inline bool GenTaskQueue::push(GenTask t) {
#if SIMPLE_STACK
  juint localBot = _bottom;
  if (_bottom < max_elems()) {
    _elems[localBot] = t;
    _bottom = localBot + 1;
    return true;
  } else {
    return false;
  }
#else
  juint localBot = _bottom;
  assert((localBot >= 0) && (localBot < n()), "_bottom out of range.");
  jushort top = get_top();
  juint dirty_n_elems = dirty_size(localBot, top);
  assert((dirty_n_elems >= 0) && (dirty_n_elems < n()),
	 "n_elems out of range.");
  if (dirty_n_elems < max_elems()) {
    _elems[localBot] = t;
    _bottom = increment_index(localBot);
    return true;
  } else {
    return push_slow(t, dirty_n_elems);
  }
#endif
}

inline bool GenTaskQueue::pop_local(GenTask& t) {
#if SIMPLE_STACK
  juint localBot = _bottom;
  assert(localBot > 0, "precondition.");
  localBot--;
  t = _elems[localBot];
  _bottom = localBot;
  return true;
#else
  juint localBot = _bottom;
  // This value cannot be n-1.  That can only occur as a result of
  // the assignment to bottom in this method.  If it does, this method
  // resets the size( to 0 before the next call (which is sequential,
  // since this is pop_local.)
  juint dirty_n_elems = dirty_size(localBot, get_top());
  assert(dirty_n_elems != n() - 1, "Shouldn't be possible...");
  if (dirty_n_elems == 0) return false;
  localBot = decrement_index(localBot);
  _bottom = localBot;
  // This is necessary to prevent any read below from being reordered
  // before the store just above.
  OrderAccess::fence();
  t = _elems[localBot];
  // This is a second read of "age"; the "size()" above is the first.
  // If there's still at least one element in the queue, based on the
  // "_bottom" and "age" we've read, then there can be no interference with 
  // a "pop_global" operation, and we're done.
  juint tp = get_top();
  if (size(localBot, tp) > 0) {
    assert(dirty_size(localBot, tp) != n() - 1,
	   "Shouldn't be possible...");
    return true;
  } else {
    // Otherwise, the queue contained exactly one element; we take the slow
    // path.
    return pop_local_slow(localBot, get_age());
  }
#endif
}
// End clone of OopTaskQueue for GenTask

typedef size_t ChunkTask;  // index for chunk

class ChunkTaskQueue: public CHeapObj {
private:
  GenTaskQueue	_chunk_queue;

 public:
  ChunkTaskQueue() {};
  ~ChunkTaskQueue() {};

  bool push_slow(ChunkTask t, juint dirty_n_elems);
  bool pop_local_slow(juint localBot, TaskQueueSuper::Age oldAge);

  inline void initialize() { _chunk_queue.initialize(); }
  inline bool push(ChunkTask t) { return _chunk_queue.push((GenTask) t); }
  inline bool pop_local(ChunkTask& t) { return _chunk_queue.pop_local((GenTask&) t); }
  bool pop_global(ChunkTask& t) { return _chunk_queue.pop_global((GenTask&) t); }  
  juint size() { return _chunk_queue.size(); }
};

class ChunkTaskQueueWithOverflow: public CHeapObj {
  friend class ChunkTaskQueueSet;
 protected:
  GenTaskQueue	              _chunk_queue;
  GrowableArray<ChunkTask>*   _overflow_stack;

 public:
  ChunkTaskQueueWithOverflow() : _overflow_stack(NULL) {}
  // Initialize both stealable queue and overflow
  void initialize();
  // Save first to stealable queue and then to overflow
  void save(ChunkTask t);
  // Retrieve first from overflow and then from stealable queue
  bool retrieve(ChunkTask& chunk_index);
  // Retrieve from stealable queue
  bool retrieve_from_stealable_queue(ChunkTask& chunk_index);
  // Retrieve from overflow
  bool retrieve_from_overflow(ChunkTask& chunk_index);
  bool is_empty();
  bool stealable_is_empty();
  bool overflow_is_empty();
  juint stealable_size() { return _chunk_queue.size(); }
};

class ChunkTaskQueueSet: public CHeapObj {
private:
  GenTaskQueueSet _task_queue_set;

  bool steal_1_random(int queue_num, int* seed, GenTask& t) {
    return _task_queue_set.steal_1_random(queue_num, seed, t);
  }
  bool steal_best_of_2(int queue_num, int* seed, GenTask& t) {
    return _task_queue_set.steal_best_of_2(queue_num, seed, t);
  }
  bool steal_best_of_all(int queue_num, int* seed, GenTask& t) {
    return _task_queue_set.steal_best_of_all(queue_num, seed, t);
  }
public:
  ChunkTaskQueueSet(int n) : _task_queue_set(n) {}

#define USE_ChunkTaskQueueWithOverflow
  void register_queue(int i, GenTaskQueue* q) {
    _task_queue_set.register_queue(i, q);
  }

  void register_queue(int i, ChunkTaskQueueWithOverflow* q) {
    register_queue(i, &q->_chunk_queue);
  }

  ChunkTaskQueue* queue(int n) { 
    return (ChunkTaskQueue*) _task_queue_set.queue(n); 
  }

  GenTaskQueueSet* task_queue_set() { return &_task_queue_set; }

  // The thread with queue number "queue_num" (and whose random number seed 
  // is at "seed") is trying to steal a task from some other queue.  (It
  // may try several queues, according to some configuration parameter.)
  // If some steal succeeds, returns "true" and sets "t" the stolen task,
  // otherwise returns false.
  bool steal(int queue_num, int* seed, GenTask& t) {
    return _task_queue_set.steal(queue_num, seed, t);
  }

  bool peek() {
    return _task_queue_set.peek();
  }
};
// End clone of OopTaskQueueSet for GenTask
