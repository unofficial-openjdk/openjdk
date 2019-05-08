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

inline bool hframe::operator==(const hframe& other) { 
    return  HFrameBase::operator==(other) && _fp == other._fp; 
}

inline intptr_t* hframe::real_fp(const ContMirror& cont) const {
  assert (!_is_interpreted, "interpreted");
  assert (cb() != NULL, "must be");
  return cont.stack_address(_sp) + cb()->frame_size();
}

inline int hframe::real_fp_index(const ContMirror& cont) const {
  assert (!_is_interpreted, "interpreted");
  // assert (_length == cont.stack_length(), "");
  return _sp + ContMirror::to_index(cb()->frame_size() * sizeof(intptr_t));
}

inline void hframe::patch_real_fp_offset(int offset, intptr_t value) { 
  long* addr = (link_address() + offset);
  *(link_address() + offset) = value; 
}

template<>
void hframe::set_link_address<Interpreted>(const ContMirror& cont) {
  assert (_is_interpreted, "");
  assert (cont.valid_stack_index(_fp), "fp: %ld stack_length: %d", _fp, cont.stack_length());
  // if (cont.valid_stack_index(_fp))
  _link_address = &cont.stack_address(_fp)[frame::link_offset];
}

template<typename FKind>
void hframe::set_link_address(const ContMirror& cont) {
  assert (!FKind::interpreted, "");
  assert (FKind::interpreted == _is_interpreted, "");

  assert (cont.valid_stack_index(_sp), "sp: %d stack_length: %d", _sp, cont.stack_length());
  // if (cont.valid_stack_index(_sp))
  _link_address = real_fp(cont) - frame::sender_sp_offset;
}

inline void hframe::set_link_address(const ContMirror& cont) { 
  _is_interpreted ? set_link_address<Interpreted>(cont) : set_link_address<Compiled>(cont); 
}

template<typename FKind>
inline address* hframe::return_pc_address() const {
  assert (FKind::interpreted == _is_interpreted, "");
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
  long* la = (long*)(&fp_address[frame::interpreter_frame_sender_sp_offset]);
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
  return (frame::interpreter_frame_monitor_block_bottom_offset - *(int*)interpreter_frame_metadata_at(frame::interpreter_frame_monitor_block_top_offset)/ELEMS_PER_WORD)/BasicObjectLock::size();
}

#ifdef ASSERT
  int hframe::interpreted_frame_top_index() const {
    InterpreterOopMap mask;
    interpreted_frame_oop_map(&mask);
    int top_offset = *(int*)interpreter_frame_metadata_at(frame::interpreter_frame_initial_sp_offset);
    int expression_stack_size = mask.expression_stack_size();
    int index = _fp + top_offset - (expression_stack_size*ELEMS_PER_WORD);
    return index;
  }
#endif

template<typename FKind>
int hframe::frame_bottom_index() const {
  assert (FKind::interpreted == _is_interpreted, "");
  if (FKind::interpreted) {
    int bottom_offset = *(int*)interpreter_frame_metadata_at(frame::interpreter_frame_locals_offset) + (1*ELEMS_PER_WORD); // exclusive, so we add 1 word
    // assert (bottom_offset == *(int*)interpreter_frame_metadata_at(frame::interpreter_frame_sender_sp_offset), 
    //   "bottom_offset: %d interpreter_frame_sender_sp: %d (%d)", 
    //   bottom_offset, *(int*)interpreter_frame_metadata_at(frame::interpreter_frame_sender_sp_offset), *(int*)interpreter_frame_metadata_at(cont, frame::interpreter_frame_locals_offset));
    return _fp + bottom_offset;
  } else {
    return _sp + cb()->frame_size()*ELEMS_PER_WORD;
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
  intptr_t* locals = (intptr_t*)((address)fp + ContMirror::to_bytes(*(long*)(fp + frame::interpreter_frame_locals_offset)));
  intptr_t* loc = &(locals[n]); // derelativize

  // tty->print_cr("interpreter_frame_local_at: %d (%p, %ld, %ld) fp: %ld sp: %d, n: %d fp: %p", index, loc, loc - cont.stack_address(_sp), loc - fp, _fp, _sp, n, fp);  
  return loc;
}

intptr_t* hframe::interpreter_frame_expression_stack_at(int offset) const {
  intptr_t* fp = link_address();
  intptr_t* monitor_end = (intptr_t*)((address)fp + ContMirror::to_bytes(*(long*)(fp + frame::interpreter_frame_monitor_block_top_offset))); // derelativize
  intptr_t* expression_stack = monitor_end-1;

  const int i = offset * frame::interpreter_frame_expression_stack_direction();
  const int n = i * Interpreter::stackElementWords;
  return &(expression_stack[n]);
}

template<typename FKind>
hframe hframe::sender(ContMirror& cont, int num_oops) const {
  address sender_pc = return_pc<FKind>();
  int sender_sp = frame_bottom_index<FKind>();
  bool is_sender_interpreted = Interpreter::contains(sender_pc);
  long sender_fp = link();
  if (is_sender_interpreted) {
    sender_fp += link_index(cont);
    // log_develop_trace(jvmcont)("real_fp: %d sender_fp: %ld", link_index(cont), sender_fp);
  }
  int sender_ref_sp = _ref_sp + num_oops;
  assert (sender_sp > _sp, "");
  if (sender_sp >= cont.stack_length())
    return hframe();
  return hframe(sender_sp, sender_ref_sp, sender_fp, sender_pc, is_sender_interpreted, cont);
}

inline frame hframe::to_frame(ContMirror& cont, address pc, bool deopt) const {
  return frame(_sp, _ref_sp, reinterpret_cast<intptr_t>(_fp), pc, 
              _cb != NULL ? _cb : (_cb = CodeCache::find_blob(_pc)),
              deopt);
}

void hframe::print_on(outputStream* st) const {
  if (is_empty()) {
    st->print_cr("\tempty");
  } else if (_is_interpreted) {
    st->print_cr("\tInterpreted sp: %d fp: %ld pc: " INTPTR_FORMAT " ref_sp: %d", _sp, _fp, p2i(_pc), _ref_sp);
  } else {
    st->print_cr("\tCompiled sp: %d fp: 0x%lx pc: " INTPTR_FORMAT " ref_sp: %d", _sp, _fp, p2i(_pc), _ref_sp);
  }
}

void hframe::print_on(ContMirror& cont, outputStream* st) const {
  print_on(st);
  if (is_empty())
    return;

  if (_is_interpreted) {
    intptr_t* fp = link_address();
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
  st->print_cr("\treturn_pc: " INTPTR_FORMAT " (at " INTPTR_FORMAT ")", p2i(CHOOSE2(_is_interpreted, return_pc)), p2i(CHOOSE2(_is_interpreted, return_pc_address)));
}

/////

hframe ContMirror::last_frame() {
  return is_empty() ? hframe() : hframe(_sp, _ref_sp, _fp, _pc, *this);
}

///////

inline intptr_t** Frame::saved_link_address(const RegisterMap* map) {
  return frame::saved_link_address(map);
}

template<typename FKind>
static inline intptr_t* real_fp(const frame& f) {
  assert (FKind::interpreted == f.is_interpreted_frame(), "");
  assert (FKind::interpreted || f.cb() != NULL, "");

  return FKind::interpreted ? f.fp() : f.unextended_sp() + f.cb()->frame_size();
}

template<typename FKind> // TODO: maybe do the same CRTP trick with Interpreted and Compiled as with hframe
static inline intptr_t** link_address(const frame& f) {
  return FKind::interpreted
            ? (intptr_t**)(f.fp() + frame::link_offset)
            : (intptr_t**)(real_fp<FKind>(f) - frame::sender_sp_offset);
}

// static inline intptr_t** link_address_stub(const frame& f) {
//   assert (!f.is_java_frame(), "");
//   return (intptr_t**)(f.fp() - frame::sender_sp_offset);
// }

static inline intptr_t** link_address(const frame& f) {
  return f.is_interpreted_frame() ? link_address<Interpreted>(f) : link_address<Compiled>(f);
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

inline address* Compiled::return_pc_address(const frame& f) {
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
  // return *(intptr_t**)f.addr_at(frame::interpreter_frame_monitor_block_top_offset) - interpreter_frame_expression_stack_size(f);
  // return (intptr_t*)f.interpreter_frame_monitor_end() - interpreter_frame_expression_stack_size(f);
}

inline intptr_t* Interpreted::frame_bottom(const frame& f) { // exclusive; this will not be copied with the frame
#ifdef ASSERT
    if (Thread::current()->is_Java_thread()) { // may not be if we're freezing in a handshake
      RegisterMap map(JavaThread::current(), false); // if thread is NULL we don't get a fix for the return barrier -> entry frame
      frame sender = f.sender(&map);
      intptr_t* locals_plus_one = *(intptr_t**)f.addr_at(frame::interpreter_frame_locals_offset) + 1;
      if (!sender.is_entry_frame() && Frame::frame_top(sender) != locals_plus_one) {
        log_trace(jvmcont)("f: "); print_vframe(f);
        log_trace(jvmcont)("sender: "); print_vframe(sender);
        log_trace(jvmcont)("sender top: " INTPTR_FORMAT " locals+1: " INTPTR_FORMAT, p2i(Frame::frame_top(sender)), p2i(locals_plus_one));
      }
      assert (Frame::frame_top(sender) >= locals_plus_one || sender.is_entry_frame(), "sender top: " INTPTR_FORMAT " locals+1: " INTPTR_FORMAT, p2i(Frame::frame_top(sender)), p2i(locals_plus_one));
    }
#endif
    return *(intptr_t**)f.addr_at(frame::interpreter_frame_locals_offset) + 1; // exclusive, so we add 1 word
}


inline void ContinuationHelper::set_last_vstack_frame(RegisterMap* map, const frame& hf) {
  map->set_last_vstack_fp(link_address(hf));
}

inline void ContinuationHelper::to_frame_info_pd(const frame& f, const frame& hf, FrameInfo* fi) {
  // we have an indirection for fp, because the link at the entry frame may hold a sender's oop, and it can be relocated
  // at a safpoint on the VM->Java transition, so we point at an address where the GC would find it
  fi->fp = (intptr_t*)link_address(hf); // f.fp(); -- dynamic branch
}

inline frame ContinuationHelper::to_frame(FrameInfo* fi) {
  address pc = fi->pc;
  int slot;
  CodeBlob* cb = ContinuationCodeBlobLookup::find_blob_and_oopmap(pc, slot);
  return frame(fi->sp, fi->sp, fi->fp, pc, cb, slot == -1 ? NULL : cb->oop_map_for_slot(slot, pc));
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
static inline frame sender_for_compiled_frame(frame& f, intptr_t** link_addr) {
  intptr_t* sender_sp = (intptr_t*)(link_addr + frame::sender_sp_offset); //  f.unextended_sp() + (fsize/wordSize); // 
  address sender_pc = (address) *(sender_sp-1);
  assert(sender_sp != f.sp(), "must have changed");

  int slot = 0;
  CodeBlob* sender_cb = ContinuationCodeBlobLookup::find_blob_and_oopmap(sender_pc, slot);
  assert (!fast || sender_cb != NULL, "");
  return fast || sender_cb != NULL 
    ? frame(sender_sp, sender_sp, *link_addr, sender_pc, sender_cb, slot == -1 ? NULL : sender_cb->oop_map_for_slot(slot, sender_pc))
    : frame(sender_sp, sender_sp, *link_addr, sender_pc);
}

static inline frame sender_for_interpreted_frame(frame& f, intptr_t** link_addr) {
  assert (*link_addr == f.link(), "");
  return frame(f.sender_sp(), f.interpreter_frame_sender_sp(), f.link(), f.sender_pc());
}

template<typename FKind>
inline void ContinuationHelper::update_register_map(RegisterMap* map, const frame& f) {
  frame::update_map_with_saved_link(map, link_address<FKind>(f));
}

// inline void Freeze<ConfigT, mode>::update_register_map_stub(RegisterMap* map, const frame& f) {
//   update_register_map(map, link_address_stub(f));
// }

template <typename ConfigT, freeze_mode mode>
template<typename FKind>
inline frame Freeze<ConfigT, mode>::sender(frame& f, intptr_t*** link_address_out) {
  assert (FKind::interpreted == f.is_interpreted_frame(), "");
  intptr_t** link_addr = link_address<FKind>(f);
  *link_address_out = link_addr;
  return FKind::interpreted 
    ? sender_for_interpreted_frame(f, link_addr) 
    : (mode == mode_fast ? sender_for_compiled_frame<true> (f, link_addr) 
                         : sender_for_compiled_frame<false>(f, link_addr));
}

template <typename ConfigT, freeze_mode mode>
inline void Freeze<ConfigT, mode>::update_register_map(RegisterMap* map, intptr_t** link_address) {
  frame::update_map_with_saved_link(map, link_address);
}

template <typename ConfigT, freeze_mode mode>
template<typename FKind> hframe Freeze<ConfigT, mode>::new_hframe(const frame& f, intptr_t* vsp, intptr_t* hsp, int ref_sp) {
  assert (FKind::interpreted == f.is_interpreted_frame(), "");

  int sp = _cont.stack_index(hsp);

  intptr_t fp;
  CodeBlob* cb;
  if (FKind::interpreted) {
    fp = _cont.stack_index(hsp + (long)(f.fp() - vsp));
    cb = NULL;
  } else {
    fp = (intptr_t)f.fp();
    cb = f.cb();
  }

  hframe result = hframe(sp, ref_sp, fp, f.pc(), cb, FKind::interpreted);
  result.set_link_address<FKind>(_cont);
  return result;
}

template <typename ConfigT, freeze_mode mode>
inline void Freeze<ConfigT, mode>::relativize_interpreted_frame_metadata(frame& f, intptr_t* vsp, intptr_t* hsp) {
  intptr_t* vfp = f.fp();
  intptr_t* hfp = hsp + (vfp - vsp);

  assert ((*(vfp + frame::interpreter_frame_last_sp_offset) != 0) || (f.unextended_sp() == f.sp()), "");

  if (*(vfp + frame::interpreter_frame_last_sp_offset) == 0) {
    *(hfp + frame::interpreter_frame_last_sp_offset) = 0;
  } else {
    ContMirror::relativize(vfp, hfp, frame::interpreter_frame_last_sp_offset);
  }
  ContMirror::relativize(vfp, hfp, frame::interpreter_frame_initial_sp_offset); // == block_top == block_bottom
  ContMirror::relativize(vfp, hfp, frame::interpreter_frame_locals_offset);
}

template <typename ConfigT, freeze_mode mode>
template <typename FKind, bool top, bool bottom>
inline void Freeze<ConfigT, mode>::patch_pd(frame& f, hframe& hf, const hframe& caller) {
  if (!FKind::interpreted) {
    if (_fp_oop_info._has_fp_oop) {
      hf.set_fp(_fp_oop_info._fp_index);
    }
  } else {
    assert (!_fp_oop_info._has_fp_oop, "only compiled frames");
  }

  assert (caller.is_empty() == bottom, "caller.is_empty(): %d bottom: %d", caller.is_empty(), bottom);
  if (!bottom) {
    if (mode != mode_fast && caller.is_interpreted_frame()) {
      hf.patch_link_relative(caller.link_address());
    } else {
      hf.patch_link(caller.fp()); // caller.fp() already contains _fp_oop_info._fp_index if appropriate, as it was patched when patch is called on the caller
    }
    if (FKind::interpreted) {
      assert (mode != mode_fast, "");
      hf.patch_sender_sp_relative(_cont.stack_address(caller.sp()));
    }
  } else { // bottom
    assert (!_cont.is_empty() || (_cont.fp() == 0 && _cont.pc() == NULL), "");
    if (Interpreter::contains(_cont.pc())) { // if empty, we'll take the second branch and patch to NULL; we want to avoid a test of is_empty, as we do it in patch, too
      hf.patch_link_relative(&_cont.stack_address(_cont.fp())[frame::link_offset]);
    } else {
      hf.patch_link(_cont.fp());
    }

    if (FKind::interpreted) {
      assert (mode != mode_fast, "");
      _cont.is_empty() // dynamic test, but we don't care because we're interpreted
        ? hf.patch_real_fp_offset(frame::interpreter_frame_sender_sp_offset, 0)
        : hf.patch_sender_sp_relative(_cont.stack_address(_cont.sp()));
    }
  }
}

template<typename FKind>
static inline void patch_thawed_frame_pd(frame& f, const frame& sender) {
  patch_link<FKind>(f, sender.fp());
}

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