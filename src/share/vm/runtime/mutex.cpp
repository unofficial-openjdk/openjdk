#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)mutex.cpp	1.60 07/05/05 17:06:43 JVM"
#endif
/*
 * Copyright 1998-2005 Sun Microsystems, Inc.  All Rights Reserved.
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
# include "incls/_mutex.cpp.incl"

#ifdef ASSERT
Histogram* MutexHistogram;
static volatile jint MutexHistogram_lock = 0;
Histogram* MutexContentionHistogram;
static volatile jint MutexContentionHistogram_lock = 0;

MutexHistogramElement::MutexHistogramElement(const char* elementName) {
  _name = elementName;
  uintx count = 0;

  while (Atomic::cmpxchg(1, &MutexHistogram_lock, 0) != 0) {
    while (OrderAccess::load_acquire(&MutexHistogram_lock) != 0) {
      count +=1;
      if ( (WarnOnStalledSpinLock > 0)
        && (count % WarnOnStalledSpinLock == 0)) {
        warning("MutexHistogram_lock seems to be stalled");
      }
    }
  }

  if (MutexHistogram == NULL) {
    MutexHistogram = new Histogram("VM Mutex Lock Attempt Counts",200);
  }

  MutexHistogram->add_element(this);
  Atomic::dec(&MutexHistogram_lock);
}


MutexContentionHistogramElement::MutexContentionHistogramElement(const char* elementName) {
  _name = elementName;
  uintx count = 0;

  while (Atomic::cmpxchg(1, &MutexContentionHistogram_lock, 0) != 0) {
    while (OrderAccess::load_acquire(&MutexContentionHistogram_lock) != 0) {
      count +=1;
      if ( (WarnOnStalledSpinLock > 0)
        && (count % WarnOnStalledSpinLock == 0)) {
        warning("MutexContentionHistogram_lock seems to be stalled");
      }
    }
  }

  if (MutexContentionHistogram == NULL) {
    MutexContentionHistogram = new Histogram("VM Mutex Lock Contention Count",200);
  }

  MutexContentionHistogram->add_element(this);
  Atomic::dec(&MutexContentionHistogram_lock);
}

#endif


// This needs to be an invalid return from Thread::current; NULL is checked
// for as invalid in its implementation. _owner == INVALID_THREAD, means that
// the lock is unlocked.

// ON THE VMTHREAD SNEAKING PAST HELD LOCKS:
// In particular, there are certain types of global lock that may be held
// by a Java thread while it is blocked at a safepoint but before it has
// written the _owner field. These locks may be sneakily acquired by the
// VM thread during a safepoint to avoid deadlocks. Alternatively, one should
// identify all such locks, and ensure that Java threads never block at
// safepoints while holding them (_no_safepopint_check_flag). While it
// seems as though this could increase the time to reach a safepoint
// (or at least increase the mean, if not the variance), the latter
// approach might make for a cleaner, more maintainable JVM design.

Thread* Mutex::INVALID_THREAD = (Thread*)NULL;

void Mutex::lock(Thread *thread) { 

#ifdef CHECK_UNHANDLED_OOPS
  // Clear unhandled oops so we get a crash right away.  Only clear for non-vm
  // or GC threads.
  if (thread->is_Java_thread()) {
    thread->clear_unhandled_oops();
  }
#endif // CHECK_UNHANDLED_OOPS

  debug_only(check_prelock_state(thread));
  // assert(!thread->is_inside_signal_handler(), "don't lock inside signal handler");

#ifdef ASSERT
  // Keep track of how many time access lock
  if (CountVMLocks) _histogram->increment_count();  
#endif

  // lock_implementation is a os-specific method
  if (lock_implementation()) {
    // Success, we now own the lock
  } else {    
#ifdef ASSERT
  // Keep track of how many times we fail 
  if (CountVMLocks) _contend_histogram->increment_count();  
#endif
    bool can_sneak = thread->is_VM_thread() &&
                     SafepointSynchronize::is_at_safepoint();
    if (can_sneak && _owner == INVALID_THREAD) {        
      // a java thread has locked the lock but has not entered the
      // critical region -- let's just pretend we've locked the lock
      // and go on.  we note this with _suppress_signal so we can also
      // pretend to unlock when the time comes.
      _suppress_signal = true;
    } else {
      check_block_state(thread);
      if (!thread->is_Java_thread()) {
	wait_for_lock_implementation();
      } else {	
	debug_only(assert(rank() > Mutex::special, 
	  "Potential deadlock with special or lesser rank mutex"));
	wait_for_lock_blocking_implementation((JavaThread*)thread);
      }
    }
  }

  assert(owner() == Mutex::INVALID_THREAD, "Mutex lock count and owner are inconsistent");
  set_owner(thread);
  trace("locks");  
}

void Mutex::lock() {
  Thread* thread = Thread::current();    
  this->lock(thread);
}

// Returns true if thread succeceed in grabbing the lock, otherwise false.
bool Mutex::try_lock() {
  Thread* thread    = Thread::current();
  debug_only(check_prelock_state(thread));
  // assert(!thread->is_inside_signal_handler(), "don't lock inside signal handler");

#ifdef ASSERT
  // Keep track of how many time access lock
  if (CountVMLocks) _histogram->increment_count();  
#endif
  // Special case, where all Java threads are stopped. The count is not -1, but the owner
  // is not yet set. In that case the VM thread can safely grab the lock.
  bool can_sneak = thread->is_VM_thread() &&
                   SafepointSynchronize::is_at_safepoint();
  if (can_sneak && _owner == INVALID_THREAD) {
    set_owner(thread); // Do not need to be atomic, since we are at a safepoint
    _suppress_signal = true;
    return true;
  }

  // The try_lock_implementation is platform-specific
  if (try_lock_implementation()) {
    // We got the lock        
    assert(owner() == Mutex::INVALID_THREAD, "Mutex lock count and owner are inconsistent");
    set_owner(thread);
    trace("try_locks");
    return true;
  } else {
#ifdef ASSERT
  // Keep track of how many times we fail 
  if (CountVMLocks) _contend_histogram->increment_count();  
#endif
    return false;
  }
}

// Lock without safepoint check. Should ONLY be used by safepoint code and other code
// that is guaranteed not to block while running inside the VM. If this is called with
// thread state set to be in VM, the safepoint synchronization code will deadlock!

void Mutex::lock_without_safepoint_check() {
#ifdef ASSERT
  // Keep track of how many time access lock
  if (CountVMLocks) _histogram->increment_count();  
#endif
  Thread* thread = Thread::current();
// #ifdef ASSERT
//   if (thread) {
//     assert(!thread->is_inside_signal_handler(), "don't lock inside signal handler");
//   }
// #endif

  // lock_implementation is platform specific
  if (lock_implementation()) {
    // Success, we now own the lock
  } else {    
#ifdef ASSERT
    // Keep track of how many times we fail 
    if (CountVMLocks) _contend_histogram->increment_count();  
#endif
    wait_for_lock_implementation();
  }

  assert(_owner == INVALID_THREAD, "Mutex lock count and owner are inconsistent");
  set_owner(thread);
}


// Can be called by non-Java threads (JVM_RawMonitorEnter)
void Mutex::jvm_raw_lock() {
#ifdef ASSERT
  // Keep track of how many time access lock
  if (CountVMLocks) _histogram->increment_count();  
#endif
  assert(rank() == native, "must be called by non-VM locks");
  if (lock_implementation()) {
    // Success, we now own the lock
  } else {    
#ifdef ASSERT
    // Keep track of how many times we fail 
    if (CountVMLocks) _contend_histogram->increment_count();  
#endif
    wait_for_lock_implementation();
  }
  assert(_owner == INVALID_THREAD, "Mutex lock count and owner are inconsistent");
  // This can potentially be called by non-java Threads. Thus, the ThreadLocalStorage
  // might return NULL. Don't call set_owner since it will break on an NULL
  // owner
  _owner = ThreadLocalStorage::thread();
}

bool Mutex::owned_by_self() const { 
  bool ret = _owner == Thread::current(); 
  assert(_lock_count>=0 || !ret, "lock count must by >=0 for a locked mutex");
  return ret;
}

void Mutex::print_on_error(outputStream* st) const {
  st->print("[" PTR_FORMAT, this);
  st->print("/" PTR_FORMAT, _lock_event);
  st->print("] %s", _name);
  st->print(" - owner thread: " PTR_FORMAT, _owner);
}

// ----------------------------------------------------------------------------------
// Non-product code


#ifndef PRODUCT
#ifdef ASSERT
Mutex* Mutex::get_least_ranked_lock(Mutex* locks) {
  Mutex *res, *tmp;
  for (res = tmp = locks; tmp != NULL; tmp = tmp->next()) {
    if (tmp->rank() < res->rank()) {
      res = tmp;
    }
  }
  if (!SafepointSynchronize::is_at_safepoint()) {
    // In this case, we expect the held locks to be
    // in increasing rank order (modulo any native ranks)
    for (tmp = locks; tmp != NULL; tmp = tmp->next()) {
      if (tmp->next() != NULL) {
        assert(tmp->rank() == Mutex::native || 
               tmp->rank() <= tmp->next()->rank(), "mutex rank anomaly?");
      }
    }
  }
  return res;
}

Mutex* Mutex::get_least_ranked_lock_besides_this(Mutex* locks) {
  Mutex *res, *tmp;
  for (res = NULL, tmp = locks; tmp != NULL; tmp = tmp->next()) {
    if (tmp != this && (res == NULL || tmp->rank() < res->rank())) {
      res = tmp;
    }
  }
  if (!SafepointSynchronize::is_at_safepoint()) {
    // In this case, we expect the held locks to be
    // in increasing rank order (modulo any native ranks)
    for (tmp = locks; tmp != NULL; tmp = tmp->next()) {
      if (tmp->next() != NULL) {
        assert(tmp->rank() == Mutex::native ||
               tmp->rank() <= tmp->next()->rank(), "mutex rank anomaly?");
      }
    }
  }
  return res;
}


bool Mutex::contains(Mutex* locks, Mutex* lock) {
  for (; locks != NULL; locks = locks->next()) {
    if (locks == lock)
      return true;
  }
  return false;
}
#endif

void Mutex::set_owner_implementation(Thread *new_owner) {  
  // This function is solely responsible for maintaining
  // and checking the invariant that threads and locks
  // are in a 1/N relation, with some some locks unowned.
  // It uses the Mutex::_owner, Mutex::_next, and
  // Thread::_owned_locks fields, and no other function
  // changes those fields.
  // It is illegal to set the mutex from one non-NULL
  // owner to another--it must be owned by NULL as an
  // intermediate state.

  if (new_owner != INVALID_THREAD) {
    // the thread is acquiring this lock
 
    assert(new_owner == Thread::current(), "Should I be doing this?");
    assert(_owner == INVALID_THREAD, "setting the owner thread of an already owned mutex");
    _owner = new_owner; // set the owner

    // link "this" into the owned locks list

    #ifdef ASSERT  // Thread::_owned_locks is under the same ifdef
      Mutex* locks = get_least_ranked_lock(new_owner->owned_locks());
                    // Mutex::set_owner_implementation is a friend of Thread

      assert(this->rank() >= 0, "bad lock rank");

      if (LogMultipleMutexLocking && locks != NULL) {
        Events::log("thread " INTPTR_FORMAT " locks %s, already owns %s", new_owner, name(), locks->name());
      }

      // Deadlock avoidance rules require us to acquire Mutexes only in
      // a global total order. For example m1 is the lowest ranked mutex 
      // that the thread holds and m2 is the mutex the thread is trying 
      // to acquire, then  deadlock avoidance rules require that the rank 
      // of m2 be less  than the rank of m1. 
      // The rank Mutex::native  is an exception in that it is not subject 
      // to the verification rules.
      // Here are some further notes relating to mutex acquisition anomalies:
      // . under Solaris, the interrupt lock gets acquired when doing
      //   profiling, so any lock could be held.
      // . it is also ok to acquire Safepoint_lock at the very end while we
      //   already hold Terminator_lock - may happen because of periodic safepoints
      if (this->rank() != Mutex::native &&
          this->rank() != Mutex::suspend_resume &&
          locks != NULL && locks->rank() <= this->rank() &&
	  !SafepointSynchronize::is_at_safepoint() &&
          this != Interrupt_lock && this != ProfileVM_lock &&
	  !(this == Safepoint_lock && contains(locks, Terminator_lock) &&
            SafepointSynchronize::is_synchronizing())) { 
        new_owner->print_owned_locks();
        fatal4("acquiring lock %s/%d out of order with lock %s/%d -- possible deadlock", 
	       this->name(), this->rank(), locks->name(), locks->rank());
      }

      this->_next = new_owner->_owned_locks;
      new_owner->_owned_locks = this;
    #endif

  } else {
    // the thread is releasing this lock

    Thread* old_owner = _owner;
    debug_only(_last_owner = old_owner);
    
    assert(old_owner != INVALID_THREAD, "removing the owner thread of an unowned mutex");
    assert(old_owner == Thread::current(), "removing the owner thread of an unowned mutex");

    _owner = INVALID_THREAD; // set the owner

    #ifdef ASSERT
      Mutex *locks = old_owner->owned_locks();

      if (LogMultipleMutexLocking && locks != this) {
        Events::log("thread " INTPTR_FORMAT " unlocks %s, still owns %s", old_owner, this->name(), locks->name());
      }
  
      // remove "this" from the owned locks list
  
      Mutex *prev = NULL;
      bool found = false;
      for (; locks != NULL; prev = locks, locks = locks->next()) {
        if (locks == this) {
	  found = true;
	  break;
        }
      }
      assert(found, "Removing a lock not owned");
      if (prev == NULL) {
        old_owner->_owned_locks = _next;
      } else {
        prev->_next = _next;
      }
      _next = NULL;
    #endif
  }
}


// Factored out common sanity checks for locking mutex'es. Used by lock() and try_lock()
void Mutex::check_prelock_state(Thread *thread) {
  assert(_lock_count >= -1, "sanity check");  
  assert((!thread->is_Java_thread() || ((JavaThread *)thread)->thread_state() == _thread_in_vm) 
         || rank() == Mutex::special, "wrong thread state for using locks");
  if (StrictSafepointChecks) { 
    if (thread->is_VM_thread() && !allow_vm_block()) {
      fatal1("VM thread using lock %s (not allowed to block on)", name());
    }
    debug_only(if (rank() != Mutex::special) \
      thread->check_for_valid_safepoint_state(false);)
  }
}

void Mutex::check_block_state(Thread *thread) {  
  if (!_allow_vm_block && thread->is_VM_thread()) {
    warning("VM thread blocked on lock");
    print();
    BREAKPOINT;
  }   
  assert(_owner != thread, "deadlock: blocking on monitor owned by current thread");    
}


void Mutex::trace(const char* operation) {
}

#endif // PRODUCT
