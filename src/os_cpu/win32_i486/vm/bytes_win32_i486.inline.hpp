#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)bytes_win32_i486.inline.hpp	1.14 07/05/05 17:04:56 JVM"
#endif
/*
 * Copyright 1998 Sun Microsystems, Inc.  All Rights Reserved.
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

#pragma warning(disable: 4035) // Disable warning 4035: no return value

// Efficient swapping of data bytes from Java byte
// ordering to native byte ordering and vice versa.
inline u2 Bytes::swap_u2(u2 x) {
  __asm {
    mov ax, x
    xchg al, ah
  }
  // no return statement needed, result is already in ax
  // compiler warning C4035 disabled via warning pragma
}

inline u4 Bytes::swap_u4(u4 x) {
  __asm {
    mov eax, x
    bswap eax
  }
  // no return statement needed, result is already in eax
  // compiler warning C4035 disabled via warning pragma
}

// Helper function for swap_u8
inline u8 Bytes::swap_u8_base(u4 x, u4 y) {
  __asm {
    mov eax, y
    mov edx, x
    bswap eax
    bswap edx
  }
  // no return statement needed, result is already in edx:eax
  // compiler warning C4035 disabled via warning pragma
}

#pragma warning(default: 4035) // Enable warning 4035: no return value
