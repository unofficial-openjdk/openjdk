#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)taskqueue.cpp	1.25 07/05/05 17:07:10 JVM"
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

# include "incls/_precompiled.incl"
# include "incls/_taskqueue.cpp.incl"

bool TaskQueueSuper::peek() {
  return _bottom != _age.top();
}

int TaskQueueSetSuper::randomParkAndMiller(int *seed0) {
  const int a =      16807;
  const int m = 2147483647;
  const int q =     127773;  /* m div a */
  const int r =       2836;  /* m mod a */
  assert(sizeof(int) == 4, "I think this relies on that");
  int seed = *seed0;
  int hi   = seed / q;
  int lo   = seed % q;
  int test = a * lo - r * hi;
  if (test > 0)
    seed = test;
  else
    seed = test + m;
  *seed0 = seed;
  return seed;
}

ParallelTaskTerminator::
ParallelTaskTerminator(int n_threads, TaskQueueSetSuper* queue_set) :
  _n_threads(n_threads),
  _queue_set(queue_set), 
  _offered_termination(0), _terminated(0),
  _term_monitor(Mutex::leaf+1, "ParTaskTerm", true) {}

bool ParallelTaskTerminator::peek_in_queue_set() {
  return _queue_set->peek();
}

void ParallelTaskTerminator::yield() {
  os::yield();
}

void ParallelTaskTerminator::sleep(uint millis) {
  os::sleep(Thread::current(), millis, false);
}

bool ParallelTaskTerminator::offer_termination() {
  Atomic::inc(&_offered_termination);

  juint yield_count = 0;
  while (true) {
    if (_offered_termination == _n_threads) {
      //inner_termination_loop();
      return true;
    } else {
      if (yield_count <= WorkStealingYieldsBeforeSleep) {
        yield_count++;
        yield();
      } else {
        if (PrintGCDetails && Verbose) {
         gclog_or_tty->print_cr("ParallelTaskTerminator::offer_termination() "
           "thread %d sleeps after %d yields",
           Thread::current(), yield_count);
        }
        yield_count = 0;
        // A sleep will cause this processor to seek work on another processor's
        // runqueue, if it has nothing else to run (as opposed to the yield
        // which may only move the thread to the end of the this processor's
        // runqueue).
        sleep(WorkStealingSleepMillis);
      }

      if (peek_in_queue_set()) {
        Atomic::dec(&_offered_termination);
        return false;
      }
    }
  }
}

void ParallelTaskTerminator::reset_for_reuse() {
  if (_offered_termination != 0) {
    assert(_offered_termination == _n_threads,
           "Terminator may still be in use");
    _offered_termination = 0;
  }
}

OopTaskQueue::OopTaskQueue() : TaskQueueSuper() {
  assert(sizeof(Age) == sizeof(jint), "Depends on this.");
}

void OopTaskQueue::initialize() {
  _elems = NEW_C_HEAP_ARRAY(Task, n());
  guarantee(_elems != NULL, "Allocation failed.");
}

bool OopTaskQueue::push_slow(Task t, juint dirty_n_elems) {
  if (dirty_n_elems == n() - 1) {
    // Actually means 0, so do the push.
    juint localBot = _bottom;
    _elems[localBot] = t;
    _bottom = increment_index(localBot);
    return true;
  } else
    return false;
}

bool OopTaskQueue::
pop_local_slow(juint localBot, Age oldAge) {
  // This queue was observed to contain exactly one element; either this
  // thread will claim it, or a competing "pop_global".  In either case,
  // the queue will be logically empty afterwards.  Create a new Age value
  // that represents the empty queue for the given value of "_bottom".  (We 
  // must also increment "tag" because of the case where "bottom == 1",
  // "top == 0".  A pop_global could read the queue element in that case,
  // then have the owner thread do a pop followed by another push.  Without
  // the incrementing of "tag", the pop_global's CAS could succeed,
  // allowing it to believe it has claimed the stale element.)
  Age newAge;
  newAge._top = localBot;
  newAge._tag = oldAge.tag() + 1;
  // Perhaps a competing pop_global has already incremented "top", in which 
  // case it wins the element.
  if (localBot == oldAge.top()) {
    Age tempAge;
    // No competing pop_global has yet incremented "top"; we'll try to
    // install new_age, thus claiming the element.
    assert(sizeof(Age) == sizeof(jint) && sizeof(jint) == sizeof(juint),
	   "Assumption about CAS unit.");
    *(jint*)&tempAge = Atomic::cmpxchg(*(jint*)&newAge, (volatile jint*)&_age, *(jint*)&oldAge);
    if (tempAge == oldAge) {
      // We win.
      assert(dirty_size(localBot, get_top()) != n() - 1,
	     "Shouldn't be possible...");
      return true;
    }
  }
  // We fail; a completing pop_global gets the element.  But the queue is
  // empty (and top is greater than bottom.)  Fix this representation of
  // the empty queue to become the canonical one.
  set_age(newAge);
  assert(dirty_size(localBot, get_top()) != n() - 1,
	 "Shouldn't be possible...");
  return false;
}

bool OopTaskQueue::pop_global(Task& t) {
  Age newAge;
  Age oldAge = get_age();
  juint localBot = _bottom;
  juint n_elems = size(localBot, oldAge.top());
  if (n_elems == 0) {
    return false;
  }
  t = _elems[oldAge.top()];
  newAge = oldAge;
  newAge._top = increment_index(newAge.top());
  if ( newAge._top == 0 ) newAge._tag++;
  Age resAge;
  *(jint*)&resAge = Atomic::cmpxchg(*(jint*)&newAge, (volatile jint*)&_age, *(jint*)&oldAge);
  // Note that using "_bottom" here might fail, since a pop_local might
  // have decremented it.
  assert(dirty_size(localBot, newAge._top) != n() - 1,
	 "Shouldn't be possible...");
  return (resAge == oldAge);
}

OopTaskQueue::~OopTaskQueue() {
  FREE_C_HEAP_ARRAY(Task, _elems);
}

OopStarTaskQueue::OopStarTaskQueue() : TaskQueueSuper() {
  assert(sizeof(Age) == sizeof(jint), "Depends on this.");
}

void OopStarTaskQueue::initialize() {
  _elems = NEW_C_HEAP_ARRAY(StarTask, n());
  guarantee(_elems != NULL, "Allocation failed.");
}

bool OopStarTaskQueue::push_slow(StarTask t, juint dirty_n_elems) {
  if (dirty_n_elems == n() - 1) {
    // Actually means 0, so do the push.
    juint localBot = _bottom;
    _elems[localBot] = t;
    _bottom = increment_index(localBot);
    return true;
  } else
    return false;
}

bool OopStarTaskQueue::
pop_local_slow(juint localBot, Age oldAge) {
  // This queue was observed to contain exactly one element; either this
  // thread will claim it, or a competing "pop_global".  In either case,
  // the queue will be logically empty afterwards.  Create a new Age value
  // that represents the empty queue for the given value of "_bottom".  (We 
  // must also increment "tag" because of the case where "bottom == 1",
  // "top == 0".  A pop_global could read the queue element in that case,
  // then have the owner thread do a pop followed by another push.  Without
  // the incrementing of "tag", the pop_global's CAS could succeed,
  // allowing it to believe it has claimed the stale element.)
  Age newAge;
  newAge._top = localBot;
  newAge._tag = oldAge.tag() + 1;
  // Perhaps a competing pop_global has already incremented "top", in which 
  // case it wins the element.
  if (localBot == oldAge.top()) {
    Age tempAge;
    // No competing pop_global has yet incremented "top"; we'll try to
    // install new_age, thus claiming the element.
    assert(sizeof(Age) == sizeof(jint) && sizeof(jint) == sizeof(juint),
	   "Assumption about CAS unit.");
    *(jint*)&tempAge = Atomic::cmpxchg(*(jint*)&newAge, (volatile jint*)&_age, *(jint*)&oldAge);
    if (tempAge == oldAge) {
      // We win.
      assert(dirty_size(localBot, get_top()) != n() - 1,
	     "Shouldn't be possible...");
      return true;
    }
  }
  // We fail; a completing pop_global gets the element.  But the queue is
  // empty (and top is greater than bottom.)  Fix this representation of
  // the empty queue to become the canonical one.
  set_age(newAge);
  assert(dirty_size(localBot, get_top()) != n() - 1,
	 "Shouldn't be possible...");
  return false;
}

bool OopStarTaskQueue::pop_global(StarTask& t) {
  Age newAge;
  Age oldAge = get_age();
  juint localBot = _bottom;
  juint n_elems = size(localBot, oldAge.top());
  if (n_elems == 0) {
    return false;
  }
  t = _elems[oldAge.top()];
  newAge = oldAge;
  newAge._top = increment_index(newAge.top());
  if ( newAge._top == 0 ) newAge._tag++;
  Age resAge;
  *(jint*)&resAge = Atomic::cmpxchg(*(jint*)&newAge, (volatile jint*)&_age, *(jint*)&oldAge);
  // Note that using "_bottom" here might fail, since a pop_local might
  // have decremented it.
  assert(dirty_size(localBot, newAge._top) != n() - 1,
	 "Shouldn't be possible...");
  return (resAge == oldAge);
}

OopStarTaskQueue::~OopStarTaskQueue() {
  FREE_C_HEAP_ARRAY(Task, _elems);
}

// GenTaskQueue is an exact clone of OopTaskQueue.  See
// header file for questions about why GenTaskQueue exists.

GenTaskQueue::GenTaskQueue() : TaskQueueSuper() {
  assert(sizeof(Age) == sizeof(jint), "Depends on this.");
}

void GenTaskQueue::initialize() {
  _elems = NEW_C_HEAP_ARRAY(GenTask, n());
  guarantee(_elems != NULL, "Allocation failed.");
}

bool GenTaskQueue::push_slow(GenTask t, juint dirty_n_elems) {
  if (dirty_n_elems == n() - 1) {
    // Actually means 0, so do the push.
    juint localBot = _bottom;
    _elems[localBot] = t;
    _bottom = increment_index(localBot);
    return true;
  } else
    return false;
}

bool GenTaskQueue::
pop_local_slow(juint localBot, Age oldAge) {
  // This queue was observed to contain exactly one element; either this
  // thread will claim it, or a competing "pop_global".  In either case,
  // the queue will be logically empty afterwards.  Create a new Age value
  // that represents the empty queue for the given value of "_bottom".  (We 
  // must also increment "tag" because of the case where "bottom == 1",
  // "top == 0".  A pop_global could read the queue element in that case,
  // then have the owner thread do a pop followed by another push.  Without
  // the incrementing of "tag", the pop_global's CAS could succeed,
  // allowing it to believe it has claimed the stale element.)
  Age newAge;
  newAge._top = localBot;
  newAge._tag = oldAge.tag() + 1;
  // Perhaps a competing pop_global has already incremented "top", in which 
  // case it wins the element.
  if (localBot == oldAge.top()) {
    Age tempAge;
    // No competing pop_global has yet incremented "top"; we'll try to
    // install new_age, thus claiming the element.
    assert(sizeof(Age) == sizeof(jint) && sizeof(jint) == sizeof(juint),
	   "Assumption about CAS unit.");
    *(jint*)&tempAge = Atomic::cmpxchg(*(jint*)&newAge, (volatile jint*)&_age, *(jint*)&oldAge);
    if (tempAge == oldAge) {
      // We win.
      assert(dirty_size(localBot, get_top()) != n() - 1,
	     "Shouldn't be possible...");
      return true;
    }
  }
  // We fail; a completing pop_global gets the element.  But the queue is
  // empty (and top is greater than bottom.)  Fix this representation of
  // the empty queue to become the canonical one.
  set_age(newAge);
  assert(dirty_size(localBot, get_top()) != n() - 1,
	 "Shouldn't be possible...");
  return false;
}

bool GenTaskQueue::pop_global(GenTask& t) {
  Age newAge;
  Age oldAge = get_age();
  juint localBot = _bottom;
  juint n_elems = size(localBot, oldAge.top());
  if (n_elems == 0) {
    return false;
  }
  t = _elems[oldAge.top()];
  newAge = oldAge;
  newAge._top = increment_index(newAge.top());
  if ( newAge._top == 0 ) newAge._tag++;
  Age resAge;
  *(jint*)&resAge = Atomic::cmpxchg(*(jint*)&newAge, (volatile jint*)&_age, *(jint*)&oldAge);
  // Note that using "_bottom" here might fail, since a pop_local might
  // have decremented it.
  assert(dirty_size(localBot, newAge._top) != n() - 1,
	 "Shouldn't be possible...");
  return (resAge == oldAge);
}

GenTaskQueue::~GenTaskQueue() {
  FREE_C_HEAP_ARRAY(GenTask, _elems);
}
// End of GenTaskQueue clone

void OopTaskQueueSet::register_queue(int i, OopTaskQueue* q) {
  assert(0 <= i && i < _n, "index out of range.");
  _queues[i] = q;
}

OopTaskQueue* OopTaskQueueSet::queue(int i) {
  return _queues[i];
}

bool OopTaskQueueSet::steal(int queue_num, int* seed, Task& t) {
  for (int i = 0; i < 2 * _n; i++)
    if (steal_best_of_2(queue_num, seed, t))
      return true;
  return false;
}

bool OopTaskQueueSet::steal_best_of_all(int queue_num, int* seed, Task& t) {
  if (_n > 2) {
    int best_k;
    jint best_sz = 0;
    for (int k = 0; k < _n; k++) {
      if (k == queue_num) continue;
      jint sz = _queues[k]->size();
      if (sz > best_sz) {
	best_sz = sz;
	best_k = k;
      }
    }
    return best_sz > 0 && _queues[best_k]->pop_global(t);
  } else if (_n == 2) {
    // Just try the other one.
    int k = (queue_num + 1) % 2;
    return _queues[k]->pop_global(t);
  } else {
    assert(_n == 1, "can't be zero.");
    return false;
  }
}

bool OopTaskQueueSet::steal_1_random(int queue_num, int* seed, Task& t) {
  if (_n > 2) {
    int k = queue_num;
    while (k == queue_num) k = randomParkAndMiller(seed) % _n;
    return _queues[2]->pop_global(t);
  } else if (_n == 2) {
    // Just try the other one.
    int k = (queue_num + 1) % 2;
    return _queues[k]->pop_global(t);
  } else {
    assert(_n == 1, "can't be zero.");
    return false;
  }
}

bool OopTaskQueueSet::steal_best_of_2(int queue_num, int* seed, Task& t) {
  if (_n > 2) {
    int k1 = queue_num;
    while (k1 == queue_num) k1 = randomParkAndMiller(seed) % _n;
    int k2 = queue_num;
    while (k2 == queue_num || k2 == k1) k2 = randomParkAndMiller(seed) % _n;
    // Sample both and try the larger.
    juint sz1 = _queues[k1]->size();
    juint sz2 = _queues[k2]->size();
    if (sz2 > sz1) return _queues[k2]->pop_global(t);
    else return _queues[k1]->pop_global(t);
  } else if (_n == 2) {
    // Just try the other one.
    int k = (queue_num + 1) % 2;
    return _queues[k]->pop_global(t);
  } else {
    assert(_n == 1, "can't be zero.");
    return false;
  }
}

bool OopTaskQueueSet::peek() {
  // Try all the queues.
  for (int j = 0; j < _n; j++) {
    if (_queues[j]->peek())
      return true;
  }
  return false;
}

void OopStarTaskQueueSet::register_queue(int i, OopStarTaskQueue* q) {
  assert(0 <= i && i < _n, "index out of range.");
  _queues[i] = q;
}

OopStarTaskQueue* OopStarTaskQueueSet::queue(int i) {
  return _queues[i];
}

bool OopStarTaskQueueSet::steal(int queue_num, int* seed, StarTask& t) {
  for (int i = 0; i < 2 * _n; i++)
    if (steal_best_of_2(queue_num, seed, t))
      return true;
  return false;
}

bool OopStarTaskQueueSet::steal_best_of_all(int queue_num, int* seed,
					    StarTask& t) {
  if (_n > 2) {
    int best_k;
    jint best_sz = 0;
    for (int k = 0; k < _n; k++) {
      if (k == queue_num) continue;
      jint sz = _queues[k]->size();
      if (sz > best_sz) {
	best_sz = sz;
	best_k = k;
      }
    }
    return best_sz > 0 && _queues[best_k]->pop_global(t);
  } else if (_n == 2) {
    // Just try the other one.
    int k = (queue_num + 1) % 2;
    return _queues[k]->pop_global(t);
  } else {
    assert(_n == 1, "can't be zero.");
    return false;
  }
}

bool OopStarTaskQueueSet::steal_1_random(int queue_num, int* seed,
					 StarTask& t) {
  if (_n > 2) {
    int k = queue_num;
    while (k == queue_num) k = randomParkAndMiller(seed) % _n;
    return _queues[2]->pop_global(t);
  } else if (_n == 2) {
    // Just try the other one.
    int k = (queue_num + 1) % 2;
    return _queues[k]->pop_global(t);
  } else {
    assert(_n == 1, "can't be zero.");
    return false;
  }
}

bool OopStarTaskQueueSet::steal_best_of_2(int queue_num, int* seed,
					  StarTask& t) {
  if (_n > 2) {
    int k1 = queue_num;
    while (k1 == queue_num) k1 = randomParkAndMiller(seed) % _n;
    int k2 = queue_num;
    while (k2 == queue_num || k2 == k1) k2 = randomParkAndMiller(seed) % _n;
    // Sample both and try the larger.
    juint sz1 = _queues[k1]->size();
    juint sz2 = _queues[k2]->size();
    if (sz2 > sz1) return _queues[k2]->pop_global(t);
    else return _queues[k1]->pop_global(t);
  } else if (_n == 2) {
    // Just try the other one.
    int k = (queue_num + 1) % 2;
    return _queues[k]->pop_global(t);
  } else {
    assert(_n == 1, "can't be zero.");
    return false;
  }
}

bool OopStarTaskQueueSet::peek() {
  // Try all the queues.
  for (int j = 0; j < _n; j++) {
    if (_queues[j]->peek())
      return true;
  }
  return false;
}

// Clone of OopTaskQueueSet for GenTask
void GenTaskQueueSet::register_queue(int i, GenTaskQueue* q) {
  assert(0 <= i && i < _n, "index out of range.");
  _queues[i] = q;
}

GenTaskQueue* GenTaskQueueSet::queue(int i) {
  return _queues[i];
}

bool GenTaskQueueSet::steal(int queue_num, int* seed, GenTask& t) {
  for (int i = 0; i < 2 * _n; i++)
    if (steal_best_of_all(queue_num, seed, t))
      return true;
  return false;
}

bool GenTaskQueueSet::steal_best_of_all(int queue_num, int* seed, GenTask& t) {
  if (_n > 2) {
    int best_k;
    jint best_sz = 0;
    for (int k = 0; k < _n; k++) {
      if (k == queue_num) continue;
      jint sz = _queues[k]->size();
      if (sz > best_sz) {
	best_sz = sz;
	best_k = k;
      }
    }
    return best_sz > 0 && _queues[best_k]->pop_global(t);
  } else if (_n == 2) {
    // Just try the other one.
    int k = (queue_num + 1) % 2;
    return _queues[k]->pop_global(t);
  } else {
    assert(_n == 1, "can't be zero.");
    return false;
  }
}

bool GenTaskQueueSet::steal_1_random(int queue_num, int* seed, GenTask& t) {
  if (_n > 2) {
    int k = queue_num;
    while (k == queue_num) k = randomParkAndMiller(seed) % _n;
    return _queues[2]->pop_global(t);
  } else if (_n == 2) {
    // Just try the other one.
    int k = (queue_num + 1) % 2;
    return _queues[k]->pop_global(t);
  } else {
    assert(_n == 1, "can't be zero.");
    return false;
  }
}

bool GenTaskQueueSet::steal_best_of_2(int queue_num, int* seed, GenTask& t) {
  if (_n > 2) {
    int k1 = queue_num;
    while (k1 == queue_num) k1 = randomParkAndMiller(seed) % _n;
    int k2 = queue_num;
    while (k2 == queue_num || k2 == k1) k2 = randomParkAndMiller(seed) % _n;
    // Sample both and try the larger.
    juint sz1 = _queues[k1]->size();
    juint sz2 = _queues[k2]->size();
    if (sz2 > sz1) return _queues[k2]->pop_global(t);
    else return _queues[k1]->pop_global(t);
  } else if (_n == 2) {
    // Just try the other one.
    int k = (queue_num + 1) % 2;
    return _queues[k]->pop_global(t);
  } else {
    assert(_n == 1, "can't be zero.");
    return false;
  }
}

bool GenTaskQueueSet::peek() {
  // Try all the queues.
  for (int j = 0; j < _n; j++) {
    if (_queues[j]->peek())
      return true;
  }
  return false;
}
// End clone of OopTaskQueueSet for GenTask

bool ChunkTaskQueueWithOverflow::is_empty() {
  return (_chunk_queue.size() == 0) &&
	 (_overflow_stack->length() == 0);
}

bool ChunkTaskQueueWithOverflow::stealable_is_empty() {
  return _chunk_queue.size() == 0;
}

bool ChunkTaskQueueWithOverflow::overflow_is_empty() {
  return _overflow_stack->length() == 0;
}

void ChunkTaskQueueWithOverflow::initialize() {
  _chunk_queue.initialize();
  assert(_overflow_stack == 0, "Creating memory leak");
  _overflow_stack = 
    new (ResourceObj::C_HEAP) GrowableArray<ChunkTask>(10, true);
}

void ChunkTaskQueueWithOverflow::save(ChunkTask t) {
  if (TraceChunkTasksQueuing && Verbose) {
    gclog_or_tty->print_cr("CTQ: save " PTR_FORMAT, t);
  }
  if(!_chunk_queue.push(t)) {
    _overflow_stack->push(t);
  }
}

// Note that using this method will retrieve all chunks
// that have been saved but that it will always check
// the overflow stack.  It may be more efficient to
// check the stealable queue and the overflow stack
// separately.
bool ChunkTaskQueueWithOverflow::retrieve(ChunkTask& chunk_task) {
  bool result = retrieve_from_overflow(chunk_task);
  if (!result) {
    result = retrieve_from_stealable_queue(chunk_task);
  }
  if (TraceChunkTasksQueuing && Verbose && result) {
    gclog_or_tty->print_cr("  CTQ: retrieve " PTR_FORMAT, result);
  }
  return result;
}

bool ChunkTaskQueueWithOverflow::retrieve_from_stealable_queue(
				   ChunkTask& chunk_task) {
  bool result = _chunk_queue.pop_local(chunk_task);
  if (TraceChunkTasksQueuing && Verbose) {
    gclog_or_tty->print_cr("CTQ: retrieve_stealable " PTR_FORMAT, chunk_task);
  }
  return result;
}

bool ChunkTaskQueueWithOverflow::retrieve_from_overflow(
					ChunkTask& chunk_task) {
  bool result;
  if (!_overflow_stack->is_empty()) {
    chunk_task = _overflow_stack->pop();
    result = true;
  } else {
    chunk_task = (ChunkTask) NULL;
    result = false;
  }
  if (TraceChunkTasksQueuing && Verbose) {
    gclog_or_tty->print_cr("CTQ: retrieve_stealable " PTR_FORMAT, chunk_task);
  }
  return result;
}
