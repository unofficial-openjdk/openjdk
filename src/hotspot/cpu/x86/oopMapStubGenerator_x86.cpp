/*
 * Copyright (c) 1998, 2018, Oracle and/or its affiliates. All rights reserved.
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
#include "asm/macroAssembler.hpp"
#include "code/vmreg.inline.hpp"
#include "compiler/oopMap.hpp"
#include "compiler/oopMap.inline.hpp"
#include "compiler/oopMapStubGenerator.hpp"
#include "gc/shared/collectedHeap.hpp"
#include "memory/resourceArea.hpp"
#ifdef COMPILER1
#include "c1/c1_Defs.hpp"
#endif
#ifdef COMPILER2
#include "opto/optoreg.hpp"
#endif


class OptOopMapStubGenerator : public StubCodeGenerator {
  // pmovzx to zero extend from compressed to uncompressed
  class MemSlice : public ResourceObj {
  private:
    Register _base;
    int _offset;
    int _used; // 1 bit for every dword
    int _uncompress; // 1 bit for every slot
    bool _is_read;
    MemSlice* _next;

    void add_read(int offset, int width) {
      int oops = width / heapOopSize;
      int n = offset - _offset;
      if (n > 0) {
        n /= heapOopSize;
      }

      while (oops-- > 0) {
        _used |= (1 << n);
        ++n;
      }
    }

    int max_read_offset() {
      return 16; // movdqu
    }

    bool need_movdqu() const {
      return (_used & 0xc);
    }

    bool need_movptr() const  {
      return (_used & 0x2);
    }

    void emit_read(MacroAssembler* masm) {
      if (need_movdqu()) {
        masm->movdqu(xmm1, Address(_base, _offset));
      } else if (need_movptr()) {
        masm->movptr(r13, Address(_base, _offset));
      } else {
        masm->xorptr(r13, r13);
        masm->movl(r13, Address(_base, _offset));
      }
    }

    void emit_extract(MacroAssembler* masm, int offset, int width) {
      if (need_movdqu()) {
        if (width == 8) {
          if (offset - _offset == 0) {
            masm->pextrq(rax, xmm1, 0);
          } else if (offset - _offset == 4) { /* |narrow|wide|narrow| */
            masm->pextrd(rax, xmm1, 1);
            masm->pextrd(r13, xmm1, 2);
            masm->shlptr(r13, 32);
            masm->orptr(rax, r13);
          } else if (offset - _offset == 8) {
            masm->pextrq(rax, xmm1, 1);
          } else {
            assert(false, "");
          }
        } else if (width == 4) {
          masm->xorptr(rax, rax);
          if (offset - _offset == 0) {
            masm->pextrd(rax, xmm1, 0);
          } else if (offset - _offset == 4) {
            masm->pextrd(rax, xmm1, 1);
          } else if (offset - _offset == 8) {
            masm->pextrd(rax, xmm1, 2);
          } else if (offset - _offset == 12) {
            masm->pextrd(rax, xmm1, 3);
          } else {
            assert(false, "");
          }
        } else {
          assert(false, "");
        }
      } else if (need_movptr()) {
        if (width == 8) {
          masm->movptr(rax, r13);
        } else {
          assert(width == 4, "");
          if (offset - _offset == 0) {
            masm->movl(rax, r13);
          } else if (offset - _offset == 4) {
            masm->movptr(rax, r13);
            masm->shrptr(rax, 32);
          } else {
            assert(false, "");
          }
        }
      } else {
        assert(width == 4, "");
        masm->xorptr(rax, rax);
        masm->movl(rax, r13);
      }
    }

    void read(MacroAssembler* masm, int offset, int width) {
      // offset is offset from base
      if (!_is_read) {
        _is_read = true;
        emit_read(masm);
      }
      emit_extract(masm, offset, width);
    }

  public:
    MemSlice(Register base, int offset) : _base(base), _offset(offset), _used(0), _is_read(false), _next(NULL) {}

    MemSlice* next() { return _next; }

    void read_oop(int offset) {
      add_read(offset, 8); // find the right constants
    }

    int offset() const { 
      return _offset;
    }

    void set_next(MemSlice* slice) {
      _next = slice;
    }

    void read_narrow(MacroAssembler* masm, int offset) {
      read(masm, offset, 4);
    }

    void read_wide(MacroAssembler* masm, int offset) {
      read(masm, offset, 8);
    }

    void read_narrow(int offset) {
      add_read(offset, 4); // find the right constants
    }

    bool can_read(Register base, int offset, int width) {
      if (base != _base) {
        return false;
      }

      int diff = (offset + width) - _offset;
      if (offset - _offset < 0 || diff > max_read_offset()) {
        return false;
      }
      return true;
    }
  };

  class OMV : public ResourceObj {
  private:
    OopMapValue _omv;
    MemSlice* _memory;

    OMV* _next;
    OMV* _derived;
    OMV* _adjacent;

    int _id;
    bool _done;

    OMV* last_adjacent() {
      OMV* n = _adjacent;
      if (n == NULL) {
        return this;
      }
      while (n->_next != NULL) {
        n = n->_next;
      }

      return n;
    }

  public:
    OMV(OopMapValue omv, int id) : _omv(omv), _memory(NULL), _next(NULL), _derived(NULL), _adjacent(NULL), _id(id), _done(false) {}

    int id() { return _id; }
    OMV* next() { return _next; }
    OMV* adjacent() { return _adjacent; }
    OMV* derived() { return _derived; }
    OopMapValue omv() { return _omv; }

    bool need_derived() {
      OMV* d = _derived;
      while (d != NULL) {
        if (!d->is_done()) {
          return true;
        }
        d = d->next();
      }
      return false;
    }

    void set_memory(MemSlice* memory) {
      _memory = memory;
    }

    bool is_reg() const {
      return _omv.reg()->is_reg();
    }

    Register base() { // need something else than Register here
      return (is_reg() ? rdx : rdi);
    }

    int offset() {
      if (is_reg()) {
        return 0;
      }
      return _omv.reg()->reg2stack() * VMRegImpl::stack_slot_size;
    }

    MemSlice* memory() {
      assert(_memory != NULL, "");
      return _memory;
    }

    int number_adjacent() {
      int n = 0;
      OMV* a = _adjacent;
      while (a != NULL) {
        ++n;
        a = a->next();
      }
      return n;
    }

    void clear_done() {
      _done = false;

      if (_adjacent != NULL) {
        _adjacent->clear_done();
      }

      if (_derived != NULL) {
        _derived->clear_done();
      }

      if (_next != NULL) {
        _next->clear_done();
      }
    }

    void add_derived(OMV* o) {
      if (_derived == NULL) {
        _derived = o;
      } else {
        OMV* n = _derived;
        while (n->_next != NULL) {
          n = n->_next;
        }
        n->_next = o;
      }
    }

    void mark_done() {
      _done = true;
    }

    bool is_done() {
      return _done;
    }

    void add_adjacent(OMV* o) {
      if (_adjacent == NULL) {
        _adjacent = o;
      } else {
        OMV* n = _adjacent;
        while (n->_next != NULL) {
          n = n->_next;
        }
        n->_next = o;
      }
    }

    void set_next(OMV* o) {
      _next = o;
    }

    bool is_base(const OMV* o) const {
      return _omv.reg() == o->_omv.content_reg();
    }

    bool is_adjacent(const OMV* o) {
      if (_omv.type() != o->_omv.type()) {
        return false;
      }

      if (_omv.reg()->is_reg() || o->_omv.reg()->is_reg()) {
        return false;
      }

      OMV* adj = last_adjacent();

      int dist = (_omv.type() == OopMapValue::oop_value) ? 8 : 4;
      int v1_offset = adj->omv().reg()->reg2stack() * VMRegImpl::stack_slot_size;
      int o_offset = o->_omv.reg()->reg2stack() * VMRegImpl::stack_slot_size;

      if (o_offset > v1_offset) {
        return (o_offset - v1_offset) == dist;
      }
      return false;
    }
  };

  class OopWriter {
  public:
    ~OopWriter() {}
    virtual void write_narrow(Register reg) = 0;
    virtual void write(Register reg) = 0;
  };


private:
  MacroAssembler* _masm;
  const ImmutableOopMap& _map;
  bool _link_offset_loaded;
  bool _written_rbp_index;
  address _freeze;
  address _thaw;

  int _num_oops;
  int _num_derived;

  OMV* _head;
  MemSlice* _mem_head;
  MemSlice* _mem_last;


  void append_mem_slice(MemSlice* slice) {
    if (_mem_head == NULL) {
      _mem_head = slice;
      _mem_last = slice;
    } else {
      _mem_last->set_next(slice);
      _mem_last = slice;
    }
  }

  MemSlice* find_slice(Register base, int offset, int width) {
    MemSlice* c = _mem_head;
    while (c != NULL) {
      if (c->can_read(base, offset, width)) {
        return c;
      }
      c = c->next();
    }

    c = new MemSlice(base, offset);
    append_mem_slice(c);
    return c;
  }

  MemSlice* read_wide(Register base, int offset) {
    MemSlice* slice = find_slice(base, offset, 8); // find constant
    assert(offset - slice->offset() >= 0, "");
    slice->read_oop(offset);
    return slice;
  }

  MemSlice* read_narrow(Register base, int offset) {
    MemSlice* slice = find_slice(base, offset, 4); // find constant
    assert(offset - slice->offset() >= 0, "");
    slice->read_narrow(offset);
    return slice;
  }

public:
  OptOopMapStubGenerator(CodeBuffer* code, const ImmutableOopMap& map) : StubCodeGenerator(code), _map(map) {
    _masm = new MacroAssembler(code);
    _link_offset_loaded = false;
    _written_rbp_index = false;
    _freeze = NULL;
    _thaw = NULL;
    _head = NULL;
    _mem_head = NULL;
    _mem_last = NULL;

    _num_oops = 0;
    _num_derived = 0;

    OMV* last = NULL;
    OMV* last_insert = NULL;
    int count = 0;

    for (OopMapStream oms(&_map); !oms.is_done(); oms.next()) {
      OopMapValue omv = oms.current();
      OMV *o = new OMV(omv, count);

      if (omv.is_oop_or_narrow()) {

        if (omv.is_oop()) {
          o->set_memory(read_wide(o->base(), o->offset()));
        } else {
          // narrow
          o->set_memory(read_narrow(o->base(), o->offset()));
        }

        ++_num_oops;
        if (_head == NULL) {
          _head = o;
          last = o;
          last_insert = o;
        } else {
          if (last->is_adjacent(o)) {
            last->add_adjacent(o);
          } else {
            last->set_next(o);
            last = o;
          }
          last_insert = o;
        }
        ++count;
      } else if (omv.is_derived_oop()) {
        ++_num_derived;
        o->set_memory(read_wide(o->base(), o->offset()));
        assert(_head != NULL, "");
        assert(last != NULL, "");
        assert(last_insert != NULL, "");
        if (!last_insert->is_base(o)) {
          what(o);
        }
        assert(last_insert->is_base(o), "");
        last_insert->add_derived(o);
      }
      ++count;
    }
  }

  void what(OMV* last) {
    tty->print_cr("!omv %p", last);
  }

  address thaw_stub() {
    return _thaw;
  }

  bool has_rbp_index() {
    return _written_rbp_index;
  }

  void load_link_offset() {
    if (!_link_offset_loaded) {
      _link_offset_loaded = true;
      _masm->movptr(rdx, Address(rdx, RegisterMap::link_offset()));
    }
  }

  void store_rbp_oop(Register idx) {
    assert(_written_rbp_index == false, "");
    _masm->movl(Address(r9, 0), 1); // offset to bool has_fp_index
    _masm->movl(Address(r9, 4), idx); // offset to int fp_index
    _written_rbp_index = true;
  }

  void thaw_single_oop(OMV* o, int& pos) {
    bool has_derived = o->derived() != NULL;
    OopMapValue omv = o->omv();
    VMReg reg = omv.reg();
    // read value from array
    if (UseCompressedOops) {

      _masm->movl(rax, Address(rsi, pos));
      if (omv.type() == OopMapValue::oop_value) {
        _masm->decode_heap_oop(rax);
        if (has_derived) {
          _masm->movptr(rcx, rax);
        }
      } else if (omv.type() == OopMapValue::narrowoop_value && has_derived) {
        _masm->movptr(rcx, rax);
        _masm->decode_heap_oop(rcx);
      }

    } else {
      _masm->movptr(rax, Address(rsi, pos));
      if (has_derived) {
        _masm->movptr(rcx, rax);
      }
    }


    if (reg->is_reg()) {
      assert(reg == rbp->as_VMReg(), "must");
      //load_link_offset();
      if (omv.type() == OopMapValue::oop_value) {
        _masm->movptr(Address(rdx, 0), rax);
      } else {
        assert(UseCompressedOops, "");
        // narrow oop
        _masm->movl(Address(rdx, 0), rax);
      }

      /* test
         store_rbp_oop(r8);
         _masm->subl(r8, 1);
         */

    } else {
      int sp_offset_in_bytes = reg->reg2stack() * VMRegImpl::stack_slot_size;

      if (omv.type() == OopMapValue::oop_value) {
        _masm->movptr(Address(rdi, sp_offset_in_bytes), rax);
      } else {
        // narrow oop
        assert(UseCompressedOops, "");
        _masm->movl(Address(rdi, sp_offset_in_bytes), rax);
      }

      // index is refStack_length - index
      /* test
         _masm->movl(Address(rcx, sp_offset_in_bytes), r8);
         _masm->subl(r8, 1);
         */
    }

    if (UseCompressedOops) {
      pos += 4;
    } else {
      pos += 8;
    }
  }

  void thaw_single_derived(OopMapValue omv, Register base) {
    VMReg reg = omv.reg();
    bool derived_is_reg = false;
    // read the derived value into rax
    int derived_sp_offset_in_bytes = -1;
    if (reg->is_reg()) {
      _masm->movptr(rax, Address(rdx, 0)); // offset -> rax
      derived_is_reg = true;
    } else {
      derived_sp_offset_in_bytes = reg->reg2stack() * VMRegImpl::stack_slot_size;
      _masm->movptr(rax, Address(rdi, derived_sp_offset_in_bytes)); // offset -> rax
    }

    address narrow_oop_base = Universe::narrow_oop_base();
    assert(narrow_oop_base == (address) NULL, "");
    // maybe we need to test for is_narrow_oop_base too...

    _masm->addptr(rax, base); // offset in (base + offset)

    if (reg->is_reg()) {
      _masm->movptr(Address(rdx, 0), rax);
    } else {
      _masm->movptr(Address(rdi, derived_sp_offset_in_bytes), rax);
    }
  }

  void thaw_derived(OMV* o) {
    OMV* d = o->derived();
    if (d != NULL) {
      Label L_next;
      Register base = rcx;

      _masm->testptr(base, base);
      _masm->jcc(Assembler::zero, L_next);

      while (d) {
        thaw_single_derived(d->omv(), base);
        d = d->next();
      }

      _masm->bind(L_next);
    }
  }

  bool need_heapbase() {
    return (UseCompressedOops && Universe::narrow_oop_base() != NULL) || CheckCompressedOops;
  }

  void generate_thaw(const ImmutableOopMap& map) {
    // need to reset these first
    _link_offset_loaded = false;
    _written_rbp_index = false;
    _head->clear_done();

    _masm->align(8);
    _thaw = _masm->pc();

    if (need_heapbase()) {
      _masm->push(r12);
      _masm->reinit_heapbase();
    }

    int pos = 0;
    {

      OMV* o = _head;
      while (o) {
        thaw_single_oop(o, pos);
        thaw_derived(o);
        OMV* a = o->adjacent();
        while (a) {
          thaw_single_oop(a, pos);
          thaw_derived(a);
          a = a->next();
        }
        o = o->next();
      }
    }

    if (need_heapbase()) {
      _masm->pop(r12);
    }
    _masm->movl(rax, map.num_oops());
    _masm->ret(0);
  }

  void freeze_single_oop(OMV* o, int& pos, OopWriter& writer) {
    if (o->is_done()) {
      return;
    }
    o->mark_done();

    OopMapValue omv = o->omv();
    VMReg reg = omv.reg();

    OMV* d = o->derived();
    if (reg->is_reg()) {
      assert(reg == rbp->as_VMReg(), "must");
      load_link_offset();
      if (omv.type() == OopMapValue::oop_value) {
        o->memory()->read_wide(_masm, 0);

        //_masm->movptr(rax, Address(rdx, 0));

        if (d != NULL) {
          assert(_map.has_derived(), "");
          _masm->movptr(r14, rax);
        }

        if (UseCompressedOops) {
          // compress
          _masm->encode_heap_oop(rax);
          writer.write_narrow(rax);
          //_masm->movl(Address(rsi, pos), rax);
        } else {
          writer.write(rax);
          //_masm->movptr(Address(rsi, pos), rax);
        }
      } else {
        assert(UseCompressedOops, "");
        // narrow oop
        o->memory()->read_narrow(_masm, 0);
        //_masm->movl(rax, Address(rdx, 0));
        if (d != NULL) {
          guarantee(false, "should not have narrow as base");
          assert(_map.has_derived(), "");
          _masm->movptr(r14, rax);
          _masm->decode_heap_oop(r14);
        }
        writer.write_narrow(rax);
        //_masm->movl(Address(rsi, pos), rax);
      }

      /* test
         store_rbp_oop(r8);
         _masm->subl(r8, 1);
         */

    } else {
      int sp_offset_in_bytes = reg->reg2stack() * VMRegImpl::stack_slot_size;

      if (omv.type() == OopMapValue::oop_value) {
        //_masm->movptr(rax, Address(rdi, sp_offset_in_bytes));
        o->memory()->read_wide(_masm, sp_offset_in_bytes);

        if (d != NULL) {
          assert(_map.has_derived(), "");
          _masm->movptr(r14, rax);
        }

        if (UseCompressedOops) {
          // compress
          _masm->encode_heap_oop(rax);
          writer.write_narrow(rax);
          //_masm->movl(Address(rsi, pos), rax);
        } else {
          writer.write(rax);
          //_masm->movptr(Address(rsi, pos), rax);
        }
      } else {
        // narrow oop
        assert(UseCompressedOops, "");
        o->memory()->read_narrow(_masm, sp_offset_in_bytes);
        //_masm->movl(rax, Address(rdi, sp_offset_in_bytes));
        writer.write_narrow(rax);
        //_masm->movl(Address(rsi, pos), rax);

        if (d != NULL) {
          guarantee(false, "should not have narrow as base");
          assert(_map.has_derived(), "");
          _masm->movptr(r14, rax);
          _masm->decode_heap_oop(r14);
        }
      }

      // index is refStack_length - index
      /* test
         _masm->movl(Address(rcx, sp_offset_in_bytes), r8);
         _masm->subl(r8, 1);
         */
    }
    /*if (UseCompressedOops) {
      pos += 4;
    } else {
      pos += 8;
    }
    */

  }

  void freeze_single_derived(OMV* o) {
    if (o->is_done()) {
      return;
    }
    o->mark_done();

    OopMapValue omv = o->omv();
    VMReg reg = omv.reg();
    bool derived_is_reg = false;
    // read the derived value into rax
    int derived_sp_offset_in_bytes = -1;
    if (reg->is_reg()) {
      load_link_offset();
      o->memory()->read_wide(_masm, 0);
      //_masm->movptr(rax, Address(rdx, 0));
      derived_is_reg = true;
    } else {
      derived_sp_offset_in_bytes = reg->reg2stack() * VMRegImpl::stack_slot_size;
      o->memory()->read_wide(_masm, derived_sp_offset_in_bytes);
      //_masm->movptr(rax, Address(rdi, derived_sp_offset_in_bytes));
    }

    // read the base value into rax
    address narrow_oop_base = Universe::narrow_oop_base();
    assert(narrow_oop_base == (address) NULL, "");
    // maybe we need to test for is_narrow_oop_base too...

    _masm->subptr(rax, r14); // derived to rax (derived - base)

    if (reg->is_reg()) {
      store_rbp_oop(rax);
    } else {
      _masm->movptr(Address(rcx, derived_sp_offset_in_bytes), rax);
    }
  }

  void freeze(OMV* o, int& pos, OopWriter& writer) {
    freeze_single_oop(o, pos, writer);
    freeze_derived(o);

    OMV* a = o->adjacent();
    while (a != NULL) {
      freeze_single_oop(a, pos, writer);
      freeze_derived(a);
      a = a->next();
    }
  }

  void freeze_all_derived(OMV* d) {
    while (d != NULL) {
      freeze_single_derived(d);
      d = d->next();
    }
  }

  void freeze_derived(OMV* o) {
    if (o->need_derived()) {
      OMV* d = o->derived();
      Label L_next;

      _masm->testptr(r14, r14);
      _masm->jcc(Assembler::zero, L_next);

      freeze_all_derived(d);

      _masm->bind(L_next);
    }
  }

  class BatchWriter : public OopWriter {
  private:
    MacroAssembler* _masm;
    int _num_oops;
    int _pos;
    int _written;

    int _size;
    int _left;
    int _slot;

    int next_size() const {
      int bytes_remaining = 0;
      int left = _num_oops - _written;
      if (UseCompressedOops) {
        bytes_remaining = left * 4;
      } else {
        bytes_remaining = left * 8;
      }

      if (bytes_remaining >= 16) {
        return 16;
      } else if (bytes_remaining >= 8) {
        return 8;
      } else if (bytes_remaining >= 4) {
        return 4;
      } else {
        return 0;
      }
    }

    bool is_xmm() {
      return _size > 8;
    }

    bool is_quad() {
      return _size == 8;
    }

    bool is_word() {
      return _size == 4;
    }

    void write_narrow_xmm(Register reg) {
      _masm->pinsrd(xmm0, reg, _slot);
    }

    void write_narrow_quad(Register reg) {
      if (_slot == 0) {
        _masm->movl(rbx, reg);
      } else if (_slot == 1) {
        _masm->shlptr(reg, 32);
        _masm->orptr(reg, rbx);
      } else {
        assert(false, "");
      }
    }

    void write_narrow_word(Register reg) {
      // nop
    }

  public:
    BatchWriter(MacroAssembler* masm, int num_oops) : _masm(masm), _num_oops(num_oops), _pos(0), _written(0), _slot(0) {
      _left = _size = next_size();
    }

    void finish() {
      if (is_xmm()) {
        _masm->movdqu(Address(rsi, _pos), xmm0);
      } else if (is_quad()) {
        _masm->movptr(Address(rsi, _pos), rax);
      } else if (is_word()) {
        _masm->movl(Address(rsi, _pos), rax);
      }
      _pos += _size;
      _slot = 0;
      _left = _size = next_size();
    }

    void written(int bytes) {
      _written += 1;
      _left -= bytes;
      _slot += 1;

      if (_left == 0) {
        finish();
      }
    }

    virtual void write_narrow(Register reg) {
      if (is_xmm()) {
        write_narrow_xmm(reg);
      } else if (is_quad()) {
        write_narrow_quad(reg);
      } else if (is_word()) {
        write_narrow_word(reg);
      } else {
        assert(false, "");
      }
      written(4);
    }

    virtual void write(Register reg) {
      assert(false, "unimplemented");
      written(8);
    }
  };

  class SingleWriter : public OopWriter {
  private:
    MacroAssembler* _masm;
    int _pos;

  public:
    SingleWriter(MacroAssembler* masm) : _masm(masm), _pos(0) {}
    virtual void write_narrow(Register reg) {
      _masm->movl(Address(rsi, _pos), reg);
      _pos += 4;
    }
    virtual void write(Register reg) {
      _masm->movptr(Address(rsi, _pos), reg);
      _pos += 8;
    }
  };

  void generate_freeze(const ImmutableOopMap& map) {
    _masm->align(8);
    _freeze = _masm->pc();

    _masm->push(rbx);

    /* rdi is source (rsp), rsi is destination (first address), rdx (rbp address), rcx (hstack), r8 (initial index (refStack_length - index) ), r9 (fp_oop_info) */
    if (need_heapbase()) {
      _masm->push(r12);
      _masm->reinit_heapbase();
    }

    _masm->push(r13);

    if (map.has_derived()) {
      _masm->push(r14);
    }

    int pos = 0;
    {
      BatchWriter writer(_masm, _num_oops);
      OMV* o = _head;
      while (o != NULL) {
        freeze(o, pos, writer);
        o = o->next();
      }
    }

    if (map.has_derived()) {
      _masm->pop(r14);
    }
    _masm->pop(r13);
    if (need_heapbase()) {
      _masm->pop(r12);
    }

    _masm->pop(rbx);

    _masm->movl(rax, map.num_oops());
    _masm->ret(0);
  }
};


class OopMapStubGeneratorX86 : public StubCodeGenerator {
private:
  MacroAssembler* _masm;
  bool _link_offset_loaded;
  bool _written_rbp_index;
  address _freeze;
  address _thaw;
  intptr_t _freeze_len, _thaw_len;

public:
  OopMapStubGeneratorX86(CodeBuffer* code) : StubCodeGenerator(code) {
    _masm = new MacroAssembler(code);
    _link_offset_loaded = false;
    _written_rbp_index = false;
    _freeze = NULL;
    _thaw = NULL;
  }

  intptr_t thaw_length() {
    return _thaw_len;
  }

  address thaw_stub() {
    return _thaw;
  }

  bool has_rbp_index() {
    return _written_rbp_index;
  }

  void load_link_offset() {
    if (!_link_offset_loaded) {
      _link_offset_loaded = true;
      _masm->movptr(rdx, Address(rdx, RegisterMap::link_offset()));
    }
  }

  void store_rbp_oop(Register idx) {
    assert(_written_rbp_index == false, "");
    _masm->movl(Address(r9, 0), 1); // offset to bool has_fp_index
    _masm->movl(Address(r9, 4), idx); // offset to int fp_index
    _written_rbp_index = true;
  }

  void generate_thaw(const ImmutableOopMap& map) {
    // need to reset these first
    _link_offset_loaded = false;
    _written_rbp_index = false;

    _thaw = _masm->pc();

    if (UseCompressedOops) {
      _masm->push(r12);
      _masm->reinit_heapbase();
    }

    int pos = 0;
    {
      int mask = OopMapValue::oop_value | OopMapValue::narrowoop_value;
      for (OopMapStream oms(&map,mask); !oms.is_done(); oms.next()) {
        OopMapValue omv = oms.current();
        VMReg reg = omv.reg();

        // read value from array
        if (UseCompressedOops) {
          _masm->movl(rax, Address(rsi, pos));

          if (omv.type() == OopMapValue::oop_value) {
            _masm->decode_heap_oop(rax);
          }
        } else {
          _masm->movptr(rax, Address(rsi, pos));
        }

        if (reg->is_reg()) {
          assert(reg == rbp->as_VMReg(), "must");
          //load_link_offset();
          if (omv.type() == OopMapValue::oop_value) {
            _masm->movptr(Address(rdx, 0), rax);
          } else {
            assert(UseCompressedOops, "");
            // narrow oop
            _masm->movl(Address(rdx, 0), rax);
          }

          /* test
          store_rbp_oop(r8);
          _masm->subl(r8, 1);
          */

        } else {
          int sp_offset_in_bytes = reg->reg2stack() * VMRegImpl::stack_slot_size;

          if (omv.type() == OopMapValue::oop_value) {
            _masm->movptr(Address(rdi, sp_offset_in_bytes), rax);
          } else {
            // narrow oop
            assert(UseCompressedOops, "");
            _masm->movl(Address(rdi, sp_offset_in_bytes), rax);
          }

          // index is refStack_length - index
          /* test
          _masm->movl(Address(rcx, sp_offset_in_bytes), r8);
          _masm->subl(r8, 1);
          */
        }
        if (UseCompressedOops) {
          pos += 4;
        } else {
          pos += 8;
        }
      }
    }

    {
      for (OopMapStream oms(&map,OopMapValue::derived_oop_value); !oms.is_done(); oms.next()) {
        OopMapValue omv = oms.current();
        VMReg reg = omv.reg();
        bool derived_is_reg = false;
        // read the derived value into rax
        int derived_sp_offset_in_bytes = -1;
        if (reg->is_reg()) {
          _masm->movptr(rax, Address(rdx, 0)); // offset -> rax
          derived_is_reg = true;
        } else {
          derived_sp_offset_in_bytes = reg->reg2stack() * VMRegImpl::stack_slot_size;
          _masm->movptr(rax, Address(rdi, derived_sp_offset_in_bytes)); // offset -> rax
        }

        // read the base value into rcx
        VMReg content_reg = omv.content_reg();
        if (content_reg->is_reg()) {
          guarantee(content_reg == rbp->as_VMReg(), "must");
          guarantee(derived_is_reg == false, "must");
          _masm->movptr(rcx, Address(rdx, 0)); // base -> rcx
        } else {
          int sp_offset_in_bytes = content_reg->reg2stack() * VMRegImpl::stack_slot_size;
          _masm->movptr(rcx, Address(rdi, sp_offset_in_bytes)); // base -> rdx
        }

        address narrow_oop_base = Universe::narrow_oop_base();
        assert(narrow_oop_base == (address) NULL, "");
        // maybe we need to test for is_narrow_oop_base too...
        Label L_next;

        _masm->testptr(rcx, rcx);
        _masm->jcc(Assembler::zero, L_next);

        _masm->addptr(rcx, rax); // offset in (base + offset)

        if (reg->is_reg()) {
          _masm->movptr(Address(rdx, 0), rcx);
        } else {
          _masm->movptr(Address(rdi, derived_sp_offset_in_bytes), rcx);
        }

        _masm->bind(L_next);
      }
    }

    if (UseCompressedOops) {
      _masm->pop(r12);
    }
    _masm->movl(rax, map.num_oops());
    _masm->ret(0);

    _thaw_len = (intptr_t) _masm->pc() - (intptr_t) _thaw;
  }

  void generate_freeze(const ImmutableOopMap& map) {
    _masm->align(8);
    _freeze = _masm->pc();

    /* rdi is source (rsp), rsi is destination (first address), rdx (rbp address), rcx (hstack), r8 (initial index (refStack_length - index) ), r9 (fp_oop_info) */
    if (UseCompressedOops) {
      _masm->push(r12);
      _masm->reinit_heapbase();
    }

    if (map.has_derived()) {
      _masm->push(r11);
    }

    int pos = 0;
    {
      int mask = OopMapValue::oop_value | OopMapValue::narrowoop_value;
      for (OopMapStream oms(&map,mask); !oms.is_done(); oms.next()) {
        OopMapValue omv = oms.current();
        VMReg reg = omv.reg();

        if (reg->is_reg()) {
          assert(reg == rbp->as_VMReg(), "must");
          load_link_offset();
          if (omv.type() == OopMapValue::oop_value) {
            _masm->movptr(rax, Address(rdx, 0));
            if (UseCompressedOops) {
              // compress
              _masm->encode_heap_oop(rax);
              _masm->movl(Address(rsi, pos), rax);
            } else {
              _masm->movptr(Address(rsi, pos), rax);
            }
          } else {
            assert(UseCompressedOops, "");
            // narrow oop
            _masm->movl(rax, Address(rdx, 0));
            _masm->movl(Address(rsi, pos), rax);
          }

          /* test
          store_rbp_oop(r8);
          _masm->subl(r8, 1);
          */

        } else {
          int sp_offset_in_bytes = reg->reg2stack() * VMRegImpl::stack_slot_size;

          if (omv.type() == OopMapValue::oop_value) {
            _masm->movptr(rax, Address(rdi, sp_offset_in_bytes));
            if (UseCompressedOops) {
              // compress
              _masm->encode_heap_oop(rax);
              _masm->movl(Address(rsi, pos), rax);
            } else {
              _masm->movptr(Address(rsi, pos), rax);
            }
          } else {
            // narrow oop
            assert(UseCompressedOops, "");
            _masm->movl(rax, Address(rdi, sp_offset_in_bytes));
            _masm->movl(Address(rsi, pos), rax);
          }

          // index is refStack_length - index
          /* test
          _masm->movl(Address(rcx, sp_offset_in_bytes), r8);
          _masm->subl(r8, 1);
          */
        }
        if (UseCompressedOops) {
          pos += 4;
        } else {
          pos += 8;
        }
      }
    }

    {
      for (OopMapStream oms(&map,OopMapValue::derived_oop_value); !oms.is_done(); oms.next()) {
        OopMapValue omv = oms.current();
        VMReg reg = omv.reg();
        bool derived_is_reg = false;
        // read the derived value into r11
        int derived_sp_offset_in_bytes = -1;
        if (reg->is_reg()) {
          load_link_offset();
          _masm->movptr(r11, Address(rdx, 0));
          derived_is_reg = true;
        } else {
          derived_sp_offset_in_bytes = reg->reg2stack() * VMRegImpl::stack_slot_size;
          _masm->movptr(r11, Address(rdi, derived_sp_offset_in_bytes));
        }

        // read the base value into rax
        VMReg content_reg = omv.content_reg();
        if (content_reg->is_reg()) {
          load_link_offset();
          guarantee(content_reg == rbp->as_VMReg(), "must");
          guarantee(derived_is_reg == false, "must");
          _masm->movptr(rax, Address(rdx, 0));
        } else {
          int sp_offset_in_bytes = content_reg->reg2stack() * VMRegImpl::stack_slot_size;
          _masm->movptr(rax, Address(rdi, sp_offset_in_bytes));
        }

        address narrow_oop_base = Universe::narrow_oop_base();
        assert(narrow_oop_base == (address) NULL, "");
        // maybe we need to test for is_narrow_oop_base too...
        Label L_next;

        _masm->testptr(rax, rax);
        _masm->jcc(Assembler::zero, L_next);

        _masm->subptr(r11, rax); // derived to r11 (derived - base)

        if (reg->is_reg()) {
          store_rbp_oop(r11);
        } else {
          _masm->movptr(Address(rcx, derived_sp_offset_in_bytes), r11);
        }

        _masm->bind(L_next);
      }
    }

    if (map.has_derived()) {
      _masm->pop(r11);
    }
    if (UseCompressedOops) {
      _masm->pop(r12);
    }
    _masm->movl(rax, map.num_oops());
    _masm->ret(0);
    _freeze_len = (intptr_t) _masm->pc() - (intptr_t) _freeze;
  }
};

bool OopMapStubGenerator::generate() {
  ResourceMark rm;

  int size = 64 + (_oopmap.count() * 6 * 15) + (CheckCompressedOops ? 2048 : 0); // worst case, 6 instructions per oop, 15 bytes per instruction;

  _blob = BufferBlob::create("oopmap stub", size);
  if (_blob == NULL) {
    return false;
  }

  CodeBuffer buf(_blob);
  //OptOopMapStubGenerator cgen(&buf, this);
  OopMapStubGeneratorX86 cgen(&buf);
  cgen.generate_freeze(_oopmap);
  cgen.generate_thaw(_oopmap);

  _freeze_stub = _blob->code_begin();
  _thaw_stub = cgen.thaw_stub();

  return true;
}

void OopMapStubGenerator::free() {
  if (_blob != NULL) {
    BufferBlob::free(_blob);
    _blob = NULL;
  }
  _freeze_stub = NULL;
  _thaw_stub = NULL;
}
