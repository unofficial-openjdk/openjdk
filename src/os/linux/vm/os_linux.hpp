#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)os_linux.hpp	1.70 07/05/05 17:04:37 JVM"
#endif
/*
 * Copyright 1999-2007 Sun Microsystems, Inc.  All Rights Reserved.
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

// Linux_OS defines the interface to Linux operating systems

/* pthread_getattr_np comes with LinuxThreads-0.9-7 on RedHat 7.1 */
typedef int (*pthread_getattr_func_type) (pthread_t, pthread_attr_t *);

class Linux {
  friend class os;

  // For signal-chaining
#define MAXSIGNUM 32
  static struct sigaction sigact[MAXSIGNUM]; // saved preinstalled sigactions
  static unsigned int sigs;             // mask of signals that have
                                        // preinstalled signal handlers
  static bool libjsig_is_loaded;        // libjsig that interposes sigaction(),
                                        // __sigaction(), signal() is loaded
  static struct sigaction *(*get_signal_action)(int);
  static struct sigaction *get_preinstalled_handler(int);
  static void save_preinstalled_handler(int, struct sigaction&);

  static void check_signal_handler(int sig);

  // For signal flags diagnostics
  static int sigflags[MAXSIGNUM];

  static int (*_clock_gettime)(clockid_t, struct timespec *);
  static int (*_pthread_getcpuclockid)(pthread_t, clockid_t *);

  static address   _initial_thread_stack_bottom;
  static uintptr_t _initial_thread_stack_size;

  static char *_glibc_version;
  static char *_libpthread_version;

  static bool _is_floating_stack;
  static bool _is_NPTL;
  static bool _supports_fast_thread_cpu_time;

 protected:

  static julong _physical_memory;
  static pthread_t _main_thread;
  static Mutex* _createThread_lock;
  static int _page_size;

  static julong available_memory();
  static julong physical_memory() { return _physical_memory; }
  static void initialize_system_info();

  static void set_glibc_version(char *s)      { _glibc_version = s; }
  static void set_libpthread_version(char *s) { _libpthread_version = s; }

  static bool supports_variable_stack_size();

  static void set_is_NPTL()                   { _is_NPTL = true;  }
  static void set_is_LinuxThreads()           { _is_NPTL = false; }
  static void set_is_floating_stack()         { _is_floating_stack = true; }

 public:

  static void init_thread_fpu_state();
  static int  get_fpu_control_word();
  static void set_fpu_control_word(int fpu_control);
  static pthread_t main_thread(void)                                { return _main_thread; }
  // returns kernel thread id (similar to LWP id on Solaris), which can be
  // used to access /proc
  static pid_t gettid();
  static void set_createThread_lock(Mutex* lk)                      { _createThread_lock = lk; }
  static Mutex* createThread_lock(void)                             { return _createThread_lock; }
  static void hotspot_sigmask(Thread* thread);

  static address   initial_thread_stack_bottom(void)                { return _initial_thread_stack_bottom; }
  static uintptr_t initial_thread_stack_size(void)                  { return _initial_thread_stack_size; }
  static bool is_initial_thread(void);

  static int page_size(void)                                        { return _page_size; }
  static void set_page_size(int val)                                { _page_size = val; }

  static address   ucontext_get_pc(ucontext_t* uc);
  static intptr_t* ucontext_get_sp(ucontext_t* uc);
  static intptr_t* ucontext_get_fp(ucontext_t* uc);

  // For Analyzer Forte AsyncGetCallTrace profiling support:
  //
  // This interface should be declared in os_linux_i486.hpp, but
  // that file provides extensions to the os class and not the
  // Linux class.
  static ExtendedPC fetch_frame_from_ucontext(Thread* thread, ucontext_t* uc,
    intptr_t** ret_sp, intptr_t** ret_fp);

  // This boolean allows users to forward their own non-matching signals
  // to JVM_handle_linux_signal, harmlessly.
  static bool signal_handlers_are_installed;

  static int get_our_sigflags(int);
  static void set_our_sigflags(int, int);
  static void signal_sets_init();
  static void install_signal_handlers();
  static void set_signal_handler(int, bool);
  static bool is_sig_ignored(int sig);

  static sigset_t* unblocked_signals();
  static sigset_t* vm_signals();
  static sigset_t* allowdebug_blocked_signals();

  // For signal-chaining
  static struct sigaction *get_chained_signal_action(int sig);
  static bool chained_handler(int sig, siginfo_t* siginfo, void* context);

  // GNU libc and libpthread version strings
  static char *glibc_version()                { return _glibc_version; }
  static char *libpthread_version()           { return _libpthread_version; }

  // NPTL or LinuxThreads?
  static bool is_LinuxThreads()               { return !_is_NPTL; }
  static bool is_NPTL()                       { return _is_NPTL;  }

  // NPTL is always floating stack. LinuxThreads could be using floating
  // stack or fixed stack.
  static bool is_floating_stack()             { return _is_floating_stack; }

  static void libpthread_init();

  // Minimum stack size a thread can be created with (allowing
  // the VM to completely create the thread and enter user code)
  static size_t min_stack_allowed;

  // Return default stack size or guard size for the specified thread type
  static size_t default_stack_size(os::ThreadType thr_type);
  static size_t default_guard_size(os::ThreadType thr_type);

  static void capture_initial_stack(size_t max_size);

  // Stack overflow handling
  static bool manually_expand_stack(JavaThread * t, address addr);
  static int max_register_window_saves_before_flushing();

  // Real-time clock functions
  static void clock_init(void);

  // fast POSIX clocks support
  static void fast_thread_clock_init(void);

  static bool supports_monotonic_clock() {
    return _clock_gettime != NULL;
  }

  static int clock_gettime(clockid_t clock_id, struct timespec *tp) {
    return _clock_gettime ? _clock_gettime(clock_id, tp) : -1;
  }

  static int pthread_getcpuclockid(pthread_t tid, clockid_t *clock_id) {
    return _pthread_getcpuclockid ? _pthread_getcpuclockid(tid, clock_id) : -1;
  }

  static bool supports_fast_thread_cpu_time() {
    return _supports_fast_thread_cpu_time;
  }

  static jlong fast_thread_cpu_time(clockid_t clockid);

  // Stack repair handling

  // none present 

  // LinuxThreads work-around for 6292965
  static int safe_cond_timedwait(pthread_cond_t *_cond, pthread_mutex_t *_mutex, const struct timespec *_abstime);

  // An event is a condition variable with associated mutex.
  // (A cond_t is only usable in combination with a mutex_t.)
  class Event : public CHeapObj {
   private:
    volatile int    _count;
    volatile int _nParked ; 
    double cachePad [4] ; 
    pthread_mutex_t _mutex[1];
    pthread_cond_t  _cond[1];

   public:
    Event * FreeNext ;                  // TSM free list linkage
    int Immortal ;                         
    
   public:
    Event() {
      verify();
      int status;
      status = pthread_cond_init(_cond, NULL);
      assert_status(status == 0, status, "cond_init");
      status = pthread_mutex_init(_mutex, NULL);
      assert_status(status == 0, status, "mutex_init");
      _count = 0;
      _nParked = 0 ; 
      FreeNext = NULL ; 
      Immortal = 0 ; 
    }
    ~Event() {
      int status;
      guarantee (Immortal == 0, "invariant") ; 
      guarantee (_nParked == 0, "invariant") ; 
      status = pthread_cond_destroy(_cond);
      assert_status(status == 0, status, "cond_destroy");
      status = pthread_mutex_destroy(_mutex);
      assert_status(status == 0, status, "mutex_destroy");
    }
    // hook to check for mutex corruption:
    void verify() PRODUCT_RETURN;
    // for use in critical sections:
    void lock() {
      verify();
      int status = pthread_mutex_lock(_mutex);
      assert_status(status == 0, status,  "mutex_lock");
    }
    bool trylock() {
      verify();
      int status = pthread_mutex_trylock(_mutex);
      if (status == EBUSY) {
	return false;
      }
      assert_status(status == 0, status, "mutex_lock");
      return true;
    }
    void unlock() {
      verify();
      int status = pthread_mutex_unlock(_mutex);
      assert_status(status == 0, status, "mutex_unlock");
    }
    int timedwait(timespec* abstime) {
      verify();
      ++_nParked ; 
      int status = safe_cond_timedwait(_cond, _mutex, abstime);
      --_nParked ; 
      if (status != 0 && _nParked == 0 && WorkAroundNPTLTimedWaitHang) {
         // Beware: if the condvar is currupted by the NPTL bug but we have
         // multiple threads parked in timedwait() -- as can happen with
         // Monitor::wait() -- then we don't have much recourse.  
         // Reinitializing the condvar would likely orphan the other waiters.  
         pthread_cond_destroy (_cond) ; 
         pthread_cond_init (_cond, NULL) ; 
      }
      assert_status(status == 0 || status == EINTR || 
		    status == ETIME || status == ETIMEDOUT, 
		    status, "cond_timedwait");
      return status;
    }
    int timedwait(jlong millis) {
      timespec abst;
      Event::compute_abstime(&abst, millis);
      return timedwait(&abst);
    }
    int wait() {
      verify();
      ++_nParked ; 
      int status = pthread_cond_wait(_cond, _mutex);
      --_nParked ; 
      // for some reason, under 2.7 lwp_cond_wait() may return ETIME ...
      // Treat this the same as if the wait was interrupted
      if(status == ETIME) {
	status = EINTR;
      }
      assert_status(status == 0 || status == EINTR, status, "cond_wait");
      return status;
    }
    void signal() {
      verify();
      int status = pthread_cond_signal(_cond);
      assert_status(status == 0, status, "cond_signal");
    }
    void broadcast() {
      verify();
      int status = pthread_cond_broadcast(_cond);
      assert_status(status == 0, status, "cond_broadcast");
    }

    // TODO-FIXME: eliminate park, unpark and reset as well as interrupt_event().
    // Convert from interrupt_interrupt() to Self->ParkEvent. 

    // functions used to support monitor and interrupt
    // Note: park() may wake up spuriously. Use it in a loop.
    void park() {
      verify();
      lock();
      while (_count <= 0) {
        wait();
      }
      _count = 0;
      unlock();
    }

    int park(jlong millis) {
      verify();
      int ret = OS_TIMEOUT;
      lock();
      if (_count <= 0) {
        timedwait(millis);
      }
      if (_count > 0) {
        _count = 0;
        ret = OS_OK;
      }
      unlock();
      return ret;
    }

    void unpark() {
      verify();
      lock();
      int AnyWaiters = _nParked - _count ; 
      _count = 1;
      // Refer to the comments in os_solaris.hpp
      // Try to avoid the call to signal(), and, if possible, 
      // call signal() after dropping the lock.  
      if (AnyWaiters > 0) { 
         if (Immortal && WorkAroundNPTLTimedWaitHang == 0) { 
            unlock(); signal(); 
         } else { 
            signal(); unlock();
         }
      } else { 
         unlock(); 
      }
    }

    void reset() {
     verify();
     assert (_nParked == 0, "invariant") ; 
     _count = 0;
    }

    // utility to compute the abstime argument to timedwait:
    static struct timespec* compute_abstime(timespec* abstime, jlong millis) {
      // millis is the relative timeout time
      // abstime will be the absolute timeout time
      if (millis < 0)  millis = 0;
      struct timeval now;
      int status = gettimeofday(&now, NULL);
      assert(status == 0, "gettimeofday");
      jlong seconds = millis / 1000;
      millis %= 1000;
      if (seconds > 50000000) { // see man cond_timedwait(3T)
        seconds = 50000000;
      }
      abstime->tv_sec = now.tv_sec  + seconds;
      long       usec = now.tv_usec + millis * 1000;
      if (usec >= 1000000) {
        abstime->tv_sec += 1;
        usec -= 1000000;
      }
      abstime->tv_nsec = usec * 1000;
      return abstime;
    }
  };

  // An OSMutex is an abstraction used in the implementation of
  // ObjectMonitor; needed to abstract over the different thread
  // libraries' mutexes on Solaris.
  class OSMutex : public CHeapObj {
   private:
    #ifndef PRODUCT
    debug_only(volatile pthread_t _owner;)
    debug_only(volatile bool      _is_owned;)
    #endif
    pthread_mutex_t _mutex[1];

   public:
    OSMutex() {
      verify();
      int status = pthread_mutex_init(_mutex, NULL);
      assert_status(status == 0, status, "pthread_mutex_init");
      #ifndef PRODUCT
      debug_only(_is_owned = false;)
      #endif
    }
    ~OSMutex() {
      int status = pthread_mutex_destroy(_mutex);
      assert_status(status == 0, status, "pthread_mutex_destroy");
    }
    // for use in critical sections:
    void lock() {
      verify();
      int status = pthread_mutex_lock(_mutex);
      assert_status(status == 0, status, "pthread_mutex_lock");
      #ifndef PRODUCT
      assert(_is_owned == false, "mutex_lock should not have had owner");
      debug_only(_owner = pthread_self();)
      debug_only(_is_owned = true;)
      #endif
    }
    bool trylock() {
      verify();
      int status = pthread_mutex_trylock(_mutex);
      if (status == EBUSY)
	return false;
      assert_status(status == 0, status, "pthread_mutex_trylock");
      #ifndef PRODUCT
      debug_only(_owner = pthread_self();)
      debug_only(_is_owned = true;)
      #endif
      return true;
    }
    void unlock() {
      verify();
      #ifndef PRODUCT
      debug_only(pthread_t my_id = pthread_self();)
      assert(pthread_equal(_owner, my_id), "mutex_unlock");
      debug_only(_is_owned = false;)
      #endif
      int status = pthread_mutex_unlock(_mutex);
      assert_status(status == 0, status, "pthread_mutex_unlock");
    }

    // hook to check for mutex corruption:
    void verify() PRODUCT_RETURN;
    void verify_locked() PRODUCT_RETURN;
  };

  // Linux suspend/resume support - this helper is a shadow of its former
  // self now that low-level suspension is barely used, and old workarounds
  // for LinuxThreads are no longer needed.
  class SuspendResume {
  private:
    volatile int _suspend_action;
    // values for suspend_action:
    #define SR_NONE               (0x00)
    #define SR_SUSPEND            (0x01)  // suspend request
    #define SR_CONTINUE           (0x02)  // resume request

    volatile jint _state;
    // values for _state: + SR_NONE
    #define SR_SUSPENDED          (0x20)
  public:
    SuspendResume() { _suspend_action = SR_NONE; _state = SR_NONE; }

    int suspend_action() const     { return _suspend_action; }
    void set_suspend_action(int x) { _suspend_action = x;    }

    // atomic updates for _state
    void set_suspended()           { 
      jint temp, temp2;
      do {
	temp = _state;
	temp2 = Atomic::cmpxchg(temp | SR_SUSPENDED, &_state, temp);
      } while (temp2 != temp);
    }
    void clear_suspended()        { 
      jint temp, temp2;
      do {
	temp = _state;
	temp2 = Atomic::cmpxchg(temp & ~SR_SUSPENDED, &_state, temp);
      } while (temp2 != temp);
    }
    bool is_suspended()            { return _state & SR_SUSPENDED;       }

    #undef SR_SUSPENDED
  };
};

  
class PlatformEvent : public CHeapObj {
  private:
    double CachePad [4] ;   // increase odds that _mutex is sole occupant of cache line
    volatile int _Event ;
    volatile int _nParked ;
    pthread_mutex_t _mutex  [1] ;
    pthread_cond_t  _cond   [1] ;
    double PostPad  [2] ;  
    Thread * _Assoc ; 
    
  public:       // TODO-FIXME: make dtor private
    ~PlatformEvent() { guarantee (0, "invariant") ; }

  public:
    PlatformEvent() {
      int status;
      status = pthread_cond_init (_cond, NULL);
      assert_status(status == 0, status, "cond_init");
      status = pthread_mutex_init (_mutex, NULL);
      assert_status(status == 0, status, "mutex_init");
      _Event   = 0 ;
      _nParked = 0 ;
      _Assoc   = NULL ; 
    }
  
    // Use caution with reset() and fired() -- they may require MEMBARs
    void reset() { _Event = 0 ; } 
    int  fired() { return _Event; } 
    void park () ; 
    void unpark () ;
    int  park (jlong millis) ;
    void SetAssociation (Thread * a) { _Assoc = a ; } 
} ;

class PlatformParker : public CHeapObj {
  protected:
    pthread_mutex_t _mutex [1] ;
    pthread_cond_t  _cond  [1] ;

  public:       // TODO-FIXME: make dtor private
    ~PlatformParker() { guarantee (0, "invariant") ; }

  public:
    PlatformParker() {
      int status;
      status = pthread_cond_init (_cond, NULL);
      assert_status(status == 0, status, "cond_init");
      status = pthread_mutex_init (_mutex, NULL);
      assert_status(status == 0, status, "mutex_init");
    }
} ;
