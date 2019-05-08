/*
 * Copyright (c) 1997, 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef CPU_X86_HFRAME_X86_HPP
#define CPU_X86_HFRAME_X86_HPP

class hframe : public HFrameBase<hframe> {
private:
  // additional fields beyond _sp and _pc:
  intptr_t _fp;

  intptr_t* _link_address;

private:
  inline intptr_t* real_fp(const ContMirror& cont) const;
  inline int real_fp_index(const ContMirror& cont) const;
  inline int link_index(const ContMirror& cont) const;
  inline intptr_t* interpreter_frame_metadata_at(int offset) const;


public:

  typedef intptr_t** callee_info;

public:
  hframe() : HFrameBase(), _fp(0), _link_address(NULL) {}

  hframe(const hframe& hf) : HFrameBase(hf), _fp(hf._fp), _link_address(hf._link_address) {}

  hframe(int sp, int ref_sp, intptr_t fp, address pc, ContMirror& cont)
    : HFrameBase(sp, ref_sp, pc, cont), _fp(fp) { set_link_address(cont); }

  hframe(int sp, int ref_sp, intptr_t fp, address pc, CodeBlob* cb, bool is_interpreted, ContMirror& cont)
    : HFrameBase(sp, ref_sp, pc, cb, is_interpreted, cont), _fp(fp) { set_link_address(cont); }

  hframe(int sp, int ref_sp, intptr_t fp, address pc, bool is_interpreted, ContMirror& cont)
    : HFrameBase(sp, ref_sp, pc, is_interpreted, cont), _fp(fp), _link_address(NULL) { set_link_address(cont); }

  hframe(int sp, int ref_sp, intptr_t fp, address pc, CodeBlob* cb, bool is_interpreted) // called by ContMirror::new_hframe
    : HFrameBase(sp, ref_sp, pc, cb, is_interpreted), _fp(fp), _link_address(NULL) {}

  inline bool operator==(const hframe& other);

  inline intptr_t  fp()     const { return _fp; }

  inline void set_fp(intptr_t fp) { _fp = fp; }

  // the link is an offset from the real fp to the sender's fp IFF the sender is interpreted; otherwise, it's the contents of the rbp register
  intptr_t* link_address() const { return _link_address; }
  intptr_t link() const          { return *link_address(); }

  template<typename FKind> void set_link_address(const ContMirror& cont);
  inline void set_link_address(const ContMirror& cont);

  void patch_link(intptr_t value) { 
    intptr_t* la = link_address();
    *la = value;
  }

  void zero_link() { patch_link(0); }

  inline void patch_link_relative(intptr_t* fp);

  inline void patch_real_fp_offset(int offset, intptr_t value);
  inline intptr_t* get_real_fp_offset(int offset) { return (intptr_t*)*(link_address() + offset); }

  inline void patch_sender_sp_relative(intptr_t* value);

  template<typename FKind> inline address* return_pc_address() const;

  template<typename FKind> int frame_bottom_index() const;

  using HFrameBase<hframe>::sender; // unhide overloaded
  template<typename FKind> hframe sender(ContMirror& cont, int num_oops) const;

  DEBUG_ONLY(int interpreted_frame_top_index() const;)
  int interpreted_frame_num_monitors() const;
  void interpreted_frame_oop_map(InterpreterOopMap* mask) const;

  address interpreter_frame_bcp() const;
  intptr_t* interpreter_frame_local_at(int index) const;
  intptr_t* interpreter_frame_expression_stack_at(int offset) const;

  template<typename FKind> Method* method() const;

  using HFrameBase<hframe>::to_frame; // unhide overloaded
  inline frame to_frame(ContMirror& cont, address pd, bool deopt) const;

  void print_on(ContMirror& cont, outputStream* st) const;
  void print_on(outputStream* st) const ;
};

template<>
Method* hframe::method<Interpreted>() const {
  assert (_is_interpreted, "");
  return *(Method**)interpreter_frame_metadata_at(frame::interpreter_frame_method_offset);
}

#endif // CPU_X86_HFRAME_X86_HPP