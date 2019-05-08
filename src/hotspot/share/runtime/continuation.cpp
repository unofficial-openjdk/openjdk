/*
 * Copyright (c) 2018, 2019 Oracle and/or its affiliates. All rights reserved.
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
#include "code/codeCache.inline.hpp"
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
#include "metaprogramming/conditional.hpp"
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
#include "runtime/prefetch.inline.hpp"
#include "runtime/sharedRuntime.hpp"
#include "runtime/vframe_hp.hpp"
#include "utilities/copy.hpp"
#include "utilities/exceptions.hpp"
#include "utilities/macros.hpp"

#ifdef __has_include
#  if __has_include(<callgrind.h>)
#    include <callgrind.h>
#  endif
#endif
#ifdef CALLGRIND_START_INSTRUMENTATION
  static int callgrind_counter = 1;
#endif

// #undef ASSERT
// #undef assert
// #define assert(p, ...)

int Continuations::_flags = 0;

static int PERFTEST_LEVEL = ContPerfTest;
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

// debugging funstions
static void print_oop(void *p, oop obj, outputStream* st = tty);
static void print_vframe(frame f, const RegisterMap* map = NULL, outputStream* st = tty);
#ifdef ASSERT
static void print_frames(JavaThread* thread, outputStream* st = tty);
static long java_tid(JavaThread* thread);
static VMReg find_register_spilled_here(void* p, RegisterMap* map);
// void static stop();
// void static stop(const frame& f);
// static void print_JavaThread_offsets();
#endif
// NOT_PRODUCT(static void trace_codeblob_maps(const frame *fr, const RegisterMap *reg_map);)

#define HOB (1ULL << 63)

#define ELEM_SIZE sizeof(jint) // stack is int[]
#define ELEMS_PER_WORD (wordSize/sizeof(jint))

#define CHOOSE1(interp, f, ...) ((interp) ? Interpreted::f(__VA_ARGS__) : Compiled::f(__VA_ARGS__))
#define CHOOSE2(interp, f, ...) ((interp) ? f<Interpreted>(__VA_ARGS__) : f<Compiled>(__VA_ARGS__))

static const unsigned char FLAG_LAST_FRAME_INTERPRETED = 1;
static const unsigned char FLAG_SAFEPOINT_YIELD = 1 << 1;

void continuations_init() {
  Continuations::init();
}

class ContMirror;

class Frame {
public:
  static inline intptr_t** saved_link_address(const RegisterMap* map);
  static inline Method* frame_method(const frame& f);
  static inline address real_pc(const frame& f);
  static inline void patch_pc(frame& f, address pc);

  static inline intptr_t* frame_top(const frame &f); // TODO: remove
};

template<typename Self>
class FrameCommon : public Frame {
public:
  static inline Method* frame_method(const frame& f);

  static inline address return_pc(const frame& f);
  static void patch_return_pc(frame& f, address pc);
};

class Interpreted : public FrameCommon<Interpreted> {
public:
  static const bool interpreted = true;
  static const bool stub = false;

public:
  static inline address* return_pc_address(const frame& f);

  static inline intptr_t* frame_top(const frame& f, InterpreterOopMap* mask = NULL);
  static inline intptr_t* frame_bottom(const frame& f);

  static void patch_sender_sp(frame& f, intptr_t* sp);

  static void oop_map(const frame& f, InterpreterOopMap* mask);
  static inline int expression_stack_size(const frame &f, InterpreterOopMap* mask = NULL);
  static bool is_owning_locks(const frame& f);
};

class Compiled : public FrameCommon<Compiled>  {
public:
  static const bool interpreted = false;
  static const bool stub = false;

public:
  static inline intptr_t* frame_top(const frame& f);
  static inline intptr_t* frame_bottom(const frame& f);

  static inline address* return_pc_address(const frame& f);
  static bool is_owning_locks(JavaThread* thread, const RegisterMap* map, const frame& f);
};

class StubF : public Compiled {
public:
  static const bool stub = true;
};

// Represents a stack frame on the horizontal stack, analogous to the frame class, for vertical-stack frames.
// class hframe; // PD

#define PD static_cast<SelfPD&>(*this)
#define CONST_PD static_cast<const SelfPD&>(*this)

template<typename SelfPD>
class HFrameBase {
protected:
  int _sp;
  int _ref_sp;
  address _pc;
  mutable CodeBlob* _cb;
  mutable const ImmutableOopMap* _oop_map; // oop map, for compiled/stubs frames only
  bool _is_interpreted;
  // int _ref_length;

  friend class ContMirror;
private:
  const ImmutableOopMap* get_oop_map() const;
  template<typename FKind> address* return_pc_address() const { return CONST_PD.template return_pc_address<FKind>(); }

  void set_codeblob(address pc) {
    if (_cb == NULL && !_is_interpreted) {// compute lazily
      _cb = ContinuationCodeBlobLookup::find_blob(_pc);
      assert(_cb != NULL, "must be valid");
    }
  }

protected:
  HFrameBase() : _sp(-1), _ref_sp(-1), _pc(NULL), _cb(NULL), _oop_map(NULL), _is_interpreted(true) {}

  HFrameBase(const HFrameBase& hf) : _sp(hf._sp), _ref_sp(hf._ref_sp), _pc(hf._pc), 
                                     _cb(hf._cb), _oop_map(hf._oop_map), _is_interpreted(hf._is_interpreted) {}

  HFrameBase(int sp, int ref_sp, address pc, ContMirror& cont)
    : _sp(sp), _ref_sp(ref_sp), _pc(pc), 
      _oop_map(NULL), _is_interpreted(Interpreter::contains(pc)) {
      _cb = NULL;
      set_codeblob(_pc);
    }

  HFrameBase(int sp, int ref_sp, address pc, CodeBlob* cb, bool is_interpreted, ContMirror& cont)
    : _sp(sp), _ref_sp(ref_sp), _pc(pc), 
      _cb(cb), _oop_map(NULL), _is_interpreted(is_interpreted) {}

  HFrameBase(int sp, int ref_sp, address pc, bool is_interpreted, ContMirror& cont)
    : _sp(sp), _ref_sp(ref_sp), _pc(pc), 
      _oop_map(NULL), _is_interpreted(is_interpreted) {
      _cb = NULL;
      set_codeblob(_pc); // TODO: lazify
    }

  HFrameBase(int sp, int ref_sp, address pc, CodeBlob* cb, bool is_interpreted) // called by ContMirror::new_hframe
    : _sp(sp), _ref_sp(ref_sp), _pc(pc),
      _cb(cb), _oop_map(NULL), _is_interpreted(is_interpreted) {}

public:
  inline bool operator==(const HFrameBase& other);
  bool is_empty() const { return _pc == NULL && _sp < 0; }

  inline bool is_interpreted_frame() const { return _is_interpreted; }
  inline int       sp()     const { return _sp; }
  inline address   pc()     const { return _pc; }
  inline int       ref_sp() const { return _ref_sp; }
  CodeBlob* cb() const { return _cb; }

  template<typename FKind> address return_pc() const { return *return_pc_address<FKind>(); }

  const ImmutableOopMap* oop_map() const {
    if (_oop_map == NULL) {
      _oop_map = get_oop_map();
    }
    return _oop_map;
  }

  template<typename FKind> int frame_top_index() const;
  template<typename FKind> int frame_bottom_index() const { return CONST_PD.template frame_bottom_index<FKind>(); };

  template<typename FKind> inline void patch_return_pc(address value);

  template <typename FKind> int compiled_frame_size(int* argsize, int* num_oops) const;
  template <typename FKind> int compiled_frame_stack_argsize() const;

  DEBUG_ONLY(int interpreted_frame_top_index() const { return CONST_PD.interpreted_frame_top_index(); } )
  int interpreted_frame_num_monitors() const         { return CONST_PD.interpreted_frame_num_monitors(); }
  int interpreted_frame_num_oops(const InterpreterOopMap& mask) const;
  int interpreted_frame_size(const InterpreterOopMap& mask, int* num_oops) const;
  void interpreted_frame_oop_map(InterpreterOopMap* mask) const { CONST_PD.interpreted_frame_oop_map(mask); }

  template<typename FKind> SelfPD sender(ContMirror& cont, int num_oops) const { return CONST_PD.template sender<FKind>(cont, num_oops); }
  template<typename FKind> SelfPD sender(ContMirror& cont, InterpreterOopMap* mask) const;
  SelfPD sender(ContMirror& cont) const;

  template<typename FKind> bool is_bottom(const ContMirror& cont) const;

  address interpreter_frame_bcp() const                             { return CONST_PD.interpreter_frame_bcp(); }
  intptr_t* interpreter_frame_local_at(int index) const             { return CONST_PD.interpreter_frame_local_at(index); }
  intptr_t* interpreter_frame_expression_stack_at(int offset) const { return CONST_PD.interpreter_frame_expression_stack_at(offset); }

  template<typename FKind> Method* method() const;

  inline frame to_frame(ContMirror& cont) const;

  void print_on(ContMirror& cont, outputStream* st) const { CONST_PD.print_on(cont, st); }
  void print_on(outputStream* st) const { CONST_PD.print_on(st); };
  void print(ContMirror& cont) const { print_on(cont, tty); }
  void print() const { print_on(tty); }
};

// defines hframe
#include CPU_HEADER(hframe)

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
  intptr_t _fp;
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

private:
  int* stack() const { return _hstack; }

  inline void post_safepoint(Handle conth);
  oop raw_allocate(Klass* klass, const size_t words, const size_t elements, bool zero);
  typeArrayOop allocate_stack_array(const size_t elements);
  objArrayOop  allocate_refstack_array(const size_t nr_oops);
  bool allocate_stack(int size);
  bool allocate_ref_stack(int nr_oops);
  bool allocate_stacks_in_native(int size, int oops, bool needs_stack, bool needs_refstack);
  void allocate_stacks_in_java(int size, int oops, int frames);
  bool grow_stack(int new_size);
  bool grow_ref_stack(int nr_oops);
  int fix_decreasing_index(int index, int old_length, int new_length) const;
  int ensure_capacity(int old, int min);

public:
  static inline int to_index(size_t x) { return x >> 2; } // stack is int[]
  static inline int to_bytes(int x)    { return x << 2; } // stack is int[]
  static inline int to_index(void* base, void* ptr) { return to_index((char*)ptr - (char*)base); }

private:
  ContMirror(const ContMirror& cont); // no copy constructor

  void read();

public:
  ContMirror(JavaThread* thread, oop cont);
  ContMirror(const RegisterMap* map);

  DEBUG_ONLY(intptr_t hash() { return _cont->identity_hash(); })
  void write();

  oop mirror() { return _cont; }
  void cleanup();

  intptr_t* entrySP() { return _entrySP; }
  intptr_t* entryFP() { return _entryFP; }
  address   entryPC() { return _entryPC; }

  bool is_mounted() { return _entryPC != NULL; }

  void set_entrySP(intptr_t* sp) { _entrySP = sp; }
  void set_entryFP(intptr_t* fp) { _entryFP = fp; }
  void set_entryPC(address pc)   {
    log_develop_trace(jvmcont)("set_entryPC " INTPTR_FORMAT, p2i(pc));
    _entryPC = pc;
  }

  int sp() const           { return _sp; }
  intptr_t fp() const      { return _fp; }
  address pc() const       { return _pc; }

  void set_sp(int sp)      { _sp = sp; }
  void set_fp(intptr_t fp) { _fp = fp; }
  void set_pc(address pc)  { _pc = pc; set_flag(FLAG_LAST_FRAME_INTERPRETED, Interpreter::contains(pc));  }

  bool is_flag(unsigned char flag) { return (_flags & flag) != 0; }
  void set_flag(unsigned char flag, bool v) { _flags = (v ? _flags |= flag : _flags &= ~flag); }

  int stack_length() const { return _stack_length; }

  JavaThread* thread() const { return _thread; }

  inline void allocate_stacks(int size, int oops, int frames);
  inline bool in_hstack(void *p) { return (_hstack != NULL && p >= _hstack && p < (_hstack + _stack_length)); }

  bool valid_stack_index(int idx) const { return idx >= 0 && idx < _stack_length; }

  void copy_to_stack(void* from, void* to, int size);
  void copy_from_stack(void* from, void* to, int size);

  objArrayOop refStack(int size);
  objArrayOop refStack() { return _ref_stack; }
  int refSP() { return _ref_sp; }
  void set_refSP(int refSP) { log_develop_trace(jvmcont)("set_refSP: %d", refSP); _ref_sp = refSP; }

  inline int stack_index(void* p) const;
  inline intptr_t* stack_address(int i) const;

  static inline void relativize(intptr_t* const fp, intptr_t* const hfp, int offset);
  static inline void derelativize(intptr_t* const fp, int offset);

  bool is_in_stack(void* p) const ;
  bool is_in_ref_stack(void* p) const;

  bool is_map_at_top(RegisterMap& map);

  bool is_empty();

  hframe last_frame();
  inline void set_last_frame(hframe& f);

  hframe from_frame(const frame& f);

  template <typename ConfigT>
  inline int add_oop(oop obj, int index);
  // inline void add_oop_location(oop* p);
  // inline void add_oop_location(narrowOop* p);

  inline oop obj_at(int i);
  int num_oops();
  void null_ref_stack(int start, int num);

  inline size_t max_size() { return _max_size; }
  inline void add_size(size_t s) { log_develop_trace(jvmcont)("add max_size: " SIZE_FORMAT " s: " SIZE_FORMAT, _max_size + s, s);
                                   _max_size += s; }
  inline void sub_size(size_t s) { log_develop_trace(jvmcont)("sub max_size: " SIZE_FORMAT " s: " SIZE_FORMAT, _max_size - s, s);
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

template<typename SelfPD>
inline bool HFrameBase<SelfPD>::operator==(const HFrameBase& other) {
  return  _sp == other._sp && _pc == other._pc;
}

template<typename SelfPD>
const ImmutableOopMap* HFrameBase<SelfPD>::get_oop_map() const {
  if (_cb == NULL) return NULL;
  if (_cb->oop_maps() != NULL) {
    NativePostCallNop* nop = nativePostCallNop_at(_pc);
    if (nop != NULL && nop->displacement() != 0) {
      int slot = ((nop->displacement() >> 24) & 0xff);
      return _cb->oop_map_for_slot(slot, _pc);
    }
    const ImmutableOopMap* oop_map = OopMapSet::find_map(cb(), pc());
    return oop_map;
  }
  return NULL;
}

template<typename SelfPD>
template<typename FKind>
inline void HFrameBase<SelfPD>::patch_return_pc(address value) {
  *(PD.template return_pc_address<FKind>()) = value;
}

template<typename SelfPD>
template<typename FKind>
bool HFrameBase<SelfPD>::is_bottom(const ContMirror& cont) const {
  return frame_bottom_index<FKind>() 
    + ((FKind::interpreted || FKind::stub) ? 0 : cb()->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size / (int)ELEM_SIZE)
    >= cont.stack_length();
}

template<typename SelfPD>
int HFrameBase<SelfPD>::interpreted_frame_num_oops(const InterpreterOopMap& mask) const {
  assert (_is_interpreted, "");
  // we calculate on relativized metadata; all monitors must be NULL on hstack, but as f.oops_do walks them, we count them
  return mask.num_oops()
     + 1 // for the mirror
     + interpreted_frame_num_monitors();  
}

template<typename SelfPD>
int HFrameBase<SelfPD>::interpreted_frame_size(const InterpreterOopMap& mask, int* num_oops) const { 
  assert (_is_interpreted, "");
  // we calculate on relativized metadata; all monitors must be NULL on hstack, but as f.oops_do walks them, we count them
  *num_oops = interpreted_frame_num_oops(mask);
  int size = (frame_bottom_index<Interpreted>() - frame_top_index<Interpreted>()) * ELEM_SIZE;
  return size;
}

template<typename SelfPD>
template <typename FKind>
int HFrameBase<SelfPD>::compiled_frame_stack_argsize() const {
  return FKind::stub ? 0 : cb()->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size;
}

template<typename SelfPD>
template <typename FKind>
int HFrameBase<SelfPD>::compiled_frame_size(int* argsize, int* num_oops) const {
  assert (!_is_interpreted, "");
  *argsize = compiled_frame_stack_argsize<FKind>();
  *num_oops = oop_map()->num_oops();
  return cb()->frame_size() * wordSize;
}

template<typename SelfPD>
template <typename FKind>
int HFrameBase<SelfPD>::frame_top_index() const {
  // _sp may be less than the above calculation when thawing an interpreted frame with lazy copy when the compiled callee has stack arguments
  // because we go through the return barrier, there callee_argsize would be zero, but the callee did not remove the arguments from the hstack.
  assert (!FKind::interpreted || interpreted_frame_top_index() >= _sp, "");
  assert (FKind::interpreted == _is_interpreted, "");

  return _sp;
}

template<typename SelfPD>
template<typename FKind>
SelfPD HFrameBase<SelfPD>::sender(ContMirror& cont, InterpreterOopMap* mask) const {
  return sender<FKind>(cont, FKind::interpreted ? interpreted_frame_num_oops(*mask) : oop_map()->num_oops());
}

template<typename SelfPD>
SelfPD HFrameBase<SelfPD>::sender(ContMirror& cont) const { 
  if (_is_interpreted) {
    InterpreterOopMap mask;
    interpreted_frame_oop_map(&mask);
    return sender<Interpreted>(cont, &mask);
  } else {
    return sender<Compiled>(cont, (InterpreterOopMap*)NULL);
  }
}

template<>
template<> Method* HFrameBase<hframe>::method<Interpreted>() const; // pd

template<typename SelfPD>
template<typename FKind>
Method* HFrameBase<SelfPD>::method() const {
  assert (!is_interpreted_frame(), "");
  assert (!FKind::interpreted, "");

  return ((CompiledMethod*)cb())->method();
}

template<typename SelfPD>
inline frame HFrameBase<SelfPD>::to_frame(ContMirror& cont) const {
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

  // tty->print_cr("-- to_frame:");
  // print_on(cont, tty);
  return CONST_PD.to_frame(cont, pc, deopt);
}

ContMirror::ContMirror(JavaThread* thread, oop cont)
 : _thread(thread), _cont(cont),
   _e_num_interpreted_frames(0), _e_num_frames(0), _e_num_refs(0), _e_size(0) {
  assert(_cont != NULL && oopDesc::is_oop_or_null(_cont), "Invalid cont: " INTPTR_FORMAT, p2i((void*)_cont));

  read();
}

ContMirror::ContMirror(const RegisterMap* map)
 : _thread(map->thread()), _cont(map->cont()),
   _e_num_interpreted_frames(0), _e_num_frames(0), _e_num_refs(0), _e_size(0) {
  assert(_cont != NULL && oopDesc::is_oop_or_null(_cont), "Invalid cont: " INTPTR_FORMAT, p2i((void*)_cont));

  read();
}

void ContMirror::read() {
  _entrySP = java_lang_Continuation::entrySP(_cont);
  _entryFP = java_lang_Continuation::entryFP(_cont);
  _entryPC = java_lang_Continuation::entryPC(_cont);

  _sp = java_lang_Continuation::sp(_cont);
  _fp = (intptr_t)java_lang_Continuation::fp(_cont);
  _pc = (address)java_lang_Continuation::pc(_cont);

  _stack = java_lang_Continuation::stack(_cont);
  if (_stack != NULL) {
    _stack_length = _stack->length();
    _hstack = (int*)_stack->base(T_INT);
  } else {
    _stack_length = 0;
    _hstack = NULL;
  }
  _max_size = java_lang_Continuation::maxSize(_cont);

  _ref_stack = java_lang_Continuation::refStack(_cont);
  _ref_sp = java_lang_Continuation::refSP(_cont);

  _flags = java_lang_Continuation::flags(_cont);

  _num_frames = java_lang_Continuation::numFrames(_cont);
  _num_interpreted_frames = java_lang_Continuation::numInterpretedFrames(_cont);

  if (log_develop_is_enabled(Trace, jvmcont)) {
    log_develop_trace(jvmcont)("Reading continuation object:");
    log_develop_trace(jvmcont)("\tentrySP: " INTPTR_FORMAT " entryFP: " INTPTR_FORMAT " entryPC: " INTPTR_FORMAT, p2i(_entrySP), p2i(_entryFP), p2i(_entryPC));
    log_develop_trace(jvmcont)("\tsp: %d fp: %ld 0x%lx pc: " INTPTR_FORMAT, _sp, _fp, _fp, p2i(_pc));
    log_develop_trace(jvmcont)("\tstack: " INTPTR_FORMAT " hstack: " INTPTR_FORMAT ", stack_length: %d max_size: " SIZE_FORMAT, p2i((oopDesc*)_stack), p2i(_hstack), _stack_length, _max_size);
    log_develop_trace(jvmcont)("\tref_stack: " INTPTR_FORMAT " ref_sp: %d", p2i((oopDesc*)_ref_stack), _ref_sp);
    log_develop_trace(jvmcont)("\tflags: %d", _flags);
    log_develop_trace(jvmcont)("\tnum_frames: %d", _num_frames);
    log_develop_trace(jvmcont)("\tnum_interpreted_frames: %d", _num_interpreted_frames);
  }
}

void ContMirror::write() {
  if (log_develop_is_enabled(Trace, jvmcont)) {
    log_develop_trace(jvmcont)("Writing continuation object:");
    log_develop_trace(jvmcont)("\tsp: %d fp: %ld 0x%lx pc: " INTPTR_FORMAT, _sp, _fp, _fp, p2i(_pc));
    log_develop_trace(jvmcont)("\tentrySP: " INTPTR_FORMAT " entryFP: " INTPTR_FORMAT " entryPC: " INTPTR_FORMAT, p2i(_entrySP), p2i(_entryFP), p2i(_entryPC));
    log_develop_trace(jvmcont)("\tmax_size: " SIZE_FORMAT, _max_size);
    log_develop_trace(jvmcont)("\tref_sp: %d", _ref_sp);
    log_develop_trace(jvmcont)("\tflags: %d", _flags);
    log_develop_trace(jvmcont)("\tnum_frames: %d", _num_frames);
    log_develop_trace(jvmcont)("\tnum_interpreted_frames: %d", _num_interpreted_frames);
    log_develop_trace(jvmcont)("\tend write");
  }

  java_lang_Continuation::set_sp(_cont, _sp);
  java_lang_Continuation::set_fp(_cont, _fp);
  java_lang_Continuation::set_pc(_cont, _pc);
  java_lang_Continuation::set_refSP(_cont, _ref_sp);

  java_lang_Continuation::set_entrySP(_cont, _entrySP);
  java_lang_Continuation::set_entryFP(_cont, _entryFP);
  java_lang_Continuation::set_entryPC(_cont, _entryPC);

  java_lang_Continuation::set_maxSize(_cont, (jint)_max_size); 
  java_lang_Continuation::set_flags(_cont, _flags);

  java_lang_Continuation::set_numFrames(_cont, _num_frames);
  java_lang_Continuation::set_numInterpretedFrames(_cont, _num_interpreted_frames);
}

void ContMirror::cleanup() {
  // cleanup nmethods
  for (hframe hf = last_frame(); !hf.is_empty(); hf = hf.sender(*this)) {
    if (!hf.is_interpreted_frame())
      hf.cb()->as_compiled_method()->dec_on_continuation_stack();
  }
}

void ContMirror::null_ref_stack(int start, int num) {
  if (java_lang_Continuation::is_reset(_cont)) return;
  
  for (int i = 0; i < num; i++)
    _ref_stack->obj_at_put(start + i, NULL);
}

bool ContMirror::is_empty() {
  return _sp < 0 || _sp >= _stack->length();
}

inline void ContMirror::set_last_frame(hframe& f) {
  // assert (f._length = _stack_length, "");
  set_sp(f.sp()); set_fp(f.fp()); set_pc(f.pc());
  // if (f.ref_sp() != -1) // frames' ref_sp is invalid during freeze
  //   set_refSP(f.ref_sp());
  if (is_empty()) {
    if (_stack_length > 0) {
      set_sp(_stack_length);
      // set_refSP(_ref_stack->length());
    }
    set_fp(0);
    set_pc(NULL);
  }

  if (log_develop_is_enabled(Trace, jvmcont)) {
    log_develop_trace(jvmcont)("set_last_frame cont sp: %d fp: 0x%lx pc: " INTPTR_FORMAT " interpreted: %d flag: %d", sp(), fp(), p2i(pc()), f.is_interpreted_frame(), is_flag(FLAG_LAST_FRAME_INTERPRETED));
    f.print_on(*this, tty);
  }
}

bool ContMirror::is_in_stack(void* p) const {
  return p >= (stack() + _sp) && p < (stack() + stack_length());
}

bool ContMirror::is_in_ref_stack(void* p) const {
  void* base = _ref_stack->base();
  int length = _ref_stack->length();

  return p >= (UseCompressedOops ? (address)&((narrowOop*)base)[_ref_sp]
                                 : (address)&(      (oop*)base)[_ref_sp]) &&
         p <= (UseCompressedOops ? (address)&((narrowOop*)base)[length-1]
                                 : (address)&(      (oop*)base)[length-1]);

   // _ref_stack->obj_at_addr<narrowOop>(_ref_sp) : (address)_ref_stack->obj_at_addr<oop>(_ref_sp));
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

inline void ContMirror::relativize(intptr_t* const fp, intptr_t* const hfp, int offset) {
  intptr_t* addr = (hfp + offset);
  intptr_t value = to_index((address)*(hfp + offset) - (address)fp);
  *addr = value;
}

inline void ContMirror::derelativize(intptr_t* const fp, int offset) {
  *(fp + offset) = (intptr_t)((address)fp + to_bytes(*(long*)(fp + offset)));
}

void ContMirror::copy_to_stack(void* from, void* to, int size) {
  log_develop_trace(jvmcont)("Copying from v: " INTPTR_FORMAT " - " INTPTR_FORMAT " (%d bytes)", p2i(from), p2i((address)from + size), size);
  log_develop_trace(jvmcont)("Copying to h: " INTPTR_FORMAT " - " INTPTR_FORMAT " (%d - %d)", p2i(to), p2i((address)to + size), to_index(_hstack, to), to_index(_hstack, (address)to + size));

  assert (size > 0, "size: %d", size);
  assert (stack_index(to) >= 0, "");
  assert (to_index(_hstack, (address)to + size) <= _sp, "");

  // this assertion is just to check whether the copying happens as intended, but not otherwise required for this method.
  //assert (write_stack_index(to) == _wsp, "to: %d wsp: %d", write_stack_index(to), _wsp);

  if (PERFTEST_LEVEL >= 25) {
    memcpy(to, from, size);
    //Copy::conjoint_memory_atomic(from, to, size); // Copy::disjoint_words((HeapWord*)from, (HeapWord*)to, size/wordSize); // 
  }

  _e_size += size;
}

void ContMirror::copy_from_stack(void* from, void* to, int size) {
  log_develop_trace(jvmcont)("Copying from h: " INTPTR_FORMAT " - " INTPTR_FORMAT " (%d - %d)", p2i(from), p2i((address)from + size), to_index(stack(), from), to_index(stack(), (address)from + size));
  log_develop_trace(jvmcont)("Copying to v: " INTPTR_FORMAT " - " INTPTR_FORMAT " (%d bytes)", p2i(to), p2i((address)to + size), size);

  assert (size > 0, "size: %d", size);
  assert (stack_index(from) >= 0, "");
  assert (to_index(stack(), (address)from + size) <= stack_length(), "index: %d length: %d", to_index(stack(), (address)from + size), stack_length());

  memcpy(to, from, size);
  //Copy::conjoint_memory_atomic(from, to, size);

  _e_size += size;
}

hframe ContMirror::from_frame(const frame& f) {
  return hframe(f.cont_sp(), f.cont_ref_sp(), reinterpret_cast<long>(f.fp()), f.pc(), f.cb(), 
          f.is_interpreted_frame(), *this);
}

template <typename ConfigT>
inline int ContMirror::add_oop(oop obj, int index) {
  assert (index < _ref_sp, "");
  log_develop_trace(jvmcont)("i: %d ", index);
  ConfigT::OopWriterT::obj_at_put(_ref_stack, index, obj);
  return index;
}

// inline void ContMirror::add_oop_location(oop* p) {
//   log_develop_trace(jvmcont)("i: %d (oop)", _num_oops);
//   assert (_num_oops < _wstack_length / 2 - 1, "");
//   assert ((((unsigned long)p) & HOB) == 0, "HOB on: " INTPTR_FORMAT, p2i(p));
//   _oops[_num_oops] = (oopLoc){false, (unsigned long)p};
//   _num_oops++;
// }

// inline void ContMirror::add_oop_location(narrowOop* p) {
//   log_develop_trace(jvmcont)("i: %d (narrow)", _num_oops);
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

bool ContMirror::is_map_at_top(RegisterMap& map) {
  return (map.location(rbp->as_VMReg()) == (address)&_fp);
}

template<typename Event> void ContMirror::post_jfr_event(Event* e) {
  if (e->should_commit()) {
    log_develop_trace(jvmcont)("JFR event: frames: %d iframes: %d size: %d refs: %d", _e_num_frames, _e_num_interpreted_frames, _e_size, _e_num_refs);
    e->set_contClass(_cont->klass());
    e->set_numFrames(_e_num_frames);
    e->set_numIFrames(_e_num_interpreted_frames);
    e->set_size(_e_size);
    e->set_numRefs(_e_num_refs);
    e->commit();
  }
}

//////////////////////////// frame functions ///////////////

class ContinuationHelper {
public:
  template<typename FKind> static inline void update_register_map(RegisterMap* map, const frame& f);
  static inline frame last_frame(JavaThread* thread);
  static inline void to_frame_info(const frame& f, const frame& callee, FrameInfo* fi);
  static inline void to_frame_info_pd(const frame& f, const frame& callee, FrameInfo* fi);
  static inline frame to_frame(FrameInfo* fi);
  static inline void set_last_vstack_frame(RegisterMap* map, const frame& callee);
};

#ifdef ASSERT
  static char* method_name(Method* m);
  static inline Method* top_java_frame_method(const frame& f);
  static inline Method* bottom_java_frame_method(const frame& f);
  static char* top_java_frame_name(frame& f);
  static char* bottom_java_frame_name(frame& f);
  static inline bool is_deopt_return(address pc, frame& sender);
#endif


inline Method* Frame::frame_method(const frame& f) {
  Method* m = NULL;
  if (f.is_interpreted_frame())
    m = f.interpreter_frame_method();
  else if (f.is_compiled_frame())
    m = ((CompiledMethod*)f.cb())->method();
  return m;
}

template<typename Self>
inline address FrameCommon<Self>::return_pc(const frame& f) {
  return *Self::return_pc_address(f);
}

template<typename Self>
void FrameCommon<Self>::patch_return_pc(frame& f, address pc) {
  *Self::return_pc_address(f) = pc;
  log_develop_trace(jvmcont)("patched return_pc at " INTPTR_FORMAT ": " INTPTR_FORMAT, p2i(Self::return_pc_address(f)), p2i(pc));
}


// static void patch_interpreted_bci(frame& f, int bci) {
//   f.interpreter_frame_set_bcp(f.interpreter_frame_method()->bcp_from(bci));
// }

void Interpreted::oop_map(const frame& f, InterpreterOopMap* mask) {
  Method* m = f.interpreter_frame_method();
  int   bci = f.interpreter_frame_bci();
  m->mask_for(bci, mask); // OopMapCache::compute_one_oop_map(m, bci, mask);
}

inline int Interpreted::expression_stack_size(const frame &f, InterpreterOopMap* mask) {
  int size;
  if (mask == NULL) {
    InterpreterOopMap mask0;
    mask = &mask0;
    oop_map(f, mask);
    size = mask->expression_stack_size();
  } else {
    size = mask->expression_stack_size();
  }
  assert (size <= f.interpreter_frame_expression_stack_size(), "size1: %d size2: %d", size, f.interpreter_frame_expression_stack_size());
  return size;
}

inline intptr_t* Compiled::frame_top(const frame& f) { // inclusive; this will be copied with the frame
  return f.unextended_sp();
}

inline intptr_t* Compiled::frame_bottom(const frame& f) { // exclusive; this will not be copied with the frame
  return f.unextended_sp() + f.cb()->frame_size();
}

intptr_t* Frame::frame_top(const frame &f) {
  return f.is_interpreted_frame() ? Interpreted::frame_top(f) : Compiled::frame_top(f);
}

bool Interpreted::is_owning_locks(const frame& f) {
  assert (f.interpreter_frame_monitor_end() <= f.interpreter_frame_monitor_begin(), "must be");
  if (f.interpreter_frame_monitor_end() == f.interpreter_frame_monitor_begin())
    return false;

  for (BasicObjectLock* current = f.previous_monitor_in_interpreter_frame(f.interpreter_frame_monitor_begin());
        current >= f.interpreter_frame_monitor_end();
        current = f.previous_monitor_in_interpreter_frame(current)) {

      oop obj = current->obj();
      if (obj != NULL) {
        return true;
      }
  }
  return false;
}

bool Compiled::is_owning_locks(JavaThread* thread, const RegisterMap* map, const frame& f) {
  if (!DetectLocksInCompiledFrames)
    return false;
  CompiledMethod* cm = f.cb()->as_compiled_method();
  assert (!cm->is_compiled() || !cm->as_compiled_method()->is_native_method(), ""); // ??? See compiledVFrame::compiledVFrame(...) in vframe_hp.cpp

  if (!cm->has_monitors()) {
    return false;
  }

  ResourceMark rm(thread);
  for (ScopeDesc* scope = cm->scope_desc_at(f.pc()); scope != NULL; scope = scope->sender()) {
    GrowableArray<MonitorValue*>* mons = scope->monitors();
    if (mons == NULL || mons->is_empty())
      continue;

    for (int index = (mons->length()-1); index >= 0; index--) { // see compiledVFrame::monitors()
      MonitorValue* mon = mons->at(index);
      if (mon->eliminated())
        continue; // TODO: are we fine with this or should we return true?
      ScopeValue* ov = mon->owner();
      StackValue* owner_sv = StackValue::create_stack_value(&f, map, ov); // it is an oop
      oop owner = owner_sv->get_obj()();
      if (owner != NULL) {
        return true;
      }
    }
  }
  return false;
}

////////////////////////////////////

static RegisterMap dmap(NULL, false); // global dummy RegisterMap

// works only in thaw
static inline bool is_entry_frame(ContMirror& cont, frame& f) {
  return f.sp() == cont.entrySP();
}

static bool is_stub(CodeBlob* cb) {
  return cb != NULL && (cb->is_safepoint_stub() || cb->is_runtime_stub());
}

static int num_java_frames(CompiledMethod* cm, address pc) {
  int count = 0;
  for (ScopeDesc* scope = cm->scope_desc_at(pc); scope != NULL; scope = scope->sender())
    count++;
  return count;
}

static int num_java_frames(const hframe& f) {
  return f.is_interpreted_frame() ? 1 : num_java_frames(f.cb()->as_compiled_method(), f.pc());
}

static int num_java_frames(ContMirror& cont) {
  int count = 0;
  for (hframe hf = cont.last_frame(); !hf.is_empty(); hf = hf.sender(cont))
    count += num_java_frames(hf);
  return count + 1; // add enter frame
}

// static int num_java_frames(const frame& f) {
//   if (f.is_interpreted_frame())
//     return 1;
//   else if (f.is_compiled_frame())
//     return num_java_frames(f.cb()->as_compiled_method(), f.pc());
//   else
//     return 0;
// }

// static int num_java_frames(ContMirror& cont, frame f) {
//   int count = 0;
//   RegisterMap map(cont.thread(), false, false, false); // should first argument be true?
//   for (; f.real_fp() > cont.entrySP(); f = f.frame_sender<ContinuationCodeBlobLookup>(&map))
//     count += num_java_frames(f);
//   return count;
// }

static inline void clear_anchor(JavaThread* thread) {
  thread->frame_anchor()->clear();
}

static void set_anchor(JavaThread* thread, FrameInfo* fi) {
  JavaFrameAnchor* anchor = thread->frame_anchor();
  anchor->set_last_Java_sp((intptr_t*)fi->sp);
  anchor->set_last_Java_fp((intptr_t*)fi->fp);
  anchor->set_last_Java_pc(fi->pc);

  assert(thread->last_frame().cb() != NULL, "");

  log_develop_trace(jvmcont)("set_anchor:");
  print_vframe(thread->last_frame());
}

// static void set_anchor(ContMirror& cont) {
//   FrameInfo fi = { cont.entryPC(), cont.entryFP(), cont.entrySP() };
//   set_anchor(cont.thread(), &fi);
// }

static oop get_continuation(JavaThread* thread) {
  assert (thread != NULL, "");
  return thread->last_continuation();
}

// static void set_continuation(JavaThread* thread, oop cont) {
//   java_lang_Thread::set_continuation(thread->threadObj(), cont);
// }

static inline void fix_continuation_bottom_sender(ContMirror& cont, frame& f) {
  if (Continuation::is_return_barrier_entry(f.pc())) {
    assert(!cont.is_empty(), "");
    f.set_pc_preserve_deopt(cont.entryPC(), ContinuationCodeBlobLookup::find_blob(cont.entryPC()));
  }
}


class ContOopBase : public OopClosure, public DerivedOopClosure {
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
    // The following is not true for the sender of the safepoint stub
    // assert(offset >= 0 || p == saved_link_address(_map),
    //   "offset: %d reg: %s", offset, (reg = find_register_spilled_here(p, _map), reg != NULL ? reg->name() : "NONE")); // calle-saved register can only be rbp
    reg = find_register_spilled_here(p, _map); // expensive operation
    if (reg != NULL) log_develop_trace(jvmcont)("reg: %s", reg->name());
    log_develop_trace(jvmcont)("p: " INTPTR_FORMAT " offset: %d %s", p2i(p), offset, p == Frame::saved_link_address(_map) ? "(link)" : "");
#endif

    return offset;
  }

  inline void process(void* p) {
    DEBUG_ONLY(verify(p);)
    _count++;
    _cont->e_inc_refs();
  }
};

///////////// FREEZE ///////

enum freeze_result {
  freeze_ok = 0,
  freeze_pinned_cs = 1,
  freeze_pinned_native = 2,
  freeze_pinned_monitor = 3,
  freeze_exception = 4
};

enum freeze_mode {
  mode_fast,
  mode_slow,
  mode_preempt
};

typedef freeze_result (*FreezeContFnT)(JavaThread*, ContMirror&, RegisterMap&, frame&, FrameInfo*);

static FreezeContFnT cont_freeze_fast = NULL;
static FreezeContFnT cont_freeze_slow = NULL;
static FreezeContFnT cont_freeze_preempt = NULL;

template<freeze_mode mode>
static freeze_result cont_freeze(JavaThread* thread, ContMirror& cont, RegisterMap& map, frame& f, FrameInfo* fi) {
  switch (mode) {
    case mode_fast:    return cont_freeze_fast   (thread, cont, map, f, fi);
    case mode_slow:    return cont_freeze_slow   (thread, cont, map, f, fi);
    case mode_preempt: return cont_freeze_preempt(thread, cont, map, f, fi);
  }
}

struct FpOopInfo {
  bool _has_fp_oop; // is fp used to store a derived pointer
  int _fp_index;    // see FreezeOopFn::do_derived_oop

  FpOopInfo() : _has_fp_oop(false), _fp_index(0) {}

  static int flag_offset() { return in_bytes(byte_offset_of(FpOopInfo, _has_fp_oop)); }
  static int index_offset() { return in_bytes(byte_offset_of(FpOopInfo, _fp_index)); }

  void set_oop_fp_index(int index) {
    assert(_has_fp_oop == false, "can only have one");
    _has_fp_oop = true;
    _fp_index = index;
  }
};

template <typename ConfigT, freeze_mode mode>
class Freeze {

class FreezeOopFn: public ContOopBase {
 private:
  FpOopInfo* _fp_info;
  void* const _hsp;
  int _starting_index;

  const address _stub_vsp;
#ifndef PRODUCT
  const address _stub_hsp;
#endif

  int add_oop(oop obj, int index) {
    return _cont->add_oop<ConfigT>(obj, index);
  }

 protected:
  template <class T> inline void do_oop_work(T* p) {
    process(p);
    oop obj = RawAccess<>::oop_load(p); // we are reading off our own stack, Raw should be fine
    int index = add_oop(obj, _starting_index + _count - 1);

#ifdef ASSERT
    // oop obj = NativeAccess<>::oop_load(p);
    print_oop(p, obj);
    assert (oopDesc::is_oop_or_null(obj), "invalid oop");
    log_develop_trace(jvmcont)("narrow: %d", sizeof(T) < wordSize);

    int offset = verify(p);
    assert(offset < 32768, "");
    if (_stub_vsp == NULL && offset < 0) { // rbp could be stored in the callee frame.
      assert (p == (T*)Frame::saved_link_address(_map), "");
      _fp_info->set_oop_fp_index(0xbaba); // assumed to be unnecessary at this time; used only in ASSERT for now
    } else {
      address hloc = (address)_hsp + offset; // address of oop in the (raw) h-stack
      assert (_cont->in_hstack(hloc), "");
      assert (*(T*)hloc == *p, "*hloc: " INTPTR_FORMAT " *p: " INTPTR_FORMAT, *(intptr_t*)hloc, *(intptr_t*)p);

      log_develop_trace(jvmcont)("Marking oop at " INTPTR_FORMAT " (offset: %d)", p2i(hloc), offset);
      memset(hloc, 0xba, sizeof(T)); // we must take care not to write a full word to a narrow oop
      if (_stub_vsp != NULL && offset < 0) { // slow path
        int offset0 = (address)p - _stub_vsp;
        assert (offset0 >= 0, "stub vsp: " INTPTR_FORMAT " p: " INTPTR_FORMAT " offset: %d", p2i(_stub_vsp), p2i(p), offset0);
        assert (hloc == _stub_hsp + offset0, "");
      }
    }
#endif
  }

 public:
  FreezeOopFn(ContMirror* cont, FpOopInfo* fp_info, frame* fr, void* vsp, void* hsp, RegisterMap* map, int starting_index, intptr_t* stub_vsp = NULL, intptr_t* stub_hsp = NULL)
   : ContOopBase(cont, fr, map, vsp), _fp_info(fp_info), _hsp(hsp), _starting_index(starting_index),
     _stub_vsp((address)stub_vsp)
#ifndef PRODUCT
     , _stub_hsp((address)stub_hsp) 
#endif
  { 
     assert (cont->in_hstack(hsp), "");
  }
  
  void do_oop(oop* p)       { do_oop_work(p); }
  void do_oop(narrowOop* p) { do_oop_work(p); }

  void do_derived_oop(oop *base_loc, oop *derived_loc) {
    assert(Universe::heap()->is_in_or_null(*base_loc), "not an oop");
    assert(derived_loc != base_loc, "Base and derived in same location");
    verify(base_loc);
    verify(derived_loc);

    intptr_t offset = cast_from_oop<intptr_t>(*derived_loc) - cast_from_oop<intptr_t>(*base_loc);

    log_develop_trace(jvmcont)(
      "Continuation freeze derived pointer@" INTPTR_FORMAT " - Derived: " INTPTR_FORMAT " Base: " INTPTR_FORMAT " (@" INTPTR_FORMAT ") (Offset: " INTX_FORMAT ")",
      p2i(derived_loc), p2i((address)*derived_loc), p2i((address)*base_loc), p2i(base_loc), offset);

    int hloc_offset = (address)derived_loc - (address)_vsp;
    if (hloc_offset < 0 && _stub_vsp == NULL) {
      assert ((intptr_t**)derived_loc == Frame::saved_link_address(_map), "");
      _fp_info->set_oop_fp_index(offset);

      log_develop_trace(jvmcont)("Writing derived pointer offset in fp (offset: %ld, 0x%lx)", offset, offset);
    } else {
      intptr_t* hloc = (intptr_t*)((address)_hsp + hloc_offset);
      *hloc = offset;

      log_develop_trace(jvmcont)("Writing derived pointer offset at " INTPTR_FORMAT " (offset: " INTX_FORMAT ", " INTPTR_FORMAT ")", p2i(hloc), offset, offset);

#ifdef ASSERT
      if (_stub_vsp != NULL && hloc_offset < 0) {
        int hloc_offset0 = (address)derived_loc - _stub_vsp;
        assert (hloc_offset0 >= 0, "hloc_offset: %d", hloc_offset0);
        assert(hloc == (intptr_t*)(_stub_hsp + hloc_offset0), "");
      }
#endif
    }
  }
};

private:
  JavaThread* _thread;
  ContMirror& _cont;
  intptr_t *_bottom_address;

  RegisterMap& _map;

  int _oops;
  int _size; // total size of all frames plus metadata. keeps track of offset where a frame should be written and how many bytes we need to allocate.
  int _frames;

  int _wsp; // the current hstack sp during the freezing operation
  int _wref_sp; // the current hstack ref_sp during the freezing operation

  FpOopInfo _fp_oop_info;
  frame _entry_frame;

  frame _safepoint_stub;
  bool  _safepoint_stub_caller;
#ifndef PRODUCT
  intptr_t* _safepoint_stub_hsp;
#endif

  template<typename FKind> static inline frame sender(frame& f, hframe::callee_info* callee_info);
  static inline void update_register_map(RegisterMap* map, hframe::callee_info callee_info);
  template <typename FKind> static inline int compiled_frame_size(frame& f, int* argsize, int* num_oops);
  static inline int interpreted_frame_size(frame&f, int* num_oops);
  template <typename FKind, bool top, bool bottom> inline void patch_pd(frame& f, hframe& callee, const hframe& caller);
  inline void relativize_interpreted_frame_metadata(frame& f, intptr_t* vsp, intptr_t* hsp);
  template<typename FKind> hframe new_hframe(const frame& f, intptr_t* vsp, intptr_t* hsp, int ref_sp);

  typedef int (*FreezeFnT)(address, address, address, address, int, FpOopInfo*);

public:
  Freeze(JavaThread* thread, ContMirror& mirror, RegisterMap& map) : 
    _thread(thread), _cont(mirror), _bottom_address(mirror.entrySP()), _map(map),
    _oops(0), _size(0), _frames(0), _wsp(0), _wref_sp(0),
    _fp_oop_info(), _safepoint_stub_caller(false) {
  }

  int nr_oops() const { return _oops; }
  int nr_bytes() const { return _size; }
  int nr_frames() const { return _frames; }

  frame entry_frame() { return _entry_frame; }

  int low_array_index() const { return _wref_sp; }

  freeze_result freeze(frame& f, FrameInfo* fi) {
    // const bool empty = _cont.is_empty();

    // assert (map.update_map(), "RegisterMap not set to update");
    assert (!_map.include_argument_oops(), "should be");
    hframe caller;
    freeze_result result = freeze<true>(f, caller, Frame::saved_link_address(&_map), 0);
    if (result == freeze_ok) {
      setup_jump(fi);
    }
    return result;
  }

  template<bool top>
  freeze_result freeze(frame& f, hframe& caller, intptr_t** callee_link_address, int callee_argsize) {
    assert (f.unextended_sp() < _bottom_address - 2, ""); // see recurse_java_frame
    assert (f.is_interpreted_frame() || ((top && mode == mode_preempt) == is_stub(f.cb())), "");

    // Dynamically branch on frame type
    if (mode == mode_fast || f.is_compiled_frame()) {
      if (mode != mode_fast && f.oop_map() == NULL)            return freeze_pinned_native; // special native frame
      if (Compiled::is_owning_locks(_cont.thread(), &_map, f)) return freeze_pinned_monitor;

      assert (f.oop_map() != NULL, "");

      return recurse_java_frame<Compiled, top>(f, caller, callee_link_address, callee_argsize);
    } else if (f.is_interpreted_frame()) {
      if (Interpreted::is_owning_locks(f)) return freeze_pinned_monitor;

      return recurse_java_frame<Interpreted, top>(f, caller, callee_link_address, callee_argsize);
    } else if (mode == mode_preempt && top && is_stub(f.cb())) {
      return recurse_stub_frame(f, caller);
    } else {
      return freeze_pinned_native;
    }
  }

  template<typename FKind, bool top>
  freeze_result recurse_java_frame(frame& f, hframe& caller, hframe::callee_info callee_info, int callee_argsize) {
    assert (FKind::interpreted == f.is_interpreted_frame(), "");

    _frames++;

    int fsize, argsize, oops;
    prepare_freeze_frame<FKind>(f, &fsize, &argsize, &oops, callee_argsize);
    FreezeFnT f_fn = get_oopmap_stub<FKind>(f); // try to do this early, so we wouldn't need to look at the oopMap again.

    assert (!FKind::interpreted || argsize == 0, "");

    hframe::callee_info my_info;
    frame senderf = sender<FKind>(f, &my_info); // f.sender_for_compiled_frame<ContinuationCodeBlobLookup>(&map);

    // sometimes an interpreted caller's sp extends a bit below entrySP, plus another word for possible alignment of compiled callee
    if (senderf.unextended_sp() >= _bottom_address - 2) { // dynmic branch
      freeze_result result = finalize(senderf); // recursion end
      if (result != freeze_ok)
        return result;
      
      update_register_map(&_map, callee_info); // restore saved link
      freeze_java_frame<FKind, top, true>(f, caller, fsize, argsize, oops, callee_argsize, f_fn);
    } else {
      bool safepoint_stub_caller; // the use of _safepoint_stub_caller is not nice, but given preemption being performance non-critical, we don't want to add either a template or a regular parameter
      if (mode == mode_preempt) {
        safepoint_stub_caller = _safepoint_stub_caller;
        _safepoint_stub_caller = false;
      }

      freeze_result result = freeze<false>(senderf, caller, my_info, argsize); // recursive call
      if (result != freeze_ok)
        return result;

      if (mode == mode_preempt) _safepoint_stub_caller = safepoint_stub_caller; // restore _stub_caller
      update_register_map(&_map, callee_info);  // restore saved link

      freeze_java_frame<FKind, top, false>(f, caller, fsize, argsize, oops, callee_argsize, f_fn);
    }

    if (top) {
      finish(f, caller);
    }
    return freeze_ok;
  }

  template <typename FKind> // TODO Choose more descriptive name
  void prepare_freeze_frame(frame& f, int* fsize, int* argsize, int* oops, int callee_argsize) {
    int oops0 = 0;
    int argsize0 = 0;
    const int fsize0 = FKind::interpreted ? interpreted_frame_size(f, &oops0) 
                                          : compiled_frame_size<FKind>(f, &argsize0, &oops0);
    assert (fsize0 > 0, "");

    _size += fsize0;
    if (!FKind::interpreted && !FKind::stub) {
      _size += argsize0;
      _size -= callee_argsize; // we added this when preparing the callee, and now we subtract, but not if the caller (current) is interpreted, an arguments are created by i2c
    }
    _oops += oops0;

    *fsize = fsize0;
    *argsize = argsize0;
    *oops = oops0;

    log_develop_trace(jvmcont)("prepare_freeze_frame fsize: %d argsize: %d oops: %d callee_argsize: %d", fsize0, argsize0, oops0, callee_argsize);
  }

  template<typename FKind>
  inline FreezeFnT get_oopmap_stub(const frame& f) {
    if (FKind::interpreted || mode != mode_preempt || !ConfigT::allow_stubs)
      return NULL;

    FreezeFnT f_fn = (FreezeFnT)f.oop_map()->freeze_stub();
    if ((void*)f_fn == (void*)f.oop_map()) {
      f_fn = NULL; // need CompressedOops for now ????
    }
    return f_fn;
  }
  
  template<typename FKind, bool top, bool bottom>
  void freeze_java_frame(frame& f, hframe& caller, int fsize, int argsize, int oops, int callee_argsize, FreezeFnT f_fn) {
    if (PERFTEST_LEVEL <= 15) return;

    log_develop_trace(jvmcont)("============================= FREEZING FRAME interpreted: %d top: %d bottom: %d", FKind::interpreted, top, bottom);
    log_develop_trace(jvmcont)("fsize: %d argsize: %d oops: %d callee_argsize: %d", fsize, argsize, oops, callee_argsize);
    if (log_develop_is_enabled(Trace, jvmcont)) f.print_on(tty);

    caller = FKind::interpreted 
      ? freeze_interpreted_frame       <top, bottom>(f, caller, fsize,          oops, callee_argsize)
      : freeze_compiled_frame<Compiled, top, bottom>(f, caller, fsize, argsize, oops, callee_argsize, f_fn);
  }

  NOINLINE freeze_result finalize(frame& f) {
#ifdef CALLGRIND_START_INSTRUMENTATION
  // if (_frames == _compiled && callgrind_counter == 1) {
  //   callgrind_counter = 2;
  //   tty->print_cr("Starting callgrind instrumentation");
  //   CALLGRIND_START_INSTRUMENTATION;
  // }
#endif

  if (PERFTEST_LEVEL <= 15) return freeze_ok;

  // ::fix_continuation_bottom_sender(*this, f); // -- we'll fix it later
  // #ifdef ASSERT
  // { ResourceMark rm(_thread);
  //   assert (strcmp(bottom_java_frame_name(f), ENTER_SIG) == 0, ""); }
  // #endif

  _entry_frame = f;

#ifdef ASSERT
  hframe orig_top_frame = _cont.last_frame();
  bool empty = _cont.is_empty();
  log_develop_trace(jvmcont)("bottom: " INTPTR_FORMAT " count %d size: %d, num_oops: %d", p2i(_bottom_address), nr_frames(), nr_bytes(), nr_oops());
  log_develop_trace(jvmcont)("top_hframe before (freeze):");
  if (log_develop_is_enabled(Trace, jvmcont)) orig_top_frame.print_on(_cont, tty);

  log_develop_trace(jvmcont)("empty: %d", empty);
  assert (!CONT_FULL_STACK || empty, "");
  assert (!empty || _cont.sp() >= _cont.stack_length() || _cont.sp() < 0, "sp: %d stack_length: %d", _cont.sp(), _cont.stack_length());
  assert (orig_top_frame.is_empty() == empty, "empty: %d f.sp: %d", empty, orig_top_frame.sp());
#endif

  _cont.allocate_stacks(_size, _oops, _frames);
  if (_thread->has_pending_exception()) 
    return freeze_exception;

  _wsp     = _cont.sp();
  _wref_sp = _cont.refSP();

  return freeze_ok;
}

template <typename FKind>
void freeze_oops(frame& f, intptr_t* vsp, intptr_t *hsp, int num_oops, FreezeFnT f_fn = NULL) {
  if (PERFTEST_LEVEL < 30) return;

  log_develop_trace(jvmcont)("Walking oops (freeze)");

  assert (!_map.include_argument_oops(), "");

  _fp_oop_info._has_fp_oop = false;

  int starting_index = _wref_sp - num_oops;
  int frozen;
  if (f_fn != NULL) { // dynamic branch
    assert (!FKind::interpreted, "");
    freeze_compiled_oops_stub(f_fn, vsp, hsp, &_map, starting_index);
  } else {
    if (num_oops == 0)
      return;
    frozen = FKind::interpreted ? freeze_intepreted_oops(f, vsp, hsp, starting_index)
                                : freeze_compiled_oops  (f, vsp, hsp, starting_index);
  }
  assert(frozen == num_oops, "frozen: %d num_oops: %d", frozen, num_oops);
  _wref_sp = starting_index;
}

template <typename FKind, bool top, bool bottom>
void patch(frame& f, hframe& hf, const hframe& caller) {
  assert (FKind::interpreted == f.is_interpreted_frame(), "");

  patch_pd<FKind, top, bottom>(f, hf, caller);

  if (bottom) { // bottom
    if (_cont.is_empty()) { // Dynamic branch, even in fast mode; but we're doing this only once, when we're bottom
      assert (hf.sender(_cont).is_empty(), "");
      
      if (!_entry_frame.is_interpreted_frame()) { // TODO PERFORMANCE
        if (mode == mode_fast || !_entry_frame.is_deoptimized_frame()) { // we do not patch if entry is deopt, as we use that information when thawing
          assert (!_entry_frame.is_deoptimized_frame(), "");
          hf.patch_return_pc<FKind>(NULL);
        }
      #ifdef ASSERT
        else {
          frame entry = _entry_frame;
          assert (entry.cb()->as_nmethod()->get_original_pc(&entry) == entry.pc(), "original_pc: " INTPTR_FORMAT " entry.pc(): " INTPTR_FORMAT, p2i(entry.cb()->as_nmethod()->get_original_pc(&entry)), p2i(entry.pc()));
          assert (is_deopt_return(hf.return_pc<FKind>(), entry), "must be");
          assert (hf.return_pc<FKind>() != entry.pc(), "hf.return_pc(): " INTPTR_FORMAT " entry.pc(): " INTPTR_FORMAT, p2i(hf.return_pc<FKind>()), p2i(entry.pc()));
          log_develop_trace(jvmcont)("Entry frame deoptimized! pc: " INTPTR_FORMAT " -> original_pc: " INTPTR_FORMAT, p2i(hf.return_pc<FKind>()), p2i(entry.pc()));
        }
      #endif
      }
    } else { // not empty
      assert (Continuation::is_cont_bottom_frame(f), "");
      log_develop_trace(jvmcont)("Fixing return address on bottom frame: " INTPTR_FORMAT, p2i(_cont.pc()));

      hf.patch_return_pc<FKind>(_cont.pc());
      assert (hf.sender(_cont) == _cont.last_frame(), "");
    }

    if (log_develop_is_enabled(Trace, jvmcont)) {
      log_develop_trace(jvmcont)("bottom h-frame:");
      hf.print(_cont);
    }
  }

  // sanity check
  assert (Interpreter::contains(hf.return_pc<FKind>()) == 
            ((!caller.is_empty() && caller.is_interpreted_frame()) 
            || (caller.is_empty() && _cont.is_empty() && _entry_frame.is_interpreted_frame())
            || (caller.is_empty() && !_cont.is_empty() && _cont.is_flag(FLAG_LAST_FRAME_INTERPRETED))), 
          "Interpreter::contains(hf.return_pc()): %d caller.is_empty(): %d _cont.is_empty(): %d caller.is_interpreted_frame(): %d _cont.is_flag(FLAG_LAST_FRAME_INTERPRETED): %d", 
          Interpreter::contains(hf.return_pc<FKind>()), caller.is_empty(), _cont.is_empty(), caller.is_interpreted_frame(), _cont.is_flag(FLAG_LAST_FRAME_INTERPRETED));

}

template <bool top, bool bottom>
hframe freeze_interpreted_frame(frame& f, hframe& caller, int fsize, int oops, int callee_argsize) {
    intptr_t* vsp = Interpreted::frame_top(f);
    assert ((Interpreted::frame_bottom(f) - vsp) * sizeof(intptr_t) == (size_t)fsize, "");

    intptr_t* hsp = freeze_raw_frame(vsp, fsize);

    // We don't use ref_sp in the hframe, so we just set it to 0; real value is _wref_sp - oops
    hframe hf = new_hframe<Interpreted>(f, vsp, hsp, 0);

    relativize_interpreted_frame_metadata(f, vsp, hsp);

    freeze_oops<Interpreted>(f, vsp, hsp, oops);
    
    patch<Interpreted, top, bottom>(f, hf, caller);

    _cont.add_size(fsize + callee_argsize);
    _cont.inc_num_frames();
    _cont.inc_num_interpreted_frames();

    return hf;
  }

  int freeze_intepreted_oops(frame& f, intptr_t* vsp, intptr_t* hsp, int starting_index) {
    FreezeOopFn oopFn(&_cont, &_fp_oop_info, &f, vsp, hsp, &_map, starting_index);
    f.oops_do(&oopFn, NULL, &oopFn, &_map);
    return oopFn.count();
  }

  template <typename FKind, bool top, bool bottom> // stub -- are we a safepoint stub (during preemption)
  hframe freeze_compiled_frame(frame& f, hframe& caller, int fsize, int argsize, int oops, int callee_argsize, FreezeFnT f_fn) {    
    assert ((callee_argsize & WordAlignmentMask) == 0, "must be");
    int callee_argsize_words = callee_argsize >> LogBytesPerWord;

    intptr_t* vsp = Compiled::frame_top(f);
    intptr_t* unxetended_vsp = vsp + callee_argsize_words;

#ifdef ASSERT
    const int fsize1 = (Compiled::frame_bottom(f) - vsp) * sizeof(intptr_t);
    assert (fsize1 == fsize, "fsize: %d fsize1: %d", fsize, fsize1);
#endif

    if (!FKind::stub) {
      f.cb()->as_compiled_method()->inc_on_continuation_stack();
    }

    intptr_t* hsp = freeze_raw_frame(unxetended_vsp, fsize + argsize - callee_argsize);
    intptr_t* extended_hsp = hsp - callee_argsize_words;

    // we need to construct the hframe with the "extended" hsp, which includes callee arguments because metadata positions (real_fp) depend on it
    // We don't use ref_sp in the hframe, so we just set it to 0; real value is _wref_sp - oops
    hframe hf = new_hframe<FKind>(f, vsp, extended_hsp, 0);

    hframe stub_hf;
    if (!FKind::stub) {
      if (mode == mode_preempt && _safepoint_stub_caller) {
        stub_hf = freeze_safepoint_stub(hf);
      }
      
      freeze_oops<Compiled>(f, vsp, extended_hsp, oops, f_fn);

      if (mode == mode_preempt && _safepoint_stub_caller) {
        assert (!_fp_oop_info._has_fp_oop, "must be");
        _safepoint_stub = frame();
      }
    } else { // stub frame has no oops
      _fp_oop_info._has_fp_oop = false;
    }

    patch<FKind, top, bottom>(f, hf, caller);
    assert(bottom || Interpreter::contains(hf.return_pc<Compiled>()) == caller.is_interpreted_frame(), "");

    _cont.inc_num_frames();
    _cont.add_size(fsize);
    if (mode != mode_fast) {
      if (bottom ? Interpreter::contains(hf.return_pc<Compiled>()) : caller.is_interpreted_frame()) // do after fixing return_pc in patch (and/or use equivalent condition above)
        _cont.add_size(sizeof(intptr_t)); // possible alignment
    }

    if (mode == mode_preempt && !FKind::stub && _safepoint_stub_caller)
      return stub_hf; // we return the callee stub; we've already frozen and patched that frame, but we need to return it so it can be passed to finish
    else
      return hf;
  }

  int freeze_compiled_oops(frame& f, intptr_t* vsp, intptr_t* hsp, int starting_index) {
    const ImmutableOopMap* oopmap = f.oop_map();
    assert(oopmap, "must have");
    // if (oopmap->num_oops() == 0) {
    //   return 0;
    // }

    address stub = oopmap->freeze_stub();
    if (mode != mode_preempt && ConfigT::allow_stubs && stub == NULL) {
      oopmap->generate_stub();
      stub = oopmap->freeze_stub();
    }

    if (mode != mode_preempt && ConfigT::allow_stubs && stub != NULL && stub != (address) oopmap) { // need CompressedOops for now
      assert (_safepoint_stub.is_empty(), "");
      return freeze_compiled_oops_stub((FreezeFnT) stub, vsp, hsp, &_map, starting_index);
    } else {
      intptr_t *stub_vsp = NULL;
      intptr_t *stub_hsp = NULL;
      if (mode == mode_preempt && !_safepoint_stub.is_empty()) {
        stub_vsp = StubF::frame_top(_safepoint_stub);
#ifndef PRODUCT
        assert (_safepoint_stub_hsp != NULL, "");
        stub_hsp = _safepoint_stub_hsp;
#endif
      }

      FreezeOopFn oopFn(&_cont, &_fp_oop_info, &f, vsp, hsp, &_map, starting_index, stub_vsp, stub_hsp);

      OopMapDo<FreezeOopFn, FreezeOopFn, IncludeAllValues> visitor(&oopFn, &oopFn, false /* no derived table lock */);
      visitor.oops_do(&f, &_map, oopmap);
      assert (!_map.include_argument_oops(), "");
      
      return oopFn.count();
    }
  }

  int freeze_compiled_oops_stub(FreezeFnT f_fn, intptr_t* vsp, intptr_t* hsp, RegisterMap* map, int starting_index) {
    typename ConfigT::OopT* addr = _cont.refStack()->template obj_at_address<typename ConfigT::OopT>(starting_index);
    int cnt = f_fn( (address) vsp,  (address) addr, (address) map, (address) hsp, _cont.refStack()->length() - starting_index, &_fp_oop_info);
    return cnt;
  }

  NOINLINE freeze_result recurse_stub_frame(frame& f, hframe& caller) {
    _frames++;

    assert (mode == mode_preempt, "");
    _safepoint_stub = f;

    int fsize, argsize, oops;
    prepare_freeze_frame<StubF>(f, &fsize, &argsize, &oops, 0);
    assert (fsize > 0 && oops == 0 && argsize == 0, "");

    hframe::callee_info my_info;
    frame senderf = sender<StubF>(f, &my_info); // f.sender_for_compiled_frame<ContinuationCodeBlobLookup>(&map);

    assert (senderf.unextended_sp() < _bottom_address - 2, "");
    assert (senderf.is_compiled_frame(), "");
    assert (senderf.oop_map() != NULL, "");

    // we can have stub_caller as a value template argument, but that's unnecessary
    _safepoint_stub_caller = true;
    freeze_result result = recurse_java_frame<Compiled, false>(senderf, caller, my_info, 0);
    if (result == freeze_ok) {
      finish(f, caller); // caller will be the stub's hframe, not its caller; see freeze_compiled_frame
    }
    return result;
  }

  NOINLINE hframe freeze_safepoint_stub(hframe& caller) {
    log_develop_trace(jvmcont)("== FREEZING STUB RAME:");

    assert(mode == mode_preempt, "");
    assert(!_safepoint_stub.is_empty(), "");

    int oops, argsize;
    int fsize = compiled_frame_size<StubF>(_safepoint_stub, &argsize, &oops);
    assert (argsize == 0 && oops == 0, "argsize: %d oops: %d", argsize, oops);

    hframe hf = freeze_compiled_frame<StubF, true, false>(_safepoint_stub, caller, fsize, 0, 0, 0, NULL);
    // patch<false, true, false>(_safepoint_stub, hf, caller);

#ifndef PRODUCT
    _safepoint_stub_hsp = _cont.stack_address(hf.sp());
#endif

    log_develop_trace(jvmcont)("== DONE FREEZING STUB RAME");
    return hf;
  }

  intptr_t* freeze_raw_frame(intptr_t* vsp, int fsize) {
    _wsp -= ContMirror::to_index(fsize);
    log_develop_trace(jvmcont)("freeze_raw_frame: _wsp: %d", _wsp);
    intptr_t* hsp = _cont.stack_address(_wsp);
    _cont.copy_to_stack(vsp, hsp, fsize);

    return hsp;
  }
  
  NOINLINE void finish(frame& f, hframe& top) {
    if (PERFTEST_LEVEL <= 15) return;

    ConfigT::OopWriterT::finish(_cont, nr_oops(), low_array_index());

    assert (_wsp <= _cont.sp(), "wsp: %d sp: %d", _wsp, _cont.sp());
    assert (_wsp == top.sp(), "wsp: %d top sp: %d", _wsp, top.sp());

    _cont.set_last_frame(top);
    _cont.set_refSP(_wref_sp);

    if (log_develop_is_enabled(Trace, jvmcont)) {
      log_develop_trace(jvmcont)("top_hframe after (freeze):");
      _cont.last_frame().print_on(_cont, tty);
    }

    assert (_cont.is_flag(FLAG_LAST_FRAME_INTERPRETED) == _cont.last_frame().is_interpreted_frame(), "flag: %d is_interpreted: %d", _cont.is_flag(FLAG_LAST_FRAME_INTERPRETED), _cont.last_frame().is_interpreted_frame());
  }

  void setup_jump(FrameInfo* fi) {
    // upon end of freeze, f points at the entry frame. we need to point it one more frame, to Continuation.run
    // TODO performance: we don't need all frame fields, e.g. we get CodeBlocb in sender, but don't use it
    ::fix_continuation_bottom_sender(_cont, _entry_frame); // need this so frame_sender could work  // check if :: is necessary
    frame run_frame = _entry_frame.frame_sender<ContinuationCodeBlobLookup>(&dmap);
    assert (Frame::real_pc(run_frame) == run_frame.raw_pc(), "");
    ContinuationHelper::to_frame_info(run_frame, _entry_frame, fi);

    // if (fi->pc != _cont.entryPC()) tty->print_cr("fi->pc: %p _cont.entryPC(): %p", fi->pc, _cont.entryPC());
    // if (fi->sp != _cont.entrySP()) tty->print_cr("fi->sp: %p _cont.entrySP(): %p", fi->sp, _cont.entrySP());
    // if (fi->fp != _cont.entryFP()) tty->print_cr("fi->fp: %p _cont.entryFP(): %p", fi->fp, _cont.entryFP());

  #ifdef ASSERT
    // if (f.pc() != real_pc(f)) tty->print_cr("Continuation.run deopted!");
    log_develop_debug(jvmcont)("Jumping to frame (freeze): [%ld] (%d)", java_tid(_thread), _thread->has_pending_exception());
    if (log_develop_is_enabled(Debug, jvmcont)) run_frame.print_value_on(tty, _thread);
    { ResourceMark rm(_thread);
      assert (strcmp(top_java_frame_name(run_frame), RUN_SIG) == 0, "name: %s", top_java_frame_name(run_frame)); }
  #endif
  }
};


template <typename ConfigT>
class NormalOopWriter {
public:
  typedef typename ConfigT::OopT OopT;

  static void obj_at_put(objArrayOop array, int index, oop obj) { array->obj_at_put_access<IS_DEST_UNINITIALIZED>(index, obj); }
  static void finish(ContMirror& mirror, int count, int low_array_index) { }
};

template <typename ConfigT>
class RawOopWriter {
public:
  typedef typename ConfigT::OopT OopT;

  static void obj_at_put(objArrayOop array, int index, oop obj) {
    OopT* addr = array->obj_at_addr<OopT>(index); // depends on UseCompressedOops
    RawAccess<IS_DEST_UNINITIALIZED>::oop_store(addr, obj);
  }

  static void finish(ContMirror& mirror, int count, int low_array_index) {
    BarrierSet* bs = BarrierSet::barrier_set();
    if (count > 0) {
      ModRefBarrierSet* mbs = barrier_set_cast<ModRefBarrierSet>(bs);
      HeapWord* start = (HeapWord*) mirror.refStack()->obj_at_addr<OopT>(low_array_index);
      mbs->write_ref_array(start, count);
    }
  }
};

template <bool compressed_oops, bool post_barrier, bool gen_stubs>
class FreezeConfig {
public:
  typedef FreezeConfig<compressed_oops, post_barrier, gen_stubs> SelfT;
  typedef typename Conditional<compressed_oops, narrowOop, oop>::type OopT;
  typedef typename Conditional<post_barrier, RawOopWriter<SelfT>, NormalOopWriter<SelfT> >::type OopWriterT;

  static const bool _compressed_oops = compressed_oops;
  static const bool allow_stubs = gen_stubs && compressed_oops && post_barrier;

  template<freeze_mode mode>
  static freeze_result freeze(JavaThread* thread, ContMirror& cont, RegisterMap& map, frame& f, FrameInfo* fi) {
    return Freeze<SelfT, mode>(thread, cont, map).freeze(f, fi);
  }
};

void ContinuationHelper::to_frame_info(const frame& f, const frame& callee, FrameInfo* fi) {
  fi->sp = f.unextended_sp(); // java_lang_Continuation::entrySP(cont);
  fi->pc = Frame::real_pc(f); // Continuation.run may have been deoptimized
  ContinuationHelper::to_frame_info_pd(f, callee, fi);
}

template<freeze_mode mode>
static frame freeze_start_frame(JavaThread* thread, FrameInfo* fi, RegisterMap* map) {
  assert (mode != mode_preempt, "");

#ifdef ASSERT
  nativePostCallNop_at(fi->pc) == NULL ? log_develop_trace(jvmcont)("no nop at freeze yield")
                                       : log_develop_trace(jvmcont)("has nop at freeze yield");
#endif

  // Note: if the doYield stub does not have its own frame, we may need to consider deopt here, especially if yield is inlinable
  frame f = ContinuationHelper::last_frame(thread); // thread->last_frame();
  assert (StubRoutines::cont_doYield_stub()->contains(f.pc()), "must be");
  ContinuationHelper::update_register_map<StubF>(map, f);
  f = f.frame_sender<ContinuationCodeBlobLookup>(map);  // this is the yield frame

  // The following doesn't work because fi->fp can contain an oop, that a GC doesn't know about when walking.
  // frame::update_map_with_saved_link(&map, (intptr_t **)&fi->fp);
  // frame f = ContinuationHelper::to_frame(fi); // the yield frame

  assert (f.pc() == fi->pc, "");

  // Log(jvmcont) logv; LogStream st(logv.debug()); f.print_on(st);
  if (log_develop_is_enabled(Debug, jvmcont)) f.print_on(tty);

  return f;
}

template<>
frame freeze_start_frame<mode_preempt>(JavaThread* thread, FrameInfo* fi, RegisterMap* map) {
  // safepoint yield
  frame f = thread->last_frame();
  f.set_fp(f.real_fp()); // Instead of this, maybe in ContMirror::set_last_frame always use the real_fp? // TODO PD
  if (Interpreter::contains(f.pc())) {
    log_develop_trace(jvmcont)("INTERPRETER SAFEPOINT");
    ContinuationHelper::update_register_map<Interpreted>(map, f);
    // f.set_sp(f.sp() - 1); // state pushed to the stack
  } else {
    log_develop_trace(jvmcont)("COMPILER SAFEPOINT");
#ifdef ASSERT
    if (!is_stub(f.cb())) { f.print_value_on(tty, JavaThread::current()); }
#endif
    assert (is_stub(f.cb()), "must be");
    assert (f.oop_map() != NULL, "must be");
    ContinuationHelper::update_register_map<StubF>(map, f);
    f.oop_map()->update_register_map(&f, map); // we have callee-save registers in this case
  }

  // Log(jvmcont) logv; LogStream st(logv.debug()); f.print_on(st);
  if (log_develop_is_enabled(Debug, jvmcont)) f.print_on(tty);

  return f;
}

void clear_frame_info(FrameInfo* fi) {
  fi->fp = NULL; 
  fi->sp = NULL; 
  fi->pc = NULL;
}

int early_return(int res, JavaThread* thread, FrameInfo* fi) {
  clear_frame_info(fi);
  thread->set_cont_yield(false);
  log_develop_trace(jvmcont)("=== end of freeze (fail %d)", res);
  return res;
}

static void post_JVMTI_yield(JavaThread* thread, ContMirror& cont) {
  if (JvmtiExport::should_post_continuation_yield() || JvmtiExport::can_post_frame_pop()) {
    JvmtiExport::post_continuation_yield(JavaThread::current(), num_java_frames(cont));
  }

  JvmtiThreadState *jvmti_state = thread->jvmti_thread_state();
  if (jvmti_state != NULL && jvmti_state->is_interp_only_mode()) {
    jvmti_state->invalidate_cur_stack_depth();
  }
}

// returns the continuation yielding (based on context), or NULL for failure (due to pinning)
// it freezes multiple continuations, depending on contex
// it must set Continuation.stackSize
// sets Continuation.fp/sp to relative indices
//
// In: fi->pc, fi->sp, fi->fp all point to the current (topmost) frame to freeze (the yield frame); THESE VALUES ARE CURRENTLY UNUSED
// Out: fi->pc, fi->sp, fi->fp all point to the run frame (entry's caller)
//      unless freezing has failed, in which case fi->pc = 0
//      However, fi->fp points to the _address_ on the stack of the entry frame's link to its caller (so *(fi->fp) is the fp)
template<freeze_mode mode>
int freeze0(JavaThread* thread, FrameInfo* fi) {
  //callgrind();
  PERFTEST_LEVEL = ContPerfTest;

  if (PERFTEST_LEVEL <= 10) return early_return(freeze_ok, thread, fi);

#ifdef ASSERT
  log_develop_debug(jvmcont)("~~~~~~~~~ freeze");
  log_develop_trace(jvmcont)("fi->sp: " INTPTR_FORMAT " fi->fp: " INTPTR_FORMAT " fi->pc: " INTPTR_FORMAT, p2i(fi->sp), p2i(fi->fp), p2i(fi->pc));
  // set_anchor(thread, fi); // DEBUG
  print_frames(thread);
#endif

  assert (thread->thread_state() == _thread_in_vm || thread->thread_state() == _thread_blocked, "thread->thread_state(): %d", thread->thread_state());
  assert (!thread->cont_yield(), "");
  assert (!thread->has_pending_exception(), "");
  // if (thread->has_pending_exception())
  //   return early_return(freeze_exception, thread, fi);

  EventContinuationFreeze event;

  thread->set_cont_yield(true);
  thread->cont_frame()->sp = NULL;
  DEBUG_ONLY(thread->_continuation = NULL;)

  // Thread* cur_thread = mode == mode_preempt ? Thread::current() : thread;
  // assert (cur_thread == Thread::current(), "");
  // ResourceMark rm(cur_thread); // we need it for Compiled::is_owning_locks

  oop oopCont = get_continuation(thread);
  ContMirror cont(thread, oopCont);
  log_develop_debug(jvmcont)("Freeze #" INTPTR_FORMAT " " INTPTR_FORMAT, cont.hash(), p2i((oopDesc*)oopCont));

  if (java_lang_Continuation::critical_section(oopCont) > 0) {
    log_develop_debug(jvmcont)("PINNED due to critical section");
    return early_return(freeze_pinned_cs, thread, fi);
  }

  RegisterMap map(thread, false, false, false);
  map.set_include_argument_oops(false);
  frame f = freeze_start_frame<mode>(thread, fi, &map);

  freeze_result res = cont_freeze<mode>(thread, cont, map, f, fi);
  if (res != freeze_ok)
    return early_return(res, thread, fi);

  if (PERFTEST_LEVEL <= 15) return freeze_ok;

  cont.set_flag(FLAG_SAFEPOINT_YIELD, mode == mode_preempt);

  cont.write(); // commit the freeze

  post_JVMTI_yield(thread, cont);
  cont.post_jfr_event(&event);
  
  // set_anchor(thread, fi);
  thread->set_cont_yield(false);

  log_develop_debug(jvmcont)("end of freeze cont ### #" INTPTR_FORMAT, cont.hash());
  log_develop_debug(jvmcont)("ENTRY: sp: " INTPTR_FORMAT " fp: " INTPTR_FORMAT " pc: " INTPTR_FORMAT, p2i(fi->sp), p2i(fi->fp), p2i(fi->pc));
  log_develop_debug(jvmcont)("=== End of freeze");

  return 0;
}

JRT_ENTRY(int, Continuation::freeze(JavaThread* thread, FrameInfo* fi))
  return freeze0<mode_slow>(thread, fi);
JRT_END

template <typename ConfigT, freeze_mode mode>
template <typename FKind>
inline int Freeze<ConfigT, mode>::compiled_frame_size(frame& f, int* argsize, int* num_oops) {
  assert (f.oop_map() != NULL, "");
  *num_oops = f.oop_map()->num_oops();
  *argsize = FKind::stub ? 0 : f.cb()->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size;
  return f.cb()->frame_size() * wordSize;
}

template <typename ConfigT, freeze_mode mode>
inline int Freeze<ConfigT, mode>::interpreted_frame_size(frame&f, int* num_oops) {
  int n = 0;
  n++; // for the mirror
  int nlocks = ((intptr_t*)f.interpreter_frame_monitor_begin() - (intptr_t*)f.interpreter_frame_monitor_end())/BasicObjectLock::size(); // all locks must be NULL when freezing, but f.oops_do walks them, so we count them
  n += nlocks;

  InterpreterOopMap mask;
  Interpreted::oop_map(f, &mask);
  n += mask.num_oops();
  *num_oops = n;

  int size = (Interpreted::frame_bottom(f) - Interpreted::frame_top(f, &mask)) * wordSize;
  return size;
}

static freeze_result is_pinned(const frame& f, const RegisterMap* map) {
  if (f.is_interpreted_frame()) {
    if (Interpreted::is_owning_locks(f)) 
      return freeze_pinned_monitor;
  } else if (f.is_compiled_frame()) {
    if (Compiled::is_owning_locks(map->thread(), map, f)) 
      return freeze_pinned_monitor;
  } else {
    return freeze_pinned_native;
  }
  return freeze_ok;
}

static freeze_result is_pinned0(JavaThread* thread, oop cont_scope, bool safepoint) {
  oop cont = get_continuation(thread);
  if (cont == (oop)NULL) {
    return freeze_ok;
  }
  if (java_lang_Continuation::critical_section(cont) > 0)
    return freeze_pinned_cs;

  RegisterMap map(thread, false, false, false); // should first argument be true?
  map.set_include_argument_oops(false);
  frame f = thread->last_frame();

  if (!safepoint) {
    f = f.frame_sender<ContinuationCodeBlobLookup>(&map); // LOOKUP // this is the yield frame
  } else { // safepoint yield
    f.set_fp(f.real_fp()); // Instead of this, maybe in ContMirror::set_last_frame always use the real_fp?
    if (!Interpreter::contains(f.pc())) {
      assert (is_stub(f.cb()), "must be");
      assert (f.oop_map() != NULL, "must be");
      f.oop_map()->update_register_map(&f, &map); // we have callee-save registers in this case
    }
  }

  while(true) {
    freeze_result res = is_pinned(f, &map);
    if (res != freeze_ok)
      return res;
    
    f = f.frame_sender<ContinuationCodeBlobLookup>(&map);
    if (!Continuation::is_frame_in_continuation(f, cont)) {
      oop scope = java_lang_Continuation::scope(cont);
      if (oopDesc::equals(scope, cont_scope))
        break;
      cont = java_lang_Continuation::parent(cont);
      if (cont == (oop)NULL)
        break;
      if (java_lang_Continuation::critical_section(cont) > 0)
        return freeze_pinned_cs;
    }
  }
  return freeze_ok;
}

typedef int (*DoYieldStub)(int scopes);

// called in a safepoint
int Continuation::try_force_yield(JavaThread* thread, const oop cont) {
  // this is the only place where we traverse the continuatuion hierarchy in native code, as it needs to be done in a safepoint
  oop scope = NULL;
  oop innermost = get_continuation(thread);
  for (oop c = innermost; c != NULL; c = java_lang_Continuation::parent(c)) {
    if (oopDesc::equals(c, cont)) {
      scope = java_lang_Continuation::scope(c);
      break;
    }
  }
  if (scope == NULL) {
    return -1; // no continuation
  }
  if (thread->_cont_yield) {
    return -2; // during yield
  }
  if (!oopDesc::equals(innermost, cont)) { // we have nested continuations
    // make sure none of the continuations in the hierarchy are pinned
    freeze_result res_pinned = is_pinned0(thread, java_lang_Continuation::scope(cont), true);
    if (res_pinned != freeze_ok)
      return res_pinned;

    java_lang_Continuation::set_yieldInfo(cont, scope);
  }

// #ifdef ASSERT
//   tty->print_cr("FREEZING:");
//   frame lf = thread->last_frame();
//   lf.print_on(tty);
//   tty->print_cr("");
//   const ImmutableOopMap* oopmap = lf.oop_map();
//   if (oopmap != NULL) {
//     oopmap->print();
//     tty->print_cr("");
//   } else {
//     tty->print_cr("oopmap: NULL");
//   }
//   tty->print_cr("*&^*&#^$*&&@(#*&@(#&*(*@#&*(&@#$^*(&#$(*&#@$(*&#($*&@#($*&$(#*$");
// #endif
  // TODO: save return value

  FrameInfo fi;
  int res = freeze0<mode_preempt>(thread, &fi); // CAST_TO_FN_PTR(DoYieldStub, StubRoutines::cont_doYield_C())(-1);
  if (res == 0) { // success
    thread->_cont_frame = fi;
    thread->_cont_preempt = true;
    frame last = thread->last_frame();
    Frame::patch_pc(last, StubRoutines::cont_jump_from_sp()); // reinstates rbpc and rlocals for the sake of the interpreter
  }
  return res;
}
///////////////

class ThawOopFn: public ContOopBase {
 private:
  int _i;

 protected:
  template <class T> inline void do_oop_work(T* p) {
    process(p);
    oop obj = _cont->obj_at(_i); // does a HeapAccess<IN_HEAP_ARRAY> load barrier
    assert (oopDesc::is_oop_or_null(obj), "invalid oop");
    log_develop_trace(jvmcont)("i: %d", _i); print_oop(p, obj);
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

    log_develop_trace(jvmcont)(
      "Continuation thaw derived pointer@" INTPTR_FORMAT " - Derived: " INTPTR_FORMAT " Base: " INTPTR_FORMAT " (@" INTPTR_FORMAT ") (Offset: " INTX_FORMAT ")",
      p2i(derived_loc), p2i((address)*derived_loc), p2i((address)*base_loc), p2i(base_loc), offset);

    oop obj = cast_to_oop(cast_from_oop<intptr_t>(*base_loc) + offset);
    assert(Universe::heap()->is_in_or_null(obj), "");
    *derived_loc = obj;

    assert(derived_loc != base_loc, "Base and derived in same location");
  }
};


static frame thaw_interpreted_frame(ContMirror& cont, hframe& hf, intptr_t* vsp, int fsize, int callee_argsize, frame& sender) {
  assert ((callee_argsize & WordAlignmentMask) == 0, "must be");
  int callee_argsize_words = callee_argsize >> LogBytesPerWord;

  intptr_t* hsp = cont.stack_address(hf.sp()) + callee_argsize_words;
  cont.copy_from_stack(hsp, vsp, fsize);
  intptr_t* hfp = cont.stack_address(hf.fp());
  intptr_t* vfp = vsp + (hfp - hsp);

  bool safepoint_stub = false;
  if (*(hfp + frame::interpreter_frame_last_sp_offset) == 0) {
      *(vfp + frame::interpreter_frame_last_sp_offset) = 0;
      safepoint_stub = true; // the last yield was forced and called in a safepoint
  } else {
    ContMirror::derelativize(vfp, frame::interpreter_frame_last_sp_offset);
  }
  ContMirror::derelativize(vfp, frame::interpreter_frame_initial_sp_offset); // == block_top == block_bottom
  ContMirror::derelativize(vfp, frame::interpreter_frame_locals_offset);

  intptr_t* unextended_sp = vsp; // safepoint_stub ? vsp : *(intptr_t**)(vfp + frame::interpreter_frame_last_sp_offset);
  frame f(vsp, unextended_sp, vfp, hf.pc());

  Interpreted::patch_sender_sp(f, sender.unextended_sp()); // ContMirror::derelativize(vfp, frame::interpreter_frame_sender_sp_offset);

  assert (*(intptr_t**)(vfp + frame::interpreter_frame_locals_offset) < Frame::frame_top(sender), "sender top: " INTPTR_FORMAT " locals: " INTPTR_FORMAT,
    p2i(Frame::frame_top(sender)), p2i(*(intptr_t**)(vfp + frame::interpreter_frame_locals_offset)));

  assert(f.is_interpreted_frame_valid(cont.thread()), "invalid thawed frame");

  cont.sub_size(fsize + callee_argsize);
  cont.dec_num_frames();
  cont.dec_num_interpreted_frames();

  return f;
}

static frame thaw_compiled_frame(ContMirror& cont, hframe& hf, intptr_t* vsp, int fsize, int argsize, int callee_argsize, frame& sender, RegisterMap& map, bool &deoptimized) {
  assert ((callee_argsize & WordAlignmentMask) == 0, "must be");
  int callee_argsize_words = callee_argsize >> LogBytesPerWord;

  bool frame_is_stub = is_stub(hf.cb());

#ifdef _LP64
  if ((long)vsp % 16 != 0) {
    log_develop_trace(jvmcont)("Aligning compiled frame: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(vsp), p2i(vsp - 1));
    assert(sender.is_interpreted_frame() 
      || (is_entry_frame(cont, sender) && !frame_is_stub && hf.compiled_frame_stack_argsize<Compiled>() % 16 != 0), "");
    vsp--;
  }
  assert((long)vsp % 16 == 0, "");
#endif

  cont.sub_size(fsize);
  if (Interpreter::contains(hf.return_pc<Compiled>())) { // false if bottom-most frame, as the return address would be patched to NULL if interpreted
    cont.sub_size(sizeof(intptr_t)); // we do this whether or not we've aligned because we add it in freeze_interpreted_frame
  }

  // when thawing on lazy copy, we may not have the callee_argsize (it may be 0) for a callee that returns through the return barrier.
  // this means that we copy the callee's arguments to the v-stack again, as we don't know how much of our frame is arguments, but that's OK.
  intptr_t* unextended_vsp = vsp + callee_argsize_words;
  intptr_t* hsp = cont.stack_address(hf.sp());
  intptr_t* unextended_hsp = hsp + callee_argsize_words;

  log_develop_trace(jvmcont)("hsp: %d unextended_hsp: %d callee_argsize_words: %d", cont.stack_index(hsp), cont.stack_index(unextended_hsp), callee_argsize_words);

  cont.copy_from_stack(unextended_hsp, unextended_vsp, fsize + argsize - callee_argsize);

  if (!frame_is_stub)
    hf.cb()->as_compiled_method()->dec_on_continuation_stack();

  frame f(vsp, (intptr_t*)hf.fp(), hf.pc());

    // TODO get nmethod. Call popNmethod if necessary
    // when copying nmethod frames, we need to check for them being made non-reentrant, in which case we need to deopt them
    // and turn them into interpreter frames.

  if (f.should_be_deoptimized() && !f.is_deoptimized_frame()) {
    log_develop_trace(jvmcont)("Deoptimizing thawed frame");
    // tty->print_cr("DDDDDDDDDDDDD");

    DEBUG_ONLY(Frame::patch_pc(f, NULL));
    Deoptimization::deoptimize(cont.thread(), f, &map);
    deoptimized = true;
  }

  cont.dec_num_frames();

  return f;
}

static void thaw_oops(ContMirror& cont, frame& f, int oop_index, int num_oops, void* target, RegisterMap& map, const ImmutableOopMap* oop_map) {
  log_develop_trace(jvmcont)("Walking oops (thaw)");

  // log_develop_trace(jvmcont)("is_top: %d", is_top);
  // assert (!is_top || cont.is_map_at_top(map), "");
  // assert (!is_top || f.is_interpreted_frame() || f.fp() == (intptr_t*)cont.fp(), "f.fp: " INTPTR_FORMAT " cont.fp: 0x%lx", p2i(f.fp()), cont.fp());

  assert (!map.include_argument_oops(), "");

  if (oop_map != NULL && oop_map->thaw_stub() != NULL && oop_map->thaw_stub() != (address) oop_map) {
    typedef int (*ThawFnT)(address /* dst */, address /* objArray */, address /* map */);
    address addr = NULL;
    if (UseCompressedOops) { // This should be optimized out with use of Config objects
      addr = (address) cont.refStack()->obj_at_address<narrowOop>(oop_index);
    } else {
      addr = (address) cont.refStack()->obj_at_address<oop>(oop_index);
    }
    ThawFnT f_fn = (ThawFnT) oop_map->thaw_stub();
    int cnt = f_fn( (address) target,  (address) addr, (address) f.fp_addr()); // write the link straight into the frame struct
    assert(cnt == num_oops, "");
    cont.null_ref_stack(oop_index, num_oops);
  } else {
    intptr_t* tmp_fp = f.fp();
    frame::update_map_with_saved_link(&map, &tmp_fp);


    // ResourceMark rm(cont.thread()); // apparently, oop-mapping may require resource allocation
    ThawOopFn oopFn(&cont, &f, oop_index, num_oops, target, &map);
    if (oop_map) {
      OopMapDo<ThawOopFn, ThawOopFn, IncludeAllValues> visitor(&oopFn, &oopFn, false /* no derived table lock */);
      visitor.oops_do(&f, &map, oop_map);
    } else {
      f.oops_do(&oopFn, NULL, &oopFn, &map);
    }

    log_develop_trace(jvmcont)("count: %d num_oops: %d", oopFn.count(), num_oops);
    assert(oopFn.count() == num_oops, "closure oop count different.");
    cont.null_ref_stack(oop_index, num_oops);

    // Thawing oops may have overwritten the link in the callee if rbp contained an oop (only possible if we're compiled).
    // This only matters when we're the top frame, as that's the value that will be restored into rbp when we jump to continue.
    if (tmp_fp != f.fp()) {
      log_develop_trace(jvmcont)("WHOA link has changed (thaw) f.fp: " INTPTR_FORMAT " link: " INTPTR_FORMAT, p2i(f.fp()), p2i(tmp_fp));
      f.set_fp(tmp_fp);
    }
  }

  log_develop_trace(jvmcont)("Done walking oops");
}

template<typename FKind> static inline void patch_thawed_frame_pd(frame& f, const frame& sender);

template<typename FKind>
static inline void patch_thawed_frame(frame& f, const frame& sender) {
  FKind::patch_return_pc(f, sender.raw_pc());
  patch_thawed_frame_pd<FKind>(f, sender);
}

static frame thaw_frame(ContMirror& cont, hframe& hf, int oop_index, int fsize, int num_oops, int argsize, int callee_argsize, frame& sender, bool &deoptimized, hframe* callee_safepoint_stub) {
  log_develop_trace(jvmcont)("============================= THAWING FRAME:");

  if (log_develop_is_enabled(Trace, jvmcont)) hf.print(cont);

  bool is_interpreted = hf.is_interpreted_frame();

  log_develop_trace(jvmcont)("hsp: %d hfp: 0x%lx", hf.sp(), hf.fp());
  log_develop_trace(jvmcont)("fsize: %d argsize: %d callee_argsize: %d num_oops: %d", fsize, argsize, callee_argsize, num_oops);
  log_develop_trace(jvmcont)("stack_length: %d", cont.stack_length());

  bool is_sender_deopt = deoptimized;
  bool is_bottom_frame = false;
  address ret_pc;
  if (is_entry_frame(cont, sender)) { // TODO: consider using count == 0 in thaw_frames, as that's cheaper
    is_bottom_frame = true;
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
    log_develop_trace(jvmcont)("Sender is deopt: " INTPTR_FORMAT, p2i(ret_pc));
    assert (is_deopt_return(ret_pc, sender), "");
  } else {
    ret_pc = CHOOSE2(is_interpreted, hf.return_pc); // sender.pc();
  }
  assert (is_entry_frame(cont, sender) || ret_pc == CHOOSE2(is_interpreted, hf.return_pc) || is_deopt_return(CHOOSE2(is_interpreted, hf.return_pc), sender), "");
  assert (ret_pc == sender.raw_pc(), "%d %d %d %d %d",
    is_entry_frame(cont, sender),
    is_deopt_return(ret_pc, sender), is_deopt_return(sender.raw_pc(), sender),
    is_sender_deopt, sender.is_deoptimized_frame());
  deoptimized = false;

#ifdef ASSERT
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
    //   log_develop_trace(jvmcont)("Sender frame already deopted");
    //   is_sender_deopt = true;
    // } else if (is_deopt_return(hf.return_pc(cont), sender)) {
    //   log_develop_trace(jvmcont)("Entry frame deoptimized! pc: " INTPTR_FORMAT, p2i(sender.pc()));
    //   *pc_addr = sender.pc(); // just to make the following call think we're walking the stack from the top
    //   tty->print_cr("Deoptimizing enter");
    //   sender.deoptimize(cont.thread());
    //   is_sender_deopt = true;
    // }
  }
#endif

  // address deopt_ret_pc = NULL;
  // if (is_sender_deopt) {
  //   // this must be done before copying the frame, because the sender's sp might not be correct
  //   // for example, if a compiled frame calls an interpreted frame, its sp must point to a couple of words before
  //   // the callee's fp, but we always create the frame so that sp = unextended_sp, and so the sp would point to
  //   // before the callee's locals
  //   deopt_ret_pc = *pc_addr; // we grab the return pc written by deoptimize (about to be clobbered by thaw_x) to restore later
  //   log_develop_trace(jvmcont)("Sender is deopt: " INTPTR_FORMAT, p2i(deopt_ret_pc));
  //   deoptimized = false;
  // }
  
  int adjust = 0; // = is_entry_frame(cont, sender) && sender.is_interpreted_frame() ? 1 : 0;
  const address bottom = (address) (sender.sp() + adjust);
  intptr_t* vsp = (intptr_t*)(bottom - fsize);

  // We add arguments for the bottom most frame frozen:
  // - If we're not doing lazy copy, then it's always enter0, for which argsize is 0, so it doesn't matter.
  // - If we are doing lazy copy, then we need the arguments for the bottom-most frame
  if (sender.is_interpreted_frame() || is_bottom_frame) { // an interpreted caller doesn't include my args
    vsp = (intptr_t*)((address)vsp - argsize);
  }

  log_develop_trace(jvmcont)("vsp: " INTPTR_FORMAT, p2i(vsp));

  RegisterMap map(cont.thread(), true, false, false); // TODO: why is update true?
  map.set_include_argument_oops(false);
  // map is only passed to thaw_compiled_frame for use in deoptimize, which uses it only for biased locks; we may not need deoptimize there at all -- investigate

  frame f = is_interpreted ? thaw_interpreted_frame(cont, hf, vsp, fsize, callee_argsize, sender)
                           : thaw_compiled_frame(cont, hf, vsp, fsize, argsize, callee_argsize, sender, map, deoptimized);

  assert (!is_entry_frame(cont, sender) || sender.fp() == cont.entryFP(), "sender.fp: " INTPTR_FORMAT " entryFP: " INTPTR_FORMAT, p2i(sender.fp()), p2i(cont.entryFP()));
  assert (!is_entry_frame(cont, sender) || sender.sp() == cont.entrySP(), "sender.sp: " INTPTR_FORMAT " entrySP: " INTPTR_FORMAT, p2i(sender.sp()), p2i(cont.entrySP()));
  // assert (!is_entry_frame(cont, sender) || link_address(f, is_interpreted) == (intptr_t**)sender.sp() - frame::sender_sp_offset, 
  //       "link_address(f): " INTPTR_FORMAT " sender.sp() - frame::sender_sp_offset: " INTPTR_FORMAT, 
  //       p2i(link_address(f, is_interpreted)), p2i((intptr_t**)sender.sp() - frame::sender_sp_offset));
  
  CHOOSE2(is_interpreted, patch_thawed_frame, f, sender);

  // if (is_sender_deopt) {
  //   assert (!is_entry_frame(cont, sender), "");
  //   tty->print_cr("Patching sender deopt");
  //   log_develop_trace(jvmcont)("Patching sender deopt");
  //   patch_return_pc(f, deopt_ret_pc, hf.is_interpreted_frame());
  // }

  frame calleef;
  if (callee_safepoint_stub != NULL) {
    // A safepoint stub is the only case we encounter callee-saved registers (aside from rbp). We therefore thaw that frame
    // before thawing the oops in its sender, as the oops will need to be written to that stub frame.
    log_develop_trace(jvmcont)("THAWING SAFEPOINT STUB");
    hframe callee = *callee_safepoint_stub;
    bool deopt_tmp = false;

    int fsize0, argsize0, num_oops0;
    fsize0 = callee.compiled_frame_size<StubF>(&argsize0, &num_oops0);
    assert (argsize0 == 0 && num_oops0 == 0, "");
    calleef = thaw_frame(cont, callee, oop_index, fsize0, num_oops0, argsize0, 0, f, deopt_tmp, NULL);

    // const int callee_fsize = callee.uncompressed_size(cont) != 0 ? callee.uncompressed_size(cont) : callee.size(cont);
    // const address callee_bottom = (address) f.sp();
    // intptr_t* callee_vsp = (intptr_t*)(callee_bottom - callee_fsize);
    // cont.sub_size(callee_fsize);
    // calleef = thaw_compiled_frame(cont, callee, callee_vsp, f, map, deoptimized);
    // patch_link(calleef, f.fp(), false);
    // patch_return_pc(calleef, ???, false);

    calleef.oop_map()->update_register_map(&calleef, &map);
    log_develop_trace(jvmcont)("THAWING OOPS FOR SENDER OF SAFEPOINT STUB");
  }
  // assert (oop_index == hf.ref_sp(), "");
  assert (hf.cb() == NULL || !is_stub(hf.cb()) || num_oops == 0, "must be");
  // if (hf.cb() == NULL || !is_stub(hf.cb()))
  thaw_oops(cont, f, oop_index, num_oops, f.sp(), map, is_interpreted ? NULL : f.oop_map());

#ifndef PRODUCT
  RegisterMap dmap(NULL, false);
  print_vframe(f, &dmap);
#endif

  if (callee_safepoint_stub != NULL) {
    return calleef;
  }

  return f;
}

static frame thaw_frames(ContMirror& cont, hframe hf, int oop_index, int num_frames, int& count, int &last_oop_index, int callee_argsize, hframe& last_frame, bool& deoptimized, hframe* callee_safepoint_stub) {
  if (num_frames == 0 || hf.is_empty()) {
    frame entry(cont.entrySP(), cont.entryFP(), cont.entryPC());
    log_develop_trace(jvmcont)("Found entry:");
    print_vframe(entry);

  #ifdef ASSERT
    { ResourceMark rm(cont.thread());
      assert (strcmp(bottom_java_frame_name(entry), ENTER_SIG) == 0, "name: %s", bottom_java_frame_name(entry)); }
  #endif

    last_oop_index = oop_index;
    last_frame = hf;

    // tty->print_cr(">>>>>>>>>");
    // hf.print_on(cont, tty);

    deoptimized = false;
    // cont.set_refSP(oop_index);
    // cont.set_last_frame(hf);
    return entry;
  }

  bool interpreted = hf.is_interpreted_frame();
  bool is_bottom;
  int fsize, num_oops;
  frame f, sender;

  // tty->print_cr(">>>>>>>>>");
  // hf.print_on(cont, tty);

  if (interpreted) {
    InterpreterOopMap mask;
    hf.interpreted_frame_oop_map(&mask);
    fsize = hf.interpreted_frame_size(mask, &num_oops);
    hframe hsender = hf.sender<Interpreted>(cont, num_oops);

    sender = thaw_frames(cont, hsender, oop_index + num_oops, num_frames - 1, count, last_oop_index, 0, last_frame, deoptimized, NULL);
    f = thaw_frame(cont, hf, oop_index, fsize, num_oops, 0, callee_argsize, sender, deoptimized, callee_safepoint_stub);

    is_bottom = hf.is_bottom<Interpreted>(cont);
  } else {
    bool is_safepoint_stub = false;
    int argsize;

    if (/*is_first &&*/ is_stub(hf.cb())) {
      log_develop_trace(jvmcont)("Found safepoint stub");
      is_safepoint_stub = true;

      fsize = hf.compiled_frame_size<StubF>(&argsize, &num_oops);
      hframe hsender = hf.sender<StubF>(cont, num_oops);

      assert (num_oops == 0, "must be");

      sender = thaw_frames(cont, hsender, oop_index + num_oops, num_frames - 1, count, last_oop_index, argsize, last_frame, deoptimized, is_safepoint_stub ? &hf : NULL);
      // In the case of a safepoint stub, the above line, called on the stub's sender, actually returns the safepoint stub after thawing it.
      f = sender;

      is_bottom = hf.is_bottom<StubF>(cont);
    } else {
      fsize = hf.compiled_frame_size<Compiled>(&argsize, &num_oops);
      hframe hsender = hf.sender<Compiled>(cont, num_oops); // TODO: maybe we can reuse fsize?

      sender = thaw_frames(cont, hsender, oop_index + num_oops, num_frames - 1, count, last_oop_index, argsize, last_frame, deoptimized, is_safepoint_stub ? &hf : NULL);
      f = thaw_frame(cont, hf, oop_index, fsize, num_oops, argsize, callee_argsize, sender, deoptimized, callee_safepoint_stub);

      is_bottom = hf.is_bottom<Compiled>(cont);
    }
  }

  log_develop_trace(jvmcont)("is_bottom: %d", is_bottom);
  assert ((count == 0) == is_entry_frame(cont, sender), "");
  assert (is_bottom <= last_frame.is_empty(), "hf.is_bottom(cont): %d last_frame.is_empty(): %d ", is_bottom, last_frame.is_empty());
// #ifdef ASSERT
//   { // ResourceMark rm(cont.thread());
//     assert (!hf.is_bottom(cont) || strcmp(frame_name(f), ENTER0_SIG) == 0, "name: %s", frame_name(f)); }
// #endif

  frame f1 = f; // the frame on which to install the return barrier
  if (count == 0) {
    if (is_stub(f.cb())) {
      RegisterMap map(cont.thread(), false, false, false); 
      f1 = f.sender(&map);
    }
    assert (is_entry_frame(cont, sender), "");
    assert (!is_bottom || hf.sender(cont).is_empty(), "");
    if (!is_bottom) {
      log_develop_trace(jvmcont)("Setting return address to return barrier: " INTPTR_FORMAT, p2i(StubRoutines::cont_returnBarrier()));
      CHOOSE1(f1.is_interpreted_frame(), patch_return_pc, f1, StubRoutines::cont_returnBarrier());
    } else {
      // we use thread->_cont_frame->sp rather than the continuations themselves (which allow nesting) b/c it's faser and simpler.
      // for that to work, we rely on the fact that parent continuation's have at lesat Continuation.run on the stack, which does not require stack arguments
      cont.thread()->cont_frame()->sp = NULL;
    //   if (sender.is_interpreted_frame()) { // unnecessary now, thanks to enter0
    //     // We enter the continuation through an interface call (target.run()), but exit through a virtual call (doContinue())
    //     // Alternatively, wrap the call to target.run() inside a private method.
    //     patch_return_pc(f, Interpreter::return_entry(vtos, 0, Bytecodes::_invokevirtual), f.is_interpreted_frame());
    //   }
    }
  }

  assert (!is_entry_frame(cont, sender) || (is_bottom == last_frame.is_empty()), "hf.is_bottom(cont): %d last_frame.is_empty(): %d ", is_bottom, last_frame.is_empty());
  assert (!is_entry_frame(cont, sender) || (is_bottom != Continuation::is_cont_bottom_frame(f1)), "hf.is_bottom(cont): %d is_cont_bottom_frame(f): %d ", is_bottom, Continuation::is_cont_bottom_frame(f));
  // assert (Continuation::is_cont_bottom_frame(f) <= (num_frames == 1 || (num_frames == 2 && cont.is_flag(FLAG_SAFEPOINT_YIELD))), 
  //   "num_frames: %d is_cont_bottom_frame(f): %d safepoint yield: %d", num_frames, Continuation::is_cont_bottom_frame(f), cont.is_flag(FLAG_SAFEPOINT_YIELD));

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
  // NoSafepointVerifier nsv;
  EventContinuationThaw event;
  ResourceMark rm(thread);

  if (return_barrier) log_develop_trace(jvmcont)("== RETURN BARRIER");
  const int num_frames = thaw_num_frames(return_barrier);

  log_develop_trace(jvmcont)("~~~~~~~~~ thaw %d", num_frames);
  log_develop_trace(jvmcont)("pc: " INTPTR_FORMAT, p2i(fi->pc));
  log_develop_trace(jvmcont)("sp: " INTPTR_FORMAT, p2i(fi->sp));
  log_develop_trace(jvmcont)("fp: " INTPTR_FORMAT, p2i(fi->fp));

  // address target = (address)fi->sp; // we leave fi->sp as-is

  oop oopCont = get_continuation(thread);
  assert(oopCont != NULL && oopDesc::is_oop_or_null(oopCont), "Invalid cont: " INTPTR_FORMAT, p2i((void*)oopCont));

  ContMirror cont(thread, oopCont);

  cont.set_entrySP(fi->sp);
  cont.set_entryFP(fi->fp);
  if (!return_barrier) { // not return barrier
    cont.set_entryPC(fi->pc);
  }

  log_develop_debug(jvmcont)("THAW ### #" INTPTR_FORMAT, cont.hash());

  int java_frame_count = -1;
  if (!return_barrier && JvmtiExport::should_post_continuation_run()) {
    java_frame_count = num_java_frames(cont);
  }

// #ifndef PRODUCT
//   set_anchor(cont);
//   print_frames(thread);
// #endif

  // log_develop_trace(jvmcont)("thaw: TARGET: " INTPTR_FORMAT, p2i(target);
  // log_develop_trace(jvmcont)("QQQ CCCCC bottom: " INTPTR_FORMAT " top: " INTPTR_FORMAT " size: %ld", p2i(cont.entrySP()), p2i(target), (address)cont.entrySP() - target);
  assert(num_frames > 0, "num_frames <= 0: %d", num_frames);
  assert(!cont.is_empty(), "no more frames");

  // ResourceMark rm(cont.thread()); // apparently, oop-mapping may require resource allocation

  // const address orig_top_pc = cont.pc();
  hframe hf = cont.last_frame();
  log_develop_trace(jvmcont)("top_hframe before (thaw):");
  if (log_develop_is_enabled(Trace, jvmcont)) hf.print_on(cont, tty);

  RegisterMap map(thread, true, false, false);
  map.set_include_argument_oops(false);
  assert (map.update_map(), "RegisterMap not set to update");

  DEBUG_ONLY(int orig_num_frames = cont.num_frames();)
  int frame_count = 0;
  int last_oop_index = 0;
  hframe last_frame;
  bool deoptimized = false;
  frame top = thaw_frames(cont, hf, cont.refSP(), num_frames, frame_count, last_oop_index, 0, last_frame, deoptimized, NULL);
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

  log_develop_trace(jvmcont)("thawed %d frames", frame_count);

  log_develop_trace(jvmcont)("top_hframe after (thaw):");
  if (log_develop_is_enabled(Trace, jvmcont)) cont.last_frame().print_on(cont, tty);

  cont.write();

#ifndef PRODUCT
  set_anchor(thread, fi);
  print_frames(thread, tty); // must be done after write(), as frame walking reads fields off the Java objects.
  clear_anchor(thread);
#endif

  log_develop_trace(jvmcont)("cont sp: %d fp: %lx", cont.sp(), cont.fp());
  log_develop_trace(jvmcont)("fi->sp: " INTPTR_FORMAT " fi->fp: " INTPTR_FORMAT " fi->pc: " INTPTR_FORMAT, p2i(fi->sp), p2i(fi->fp), p2i(fi->pc));

  if (log_develop_is_enabled(Trace, jvmcont)) {
    log_develop_trace(jvmcont)("Jumping to frame (thaw):");
    frame f = frame(fi->sp, fi->fp, fi->pc);
    print_vframe(f, NULL);
  }

// #ifdef ASSERT
//   { ResourceMark rm(thread);
//     assert (!CONT_FULL_STACK || strcmp(frame_name(f), YIELD_SIG) == 0, "name: %s", frame_name(f)); } // not true if yield is not @DontInline
// #endif

  DEBUG_ONLY(thread->_continuation = oopCont;)

  if (!return_barrier && JvmtiExport::should_post_continuation_run()) {
    set_anchor(thread, fi); // ensure thawed frames are visible
    JvmtiExport::post_continuation_run(JavaThread::current(), java_frame_count);
    clear_anchor(thread);
  }

  JvmtiThreadState *jvmti_state = thread->jvmti_thread_state();
  if (jvmti_state != NULL && jvmti_state->is_interp_only_mode()) {
    jvmti_state->invalidate_cur_stack_depth();
  }

  cont.post_jfr_event(&event);

  log_develop_debug(jvmcont)("=== End of thaw #" INTPTR_FORMAT, cont.hash());
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

//     sp += to_index(fsize);
//     // fp += hstack[fp]; // contains offset to previous fp
//   }
//   log_develop_trace(jvmcont)("frames_size: %lu", size);
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
  PERFTEST_LEVEL = ContPerfTest;
  
  if (PERFTEST_LEVEL <= 110) return 0;

  log_develop_trace(jvmcont)("~~~~~~~~~ prepare_thaw");

  int num_frames = thaw_num_frames(return_barrier);

  log_develop_trace(jvmcont)("prepare_thaw %d %d", return_barrier, num_frames);
  log_develop_trace(jvmcont)("pc: " INTPTR_FORMAT, p2i(fi->pc));
  log_develop_trace(jvmcont)("rbp: " INTPTR_FORMAT, p2i(fi->fp));

  const address bottom = (address)fi->sp; // os::current_stack_pointer(); points to the entry frame
  log_develop_trace(jvmcont)("bottom: " INTPTR_FORMAT, p2i(bottom));

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
  log_develop_trace(jvmcont)("target: " INTPTR_FORMAT, p2i(target));
  log_develop_trace(jvmcont)("QQQ BBBBB bottom: " INTPTR_FORMAT " top: " INTPTR_FORMAT " size: %d", p2i(bottom), p2i(target), size);

  if (PERFTEST_LEVEL <= 120) return 0;

  return size;
JRT_END

// IN:  fi->sp = the future SP of the topmost thawed frame (where we'll copy the thawed frames)
// Out: fi->sp = the SP of the topmost thawed frame -- the one we will resume at
//      fi->fp = the FP " ...
//      fi->pc = the PC " ...
// JRT_ENTRY(void, Continuation::thaw(JavaThread* thread, FrameInfo* fi, int num_frames))
JRT_LEAF(address, Continuation::thaw_leaf(FrameInfo* fi, bool return_barrier, bool exception))
  //callgrind();
  PERFTEST_LEVEL = ContPerfTest;

  thaw1(JavaThread::current(), fi, return_barrier);

  if (exception) {
    // TODO: handle deopt. see TemplateInterpreterGenerator::generate_throw_exception, OptoRuntime::handle_exception_C, OptoRuntime::handle_exception_helper
    // assert (!top.is_deoptimized_frame(), ""); -- seems to be handled
    address ret = fi->pc;
    fi->pc = SharedRuntime::raw_exception_handler_for_return_address(JavaThread::current(), fi->pc);
    return ret;
  } else
    return reinterpret_cast<address>(Interpreter::contains(fi->pc)); // really only necessary in the case of continuing from a forced yield
JRT_END

JRT_ENTRY(address, Continuation::thaw(JavaThread* thread, FrameInfo* fi, bool return_barrier, bool exception))
  //callgrind();
  PERFTEST_LEVEL = ContPerfTest;

  thaw1(JavaThread::current(), fi, return_barrier);

  if (exception) {
    // TODO: handle deopt. see TemplateInterpreterGenerator::generate_throw_exception, OptoRuntime::handle_exception_C, OptoRuntime::handle_exception_helper
    // assert (!top.is_deoptimized_frame(), ""); -- seems to be handled
    address ret = fi->pc;
    fi->pc = SharedRuntime::raw_exception_handler_for_return_address(JavaThread::current(), fi->pc);
    return ret;
  } else
    return reinterpret_cast<address>(Interpreter::contains(fi->pc)); // really only necessary in the case of continuing from a forced yield
JRT_END

bool Continuation::is_continuation_entry_frame(const frame& f, const RegisterMap* map) {
  if (map->in_cont()) // A continuation's entry frame is always on the v-stack
    return false;

  Method* m = Frame::frame_method(f);
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
  return is_return_barrier_entry(CHOOSE1(f.is_interpreted_frame(), return_pc, f));
}

bool Continuation::is_return_barrier_entry(const address pc) { 
  return pc == StubRoutines::cont_returnBarrier(); 
}

static inline bool is_sp_in_continuation(intptr_t* const sp, oop cont) {
  return java_lang_Continuation::entrySP(cont) >= sp;
}

bool Continuation::is_frame_in_continuation(const frame& f, oop cont) {
  return is_sp_in_continuation(f.sp(), cont);
}

static oop find_continuation_for_frame(JavaThread* thread, intptr_t* const sp) {
  oop cont = get_continuation(thread);
  while (cont != NULL && !is_sp_in_continuation(sp, cont))
    cont = java_lang_Continuation::parent(cont);
  return cont;
}

oop Continuation::get_continutation_for_frame(JavaThread* thread, const frame& f) {
  return find_continuation_for_frame(thread, f.sp());
}

bool Continuation::is_frame_in_continuation(JavaThread* thread, const frame& f) {
  return find_continuation_for_frame(thread, f.sp()) != NULL;
}

static address get_entry_pc_past_barrier(JavaThread* thread, const frame& f) {
  oop cont = find_continuation_for_frame(thread, f.sp());
  assert (cont != NULL, "");
  address pc = java_lang_Continuation::entryPC(cont);
  // log_develop_trace(jvmcont)("YEYEYEYEYEYEYEEYEY: " INTPTR_FORMAT, p2i(pc));
  assert (pc != NULL, "");
  return pc;
}

void Continuation::fix_continuation_bottom_sender(const frame* callee, RegisterMap* map, address* sender_pc, intptr_t** sender_sp) {
  if (map->thread() != NULL && is_return_barrier_entry(*sender_pc)) {
    *sender_pc = get_entry_pc_past_barrier(map->thread(), *callee);
    if (callee->is_compiled_frame()) {
      // The callee's stack arguments (part of the caller frame) are also thawed to the stack when using lazy-copy
      int argsize = callee->cb()->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size;
      assert ((argsize & WordAlignmentMask) == 0, "must be");
      argsize >>= LogBytesPerWord;
      if (argsize % 2 != 0)
        argsize++; // 16-byte alignment for compiled frame sp

      *sender_sp += argsize;
    }
  }
}

// frame Continuation::fix_continuation_bottom_sender(const frame& callee, frame f, RegisterMap* map) {
//   if (map->thread() != NULL && is_cont_bottom_frame(callee)) {
//     f.set_pc_preserve_deopt(get_entry_pc_past_barrier(map->thread(), f));
//   }
//   return f;
// }

bool Continuation::is_scope_bottom(oop cont_scope, const frame& f, const RegisterMap* map) {
  if (cont_scope == NULL || !is_continuation_entry_frame(f, map))
    return false;

  assert (!map->in_cont(), "");
  // if (map->in_cont()) return false;

  oop cont = find_continuation_for_frame(map->thread(), f.sp());
  if (cont == NULL)
    return false;

  oop sc = continuation_scope(cont);
  assert(sc != NULL, "");
  return oopDesc::equals(sc, cont_scope);
}

// bool Continuation::is_scope_bottom(oop cont_scope, const frame& f, const RegisterMap* map) {
//   if (cont_scope == NULL || !map->in_cont())
//     return false;

//   oop sc = continuation_scope(map->cont());
//   assert(sc != NULL, "");
//   if (!oopDesc::equals(sc, cont_scope))
//     return false;

//   ContMirror cont(map);

//   hframe hf = cont.from_frame(f);
//   hframe sender = hf.sender(cont);

//   // tty->print_cr(">>> is_scope_bottom");
//   // hf.print_on(cont, tty);
//   // tty->print_cr(">> sender");
//   // sender.print_on(cont, tty);

//   return sender.is_empty();
// }

static frame continuation_top_frame(oop contOop, RegisterMap* map) {
  ContMirror cont(NULL, contOop);

  hframe hf = cont.last_frame();
  assert (!hf.is_empty(), "");

  // tty->print_cr(">>>> continuation_top_frame");
  // hf.print_on(cont, tty);
  
  // if (!oopDesc::equals(map->cont(), contOop))
  map->set_cont(contOop);
  map->set_in_cont(true);

  if (map->update_map() && !hf.is_interpreted_frame()) { // TODO : what about forced preemption? see `if (callee_safepoint_stub != NULL)` in thaw_frame
    frame::update_map_with_saved_link(map, reinterpret_cast<intptr_t**>(-1));
  }

  return hf.to_frame(cont);
}

static frame continuation_parent_frame(ContMirror& cont, RegisterMap* map) {
  assert (map->thread() != NULL || !cont.is_mounted(), "map->thread() == NULL: %d cont.is_mounted(): %d", map->thread() == NULL, cont.is_mounted());

  // if (map->thread() == NULL) { // When a continuation is mounted, its entry frame is always on the v-stack
  //   oop parentOop = java_lang_Continuation::parent(cont.mirror());
  //   if (parentOop != NULL) {
  //     // tty->print_cr("continuation_parent_frame: parent");
  //     return continuation_top_frame(parentOop, map);
  //   }
  // }
  
  map->set_cont(java_lang_Continuation::parent(cont.mirror()));
  map->set_in_cont(false);

  if (!cont.is_mounted()) { // When we're walking an unmounted continuation and reached the end
    // tty->print_cr("continuation_parent_frame: no more");
    return frame(); 
  }

  frame sender(cont.entrySP(), cont.entryFP(), cont.entryPC());

  // tty->print_cr("continuation_parent_frame");
  // print_vframe(sender, map, NULL);

  return sender;
}

frame Continuation::last_frame(Handle continuation, RegisterMap *map) {
  assert(map != NULL, "a map must be given");
  map->set_cont(continuation); // set handle
  return continuation_top_frame(continuation(), map);
}

bool Continuation::has_last_Java_frame(Handle continuation) {
  return java_lang_Continuation::pc(continuation()) != NULL;
}

javaVFrame* Continuation::last_java_vframe(Handle continuation, RegisterMap *map) {
  assert(map != NULL, "a map must be given");
  frame f = last_frame(continuation, map);
  for (vframe* vf = vframe::new_vframe(&f, map, NULL); vf; vf = vf->sender()) {
    if (vf->is_java_frame()) return javaVFrame::cast(vf);
  }
  return NULL;
}

frame Continuation::top_frame(const frame& callee, RegisterMap* map) {
  oop contOop = find_continuation_for_frame(map->thread(), callee.sp());
  
  log_develop_trace(jvmcont)("Continuation::top_frame: setting map->last_vstack_fp: " INTPTR_FORMAT, p2i(callee.real_fp()));

  ContinuationHelper::set_last_vstack_frame(map, callee);
  return continuation_top_frame(contOop, map);
}

static frame sender_for_frame(const frame& f, RegisterMap* map) {
  ContMirror cont(map);
  hframe hf = cont.from_frame(f);
  hframe sender = hf.sender(cont);

  // tty->print_cr(">>>> sender_for_frame");
  // sender.print_on(cont, tty);

  if (map->update_map()) {
    if (sender.is_empty()) {
      // we need to return the link address for the entry frame; it is saved in the bottom-most thawed frame
      intptr_t** fp = (intptr_t**)(map->last_vstack_fp());
      log_develop_trace(jvmcont)("sender_for_frame: frame::update_map_with_saved_link: " INTPTR_FORMAT, p2i(fp));
      frame::update_map_with_saved_link(map, fp);
    } else { // if (!sender.is_interpreted_frame())
      if (is_stub(f.cb())) {
        f.oop_map()->update_register_map(&f, map); // we have callee-save registers in this case
      }
      int link_index = cont.stack_index(hf.link_address());
      log_develop_trace(jvmcont)("sender_for_frame: frame::update_map_with_saved_link: %d", link_index);
      frame::update_map_with_saved_link(map, reinterpret_cast<intptr_t**>(link_index));
    }
  }

  if (!sender.is_empty()) {
    return sender.to_frame(cont);
  } else {
    log_develop_trace(jvmcont)("sender_for_frame: continuation_parent_frame");
    return continuation_parent_frame(cont, map);
  }
}

frame Continuation::sender_for_interpreter_frame(const frame& callee, RegisterMap* map) {
  return sender_for_frame(callee, map);
}

frame Continuation::sender_for_compiled_frame(const frame& callee, RegisterMap* map) {
  return sender_for_frame(callee, map);
}

class OopIndexClosure : public OopMapClosure {
private:
  int _i;
  int _index;

  int _offset;
  VMReg _reg;

public:
  OopIndexClosure(int offset) : _i(0), _index(-1), _offset(offset), _reg(VMRegImpl::Bad()) {}
  OopIndexClosure(VMReg reg)  : _i(0), _index(-1), _offset(-1), _reg(reg) {}

  int index() { return _index; }
  int is_oop() { return _index >= 0; }

  void do_value(VMReg reg, OopMapValue::oop_types type) {
    assert (type == OopMapValue::oop_value || type == OopMapValue::narrowoop_value, "");
    if (reg->is_reg()) {
        if (_reg == reg) _index = _i;
    } else {
      int sp_offset_in_bytes = reg->reg2stack() * VMRegImpl::stack_slot_size;
      if (sp_offset_in_bytes == _offset) _index = _i;
    }
    _i++;
  }
};

class InterpreterOopIndexClosure : public OffsetClosure {
private:
  int _i;
  int _index;

  int _offset;

public:
  InterpreterOopIndexClosure(int offset) : _i(0), _index(-1), _offset(offset) {}

  int index() { return _index; }
  int is_oop() { return _index >= 0; }

  void offset_do(int offset) {
    if (offset == _offset) _index = _i;
    _i++;
  }
};

// *grossly* inefficient
static int find_oop_in_compiled_frame(const frame& fr, const RegisterMap* map, const int usp_offset_in_bytes) {
  assert (fr.is_compiled_frame(), "");
  const ImmutableOopMap* oop_map = fr.oop_map();
  assert (oop_map != NULL, "");
  OopIndexClosure ioc(usp_offset_in_bytes);
  oop_map->all_do(&fr, OopMapValue::oop_value | OopMapValue::narrowoop_value, &ioc);
  return ioc.index();
}

static int find_oop_in_compiled_frame(const frame& fr, const RegisterMap* map, VMReg reg) {
  assert (fr.is_compiled_frame(), "");
  const ImmutableOopMap* oop_map = fr.oop_map();
  assert (oop_map != NULL, "");
  OopIndexClosure ioc(reg);
  oop_map->all_do(&fr, OopMapValue::oop_value | OopMapValue::narrowoop_value, &ioc);
  return ioc.index();
}

static int find_oop_in_interpreted_frame(const hframe& hf, int offset, const InterpreterOopMap& mask) {
  // see void frame::oops_interpreted_do
  InterpreterOopIndexClosure ioc(offset);
  mask.iterate_oop(&ioc);
  int res = ioc.index() + 1 + hf.interpreted_frame_num_monitors(); // index 0 is mirror; next are InterpreterOopMap::iterate_oop
  return res; // index 0 is mirror
}

address Continuation::oop_address(objArrayOop ref_stack, int ref_sp, int index) {
  assert (index >= ref_sp && index < ref_stack->length(), "i: %d ref_sp: %d length: %d", index, ref_sp, ref_stack->length());
  oop obj = ref_stack->obj_at(index); // invoke barriers
  address p = UseCompressedOops ? (address)ref_stack->obj_at_addr<narrowOop>(index)
                                : (address)ref_stack->obj_at_addr<oop>(index);

  log_develop_trace(jvmcont)("oop_address: index: %d", index);
  // print_oop(p, obj);
  assert (oopDesc::is_oop_or_null(obj), "invalid oop");
  return p;  
}

bool Continuation::is_in_usable_stack(address addr, const RegisterMap* map) {
  ContMirror cont(map);
  return cont.is_in_stack(addr) || cont.is_in_ref_stack(addr);
}

address Continuation::usp_offset_to_location(const frame& fr, const RegisterMap* map, const int usp_offset_in_bytes) {
  return usp_offset_to_location(fr, map, usp_offset_in_bytes, find_oop_in_compiled_frame(fr, map, usp_offset_in_bytes) >= 0);
}

// if oop, it is narrow iff UseCompressedOops
address Continuation::usp_offset_to_location(const frame& fr, const RegisterMap* map, const int usp_offset_in_bytes, bool is_oop) {
  assert (fr.is_compiled_frame(), "");
  ContMirror cont(map);
  hframe hf = cont.from_frame(fr);

  intptr_t* hsp = cont.stack_address(hf.sp());
  address loc = (address)hsp + usp_offset_in_bytes;

  log_develop_trace(jvmcont)("usp_offset_to_location oop_address: stack index: %d length: %d", cont.stack_index(loc), cont.stack_length());

  int oop_offset = find_oop_in_compiled_frame(fr, map, usp_offset_in_bytes);
  assert (is_oop == (oop_offset >= 0), "must be");
  address res = is_oop ? oop_address(cont.refStack(), cont.refSP(), hf.ref_sp() + oop_offset) : loc;
  return res;
}

int Continuation::usp_offset_to_index(const frame& fr, const RegisterMap* map, const int usp_offset_in_bytes) {
  assert (fr.is_compiled_frame() || is_stub(fr.cb()), "");
  ContMirror cont(map);
  hframe hf = cont.from_frame(fr);

  intptr_t* hsp;
  if (usp_offset_in_bytes >= 0) {
     hsp = cont.stack_address(hf.sp());
  } else {    
    hframe stub = cont.last_frame();

    assert (cont.is_flag(FLAG_SAFEPOINT_YIELD), "must be");
    assert (is_stub(stub.cb()), "must be");
    assert (stub.sender(cont) == hf, "must be");

    hsp = cont.stack_address(stub.sp()) + stub.cb()->frame_size();
  }
  address loc = (address)hsp + usp_offset_in_bytes;
  int index = cont.stack_index(loc);

  log_develop_trace(jvmcont)("usp_offset_to_location oop_address: stack index: %d length: %d", index, cont.stack_length());
  return index;
}

address Continuation::reg_to_location(const frame& fr, const RegisterMap* map, VMReg reg) {
  return reg_to_location(fr, map, reg, find_oop_in_compiled_frame(fr, map, reg) >= 0);
}

address Continuation::reg_to_location(const frame& fr, const RegisterMap* map, VMReg reg, bool is_oop) {
  oop cont;
  if (map->in_cont()) {
    cont = map->cont();
  } else {
    cont = get_continutation_for_frame(map->thread(), fr);
  }
  return reg_to_location(cont, fr, map, reg, is_oop);
}

address Continuation::reg_to_location(oop contOop, const frame& fr, const RegisterMap* map, VMReg reg, bool is_oop) {
  assert (fr.is_compiled_frame(), "");

  assert (!is_continuation_entry_frame(fr, map), "");
  // if (is_continuation_entry_frame(fr, map)) {
  //   log_develop_trace(jvmcont)("reg_to_location continuation entry link address: " INTPTR_FORMAT, p2i(map->location(reg)));
  //   return map->location(reg); // see sender_for_frame, `if (sender.is_empty())`
  // }

  assert (contOop != NULL, "");

  ContMirror cont(map->thread(), contOop);
  hframe hf = cont.from_frame(fr);

  int oop_index = find_oop_in_compiled_frame(fr, map, reg);
  assert (is_oop == oop_index >= 0, "must be");

  address res = NULL;
  if (oop_index >= 0) {
    res = oop_address(cont.refStack(), cont.refSP(), hf.ref_sp() + find_oop_in_compiled_frame(fr, map, reg));
  } else {
  // assert ((void*)saved_link_address(map) == (void*)map->location(reg), "must be the link register (rbp): %s", reg->name());
    int index = (int)reinterpret_cast<uintptr_t>(map->location(reg)); // the RegisterMap should contain the link index. See sender_for_frame
    assert (index >= 0, "non-oop in fp of the topmost frame is not supported");
    if (index >= 0) { // see frame::update_map_with_saved_link in continuation_top_frame
      address loc = (address)cont.stack_address(index);
      log_develop_trace(jvmcont)("reg_to_location oop_address: stack index: %d length: %d", index, cont.stack_length());
      if (oop_index < 0)
        res = loc;
    }
  }
  return res;
}

address Continuation::interpreter_frame_expression_stack_at(const frame& fr, const RegisterMap* map, const InterpreterOopMap& oop_mask, int index) {
  assert (fr.is_interpreted_frame(), "");
  ContMirror cont(map);
  hframe hf = cont.from_frame(fr);

  int max_locals = hf.method<Interpreted>()->max_locals();
  address loc = (address)hf.interpreter_frame_expression_stack_at(index);
  if (loc == NULL)
    return NULL;

  int index1 = max_locals + index; // see stack_expressions in vframe.cpp
  log_develop_trace(jvmcont)("interpreter_frame_expression_stack_at oop_address: stack index: %d, length: %d exp: %d index1: %d", cont.stack_index(loc), cont.stack_length(), index, index1);

  address res = oop_mask.is_oop(index1) 
    ? oop_address(cont.refStack(), cont.refSP(), hf.ref_sp() + find_oop_in_interpreted_frame(hf, index1, oop_mask))
    : loc; 
  return res;
}

address Continuation::interpreter_frame_local_at(const frame& fr, const RegisterMap* map, const InterpreterOopMap& oop_mask, int index) {
  assert (fr.is_interpreted_frame(), "");
  ContMirror cont(map);
  hframe hf = cont.from_frame(fr);

  address loc = (address)hf.interpreter_frame_local_at(index);

  log_develop_trace(jvmcont)("interpreter_frame_local_at oop_address: stack index: %d length: %d local: %d", cont.stack_index(loc), cont.stack_length(), index);
  address res = oop_mask.is_oop(index) 
    ? oop_address(cont.refStack(), cont.refSP(), hf.ref_sp() + find_oop_in_interpreted_frame(hf, index, oop_mask))
    : loc;
  return res;
}

Method* Continuation::interpreter_frame_method(const frame& fr, const RegisterMap* map) {
  assert (fr.is_interpreted_frame(), "");
  hframe hf = ContMirror(map).from_frame(fr);

  return hf.method<Interpreted>();
}

address Continuation::interpreter_frame_bcp(const frame& fr, const RegisterMap* map) {
  assert (fr.is_interpreted_frame(), "");
  hframe hf = ContMirror(map).from_frame(fr);

  return hf.interpreter_frame_bcp();
}

oop Continuation::continuation_scope(oop cont) { 
  return cont != NULL ? java_lang_Continuation::scope(cont) : (oop)NULL; 
}

///// Allocation

inline void ContMirror::post_safepoint(Handle conth) {
  _cont = conth();  // reload oop
  _ref_stack = java_lang_Continuation::refStack(_cont);
  _stack = java_lang_Continuation::stack(_cont);
  _hstack = (int*)_stack->base(T_INT);
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
    post_safepoint(conth);
    return result;
  }

  return allocator.initialize(start);
}

typeArrayOop ContMirror::allocate_stack_array(size_t elements) {
  assert(elements > 0, "");

  TypeArrayKlass* klass = TypeArrayKlass::cast(Universe::intArrayKlassObj());
  const size_t size_in_words = typeArrayOopDesc::object_size(klass, elements);
  return typeArrayOop(raw_allocate(klass, size_in_words, elements, false));
}

objArrayOop ContMirror::allocate_refstack_array(const size_t nr_oops) {
  assert(nr_oops > 0, "");

  ArrayKlass* klass = ArrayKlass::cast(Universe::objectArrayKlassObj());
  const size_t size_in_words = objArrayOopDesc::object_size(nr_oops);
  return objArrayOop(raw_allocate(klass, size_in_words, nr_oops, true));
}

bool ContMirror::allocate_stack(int size) {
  int elements = size >> 2;
  oop result = allocate_stack_array(elements);
  if (result == NULL) {
    return false;
  }

  _stack = typeArrayOop(result);
  _sp = elements;
  _stack_length = elements;
  _hstack = (int*)_stack->base(T_INT);

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

    // TODO: may not be necessary because at this point we know that the freeze will succeed and these values will get written in ::write
    java_lang_Continuation::set_sp(_cont, _sp);
    java_lang_Continuation::set_fp(_cont, _fp);
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

    // TODO: may not be necessary because at this point we know that the freeze will succeed and this value will get written in ::write
    java_lang_Continuation::set_refSP(_cont, _ref_sp);
  }

  return true;
}


void ContMirror::allocate_stacks(int size, int oops, int frames) {
  bool needs_stack_allocation    = (_stack == NULL || to_index(size) > (_sp >= 0 ? _sp : _stack_length));
  bool needs_refStack_allocation = (_ref_stack == NULL || oops > _ref_sp);

  log_develop_trace(jvmcont)("stack size: %d (int): %d sp: %d stack_length: %d needs alloc: %d", size, to_index(size), _sp, _stack_length, needs_stack_allocation);
  log_develop_trace(jvmcont)("num_oops: %d ref_sp: %d needs alloc: %d", oops, _ref_sp, needs_stack_allocation);

  assert(_sp == java_lang_Continuation::sp(_cont), "");
  assert(_fp == java_lang_Continuation::fp(_cont), "");
  assert(_pc == java_lang_Continuation::pc(_cont), "");

  if (!(needs_stack_allocation | needs_refStack_allocation))
    return;

  if (PERFTEST_LEVEL < 100) {
    tty->print_cr("stack size: %d (int): %d sp: %d stack_length: %d needs alloc: %d", size, to_index(size), _sp, _stack_length, needs_stack_allocation);
    tty->print_cr("num_oops: %d ref_sp: %d needs alloc: %d", oops, _ref_sp, needs_stack_allocation);
  }
  guarantee(PERFTEST_LEVEL >= 100, "");

  if (!allocate_stacks_in_native(size, oops, needs_stack_allocation, needs_refStack_allocation)) {
    allocate_stacks_in_java(size, oops, frames);
    if (!thread()->has_pending_exception()) return;
  }

  // These assertions aren't important, as we'll overwrite the Java-computed ones, but they're just to test that the Java computation is OK.
  assert(_pc == java_lang_Continuation::pc(_cont), "_pc: " INTPTR_FORMAT "  this.pc: " INTPTR_FORMAT "",  p2i(_pc), p2i(java_lang_Continuation::pc(_cont)));
  assert(_sp == java_lang_Continuation::sp(_cont), "_sp: %d  this.sp: %d",  _sp, java_lang_Continuation::sp(_cont));
  assert(_fp == java_lang_Continuation::fp(_cont), "_fp: %lu  this.fp: " JLONG_FORMAT " %d %d", _fp, java_lang_Continuation::fp(_cont), Interpreter::contains(_pc), is_flag(FLAG_LAST_FRAME_INTERPRETED));
  assert (oopDesc::equals(_stack, java_lang_Continuation::stack(_cont)), "");
  assert (_stack->base(T_INT) == _hstack, "");

  assert (to_bytes(_stack_length) >= size, "sanity check: stack_size: %d size: %d", to_bytes(_stack_length), size);
  assert (to_bytes(_sp) >= size, "sanity check");
  assert (to_bytes(_ref_sp) >= oops, "oops: %d ref_sp: %d refStack length: %d", oops, _ref_sp, _ref_stack->length());
}

void ContMirror::allocate_stacks_in_java(int size, int oops, int frames) {
  // tty->print_cr("ALLOCATE_STACKS_IN_JAVA");
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
  post_safepoint(conth); // reload oop after java call

  _sp    = java_lang_Continuation::sp(_cont);
  _fp    = java_lang_Continuation::fp(_cont);
  _ref_sp    = java_lang_Continuation::refSP(_cont);

  _stack_length = _stack->length();
  /* We probably should handle OOM? */
}

bool ContMirror::grow_stack(int new_size) {
  new_size = new_size >> 2; // convert to number of elements 

  int old_length = _stack_length;
  int offset = _sp > 0 ? _sp : old_length;
  int min_length = (old_length - offset) + new_size;

  if (min_length <= old_length) {
    return false;
  }

  int new_length = ensure_capacity(old_length, min_length);
  if (new_length == -1) {
    return false;
  }

  typeArrayOop new_stack = allocate_stack_array(new_length);
  if (new_stack == NULL) {
    return false;
  }

  log_develop_trace(jvmcont)("grow_stack old_length: %d new_length: %d", old_length, new_length);
  int* new_hstack = (int*)new_stack->base(T_INT);
  int n = old_length - offset;
  assert(new_length > n, "");
  if (n > 0) {
    int* from = _hstack + offset;
    void* to = new_hstack + (new_length - n);
    size_t size = to_bytes(n);
    memcpy(to, from, size);
    //Copy::conjoint_memory_atomic(from, to, size); // Copy::disjoint_words((HeapWord*)from, (HeapWord*)to, size/wordSize); // 
    // ArrayAccess<ARRAYCOPY_DISJOINT>::oop_arraycopy(_stack, offset * 4, new_stack, (new_length - n) * 4, n);
  }
  _stack = new_stack;
  _stack_length = new_length;
  _hstack = new_hstack;

  log_develop_trace(jvmcont)("grow_stack old sp: %d fp: %ld", _sp, _fp);
  _sp = fix_decreasing_index(_sp, old_length, new_length);
  if (is_flag(FLAG_LAST_FRAME_INTERPRETED)) { // if (Interpreter::contains(_pc)) {// only interpreter frames use relative (index) fp
    _fp = fix_decreasing_index(_fp, old_length, new_length);
  }
  log_develop_trace(jvmcont)("grow_stack new sp: %d fp: %ld", _sp, _fp);

  return true;
}

bool ContMirror::grow_ref_stack(int nr_oops) {
  int old_length = _ref_stack->length();
  //assert(old_length == _ref_sp, "check"); // this assert is just a check to see if this ever happens and not to enforce behaviour -- it does happen with lazy-copy

  int min_length = (old_length - _ref_sp) + nr_oops;
  int new_length = ensure_capacity(old_length, min_length);
  if (new_length == -1) {
    return false;
  }
  objArrayOop new_ref_stack = allocate_refstack_array(new_length);

  if (new_ref_stack == NULL) {
    return false;
  }

  log_develop_trace(jvmcont)("grow_ref_stack old_length: %d new_length: %d", old_length, new_length);

  int n = old_length - _ref_sp;
  if (n > 0) {
    assert(UseNewCode, "");
    for (int i=0, old_i = _ref_sp, new_i = fix_decreasing_index(_ref_sp, old_length, new_length); i<n; i++, old_i++, new_i++) new_ref_stack->obj_at_put(new_i, _ref_stack->obj_at(old_i));
    // The following fails on Skynet with UseCompressedOops + tiered compilation + UseNewCode (it succeeds with any one of these options being turned off)
    // ArrayAccess<ARRAYCOPY_DISJOINT>::oop_arraycopy(_ref_stack, _ref_sp * heapOopSize, new_ref_stack, (new_length - n) * heapOopSize, n * heapOopSize);
  }

  _ref_stack = new_ref_stack;

  log_develop_trace(jvmcont)("grow_stack old ref_sp: %d", _ref_sp);
  _ref_sp = fix_decreasing_index(_ref_sp, old_length, new_length);
  log_develop_trace(jvmcont)("grow_stack new ref_sp: %d", _ref_sp);
  return true;
}

int ContMirror::fix_decreasing_index(int index, int old_length, int new_length) const {
  return new_length - (old_length - index);
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

///// DEBUGGING

#ifndef PRODUCT
void Continuation::describe(FrameValues &values) {
  JavaThread* thread = JavaThread::current();
  if (thread != NULL) {
    for (oop cont = thread->last_continuation(); cont != (oop)NULL; cont = java_lang_Continuation::parent(cont)) {
      intptr_t* bottom = java_lang_Continuation::entrySP(cont);
      if (bottom != NULL)
        values.describe(-1, bottom, "continuation entry");
    }
  }
}
#endif

static void print_oop(void *p, oop obj, outputStream* st) {
  if (!log_develop_is_enabled(Trace, jvmcont) && st != NULL) return;

  if (st == NULL) st = tty;

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

#ifdef ASSERT

static long java_tid(JavaThread* thread) {
  return java_lang_Thread::thread_id(thread->threadObj());
}

static void print_frames(JavaThread* thread, outputStream* st) {
  if (st != NULL && !log_develop_is_enabled(Trace, jvmcont)) return;
  if (st == NULL) st = tty;

  if (true) {
    st->print_cr("------- frames ---------");
    RegisterMap map(thread, true, false);
  #ifndef PRODUCT
    map.set_skip_missing(true);
    ResourceMark rm;
    FrameValues values;
  #endif

    int i = 0;
    for (frame f = thread->last_frame(); !f.is_entry_frame(); f = f.sender(&map)) {
  #ifndef PRODUCT
      // print_vframe(f, &map, st);
      f.describe(values, i, &map);
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

// static inline bool is_not_entrant(const frame& f) {
//   return  f.is_compiled_frame() ? f.cb()->as_nmethod()->is_not_entrant() : false;
// }

static char* method_name(Method* m) {
  return m != NULL ? m->name_and_sig_as_C_string() : NULL;
}

static inline Method* top_java_frame_method(const frame& f) {
  Method* m = NULL;
  if (f.is_interpreted_frame()) {
    m = f.interpreter_frame_method();
  } else if (f.is_compiled_frame()) {
    CompiledMethod* cm = f.cb()->as_compiled_method();
    ScopeDesc* scope = cm->scope_desc_at(f.pc());
    m = scope->method();
  }
  // m = ((CompiledMethod*)f.cb())->method();
  return m;
}

static inline Method* bottom_java_frame_method(const frame& f) {
  return Frame::frame_method(f);
}

static char* top_java_frame_name(frame& f) {
  return method_name(top_java_frame_method(f));
}

static char* bottom_java_frame_name(frame& f) {
  return method_name(bottom_java_frame_method(f));
}

static inline bool is_deopt_return(address pc, frame& sender) {
  if (sender.is_interpreted_frame()) return false;

  CompiledMethod* cm = sender.cb()->as_compiled_method();
  return cm->is_deopt_pc(pc);
}
#endif

void ContMirror::print_hframes(outputStream* st) {
  if (st != NULL && !log_develop_is_enabled(Trace, jvmcont)) return;
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

// void static stop() {
//     print_frames(JavaThread::current(), NULL);
//     assert (false, "");
// }

// void static stop(const frame& f) {
//     f.print_on(tty);
//     stop();
// }
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
    cont.cleanup();
}
JVM_END

JVM_ENTRY(jint, CONT_isPinned0(JNIEnv* env, jobject cont_scope)) {
  JavaThread* thread = JavaThread::thread_from_jni_environment(env);
  return is_pinned0(thread, JNIHandles::resolve(cont_scope), false);
}
JVM_END

JVM_ENTRY(jint, CONT_TryForceYield0(JNIEnv* env, jobject jcont, jobject jthread)) {
  JavaThread* thread = JavaThread::thread_from_jni_environment(env);

  if (!ThreadLocalHandshakes || !SafepointMechanism::uses_thread_local_poll()) {
    return -5;
  }

  class ForceYieldClosure : public ThreadClosure {
    jobject _jcont;
    jint _result;

    void do_thread(Thread* th) {
      // assert (th == Thread::current(), ""); -- the handshake can be carried out by a VM thread (see HandshakeState::process_by_vmthread)
      assert (th->is_Java_thread(), "");
      JavaThread* thread = (JavaThread*)th;
      
      oop oopCont = JNIHandles::resolve_non_null(_jcont);
      _result = Continuation::try_force_yield(thread, oopCont);
    }

  public:
    ForceYieldClosure(jobject jcont) : _jcont(jcont), _result(-1) {}
    jint result() const { return _result; }
  };
  ForceYieldClosure fyc(jcont);

  // tty->print_cr("TRY_FORCE_YIELD0");
  // thread->print();
  // tty->print_cr("");

  if (true) {
    oop thread_oop = JNIHandles::resolve(jthread);
    if (thread_oop != NULL) {
      JavaThread* target = java_lang_Thread::thread(thread_oop);
      Handshake::execute(&fyc, target);
    }
  } else {
    Handshake::execute(&fyc);
  }
  return fyc.result();
}
JVM_END

#define CC (char*)  /*cast a literal from (const char*)*/
#define FN_PTR(f) CAST_FROM_FN_PTR(void*, &f)

static JNINativeMethod CONT_methods[] = {
    {CC"clean0",           CC"()V",                              FN_PTR(CONT_Clean)},
    {CC"tryForceYield0",   CC"(Ljava/lang/Thread;)I",            FN_PTR(CONT_TryForceYield0)},
    {CC"isPinned0",        CC"(Ljava/lang/ContinuationScope;)I", FN_PTR(CONT_isPinned0)},
};

void CONT_RegisterNativeMethods(JNIEnv *env, jclass cls) {
    Thread* thread = Thread::current();
    assert(thread->is_Java_thread(), "");
    ThreadToNativeFromVM trans((JavaThread*)thread);
    int status = env->RegisterNatives(cls, CONT_methods, sizeof(CONT_methods)/sizeof(JNINativeMethod));
    guarantee(status == JNI_OK && !env->ExceptionOccurred(), "register java.lang.Continuation natives");
}

#include CPU_HEADER_INLINE(continuation)

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
//
// #endif

class ConfigResolve {
public:

  static void resolve() { resolve_compressed(); }

  static void resolve_compressed() {
    UseCompressedOops ? resolve_modref<true>() 
                      : resolve_modref<false>();
  }

  template <bool use_compressed>
  static void resolve_modref() {
    BarrierSet::barrier_set()->is_a(BarrierSet::ModRef)
      ? resolve_gencode<use_compressed, true>()
      : resolve_gencode<use_compressed, false>();
  }

  template <bool use_compressed, bool is_modref>
  static void resolve_gencode() {
    LoomGenCode ? resolve<use_compressed, is_modref, true>() 
                : resolve<use_compressed, is_modref, false>();
  }

  template <bool use_compressed, bool is_modref, bool gen_code>
  static void resolve() {
    cont_freeze_fast    = FreezeConfig<use_compressed, is_modref, gen_code>::template freeze<mode_fast>;
    cont_freeze_slow    = FreezeConfig<use_compressed, is_modref, gen_code>::template freeze<mode_slow>;
    cont_freeze_preempt = FreezeConfig<use_compressed, is_modref, gen_code>::template freeze<mode_preempt>;
  }
};


void Continuations::init() {
  ConfigResolve::resolve();
}
