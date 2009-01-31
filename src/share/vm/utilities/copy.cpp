#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)copy.cpp	1.2 07/04/13 10:35:00 JVM"
#endif
/*
 * Copyright 2006 Sun Microsystems, Inc.  All rights reserved.
 * SUN PROPRIETARY/CONFIDENTIAL.  Use is subject to license terms.
 */

# include "incls/_precompiled.incl"
# include "incls/_copy.cpp.incl"


// Copy bytes; larger units are filled atomically if everything is aligned.
void Copy::conjoint_memory_atomic(void* from, void* to, size_t size) {
  address src = (address) from;
  address dst = (address) to;
  uintptr_t bits = (uintptr_t) src | (uintptr_t) dst | (uintptr_t) size;

  // (Note:  We could improve performance by ignoring the low bits of size,
  // and putting a short cleanup loop after each bulk copy loop.
  // There are plenty of other ways to make this faster also,
  // and it's a slippery slope.  For now, let's keep this code simple
  // since the simplicity helps clarify the atomicity semantics of
  // this operation.  There are also CPU-specific assembly versions
  // which may or may not want to include such optimizations.)

  if (bits % sizeof(jlong) == 0) {
    Copy::conjoint_jlongs_atomic((jlong*) src, (jlong*) dst, size / sizeof(jlong));
  } else if (bits % sizeof(jint) == 0) {
    Copy::conjoint_jints_atomic((jint*) src, (jint*) dst, size / sizeof(jint));
  } else if (bits % sizeof(jshort) == 0) {
    Copy::conjoint_jshorts_atomic((jshort*) src, (jshort*) dst, size / sizeof(jshort));
  } else {
    // Not aligned, so no need to be atomic.
    Copy::conjoint_bytes((void*) src, (void*) dst, size);
  }
}


// Fill bytes; larger units are filled atomically if everything is aligned.
void Copy::fill_to_memory_atomic(void* to, size_t size, jubyte value) {
  address dst = (address) to;
  uintptr_t bits = (uintptr_t) to | (uintptr_t) size;
  if (bits % sizeof(jlong) == 0) {
    jlong fill = (julong)( (jubyte)value ); // zero-extend
    if (fill != 0) {
      fill += fill << 8;
      fill += fill << 16;
      fill += fill << 32;
    }
    //Copy::fill_to_jlongs_atomic((jlong*) dst, size / sizeof(jlong));
    for (uintptr_t off = 0; off < size; off += sizeof(jlong)) {
      *(jlong*)(dst + off) = fill;
    }
  } else if (bits % sizeof(jint) == 0) {
    jint fill = (juint)( (jubyte)value ); // zero-extend
    if (fill != 0) {
      fill += fill << 8;
      fill += fill << 16;
    }
    //Copy::fill_to_jints_atomic((jint*) dst, size / sizeof(jint));
    for (uintptr_t off = 0; off < size; off += sizeof(jint)) {
      *(jint*)(dst + off) = fill;
    }
  } else if (bits % sizeof(jshort) == 0) {
    jshort fill = (jushort)( (jubyte)value ); // zero-extend
    fill += fill << 8;
    //Copy::fill_to_jshorts_atomic((jshort*) dst, size / sizeof(jshort));
    for (uintptr_t off = 0; off < size; off += sizeof(jshort)) {
      *(jshort*)(dst + off) = fill;
    }
  } else {
    // Not aligned, so no need to be atomic.
    Copy::fill_to_bytes(dst, size, value);
  }
}
