#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)mutex.hpp	1.68 07/05/05 17:06:50 JVM"
#endif
/*
 * Copyright 1998-2006 Sun Microsystems, Inc.  All Rights Reserved.
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

#ifdef  ASSERT
class MutexHistogramElement : public HistogramElement {
  public:
   MutexHistogramElement(const char* elementname);
};

class MutexContentionHistogramElement : public HistogramElement {
  public:
   MutexContentionHistogramElement(const char* elementname);
};


#endif

// A simple Mutex for VM locking using OS primitives.  Note that
// Mutex locking is NOT guaranteed to interoperate with the fast
// object locking, which is intentional: it reduces reliance on the
// fast locking mechanism as it is developed and tuned, and gives us
// a way out of all the recursive locking rat-holes that appear when
// we try to use a single locking mechanism.
//
// Implementation adapted from WIN32 Q&A by Jeffery Richter
// (implementation should be moved into platform specific file)
//
//
//                NOTE WELL!!
//
// See orderAccess.hpp.  We assume throughout the VM that mutex lock and
// try_lock do fence-lock-acquire, and that unlock does a release-unlock,
// *in that order*.  If their implementations change such that these
// assumptions are violated, a whole lot of code will break.

class Mutex : public CHeapObj {
 private:
  // The following methods are machine-specific, and defined in mutex_<os>.inline.hpp or mutex_<os>.cpp
  bool lock_implementation();
  bool try_lock_implementation();
  void wait_for_lock_implementation();
  void wait_for_lock_blocking_implementation(JavaThread *thread);

 public:
  // A special lock: Is a lock where you are guarantteed not to block while you are holding it, i.e., no
  // vm operation can happen, taking other locks, etc. 
  // NOTE: It is critical that the rank 'special' be the lowest (earliest)
  // (except for "event"?) for the deadlock dection to work correctly.
  // The rank native is only for use in Mutex's created by JVM_RawMonitorCreate,
  // which being external to the VM are not subject to deadlock detection.
  // The rank safepoint is used only for synchronization in reaching a
  // safepoint and leaving a safepoint.  It is only used for the Safepoint_lock
  // currently.  While at a safepoint no mutexes of rank safepoint are held
  // by any thread.
  // The rank named "leaf" is probably historical (and should 
  // be changed) -- mutexes of this rank aren't really leaf mutexes
  // at all.
  enum lock_types {
       event,
       special,
       suspend_resume,
       leaf        = suspend_resume +   2,
       safepoint   = leaf           +  10,
       barrier     = safepoint      +   1,
       nonleaf     = barrier        +   1,
       max_nonleaf = nonleaf        + 900,
       native      = max_nonleaf    +   1
  };

 protected:     
  // Fields
  jint              _lock_count; 
  void*             _lock_event;  
  volatile bool     _suppress_signal;    // Used for sneaky locking
  Thread* volatile  _owner;              // The owner of the lock
  const char* _name;                     // Name of mutex  
#ifdef ASSERT
  MutexHistogramElement *_histogram;
  MutexContentionHistogramElement *_contend_histogram;
#endif

  // Debugging fields for naming, deadlock detection, etc. (some only used in debug mode)
#ifndef PRODUCT
  bool      _allow_vm_block;
  debug_only(int _rank;)     	   // rank (to avoid/detect potential deadlocks)
  debug_only(Mutex* _next;)        // Used by a Thread to link up owned locks    
  debug_only(Thread* _last_owner;) // the last thread to own the lock    
  debug_only(static bool contains(Mutex* locks, Mutex* lock);)
  debug_only(static Mutex* get_least_ranked_lock(Mutex* locks);)
  debug_only(Mutex* get_least_ranked_lock_besides_this(Mutex* locks);)
#endif
  
  void set_owner_implementation(Thread* owner)                        PRODUCT_RETURN;
  void trace                   (const char* operation)                PRODUCT_RETURN;
  void check_prelock_state     (Thread* thread)                       PRODUCT_RETURN;
  void check_block_state       (Thread* thread)                       PRODUCT_RETURN;

  // platform-dependent support code can go here (in os_<os_family>.cpp)
  friend class MutexImplementation;
  friend class RawMonitor;
 public:
  enum {
    _no_safepoint_check_flag    = true,
    _allow_vm_block_flag        = true,
    _as_suspend_equivalent_flag = true
  };	

  Mutex(int rank, const char *name, bool allow_vm_block = !_allow_vm_block_flag);
  ~Mutex();
  
  void lock(); // prints out warning if VM thread blocks 
  void lock(Thread *thread); // overloaded with current thread
  void unlock();
  bool is_locked() const                     { return _owner != INVALID_THREAD; }

  bool try_lock(); // Like lock(), but unblocking. It returns false instead

  // Lock without safepoint check. Should ONLY be used by safepoint code and other code
  // that is guaranteed not to block while running inside the VM.
  void lock_without_safepoint_check();    

  // Current owner - not not MT-safe. Can only be used to guarantee that
  // the current running thread owns the lock
  Thread* owner() const         { return _owner; }
  bool owned_by_self() const;

  // Support for JVM_RawMonitorEnter & JVM_RawMonitorExit. These can be called by
  // non-Java thread. (We should really have a RawMonitor abstraction)
  void jvm_raw_lock();
  void jvm_raw_unlock();
  const char *name() const                  { return _name; }

  void print_on_error(outputStream* st) const;

  #ifndef PRODUCT
    void print_on(outputStream* st) const;
    void print() const			    { print_on(tty); }
    debug_only(int    rank() const          { return _rank; })
    bool   allow_vm_block()                 { return _allow_vm_block; }
    
    debug_only(Mutex *next()  const         { return _next; }) 
    debug_only(void   set_next(Mutex *next) { _next = next; })       
  #endif
  
  void set_owner(Thread* owner) {
  #ifndef PRODUCT
    set_owner_implementation(owner);
    debug_only(void verify_mutex_rank(Thread* thr));
  #else  
    _owner = owner;
  #endif
  }

  static Thread* INVALID_THREAD; // Value of _owner when unowned. (i.e., lock is unlocked)
};


// A Monitor is a Mutex with a built in condition variable. It allows a thread, to
// temporarily to give up the lock and wait on the lock, until it is notified.
class Monitor : public Mutex {
 protected:
  void* _event; 	// Manual-reset event for notifications
  long _counter;	// Current number of notifications
  long _waiters;	// Number of threads waiting for notification
  long _tickets;	// Number of waiters to be notified

  enum WaitResults {
    CONDVAR_EVENT,         // Wait returned because of condition variable notification
    INTERRUPT_EVENT,       // Wait returned because waiting thread was interrupted
    NUMBER_WAIT_RESULTS
  };

  friend class RawMonitor;
 public:
  Monitor(int rank, const char *name, bool allow_vm_block=false);
  ~Monitor();

  // Wait until monitor is notified (or times out).
  // Defaults are to make safepoint checks, wait time is forever (i.e.,
  // zero), and not a suspend-equivalent condition. Returns true if wait
  // times out; otherwise returns false.
  bool wait(bool no_safepoint_check = !_no_safepoint_check_flag,
            long timeout = 0,
            bool as_suspend_equivalent = !_as_suspend_equivalent_flag);
  bool notify();
  bool notify_all();
};

/*
 * Per-thread blocking support for JSR166. See the Java-level
 * Documentation for rationale. Basically, park acts like wait, unpark
 * like notify.
 * 
 * 6271289 --
 * To avoid errors where an os thread expires but the JavaThread still
 * exists, Parkers are immortal (type-stable) and are recycled across 
 * new threads.  This parallels the Event and ParkEvent implementation.
 * Because park-unpark alow spurious wakeups it is harmless if an
 * unpark call unparks a new thread using the old Parker reference.  
 *
 * In the future we'll want to think about eliminating Parker and using
 * ParkEvent instead.  There's consider duplication between the two
 * services.  
 *
 */

class Parker : public os::PlatformParker {
private:
  volatile int _counter ; 
  Parker * FreeNext ;
  JavaThread * AssociatedWith ; // Current association
  
public:
  Parker() : PlatformParker() { 
    _counter       = 0 ; 
    FreeNext       = NULL ; 
    AssociatedWith = NULL ; 
  } 
protected:
  ~Parker() { ShouldNotReachHere(); } 
public:
  // For simplicity of interface with Java, all forms of park (indefinite,
  // relative, and absolute) are multiplexed into one call.
  void park(bool isAbsolute, jlong time);
  void unpark();

  // Lifecycle operators
  static Parker * Allocate (JavaThread * t) ;
  static void Release (Parker * e) ;
private:
  static Parker * volatile FreeList ;
  static volatile int ListLock ;
};

