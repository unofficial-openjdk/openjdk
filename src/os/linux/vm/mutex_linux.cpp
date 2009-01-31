#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)mutex_linux.cpp	1.48 07/05/29 11:38:16 JVM"
#endif
/*
 * Copyright 1999-2006 Sun Microsystems, Inc.  All Rights Reserved.
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
# include "incls/_mutex_linux.cpp.incl"

// put OS-includes here
# include <signal.h>

// Implementation of Mutex

// A simple Mutex for VM locking: it is not guaranteed to interoperate with
// the fast object locking, so exclusively use Mutex locking or exclusively
// use fast object locking.

Mutex::Mutex(int rank, const char *name, bool allow_vm_block)
  debug_only( : _rank(rank) )
{
  _lock_event     = new os::Linux::Event;
  _suppress_signal = false;
  _owner          = INVALID_THREAD;
  _name           = name;

#ifdef ASSERT
  if (CountVMLocks) {
    _histogram         = new MutexHistogramElement(name);
    _contend_histogram = new MutexContentionHistogramElement(name);
  }
#endif

#ifndef PRODUCT
  _lock_count     = -1; // unused in solaris
  _allow_vm_block = allow_vm_block;
  debug_only(_next = NULL;)
  debug_only(_last_owner = INVALID_THREAD;)
#endif
}


Mutex::~Mutex() {  
  os::Linux::Event* const _Lock_Event = (os::Linux::Event*)_lock_event;

  assert(_owner == INVALID_THREAD, "Owned Mutex being deleted");
  assert(_lock_count == -1, "Mutex being deleted with non -1 lock count");
  delete _Lock_Event;
}


void Mutex::unlock() {
  os::Linux::Event* const _Lock_Event = (os::Linux::Event*)_lock_event;

  assert(_owner == Thread::current(), "Mutex not being unlocked by owner");

  set_owner(INVALID_THREAD);

  if (_suppress_signal) {
    assert(SafepointSynchronize::is_at_safepoint() &&
           Thread::current()->is_VM_thread(), "can't sneak");
    _suppress_signal = false;
  }
  else {
    assert(_lock_count >= 0, "Mutex being unlocked without positive lock count");
    debug_only(_lock_count--;)
    _Lock_Event->unlock();
  }
}


// Can be called by non-Java threads (JVM_RawMonitorExit)
void Mutex::jvm_raw_unlock() {
  os::Linux::Event* const _Lock_Event = (os::Linux::Event*)_lock_event;
  // Do not call set_owner, as this would break.
  _owner = INVALID_THREAD;
  if (_suppress_signal) {
    assert(SafepointSynchronize::is_at_safepoint() &&
           Thread::current()->is_VM_thread(), "can't sneak");
    _suppress_signal = false;
  }
  else {
    debug_only(_lock_count--;)
    _Lock_Event->unlock();
  }
}


void Mutex::wait_for_lock_blocking_implementation(JavaThread *thread) {
  ThreadBlockInVM tbivm(thread);

  wait_for_lock_implementation();
}


#ifndef PRODUCT
void Mutex::print_on(outputStream* st) const {
  os::Linux::Event* const _Lock_Event = (os::Linux::Event*)_lock_event;

  st->print_cr("Mutex: [0x%x/0x%x] %s - owner: 0x%x", this, _Lock_Event, _name, _owner);
}
#endif


//
// Monitor
//


Monitor::Monitor(int rank, const char *name, bool allow_vm_block) : Mutex(rank, name, allow_vm_block) {
  _event   = NULL;		
  _counter = 0;
  _tickets = 0;
  _waiters = 0;
}


Monitor::~Monitor() {
}  


bool Monitor::wait(bool no_safepoint_check, long timeout,
                   bool as_suspend_equivalent) {
  os::Linux::Event* const _Lock_Event = (os::Linux::Event*)_lock_event;
  Thread* thread = Thread::current();

  assert(_owner != INVALID_THREAD, "Wait on unknown thread");
  assert(_owner == thread, "Wait on Monitor not by owner");

  // The design rule for use of mutexes of rank special or less is
  // that we are guaranteed not to block while holding such mutexes.
  // Here we verify that the least ranked mutex that we hold,
  // modulo the mutex we are about to relinquish, satisfies that
  // constraint, since we are about to block in a wait.
  #ifdef ASSERT
    Mutex* least = get_least_ranked_lock_besides_this(thread->owned_locks());
    assert(least != this, "Specification of get_least_... call above");
    if (least != NULL && least->rank() <= special) {
      tty->print("Attempting to wait on monitor %s/%d while holding"
                 " lock %s/%d -- possible deadlock",
                 name(), rank(), least->name(), least->rank());
      assert(false,
             "Shouldn't block(wait) while holding a lock of rank special");
    }
  #endif // ASSERT

  long c = _counter;

  #ifdef ASSERT
    // Don't catch signals while blocked; let the running threads have the signals.
    // (This allows a debugger to break into the running thread.)
    sigset_t oldsigs;
    sigset_t* allowdebug_blocked = os::Linux::allowdebug_blocked_signals();
    pthread_sigmask(SIG_BLOCK, allowdebug_blocked, &oldsigs);
  #endif

  _waiters++;
  // Loop until condition variable is signaled.  Tickets will
  // reflect the number of threads which have been notified. The counter
  // field is used to make sure we don't respond to notifications that
  // have occurred *before* we started waiting, and is incremented each
  // time the condition variable is signaled.
  // Use a ticket scheme to guard against spurious wakeups.
  int wait_status;

  while (true) {

    if (no_safepoint_check) {

      // conceptually set the owner to INVALID_THREAD in anticipation of yielding the lock in wait
      set_owner(Mutex::INVALID_THREAD);

      // (SafepointTimeout is not implemented)
      if(timeout == 0) {
	wait_status = _Lock_Event->wait();
      }
      else {
	wait_status = _Lock_Event->timedwait(timeout);
      }
    } else {
      JavaThread *jt = (JavaThread *)thread;

      // conceptually set the owner to INVALID_THREAD in anticipation of yielding the lock in wait
      set_owner(Mutex::INVALID_THREAD);

      // Enter safepoint region
      ThreadBlockInVM tbivm(jt);
      OSThreadWaitState osts(thread->osthread(), false /* not Object.wait() */);

      if (as_suspend_equivalent) {
        jt->set_suspend_equivalent();
        // cleared by handle_special_suspend_equivalent_condition() or
        // java_suspend_self()
      }

      if(timeout == 0) {
	wait_status = _Lock_Event->wait();
      }
      else {
	wait_status = _Lock_Event->timedwait(timeout);
      }

      // were we externally suspended while we were waiting?
      if (as_suspend_equivalent &&
          jt->handle_special_suspend_equivalent_condition()) {
        //
        // Our event wait has finished and we own the _Lock_Event, but
        // while we were waiting another thread suspended us. We don't
        // want to hold the _Lock_Event while suspended because that
        // would surprise the thread that suspended us.
        //
        _Lock_Event->unlock();
        jt->java_suspend_self();
        _Lock_Event->lock();
      }
    } // if no_safepoint_check

    // conceptually reaquire the lock (the actual Linux lock is already reacquired after waiting)
    set_owner(thread);

    // We get to this point if either:
    // a) a notify has been executed by some other thread and woke us up
    // b) a signal has been delivered to this thread and terminated wait
    // c) the above two events happened while we were waiting - that is a signal
    //    was delivered while notify was executed by some other thread.

    // Handle cases a) and c) here. We consume one ticket even in case c) when notify
    // and a signal arrive together
    if (_tickets != 0 && _counter != c) {
      break;
    }
    
    // If wait was interrupted by a signal or timeout, do not use up a ticket
    if (wait_status == EINTR || wait_status == ETIME || wait_status == ETIMEDOUT) {
      ++_tickets;		// will be decremented again below
      break;
    }


  }
  _waiters--;
  _tickets--;

#ifdef ASSERT
  pthread_sigmask(SIG_SETMASK, &oldsigs, NULL);
#endif
   
  // return true if timed out
  return (wait_status == ETIME || wait_status == ETIMEDOUT);
}


// Notify a single thread waiting on this condition variable
bool Monitor::notify() {
  os::Linux::Event* const _Lock_Event = (os::Linux::Event*)_lock_event;

  assert(_owner != INVALID_THREAD, "notify on unknown thread");
  assert(_owner == Thread::current(), "notify on Monitor not by owner");

  if (_waiters > _tickets) {
    
    _Lock_Event->signal();
    
    _tickets++;
    _counter++;
  }

  return true;

}


// Notify all threads waiting on this ConditionVariable
bool Monitor::notify_all() {
  os::Linux::Event* const _Lock_Event = (os::Linux::Event*)_lock_event;

  assert(_owner != INVALID_THREAD, "notify on unknown thread");
  assert(_owner == Thread::current(), "notify on Monitor not by owner");

  if (_waiters > 0) {

    _Lock_Event->broadcast();

    _tickets = _waiters;
    _counter++;
  }

  return true;
}

// JSR166
// -------------------------------------------------------

/*
 * The solaris and linux implementations of park/unpark are fairly
 * conservative for now, but can be improved. They currently use a
 * mutex/condvar pair, plus a a count. 
 * Park decrements count if > 0, else does a condvar wait.  Unpark
 * sets count to 1 and signals condvar.  Only one thread ever waits 
 * on the condvar. Contention seen when trying to park implies that someone 
 * is unparking you, so don't wait. And spurious returns are fine, so there 
 * is no need to track notifications.
 */

#define NANOSECS_PER_SEC 1000000000
#define NANOSECS_PER_MILLISEC 1000000
#define MAX_SECS 100000000
/*
 * This code is common to linux and solaris and will be moved to a
 * common place in dolphin.
 *
 * The passed in time value is either a relative time in nanoseconds
 * or an absolute time in milliseconds. Either way it has to be unpacked
 * into suitable seconds and nanoseconds components and stored in the
 * given timespec structure. 
 * Given time is a 64-bit value and the time_t used in the timespec is only 
 * a signed-32-bit value (except on 64-bit Linux) we have to watch for
 * overflow if times way in the future are given. Further on Solaris versions
 * prior to 10 there is a restriction (see cond_timedwait) that the specified
 * number of seconds, in abstime, is less than current_time  + 100,000,000.
 * As it will be 28 years before "now + 100000000" will overflow we can
 * ignore overflow and just impose a hard-limit on seconds using the value
 * of "now + 100,000,000". This places a limit on the timeout of about 3.17
 * years from "now".
 */
static void unpackTime(timespec* absTime, bool isAbsolute, jlong time) {
  assert (time > 0, "convertTime");

  struct timeval now;
  int status = gettimeofday(&now, NULL);
  assert(status == 0, "gettimeofday");

  time_t max_secs = now.tv_sec + MAX_SECS;

  if (isAbsolute) {
    jlong secs = time / 1000;
    if (secs > max_secs) {
      absTime->tv_sec = max_secs;
    }
    else {
      absTime->tv_sec = secs;
    }
    absTime->tv_nsec = (time % 1000) * NANOSECS_PER_MILLISEC;   
  }
  else {
    jlong secs = time / NANOSECS_PER_SEC;
    if (secs >= MAX_SECS) {
      absTime->tv_sec = max_secs;
      absTime->tv_nsec = 0;
    }
    else {
      absTime->tv_sec = now.tv_sec + secs;
      absTime->tv_nsec = (time % NANOSECS_PER_SEC) + now.tv_usec*1000;
      if (absTime->tv_nsec >= NANOSECS_PER_SEC) {
        absTime->tv_nsec -= NANOSECS_PER_SEC;
        ++absTime->tv_sec; // note: this must be <= max_secs
      }
    }
  }
  assert(absTime->tv_sec >= 0, "tv_sec < 0");
  assert(absTime->tv_sec <= max_secs, "tv_sec > max_secs");
  assert(absTime->tv_nsec >= 0, "tv_nsec < 0");
  assert(absTime->tv_nsec < NANOSECS_PER_SEC, "tv_nsec >= nanos_per_sec");
}

void Parker::park(bool isAbsolute, jlong time) {
  // Optional fast-path check:
  // Return immediately if a permit is available.
  if (_counter > 0) { 
      _counter = 0 ;  
      return ;  
  }

  Thread* thread = Thread::current();
  assert(thread->is_Java_thread(), "Must be JavaThread");
  JavaThread *jt = (JavaThread *)thread;

  // Optional optimization -- avoid state transitions if there's an interrupt pending.
  // Check interrupt before trying to wait
  if (Thread::is_interrupted(thread, false)) {
    return;
  }

  // Next, demultiplex/decode time arguments
  timespec absTime;
  if (time < 0) { // don't wait at all
    return; 
  }
  if (time > 0) {
    unpackTime(&absTime, isAbsolute, time);
  }


  // Enter safepoint region
  // Beware of deadlocks such as 6317397. 
  // The per-thread Parker:: mutex is a classic leaf-lock.
  // In particular a thread must never block on the Threads_lock while
  // holding the Parker:: mutex.  If safepoints are pending both the
  // the ThreadBlockInVM() CTOR and DTOR may grab Threads_lock.  
  ThreadBlockInVM tbivm(jt);

  // Don't wait if cannot get lock since interference arises from
  // unblocking.  Also. check interrupt before trying wait
  if (Thread::is_interrupted(thread, false) || pthread_mutex_trylock(_mutex) != 0) {
    return;
  }

  int status ; 
  if (_counter > 0)  { // no wait needed
    _counter = 0;
    status = pthread_mutex_unlock(_mutex);
    assert (status == 0, "invariant") ; 
    return;
  }

#ifdef ASSERT
  // Don't catch signals while blocked; let the running threads have the signals.
  // (This allows a debugger to break into the running thread.)
  sigset_t oldsigs;
  sigset_t* allowdebug_blocked = os::Linux::allowdebug_blocked_signals();
  pthread_sigmask(SIG_BLOCK, allowdebug_blocked, &oldsigs);
#endif
  
  OSThreadWaitState osts(thread->osthread(), false /* not Object.wait() */);
  jt->set_suspend_equivalent();
  // cleared by handle_special_suspend_equivalent_condition() or java_suspend_self()
  
  if (time == 0) {
    status = pthread_cond_wait (_cond, _mutex) ; 
  } else {
    status = os::Linux::safe_cond_timedwait (_cond, _mutex, &absTime) ; 
    if (status != 0 && WorkAroundNPTLTimedWaitHang) { 
      pthread_cond_destroy (_cond) ; 
      pthread_cond_init    (_cond, NULL); 
    }
  }
  assert_status(status == 0 || status == EINTR || 
                status == ETIME || status == ETIMEDOUT, 
                status, "cond_timedwait");

#ifdef ASSERT
  pthread_sigmask(SIG_SETMASK, &oldsigs, NULL);
#endif

  _counter = 0 ; 
  status = pthread_mutex_unlock(_mutex) ;
  assert_status(status == 0, status, "invariant") ; 
  // If externally suspended while waiting, re-suspend
  if (jt->handle_special_suspend_equivalent_condition()) {
    jt->java_suspend_self();
  }

}

void Parker::unpark() {
  int s, status ; 
  status = pthread_mutex_lock(_mutex);
  assert (status == 0, "invariant") ; 
  s = _counter;
  _counter = 1;
  if (s < 1) { 
     if (WorkAroundNPTLTimedWaitHang) { 
        status = pthread_cond_signal (_cond) ; 
        assert (status == 0, "invariant") ; 
        status = pthread_mutex_unlock(_mutex);
        assert (status == 0, "invariant") ; 
     } else {
        status = pthread_mutex_unlock(_mutex);
        assert (status == 0, "invariant") ; 
        status = pthread_cond_signal (_cond) ; 
        assert (status == 0, "invariant") ; 
     }
  } else {
    pthread_mutex_unlock(_mutex);
    assert (status == 0, "invariant") ; 
  }
}

