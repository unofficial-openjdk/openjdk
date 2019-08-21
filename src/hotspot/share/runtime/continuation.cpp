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
#include "gc/shared/oopStorage.hpp"
#include "gc/shared/threadLocalAllocBuffer.inline.hpp"
#include "interpreter/interpreter.hpp"
#include "interpreter/linkResolver.hpp"
#include "interpreter/oopMapCache.hpp"
#include "logging/log.hpp"
#include "logging/logStream.hpp"
#include "metaprogramming/conditional.hpp"
#include "oops/access.inline.hpp"
#include "oops/objArrayOop.inline.hpp"
#include "oops/weakHandle.hpp"
#include "oops/weakHandle.inline.hpp"
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
#include "utilities/debug.hpp"
#include "utilities/exceptions.hpp"
#include "utilities/macros.hpp"

// #define PERFTEST 1

#ifdef PERFTEST
#define PERFTEST_ONLY(code) code
#else 
#define PERFTEST_ONLY(code)
#endif // ASSERT

#ifdef __has_include
#  if __has_include(<valgrind/callgrind.h>)
#    include <valgrind/callgrind.h>
#  endif
#endif

#ifdef CALLGRIND_START_INSTRUMENTATION
  static int callgrind_counter = 1;
  // static void callgrind() {
  //   if (callgrind_counter != 0) {
  //     if (callgrind_counter > 20000) {
  //       tty->print_cr("Starting callgrind instrumentation");
  //       CALLGRIND_START_INSTRUMENTATION;
  //       callgrind_counter = 0;
  //     } else
  //       callgrind_counter++;
  //   }	
  // }	
#else	
  // static void callgrind() {}
#endif

// #undef log_develop_info
// #undef log_develop_debug
// #undef log_develop_trace
// #undef log_develop_is_enabled
// #define log_develop_info(...)  (!log_is_enabled(Info, __VA_ARGS__))   ? (void)0 : LogImpl<LOG_TAGS(__VA_ARGS__)>::write<LogLevel::Info>
// #define log_develop_debug(...) (!log_is_enabled(Debug, __VA_ARGS__)) ? (void)0 : LogImpl<LOG_TAGS(__VA_ARGS__)>::write<LogLevel::Debug>
// #define log_develop_trace(...) (!log_is_enabled(Trace, __VA_ARGS__))  ? (void)0 : LogImpl<LOG_TAGS(__VA_ARGS__)>::write<LogLevel::Trace>
// #define log_develop_is_enabled(level, ...)  log_is_enabled(level, __VA_ARGS__)

// #undef ASSERT
// #undef assert
// #define assert(p, ...)

int Continuations::_flags = 0;

OopStorage* Continuation::_weak_handles = NULL;

PERFTEST_ONLY(static int PERFTEST_LEVEL = ContPerfTest;)
// Freeze:
// 5 - no call into C
// 10 - immediate return from C
// 15 - return after count_frames
// 20 - all work, but no copying
// 25 - copy to stack
// 30 - freeze oops
// <100 - don't allocate
// 100 - everything
//
// Thaw:
// 105 - no call into C (prepare_thaw)
// 110 - immediate return from C (prepare_thaw)
// 112 - no call to thaw0
// 115 - return after traversing frames
// 120
// 125 - copy from stack
// 130 - thaw oops


// TODO
//
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
//
// For non-temporal load/store in clang (__builtin_nontemporal_load/store) see: https://clang.llvm.org/docs/LanguageExtensions.html

#define YIELD_SIG  "java.lang.Continuation.yield(Ljava/lang/ContinuationScope;)V"
#define YIELD0_SIG  "java.lang.Continuation.yield0(Ljava/lang/ContinuationScope;Ljava/lang/Continuation;)Z"
#define ENTER_SIG  "java.lang.Continuation.enter()V"
#define RUN_SIG    "java.lang.Continuation.run()V"

static bool is_stub(CodeBlob* cb);
template<bool indirect>
static void set_anchor(JavaThread* thread, const FrameInfo* fi);
// static void set_anchor(JavaThread* thread, const frame& f); -- unused

// debugging functions
static void print_oop(void *p, oop obj, outputStream* st = tty);
static void print_vframe(frame f, const RegisterMap* map = NULL, outputStream* st = tty);

#ifdef ASSERT
  static void print_frames(JavaThread* thread, outputStream* st = tty);
  static jlong java_tid(JavaThread* thread);
  static void print_blob(outputStream* st, address addr);
  // void static stop();
  // void static stop(const frame& f);
  // static void print_JavaThread_offsets();
  // static void trace_codeblob_maps(const frame *fr, const RegisterMap *reg_map);

  static RegisterMap dmap(NULL, false); // global dummy RegisterMap
#endif

#define ELEMS_PER_WORD (wordSize/sizeof(jint))
// Primitive hstack is int[]
typedef jint ElemType;
const BasicType basicElementType   = T_INT;
const int       elementSizeInBytes = T_INT_aelem_bytes;
const int       LogBytesPerElement = LogBytesPerInt;
const int       elemsPerWord       = wordSize/elementSizeInBytes;
const int       LogElemsPerWord    = 1;

STATIC_ASSERT(elemsPerWord >= 1);
STATIC_ASSERT(elementSizeInBytes == sizeof(ElemType));
STATIC_ASSERT(elementSizeInBytes == (1 << LogBytesPerElement));
STATIC_ASSERT(elementSizeInBytes <<  LogElemsPerWord == wordSize);

// #define CHOOSE1(interp, f, ...) ((interp) ? Interpreted::f(__VA_ARGS__) : NonInterpretedUnknown::f(__VA_ARGS__))
#define CHOOSE2(interp, f, ...) ((interp) ? f<Interpreted>(__VA_ARGS__) : f<NonInterpretedUnknown>(__VA_ARGS__))

static const unsigned char FLAG_LAST_FRAME_INTERPRETED = 1;
static const unsigned char FLAG_SAFEPOINT_YIELD = 1 << 1;

static const int SP_WIGGLE = 3; // depends on the extra space between interpreted and compiled we add in Thaw::align

void continuations_init() {
  Continuations::init();
}

class SmallRegisterMap;
class ContMirror;
class hframe;

template <typename ConfigT>
class CompiledMethodKeepalive;

#ifdef CONT_DOUBLE_NOP
class CachedCompiledMetadata;
#endif

// TODO R remove
template<typename FKind> static intptr_t** slow_link_address(const frame& f);

class Frame {
public:
  template<typename RegisterMapT> static inline intptr_t** map_link_address(const RegisterMapT* map);
  static inline intptr_t** callee_link_address(const frame& f);
  static inline Method* frame_method(const frame& f);
  static inline address real_pc(const frame& f);
  static inline void patch_pc(const frame& f, address pc);
  static address* return_pc_address(const frame& f);
  static address return_pc(const frame& f);

  DEBUG_ONLY(static inline intptr_t* frame_top(const frame &f);)
};

template<typename Self>
class FrameCommon : public Frame {
public:
  static inline Method* frame_method(const frame& f);

  template <typename FrameT> static bool is_instance(const FrameT& f);
};

class Interpreted : public FrameCommon<Interpreted> {
public:
  DEBUG_ONLY(static const char* name;)
  static const bool interpreted = true;
  static const bool stub = false;
  static const int extra_oops = 0;
  static const char type = 'i';

public:

  static inline intptr_t* frame_top(const frame& f, InterpreterOopMap* mask);
  static inline intptr_t* frame_bottom(const frame& f);

  static inline address* return_pc_address(const frame& f);
  static inline address return_pc(const frame& f);
  static void patch_return_pc(frame& f, address pc);
  static void patch_sender_sp(frame& f, intptr_t* sp);

  static void oop_map(const frame& f, InterpreterOopMap* mask);
  static int num_oops(const frame&f, InterpreterOopMap* mask);
  static int size(const frame&f, InterpreterOopMap* mask);
  static inline int expression_stack_size(const frame &f, InterpreterOopMap* mask);
  static bool is_owning_locks(const frame& f);

  typedef InterpreterOopMap* ExtraT;
};

DEBUG_ONLY(const char* Interpreted::name = "Interpreted";)

template<typename Self>
class NonInterpreted : public FrameCommon<Self>  {
public:
  static inline intptr_t* frame_top(const frame& f);
  static inline intptr_t* frame_bottom(const frame& f);

  template <typename FrameT> static inline int size(const FrameT& f);
  template <typename FrameT> static inline int stack_argsize(const FrameT& f);
  static inline int num_oops(const frame& f);
 
  template <typename RegisterMapT>
  static bool is_owning_locks(JavaThread* thread, const RegisterMapT* map, const frame& f);
};

class NonInterpretedUnknown : public NonInterpreted<NonInterpretedUnknown>  {
public:
  DEBUG_ONLY(static const char* name;)
  static const bool interpreted = false;

  template <typename FrameT> static bool is_instance(const FrameT& f);
};

DEBUG_ONLY(const char* NonInterpretedUnknown::name = "NonInterpretedUnknown";)

struct FpOopInfo;
typedef int (*FreezeFnT)(address, address, address, address, int, FpOopInfo*);

class Compiled : public NonInterpreted<Compiled>  {
public:
  DEBUG_ONLY(static const char* name;)
  static const bool interpreted = false;
  static const bool stub = false;
  static const int extra_oops = 1;
  static const char type = 'c';

  typedef FreezeFnT ExtraT;
};

DEBUG_ONLY(const char* Compiled::name = "Compiled";)

class StubF : public NonInterpreted<StubF> {
public:
  DEBUG_ONLY(static const char* name;)
  static const bool interpreted = false;
  static const bool stub = true;
  static const int extra_oops = 0;
  static const char type = 's';
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

// We do not maintain an sp and an unexetended sp. Instead, sp represents frame's unextended_sp, and various patching of interpreted frames is especially handled.
template<typename SelfPD>
class HFrameBase {
protected:
  int     _sp;
  int     _ref_sp;
  address _pc;

  bool _is_interpreted;
  mutable void* _cb_imd; // stores CodeBlob in compiled frames and interpreted frame metadata for interpretedd frames
  mutable const ImmutableOopMap* _oop_map; // oop map, for compiled/stubs frames only

  friend class ContMirror;
private:
  const SelfPD& self() const { return static_cast<const SelfPD&>(*this); }
  SelfPD& self() { return static_cast<SelfPD&>(*this); }

  const ImmutableOopMap* get_oop_map() const { return self().get_oop_map(); };

  void set_codeblob(address pc) {
    if (_cb_imd == NULL && !_is_interpreted) {// compute lazily
      _cb_imd = ContinuationCodeBlobLookup::find_blob(_pc);
      assert(_cb_imd != NULL, "must be valid");
    }
  }

protected:
  HFrameBase() : _sp(-1), _ref_sp(-1), _pc(NULL), _is_interpreted(true), _cb_imd(NULL), _oop_map(NULL) {}

  HFrameBase(const HFrameBase& hf) : _sp(hf._sp),_ref_sp(hf._ref_sp), _pc(hf._pc),
                                     _is_interpreted(hf._is_interpreted), _cb_imd(hf._cb_imd), _oop_map(hf._oop_map) {}

  HFrameBase(int sp, int ref_sp, address pc, void* cb_md, bool is_interpreted)
    : _sp(sp), _ref_sp(ref_sp), _pc(pc),
      _is_interpreted(is_interpreted), _cb_imd((intptr_t*)cb_md), _oop_map(NULL) {}

  HFrameBase(int sp, int ref_sp, address pc, const ContMirror& cont)
    : _sp(sp), _ref_sp(ref_sp), _pc(pc),
      _is_interpreted(Interpreter::contains(pc)), _cb_imd(NULL), _oop_map(NULL) {
      set_codeblob(_pc);
    }

  static address deopt_original_pc(const ContMirror& cont, address pc, CodeBlob* cb, int sp);

public:
  inline bool operator==(const HFrameBase& other) const;
  bool is_empty() const { return _pc == NULL; }

  inline int       sp()     const { return _sp; }
  inline address   pc()     const { return _pc; }
  inline int       ref_sp() const { return _ref_sp; }

  inline void set_sp(int sp) { _sp = sp; }

  inline CodeBlob* cb()     const { assert (!Interpreter::contains(pc()), ""); return (CodeBlob*)_cb_imd; }
  void set_cb(CodeBlob* cb) {
    assert (!_is_interpreted, "");
    if (_cb_imd == NULL) _cb_imd = cb;
    assert (cb == slow_get_cb(*this), "");
    assert (_cb_imd == cb, "");
    assert (((CodeBlob*)_cb_imd)->contains(_pc), "");
  }
  inline bool is_interpreted_frame() const { return _is_interpreted; } // due to partial copy below, this may lie in mode_fast

  template<op_mode mode>
  void copy_partial(const SelfPD& other) {
    _sp = other._sp;
    _ref_sp = other._ref_sp;
    _pc = other._pc;
    if (mode != mode_fast) {
      _is_interpreted = other._is_interpreted;
    }
    self().copy_partial_pd(other);
  }

  inline void set_pc(address pc) { _pc = pc; }
  inline void set_ref_sp(int ref_sp) { _ref_sp = ref_sp; }

  template<typename FKind> address return_pc() const { return *self().template return_pc_address<FKind>(); }

  const CodeBlob* get_cb() const { return self().get_cb(); }

  const ImmutableOopMap* oop_map() const {
    if (_oop_map == NULL) {
      _oop_map = get_oop_map();
    }
    return _oop_map;
  }

  template<typename FKind> int frame_top_index() const;
  template<typename FKind> int frame_bottom_index() const { return self().template frame_bottom_index<FKind>(); };

  address real_pc(const ContMirror& cont) const;
  void patch_pc(address pc, const ContMirror& cont) const;
  template<typename FKind> inline void patch_return_pc(address value); // only for interpreted frames
  
  int compiled_frame_size() const;
  int compiled_frame_num_oops() const;
  int compiled_frame_stack_argsize() const;

  DEBUG_ONLY(int interpreted_frame_top_index() const { return self().interpreted_frame_top_index(); } )
  int interpreted_frame_num_monitors() const         { return self().interpreted_frame_num_monitors(); }
  int interpreted_frame_num_oops(const InterpreterOopMap& mask) const;
  int interpreted_frame_size() const;
  void interpreted_frame_oop_map(InterpreterOopMap* mask) const { self().interpreted_frame_oop_map(mask); }

  template<typename FKind, op_mode mode> SelfPD sender(const ContMirror& cont, int num_oops) const {
    assert (mode != mode_fast || !FKind::interpreted, "");
    return self().template sender<FKind, mode>(cont, num_oops);
  }
  template<typename FKind, op_mode mode> SelfPD sender(const ContMirror& cont, const InterpreterOopMap* mask, int extra_oops = 0) const;
  template<op_mode mode /* = mode_slow*/> SelfPD sender(const ContMirror& cont) const;

  template<typename FKind> bool is_bottom(const ContMirror& cont) const;

  address interpreter_frame_bcp() const                             { return self().interpreter_frame_bcp(); }
  intptr_t* interpreter_frame_local_at(int index) const             { return self().interpreter_frame_local_at(index); }
  intptr_t* interpreter_frame_expression_stack_at(int offset) const { return self().interpreter_frame_expression_stack_at(offset); }

  template<typename FKind> Method* method() const;

  inline frame to_frame(ContMirror& cont) const;

  void print_on(const ContMirror& cont, outputStream* st) const { self().print_on(cont, st); }
  void print_on(outputStream* st) const { self().print_on(st); };
  void print(const ContMirror& cont) const { print_on(cont, tty); }
  void print() const { print_on(tty); }
};

// defines hframe
#include CPU_HEADER(hframe)

template<typename Self> 
template <typename FrameT> 
bool FrameCommon<Self>::is_instance(const FrameT& f) { 
  return (Self::interpreted == f.is_interpreted_frame()) && (Self::stub == (!Self::interpreted && is_stub(slow_get_cb(f))));
}

template <typename FrameT> 
bool NonInterpretedUnknown::is_instance(const FrameT& f) {
  return (interpreted == f.is_interpreted_frame()); 
}

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
  ElemType* _hstack;

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
  ElemType* stack() const { return _hstack; }

  template <typename ConfigT> bool allocate_stacks_in_native(int size, int oops, bool needs_stack, bool needs_refstack);
  void allocate_stacks_in_java(int size, int oops, int frames);
  static int fix_decreasing_index(int index, int old_length, int new_length);
  inline void post_safepoint(Handle conth);
  int ensure_capacity(int old, int min);
  bool allocate_stack(int size);
  typeArrayOop allocate_stack_array(size_t elements);
  bool grow_stack(int new_size);
  static void copy_primitive_array(typeArrayOop old_array, int old_start, typeArrayOop new_array, int new_start, int count);
  template <typename ConfigT> bool allocate_ref_stack(int nr_oops);
  template <typename ConfigT> objArrayOop  allocate_refstack_array(size_t nr_oops);
  template <typename ConfigT> objArrayOop  allocate_keepalive_array(size_t nr_oops);
  template <typename ConfigT> bool grow_ref_stack(int nr_oops);
  template <typename ConfigT> void copy_ref_array(objArrayOop old_array, int old_start, objArrayOop new_array, int new_start, int count);
  template <typename ConfigT> void zero_ref_array(objArrayOop new_array, int new_length, int min_length);
  oop raw_allocate(Klass* klass, size_t words, size_t elements, bool zero);

public:
  // TODO R: get rid of these:
  static inline int to_index(int x) { return x >> LogBytesPerElement; }
  static inline int to_bytes(int x)    { return x << LogBytesPerElement; }
  static inline int to_index(const void* base, const void* ptr) { return to_index((const char*)ptr - (const char*)base); }

private:
  ContMirror(const ContMirror& cont); // no copy constructor

  void read();

public:
  ContMirror(JavaThread* thread, oop cont);
  ContMirror(const RegisterMap* map);

  intptr_t hash() { 
    #ifndef PRODUCT
      return Thread::current()->is_Java_thread() ? _cont->identity_hash() : -1;
    #else
      return 0;
    #endif
  }

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
  void set_entryPC(address pc)   { _entryPC = pc; log_develop_trace(jvmcont)("set_entryPC " INTPTR_FORMAT, p2i(pc)); }

  int sp() const           { return _sp; }
  intptr_t fp() const      { return _fp; }
  address pc() const       { return _pc; }

  void set_sp(int sp)      { _sp = sp; }
  void set_fp(intptr_t fp) { _fp = fp; }
  void clear_pc()  { _pc = NULL; set_flag(FLAG_LAST_FRAME_INTERPRETED, false); }
  void set_pc(address pc, bool interpreted)  { _pc = pc; set_flag(FLAG_LAST_FRAME_INTERPRETED, interpreted); 
                                               assert (interpreted == Interpreter::contains(pc), ""); }

  bool is_flag(unsigned char flag) { return (_flags & flag) != 0; }
  void set_flag(unsigned char flag, bool v) { _flags = (v ? _flags |= flag : _flags &= ~flag); }

  int stack_length() const { return _stack_length; }

  JavaThread* thread() const { return _thread; }

  template <typename ConfigT> inline void allocate_stacks(int size, int oops, int frames);

  template <typename ConfigT>
  void make_keepalive(CompiledMethodKeepalive<ConfigT>* keepalive);

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

  bool is_empty();

  template<op_mode mode> const hframe last_frame();
  template<op_mode mode> void set_last_frame(const hframe& f);
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
  inline void add_num_frames(int n) { _num_frames += n; _e_num_frames += n; }
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
address HFrameBase<SelfPD>::real_pc(const ContMirror& cont) const {
  address* pc_addr = cont.stack_address(self().pc_index());
  return *pc_addr;
}

template<typename SelfPD>
template<typename FKind>
inline void HFrameBase<SelfPD>::patch_return_pc(address value) {
  *(self().template return_pc_address<FKind>()) = value;
}

template<typename SelfPD>
void HFrameBase<SelfPD>::patch_pc(address pc, const ContMirror& cont) const {
  address* pc_addr = (address*)cont.stack_address(self().pc_index());
  // tty->print_cr(">>>> patching %p with %p", pc_addr, pc);
  *pc_addr = pc;
}

template<typename SelfPD>
template<typename FKind>
bool HFrameBase<SelfPD>::is_bottom(const ContMirror& cont) const {
  return frame_bottom_index<FKind>()
    + ((FKind::interpreted || FKind::stub) ? 0 : cb()->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size / elementSizeInBytes)
    >= cont.stack_length();
}

template<typename SelfPD>
int HFrameBase<SelfPD>::interpreted_frame_num_oops(const InterpreterOopMap& mask) const {
  assert (_is_interpreted, "");
  // we calculate on relativized metadata; all monitors must be NULL on hstack, but as f.oops_do walks them, we count them
  return   mask.num_oops()
         + 1 // for the mirror
         + interpreted_frame_num_monitors();
}

template<typename SelfPD>
int HFrameBase<SelfPD>::interpreted_frame_size() const {
  assert (_is_interpreted, "");
  return (frame_bottom_index<Interpreted>() - frame_top_index<Interpreted>()) * elementSizeInBytes;
}

template<typename SelfPD>
inline int HFrameBase<SelfPD>::compiled_frame_num_oops() const {
  assert (!_is_interpreted, "");
  return oop_map()->num_oops();
}

template<typename SelfPD>
int HFrameBase<SelfPD>::compiled_frame_size() const {
  return NonInterpretedUnknown::size(*this);
}

template<typename SelfPD>
int HFrameBase<SelfPD>::compiled_frame_stack_argsize() const {
  return NonInterpretedUnknown::stack_argsize(*this);
}

template<typename SelfPD>
template <typename FKind>
int HFrameBase<SelfPD>::frame_top_index() const {
  assert (!FKind::interpreted || interpreted_frame_top_index() >= _sp, "");
  assert (FKind::is_instance(*(hframe*)this), "");

  return _sp;
}

#ifdef CONT_DOUBLE_NOP
// TODO R remove after PD separation
template<op_mode mode>
static inline CachedCompiledMetadata cached_metadata(const hframe& hf);
#endif

template<typename SelfPD>
template<typename FKind, op_mode mode>
SelfPD HFrameBase<SelfPD>::sender(const ContMirror& cont, const InterpreterOopMap* mask, int extra_oops) const {
  assert (mode != mode_fast || !FKind::interpreted, "");
  int num_oops;
#ifdef CONT_DOUBLE_NOP
  CachedCompiledMetadata md;
#endif
  if (FKind::interpreted) {
    num_oops = interpreted_frame_num_oops(*mask);
  } else
#ifdef CONT_DOUBLE_NOP
  if (mode == mode_fast && LIKELY(!(md = cached_metadata<mode>(self())).empty()))
    num_oops = md.num_oops();
  else {
    get_cb();
#endif
    num_oops = compiled_frame_num_oops();
#ifdef CONT_DOUBLE_NOP
  }
#endif

  return sender<FKind, mode>(cont, extra_oops + num_oops);
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
  return self().to_frame(cont, pc, deopt);
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
    _hstack = (ElemType*)_stack->base(basicElementType);
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
  /*
  for (hframe hf = last_frame<mode_slow>(); !hf.is_empty(); hf = hf.sender<mode_slow>(*this)) {
    if (!hf.is_interpreted_frame()) {
      hf.cb()->as_compiled_method()->dec_on_continuation_stack();
    }
  }
  */
}

void ContMirror::null_ref_stack(int start, int num) {
  if (java_lang_Continuation::is_reset(_cont)) return;

  //log_develop_info(jvmcont)("clearing %d at %d", num, start);
  for (int i = 0; i < num; i++)
    _ref_stack->obj_at_put(start + i, NULL);
}

bool ContMirror::is_empty() {
  assert ((_pc == NULL) == (_sp < 0 || _sp >= _stack->length()), "");
  return _pc == NULL;
}

template<op_mode mode>
inline void ContMirror::set_last_frame(const hframe& f) {
  assert (mode != mode_fast || !Interpreter::contains(f.pc()), "");
  assert (mode == mode_fast || f.is_interpreted_frame() == Interpreter::contains(f.pc()), "");
  set_pc(f.pc(), mode == mode_fast ? false : f.is_interpreted_frame());
  set_sp(f.sp());
  set_last_frame_pd(f);
  set_refSP(f.ref_sp());

  assert (!is_empty(), ""); // if (is_empty()) set_empty();

  if (log_develop_is_enabled(Trace, jvmcont)) {
    log_develop_trace(jvmcont)("set_last_frame cont sp: %d fp: 0x%lx pc: " INTPTR_FORMAT " interpreted: %d flag: %d", sp(), fp(), p2i(pc()), f.is_interpreted_frame(), is_flag(FLAG_LAST_FRAME_INTERPRETED));
    f.print_on(tty);
  }
}

inline void ContMirror::set_empty() {
  if (_stack_length > 0) {
    set_sp(_stack_length);
    set_refSP(_ref_stack->length());
  }
  set_fp(0);
  clear_pc();
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
  *(fp + offset) = (intptr_t)((address)fp + to_bytes(*(intptr_t*)(fp + offset)));
}

void ContMirror::copy_to_stack(void* from, void* to, int size) {
  log_develop_trace(jvmcont)("Copying from v: " INTPTR_FORMAT " - " INTPTR_FORMAT " (%d bytes)", p2i(from), p2i((address)from + size), size);
  log_develop_trace(jvmcont)("Copying to h: " INTPTR_FORMAT " - " INTPTR_FORMAT " (%d - %d)", p2i(to), p2i((address)to + size), to_index(_hstack, to), to_index(_hstack, (address)to + size));

  assert (size > 0, "size: %d", size);
  assert (stack_index(to) >= 0, "");
  assert (to_index(_hstack, (address)to + size) <= _sp, "");

  // TODO PERF non-temporal store
  PERFTEST_ONLY(if (PERFTEST_LEVEL >= 25))
    memcpy(to, from, size); //Copy::conjoint_memory_atomic(from, to, size); // Copy::disjoint_words((HeapWord*)from, (HeapWord*)to, size/wordSize); //

  _e_size += size;
}

void ContMirror::copy_from_stack(void* from, void* to, int size) {
  log_develop_trace(jvmcont)("Copying from h: " INTPTR_FORMAT " - " INTPTR_FORMAT " (%d - %d)", p2i(from), p2i((address)from + size), to_index(stack(), from), to_index(stack(), (address)from + size));
  log_develop_trace(jvmcont)("Copying to v: " INTPTR_FORMAT " - " INTPTR_FORMAT " (%d bytes)", p2i(to), p2i((address)to + size), size);

  assert (size > 0, "size: %d", size);
  assert (stack_index(from) >= 0, "");
  assert (to_index(stack(), (address)from + size) <= stack_length(), "index: %d length: %d", to_index(stack(), (address)from + size), stack_length());

  // TODO PERF non-temporal load
  PERFTEST_ONLY(if (PERFTEST_LEVEL >= 125))
    memcpy(to, from, size); //Copy::conjoint_memory_atomic(from, to, size);

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

class CachedCompiledMetadata; // defined in PD
struct FpOopInfo;

typedef int (*FreezeFnT)(address, address, address, address, int, FpOopInfo*);
typedef int (*ThawFnT)(address /* dst */, address /* objArray */, address /* map */);


class ContinuationHelper {
public:
#ifdef CONT_DOUBLE_NOP
  static inline CachedCompiledMetadata cached_metadata(address pc);
  template<op_mode mode, typename FrameT> static inline CachedCompiledMetadata cached_metadata(const FrameT& f);
  template<typename FrameT> static void patch_freeze_stub(const FrameT& f, address freeze_stub);
#endif

  template<op_mode mode, typename FrameT> static FreezeFnT freeze_stub(const FrameT& f);
  template<op_mode mode, typename FrameT> static ThawFnT thaw_stub(const FrameT& f);
  
  template<typename FKind, typename RegisterMapT> static inline void update_register_map(RegisterMapT* map, const frame& f);
  template<typename RegisterMapT> static inline void update_register_map_with_callee(RegisterMapT* map, const frame& f);
  template<typename RegisterMapT> static inline void update_register_map(RegisterMapT* map, hframe::callee_info callee_info);
  static void update_register_map(RegisterMap* map, const hframe& sender, const ContMirror& cont);
  static void update_register_map_from_last_vstack_frame(RegisterMap* map);

  static inline frame frame_with(frame& f, intptr_t* sp, address pc, intptr_t* fp);
  static inline frame last_frame(JavaThread* thread);
  static inline void to_frame_info(const frame& f, const frame& callee, FrameInfo* fi);
  template<typename FKind> static inline void to_frame_info_pd(const frame& f, const frame& callee, FrameInfo* fi);
  static inline void to_frame_info_pd(const frame& f, FrameInfo* fi);
  template<bool indirect>
  static inline frame to_frame(FrameInfo* fi);
  static inline void set_last_vstack_frame(RegisterMap* map, const frame& callee);
  static inline void clear_last_vstack_frame(RegisterMap* map);
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

  template <typename FrameT> static CodeBlob* slow_get_cb(const FrameT& f);
  template <typename FrameT> static const ImmutableOopMap* slow_get_oopmap(const FrameT& f);
  template <typename FrameT> static int slow_size(const FrameT& f);
  template <typename FrameT> static address slow_return_pc(const FrameT& f);
  template <typename FrameT> static int slow_stack_argsize(const FrameT& f);
  template <typename FrameT> static int slow_num_oops(const FrameT& f);
#endif


inline Method* Frame::frame_method(const frame& f) {
  Method* m = NULL;
  if (f.is_interpreted_frame())
    m = f.interpreter_frame_method();
  else if (f.is_compiled_frame())
    m = ((CompiledMethod*)f.cb())->method();
  return m;
}

address Frame::return_pc(const frame& f) {
  return *return_pc_address(f);
}

// static void patch_interpreted_bci(frame& f, int bci) {
//   f.interpreter_frame_set_bcp(f.interpreter_frame_method()->bcp_from(bci));
// }

address Interpreted::return_pc(const frame& f) {
  return *return_pc_address(f);
}

void Interpreted::patch_return_pc(frame& f, address pc) {
  *return_pc_address(f) = pc;
}

void Interpreted::oop_map(const frame& f, InterpreterOopMap* mask) {
  assert (mask != NULL, "");
  Method* m = f.interpreter_frame_method();
  int   bci = f.interpreter_frame_bci();
  m->mask_for(bci, mask); // OopMapCache::compute_one_oop_map(m, bci, mask);
}

int Interpreted::num_oops(const frame&f, InterpreterOopMap* mask) {
  return   mask->num_oops()
         + 1 // for the mirror oop
         + ((intptr_t*)f.interpreter_frame_monitor_begin() - (intptr_t*)f.interpreter_frame_monitor_end())/BasicObjectLock::size(); // all locks must be NULL when freezing, but f.oops_do walks them, so we count them
}

int Interpreted::size(const frame&f, InterpreterOopMap* mask) {
  return (Interpreted::frame_bottom(f) - Interpreted::frame_top(f, mask)) * wordSize;
}

inline int Interpreted::expression_stack_size(const frame &f, InterpreterOopMap* mask) {
  int size = mask->expression_stack_size();
  assert (size <= f.interpreter_frame_expression_stack_size(), "size1: %d size2: %d", size, f.interpreter_frame_expression_stack_size());
  return size;
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
inline intptr_t* NonInterpreted<Self>::frame_top(const frame& f) { // inclusive; this will be copied with the frame
  return f.unextended_sp();
}

template<typename Self>
inline intptr_t* NonInterpreted<Self>::frame_bottom(const frame& f) { // exclusive; this will not be copied with the frame
  return f.unextended_sp() + f.cb()->frame_size();
}

#ifdef ASSERT
  intptr_t* Frame::frame_top(const frame &f) {
    if (f.is_interpreted_frame()) {
      InterpreterOopMap mask;
      Interpreted::oop_map(f, &mask);
      return Interpreted::frame_top(f, &mask);
    } else {
      return Compiled::frame_top(f);
    }
  }
#endif

template<typename Self>
template<typename FrameT>
inline int NonInterpreted<Self>::size(const FrameT& f) {
  assert (!f.is_interpreted_frame() && Self::is_instance(f), "");
  return f.cb()->frame_size() * wordSize;
}

template<typename Self>
template<typename FrameT>
inline int NonInterpreted<Self>::stack_argsize(const FrameT& f) {  assert (f.cb()->is_compiled(), "");
  return f.cb()->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size;
}

template<typename Self>
inline int NonInterpreted<Self>::num_oops(const frame& f) {
  assert (!f.is_interpreted_frame() && Self::is_instance(f), "");
  assert (f.oop_map() != NULL, "");
  return f.oop_map()->num_oops() + Self::extra_oops;
}

template<typename Self>
template<typename RegisterMapT>
bool NonInterpreted<Self>::is_owning_locks(JavaThread* thread, const RegisterMapT* map, const frame& f) {
  // if (!DetectLocksInCompiledFrames) return false;
  assert (!f.is_interpreted_frame() && Self::is_instance(f), "");

  CompiledMethod* cm = f.cb()->as_compiled_method();
  assert (!cm->is_compiled() || !cm->as_compiled_method()->is_native_method(), ""); // See compiledVFrame::compiledVFrame(...) in vframe_hp.cpp

  if (!cm->has_monitors()) {
    return false;
  }

  ResourceMark rm;
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

#ifdef ASSERT
static void set_anchor(ContMirror& cont) {
  FrameInfo fi = { cont.entryPC(), cont.entryFP(), cont.entrySP() };
  set_anchor<false>(cont.thread(), &fi);
}
#endif

static oop get_continuation(JavaThread* thread) {
  assert (thread != NULL, "");
  return thread->last_continuation();
}

// static void set_continuation(JavaThread* thread, oop cont) {
//   java_lang_Thread::set_continuation(thread->threadObj(), cont);
// }

template<typename RegisterMapT>
class ContOopBase : public OopClosure, public DerivedOopClosure {
protected:
  ContMirror* const _cont;
  const frame* _fr;
  void* const _vsp;
  int _count;
#ifdef ASSERT
  RegisterMapT* _map;
#endif

public:
  int count() { return _count; }

protected:
  ContOopBase(ContMirror* cont, const frame* fr, RegisterMapT* map, void* vsp)
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
    // assert(offset >= 0 || p == Frame::map_link_address(_map),
    //   "offset: %d reg: %s", offset, (reg = _map->find_register_spilled_here(p), reg != NULL ? reg->name() : "NONE")); // calle-saved register can only be rbp
    reg = _map->find_register_spilled_here(p); // expensive operation
    if (reg != NULL) log_develop_trace(jvmcont)("reg: %s", reg->name());
    log_develop_trace(jvmcont)("p: " INTPTR_FORMAT " offset: %d %s", p2i(p), offset, p == Frame::map_link_address(_map) ? "(link)" : "");
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

static void freeze_compiled_frame_bp() {}
static void thaw_compiled_frame_bp() {}

static FreezeContFnT cont_freeze_fast = NULL;
static FreezeContFnT cont_freeze_slow = NULL;
static FreezeContFnT cont_freeze_preempt = NULL;

template<op_mode mode>
static freeze_result cont_freeze(JavaThread* thread, ContMirror& cont, FrameInfo* fi) {
  switch (mode) {
    case mode_fast:    return cont_freeze_fast   (thread, cont, fi);
    case mode_slow:    return cont_freeze_slow   (thread, cont, fi);
    case mode_preempt: return cont_freeze_preempt(thread, cont, fi);
    default:
      guarantee(false, "unreachable");
      return freeze_exception;
  }
}

class CountOops : public OopClosure {
private:
  int _nr_oops;
public:
  CountOops() : _nr_oops(0) {}
  int nr_oops() const { return _nr_oops; }


  virtual void do_oop(oop* o) { _nr_oops++; }
  virtual void do_oop(narrowOop* o) { _nr_oops++; }
};

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

template <typename OopT>
class PersistOops : public OopClosure {
private:
  int _limit;
  int _current;
  objArrayOop _array;
public:
  PersistOops(int limit, objArrayOop array) : _limit(limit), _current(0), _array(array) {}

  virtual void do_oop(oop* o) { write_oop(o); }
  virtual void do_oop(narrowOop* o) { write_oop(o); }

private:
  template <typename T>
  void write_oop(T* p) {
    assert(_current < _limit, "");
    oop obj = NativeAccess<>::oop_load(p);
    OopT* addr = _array->obj_at_address<OopT>(_current++); // depends on UseCompressedOops
    NativeAccess<IS_DEST_UNINITIALIZED>::oop_store(addr, obj);
  }
};

/*
 * This class is mainly responsible for the work that is required to make sure that nmethods that
 * are referenced from a Continuation stack are kept alive.
 *
 * While freezing, for each nmethod a keepalive array is allocated. It contains elements for all the
 * oops that are either immediates or in the oop section in the nmethod (basically all that would be
 * published to the closure while running nm->oops_do().).
 *
 * The keepalive array is than strongly linked from the oop array in the Continuation, a weak reference
 * is kept in the nmethod -> the keepalive array.
 *
 * Some GCs (currently only G1) have code that considers the weak reference to the keepalive array a
 * strong reference while this nmethod is on the stack. This is true while we are freezing, it helps
 * performance because we don't need to allocate and keep oops to this objects in a Handle for such GCs.
 * As soon as they are linked into the nmethod we know the object will stay alive.
 */
template <typename ConfigT>
class CompiledMethodKeepalive {
private:
  typedef typename ConfigT::OopT OopT;
  typedef CompiledMethodKeepalive<ConfigT> SelfT;
  typedef typename ConfigT::KeepaliveObjectT KeepaliveObjectT;

  typename KeepaliveObjectT::TypeT _keepalive;
  CompiledMethod* _method;
  SelfT* _parent;
  JavaThread* _thread;
  int _nr_oops;
  bool _required;

  void store_keepalive(JavaThread* thread, oop* keepalive) { _keepalive = KeepaliveObjectT::make_keepalive(thread, keepalive); }
  oop read_keepalive() { return KeepaliveObjectT::read_keepalive(_keepalive); }

public:
  CompiledMethodKeepalive(CompiledMethod* cm, SelfT* parent, JavaThread* thread) : _method(cm), _parent(NULL), _thread(thread), _nr_oops(0), _required(false) {
    oop* keepalive = cm->get_keepalive();
    if (keepalive != NULL) {
   //   log_info(jvmcont)("keepalive is %p (%p) for nm %p", keepalive, (void *) *keepalive, cm);
      WeakHandle<vm_nmethod_keepalive_data> wh = WeakHandle<vm_nmethod_keepalive_data>::from_raw(keepalive);
      oop resolved = wh.resolve();
      if (resolved != NULL) {
        //log_info(jvmcont)("found keepalive %p (%p)", keepalive, (void *) resolved);
        store_keepalive(thread, keepalive);
        return;
      }

      //log_info(jvmcont)("trying to clear stale keepalive for %p", _method);
      if (cm->clear_keepalive(keepalive)) {
        //log_info(jvmcont)("keepalive cleared for %p", _method);
        thread->keepalive_cleanup()->append(wh);
        // put on a list for cleanup in a safepoint
      }
    }
  //  log_info(jvmcont)("keepalive is %p for nm %p", keepalive, cm);

    nmethod* nm = cm->as_nmethod_or_null();
    if (nm != NULL) {
      _nr_oops = nm->nr_oops();
      //log_info(jvmcont)("need keepalive for %d oops", _nr_oops);
      _required = true;
      _parent = parent;
    }
  }

  void write_at(ContMirror& mirror, int index) {
    //assert(_keepalive != NULL, "");
    //log_develop_info(jvmcont)("writing mirror at %d\n", index);
    mirror.add_oop<ConfigT>(read_keepalive(), index);
    //*(hsp + index)
  }

  void persist_oops() {
    if (!_required) {
      // Even though our first one might have said require, someone else might have written a new entry before we wrote our own.
      return;
    }

    nmethod* nm = _method->as_nmethod_or_null();
    if (nm != NULL) {
      //assert(_keepalive != NULL && read_keepalive() != NULL, "");
      PersistOops<OopT> persist(_nr_oops, (objArrayOop) read_keepalive());
      nm->oops_do(&persist);
      //log_info(jvmcont)("oops persisted");
    }
  }

  void set_handle(Handle keepalive) {
    WeakHandle<vm_nmethod_keepalive_data> wh = WeakHandle<vm_nmethod_keepalive_data>::create(keepalive);
    oop* result = _method->set_keepalive(wh.raw());

    if (result != NULL) {
      store_keepalive(_thread, result);
      // someone else managed to do it before us, destroy the weak
      _required = false;
      wh.release();
    } else {
      store_keepalive(_thread, wh.raw());
      //log_info(jvmcont)("Winning cas for %p (%p -> %p (%p))", _method, result, wh.raw(), (void *) wh.resolve());
    }
  }

  SelfT* parent() { return _parent; }
  bool required() const { return _required; }
  int nr_oops() const { return _nr_oops; }

};

template <typename FKind>
class FreezeFrame {
};

template <>
class FreezeFrame<Interpreted> {
  public:
  template <bool top, bool bottom, bool IsKeepalive, typename FreezeT>
  static hframe dispatch(FreezeT& self, const frame& f, const hframe& caller, int fsize, int argsize, int oops, InterpreterOopMap* mask, typename FreezeT::CompiledMethodKeepaliveT* ignore) {
    return self.template freeze_interpreted_frame<top, bottom>(f, caller, fsize, oops, mask);
  }
};

template <>
class FreezeFrame<Compiled> {
  public:
  template <bool top, bool bottom, bool IsKeepalive, typename FreezeT>
  static hframe dispatch(FreezeT& self, const frame& f, const hframe& caller, int fsize, int argsize, int oops, FreezeFnT f_fn, typename FreezeT::CompiledMethodKeepaliveT* kd) {
    return self.template freeze_compiled_frame<Compiled, top, bottom, IsKeepalive>(f, caller, fsize, argsize, oops, f_fn, kd);
  }
};

template <typename ConfigT, op_mode mode>
class Freeze {
  typedef typename Conditional<mode == mode_preempt, RegisterMap, SmallRegisterMap>::type RegisterMapT;
  typedef Freeze<ConfigT, mode> SelfT;
  typedef CompiledMethodKeepalive<ConfigT> CompiledMethodKeepaliveT;

private:
  JavaThread* _thread;
  ContMirror& _cont;
  intptr_t *_bottom_address;

  int _oops;
  int _size; // total size of all frames plus metadata. keeps track of offset where a frame should be written and how many bytes we need to allocate.
  int _frames;
  int _cgrind_interpreted_frames;

  FpOopInfo _fp_oop_info;
  FrameInfo* _fi;

  RegisterMapT _map;

  frame _safepoint_stub;
  hframe _safepoint_stub_h;
  bool  _safepoint_stub_caller;
  CompiledMethodKeepaliveT* _keepalive;
#ifndef PRODUCT
  intptr_t* _safepoint_stub_hsp;
#endif

  template<typename FKind> static inline frame sender(const frame& f);
  template <typename FKind, bool top, bool bottom> inline void patch_pd(const frame& f, hframe& callee, const hframe& caller);
  template <bool bottom> inline void align(const hframe& caller, int argsize);
  inline void relativize_interpreted_frame_metadata(const frame& f, intptr_t* vsp, const hframe& hf);
  template<bool cont_empty> hframe new_bottom_hframe(int sp, int ref_sp, address pc, bool interpreted);
  template<typename FKind> hframe new_hframe(const frame& f, intptr_t* vsp, const hframe& caller, int fsize, int num_oops, int argsize);

public:

  Freeze(JavaThread* thread, ContMirror& mirror) :
    _thread(thread), _cont(mirror), _bottom_address(mirror.entrySP()),
    _oops(0), _size(0), _frames(0), _cgrind_interpreted_frames(0),
    _fp_oop_info(), _map(thread, false, false, false),
    _safepoint_stub_caller(false), _keepalive(NULL) {

    _map.set_include_argument_oops(false);
  }

  int nr_oops() const   { return _oops; }
  int nr_bytes() const  { return _size; }
  int nr_frames() const { return _frames; }

  freeze_result freeze(FrameInfo* fi) {
    _fi = fi;

    HandleMark hm(_thread);

    // tty->print_cr(">>> freeze mode: %d", mode);

    // assert (map.update_map(), "RegisterMap not set to update");
    assert (!_map.include_argument_oops(), "should be");
    frame f = freeze_start_frame(_map);
    hframe caller;
    return freeze<true>(f, caller, 0);
  }

  frame freeze_start_frame(SmallRegisterMap& ignored) {
    // if (mode == mode_preempt) // TODO: we should really do partial specialization, but then we'll need to define this out-of-line
    //   return freeze_start_frame_safepoint_stub();

    assert (mode != mode_preempt, "");

    log_develop_trace(jvmcont)("%s nop at freeze yield", nativePostCallNop_at(_fi->pc) != NULL ? "has" : "no");

    // Note: if the doYield stub does not have its own frame, we may need to consider deopt here, especially if yield is inlinable
    frame f = ContinuationHelper::last_frame(_thread); // thread->last_frame();
    assert(StubRoutines::cont_doYield_stub()->contains(f.pc()), "must be");
  #ifdef ASSERT
    hframe::callee_info my_info = slow_link_address<StubF>(f);
  #endif
    f = sender<StubF>(f);
    assert (Frame::callee_link_address(f) == my_info, "");
    // ContinuationHelper::update_register_map_with_callee(&_map, f);

    // The following doesn't work because fi->fp can contain an oop, that a GC doesn't know about when walking.
    // frame::update_map_with_saved_link(&map, (intptr_t **)&fi->fp);
    // frame f = ContinuationHelper::to_frame(fi); // the yield frame

    assert (f.pc() == _fi->pc, "");

    // Log(jvmcont) logv; LogStream st(logv.debug()); f.print_on(st);
    if (log_develop_is_enabled(Debug, jvmcont)) f.print_on(tty);

    return f;
  }

  frame freeze_start_frame(RegisterMap& ignored) {
    assert (mode == mode_preempt, "");

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
  NOINLINE freeze_result freeze(const frame& f, hframe& caller, int callee_argsize) {
    assert (f.unextended_sp() < _bottom_address - SP_WIGGLE, ""); // see recurse_freeze_java_frame
    assert (f.is_interpreted_frame() || ((top && mode == mode_preempt) == is_stub(f.cb())), "");
    assert (mode != mode_fast || (!f.is_interpreted_frame() && slow_get_cb(f)->is_compiled()), "");
    assert (mode != mode_fast || !f.is_deoptimized_frame(), "");

    // Dynamically branch on frame type
    if (mode == mode_fast || f.is_compiled_frame()) {
      if (UNLIKELY(mode != mode_fast && f.oop_map() == NULL))            return freeze_pinned_native; // special native frame

      #ifdef CONT_DOUBLE_NOP
        if (UNLIKELY(!(mode == mode_fast && !ContinuationHelper::cached_metadata<mode>(f).empty()) &&
             Compiled::is_owning_locks(_cont.thread(), &_map, f))) return freeze_pinned_monitor;
      #else
        if (UNLIKELY(Compiled::is_owning_locks(_cont.thread(), &_map, f))) return freeze_pinned_monitor;
      #endif

      // Keepalive info here...
      CompiledMethodKeepaliveT kd(f.cb()->as_compiled_method(), _keepalive, _thread);
      if (kd.required()) {
        _keepalive = &kd;
        return recurse_freeze_compiled_frame<top, true>(f, caller, &kd);
      }

      return recurse_freeze_compiled_frame<top, false>(f, caller, &kd);
    } else if (f.is_interpreted_frame()) {
      if (Interpreted::is_owning_locks(f)) return freeze_pinned_monitor;

      return recurse_freeze_interpreted_frame<top>(f, caller, callee_argsize);
    } else if (mode == mode_preempt && top && is_stub(f.cb())) {
      return recurse_freeze_stub_frame(f, caller);
    } else {
      return freeze_pinned_native;
    }
  }

  template<typename FKind, bool top, bool IsKeepalive>
  inline freeze_result recurse_freeze_java_frame(const frame& f, hframe& caller, int fsize, int argsize, int oops, typename FKind::ExtraT extra, CompiledMethodKeepaliveT* kd) {
    assert (FKind::is_instance(f), "");
    log_develop_trace(jvmcont)("recurse_freeze_java_frame fsize: %d oops: %d", fsize, oops);

  #ifdef ASSERT
    hframe::callee_info my_info = slow_link_address<FKind>(f);
  #endif
    frame senderf = sender<FKind>(f); // f.sender_for_compiled_frame<ContinuationCodeBlobLookup>(&map);
    assert (FKind::interpreted || senderf.sp() == senderf.unextended_sp(), "");
    assert (Frame::callee_link_address(senderf) == my_info, "");

    // sometimes an interpreted caller's sp extends a bit below entrySP, plus another word for possible alignment of compiled callee
    if (senderf.unextended_sp() >= _bottom_address - SP_WIGGLE) { // dynamic branch
      if (UNLIKELY(!allocate()))
        return freeze_exception;

      // senderf is the entry frame
      argsize = finalize<FKind>(senderf, f, caller); // recursion end

      freeze_java_frame<FKind, top, true, IsKeepalive>(f, caller, fsize, argsize, oops, extra, kd);

      if (log_develop_is_enabled(Trace, jvmcont)) {
        log_develop_trace(jvmcont)("bottom h-frame:");
        caller.print_on(tty); // caller is now the current hframe
      }
    } else {
      bool safepoint_stub_caller; // the use of _safepoint_stub_caller is not nice, but given preemption being performance non-critical, we don't want to add either a template or a regular parameter
      if (mode == mode_preempt) {
        safepoint_stub_caller = _safepoint_stub_caller;
        _safepoint_stub_caller = false;
      }

      freeze_result result = freeze<false>(senderf, caller, argsize); // recursive call
      if (UNLIKELY(result != freeze_ok))
        return result;

      if (mode == mode_preempt) _safepoint_stub_caller = safepoint_stub_caller; // restore _stub_caller

      freeze_java_frame<FKind, top, false, IsKeepalive>(f, caller, fsize, argsize, oops, extra, kd);
    }

    if (top) {
      finish(f, caller);
    }
    return freeze_ok;
  }

  void allocate_keepalive() {
    if (_keepalive == NULL) {
      return;
    }

    CompiledMethodKeepaliveT* current = _keepalive;
    while (current != NULL) {
      _cont.make_keepalive<ConfigT>(current);
      current = current->parent();
    }
  }

  inline bool allocate() {
    _cont.allocate_stacks<ConfigT>(_size, _oops, _frames);
    return !_thread->has_pending_exception();
  }

  template<typename FKind> // the callee's type
  int finalize(const frame& f, const frame& callee, hframe& caller) {
  #ifdef CALLGRIND_START_INSTRUMENTATION
    if (_frames > 0 && _cgrind_interpreted_frames == 0 && callgrind_counter == 1) {
      callgrind_counter = 2;
      tty->print_cr("Starting callgrind instrumentation");
      CALLGRIND_START_INSTRUMENTATION;
    }
  #endif

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

    PERFTEST_ONLY(if (PERFTEST_LEVEL <= 15) return freeze_ok;)

    _cont.allocate_stacks<ConfigT>(_size, _oops, _frames);
    if (_thread->has_pending_exception()) {
      return freeze_exception;
    }

    allocate_keepalive();

    int argsize = 0;
    if (_cont.is_empty()) {
      caller = new_bottom_hframe<true>(_cont.sp(), _cont.refSP(), NULL, false);
    } else {
      assert (_cont.is_flag(FLAG_LAST_FRAME_INTERPRETED) == Interpreter::contains(_cont.pc()), "");
      int sp = _cont.sp();

      if (!FKind::interpreted) {
    #ifdef CONT_DOUBLE_NOP
        CachedCompiledMetadata md = ContinuationHelper::cached_metadata<mode>(callee);
        if (LIKELY(!md.empty())) {
          argsize = md.stack_argsize();
          assert(argsize == slow_stack_argsize(callee), "argsize: %d slow_stack_argsize: %d", argsize, slow_stack_argsize(callee));
        } else
    #endif
          argsize = Compiled::stack_argsize(callee);

        if (_cont.is_flag(FLAG_LAST_FRAME_INTERPRETED)) {
          log_develop_trace(jvmcont)("finalize _size: %d add argsize: %d", _size, argsize);
          _size += argsize;
        } else {
          // the arguments of the bottom-most frame are part of the topmost compiled frame on the hstack; we overwrite that part
          sp += argsize >> LogBytesPerElement;
        }
      }
      caller = new_bottom_hframe<false>(sp, _cont.refSP(), _cont.pc(), _cont.is_flag(FLAG_LAST_FRAME_INTERPRETED));
    }

    DEBUG_ONLY(log_develop_trace(jvmcont)("finalize bottom frame:"); if (log_develop_is_enabled(Trace, jvmcont)) caller.print_on(_cont, tty);)

    _cont.add_num_frames(_frames);
    _cont.add_size(_size);
    _cont.e_add_refs(_oops);

    return argsize;
  }

  template<typename FKind> // the callee's type
  void setup_jump(const frame& f, const frame& callee) {
    assert (f.pc() == Frame::real_pc(f) || (f.is_compiled_frame() && f.cb()->as_compiled_method()->is_deopt_pc(Frame::real_pc(f))), "");
    ContinuationHelper::to_frame_info_pd<FKind>(f, callee, _fi);
    _fi->sp = f.unextended_sp(); // java_lang_Continuation::entrySP(cont);
    _fi->pc = Continuation::is_return_barrier_entry(f.pc()) ? _cont.entryPC()
                                                            : Frame::real_pc(f); // Continuation.run may have been deoptimized

  #ifdef ASSERT
    // if (f.pc() != real_pc(f)) tty->print_cr("Continuation.run deopted!");
    log_develop_debug(jvmcont)("Jumping to frame (freeze): [%ld] (%d)", java_tid(_thread), _thread->has_pending_exception());
    frame f1 = ContinuationHelper::to_frame<true>(_fi);
    if (log_develop_is_enabled(Debug, jvmcont)) f1.print_on(tty);
    assert_top_java_frame_name(f1, RUN_SIG);
  #endif
  }

  template <typename T>
  friend class FreezeFrame;

  template<typename FKind, bool top, bool bottom, bool IsKeepalive>
  void freeze_java_frame(const frame& f, hframe& caller, int fsize, int argsize, int oops, typename FKind::ExtraT extra, CompiledMethodKeepaliveT* kd) {
    PERFTEST_ONLY(if (PERFTEST_LEVEL <= 15) return;)

    log_develop_trace(jvmcont)("============================= FREEZING FRAME interpreted: %d top: %d bottom: %d", FKind::interpreted, top, bottom);
    log_develop_trace(jvmcont)("fsize: %d argsize: %d oops: %d", fsize, argsize, oops);
    if (log_develop_is_enabled(Trace, jvmcont)) f.print_on(tty);
    assert ((mode == mode_fast && !bottom) || caller.is_interpreted_frame() == Interpreter::contains(caller.pc()), "");

    caller.copy_partial<mode>(FreezeFrame<FKind>::template dispatch<top, bottom, IsKeepalive, SelfT>(*this, f, caller, fsize, argsize, oops, extra, kd));
  }

  template <typename FKind>
  void freeze_oops(const frame& f, intptr_t* vsp, intptr_t *hsp, int index, int num_oops, void* extra) {
    PERFTEST_ONLY(if (PERFTEST_LEVEL < 30) return;)
    //log_develop_info(jvmcont)("writing %d oops from %d (%c)", num_oops, index, FKind::type);

    log_develop_trace(jvmcont)("Walking oops (freeze)");

    assert (!_map.include_argument_oops(), "");

    _fp_oop_info._has_fp_oop = false;

    int frozen;
    if (LIKELY(!FKind::interpreted && extra != NULL)) { // dynamic branch
      FreezeFnT f_fn = (FreezeFnT)extra;
      // tty->print_cr(">>>>0000<<<<<");
      frozen = freeze_compiled_oops_stub(f_fn, f, vsp, hsp, index);
    } else {
      if (num_oops == 0)
        return;
      ContinuationHelper::update_register_map_with_callee(&_map, f); // restore saved link
      frozen = FKind::interpreted ? freeze_intepreted_oops(f, vsp, hsp, index, *(InterpreterOopMap*)extra)
                                  : freeze_compiled_oops  (f, vsp, hsp, index);
    }
    assert(frozen == num_oops, "frozen: %d num_oops: %d", frozen, num_oops);
  }

  template <typename FKind, bool top, bool bottom>
  void patch(const frame& f, hframe& hf, const hframe& caller) {
    assert (FKind::is_instance(f), "");
    assert (bottom || !caller.is_empty(), "");
    // in fast mode, partial copy does not copy _is_interpreted for the caller
    assert (bottom || mode == mode_fast || Interpreter::contains(FKind::interpreted ? hf.return_pc<FKind>() : caller.real_pc(_cont)) == caller.is_interpreted_frame(), 
      "FKind: %s contains: %d is_interpreted: %d", FKind::name, Interpreter::contains(FKind::interpreted ? hf.return_pc<FKind>() : caller.real_pc(_cont)), caller.is_interpreted_frame()); // fails for perftest < 25, but that's ok
    assert (!bottom || !_cont.is_empty() || (_cont.fp() == 0 && _cont.pc() == NULL), "");
    assert (!bottom || _cont.is_empty() || caller == _cont.last_frame<mode_slow>(), "");
    assert (!bottom || _cont.is_empty() || Continuation::is_cont_barrier_frame(f), "");
    assert (!bottom || _cont.is_flag(FLAG_LAST_FRAME_INTERPRETED) == Interpreter::contains(_cont.pc()), "");
    assert (!FKind::interpreted || hf.interpreted_link_address() == _cont.stack_address(hf.fp()), "");

    if (bottom) {
      log_develop_trace(jvmcont)("Fixing return address on bottom frame: " INTPTR_FORMAT, p2i(_cont.pc()));
      FKind::interpreted ? hf.patch_return_pc<FKind>(_cont.pc())
                         : caller.patch_pc(_cont.pc(), _cont); // TODO PERF non-temporal store
    }

    patch_pd<FKind, top, bottom>(f, hf, caller);

#ifdef ASSERT
    // TODO DEOPT: long term solution: unroll on freeze and patch pc
    if (mode != mode_fast && !FKind::interpreted && !FKind::stub) {
      assert (hf.cb()->is_compiled(), "");
      if (f.is_deoptimized_frame()) {
        log_develop_trace(jvmcont)("Freezing deoptimized frame");
        assert (f.cb()->as_compiled_method()->is_deopt_pc(f.raw_pc()), "");
        assert (f.cb()->as_compiled_method()->is_deopt_pc(Frame::real_pc(f)), "");
      }
    }
#endif
  }

  template<bool top>
  NOINLINE freeze_result recurse_freeze_interpreted_frame(const frame& f, hframe& caller, int callee_argsize) {
    // ResourceMark rm(_thread);
    InterpreterOopMap mask;
    Interpreted::oop_map(f, &mask);
    int fsize = Interpreted::size(f, &mask);
    int oops  = Interpreted::num_oops(f, &mask);

    log_develop_trace(jvmcont)("recurse_interpreted_frame _size: %d add fsize: %d callee_argsize: %d -- %d", _size, fsize, callee_argsize, fsize + callee_argsize);
    _size += fsize + callee_argsize;
    _oops += oops;
    _frames++;
    _cgrind_interpreted_frames++;

    return recurse_freeze_java_frame<Interpreted, top, false>(f, caller, fsize, 0, oops, &mask, NULL);
  }

  template <bool top, bool bottom>
  hframe freeze_interpreted_frame(const frame& f, const hframe& caller, int fsize, int oops, InterpreterOopMap* mask) {
    intptr_t* vsp = Interpreted::frame_top(f, mask);
    assert ((Interpreted::frame_bottom(f) - vsp) * sizeof(intptr_t) == (size_t)fsize, "");

    hframe hf = new_hframe<Interpreted>(f, vsp, caller, fsize, oops, 0);
    intptr_t* hsp = _cont.stack_address(hf.sp());

    freeze_raw_frame(vsp, hsp, fsize);

    relativize_interpreted_frame_metadata(f, vsp, hf);

    freeze_oops<Interpreted>(f, vsp, hsp, hf.ref_sp(), oops, mask);

    patch<Interpreted, top, bottom>(f, hf, caller);

    _cont.inc_num_interpreted_frames();

    return hf;
  }

  int freeze_intepreted_oops(const frame& f, intptr_t* vsp, intptr_t* hsp, int starting_index, const InterpreterOopMap& mask) {
    FreezeOopFn oopFn(&_cont, &_fp_oop_info, &f, vsp, hsp, &_map, starting_index);
    const_cast<frame&>(f).oops_interpreted_do(&oopFn, NULL, mask);
    return oopFn.count();
  }

  template<bool top, bool IsKeepalive>
  freeze_result recurse_freeze_compiled_frame(const frame& f, hframe& caller, CompiledMethodKeepaliveT* kd) {
    int fsize, oops, argsize;
#ifdef CONT_DOUBLE_NOP
    CachedCompiledMetadata md = ContinuationHelper::cached_metadata<mode>(f); // MUST BE SAFE FOR STUB CALLER; we're not at a call instruction
    fsize = md.size();
    if (LIKELY(fsize != 0)) {
      oops = md.num_oops();
      argsize = md.stack_argsize();

      assert(fsize == slow_size(f), "fsize: %d slow_size: %d", fsize, slow_size(f));
      assert(oops  == slow_num_oops(f), "oops: %d slow_num_oops: %d", oops, slow_num_oops(f));
      assert(argsize == slow_stack_argsize(f), "argsize: %d slow_stack_argsize: %d", argsize, slow_stack_argsize(f));
    } else
#endif
    {
      fsize = Compiled::size(f);
      oops  = Compiled::num_oops(f);
      argsize = mode == mode_fast ? 0 : Compiled::stack_argsize(f);
    }
    FreezeFnT f_fn = get_oopmap_stub(f); // try to do this early, so we wouldn't need to look at the oopMap again.

    log_develop_trace(jvmcont)("recurse_freeze_compiled_frame _size: %d add fsize: %d", _size, fsize);
    _size += fsize;
    _oops += oops;
    _frames++;

    // TODO PERF: consider recalculating fsize, argsize and oops in freeze_compiled_frame instead of passing them, as we now do in thaw
    return recurse_freeze_java_frame<Compiled, top, IsKeepalive>(f, caller, fsize, argsize, oops, f_fn, kd);
  }

  template <typename FKind, bool top, bool bottom, bool IsKeepalive>
  hframe freeze_compiled_frame(const frame& f, const hframe& caller, int fsize, int argsize, int oops, FreezeFnT f_fn, CompiledMethodKeepaliveT* kd) {
    freeze_compiled_frame_bp();

    intptr_t* vsp = FKind::frame_top(f);

    // The following assertion appears also in patch_pd and align. 
    // Even in fast mode, we allow the caller of the bottom frame (i.e. last frame still on the hstack) to be interpreted.
    // We can have a different tradeoff, and only set mode_fast if this is not the case by uncommenting _fastpath = false in Thaw::finalize where we're setting the last frame
    // Doing so can save us the test for caller.is_interpreted_frame() when we're in mode_fast and bottom, but at the cost of not switching to fast mode even if only a frozen frame is interpreted.
    assert (mode != mode_fast || bottom || !Interpreter::contains(caller.pc()), "");

    // in mode_fast we must not look at caller.is_interpreted_frame() because it may be wrong (hframe::partial_copy)

    if (bottom || (mode != mode_fast && caller.is_interpreted_frame())) {
      if (!bottom) { // if we're bottom, argsize has been computed in finalize
        argsize = Compiled::stack_argsize(f);
      }
      log_develop_trace(jvmcont)("freeze_compiled_frame add argsize: fsize: %d argsize: %d fsize: %d", fsize, argsize, fsize + argsize);
      fsize += argsize;
      align<bottom>(caller, argsize); // TODO PERF
    }

    hframe hf = new_hframe<FKind>(f, vsp, caller, fsize, oops, argsize);
    intptr_t* hsp = _cont.stack_address(hf.sp());

    freeze_raw_frame(vsp, hsp, fsize);

    if (!FKind::stub) {
      if (mode == mode_preempt && _safepoint_stub_caller) {
        _safepoint_stub_h = freeze_safepoint_stub(hf);
      }

      // ref_sp: 3, oops 4  -> [ 3: oop, 4: oop, 5: oop, 6: nmethod ]
      kd->write_at(_cont, hf.ref_sp() + oops - 1);
      //freeze_oops<Compiled>(f, vsp, hsp, hf.ref_sp() + 1, oops - 1, (void*)f_fn);
      freeze_oops<Compiled>(f, vsp, hsp, hf.ref_sp(), oops - 1, (void*)f_fn);

      if (mode == mode_preempt && _safepoint_stub_caller) {
        assert (!_fp_oop_info._has_fp_oop, "must be");
        _safepoint_stub = frame();
      }

      if (IsKeepalive) {
        kd->persist_oops();
      }
    } else { // stub frame has no oops
      _fp_oop_info._has_fp_oop = false;
    }

    patch<FKind, top, bottom>(f, hf, caller);

    // log_develop_trace(jvmcont)("freeze_compiled_frame real_pc: " INTPTR_FORMAT " address: " INTPTR_FORMAT " sp: " INTPTR_FORMAT, p2i(Frame::real_pc(f)), p2i(&(((address*) f.sp())[-1])), p2i(f.sp()));
    assert(bottom || mode == mode_fast || Interpreter::contains(caller.real_pc(_cont)) == caller.is_interpreted_frame(), "");

    return hf;
  }

  int freeze_compiled_oops(const frame& f, intptr_t* vsp, intptr_t* hsp, int starting_index) {
    if (mode != mode_preempt && ConfigT::allow_stubs && get_oopmap_stub(f) == NULL) {
  #ifdef CONT_DOUBLE_NOP
      f.get_cb();
  #endif
      const ImmutableOopMap* oopmap = f.oop_map();
      assert(oopmap, "must have");
      oopmap->generate_stub(f.cb());
  #ifdef CONT_DOUBLE_NOP
      ContinuationHelper::patch_freeze_stub(f, (address)get_oopmap_stub(f));
  #endif
      log_develop_trace(jvmcont)("freeze_compiled_oops generating oopmap stub; success: %d", get_oopmap_stub(f) != NULL);
      // tty->print_cr(">>>> generating oopmap stub; success: %d <<<<<", get_oopmap_stub(f) != NULL);
      // f.print_on(tty);
    }
    FreezeFnT stub = get_oopmap_stub(f);

    if (mode != mode_preempt && ConfigT::allow_stubs && stub != NULL) {
      assert (_safepoint_stub.is_empty(), "");
      return freeze_compiled_oops_stub(stub, f, vsp, hsp, starting_index);
    } else {
      // tty->print_cr(">>>>33333<<<<<");
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

  #ifdef CONT_DOUBLE_NOP
      f.get_cb();
  #endif
      const ImmutableOopMap* oopmap = f.oop_map();
      assert(oopmap, "must have");

      FreezeOopFn oopFn(&_cont, &_fp_oop_info, &f, vsp, hsp, &_map, starting_index, stub_vsp, stub_hsp);

      OopMapDo<FreezeOopFn, FreezeOopFn, IncludeAllValues> visitor(&oopFn, &oopFn);
      visitor.oops_do(&f, &_map, oopmap);
      assert (!_map.include_argument_oops(), "");

      return oopFn.count();
    }
  }

  inline int freeze_compiled_oops_stub(FreezeFnT f_fn, const frame& f, intptr_t* vsp, intptr_t* hsp, int starting_index) {
    // tty->print_cr(">>>>2222<<<<<");
    // ContinuationHelper::update_register_map_with_callee(&_map, f);
    intptr_t** link_addr = Frame::callee_link_address(f); // Frame::map_link_address(map);
    typename ConfigT::OopT* addr = _cont.refStack()->template obj_at_address<typename ConfigT::OopT>(starting_index);
    int cnt = f_fn( (address) vsp,  (address) addr, (address) link_addr, (address) hsp, _cont.refStack()->length() - starting_index, &_fp_oop_info);
    return cnt;
  }

  NOINLINE void finish(const frame& f, const hframe& top) {
    PERFTEST_ONLY(if (PERFTEST_LEVEL <= 15) return;)

    ConfigT::OopWriterT::finish(_cont, nr_oops(), top.ref_sp());

    assert (top.sp() <= _cont.sp(), "top.sp(): %d sp: %d", top.sp(), _cont.sp());

    _cont.set_last_frame<mode>(top);

    if (log_develop_is_enabled(Trace, jvmcont)) {
      log_develop_trace(jvmcont)("top_hframe after (freeze):");
      _cont.last_frame<mode_preempt>().print_on(_cont, tty);
    }

    assert (_cont.is_flag(FLAG_LAST_FRAME_INTERPRETED) == _cont.last_frame<mode>().is_interpreted_frame(), "");
  }

  NOINLINE freeze_result recurse_freeze_stub_frame(const frame& f, hframe& caller) {
    int fsize = StubF::size(f);

    log_develop_trace(jvmcont)("recurse_stub_frame _size: %d add fsize: %d", _size, fsize);
    _size += fsize;
    _frames++;

    assert (mode == mode_preempt, "");
    _safepoint_stub = f;

  #ifdef ASSERT
    hframe::callee_info my_info = slow_link_address<StubF>(f);
  #endif
    frame senderf = sender<StubF>(f); // f.sender_for_compiled_frame<ContinuationCodeBlobLookup>(&map);

    assert (Frame::callee_link_address(senderf) == my_info, "");
    assert (senderf.unextended_sp() < _bottom_address - SP_WIGGLE, "");
    assert (senderf.is_compiled_frame(), ""); // TODO has been seen to fail in Preempt.java with -XX:+DeoptimizeALot
    assert (senderf.oop_map() != NULL, "");

    // we can have stub_caller as a value template argument, but that's unnecessary
    _safepoint_stub_caller = true;
    freeze_result result = recurse_freeze_compiled_frame<false, false>(senderf, caller, NULL);
    if (result == freeze_ok) {
      finish(f, _safepoint_stub_h);
    }
    return result;
  }

  NOINLINE hframe freeze_safepoint_stub(hframe& caller) {
    log_develop_trace(jvmcont)("== FREEZING STUB FRAME:");

    assert(mode == mode_preempt, "");
    assert(!_safepoint_stub.is_empty(), "");

    int fsize = StubF::size(_safepoint_stub);

    hframe hf = freeze_compiled_frame<StubF, true, false, false>(_safepoint_stub, caller, fsize, 0, 0, NULL, NULL);

#ifndef PRODUCT
    _safepoint_stub_hsp = _cont.stack_address(hf.sp());
#endif

    log_develop_trace(jvmcont)("== DONE FREEZING STUB FRAME");
    return hf;
  }

  inline FreezeFnT get_oopmap_stub(const frame& f) {
    if (!ConfigT::allow_stubs)
      return NULL;
    return ContinuationHelper::freeze_stub<mode>(f);
  }

  inline void freeze_raw_frame(intptr_t* vsp, intptr_t* hsp, int fsize) {
    log_develop_trace(jvmcont)("freeze_raw_frame: sp: %d", _cont.stack_index(hsp));
    _cont.copy_to_stack(vsp, hsp, fsize);
  }

  class FreezeOopFn : public ContOopBase<RegisterMapT> {
  private:
    FpOopInfo* _fp_info;
    void* const _hsp;
    int _starting_index;

    const address _stub_vsp;
  #ifndef PRODUCT
    const address _stub_hsp;
  #endif

    int add_oop(oop obj, int index) {
      //log_develop_info(jvmcont)("writing oop at %d", index);
      return this->_cont->template add_oop<ConfigT>(obj, index);
    }

  protected:
    template <class T> inline void do_oop_work(T* p) {
      this->process(p);
      oop obj = RawAccess<>::oop_load(p); // we are reading off our own stack, Raw should be fine
      int index = add_oop(obj, _starting_index + this->_count - 1);

  #ifdef ASSERT
      print_oop(p, obj);
      assert (oopDesc::is_oop_or_null(obj), "invalid oop");
      log_develop_trace(jvmcont)("narrow: %d", sizeof(T) < wordSize);

      int offset = this->verify(p);
      assert(offset < 32768, "");
      if (_stub_vsp == NULL && offset < 0) { // rbp could be stored in the callee frame.
        assert (p == (T*)Frame::map_link_address(this->_map), "");
        _fp_info->set_oop_fp_index(0xbaba); // assumed to be unnecessary at this time; used only in ASSERT for now
      } else {
        address hloc = (address)_hsp + offset; // address of oop in the (raw) h-stack
        assert (this->_cont->in_hstack(hloc), "");
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
    FreezeOopFn(ContMirror* cont, FpOopInfo* fp_info, const frame* fr, void* vsp, void* hsp, RegisterMapT* map, int starting_index, intptr_t* stub_vsp = NULL, intptr_t* stub_hsp = NULL)
    : ContOopBase<RegisterMapT>(cont, fr, map, vsp), _fp_info(fp_info), _hsp(hsp), _starting_index(starting_index),
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
      DEBUG_ONLY(this->verify(base_loc);)
      DEBUG_ONLY(this->verify(derived_loc);)

      intptr_t offset = cast_from_oop<intptr_t>(*derived_loc) - cast_from_oop<intptr_t>(*base_loc);

      log_develop_trace(jvmcont)(
        "Continuation freeze derived pointer@" INTPTR_FORMAT " - Derived: " INTPTR_FORMAT " Base: " INTPTR_FORMAT " (@" INTPTR_FORMAT ") (Offset: " INTX_FORMAT ")",
        p2i(derived_loc), p2i((address)*derived_loc), p2i((address)*base_loc), p2i(base_loc), offset);

      int hloc_offset = (address)derived_loc - (address)this->_vsp;
      if (hloc_offset < 0 && _stub_vsp == NULL) {
        assert ((intptr_t**)derived_loc == Frame::map_link_address(this->_map), "");
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
    //assert(*addr == (OopT) NULL, "");
    RawAccess<IS_DEST_UNINITIALIZED>::oop_store(addr, obj);
  }

  static void finish(ContMirror& mirror, int count, int low_array_index) {
    if (count > 0) {
      BarrierSet* bs = BarrierSet::barrier_set();
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
  if (thread->is_interp_only_mode()) {
    JvmtiThreadState *jvmti_state = thread->jvmti_thread_state();
    if (jvmti_state != NULL)
      jvmti_state->invalidate_cur_stack_depth();
  }
}

static void post_JVMTI_yield(JavaThread* thread, ContMirror& cont, const FrameInfo* fi) {
  if (JvmtiExport::should_post_continuation_yield() || JvmtiExport::can_post_frame_pop()) {
    set_anchor<true>(thread, fi); // ensure frozen frames are invisible
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
  PERFTEST_ONLY(PERFTEST_LEVEL = ContPerfTest;)

  PERFTEST_ONLY(if (PERFTEST_LEVEL <= 10) return early_return(freeze_ok, thread, fi);)
  PERFTEST_ONLY(if (PERFTEST_LEVEL < 1000) thread->set_cont_yield(false);)

#ifdef ASSERT
  log_develop_trace(jvmcont)("~~~~~~~~~ freeze mode: %d fi->sp: " INTPTR_FORMAT " fi->fp: " INTPTR_FORMAT " fi->pc: " INTPTR_FORMAT, mode, p2i(fi->sp), p2i(fi->fp), p2i(fi->pc));
  /* set_anchor(thread, fi); */ print_frames(thread);
#endif
  // if (mode != mode_fast) tty->print_cr(">>> freeze0 mode: %d", mode);

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

  PERFTEST_ONLY(if (PERFTEST_LEVEL <= 15) return freeze_ok;)

  cont.set_flag(FLAG_SAFEPOINT_YIELD, mode == mode_preempt);

  cont.write(); // commit the freeze

  cont.post_jfr_event(&event);
  post_JVMTI_yield(thread, cont, fi); // can safepoint

  // set_anchor(thread, fi);
  thread->set_cont_yield(false);

  log_develop_debug(jvmcont)("ENTRY: sp: " INTPTR_FORMAT " fp: " INTPTR_FORMAT " pc: " INTPTR_FORMAT, p2i(fi->sp), p2i(fi->fp), p2i(fi->pc));
  log_develop_debug(jvmcont)("=== End of freeze cont ### #" INTPTR_FORMAT, cont.hash());

  return 0;
}

JRT_ENTRY(int, Continuation::freeze(JavaThread* thread, FrameInfo* fi, bool from_interpreter))
  // There are no interpreted frames if we're not called from the interpreter and we haven't ancountered an i2c adapter or called Deoptimization::unpack_frames
  // Calls from native frames also go through the interpreter (see JavaCalls::call_helper)
  // We also clear thread->cont_fastpath in Deoptimize::deoptimize_single_frame and when we thaw interpreted frames
  bool fast = UseContinuationFastPath && thread->cont_fastpath() && !from_interpreter;
  // tty->print_cr(">>> freeze fast: %d thread->cont_fastpath(): %d from_interpreter: %d", fast, thread->cont_fastpath(), from_interpreter);
  return fast ? freeze0<mode_fast>(thread, fi)
              : freeze0<mode_slow>(thread, fi);
JRT_END

static freeze_result is_pinned(const frame& f, const RegisterMap* map) {
  if (f.is_interpreted_frame()) {
    if (Interpreted::is_owning_locks(f)) {
      return freeze_pinned_monitor;
    }

  } else if (f.is_compiled_frame()) {
    if (Compiled::is_owning_locks(map->thread(), map, f)) {
      return freeze_pinned_monitor;
    }

  } else {
    return freeze_pinned_native;
  }
  return freeze_ok;
}

static freeze_result is_pinned0(JavaThread* thread, oop cont_scope, bool safepoint) {
  oop cont = get_continuation(thread);
  if (cont == (oop) NULL) {
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
      if (cont == (oop) NULL)
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

typedef bool (*ThawContFnT)(JavaThread*, ContMirror&, FrameInfo*, int);

static ThawContFnT cont_thaw_fast = NULL;
static ThawContFnT cont_thaw_slow = NULL;
static ThawContFnT cont_thaw_preempt = NULL;

template<op_mode mode>
static bool cont_thaw(JavaThread* thread, ContMirror& cont, FrameInfo* fi, int num_frames) {
  switch (mode) {
    case mode_fast:    return cont_thaw_fast   (thread, cont, fi, num_frames);
    case mode_slow:    return cont_thaw_slow   (thread, cont, fi, num_frames);
    case mode_preempt: return cont_thaw_preempt(thread, cont, fi, num_frames);
    default:
      guarantee(false, "unreachable");
      return false;
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
  PERFTEST_ONLY(PERFTEST_LEVEL = ContPerfTest;)

  PERFTEST_ONLY(if (PERFTEST_LEVEL <= 110) return 0;)

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
  size += SP_WIGGLE * sizeof(intptr_t); // just in case we have an interpreted entry after which we need to align

  const address bottom = (address)fi->sp; // os::current_stack_pointer(); points to the entry frame
  if (!stack_overflow_check(thread, size + 300, bottom)) {
    fi->pc = StubRoutines::throw_StackOverflowError_entry();
    return 0;
  }

  log_develop_trace(jvmcont)("prepare_thaw bottom: " INTPTR_FORMAT " top: " INTPTR_FORMAT " size: %d", p2i(bottom), p2i(bottom - size), size);

  PERFTEST_ONLY(if (PERFTEST_LEVEL <= 120) return 0;)

  return size;
JRT_END

template <typename ConfigT, op_mode mode>
class Thaw {
  typedef typename Conditional<mode == mode_preempt, RegisterMap, SmallRegisterMap>::type RegisterMapT;

private:
  JavaThread* _thread;
  ContMirror& _cont;
  FrameInfo* _fi;

  bool _fastpath; // if true, a subsequent freeze can be in mode_fast

  RegisterMapT _map; // map is only passed to thaw_compiled_frame for use in deoptimize, which uses it only for biased locks; we may not need deoptimize there at all -- investigate

  const hframe* _safepoint_stub;
  bool _safepoint_stub_caller;
  frame _safepoint_stub_f;

  DEBUG_ONLY(int _frames;)

  inline frame new_entry_frame();
  template<typename FKind> frame new_frame(const hframe& hf, intptr_t* vsp);
  template<typename FKind, bool top, bool bottom> inline void patch_pd(frame& f, const frame& sender);
  void derelativize_interpreted_frame_metadata(const hframe& hf, const frame& f);
  inline hframe::callee_info frame_callee_info_address(frame& f);
  template<typename FKind, bool top, bool bottom> inline intptr_t* align(const hframe& hf, intptr_t* vsp, frame& caller);

  bool should_deoptimize() { return true; /* mode != mode_fast && _thread->is_interp_only_mode(); */ } // TODO PERF

public:

  Thaw(JavaThread* thread, ContMirror& mirror) :
    _thread(thread), _cont(mirror),
    _fastpath(true),
    _map(thread, false, false, false),
    _safepoint_stub(NULL), _safepoint_stub_caller(false) {

    _map.set_include_argument_oops(false);
  }

  bool thaw(FrameInfo* fi, int num_frames) {
    _fi = fi;

    assert (!_map.include_argument_oops(), "should be");

    DEBUG_ONLY(int orig_num_frames = _cont.num_frames();)
    DEBUG_ONLY(_frames = 0;)

    hframe hf = _cont.last_frame<mode>();

    log_develop_trace(jvmcont)("top_hframe before (thaw):"); if (log_develop_is_enabled(Trace, jvmcont)) hf.print_on(_cont, tty);

    frame caller;
    thaw<true>(hf, caller, num_frames);

    assert (_cont.num_frames() == orig_num_frames - _frames, "cont.is_empty: %d num_frames: %d orig_num_frames: %d frame_count: %d", _cont.is_empty(), _cont.num_frames(), orig_num_frames, _frames);
    assert (mode != mode_fast || _fastpath, "");
    return _fastpath;
  }

  template<bool top>
  void thaw(const hframe& hf, frame& caller, int num_frames) {
    assert (num_frames > 0 && !hf.is_empty(), "");

    // Dynamically branch on frame type
    if (mode == mode_preempt && top && !hf.is_interpreted_frame()) {
      assert (is_stub(hf.cb()), "");
      recurse_stub_frame(hf, caller, num_frames);
    } else if (mode == mode_fast || !hf.is_interpreted_frame()) {
      recurse_compiled_frame<top>(hf, caller, num_frames);
    } else {
      assert (mode != mode_fast, "");
      recurse_interpreted_frame<top>(hf, caller, num_frames);
    }
  }

  template<typename FKind, bool top>
  void recurse_thaw_java_frame(const hframe& hf, frame& caller, int num_frames, void* extra) {
    assert (num_frames > 0, "");

    //hframe hsender = hf.sender<FKind, mode(_cont, 
    //return sender<FKind, mode>(cont, FKind::interpreted ? interpreted_frame_num_oops(*mask) : compiled_frame_num_oops());
    hframe hsender = hf.sender<FKind, mode>(_cont, FKind::interpreted ? (InterpreterOopMap*)extra : NULL, FKind::extra_oops); // TODO PERF maybe we can reuse fsize?

    bool is_empty = hsender.is_empty();
    if (num_frames == 1 || is_empty) {
      log_develop_trace(jvmcont)("is_empty: %d", is_empty);
      finalize<FKind>(hsender, hf, is_empty, caller);
      thaw_java_frame<FKind, top, true>(hf, caller, extra);
    } else {
      bool safepoint_stub_caller; // the use of _safepoint_stub_caller is not nice, but given preemption being performance non-critical, we don't want to add either a template or a regular parameter
      if (mode == mode_preempt) {
        safepoint_stub_caller = _safepoint_stub_caller;
        _safepoint_stub_caller = false;
      }

      thaw<false>(hsender, caller, num_frames - 1); // recurse

      if (mode == mode_preempt) _safepoint_stub_caller = safepoint_stub_caller; // restore _stub_caller

      thaw_java_frame<FKind, top, false>(hf, caller, extra);
    }

    if (top) {
      finish(caller); // caller is now the current frame
    }

    DEBUG_ONLY(_frames++;)
  }

  template<typename FKind>
  void finalize(const hframe& hf, const hframe& callee, bool is_empty, frame& entry) {
    PERFTEST_ONLY(if (PERFTEST_LEVEL <= 115) return;)

    entry = new_entry_frame();
    // if (entry.is_interpreted_frame()) _fastpath = false; // set _fastpath if entry is interpreted ? 

  #ifdef ASSERT
    log_develop_trace(jvmcont)("Found entry:");
    print_vframe(entry);
    assert_bottom_java_frame_name(entry, RUN_SIG);
  #endif

    if (is_empty) {
      _cont.set_empty();

      // This is part of the mechanism to pop stack-passed compiler arguments; see generate_cont_thaw's no_saved_sp label.
      // we use thread->_cont_frame->sp rather than the continuations themselves (which allow nesting) b/c it's faser and simpler.
      // for that to work, we rely on the fact that parent continuation's have at least Continuation.run on the stack, which does not require stack arguments
      _cont.thread()->cont_frame()->sp = NULL;
      // _cont.set_entryPC(NULL);
    } else {
      _cont.set_last_frame<mode>(hf); // _last_frame = hf;
      if (!FKind::interpreted && !hf.is_interpreted_frame()) {
        int argsize;
    #ifdef CONT_DOUBLE_NOP
        CachedCompiledMetadata md = ContinuationHelper::cached_metadata<mode>(callee);
        if (LIKELY(!md.empty())) {
          argsize = md.stack_argsize();
          assert(argsize == slow_stack_argsize(callee), "argsize: %d slow_stack_argsize: %d", argsize, slow_stack_argsize(callee));
        } else
    #endif
          argsize = callee.compiled_frame_stack_argsize();
        // we'll be subtracting the argsize in thaw_compiled_frame, but if the caller is compiled, we shouldn't
        _cont.add_size(argsize);
      } 
      // else {
      //   _fastpath = false; // see discussion in Freeze::freeze_compiled_frame
      // }
    }

    assert (is_entry_frame(_cont, entry), "");
    assert (_frames == 0, "");
    assert (is_empty == _cont.is_empty() /* _last_frame.is_empty()*/, "hf.is_empty(cont): %d last_frame.is_empty(): %d ", is_empty, _cont.is_empty()/*_last_frame.is_empty()*/);
  }

  template<typename FKind, bool top, bool bottom>
  void thaw_java_frame(const hframe& hf, frame& caller, void* extra) {
    PERFTEST_ONLY(if (PERFTEST_LEVEL <= 115) return;)

    log_develop_trace(jvmcont)("============================= THAWING FRAME:");

    assert (FKind::is_instance(hf), "");
    assert (bottom == is_entry_frame(_cont, caller), "");

    if (log_develop_is_enabled(Trace, jvmcont)) hf.print(_cont);

    log_develop_trace(jvmcont)("stack_length: %d", _cont.stack_length());

    // TODO PERF see partial_copy in Freeze
    caller = FKind::interpreted ? thaw_interpreted_frame    <top, bottom>(hf, caller, (InterpreterOopMap*)extra)
                                : thaw_compiled_frame<FKind, top, bottom>(hf, caller, (ThawFnT)extra);

    log_develop_trace(jvmcont)("thawed frame:");
    DEBUG_ONLY(print_vframe(caller, &dmap);)
  }

  template <typename FKind>
  void thaw_oops(frame& f, intptr_t* vsp, int oop_index, void* extra) {
    PERFTEST_ONLY(if (PERFTEST_LEVEL < 130) return;)

    log_develop_trace(jvmcont)("Walking oops (thaw)");

    assert (!_map.include_argument_oops(), "");

    int thawed;
    if (!FKind::interpreted && extra != NULL) {
      thawed = thaw_compiled_oops_stub(f, (ThawFnT)extra, vsp, oop_index);
      //log_develop_info(jvmcont)("thawing %d oops from %d (stub)", thawed, oop_index);
    } else {
      int num_oops = FKind::interpreted ? Interpreted::num_oops(f, (InterpreterOopMap*)extra) : NonInterpreted<FKind>::num_oops(f);
      num_oops -= FKind::extra_oops;
      //log_develop_info(jvmcont)("thawing %d oops from %d", num_oops, oop_index);
      if (num_oops == 0) {
        if (FKind::extra_oops > 0) {
          _cont.null_ref_stack(oop_index, FKind::extra_oops);
        }
        return;
      }

      thawed = FKind::interpreted ? thaw_interpreted_oops(f, vsp, oop_index, (InterpreterOopMap*)extra)
                                  : thaw_compiled_oops   (f, vsp, oop_index);
    }

    log_develop_trace(jvmcont)("count: %d", thawed);
#ifdef ASSERT
    int num_oops = FKind::interpreted ? Interpreted::num_oops(f, (InterpreterOopMap*)extra) : NonInterpreted<FKind>::num_oops(f);
    assert(thawed == num_oops - FKind::extra_oops, "closure oop count different.");
#endif

    _cont.null_ref_stack(oop_index, thawed + FKind::extra_oops);
    _cont.e_add_refs(thawed);

    log_develop_trace(jvmcont)("Done walking oops");
  }

  template<typename FKind, bool top, bool bottom>
  inline void patch(frame& f, const frame& caller) {
    if (bottom && !_cont.is_empty()) {
      log_develop_trace(jvmcont)("Setting return address to return barrier: " INTPTR_FORMAT, p2i(StubRoutines::cont_returnBarrier()));
      FKind::interpreted ? Interpreted::patch_return_pc(f, StubRoutines::cont_returnBarrier())
                         : FKind::patch_pc(caller, StubRoutines::cont_returnBarrier());
    } else if (bottom || should_deoptimize()) {
      FKind::interpreted ? Interpreted::patch_return_pc(f, caller.raw_pc())
                         : FKind::patch_pc(caller, caller.raw_pc()); // this patches the return address to the deopt handler if necessary
    }
    patch_pd<FKind, top, bottom>(f, caller);

    if (FKind::interpreted) {
      Interpreted::patch_sender_sp(f, caller.unextended_sp()); // ContMirror::derelativize(vfp, frame::interpreter_frame_sender_sp_offset);
    }

    assert (!bottom || !_cont.is_empty() || assert_bottom_java_frame_name(f, ENTER_SIG), "");
    assert (!bottom || (_cont.is_empty() != Continuation::is_cont_barrier_frame(f)), "cont.is_empty(): %d is_cont_barrier_frame(f): %d ", _cont.is_empty(), Continuation::is_cont_barrier_frame(f));
  }

  template<bool top>
  NOINLINE void recurse_interpreted_frame(const hframe& hf, frame& caller, int num_frames) {
    // ResourceMark rm(_thread);
    InterpreterOopMap mask;
    hf.interpreted_frame_oop_map(&mask);
    int fsize = hf.interpreted_frame_size();
    int oops  = hf.interpreted_frame_num_oops(mask);

    recurse_thaw_java_frame<Interpreted, top>(hf, caller, num_frames, (void*)&mask);
  }

  template<bool top, bool bottom>
  frame thaw_interpreted_frame(const hframe& hf, const frame& caller, InterpreterOopMap* mask) {
    int fsize = hf.interpreted_frame_size();
    log_develop_trace(jvmcont)("fsize: %d", fsize);
    intptr_t* vsp = (intptr_t*)((address)caller.unextended_sp() - fsize);
    intptr_t* hsp = _cont.stack_address(hf.sp());

    frame f = new_frame<Interpreted>(hf, vsp);

    // if the caller is compiled we should really extend its sp to be our fp + 2 (1 for the return address, plus 1), but we don't bother as we don't use it

    thaw_raw_frame(hsp, vsp, fsize);

    derelativize_interpreted_frame_metadata(hf, f);

    thaw_oops<Interpreted>(f, f.sp(), hf.ref_sp(), mask);

    patch<Interpreted, top, bottom>(f, caller);

    assert(f.is_interpreted_frame_valid(_cont.thread()), "invalid thawed frame");
    assert(Interpreted::frame_bottom(f) <= Frame::frame_top(caller), "");

    _cont.sub_size(fsize);
    _cont.dec_num_frames();
    _cont.dec_num_interpreted_frames();

    _fastpath = false;

    return f;
  }

  int thaw_interpreted_oops(frame& f, intptr_t* vsp, int starting_index, InterpreterOopMap* mask) {
    assert (mask != NULL, "");

    ThawOopFn oopFn(&_cont, &f, starting_index, vsp, &_map);
    f.oops_interpreted_do(&oopFn, NULL, mask); // f.oops_do(&oopFn, NULL, &oopFn, &_map);
    return oopFn.count();
  }

  template<bool top>
  void recurse_compiled_frame(const hframe& hf, frame& caller, int num_frames) {
    ThawFnT t_fn = get_oopmap_stub(hf); // try to do this early, so we wouldn't need to look at the oopMap again.

    return recurse_thaw_java_frame<Compiled, top>(hf, caller, num_frames, (void*)t_fn);
  }

  template<typename FKind, bool top, bool bottom>
  frame thaw_compiled_frame(const hframe& hf, const frame& caller, ThawFnT t_fn) {
    thaw_compiled_frame_bp();
    assert(FKind::stub == is_stub(hf.cb()), "");
    assert (caller.sp() == caller.unextended_sp(), "");

    int fsize;
#ifdef CONT_DOUBLE_NOP
    CachedCompiledMetadata md;
    if (mode != mode_preempt) {
      md = ContinuationHelper::cached_metadata(hf.pc());
      fsize = md.size();
    }
    if (mode == mode_preempt || UNLIKELY(fsize == 0))
#endif
      fsize = hf.compiled_frame_size();
    assert(fsize == slow_size(hf), "argsize: %d slow_size: %d", fsize, slow_size(hf));
    log_develop_trace(jvmcont)("fsize: %d", fsize);

    intptr_t* vsp = (intptr_t*)((address)caller.unextended_sp() - fsize);
    log_develop_trace(jvmcont)("vsp: " INTPTR_FORMAT, p2i(vsp));

    if (bottom || (mode != mode_fast && caller.is_interpreted_frame())) {
      log_develop_trace(jvmcont)("thaw_compiled_frame add argsize: fsize: %d argsize: %d fsize: %d", fsize, hf.compiled_frame_stack_argsize(), fsize + hf.compiled_frame_stack_argsize());
      int argsize;
  #ifdef CONT_DOUBLE_NOP
      if (mode != mode_preempt && LIKELY(!md.empty())) {
        argsize = md.stack_argsize();
        assert(argsize == slow_stack_argsize(hf), "argsize: %d slow_stack_argsize: %d", argsize, slow_stack_argsize(hf));
      } else
  #endif
        argsize = hf.compiled_frame_stack_argsize();

      fsize += argsize;
      vsp   -= argsize >> LogBytesPerWord;

      const_cast<frame&>(caller).set_sp((intptr_t*)((address)caller.sp() - argsize));
      assert (caller.sp() == (intptr_t*)((address)vsp + (fsize-argsize)), "");

      vsp = align<FKind, top, bottom>(hf, vsp, const_cast<frame&>(caller));
    }

    _cont.sub_size(fsize);

    intptr_t* hsp = _cont.stack_address(hf.sp());

    log_develop_trace(jvmcont)("hsp: %d ", _cont.stack_index(hsp));

    frame f = new_frame<FKind>(hf, vsp);

    thaw_raw_frame(hsp, vsp, fsize);

    if (!FKind::stub) {
      if (mode == mode_preempt && _safepoint_stub_caller) {
        _safepoint_stub_f = thaw_safepoint_stub(f);
      }

      thaw_oops<FKind>(f, f.sp(), hf.ref_sp(), (void*)t_fn);
    }

    patch<FKind, top, bottom>(f, caller);

    _cont.dec_num_frames();

    if (!FKind::stub) {
      if (f.is_deoptimized_frame()) { // TODO PERF
        _fastpath = false;
      } else if (should_deoptimize()
          && (hf.cb()->as_compiled_method()->is_marked_for_deoptimization() || (mode != mode_fast && _thread->is_interp_only_mode()))) {
        log_develop_trace(jvmcont)("Deoptimizing thawed frame");
        DEBUG_ONLY(Frame::patch_pc(f, NULL));

        f.deoptimize(_thread); // we're assuming there are no monitors; this doesn't revoke biased locks
        // set_anchor(_thread, f); // deoptimization may need this
        // Deoptimization::deoptimize(_thread, f, &_map); // gets passed frame by value 
        // clear_anchor(_thread);

        assert (f.is_deoptimized_frame() && is_deopt_return(f.raw_pc(), f), 
          "f.is_deoptimized_frame(): %d is_deopt_return(f.raw_pc()): %d is_deopt_return(f.pc()): %d", 
          f.is_deoptimized_frame(), is_deopt_return(f.raw_pc(), f), is_deopt_return(f.pc(), f));
        _fastpath = false;
      } 
    }

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

    int cnt = oopFn.count();
    return cnt;
  }

  int thaw_compiled_oops_stub(frame& f, ThawFnT t_fn, intptr_t* vsp, int starting_index) {
    typename ConfigT::OopT* addr = _cont.refStack()->template obj_at_address<typename ConfigT::OopT>(starting_index);
    int cnt = t_fn((address) vsp, (address)addr, (address)frame_callee_info_address(f)); // write the link straight into the frame struct
    return cnt;
  }

  void finish(frame& f) {
    PERFTEST_ONLY(if (PERFTEST_LEVEL <= 115) return;)

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

  void setup_jump(frame& f) {
    assert (!f.is_compiled_frame() || f.is_deoptimized_frame() == f.cb()->as_compiled_method()->is_deopt_pc(f.raw_pc()), "");
    assert (!f.is_compiled_frame() || f.is_deoptimized_frame() == (f.pc() != f.raw_pc()), "");

    assert ((address)(_fi + 1) < (address)f.sp(), "");
    _fi->sp = f.sp();
    address pc = f.raw_pc();
    _fi->pc = pc;
    ContinuationHelper::to_frame_info_pd(f, _fi);

    Frame::patch_pc(f, pc); // in case we want to deopt the frame in a full transition, this is checked.

    assert (mode == mode_preempt || !CONT_FULL_STACK || assert_top_java_frame_name(f, YIELD0_SIG), "");
  }

  void recurse_stub_frame(const hframe& hf, frame& caller, int num_frames) {
    log_develop_trace(jvmcont)("Found safepoint stub");

    assert (num_frames > 1, "");
    assert (mode == mode_preempt, "");
    assert(!hf.is_bottom<StubF>(_cont), "");

    assert (hf.compiled_frame_num_oops() == 0, "");

    _safepoint_stub = &hf;
    _safepoint_stub_caller = true;

    hframe hsender = hf.sender<StubF, mode>(_cont, 0);
    assert (!hsender.is_interpreted_frame(), "");
    recurse_compiled_frame<false>(hsender, caller, num_frames - 1);

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

    frame f = thaw_compiled_frame<StubF, true, false>(stubf, caller, NULL);

    f.oop_map()->update_register_map(&f, _map.as_RegisterMap());
    log_develop_trace(jvmcont)("THAWING OOPS FOR SENDER OF SAFEPOINT STUB");
    return f;
  }

  inline ThawFnT get_oopmap_stub(const hframe& f) {
    if (!ConfigT::allow_stubs)
      return NULL;
    return ContinuationHelper::thaw_stub<mode>(f);
  }

  inline void thaw_raw_frame(intptr_t* hsp, intptr_t* vsp, int fsize) {
    log_develop_trace(jvmcont)("thaw_raw_frame: sp: %d", _cont.stack_index(hsp));
    _cont.copy_from_stack(hsp, vsp, fsize);
  }

  class ThawOopFn : public ContOopBase<RegisterMapT> {
  private:
    int _i;

  protected:
    template <class T> inline void do_oop_work(T* p) {
      this->process(p);
      oop obj = this->_cont->obj_at(_i); // does a HeapAccess<IN_HEAP_ARRAY> load barrier

      assert (oopDesc::is_oop_or_null(obj), "invalid oop");
      log_develop_trace(jvmcont)("i: %d", _i); print_oop(p, obj);
      
      NativeAccess<IS_DEST_UNINITIALIZED>::oop_store(p, obj);
      _i++;
    }
  public:
    ThawOopFn(ContMirror* cont, frame* fr, int index, void* vsp, RegisterMapT* map)
      : ContOopBase<RegisterMapT>(cont, fr, map, vsp) { _i = index; }
    void do_oop(oop* p)       { do_oop_work(p); }
    void do_oop(narrowOop* p) { do_oop_work(p); }

    void do_derived_oop(oop *base_loc, oop *derived_loc) {
      assert(Universe::heap()->is_in_or_null(*base_loc), "not an oop: " INTPTR_FORMAT " (at " INTPTR_FORMAT ")", p2i((oopDesc*)*base_loc), p2i(base_loc));
      assert(derived_loc != base_loc, "Base and derived in same location");
      DEBUG_ONLY(this->verify(base_loc);)
      DEBUG_ONLY(this->verify(derived_loc);)
      assert (oopDesc::is_oop_or_null(*base_loc), "invalid oop");

      intptr_t offset = *(intptr_t*)derived_loc;

      log_develop_trace(jvmcont)(
        "Continuation thaw derived pointer@" INTPTR_FORMAT " - Derived: " INTPTR_FORMAT " Base: " INTPTR_FORMAT " (@" INTPTR_FORMAT ") (Offset: " INTX_FORMAT ")",
        p2i(derived_loc), p2i((address)*derived_loc), p2i((address)*base_loc), p2i(base_loc), offset);

      oop obj = cast_to_oop(cast_from_oop<intptr_t>(*base_loc) + offset);
      *derived_loc = obj;

      assert(Universe::heap()->is_in_or_null(obj), "");
    }
  };
};

static void post_JVMTI_continue(JavaThread* thread, FrameInfo* fi, int java_frame_count) {
  if (JvmtiExport::should_post_continuation_run()) {
    set_anchor<false>(thread, fi); // ensure thawed frames are visible
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

  if (return_barrier) {
    log_develop_trace(jvmcont)("== RETURN BARRIER");
  }
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

  bool res; // whether only compiled frames are thawed
  if (cont.is_flag(FLAG_SAFEPOINT_YIELD)) {
    res = cont_thaw<mode_preempt>(thread, cont, fi, num_frames);
  } else if (cont.num_interpreted_frames() == 0 && !thread->is_interp_only_mode()) {
    res = cont_thaw<mode_fast>(thread, cont, fi, num_frames);
  } else {
    res = cont_thaw<mode_slow>(thread, cont, fi, num_frames);
  }

  cont.write();

  thread->set_cont_fastpath(res);

  log_develop_trace(jvmcont)("fi->sp: " INTPTR_FORMAT " fi->fp: " INTPTR_FORMAT " fi->pc: " INTPTR_FORMAT, p2i(fi->sp), p2i(fi->fp), p2i(fi->pc));

#ifndef PRODUCT
  set_anchor<false>(thread, fi);
  print_frames(thread, tty); // must be done after write(), as frame walking reads fields off the Java objects.
  clear_anchor(thread);
#endif

  if (log_develop_is_enabled(Trace, jvmcont)) {
    log_develop_trace(jvmcont)("Jumping to frame (thaw):");
    frame f = frame(fi->sp, fi->fp, fi->pc);
    print_vframe(f, NULL);
  }

  DEBUG_ONLY(thread->_continuation = oopCont;)

  cont.post_jfr_event(&event);
  if (!return_barrier) {
    post_JVMTI_continue(thread, fi, java_frame_count);
  }

  log_develop_debug(jvmcont)("=== End of thaw #" INTPTR_FORMAT, cont.hash());
}

// IN:  fi->sp = the future SP of the topmost thawed frame (where we'll copy the thawed frames)
// Out: fi->sp = the SP of the topmost thawed frame -- the one we will resume at
//      fi->fp = the FP " ...
//      fi->pc = the PC " ...
// JRT_ENTRY(void, Continuation::thaw(JavaThread* thread, FrameInfo* fi, int num_frames))
JRT_LEAF(address, Continuation::thaw_leaf(FrameInfo* fi, bool return_barrier, bool exception))
  //callgrind();
  PERFTEST_ONLY(PERFTEST_LEVEL = ContPerfTest;)

  thaw0(JavaThread::current(), fi, return_barrier);
  // clear_anchor(JavaThread::current());

  if (exception) {
    // TODO: handle deopt. see TemplateInterpreterGenerator::generate_throw_exception, OptoRuntime::handle_exception_C, OptoRuntime::handle_exception_helper
    // assert (!top.is_deoptimized_frame(), ""); -- seems to be handled
    address ret = fi->pc;
    fi->pc = SharedRuntime::raw_exception_handler_for_return_address(JavaThread::current(), fi->pc);
    return ret;
  } else {
    return reinterpret_cast<address>(Interpreter::contains(fi->pc)); // TODO PERF: really only necessary in the case of continuing from a forced yield
  }
JRT_END

JRT_ENTRY(address, Continuation::thaw(JavaThread* thread, FrameInfo* fi, bool return_barrier, bool exception))
  //callgrind();
  PERFTEST_ONLY(PERFTEST_LEVEL = ContPerfTest;)

  assert(thread == JavaThread::current(), "");

  thaw0(thread, fi, return_barrier);
  set_anchor<false>(thread, fi); // we're in a full transition that expects last_java_frame

  if (exception) {
    // TODO: handle deopt. see TemplateInterpreterGenerator::generate_throw_exception, OptoRuntime::handle_exception_C, OptoRuntime::handle_exception_helper
    // assert (!top.is_deoptimized_frame(), ""); -- seems to be handled
    address ret = fi->pc;
    fi->pc = SharedRuntime::raw_exception_handler_for_return_address(JavaThread::current(), fi->pc);
    return ret;
  } else {
    return reinterpret_cast<address>(Interpreter::contains(fi->pc)); // TODO PERF: really only necessary in the case of continuing from a forced yield
  }
JRT_END

bool Continuation::is_continuation_entry_frame(const frame& f, const RegisterMap* map) {
  Method* m = (map->in_cont() && f.is_interpreted_frame()) ? Continuation::interpreter_frame_method(f, map)
                                                           : Frame::frame_method(f);
  if (m == NULL)
    return false;

  // we can do this because the entry frame is never inlined
  return m->intrinsic_id() == vmIntrinsics::_Continuation_enter;
}

bool Continuation::is_cont_post_barrier_entry_frame(const frame& f) {
  return is_return_barrier_entry(Frame::real_pc(f));
}

// When walking the virtual stack, this method returns true
// iff the frame is a thawed continuation frame whose
// caller is still frozen on the h-stack.
// The continuation object can be extracted from the thread.
bool Continuation::is_cont_barrier_frame(const frame& f) {
#ifdef CONT_DOUBLE_NOP
  #ifdef ASSERT
    if (!f.is_interpreted_frame()) return is_return_barrier_entry(slow_return_pc(f));
  #endif
#endif
  assert (f.is_interpreted_frame() || f.cb() != NULL, "");
  return is_return_barrier_entry(f.is_interpreted_frame() ? Interpreted::return_pc(f) : Compiled::return_pc(f));
}

bool Continuation::is_return_barrier_entry(const address pc) {
  return pc == StubRoutines::cont_returnBarrier();
}

static inline bool is_sp_in_continuation(intptr_t* const sp, oop cont) {
  // tty->print_cr(">>>> is_sp_in_continuation cont: %p sp: %p entry: %p in: %d", (oopDesc*)cont, sp, java_lang_Continuation::entrySP(cont), java_lang_Continuation::entrySP(cont) > sp);
  return java_lang_Continuation::entrySP(cont) > sp;
}

bool Continuation::is_frame_in_continuation(const frame& f, oop cont) {
  return is_sp_in_continuation(f.unextended_sp(), cont);
}

static oop get_continuation_for_frame(JavaThread* thread, intptr_t* const sp) {
  oop cont = get_continuation(thread);
  while (cont != NULL && !is_sp_in_continuation(sp, cont)) {
    cont = java_lang_Continuation::parent(cont);
  }
  // tty->print_cr("get_continuation_for_frame cont: %p entrySP: %p", (oopDesc*)cont, cont != NULL ? java_lang_Continuation::entrySP(cont): NULL);
  return cont;
}

oop Continuation::get_continutation_for_frame(JavaThread* thread, const frame& f) {
  return get_continuation_for_frame(thread, f.unextended_sp());
}

bool Continuation::is_frame_in_continuation(JavaThread* thread, const frame& f) {
  return get_continuation_for_frame(thread, f.unextended_sp()) != NULL;
}

address* Continuation::get_continuation_entry_pc_for_sender(Thread* thread, const frame& f, address* pc_addr0) {
  if (!thread->is_Java_thread()) 
    return pc_addr0;
  oop cont = get_continuation_for_frame((JavaThread*)thread, f.unextended_sp() - 1);
  if (cont == NULL)
    return pc_addr0;
  if (is_sp_in_continuation(f.unextended_sp(), cont))
    return pc_addr0; // not the run frame
  if (*pc_addr0 == f.raw_pc())
    return pc_addr0;
  
  address *pc_addr = java_lang_Continuation::entryPC_addr(cont);
  // If our callee is the entry frame, we can continue as usual becuse we use the ordinary return address; see Freeze::setup_jump
  // If the entry frame is the callee, we set entryPC_addr to NULL in Thaw::finalize
  // if (*pc_addr == NULL) {
  //   assert (!is_return_barrier_entry(*pc_addr0), "");
  //   return pc_addr0;
  // }
 
  // tty->print_cr(">>>> get_continuation_entry_pc_for_sender"); f.print_on(tty);
  log_develop_trace(jvmcont)("get_continuation_entry_pc_for_sender pc_addr: " INTPTR_FORMAT " *pc_addr: " INTPTR_FORMAT, p2i(pc_addr), p2i(*pc_addr));
  DEBUG_ONLY(if (log_develop_is_enabled(Trace, jvmcont)) { print_blob(tty, *pc_addr); print_blob(tty, *(address*)(f.sp()-1)); })
  // if (log_develop_is_enabled(Trace, jvmcont)) { os::print_location(tty, (intptr_t)pc_addr); os::print_location(tty, (intptr_t)*pc_addr); }

  return pc_addr;
}

bool Continuation::fix_continuation_bottom_sender(JavaThread* thread, const frame& callee, address* sender_pc, intptr_t** sender_sp, intptr_t** sender_fp) {
  // TODO : this code and its use sites, as well as get_continuation_entry_pc_for_sender, probably need more work
  if (thread != NULL && is_return_barrier_entry(*sender_pc)) {
    log_develop_trace(jvmcont)("fix_continuation_bottom_sender callee:"); if (log_develop_is_enabled(Debug, jvmcont)) callee.print_value_on(tty, thread);
    log_develop_trace(jvmcont)("fix_continuation_bottom_sender: sender_pc: " INTPTR_FORMAT " sender_sp: " INTPTR_FORMAT " sender_fp: " INTPTR_FORMAT, p2i(*sender_pc), p2i(*sender_sp), p2i(*sender_fp));
      
    oop cont = get_continuation_for_frame(thread, callee.is_interpreted_frame() ? callee.interpreter_frame_last_sp() : callee.unextended_sp());
    assert (cont != NULL, "callee.unextended_sp(): " INTPTR_FORMAT, p2i(callee.unextended_sp()));
    log_develop_trace(jvmcont)("fix_continuation_bottom_sender: continuation entrySP: " INTPTR_FORMAT " entryPC: " INTPTR_FORMAT " entryFP: " INTPTR_FORMAT, 
      p2i(java_lang_Continuation::entrySP(cont)), p2i(java_lang_Continuation::entryPC(cont)), p2i(java_lang_Continuation::entryFP(cont)));

    address new_pc = java_lang_Continuation::entryPC(cont);
    log_develop_trace(jvmcont)("fix_continuation_bottom_sender: sender_pc: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(*sender_pc), p2i(new_pc));
    assert (new_pc != NULL, "");
    *sender_pc = new_pc;

    intptr_t* new_fp = java_lang_Continuation::entryFP(cont);
    log_develop_trace(jvmcont)("fix_continuation_bottom_sender: sender_fp: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(*sender_fp), p2i(new_fp));
    *sender_fp = new_fp;

    if (callee.is_compiled_frame() && !Interpreter::contains(*sender_pc)) {
      // The callee's stack arguments (part of the caller frame) are also thawed to the stack when using lazy-copy
      int argsize = callee.cb()->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size;
      assert ((argsize & WordAlignmentMask) == 0, "must be");
      argsize >>= LogBytesPerWord;
    #ifdef _LP64 // TODO PD
      if (argsize % 2 != 0)
        argsize++; // 16-byte alignment for compiled frame sp
    #endif
      log_develop_trace(jvmcont)("fix_continuation_bottom_sender: sender_sp: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(*sender_sp), p2i(*sender_sp + argsize));
      *sender_sp += argsize;
    } else {
      // intptr_t* new_sp = java_lang_Continuation::entrySP(cont);
      // if (Interpreter::contains(*sender_pc)) {
      //   new_sp -= 2;
      // }
      // log_develop_trace(jvmcont)("fix_continuation_bottom_sender: sender_sp: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(*sender_sp), p2i(new_sp));
      // *sender_sp = new_sp;
    }
    return true;
  }
  return false;
}

bool Continuation::fix_continuation_bottom_sender(RegisterMap* map, const frame& callee, address* sender_pc, intptr_t** sender_sp, intptr_t** sender_fp) {
  bool res = fix_continuation_bottom_sender(map->thread(), callee, sender_pc, sender_sp, sender_fp);
  if (res && !callee.is_interpreted_frame()) {
    ContinuationHelper::set_last_vstack_frame(map, callee);
  } else {
    ContinuationHelper::clear_last_vstack_frame(map);
  }
  return res;
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
    intptr_t* sender_fp = f.fp();
    fix_continuation_bottom_sender(map, callee, &sender_pc, &sender_sp, &sender_fp);
    return ContinuationHelper::frame_with(f, sender_sp, sender_pc, sender_fp);
  }

  return f;
}

address Continuation::get_top_return_pc_post_barrier(JavaThread* thread, address pc) {
  oop cont;
  if (thread != NULL && is_return_barrier_entry(pc) && (cont = get_continuation(thread)) != NULL) {
    pc = java_lang_Continuation::entryPC(cont);
  }
  return pc;
}

bool Continuation::is_scope_bottom(oop cont_scope, const frame& f, const RegisterMap* map) {
  if (cont_scope == NULL || !is_continuation_entry_frame(f, map))
    return false;

  assert (!map->in_cont(), "");
  // if (map->in_cont()) return false;

  oop cont = get_continuation_for_frame(map->thread(), f.sp());
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

  hframe hf = cont.last_frame<mode_preempt>(); // here mode_preempt merely makes the fewest assumptions
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
  oop cont = get_continuation_for_frame(map->thread(), callee.sp());

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
      ContinuationHelper::update_register_map_from_last_vstack_frame(map);
    } else { // if (!sender.is_interpreted_frame())
      if (is_stub(f.cb())) {
        f.oop_map()->update_register_map(&f, map); // we have callee-save registers in this case
      }
      ContinuationHelper::update_register_map(map, sender, cont);
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

int Continuation::frame_size(const frame& f, const RegisterMap* map) {
  if (map->in_cont()) {
    ContMirror cont(map);
    hframe hf = cont.from_frame(f);
    return (hf.is_interpreted_frame() ? hf.interpreted_frame_size() : hf.compiled_frame_size()) >> LogBytesPerWord;
  } else {
    assert (Continuation::is_cont_barrier_frame(f), "");
    return (f.is_interpreted_frame() ? ((address)Interpreted::frame_bottom(f) - (address)f.sp()) : NonInterpretedUnknown::size(f)) >> LogBytesPerWord;
  }
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

static int find_oop_in_interpreted_frame(const hframe& hf, int offset, const InterpreterOopMap& mask, const ContMirror& cont) {
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
  assert (map != NULL, "");
  oop cont;
  if (map->in_cont()) {
    cont = map->cont();
  } else {
    cont = get_continutation_for_frame(map->thread(), fr);
  }
  return reg_to_location(cont, fr, map, reg, is_oop);
}

address Continuation::reg_to_location(oop contOop, const frame& fr, const RegisterMap* map, VMReg reg, bool is_oop) {
  assert (map != NULL, "");
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
  // assert ((void*)Frame::map_link_address(map) == (void*)map->location(reg), "must be the link register (rbp): %s", reg->name());
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
    ? oop_address(cont.refStack(), cont.refSP(), hf.ref_sp() + find_oop_in_interpreted_frame(hf, index1, oop_mask, cont))
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
    ? oop_address(cont.refStack(), cont.refSP(), hf.ref_sp() + find_oop_in_interpreted_frame(hf, index, oop_mask, cont))
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

template <typename ConfigT>
void ContMirror::make_keepalive(CompiledMethodKeepalive<ConfigT>* keepalive) {
  Handle conth(_thread, _cont);
  int oops = keepalive->nr_oops();
  if (oops == 0) {
    oops = 1;
  }
  oop keepalive_obj = allocate_keepalive_array<ConfigT>(oops);

  uint64_t counter = SafepointSynchronize::safepoint_counter();
  // check gc cycle
  Handle keepaliveHandle = Handle(_thread, keepalive_obj);
  keepalive->set_handle(keepaliveHandle);
  // check gc cycle and maybe reload
  //if (!SafepointSynchronize::is_same_safepoint(counter)) {
    post_safepoint(conth);
  //}
}

template <typename ConfigT>
inline void ContMirror::allocate_stacks(int size, int oops, int frames) {
  bool needs_stack_allocation    = (_stack == NULL || to_index(size) > (_sp >= 0 ? _sp : _stack_length));
  bool needs_refStack_allocation = (_ref_stack == NULL || oops > _ref_sp);

  log_develop_trace(jvmcont)("stack size: %d (int): %d sp: %d stack_length: %d needs alloc: %d", size, to_index(size), _sp, _stack_length, needs_stack_allocation);
  log_develop_trace(jvmcont)("num_oops: %d ref_sp: %d needs alloc: %d", oops, _ref_sp, needs_stack_allocation);

  assert(_sp == java_lang_Continuation::sp(_cont) && _fp == java_lang_Continuation::fp(_cont) && _pc == java_lang_Continuation::pc(_cont), "");

  if (!(needs_stack_allocation | needs_refStack_allocation))
    return;

#ifdef PERFTEST
  if (PERFTEST_LEVEL < 100) {
    tty->print_cr("stack size: %d (int): %d sp: %d stack_length: %d needs alloc: %d", size, to_index(size), _sp, _stack_length, needs_stack_allocation);
    tty->print_cr("num_oops: %d ref_sp: %d needs alloc: %d", oops, _ref_sp, needs_stack_allocation);
  }
  guarantee(PERFTEST_LEVEL >= 100, "");
#endif

  if (!allocate_stacks_in_native<ConfigT>(size, oops, needs_stack_allocation, needs_refStack_allocation)) {
    allocate_stacks_in_java(size, oops, frames);
    if (!thread()->has_pending_exception()) return;
  }

  // This first assertion isn't important, as we'll overwrite the Java-computed ones, but it's just to test that the Java computation is OK.
  assert(_sp == java_lang_Continuation::sp(_cont) && _fp == java_lang_Continuation::fp(_cont) && _pc == java_lang_Continuation::pc(_cont), "");
  assert (oopDesc::equals(_stack, java_lang_Continuation::stack(_cont)), "");
  assert (_stack->base(basicElementType) == _hstack, "");
  assert (to_bytes(_stack_length) >= size && to_bytes(_sp) >= size, "stack_length: %d sp: %d size: %d", to_bytes(_stack_length), _sp, size);
  assert (to_bytes(_ref_sp) >= oops, "oops: %d ref_sp: %d refStack length: %d", oops, _ref_sp, _ref_stack->length());
}

template <typename ConfigT>
NOINLINE bool ContMirror::allocate_stacks_in_native(int size, int oops, bool needs_stack, bool needs_refstack) {
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
      if (!allocate_ref_stack<ConfigT>(oops)) {
        return false;
      }
    } else {
      if (!grow_ref_stack<ConfigT>(oops)) {
        return false;
      }
    }
    java_lang_Continuation::set_refStack(_cont, _ref_stack);

    // TODO: may not be necessary because at this point we know that the freeze will succeed and this value will get written in ::write
    java_lang_Continuation::set_refSP(_cont, _ref_sp);
  }

  return true;
}

bool ContMirror::allocate_stack(int size) {
  int elements = size >> LogBytesPerElement;
  oop result = allocate_stack_array(elements);
  if (result == NULL) {
    return false;
  }

  _stack = typeArrayOop(result);
  _sp = elements;
  _stack_length = elements;
  _hstack = (ElemType*)_stack->base(basicElementType);

  return true;
}

bool ContMirror::grow_stack(int new_size) {
  new_size = new_size >> LogBytesPerElement;

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
  ElemType* new_hstack = (ElemType*)new_stack->base(basicElementType);
  int n = old_length - offset;
  assert(new_length > n, "");
  if (n > 0) {
    copy_primitive_array(_stack, offset, new_stack, new_length - n, n);
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

template <typename ConfigT>
bool ContMirror::allocate_ref_stack(int nr_oops) {
  // we don't zero the array because we allocate an array that exactly holds all the oops we'll fill in as we freeze
  oop result = allocate_refstack_array<ConfigT>(nr_oops);
  if (result == NULL) {
    return false;
  }
  _ref_stack = objArrayOop(result);
  _ref_sp = nr_oops;

  assert (_ref_stack->length() == nr_oops, "");

  return true;
}

template <typename ConfigT>
bool ContMirror::grow_ref_stack(int nr_oops) {
  int old_length = _ref_stack->length();
  int offset = _ref_sp > 0 ? _ref_sp : old_length;
  int old_oops = old_length - offset;
  int min_length = old_oops + nr_oops;

  int new_length = ensure_capacity(old_length, min_length);
  if (new_length == -1) {
    return false;
  }

  objArrayOop new_ref_stack = allocate_refstack_array<ConfigT>(new_length);
  if (new_ref_stack == NULL) {
    return false;
  }
  assert (new_ref_stack->length() == new_length, "");
  log_develop_trace(jvmcont)("grow_ref_stack old_length: %d new_length: %d", old_length, new_length);

  zero_ref_array<ConfigT>(new_ref_stack, new_length, min_length);
  if (old_oops > 0) {
    assert(!CONT_FULL_STACK, "");
    copy_ref_array<ConfigT>(_ref_stack, offset, new_ref_stack, fix_decreasing_index(offset, old_length, new_length), old_oops);
  }

  _ref_stack = new_ref_stack;

  log_develop_trace(jvmcont)("grow_ref_stack old ref_sp: %d", _ref_sp);
  _ref_sp = fix_decreasing_index(_ref_sp, old_length, new_length);
  log_develop_trace(jvmcont)("grow_ref_stack new ref_sp: %d", _ref_sp);
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

int ContMirror::fix_decreasing_index(int index, int old_length, int new_length) {
  return new_length - (old_length - index);
}

inline void ContMirror::post_safepoint(Handle conth) {
  _cont = conth(); // reload oop
  _ref_stack = java_lang_Continuation::refStack(_cont);
  _stack = java_lang_Continuation::stack(_cont);
  _hstack = (ElemType*)_stack->base(basicElementType);
}

typeArrayOop ContMirror::allocate_stack_array(size_t elements) {
  assert(elements > 0, "");
  log_develop_trace(jvmcont)("allocate_stack_array elements: %lu", elements);

  TypeArrayKlass* klass = TypeArrayKlass::cast(Universe::intArrayKlassObj());
  size_t size_in_words = typeArrayOopDesc::object_size(klass, (int)elements);
  return typeArrayOop(raw_allocate(klass, size_in_words, elements, false));
}

void ContMirror::copy_primitive_array(typeArrayOop old_array, int old_start, typeArrayOop new_array, int new_start, int count) {
  ElemType* from = (ElemType*)old_array->base(basicElementType) + old_start;
  ElemType* to   = (ElemType*)new_array->base(basicElementType) + new_start;
  size_t size = to_bytes(count);
  memcpy(to, from, size);

  //Copy::conjoint_memory_atomic(from, to, size); // Copy::disjoint_words((HeapWord*)from, (HeapWord*)to, size/wordSize); //
  // ArrayAccess<ARRAYCOPY_DISJOINT>::oop_arraycopy(_stack, offset * elementSizeInBytes, new_stack, (new_length - n) * elementSizeInBytes, n);
}

template <typename ConfigT>
objArrayOop ContMirror::allocate_refstack_array(size_t nr_oops) {
  assert(nr_oops > 0, "");
  bool zero = !ConfigT::_post_barrier; // !BarrierSet::barrier_set()->is_a(BarrierSet::ModRef);
  log_develop_trace(jvmcont)("allocate_refstack_array nr_oops: %lu zero: %d", nr_oops, zero);

  ArrayKlass* klass = ArrayKlass::cast(Universe::objectArrayKlassObj());
  size_t size_in_words = objArrayOopDesc::object_size((int)nr_oops);
  return objArrayOop(raw_allocate(klass, size_in_words, nr_oops, zero));
}

template <typename ConfigT>
objArrayOop ContMirror::allocate_keepalive_array(size_t nr_oops) {
  //assert(nr_oops > 0, "");
  bool zero = true; // !BarrierSet::barrier_set()->is_a(BarrierSet::ModRef);
  log_develop_trace(jvmcont)("allocate_keepalive_array nr_oops: %lu zero: %d", nr_oops, zero);

  ArrayKlass* klass = ArrayKlass::cast(Universe::objectArrayKlassObj());
  size_t size_in_words = objArrayOopDesc::object_size((int)nr_oops);
  return objArrayOop(raw_allocate(klass, size_in_words, nr_oops, zero));
}


template <typename ConfigT>
void ContMirror::zero_ref_array(objArrayOop new_array, int new_length, int min_length) {
  assert (new_length == new_array->length(), "");
  int extra_oops = new_length - min_length;

  if (ConfigT::_post_barrier) {
    // zero the bottom part of the array that won't be filled in the freeze
    HeapWord* new_base = new_array->base();
    const uint OopsPerHeapWord = HeapWordSize/heapOopSize;
    assert(OopsPerHeapWord >= 1 && (HeapWordSize % heapOopSize == 0), "");
    uint word_size = ((uint)extra_oops + OopsPerHeapWord - 1)/OopsPerHeapWord;
    Copy::fill_to_aligned_words(new_base, word_size, 0); // fill_to_words (we could be filling more than the elements if narrow, but we do this before copying)
  }

  DEBUG_ONLY(for (int i=0; i<extra_oops; i++) assert(new_array->obj_at(i) == (oop)NULL, "");)
}

template <typename ConfigT>
void ContMirror::copy_ref_array(objArrayOop old_array, int old_start, objArrayOop new_array, int new_start, int count) {
  assert (new_start + count == new_array->length(), "");

  typedef typename ConfigT::OopT OopT;
  if (ConfigT::_post_barrier) {
    OopT* from = (OopT*)old_array->base() + old_start;
    OopT* to   = (OopT*)new_array->base() + new_start;
    memcpy((void*)to, (void*)from, count * sizeof(OopT));
    barrier_set_cast<ModRefBarrierSet>(BarrierSet::barrier_set())->write_ref_array((HeapWord*)to, count);
  } else {
    // Requires the array is zeroed (see G1BarrierSet::write_ref_array_pre_work)
    DEBUG_ONLY(for (int i=0; i<count; i++) assert(new_array->obj_at(new_start + i) == (oop)NULL, "");)
    size_t src_offset = (size_t) objArrayOopDesc::obj_at_offset<OopT>(old_start);
    size_t dst_offset = (size_t) objArrayOopDesc::obj_at_offset<OopT>(new_start);
    ArrayAccess<ARRAYCOPY_DISJOINT>::oop_arraycopy(old_array, src_offset, new_array, dst_offset, count);

    // for (int i=0, old_i = old_start, new_i = new_start; i < count; i++, old_i++, new_i++) new_array->obj_at_put(new_i, old_array->obj_at(old_i));
  }
}

/* try to allocate an array from the tlab, if it doesn't work allocate one using the allocate
 * method. In the later case we might have done a safepoint and need to reload our oops */
oop ContMirror::raw_allocate(Klass* klass, size_t size_in_words, size_t elements, bool zero) {
  ObjArrayAllocator allocator(klass, size_in_words, (int)elements, zero, _thread);
  HeapWord* start = _thread->tlab().allocate(size_in_words);
  if (start != NULL) {
    return allocator.initialize(start);
  } else {
    //HandleMark hm(_thread);
    Handle conth(_thread, _cont);
    uint64_t counter = SafepointSynchronize::safepoint_counter();
    oop result = allocator.allocate(/* use_tlab */ false);
    //if (!SafepointSynchronize::is_same_safepoint(counter)) {
      post_safepoint(conth);
    //}
    return result;
  }
}

NOINLINE void ContMirror::allocate_stacks_in_java(int size, int oops, int frames) {
  guarantee (false, "unreachable");
  int old_stack_length = _stack_length;

  //HandleMark hm(_thread);
  Handle conth(_thread, _cont);
  JavaCallArguments args;
  args.push_oop(conth);
  args.push_int(size);
  args.push_int(oops);
  args.push_int(frames);
  JavaValue result(T_VOID);
  JavaCalls::call_virtual(&result, SystemDictionary::Continuation_klass(), vmSymbols::getStacks_name(), vmSymbols::continuationGetStacks_signature(), &args, _thread);
  post_safepoint(conth); // reload oop after java call

  _sp     = java_lang_Continuation::sp(_cont);
  _fp     = java_lang_Continuation::fp(_cont);
  _ref_sp = java_lang_Continuation::refSP(_cont);
  _stack_length = _stack->length();
  /* We probably should handle OOM? */
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

#ifdef CONT_DOUBLE_NOP
template<op_mode mode>
static inline CachedCompiledMetadata cached_metadata(const hframe& hf) {
  return ContinuationHelper::cached_metadata<mode>(hf);
}
#endif

/* This is hopefully only temporary, currently only G1 has support for making the weak
 * keepalive OOPs strong while their nmethods are on the stack. */
class HandleKeepalive {
public:
  typedef Handle TypeT;

  static Handle make_keepalive(JavaThread* thread, oop* keepalive) {
    return Handle(thread, WeakHandle<vm_nmethod_keepalive_data>::from_raw(keepalive).resolve());
  }

  static oop read_keepalive(Handle obj) {
    return obj();
  }
};

class NoKeepalive {
public:
  typedef oop* TypeT;

  static oop* make_keepalive(JavaThread* thread, oop* keepalive) {
    return keepalive;
  }

  static oop read_keepalive(oop* keepalive) {
    return WeakHandle<vm_nmethod_keepalive_data>::from_raw(keepalive).resolve();
  }
};

template <bool compressed_oops, bool post_barrier, bool gen_stubs, bool g1gc>
class Config {
public:
  typedef Config<compressed_oops, post_barrier, gen_stubs, g1gc> SelfT;
  typedef typename Conditional<compressed_oops, narrowOop, oop>::type OopT;
  typedef typename Conditional<post_barrier, RawOopWriter<SelfT>, NormalOopWriter<SelfT> >::type OopWriterT;
  typedef typename Conditional<g1gc, NoKeepalive, HandleKeepalive>::type KeepaliveObjectT;

  static const bool _compressed_oops = compressed_oops;
  static const bool _post_barrier = post_barrier;
  static const bool allow_stubs = gen_stubs && post_barrier && compressed_oops;

  template<op_mode mode>
  static freeze_result freeze(JavaThread* thread, ContMirror& cont, FrameInfo* fi) {
    return Freeze<SelfT, mode>(thread, cont).freeze(fi);
  }

  template<op_mode mode>
  static bool thaw(JavaThread* thread, ContMirror& cont, FrameInfo* fi, int num_frames) {
    return Thaw<SelfT, mode>(thread, cont).thaw(fi, num_frames);
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
    LoomGenCode 
      ? resolve_g1<use_compressed, is_modref, true>()
      : resolve_g1<use_compressed, is_modref, false>();
  } 

  template <bool use_compressed, bool is_modref, bool gencode>
  static void resolve_g1() {
    UseG1GC && UseContinuationStrong
      ? resolve<use_compressed, is_modref, gencode, true>()
      : resolve<use_compressed, is_modref, gencode, false>();
  }

  template <bool use_compressed, bool is_modref, bool gencode, bool g1gc>
  static void resolve() {
    // tty->print_cr(">>> ConfigResolve::resolve use_compressed: %d is_modref: %d gen_code:%d", use_compressed, is_modref, gen_code);

    cont_freeze_fast    = Config<use_compressed, is_modref, gencode, g1gc>::template freeze<mode_fast>;
    cont_freeze_slow    = Config<use_compressed, is_modref, gencode, g1gc>::template freeze<mode_slow>;
    cont_freeze_preempt = Config<use_compressed, is_modref, gencode, g1gc>::template freeze<mode_preempt>;

    cont_thaw_fast    = Config<use_compressed, is_modref, gencode, g1gc>::template thaw<mode_fast>;
    cont_thaw_slow    = Config<use_compressed, is_modref, gencode, g1gc>::template thaw<mode_slow>;
    cont_thaw_preempt = Config<use_compressed, is_modref, gencode, g1gc>::template thaw<mode_preempt>;
  }
};

void Continuations::init() {
  ConfigResolve::resolve();
  OopMapStubGenerator::init();
  Continuation::init();
}

void Continuation::init() {
  _weak_handles = new OopStorage("Continuation NMethodKeepalive weak",
      NMethodKeepaliveAlloc_lock,
      NMethodKeepaliveActive_lock);
}

class KeepaliveCleanupClosure : public ThreadClosure {
private:
  int _count;
public:
  KeepaliveCleanupClosure() : _count(0) {}

  int count() const { return _count; }

  virtual void do_thread(Thread* thread) {
    JavaThread* jthread = (JavaThread*) thread;
    GrowableArray<WeakHandle<vm_nmethod_keepalive_data> >* cleanup_list = jthread->keepalive_cleanup();
    int len = cleanup_list->length();
    _count += len;
    for (int i = 0; i < len; ++i) {
      WeakHandle<vm_nmethod_keepalive_data> ref = cleanup_list->at(i);
      ref.release();
    }

    cleanup_list->clear();
    assert(cleanup_list->length() == 0, "should be clean");
  }
};

void Continuations::cleanup_keepalives() {
  KeepaliveCleanupClosure closure;
  Threads::java_threads_do(&closure);
  //log_info(jvmcont)("cleanup %d refs", closure.count());
}

volatile intptr_t Continuations::_exploded_miss = 0;
volatile intptr_t Continuations::_exploded_hit = 0;
volatile intptr_t Continuations::_nmethod_miss = 0;
volatile intptr_t Continuations::_nmethod_hit = 0;

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

void Continuation::nmethod_patched(nmethod* nm) {
  //log_info(jvmcont)("nmethod patched %p", nm);
  oop* keepalive = nm->get_keepalive();
  if (keepalive == NULL) {
    return;
  }
  WeakHandle<vm_nmethod_keepalive_data> wh = WeakHandle<vm_nmethod_keepalive_data>::from_raw(keepalive);
  oop resolved = wh.resolve();
#ifdef DEBUG
  Universe::heap()->is_in_or_null(resolved);
#endif

#ifndef PRODUCT
  CountOops count;
  nm->oops_do(&count, false, true);
  assert(nm->nr_oops() >= count.nr_oops(), "should be");
#endif

  if (resolved == NULL) {
    return;
  }

  if (UseCompressedOops) {
    PersistOops<narrowOop> persist(nm->nr_oops(), (objArrayOop) resolved);
    nm->oops_do(&persist);
  } else {
    PersistOops<oop> persist(nm->nr_oops(), (objArrayOop) resolved);;
    nm->oops_do(&persist);
  }
}

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

static jlong java_tid(JavaThread* thread) {
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
  ResourceMark rm;
  HandleMark hm;
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

template <typename FrameT>
static CodeBlob* slow_get_cb(const FrameT& f) {
  assert (!f.is_interpreted_frame(), "");
  CodeBlob* cb = f.cb();
  if (cb == NULL) {
    cb = CodeCache::find_blob(f.pc());
  }
  assert (cb != NULL, "");
  return cb;
}

template <typename FrameT>
static const ImmutableOopMap* slow_get_oopmap(const FrameT& f) {
  const ImmutableOopMap* oopmap = f.oop_map();
  if (oopmap == NULL) {
    oopmap = OopMapSet::find_map(slow_get_cb(f), f.pc());
  }
  assert (oopmap != NULL, "");
  return oopmap;
}

template <typename FrameT>
static int slow_size(const FrameT& f) { 
  return slow_get_cb(f)->frame_size() * wordSize; 
}

template <typename FrameT>
static address slow_return_pc(const FrameT& f) { 
  return *slow_return_pc_address<NonInterpretedUnknown>(f); 
}

template <typename FrameT>
static int slow_stack_argsize(const FrameT& f) { 
  CodeBlob* cb = slow_get_cb(f);
  assert (cb->is_compiled(), "");
  return cb->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size; 
}

template <typename FrameT>
static int slow_num_oops(const FrameT& f) { 
  return slow_get_oopmap(f)->num_oops(); 
}

static void print_blob(outputStream* st, address addr) {
  CodeBlob* b = CodeCache::find_blob_unsafe(addr);
  st->print("address: " INTPTR_FORMAT " blob: ", p2i(addr));
  if (b != NULL) {
    b->dump_for_addr(addr, st, false);
  } else {
    st->print_cr("NULL");
  }
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
