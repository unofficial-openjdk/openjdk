/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef CPU_X86_CONTINUATION_X86_INLINE_HPP
#define CPU_X86_CONTINUATION_X86_INLINE_HPP

#include "compiler/oopMapStubGenerator.hpp"
#include "runtime/frame.hpp"
#include "runtime/frame.inline.hpp"

template<bool indirect>
static void set_anchor(JavaThread* thread, const FrameInfo* fi) {
  JavaFrameAnchor* anchor = thread->frame_anchor();
  anchor->set_last_Java_sp((intptr_t*)fi->sp);
  anchor->set_last_Java_fp(indirect ? *(intptr_t**)fi->fp : (intptr_t*)fi->fp); // there is an indirection in fi->fp in the FrameInfo created by Freeze::setup_jump
  anchor->set_last_Java_pc(fi->pc);

  assert (thread->has_last_Java_frame(), "");
  assert(thread->last_frame().cb() != NULL, "");
  log_develop_trace(jvmcont)("set_anchor:");
  print_vframe(thread->last_frame());
}

// unused
// static void set_anchor(JavaThread* thread, const frame& f) {
//   JavaFrameAnchor* anchor = thread->frame_anchor();
//   anchor->set_last_Java_sp(f.unextended_sp());
//   anchor->set_last_Java_fp(f.fp());
//   anchor->set_last_Java_pc(f.pc());

//   assert (thread->has_last_Java_frame(), "");
//   assert(thread->last_frame().cb() != NULL, "");
//   log_develop_trace(jvmcont)("set_anchor:");
//   print_vframe(thread->last_frame());
// }

#ifdef CONT_DOUBLE_NOP

template<typename FrameT>
__COLD NOINLINE static CachedCompiledMetadata patch_nop(NativePostCallNop* nop, const FrameT& f) {
  f.get_cb();
  f.oop_map();
  assert(f.cb() != NULL && f.cb()->is_compiled() && f.oop_map() != NULL, "");
  int fsize   = Compiled::size(f);
  int oops    = Compiled::num_oops(f);
  int argsize = Compiled::stack_argsize(f);

  CachedCompiledMetadata md(fsize, oops, argsize);
  if (!md.empty() && !f.cb()->as_compiled_method()->has_monitors()) {
    nop->patch(md.int1(), 1); 
    assert(nop->is_mode2(), "");
  } else {
    // TODO R prevent repeated attempts to patch ???
  }
  return md;
}

template<typename FrameT>
__COLD NOINLINE void ContinuationHelper::patch_freeze_stub(const FrameT& f, address freeze_stub) {
  assert(f.cb() != NULL && f.cb()->is_compiled() && f.oop_map() != NULL, "");
  NativePostCallNop* nop = nativePostCallNop_unsafe_at(f.pc());
  if (freeze_stub != NULL && nop->is_mode2()) {
    intptr_t ptr = nop->int2_data();
    if (ptr == 1) {
      nop->patch_int2(OopMapStubGenerator::stub_to_offset((address)freeze_stub));
    }
  }
}

inline CachedCompiledMetadata ContinuationHelper::cached_metadata(address pc) {
  NativePostCallNop* nop = nativePostCallNop_unsafe_at(pc);
  if (LIKELY(nop->is_mode2())) {
    return CachedCompiledMetadata(nop->int1_data());
  } else {
    return CachedCompiledMetadata(0);
  }
}

template<op_mode mode, typename FrameT>
inline CachedCompiledMetadata ContinuationHelper::cached_metadata(const FrameT& f) {
  if (mode == mode_preempt) return CachedCompiledMetadata(0);

  NativePostCallNop* nop = nativePostCallNop_unsafe_at(f.pc());
  assert (!nop->is_mode2() || slow_get_cb(f)->is_compiled(), "");
  if (LIKELY(nop->is_mode2())) {
    // tty->print_cr(">>> PATCHED 33 -- %d", !md.empty());
    return CachedCompiledMetadata(nop->int1_data());
  } else {
    return patch_nop(nop, f);
  }
}
#endif

template<op_mode mode, typename FrameT>
FreezeFnT ContinuationHelper::freeze_stub(const FrameT& f) {
  // static int __counter = 0;
#ifdef CONT_DOUBLE_NOP
  if (mode != mode_preempt) {
    NativePostCallNop* nop = nativePostCallNop_unsafe_at(f.pc());
    uint32_t ptr = nop->int2_data();
    if (LIKELY(ptr > (uint32_t)1)) {
      return (FreezeFnT)OopMapStubGenerator::offset_to_stub(ptr);
    }
    assert (ptr == 0 || ptr == 1, "");
    if (f.cb() == NULL) return NULL; // f.get_cb();

    // __counter++;
    // if (__counter % 100 == 0) tty->print_cr(">>>> freeze_stub %d %d", ptr, __counter);
    // if (mode == mode_fast) { 
    //   tty->print_cr(">>>> freeze_stub"); f.print_on(tty); tty->print_cr("<<<< freeze_stub"); 
    //   assert(false, "");
    // }
  }
#endif

  FreezeFnT f_fn = (FreezeFnT)f.oop_map()->freeze_stub();
  if ((void*)f_fn == (void*)f.oop_map()) {
    f_fn = NULL; // need CompressedOops for now ????
  }
#ifdef CONT_DOUBLE_NOP
  // we currently patch explicitly, based on ConfigT etc.
  // if (LIKELY(nop != NULL && f_fn != NULL && !nop->is_mode2())) {
  //   nop->patch_int2(OopMapStubGenerator::stub_to_offset((address)f_fn));
  // }
#endif
  return f_fn;
}

template<op_mode mode, typename FrameT>
ThawFnT ContinuationHelper::thaw_stub(const FrameT& f) {
#ifdef CONT_DOUBLE_NOP
  if (mode != mode_preempt) {
    NativePostCallNop* nop = nativePostCallNop_unsafe_at(f.pc());
    uint32_t ptr = nop->int2_data();
    if (LIKELY(ptr > (uint32_t)1)) {
      address freeze_stub = OopMapStubGenerator::offset_to_stub(ptr);
      address thaw_stub = OopMapStubGenerator::thaw_stub(freeze_stub);
      if (f.cb() == NULL) { // TODO PERF: this is only necessary for new_frame called from thaw, because we need cb for deopt info
        CodeBlob* cb = OopMapStubGenerator::code_blob(thaw_stub);
        assert (cb == slow_get_cb(f), "");
        const_cast<FrameT&>(f).set_cb(cb);
      }
      assert (f.cb() != NULL, "");
      return (ThawFnT)thaw_stub;
    }
    assert (ptr == 0 || ptr == 1, "");
    if (f.cb() == NULL) return NULL; // f.get_cb();
  }
#endif
  ThawFnT t_fn = (ThawFnT)f.oop_map()->thaw_stub();
  if ((void*)t_fn == (void*)f.oop_map()) {
    t_fn = NULL; // need CompressedOops for now ????
  }
  return t_fn;
}

inline bool hframe::operator==(const hframe& other) const {
    return  HFrameBase::operator==(other) && _fp == other._fp;
}

intptr_t* hframe::interpreted_link_address(intptr_t fp, const ContMirror& cont) {
  return cont.stack_address((int)fp + (frame::link_offset << LogElemsPerWord));
}

template<typename FKind>
inline address* hframe::return_pc_address() const {
  assert (FKind::interpreted, "");
  return (address*)&interpreted_link_address()[frame::return_addr_offset];
}

const CodeBlob* hframe::get_cb() const {
  if (_cb_imd == NULL) {
    int slot;
    _cb_imd = CodeCache::find_blob_and_oopmap(_pc, slot);
    if (_oop_map == NULL && slot >= 0) {
      _oop_map = ((CodeBlob*)_cb_imd)->oop_map_for_slot(slot, _pc);
    }
  }
  return (CodeBlob*)_cb_imd;
}

const ImmutableOopMap* hframe::get_oop_map() const {
  if (_cb_imd == NULL) return NULL;
  if (((CodeBlob*)_cb_imd)->oop_maps() != NULL) {
    NativePostCallNop* nop = nativePostCallNop_at(_pc);
    if (nop != NULL &&
#ifdef CONT_DOUBLE_NOP
      !nop->is_mode2() &&
#endif
      nop->displacement() != 0
    ) {
      int slot = ((nop->displacement() >> 24) & 0xff);
      // tty->print_cr("hframe::get_oop_map slot: %d", slot);
      return ((CodeBlob*)_cb_imd)->oop_map_for_slot(slot, _pc);
    }
    const ImmutableOopMap* oop_map = OopMapSet::find_map(cb(), pc());
    return oop_map;
  }
  return NULL;
}

intptr_t* hframe::interpreter_frame_metadata_at(int offset) const {
  return interpreted_link_address() + offset;
}

inline void hframe::patch_interpreter_metadata_offset(int offset, intptr_t value) {
  *interpreter_frame_metadata_at(offset) = value;
}

inline void hframe::patch_interpreted_link(intptr_t value) {
  intptr_t* la = interpreted_link_address();
  log_develop_trace(jvmcont)("patch_interpreted_link patching link at %ld to %ld", _fp, value);
  *la = value;
}

inline void hframe::patch_interpreted_link_relative(intptr_t fp) {
  intptr_t* la = interpreted_link_address();
  intptr_t new_value = fp - _fp;
  log_develop_trace(jvmcont)("patch_interpreted_link_relative patching link at %ld to %ld", _fp, new_value);
  // assert (new_value == cont.stack_index(fp) - link_index(cont), "res: %d index delta: %d", new_value, cont.stack_index(fp) - link_index(cont));
  *la = new_value;
}

inline void hframe::patch_sender_sp_relative(intptr_t* value) {
  assert (_is_interpreted, "");
  intptr_t* fp_address = interpreted_link_address();
  intptr_t* la = &fp_address[frame::interpreter_frame_sender_sp_offset];
  *la = ContMirror::to_index((address)value - (address)fp_address); // all relative indices are relative to fp
}

void hframe::interpreted_frame_oop_map(InterpreterOopMap* mask) const {
  assert (_is_interpreted, "");
  Method* m = method<Interpreted>();
  int bci = m->bci_from(*(address*)interpreter_frame_metadata_at(frame::interpreter_frame_bcp_offset));
  m->mask_for(bci, mask);
}

int hframe::interpreted_frame_num_monitors() const {
  assert (_is_interpreted, "");
  return (frame::interpreter_frame_monitor_block_bottom_offset - *(int*)interpreter_frame_metadata_at(frame::interpreter_frame_monitor_block_top_offset)/elemsPerWord)/BasicObjectLock::size();
}

#ifdef ASSERT
  int hframe::interpreted_frame_top_index() const {
    InterpreterOopMap mask;
    interpreted_frame_oop_map(&mask);
    int top_offset = *(int*)interpreter_frame_metadata_at(frame::interpreter_frame_initial_sp_offset);
    int expression_stack_size = mask.expression_stack_size();
    int index = _fp + top_offset - (expression_stack_size << LogElemsPerWord);
    return index;
  }
#endif

template<typename FKind>
int hframe::frame_bottom_index() const {
  assert (FKind::is_instance(*this), "");
  if (FKind::interpreted) {
    int bottom_offset = *(int*)interpreter_frame_metadata_at(frame::interpreter_frame_locals_offset) + (1*elemsPerWord); // exclusive, so we add 1 word
    return _fp + bottom_offset;
  } else {
    return _sp + (cb()->frame_size() << LogElemsPerWord);
  }
}

address hframe::interpreter_frame_bcp() const {
  address bcp;
  bcp = (address)*interpreter_frame_metadata_at(frame::interpreter_frame_bcp_offset);
  bcp = method<Interpreted>()->bcp_from(bcp);
  return bcp;
}

intptr_t* hframe::interpreter_frame_local_at(int index) const {
  intptr_t* fp = interpreted_link_address();
  const int n = Interpreter::local_offset_in_bytes(index)/wordSize;
  intptr_t* locals = (intptr_t*)((address)fp + ContMirror::to_bytes(*(intptr_t*)(fp + frame::interpreter_frame_locals_offset)));
  intptr_t* loc = &(locals[n]); // derelativize

  // tty->print_cr("interpreter_frame_local_at: %d (%p, %ld, %ld) fp: %ld sp: %d, n: %d fp: %p", index, loc, loc - cont.stack_address(_sp), loc - fp, _fp, _sp, n, fp);  
  return loc;
}

intptr_t* hframe::interpreter_frame_expression_stack_at(int offset) const {
  intptr_t* fp = interpreted_link_address();
  intptr_t* monitor_end = (intptr_t*)((address)fp + ContMirror::to_bytes(*(intptr_t*)(fp + frame::interpreter_frame_monitor_block_top_offset))); // derelativize
  intptr_t* expression_stack = monitor_end-1;

  const int i = offset * frame::interpreter_frame_expression_stack_direction();
  const int n = i * Interpreter::stackElementWords;
  return &(expression_stack[n]);
}

inline int hframe::callee_link_index() const {
  return _sp - (frame::sender_sp_offset << LogElemsPerWord);
}

inline void hframe::patch_callee_link(intptr_t value, const ContMirror& cont) const {
  *cont.stack_address(callee_link_index()) = value;
}

inline void hframe::patch_callee_link_relative(intptr_t fp, const ContMirror& cont) const {
  int index = callee_link_index();
  intptr_t* la = cont.stack_address(index);
  intptr_t new_value = fp - index;
  // assert (new_value == cont.stack_index(fp) - link_index(cont), "res: %d index delta: %d", new_value, cont.stack_index(fp) - link_index(cont));
  *la = new_value;
}

inline int hframe::pc_index() const {
  return _sp - (frame::return_addr_offset << LogElemsPerWord);
}

inline address hframe::real_pc(const ContMirror& cont) const {
  return *(address*)cont.stack_address(pc_index());
}

template<typename FKind, op_mode mode>
hframe hframe::sender(const ContMirror& cont, int num_oops) const {
  // tty->print_cr(">> sender of:");
  // print_on(cont, tty);

  int sender_ref_sp = _ref_sp + num_oops;

#ifdef CONT_DOUBLE_NOP
  CachedCompiledMetadata md;
  if (mode == mode_fast && LIKELY(!(md = ContinuationHelper::cached_metadata<mode>(*this)).empty())) {
    int sender_sp = _sp + (md.size_words() << LogElemsPerWord);
    assert (sender_sp > _sp, "");
    if (sender_sp >= cont.stack_length())
      return hframe();

    int link_index = sender_sp - (frame::sender_sp_offset << LogElemsPerWord);
    intptr_t sender_fp = *cont.stack_address(link_index);
    address sender_pc  = (address)*cont.stack_address(link_index + (frame::return_addr_offset << LogElemsPerWord));
    assert (mode != mode_fast || !Interpreter::contains(sender_pc), "");
    return hframe(sender_sp, sender_ref_sp, sender_fp, sender_pc, NULL, false);
  }
#endif

  int sender_sp = frame_bottom_index<FKind>();
  assert (sender_sp > _sp, "");

  if (sender_sp >= cont.stack_length())
    return hframe(sender_sp, sender_ref_sp, 0, NULL, NULL, false); // hframe()

  int link_index = FKind::interpreted ? _fp
                                      : sender_sp - (frame::sender_sp_offset << LogElemsPerWord);

  intptr_t sender_fp = *cont.stack_address(link_index);
  address sender_pc  = FKind::interpreted ? return_pc<Interpreted>()
                                          : (address)*cont.stack_address(sender_sp - (frame::return_addr_offset << LogElemsPerWord));

  assert (mode != mode_fast || !Interpreter::contains(sender_pc), "");
  bool is_sender_interpreted = mode == mode_fast ? false : Interpreter::contains(sender_pc); 

  void* sender_md;
  if (mode != mode_fast && is_sender_interpreted) {
    sender_fp += link_index;
    sender_md = cont.stack_address(sender_fp + (frame::link_offset << LogElemsPerWord));
    sender_sp += FKind::interpreted ? 0 : compiled_frame_stack_argsize() >> LogBytesPerElement;
    // log_develop_trace(jvmcont)("real_fp: %d sender_fp: %ld", link_index, sender_fp);
  } else {
    sender_md = ContinuationCodeBlobLookup::find_blob(sender_pc);
    sender_pc = hframe::deopt_original_pc(cont, sender_pc, (CodeBlob*)sender_md, sender_sp); // TODO PERF: unnecessary in the long term solution of unrolling deopted frames on freeze
    // a stub can only appear as the topmost frame; all senders must be compiled/interpreted Java frames so we can call deopt_original_pc, which assumes a compiled Java frame
  }
  return hframe(sender_sp, sender_ref_sp, sender_fp, sender_pc, sender_md, is_sender_interpreted);
}

inline frame hframe::to_frame(ContMirror& cont, address pc, bool deopt) const {
  return frame(_sp, _ref_sp, _fp, pc,
              (!_is_interpreted && _cb_imd != NULL) ? cb() : (CodeBlob*)(_cb_imd = CodeCache::find_blob(_pc)),
              deopt);
}

void hframe::print_on(outputStream* st) const {
  if (is_empty()) {
    st->print_cr("\tempty");
  } else if (Interpreter::contains(pc())) { // in fast mode we cannot rely on _is_interpreted
    st->print_cr("\tInterpreted sp: %d fp: %ld pc: " INTPTR_FORMAT " ref_sp: %d (is_interpreted: %d) link address: " INTPTR_FORMAT, _sp, _fp, p2i(_pc), _ref_sp, _is_interpreted, p2i(interpreted_link_address()));
  } else {
    st->print_cr("\tCompiled sp: %d fp: 0x%lx pc: "  INTPTR_FORMAT " ref_sp: %d (is_interpreted: %d)", _sp, _fp, p2i(_pc), _ref_sp, _is_interpreted);
  }
}

void hframe::print_on(const ContMirror& cont, outputStream* st) const {
  print_on(st);
  if (is_empty())
    return;

  if (Interpreter::contains(pc())) { // in fast mode we cannot rely on _is_interpreted
    intptr_t* fp = cont.stack_address((int)_fp); // interpreted_link_address();
    Method** method_addr = (Method**)(fp + frame::interpreter_frame_method_offset);
    Method* method = *method_addr;
    st->print_cr("\tmethod: " INTPTR_FORMAT " (at " INTPTR_FORMAT ")", p2i(method), p2i(method_addr));
    st->print("\tmethod: "); method->print_short_name(st); st->cr();
    st->print_cr("\tlink: %ld", *(intptr_t*) fp);
    st->print_cr("\tissp: %ld",             *(intptr_t*) (fp + frame::interpreter_frame_sender_sp_offset));
    st->print_cr("\tlast_sp: %ld",          *(intptr_t*) (fp + frame::interpreter_frame_last_sp_offset));
    st->print_cr("\tinitial_sp: %ld",       *(intptr_t*) (fp + frame::interpreter_frame_initial_sp_offset));
    // st->print_cr("\tmon_block_top: %ld",    *(intptr_t*) (fp + frame::interpreter_frame_monitor_block_top_offset));
    // st->print_cr("\tmon_block_bottom: %ld", *(intptr_t*) (fp + frame::interpreter_frame_monitor_block_bottom_offset));
    st->print_cr("\tlocals: %ld",           *(intptr_t*) (fp + frame::interpreter_frame_locals_offset));
    st->print_cr("\tcache: " INTPTR_FORMAT, p2i(*(void**)(fp + frame::interpreter_frame_cache_offset)));
    st->print_cr("\tbcp: " INTPTR_FORMAT,   p2i(*(void**)(fp + frame::interpreter_frame_bcp_offset)));
    st->print_cr("\tbci: %d",               method->bci_from(*(address*)(fp + frame::interpreter_frame_bcp_offset)));
    st->print_cr("\tmirror: " INTPTR_FORMAT, p2i(*(void**)(fp + frame::interpreter_frame_mirror_offset)));
    // st->print("\tmirror: "); os::print_location(st, *(intptr_t*)(fp + frame::interpreter_frame_mirror_offset), true);
  } else {
    if (_sp > 0) st->print_cr("\treal_pc: " INTPTR_FORMAT, p2i(real_pc(cont)));
    st->print_cr("\tcb: " INTPTR_FORMAT, p2i(cb()));
    if (cb() != NULL) {
      st->print("\tcb: "); cb()->print_value_on(st); st->cr();
      st->print_cr("\tcb.frame_size: %d", cb()->frame_size());
    }
  }
  // if (link_address() != NULL) {
  //   st->print_cr("\tlink: 0x%lx %ld (at: " INTPTR_FORMAT ")", link(), link(), p2i(link_address()));
  //   st->print_cr("\treturn_pc: " INTPTR_FORMAT " (at " INTPTR_FORMAT ")", p2i(CHOOSE2(_is_interpreted, return_pc)), p2i(CHOOSE2(_is_interpreted, return_pc_address)));
  // } else {
  //   st->print_cr("\tlink address: NULL");
  // }
}

/////

inline void ContMirror::set_last_frame_pd(const hframe& f) {
  set_fp(f.fp());
}

/*
 * Here mode_preempt makes the fewest assumptions
 */
template<op_mode mode /* = mode_slow*/> // TODO: add default when switching to C++11+
const hframe ContMirror::last_frame() {
  if (is_empty()) return hframe();

  assert (mode != mode_fast || !Interpreter::contains(_pc), "");
  assert (Interpreter::contains(_pc) == is_flag(FLAG_LAST_FRAME_INTERPRETED), "");

  if (mode == mode_fast || !is_flag(FLAG_LAST_FRAME_INTERPRETED)) {
    CodeBlob* cb;
  #ifdef CONT_DOUBLE_NOP
    if (mode != mode_preempt && LIKELY(!ContinuationHelper::cached_metadata(_pc).empty()))
      cb = NULL;
    else
  #endif
      cb = ContinuationCodeBlobLookup::find_blob(_pc);

    return hframe(_sp, _ref_sp, _fp, _pc, cb, false);
  } else {
    return hframe(_sp, _ref_sp, _fp, _pc, hframe::interpreted_link_address(_fp, *this), true);
  }
}

hframe ContMirror::from_frame(const frame& f) {
  void* md = f.is_interpreted_frame() ? (void*)hframe::interpreted_link_address((intptr_t)f.fp(), *this) : (void*)f.cb();
  return hframe(f.cont_sp(), f.cont_ref_sp(), (intptr_t)f.fp(), f.pc(), md, f.is_interpreted_frame());
}

///////

#ifdef ASSERT
template<typename FKind>
static intptr_t* slow_real_fp(const frame& f) {
  assert (FKind::is_instance(f), "");
  return FKind::interpreted ? f.fp() : f.unextended_sp() + slow_get_cb(f)->frame_size();
}

template<typename FKind> // TODO: maybe do the same CRTP trick with Interpreted and Compiled as with hframe
static intptr_t** slow_link_address(const frame& f) {
  assert (FKind::is_instance(f), "");
  return FKind::interpreted
            ? (intptr_t**)(f.fp() + frame::link_offset)
            : (intptr_t**)(slow_real_fp<FKind>(f) - frame::sender_sp_offset);
}

template<typename FKind>
static address* slow_return_pc_address(const frame& f) {
  return (address*)(slow_real_fp<FKind>(f) - 1);
}
#endif

inline intptr_t** Frame::callee_link_address(const frame& f) {
  return (intptr_t**)(f.sp() - frame::sender_sp_offset);
}

static void patch_callee_link(const frame& f, intptr_t* fp) {
  *Frame::callee_link_address(f) = fp;
  log_trace(jvmcont)("patched link at " INTPTR_FORMAT ": " INTPTR_FORMAT, p2i(Frame::callee_link_address(f)), p2i(fp));
}

template <typename RegisterMapT>
inline intptr_t** Frame::map_link_address(const RegisterMapT* map) {
  return (intptr_t**)map->location(rbp->as_VMReg());
}

static inline intptr_t* noninterpreted_real_fp(intptr_t* unextended_sp, int size_in_words) {
  return unextended_sp + size_in_words;
}

template<typename FKind>
static inline intptr_t* real_fp(const frame& f) {
  assert (FKind::is_instance(f), "");
  assert (FKind::interpreted || f.cb() != NULL, "");

  return FKind::interpreted ? f.fp() : f.unextended_sp() + f.cb()->frame_size();
}

static inline intptr_t** noninterpreted_link_address(intptr_t* unextended_sp, int size_in_words) {
  return (intptr_t**)(noninterpreted_real_fp(unextended_sp, size_in_words) - frame::sender_sp_offset);
}

template<typename FKind> // TODO: maybe do the same CRTP trick with Interpreted and Compiled as with hframe
static inline intptr_t** link_address(const frame& f) {
  assert (FKind::is_instance(f), "");
  return FKind::interpreted
            ? (intptr_t**)(f.fp() + frame::link_offset)
            : (intptr_t**)(real_fp<FKind>(f) - frame::sender_sp_offset);
}

template<typename FKind>
static void patch_link(frame& f, intptr_t* fp) {
  assert (FKind::interpreted, "");
  *link_address<FKind>(f) = fp;
  log_trace(jvmcont)("patched link at " INTPTR_FORMAT ": " INTPTR_FORMAT, p2i(link_address<FKind>(f)), p2i(fp));
}

// static inline intptr_t** link_address_stub(const frame& f) {
//   assert (!f.is_java_frame(), "");
//   return (intptr_t**)(f.fp() - frame::sender_sp_offset);
// }

static inline intptr_t** link_address(const frame& f) {
  return f.is_interpreted_frame() ? link_address<Interpreted>(f) : link_address<NonInterpretedUnknown>(f);
}

inline address* Interpreted::return_pc_address(const frame& f) {
  return (address*)(f.fp() + frame::return_addr_offset);
}

void Interpreted::patch_sender_sp(frame& f, intptr_t* sp) {
  assert (f.is_interpreted_frame(), "");
  *(intptr_t**)(f.fp() + frame::interpreter_frame_sender_sp_offset) = sp;
  log_trace(jvmcont)("patched sender_sp: " INTPTR_FORMAT, p2i(sp));
}

inline address* Frame::return_pc_address(const frame& f) {
  return (address*)(f.real_fp() - 1);
}

// inline address* Frame::pc_address(const frame& f) {
//   return (address*)(f.sp() - frame::return_addr_offset);
// }

inline address Frame::real_pc(const frame& f) {
  address* pc_addr = &(((address*) f.sp())[-1]);
  return *pc_addr;
}

inline void Frame::patch_pc(const frame& f, address pc) {
  address* pc_addr = &(((address*) f.sp())[-1]);
  *pc_addr = pc;
}

inline intptr_t* Interpreted::frame_top(const frame& f, InterpreterOopMap* mask) { // inclusive; this will be copied with the frame
  intptr_t* res = *(intptr_t**)f.addr_at(frame::interpreter_frame_initial_sp_offset) - expression_stack_size(f, mask);
  assert (res == (intptr_t*)f.interpreter_frame_monitor_end() - expression_stack_size(f, mask), "");
  assert (res >= f.unextended_sp(), "");
  return res;
  // Not true, but using unextended_sp might work
  // assert (res == f.unextended_sp(), "res: " INTPTR_FORMAT " unextended_sp: " INTPTR_FORMAT, p2i(res), p2i(f.unextended_sp() + 1));
}

inline intptr_t* Interpreted::frame_bottom(const frame& f) { // exclusive; this will not be copied with the frame
    return *(intptr_t**)f.addr_at(frame::interpreter_frame_locals_offset) + 1; // exclusive, so we add 1 word
}


/////////

static inline intptr_t** callee_link_address(const frame& f) {
  return (intptr_t**)(f.sp() - frame::sender_sp_offset);
}

template<typename FKind, typename RegisterMapT>
inline void ContinuationHelper::update_register_map(RegisterMapT* map, const frame& f) {
  frame::update_map_with_saved_link(map, link_address<FKind>(f));
}

template<typename RegisterMapT>
inline void ContinuationHelper::update_register_map(RegisterMapT* map, intptr_t** link_address) {
  frame::update_map_with_saved_link(map, link_address);
}

template<typename RegisterMapT>
inline void ContinuationHelper::update_register_map_with_callee(RegisterMapT* map, const frame& f) {
  frame::update_map_with_saved_link(map, callee_link_address(f));
}

void ContinuationHelper::update_register_map(RegisterMap* map, const hframe& caller, const ContMirror& cont) {
  // we save the link _index_ in the oop map; it is read and converted back in Continuation::reg_to_location
  int link_index = caller.callee_link_index();
  log_develop_trace(jvmcont)("ContinuationHelper::update_register_map: frame::update_map_with_saved_link: %d", link_index);
  intptr_t link_index0 = link_index;
  frame::update_map_with_saved_link(map, reinterpret_cast<intptr_t**>(link_index0));
}

void ContinuationHelper::update_register_map_from_last_vstack_frame(RegisterMap* map) {
  // we need to return the link address for the entry frame; it is saved in the bottom-most thawed frame
  intptr_t** fp = (intptr_t**)(map->last_vstack_fp());
  log_develop_trace(jvmcont)("ContinuationHelper::update_register_map_from_last_vstack_frame: frame::update_map_with_saved_link: " INTPTR_FORMAT, p2i(fp));
  frame::update_map_with_saved_link(map, fp);
}

inline frame ContinuationHelper::frame_with(frame& f, intptr_t* sp, address pc) {
  return frame(sp, f.unextended_sp(), f.fp(), pc, CodeCache::find_blob(pc));
}

inline void ContinuationHelper::set_last_vstack_frame(RegisterMap* map, const frame& hf) {
  log_develop_trace(jvmcont)("setting map->last_vstack_fp: " INTPTR_FORMAT, p2i(hf.real_fp()));
  map->set_last_vstack_fp(link_address(hf));
}

inline void ContinuationHelper::clear_last_vstack_frame(RegisterMap* map) {
  log_develop_trace(jvmcont)("clearing map->last_vstack_fp");
  map->set_last_vstack_fp(NULL);
}

template<typename FKind> // the callee's type
inline void ContinuationHelper::to_frame_info_pd(const frame& f, const frame& callee, FrameInfo* fi) {
  // we have an indirection for fp, because the link at the entry frame may hold a sender's oop, and it can be relocated
  // at a safpoint on the VM->Java transition, so we point at an address where the GC would find it
  assert (callee_link_address(f) == slow_link_address<FKind>(callee), "");
  fi->fp = (intptr_t*)callee_link_address(f); // f.fp();
}

inline void ContinuationHelper::to_frame_info_pd(const frame& f, FrameInfo* fi) {
  fi->fp = f.fp();
}

template<bool indirect>
inline frame ContinuationHelper::to_frame(FrameInfo* fi) {
  address pc = fi->pc;
  int slot;
  CodeBlob* cb = ContinuationCodeBlobLookup::find_blob_and_oopmap(pc, slot);
  return frame(fi->sp, fi->sp, 
    indirect ? *(intptr_t**)fi->fp : fi->fp, 
    pc, cb, slot == -1 ? NULL : cb->oop_map_for_slot(slot, pc));
}

// creates the yield stub frame faster than JavaThread::last_frame
inline frame ContinuationHelper::last_frame(JavaThread* thread) {
  JavaFrameAnchor* anchor = thread->frame_anchor();
  assert (anchor->last_Java_sp() != NULL, "");
  assert (anchor->last_Java_pc() != NULL, "");

  assert (StubRoutines::cont_doYield_stub()->contains(anchor->last_Java_pc()), "must be");
  assert (StubRoutines::cont_doYield_stub()->oop_maps()->count() == 1, "must be");

  return frame(anchor->last_Java_sp(), anchor->last_Java_sp(), anchor->last_Java_fp(), anchor->last_Java_pc(), NULL, NULL, true);
  // return frame(anchor->last_Java_sp(), anchor->last_Java_sp(), anchor->last_Java_fp(), anchor->last_Java_pc(), 
  //   StubRoutines::cont_doYield_stub(), StubRoutines::cont_doYield_stub()->oop_map_for_slot(0, anchor->last_Java_pc()), true);
}

template<typename FKind, op_mode mode>
static inline frame sender_for_compiled_frame(const frame& f) {
#ifdef CONT_DOUBLE_NOP
  CachedCompiledMetadata md;
  // tty->print_cr(">>> sender fast: %d !FKind::stub: %d", fast, !FKind::stub);
  if (mode == mode_fast && !FKind::stub && LIKELY(!(md = ContinuationHelper::cached_metadata<mode>(f)).empty())) {
    intptr_t* sender_sp = f.unextended_sp() + md.size_words();
    intptr_t** link_addr = (intptr_t**)(sender_sp - frame::sender_sp_offset);
    address sender_pc = (address) *(sender_sp-1);

    assert(sender_sp != f.sp(), "must have changed");
    return frame(sender_sp, sender_sp, *link_addr, sender_pc, NULL, NULL, true); // no deopt check TODO PERF: use a faster constructor that doesn't write cb (shows up in profile)
  }
  // tty->print_cr(">>> slow sender1");
#endif

  assert (mode == mode_preempt || !FKind::stub || StubRoutines::cont_doYield_stub()->contains(f.pc()), "must be");
  assert (mode == mode_preempt || !FKind::stub || slow_get_cb(f)->frame_size() == 5, "must be");
  intptr_t** link_addr = (mode != mode_preempt && FKind::stub) ? noninterpreted_link_address(f.unextended_sp(), 5) : link_address<FKind>(f);

  intptr_t* sender_sp = (intptr_t*)(link_addr + frame::sender_sp_offset); //  f.unextended_sp() + (fsize/wordSize); // 
  address sender_pc = (address) *(sender_sp-1);
  assert(sender_sp != f.sp(), "must have changed");

#ifdef CONT_DOUBLE_NOP
  if (mode == mode_fast) {
    assert (!Interpreter::contains(sender_pc), "");
    return frame(sender_sp, sender_sp, *link_addr, sender_pc, NULL, NULL, true); // no deopt check
  }
#endif

  // tty->print_cr("33333 fast: %d stub: %d", fast, FKind::stub); if (fast) f.print_on(tty);
  int slot = 0;
  CodeBlob* sender_cb = ContinuationCodeBlobLookup::find_blob_and_oopmap(sender_pc, slot);
  if (mode == mode_fast) {
    assert (!Interpreter::contains(sender_pc), "");
    assert (sender_cb != NULL, "");
    return frame(sender_sp, sender_sp, *link_addr, sender_pc, sender_cb, slot == -1 ? NULL : sender_cb->oop_map_for_slot(slot, sender_pc), true); // no deopt check TODO PERF: use a faster constructor that doesn't write cb (shows up in profile)
  } else {
    return sender_cb != NULL
      ? frame(sender_sp, sender_sp, *link_addr, sender_pc, sender_cb, slot == -1 ? NULL : sender_cb->oop_map_for_slot(slot, sender_pc))
      : frame(sender_sp, sender_sp, *link_addr, sender_pc);
  }
}

static inline frame sender_for_interpreted_frame(const frame& f) {
  return frame(f.sender_sp(), f.interpreter_frame_sender_sp(), f.link(), f.sender_pc());
}

// inline void Freeze<ConfigT, mode>::update_register_map_stub(RegisterMap* map, const frame& f) {
//   update_register_map(map, link_address_stub(f));
// }

template <typename ConfigT, op_mode mode>
template<typename FKind>
inline frame Freeze<ConfigT, mode>::sender(const frame& f) {
  assert (FKind::is_instance(f), "");
  if (FKind::interpreted) {
    return sender_for_interpreted_frame(f);
  } else {
    return sender_for_compiled_frame<FKind, mode>(f);
  }
}

static inline int callee_link_index(const hframe& f) {
  return f.sp() - (frame::sender_sp_offset << LogElemsPerWord);
}

template <typename ConfigT, op_mode mode>
template<bool cont_empty>
hframe Freeze<ConfigT, mode>::new_bottom_hframe(int sp, int ref_sp, address pc, bool interpreted) {
  intptr_t fp = _cont.fp();
  assert (!cont_empty || fp == 0, "");
  void* imd = NULL;
  DEBUG_ONLY(imd = interpreted ? hframe::interpreted_link_address(fp, _cont) : NULL);
  return hframe(sp, ref_sp, fp, pc, imd, interpreted);
}

template <typename ConfigT, op_mode mode>
template<typename FKind> hframe Freeze<ConfigT, mode>::new_hframe(const frame& f, intptr_t* vsp, const hframe& caller, int fsize, int num_oops, int argsize) {
  assert (FKind::is_instance(f), "");
  assert (f.sp() <= vsp, "");
  assert (mode != mode_fast || f.sp() == f.unextended_sp(), "");

  int sp = caller.sp() - ContMirror::to_index(fsize);
  // int sp = mode == mode_fast ? usp : usp - ((vsp - f.sp()) << LogElemsPerWord);
  int ref_sp = caller.ref_sp() - num_oops;
  if (mode != mode_fast && caller.is_interpreted_frame()) { // must be done after computing sp above
    const_cast<hframe&>(caller).set_sp(caller.sp() - (argsize >> LogBytesPerElement));
  }
  intptr_t fp;
  void* cb_imd;
  if (FKind::interpreted) {
    fp = sp + ((f.fp() - vsp) << LogElemsPerWord);
    cb_imd = hframe::interpreted_link_address(fp, _cont);
  } else {
    fp = (intptr_t)f.fp();
    cb_imd = f.cb();
  }

  return hframe(sp, ref_sp, fp, f.pc(), cb_imd, FKind::interpreted);
}

template <typename ConfigT, op_mode mode>
template <typename FKind, bool top, bool bottom>
inline void Freeze<ConfigT, mode>::patch_pd(const frame& f, hframe& hf, const hframe& caller) {
  if (!FKind::interpreted) {
    if (_fp_oop_info._has_fp_oop) {
      hf.set_fp(_fp_oop_info._fp_index); // TODO PERF non-temporal store
    }
  } else {
    assert (!_fp_oop_info._has_fp_oop, "only compiled frames");
  }

  assert (!FKind::interpreted || hf.interpreted_link_address() == _cont.stack_address(hf.fp()), "");
  assert (mode != mode_fast || bottom || !Interpreter::contains(caller.pc()), "");
  assert (!bottom || caller.is_interpreted_frame() == _cont.is_flag(FLAG_LAST_FRAME_INTERPRETED), "");

  if ((mode != mode_fast || bottom) && caller.is_interpreted_frame()) {
    FKind::interpreted ? hf.patch_interpreted_link_relative(caller.fp())
                       : caller.patch_callee_link_relative(caller.fp(), _cont); // TODO PERF non-temporal store
  } else {
    assert (!Interpreter::contains(caller.pc()), "");
    // TODO PERF non-temporal store
    FKind::interpreted ? hf.patch_interpreted_link(caller.fp())
                       : caller.patch_callee_link(caller.fp(), _cont); // caller.fp() already contains _fp_oop_info._fp_index if appropriate, as it was patched when patch is called on the caller
  }
  if (FKind::interpreted) {
    assert (mode != mode_fast, "");
    if (bottom && _cont.is_empty()) { // dynamic test, but we don't care because we're interpreted
      hf.patch_interpreter_metadata_offset(frame::interpreter_frame_sender_sp_offset, 0);
    } else {
      hf.patch_sender_sp_relative(_cont.stack_address(caller.sp()));
    }
  }
}

template <typename ConfigT, op_mode mode>
template <bool bottom>
inline void Freeze<ConfigT, mode>::align(const hframe& caller, int argsize) {
  assert (mode != mode_fast || bottom || !Interpreter::contains(caller.pc()), "");
  if ((mode != mode_fast || bottom) && caller.is_interpreted_frame()) {
    assert (argsize >= 0, "");
    // See Thaw::align
    _cont.add_size((SP_WIGGLE + ((argsize /* / 2*/) >> LogBytesPerWord)) * sizeof(intptr_t));
  }
}

template <typename ConfigT, op_mode mode>
inline void Freeze<ConfigT, mode>::relativize_interpreted_frame_metadata(const frame& f, intptr_t* vsp, const hframe& hf) {
  intptr_t* vfp = f.fp();
  intptr_t* hfp = _cont.stack_address(hf.fp());
  assert (hfp == _cont.stack_address(hf.sp()) + (vfp - vsp), "");

  assert ((*(vfp + frame::interpreter_frame_last_sp_offset) != 0) || (f.unextended_sp() == f.sp()), "");

  if (*(vfp + frame::interpreter_frame_last_sp_offset) == 0) {
    *(hfp + frame::interpreter_frame_last_sp_offset) = 0;
  } else {
    ContMirror::relativize(vfp, hfp, frame::interpreter_frame_last_sp_offset);
  }
  ContMirror::relativize(vfp, hfp, frame::interpreter_frame_initial_sp_offset); // == block_top == block_bottom
  ContMirror::relativize(vfp, hfp, frame::interpreter_frame_locals_offset);
}

template <typename ConfigT, op_mode mode>
inline frame Thaw<ConfigT, mode>::new_entry_frame() {
  // if (Interpreter::contains(_cont.entryPC())) _cont.set_entrySP(_cont.entrySP() - 1);
  return frame(_cont.entrySP(), _cont.entryFP(), _cont.entryPC()); // TODO PERF: This finds code blob and computes deopt state
}

template <typename ConfigT, op_mode mode>
template<typename FKind> frame Thaw<ConfigT, mode>::new_frame(const hframe& hf, intptr_t* vsp) {
  assert (FKind::is_instance(hf), "");

  if (FKind::interpreted) {
    // intptr_t* sp = vsp - ((hsp - hf.sp()) >> LogElemsPerWord);
    int hsp = hf.sp();
    intptr_t* fp = vsp + ((hf.fp() - hsp) >> LogElemsPerWord);
    return frame(vsp, vsp, fp, hf.pc());
  } else {
    intptr_t* fp = (intptr_t*)hf.fp();
  #ifdef CONT_DOUBLE_NOP
    hf.get_cb();
  #endif
    assert (hf.cb() != NULL && hf.oop_map() != NULL, "");
    return frame(vsp, vsp, fp, hf.pc(), hf.cb(), hf.oop_map()); // TODO PERF : this computes deopt state; is it necessary?
  }
}

template <typename ConfigT, op_mode mode>
inline intptr_t** Thaw<ConfigT, mode>::frame_callee_info_address(frame& f) {
  return f.fp_addr(); // we write into the frame object, not the frame on the stack
}

template <typename ConfigT, op_mode mode>
template<typename FKind, bool top, bool bottom>
inline intptr_t* Thaw<ConfigT, mode>::align(const hframe& hf, intptr_t* vsp, frame& caller) {
  assert (FKind::is_instance(hf), "");
  assert (mode != mode_fast || bottom, "");

  if (!FKind::interpreted && !FKind::stub) {
    int addedWords = 0;
    assert (_cont.is_flag(FLAG_LAST_FRAME_INTERPRETED) == Interpreter::contains(_cont.pc()), "");
    if (((bottom || mode != mode_fast) && caller.is_interpreted_frame()) 
        || (bottom && _cont.is_flag(FLAG_LAST_FRAME_INTERPRETED))) {

      // Deoptimization likes ample room between interpreted frames and compiled frames. 
      // This is due to caller_adjustment calculation in Deoptimization::fetch_unroll_info_helper.
      // An attempt to simplify that calculation and make more room during deopt has failed some tests.

      addedWords = (SP_WIGGLE-1); // We subtract 1 for alignment, which we may add later

      // SharedRuntime::gen_i2c_adapter makes room that's twice as big as required for the stack-passed arguments by counting slots but subtracting words from rsp 
      assert (VMRegImpl::stack_slot_size == 4, "");
      int argsize = hf.compiled_frame_stack_argsize();
      assert (argsize >= 0, "");
      addedWords += (argsize /* / 2*/) >> LogBytesPerWord; // Not sure why dividing by 2 is not big enough.

      if (!bottom || _cont.is_flag(FLAG_LAST_FRAME_INTERPRETED)) {
        _cont.sub_size((1 + addedWords) * sizeof(intptr_t)); // we add one whether or not we've aligned because we add it in freeze_interpreted_frame
      } 
      if (!bottom || caller.is_interpreted_frame()) {
        log_develop_trace(jvmcont)("Aligning compiled frame 0: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(vsp), p2i(vsp - addedWords));
        vsp -= addedWords;
      } else {
        addedWords = 0;
      }
    }
  #ifdef _LP64
    if ((intptr_t)vsp % 16 != 0) {
      log_develop_trace(jvmcont)("Aligning compiled frame 1: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(vsp), p2i(vsp - 1));
      assert(caller.is_interpreted_frame() 
        || (bottom && !FKind::stub && hf.compiled_frame_stack_argsize() % 16 != 0), "");
      addedWords++;
      vsp--;
    }
    assert((intptr_t)vsp % 16 == 0, "");
  #endif

   log_develop_trace(jvmcont)("Aligning sender sp: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(caller.sp()), p2i(caller.sp() - addedWords));
    caller.set_sp(caller.sp() - addedWords);
  }

  return vsp;
}

template <typename ConfigT, op_mode mode>
template<typename FKind, bool top, bool bottom>
inline void Thaw<ConfigT, mode>::patch_pd(frame& f, const frame& caller) {
  assert (!bottom || caller.fp() == _cont.entryFP(), "caller.fp: " INTPTR_FORMAT " entryFP: " INTPTR_FORMAT, p2i(caller.fp()), p2i(_cont.entryFP()));
  assert (FKind::interpreted || slow_link_address<FKind>(f) == Frame::callee_link_address(caller), "");
  FKind::interpreted ? patch_link<FKind>(f, caller.fp())
                     : patch_callee_link(caller, caller.fp());
}

template <typename ConfigT, op_mode mode>
inline void Thaw<ConfigT, mode>::derelativize_interpreted_frame_metadata(const hframe& hf, const frame& f) {
  intptr_t* vfp = f.fp();

  intptr_t* hfp = _cont.stack_address(hf.fp());
  if (*(hfp + frame::interpreter_frame_last_sp_offset) == 0) {
      *(vfp + frame::interpreter_frame_last_sp_offset) = 0;
  } else {
    ContMirror::derelativize(vfp, frame::interpreter_frame_last_sp_offset);
  }
  ContMirror::derelativize(vfp, frame::interpreter_frame_initial_sp_offset);
  ContMirror::derelativize(vfp, frame::interpreter_frame_locals_offset);
}

////////

// Java frames don't have callee saved registers (except for rbp), so we can use a smaller RegisterMap
class SmallRegisterMap {
  static const VMReg my_reg; // = rbp->as_VMReg();

public:
  // as_RegisterMap is used when we didn't want to templatize and abstract over RegisterMap type to support SmallRegisterMap
  // Consider enhancing SmallRegisterMap to support those cases
  const RegisterMap* as_RegisterMap() const { return NULL; }
  RegisterMap* as_RegisterMap() { return NULL; }
  
private:
  intptr_t*   _rbp;

#ifdef ASSERT
  JavaThread* _thread;
#endif
  // bool        _update_map;              // Tells if the register map need to be updated when traversing the stack
  // bool        _validate_oops;           // whether to perform valid oop checks in asserts -- used only in the map use for continuation freeze/thaw
  // bool        _walk_cont;               // whether to walk frames on a continuation stack
public:
  SmallRegisterMap(JavaThread *thread, bool update_map = true, bool walk_cont = false, bool validate_oops = true) 
   DEBUG_ONLY(: _thread(thread)) /*, _update_map(update_map), _validate_oops(validate_oops) */ {
     _rbp = NULL;
  }
  SmallRegisterMap(const SmallRegisterMap* map) 
    DEBUG_ONLY(: _thread(map->thread())) /*, _update_map(map->update_map()), _validate_oops(map->validate_oops()) */ {
    _rbp = map->_rbp;
  }
  SmallRegisterMap(const RegisterMap* map) 
    DEBUG_ONLY(: _thread(map->thread())) /* , _update_map(map->update_map()), _validate_oops(map->validate_oops()) */ {
    _rbp = (intptr_t*)map->location(my_reg);
  }

  address location(VMReg reg) const {
    assert (reg == my_reg || reg == my_reg->next(), "Reg: %s", reg->name());
    return (address)_rbp;
  }

  void set_location(VMReg reg, address loc) {
    assert(!validate_oops() || update_map(), "updating map that does not need updating");
    assert (reg == my_reg || reg == my_reg->next(), "Reg: %s", reg->name());
    // tty->print_cr(">>> set location %s(%ld) loc: %p", reg->name(), reg->value(), loc);
    _rbp = (intptr_t*)loc;
  }

  JavaThread* thread() const {
  #ifdef ASSERT
    return _thread;
  #else
    guarantee (false, ""); 
    return NULL; 
  #endif
  }
  bool update_map()    const { return false; }
  bool validate_oops() const { return false; }
  bool walk_cont()     const { return false; }
  bool include_argument_oops() const { return false; }
  void set_include_argument_oops(bool f)  {}
  bool in_cont()      const { return false; }

#ifdef ASSERT
  // void set_skip_missing(bool value) { _skip_missing = value; }
  bool should_skip_missing() const  { return false; }

  VMReg find_register_spilled_here(void* p) {
    return _rbp == (intptr_t*)p ? my_reg : NULL;
  }
#endif

#ifndef PRODUCT
  void print() const { print_on(tty); }
  
  void print_on(outputStream* st) const {
    st->print_cr("Register map");

    VMReg r = my_reg;

    intptr_t* src = (intptr_t*) location(r);
    if (src != NULL) {
      r->print_on(st);
      st->print(" [" INTPTR_FORMAT "] = ", p2i(src));
      if (((uintptr_t)src & (sizeof(*src)-1)) != 0) {
        st->print_cr("<misaligned>");
      } else {
        st->print_cr(INTPTR_FORMAT, *src);
      }
    }
  }

#endif
};

const VMReg SmallRegisterMap::my_reg = rbp->as_VMReg();

/// DEBUGGING

static void print_vframe(frame f, const RegisterMap* map, outputStream* st) {
  if (st != NULL && !log_is_enabled(Trace, jvmcont)) return;
  if (st == NULL) st = tty;

  st->print_cr("\tfp: " INTPTR_FORMAT " real_fp: " INTPTR_FORMAT ", sp: " INTPTR_FORMAT " pc: " INTPTR_FORMAT " usp: " INTPTR_FORMAT, p2i(f.fp()), p2i(f.real_fp()), p2i(f.sp()), p2i(f.pc()), p2i(f.unextended_sp()));

  f.print_on(st);

  // st->print("\tpc: "); os::print_location(st, *(intptr_t*)f.pc());
  intptr_t* fp = f.fp();
  st->print("\tcb: ");
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
}

#endif // CPU_X86_CONTINUATION_X86_INLINE_HPP
