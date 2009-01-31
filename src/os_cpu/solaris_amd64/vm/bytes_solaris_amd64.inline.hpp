#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)bytes_solaris_amd64.inline.hpp	1.8 07/05/05 17:04:49 JVM"
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
  u2 _raw_swap_u2(u2 x);
  u4 _raw_swap_u4(u4 x);
  u8 _raw_swap_u8(u8 x);
}

// Efficient swapping of data bytes from Java byte
// ordering to native byte ordering and vice versa.
inline u2   Bytes::swap_u2(u2 x) {
  return _raw_swap_u2(x);
}

inline u4   Bytes::swap_u4(u4 x) {
  return _raw_swap_u4(x);
}

inline u8   Bytes::swap_u8(u8 x) {
  return _raw_swap_u8(x);
}

#ifdef _GNU_SOURCE
extern "C" {
  inline u2 _raw_swap_u2(u2 x) {
    register unsigned short int __dest;
    __asm__ ("rorw $8, %w0": "=r" (__dest): "0" (x): "cc");
    return __dest; 
  }
  inline u4 _raw_swap_u4(u4 x) {
    register unsigned int __dest;
    __asm__ ("bswap %0" : "=r" (__dest) : "0" (x));
    return __dest;
  }
  inline u8 _raw_swap_u8(u8 x) {
    register unsigned long  __dest;
    __asm__ ("bswap %q0" : "=r" (__dest) : "0" (x));
    return __dest;
  }
}
#endif
