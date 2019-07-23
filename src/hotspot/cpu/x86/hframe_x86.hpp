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

public:

  typedef intptr_t** callee_info;

public:
  hframe() : HFrameBase(), _fp(0) {}

  hframe(const hframe& hf) : HFrameBase(hf), _fp(hf._fp) {}

  hframe(int sp, int ref_sp, intptr_t fp, address pc, const ContMirror& cont) // called by ContMirror::last_frame
    : HFrameBase(sp, ref_sp, pc, cont), _fp(fp) {}
  

  hframe(int sp, int ref_sp, intptr_t fp, address pc, void* cb_md, bool is_interpreted) 
    : HFrameBase(sp, ref_sp, pc, cb_md, is_interpreted), _fp(fp) {}

  inline bool operator==(const hframe& other) const;

  void copy_partial_pd(const hframe& other) {
    _fp = other._fp;
  } 

  inline intptr_t  fp()     const { return _fp; }

  inline void set_fp(intptr_t fp) { _fp = fp; }

  const CodeBlob* get_cb() const;
  const ImmutableOopMap* get_oop_map() const;

  inline int callee_link_index() const;
  inline int pc_index() const;

  inline address real_pc(const ContMirror& cont) const;

  inline intptr_t* interpreted_link_address() const { assert (Interpreter::contains(_pc), ""); return (intptr_t*)_cb_imd; }

  static intptr_t* interpreted_link_address(intptr_t fp, const ContMirror& cont);

  inline void patch_interpreter_metadata_offset(int offset, intptr_t value);
  inline intptr_t* interpreter_frame_metadata_at(int offset) const;

  inline void patch_interpreted_link(intptr_t value);
  inline void patch_interpreted_link_relative(intptr_t fp);

  inline void patch_callee_link(intptr_t value, const ContMirror& cont) const;
  inline void patch_callee_link_relative(intptr_t fp, const ContMirror& cont) const;

  inline void patch_sender_sp_relative(intptr_t* value);

  template<typename FKind> inline address* return_pc_address() const;

  template<typename FKind> int frame_bottom_index() const;

  using HFrameBase<hframe>::sender; // unhide overloaded
  template<typename FKind, op_mode mode> hframe sender(const ContMirror& cont, int num_oops) const;

  DEBUG_ONLY(int interpreted_frame_top_index() const;)
  int interpreted_frame_num_monitors() const;
  void interpreted_frame_oop_map(InterpreterOopMap* mask) const;

  address interpreter_frame_bcp() const;
  intptr_t* interpreter_frame_local_at(int index) const;
  intptr_t* interpreter_frame_expression_stack_at(int offset) const;

  template<typename FKind> Method* method() const;

  using HFrameBase<hframe>::to_frame; // unhide overloaded
  inline frame to_frame(ContMirror& cont, address pd, bool deopt) const;

  void print_on(const ContMirror& cont, outputStream* st) const;
  void print_on(outputStream* st) const ;
};

template<>
Method* hframe::method<Interpreted>() const {
  assert (_is_interpreted, "");
  return *(Method**)interpreter_frame_metadata_at(frame::interpreter_frame_method_offset);
}

#ifdef CONT_DOUBLE_NOP

// TODO R move to continuation_x86.inline.hpp once PD has been separated

const int mdSizeBits    = 13;
const int mdOopBits     = 14;
const int mdArgsizeBits = 5;
STATIC_ASSERT(mdSizeBits + mdOopBits + mdArgsizeBits == 32);

class CachedCompiledMetadata {
private:
  union {
    struct {
      uint _size    : mdSizeBits; // in DWORDS
      uint _oops    : mdOopBits;
      uint _argsize : mdArgsizeBits;
    };
    uint32_t _int1;
  };

public:
  CachedCompiledMetadata() {}
  CachedCompiledMetadata(uint32_t int1) { _int1 = int1; }
  CachedCompiledMetadata(int size, int oops, int argsize) {
    assert (size % 8 == 0, "");
    size >>= LogBytesPerWord;
    if (size <= ((1 << mdSizeBits) - 1) && oops <= ((1 << mdOopBits) - 1) && argsize <= ((1 << mdArgsizeBits) - 1)) {
      _size = size;
      _oops = oops;
      _argsize = argsize;
    } else {
      tty->print_cr(">> metadata failed: size: %d oops: %d argsize: %d", size, oops, argsize);
      _int1 = 0;
    }
  }

  bool empty()        const { return _size == 0; }
  int size()          const { return ((int)_size) << LogBytesPerWord; }
  int size_words()    const { return (int)_size; }
  int num_oops()      const { return (int)_oops; }
  int stack_argsize() const { return (int)_argsize; }

  uint32_t int1() const { return _int1; }

  void print_on(outputStream* st) { st->print("size: %d args: %d oops: %d", size(), stack_argsize(), num_oops()); }
  void print() { print_on(tty); }
};

STATIC_ASSERT(sizeof(CachedCompiledMetadata) == 4);

#endif

#endif // CPU_X86_HFRAME_X86_HPP