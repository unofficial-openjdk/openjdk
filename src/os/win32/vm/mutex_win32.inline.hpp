#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)mutex_win32.inline.hpp	1.18 07/05/05 17:04:44 JVM"
#endif
/*
 * Copyright 1998-2003 Sun Microsystems, Inc.  All Rights Reserved.
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
  return InterlockedIncrement((long *) &_lock_count)==0;
}


inline bool Mutex::try_lock_implementation() {
  // We can only get the lock, if we can atomicly increase the _lock_count 
  // from -1 to 0. Hence, this is like lock_implementation, except that we
  // only count if the initial value is -1.
  return (Atomic::cmpxchg(0, &_lock_count, -1) == -1);
}
