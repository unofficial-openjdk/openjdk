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
#include "jfr/jfrEvents.hpp"
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
#include "runtime/vframe_hp.hpp"
#include "utilities/copy.hpp"
#include "utilities/exceptions.hpp"
#include "utilities/macros.hpp"

// #undef ASSERT
// #undef assert
// #define assert(p, ...)

#define USE_GROWABLE_ARRAY false

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
static void print_vframe(frame f, RegisterMap* map = NULL, outputStream* st = tty);
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

class ContinuationCodeBlobLookup : public CodeBlobLookup {
public:
  virtual CodeBlob* find_blob(address pc) const {
    if (UseNewCode2) {
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
        //log_info(jvmcont)("found nop, cb @ %p - %s", cb, patched ? "patched" : "existing");
        assert(cb != NULL, "must be");
        //assert(cb == CodeCache::find_blob(pc), "double check");
        return cb;
      } else {
        CodeBlob* cb = CodeCache::find_blob(pc);
        if (cb->is_nmethod()) {
          Continuations::nmethod_miss();
        }
        //log_info(jvmcont)("failed to find nop in cb %p, %d, %d", cb, cb->is_compiled_by_c1(), cb->is_compiled_by_c2());
        return cb;
      }
    } else {
      return CodeCache::find_blob(pc);
    }

  }
};

class ContMirror;

// Represents a stack frame on the horizontal stack, analogous to the frame class, for vertical-stack frames.
class hframe {
private:
  bool _write;
  int _sp;
  long _fp;
  address _pc;
  bool _is_interpreted;
  CodeBlob* _cb;
  int _length;

  friend class ContMirror;
private:
  inline HFrameMetadata* meta(ContMirror& cont);
  inline intptr_t* real_fp(ContMirror& cont);
  inline int real_fp_index(ContMirror& cont);
  inline int link_index(ContMirror& cont);
  inline address* return_pc_address(ContMirror& cont);

public:
  hframe() : _write(false), _length(0), _sp(-1), _fp(0), _pc(NULL), _is_interpreted(true), _cb(NULL) {}
  hframe(const hframe& hf) : _write(hf._write), _length(hf._length),
                             _sp(hf._sp), _fp(hf._fp), _pc(hf._pc), _is_interpreted(hf._is_interpreted), _cb(hf._cb) {}

  hframe(int sp, long fp, address pc, bool write, int length)
    : _write(write), _length(length), _sp(sp), _fp(fp), _pc(pc), _is_interpreted(Interpreter::contains(pc)) {
      _cb = NULL;
      assert (write  || length > 0, "");
      assert (!write || length == 0, "");
    }
  hframe(int sp, long fp, address pc, CodeBlob* cb, bool is_interpreted, bool write, int length)
    : _write(write), _length(length), _sp(sp), _fp(fp), _pc(pc), _cb(cb), _is_interpreted(is_interpreted) {
      assert (write  || length > 0, "");
      assert (!write || length == 0, "");
    }
  hframe(int sp, long fp, address pc, bool is_interpreted, bool write, int length)
    : _write(write), _length(length), _sp(sp), _fp(fp), _pc(pc), _is_interpreted(is_interpreted) {
      _cb = NULL;
      assert (write  || length > 0, "");
      assert (!write || length == 0, "");
    }

  bool operator==(const hframe& other) { return _write == other._write && _sp == other._sp && _fp == other._fp && _pc == other._pc; }
  bool is_empty() { return _pc == NULL && _sp < 0; }

  inline bool is_interpreted_frame() { return _is_interpreted; }
  inline int       sp() { return _sp; }
  inline long      fp() { return _fp; }
  inline address   pc() { return _pc; }
  CodeBlob* cb();

  inline void set_fp(long fp) { _fp = fp; }

  inline bool write() { return _write; }

  size_t size(ContMirror& cont)              { return meta(cont)->frame_size; }
  size_t uncompressed_size(ContMirror& cont) { return meta(cont)->uncompressed_size; }
  int num_oops(ContMirror& cont)             { return meta(cont)->num_oops; }

  void set_size(ContMirror& cont, size_t size)              { assert(size < 0xffff, ""); meta(cont)->frame_size = size; }
  void set_num_oops(ContMirror& cont, int num)              { assert(num  < 0xffff, ""); meta(cont)->num_oops = num; }
  void set_uncompressed_size(ContMirror& cont, size_t size) { assert(size < 0xffff, ""); meta(cont)->uncompressed_size = size; }

  // the link is an offset from the real fp to the sender's fp IFF the sender is interpreted
  // otherwise, it's the contents of the rbp register
  inline long* link_address(ContMirror& cont);
  inline long link(ContMirror& cont)         { return *link_address(cont); }
  inline address return_pc(ContMirror& cont) { return *return_pc_address(cont); }

  hframe sender(ContMirror& cont);

  inline void patch_link(ContMirror& cont, long value) { *link_address(cont) = value; }
  inline void patch_link_relative(ContMirror& cont, intptr_t* fp);
  inline void patch_callee(ContMirror& cont, hframe& sender);

  inline void patch_return_pc(ContMirror& cont, address value) { *return_pc_address(cont) = value; }
  inline void patch_real_fp_offset(ContMirror& cont, int offset, intptr_t value) { *(link_address(cont) + offset) = value; }
  inline intptr_t* get_real_fp_offset(ContMirror& cont, int offset) { return (intptr_t*)*(link_address(cont) + offset); }
  inline void patch_real_fp_offset_relative(ContMirror& cont, int offset, intptr_t* value);

  bool is_bottom(ContMirror& cont);

  inline intptr_t* index_address(ContMirror& cont, int i);

  Method* method(ContMirror& cont);

  void print_on(ContMirror& cont, outputStream* st);
  void print(ContMirror& cont) { print_on(cont, tty); }
  void print_on(outputStream* st);
  void print() { print_on(tty); }
};

// freeze result
enum res_freeze {
  freeze_ok = 0,
  freeze_pinned_native = 1,
  freeze_pinned_monitor = 2,
  freeze_exception = 3
};

struct oopLoc {
  bool narrow  : 1;
  unsigned long loc : 63;
};

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

  int* _write_stack;
  int _wstack_length;
  int _wsp; // traditional indexing. increases, equals number of cells written

  size_t _max_size;

  int _ref_sp;
  objArrayOop  _ref_stack;
  int _wref_start;
  int _num_oops;

// #if USE_GROWABLE_ARRAY
//   GrowableArray<oopLoc>* _oops;
// #else
//   oopLoc* _oops;
//   int _num_oops;
// #endif

  unsigned char _flags;

  short _num_interpreted_frames;
  short _num_frames;

  // Profiling data for the JFR event
  short _e_num_interpreted_frames;
  short _e_num_frames;
  short _e_num_refs;
  short _e_size;

  ContMirror(const ContMirror& cont); // no copy constructor

  int* stack() { return _hstack; }

  void allocate_stacks(int size, int oops, int frames);
  inline intptr_t* write_stack_address(int i);
  inline int write_stack_index(void* p);
  inline int fix_write_index_after_write(int index);
  inline int fix_index_after_write(int index, int old_length, int new_length);

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
    log_trace(jvmcont)("set_entryPC %p", pc);
    _entryPC = pc;
  }

  int sp()                 { return _sp; }
  long fp()                { return _fp; }
  address pc()             { return _pc; }

  void set_sp(int sp)      { _sp = sp;   }
  void set_fp(long fp)     { _fp = fp;   }
  void set_pc(address pc)  { _pc = pc; set_flag(FLAG_LAST_FRAME_INTERPRETED, Interpreter::contains(pc));  }

  bool is_flag(unsigned char flag) { return (_flags & flag) != 0; }
  void set_flag(unsigned char flag, bool v) { _flags = (v ? _flags |= flag : _flags &= ~flag); }

  int stack_length() { return _stack_length; }

  JavaThread* thread() { return _thread; }

  inline bool in_writestack(void *p) { return (_write_stack != NULL && p >= _write_stack && p < (_write_stack + _wstack_length)); }
  inline bool in_hstack(void *p) { return (_hstack != NULL && p >= _hstack && p < (_hstack + _stack_length)); }

  void copy_to_stack(void* from, void* to, int size);
  void copy_from_stack(void* from, void* to, int size);

  objArrayOop  refStack(int size);
  objArrayOop refStack() { return _ref_stack; }
  int refSP() { return _ref_sp; }
  void set_refSP(int refSP) { log_trace(jvmcont)("set_refSP: %d", refSP); _ref_sp = refSP; }

  typeArrayOop stack(int size);
  inline bool in_stack(void *p) { return in_hstack(p) || in_writestack(p); }
  inline int stack_index(void* p);
  inline intptr_t* stack_address(int i);
  inline intptr_t* stack_address(int i, bool write);

  // void call_pinned(res_freeze res, frame& f);

  void update_register_map(RegisterMap& map);
  bool is_map_at_top(RegisterMap& map);

  bool is_empty();
  hframe new_hframe(intptr_t* hsp, intptr_t* hfp, address pc, CodeBlob* cb, bool is_interpreted);
  hframe last_frame();
  inline void set_last_frame(hframe& f);

  void init_write_arrays(int size, int noops, int nframes);
  address freeze_target();
  inline hframe fix_hframe_afer_write(hframe& hf);
  void commit_stacks();
  void revert(hframe& new_top);

  inline void add_oop(oop obj);
  // inline void add_oop_location(oop* p);
  // inline void add_oop_location(narrowOop* p);

  inline oop obj_at(int i);
  int num_oops();
  void null_ref_stack(int start, int num);

  inline size_t max_size() { return _max_size; }
  inline void add_size(size_t s) { log_trace(jvmcont)("add max_size: %lu s: %lu", _max_size + s, s);
                                   _max_size += s; }
  inline void sub_size(size_t s) { log_trace(jvmcont)("sub max_size: %lu s: %lu", _max_size - s, s);
                                   assert(s <= _max_size, "s: %lu max_size: %lu", s, _max_size);
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

void hframe::print_on(outputStream* st) {
  if (is_empty()) {
    st->print_cr("\tempty");
  } else if (_is_interpreted) {
    st->print_cr("\tInterpreted sp: %d fp: %ld pc: %p", _sp, _fp, _pc);
  } else {
    st->print_cr("\tCompiled sp: %d fp: 0x%lx pc: %p", _sp, _fp, _pc);
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
    st->print_cr("\tmethod: %p (at %p)", method, method_addr);
    st->print("\tmethod: "); method->print_short_name(st); st->cr();

    st->print_cr("\tissp: %ld",             *(long*) (fp + frame::interpreter_frame_sender_sp_offset));
    st->print_cr("\tlast_sp: %ld",          *(long*) (fp + frame::interpreter_frame_last_sp_offset));
    st->print_cr("\tinitial_sp: %ld",       *(long*) (fp + frame::interpreter_frame_initial_sp_offset));
    // st->print_cr("\tmon_block_top: %ld",    *(long*) (fp + frame::interpreter_frame_monitor_block_top_offset));
    // st->print_cr("\tmon_block_bottom: %ld", *(long*) (fp + frame::interpreter_frame_monitor_block_bottom_offset));
    st->print_cr("\tlocals: %ld",           *(long*) (fp + frame::interpreter_frame_locals_offset));
    st->print_cr("\tcache: %p",             *(void**)(fp + frame::interpreter_frame_cache_offset));
    st->print_cr("\tbcp: %p",               *(void**)(fp + frame::interpreter_frame_bcp_offset));
    st->print_cr("\tbci: %d",               method->bci_from(*(address*)(fp + frame::interpreter_frame_bcp_offset)));
    st->print_cr("\tmirror: %p",            *(void**)(fp + frame::interpreter_frame_mirror_offset));
    // st->print("\tmirror: "); os::print_location(st, *(intptr_t*)(fp + frame::interpreter_frame_mirror_offset), true);
  } else {
    st->print_cr("\tcb: %p", cb());
    if (_cb != NULL) {
      st->print("\tcb: "); _cb->print_value_on(st); st->cr();
      st->print_cr("\tcb.frame_size: %d", _cb->frame_size());
    }
  }
  st->print_cr("\tlink: 0x%lx %ld (at: %p)", link(cont), link(cont), link_address(cont));
  st->print_cr("\treturn_pc: %p (at %p)", return_pc(cont), return_pc_address(cont));

  if (false) {
    address sp = (address)index_address(cont, _sp);
    st->print_cr("--data--");
    int fsize = meta(cont)->frame_size;
    for(int i=0; i < fsize; i++)
      st->print_cr("%p: %x", (sp + i), *(sp + i));
    st->print_cr("--end data--");
  }
}

inline intptr_t* hframe::index_address(ContMirror& cont, int i) {
  assert (_length == (_write ? 0 : cont.stack_length()), "write: %d length: %d cont.stack_length: %d", _write, _length, cont.stack_length());
  return (intptr_t*)cont.stack_address(i, _write);
}

inline HFrameMetadata* hframe::meta(ContMirror& cont) {
  return (HFrameMetadata*)index_address(cont, _sp - to_index(METADATA_SIZE));
}

bool hframe::is_bottom(ContMirror& cont) {
  assert (!_write, "");
  return _sp + to_index(size(cont) + METADATA_SIZE) >= cont.stack_length();
}

CodeBlob* hframe::cb() {
  if (_cb == NULL && !_is_interpreted) {// compute lazily
    ContinuationCodeBlobLookup lookup;
    _cb = lookup.find_blob(_pc);
    assert(_cb != NULL, "must be valid");
  }
  return _cb;
}

inline intptr_t* hframe::real_fp(ContMirror& cont) {
  assert (!_is_interpreted, "interpreted");
  return index_address(cont, _sp) + cb()->frame_size();
}

inline int hframe::real_fp_index(ContMirror& cont) {
  assert (!_is_interpreted, "interpreted");
  assert (_length == cont.stack_length(), "");
  return _sp + to_index(cb()->frame_size() * sizeof(intptr_t));
}

inline long* hframe::link_address(ContMirror& cont) {
  return _is_interpreted
    ? (long*)&index_address(cont, _fp)[frame::link_offset]
    : (long*)(real_fp(cont) - frame::sender_sp_offset); // x86-specific
}

inline int hframe::link_index(ContMirror& cont) {
  return _is_interpreted ? _fp : (real_fp_index(cont) - to_index(frame::sender_sp_offset * sizeof(intptr_t*))); // x86-specific
}

inline address* hframe::return_pc_address(ContMirror& cont) {
  return _is_interpreted
    ? (address*)&index_address(cont, _fp)[frame::return_addr_offset]
    : (address*)(real_fp(cont) - 1); // x86-specific
}

inline void hframe::patch_real_fp_offset_relative(ContMirror& cont, int offset, intptr_t* value) {
  long* la = (long*)((_is_interpreted ? index_address(cont, _fp) : real_fp(cont)) + offset);
  *la = to_index((address)value - (address)la);
  log_trace(jvmcont)("patched relative offset: %d value: %p", offset, value);
}

inline void hframe::patch_link_relative(ContMirror& cont, intptr_t* fp) {
  long* la = link_address(cont);
  *la = to_index((address)fp - (address)la);
  log_trace(jvmcont)("patched link: %ld", *la);
}

inline void hframe::patch_callee(ContMirror& cont, hframe& sender) {
  assert (_write == sender._write, "");
  if (sender.is_interpreted_frame()) {
    patch_link_relative(cont, sender.link_address(cont));
  } else {
    patch_link(cont, sender.fp());
  }
  if (is_interpreted_frame()) {
    patch_real_fp_offset_relative(cont, frame::interpreter_frame_sender_sp_offset, index_address(cont, sender.sp()));
  }
}

hframe hframe::sender(ContMirror& cont) {
  assert (!_write, "");
  assert (_length == cont.stack_length(), "");
  address sender_pc = return_pc(cont);
  bool is_sender_interpreted = Interpreter::contains(sender_pc);
  int sender_sp = _sp + to_index(size(cont) + METADATA_SIZE);
  long sender_fp = link(cont);
  // log_trace(jvmcont)("hframe::sender sender_fp0: %ld", sender_fp);
  // if (log_is_enabled(Trace, jvmcont)) print_on(cont, tty);
  if (is_sender_interpreted) {
    sender_fp += link_index(cont);
    // log_trace(jvmcont)("hframe::sender real_fp: %d sender_fp: %ld", link_index(cont), sender_fp);
  }
  if (sender_sp >= cont.stack_length())
    return hframe();
  return hframe(sender_sp, sender_fp, sender_pc, is_sender_interpreted, _write, _length);
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
  _cont = cont;
  _stack     = NULL;
  _hstack    = NULL;
  _ref_stack = NULL;
  _stack_length = 0;

  // _oops = NULL;
// #if !USE_GROWABLE_ARRAY
//   _num_oops = 0;
// #endif
  _num_oops = 0;
  _wref_start = -1;
  _write_stack = NULL;
  _wstack_length = 0;
  _wsp = 0;
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

  _entrySP = (intptr_t*) java_lang_Continuation::entrySP(_cont);
  _entryFP = NULL;
  _entryPC = (address) java_lang_Continuation::entryPC(_cont);
  log_trace(jvmcont)("set_entryPC Z %p", _entryPC);
  log_trace(jvmcont)("\tentrySP: %p entryFP: %p entryPC: %p", _entrySP, _entryFP, _entryPC);

  _sp = java_lang_Continuation::sp(_cont);
  _fp = java_lang_Continuation::fp(_cont);
  _pc = (address)java_lang_Continuation::pc(_cont);
  log_trace(jvmcont)("\tsp: %d fp: %ld 0x%lx pc: %p", _sp, _fp, _fp, _pc);

  _stack = java_lang_Continuation::stack(_cont);
  if (_stack != NULL) {
    _stack_length = _stack->length();
    _hstack = (int*)_stack->base(T_INT);
  } else {
    _stack_length = 0;
    _hstack = NULL;
  }
  _max_size = java_lang_Continuation::maxSize(_cont);
  log_trace(jvmcont)("\tstack: %p hstack: %p, stack_length: %d max_size: %lu", (oopDesc*)_stack, _hstack, _stack_length, _max_size);

  _ref_stack = java_lang_Continuation::refStack(_cont);
  _ref_sp = java_lang_Continuation::refSP(_cont);
  log_trace(jvmcont)("\tref_stack: %p ref_sp: %d", (oopDesc*)_ref_stack, _ref_sp);

  _flags = java_lang_Continuation::flags(_cont);
  log_trace(jvmcont)("\tflags: %d", _flags);

  _num_frames = java_lang_Continuation::numFrames(_cont);
  log_trace(jvmcont)("\tnum_frames: %d", _num_frames);

  _num_interpreted_frames = java_lang_Continuation::numInterpretedFrames(_cont);
  log_trace(jvmcont)("\tnum_interpreted_frames: %d", _num_interpreted_frames);
}

void ContMirror::write() {
  log_trace(jvmcont)("Writing continuation object:");

  log_trace(jvmcont)("\tsp: %d fp: %ld 0x%lx pc: %p", _sp, _fp, _fp, _pc);
  java_lang_Continuation::set_sp(_cont, _sp);
  java_lang_Continuation::set_fp(_cont, _fp);
  java_lang_Continuation::set_pc(_cont, _pc);

  log_trace(jvmcont)("WRITE set_entryPC: %p", _entryPC);
  java_lang_Continuation::set_entrySP(_cont, _entrySP);
  // java_lang_Continuation::set_entryFP(_cont, _entryFP);
  java_lang_Continuation::set_entryPC(_cont, _entryPC);

  commit_stacks();

  log_trace(jvmcont)("\tmax_size: %lu", _max_size);
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
  return is_empty() ? hframe() : hframe(_sp, _fp, _pc, false, _stack_length);
}

inline void ContMirror::set_last_frame(hframe& f) {
  assert (f._length = _stack_length, "");
  set_sp(f.sp()); set_fp(f.fp()); set_pc(f.pc());
  log_trace(jvmcont)("set_last_frame cont sp: %d fp: 0x%lx pc: %p interpreted: %d flag: %d", sp(), fp(), pc(), f.is_interpreted_frame(), is_flag(FLAG_LAST_FRAME_INTERPRETED));
  if (log_is_enabled(Trace, jvmcont)) f.print_on(*this, tty);
  if (is_empty()) {
    if (_stack_length > 0)
      set_sp(_stack_length + to_index(METADATA_SIZE));
    set_fp(0);
    set_pc(NULL);
  }
}

inline int ContMirror::stack_index(void* p) {
  int i = to_index(stack(), p);
  assert (i >= 0 && i < stack_length(), "i: %d length: %d", i, stack_length());
  return i;
}

inline intptr_t* ContMirror::stack_address(int i) {
  assert (i >= 0 && i < stack_length(), "i: %d length: %d", i, stack_length());
  return (intptr_t*)&stack()[i];
}

inline int ContMirror::write_stack_index(void* p) {
  assert (_write_stack != NULL, "");
  int i = to_index(_write_stack, p);
  assert (i >= 0 && i < _wstack_length, "i: %d length: %d", i, _wstack_length);
  return i;
}

inline intptr_t* ContMirror::write_stack_address(int i) {
  assert (_write_stack != NULL, "");
  assert (i >= 0 && i < _wstack_length, "i: %d length: %d", i, _wstack_length);
  return (intptr_t*)&_write_stack[i];
}

inline intptr_t* ContMirror::stack_address(int i, bool write) {
  return write ? write_stack_address(i) : stack_address(i);
}

void ContMirror::copy_to_stack(void* from, void* to, int size) {
  log_trace(jvmcont)("Copying from v: %p - %p (%d bytes)", from, (address)from + size, size);
  log_trace(jvmcont)("Copying to h: %p - %p (%d - %d)", to, (address)to + size, to_index(_write_stack, to), to_index(_write_stack, (address)to + size));

  assert (size > 0, "size: %d", size);
  assert (write_stack_index(to) >= 0, "");
  assert (to_index(_write_stack, (address)to + size) <= _wstack_length, "");

  // this assertion is just to check whether the copying happens as intended, but not otherwise required for this method.
  assert (write_stack_index(to) == _wsp + to_index(METADATA_SIZE), "to: %d wsp: %d", write_stack_index(to), _wsp);

  Copy::conjoint_memory_atomic(from, to, size);
  _wsp = to_index(_write_stack, (address)to + size);

  _e_size += size;
}

void ContMirror::copy_from_stack(void* from, void* to, int size) {
  log_trace(jvmcont)("Copying from h: %p - %p (%d - %d)", from, (address)from + size, to_index(stack(), from), to_index(stack(), (address)from + size));
  log_trace(jvmcont)("Copying to v: %p - %p (%d bytes)", to, (address)to + size, size);

  assert (size > 0, "size: %d", size);
  assert (stack_index(from) >= 0, "");
  assert (to_index(stack(), (address)from + size) <= stack_length(), "");

  Copy::conjoint_memory_atomic(from, to, size);

  _e_size += size;
}

void ContMirror::init_write_arrays(int size, int noops, int nframes) {
  // size += 8; // due to overlap of bottom interpreted frame with entry frame ????
  allocate_stacks(size, noops, nframes);

  _wstack_length = _sp - to_index(METADATA_SIZE);
  _write_stack = _hstack;
  _wsp = _wstack_length - to_index(size);
  assert (_wsp >= 0, "");

  _wref_start = _ref_sp - noops;
  _num_oops = 0;

//   _wstack_length = to_index(size);
//   _write_stack = NEW_RESOURCE_ARRAY(int, _wstack_length);
//   _wsp = 0;
// #if USE_GROWABLE_ARRAY
//   _oops = new GrowableArray<oopLoc>();
// #else
//   _oops = NEW_RESOURCE_ARRAY(oopLoc, size / 8);
//   _num_oops = 0;
// #endif
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

  log_trace(jvmcont)("allocating stacks");

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
  _stack_length = _stack->length();
  _hstack = (int*)_stack->base(T_INT);

  _sp = (old_stack_length <= 0 || _sp < 0) ? _stack_length + to_index(METADATA_SIZE) : _stack_length - (old_stack_length - _sp);
  if (Interpreter::contains(_pc)) // only interpreter frames use relative (index) fp
    _fp = _stack_length - (old_stack_length - _fp);

  // These assertions aren't important, as we'll overwrite the Java-computed ones, but they're just to test that the Java computation is OK.
  assert(_pc == java_lang_Continuation::pc(_cont), "_pc: %p  this.pc: %p",  _pc, java_lang_Continuation::pc(_cont));
  assert(_sp == java_lang_Continuation::sp(_cont), "_sp: %d  this.sp: %d",  _sp, java_lang_Continuation::sp(_cont));
  assert(_fp == java_lang_Continuation::fp(_cont), "_fp: %ld this.fp: %ld %d %d", _fp, java_lang_Continuation::fp(_cont), Interpreter::contains(_pc), is_flag(FLAG_LAST_FRAME_INTERPRETED));

  log_trace(jvmcont)("sp: %d stack_length: %d", _sp, _stack_length);

  _ref_stack = java_lang_Continuation::refStack(_cont);
  _ref_sp    = java_lang_Continuation::refSP(_cont);

  log_trace(jvmcont)("ref_sp: %d refStack length: %d", _ref_sp, _ref_stack->length());

  if (!thread()->has_pending_exception()) return;

  assert (to_bytes(_stack_length) >= size, "sanity check: stack_size: %d size: %d", to_bytes(_stack_length), size);
  assert (to_bytes(_sp) - (int)METADATA_SIZE >= size, "sanity check");
  assert (to_bytes(_ref_sp) >= oops, "oops: %d ref_sp: %d refStack length: %d", oops, _ref_sp, _ref_stack->length());
}

void ContMirror::commit_stacks() {
  if (_write_stack == NULL) {
    // assert(_oops == NULL, "");
    assert(_wref_start == -1, "");
    return;
  }

  log_trace(jvmcont)("Committing stacks");

  assert (_wref_start + _num_oops == _ref_sp, "wref_start + num_oops: %d ref_sp: %d", _wref_start + _num_oops, _ref_sp);
  _write_stack = NULL;
  _ref_sp = _wref_start;
  _wref_start = -1;

  assert (_wsp == _sp - to_index(METADATA_SIZE), "wsp: %d sp - to_index(METADATA_SIZE): %d", _wsp, _sp - to_index(METADATA_SIZE));
// #if USE_GROWABLE_ARRAY
//   int num_oops = _oops->length();
// #else
//   int num_oops = _num_oops;
// #endif
//   int size = to_bytes(_wsp);

//   allocate_stacks(size, num_oops, 0);
//   if (thread()->has_pending_exception()) return;

//   address to = (address)stack_address(_sp - to_index(METADATA_SIZE) - _wsp);
//   log_trace(jvmcont)("Copying %d bytes", size);
//   log_trace(jvmcont)("Copying to h: %p - %p (%d - %d)", to, to + size, to_index(stack(), to), to_index(stack(), to + size));

//   Copy::conjoint_memory_atomic(_write_stack, to, size);

//   // delete _write_stack;
//   _write_stack = NULL;

//   log_trace(jvmcont)("Copying %d oops", num_oops);
//   for (int i = 0; i < num_oops; i++) {
// #if USE_GROWABLE_ARRAY
//     oopLoc ol = _oops->at(i);
// #else
//     oopLoc ol = _oops[i];
// #endif
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
}

void ContMirror::revert(hframe& new_top) {
  // cleanup oops
  assert (_wref_start + _num_oops <= _ref_sp, "");
  null_ref_stack(_wref_start, _num_oops);
  _num_oops = 0;

  // cleanup nmethods
  for (hframe hf = new_top; !hf.is_empty() && hf.sp() > _sp; hf = hf.sender(*this)) {
    if (!hf.is_interpreted_frame())
      hf.cb()->as_compiled_method()->dec_on_continuation_stack();
  }
}

void ContMirror::cleanup() {
  // cleanup nmethods
  for (hframe hf = last_frame(); !hf.is_empty(); hf = hf.sender(*this)) {
    if (!hf.is_interpreted_frame())
      hf.cb()->as_compiled_method()->dec_on_continuation_stack();
  }
}

hframe ContMirror::new_hframe(intptr_t* hsp, intptr_t* hfp, address pc, CodeBlob* cb, bool is_interpreted) {
  assert (!is_interpreted || in_writestack(hsp) == in_writestack(hfp), "");

  bool write = in_writestack(hsp);
  int sp;
  long fp;
  if (write) {
    sp = write_stack_index(hsp);
    fp = is_interpreted ? write_stack_index(hfp) : (long)hfp;
  } else {
    sp = stack_index(hsp);
    fp = is_interpreted ? stack_index(hfp) : (long)hfp;
  }
  return hframe(sp, fp, pc, cb, is_interpreted, write, write ? 0 : _stack_length);
}

inline int ContMirror::fix_write_index_after_write(int index) {
  return _sp - to_index(METADATA_SIZE) - _wsp + index;
}

inline int ContMirror::fix_index_after_write(int index, int old_length, int new_length) {
  return new_length - (old_length - index);
}

inline hframe ContMirror::fix_hframe_afer_write(hframe& hf) {
  if (hf.write()) {
    return hframe(fix_write_index_after_write(hf.sp()),
                  hf.is_interpreted_frame() ? fix_write_index_after_write(hf.fp()) : hf.fp(),
                  hf.pc(),
                  hf.cb(),
                  hf.is_interpreted_frame(),
                  false,
                  _stack_length);
  } else {
    return hframe(fix_index_after_write(hf.sp(), hf._length, _stack_length),
                  hf.is_interpreted_frame() ? fix_index_after_write(hf.fp(), hf._length, _stack_length) : hf.fp(),
                  hf.pc(),
                  hf.cb(),
                  hf.is_interpreted_frame(),
                  false,
                  _stack_length);
  }
}

address ContMirror::freeze_target() {
  assert (_write_stack != NULL, "");
  return (address) (_write_stack + _wsp);
}

void ContMirror::null_ref_stack(int start, int num) {
  for (int i = 0; i < num; i++) {
    _ref_stack->obj_at_put(start + i, NULL);
  }
}

inline void ContMirror::add_oop(oop obj) {
  int index = _wref_start + _num_oops;
  assert (index < _ref_sp, "");
  log_trace(jvmcont)("i: %d ", index);
  _ref_stack->obj_at_put(index, obj);
  _num_oops++;
}

// inline void ContMirror::add_oop_location(oop* p) {
// #if USE_GROWABLE_ARRAY
//   log_trace(jvmcont)("i: %d (oop)", _oops->length());
//   assert ((((unsigned long)p) & HOB) == 0, "HOB on: %p", p);
//   _oops->append((oopLoc){false, (unsigned long)p});
// #else
//   log_trace(jvmcont)("i: %d (oop)", _num_oops);
//   assert (_num_oops < _wstack_length / 2 - 1, "");
//   assert ((((unsigned long)p) & HOB) == 0, "HOB on: %p", p);
//   _oops[_num_oops] = (oopLoc){false, (unsigned long)p};
//   _num_oops++;
// #endif
// }

// inline void ContMirror::add_oop_location(narrowOop* p) {
// #if USE_GROWABLE_ARRAY
//   log_trace(jvmcont)("i: %d (narrow)", _oops->length());
//   assert ((((unsigned long)p) & HOB) == 0, "HOB on: %p", p);
//   _oops->append((oopLoc){true, (unsigned long)p});
// #else
//   log_trace(jvmcont)("i: %d (narrow)", _num_oops);
//   assert (_num_oops < _wstack_length / 2 - 1, "");
//   assert ((((unsigned long)p) & HOB) == 0, "HOB on: %p", p);
//   _oops[_num_oops] = (oopLoc){true, (unsigned long)p};
//   _num_oops++;
// #endif
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
  log_trace(jvmcont)("Setting RegisterMap saved link address to: %p", &_fp);
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
  log_trace(jvmcont)("patched link: %p", fp);
}

static void patch_sender_sp(frame& f, intptr_t* sp) {
  assert (f.is_interpreted_frame(), "");
  *(intptr_t**)(f.fp() + frame::interpreter_frame_sender_sp_offset) = sp;
  log_trace(jvmcont)("patched sender_sp: %p", sp);
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
  log_trace(jvmcont)("patched return_pc: %p", pc);
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
      log_trace(jvmcont)("sender top: %p locals+1: %p", frame_top(sender), locals_plus_one);
    }
    assert (frame_top(sender) >= locals_plus_one || sender.is_entry_frame(), "sender top: %p locals+1: %p", frame_top(sender), locals_plus_one);
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

static int frame_size(frame& f, int* num_oops) {
  if (f.is_interpreted_frame()) {
    int n = 0;
    n++; // for the mirror
    n += ((intptr_t*)f.interpreter_frame_monitor_begin() - (intptr_t*)f.interpreter_frame_monitor_end())/f.interpreter_frame_monitor_size();

    InterpreterOopMap mask;
    interpreter_oop_mask(f, &mask);
    n += mask.num_oops();
    *num_oops = n;

    int size = (interpreted_frame_bottom(f) - interpreted_frame_top(f, &mask)) * wordSize;
    return size;
  } else if (f.oop_map() != NULL) {
    *num_oops = f.oop_map()->num_oops();
    return f.cb()->frame_size() * wordSize;
  } else // TODO: handle special native functions
    return -1;
  assert(false, "");
  *num_oops = 0;
  return 0;
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
    *(long*)(hfp + offset) = to_index((address)*(hfp + offset) - (address)fp);
}

static inline void derelativize(intptr_t* const fp, int offset) {
    *(fp + offset) = (intptr_t)((address)fp + to_bytes(*(long*)(fp + offset)));
}

class ContOopClosure : public OopClosure, public DerivedOopClosure {
protected:
  ContMirror* const _cont;
  void* const _vsp;
  frame* _fr;
  int _count;
#ifdef ASSERT
  RegisterMap* _map;
#endif

public:
  int count() { return _count; }

protected:
  ContOopClosure(ContMirror* cont, frame* fr, RegisterMap* map, void* vsp)
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
    log_trace(jvmcont)("p: %p offset: %d %s", p, offset, p == _fr->saved_link_address(_map) ? "(link)" : "");
// #else
//     log_trace(jvmcont)("p: %p offset: %d", p, offset);
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

class FreezeOopClosure: public ContOopClosure {
 private:
  void* const _hsp;
  intptr_t** _h_saved_link_address;

 protected:
  template <class T> inline void do_oop_work(T* p) {
    if (!process(p)) return;

    oop obj = NativeAccess<>::oop_load(p);
    _cont->add_oop(obj);
    // _cont->add_oop_location(p);

  #ifdef ASSERT
    // oop obj = NativeAccess<>::oop_load(p);
    print_oop(p, obj);
    assert (oopDesc::is_oop_or_null(obj), "invalid oop");
    log_trace(jvmcont)("narrow: %d", sizeof(T) < wordSize);
  #endif

  #ifndef PRODUCT
    int offset = verify(p);
    address hloc;
    if (offset >= 0) { // rbp could be stored in the callee frame. because frames are stored differently on the h-stack, we don't mark if outside frame
      hloc = (address)_hsp + offset;
      assert (_cont->in_writestack(hloc), "");
    } else {
      assert (p == (T*)_fr->saved_link_address(_map), "");
      hloc = (address) _h_saved_link_address;
      assert (_h_saved_link_address != NULL, "");
    }
    log_trace(jvmcont)("Marking oop at %p (offset: %d)", hloc, offset);
    memset(hloc, 0xba, sizeof(T)); // mark oop locations
  #endif
  }
 public:
  FreezeOopClosure(ContMirror* cont, frame* fr, void* vsp, void* hsp, intptr_t** h_saved_link_address, RegisterMap* map)
   : ContOopClosure(cont, fr, map, vsp), _hsp(hsp), _h_saved_link_address(h_saved_link_address) { assert (cont->in_stack(hsp), ""); }
  virtual void do_oop(oop* p)       { do_oop_work(p); }
  virtual void do_oop(narrowOop* p) { do_oop_work(p); }

  virtual void do_derived_oop(oop *base_loc, oop *derived_loc) {
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
    } else {
      assert ((intptr_t**)derived_loc == _fr->saved_link_address(_map), "");
      assert (_h_saved_link_address != NULL, "");
      hloc = (intptr_t*) _h_saved_link_address;
    }
    log_trace(jvmcont)("Writing derived pointer offset at %p (offset: %ld, 0x%lx)", hloc, offset, offset);
    *hloc = offset;
  }
};

class ThawOopClosure: public ContOopClosure {
 private:
  int _i;

 protected:
  template <class T> inline void do_oop_work(T* p) {
    if (!process(p)) return;

    oop obj = _cont->obj_at(_i); // does a HeapAccess<IN_HEAP_ARRAY> load barrier
    log_trace(jvmcont)("i: %d", _i); print_oop(p, obj);
    NativeAccess<IS_DEST_UNINITIALIZED>::oop_store(p, obj);
    _i++;
  }
 public:
  ThawOopClosure(ContMirror* cont, frame* fr, int index, int num_oops, void* vsp, RegisterMap* map)
    : ContOopClosure(cont, fr, map, vsp) { _i = index; }
  virtual void do_oop(oop* p)       { do_oop_work(p); }
  virtual void do_oop(narrowOop* p) { do_oop_work(p); }

  virtual void do_derived_oop(oop *base_loc, oop *derived_loc) {
    assert(Universe::heap()->is_in_or_null(*base_loc), "not an oop: %p (at %p)", (oopDesc*)*base_loc, base_loc);
    verify(derived_loc);
    verify(base_loc);

    intptr_t offset = *(intptr_t*)derived_loc;

    *derived_loc = cast_to_oop(cast_from_oop<intptr_t>(*base_loc) + offset);

    log_trace(jvmcont)(
      "Continuation thaw derived pointer@" INTPTR_FORMAT " - Derived: " INTPTR_FORMAT " Base: " INTPTR_FORMAT " (@" INTPTR_FORMAT ") (Offset: " INTX_FORMAT ")",
      p2i(derived_loc), p2i((address)*derived_loc), p2i((address)*base_loc), p2i(base_loc), offset);

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

static int freeze_oops(ContMirror& cont, frame &f, hframe &hf, hframe& callee, void* vsp, void* hsp, RegisterMap& map, const ImmutableOopMap* oop_map) {
  log_trace(jvmcont)("Walking oops (freeze)");

  assert (!map.include_argument_oops(), "");

  long tmp_fp = hf.fp();
  FreezeOopClosure oopClosure(&cont, &f, vsp, hsp, (intptr_t**)(callee.is_empty() ? &tmp_fp : callee.link_address(cont)), &map);
  if (oop_map) {
    log_info(jvmcont)("Cached OopMap %p for %p", oop_map, f.pc());

    // void frame::oops_do_internal(OopClosure* f, CodeBlobClosure* cf, DerivedOopClosure* df, RegisterMap* map, bool use_interpreter_oop_map_cache) {
    oop_map->oops_do(&f, &map, &oopClosure, &oopClosure);

    // Preserve potential arguments for a callee. We handle this by dispatching
    // on the codeblob. For c2i, we do
    if (map.include_argument_oops()) {
      f.cb()->preserve_callee_argument_oops(f, &map, &oopClosure);
    }
  } else {
    f.oops_do(&oopClosure, NULL, &oopClosure, &map);
  }

  if (tmp_fp != hf.fp()) {
    log_trace(jvmcont)("WHOA link has changed (freeze) f.fp: 0x%lx link: 0x%lx", hf.fp(), tmp_fp);
    hf.set_fp(tmp_fp);
  }

  log_trace(jvmcont)("Done walking oops");

  int num_oops = oopClosure.count();
#ifdef ASSERT
  int num_oops_in_frame;
  frame_size(f, &num_oops_in_frame);
  assert (num_oops == num_oops_in_frame, "is_interpreted: %d, num_oops: %d num_oops_in_frame: %d", f.is_interpreted_frame(), num_oops, num_oops_in_frame);
#endif
  return num_oops;
}

static inline size_t freeze_interpreted_frame(ContMirror& cont, frame& f, hframe& hf, address target) {
  intptr_t* vsp = interpreted_frame_top(f);
  intptr_t* bottom = interpreted_frame_bottom(f);
  // if (bottom > cont.entrySP()) bottom = cont.entrySP(); // due to a difference between initial_sp and unextended_sp; need to understand better
  // assert (bottom <= cont.entrySP(), "bottom: %p entrySP: %p", bottom, cont.entrySP());
  assert (bottom > vsp, "bottom: %p vsp: %p", bottom, vsp);
  assert (f.unextended_sp() <= vsp, "frame top: %p unextended_sp: %p", vsp, f.unextended_sp());
  const int fsize = (bottom - vsp) * sizeof(intptr_t);

  intptr_t* hsp = (intptr_t*)(target + METADATA_SIZE);
  intptr_t* vfp = f.fp();
  intptr_t* hfp = hsp + (vfp - vsp);

  assert (*(intptr_t**)(vfp + frame::interpreter_frame_locals_offset) < bottom, "frame bottom: %p locals: %p",
    bottom, *(intptr_t**)(vfp + frame::interpreter_frame_locals_offset));

  // hf is the callee
  if (!hf.is_empty()) {
    hf.patch_link_relative(cont, hfp);
    if (hf.is_interpreted_frame() && hf.get_real_fp_offset(cont, frame::interpreter_frame_sender_sp_offset) == 0) {
      hf.patch_real_fp_offset_relative(cont, frame::interpreter_frame_sender_sp_offset, hsp);
    }
  }

  cont.copy_to_stack(vsp, hsp, fsize);

  hf = cont.new_hframe(hsp, hfp, f.pc(), NULL, true);

  // TODO: for compression, initial_sp seems to always point to itself, and locals points to the previous frame's initial_sp + 1 word
  if (*(intptr_t**)(vfp + frame::interpreter_frame_locals_offset) < bottom)
    relativize(vfp, hfp, frame::interpreter_frame_sender_sp_offset);
  else
    hf.patch_real_fp_offset(cont, frame::interpreter_frame_sender_sp_offset, 0);
  relativize(vfp, hfp, frame::interpreter_frame_last_sp_offset);
  relativize(vfp, hfp, frame::interpreter_frame_initial_sp_offset); // == block_top == block_bottom
  relativize(vfp, hfp, frame::interpreter_frame_locals_offset);

  hf.patch_link(cont, 0);
  hf.patch_real_fp_offset(cont, frame::interpreter_frame_sender_sp_offset, 0);

  hf.set_size(cont, fsize);
  hf.set_uncompressed_size(cont, fsize);
  hf.set_num_oops(cont, 0);

  cont.add_size(fsize);
  cont.inc_num_interpreted_frames();
  cont.inc_num_frames();

  return fsize + METADATA_SIZE;
}

static inline size_t freeze_compiled_frame(ContMirror& cont, frame& f, hframe& hf, address target) {
  intptr_t* vsp = compiled_frame_top(f);
  intptr_t* bottom = compiled_frame_bottom(f);
  if (false &&  bottom + 2 < cont.entrySP()) { // we don't freeze parameters from entry. this hack tells us if sender is entry.
    bottom += compiled_frame_num_parameters(f);
  }
  assert (bottom > vsp, "bottom: %p vsp: %p", bottom, vsp);
  assert (bottom <= cont.entrySP(), "bottom: %p entrySP: %p", bottom, cont.entrySP());

  const int fsize = (bottom - vsp) * sizeof(intptr_t);

  intptr_t* hsp = (intptr_t*)(target + METADATA_SIZE);

  // hf is the callee
  if (!hf.is_empty()) {
    hf.patch_link(cont, (long)f.fp());
    if (hf.is_interpreted_frame()) {
      assert (hf.get_real_fp_offset(cont, frame::interpreter_frame_sender_sp_offset) == 0, "");
      hf.patch_real_fp_offset_relative(cont, frame::interpreter_frame_sender_sp_offset, hsp);
    }
  }

  cont.copy_to_stack(vsp, hsp, fsize);

  f.cb()->as_compiled_method()->inc_on_continuation_stack();

  hf = cont.new_hframe(hsp, f.fp(), f.pc(), f.cb(), false);
  hf.patch_link(cont, 0);

  hf.set_size(cont, fsize);
  hf.set_uncompressed_size(cont, 0);
  hf.set_num_oops(cont, 0);

  cont.inc_num_frames();
  cont.add_size(fsize);

  return fsize + METADATA_SIZE;
}

template <bool is_compiled, bool is_interpreted>
static res_freeze freeze_frame1(ContMirror& cont, address &target, frame &f, RegisterMap &map, hframe &hf, bool is_top) {
  assert (!is_interpreted || f.is_interpreted_frame_valid(cont.thread()), "invalid frame");

  if ((is_interpreted && is_interpreted_frame_owning_locks(f))
        || (is_compiled && is_compiled_frame_owning_locks(cont.thread(), &map, f))) {
      return freeze_pinned_monitor;
  }

  hframe callee = hf;

  if (f.is_deoptimized_frame()) log_trace(jvmcont)("freezing deoptimized");

  size_t nbytes = 0;
  if      (is_compiled)    nbytes = freeze_compiled_frame(cont, f, hf, target);
  else if (is_interpreted) nbytes = freeze_interpreted_frame(cont, f, hf, target);
  else {
    // TODO: support reflection, doPrivileged
    log_trace(jvmcont)("not Java: %p", f.pc());
    if (log_is_enabled(Trace, jvmcont)) os::print_location(tty, *((intptr_t*)((void*)f.pc())));
    return freeze_pinned_native;
  }

  if (Continuation::is_cont_bottom_frame(f)) {
    assert (!cont.is_empty(), "");
    log_trace(jvmcont)("Fixing return address on bottom frame: %p", cont.pc());
    hf.patch_return_pc(cont, cont.pc());
  }

  if (!hf.is_interpreted_frame() && Interpreter::contains(hf.return_pc(cont))) { // do after fixing return_pc
      cont.add_size(sizeof(intptr_t)); // possible alignment
      // cont.add_size(sizeof(intptr_t) * ((CompiledMethod*)hf.cb())->method()->size_of_parameters()); // see thaw_compiled_frame
  }

  if (nbytes > 0) {
    intptr_t* vsp = frame_top(f);
    intptr_t* hsp = (intptr_t*)(target + METADATA_SIZE);
    int num_oops = freeze_oops(cont, f, hf, callee, vsp, hsp, map, NULL);
    hf.set_num_oops(cont, num_oops);
  }

  ContinuationCodeBlobLookup lookup;
  frame sender = f.sender(&map, &lookup);

  // last condition is after fixing bottom-most frozen frame
  assert ((hf.return_pc(cont) != sender.pc()) <= (sender.is_deoptimized_frame() || hf.return_pc(cont) == cont.pc()), "hf.return_pc: %p sender.pc: %p sender.is_deoptimized_frame: %d", hf.return_pc(cont), sender.pc(), sender.is_deoptimized_frame());
  if (false) { // TODO: try this instead of deoptimize parameter in thaw
    if (sender.is_deoptimized_frame() && hf.return_pc(cont) != cont.pc()) {
      log_trace(jvmcont)("re-patching deopt"); // we will deopt again when thawing
      hf.patch_return_pc(cont, sender.pc());
    }
  }

  log_trace(jvmcont)("hframe:");
  if (log_is_enabled(Trace, jvmcont)) hf.print(cont);

  target += nbytes;
  f = sender;

  return freeze_ok;
}

// freezes a single frame
static res_freeze freeze_frame(ContMirror& cont, address &target, frame &f, RegisterMap &map, hframe &hf, bool is_top) {
  log_trace(jvmcont)("=============================");

  RegisterMap dmap(NULL, false);
  print_vframe(f, &dmap);
  // assert (strcmp(frame_name(f), ENTER_SIG) != 0, "name: %s", frame_name(f)); // not true if freezing a nesting cont

  const bool is_interpreted = f.is_interpreted_frame();
  const bool is_compiled = f.is_compiled_frame();

  if (is_compiled) {
    return freeze_frame1<true, false>(cont, target, f, map, hf, is_top);
  } else if (is_interpreted) {
    return freeze_frame1<false, true>(cont, target, f, map, hf, is_top);
  } else {
    return freeze_frame1<false, false>(cont, target, f, map, hf, is_top);
  }

}

static res_freeze count_frames(JavaThread* thread, frame f, intptr_t* bottom, intptr_t* top, int* num_frames, int* size, int* num_oops) {
  RegisterMap map(thread, false);
  ContinuationCodeBlobLookup lookup;

  int oops = 0;
  int sz = 0;
  int frames = 0;
  log_trace(jvmcont)("count_frames bottom: %p", bottom);
  while (f.real_fp() <= bottom) { // sp/unextended_sp aren't accurate enough TODO -- reconsider
    DEBUG_ONLY(log_trace(jvmcont)("count_frames %d: %s", frames, frame_name(f)));
    print_vframe(f, &map);

    frames++;

    int oops_in_frame;
    int s = frame_size(f, &oops_in_frame);
    if (s < 0)
      return freeze_pinned_native;
    sz += s + METADATA_SIZE;
    oops += oops_in_frame;

    f = f.sender(&map, &lookup);
  }
  log_trace(jvmcont)("count_frames #frames: %d", frames);
  print_vframe(f, &map);
  assert (strcmp(frame_name(f), ENTER_SIG) == 0, "name: %s", frame_name(f));

  assert (frames < 1000 && frames > 0 && sz > 0, "num_frames: %d size: %d", frames, sz); // just sanity; sometimes get garbage

  *size = sz; // (bottom - top) * sizeof(intptr_t) + num_frames * METADATA_SIZE; // assert (*size == s, "*size: %d s: %d", *size, s);
  *num_oops = oops;
  *num_frames = frames;

  return freeze_ok;
}

// freezes all frames of a single continuation
static res_freeze freeze_continuation(JavaThread* thread, oop oopCont, frame& f, RegisterMap& map) {
  HandleMark hm(thread); // TODO: necessary?
  ResourceMark rm(thread); // required for the arrays created in ContMirror::init_arrays(int)

  assert (oopCont != NULL, "");

  log_trace(jvmcont)("Freeze ___ cont: %p", (oopDesc*)oopCont);

  EventContinuationFreeze event;
  ContMirror cont(thread, oopCont);
  cont.read();

  LogStreamHandle(Trace, jvmcont) st;

  DEBUG_ONLY(log_debug(jvmcont)("Freeze ### #%lx", cont.hash()));
  log_trace(jvmcont)("Freeze 0000 sp: %p fp: %p pc: %p", f.sp(), f.fp(), f.pc());
  log_trace(jvmcont)("Freeze 1111 sp: %d fp: 0x%lx pc: %p", cont.sp(), cont.fp(), cont.pc());

  intptr_t* bottom = cont.entrySP(); // (bottom is highest address; stacks grow down)
  intptr_t* top = f.sp();

  log_trace(jvmcont)("QQQ AAAAA bottom: %p top: %p size: %ld", bottom, top, (address)bottom - (address)top);

  int size, num_oops, num_frames;
  res_freeze count_res = count_frames(thread, f, bottom, top, &num_frames, &size, &num_oops);
  if (count_res != freeze_ok) {
    log_trace(jvmcont)("FREEZE FAILED (count) %d", count_res);
    return count_res;
  }
  log_trace(jvmcont)("bottom: %p count %d size: %d, num_oops: %d", bottom, num_frames, size, num_oops);

  hframe orig_top_frame = cont.last_frame();
  log_trace(jvmcont)("top_hframe before (freeze):");
  if (log_is_enabled(Trace, jvmcont)) orig_top_frame.print_on(cont, tty);

  const bool empty = cont.is_empty();
  log_trace(jvmcont)("empty: %d", empty);
  assert (!CONT_FULL_STACK || empty, "");
  assert (!empty || cont.sp() > cont.stack_length() || cont.sp() < 0, "sp: %d stack_length: %d", cont.sp(), cont.stack_length());
  assert (orig_top_frame.is_empty() == empty, "empty: %d f.sp: %d f.fp: 0x%lx f.pc: %p", empty, orig_top_frame.sp(), orig_top_frame.fp(), orig_top_frame.pc());

  cont.init_write_arrays(size, num_oops, num_frames);
  address target = cont.freeze_target();

  hframe hf;
  hframe new_top;
  int nframes = 0;
  DEBUG_ONLY(frame last_frozen;)
  while (f.real_fp() <= bottom) { // sp/unextended_sp aren't accurate enough TODO -- reconsider
    DEBUG_ONLY(last_frozen = f;)
    res_freeze res = freeze_frame(cont, target, f, map, hf, nframes == 0); // changes f, target, hf
    if (res != freeze_ok) { // f hasn't changed"
        cont.revert(new_top);
        log_trace(jvmcont)("FREEZE FAILED %d", res);
        return res;
        // cont.call_pinned(res, f);
        // return false;
    }
    if (nframes == 0)
      new_top = hf;
    nframes++;
  }
  if (log_is_enabled(Trace, jvmcont)) { log_trace(jvmcont)("Found entry frame: "); print_vframe(f); }
  assert (strcmp(frame_name(f), ENTER_SIG) == 0, "name: %s", frame_name(f));
  assert (nframes == num_frames, "nframes: %d num_frames: %d", nframes, num_frames);

  assert (!empty == Continuation::is_cont_bottom_frame(last_frozen),
    "empty: %d is_cont_bottom_frame(last_frozen): %d", empty, Continuation::is_cont_bottom_frame(last_frozen));

  cont.commit_stacks();
  if (thread->has_pending_exception()) return freeze_exception;

  hf      = cont.fix_hframe_afer_write(hf);
  new_top = cont.fix_hframe_afer_write(new_top);
  orig_top_frame = cont.fix_hframe_afer_write(orig_top_frame);

  cont.set_last_frame(new_top); // must be done after loop, because we rely on the old top when patching last-copied frame

  // f now points at the entry frame

  assert (hf.is_interpreted_frame() || hf.size(cont) % 16 == 0, "");

  if (empty) {
    if (f.is_interpreted_frame()) {
      hf.patch_link(cont, 0);
    } else {
      if (f.is_deoptimized_frame()) {
        assert (f.cb()->as_nmethod()->get_original_pc(&f) == f.pc(), "original_pc: %p f.pc(): %p", f.cb()->as_nmethod()->get_original_pc(&f), f.pc());
        assert (is_deopt_return(hf.return_pc(cont), f), "must be");
        assert (hf.return_pc(cont) != f.pc(), "hf.return_pc(): %p f.pc(): %p", hf.return_pc(cont), f.pc());
        log_trace(jvmcont)("Entry frame deoptimized! pc: %p -> original_pc: %p", hf.return_pc(cont), f.pc());
      } else // we do not patch if entry is deopt, as we use that information when thawing
        hf.patch_return_pc(cont, NULL);
    }
    assert (hf.sender(cont).is_empty(), "");
  } else {
    hf.patch_callee(cont, orig_top_frame);

    assert (hf.sender(cont) == orig_top_frame, "");
  }

  log_trace(jvmcont)("last h-frame:");
  if (log_is_enabled(Trace, jvmcont)) hf.print(cont);

  log_trace(jvmcont)("top_hframe after (freeze):");
  if (log_is_enabled(Trace, jvmcont)) cont.last_frame().print_on(cont, tty);

  DEBUG_ONLY(address ret_pc =  return_pc(f, f.is_interpreted_frame());)

  // assert (strcmp(method_name(new_top.method(cont)), YIELD_SIG) == 0, "name: %s", method_name(new_top.method(cont)));  // not true if yield is not @DontInline
  assert (cont.is_flag(FLAG_LAST_FRAME_INTERPRETED) == new_top.is_interpreted_frame(), "flag: %d is_interpreted: %d", cont.is_flag(FLAG_LAST_FRAME_INTERPRETED), new_top.is_interpreted_frame());

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
  log_debug(jvmcont)("end of freeze cont ### #%lx", cont.hash());
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
  log_debug(jvmcont)("~~~~~~~~~ freeze");
  log_trace(jvmcont)("fi->sp: %p fi->fp: %p fi->pc: %p", fi->sp, fi->fp, fi->pc);
  ContinuationCodeBlobLookup lookup;

  // set_anchor(thread, fi); // DEBUG
  print_frames(thread);

  DEBUG_ONLY(thread->_continuation = NULL;)

  HandleMark hm(thread);

  // call here, when we know we can handle safepoints, to initialize Continuation::_entry_method
  // potentially racy, but benign
  Continuation::entry_method(thread);
  if (thread->has_pending_exception()) {
    fi->fp = NULL; fi->sp = NULL; fi->pc = NULL;
    log_trace(jvmcont)("=== end of freeze (fail 0)");
    return freeze_exception;
  }

  oop cont = get_continuation(thread);
  assert(cont != NULL && oopDesc::is_oop_or_null(cont), "Invalid cont: %p", (void*)cont);

  RegisterMap map(thread, true);
  map.set_include_argument_oops(false);
  // Note: if the doYield stub does not have its own frame, we may need to consider deopt here, especially if yield is inlinable
  frame f = thread->last_frame(); // this is the doYield stub frame. last_frame is set up by the call_VM infrastructure
  f = f.sender(&map, &lookup); // this is the yield frame
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
  f = f.sender(&dmap, &lookup);

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

  log_debug(jvmcont)("ENTRY: sp: %p fp: %p pc: %p", fi->sp, fi->fp, fi->pc);
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

  assert (*(intptr_t**)(vfp + frame::interpreter_frame_locals_offset) < frame_top(sender), "sender top: %p locals: %p",
    frame_top(sender), *(intptr_t**)(vfp + frame::interpreter_frame_locals_offset));

  assert(f.is_interpreted_frame_valid(cont.thread()), "invalid thawed frame");

  cont.dec_num_frames();
  cont.dec_num_interpreted_frames();

  return f;
}

static frame thaw_compiled_frame(ContMirror& cont, hframe& hf, intptr_t* vsp, frame& sender, RegisterMap& map, bool &deoptimized) {
#ifdef _LP64
  if ((long)vsp % 16 != 0) {
    log_trace(jvmcont)("Aligning compiled frame: %p -> %p", vsp, vsp - 1);
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

static void thaw_oops(ContMirror& cont, frame& f, int oop_index, int num_oops, void* target, RegisterMap& map) {
  log_trace(jvmcont)("Walking oops (thaw)");

  // log_trace(jvmcont)("is_top: %d", is_top);
  // assert (!is_top || cont.is_map_at_top(map), "");
  // assert (!is_top || f.is_interpreted_frame() || f.fp() == (intptr_t*)cont.fp(), "f.fp: %p cont.fp: 0x%lx", f.fp(), cont.fp());

  assert (!map.include_argument_oops(), "");

  intptr_t* tmp_fp = f.fp();
  frame::update_map_with_saved_link(&map, &tmp_fp);

  // ResourceMark rm(cont.thread()); // apparently, oop-mapping may require resource allocation
  ThawOopClosure oopClosure(&cont, &f, oop_index, num_oops, target, &map);
  f.oops_do(&oopClosure, NULL, &oopClosure, &map); // can overwrite cont.fp() (because of update_register_map)
  log_trace(jvmcont)("count: %d num_oops: %d", oopClosure.count(), num_oops);
  assert(oopClosure.count() == num_oops, "closure oop count different.");
  cont.null_ref_stack(oop_index, num_oops);

  // Thawing oops may have overwritten the link in the callee if rbp contained an oop (only possible if we're compiled).
  // This only matters when we're the top frame, as that's the value that will be restored into rbp when we jump to continue.
  if (tmp_fp != f.fp()) {
    log_trace(jvmcont)("WHOA link has changed (thaw) f.fp: %p link: %p", f.fp(), tmp_fp);
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
  log_trace(jvmcont)("bottom: %p vsp: %p fsize: %d", bottom, vsp, fsize);


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
    log_trace(jvmcont)("Sender is deopt: %p", ret_pc);
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
    //   tty->print_cr("return: %p real: %p pc: %p raw: %p entry: %p", hf.return_pc(cont), real_pc(sender), sender.pc(), sender.raw_pc(), cont.entryPC());
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
    //   log_trace(jvmcont)("Entry frame deoptimized! pc: %p", sender.pc());
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
  //   log_trace(jvmcont)("Sender is deopt: %p", deopt_ret_pc);
  //   deoptimized = false;
  // }

  RegisterMap map(cont.thread(), true, false);
  map.set_include_argument_oops(false);

  frame f = hf.is_interpreted_frame() ? thaw_interpreted_frame(cont, hf, vsp, sender)
                                      :    thaw_compiled_frame(cont, hf, vsp, sender, map, deoptimized);

  patch_link(f, sender.fp(), hf.is_interpreted_frame());
  patch_return_pc(f, ret_pc, hf.is_interpreted_frame());
  // if (is_sender_deopt) {
  //   assert (!is_entry_frame(cont, sender), "");
  //   tty->print_cr("Patching sender deopt");
  //   log_trace(jvmcont)("Patching sender deopt");
  //   patch_return_pc(f, deopt_ret_pc, hf.is_interpreted_frame());
  // }

  assert (!is_entry_frame(cont, sender) || sender.fp() == cont.entryFP(), "sender.fp: %p entryFP: %p", sender.fp(), cont.entryFP());

  thaw_oops(cont, f, oop_index, hf.num_oops(cont), f.sp(), map);

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
      log_trace(jvmcont)("Setting return address to return barrier: %p", StubRoutines::cont_returnBarrier());
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
  log_trace(jvmcont)("pc: %p", fi->pc);
  log_trace(jvmcont)("rbp: %p", fi->fp);

  // address target = (address)fi->sp; // we leave fi->sp as-is

  oop oopCont = get_continuation(thread);
  assert(oopCont != NULL && oopDesc::is_oop_or_null(oopCont), "Invalid cont: %p", (void*)oopCont);

  ContMirror cont(thread, oopCont);
  cont.read();
  cont.set_entrySP(fi->sp);
  cont.set_entryFP(fi->fp);
  if (!return_barrier) { // not return barrier
    cont.set_entryPC(fi->pc);
  }

  DEBUG_ONLY(log_debug(jvmcont)("THAW ### #%lx", cont.hash()));

#ifndef PRODUCT
  set_anchor(cont);
  // print_frames(thread);
#endif

  // log_trace(jvmcont)("thaw: TARGET: %p", target);
  // log_trace(jvmcont)("QQQ CCCCC bottom: %p top: %p size: %ld", cont.entrySP(), target, (address)cont.entrySP() - target);
  assert(num_frames > 0, "num_frames <= 0: %d", num_frames);
  assert(!cont.is_empty(), "no more frames");

  // ResourceMark rm(cont.thread()); // apparently, oop-mapping may require resource allocation


  hframe hf = cont.last_frame();
  log_trace(jvmcont)("top_hframe before (thaw):");
  if (log_is_enabled(Trace, jvmcont)) hf.print_on(cont, tty);

  RegisterMap map(thread, true, false);
  map.set_include_argument_oops(false);
  assert (map.update_map(), "RegisterMap not set to update");

  DEBUG_ONLY(int orig_num_frames = cont.num_frames();)
  int frame_count = 0;
  int last_oop_index = 0;
  hframe last_frame;
  bool deoptimized = false;
  frame top = thaw_frames(cont, cont.last_frame(), cont.refSP(), num_frames, frame_count, last_oop_index, last_frame, deoptimized);
  cont.set_last_frame(last_frame);
  cont.set_refSP(last_oop_index);

  assert (!CONT_FULL_STACK || cont.is_empty(), "");
  assert (cont.is_empty() == cont.last_frame().is_empty(), "cont.is_empty: %d cont.last_frame().is_empty(): %d", cont.is_empty(), cont.last_frame().is_empty());
  assert (cont.is_empty() == (cont.max_size() == 0), "cont.is_empty: %d cont.max_size: %lu", cont.is_empty(), cont.max_size());
  assert (cont.is_empty() <= (cont.refSP() == cont.refStack()->length()), "cont.is_empty: %d ref_sp: %d refStack.length: %d", cont.is_empty(), cont.refSP(), cont.refStack()->length());
  assert (cont.is_empty() == (cont.num_frames() == 0), "cont.is_empty: %d num_frames: %d", cont.is_empty(), cont.num_frames());
  assert (cont.is_empty() <= (cont.num_interpreted_frames() == 0), "cont.is_empty: %d num_interpreted_frames: %d", cont.is_empty(), cont.num_interpreted_frames());
  assert (cont.num_frames() == orig_num_frames - frame_count, "cont.is_empty: %d num_frames: %d orig_num_frames: %d frame_count: %d", cont.is_empty(), cont.num_frames(), orig_num_frames, frame_count);

  fi->sp = top.sp();
  fi->fp = top.fp();
  fi->pc = top.pc(); // we'll jump to the current continuation pc // Interpreter::return_entry(vtos, 0, Bytecodes::_invokestatic, true); //

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
  log_trace(jvmcont)("fi->sp: %p fi->fp: %p fi->pc: %p", fi->sp, fi->fp, fi->pc);

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
  log_debug(jvmcont)("=== End of thaw #%lx", cont.hash());
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
  log_trace(jvmcont)("~~~~~~~~~ prepare_thaw");

  int num_frames = thaw_num_frames(return_barrier);

  log_trace(jvmcont)("prepare_thaw %d %d", return_barrier, num_frames);
  log_trace(jvmcont)("pc: %p", fi->pc);
  log_trace(jvmcont)("rbp: %p", fi->fp);

  const address bottom = (address)fi->sp; // os::current_stack_pointer(); points to the entry frame
  log_trace(jvmcont)("bottom: %p", bottom);

  JavaThread* thread = JavaThread::current();
  oop cont = get_continuation(thread);

  // if the entry frame is interpreted, it may leave a parameter on the stack, which would be left there if the return barrier is hit
  // assert ((address)java_lang_Continuation::entrySP(cont) - bottom <= 8, "bottom: %p, entrySP: %p", bottom, java_lang_Continuation::entrySP(cont));
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
  log_trace(jvmcont)("target: %p", target);
  log_trace(jvmcont)("QQQ BBBBB bottom: %p top: %p size: %d", bottom, target, size);

  return size;
JRT_END

// IN:  fi->sp = the future SP of the topmost thawed frame (where we'll copy the thawed frames)
// Out: fi->sp = the SP of the topmost thawed frame -- the one we will resume at
//      fi->fp = the FP " ...
//      fi->pc = the PC " ...
// JRT_ENTRY(void, Continuation::thaw(JavaThread* thread, FrameInfo* fi, int num_frames))
JRT_LEAF(void, Continuation::thaw(FrameInfo* fi, bool return_barrier))
  thaw1(JavaThread::current(), fi, return_barrier);
JRT_END

Method* Continuation::_entry_method = NULL;

Method* Continuation::entry_method(Thread* THREAD) {
  if (_entry_method == NULL) {
    CallInfo callinfo;
    Klass* recvrKlass = SystemDictionary::resolve_or_null(vmSymbols::java_lang_Continuation(), THREAD); // SystemDictionary::Continuation_klass();
    LinkInfo link_info(recvrKlass, vmSymbols::enter_name(), vmSymbols::continuationEnter_signature());
    LinkResolver::resolve_special_call(callinfo, Handle(), link_info, THREAD);
    methodHandle method = callinfo.selected_method();
    assert(method.not_null(), "should have thrown exception");
    _entry_method = method();
  }
  return _entry_method;
}

bool Continuation::is_continuation_entry_frame(const frame& f) {
  Method* m = frame_method(f);
  if (m == NULL)
    return false;

  // we can do this because the entry frame is never inlined
  return m == _entry_method;
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

address Continuation::get_entry_pc_past_barrier(JavaThread* thread, const frame& f) {
  oop cont = find_continuation_for_frame(thread, f.sp());
  assert (cont != NULL, "");
  address pc = java_lang_Continuation::entryPC(cont);
  log_trace(jvmcont)("YEYEYEYEYEYEYEEYEY: %p", pc);
  return pc;
}

bool Continuation::is_return_barrier_entry(address pc) {
  return pc == StubRoutines::cont_returnBarrier();
}

address Continuation::sender_pc_past_barrier(JavaThread* thread, const frame& f) {
  return is_return_barrier_entry(f.pc()) ? get_entry_pc_past_barrier(thread, f) : f.pc();
}

address Continuation::fix_continuation_bottom_sender(const frame* callee, RegisterMap* map, address pc) {
  return (map->thread() != NULL && is_return_barrier_entry(pc)) ? get_entry_pc_past_barrier(map->thread(), *callee) : pc;
}

frame Continuation::fix_continuation_bottom_sender(const frame& callee, frame f, RegisterMap* map) {
  if (map->thread() != NULL && is_cont_bottom_frame(callee)) {
    f.set_pc_preserve_deopt(get_entry_pc_past_barrier(map->thread(), f));
  }
  return f;
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

static void print_vframe(frame f, RegisterMap* map, outputStream* st) {
  if (st != NULL && !log_is_enabled(Trace, jvmcont) ) return;
  if (st == NULL) st = tty;

  st->print_cr("\tfp: %p real_fp: %p, sp: %p pc: %p usp: %p", f.fp(), f.real_fp(), f.sp(), f.pc(), f.unextended_sp());

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
    st->print("\tMethod (at: %p): ", fp + frame::interpreter_frame_method_offset); method->print_short_name(st); st->cr();
    st->print_cr("\tcode_size: %d",         method->code_size());
    // st->print_cr("base: %p end: %p", method->constMethod()->code_base(), method->constMethod()->code_end());
    intptr_t** link_address = (intptr_t**)(fp + frame::link_offset);
    st->print_cr("\tlink: %p (at: %p)",    *link_address, link_address);
    st->print_cr("\treturn_pc: %p",        *(void**)(fp + frame::return_addr_offset));
    st->print_cr("\tssp: %p",              (void*)  (fp + frame::sender_sp_offset));
    st->print_cr("\tissp: %p",             *(void**)(fp + frame::interpreter_frame_sender_sp_offset));
    st->print_cr("\tlast_sp: %p",          *(void**)(fp + frame::interpreter_frame_last_sp_offset));
    st->print_cr("\tinitial_sp: %p",       *(void**)(fp + frame::interpreter_frame_initial_sp_offset));
    // st->print_cr("\tmon_block_top: %p",    *(void**)(fp + frame::interpreter_frame_monitor_block_top_offset));
    // st->print_cr("\tmon_block_bottom: %p", *(void**)(fp + frame::interpreter_frame_monitor_block_bottom_offset));
    st->print_cr("\tlocals: %p",           *(void**)(fp + frame::interpreter_frame_locals_offset));
    st->print_cr("\texpression_stack_size: %d", f.interpreter_frame_expression_stack_size());
    // st->print_cr("\tcomputed expression_stack_size: %d", interpreter_frame_expression_stack_size(f));
    st->print_cr("\tcache: %p",            *(void**)(fp + frame::interpreter_frame_cache_offset));
    st->print_cr("\tbcp: %p",              *(void**)(fp + frame::interpreter_frame_bcp_offset));
    st->print_cr("\tbci: %d",               method->bci_from(*(address*)(fp + frame::interpreter_frame_bcp_offset)));
    st->print_cr("\tmirror: %p",           *(void**)(fp + frame::interpreter_frame_mirror_offset));
    // st->print("\tmirror: "); os::print_location(st, *(intptr_t*)(fp + frame::interpreter_frame_mirror_offset), true);
    st->print("\treturn_pc: "); os::print_location(st, *(intptr_t*)(fp + frame::return_addr_offset));
  } else {
    st->print_cr("\tcompiled/C");
    if (f.is_compiled_frame())
      st->print_cr("\torig_pc: %p",    f.cb()->as_nmethod()->get_original_pc(&f));
    // st->print_cr("\torig_pc_address: %p", f.cb()->as_nmethod()->orig_pc_addr(&f));
    // st->print_cr("\tlink: %p",       (void*)f.at(frame::link_offset));
    // st->print_cr("\treturn_pc: %p",  *(void**)(fp + frame::return_addr_offset));
    // st->print_cr("\tssp: %p",        *(void**)(fp + frame::sender_sp_offset));
    st->print_cr("\tcb.size: %d",    f.cb()->frame_size());
    intptr_t** link_address = (intptr_t**)(f.real_fp() - frame::sender_sp_offset);
    st->print_cr("\tlink: %p (at: %p)", *link_address, link_address);
    st->print_cr("\t'real' return_pc: %p",  *(void**)(f.real_fp() - 1));
    st->print("\t'real' return_pc: "); os::print_location(st, *(intptr_t*)(f.real_fp() - 1));
    // st->print("\treturn_pc: "); os::print_location(st, *(intptr_t*)(fp + frame::return_addr_offset));
  }
  if (false && map != NULL) {
    intptr_t* bottom = frame_bottom(f);
    intptr_t* usp = frame_top(f);
    long fsize = (address)bottom - (address)usp;
    st->print_cr("\tsize: %ld", fsize);
    st->print_cr("\tbounds: %p - %p", usp, bottom);

    if (false) {
      st->print_cr("--data--");
      for(int i=0; i<fsize; i++)
        st->print_cr("%p: %x", ((address)usp + i), *((address)usp + i));
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
  tty->print_cr("Continuations hit/miss %ld / %ld", _exploded_hit, _exploded_miss);
  tty->print_cr("Continuations nmethod hit/miss %ld / %ld", _nmethod_hit, _nmethod_miss);
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
