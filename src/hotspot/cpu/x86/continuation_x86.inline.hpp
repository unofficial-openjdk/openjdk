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

#include "runtime/frame.hpp"
#include "runtime/frame.inline.hpp"

static void set_anchor(JavaThread* thread, const FrameInfo* fi) {
  JavaFrameAnchor* anchor = thread->frame_anchor();
  anchor->set_last_Java_sp((intptr_t*)fi->sp);
  anchor->set_last_Java_fp((intptr_t*)fi->fp);
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

inline bool hframe::operator==(const hframe& other) const {
    return  HFrameBase::operator==(other) && _fp == other._fp;
}

inline void hframe::patch_real_fp_offset(int offset, intptr_t value) {
  intptr_t* addr = (link_address() + offset);
  *(link_address() + offset) = value;
}

template<> 
inline intptr_t* hframe::link_address<Interpreted>(int sp, intptr_t fp, const CodeBlob* cb, const ContMirror& cont) {
  assert (cont.valid_stack_index(fp), "fp: %ld stack_length: %d", fp, cont.stack_length());
  return &cont.stack_address(fp)[frame::link_offset];
}

template<typename FKind> 
inline intptr_t* hframe::link_address(int sp, intptr_t fp, const CodeBlob* cb, const ContMirror& cont) {
  assert (cont.valid_stack_index(sp), "sp: %d stack_length: %d", sp, cont.stack_length());
  assert (cb != NULL, "must be");
  return (cont.stack_address(sp) + cb->frame_size()) - frame::sender_sp_offset;
}

template<typename FKind>
inline void hframe::set_link_address(const ContMirror& cont) {
  assert (FKind::is_instance(*this), "");
  _link_address = link_address<FKind>(_sp, _fp, _cb, cont);
}

inline void hframe::set_link_address(const ContMirror& cont) {
  _is_interpreted ? set_link_address<Interpreted>(cont) : set_link_address<NonInterpretedUnknown>(cont);
}

template<typename FKind>
inline address* hframe::return_pc_address() const {
  assert (FKind::is_instance(*this), "");
  // for compiled frames, link_address = real_fp - frame::sender_sp_offset
  return (address*)&link_address()[frame::return_addr_offset];
}

inline int hframe::link_index(const ContMirror& cont) const {
  return cont.stack_index(link_address());
}

inline void hframe::patch_link_relative(intptr_t* fp) {
  intptr_t* la = link_address();
  intptr_t new_value = ContMirror::to_index((address)fp - (address)la);
  // assert (new_value == cont.stack_index(fp) - link_index(cont), "res: %d index delta: %d", new_value, cont.stack_index(fp) - link_index(cont));
  *la = new_value;
}

inline void hframe::patch_sender_sp_relative(intptr_t* value) {
  assert (_is_interpreted, "");
  intptr_t* fp_address = link_address();
  intptr_t* la = &fp_address[frame::interpreter_frame_sender_sp_offset];
  *la = ContMirror::to_index((address)value - (address)fp_address); // all relative indices are relative to fp
}

void hframe::interpreted_frame_oop_map(InterpreterOopMap* mask) const {
  assert (_is_interpreted, "");
  Method* method = *(Method**)interpreter_frame_metadata_at(frame::interpreter_frame_method_offset);
  int bci = method->bci_from(*(address*)interpreter_frame_metadata_at(frame::interpreter_frame_bcp_offset));
  method->mask_for(bci, mask);
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
    int index = _fp + top_offset - (expression_stack_size*elemsPerWord);
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
    return _sp + cb()->frame_size()*elemsPerWord;
  }
}

intptr_t* hframe::interpreter_frame_metadata_at(int offset) const {
  return link_address() + offset;
}

address hframe::interpreter_frame_bcp() const {
  address bcp;
  bcp = (address)*interpreter_frame_metadata_at(frame::interpreter_frame_bcp_offset);
  bcp = method<Interpreted>()->bcp_from(bcp);
  return bcp;
}

intptr_t* hframe::interpreter_frame_local_at(int index) const {
  intptr_t* fp = link_address();
  const int n = Interpreter::local_offset_in_bytes(index)/wordSize;
  intptr_t* locals = (intptr_t*)((address)fp + ContMirror::to_bytes(*(intptr_t*)(fp + frame::interpreter_frame_locals_offset)));
  intptr_t* loc = &(locals[n]); // derelativize

  // tty->print_cr("interpreter_frame_local_at: %d (%p, %ld, %ld) fp: %ld sp: %d, n: %d fp: %p", index, loc, loc - cont.stack_address(_sp), loc - fp, _fp, _sp, n, fp);  
  return loc;
}

intptr_t* hframe::interpreter_frame_expression_stack_at(int offset) const {
  intptr_t* fp = link_address();
  intptr_t* monitor_end = (intptr_t*)((address)fp + ContMirror::to_bytes(*(intptr_t*)(fp + frame::interpreter_frame_monitor_block_top_offset))); // derelativize
  intptr_t* expression_stack = monitor_end-1;

  const int i = offset * frame::interpreter_frame_expression_stack_direction();
  const int n = i * Interpreter::stackElementWords;
  return &(expression_stack[n]);
}

template<typename FKind, op_mode mode>
hframe hframe::sender(const ContMirror& cont, int num_oops) const {
  // tty->print_cr(">> sender of:");
  // print_on(cont, tty);

  int sender_sp = frame_bottom_index<FKind>();
  int sender_ref_sp = _ref_sp + num_oops;
  assert (sender_sp > _sp, "");
  if (sender_sp >= cont.stack_length())
    return hframe();

  address sender_pc = return_pc<FKind>();
  assert (mode != mode_fast || !Interpreter::contains(sender_pc), "");
  bool is_sender_interpreted = mode == mode_fast ? false : Interpreter::contains(sender_pc); 
  CodeBlob* sender_cb;

  intptr_t sender_fp = link();

  if (mode != mode_fast && is_sender_interpreted) {
    sender_fp += link_index(cont);
    sender_cb = NULL;
    sender_sp += FKind::interpreted ? 0 : compiled_frame_stack_argsize() >> LogBytesPerElement;
    // log_develop_trace(jvmcont)("real_fp: %d sender_fp: %ld", link_index(cont), sender_fp);
  } else {
    sender_cb = ContinuationCodeBlobLookup::find_blob(sender_pc);
    sender_pc = hframe::deopt_original_pc(cont, sender_pc, sender_cb, sender_sp); // TODO PERF: unnecessary in the long term solution of unrolling deopted frames on freeze
    // a stub can only appear as the topmost frame; all senders must be compiled/interpreted Java frames so we can call deopt_original_pc, which assumes a compiled Java frame
  }
  return mode == mode_fast ? hframe::new_hframe<Compiled>(sender_sp, sender_ref_sp, sender_fp, sender_pc, cont)
                           : hframe(sender_sp, sender_ref_sp, sender_fp, sender_pc, sender_cb, is_sender_interpreted, cont);
}

inline frame hframe::to_frame(ContMirror& cont, address pc, bool deopt) const {
  return frame(_sp, _ref_sp, _fp, pc,
              _cb != NULL ? _cb : (_cb = CodeCache::find_blob(_pc)),
              deopt);
}

void hframe::print_on(outputStream* st) const {
  if (is_empty()) {
    st->print_cr("\tempty");
  } else if (Interpreter::contains(pc())) { // in fast mode we cannot rely on _is_interpreted
    st->print_cr("\tInterpreted sp: %d fp: %ld pc: " INTPTR_FORMAT " ref_sp: %d (is_interpreted: %d)", _sp, _fp, p2i(_pc), _ref_sp, _is_interpreted);
  } else {
    st->print_cr("\tCompiled sp: %d fp: 0x%lx pc: " INTPTR_FORMAT " ref_sp: %d (is_interpreted: %d)", _sp, _fp, p2i(_pc), _ref_sp, _is_interpreted);
  }
}

void hframe::print_on(const ContMirror& cont, outputStream* st) const {
  print_on(st);
  if (is_empty())
    return;

  if (Interpreter::contains(pc())) { // in fast mode we cannot rely on _is_interpreted
    intptr_t* fp = link_address();
    Method** method_addr = (Method**)(fp + frame::interpreter_frame_method_offset);
    Method* method = *method_addr;
    st->print_cr("\tmethod: " INTPTR_FORMAT " (at " INTPTR_FORMAT ")", p2i(method), p2i(method_addr));
    st->print("\tmethod: "); method->print_short_name(st); st->cr();

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
    st->print_cr("\tcb: " INTPTR_FORMAT, p2i(cb()));
    if (_cb != NULL) {
      st->print("\tcb: "); _cb->print_value_on(st); st->cr();
      st->print_cr("\tcb.frame_size: %d", _cb->frame_size());
    }
  }
  if (link_address() != NULL) {
    st->print_cr("\tlink: 0x%lx %ld (at: " INTPTR_FORMAT ")", link(), link(), p2i(link_address()));
    st->print_cr("\treturn_pc: " INTPTR_FORMAT " (at " INTPTR_FORMAT ")", p2i(CHOOSE2(_is_interpreted, return_pc)), p2i(CHOOSE2(_is_interpreted, return_pc_address)));
  } else {
    st->print_cr("\tlink address: NULL");
  }
}

/////

inline void ContMirror::set_last_frame_pd(const hframe& f) {
  set_fp(f.fp());
}

template<op_mode mode /* = mode_slow*/> // TODO: add default when switching to C++11+
const hframe ContMirror::last_frame() {
  if (is_empty()) return hframe();
  assert (mode != mode_fast || !Interpreter::contains(_pc), "");
  return mode == mode_fast ? hframe::new_hframe<Compiled>(_sp, _ref_sp, _fp, _pc, *this)
                           : hframe(_sp, _ref_sp, _fp, _pc, *this);
}

hframe ContMirror::from_frame(const frame& f) {
  return hframe(f.cont_sp(), f.cont_ref_sp(), (intptr_t)f.fp(), f.pc(), f.cb(), f.is_interpreted_frame(), *this);
}

///////

template <typename RegisterMapT>
inline intptr_t** Frame::map_link_address(const RegisterMapT* map) {
  return (intptr_t**)map->location(rbp->as_VMReg());
}

template<typename FKind>
static inline intptr_t* real_fp(const frame& f) {
  assert (FKind::is_instance(f), "");
  assert (FKind::interpreted || f.cb() != NULL, "");

  return FKind::interpreted ? f.fp() : f.unextended_sp() + f.cb()->frame_size();
}

template<typename FKind> // TODO: maybe do the same CRTP trick with Interpreted and Compiled as with hframe
static inline intptr_t** link_address(const frame& f) {
  assert (FKind::is_instance(f), "");
  return FKind::interpreted
            ? (intptr_t**)(f.fp() + frame::link_offset)
            : (intptr_t**)(real_fp<FKind>(f) - frame::sender_sp_offset);
}

// static inline intptr_t** link_address_stub(const frame& f) {
//   assert (!f.is_java_frame(), "");
//   return (intptr_t**)(f.fp() - frame::sender_sp_offset);
// }

static inline intptr_t** link_address(const frame& f) {
  return f.is_interpreted_frame() ? link_address<Interpreted>(f) : link_address<NonInterpretedUnknown>(f);
}

template<typename FKind>
static void patch_link(frame& f, intptr_t* fp) {
  *link_address<FKind>(f) = fp;
  log_trace(jvmcont)("patched link at " INTPTR_FORMAT ": " INTPTR_FORMAT, p2i(link_address<FKind>(f)), p2i(fp));
}

void Interpreted::patch_sender_sp(frame& f, intptr_t* sp) {
  assert (f.is_interpreted_frame(), "");
  *(intptr_t**)(f.fp() + frame::interpreter_frame_sender_sp_offset) = sp;
  log_trace(jvmcont)("patched sender_sp: " INTPTR_FORMAT, p2i(sp));
}


inline address* Interpreted::return_pc_address(const frame& f) {
  return (address*)(f.fp() + frame::return_addr_offset);
}

template<typename Self>
inline address* NonInterpreted<Self>::return_pc_address(const frame& f) {
  return (address*)(f.real_fp() - 1);
}

inline address Frame::real_pc(const frame& f) {
  address* pc_addr = &(((address*) f.sp())[-1]);
  return *pc_addr;
}

inline void Frame::patch_pc(frame& f, address pc) {
  address* pc_addr = &(((address*) f.sp())[-1]);
  *pc_addr = pc;
}

inline intptr_t* Interpreted::frame_top(const frame& f, InterpreterOopMap* mask) { // inclusive; this will be copied with the frame
  intptr_t* res = *(intptr_t**)f.addr_at(frame::interpreter_frame_initial_sp_offset) - expression_stack_size(f, mask);
  assert (res == (intptr_t*)f.interpreter_frame_monitor_end() - expression_stack_size(f, mask), "");
  return res;
  // Not true, but using unextended_sp might work
  // assert (res == f.unextended_sp() + 1, "res: " INTPTR_FORMAT " unextended_sp: " INTPTR_FORMAT, p2i(res), p2i(f.unextended_sp() + 1));
}

inline intptr_t* Interpreted::frame_bottom(const frame& f) { // exclusive; this will not be copied with the frame
    return *(intptr_t**)f.addr_at(frame::interpreter_frame_locals_offset) + 1; // exclusive, so we add 1 word
}


/////////


template<typename FKind, typename RegisterMapT>
inline void ContinuationHelper::update_register_map(RegisterMapT* map, const frame& f) {
  frame::update_map_with_saved_link(map, link_address<FKind>(f));
}

template<typename RegisterMapT>
inline void ContinuationHelper::update_register_map(RegisterMapT* map, intptr_t** link_address) {
  frame::update_map_with_saved_link(map, link_address);
}

void ContinuationHelper::update_register_map(RegisterMap* map, const hframe& hf, const ContMirror& cont) {
  // we save the link _index_ in the oop map; it is read and converted back in Continuation::reg_to_location
  int link_index = cont.stack_index(hf.link_address());
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
  fi->fp = (intptr_t*)link_address<FKind>(callee); // f.fp();
}

inline void ContinuationHelper::to_frame_info_pd(const frame& f, FrameInfo* fi) {
  fi->fp = f.fp();
}

inline frame ContinuationHelper::to_frame(FrameInfo* fi) {
  address pc = fi->pc;
  int slot;
  CodeBlob* cb = ContinuationCodeBlobLookup::find_blob_and_oopmap(pc, slot);
  return frame(fi->sp, fi->sp, fi->fp, pc, cb, slot == -1 ? NULL : cb->oop_map_for_slot(slot, pc));
}

inline frame ContinuationHelper::to_frame_indirect(FrameInfo* fi) {
  address pc = fi->pc;
  int slot;
  CodeBlob* cb = ContinuationCodeBlobLookup::find_blob_and_oopmap(pc, slot);
  return frame(fi->sp, fi->sp, (intptr_t*)*fi->fp, pc, cb, slot == -1 ? NULL : cb->oop_map_for_slot(slot, pc));
}

// creates the yield stub frame faster than JavaThread::last_frame
inline frame ContinuationHelper::last_frame(JavaThread* thread) {
  JavaFrameAnchor* anchor = thread->frame_anchor();
  assert (anchor->last_Java_sp() != NULL, "");
  assert (anchor->last_Java_pc() != NULL, "");

  assert (StubRoutines::cont_doYield_stub()->contains(anchor->last_Java_pc()), "must be");
  assert (StubRoutines::cont_doYield_stub()->oop_maps()->count() == 1, "must be");

  return frame(anchor->last_Java_sp(), anchor->last_Java_sp(), anchor->last_Java_fp(), anchor->last_Java_pc(), 
    StubRoutines::cont_doYield_stub(), StubRoutines::cont_doYield_stub()->oop_map_for_slot(0, anchor->last_Java_pc()));
}

template<bool fast>
static inline frame sender_for_compiled_frame(const frame& f, intptr_t** link_addr) {
  intptr_t* sender_sp = (intptr_t*)(link_addr + frame::sender_sp_offset); //  f.unextended_sp() + (fsize/wordSize); // 
  address sender_pc = (address) *(sender_sp-1);
  assert(sender_sp != f.sp(), "must have changed");

  int slot = 0;
  CodeBlob* sender_cb = ContinuationCodeBlobLookup::find_blob_and_oopmap(sender_pc, slot);
  if (fast) {
    assert (!Interpreter::contains(sender_pc), "");
    assert (sender_cb != NULL, "");
    return frame(sender_sp, sender_sp, *link_addr, sender_pc, sender_cb, slot == -1 ? NULL : sender_cb->oop_map_for_slot(slot, sender_pc), true); // no deopt check; TODO: not sure about this
  } else {
    return sender_cb != NULL
      ? frame(sender_sp, sender_sp, *link_addr, sender_pc, sender_cb, slot == -1 ? NULL : sender_cb->oop_map_for_slot(slot, sender_pc))
      : frame(sender_sp, sender_sp, *link_addr, sender_pc);
  }
}

static inline frame sender_for_interpreted_frame(const frame& f, intptr_t** link_addr) {
  assert (*link_addr == f.link(), "");
  return frame(f.sender_sp(), f.interpreter_frame_sender_sp(), f.link(), f.sender_pc());
}

// inline void Freeze<ConfigT, mode>::update_register_map_stub(RegisterMap* map, const frame& f) {
//   update_register_map(map, link_address_stub(f));
// }

template <typename ConfigT, op_mode mode>
template<typename FKind>
inline frame Freeze<ConfigT, mode>::sender(const frame& f, intptr_t*** link_address_out) {
  assert (FKind::is_instance(f), "");
  intptr_t** link_addr = link_address<FKind>(f);
  *link_address_out = link_addr;
  return FKind::interpreted 
    ? sender_for_interpreted_frame(f, link_addr) 
    : (mode == mode_fast ? sender_for_compiled_frame<true> (f, link_addr) 
                         : sender_for_compiled_frame<false>(f, link_addr));
}

template <typename ConfigT, op_mode mode>
template<typename FKind>
inline frame Freeze<ConfigT, mode>::sender(const frame& f) {
  assert (FKind::is_instance(f), "");
  intptr_t** link_addr = link_address<FKind>(f);
  return FKind::interpreted 
    ? sender_for_interpreted_frame(f, link_addr) 
    : (mode == mode_fast ? sender_for_compiled_frame<true> (f, link_addr) 
                         : sender_for_compiled_frame<false>(f, link_addr));
}

template <typename ConfigT, op_mode mode>
template<bool cont_empty>
hframe Freeze<ConfigT, mode>::new_bottom_hframe(int sp, int ref_sp, address pc, bool interpreted) {
  intptr_t fp = _cont.fp();
  assert (!cont_empty || fp == 0, "");
  intptr_t* link_address = (cont_empty || !interpreted) ? NULL // if we're not interpreted, we're not interested in the link addresss
                                                        : hframe::link_address<Interpreted>(sp, fp, NULL, _cont);
  return hframe(sp, ref_sp, fp, pc, NULL, interpreted, link_address);
}

template <typename ConfigT, op_mode mode>
template<typename FKind> hframe Freeze<ConfigT, mode>::new_callee_hframe(const frame& f, intptr_t* vsp, const hframe& caller, int fsize, int num_oops) {
  assert (FKind::is_instance(f), "");

  int sp = caller.sp() - ContMirror::to_index(fsize);

  intptr_t fp;
  CodeBlob* cb;
  if (FKind::interpreted) {
    fp = sp + ((f.fp() - vsp) << LogElemsPerWord);
    cb = NULL;
  } else {
    fp = (intptr_t)f.fp();
    cb = f.cb();
  }

  return hframe(sp, caller.ref_sp() - num_oops, fp, f.pc(), cb, FKind::interpreted, hframe::link_address<FKind>(sp, fp, cb, _cont));
}

template <typename ConfigT, op_mode mode>
template <typename FKind, bool top, bool bottom>
inline void Freeze<ConfigT, mode>::patch_pd(const frame& f, hframe& hf, const hframe& caller) {
  if (!FKind::interpreted) {
    if (_fp_oop_info._has_fp_oop) {
      hf.set_fp(_fp_oop_info._fp_index);
    }
  } else {
    assert (!_fp_oop_info._has_fp_oop, "only compiled frames");
  }

  assert (mode != mode_fast || bottom || !Interpreter::contains(caller.pc()), "");
  assert (!bottom || caller.is_interpreted_frame() == _cont.is_flag(FLAG_LAST_FRAME_INTERPRETED), "");

  if ((mode != mode_fast || bottom) && caller.is_interpreted_frame()) {
    hf.patch_link_relative(caller.link_address());
  } else {
    assert (!Interpreter::contains(caller.pc()), "");
    hf.patch_link(caller.fp()); // caller.fp() already contains _fp_oop_info._fp_index if appropriate, as it was patched when patch is called on the caller
  }
  if (FKind::interpreted) {
    assert (mode != mode_fast, "");
    if (bottom && _cont.is_empty()) { // dynamic test, but we don't care because we're interpreted
      hf.patch_real_fp_offset(frame::interpreter_frame_sender_sp_offset, 0);
    } else {
      hf.patch_sender_sp_relative(_cont.stack_address(caller.sp()));
    }
  }
}

template <typename ConfigT, op_mode mode>
template <bool bottom> 
inline void Freeze<ConfigT, mode>::align(const hframe& caller) {
  assert (mode != mode_fast || bottom || !Interpreter::contains(caller.pc()), "");
  if ((mode != mode_fast || bottom) && caller.is_interpreted_frame()) {
    _cont.add_size(sizeof(intptr_t));
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
  return frame(_cont.entrySP(), _cont.entryFP(), _cont.entryPC()); // TODO PERF: This find code blob and computes deopt state
}

template <typename ConfigT, op_mode mode>
template<typename FKind> frame Thaw<ConfigT, mode>::new_frame(const hframe& hf, intptr_t* vsp) {
  assert (FKind::is_instance(hf), "");

  intptr_t* fp;
  if (FKind::interpreted) {
    int hsp = hf.sp();
    fp = vsp + ((hf.fp() - hsp) >> LogElemsPerWord);
    return frame(vsp, vsp, fp, hf.pc());
  } else {
    fp = (intptr_t*)hf.fp();
    assert (hf.oop_map() != NULL, "");
    return frame(vsp, vsp, fp, hf.pc(), hf.cb(), hf.oop_map()); // TODO PERF : this computes deopt state; is it necessary?
  }
}

template <typename ConfigT, op_mode mode>
inline intptr_t** Thaw<ConfigT, mode>::frame_callee_info_address(frame& f) {
  return f.fp_addr(); // we write into the frame object, not the frame on the stack
}

template <typename ConfigT, op_mode mode>
template<typename FKind, bool top, bool bottom>
inline intptr_t* Thaw<ConfigT, mode>::align(const hframe& hf, intptr_t* vsp, const frame& caller) {
  assert (FKind::is_instance(hf), "");

  if (!FKind::interpreted && !FKind::stub) {
  #ifdef _LP64
    if ((intptr_t)vsp % 16 != 0) {
      log_develop_trace(jvmcont)("Aligning compiled frame: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(vsp), p2i(vsp - 1));
      assert(caller.is_interpreted_frame() 
        || (bottom && !FKind::stub && hf.compiled_frame_stack_argsize() % 16 != 0), "");
      vsp--;
    }
    assert((intptr_t)vsp % 16 == 0, "");
  #endif
  
    if (Interpreter::contains(hf.return_pc<FKind>())) { // false if bottom-most frame, as the return address would be patched to NULL if interpreted
      _cont.sub_size(sizeof(intptr_t)); // we do this whether or not we've aligned because we add it in freeze_interpreted_frame
    }
  }
  return vsp;
}

template <typename ConfigT, op_mode mode>
template<typename FKind, bool top, bool bottom>
inline void Thaw<ConfigT, mode>::patch_pd(frame& f, const frame& caller) {
  assert (!bottom || caller.fp() == _cont.entryFP(), "caller.fp: " INTPTR_FORMAT " entryFP: " INTPTR_FORMAT, p2i(caller.fp()), p2i(_cont.entryFP()));

  patch_link<FKind>(f, caller.fp());
}

template <typename ConfigT, op_mode mode>
inline void Thaw<ConfigT, mode>::derelativize_interpreted_frame_metadata(const hframe& hf, const frame& f) {
  intptr_t* hfp = _cont.stack_address(hf.fp());
  intptr_t* vfp = f.fp();

  if (*(hfp + frame::interpreter_frame_last_sp_offset) == 0) {
      *(vfp + frame::interpreter_frame_last_sp_offset) = 0;
  } else {
    ContMirror::derelativize(vfp, frame::interpreter_frame_last_sp_offset);
  }
  ContMirror::derelativize(vfp, frame::interpreter_frame_initial_sp_offset); // == block_top == block_bottom
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
