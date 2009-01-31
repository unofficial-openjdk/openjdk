#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)prefetch_solaris_amd64.inline.hpp	1.8 07/05/05 17:04:50 JVM"
#endif
/*
 * Copyright 2004-2007 Sun Microsystems, Inc.  All Rights Reserved.
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

// For Sun Studio - implementation is in solaris_amd64.il.
// For gcc - implementation is just below.

extern "C" {
  void _Prefetch_read (void *loc, intx interval);
  void _Prefetch_write(void *loc, intx interval);
}

inline void Prefetch::read (void *loc, intx interval) {
  _Prefetch_read(loc, interval);
}

// Use of this method should be gated by VM_Version::has_prefetchw.
inline void Prefetch::write(void *loc, intx interval) {
  _Prefetch_write(loc, interval);
}


#ifdef _GNU_SOURCE
extern "C" {
  inline void _Prefetch_read (void *loc, intx interval) {
  __asm__ ("prefetcht0 (%0,%1,1)" : : "r" (loc), "r" (interval));
  }

  // Use of this method should be gated by VM_Version::has_prefetchw.
  inline void _Prefetch_write(void *loc, intx interval) {
  // Do not use the 3dnow prefetchw instruction.  It isn't supported on em64t.
  //  __asm__ ("prefetchw (%0,%1,1)" : : "r" (loc), "r" (interval));
  __asm__ ("prefetcht0 (%0,%1,1)" : : "r" (loc), "r" (interval));
  }
}
#endif  //_GNU_SOURCE
