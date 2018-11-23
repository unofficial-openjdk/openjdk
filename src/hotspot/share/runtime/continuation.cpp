/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#include "precompiled.hpp"
#include "classfile/javaClasses.inline.hpp"
#include "classfile/vmSymbols.hpp"
#include "code/compiledMethod.inline.hpp"
#include "code/scopeDesc.hpp"
#include "code/vmreg.inline.hpp"
#include "compiler/oopMap.hpp"
#include "compiler/oopMap.inline.hpp"
#include "jfr/jfrEvents.hpp"
#include "gc/shared/memAllocator.hpp"
#include "gc/shared/threadLocalAllocBuffer.inline.hpp"
#include "interpreter/interpreter.hpp"
#include "interpreter/linkResolver.hpp"
#include "interpreter/oopMapCache.hpp"
#include "logging/log.hpp"
#include "logging/logStream.hpp"
#include "oops/access.inline.hpp"
#include "oops/objArrayOop.inline.hpp"
#include "prims/jvmtiThreadState.hpp"
#include "runtime/continuation.hpp"
#include "runtime/deoptimization.hpp"
#include "runtime/interfaceSupport.inline.hpp"
#include "runtime/frame.hpp"
#include "runtime/frame.inline.hpp"
#include "runtime/javaCalls.hpp"
#include "runtime/jniHandles.inline.hpp"
#include "runtime/sharedRuntime.hpp"
#include "runtime/vframe_hp.hpp"
#include "utilities/copy.hpp"
#include "utilities/exceptions.hpp"
#include "utilities/macros.hpp"

#ifdef __has_include
#  if __has_include(<valgrind/callgrind.h>)
#    include <valgrind/callgrind.h>
#  endif
#endif
#ifdef CALLGRIND_START_INSTRUMENTATION
  static int callgrind_counter = 1;
  static void callgrind() {
    if (callgrind_counter != 0) {
      if (callgrind_counter > 20000) {
        tty->print_cr("Starting callgrind instrumentation");
        CALLGRIND_START_INSTRUMENTATION;
        callgrind_counter = 0;
      } else
        callgrind_counter++;
    }
  }
#else
  static void callgrind() {};
#endif

// #undef ASSERT
// #undef assert
// #define assert(p, ...)

int Continuation::PERFTEST_LEVEL = ContPerfTest;
// Freeze:
// 5 - no call into C
// 10 - immediate return from C
// 15 - return after count_frames
// 20 - all work, but no copying
// 25 - copy to stack
// 30 - freeze oops
// 100 - everything
//
// Thaw:
// 105 - no call into C (prepare_thaw)
// 110 - immediate return from C (prepare_thaw)
//
// TODO
//
// !!! Keep an eye out for deopt, and patch_pc
//
// Add:
//  - method/nmethod metadata
//  - Precise monitor detection
//  - Exceptions in lazy-copy
//  - stack walking in lazy-copy
//  - compress interpreted frames
//  - special native methods: Method.invoke, doPrivileged (+ method handles)
//  - compiled->intrepreted for serialization (look at scopeDesc)
//  - caching h-stacks in thread stacks
//
// Things to compress in interpreted frames: return address, monitors, last_sp
//
// See: deoptimization.cpp, vframeArray.cpp, abstractInterpreter_x86.cpp

#define YIELD_SIG  "java.lang.Continuation.yield(Ljava/lang/ContinuationScope;)V"
#define ENTER_SIG  "java.lang.Continuation.enter()V"
#define ENTER0_SIG "java.lang.Continuation.enter0()V"
#define RUN_SIG    "java.lang.Continuation.run()V"

static void print_oop(void *p, oop obj, outputStream* st = tty);
static void print_vframe(frame f, const RegisterMap* map = NULL, outputStream* st = tty);
static void print_frames(JavaThread* thread, outputStream* st = tty);
#ifdef ASSERT
static VMReg find_register_spilled_here(void* p, RegisterMap* map);
// static void print_JavaThread_offsets();
#endif
// NOT_PRODUCT(static void trace_codeblob_maps(const frame *fr, const RegisterMap *reg_map);)

#define HOB (1ULL << 63)

struct HFrameMetadata {
  int num_oops;
  unsigned short frame_size;
  unsigned short uncompressed_size;
};

#define METADATA_SIZE sizeof(HFrameMetadata) // bytes

#define ELEM_SIZE sizeof(jint) // stack is int[]
static inline int to_index(size_t x) { return x >> 2; } // stack is int[]
static inline int to_bytes(int x)    { return x << 2; } // stack is int[]

#define METADATA_ELEMENTS (METADATA_SIZE / ELEM_SIZE)

static const unsigned char FLAG_LAST_FRAME_INTERPRETED = 1;


// static inline HFrameMetadata* metadata(intptr_t* hsp) {
//   return (HFrameMetadata*)((address)hsp - METADATA_SIZE);
// }

// static inline intptr_t* to_haddress(const void* base, const int index) {
//   return (intptr_t*)((address)base + to_bytes(index));
// }

static inline int to_index(void* base, void* ptr) {
  return to_index((char*)ptr - (char*)base);
}

static oop get_continuation(JavaThread* thread) {
  return java_lang_Thread::continuation(thread->threadObj());
}

static long java_tid(JavaThread* thread) {
  return java_lang_Thread::thread_id(thread->threadObj());
}

// static void set_continuation(JavaThread* thread, oop cont) {
//   java_lang_Thread::set_continuation(thread->threadObj(), cont);
// }

class ContinuationCodeBlobLookup {
public:
  static CodeBlob* find_blob(address pc) {
    NativePostCallNop* nop = nativePostCallNop_at(pc);
    if (nop != NULL) {
      Continuations::nmethod_hit();
      bool patched = false;
      CodeBlob* cb;
      if (nop->displacement() != 0) {
        int offset = nop->displacement();
        cb = (CodeBlob*) ((address) pc - offset);
      } else {
        cb = CodeCache::find_blob(pc);
        intptr_t cbaddr = (intptr_t) cb;
        intptr_t offset = ((intptr_t) pc) - cbaddr;
        nop->patch((jint) offset);
        patched = true;
      }
      //log_info(jvmcont)("found nop, cb @ " INTPTR_FORMAT " - %s", p2i(cb), patched ? "patched" : "existing");
      assert(cb != NULL, "must be");
      //assert(cb == CodeCache::find_blob(pc), "double check");
      return cb;
    } else {
      CodeBlob* cb = CodeCache::find_blob(pc);
      if (cb->is_nmethod()) {
        Continuations::nmethod_miss();
      }
      //log_info(jvmcont)("failed to find nop in cb " INTPTR_FORMAT ", %d, %d", p2i(cb), cb->is_compiled_by_c1(), cb->is_compiled_by_c2());
      return cb;
    }
  }
};

class ContMirror;

// Represents a stack frame on the horizontal stack, analogous to the frame class, for vertical-stack frames.
class hframe {
private:
  int _sp;
  long _fp;
  address _pc;
  CodeBlob* _cb;
  bool _is_interpreted;
  long* _link_address;
  // int _ref_length;

  friend class ContMirror;
private:
  inline HFrameMetadata* meta(const ContMirror& cont) const;
  inline intptr_t* real_fp(const ContMirror& cont) const;
  inline int real_fp_index(const ContMirror& cont) const;
  inline int link_index(const ContMirror& cont) const;
  inline address* return_pc_address(const ContMirror& cont) const;

  void set_codeblob(address pc) {
    if (_cb == NULL && !_is_interpreted) {// compute lazily
      ContinuationCodeBlobLookup lookup;
      _cb = lookup.find_blob(_pc);
      assert(_cb != NULL, "must be valid");
    }
  }

public:
  hframe() : _sp(-1), _fp(0), _pc(NULL), _cb(NULL), _is_interpreted(true), _link_address(NULL) {}
  hframe(const hframe& hf) : _sp(hf._sp), _fp(hf._fp), _pc(hf._pc), _cb(hf._cb), 
                             _is_interpreted(hf._is_interpreted), _link_address(hf._link_address) {}

  hframe(int sp, long fp, address pc, ContMirror& cont)
    : _sp(sp), _fp(fp), _pc(pc), 
      _is_interpreted(Interpreter::contains(pc)), _link_address(NULL) {
      _cb = NULL;
      set_codeblob(_pc);
      set_link_address(cont);
    }
  hframe(int sp, long fp, address pc, CodeBlob* cb, bool is_interpreted, ContMirror& cont)
    : _sp(sp), _fp(fp), _pc(pc), _cb(cb), 
      _is_interpreted(is_interpreted), _link_address(NULL) {
      set_link_address(cont);
    }
  hframe(int sp, long fp, address pc, bool is_interpreted, ContMirror& cont)
    : _sp(sp), _fp(fp), _pc(pc), 
      _is_interpreted(is_interpreted), _link_address(NULL) {
      _cb = NULL;
      set_codeblob(_pc);
      set_link_address(cont);
    }
  hframe(int sp, long fp, address pc, CodeBlob* cb, bool is_interpreted, ContMirror& cont, bool dummy) // called by ContMirror::new_hframe
    : _sp(sp), _fp(fp), _pc(pc), _cb(cb), 
      _is_interpreted(is_interpreted), _link_address(NULL) {
    }

  bool operator==(const hframe& other) { 
    return _sp == other._sp && _fp == other._fp && _pc == other._pc; 
  }

  bool is_empty() const { return _pc == NULL && _sp < 0; }

  inline bool is_interpreted_frame() const { return _is_interpreted; }
  inline int       sp() const { return _sp; }
  inline long      fp() const { return _fp; }
  inline address   pc() const { return _pc; }
  CodeBlob* cb() const { return _cb; }

  inline void set_fp(long fp) { _fp = fp; }

  size_t size(const ContMirror& cont) const { return meta(cont)->frame_size; }
  size_t uncompressed_size(const ContMirror& cont) const { return meta(cont)->uncompressed_size; }
  int num_oops(const ContMirror& cont) const { return meta(cont)->num_oops; }

  void set_size(ContMirror& cont, size_t size);              //{ assert(size < 0xffff, ""); meta(cont)->frame_size = size; }
  void set_num_oops(ContMirror& cont, int num);              //{ assert(num  < 0xffff, ""); meta(cont)->num_oops = num; }
  void set_uncompressed_size(ContMirror& cont, size_t size); //{ assert(size < 0xffff, ""); meta(cont)->uncompressed_size = size; }

  // the link is an offset from the real fp to the sender's fp IFF the sender is interpreted
  // otherwise, it's the contents of the rbp register
  long* link_address() const { return _link_address; }
  long link()         { return *link_address(); }
  address return_pc(ContMirror& cont) { return *return_pc_address(cont); }

  template<bool interpreted> void set_link_address(const ContMirror& cont);

  void set_link_address(const ContMirror& cont)  { if (_is_interpreted) set_link_address<true>(cont); else set_link_address<false>(cont); }

  hframe sender(ContMirror& cont);

  void patch_link(long value) { 
    long* la = link_address();
    *la = value;
  }

  void zero_link() {
    long* la = link_address();
    *la = 0;
  }

  inline void patch_link_relative(intptr_t* fp);
  inline void patch_sender_sp_relative(ContMirror& cont, intptr_t* value);

  inline void patch_callee(ContMirror& cont, hframe& sender);

  intptr_t* interpreter_frame_metadata_at(const ContMirror& cont, int offset) const;
  intptr_t* interpreter_frame_local_at(ContMirror& cont, int index) const;
  intptr_t* interpreter_frame_expression_stack_at(ContMirror& cont, int offset) const;

  inline void patch_return_pc(ContMirror& cont, address value);
  inline void patch_real_fp_offset(int offset, intptr_t value);
  inline intptr_t* get_real_fp_offset(int offset) { return (intptr_t*)*(link_address() + offset); }

  bool is_bottom(const ContMirror& cont) const;

  inline intptr_t* index_address(const ContMirror& cont, int i) const;

  Method* method(ContMirror& cont);

  inline frame to_frame(ContMirror& cont);

  void print_on(ContMirror& cont, outputStream* st);
  void print(ContMirror& cont) { print_on(cont, tty); }
  void print_on(outputStream* st);
  void print() { print_on(tty); }
};

class FreezeContinuation;

class HStackFrameDescriptor {
private:
  size_t _size; // nr of bytes for this frame

  address _pc;
  CodeBlob* _cb;
  bool _interpreted;

  intptr_t* _fp_value; // for compiled frames this is the content of rbp / fp register
  intptr_t* _link_offset; // link offset for interpreted frames

public:
  HStackFrameDescriptor() : _size(0) {}
  HStackFrameDescriptor(size_t size, address pc, CodeBlob* cb, bool interpreted, intptr_t* fp_value, intptr_t* link_offset) : _size(size), _pc(pc), _cb(cb), _interpreted(interpreted), _fp_value(fp_value), _link_offset(link_offset) {}
  bool empty() const { return _size <= METADATA_SIZE; } // used to be > 0 but since every frame has a METADATA_SIZE...
  template<bool interpreted> hframe create_hframe(FreezeContinuation* freeze, ContMirror& mirror, intptr_t *hsp);

  size_t index_to_addr_offset(size_t index) const {
    return sizeof(void*) * index;
  }

  bool interpreted() const { return _interpreted; }
};

intptr_t* hframe::interpreter_frame_metadata_at(const ContMirror& cont, int offset) const {
  intptr_t* fp = index_address(cont, _fp);
  return fp + offset;
}

intptr_t* hframe::interpreter_frame_local_at(ContMirror& cont, int index) const {
  intptr_t* fp = index_address(cont, _fp);
  const int n = Interpreter::local_offset_in_bytes(index)/wordSize;
  intptr_t* locals = (intptr_t*)((address)fp + to_bytes(*(long*)(fp + frame::interpreter_frame_locals_offset)));
  intptr_t* loc = &(locals[n]); // derelativize

  // tty->print_cr("interpreter_frame_local_at: %d (%p, %ld, %ld) fp: %ld sp: %d, n: %d fp: %p", index, loc, loc - index_address(cont, _sp), loc - fp, _fp, _sp, n, fp);  
  return loc;
}

// static bool is_in_expression_stack(const frame& fr, const intptr_t* const addr) {
//   assert(addr != NULL, "invariant");

//   // Ensure to be 'inside' the expresion stack (i.e., addr >= sp for Intel).
//   // In case of exceptions, the expression stack is invalid and the sp
//   // will be reset to express this condition.
//   if (frame::interpreter_frame_expression_stack_direction() > 0) {
//     return addr <= fr.interpreter_frame_tos_address();
//   }

//   return addr >= fr.interpreter_frame_tos_address();
// }

intptr_t* hframe::interpreter_frame_expression_stack_at(ContMirror& cont, int offset) const {
  intptr_t* fp = index_address(cont, _fp);
  intptr_t* monitor_end = (intptr_t*)((address)fp + to_bytes(*(long*)(fp + frame::interpreter_frame_monitor_block_top_offset))); // derelativize
  intptr_t* expression_stack = monitor_end-1;

  const int i = offset * frame::interpreter_frame_expression_stack_direction();
  const int n = i * Interpreter::stackElementWords;
  return &(expression_stack[n]);
}

// freeze result
enum res_freeze {
  freeze_ok = 0,
  freeze_pinned_native = 1,
  freeze_pinned_monitor = 2,
  freeze_exception = 3
};

// struct oopLoc {
//   bool narrow  : 1;
//   unsigned long loc : 63;
// };

// Mirrors the Java continuation objects.
// This object is created when we begin a freeze/thaw operation for a continuation, and is destroyed when the operation completes.
// Contents are read from the Java object at the entry points of this module, and written at exists or intermediate calls into Java
class ContMirror {
private:
  JavaThread* const _thread;
  oop _cont;
  intptr_t* _entrySP;
  intptr_t* _entryFP;
  address _entryPC;

  int  _sp;
  long _fp;
  address _pc;

  typeArrayOop _stack;
  int _stack_length;
  int* _hstack;

  size_t _max_size;

  int _ref_sp;
  objArrayOop  _ref_stack;

  unsigned char _flags;

  short _num_interpreted_frames;
  short _num_frames;

  // Profiling data for the JFR event
  short _e_num_interpreted_frames;
  short _e_num_frames;
  short _e_num_refs;
  short _e_size;

  ContMirror(const ContMirror& cont); // no copy constructor

  int* stack() const { return _hstack; }

  int ensure_capacity(int old, int min);

  oop raw_allocate(Klass* klass, const size_t words, const size_t elements, bool zero);
  oop allocate_stack_array(const size_t elements);
  oop allocate_refstack_array(const size_t nr_oops);

  bool allocate_stack(int size);
  bool allocate_ref_stack(int nr_oops);
  bool allocate_stacks_in_native(int size, int oops, bool needs_stack, bool needs_refstack);
  void allocate_stacks_in_java(int size, int oops, int frames);
  int fix_decreasing_index(int index, int old_length, int new_length) const;
  bool grow_stack(int new_size);
  bool grow_ref_stack(int nr_oops);

public:
  ContMirror(JavaThread* thread, oop cont);

  void cleanup();

  oop mirror() { return _cont; }
  DEBUG_ONLY(intptr_t hash() { return _cont->identity_hash(); })
  void read();
  void write();

  intptr_t* entrySP() { return _entrySP; }
  intptr_t* entryFP() { return _entryFP; }
  address   entryPC() { return _entryPC; }

  void set_entrySP(intptr_t* sp) { _entrySP = sp; }
  void set_entryFP(intptr_t* fp) { _entryFP = fp; }
  void set_entryPC(address pc)   {
    log_trace(jvmcont)("set_entryPC " INTPTR_FORMAT, p2i(pc));
    _entryPC = pc;
  }

  int sp() const           { return _sp; }
  long fp() const          { return _fp; }
  address pc() const       { return _pc; }

  void set_sp(int sp)      { _sp = sp;   }
  void set_fp(long fp)     { _fp = fp;   }
  void set_pc(address pc)  { _pc = pc; set_flag(FLAG_LAST_FRAME_INTERPRETED, Interpreter::contains(pc));  }

  bool is_flag(unsigned char flag) { return (_flags & flag) != 0; }
  void set_flag(unsigned char flag, bool v) { _flags = (v ? _flags |= flag : _flags &= ~flag); }

  int stack_length() const { return _stack_length; }

  JavaThread* thread() const { return _thread; }

  void allocate_stacks(int size, int oops, int frames);
  inline bool in_hstack(void *p) { return (_hstack != NULL && p >= _hstack && p < (_hstack + _stack_length)); }

  bool valid_stack_index(int idx) const { return idx >= 0 && idx < _stack_length; }

  void copy_to_stack(void* from, void* to, int size);
  void copy_from_stack(void* from, void* to, int size);

  objArrayOop refStack(int size);
  objArrayOop refStack() { return _ref_stack; }
  int refSP() { return _ref_sp; }
  void set_refSP(int refSP) { log_trace(jvmcont)("set_refSP: %d", refSP); _ref_sp = refSP; }

  typeArrayOop stack(int size);
  inline int stack_index(void* p) const;
  inline intptr_t* stack_address(int i) const;

  // void call_pinned(res_freeze res, frame& f);

  void update_register_map(RegisterMap& map);
  bool is_map_at_top(RegisterMap& map);

  bool is_empty();

  //hframe new_hframe(int hsp_offset, int hfp_offset, address pc, CodeBlob* cb, bool is_interpreted);
  template <bool interpreted> hframe new_hframe(intptr_t* hsp, intptr_t* hfp, address pc, CodeBlob* cb, bool is_interpreted);
  hframe last_frame();
  inline void set_last_frame(hframe& f);

  hframe from_frame(const frame& f);

  inline int add_oop(oop obj, int index);
  // inline void add_oop_location(oop* p);
  // inline void add_oop_location(narrowOop* p);

  inline oop obj_at(int i);
  int num_oops();
  void null_ref_stack(int start, int num);

  inline size_t max_size() { return _max_size; }
  inline void add_size(size_t s) { log_trace(jvmcont)("add max_size: " SIZE_FORMAT " s: " SIZE_FORMAT, _max_size + s, s);
                                   _max_size += s; }
  inline void sub_size(size_t s) { log_trace(jvmcont)("sub max_size: " SIZE_FORMAT " s: " SIZE_FORMAT, _max_size - s, s);
                                   assert(s <= _max_size, "s: " SIZE_FORMAT " max_size: " SIZE_FORMAT, s, _max_size);
                                   _max_size -= s; }
  inline short num_interpreted_frames() { return _num_interpreted_frames; }
  inline void inc_num_interpreted_frames() { _num_interpreted_frames++; _e_num_interpreted_frames++; }
  inline void dec_num_interpreted_frames() { _num_interpreted_frames--; _e_num_interpreted_frames++; }

  inline short num_frames() { return _num_frames; }
  inline void inc_num_frames() { _num_frames++; _e_num_frames++; }
  inline void dec_num_frames() { _num_frames--; _e_num_frames++; }

  void print_hframes(outputStream* st = tty);

  inline void e_inc_refs() { _e_num_refs++; }
  template<typename Event> void post_jfr_event(Event *e);
};

template<bool interp>
hframe HStackFrameDescriptor::create_hframe(FreezeContinuation* freeze, ContMirror& mirror, intptr_t *hsp) {
  assert(!empty(), "never");
  assert(interp == _interpreted, "");
  return mirror.new_hframe<interp>(hsp, interp ? (hsp + (long) _link_offset) : _fp_value, _pc, _cb, interp);
}

inline frame hframe::to_frame(ContMirror& cont) {
  bool deopt = false;
  address pc = _pc;
  if (!is_interpreted_frame()) {
    CompiledMethod* cm = cb()->as_compiled_method_or_null();
    if (cm != NULL && cm->is_deopt_pc(pc)) {
      intptr_t* hsp = cont.stack_address(sp());
      address orig_pc = *(address*) ((address)hsp + cm->orig_pc_offset());
      assert (orig_pc != pc, "");
      assert (orig_pc != NULL, "");

      pc = orig_pc;
      deopt = true;
    }
  }

  return frame(reinterpret_cast<intptr_t*>(_sp), reinterpret_cast<intptr_t*>(_sp), reinterpret_cast<intptr_t*>(_fp), pc, 
              _cb != NULL ? _cb : (_cb = CodeCache::find_blob(_pc)),
              deopt);
}

void hframe::print_on(outputStream* st) {
  if (is_empty()) {
    st->print_cr("\tempty");
  } else if (_is_interpreted) {
    st->print_cr("\tInterpreted sp: %d fp: %ld pc: " INTPTR_FORMAT, _sp, _fp, p2i(_pc));
  } else {
    st->print_cr("\tCompiled sp: %d fp: 0x%lx pc: " INTPTR_FORMAT, _sp, _fp, p2i(_pc));
  }
}

void hframe::print_on(ContMirror& cont, outputStream* st) {
  print_on(st);
  if (is_empty())
    return;

  st->print_cr("\tMetadata size: %d num_oops: %d", meta(cont)->frame_size, meta(cont)->num_oops);

  if (_is_interpreted) {
    intptr_t* fp = index_address(cont, _fp);
    Method** method_addr = (Method**)(fp + frame::interpreter_frame_method_offset);
    Method* method = *method_addr;
    st->print_cr("\tmethod: " INTPTR_FORMAT " (at " INTPTR_FORMAT ")", p2i(method), p2i(method_addr));
    st->print("\tmethod: "); method->print_short_name(st); st->cr();

    st->print_cr("\tissp: %ld",             *(long*) (fp + frame::interpreter_frame_sender_sp_offset));
    st->print_cr("\tlast_sp: %ld",          *(long*) (fp + frame::interpreter_frame_last_sp_offset));
    st->print_cr("\tinitial_sp: %ld",       *(long*) (fp + frame::interpreter_frame_initial_sp_offset));
    // st->print_cr("\tmon_block_top: %ld",    *(long*) (fp + frame::interpreter_frame_monitor_block_top_offset));
    // st->print_cr("\tmon_block_bottom: %ld", *(long*) (fp + frame::interpreter_frame_monitor_block_bottom_offset));
    st->print_cr("\tlocals: %ld",           *(long*) (fp + frame::interpreter_frame_locals_offset));
    st->print_cr("\tcache: " INTPTR_FORMAT, p2i(*(void**)(fp + frame::interpreter_frame_cache_offset)));
    st->print_cr("\tbcp: " INTPTR_FORMAT,   p2i(*(void**)(fp + frame::interpreter_frame_bcp_offset)));
    st->print_cr("\tbci: %d",               method->bci_from(*(address*)(fp + frame::interpreter_frame_bcp_offset)));
    st->print_cr("\tmirror: " INTPTR_FORMAT, p2i(*(void**)(fp + frame::interpreter_frame_mirror_offset)));
    // st->print("\tmirror: "); os::print_location(st, *(intptr_t*)(fp + frame::interpreter_frame_mirror_offset), true);
  } else {
    st->print_cr("\tcb: " INTPTR_FORMAT, p2i(cb()));
    if (_cb != NULL) {
      st->print("\tcb: "); _cb->print_value_on(st); st->cr();
      st->print_cr("\tcb.frame_size: %d", _cb->frame_size());
    }
  }
  st->print_cr("\tlink: 0x%lx %ld (at: " INTPTR_FORMAT ")", link(), link(), p2i(link_address()));
  st->print_cr("\treturn_pc: " INTPTR_FORMAT " (at " INTPTR_FORMAT ")", p2i(return_pc(cont)), p2i(return_pc_address(cont)));

  if (false) {
    address sp = (address)index_address(cont, _sp);
    st->print_cr("--data--");
    int fsize = meta(cont)->frame_size;
    for(int i=0; i < fsize; i++)
      st->print_cr(INTPTR_FORMAT ": %x", p2i(sp + i), *(sp + i));
    st->print_cr("--end data--");
  }
}

inline intptr_t* hframe::index_address(const ContMirror& cont, int i) const {
  // assert (_length == cont.stack_length(), "length: %d cont.stack_length: %d", _length, cont.stack_length());
  return (intptr_t*)cont.stack_address(i);
}

inline HFrameMetadata* hframe::meta(const ContMirror& cont) const {
  return (HFrameMetadata*)index_address(cont, _sp - to_index(METADATA_SIZE));
}

bool hframe::is_bottom(const ContMirror& cont) const {
  return _sp + to_index(size(cont) + METADATA_SIZE) >= cont.stack_length();
}

inline intptr_t* hframe::real_fp(const ContMirror& cont) const {
  assert (!_is_interpreted, "interpreted");
  return index_address(cont, _sp) + cb()->frame_size();
}

inline int hframe::real_fp_index(const ContMirror& cont) const {
  assert (!_is_interpreted, "interpreted");
  // assert (_length == cont.stack_length(), "");
  return _sp + to_index(cb()->frame_size() * sizeof(intptr_t));
}

template<bool interpreted>
void hframe::set_link_address(const ContMirror& cont) {
  if (interpreted) {
    if (cont.valid_stack_index(_fp)) {
      _link_address = (long*)&index_address(cont, _fp)[frame::link_offset];
    }
  } else {
    if (cont.valid_stack_index(_sp)) {
      _link_address = (long*)(real_fp(cont) - frame::sender_sp_offset); // x86-specific
    }
  }
}
inline int hframe::link_index(const ContMirror& cont) const {
  return _is_interpreted ? _fp : (real_fp_index(cont) - to_index(frame::sender_sp_offset * sizeof(intptr_t*))); // x86-specific
}

inline address* hframe::return_pc_address(const ContMirror& cont) const {
  return _is_interpreted
    ? (address*)&index_address(cont, _fp)[frame::return_addr_offset]
    : (address*)(real_fp(cont) - 1); // x86-specific
}

inline void hframe::patch_link_relative(intptr_t* fp) {
  long* la = link_address();
  long new_value = to_index((address)fp - (address)la);
  *la = new_value;
}

inline void hframe::patch_sender_sp_relative(ContMirror& cont, intptr_t* value) {
  assert (_is_interpreted, "");
  long* la = (long*)(index_address(cont, _fp) + frame::interpreter_frame_sender_sp_offset);
  *la = to_index((address)value - (address)la);
}

void hframe::set_size(ContMirror& cont, size_t size) { 
  assert(size < 0xffff, ""); 
  meta(cont)->frame_size = size; 
}

void hframe::set_num_oops(ContMirror& cont, int num) { 
  assert(num  < 0xffff, ""); 
  meta(cont)->num_oops = num; 
}

void hframe::set_uncompressed_size(ContMirror& cont, size_t size) { 
  assert(size < 0xffff, ""); 
  meta(cont)->uncompressed_size = size; 
}

inline void hframe::patch_return_pc(ContMirror& cont, address value) {
  address* ra = return_pc_address(cont);
  *ra = value; 
}

inline void hframe::patch_real_fp_offset(int offset, intptr_t value) { 
  long* addr = (link_address() + offset);
  *(link_address() + offset) = value; 
}

inline void hframe::patch_callee(ContMirror& cont, hframe& sender) {
  if (sender.is_interpreted_frame()) {
    assert(sizeof(long) == sizeof(intptr_t), "risky cast!");
    patch_link_relative((intptr_t*)sender.link_address());
  } else {
    patch_link(sender.fp());
  }
  if (is_interpreted_frame()) {
    patch_sender_sp_relative(cont, index_address(cont, sender.sp()));
  }
}

hframe hframe::sender(ContMirror& cont) {
  // assert (_length == cont.stack_length(), "");
  address sender_pc = return_pc(cont);
  bool is_sender_interpreted = Interpreter::contains(sender_pc);
  int sender_sp = _sp + to_index(size(cont) + METADATA_SIZE);
  long sender_fp = link();
  // log_trace(jvmcont)("hframe::sender sender_fp0: %ld", sender_fp);
  // if (log_is_enabled(Trace, jvmcont)) print_on(cont, tty);
  if (is_sender_interpreted) {
    sender_fp += link_index(cont);
    // log_trace(jvmcont)("hframe::sender real_fp: %d sender_fp: %ld", link_index(cont), sender_fp);
  }
  if (sender_sp >= cont.stack_length())
    return hframe();
  return hframe(sender_sp, sender_fp, sender_pc, is_sender_interpreted, cont);
}

Method* hframe::method(ContMirror& cont) {
  Method* m = NULL;
  if (is_interpreted_frame())
    m = *(Method**)&index_address(cont, _fp)[frame::interpreter_frame_method_offset];
  else
    m = ((CompiledMethod*)cb())->method();
  return m;
}

ContMirror::ContMirror(JavaThread* thread, oop cont)
 : _thread(thread) {
   assert (cont != NULL, "");
  _cont = cont;
  _stack     = NULL;
  _hstack    = NULL;
  _ref_stack = NULL;
  _stack_length = 0;

  _num_frames = 0;
  _num_interpreted_frames = 0;
  _flags = 0;

  _e_num_interpreted_frames = 0;
  _e_num_frames = 0;
  _e_num_refs = 0;
  _e_size = 0;
}

void ContMirror::read() {
  log_trace(jvmcont)("Reading continuation object:");

  _entrySP = java_lang_Continuation::entrySP(_cont);
  _entryFP = java_lang_Continuation::entryFP(_cont);
  _entryPC = java_lang_Continuation::entryPC(_cont);
  log_trace(jvmcont)("\tentrySP: " INTPTR_FORMAT " entryFP: " INTPTR_FORMAT " entryPC: " INTPTR_FORMAT, p2i(_entrySP), p2i(_entryFP), p2i(_entryPC));

  _sp = java_lang_Continuation::sp(_cont);
  _fp = java_lang_Continuation::fp(_cont);
  _pc = (address)java_lang_Continuation::pc(_cont);
  log_trace(jvmcont)("\tsp: %d fp: %ld 0x%lx pc: " INTPTR_FORMAT, _sp, _fp, _fp, p2i(_pc));

  _stack = java_lang_Continuation::stack(_cont);
  if (_stack != NULL) {
    _stack_length = _stack->length();
    _hstack = (int*)_stack->base(T_INT);
  } else {
    _stack_length = 0;
    _hstack = NULL;
  }
  _max_size = java_lang_Continuation::maxSize(_cont);
  log_trace(jvmcont)("\tstack: " INTPTR_FORMAT " hstack: " INTPTR_FORMAT ", stack_length: %d max_size: " SIZE_FORMAT, p2i((oopDesc*)_stack), p2i(_hstack), _stack_length, _max_size);

  _ref_stack = java_lang_Continuation::refStack(_cont);
  _ref_sp = java_lang_Continuation::refSP(_cont);
  log_trace(jvmcont)("\tref_stack: " INTPTR_FORMAT " ref_sp: %d", p2i((oopDesc*)_ref_stack), _ref_sp);

  _flags = java_lang_Continuation::flags(_cont);
  log_trace(jvmcont)("\tflags: %d", _flags);

  _num_frames = java_lang_Continuation::numFrames(_cont);
  log_trace(jvmcont)("\tnum_frames: %d", _num_frames);

  _num_interpreted_frames = java_lang_Continuation::numInterpretedFrames(_cont);
  log_trace(jvmcont)("\tnum_interpreted_frames: %d", _num_interpreted_frames);
}

void ContMirror::write() {
  log_trace(jvmcont)("Writing continuation object:");

  log_trace(jvmcont)("\tsp: %d fp: %ld 0x%lx pc: " INTPTR_FORMAT, _sp, _fp, _fp, p2i(_pc));
  java_lang_Continuation::set_sp(_cont, _sp);
  java_lang_Continuation::set_fp(_cont, _fp);
  java_lang_Continuation::set_pc(_cont, _pc);

  log_trace(jvmcont)("\tentrySP: " INTPTR_FORMAT " entryFP: " INTPTR_FORMAT " entryPC: " INTPTR_FORMAT, p2i(_entrySP), p2i(_entryFP), p2i(_entryPC));
  java_lang_Continuation::set_entrySP(_cont, _entrySP);
  java_lang_Continuation::set_entryFP(_cont, _entryFP);
  java_lang_Continuation::set_entryPC(_cont, _entryPC);

  log_trace(jvmcont)("\tmax_size: " SIZE_FORMAT, _max_size);
  java_lang_Continuation::set_maxSize(_cont, (jint)_max_size);

  log_trace(jvmcont)("\tref_sp: %d", _ref_sp);
  java_lang_Continuation::set_refSP(_cont, _ref_sp);

  java_lang_Continuation::set_flags(_cont, _flags);
  log_trace(jvmcont)("\tflags: %d", _flags);

  java_lang_Continuation::set_numFrames(_cont, _num_frames);
  log_trace(jvmcont)("\tnum_frames: %d", _num_frames);

  java_lang_Continuation::set_numInterpretedFrames(_cont, _num_interpreted_frames);
  log_trace(jvmcont)("\tnum_interpreted_frames: %d", _num_interpreted_frames);

  log_trace(jvmcont)("\tend write");
}

bool ContMirror::is_empty() {
  return _sp < 0 || _sp >= _stack->length();
}

hframe ContMirror::last_frame() {
  return is_empty() ? hframe() : hframe(_sp, _fp, _pc, *this);
}

inline void ContMirror::set_last_frame(hframe& f) {
  // assert (f._length = _stack_length, "");
  set_sp(f.sp()); set_fp(f.fp()); set_pc(f.pc());
  // if (f.ref_sp() != -1) // frames' ref_sp is invalid during freeze
  //   set_refSP(f.ref_sp());
  if (is_empty()) {
    if (_stack_length > 0) {
      set_sp(_stack_length + to_index(METADATA_SIZE));
      // set_refSP(_ref_stack->length());
    }
    set_fp(0);
    set_pc(NULL);
  }
  log_trace(jvmcont)("set_last_frame cont sp: %d fp: 0x%lx pc: " INTPTR_FORMAT " interpreted: %d flag: %d", sp(), fp(), p2i(pc()), f.is_interpreted_frame(), is_flag(FLAG_LAST_FRAME_INTERPRETED));
  if (log_is_enabled(Trace, jvmcont)) f.print_on(*this, tty);
}

inline int ContMirror::stack_index(void* p) const {
  int i = to_index(stack(), p);
  assert (i >= 0 && i < stack_length(), "i: %d length: %d", i, stack_length());
  return i;
}

inline intptr_t* ContMirror::stack_address(int i) const {
  assert (i >= 0 && i < stack_length(), "i: %d length: %d", i, stack_length());
  return (intptr_t*)&stack()[i];
}

void ContMirror::copy_to_stack(void* from, void* to, int size) {
  log_trace(jvmcont)("Copying from v: " INTPTR_FORMAT " - " INTPTR_FORMAT " (%d bytes)", p2i(from), p2i((address)from + size), size);
  log_trace(jvmcont)("Copying to h: " INTPTR_FORMAT " - " INTPTR_FORMAT " (%d - %d)", p2i(to), p2i((address)to + size), to_index(_hstack, to), to_index(_hstack, (address)to + size));

  assert (size > 0, "size: %d", size);
  assert (stack_index(to) >= 0, "");
  assert (to_index(_hstack, (address)to + size) <= _sp, "");

  // this assertion is just to check whether the copying happens as intended, but not otherwise required for this method.
  //assert (write_stack_index(to) == _wsp + to_index(METADATA_SIZE), "to: %d wsp: %d", write_stack_index(to), _wsp);

  if (Continuation::PERFTEST_LEVEL >= 25) {
    Copy::conjoint_memory_atomic(from, to, size);
  }

  _e_size += size;
}

void ContMirror::copy_from_stack(void* from, void* to, int size) {
  log_trace(jvmcont)("Copying from h: " INTPTR_FORMAT " - " INTPTR_FORMAT " (%d - %d)", p2i(from), p2i((address)from + size), to_index(stack(), from), to_index(stack(), (address)from + size));
  log_trace(jvmcont)("Copying to v: " INTPTR_FORMAT " - " INTPTR_FORMAT " (%d bytes)", p2i(to), p2i((address)to + size), size);

  assert (size > 0, "size: %d", size);
  assert (stack_index(from) >= 0, "");
  assert (to_index(stack(), (address)from + size) <= stack_length(), "");

  Copy::conjoint_memory_atomic(from, to, size);

  _e_size += size;
}

/* try to allocate an array from the tlab, if it doesn't work allocate one using the allocate
 * method. In the later case we might have done a safepoint and need to reload our oops */
oop ContMirror::raw_allocate(Klass* klass, const size_t size_in_words, const size_t elements, bool zero) {
  ThreadLocalAllocBuffer& tlab = _thread->tlab();
  HeapWord* start = tlab.allocate(size_in_words);

  ObjArrayAllocator allocator(klass, size_in_words, elements, /* do_zero */ zero);
  if (start == NULL) {
    HandleMark hm(_thread);
    Handle conth(_thread, _cont);
    oop result = allocator.allocate(/* use_tlab */ false);
    _cont = conth();  // reload oop after java call
    _stack = java_lang_Continuation::stack(_cont);
    _ref_stack = java_lang_Continuation::refStack(_cont);
    return result;
  }

  return allocator.initialize(start);
}

oop ContMirror::allocate_stack_array(size_t elements) {
  assert(elements > 0, "");

  TypeArrayKlass* klass = TypeArrayKlass::cast(Universe::intArrayKlassObj());
  const size_t size_in_words = typeArrayOopDesc::object_size(klass, elements);
  return raw_allocate(klass, size_in_words, elements, false);
}

oop ContMirror::allocate_refstack_array(const size_t nr_oops) {
  assert(nr_oops > 0, "");

  ArrayKlass* klass = ArrayKlass::cast(Universe::objectArrayKlassObj());
  const size_t size_in_words = objArrayOopDesc::object_size(nr_oops);
  return raw_allocate(klass, size_in_words, nr_oops, true);
}

int ContMirror::fix_decreasing_index(int index, int old_length, int new_length) const {
  return new_length - (old_length - index);
}

bool ContMirror::grow_stack(int new_size) {
  new_size = new_size >> 2; // convert to number of elements 

  int old_length = _stack_length;
  int offset = _sp > 0 ? _sp - METADATA_ELEMENTS : old_length;
  int min_length = (old_length - offset) + new_size;

  if (min_length <= old_length) {
    return false;
  }

  int new_length = ensure_capacity(old_length, min_length);
  if (new_length == -1) {
    return false;
  }

  oop new_stack = allocate_stack_array(new_length);
  if (new_stack == NULL) {
    return false;
  }

  int n = old_length - offset;
  assert(new_length > n, "");
  if (n > 0) {
    ArrayAccess<ARRAYCOPY_DISJOINT>::oop_arraycopy(_stack, offset * 4, arrayOop(new_stack), (new_length - n) * 4, n);
  }

  _stack = typeArrayOop(new_stack);
  _sp = fix_decreasing_index(_sp, old_length, new_length);
  _stack_length = new_length;
  return true;
}

int ContMirror::ensure_capacity(int old, int min) {
  int newsize = old + (old >> 1);
  if (newsize - min <= 0) {
    if (min < 0) { // overflow
      return -1;
    }
    return min;
  }
  return newsize;
}

bool ContMirror::grow_ref_stack(int nr_oops) {
  int old_length = _ref_stack->length();
  //assert(old_length == _ref_sp, "check"); // this assert is just a check to see if this ever happens and not to enforce behaviour -- it does happen with lazy-copy

  int min_length = (old_length - _ref_sp) + nr_oops;
  int new_length = ensure_capacity(old_length, min_length);
  if (new_length == -1) {
    return false;
  }
  oop new_ref_stack = allocate_refstack_array(new_length);

  if (new_ref_stack == NULL) {
    return false;
  }

  int n = old_length - _ref_sp;
  if (n > 0) {
    assert(UseNewCode, "");
    ArrayAccess<ARRAYCOPY_DISJOINT>::oop_arraycopy(_ref_stack, _ref_sp * heapOopSize, arrayOop(new_ref_stack), (new_length - n) * heapOopSize, n);
  }

  _ref_stack = objArrayOop(new_ref_stack);
  _ref_sp = fix_decreasing_index(_ref_sp, old_length, new_length);
  return true;
}

bool ContMirror::allocate_stack(int size) {
  int elements = size >> 2;
  oop result = allocate_stack_array(elements);
  if (result == NULL) {
    return false;
  }

  _stack = typeArrayOop(result);
  _sp = elements + METADATA_ELEMENTS;
  _stack_length = elements;

  return true;
}

bool ContMirror::allocate_ref_stack(int nr_oops) {
  oop result = allocate_refstack_array(nr_oops);
  if (result == NULL) {
    return false;
  }
  _ref_stack = objArrayOop(result);
  _ref_sp = nr_oops;

  return true;
}

bool ContMirror::allocate_stacks_in_native(int size, int oops, bool needs_stack, bool needs_refstack) {
  if (needs_stack) {
    if (_stack == NULL) {
      if (!allocate_stack(size)) {
        return false;
      } 
    } else {
      if (!grow_stack(size)) {
        return false;
      }
    }

    java_lang_Continuation::set_stack(_cont, _stack);
    java_lang_Continuation::set_sp(_cont, _sp);

  }

  if (needs_refstack) {
    if (_ref_stack == NULL) {
      if (!allocate_ref_stack(oops)) {
        return false;
      }
    } else {
      if (!grow_ref_stack(oops)) {
        return false;
      }
    }
    java_lang_Continuation::set_refStack(_cont, _ref_stack);
    java_lang_Continuation::set_refSP(_cont, _ref_sp);
  }

  return true;
}


void ContMirror::allocate_stacks(int size, int oops, int frames) {
  bool needs_stack_allocation = (_stack == NULL || to_index(size) > (_sp >= 0 ? _sp - to_index(METADATA_SIZE) : _stack_length));
  log_trace(jvmcont)("stack size: %d (int): %d sp: %d stack_length: %d needs alloc: %d", size, to_index(size), _sp, _stack_length, needs_stack_allocation);

  bool needs_refStack_allocation = (_ref_stack == NULL || oops > _ref_sp);
  log_trace(jvmcont)("num_oops: %d ref_sp: %d needs alloc: %d", oops, _ref_sp, needs_stack_allocation);

  assert(_sp == java_lang_Continuation::sp(_cont), "");
  assert(_fp == java_lang_Continuation::fp(_cont), "");
  assert(_pc == java_lang_Continuation::pc(_cont), "");

  if (!(needs_stack_allocation | needs_refStack_allocation))
    return;

  if (Continuation::PERFTEST_LEVEL < 100) {
    tty->print_cr("stack size: %d (int): %d sp: %d stack_length: %d needs alloc: %d", size, to_index(size), _sp, _stack_length, needs_stack_allocation);
    tty->print_cr("num_oops: %d ref_sp: %d needs alloc: %d", oops, _ref_sp, needs_stack_allocation);
  }
  guarantee(Continuation::PERFTEST_LEVEL >= 100, "");

  int old_stack_length = _stack_length;
  int old_sp = _sp;

  if (!allocate_stacks_in_native(size, oops, needs_stack_allocation, needs_refStack_allocation)) {
    allocate_stacks_in_java(size, oops, frames);
  }

  /* Update some stack based values */
  _stack_length = _stack->length();
  _sp = (old_stack_length <= 0 || _sp < 0) ? _stack_length + to_index(METADATA_SIZE) : _stack_length - (old_stack_length - old_sp);
  _hstack = (int*)_stack->base(T_INT);

  if (Interpreter::contains(_pc)) {// only interpreter frames use relative (index) fp
    _fp = _stack_length - (old_stack_length - _fp);
    java_lang_Continuation::set_fp(_cont, _fp);
  }

  // These assertions aren't important, as we'll overwrite the Java-computed ones, but they're just to test that the Java computation is OK.
  assert(_pc == java_lang_Continuation::pc(_cont), "_pc: " INTPTR_FORMAT "  this.pc: " INTPTR_FORMAT "",  p2i(_pc), p2i(java_lang_Continuation::pc(_cont)));
  assert(_sp == java_lang_Continuation::sp(_cont), "_sp: %d  this.sp: %d",  _sp, java_lang_Continuation::sp(_cont));
  assert(_fp == java_lang_Continuation::fp(_cont), "_fp: %lu  this.fp: " JLONG_FORMAT " %d %d", _fp, java_lang_Continuation::fp(_cont), Interpreter::contains(_pc), is_flag(FLAG_LAST_FRAME_INTERPRETED));

  if (!thread()->has_pending_exception()) return;

  assert (to_bytes(_stack_length) >= size, "sanity check: stack_size: %d size: %d", to_bytes(_stack_length), size);
  assert (to_bytes(_sp) - (int)METADATA_SIZE >= size, "sanity check");
  assert (to_bytes(_ref_sp) >= oops, "oops: %d ref_sp: %d refStack length: %d", oops, _ref_sp, _ref_stack->length());
}

void ContMirror::allocate_stacks_in_java(int size, int oops, int frames) {
  int old_stack_length = _stack_length;

  HandleMark hm(_thread);
  Handle conth(_thread, _cont);
  JavaCallArguments args;
  args.push_oop(conth);
  args.push_int(size);
  args.push_int(oops);
  args.push_int(frames);
  JavaValue result(T_VOID);
  JavaCalls::call_virtual(&result, SystemDictionary::Continuation_klass(), vmSymbols::getStacks_name(), vmSymbols::continuationGetStacks_signature(), &args, _thread);
  _cont = conth();  // reload oop after java call

  _stack = java_lang_Continuation::stack(_cont);
  _ref_stack = java_lang_Continuation::refStack(_cont);
  _ref_sp    = java_lang_Continuation::refSP(_cont);
  /* We probably should handle OOM? */
}

// void ContMirror::commit_stacks() {
//   if (_commited) {
//     // assert(_oops == NULL, "");
//     assert(_wref_start == -1, "");
//     return;
//   }

//   log_trace(jvmcont)("Committing stacks");
//   _commited = true;

//   int num_oops = _num_oops;
//   int size = to_bytes(_wsp);

//   allocate_stacks(size, num_oops, 0);
//   if (thread()->has_pending_exception()) return;

//   log_trace(jvmcont)("Copying %d oops", num_oops);
//   for (int i = 0; i < num_oops; i++) {
//     oopLoc ol = _oops[i];
//     oop obj = ol.narrow ? (oop)NativeAccess<>::oop_load(reinterpret_cast<narrowOop*>(ol.loc))
//                         :      NativeAccess<>::oop_load(reinterpret_cast<oop*>(ol.loc));
//     int index = _ref_sp - num_oops + i;
//     log_trace(jvmcont)("i: %d -> index: %d narrow: %d", i, index, ol.narrow); print_oop(reinterpret_cast<void*>(ol.loc), obj);
//     assert (oopDesc::is_oop_or_null(obj), "invalid oop");
//     _ref_stack->obj_at_put(index, obj); // does a HeapAccess<IN_HEAP_ARRAY> write barrier
//   }

//   _ref_sp = _ref_sp - num_oops;
//   assert (_ref_sp >= 0, "_ref_sp: %d", _ref_sp);
//   // delete oops;
//   _oops = NULL;
// }

void ContMirror::cleanup() {
  // cleanup nmethods
  for (hframe hf = last_frame(); !hf.is_empty(); hf = hf.sender(*this)) {
    if (!hf.is_interpreted_frame())
      hf.cb()->as_compiled_method()->dec_on_continuation_stack();
  }
}

template <bool interpreted>
hframe ContMirror::new_hframe(intptr_t* hsp, intptr_t* hfp, address pc, CodeBlob* cb, bool is_interpreted) {
  assert (interpreted == is_interpreted, "");
  int sp;
  long fp;
  sp = stack_index(hsp);
  fp = interpreted ? stack_index(hfp) : (long)hfp;
  hframe result = hframe(sp, fp, pc, cb, interpreted, *this, true);
  result.set_link_address<interpreted>(*this);
  return result;
}

hframe ContMirror::from_frame(const frame& f) {
  return hframe(static_cast<int>(reinterpret_cast<intptr_t>(f.sp())), reinterpret_cast<long>(f.fp()), f.pc(), f.cb(), 
          f.is_interpreted_frame(), *this);
}

void ContMirror::null_ref_stack(int start, int num) {
  if (java_lang_Continuation::is_reset(_cont)) return;
  
  for (int i = 0; i < num; i++)
    _ref_stack->obj_at_put(start + i, NULL);
}

inline int ContMirror::add_oop(oop obj, int index) {
  assert (index < _ref_sp, "");
  log_trace(jvmcont)("i: %d ", index);
  _ref_stack->obj_at_put(index, obj);
  return index;
}

// inline void ContMirror::add_oop_location(oop* p) {
//   log_trace(jvmcont)("i: %d (oop)", _num_oops);
//   assert (_num_oops < _wstack_length / 2 - 1, "");
//   assert ((((unsigned long)p) & HOB) == 0, "HOB on: " INTPTR_FORMAT, p2i(p));
//   _oops[_num_oops] = (oopLoc){false, (unsigned long)p};
//   _num_oops++;
// }

// inline void ContMirror::add_oop_location(narrowOop* p) {
//   log_trace(jvmcont)("i: %d (narrow)", _num_oops);
//   assert (_num_oops < _wstack_length / 2 - 1, "");
//   assert ((((unsigned long)p) & HOB) == 0, "HOB on: " INTPTR_FORMAT, p2i(p));
//   _oops[_num_oops] = (oopLoc){true, (unsigned long)p};
//   _num_oops++;
// }

inline oop ContMirror::obj_at(int i) {
  assert (_ref_stack != NULL, "");
  assert (_ref_sp <= i && i < _ref_stack->length(), "i: %d _ref_sp: %d, length: %d", i, _ref_sp, _ref_stack->length());
  return _ref_stack->obj_at(i);
}

int ContMirror::num_oops() {
  return _ref_stack == NULL ? 0 : _ref_stack->length() - _ref_sp;
}

void ContMirror::update_register_map(RegisterMap& map) {
  log_trace(jvmcont)("Setting RegisterMap saved link address to: " INTPTR_FORMAT, p2i(&_fp));
  frame::update_map_with_saved_link(&map, (intptr_t **)&_fp);
}

bool ContMirror::is_map_at_top(RegisterMap& map) {
  return (map.location(rbp->as_VMReg()) == (address)&_fp);
}

// void ContMirror::call_pinned(res_freeze res, frame& f) {
//   write();

//   HandleMark hm(_thread);
//   Handle conth(_thread, _cont);
//   JavaCallArguments args;
//   args.push_oop(conth);
//   args.push_int(res);
//   JavaValue result(T_VOID);
//   JavaCalls::call_virtual(&result, SystemDictionary::Continuation_klass(), vmSymbols::onPinned_name(), vmSymbols::continuationOnPinned_signature(), &args, _thread);
//   _cont = conth();  // reload oop after java call
//   log_trace(jvmcont)("YTYTYTYTYTYT");
// }

template<typename Event> void ContMirror::post_jfr_event(Event* e) {
  if (e->should_commit()) {
    log_trace(jvmcont)("JFR event: frames: %d iframes: %d size: %d refs: %d", _e_num_frames, _e_num_interpreted_frames, _e_size, _e_num_refs);
    e->set_contClass(_cont->klass());
    e->set_numFrames(_e_num_frames);
    e->set_numIFrames(_e_num_interpreted_frames);
    e->set_size(_e_size);
    e->set_numRefs(_e_num_refs);
    e->commit();
  }
}

// static inline bool is_empty(frame& f) {
//   return f.pc() == NULL;
// }

static inline Method* frame_method(const frame& f) {
  Method* m = NULL;
  if (f.is_interpreted_frame())
    m = f.interpreter_frame_method();
  else if (f.is_compiled_frame())
    m = ((CompiledMethod*)f.cb())->method();
  return m;
}

#ifdef ASSERT
// static inline bool is_not_entrant(const frame& f) {
//   return  f.is_compiled_frame() ? f.cb()->as_nmethod()->is_not_entrant() : false;
// }

static char* method_name(Method* m) {
  return m != NULL ? m->name_and_sig_as_C_string() : NULL;
}

static char* frame_name(frame& f) {
  return method_name(frame_method(f));
}

static inline bool is_deopt_return(address pc, frame& sender) {
  if (sender.is_interpreted_frame()) return false;

  CompiledMethod* cm = sender.cb()->as_compiled_method();
  return cm->is_deopt_pc(pc);
}
#endif

// works only in thaw
static inline bool is_entry_frame(ContMirror& cont, frame& f) {
  return f.sp() == cont.entrySP();
}

static inline intptr_t** link_address(frame& f, bool is_interpreted) {
  return is_interpreted
            ? (intptr_t**)(f.fp() + frame::link_offset)
            : (intptr_t**)(f.real_fp() - frame::sender_sp_offset); // x86-specific
}

static inline intptr_t** link_address(frame& f) {
  return link_address(f, f.is_interpreted_frame());
}

// static inline intptr_t* real_link(frame& f, bool is_interpreted) {
//   return *link_address(f, is_interpreted);
// }

static void patch_link(frame& f, intptr_t* fp, bool is_interpreted) {
  *link_address(f, is_interpreted) = fp;
  log_trace(jvmcont)("patched link: " INTPTR_FORMAT, p2i(fp));
}

static void patch_sender_sp(frame& f, intptr_t* sp) {
  assert (f.is_interpreted_frame(), "");
  *(intptr_t**)(f.fp() + frame::interpreter_frame_sender_sp_offset) = sp;
  log_trace(jvmcont)("patched sender_sp: " INTPTR_FORMAT, p2i(sp));
}

static inline address* return_pc_address(const frame& f, bool is_interpreted) {
  return is_interpreted
            ? (address*)(f.fp() + frame::return_addr_offset)
            : (address*)(f.real_fp() - 1); // x86-specific
}

static inline address real_pc(frame& f) {
  address* pc_addr = &(((address*) f.sp())[-1]);
  return *pc_addr;
}

static inline address return_pc(const frame& f, bool is_interpreted) {
  return *return_pc_address(f, is_interpreted);
}

static void patch_return_pc(frame& f, address pc, bool is_interpreted) {
  *return_pc_address(f, is_interpreted) = pc;
  log_trace(jvmcont)("patched return_pc: " INTPTR_FORMAT, p2i(pc));
}

// static void patch_interpreted_bci(frame& f, int bci) {
//   f.interpreter_frame_set_bcp(f.interpreter_frame_method()->bcp_from(bci));
// }

static void interpreter_oop_mask(frame& f, InterpreterOopMap* mask) {
  Method* m = f.interpreter_frame_method();
  int   bci = f.interpreter_frame_bci();
  m->mask_for(bci, mask); // OopMapCache::compute_one_oop_map(m, bci, mask);
}

static inline int interpreter_frame_expression_stack_size(frame &f, InterpreterOopMap* mask =  NULL) {
  if (mask == NULL) {
    InterpreterOopMap mask0;
    mask = &mask0;
    interpreter_oop_mask(f, mask);
    return mask->expression_stack_size();
  } else
    return mask->expression_stack_size();
}

static inline intptr_t* interpreted_frame_top(frame& f, InterpreterOopMap* mask = NULL) { // inclusive; this will be copied with the frame
  return *(intptr_t**)f.addr_at(frame::interpreter_frame_initial_sp_offset) - interpreter_frame_expression_stack_size(f);
}

static inline intptr_t* compiled_frame_top(frame& f) { // inclusive; this will be copied with the frame
  return f.unextended_sp();
}

static inline intptr_t* frame_top(frame &f) { // inclusive; this will be copied with the frame
  return f.is_interpreted_frame() ? interpreted_frame_top(f) : compiled_frame_top(f);
}

static inline intptr_t* interpreted_frame_bottom(frame& f) { // exclusive; this will not be copied with the frame
#ifdef ASSERT
    RegisterMap map(JavaThread::current(), false); // if thread is NULL we don't get a fix for the return barrier -> entry frame
    frame sender = f.sender(&map);
    intptr_t* locals_plus_one = *(intptr_t**)f.addr_at(frame::interpreter_frame_locals_offset) + 1;
    if (!sender.is_entry_frame() && frame_top(sender) != locals_plus_one) {
      log_trace(jvmcont)("f: "); print_vframe(f);
      log_trace(jvmcont)("sender: "); print_vframe(sender);
      log_trace(jvmcont)("sender top: " INTPTR_FORMAT " locals+1: " INTPTR_FORMAT, p2i(frame_top(sender)), p2i(locals_plus_one));
    }
    assert (frame_top(sender) >= locals_plus_one || sender.is_entry_frame(), "sender top: " INTPTR_FORMAT " locals+1: " INTPTR_FORMAT, p2i(frame_top(sender)), p2i(locals_plus_one));
#endif
    return *(intptr_t**)f.addr_at(frame::interpreter_frame_locals_offset) + 1; // exclusive, so we add 1 word
}

static inline intptr_t* compiled_frame_bottom(frame& f) { // exclusive; this will not be copied with the frame
  return f.unextended_sp() + f.cb()->frame_size();
}

static inline int compiled_frame_num_parameters(frame& f) {
  assert (f.is_compiled_frame(), "");
  if (Interpreter::contains(return_pc(f, false))) {
    return ((CompiledMethod*)f.cb())->method()->size_of_parameters();
  }
  return 0;
}

static inline intptr_t* frame_bottom(frame &f) { // exclusive this will be copied with the frame
  return f.is_interpreted_frame() ? interpreted_frame_bottom(f) : compiled_frame_bottom(f);
}

static bool is_interpreted_frame_owning_locks(frame& f) {
  return f.interpreter_frame_monitor_end() < f.interpreter_frame_monitor_begin();
}

static bool is_compiled_frame_owning_locks(JavaThread* thread, RegisterMap* map, frame& f) {
  if (!DetectLocksInCompiledFrames)
    return false;
  // ResourceMark rm(thread); // vframes/scopes are allocated in the resource area
  nmethod* nm = f.cb()->as_nmethod();
  assert (!nm->is_compiled() || !nm->as_compiled_method()->is_native_method(), ""); // ??? See compiledVFrame::compiledVFrame(...) in vframe_hp.cpp

  if (!nm->has_monitors()) {
    return false;
  }

  for (ScopeDesc* scope = nm->scope_desc_at(f.pc()); scope != NULL; scope = scope->sender()) {
    // scope->print_on(tty);
    GrowableArray<MonitorValue*>* mons = scope->monitors();
    if (mons == NULL || mons->is_empty())
      continue;
    return true;
    for (int index = (mons->length()-1); index >= 0; index--) { // see compiledVFrame::monitors()
      MonitorValue* mon = mons->at(index);
      if (mon->eliminated())
        continue;
      ScopeValue* ov = mon->owner();
      StackValue* owner_sv = StackValue::create_stack_value(&f, map, ov); // it is an oop
      oop owner = owner_sv->get_obj()();
      if (owner != NULL) {
        // tty->print_cr("ZZZ owner:");
        // owner->print_on(tty);
        return true;
      }
    }
  }
  return false;
}

static inline void relativize(intptr_t* const fp, intptr_t* const hfp, int offset) {
  long* addr = (hfp + offset);
  long value = to_index((address)*(hfp + offset) - (address)fp);
  *addr = value;
}

static inline void derelativize(intptr_t* const fp, int offset) {
    *(fp + offset) = (intptr_t)((address)fp + to_bytes(*(long*)(fp + offset)));
}

class ContOopBase {
protected:
  ContMirror* const _cont;
  frame* _fr;
  void* const _vsp;
  int _count;
#ifdef ASSERT
  RegisterMap* _map;
#endif

public:
  int count() { return _count; }

protected:
  ContOopBase(ContMirror* cont, frame* fr, RegisterMap* map, void* vsp)
   : _cont(cont), _fr(fr), _vsp(vsp) {
     _count = 0;
  #ifdef ASSERT
    _map = map;
  #endif
  }

  inline int verify(void* p) {
    int offset = (address)p - (address)_vsp; // in thaw_oops we set the saved link to a local, so if offset is negative, it can be big

#ifdef ASSERT // this section adds substantial overhead
    VMReg reg;
    assert(offset >= 0 || p == _fr->saved_link_address(_map),
      "offset: %d reg: %s", offset, (reg = find_register_spilled_here(p, _map), reg != NULL ? reg->name() : "NONE")); // calle-saved register can only be rbp
    reg = find_register_spilled_here(p, _map); // expensive operation
    if (reg != NULL) log_trace(jvmcont)("reg: %s", reg->name());
    log_trace(jvmcont)("p: " INTPTR_FORMAT " offset: %d %s", p2i(p), offset, p == _fr->saved_link_address(_map) ? "(link)" : "");
// #else
//     log_trace(jvmcont)("p: " INTPTR_FORMAT " offset: %d", p2i(p), offset);
#endif

    return offset;
  }

  inline bool process(void* p) {
    DEBUG_ONLY(verify(p);)
    _count++;
    _cont->e_inc_refs();
    return true;
  }
};

class CallerInfo {
private:
  intptr_t* _hsp;
  intptr_t* _hfp;
  intptr_t* _fp;
  bool _interpreted;
  bool _has_fp_index;
  int _fp_index;

  CallerInfo(intptr_t* hsp, intptr_t *hfp, intptr_t* fp, bool interpreted) : _hsp(hsp), _hfp(hfp), _fp(fp), _interpreted(interpreted), _has_fp_index(false), _fp_index(0) {}
public:
  CallerInfo() : _hsp(NULL), _hfp(NULL), _fp(NULL), _interpreted(false), _has_fp_index(false), _fp_index(0) {}
  bool is_interpreted_frame() const { return _interpreted; }

  intptr_t* hsp() const { return _hsp; }
  intptr_t* hfp() const { assert(is_interpreted_frame(), "only interpreted"); return _hfp; }
  intptr_t* fp() const { assert(!is_interpreted_frame(), "only compiled"); return _fp; }
  bool empty() const { return _hsp == NULL; }

  void set_fp_index(int index) {
    assert(_has_fp_index == false, "can only have one");
    _has_fp_index = true;
    _fp_index = index;
  }

  bool has_fp_index() const {
    return _has_fp_index;
  }

  int fp_index() const { 
    return _fp_index;
  }

  static CallerInfo create_interpreted(intptr_t* hsp, intptr_t *hfp) { return CallerInfo(hsp, hfp, NULL, true); }
  static CallerInfo create_compiled(intptr_t* hsp, intptr_t *fp) { return CallerInfo(hsp, NULL, fp, false); }
};


template <typename T>
class ForwardingOopClosure: public OopClosure, public DerivedOopClosure {
private:
  T* _fn;
public:
  ForwardingOopClosure(T* fn) : _fn(fn) {}
  virtual void do_oop(oop* p)       { _fn->do_oop(p); }
  virtual void do_oop(narrowOop* p) { _fn->do_oop(p); }
  virtual void do_derived_oop(oop *base_loc, oop *derived_loc) { _fn->do_derived_oop(base_loc, derived_loc); }
};

class InterpretedFreezeOopFn: public ContOopBase {
 public:
  enum { SkipNull = false };

 private:
  void* const _hsp;
  int _starting_index;
  int _refStack_length;
  int _count;

 protected:
  template <class T> inline void do_oop_work(T* p) {
    if (!process(p)) return;

    oop obj = NativeAccess<>::oop_load(p);
    int index = _cont->add_oop(obj, _starting_index + _count);
    _count += 1;

  #ifdef ASSERT
    print_oop(p, obj);
    assert (oopDesc::is_oop_or_null(obj), "invalid oop");
    log_trace(jvmcont)("narrow: %d", sizeof(T) < wordSize);
  #endif

    int offset = verify(p);
    address hloc; // address of oop in the (raw) h-stack
    assert(offset >= 0, "should be true for interpreted frames");
    hloc = (address)_hsp + offset;
    assert (_cont->in_hstack(hloc), "");
  #ifndef PRODUCT
    log_trace(jvmcont)("Marking oop at " INTPTR_FORMAT " (offset: %d)", p2i(hloc), offset);
    memset(hloc, 0xba, sizeof(T)); // mark oop locations
  #endif
    int oop_reverse_index = _refStack_length - index;
    log_trace(jvmcont)("Setting reverse oop index at " INTPTR_FORMAT " (offset: %d) : %d (length: %d)", p2i(hloc), offset, oop_reverse_index, _refStack_length);
    assert (oop_reverse_index > 0, "oop_reverse_index: %d", oop_reverse_index);
    *(int*)hloc = oop_reverse_index;
  }

 public:
  InterpretedFreezeOopFn(ContMirror* cont, frame* fr, void* vsp, void* hsp, RegisterMap* map, int starting_index)
   : ContOopBase(cont, fr, map, vsp), _hsp(hsp), _starting_index(starting_index), _count(0) { 
     assert (cont->in_hstack(hsp), "");
     _refStack_length = cont->refStack()->length();
  }
  void do_oop(oop* p)       { do_oop_work(p); }
  void do_oop(narrowOop* p) { do_oop_work(p); }

  void do_derived_oop(oop *base_loc, oop *derived_loc) {
    assert(false, "not supported for interpreted frames");
  }
};

class CompiledFreezeOopFn: public ContOopBase {
 public:
  enum { SkipNull = false };

 private:
  void* const _hsp;
  CallerInfo& _callerinfo;
  int _starting_index;
  int _refStack_length;
  int _count;

 protected:
  template <class T> inline void do_oop_work(T* p) {
    if (!process(p)) return;

    oop obj = NativeAccess<>::oop_load(p);
    int index = _cont->add_oop(obj, _starting_index + _count);
    // _cont->add_oop_location(p);
    _count++;

  #ifdef ASSERT
    // oop obj = NativeAccess<>::oop_load(p);
    print_oop(p, obj);
    assert (oopDesc::is_oop_or_null(obj), "invalid oop");
    log_trace(jvmcont)("narrow: %d", sizeof(T) < wordSize);
  #endif

    int offset = verify(p);
    address hloc; // address of oop in the (raw) h-stack
    int oop_reverse_index = _refStack_length - index;
    assert (oop_reverse_index > 0, "oop_reverse_index: %d", oop_reverse_index);

    if (offset >= 0) { // rbp could be stored in the callee frame.
      hloc = (address)_hsp + offset;
      assert (_cont->in_hstack(hloc), "");
#ifndef PRODUCT
      log_trace(jvmcont)("Marking oop at " INTPTR_FORMAT " (offset: %d)", p2i(hloc), offset);
      memset(hloc, 0xba, sizeof(T)); // mark oop locations
#endif
      *(int*)hloc = oop_reverse_index;
      log_trace(jvmcont)("Setting reverse oop index at " INTPTR_FORMAT " (offset: %d) : %d (length: %d)", p2i(hloc), offset, oop_reverse_index, _refStack_length);
      assert(offset < 32768, "");
    } else {
      assert (p == (T*)_fr->saved_link_address(_map), "");
      _callerinfo.set_fp_index(oop_reverse_index);
      log_trace(jvmcont)("Setting reverse oop index in callerinfo (offset: %d) : %d (length: %d)", offset, oop_reverse_index, _refStack_length);
    }
  }

 public:
  CompiledFreezeOopFn(ContMirror* cont, frame* fr, void* vsp, void* hsp, CallerInfo& callerinfo, RegisterMap* map, int starting_index)
   : ContOopBase(cont, fr, map, vsp), _hsp(hsp), _callerinfo(callerinfo), _starting_index(starting_index), _count(0) { 
     assert (cont->in_hstack(hsp), "");
     _refStack_length = cont->refStack()->length();
  }
  void do_oop(oop* p)       { do_oop_work(p); }
  void do_oop(narrowOop* p) { do_oop_work(p); }

  void do_derived_oop(oop *base_loc, oop *derived_loc) {
    assert(Universe::heap()->is_in_or_null(*base_loc), "not an oop");
    assert(derived_loc != base_loc, "Base and derived in same location");
    verify(base_loc);
    verify(derived_loc);

    intptr_t offset = cast_from_oop<intptr_t>(*derived_loc) - cast_from_oop<intptr_t>(*base_loc);

    log_trace(jvmcont)(
      "Continuation freeze derived pointer@" INTPTR_FORMAT " - Derived: " INTPTR_FORMAT " Base: " INTPTR_FORMAT " (@" INTPTR_FORMAT ") (Offset: " INTX_FORMAT ")",
      p2i(derived_loc), p2i((address)*derived_loc), p2i((address)*base_loc), p2i(base_loc), offset);

    int hloc_offset = (address)derived_loc - (address)_vsp;
    intptr_t* hloc;
    if (hloc_offset >= 0) {
      hloc = (intptr_t*)((address)_hsp + hloc_offset);
      *hloc = offset;
      log_trace(jvmcont)("Writing derived pointer offset at " INTPTR_FORMAT " (offset: " INTX_FORMAT ", " INTPTR_FORMAT ")", p2i(hloc), offset, offset);
    } else {
      assert ((intptr_t**)derived_loc == _fr->saved_link_address(_map), "");
      _callerinfo.set_fp_index(offset);
      log_trace(jvmcont)("Writing derived pointer offset in callerinfo (offset: %ld, 0x%lx)", offset, offset);
    }
  }
};

class ThawOopFn: public ContOopBase {
 private:
  int _i;

 protected:
  template <class T> inline void do_oop_work(T* p) {
    if (!process(p)) return;

    oop obj = _cont->obj_at(_i); // does a HeapAccess<IN_HEAP_ARRAY> load barrier
    assert (oopDesc::is_oop_or_null(obj), "invalid oop");
    log_trace(jvmcont)("i: %d", _i); print_oop(p, obj);
    NativeAccess<IS_DEST_UNINITIALIZED>::oop_store(p, obj);
    _i++;
  }
 public:
  ThawOopFn(ContMirror* cont, frame* fr, int index, int num_oops, void* vsp, RegisterMap* map)
    : ContOopBase(cont, fr, map, vsp) { _i = index; }
  void do_oop(oop* p)       { do_oop_work(p); }
  void do_oop(narrowOop* p) { do_oop_work(p); }

  void do_derived_oop(oop *base_loc, oop *derived_loc) {
    assert(Universe::heap()->is_in_or_null(*base_loc), "not an oop: " INTPTR_FORMAT " (at " INTPTR_FORMAT ")", p2i((oopDesc*)*base_loc), p2i(base_loc));
    verify(derived_loc);
    verify(base_loc);
    assert (oopDesc::is_oop_or_null(*base_loc), "invalid oop");

    intptr_t offset = *(intptr_t*)derived_loc;

    log_trace(jvmcont)(
      "Continuation thaw derived pointer@" INTPTR_FORMAT " - Derived: " INTPTR_FORMAT " Base: " INTPTR_FORMAT " (@" INTPTR_FORMAT ") (Offset: " INTX_FORMAT ")",
      p2i(derived_loc), p2i((address)*derived_loc), p2i((address)*base_loc), p2i(base_loc), offset);

    oop obj = cast_to_oop(cast_from_oop<intptr_t>(*base_loc) + offset);
    assert(Universe::heap()->is_in_or_null(obj), "");
    *derived_loc = obj;


    assert(derived_loc != base_loc, "Base and derived in same location");
  }
};

#ifndef PRODUCT
static void set_anchor(JavaThread* thread, FrameInfo* fi) {
  JavaFrameAnchor* anchor = thread->frame_anchor();
  anchor->set_last_Java_sp((intptr_t*)fi->sp);
  anchor->set_last_Java_fp((intptr_t*)fi->fp);
  anchor->set_last_Java_pc(fi->pc);

  assert(thread->last_frame().cb() != NULL, "");

  log_trace(jvmcont)("set_anchor:");
  print_vframe(thread->last_frame());
}

static void set_anchor(ContMirror& cont) {
  FrameInfo fi = { cont.entryPC(), cont.entryFP(), cont.entrySP() };
  set_anchor(cont.thread(), &fi);
}

static inline void clear_anchor(JavaThread* thread) {
  thread->frame_anchor()->clear();
}
#endif

class FreezeContinuation {
private:
  JavaThread* _thread;
  ContMirror& _mirror;
  intptr_t *_bottom_address;

  int _oops;
  int _size; // total size of all frames plus metadata. keeps track of offset where a frame should be written and how many bytes we need to allocate.
  int _frames;

  int _wsp; // the current hstack sp during the freezing operation
  int _wref_sp; // the current hstack ref_sp during the freezing operation

  hframe _bottom;
  hframe _top;
  frame _entry_frame;

  bool _is_first;
  bool _is_last;

  VMReg vmRegRbp;
  VMReg vmRegRbpNext;

  bool is_first() { return _is_first; }
  bool is_last()  { return _is_last;  } // this is only true after returning from the recursive call

public:
  FreezeContinuation(JavaThread* thread, ContMirror& mirror) : 
    _thread(thread), _mirror(mirror), _bottom_address(mirror.entrySP()), 
    _oops(0), _size(0), _frames(0), _wsp(0), _wref_sp(0),
    _is_first(false), _is_last(false) {

      vmRegRbp = rbp->as_VMReg();
      vmRegRbpNext = rbp->as_VMReg()->next();
  }

  int nr_oops() const { return _oops; }
  int nr_bytes() const { return _size; }
  int nr_frames() const { return _frames; }
  hframe bottom_hframe() { return _bottom; }
  hframe top_hframe() { return _top; }
  frame entry_frame() { return _entry_frame; }

  res_freeze freeze(frame f, RegisterMap& regmap) {
    RegisterMap map = regmap;
    assert (map.update_map(), "RegisterMap not set to update");
    map.set_include_argument_oops(false);
    CallerInfo caller;

    _is_first = true; // the first frame we'll visit is the top
    res_freeze result = freeze(f, map, caller);

    if (caller.has_fp_index()) {
      assert(!caller.is_interpreted_frame(), "only compiled frames");
      _top.set_fp(caller.fp_index());
    }

    return result;
  }

  void save_bounding_hframe(hframe& hf) {
    if (is_first()) {
      _top = hf;
    }
    if (is_last()) {
       _bottom = hf;
    }
  }

  static int compiled_frame_size(frame& f, int* num_oops) {
    *num_oops = f.oop_map()->num_oops();
    return f.cb()->frame_size() * wordSize;
  }

  static int interpreted_frame_size(frame&f, int* num_oops) {
    int n = 0;
    n++; // for the mirror
    n += ((intptr_t*)f.interpreter_frame_monitor_begin() - (intptr_t*)f.interpreter_frame_monitor_end())/f.interpreter_frame_monitor_size();

    InterpreterOopMap mask;
    interpreter_oop_mask(f, &mask);
    n += mask.num_oops();
    *num_oops = n;

    int size = (interpreted_frame_bottom(f) - interpreted_frame_top(f, &mask)) * wordSize;
    return size;
  }

  template <bool callee_is_interpreted>
  void patch(frame& f, hframe& callee, CallerInfo& caller) {
    if (!caller.empty()) {
      if (caller.is_interpreted_frame()) {
        callee.patch_link_relative(caller.hfp());
      } else {
        callee.patch_link((long)caller.fp());
      }
      if (callee_is_interpreted) {
        callee.patch_sender_sp_relative(_mirror, caller.hsp());
      }
    } else {
      callee.zero_link();
      if (callee_is_interpreted) {
        callee.patch_real_fp_offset(frame::interpreter_frame_sender_sp_offset, 0);
      }
    }

    if (caller.has_fp_index()) {
      assert(!caller.is_interpreted_frame(), "only compiled frames");
      callee.patch_link(caller.fp_index());
    }

    if (is_last() && !_mirror.is_empty()) {
      assert (Continuation::is_cont_bottom_frame(f), "");
      log_trace(jvmcont)("Fixing return address on bottom frame: " INTPTR_FORMAT, p2i(_mirror.pc()));
      callee.patch_return_pc(_mirror, _mirror.pc());
    }
  }

  intptr_t* freeze_raw_frame(intptr_t* vsp, int fsize) {
    _wsp -= to_index(fsize);
    intptr_t* hsp = _mirror.stack_address(_wsp);
    _mirror.copy_to_stack(vsp, hsp, fsize);
    _wsp -= to_index(METADATA_SIZE);

    return hsp;
  }

  class FreezeInterpretedOops {
  public:
    static int freeze_oops(ContMirror& mirror, frame& f, intptr_t* vsp, intptr_t* hsp, RegisterMap& map, int starting_index, CallerInfo& current) {
      InterpretedFreezeOopFn oopFn(&mirror, &f, vsp, hsp, &map, starting_index);
      ForwardingOopClosure<InterpretedFreezeOopFn> oopClosure(&oopFn);
      f.oops_do(&oopClosure, NULL, &oopClosure, &map);
      return oopFn.count();
    }
  };

  class FreezeCompiledOops {
  public:
    static int freeze_oops(ContMirror& mirror, frame& f, intptr_t* vsp, intptr_t* hsp, RegisterMap& map, int starting_index, CallerInfo& current) {
      const ImmutableOopMap* oopmap = f.oop_map();
      assert(oopmap, "must have");
      CompiledFreezeOopFn oopFn(&mirror, &f, vsp, hsp, current, &map, starting_index);

      OopMapDo<CompiledFreezeOopFn, CompiledFreezeOopFn, IncludeAllValues> visitor(&oopFn, &oopFn, false /* no derived table lock */);
      visitor.oops_do(&f, &map, oopmap);
      if (map.include_argument_oops()) {
        ForwardingOopClosure<CompiledFreezeOopFn> oopClosure(&oopFn);
        f.cb()->preserve_callee_argument_oops(f, &map, &oopClosure);
      }
      return oopFn.count();
    }
  };

  template <typename FreezeOops>
  void freeze_oops(frame& f, intptr_t* vsp, intptr_t *hsp, RegisterMap& map, int num_oops, CallerInfo& current) {
    if (Continuation::PERFTEST_LEVEL < 30) {
      return;
    }

    log_trace(jvmcont)("Walking oops (freeze)");

    assert (!map.include_argument_oops(), "");

    int starting_index = _wref_sp - num_oops;
    int frozen = FreezeOops::freeze_oops(_mirror, f, vsp, hsp, map, starting_index, current);
    assert(frozen == num_oops, "check");
    _wref_sp = starting_index;
  }

  res_freeze freeze_interpreted_stackframe(frame& f, RegisterMap& map, CallerInfo& caller) {
    if (is_interpreted_frame_owning_locks(f)) {
      return freeze_pinned_monitor;
    }

    int oops = 0;
    const int fsize = interpreted_frame_size(f, &oops);
    if (fsize < 0) {
      return freeze_pinned_native;
    }

    _size += fsize + METADATA_SIZE;
    _oops += oops;

    res_freeze result = freeze_caller(f, map, caller); // <----- recursive call
    if (result != freeze_ok) {
      return result;
    }

    intptr_t* vsp = interpreted_frame_top(f);
    intptr_t* vfp = f.fp();
#ifdef ASSERT
    intptr_t* bottom1 = interpreted_frame_bottom(f);
    const int fsize1 = (bottom1 - vsp) * sizeof(intptr_t);
    assert (fsize1 == fsize, "");
#endif

    if (Continuation::PERFTEST_LEVEL <= 15) return freeze_ok;

    intptr_t* hsp = freeze_raw_frame(vsp, fsize);
    intptr_t* hfp = hsp + (vfp - vsp);

    HStackFrameDescriptor hstackframe(fsize, f.pc(), NULL, true, 0, (intptr_t *) (f.fp() - vsp));
    hframe hf = hstackframe.create_hframe<true>(this, _mirror, hsp);
    save_bounding_hframe(hf);

    relativize(vfp, hfp, frame::interpreter_frame_last_sp_offset);
    relativize(vfp, hfp, frame::interpreter_frame_initial_sp_offset); // == block_top == block_bottom
    relativize(vfp, hfp, frame::interpreter_frame_locals_offset);

    hf.set_size(_mirror, fsize);
    hf.set_uncompressed_size(_mirror, fsize);
    hf.set_num_oops(_mirror, oops);

    // patch our stuff - this used to happen in the caller so it needs to happen last
    patch<true>(f, hf, caller); 

    freeze_oops<FreezeInterpretedOops>(f, vsp, hsp, map, oops, caller);

    caller = CallerInfo::create_interpreted(hsp, hfp);

    _mirror.add_size(fsize);
    _mirror.inc_num_interpreted_frames();
    _mirror.inc_num_frames();

    return freeze_ok;
  }

  res_freeze freeze_compiled_stackframe(frame& f, RegisterMap& map, CallerInfo& caller) {
    if (is_compiled_frame_owning_locks(_mirror.thread(), &map, f)) {
      return freeze_pinned_monitor;
    }

    int oops = 0;
    const int fsize = compiled_frame_size(f, &oops);
    if (fsize < 0) {
      return freeze_pinned_native;
    }

    _size += fsize + METADATA_SIZE;
    _oops += oops;

    res_freeze result = freeze_caller(f, map, caller); // <----- recursive call
    if (result != freeze_ok) {
      return result;
    }

    intptr_t* vsp = compiled_frame_top(f); // consider moving past recursive call
#ifdef ASSERT
    intptr_t* bottom = compiled_frame_bottom(f);
    const int fsize1 = (bottom - vsp) * sizeof(intptr_t);
    assert (fsize1 == fsize, "");
#endif

    if (Continuation::PERFTEST_LEVEL <= 15) return freeze_ok;

    f.cb()->as_compiled_method()->inc_on_continuation_stack();

    intptr_t* hsp = freeze_raw_frame(vsp, fsize);

    HStackFrameDescriptor hstackframe(fsize, f.pc(), f.cb(), false, f.fp(), NULL);
    hframe hf = hstackframe.create_hframe<false>(this, _mirror, hsp);
    save_bounding_hframe(hf);

    hf.set_size(_mirror, fsize);
    hf.set_uncompressed_size(_mirror, 0);
    hf.set_num_oops(_mirror, oops);

    patch<false>(f, hf, caller);

    assert (Interpreter::contains(hf.return_pc(_mirror)) == ((!caller.empty() && caller.is_interpreted_frame()) || (caller.empty() && !_mirror.is_empty() && _mirror.is_flag(FLAG_LAST_FRAME_INTERPRETED))), "");
    bool may_need_alignment = Interpreter::contains(hf.return_pc(_mirror)); // do after fixing return_pc in patch (and/or use equivalent condition above)

    caller = CallerInfo::create_compiled(hsp, f.fp());

    freeze_oops<FreezeCompiledOops>(f, vsp, hsp, map, oops, caller);

    _mirror.inc_num_frames();
    _mirror.add_size(fsize);
    if (may_need_alignment) { 
      _mirror.add_size(sizeof(intptr_t)); // possible alignment
    }

    return freeze_ok;
  }

  NOINLINE res_freeze finalize(frame& f) {
    if (Continuation::PERFTEST_LEVEL <= 15) return freeze_ok;

    _entry_frame = f;
#ifdef ASSERT
    log_trace(jvmcont)("bottom: " INTPTR_FORMAT " count %d size: %d, num_oops: %d", p2i(_bottom_address), nr_frames(), nr_bytes(), nr_oops());
    hframe orig_top_frame = _mirror.last_frame();
    log_trace(jvmcont)("top_hframe before (freeze):");
    if (log_is_enabled(Trace, jvmcont)) orig_top_frame.print_on(_mirror, tty);

    const bool empty = _mirror.is_empty();
    log_trace(jvmcont)("empty: %d", empty);
    assert (!CONT_FULL_STACK || empty, "");
    assert (!empty || _mirror.sp() > _mirror.stack_length() || _mirror.sp() < 0, "sp: %d stack_length: %d", _mirror.sp(), _mirror.stack_length());
    assert (orig_top_frame.is_empty() == empty, "empty: %d f.sp: %d f.fp: 0x%lx f.pc: " INTPTR_FORMAT, empty, orig_top_frame.sp(), orig_top_frame.fp(), p2i(orig_top_frame.pc()));
#endif

    _mirror.allocate_stacks(_size, _oops, _frames);

    if (_thread->has_pending_exception()) return freeze_exception;

    _wsp = _mirror.sp() - to_index(METADATA_SIZE);
    _wref_sp = _mirror.refSP();

    return freeze_ok;
  }

  inline res_freeze freeze_caller(frame& f, RegisterMap& map, CallerInfo& caller) { // TODO: templatize by callee frame type and use in sender;
    bool is_first = _is_first;
    if (is_first) _is_first = false;

    address link1 = map.trusted_location(vmRegRbp);
    //address link2 = map.trusted_location(vmRegRbpNext);
    
    frame sender = f.frame_sender<ContinuationCodeBlobLookup>(&map); // LOOKUP // TODO: templatize by callee frame type
    res_freeze result = freeze(sender, map, caller);

    map.update_location(vmRegRbp,     link1);
    map.update_location(vmRegRbpNext, link1);

    _is_first = is_first;

    return result;
  }

  res_freeze freeze(frame& f, RegisterMap& map, CallerInfo& caller) {
    if (f.real_fp() > _bottom_address) {
      _is_last = true; // the next frame we return to is bottom
      return finalize(f); // done with recursion
    }

    _frames++;
    res_freeze result;
    bool is_compiled = f.is_compiled_frame();
    if (is_compiled) {
      if (f.oop_map() == NULL) {
        return freeze_pinned_native; // special native frame
      }
      result = freeze_compiled_stackframe(f, map, caller);
    } else {
      bool is_interpreted = f.is_interpreted_frame();
      if (!is_interpreted) {
        return freeze_pinned_native;
      }
      result = freeze_interpreted_stackframe(f, map, caller);
    }

    if (_is_last) _is_last = false;

    return result;
  }

  bool cmp(int* a, int* b, int size) {
    bool result = true;
    for (int i = 0; i < size; ++i) {
      if (a[i] != b[i]) {
        tty->print_cr("%d: %d %d", i, a[i], b[i]);
        result = false;
      }
    }
    return result;
  }

  void commit() {
    assert (_wsp <= _mirror.sp() - to_index(METADATA_SIZE), "wsp: %d sp - to_index(METADATA_SIZE): %d", _wsp, _mirror.sp() - to_index(METADATA_SIZE));
    assert (_wsp == _top.sp() - to_index(METADATA_SIZE), "wsp: %d top sp - to_index(METADATA_SIZE): %d", _wsp, _top.sp() - to_index(METADATA_SIZE));
    _mirror.set_last_frame(_top);
    _mirror.set_refSP(_wref_sp);
  }

  void finish(bool empty, frame& f) {
    hframe orig_top_frame = _mirror.last_frame(); // must be done before committing the changes

    commit();

    f = entry_frame();

    assert (_bottom.is_interpreted_frame() || _bottom.size(_mirror) % 16 == 0, "");

    if (empty) {
      if (f.is_interpreted_frame()) {
        _bottom.zero_link();
      } else {
        if (f.is_deoptimized_frame()) {
          assert (f.cb()->as_nmethod()->get_original_pc(&f) == f.pc(), "original_pc: " INTPTR_FORMAT " f.pc(): " INTPTR_FORMAT, p2i(f.cb()->as_nmethod()->get_original_pc(&f)), p2i(f.pc()));
          assert (is_deopt_return(_bottom.return_pc(_mirror), f), "must be");
          assert (_bottom.return_pc(_mirror) != f.pc(), "_bottom.return_pc(): " INTPTR_FORMAT " f.pc(): " INTPTR_FORMAT, p2i(_bottom.return_pc(_mirror)), p2i(f.pc()));
          log_trace(jvmcont)("Entry frame deoptimized! pc: " INTPTR_FORMAT " -> original_pc: " INTPTR_FORMAT, p2i(_bottom.return_pc(_mirror)), p2i(f.pc()));
        } else // we do not patch if entry is deopt, as we use that information when thawing
          _bottom.patch_return_pc(_mirror, NULL);
      }
      assert (_bottom.sender(_mirror).is_empty(), "");
    } else {
      _bottom.patch_callee(_mirror, orig_top_frame);

      assert (_bottom.sender(_mirror) == orig_top_frame, "");
    }

    log_trace(jvmcont)("last h-frame:");
    if (log_is_enabled(Trace, jvmcont)) _bottom.print(_mirror);

    log_trace(jvmcont)("top_hframe after (freeze):");
    if (log_is_enabled(Trace, jvmcont)) _mirror.last_frame().print_on(_mirror, tty);

    DEBUG_ONLY(address ret_pc =  return_pc(f, f.is_interpreted_frame());)

    // assert (strcmp(method_name(new_top.method(_mirror)), YIELD_SIG) == 0, "name: %s", method_name(new_top.method(_mirror)));  // not true if yield is not @DontInline
    assert (_mirror.is_flag(FLAG_LAST_FRAME_INTERPRETED) == _mirror.last_frame().is_interpreted_frame(), "flag: %d is_interpreted: %d", _mirror.is_flag(FLAG_LAST_FRAME_INTERPRETED), _mirror.last_frame().is_interpreted_frame());
  }
};

// freezes all frames of a single continuation
static res_freeze freeze_continuation(JavaThread* thread, oop oopCont, frame& f, RegisterMap& map) {
  assert (oopCont != NULL, "");

  log_trace(jvmcont)("Freeze ___ cont: " INTPTR_FORMAT, p2i((oopDesc*)oopCont));

  EventContinuationFreeze event;
  ContMirror cont(thread, oopCont);
  cont.read();

  LogStreamHandle(Trace, jvmcont) st;

  DEBUG_ONLY(log_debug(jvmcont)("Freeze ### #" INTPTR_FORMAT, cont.hash()));
  log_trace(jvmcont)("Freeze 0000 sp: " INTPTR_FORMAT " fp: " INTPTR_FORMAT " pc: " INTPTR_FORMAT, p2i(f.sp()), p2i(f.fp()), p2i(f.pc()));
  log_trace(jvmcont)("Freeze 1111 sp: %d fp: 0x%lx pc: " INTPTR_FORMAT, cont.sp(), cont.fp(), p2i(cont.pc()));

  intptr_t* bottom = cont.entrySP(); // (bottom is highest address; stacks grow down)
  intptr_t* top = f.sp();

  log_trace(jvmcont)("QQQ AAAAA bottom: " INTPTR_FORMAT " top: " INTPTR_FORMAT " size: " SIZE_FORMAT, p2i(bottom), p2i(top), pointer_delta(bottom, top, sizeof(address)));

  if (Continuation::PERFTEST_LEVEL <= 13) return freeze_ok;

  const bool empty = cont.is_empty();

  FreezeContinuation fc(thread, cont);
  res_freeze result = fc.freeze(f, map);
  if (result != freeze_ok) {
    return result;
  }

  if (Continuation::PERFTEST_LEVEL <= 15) return freeze_ok;

  fc.finish(empty, f);
  
  cont.write();

  // notify JVMTI
  JvmtiThreadState *jvmti_state = thread->jvmti_thread_state();
  if (jvmti_state != NULL && jvmti_state->is_interp_only_mode()) {
    jvmti_state->invalidate_cur_stack_depth();
    // jvmti_state->set_exception_detected();
    // for (XXX) {
    //   jvmti_state->decr_cur_stack_depth(); // JvmtiExport::post_method_exit(thread, last_frame.method() XXXX, last_frame.get_frame() XXXXX);
    // }
  }

  cont.post_jfr_event(&event);

#ifdef ASSERT
  log_debug(jvmcont)("end of freeze cont ### #" INTPTR_FORMAT, cont.hash());
#else
  log_trace(jvmcont)("--- end of freeze_continuation");
#endif

  return freeze_ok;
}

// returns the continuation yielding (based on context), or NULL for failure (due to pinning)
// it freezes multiple continuations, depending on contex
// it must set Continuation.stackSize
// sets Continuation.fp/sp to relative indices
//
// In: fi->pc, fi->sp, fi->fp all point to the current (topmost) frame to freeze (the yield frame)
// Out: fi->pc, fi->sp, fi->fp all point to the run frame (entry's caller)
//      unless freezing has failed, in which case fi->pc = 0
//      However, fi->fp points to the _address_ on the stack of the entry frame's link to its caller (so *(fi->fp) is the fp)
JRT_ENTRY(int, Continuation::freeze(JavaThread* thread, FrameInfo* fi))
  callgrind();
  Continuation::PERFTEST_LEVEL = ContPerfTest;

  if (PERFTEST_LEVEL <= 10) {
    fi->fp = NULL; fi->sp = NULL; fi->pc = NULL;
    return freeze_ok;
  }

  log_debug(jvmcont)("~~~~~~~~~ freeze");
  log_trace(jvmcont)("fi->sp: " INTPTR_FORMAT " fi->fp: " INTPTR_FORMAT " fi->pc: " INTPTR_FORMAT, p2i(fi->sp), p2i(fi->fp), p2i(fi->pc));
  ContinuationCodeBlobLookup lookup;

  // set_anchor(thread, fi); // DEBUG
  print_frames(thread);

  DEBUG_ONLY(thread->_continuation = NULL;)

  HandleMark hm(thread);

  if (thread->has_pending_exception()) {
    fi->fp = NULL; fi->sp = NULL; fi->pc = NULL;
    log_trace(jvmcont)("=== end of freeze (fail 0)");
    return freeze_exception;
  }

  oop cont = get_continuation(thread);
  assert(cont != NULL && oopDesc::is_oop_or_null(cont), "Invalid cont: " INTPTR_FORMAT, p2i((void*)cont));

  RegisterMap map(thread, true);
  map.set_include_argument_oops(false);
  // Note: if the doYield stub does not have its own frame, we may need to consider deopt here, especially if yield is inlinable
  frame f = thread->last_frame(); // this is the doYield stub frame. last_frame is set up by the call_VM infrastructure
  f = f.frame_sender<ContinuationCodeBlobLookup>(&map); // LOOKUP // this is the yield frame
  assert (f.pc() == fi->pc, "");
  // The following doesn't work because fi->fp can contain an oop, that a GC doesn't know about when walking.
  // frame::update_map_with_saved_link(&map, (intptr_t **)&fi->fp);
  // frame f(fi->sp, fi->fp, fi->pc); // the yield frame

  res_freeze res = freeze_continuation(thread, cont, f, map); // changes f
  if (res != freeze_ok) {
    fi->fp = NULL; fi->sp = NULL; fi->pc = NULL;
    log_trace(jvmcont)("=== end of freeze (fail)");
    return res;
  }

  // upon return, f points at the entry frame. we need to point it one more frame, to Continuation.run
  RegisterMap dmap(NULL, false);
  intptr_t** entry_link_address = link_address(f);
  f = f.frame_sender<ContinuationCodeBlobLookup>(&dmap); // LOOKUP

  log_debug(jvmcont)("Jumping to frame (freeze): [%ld] (%d)", java_tid(thread), thread->has_pending_exception());
  print_vframe(f, &map);

// #ifdef ASSERT
//   { ResourceMark rm(thread);
//     assert (strcmp(frame_name(f), RUN_SIG) == 0, "name: %s", frame_name(f)); }  // not true if run is not @DontInline
// #endif

  assert (real_pc(f) == f.raw_pc(), "");
  // we have an indirection for fp, because the link at the entry frame may hold a sender's oop, and it can be relocated
  // at a safpoint on the VM->Java transition, so we point at an address where the GC would find it
  fi->sp = f.unextended_sp(); // java_lang_Continuation::entrySP(cont);
  fi->fp = (intptr_t*)entry_link_address; // f.fp();
  fi->pc = real_pc(f); // Continuation.run may have been deoptimized


// #ifdef ASSERT
//    if (f.pc() != real_pc(f)) tty->print_cr("Continuation.run deopted!");
// #endif

  // set_anchor(thread, fi);

  log_debug(jvmcont)("ENTRY: sp: " INTPTR_FORMAT " fp: " INTPTR_FORMAT " pc: " INTPTR_FORMAT, p2i(fi->sp), p2i(fi->fp), p2i(fi->pc));
  log_debug(jvmcont)("=== End of freeze");

  return 0;
JRT_END


static frame thaw_interpreted_frame(ContMirror& cont, hframe& hf, intptr_t* vsp, frame& sender) {
  RegisterMap dmap(NULL, false);

  // log_trace(jvmcont)("ZZZZ SENDER 111:"); print_vframe(sender, &dmap);

  intptr_t* hsp = cont.stack_address(hf.sp());
  cont.copy_from_stack(hsp, vsp, hf.size(cont));

  // log_trace(jvmcont)("ZZZZ SENDER 222:"); print_vframe(sender, &dmap);

  intptr_t* hfp = cont.stack_address(hf.fp());
  intptr_t* vfp = vsp + (hfp - hsp);

  derelativize(vfp, frame::interpreter_frame_last_sp_offset);
  derelativize(vfp, frame::interpreter_frame_initial_sp_offset); // == block_top == block_bottom
  derelativize(vfp, frame::interpreter_frame_locals_offset);

  intptr_t* unextended_sp = *(intptr_t**)(vfp + frame::interpreter_frame_last_sp_offset);
  frame f(vsp, unextended_sp, vfp, hf.pc());

  patch_sender_sp(f, sender.unextended_sp()); // derelativize(vfp, frame::interpreter_frame_sender_sp_offset);

  assert (*(intptr_t**)(vfp + frame::interpreter_frame_locals_offset) < frame_top(sender), "sender top: " INTPTR_FORMAT " locals: " INTPTR_FORMAT,
    p2i(frame_top(sender)), p2i(*(intptr_t**)(vfp + frame::interpreter_frame_locals_offset)));

  if (!f.is_interpreted_frame_valid(cont.thread())) {
    assert(f.is_interpreted_frame_valid(cont.thread()), "invalid thawed frame");
  }

  cont.dec_num_frames();
  cont.dec_num_interpreted_frames();

  return f;
}

static frame thaw_compiled_frame(ContMirror& cont, hframe& hf, intptr_t* vsp, frame& sender, RegisterMap& map, bool &deoptimized) {
#ifdef _LP64
  if ((long)vsp % 16 != 0) {
    log_trace(jvmcont)("Aligning compiled frame: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(vsp), p2i(vsp - 1));
    assert(sender.is_interpreted_frame(), "");
    vsp--;
  }
  assert((long)vsp % 16 == 0, "");
#endif

  if (Interpreter::contains(hf.return_pc(cont))) { // false if bottom-most frame, as the return address would be patched to NULL if interpreted
    cont.sub_size(sizeof(intptr_t)); // we do this whether or not we've aligned because we add it in freeze_interpreted_frame
  }
  if (sender.is_interpreted_frame()) {
    // int num_of_parameters = ((CompiledMethod*)hf.cb())->method()->size_of_parameters();
    // vsp -= num_of_parameters; // we may need this space for deopt TODO
    // cont.sub_size(sizeof(intptr_t) * num_of_parameters);
  }

  intptr_t* hsp = cont.stack_address(hf.sp());
  cont.copy_from_stack(hsp, vsp, hf.size(cont));

  hf.cb()->as_compiled_method()->dec_on_continuation_stack();

  frame f(vsp, (intptr_t*)hf.fp(), hf.pc());

    // TODO get nmethod. Call popNmethod if necessary
    // when copying nmethod frames, we need to check for them being made non-reentrant, in which case we need to deopt them
    // and turn them into interpreter frames.

  if (f.should_be_deoptimized() && !f.is_deoptimized_frame()) {
    log_trace(jvmcont)("Deoptimizing thawed frame");
    // tty->print_cr("DDDDDDDDDDDDD");
    Deoptimization::deoptimize(cont.thread(), f, &map);
    deoptimized = true;
  }

  cont.dec_num_frames();

  return f;
}

static void thaw_oops(ContMirror& cont, frame& f, int oop_index, int num_oops, void* target, RegisterMap& map, const ImmutableOopMap* oop_map) {
  log_trace(jvmcont)("Walking oops (thaw)");

  // log_trace(jvmcont)("is_top: %d", is_top);
  // assert (!is_top || cont.is_map_at_top(map), "");
  // assert (!is_top || f.is_interpreted_frame() || f.fp() == (intptr_t*)cont.fp(), "f.fp: " INTPTR_FORMAT " cont.fp: 0x%lx", p2i(f.fp()), cont.fp());

  assert (!map.include_argument_oops(), "");

  intptr_t* tmp_fp = f.fp();
  frame::update_map_with_saved_link(&map, &tmp_fp);

  // ResourceMark rm(cont.thread()); // apparently, oop-mapping may require resource allocation
  ThawOopFn oopFn(&cont, &f, oop_index, num_oops, target, &map);
  ForwardingOopClosure<ThawOopFn> oopClosure(&oopFn);
  if (oop_map) {
    OopMapDo<ThawOopFn, ThawOopFn, IncludeAllValues> visitor(&oopFn, &oopFn, false /* no derived table lock */);
    visitor.oops_do(&f, &map, oop_map);
    // void frame::oops_do_internal(OopClosure* f, CodeBlobClosure* cf, DerivedOopClosure* df, RegisterMap* map, bool use_interpreter_oop_map_cache) {
    //oop_map->oops_do(&f, &map, &oopClosure, &oopClosure);

    // Preserve potential arguments for a callee. We handle this by dispatching
    // on the codeblob. For c2i, we do
    if (map.include_argument_oops()) {
      f.cb()->preserve_callee_argument_oops(f, &map, &oopClosure);
    }
  } else {
    f.oops_do(&oopClosure, NULL, &oopClosure, &map);
  }
  log_trace(jvmcont)("count: %d num_oops: %d", oopFn.count(), num_oops);
  assert(oopFn.count() == num_oops, "closure oop count different.");
  cont.null_ref_stack(oop_index, num_oops);

  // Thawing oops may have overwritten the link in the callee if rbp contained an oop (only possible if we're compiled).
  // This only matters when we're the top frame, as that's the value that will be restored into rbp when we jump to continue.
  if (tmp_fp != f.fp()) {
    log_trace(jvmcont)("WHOA link has changed (thaw) f.fp: " INTPTR_FORMAT " link: " INTPTR_FORMAT, p2i(f.fp()), p2i(tmp_fp));
    f.set_fp(tmp_fp);
  }

  log_trace(jvmcont)("Done walking oops");
}

static frame thaw_frame(ContMirror& cont, hframe& hf, int oop_index, frame& sender, bool &deoptimized) {
  log_trace(jvmcont)("=============================");

  if (log_is_enabled(Trace, jvmcont)) hf.print(cont);

  const int fsize = hf.uncompressed_size(cont) != 0 ? hf.uncompressed_size(cont) : hf.size(cont);
  const address bottom = (address) sender.sp();
  intptr_t* vsp = (intptr_t*)(bottom - fsize);

  cont.sub_size(fsize);

  log_trace(jvmcont)("hsp: %d hfp: 0x%lx is_bottom: %d", hf.sp(), hf.fp(), hf.is_bottom(cont));
  log_trace(jvmcont)("stack_length: %d", cont.stack_length());
  log_trace(jvmcont)("bottom: " INTPTR_FORMAT " vsp: " INTPTR_FORMAT " fsize: %d", p2i(bottom), p2i(vsp), fsize);


  bool is_sender_deopt = deoptimized;
  address ret_pc;
  if (is_entry_frame(cont, sender)) {
    // the entry frame could have been compiled/deopted since we froze the bottom frame  XXXXXXXX
    assert (sender.pc() == sender.raw_pc(), "");
    assert (sender.pc() == cont.entryPC(), "");
    assert (!is_deopt_return(sender.raw_pc(), sender), ""); // because there can be no safepoints to deopt while thawing
    ret_pc = sender.pc();
  } else if (is_sender_deopt) {
    // this must be done before copying the frame, because the sender's sp might not be correct
    // for example, if a compiled frame calls an interpreted frame, its sp must point to a couple of words before
    // the callee's fp, but we always create the frame so that sp = unextended_sp, and so the sp would point to
    // before the callee's locals
    address* pc_addr = &(((address*) sender.sp())[-1]);
    ret_pc = *pc_addr;
    log_trace(jvmcont)("Sender is deopt: " INTPTR_FORMAT, p2i(ret_pc));
    assert (is_deopt_return(ret_pc, sender), "");
  } else {
    ret_pc = hf.return_pc(cont); // sender.pc();
    assert (is_entry_frame(cont, sender) || ret_pc == hf.return_pc(cont) || is_deopt_return(hf.return_pc(cont), sender), "");
  }
  assert (ret_pc == sender.raw_pc(), "%d %d %d %d %d",
    is_entry_frame(cont, sender),
    is_deopt_return(ret_pc, sender), is_deopt_return(sender.raw_pc(), sender),
    is_sender_deopt, sender.is_deoptimized_frame());
  deoptimized = false;

  if (is_entry_frame(cont, sender)) {

    // if (hf.return_pc(cont) != cont.entryPC()) {
    //   tty->print_cr("return: " INTPTR_FORMAT " real: " INTPTR_FORMAT " pc: " INTPTR_FORMAT " raw: " INTPTR_FORMAT " entry: " INTPTR_FORMAT, p2i(hf.return_pc(cont)), p2i(real_pc(sender)), p2i(sender.pc()), p2i(sender.raw_pc()), p2i(cont.entryPC()));
    //   tty->print_cr("deopt return: %d deopt real pc: %d deopt entry: %d zero: %d",
    //   is_deopt_return(hf.return_pc(cont), sender), is_deopt_return(real_pc(sender), sender), is_deopt_return(cont.entryPC(), sender), is_deopt_return(0, sender));
    //   assert (hf.return_pc(cont) == cont.entryPC(), "");
    // }
    assert (!is_sender_deopt, "");
    assert (!sender.is_deoptimized_frame(), "");
    // if (sender.is_deoptimized_frame()) {
    //   log_trace(jvmcont)("Sender frame already deopted");
    //   is_sender_deopt = true;
    // } else if (is_deopt_return(hf.return_pc(cont), sender)) {
    //   log_trace(jvmcont)("Entry frame deoptimized! pc: " INTPTR_FORMAT, p2i(sender.pc()));
    //   *pc_addr = sender.pc(); // just to make the following call think we're walking the stack from the top
    //   tty->print_cr("Deoptimizing enter");
    //   sender.deoptimize(cont.thread());
    //   is_sender_deopt = true;
    // }
  }

  // address deopt_ret_pc = NULL;
  // if (is_sender_deopt) {
  //   // this must be done before copying the frame, because the sender's sp might not be correct
  //   // for example, if a compiled frame calls an interpreted frame, its sp must point to a couple of words before
  //   // the callee's fp, but we always create the frame so that sp = unextended_sp, and so the sp would point to
  //   // before the callee's locals
  //   deopt_ret_pc = *pc_addr; // we grab the return pc written by deoptimize (about to be clobbered by thaw_x) to restore later
  //   log_trace(jvmcont)("Sender is deopt: " INTPTR_FORMAT, p2i(deopt_ret_pc));
  //   deoptimized = false;
  // }

  RegisterMap map(cont.thread(), true, false, false);
  map.set_include_argument_oops(false);

  bool is_interpreted = hf.is_interpreted_frame();
  frame f = is_interpreted ? thaw_interpreted_frame(cont, hf, vsp, sender)
                                      :    thaw_compiled_frame(cont, hf, vsp, sender, map, deoptimized);

  patch_link(f, sender.fp(), is_interpreted);
  patch_return_pc(f, ret_pc, is_interpreted);
  // if (is_sender_deopt) {
  //   assert (!is_entry_frame(cont, sender), "");
  //   tty->print_cr("Patching sender deopt");
  //   log_trace(jvmcont)("Patching sender deopt");
  //   patch_return_pc(f, deopt_ret_pc, hf.is_interpreted_frame());
  // }

  assert (!is_entry_frame(cont, sender) || sender.fp() == cont.entryFP(), "sender.fp: " INTPTR_FORMAT " entryFP: " INTPTR_FORMAT, p2i(sender.fp()), p2i(cont.entryFP()));

  // assert (oop_index == hf.ref_sp(), "");
  thaw_oops(cont, f, oop_index, hf.num_oops(cont), f.sp(), map, is_interpreted ? NULL : f.oop_map());

#ifndef PRODUCT
  RegisterMap dmap(NULL, false);
  print_vframe(f, &dmap);
#endif

  return f;
}

static frame thaw_frames(ContMirror& cont, hframe hf, int oop_index, int num_frames, int& count, int &last_oop_index, hframe& last_frame, bool& deoptimized) {
  if (num_frames == 0 || hf.is_empty()) {
    frame entry(cont.entrySP(), cont.entryFP(), cont.entryPC());
    log_trace(jvmcont)("Found entry:");
    print_vframe(entry);

  #ifdef ASSERT
    { ResourceMark rm(cont.thread());
      assert (strcmp(frame_name(entry), ENTER_SIG) == 0, "name: %s", frame_name(entry)); }
  #endif

    last_oop_index = oop_index;
    last_frame = hf;
    deoptimized = false;
    // cont.set_refSP(oop_index);
    // cont.set_last_frame(hf);
    return entry;
  }

  // assert (oop_index == hf.ref_sp(), "oop_index: %d hf.ref_sp(): %d", oop_index, hf.ref_sp());
  hframe hsender = hf.sender(cont);
  frame sender = thaw_frames(cont, hsender, oop_index + hf.num_oops(cont), num_frames - 1, count, last_oop_index, last_frame, deoptimized);
  frame f = thaw_frame(cont, hf, oop_index, sender, deoptimized);

  assert ((count == 0) == is_entry_frame(cont, sender), "");
  assert (hf.is_bottom(cont) <= last_frame.is_empty(), "hf.is_bottom(cont): %d last_frame.is_empty(): %d ", hf.is_bottom(cont), last_frame.is_empty());
// #ifdef ASSERT
//   { // ResourceMark rm(cont.thread());
//     assert (!hf.is_bottom(cont) || strcmp(frame_name(f), ENTER0_SIG) == 0, "name: %s", frame_name(f)); }
// #endif

  if (count == 0) {
    assert (is_entry_frame(cont, sender), "");
    assert (!hf.is_bottom(cont) || hf.sender(cont).is_empty(), "");
    if (!hf.is_bottom(cont)) {
      log_trace(jvmcont)("Setting return address to return barrier: " INTPTR_FORMAT, p2i(StubRoutines::cont_returnBarrier()));
      patch_return_pc(f, StubRoutines::cont_returnBarrier(), f.is_interpreted_frame());
    }
    // else {
    //   if (sender.is_interpreted_frame()) { // unnecessary now, thanks to enter0
    //     // We enter the continuation through an interface call (target.run()), but exit through a virtual call (doContinue())
    //     // Alternatively, wrap the call to target.run() inside a private method.
    //     patch_return_pc(f, Interpreter::return_entry(vtos, 0, Bytecodes::_invokevirtual), f.is_interpreted_frame());
    //   }
    // }
  }

  assert (!is_entry_frame(cont, sender) || (hf.is_bottom(cont) == last_frame.is_empty()), "hf.is_bottom(cont): %d last_frame.is_empty(): %d ", hf.is_bottom(cont), last_frame.is_empty());
  assert (!is_entry_frame(cont, sender) || (hf.is_bottom(cont) != Continuation::is_cont_bottom_frame(f)), "hf.is_bottom(cont): %d is_cont_bottom_frame(f): %d ", hf.is_bottom(cont), Continuation::is_cont_bottom_frame(f));
  assert (Continuation::is_cont_bottom_frame(f) <= (num_frames == 1), "num_frames: %d is_cont_bottom_frame(f): %d ", num_frames, Continuation::is_cont_bottom_frame(f));

  count++;
  return f;
}

static inline int thaw_num_frames(bool return_barrier) {
  if (CONT_FULL_STACK) {
    assert (!return_barrier, "");
    return 10000;
  }
  return return_barrier ? 1 : 2;
}

// fi->pc is the return address -- the entry
// fi->sp is the top of the stack after thaw
// fi->fp current rbp
// called after preparations (stack overflow check and making room)
static inline void thaw1(JavaThread* thread, FrameInfo* fi, const bool return_barrier) {
  EventContinuationThaw event;
  ResourceMark rm(thread);

  if (return_barrier) log_trace(jvmcont)("== RETURN BARRIER");
  const int num_frames = thaw_num_frames(return_barrier);

  log_trace(jvmcont)("~~~~~~~~~ thaw %d", num_frames);
  log_trace(jvmcont)("pc: " INTPTR_FORMAT, p2i(fi->pc));
  log_trace(jvmcont)("rbp: " INTPTR_FORMAT, p2i(fi->fp));

  // address target = (address)fi->sp; // we leave fi->sp as-is

  oop oopCont = get_continuation(thread);
  assert(oopCont != NULL && oopDesc::is_oop_or_null(oopCont), "Invalid cont: " INTPTR_FORMAT, p2i((void*)oopCont));

  ContMirror cont(thread, oopCont);
  cont.read();
  cont.set_entrySP(fi->sp);
  cont.set_entryFP(fi->fp);
  if (!return_barrier) { // not return barrier
    cont.set_entryPC(fi->pc);
  }

  DEBUG_ONLY(log_debug(jvmcont)("THAW ### #" INTPTR_FORMAT, cont.hash()));

#ifndef PRODUCT
  set_anchor(cont);
  // print_frames(thread);
#endif

  // log_trace(jvmcont)("thaw: TARGET: " INTPTR_FORMAT, p2i(target);
  // log_trace(jvmcont)("QQQ CCCCC bottom: " INTPTR_FORMAT " top: " INTPTR_FORMAT " size: %ld", p2i(cont.entrySP()), p2i(target), (address)cont.entrySP() - target);
  assert(num_frames > 0, "num_frames <= 0: %d", num_frames);
  assert(!cont.is_empty(), "no more frames");

  // ResourceMark rm(cont.thread()); // apparently, oop-mapping may require resource allocation

  // const address orig_top_pc = cont.pc();
  hframe hf = cont.last_frame();
  log_trace(jvmcont)("top_hframe before (thaw):");
  if (log_is_enabled(Trace, jvmcont)) hf.print_on(cont, tty);

  RegisterMap map(thread, true, false, false);
  map.set_include_argument_oops(false);
  assert (map.update_map(), "RegisterMap not set to update");

  DEBUG_ONLY(int orig_num_frames = cont.num_frames();)
  int frame_count = 0;
  int last_oop_index = 0;
  hframe last_frame;
  bool deoptimized = false;
  frame top = thaw_frames(cont, hf, cont.refSP(), num_frames, frame_count, last_oop_index, last_frame, deoptimized);
  cont.set_last_frame(last_frame);
  // assert (last_oop_index == cont.refSP(), "");
  cont.set_refSP(last_oop_index);
  // assert (last_oop_index == last_frame.ref_sp(), "last_oop_index: %d last_frame.ref_sp(): %d", last_oop_index, last_frame.ref_sp());
  assert (last_oop_index == cont.refSP(), "");

  assert (!CONT_FULL_STACK || cont.is_empty(), "");
  assert (cont.is_empty() == cont.last_frame().is_empty(), "cont.is_empty: %d cont.last_frame().is_empty(): %d", cont.is_empty(), cont.last_frame().is_empty());
  assert (cont.is_empty() == (cont.max_size() == 0), "cont.is_empty: %d cont.max_size: " SIZE_FORMAT, cont.is_empty(), cont.max_size());
  assert (cont.is_empty() <= (cont.refSP() == cont.refStack()->length()), "cont.is_empty: %d ref_sp: %d refStack.length: %d", cont.is_empty(), cont.refSP(), cont.refStack()->length());
  assert (cont.is_empty() == (cont.num_frames() == 0), "cont.is_empty: %d num_frames: %d", cont.is_empty(), cont.num_frames());
  assert (cont.is_empty() <= (cont.num_interpreted_frames() == 0), "cont.is_empty: %d num_interpreted_frames: %d", cont.is_empty(), cont.num_interpreted_frames());
  assert (cont.num_frames() == orig_num_frames - frame_count, "cont.is_empty: %d num_frames: %d orig_num_frames: %d frame_count: %d", cont.is_empty(), cont.num_frames(), orig_num_frames, frame_count);

  assert (!top.is_compiled_frame() || top.is_deoptimized_frame() == top.cb()->as_compiled_method()->is_deopt_pc(top.raw_pc()), "");
  assert (!top.is_compiled_frame() || top.is_deoptimized_frame() == (top.pc() != top.raw_pc()), "");

  fi->sp = top.sp();
  fi->fp = top.fp();
  fi->pc = top.raw_pc(); // we'll jump to the current continuation pc // Interpreter::return_entry(vtos, 0, Bytecodes::_invokestatic, true); //

  log_trace(jvmcont)("thawed %d frames", frame_count);

  log_trace(jvmcont)("top_hframe after (thaw):");
  if (log_is_enabled(Trace, jvmcont)) cont.last_frame().print_on(cont, tty);

  cont.write();

#ifndef PRODUCT
  set_anchor(thread, fi);
  print_frames(thread); // must be done after write(), as frame walking reads fields off the Java objects.
  clear_anchor(thread);
#endif

  log_trace(jvmcont)("cont sp: %d fp: %lx", cont.sp(), cont.fp());
  log_trace(jvmcont)("fi->sp: " INTPTR_FORMAT " fi->fp: " INTPTR_FORMAT " fi->pc: " INTPTR_FORMAT, p2i(fi->sp), p2i(fi->fp), p2i(fi->pc));

  if (log_is_enabled(Trace, jvmcont)) {
    log_trace(jvmcont)("Jumping to frame (thaw):");
    frame f = frame(fi->sp, fi->fp, fi->pc);
    print_vframe(f, NULL);
  }

// #ifdef ASSERT
//   { ResourceMark rm(thread);
//     assert (!CONT_FULL_STACK || strcmp(frame_name(f), YIELD_SIG) == 0, "name: %s", frame_name(f)); } // not true if yield is not @DontInline
// #endif

  DEBUG_ONLY(thread->_continuation = oopCont;)

  JvmtiThreadState *jvmti_state = thread->jvmti_thread_state();
  if (jvmti_state != NULL && jvmti_state->is_interp_only_mode()) {
    jvmti_state->invalidate_cur_stack_depth();
    // for (XXX) {
    //   jvmti_state->incr_cur_stack_depth();
    // }
  }

  cont.post_jfr_event(&event);

#ifdef ASSERT
  log_debug(jvmcont)("=== End of thaw #" INTPTR_FORMAT, cont.hash());
#else
  log_debug(jvmcont)("=== End of thaw");
#endif
}

// static size_t frames_size(oop cont, int frames) {
//   size_t size = 0;
//   int length = java_lang_Continuation::stack(cont)->length();
//   int* hstack = (int*)java_lang_Continuation::stack_base(cont);
//   int sp = java_lang_Continuation::sp(cont);
//   // int fp = java_lang_Continuation::fp(cont);

//   size = 8;
//   bool last_interpreted = false;

//   for (int i=0; i < frames && sp >= 0 && sp < length; i++) {
//     HFrameMetadata* md = metadata(to_haddress(hstack, sp));
//     size_t uncompressed_size = md->uncompressed_size;
//     size_t fsize = md->frame_size; // (indices are to 32-bit words)

//     size += uncompressed_size != 0 ? uncompressed_size : fsize;

//     bool is_interpreted = uncompressed_size != 0;
//     if (is_interpreted != last_interpreted) {
//       size += 8;
//       last_interpreted = is_interpreted;
//     }

//     sp += to_index(fsize + METADATA_SIZE);
//     // fp += hstack[fp]; // contains offset to previous fp
//   }
//   log_trace(jvmcont)("frames_size: %lu", size);
//   return size;
// }

static bool stack_overflow_check(JavaThread* thread, int size, address sp) {
  const int page_size = os::vm_page_size();
  if (size > page_size) {
    if (sp - size < thread->stack_overflow_limit()) {
      return false;
    }
  }
  return true;
}

// In: fi->sp = the sp of the entry frame
// Out: returns the size of frames to thaw or 0 for no more frames or a stack overflow
//      On failure: fi->sp - cont's entry SP
//                  fi->fp - cont's entry FP
//                  fi->pc - overflow? throw StackOverflowError : cont's entry PC
JRT_LEAF(int, Continuation::prepare_thaw(FrameInfo* fi, bool return_barrier))
  Continuation::PERFTEST_LEVEL = ContPerfTest;
  
  if (PERFTEST_LEVEL <= 110) return 0;

  log_trace(jvmcont)("~~~~~~~~~ prepare_thaw");

  int num_frames = thaw_num_frames(return_barrier);

  log_trace(jvmcont)("prepare_thaw %d %d", return_barrier, num_frames);
  log_trace(jvmcont)("pc: " INTPTR_FORMAT, p2i(fi->pc));
  log_trace(jvmcont)("rbp: " INTPTR_FORMAT, p2i(fi->fp));

  const address bottom = (address)fi->sp; // os::current_stack_pointer(); points to the entry frame
  log_trace(jvmcont)("bottom: " INTPTR_FORMAT, p2i(bottom));

  JavaThread* thread = JavaThread::current();
  oop cont = get_continuation(thread);

  // if the entry frame is interpreted, it may leave a parameter on the stack, which would be left there if the return barrier is hit
  // assert ((address)java_lang_Continuation::entrySP(cont) - bottom <= 8, "bottom: " INTPTR_FORMAT ", entrySP: " INTPTR_FORMAT, bottom, java_lang_Continuation::entrySP(cont));
  int size = java_lang_Continuation::maxSize(cont); // frames_size(cont, num_frames);
  if (size == 0) { // no more frames
    return 0;
  }
  if (!stack_overflow_check(thread, size + 300, bottom)) {
    fi->pc = StubRoutines::throw_StackOverflowError_entry();
    return 0;
  }

  size += sizeof(intptr_t); // just in case we have an interpreted entry after which we need to align

  address target = bottom - size;
  log_trace(jvmcont)("target: " INTPTR_FORMAT, p2i(target));
  log_trace(jvmcont)("QQQ BBBBB bottom: " INTPTR_FORMAT " top: " INTPTR_FORMAT " size: %d", p2i(bottom), p2i(target), size);

  if (PERFTEST_LEVEL <= 120) return 0;

  return size;
JRT_END

// IN:  fi->sp = the future SP of the topmost thawed frame (where we'll copy the thawed frames)
// Out: fi->sp = the SP of the topmost thawed frame -- the one we will resume at
//      fi->fp = the FP " ...
//      fi->pc = the PC " ...
// JRT_ENTRY(void, Continuation::thaw(JavaThread* thread, FrameInfo* fi, int num_frames))
JRT_LEAF(address, Continuation::thaw(FrameInfo* fi, bool return_barrier, bool exception))
  callgrind();
  Continuation::PERFTEST_LEVEL = ContPerfTest;

  thaw1(JavaThread::current(), fi, return_barrier);

  if (exception) {
    // TODO: handle deopt. see TemplateInterpreterGenerator::generate_throw_exception, OptoRuntime::handle_exception_C, OptoRuntime::handle_exception_helper
    // assert (!top.is_deoptimized_frame(), ""); -- seems to be handled
    address ret = fi->pc;
    fi->pc = SharedRuntime::raw_exception_handler_for_return_address(JavaThread::current(), fi->pc);
    return ret;
  } else
    return NULL;
JRT_END

bool Continuation::is_continuation_entry_frame(const frame& f, const RegisterMap* map) {
  if (map->cont() != NULL) // A continuation's entry frame is always on the v-stack
    return false;

  Method* m = frame_method(f);
  if (m == NULL)
    return false;

  // we can do this because the entry frame is never inlined
  return m->intrinsic_id() == vmIntrinsics::_Continuation_enter;
}

// When walking the virtual stack, this method returns true
// iff the frame is a thawed continuation frame whose
// caller is still frozen on the h-stack.
// The continuation object can be extracted from the thread.
bool Continuation::is_cont_bottom_frame(const frame& f) {
  return is_return_barrier_entry(return_pc(f, f.is_interpreted_frame()));
}

static oop find_continuation_for_frame(JavaThread* thread, intptr_t* const sp) {
  oop cont = get_continuation(thread);
  while (cont != NULL && java_lang_Continuation::entrySP(cont) < sp)
    cont = java_lang_Continuation::parent(cont);
  return cont;
}

static address get_entry_pc_past_barrier(JavaThread* thread, const frame& f) {
  oop cont = find_continuation_for_frame(thread, f.sp());
  assert (cont != NULL, "");
  address pc = java_lang_Continuation::entryPC(cont);
  // log_trace(jvmcont)("YEYEYEYEYEYEYEEYEY: " INTPTR_FORMAT, p2i(pc));
  return pc;
}

address Continuation::fix_continuation_bottom_sender(const frame* callee, RegisterMap* map, address pc) {
  return (map->thread() != NULL && is_return_barrier_entry(pc)) ? get_entry_pc_past_barrier(map->thread(), *callee) : pc;
}

// frame Continuation::fix_continuation_bottom_sender(const frame& callee, frame f, RegisterMap* map) {
//   if (map->thread() != NULL && is_cont_bottom_frame(callee)) {
//     f.set_pc_preserve_deopt(get_entry_pc_past_barrier(map->thread(), f));
//   }
//   return f;
// }

bool Continuation::is_frame_in_continuation(JavaThread* thread, const frame& f) {
  return find_continuation_for_frame(thread, f.sp()) != NULL;
}

bool Continuation::is_scope_bottom(oop cont_scope, const frame& f, const RegisterMap* map) {
  if (cont_scope == NULL || !is_continuation_entry_frame(f, map))
    return false;

  assert (map->cont() == NULL, "");
  // if (map->cont() != NULL)
  //   return false;

  oop cont = find_continuation_for_frame(map->thread(), f.sp());
  if (cont == NULL)
    return false;

  oop sc = continuation_scope(cont);
  assert(sc != NULL, "");

  return sc == cont_scope;
}

static frame continuation_top_frame(oop contOop, RegisterMap* map) {
  ContMirror cont(NULL, contOop);
  cont.read();

  hframe hf = cont.last_frame();
  assert (!hf.is_empty(), "");

  // tty->print_cr("continuation_top_frame");
  
  map->set_cont(map->thread(), contOop);
  return hf.to_frame(cont);
}

static frame continuation_parent_frame(ContMirror& cont, RegisterMap* map) {
  // The following is commented out because a continuation's entry frame is always on the v-stack
  // oop parentOop = java_lang_Continuation::parent(cont.mirror());
  // if (parentOop != NULL)
  //   return continuation_top_frame(parentOop, map);
  
  frame sender(cont.entrySP(), cont.entryFP(), cont.entryPC());

  // tty->print_cr("continuation_parent_frame");
  // print_vframe(sender, map, NULL);

  map->set_cont(map->thread(), NULL);
  return sender;
}

frame Continuation::top_frame(const frame& callee, RegisterMap* map) {
  oop contOop = find_continuation_for_frame(map->thread(), callee.sp());
  return continuation_top_frame(contOop, map);
}

static frame sender_for_frame(const frame& callee, RegisterMap* map) {
  ContMirror cont(map->thread(), map->cont());
  cont.read();

  hframe hfcallee = cont.from_frame(callee);
  hframe sender = hfcallee.sender(cont);

  if (!sender.is_empty())
    return sender.to_frame(cont);
  return continuation_parent_frame(cont, map);
}

frame Continuation::sender_for_interpreter_frame(const frame& callee, RegisterMap* map) {
  return sender_for_frame(callee, map);
}

frame Continuation::sender_for_compiled_frame(const frame& callee, RegisterMap* map) {
  return sender_for_frame(callee, map);
}

class IsOopClosure : public OopMapClosure {
public:
  int _offset;
  bool _is_oop;
  IsOopClosure(int offset) : _offset(offset), _is_oop(false) {}
  void do_value(VMReg reg, OopMapValue::oop_types type) {
    assert (type == OopMapValue::oop_value || type == OopMapValue::narrowoop_value, "");
    if (reg->is_reg())
        return;
    int sp_offset_in_bytes = reg->reg2stack() * VMRegImpl::stack_slot_size;
    if (sp_offset_in_bytes == _offset)
      _is_oop = true;
  }
};

// *grossly* inefficient
static bool is_oop_in_compiler_frame(const frame& fr, const RegisterMap* map, const int usp_offset_in_bytes) {
  const ImmutableOopMap* oop_map = fr.oop_map();
  IsOopClosure ioc(usp_offset_in_bytes);
  oop_map->all_do(&fr, OopMapValue::oop_value | OopMapValue::narrowoop_value, &ioc);
  return ioc._is_oop;
}

// given an address in the raw h-stack known to conain an oop, returns the address of the corresponding actual oop in the ref stack.
address Continuation::oop_address(objArrayOop ref_stack, address stack_address) {
  int oop_reverse_index = *(int*)stack_address;
  assert (ref_stack != NULL, "");
  int i = ref_stack->length() - oop_reverse_index;

  // tty->print_cr("oop_reverse_index: %d index: %d", oop_reverse_index, i);
  
  oop obj = ref_stack->obj_at(i); // invoke barriers
  address p = UseCompressedOops ? (address)ref_stack->obj_at_addr<narrowOop>(i)
                                : (address)ref_stack->obj_at_addr<oop>(i);

  // print_oop(p, obj);
  assert (oopDesc::is_oop_or_null(obj), "invalid oop");

  return p;
}

// if oop, it is narrow iff UseCompressedOops
address Continuation::usp_offset_to_location(const frame& fr, const RegisterMap* map, const int usp_offset_in_bytes) {
  assert (fr.is_compiled_frame(), "");
  ContMirror cont(map->thread(), map->cont());
  cont.read();

  hframe hf = cont.from_frame(fr);
  intptr_t* hsp = cont.stack_address(hf.sp());
  address loc = (address)hsp + usp_offset_in_bytes;

  return is_oop_in_compiler_frame(fr, map, usp_offset_in_bytes) ? oop_address(cont.refStack(), loc) : loc;
}

address Continuation::interpreter_frame_expression_stack_at(const frame& fr, const RegisterMap* map, const InterpreterOopMap& oop_mask, int index) {
  assert (fr.is_interpreted_frame(), "");
  ContMirror cont(map->thread(), map->cont());
  cont.read();
  hframe hf = cont.from_frame(fr);

  Method* method = hf.method(cont);
  int max_locals = method->max_locals();
  address loc = (address)hf.interpreter_frame_expression_stack_at(cont, index);
  if (loc == NULL)
    return NULL;
  return oop_mask.is_oop(max_locals + index) ? oop_address(cont.refStack(), loc) : loc; // see stack_expressions in vframe.cpp
}

address Continuation::interpreter_frame_local_at(const frame& fr, const RegisterMap* map, const InterpreterOopMap& oop_mask, int index) {
  assert (fr.is_interpreted_frame(), "");
  ContMirror cont(map->thread(), map->cont());
  cont.read();
  
  hframe hf = cont.from_frame(fr);
  address loc = (address)hf.interpreter_frame_local_at(cont, index);

  // tty->print_cr("interpreter_frame_local_at2: %d oop: %d", index, oop_mask.is_oop(index));
  // hf.print();

  return oop_mask.is_oop(index) ? oop_address(cont.refStack(), loc) : loc;
}

// address Continuation::interpreter_frame_metadata(const frame& fr, const RegisterMap* map, int fp_index) {
//   assert (fr.is_interpreted_frame(), "");
//   ContMirror cont(map->thread(), map->cont());
//   cont.read();
//   hframe hf = cont.from_frame(fr);
//   address loc = (address)hf.interpreter_frame_metadata_at(cont, fp_index);
//   return (fp_index == frame::interpreter_frame_mirror_offset) ? oop_address(cont.refStack(), loc) : loc;
// }

Method* Continuation::interpreter_frame_method(const frame& fr, const RegisterMap* map) {
  assert (fr.is_interpreted_frame(), "");
  ContMirror cont(map->thread(), map->cont());
  cont.read();
  hframe hf = cont.from_frame(fr);

  return hf.method(cont);
}

address Continuation::interpreter_frame_bcp(const frame& fr, const RegisterMap* map) {
  assert (fr.is_interpreted_frame(), "");
  ContMirror cont(map->thread(), map->cont());
  cont.read();
  hframe hf = cont.from_frame(fr);

  Method* method = hf.method(cont);
  address bcp = (address)*hf.interpreter_frame_metadata_at(cont, frame::interpreter_frame_bcp_offset);
  return method->bcp_from(bcp);
}

///// DEBUGGING

static void print_oop(void *p, oop obj, outputStream* st) {
  if (!log_is_enabled(Trace, jvmcont)) return;

  st->print_cr(INTPTR_FORMAT ": ", p2i(p));
  if (obj == NULL) {
    st->print_cr("*NULL*");
  } else {
    if (oopDesc::is_oop_or_null(obj)) {
      if (obj->is_objArray()) {
        st->print_cr("valid objArray: " INTPTR_FORMAT, p2i(obj));
      } else {
        obj->print_value_on(st);
        // obj->print();
      }
    } else {
      st->print_cr("invalid oop: " INTPTR_FORMAT, p2i(obj));
    }
    st->cr();
  }
}

static void print_vframe(frame f, const RegisterMap* map, outputStream* st) {
  if (st != NULL && !log_is_enabled(Trace, jvmcont)) return;
  if (st == NULL) st = tty;

  st->print_cr("\tfp: " INTPTR_FORMAT " real_fp: " INTPTR_FORMAT ", sp: " INTPTR_FORMAT " pc: " INTPTR_FORMAT " usp: " INTPTR_FORMAT, p2i(f.fp()), p2i(f.real_fp()), p2i(f.sp()), p2i(f.pc()), p2i(f.unextended_sp()));

  f.print_on(st);

  // st->print("\tpc: "); os::print_location(st, *(intptr_t*)f.pc());
  intptr_t* fp = f.fp();
  st->print("cb: ");
  if (f.cb() == NULL) {
    st->print_cr("NULL");
    return;
  }
  f.cb()->print_value_on(st); st->cr();
  if (f.is_interpreted_frame()) {
    Method* method = f.interpreter_frame_method();
    st->print_cr("\tinterpreted");
    st->print("\tMethod (at: " INTPTR_FORMAT "): ", p2i(fp + frame::interpreter_frame_method_offset)); method->print_short_name(st); st->cr();
    st->print_cr("\tcode_size: %d",         method->code_size());
    // st->print_cr("base: " INTPTR_FORMAT " end: " INTPTR_FORMAT, p2i(method->constMethod()->code_base()), p2i(method->constMethod()->code_end()));
    intptr_t** link_address = (intptr_t**)(fp + frame::link_offset);
    st->print_cr("\tlink: " INTPTR_FORMAT " (at: " INTPTR_FORMAT ")",    p2i(*link_address), p2i(link_address));
    st->print_cr("\treturn_pc: " INTPTR_FORMAT,        p2i(*(void**)(fp + frame::return_addr_offset)));
    st->print_cr("\tssp: " INTPTR_FORMAT,              p2i((void*)  (fp + frame::sender_sp_offset)));
    st->print_cr("\tissp: " INTPTR_FORMAT,             p2i(*(void**)(fp + frame::interpreter_frame_sender_sp_offset)));
    st->print_cr("\tlast_sp: " INTPTR_FORMAT,          p2i(*(void**)(fp + frame::interpreter_frame_last_sp_offset)));
    st->print_cr("\tinitial_sp: " INTPTR_FORMAT,       p2i(*(void**)(fp + frame::interpreter_frame_initial_sp_offset)));
    // st->print_cr("\tmon_block_top: " INTPTR_FORMAT,    p2i(*(void**)(fp + frame::interpreter_frame_monitor_block_top_offset)));
    // st->print_cr("\tmon_block_bottom: " INTPTR_FORMAT, p2i(*(void**)(fp + frame::interpreter_frame_monitor_block_bottom_offset)));
    st->print_cr("\tlocals: " INTPTR_FORMAT,           p2i(*(void**)(fp + frame::interpreter_frame_locals_offset)));
    st->print_cr("\texpression_stack_size: %d", f.interpreter_frame_expression_stack_size());
    // st->print_cr("\tcomputed expression_stack_size: %d", interpreter_frame_expression_stack_size(f));
    st->print_cr("\tcache: " INTPTR_FORMAT,            p2i(*(void**)(fp + frame::interpreter_frame_cache_offset)));
    st->print_cr("\tbcp: " INTPTR_FORMAT,              p2i(*(void**)(fp + frame::interpreter_frame_bcp_offset)));
    st->print_cr("\tbci: %d",               method->bci_from(*(address*)(fp + frame::interpreter_frame_bcp_offset)));
    st->print_cr("\tmirror: " INTPTR_FORMAT,           p2i(*(void**)(fp + frame::interpreter_frame_mirror_offset)));
    // st->print("\tmirror: "); os::print_location(st, *(intptr_t*)(fp + frame::interpreter_frame_mirror_offset), true);
    st->print("\treturn_pc: "); os::print_location(st, *(intptr_t*)(fp + frame::return_addr_offset));
  } else {
    st->print_cr("\tcompiled/C");
    if (f.is_compiled_frame())
      st->print_cr("\torig_pc: " INTPTR_FORMAT,    p2i(f.cb()->as_nmethod()->get_original_pc(&f)));
    // st->print_cr("\torig_pc_address: " INTPTR_FORMAT, p2i(f.cb()->as_nmethod()->orig_pc_addr(&f)));
    // st->print_cr("\tlink: " INTPTR_FORMAT,       p2i((void*)f.at(frame::link_offset)));
    // st->print_cr("\treturn_pc: " INTPTR_FORMAT,  p2i(*(void**)(fp + frame::return_addr_offset)));
    // st->print_cr("\tssp: " INTPTR_FORMAT,        p2i(*(void**)(fp + frame::sender_sp_offset)));
    st->print_cr("\tcb.size: %d",    f.cb()->frame_size());
    intptr_t** link_address = (intptr_t**)(f.real_fp() - frame::sender_sp_offset);
    st->print_cr("\tlink: " INTPTR_FORMAT " (at: " INTPTR_FORMAT ")", p2i(*link_address), p2i(link_address));
    st->print_cr("\t'real' return_pc: " INTPTR_FORMAT,  p2i(*(void**)(f.real_fp() - 1)));
    st->print("\t'real' return_pc: "); os::print_location(st, *(intptr_t*)(f.real_fp() - 1));
    // st->print("\treturn_pc: "); os::print_location(st, *(intptr_t*)(fp + frame::return_addr_offset));
  }
  if (false && map != NULL) {
    intptr_t* bottom = frame_bottom(f);
    intptr_t* usp = frame_top(f);
    long fsize = (address)bottom - (address)usp;
    st->print_cr("\tsize: %ld", fsize);
    st->print_cr("\tbounds: " INTPTR_FORMAT " - " INTPTR_FORMAT, p2i(usp), p2i(bottom));

    if (false) {
      st->print_cr("--data--");
      for(int i=0; i<fsize; i++)
        st->print_cr(INTPTR_FORMAT ": %x", p2i((address)usp + i), *((address)usp + i));
      st->print_cr("--end data--");
    }
  }
}

static void print_frames(JavaThread* thread, outputStream* st) {
  if (st != NULL && !log_is_enabled(Trace, jvmcont) ) return;
  if (st == NULL) st = tty;

  if (true) {
    st->print_cr("------- frames ---------");
    RegisterMap map(thread, false);
  #ifndef PRODUCT
    ResourceMark rm;
    FrameValues values;
  #endif

    int i = 0;
    for (frame f = thread->last_frame(); !f.is_entry_frame(); f = f.sender(&map)) {
  #ifndef PRODUCT
      f.describe(values, i);
  #else
      print_vframe(f, &map, st);
  #endif
      i++;
    }
  #ifndef PRODUCT
    values.print(thread);
  #endif
    st->print_cr("======= end frames =========");
  }
}

void ContMirror::print_hframes(outputStream* st) {
  if (st != NULL && !log_is_enabled(Trace, jvmcont)) return;
  if (st == NULL) st = tty;

  st->print_cr("------- hframes ---------");
  st->print_cr("sp: %d length: %d", _sp, _stack_length);
  int i = 0;
  for (hframe f = last_frame(); !f.is_empty(); f = f.sender(*this)) {
    st->print_cr("frame: %d", i);
    f.print_on(*this, st);
    i++;
  }
  st->print_cr("======= end hframes =========");
}

#ifdef ASSERT
// Does a reverse lookup of a RegisterMap. Returns the register, if any, spilled at the given address.
static VMReg find_register_spilled_here(void* p, RegisterMap* map) {
  for(int i = 0; i < RegisterMap::reg_count; i++) {
    VMReg r = VMRegImpl::as_VMReg(i);
    if (p == map->location(r)) return r;
  }
  return NULL;
}
#endif

volatile long Continuations::_exploded_miss = 0;
volatile long Continuations::_exploded_hit = 0;
volatile long Continuations::_nmethod_miss = 0;
volatile long Continuations::_nmethod_hit = 0;

void Continuations::exploded_miss() {
  //Atomic::inc(&_exploded_miss);
}

void Continuations::exploded_hit() {
  //Atomic::inc(&_exploded_hit);
}

void Continuations::nmethod_miss() {
  //Atomic::inc(&_nmethod_miss);
}

void Continuations::nmethod_hit() {
  //Atomic::inc(&_nmethod_hit);
}

void Continuations::print_statistics() {
  //tty->print_cr("Continuations hit/miss %ld / %ld", _exploded_hit, _exploded_miss);
  //tty->print_cr("Continuations nmethod hit/miss %ld / %ld", _nmethod_hit, _nmethod_miss);
}

JVM_ENTRY(void, CONT_Clean(JNIEnv* env, jobject jcont)) {
    JavaThread* thread = JavaThread::thread_from_jni_environment(env);
    oop oopCont = JNIHandles::resolve_non_null(jcont);
    ContMirror cont(thread, oopCont);
    cont.read();
    cont.cleanup();
}
JVM_END

#define CC (char*)  /*cast a literal from (const char*)*/
#define FN_PTR(f) CAST_FROM_FN_PTR(void*, &f)

static JNINativeMethod CONT_methods[] = {
    {CC"clean0",           CC"()V",        FN_PTR(CONT_Clean)},
};

void CONT_RegisterNativeMethods(JNIEnv *env, jclass cls) {
    Thread* thread = Thread::current();
    assert(thread->is_Java_thread(), "");
    ThreadToNativeFromVM trans((JavaThread*)thread);
    int status = env->RegisterNatives(cls, CONT_methods, sizeof(CONT_methods)/sizeof(JNINativeMethod));
    guarantee(status == JNI_OK && !env->ExceptionOccurred(), "register java.lang.Continuation natives");
}

// copied from oopMap.cpp
// #ifndef PRODUCT
// static void trace_codeblob_maps(const frame *fr, const RegisterMap *reg_map) {
//   // Print oopmap and regmap
//   if (fr->is_interpreted_frame())
//     return;
//   tty->print_cr("------ ");
//   CodeBlob* cb = fr->cb();
//   const ImmutableOopMapSet* maps = cb->oop_maps();
//   const ImmutableOopMap* map = cb->oop_map_for_return_address(fr->pc());
//   map->print();
//   if( cb->is_nmethod() ) {
//     nmethod* nm = (nmethod*)cb;
//     // native wrappers have no scope data, it is implied
//     if (nm->is_native_method()) {
//       tty->print("bci: 0 (native)");
//     } else {
//       ScopeDesc* scope  = nm->scope_desc_at(fr->pc());
//       tty->print("bci: %d ",scope->bci());
//     }
//   }
//   tty->cr();
//   fr->print_on(tty);
//   tty->print("     ");
//   cb->print_value_on(tty);  tty->cr();
//   reg_map->print();
//   tty->print_cr("------ ");

// }
// #endif // PRODUCT

// #ifdef ASSERT
// #define JAVA_THREAD_OFFSET(field) tty->print_cr("JavaThread." #field " 0x%x", in_bytes(JavaThread:: cat2(field,_offset()) ))
// #define cat2(a,b)         cat2_hidden(a,b)
// #define cat2_hidden(a,b)  a ## b
// #define cat3(a,b,c)       cat3_hidden(a,b,c)
// #define cat3_hidden(a,b,c)  a ## b ## c

// static void print_JavaThread_offsets() {
//   JAVA_THREAD_OFFSET(threadObj);
//   JAVA_THREAD_OFFSET(jni_environment);
//   JAVA_THREAD_OFFSET(pending_jni_exception_check_fn);
//   JAVA_THREAD_OFFSET(last_Java_sp);
//   JAVA_THREAD_OFFSET(last_Java_pc);
//   JAVA_THREAD_OFFSET(frame_anchor);
//   JAVA_THREAD_OFFSET(callee_target);
//   JAVA_THREAD_OFFSET(vm_result);
//   JAVA_THREAD_OFFSET(vm_result_2);
//   JAVA_THREAD_OFFSET(thread_state);
//   JAVA_THREAD_OFFSET(saved_exception_pc);
//   JAVA_THREAD_OFFSET(osthread);
//   JAVA_THREAD_OFFSET(continuation);
//   JAVA_THREAD_OFFSET(exception_oop);
//   JAVA_THREAD_OFFSET(exception_pc);
//   JAVA_THREAD_OFFSET(exception_handler_pc);
//   JAVA_THREAD_OFFSET(stack_overflow_limit);
//   JAVA_THREAD_OFFSET(is_method_handle_return);
//   JAVA_THREAD_OFFSET(stack_guard_state);
//   JAVA_THREAD_OFFSET(reserved_stack_activation);
//   JAVA_THREAD_OFFSET(suspend_flags);
//   JAVA_THREAD_OFFSET(do_not_unlock_if_synchronized);
//   JAVA_THREAD_OFFSET(should_post_on_exceptions_flag);
// // #ifndef PRODUCT
// //   static ByteSize jmp_ring_index_offset()        { return byte_offset_of(JavaThread, _jmp_ring_index); }
// //   static ByteSize jmp_ring_offset()              { return byte_offset_of(JavaThread, _jmp_ring); }
// // #endif // PRODUCT
// // #if INCLUDE_JVMCI
// //   static ByteSize pending_deoptimization_offset() { return byte_offset_of(JavaThread, _pending_deoptimization); }
// //   static ByteSize pending_monitorenter_offset()  { return byte_offset_of(JavaThread, _pending_monitorenter); }
// //   static ByteSize pending_failed_speculation_offset() { return byte_offset_of(JavaThread, _pending_failed_speculation); }
// //   static ByteSize jvmci_alternate_call_target_offset() { return byte_offset_of(JavaThread, _jvmci._alternate_call_target); }
// //   static ByteSize jvmci_implicit_exception_pc_offset() { return byte_offset_of(JavaThread, _jvmci._implicit_exception_pc); }
// //   static ByteSize jvmci_counters_offset()        { return byte_offset_of(JavaThread, _jvmci_counters); }
// // #endif // INCLUDE_JVMCI
// }
// #endif
