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
#define YIELD0_SIG  "java.lang.Continuation.yield0(Ljava/lang/ContinuationScope;Ljava/lang/Continuation;)Z"
#define ENTER_SIG  "java.lang.Continuation.enter()V"
#define RUN_SIG    "java.lang.Continuation.run()V"

static bool is_stub(CodeBlob* cb);

// debugging functions
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

// #define CHOOSE1(interp, f, ...) ((interp) ? Interpreted::f(__VA_ARGS__) : Compiled::f(__VA_ARGS__))
#define CHOOSE2(interp, f, ...) ((interp) ? f<Interpreted>(__VA_ARGS__) : f<Compiled>(__VA_ARGS__))

static const unsigned char FLAG_LAST_FRAME_INTERPRETED = 1;
static const unsigned char FLAG_SAFEPOINT_YIELD = 1 << 1;

void continuations_init() {
  Continuations::init();
}

class ContMirror;
class hframe;

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

  static bool is_instance(const frame& f);
  static bool is_instance(const hframe& hf);
};

class Interpreted : public FrameCommon<Interpreted> {
public:
  DEBUG_ONLY(static const char* name;)
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

DEBUG_ONLY(const char* Interpreted::name = "Interpreted";)

template<typename Self>
class NonInterpreted : public FrameCommon<Self>  {
public:
  static inline intptr_t* frame_top(const frame& f);
  static inline intptr_t* frame_bottom(const frame& f);

  static inline address* return_pc_address(const frame& f);
  static bool is_owning_locks(JavaThread* thread, const RegisterMap* map, const frame& f);
};

class NonInterpretedUnknown : public NonInterpreted<NonInterpretedUnknown>  {
public:
  DEBUG_ONLY(static const char* name;)
  static const bool interpreted = false;

  static bool is_instance(const frame& f);
  static bool is_instance(const hframe& hf);
};

DEBUG_ONLY(const char* NonInterpretedUnknown::name = "NonInterpretedUnknown";)

class Compiled : public NonInterpreted<Compiled>  {
public:
  DEBUG_ONLY(static const char* name;)
  static const bool interpreted = false;
  static const bool stub = false;
};

DEBUG_ONLY(const char* Compiled::name = "Compiled";)

class StubF : public NonInterpreted<StubF> {
public:
  DEBUG_ONLY(static const char* name;)
  static const bool interpreted = false;
  static const bool stub = true;
};

DEBUG_ONLY(const char* StubF::name = "Stub";)

static bool is_stub(CodeBlob* cb) {
  return cb != NULL && (cb->is_safepoint_stub() || cb->is_runtime_stub());
}

enum op_mode {
  mode_fast,   // only compiled frames
  mode_slow,   // possibly interpreted frames
  mode_preempt // top frame is safepoint stub (forced preemption)
};

// Represents a stack frame on the horizontal stack, analogous to the frame class, for vertical-stack frames.

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

  HFrameBase(int sp, int ref_sp, address pc, const ContMirror& cont)
    : _sp(sp), _ref_sp(ref_sp), _pc(pc), 
      _oop_map(NULL), _is_interpreted(Interpreter::contains(pc)) {
      _cb = NULL;
      set_codeblob(_pc);
    }

  HFrameBase(int sp, int ref_sp, address pc, CodeBlob* cb, bool is_interpreted) // called by ContMirror::new_hframe
    : _sp(sp), _ref_sp(ref_sp), _pc(pc),
      _cb(cb), _oop_map(NULL), _is_interpreted(is_interpreted) {}

  static address deopt_original_pc(const ContMirror& cont, address pc, CodeBlob* cb, int sp);

public:
  inline bool operator==(const HFrameBase& other) const;
  bool is_empty() const { return _pc == NULL && _sp < 0; }

  inline bool is_interpreted_frame() const { return _is_interpreted; }
  inline int       sp()     const { return _sp; }
  inline address   pc()     const { return _pc; }
  inline int       ref_sp() const { return _ref_sp; }
  CodeBlob* cb() const { return _cb; }

  inline void set_pc(address pc) { _pc = pc; }
  inline void set_ref_sp(int ref_sp) { _ref_sp = ref_sp; }

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

  template<typename FKind, op_mode mode> SelfPD sender(const ContMirror& cont, int num_oops) const {
    assert (mode != mode_fast || !FKind::interpreted, "");
    return CONST_PD.template sender<FKind, mode>(cont, num_oops); 
  }
  template<typename FKind, op_mode mode> SelfPD sender(const ContMirror& cont, InterpreterOopMap* mask) const;
  template<op_mode mode /* = mode_slow*/> SelfPD sender(const ContMirror& cont) const;

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

template<typename Self> bool FrameCommon<Self>::is_instance(const frame& f)  { return (Self::interpreted == f.is_interpreted_frame()) && (Self::stub == is_stub(f.cb())); }
template<typename Self> bool FrameCommon<Self>::is_instance(const hframe& f) { return (Self::interpreted == f.is_interpreted_frame()) && (Self::stub == is_stub(f.cb())); }

bool NonInterpretedUnknown::is_instance(const frame& f)  { return (interpreted == f.is_interpreted_frame()); }
bool NonInterpretedUnknown::is_instance(const hframe& f) { return (interpreted == f.is_interpreted_frame()); }

const int LogBytesPerIndex = 2;

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
  objArrayOop _ref_stack;

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
  oop raw_allocate(Klass* klass, size_t words, size_t elements, bool zero);
  typeArrayOop allocate_stack_array(size_t elements);
  objArrayOop  allocate_refstack_array(size_t nr_oops);
  bool allocate_stack(int size);
  bool allocate_ref_stack(int nr_oops);
  bool allocate_stacks_in_native(int size, int oops, bool needs_stack, bool needs_refstack);
  void allocate_stacks_in_java(int size, int oops, int frames);
  bool grow_stack(int new_size);
  bool grow_ref_stack(int nr_oops);
  int fix_decreasing_index(int index, int old_length, int new_length) const;
  int ensure_capacity(int old, int min);

public:
  static inline int to_index(size_t x) { return x >> LogBytesPerIndex; } // stack is int[]
  static inline int to_bytes(int x)    { return x << LogBytesPerIndex; } // stack is int[]
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
  oop parent() { return java_lang_Continuation::parent(_cont); }
  void cleanup();

  intptr_t* entrySP() const { return _entrySP; }
  intptr_t* entryFP() const { return _entryFP; }
  address   entryPC() const { return _entryPC; }

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

  template<op_mode mode> const hframe last_frame();
  inline void set_last_frame(const hframe& f);
  inline void set_last_frame_pd(const hframe& f);
  inline void set_empty();

  hframe from_frame(const frame& f);

  template <typename ConfigT>
  inline int add_oop(oop obj, int index);

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

  inline void e_add_refs(int num) { _e_num_refs += num; }
  template<typename Event> void post_jfr_event(Event *e);
};

template<typename SelfPD>
inline bool HFrameBase<SelfPD>::operator==(const HFrameBase& other) const {
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
address HFrameBase<SelfPD>::deopt_original_pc(const ContMirror& cont, address pc, CodeBlob* cb, int sp) {
  // TODO DEOPT: unnecessary in the long term solution of unroll on freeze

  assert (cb != NULL && cb->is_compiled(), "");
  CompiledMethod* cm = cb->as_compiled_method();
  if (cm->is_deopt_pc(pc)) {
    log_develop_trace(jvmcont)("hframe::deopt_original_pc deoptimized frame");
    pc = *(address*)((address)cont.stack_address(sp) + cm->orig_pc_offset());
    assert(pc != NULL, "");
    assert(cm->insts_contains_inclusive(pc), "original PC must be in the main code section of the the compiled method (or must be immediately following it)");
    assert(!cm->is_deopt_pc(pc), "");
    // _deopt_state = is_deoptimized;
  }

  return pc;
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
  assert (FKind::is_instance(*(hframe*)this), "");

  return _sp;
}

template<typename SelfPD>
template<typename FKind, op_mode mode>
SelfPD HFrameBase<SelfPD>::sender(const ContMirror& cont, InterpreterOopMap* mask) const {
  assert (mode != mode_fast || !FKind::interpreted, "");
  return sender<FKind, mode>(cont, FKind::interpreted ? interpreted_frame_num_oops(*mask) : oop_map()->num_oops());
}

template<typename SelfPD>
template<op_mode mode>
SelfPD HFrameBase<SelfPD>::sender(const ContMirror& cont) const { 
  if (_is_interpreted) {
    InterpreterOopMap mask;
    interpreted_frame_oop_map(&mask);
    return sender<Interpreted, mode>(cont, &mask);
  } else {
    return sender<NonInterpretedUnknown, mode>(cont, (InterpreterOopMap*)NULL);
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
  for (hframe hf = last_frame<mode_slow>(); !hf.is_empty(); hf = hf.sender<mode_slow>(*this)) {
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
  bool empty = _sp < 0 || _sp >= _stack->length();
  assert (empty == (_pc == NULL), "");
  return empty;
}

inline void ContMirror::set_last_frame(const hframe& f) {
  // assert (f._length = _stack_length, "");
  set_sp(f.sp());
  set_pc(f.pc()); // DEOPT?
  set_last_frame_pd(f);
  set_refSP(f.ref_sp());

  assert (!is_empty(), ""); // if (is_empty()) set_empty();

  if (log_develop_is_enabled(Trace, jvmcont)) {
    log_develop_trace(jvmcont)("set_last_frame cont sp: %d fp: 0x%lx pc: " INTPTR_FORMAT " interpreted: %d flag: %d", sp(), fp(), p2i(pc()), f.is_interpreted_frame(), is_flag(FLAG_LAST_FRAME_INTERPRETED));
    f.print_on(*this, tty);
  }
}

inline void ContMirror::set_empty() {
  if (_stack_length > 0) {
    set_sp(_stack_length);
    set_refSP(_ref_stack->length());
  }
  set_fp(0);
  set_pc(NULL);
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

template <typename ConfigT>
inline int ContMirror::add_oop(oop obj, int index) {
  // assert (_ref_stack != NULL, "");
  // assert (index >= 0 && index < _ref_stack->length(), "index: %d length: %d", index, _ref_stack->length());
  assert (index < _ref_sp, "");

  log_develop_trace(jvmcont)("i: %d ", index);
  ConfigT::OopWriterT::obj_at_put(_ref_stack, index, obj);
  return index;
}

inline oop ContMirror::obj_at(int i) {
  assert (_ref_stack != NULL, "");
  assert (0 <= i && i < _ref_stack->length(), "i: %d length: %d", i, _ref_stack->length()); 
  // assert (_ref_sp <= i, "i: %d _ref_sp: %d length: %d", i, _ref_sp, _ref_stack->length()); -- in Thaw, we set_last_frame before reading the objects during the recursion return trip
  
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
  static inline void update_register_map(RegisterMap* map, hframe::callee_info callee_info);
  static inline frame frame_with(frame& f, intptr_t* sp, address pc);
  static inline frame last_frame(JavaThread* thread);
  static inline void to_frame_info(const frame& f, const frame& callee, FrameInfo* fi);
  template<typename FKind> static inline void to_frame_info_pd(const frame& f, const frame& callee, FrameInfo* fi);
  static inline void to_frame_info_pd(const frame& f, FrameInfo* fi);
  static inline frame to_frame(FrameInfo* fi);
  static inline frame to_frame_indirect(FrameInfo* fi);
  static inline void set_last_vstack_frame(RegisterMap* map, const frame& callee);
};

#ifdef ASSERT
  static char* method_name(Method* m);
  static inline Method* top_java_frame_method(const frame& f);
  static inline Method* bottom_java_frame_method(const frame& f);
  static char* top_java_frame_name(const frame& f);
  static char* bottom_java_frame_name(const frame& f);
  static bool assert_top_java_frame_name(const frame& f, const char* name);
  static bool assert_bottom_java_frame_name(const frame& f, const char* name);
  static inline bool is_deopt_return(address pc, const frame& sender);
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
  assert (mask != NULL, "");
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

template<typename Self>
inline intptr_t* NonInterpreted<Self>::frame_top(const frame& f) { // inclusive; this will be copied with the frame
  return f.unextended_sp();
}

template<typename Self>
inline intptr_t* NonInterpreted<Self>::frame_bottom(const frame& f) { // exclusive; this will not be copied with the frame
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

template<typename Self>
bool NonInterpreted<Self>::is_owning_locks(JavaThread* thread, const RegisterMap* map, const frame& f) {
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

void ContinuationHelper::to_frame_info(const frame& f, const frame& callee, FrameInfo* fi) {
  fi->sp = f.unextended_sp(); // java_lang_Continuation::entrySP(cont);
  fi->pc = Frame::real_pc(f); // Continuation.run may have been deoptimized
  // callee.is_interpreted_frame() ? ContinuationHelper::to_frame_info_pd<Interpreted>(f, callee, fi)
  //                               : ContinuationHelper::to_frame_info_pd<Compiled   >(f, callee, fi);
  CHOOSE2(callee.is_interpreted_frame(), ContinuationHelper::to_frame_info_pd, f, callee, fi);
}

void clear_frame_info(FrameInfo* fi) {
  fi->fp = NULL; 
  fi->sp = NULL; 
  fi->pc = NULL;
}

// works only in thaw
static inline bool is_entry_frame(const ContMirror& cont, const frame& f) {
  return f.sp() == cont.entrySP();
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
  ResourceMark rm; // used for scope traversal in num_java_frames(CompiledMethod*, address)
  int count = 0;
  for (hframe hf = cont.last_frame<mode_slow>(); !hf.is_empty(); hf = hf.sender<mode_slow>(cont))
    count += num_java_frames(hf);
  return count;
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

#ifdef ASSERT
static void set_anchor(ContMirror& cont) {
  FrameInfo fi = { cont.entryPC(), cont.entryFP(), cont.entrySP() };
  set_anchor(cont.thread(), &fi);
}
#endif

static oop get_continuation(JavaThread* thread) {
  assert (thread != NULL, "");
  return thread->last_continuation();
}

// static void set_continuation(JavaThread* thread, oop cont) {
//   java_lang_Thread::set_continuation(thread->threadObj(), cont);
// }

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

typedef freeze_result (*FreezeContFnT)(JavaThread*, ContMirror&, FrameInfo*);

static FreezeContFnT cont_freeze_fast = NULL;
static FreezeContFnT cont_freeze_slow = NULL;
static FreezeContFnT cont_freeze_preempt = NULL;

template<op_mode mode>
static freeze_result cont_freeze(JavaThread* thread, ContMirror& cont, FrameInfo* fi) {
  switch (mode) {
    case mode_fast:    return cont_freeze_fast   (thread, cont, fi);
    case mode_slow:    return cont_freeze_slow   (thread, cont, fi);
    case mode_preempt: return cont_freeze_preempt(thread, cont, fi);
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

template <typename ConfigT, op_mode mode>
class Freeze {

private:
  JavaThread* _thread;
  ContMirror& _cont;
  intptr_t *_bottom_address;

  RegisterMap _map;

  int _oops;
  int _size; // total size of all frames plus metadata. keeps track of offset where a frame should be written and how many bytes we need to allocate.
  int _frames;

  FpOopInfo _fp_oop_info;
  FrameInfo* _fi;

  frame _safepoint_stub;
  hframe _safepoint_stub_h;
  bool  _safepoint_stub_caller;
#ifndef PRODUCT
  intptr_t* _safepoint_stub_hsp;
#endif

  template<typename FKind> static inline frame sender(frame& f);
  template<typename FKind> static inline frame sender(frame& f, hframe::callee_info* callee_info);
  template <typename FKind, bool top, bool bottom> inline void patch_pd(frame& f, hframe& callee, const hframe& caller);
  template <typename FKind, bool top, bool bottom> inline void align(hframe& caller);
  inline void relativize_interpreted_frame_metadata(frame& f, intptr_t* vsp, hframe& hf);
  template<typename FKind> hframe new_callee_hframe(const frame& f, intptr_t* vsp, hframe& caller, int fsize, int num_oops);
  template<bool cont_empty> hframe new_bottom_hframe(int sp, int ref_sp, address pc, bool interpreted);

  typedef int (*FreezeFnT)(address, address, address, address, int, FpOopInfo*);

public:

  Freeze(JavaThread* thread, ContMirror& mirror) : 
    _thread(thread), _cont(mirror), _bottom_address(mirror.entrySP()), 
    _map(thread, false, false, false),
    _oops(0), _size(0), _frames(0),
    _fp_oop_info(), _safepoint_stub_caller(false) {
    
    _map.set_include_argument_oops(false);
  }

  int nr_oops() const   { return _oops; }
  int nr_bytes() const  { return _size; }
  int nr_frames() const { return _frames; }

  freeze_result freeze(FrameInfo* fi) {
    _fi = fi;

    // assert (map.update_map(), "RegisterMap not set to update");
    assert (!_map.include_argument_oops(), "should be");
    frame f = freeze_start_frame();
    hframe caller;
    return freeze<true>(f, caller, Frame::saved_link_address(&_map), 0);
  }

  frame freeze_start_frame() {
    if (mode == mode_preempt) // TODO: we should really do partial specialization, but then we'll need to define this out-of-line
      return freeze_start_frame_safepoint_stub();
    
    assert (mode != mode_preempt, "");

    log_develop_trace(jvmcont)("%s nop at freeze yield", nativePostCallNop_at(_fi->pc) != NULL ? "has" : "no");

    // Note: if the doYield stub does not have its own frame, we may need to consider deopt here, especially if yield is inlinable
    frame f = ContinuationHelper::last_frame(_thread); // thread->last_frame();
    assert (StubRoutines::cont_doYield_stub()->contains(f.pc()), "must be");
    ContinuationHelper::update_register_map<StubF>(&_map, f);
    f = sender<StubF>(f);  // this is the yield frame

    // The following doesn't work because fi->fp can contain an oop, that a GC doesn't know about when walking.
    // frame::update_map_with_saved_link(&map, (intptr_t **)&fi->fp);
    // frame f = ContinuationHelper::to_frame(fi); // the yield frame

    assert (f.pc() == _fi->pc, "");

    // Log(jvmcont) logv; LogStream st(logv.debug()); f.print_on(st);
    if (log_develop_is_enabled(Debug, jvmcont)) f.print_on(tty);

    return f;
  }

  frame freeze_start_frame_safepoint_stub() {
    // safepoint yield
    frame f = _thread->last_frame();
    f.set_fp(f.real_fp()); // Instead of this, maybe in ContMirror::set_last_frame always use the real_fp? // TODO PD
    if (Interpreter::contains(f.pc())) {
      log_develop_trace(jvmcont)("INTERPRETER SAFEPOINT");
      ContinuationHelper::update_register_map<Interpreted>(&_map, f);
      // f.set_sp(f.sp() - 1); // state pushed to the stack
    } else {
      log_develop_trace(jvmcont)("COMPILER SAFEPOINT");
  #ifdef ASSERT
      if (!is_stub(f.cb())) { f.print_value_on(tty, JavaThread::current()); }
  #endif
      assert (is_stub(f.cb()), "must be");
      assert (f.oop_map() != NULL, "must be");
      ContinuationHelper::update_register_map<StubF>(&_map, f);
      f.oop_map()->update_register_map(&f, &_map); // we have callee-save registers in this case
    }

    // Log(jvmcont) logv; LogStream st(logv.debug()); f.print_on(st);
    if (log_develop_is_enabled(Debug, jvmcont)) f.print_on(tty);

    return f;
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

      return recurse_java_frame<Compiled, top>(f, caller, callee_link_address, callee_argsize, NULL);
    } else if (f.is_interpreted_frame()) {
      if (Interpreted::is_owning_locks(f)) return freeze_pinned_monitor;
      // ResourceMark rm(_thread);
      InterpreterOopMap mask;
      Interpreted::oop_map(f, &mask);
      return recurse_java_frame<Interpreted, top>(f, caller, callee_link_address, callee_argsize, &mask);
    } else if (mode == mode_preempt && top && is_stub(f.cb())) {
      return recurse_stub_frame(f, caller);
    } else {
      return freeze_pinned_native;
    }
  }

  template<typename FKind, bool top>
  freeze_result recurse_java_frame(frame& f, hframe& caller, hframe::callee_info callee_info, int callee_argsize, InterpreterOopMap* mask) {
    assert (FKind::is_instance(f), "");

    _frames++;

    int fsize, argsize, oops;
    prepare_freeze_frame<FKind>(f, &fsize, &argsize, &oops, callee_argsize, mask);
    void* extra = FKind::interpreted ? (void*) mask
                                     : (void*) get_oopmap_stub<FKind>(f); // try to do this early, so we wouldn't need to look at the oopMap again.

    assert (!FKind::interpreted || argsize == 0, "");

    hframe::callee_info my_info;
    frame senderf = sender<FKind>(f, &my_info); // f.sender_for_compiled_frame<ContinuationCodeBlobLookup>(&map);

    // sometimes an interpreted caller's sp extends a bit below entrySP, plus another word for possible alignment of compiled callee
    if (senderf.unextended_sp() >= _bottom_address - 2) { // dynmic branch
      // senderf is the entry frame
      freeze_result result = finalize<FKind>(senderf, f, caller); // recursion end
      if (result != freeze_ok)
        return result;
      
      ContinuationHelper::update_register_map(&_map, callee_info); // restore saved link
      freeze_java_frame<FKind, top, true>(f, caller, fsize, argsize, oops, callee_argsize, extra);

      if (log_develop_is_enabled(Trace, jvmcont)) {
        log_develop_trace(jvmcont)("bottom h-frame:");
        caller.print(_cont); // caller is now the current hframe
      }
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
      ContinuationHelper::update_register_map(&_map, callee_info);  // restore saved link

      freeze_java_frame<FKind, top, false>(f, caller, fsize, argsize, oops, callee_argsize, extra);
    }

    if (top) {
      finish(f, caller);
    }
    return freeze_ok;
  }

  template <typename FKind> // TODO Choose more descriptive name
  void prepare_freeze_frame(frame& f, int* fsize, int* argsize, int* oops, int callee_argsize, InterpreterOopMap* mask) {
    int oops0 = 0;
    int argsize0 = 0;
    const int fsize0 = FKind::interpreted ? interpreted_frame_size(f, &oops0, mask) 
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

  template<typename FKind> // the callee's type
  NOINLINE freeze_result finalize(frame& f, frame& callee, hframe& caller) {
  #ifdef CALLGRIND_START_INSTRUMENTATION
    // if (_frames == _compiled && callgrind_counter == 1) {
    //   callgrind_counter = 2;
    //   tty->print_cr("Starting callgrind instrumentation");
    //   CALLGRIND_START_INSTRUMENTATION;
    // }
  #endif
    if (PERFTEST_LEVEL <= 15) return freeze_ok;

    // f is the entry frame

  #ifdef ASSERT
    log_develop_trace(jvmcont)("Found entry:");
    if (log_develop_is_enabled(Trace, jvmcont)) f.print_on(tty);

    hframe orig_top_frame = _cont.last_frame<mode_slow>();
    bool empty = _cont.is_empty();
    log_develop_trace(jvmcont)("bottom: " INTPTR_FORMAT " count %d size: %d, num_oops: %d", p2i(_bottom_address), nr_frames(), nr_bytes(), nr_oops());
    log_develop_trace(jvmcont)("top_hframe before (freeze):");
    if (log_develop_is_enabled(Trace, jvmcont)) orig_top_frame.print_on(_cont, tty);

    log_develop_trace(jvmcont)("empty: %d", empty);
    assert (!CONT_FULL_STACK || empty, "");
    assert (!empty || _cont.sp() >= _cont.stack_length() || _cont.sp() < 0, "sp: %d stack_length: %d", _cont.sp(), _cont.stack_length());
    assert (orig_top_frame.is_empty() == empty, "empty: %d f.sp: %d", empty, orig_top_frame.sp());
  #endif

    setup_jump<FKind>(f, callee);

    _cont.allocate_stacks(_size, _oops, _frames);
    if (_thread->has_pending_exception()) 
      return freeze_exception;

    if (_cont.is_empty()) {
      caller = new_bottom_hframe<true>(_cont.sp(), _cont.refSP(), NULL, false);
    } else {
      assert (_cont.is_flag(FLAG_LAST_FRAME_INTERPRETED) == Interpreter::contains(_cont.pc()), "");
      caller = new_bottom_hframe<false>(_cont.sp(), _cont.refSP(), _cont.pc(), _cont.is_flag(FLAG_LAST_FRAME_INTERPRETED));
    }

    return freeze_ok;
  }

  template<typename FKind> // the callee's type
  void setup_jump(frame& f, frame& callee) {
    // upon end of freeze, f points at the entry frame. we need to point it one more frame, to Continuation.run
    // TODO performance: we don't need all frame fields, e.g. we get CodeBlocb in sender, but don't use it
    // ::fix_continuation_bottom_sender(_cont, f); // need this so frame_sender could work  // check if :: is necessary
    // frame f = f0.frame_sender<ContinuationCodeBlobLookup>(&dmap);

    ContinuationHelper::to_frame_info_pd<FKind>(f, callee, _fi);
    _fi->sp = f.unextended_sp(); // java_lang_Continuation::entrySP(cont);
    _fi->pc = Continuation::is_return_barrier_entry(f.pc()) ? _cont.entryPC()
                                                            : Frame::real_pc(f); // Continuation.run may have been deoptimized

  #ifdef ASSERT
    // if (f.pc() != real_pc(f)) tty->print_cr("Continuation.run deopted!");
    log_develop_debug(jvmcont)("Jumping to frame (freeze): [%ld] (%d)", java_tid(_thread), _thread->has_pending_exception());
    frame f1 = ContinuationHelper::to_frame_indirect(_fi);
    if (log_develop_is_enabled(Debug, jvmcont)) f1.print_on(tty);
    assert_top_java_frame_name(f1, RUN_SIG);
  #endif
  }
  
  template<typename FKind, bool top, bool bottom>
  void freeze_java_frame(frame& f, hframe& caller, int fsize, int argsize, int oops, int callee_argsize, void* extra) {
    if (PERFTEST_LEVEL <= 15) return;

    log_develop_trace(jvmcont)("============================= FREEZING FRAME interpreted: %d top: %d bottom: %d", FKind::interpreted, top, bottom);
    log_develop_trace(jvmcont)("fsize: %d argsize: %d oops: %d callee_argsize: %d", fsize, argsize, oops, callee_argsize);
    if (log_develop_is_enabled(Trace, jvmcont)) f.print_on(tty);

    caller = FKind::interpreted 
      ? freeze_interpreted_frame       <top, bottom>(f, caller, fsize,          oops, callee_argsize, (InterpreterOopMap*)extra)
      : freeze_compiled_frame<Compiled, top, bottom>(f, caller, fsize, argsize, oops, callee_argsize, (FreezeFnT)extra);
  }

  template <typename FKind>
  void freeze_oops(frame& f, intptr_t* vsp, intptr_t *hsp, int index, int num_oops, void* extra) {
    if (PERFTEST_LEVEL < 30) return;

    log_develop_trace(jvmcont)("Walking oops (freeze)");

    assert (!_map.include_argument_oops(), "");

    _fp_oop_info._has_fp_oop = false;

    int frozen;
    if (!FKind::interpreted && extra != NULL) { // dynamic branch
      FreezeFnT f_fn = (FreezeFnT)extra;
      frozen = freeze_compiled_oops_stub(f_fn, vsp, hsp, &_map, index);
    } else {
      if (num_oops == 0)
        return;
      frozen = FKind::interpreted ? freeze_intepreted_oops(f, vsp, hsp, index, *(InterpreterOopMap*)extra)
                                  : freeze_compiled_oops  (f, vsp, hsp, index);
    }
    assert(frozen == num_oops, "frozen: %d num_oops: %d", frozen, num_oops);
  }

  template <typename FKind, bool top, bool bottom>
  void patch(frame& f, hframe& hf, const hframe& caller) {
    assert (FKind::is_instance(f), "");
    assert (bottom || !caller.is_empty(), "");
    assert (bottom || Interpreter::contains(hf.return_pc<FKind>()) == caller.is_interpreted_frame(), "Interpreter::contains(hf.return_pc()): %d caller.is_interpreted_frame(): %d", Interpreter::contains(hf.return_pc<FKind>()), caller.is_interpreted_frame());
    assert (!bottom || !_cont.is_empty() || (_cont.fp() == 0 && _cont.pc() == NULL), "");
    assert (!bottom || _cont.is_empty() || caller == _cont.last_frame<mode_slow>(), "");
    assert (!bottom || _cont.is_empty() || Continuation::is_cont_barrier_frame(f), "");
    assert (!bottom || _cont.is_flag(FLAG_LAST_FRAME_INTERPRETED) == Interpreter::contains(_cont.pc()), "");

    if (bottom) {
      log_develop_trace(jvmcont)("Fixing return address on bottom frame: " INTPTR_FORMAT, p2i(_cont.pc()));
      hf.patch_return_pc<FKind>(_cont.pc());
    }

    patch_pd<FKind, top, bottom>(f, hf, caller);

#ifdef ASSERT
    // TODO DEOPT: long term solution: unroll on freeze and patch pc
    if (!FKind::interpreted && !FKind::stub) {
      assert (hf.cb()->is_compiled(), "");
      if (f.is_deoptimized_frame()) {
        log_develop_trace(jvmcont)("Freezing deoptimized frame");
        assert (f.cb()->as_compiled_method()->is_deopt_pc(f.raw_pc()), "");
        assert (f.cb()->as_compiled_method()->is_deopt_pc(Frame::real_pc(f)), "");
      }
    }
#endif
  }

  inline int interpreted_frame_size(frame&f, int* num_oops, InterpreterOopMap* mask) {
    int n = 0;
    n++; // for the mirror oop
    n += ((intptr_t*)f.interpreter_frame_monitor_begin() - (intptr_t*)f.interpreter_frame_monitor_end())/BasicObjectLock::size(); // all locks must be NULL when freezing, but f.oops_do walks them, so we count them

    n += mask->num_oops();
    *num_oops = n;

    return (Interpreted::frame_bottom(f) - Interpreted::frame_top(f, mask)) * wordSize;
  }

  template <bool top, bool bottom>
  hframe freeze_interpreted_frame(frame& f, hframe& caller, int fsize, int oops, int callee_argsize, InterpreterOopMap* mask) {
    intptr_t* vsp = Interpreted::frame_top(f, mask);
    assert ((Interpreted::frame_bottom(f) - vsp) * sizeof(intptr_t) == (size_t)fsize, "");

    hframe hf = new_callee_hframe<Interpreted>(f, vsp, caller, fsize, oops);
    intptr_t* hsp = _cont.stack_address(hf.sp());

    freeze_raw_frame(vsp, hsp, fsize);

    relativize_interpreted_frame_metadata(f, vsp, hf);

    freeze_oops<Interpreted>(f, vsp, hsp, hf.ref_sp(), oops, mask);
    
    patch<Interpreted, top, bottom>(f, hf, caller);

    _cont.add_size(fsize + callee_argsize);
    _cont.inc_num_frames();
    _cont.inc_num_interpreted_frames();
    _cont.e_add_refs(oops);

    return hf;
  }

  int freeze_intepreted_oops(frame& f, intptr_t* vsp, intptr_t* hsp, int starting_index, const InterpreterOopMap& mask) {
    FreezeOopFn oopFn(&_cont, &_fp_oop_info, &f, vsp, hsp, &_map, starting_index);
    f.oops_interpreted_do(&oopFn, &_map, mask);
    return oopFn.count();
  }
  
  template <typename FKind>
  inline int compiled_frame_size(frame& f, int* argsize, int* num_oops) {
    assert (f.oop_map() != NULL, "");
    *num_oops = f.oop_map()->num_oops();
    *argsize = FKind::stub ? 0 : f.cb()->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size;
    return f.cb()->frame_size() * wordSize;
  }

  template <typename FKind, bool top, bool bottom>
  hframe freeze_compiled_frame(frame& f, hframe& caller, int fsize, int argsize, int oops, int callee_argsize, FreezeFnT f_fn) {    
    assert ((callee_argsize & WordAlignmentMask) == 0, "must be");
    int callee_argsize_words = callee_argsize >> LogBytesPerWord;

    intptr_t* vsp = FKind::frame_top(f);
    intptr_t* unxetended_vsp = vsp + callee_argsize_words;

#ifdef ASSERT
    const int fsize1 = (FKind::frame_bottom(f) - vsp) * sizeof(intptr_t);
    assert (fsize1 == fsize, "fsize: %d fsize1: %d", fsize, fsize1);
#endif

    if (!FKind::stub) {
      f.cb()->as_compiled_method()->inc_on_continuation_stack();
    }

    // we need to construct the hframe with the "extended" hsp, which includes callee arguments because metadata positions (real_fp) depend on it
    hframe hf = new_callee_hframe<FKind>(f, vsp, caller, fsize + argsize, oops);
    intptr_t* extended_hsp = _cont.stack_address(hf.sp());
    intptr_t* hsp = extended_hsp + callee_argsize_words;

    freeze_raw_frame(unxetended_vsp, hsp, fsize + argsize - callee_argsize);

    if (!FKind::stub) {
      if (mode == mode_preempt && _safepoint_stub_caller) {
        _safepoint_stub_h = freeze_safepoint_stub(hf);
      }
      
      freeze_oops<Compiled>(f, vsp, extended_hsp, hf.ref_sp(), oops, (void*)f_fn);

      if (mode == mode_preempt && _safepoint_stub_caller) {
        assert (!_fp_oop_info._has_fp_oop, "must be");
        _safepoint_stub = frame();
      }
    } else { // stub frame has no oops
      _fp_oop_info._has_fp_oop = false;
    }

    patch<FKind, top, bottom>(f, hf, caller);
    assert(bottom || Interpreter::contains(hf.return_pc<FKind>()) == caller.is_interpreted_frame(), "");

    _cont.inc_num_frames();
    _cont.add_size(fsize);
    align<FKind, top, bottom>(caller);
    _cont.e_add_refs(oops);

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
      if (mode == mode_preempt && _safepoint_stub_caller) {
        assert (!_safepoint_stub.is_empty(), "");
        stub_vsp = StubF::frame_top(_safepoint_stub);
  #ifndef PRODUCT
        assert (_safepoint_stub_hsp != NULL, "");
        stub_hsp = _safepoint_stub_hsp;
  #endif
      }

      FreezeOopFn oopFn(&_cont, &_fp_oop_info, &f, vsp, hsp, &_map, starting_index, stub_vsp, stub_hsp);

      OopMapDo<FreezeOopFn, FreezeOopFn, IncludeAllValues> visitor(&oopFn, &oopFn);
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

  NOINLINE void finish(frame& f, hframe& top) {
    if (PERFTEST_LEVEL <= 15) return;

    ConfigT::OopWriterT::finish(_cont, nr_oops(), top.ref_sp());

    assert (top.sp() <= _cont.sp(), "top.sp(): %d sp: %d", top.sp(), _cont.sp());

    _cont.set_last_frame(top);

    if (log_develop_is_enabled(Trace, jvmcont)) {
      log_develop_trace(jvmcont)("top_hframe after (freeze):");
      _cont.last_frame<mode_slow>().print_on(_cont, tty);
    }

    assert (_cont.is_flag(FLAG_LAST_FRAME_INTERPRETED) == _cont.last_frame<mode_slow>().is_interpreted_frame(), 
      "flag: %d is_interpreted: %d", _cont.is_flag(FLAG_LAST_FRAME_INTERPRETED), _cont.last_frame<mode_slow>().is_interpreted_frame());
  }

  NOINLINE freeze_result recurse_stub_frame(frame& f, hframe& caller) {
    _frames++;

    assert (mode == mode_preempt, "");
    _safepoint_stub = f;

    int fsize, argsize, oops;
    prepare_freeze_frame<StubF>(f, &fsize, &argsize, &oops, 0, NULL);
    assert (fsize > 0 && oops == 0 && argsize == 0, "");

    hframe::callee_info my_info;
    frame senderf = sender<StubF>(f, &my_info); // f.sender_for_compiled_frame<ContinuationCodeBlobLookup>(&map);

    assert (senderf.unextended_sp() < _bottom_address - 2, "");
    assert (senderf.is_compiled_frame(), "");
    assert (senderf.oop_map() != NULL, "");

    // we can have stub_caller as a value template argument, but that's unnecessary
    _safepoint_stub_caller = true;
    freeze_result result = recurse_java_frame<Compiled, false>(senderf, caller, my_info, 0, NULL);
    if (result == freeze_ok) {
      finish(f, _safepoint_stub_h);
    }
    return result;
  }

  NOINLINE hframe freeze_safepoint_stub(hframe& caller) {
    log_develop_trace(jvmcont)("== FREEZING STUB FRAME:");

    assert(mode == mode_preempt, "");
    assert(!_safepoint_stub.is_empty(), "");

    int oops, argsize;
    int fsize = compiled_frame_size<StubF>(_safepoint_stub, &argsize, &oops);
    assert (argsize == 0 && oops == 0, "argsize: %d oops: %d", argsize, oops);

    hframe hf = freeze_compiled_frame<StubF, true, false>(_safepoint_stub, caller, fsize, 0, 0, 0, NULL);

#ifndef PRODUCT
    _safepoint_stub_hsp = _cont.stack_address(hf.sp());
#endif

    log_develop_trace(jvmcont)("== DONE FREEZING STUB FRAME");
    return hf;
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

  inline void freeze_raw_frame(intptr_t* vsp, intptr_t* hsp, int fsize) {
    log_develop_trace(jvmcont)("freeze_raw_frame: sp: %d", _cont.stack_index(hsp));
    _cont.copy_to_stack(vsp, hsp, fsize);
  }

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
      DEBUG_ONLY(verify(base_loc);)
      DEBUG_ONLY(verify(derived_loc);)

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

int early_return(int res, JavaThread* thread, FrameInfo* fi) {
  clear_frame_info(fi);
  thread->set_cont_yield(false);
  log_develop_trace(jvmcont)("=== end of freeze (fail %d)", res);
  return res;
}

static void invlidate_JVMTI_stack(JavaThread* thread) {
  JvmtiThreadState *jvmti_state = thread->jvmti_thread_state();
  if (jvmti_state != NULL && jvmti_state->is_interp_only_mode()) {
    jvmti_state->invalidate_cur_stack_depth();
  }
}

static void post_JVMTI_yield(JavaThread* thread, ContMirror& cont) {
  if (JvmtiExport::should_post_continuation_yield() || JvmtiExport::can_post_frame_pop()) {
    JvmtiExport::post_continuation_yield(JavaThread::current(), num_java_frames(cont));
  }

  invlidate_JVMTI_stack(thread);
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
template<op_mode mode>
int freeze0(JavaThread* thread, FrameInfo* fi) {
  //callgrind();
  PERFTEST_LEVEL = ContPerfTest;

  if (PERFTEST_LEVEL <= 10) return early_return(freeze_ok, thread, fi);

#ifdef ASSERT
  log_develop_trace(jvmcont)("~~~~~~~~~ freeze fi->sp: " INTPTR_FORMAT " fi->fp: " INTPTR_FORMAT " fi->pc: " INTPTR_FORMAT, p2i(fi->sp), p2i(fi->fp), p2i(fi->pc));
  /* set_anchor(thread, fi); */ print_frames(thread);
#endif

  assert (thread->thread_state() == _thread_in_vm || thread->thread_state() == _thread_blocked, "thread->thread_state(): %d", thread->thread_state());
  assert (!thread->cont_yield(), "");
  assert (!thread->has_pending_exception(), ""); // if (thread->has_pending_exception()) return early_return(freeze_exception, thread, fi);

  EventContinuationFreeze event;

  thread->set_cont_yield(true);
  thread->cont_frame()->sp = NULL;
  DEBUG_ONLY(thread->_continuation = NULL;)

  oop oopCont = get_continuation(thread);
  ContMirror cont(thread, oopCont);
  log_develop_debug(jvmcont)("FREEZE #" INTPTR_FORMAT " " INTPTR_FORMAT, cont.hash(), p2i((oopDesc*)oopCont));

  if (java_lang_Continuation::critical_section(oopCont) > 0) {
    log_develop_debug(jvmcont)("PINNED due to critical section");
    return early_return(freeze_pinned_cs, thread, fi);
  }

  freeze_result res = cont_freeze<mode>(thread, cont, fi);
  if (res != freeze_ok)
    return early_return(res, thread, fi);

  if (PERFTEST_LEVEL <= 15) return freeze_ok;

  cont.set_flag(FLAG_SAFEPOINT_YIELD, mode == mode_preempt);

  cont.write(); // commit the freeze

  post_JVMTI_yield(thread, cont);
  cont.post_jfr_event(&event);
  
  // set_anchor(thread, fi);
  thread->set_cont_yield(false);

  log_develop_debug(jvmcont)("ENTRY: sp: " INTPTR_FORMAT " fp: " INTPTR_FORMAT " pc: " INTPTR_FORMAT, p2i(fi->sp), p2i(fi->fp), p2i(fi->pc));
  log_develop_debug(jvmcont)("=== End of freeze cont ### #" INTPTR_FORMAT, cont.hash());

  return 0;
}

JRT_ENTRY(int, Continuation::freeze(JavaThread* thread, FrameInfo* fi))
  return freeze0<mode_slow>(thread, fi);
JRT_END

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

  while (true) {
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
    thread->set_cont_preempt(true);

    frame last = thread->last_frame();
    Frame::patch_pc(last, StubRoutines::cont_jump_from_sp()); // reinstates rbpc and rlocals for the sake of the interpreter
    log_develop_trace(jvmcont)("try_force_yield installed cont_jump_from_sp stub on"); if (log_develop_is_enabled(Trace, jvmcont)) last.print_on(tty);

    // this return barrier is used for compiled frames; for interpreted frames we use the call to StubRoutines::cont_jump_from_sp_C in JavaThread::handle_special_runtime_exit_condition
  }
  return res;
}
/////////////// THAW ////

typedef void (*ThawContFnT)(JavaThread*, ContMirror&, FrameInfo*, int);

static ThawContFnT cont_thaw_fast = NULL;
static ThawContFnT cont_thaw_slow = NULL;
static ThawContFnT cont_thaw_preempt = NULL;

template<op_mode mode>
static void cont_thaw(JavaThread* thread, ContMirror& cont, FrameInfo* fi, int num_frames) {
  switch (mode) {
    case mode_fast:    return cont_thaw_fast   (thread, cont, fi, num_frames);
    case mode_slow:    return cont_thaw_slow   (thread, cont, fi, num_frames);
    case mode_preempt: return cont_thaw_preempt(thread, cont, fi, num_frames);
  }
}

static inline int thaw_num_frames(bool return_barrier) {
  if (CONT_FULL_STACK) {
    assert (!return_barrier, "");
    return 10000;
  }
  return return_barrier ? 1 : 2;
}

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

  int num_frames = thaw_num_frames(return_barrier);

  log_develop_trace(jvmcont)("~~~~~~~~~ prepare_thaw return_barrier: %d num_frames: %d", return_barrier, num_frames);
  log_develop_trace(jvmcont)("prepare_thaw pc: " INTPTR_FORMAT " fp: " INTPTR_FORMAT " sp: " INTPTR_FORMAT, p2i(fi->pc), p2i(fi->fp), p2i(fi->sp));

  JavaThread* thread = JavaThread::current();
  oop cont = get_continuation(thread);

  // if the entry frame is interpreted, it may leave a parameter on the stack, which would be left there if the return barrier is hit
  // assert ((address)java_lang_Continuation::entrySP(cont) - bottom <= 8, "bottom: " INTPTR_FORMAT ", entrySP: " INTPTR_FORMAT, bottom, java_lang_Continuation::entrySP(cont));
  int size = java_lang_Continuation::maxSize(cont); // frames_size(cont, num_frames);
  if (size == 0) { // no more frames
    return 0;
  }
  size += sizeof(intptr_t); // just in case we have an interpreted entry after which we need to align

  const address bottom = (address)fi->sp; // os::current_stack_pointer(); points to the entry frame
  if (!stack_overflow_check(thread, size + 300, bottom)) {
    fi->pc = StubRoutines::throw_StackOverflowError_entry();
    return 0;
  }

  log_develop_trace(jvmcont)("prepare_thaw bottom: " INTPTR_FORMAT " top: " INTPTR_FORMAT " size: %d", p2i(bottom), p2i(bottom - size), size);

  if (PERFTEST_LEVEL <= 120) return 0;

  return size;
JRT_END

template <typename ConfigT, op_mode mode>
class Thaw {
private:
  JavaThread* _thread;
  ContMirror& _cont;
  FrameInfo* _fi;

  RegisterMap _map; // map is only passed to thaw_compiled_frame for use in deoptimize, which uses it only for biased locks; we may not need deoptimize there at all -- investigate

  const hframe* _safepoint_stub;
  bool _safepoint_stub_caller;
  frame _safepoint_stub_f;

  DEBUG_ONLY(int _frames;)

  inline frame new_entry_frame();
  template<typename FKind> frame new_frame(const hframe& hf, intptr_t* vsp, int callee_argsize_words);
  template<typename FKind> static inline void patch_pd(frame& f, const frame& sender);
  void derelativize_interpreted_frame_metadata(const hframe& hf, const frame& f);
  inline hframe::callee_info frame_callee_info_address(frame& f);
  template<typename FKind, bool top, bool bottom> inline intptr_t* align(const hframe& hf, intptr_t* vsp, const frame& caller);

  typedef int (*ThawFnT)(address /* dst */, address /* objArray */, address /* map */);

public: 

  Thaw(JavaThread* thread, ContMirror& mirror) :
    _thread(thread), _cont(mirror), 
    _map(thread, false, false, false),
    _safepoint_stub(NULL), _safepoint_stub_caller(false) {
    
    _map.set_include_argument_oops(false);
  }

  void thaw(FrameInfo* fi, int num_frames) {
    _fi = fi;

    assert (!_map.include_argument_oops(), "should be");

    DEBUG_ONLY(int orig_num_frames = _cont.num_frames();)
    DEBUG_ONLY(_frames = 0;)

    hframe hf = _cont.last_frame<mode>();

    log_develop_trace(jvmcont)("top_hframe before (thaw):"); if (log_develop_is_enabled(Trace, jvmcont)) hf.print_on(_cont, tty);

    frame caller;
    thaw<true>(hf, caller, num_frames, 0);

    assert (_cont.num_frames() == orig_num_frames - _frames, "cont.is_empty: %d num_frames: %d orig_num_frames: %d frame_count: %d", _cont.is_empty(), _cont.num_frames(), orig_num_frames, _frames);
  }

  template<bool top>
  void thaw(const hframe& hf, frame& caller, int num_frames, int callee_argsize) {
    assert (num_frames > 0 && !hf.is_empty(), "");

    // Dynamically branch on frame type
    if (mode == mode_preempt && top && !hf.is_interpreted_frame()) {
      assert (is_stub(hf.cb()), "");
      recurse_stub_frame(hf, caller, num_frames, callee_argsize);
    } else if (mode == mode_fast || !hf.is_interpreted_frame()) {
      recurse_java_frame<Compiled, top>(hf, caller, num_frames, callee_argsize, NULL);
    } else {
      assert (mode != mode_fast, "");
      // ResourceMark rm(_thread);
      InterpreterOopMap mask;
      recurse_java_frame<Interpreted, top>(hf, caller, num_frames, callee_argsize, &mask);
    }
  }

  template<typename FKind, bool top>
  void recurse_java_frame(const hframe& hf, frame& caller, int num_frames, int callee_argsize, InterpreterOopMap* mask) {
    assert (num_frames > 0, "");

    int fsize, argsize, num_oops;
    fsize = prepare_thaw_frame<FKind>(hf, &argsize, &num_oops, mask);
    void* extra = FKind::interpreted ? (void*) mask
                                     : (void*) get_oopmap_stub<FKind>(hf); // try to do this early, so we wouldn't need to look at the oopMap again.
    hframe hsender = hf.sender<FKind, mode>(_cont, num_oops); // TODO PERF maybe we can reuse fsize?

    bool is_empty = hsender.is_empty();
    if (num_frames == 1 || is_empty) {
      finalize(hsender, caller, is_empty);
      frame runf = caller;
      thaw_java_frame<FKind, top, true>(hf, fsize, num_oops, argsize, callee_argsize, caller, extra);
      patch_bottom<FKind>(hf, caller, runf, is_empty);
    } else {
      bool safepoint_stub_caller; // the use of _safepoint_stub_caller is not nice, but given preemption being performance non-critical, we don't want to add either a template or a regular parameter
      if (mode == mode_preempt) {
        safepoint_stub_caller = _safepoint_stub_caller;
        _safepoint_stub_caller = false;
      }

      thaw<false>(hsender, caller, num_frames - 1, 0); // recurse

      if (mode == mode_preempt) _safepoint_stub_caller = safepoint_stub_caller; // restore _stub_caller

      thaw_java_frame<FKind, top, false>(hf, fsize, num_oops, argsize, callee_argsize, caller, extra);
    }

    if (top) {
      finish(caller);
    }

    DEBUG_ONLY(_frames++;)
  }

  template<typename FKind>
  int prepare_thaw_frame(const hframe& hf, int* argsize, int* oops, InterpreterOopMap* mask) {
    if (FKind::interpreted) {
      hf.interpreted_frame_oop_map(mask);
      *argsize = 0;
      return hf.interpreted_frame_size(*mask, oops);
    } else {
      return hf.compiled_frame_size<FKind>(argsize, oops);
    }
  }

  void finalize(const hframe& hf, frame& entry, bool is_empty) {
    entry = new_entry_frame();

  #ifdef ASSERT
    log_develop_trace(jvmcont)("Found entry:");
    print_vframe(entry);
    assert_bottom_java_frame_name(entry, RUN_SIG);
  #endif

    // tty->print_cr(">>>>>>>>> thaw_frames entry");
    // hf.print_on(cont, tty);

    is_empty ? _cont.set_empty() : _cont.set_last_frame(hf); // _last_frame = hf;
  }

  template<typename FKind>
  void patch_bottom(const hframe& hf, frame& f, const frame& caller, bool is_empty) {
    assert (FKind::is_instance(hf), "");
    assert (is_entry_frame(_cont, caller), "");
    assert (_frames == 0, "");
    assert (!is_stub(f.cb()), "");

    if (!is_empty) { //  || cont.parent() != (oop)NULL
      log_develop_trace(jvmcont)("Setting return address to return barrier: " INTPTR_FORMAT, p2i(StubRoutines::cont_returnBarrier()));

      FKind::patch_return_pc(f, StubRoutines::cont_returnBarrier());
    } else {
      // This is part of the mechanism to pop stack-passed compiler arguments; see generate_cont_thaw's no_saved_sp label.
      // we use thread->_cont_frame->sp rather than the continuations themselves (which allow nesting) b/c it's faser and simpler.
      // for that to work, we rely on the fact that parent continuation's have at lesat Continuation.run on the stack, which does not require stack arguments
      _cont.thread()->cont_frame()->sp = NULL;
    }

    log_develop_trace(jvmcont)("is_empty: %d", is_empty);
    assert (!is_empty || hf.sender<mode_slow>(_cont).is_empty(), "");
    assert (!is_empty || assert_bottom_java_frame_name(f, ENTER_SIG), "");
    assert (is_empty == _cont.is_empty() /* _last_frame.is_empty()*/, "hf.is_empty(cont): %d last_frame.is_empty(): %d ", is_empty, _cont.is_empty()/*_last_frame.is_empty()*/);
    assert ((is_empty != Continuation::is_cont_barrier_frame(f)), "hf.is_empty(cont): %d is_cont_barrier_frame(f): %d ", is_empty, Continuation::is_cont_barrier_frame(f));
  }

  template<typename FKind, bool top, bool bottom>
  void thaw_java_frame(const hframe& hf, int fsize, int num_oops, int argsize, int callee_argsize, frame& caller, void* extra) {
    log_develop_trace(jvmcont)("============================= THAWING FRAME:");

    assert (FKind::is_instance(hf), "");
    assert (bottom == is_entry_frame(_cont, caller), "");
    assert (!bottom || caller.sp() == _cont.entrySP(), "caller.sp: " INTPTR_FORMAT " entrySP: " INTPTR_FORMAT, p2i(caller.sp()), p2i(_cont.entrySP()));
    assert (!bottom || caller.fp() == _cont.entryFP(), "caller.fp: " INTPTR_FORMAT " entryFP: " INTPTR_FORMAT, p2i(caller.fp()), p2i(_cont.entryFP())); // TODO PD

    if (log_develop_is_enabled(Trace, jvmcont)) hf.print(_cont);

    log_develop_trace(jvmcont)("fsize: %d argsize: %d callee_argsize: %d num_oops: %d", fsize, argsize, callee_argsize, num_oops);
    log_develop_trace(jvmcont)("stack_length: %d", _cont.stack_length());

    caller = FKind::interpreted ? thaw_interpreted_frame    <top, bottom>(hf, fsize,          num_oops, callee_argsize, caller, (InterpreterOopMap*)extra)
                                : thaw_compiled_frame<FKind, top, bottom>(hf, fsize, argsize, num_oops, callee_argsize, caller, (ThawFnT)extra);

    DEBUG_ONLY(print_vframe(caller, &dmap);)
  }

  template <typename FKind>
  void thaw_oops(frame& f, intptr_t* vsp, int oop_index, int num_oops, void* extra) {
    log_develop_trace(jvmcont)("Walking oops (thaw)");

    assert (!_map.include_argument_oops(), "");

    int thawed;
    if (!FKind::interpreted && extra != NULL) {
      thawed = thaw_compiled_oops_stub(f, (ThawFnT)extra, vsp, oop_index);
    } else {
      if (num_oops == 0)
        return;

      thawed = FKind::interpreted ? thaw_interpreted_oops(f, vsp, oop_index, (InterpreterOopMap*)extra)
                                  : thaw_compiled_oops   (f, vsp, oop_index);
    }

    log_develop_trace(jvmcont)("count: %d num_oops: %d", thawed, num_oops);
    assert(thawed == num_oops, "closure oop count different.");

    _cont.null_ref_stack(oop_index, num_oops);
    _cont.e_add_refs(num_oops);

    log_develop_trace(jvmcont)("Done walking oops");
  }

  template<typename FKind>
  static inline void patch(frame& f, const frame& caller) {
    FKind::patch_return_pc(f, caller.raw_pc()); // this patches the return address to the deopt handler if necessary
    patch_pd<FKind>(f, caller);

    if (FKind::interpreted) {
      Interpreted::patch_sender_sp(f, caller.unextended_sp()); // ContMirror::derelativize(vfp, frame::interpreter_frame_sender_sp_offset);
    }
  }

  template<bool top, bool bottom>
  frame thaw_interpreted_frame(const hframe& hf, int fsize, int num_oops, int callee_argsize, const frame& caller, InterpreterOopMap* mask) {
    assert ((callee_argsize & WordAlignmentMask) == 0, "must be");
    int callee_argsize_words = callee_argsize >> LogBytesPerWord;

    intptr_t* vsp = (intptr_t*)((address)caller.sp() - fsize);
    intptr_t* hsp = _cont.stack_address(hf.sp()) + callee_argsize_words;

    frame f = new_frame<Interpreted>(hf, vsp, callee_argsize_words);

    thaw_raw_frame(hsp, vsp, fsize);

    derelativize_interpreted_frame_metadata(hf, f);

    thaw_oops<Interpreted>(f, f.sp(), hf.ref_sp(), num_oops, mask);

    patch<Interpreted>(f, caller);

    assert(f.is_interpreted_frame_valid(_cont.thread()), "invalid thawed frame");
    assert (Interpreted::frame_bottom(f) <= Frame::frame_top(caller), "");

    _cont.sub_size(fsize + callee_argsize);
    _cont.dec_num_frames();
    _cont.dec_num_interpreted_frames();

    return f;
  }

  int thaw_interpreted_oops(frame& f, intptr_t* vsp, int starting_index, InterpreterOopMap* mask) {
    assert (mask != NULL, "");

    ThawOopFn oopFn(&_cont, &f, starting_index, vsp, &_map);
    f.oops_interpreted_do(&oopFn, &_map, mask); // f.oops_do(&oopFn, NULL, &oopFn, &_map);
    return oopFn.count();
  }

  template<typename FKind, bool top, bool bottom>
  frame thaw_compiled_frame(const hframe& hf, int fsize, int argsize, int num_oops, int callee_argsize, const frame& caller, ThawFnT t_fn) {
    assert ((callee_argsize & WordAlignmentMask) == 0, "must be");
    int callee_argsize_words = callee_argsize >> LogBytesPerWord;

    assert (FKind::stub == is_stub(hf.cb()), "");

    intptr_t* vsp = (intptr_t*)((address)caller.sp() - fsize);

    // We add arguments for the bottom most frame frozen:
    // - If we're not doing lazy copy, then it's always enter0, for which argsize is 0, so it doesn't matter.
    // - If we are doing lazy copy, then we need the arguments for the bottom-most frame
    if ((mode != mode_fast && caller.is_interpreted_frame()) || bottom) { // an interpreted caller doesn't include my args
      vsp = (intptr_t*)((address)vsp - argsize);
    }
    log_develop_trace(jvmcont)("vsp: " INTPTR_FORMAT, p2i(vsp));

    vsp = align<FKind, top, bottom>(hf, vsp, caller);

    _cont.sub_size(fsize);

    // when thawing on lazy copy, we may not have the callee_argsize (it may be 0) for a callee that returns through the return barrier.
    // this means that we copy the callee's arguments to the v-stack again, as we don't know how much of our frame is arguments, but that's OK.
    intptr_t* unextended_vsp = vsp + callee_argsize_words;
    intptr_t* hsp = _cont.stack_address(hf.sp());
    intptr_t* unextended_hsp = hsp + callee_argsize_words;

    log_develop_trace(jvmcont)("hsp: %d unextended_hsp: %d callee_argsize_words: %d", _cont.stack_index(hsp), _cont.stack_index(unextended_hsp), callee_argsize_words);

    frame f = new_frame<FKind>(hf, vsp, callee_argsize_words);

    thaw_raw_frame(unextended_hsp, unextended_vsp, fsize + argsize - callee_argsize);

    if (!FKind::stub)
      hf.cb()->as_compiled_method()->dec_on_continuation_stack();

      // TODO get nmethod. Call popNmethod if necessary
      // when copying nmethod frames, we need to check for them being made non-reentrant, in which case we need to deopt them
      // and turn them into interpreter frames.

    if (f.should_be_deoptimized() && !f.is_deoptimized_frame()) {
      log_develop_trace(jvmcont)("Deoptimizing thawed frame");
      // tty->print_cr("DDDDDDDDDDDDD");

      DEBUG_ONLY(Frame::patch_pc(f, NULL));
      Deoptimization::deoptimize(_cont.thread(), f, &_map);
    }

    if (!FKind::stub) {
      if (_safepoint_stub_caller) {
        _safepoint_stub_f = thaw_safepoint_stub(f);
      }

      thaw_oops<FKind>(f, f.sp(), hf.ref_sp(), num_oops, (void*)t_fn);
    }

    patch<FKind>(f, caller);

    _cont.dec_num_frames();

    return f;
  }

  int thaw_compiled_oops(frame& f, intptr_t* vsp, int starting_index) {
    DEBUG_ONLY(intptr_t* tmp_fp = f.fp();) // TODO PD

    // Thawing oops overwrite the link in the callee if rbp contained an oop (only possible if we're compiled).
    // This only matters when we're the top frame, as that's the value that will be restored into rbp when we jump to continue.
    ContinuationHelper::update_register_map(&_map, frame_callee_info_address(f));

    ThawOopFn oopFn(&_cont, &f, starting_index, vsp, &_map);
    OopMapDo<ThawOopFn, ThawOopFn, IncludeAllValues> visitor(&oopFn, &oopFn);
    visitor.oops_do(&f, &_map, f.oop_map());

    DEBUG_ONLY(if (tmp_fp != f.fp()) log_develop_trace(jvmcont)("WHOA link has changed (thaw) f.fp: " INTPTR_FORMAT " link: " INTPTR_FORMAT, p2i(f.fp()), p2i(tmp_fp));) // TODO PD

    return oopFn.count();
  }

  int thaw_compiled_oops_stub(frame& f, ThawFnT t_fn, intptr_t* vsp, int starting_index) {
    typename ConfigT::OopT* addr = _cont.refStack()->template obj_at_address<typename ConfigT::OopT>(starting_index);
    int cnt = t_fn((address) vsp, (address)addr, (address)frame_callee_info_address(f)); // write the link straight into the frame struct
    return cnt;
  }

  void finish(frame& f) {
    setup_jump(f);

    // _cont.set_last_frame(_last_frame);

    assert (!CONT_FULL_STACK || _cont.is_empty(), "");
    assert (_cont.is_empty() == _cont.last_frame<mode_slow>().is_empty(), "cont.is_empty: %d cont.last_frame().is_empty(): %d", _cont.is_empty(), _cont.last_frame<mode_slow>().is_empty());
    assert (_cont.is_empty() == (_cont.max_size() == 0), "cont.is_empty: %d cont.max_size: " SIZE_FORMAT, _cont.is_empty(), _cont.max_size());
    assert (_cont.is_empty() == (_cont.num_frames() == 0), "cont.is_empty: %d num_frames: %d", _cont.is_empty(), _cont.num_frames());
    assert (_cont.is_empty() <= (_cont.num_interpreted_frames() == 0), "cont.is_empty: %d num_interpreted_frames: %d", _cont.is_empty(), _cont.num_interpreted_frames());

    log_develop_trace(jvmcont)("thawed %d frames", _frames);

    log_develop_trace(jvmcont)("top_hframe after (thaw):");
    if (log_develop_is_enabled(Trace, jvmcont)) _cont.last_frame<mode_slow>().print_on(_cont, tty);
  }

  void setup_jump(const frame& f) {
    assert (!f.is_compiled_frame() || f.is_deoptimized_frame() == f.cb()->as_compiled_method()->is_deopt_pc(f.raw_pc()), "");
    assert (!f.is_compiled_frame() || f.is_deoptimized_frame() == (f.pc() != f.raw_pc()), "");

    _fi->sp = f.sp();
    _fi->pc = f.raw_pc(); // we'll jump to the current continuation pc // Interpreter::return_entry(vtos, 0, Bytecodes::_invokestatic, true); //
    ContinuationHelper::to_frame_info_pd(f, _fi);

    assert (mode == mode_preempt || !CONT_FULL_STACK || assert_top_java_frame_name(f, YIELD0_SIG), "");
  }

  void recurse_stub_frame(const hframe& hf, frame& caller, int num_frames, int callee_argsize) {
    log_develop_trace(jvmcont)("Found safepoint stub");

    assert (num_frames > 1, "");
    assert (mode == mode_preempt, "");
    assert(!hf.is_bottom<StubF>(_cont), "");

    int argsize, num_oops;
    int fsize = prepare_thaw_frame<StubF>(hf, &argsize, &num_oops, NULL);
    assert (num_oops == 0 & argsize == 0, "");

    _safepoint_stub = &hf;
    _safepoint_stub_caller = true;

    hframe hsender = hf.sender<StubF, mode>(_cont, num_oops);
    assert (!hsender.is_interpreted_frame(), "");
    recurse_java_frame<Compiled, false>(hsender, caller, num_frames - 1, argsize, NULL);

    _safepoint_stub_caller = false;

    // In the case of a safepoint stub, the above line, called on the stub's sender, actually returns the safepoint stub after thawing it.
    finish(_safepoint_stub_f);

    DEBUG_ONLY(_frames++;)
  }

  NOINLINE frame thaw_safepoint_stub(frame& caller) {
    // A safepoint stub is the only case we encounter callee-saved registers (aside from rbp). We therefore thaw that frame
    // before thawing the oops in its sender, as the oops will need to be written to that stub frame.
    log_develop_trace(jvmcont)("THAWING SAFEPOINT STUB");

    assert(mode == mode_preempt, "");
    assert (_safepoint_stub != NULL, "");

    hframe stubf = *_safepoint_stub;
    _safepoint_stub_caller = false;
    _safepoint_stub = NULL;

    int fsize, argsize, num_oops;
    fsize = stubf.compiled_frame_size<StubF>(&argsize, &num_oops);
    assert (argsize == 0 && num_oops == 0, "");
    frame f = thaw_compiled_frame<StubF, true, false>(stubf, fsize, 0, 0, 0, caller, NULL);

    f.oop_map()->update_register_map(&f, &_map);
    log_develop_trace(jvmcont)("THAWING OOPS FOR SENDER OF SAFEPOINT STUB");
    return f;
  }

  template<typename FKind>
  inline ThawFnT get_oopmap_stub(const hframe& f) {
    if (FKind::interpreted || mode != mode_preempt || !ConfigT::allow_stubs)
      return NULL;

    ThawFnT t_fn = (ThawFnT)f.oop_map()->thaw_stub();
    if ((void*)t_fn == (void*)f.oop_map()) {
      t_fn = NULL; // need CompressedOops for now ????
    }
    return t_fn;
  }

  inline void thaw_raw_frame(intptr_t* hsp, intptr_t* vsp, int fsize) {
    log_develop_trace(jvmcont)("thaw_raw_frame: sp: %d", _cont.stack_index(hsp));
    _cont.copy_from_stack(hsp, vsp, fsize);
  }

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
    ThawOopFn(ContMirror* cont, frame* fr, int index, void* vsp, RegisterMap* map)
      : ContOopBase(cont, fr, map, vsp) { _i = index; }
    void do_oop(oop* p)       { do_oop_work(p); }
    void do_oop(narrowOop* p) { do_oop_work(p); }

    void do_derived_oop(oop *base_loc, oop *derived_loc) {
      assert(Universe::heap()->is_in_or_null(*base_loc), "not an oop: " INTPTR_FORMAT " (at " INTPTR_FORMAT ")", p2i((oopDesc*)*base_loc), p2i(base_loc));
      assert(derived_loc != base_loc, "Base and derived in same location");
      DEBUG_ONLY(verify(base_loc);)
      DEBUG_ONLY(verify(derived_loc);)
      assert (oopDesc::is_oop_or_null(*base_loc), "invalid oop");

      intptr_t offset = *(intptr_t*)derived_loc;

      log_develop_trace(jvmcont)(
        "Continuation thaw derived pointer@" INTPTR_FORMAT " - Derived: " INTPTR_FORMAT " Base: " INTPTR_FORMAT " (@" INTPTR_FORMAT ") (Offset: " INTX_FORMAT ")",
        p2i(derived_loc), p2i((address)*derived_loc), p2i((address)*base_loc), p2i(base_loc), offset);

      oop obj = cast_to_oop(cast_from_oop<intptr_t>(*base_loc) + offset);
      assert(Universe::heap()->is_in_or_null(obj), "");
      *derived_loc = obj;
    }
  };
};

static void post_JVMTI_continue(JavaThread* thread, FrameInfo* fi, int java_frame_count) {
  if (JvmtiExport::should_post_continuation_run()) {
    set_anchor(thread, fi); // ensure thawed frames are visible
    JvmtiExport::post_continuation_run(JavaThread::current(), java_frame_count);
    clear_anchor(thread);
  }

  invlidate_JVMTI_stack(thread);
}

// fi->pc is the return address -- the entry
// fi->sp is the top of the stack after thaw
// fi->fp current rbp
// called after preparations (stack overflow check and making room)
static inline void thaw0(JavaThread* thread, FrameInfo* fi, const bool return_barrier) {
  // NoSafepointVerifier nsv;
  EventContinuationThaw event;

  if (return_barrier) log_develop_trace(jvmcont)("== RETURN BARRIER");
  const int num_frames = thaw_num_frames(return_barrier);

  log_develop_trace(jvmcont)("~~~~~~~~~ thaw num_frames: %d", num_frames);
  log_develop_trace(jvmcont)("sp: " INTPTR_FORMAT " fp: " INTPTR_FORMAT " pc: " INTPTR_FORMAT, p2i(fi->sp), p2i(fi->fp), p2i(fi->pc));

  oop oopCont = get_continuation(thread);
  ContMirror cont(thread, oopCont);
  log_develop_debug(jvmcont)("THAW #" INTPTR_FORMAT " " INTPTR_FORMAT, cont.hash(), p2i((oopDesc*)oopCont));

  cont.set_entrySP(fi->sp);
  cont.set_entryFP(fi->fp);
  if (!return_barrier) { // not return barrier
    cont.set_entryPC(fi->pc);
  }

#ifdef ASSERT
  set_anchor(cont); // required for assert(thread->frame_anchor()->has_last_Java_frame()) in frame::deoptimize
//   print_frames(thread);
#endif

  assert(num_frames > 0, "num_frames <= 0: %d", num_frames);
  assert(!cont.is_empty(), "no more frames");

  int java_frame_count = -1;
  if (!return_barrier && JvmtiExport::should_post_continuation_run()) {
    java_frame_count = num_java_frames(cont);
  }

  if (cont.is_flag(FLAG_SAFEPOINT_YIELD)) {
    cont_thaw<mode_preempt>(thread, cont, fi, num_frames);
  } else if (cont.num_interpreted_frames() == 0) {
    cont_thaw<mode_fast>(thread, cont, fi, num_frames);
  } else {
    cont_thaw<mode_slow>(thread, cont, fi, num_frames);
  }

  cont.write();

  log_develop_trace(jvmcont)("fi->sp: " INTPTR_FORMAT " fi->fp: " INTPTR_FORMAT " fi->pc: " INTPTR_FORMAT, p2i(fi->sp), p2i(fi->fp), p2i(fi->pc));

#ifndef PRODUCT
  // set_anchor(thread, fi);
  print_frames(thread, tty); // must be done after write(), as frame walking reads fields off the Java objects.
  clear_anchor(thread);
#endif

  if (log_develop_is_enabled(Trace, jvmcont)) {
    log_develop_trace(jvmcont)("Jumping to frame (thaw):");
    frame f = frame(fi->sp, fi->fp, fi->pc);
    print_vframe(f, NULL);
  }

  DEBUG_ONLY(thread->_continuation = oopCont;)

  if (!return_barrier) {
    post_JVMTI_continue(thread, fi, java_frame_count);
  }
  cont.post_jfr_event(&event);

  log_develop_debug(jvmcont)("=== End of thaw #" INTPTR_FORMAT, cont.hash());
}

// IN:  fi->sp = the future SP of the topmost thawed frame (where we'll copy the thawed frames)
// Out: fi->sp = the SP of the topmost thawed frame -- the one we will resume at
//      fi->fp = the FP " ...
//      fi->pc = the PC " ...
// JRT_ENTRY(void, Continuation::thaw(JavaThread* thread, FrameInfo* fi, int num_frames))
JRT_LEAF(address, Continuation::thaw_leaf(FrameInfo* fi, bool return_barrier, bool exception))
  //callgrind();
  PERFTEST_LEVEL = ContPerfTest;

  thaw0(JavaThread::current(), fi, return_barrier);

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

  thaw0(JavaThread::current(), fi, return_barrier);

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
  Method* m = (map->in_cont() && f.is_interpreted_frame()) ? Continuation::interpreter_frame_method(f, map)
                                                           : Frame::frame_method(f);
  if (m == NULL)
    return false;

  // we can do this because the entry frame is never inlined
  return m->intrinsic_id() == vmIntrinsics::_Continuation_enter;
}

// When walking the virtual stack, this method returns true
// iff the frame is a thawed continuation frame whose
// caller is still frozen on the h-stack.
// The continuation object can be extracted from the thread.
bool Continuation::is_cont_barrier_frame(const frame& f) {
  return is_return_barrier_entry(f.is_interpreted_frame() ? Interpreted::return_pc(f) : Compiled::return_pc(f));
  // return is_return_barrier_entry(CHOOSE1(f.is_interpreted_frame(), return_pc, f));
}

bool Continuation::is_return_barrier_entry(const address pc) { 
  return pc == StubRoutines::cont_returnBarrier(); 
}

static inline bool is_sp_in_continuation(intptr_t* const sp, oop cont) {
  // tty->print_cr(">>>> is_sp_in_continuation cont: %p sp: %p entry: %p in: %d", (oopDesc*)cont, sp, java_lang_Continuation::entrySP(cont), java_lang_Continuation::entrySP(cont) >= sp);
  return java_lang_Continuation::entrySP(cont) > sp;
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

void Continuation::fix_continuation_bottom_sender(JavaThread* thread, const frame& callee, address* sender_pc, intptr_t** sender_sp) {
  // tty->print_cr(">>> fix_continuation_bottom_sender: %p", *sender_pc);
  if (thread != NULL && is_return_barrier_entry(*sender_pc)) {
    *sender_pc = get_entry_pc_past_barrier(thread, callee);
    if (callee.is_compiled_frame()) {
      // The callee's stack arguments (part of the caller frame) are also thawed to the stack when using lazy-copy
      int argsize = callee.cb()->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size;
      assert ((argsize & WordAlignmentMask) == 0, "must be");
      argsize >>= LogBytesPerWord;
      if (argsize % 2 != 0)
        argsize++; // 16-byte alignment for compiled frame sp

      *sender_sp += argsize;
    }
    // tty->print_cr(">>> fix_continuation_bottom_sender 2: %p", *sender_pc);
  }
}

frame Continuation::fix_continuation_bottom_sender(const frame& callee, RegisterMap* map, frame f) {
  if (!is_return_barrier_entry(f.pc())) {
    return f;
  }

  if (map->walk_cont()) {
    return top_frame(callee, map);
  }

  if (map->thread() != NULL) {
    address   sender_pc = f.pc();
    intptr_t* sender_sp = f.sp();
    fix_continuation_bottom_sender(map->thread(), callee, &sender_pc, &sender_sp);
    return ContinuationHelper::frame_with(f, sender_sp, sender_pc);
  }

  return f;
}

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

// TODO: delete? consider other is_scope_bottom or something
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

//   return sender.is_empty();
// }

static frame continuation_top_frame(oop contOop, RegisterMap* map) {
  ContMirror cont(NULL, contOop);

  hframe hf = cont.last_frame<mode_slow>();
  assert (!hf.is_empty(), "");

  // tty->print_cr(">>>> continuation_top_frame");
  // hf.print_on(cont, tty);
  
  // if (!oopDesc::equals(map->cont(), contOop))
  map->set_cont(contOop);
  map->set_in_cont(true);

  if (map->update_map() && !hf.is_interpreted_frame()) { // TODO : what about forced preemption? see `if (callee_safepoint_stub != NULL)` in thaw_java_frame
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
  
  oop parent = java_lang_Continuation::parent(cont.mirror());
  map->set_cont(parent);
  map->set_in_cont(false); // TODO parent != (oop)NULL; consider getting rid of set_in_cont altogether

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
  oop cont = find_continuation_for_frame(map->thread(), callee.sp());
  log_develop_trace(jvmcont)("Continuation::top_frame: setting map->last_vstack_fp: " INTPTR_FORMAT, p2i(callee.real_fp()));

  ContinuationHelper::set_last_vstack_frame(map, callee);
  return continuation_top_frame(cont, map);
}

static frame sender_for_frame(const frame& f, RegisterMap* map) {
  ContMirror cont(map);
  hframe hf = cont.from_frame(f);
  hframe sender = hf.sender<mode_slow>(cont);

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
    hframe stub = cont.last_frame<mode_slow>();

    assert (cont.is_flag(FLAG_SAFEPOINT_YIELD), "must be");
    assert (is_stub(stub.cb()), "must be");
    assert (stub.sender<mode_slow>(cont) == hf, "must be");

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

  // assert (!is_continuation_entry_frame(fr, map), "");
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
oop ContMirror::raw_allocate(Klass* klass, size_t size_in_words, size_t elements, bool zero) {
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

  log_develop_trace(jvmcont)("allocate_stack_array elements: %lu", elements);
  TypeArrayKlass* klass = TypeArrayKlass::cast(Universe::intArrayKlassObj());
  size_t size_in_words = typeArrayOopDesc::object_size(klass, elements);
  return typeArrayOop(raw_allocate(klass, size_in_words, elements, false));
}

objArrayOop ContMirror::allocate_refstack_array(size_t nr_oops) {
  assert(nr_oops > 0, "");

  log_develop_trace(jvmcont)("allocate_refstack_array nr_oops: %lu", nr_oops);
  ArrayKlass* klass = ArrayKlass::cast(Universe::objectArrayKlassObj());
  size_t size_in_words = objArrayOopDesc::object_size(nr_oops);
  return objArrayOop(raw_allocate(klass, size_in_words, nr_oops, true)); // TODO PERF: consider no zero.
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
  assert(_fp == java_lang_Continuation::fp(_cont), "_fp: %lu this.fp: " JLONG_FORMAT " %d %d", _fp, java_lang_Continuation::fp(_cont), Interpreter::contains(_pc), is_flag(FLAG_LAST_FRAME_INTERPRETED));
  
  assert (oopDesc::equals(_stack, java_lang_Continuation::stack(_cont)), "");
  assert (_stack->base(T_INT) == _hstack, "");

  assert (to_bytes(_stack_length) >= size, "sanity check: stack_size: %d size: %d", to_bytes(_stack_length), size);
  assert (to_bytes(_sp) >= size, "sanity check");
  assert (to_bytes(_ref_sp) >= oops, "oops: %d ref_sp: %d refStack length: %d", oops, _ref_sp, _ref_stack->length());
}

void ContMirror::allocate_stacks_in_java(int size, int oops, int frames) {
  guarantee (false, "unreachable");
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
  int offset = _ref_sp > 0 ? _ref_sp : old_length;
  int min_length = (old_length - offset) + nr_oops;

  int new_length = ensure_capacity(old_length, min_length);
  if (new_length == -1) {
    return false;
  }
  objArrayOop new_ref_stack = allocate_refstack_array(new_length);

  if (new_ref_stack == NULL) {
    return false;
  }

  log_develop_trace(jvmcont)("grow_ref_stack old_length: %d new_length: %d", old_length, new_length);

  int n = old_length - offset;
  if (n > 0) { // TODO PERF R: fix below and zero here instead of on init
    assert(UseNewCode, "");
    for (int i=0, old_i = offset, new_i = fix_decreasing_index(offset, old_length, new_length); i<n; i++, old_i++, new_i++) 
      new_ref_stack->obj_at_put(new_i, _ref_stack->obj_at(old_i));
    // The following fails on Skynet with UseCompressedOops + tiered compilation + UseNewCode (it succeeds with any one of these options being turned off)
    // ArrayAccess<ARRAYCOPY_DISJOINT>::oop_arraycopy(_ref_stack, _ref_sp * heapOopSize, new_ref_stack, (new_length - n) * heapOopSize, n * heapOopSize);
  }

  _ref_stack = new_ref_stack;

  log_develop_trace(jvmcont)("grow_ref_stack old ref_sp: %d", _ref_sp);
  _ref_sp = fix_decreasing_index(_ref_sp, old_length, new_length);
  log_develop_trace(jvmcont)("grow_ref_stack new ref_sp: %d", _ref_sp);
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

void ContMirror::print_hframes(outputStream* st) {
  if (st != NULL && !log_develop_is_enabled(Trace, jvmcont)) return;
  if (st == NULL) st = tty;

  st->print_cr("------- hframes ---------");
  st->print_cr("sp: %d length: %d", _sp, _stack_length);
  int i = 0;
  for (hframe f = last_frame<mode_slow>(); !f.is_empty(); f = f.sender<mode_slow>(*this)) {
    st->print_cr("frame: %d", i);
    f.print_on(*this, st);
    i++;
  }
  st->print_cr("======= end hframes =========");
}

#ifdef ASSERT

static long java_tid(JavaThread* thread) {
  return java_lang_Thread::thread_id(thread->threadObj());
}

static void print_frames(JavaThread* thread, outputStream* st) {
  if (st != NULL && !log_develop_is_enabled(Trace, jvmcont)) return;
  if (st == NULL) st = tty;

  st->print_cr("------- frames ---------");
  RegisterMap map(thread, true, false);
#ifndef PRODUCT
  map.set_skip_missing(true);
  ResetNoHandleMark rnhm;
  ResourceMark rm(thread);
  HandleMark hm(thread);
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

static char* top_java_frame_name(const frame& f) {
  return method_name(top_java_frame_method(f));
}

static char* bottom_java_frame_name(const frame& f) {
  return method_name(bottom_java_frame_method(f));
}

static bool assert_top_java_frame_name(const frame& f, const char* name) {
  ResourceMark rm;
  bool res = (strcmp(top_java_frame_name(f), name) == 0);
  assert (res, "name: %s", top_java_frame_name(f));
  return res;
}

static bool assert_bottom_java_frame_name(const frame& f, const char* name) {
  ResourceMark rm;
  bool res = (strcmp(bottom_java_frame_name(f), name) == 0);
  assert (res, "name: %s", bottom_java_frame_name(f));
  return res;
}

static inline bool is_deopt_return(address pc, const frame& sender) {
  if (sender.is_interpreted_frame()) return false;

  CompiledMethod* cm = sender.cb()->as_compiled_method();
  return cm->is_deopt_pc(pc);
}

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

      // tty->print_cr(">>> ForceYieldClosure thread");
      // thread->print_on(tty);
      // if (thread != Thread::current()) {
      //   tty->print_cr(">>> current thread");
      //   Thread::current()->print_on(tty);
      // }
      
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

template <bool compressed_oops, bool post_barrier, bool gen_stubs>
class Config {
public:
  typedef Config<compressed_oops, post_barrier, gen_stubs> SelfT;
  typedef typename Conditional<compressed_oops, narrowOop, oop>::type OopT;
  typedef typename Conditional<post_barrier, RawOopWriter<SelfT>, NormalOopWriter<SelfT> >::type OopWriterT;

  static const bool _compressed_oops = compressed_oops;
  static const bool allow_stubs = gen_stubs && compressed_oops && post_barrier;

  template<op_mode mode>
  static freeze_result freeze(JavaThread* thread, ContMirror& cont, FrameInfo* fi) {
    return Freeze<SelfT, mode>(thread, cont).freeze(fi);
  }

  template<op_mode mode>
  static void thaw(JavaThread* thread, ContMirror& cont, FrameInfo* fi, int num_frames) {
    Thaw<SelfT, mode>(thread, cont).thaw(fi, num_frames);
  }
};

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
    cont_freeze_fast    = Config<use_compressed, is_modref, gen_code>::template freeze<mode_fast>;
    cont_freeze_slow    = Config<use_compressed, is_modref, gen_code>::template freeze<mode_slow>;
    cont_freeze_preempt = Config<use_compressed, is_modref, gen_code>::template freeze<mode_preempt>;

    cont_thaw_fast    = Config<use_compressed, is_modref, gen_code>::template thaw<mode_fast>;
    cont_thaw_slow    = Config<use_compressed, is_modref, gen_code>::template thaw<mode_slow>;
    cont_thaw_preempt = Config<use_compressed, is_modref, gen_code>::template thaw<mode_preempt>;
  }
};

void Continuations::init() {
  ConfigResolve::resolve();
}

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