#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "%W% %E% %U% JVM"
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

// Implementation of class atomic

inline void Atomic::store    (jbyte    store_value, jbyte*    dest) { *dest = store_value; }
inline void Atomic::store    (jshort   store_value, jshort*   dest) { *dest = store_value; }
inline void Atomic::store    (jint     store_value, jint*     dest) { *dest = store_value; }
inline void Atomic::store    (jlong    store_value, jlong*    dest) { *dest = store_value; }
inline void Atomic::store_ptr(intptr_t store_value, intptr_t* dest) { *dest = store_value; }
inline void Atomic::store_ptr(void*    store_value, void*     dest) { *(void**)dest = store_value; }

inline void Atomic::store    (jbyte    store_value, volatile jbyte*    dest) { *dest = store_value; }
inline void Atomic::store    (jshort   store_value, volatile jshort*   dest) { *dest = store_value; }
inline void Atomic::store    (jint     store_value, volatile jint*     dest) { *dest = store_value; }
inline void Atomic::store    (jlong    store_value, volatile jlong*    dest) { *dest = store_value; }
inline void Atomic::store_ptr(intptr_t store_value, volatile intptr_t* dest) { *dest = store_value; }
inline void Atomic::store_ptr(void*    store_value, volatile void*     dest) { *(void* volatile *)dest = store_value; }

inline void Atomic::inc    (volatile jint*     dest) { (void)add    (1, dest); }
inline void Atomic::inc_ptr(volatile intptr_t* dest) { (void)add_ptr(1, dest); }
inline void Atomic::inc_ptr(volatile void*     dest) { (void)add_ptr(1, dest); }

inline void Atomic::dec    (volatile jint*     dest) { (void)add    (-1, dest); }
inline void Atomic::dec_ptr(volatile intptr_t* dest) { (void)add_ptr(-1, dest); }
inline void Atomic::dec_ptr(volatile void*     dest) { (void)add_ptr(-1, dest); }


// This is the interface to the atomic instructions 
// For Sub Studio compiler - in solaris_amd64.il.
// For gcc - just below .

extern "C" jint  _Atomic_add     (jint  add_value, volatile jint*  dest, int mp);
extern "C" jlong _Atomic_add_long(jlong add_value, volatile jlong* dest, int mp);

extern "C" jint  _Atomic_xchg     (jint  exchange_value, volatile jint*  dest);
extern "C" jlong _Atomic_xchg_long(jlong exchange_value, volatile jlong* dest);

extern "C" jint  _Atomic_cmpxchg     (jint  exchange_value, volatile jint*  dest, jint  compare_value, int mp);
extern "C" jlong _Atomic_cmpxchg_long(jlong exchange_value, volatile jlong* dest, jlong compare_value, int mp);

inline jint     Atomic::add    (jint     add_value, volatile jint*     dest) {
  return _Atomic_add(add_value, dest, (int) os::is_MP());
}

inline intptr_t Atomic::add_ptr(intptr_t add_value, volatile intptr_t* dest) {
  return (intptr_t)_Atomic_add_long((jlong)add_value, (volatile jlong*)dest, (int) os::is_MP());
}

inline void*    Atomic::add_ptr(intptr_t add_value, volatile void*     dest) {
  return (void*)_Atomic_add_long((jlong)add_value, (volatile jlong*)dest, (int) os::is_MP());
}


inline jint     Atomic::xchg    (jint     exchange_value, volatile jint*     dest) {
  return _Atomic_xchg(exchange_value, dest);
}

inline intptr_t Atomic::xchg_ptr(intptr_t exchange_value, volatile intptr_t* dest) {
  return (intptr_t)_Atomic_xchg_long((jlong)exchange_value, (volatile jlong*)dest);
}

inline void*    Atomic::xchg_ptr(void*    exchange_value, volatile void*     dest) {
  return (void*)_Atomic_xchg_long((jlong)exchange_value, (volatile jlong*)dest);
}


inline jint     Atomic::cmpxchg    (jint     exchange_value, volatile jint*     dest, jint     compare_value) {
  return _Atomic_cmpxchg(exchange_value, dest, compare_value, (int) os::is_MP());
}

inline jlong    Atomic::cmpxchg    (jlong    exchange_value, volatile jlong*    dest, jlong    compare_value) {
  return _Atomic_cmpxchg_long(exchange_value, dest, compare_value, (int) os::is_MP());
}

inline intptr_t Atomic::cmpxchg_ptr(intptr_t exchange_value, volatile intptr_t* dest, intptr_t compare_value) {
  return (intptr_t)_Atomic_cmpxchg_long((jlong)exchange_value, (volatile jlong*)dest, (jlong)compare_value, (int) os::is_MP());
}

inline void*    Atomic::cmpxchg_ptr(void*    exchange_value, volatile void*     dest, void*    compare_value) {
  return (void*)_Atomic_cmpxchg_long((jlong)exchange_value, (volatile jlong*)dest, (jlong)compare_value, (int) os::is_MP());
}


#ifdef _GNU_SOURCE

// Add a lock prefix to an instruction on an MP machine
#define LOCK_IF_MP(mp) "cmp $0, " #mp "; je 1f; lock; 1: "

extern "C" {
  inline jint  _Atomic_add     (jint  add_value, volatile jint*  dest, int mp) {
    jint addend = add_value;
    __asm__ __volatile__ (LOCK_IF_MP(%3) "xaddl %0,(%2)"
                        : "=r" (addend)
                        : "0" (addend), "r" (dest), "r" (mp)
                        : "cc", "memory");
    return addend + add_value;
  }
  inline jlong _Atomic_add_long(jlong add_value, volatile jlong* dest, int mp) {
    intptr_t addend = add_value;
    __asm__ __volatile__ (LOCK_IF_MP(%3) "xaddq %0,(%2)"
                        : "=r" (addend)
                        : "0" (addend), "r" (dest), "r" (mp)
                        : "cc", "memory");
    return addend + add_value;
  }

  inline jint  _Atomic_xchg     (jint  exchange_value, volatile jint*  dest) {
    __asm__ __volatile__ ("xchgl (%2),%0"
                          : "=r" (exchange_value)
                        : "0" (exchange_value), "r" (dest)
                        : "memory");
    return exchange_value;
  }

  inline jlong _Atomic_xchg_long(jlong exchange_value, volatile jlong* dest) {
    __asm__ __volatile__ ("xchgq (%2),%0"
                        : "=r" (exchange_value)
                        : "0" (exchange_value), "r" (dest)
                        : "memory");
    return exchange_value;
  }

  inline jint  _Atomic_cmpxchg     (jint  exchange_value, volatile jint*  dest, jint  compare_value, int mp) {
    __asm__ __volatile__ (LOCK_IF_MP(%4) "cmpxchgl %1,(%3)"
                        : "=a" (exchange_value)
                        : "r" (exchange_value), "a" (compare_value), "r" (dest), "r" (mp)
                        : "cc", "memory");
    return exchange_value;
  }  

  inline jlong _Atomic_cmpxchg_long(jlong exchange_value, volatile jlong* dest, jlong compare_value, int mp) {
    __asm__ __volatile__ (LOCK_IF_MP(%4) "cmpxchgq %1,(%3)"
                        : "=a" (exchange_value)
                        : "r" (exchange_value), "a" (compare_value), "r" (dest), "r" (mp)
                        : "cc", "memory");
    return exchange_value;
  }
}
#undef LOCK_IF_MP

#endif
