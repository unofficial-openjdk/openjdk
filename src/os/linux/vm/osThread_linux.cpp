#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)osThread_linux.cpp	1.24 07/05/05 17:04:36 JVM"
#endif
/*
 * Copyright 1999-2004 Sun Microsystems, Inc.  All Rights Reserved.
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

// do not include  precompiled  header file
# include "incls/_osThread_linux.cpp.incl"


// Events associated with threads via "interrupt_event" must
// reside in a TSM (type-stable memory) pool.  
// The relationship between the interrupt_event and a thread
// must be stable for the lifetime of the thread.  
//
// A slightly better implementation would be to subclass Event
// with a "TSMEvent" that added the FreeNext field.  
 
static os::Linux::Event * EventFreeList = NULL ;     
static pthread_mutex_t EventFreeLock = PTHREAD_MUTEX_INITIALIZER ;
 
void OSThread::pd_initialize() {
  assert(this != NULL, "check");
  _thread_id        = 0;
  _pthread_id       = 0;
  _siginfo = NULL;
  _ucontext = NULL;
  _expanding_stack = 0;
  _alt_sig_stack = NULL;

  sigemptyset(&_caller_sigmask);

  // Try to allocate an Event from the TSM list, otherwise
  // instantiate a new Event.
  pthread_mutex_lock (&EventFreeLock) ;
  os::Linux::Event * ie = EventFreeList ;
  if (ie != NULL) {
     guarantee (ie->Immortal, "invariant") ;
     EventFreeList = ie->FreeNext ;
  }
  pthread_mutex_unlock (&EventFreeLock) ;
  if (ie == NULL) {
     ie = new os::Linux::Event();
  } else { 
     ie->reset () ;
  }
  ie->FreeNext = (os::Linux::Event *) 0xBAD ;
  ie->Immortal = 1 ;
  _interrupt_event = ie ;

  _startThread_lock = new Monitor(Mutex::event, "startThread_lock", true);
  assert(_startThread_lock !=NULL, "check");
}

void OSThread::pd_destroy() {
  os::Linux::Event * ie = _interrupt_event ;
  _interrupt_event = NULL ;
  guarantee (ie != NULL, "invariant") ;
  guarantee (ie->Immortal, "invariant") ;
  pthread_mutex_lock (&EventFreeLock) ;
  ie->FreeNext = EventFreeList ;
  EventFreeList = ie ;
  pthread_mutex_unlock (&EventFreeLock) ;

  delete _startThread_lock;
}
