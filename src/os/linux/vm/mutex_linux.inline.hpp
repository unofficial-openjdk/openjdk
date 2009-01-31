#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)mutex_linux.inline.hpp	1.12 07/05/05 17:04:36 JVM"
#endif
/*
 * Copyright 1999-2002 Sun Microsystems, Inc.  All Rights Reserved.
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

inline bool Mutex::lock_implementation() {
  int status = ((os::Linux::Event*)_lock_event)->trylock();
  if (status != 0) { 
    debug_only(_lock_count++); 
    return true; 
  } 
  return false;
}

inline bool Mutex::try_lock_implementation() {
  // Same on Linux.
  return lock_implementation();
}


inline void Mutex::wait_for_lock_implementation() {
  assert(!owned_by_self(), "deadlock");
  ((os::Linux::Event*)_lock_event)->lock();
  debug_only(_lock_count++;)
}

// Reconciliation History
// mutex_solaris.inline.hpp	1.5 99/06/22 16:38:49
// End
