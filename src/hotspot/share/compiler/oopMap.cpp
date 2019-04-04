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
#include "code/codeBlob.hpp"
#include "code/codeCache.hpp"
#include "code/nmethod.hpp"
#include "code/scopeDesc.hpp"
#include "compiler/oopMap.hpp"
#include "compiler/oopMap.inline.hpp"
#include "gc/shared/collectedHeap.hpp"
#include "logging/log.hpp"
#include "logging/logStream.hpp"
#include "memory/allocation.inline.hpp"
#include "memory/iterator.hpp"
#include "memory/resourceArea.hpp"
#include "runtime/atomic.hpp"
#include "runtime/frame.inline.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/signature.hpp"
#include "utilities/align.hpp"
#ifdef COMPILER1
#include "c1/c1_Defs.hpp"
#endif
#ifdef COMPILER2
#include "opto/optoreg.hpp"
#endif

// OopMapStream

OopMapStream::OopMapStream(const OopMap* oop_map, int oop_types_mask)
  : _stream(oop_map->write_stream()->buffer()) {
  // _stream = new CompressedReadStream();
  _mask = oop_types_mask;
  _size = oop_map->omv_count();
  _position = 0;
  _valid_omv = false;
}

OopMapStream::OopMapStream(const ImmutableOopMap* oop_map, int oop_types_mask)
  : _stream(oop_map->data_addr()) {
  // _stream = new CompressedReadStream(oop_map->data_addr());
  _mask = oop_types_mask;
  _size = oop_map->count();
  _position = 0;
  _valid_omv = false;
}

void OopMapStream::find_next() {
  while(_position++ < _size) {
    _omv.read_from(&_stream);
    if(((int)_omv.type() & _mask) > 0) {
      _valid_omv = true;
      return;
    }
  }
  _valid_omv = false;
}


// OopMap

// frame_size units are stack-slots (4 bytes) NOT intptr_t; we can name odd
// slots to hold 4-byte values like ints and floats in the LP64 build.
OopMap::OopMap(int frame_size, int arg_count) {
  // OopMaps are usually quite so small, so pick a small initial size
  set_write_stream(new CompressedWriteStream(32));
  set_omv_count(0);
  _num_oops = 0;
  _index = -1;

#ifdef ASSERT
  _locs_length = VMRegImpl::stack2reg(0)->value() + frame_size + arg_count;
  _locs_used   = NEW_RESOURCE_ARRAY(OopMapValue::oop_types, _locs_length);
  for(int i = 0; i < _locs_length; i++) _locs_used[i] = OopMapValue::unused_value;
#endif
}


OopMap::OopMap(OopMap::DeepCopyToken, OopMap* source) {
  // This constructor does a deep copy
  // of the source OopMap.
  set_write_stream(new CompressedWriteStream(source->omv_count() * 2));
  set_omv_count(0);
  set_offset(source->offset());
  _num_oops = source->num_oops();
  _index = -1;

#ifdef ASSERT
  _locs_length = source->_locs_length;
  _locs_used = NEW_RESOURCE_ARRAY(OopMapValue::oop_types, _locs_length);
  for(int i = 0; i < _locs_length; i++) _locs_used[i] = OopMapValue::unused_value;
#endif

  // We need to copy the entries too.
  for (OopMapStream oms(source); !oms.is_done(); oms.next()) {
    OopMapValue omv = oms.current();
    omv.write_on(write_stream());
    increment_count();
  }
}


OopMap* OopMap::deep_copy() {
  return new OopMap(_deep_copy_token, this);
}

void OopMap::copy_data_to(address addr) const {
  memcpy(addr, write_stream()->buffer(), write_stream()->position());
}

class OopMapSort {
private:
  const OopMap* _map;
  OopMapValue* _values;
  int _count;

public:
  OopMapSort(const OopMap* map) : _map(map), _count(0) {
    _values = NEW_RESOURCE_ARRAY(OopMapValue, _map->omv_count());
  }

  void sort();

  void print();

  void write(CompressedWriteStream* stream) {
    for (int i = 0; i < _count; ++i) {
      _values[i].write_on(stream);
    }
  }

private:
  int find_derived_position(OopMapValue omv, int start) {
    assert(omv.type() == OopMapValue::derived_oop_value, "");

    VMReg base = omv.content_reg();
    int i = start;

    for (; i < _count; ++i) {
      if (base == _values[i].reg()) {

        for (int n = i + 1; n < _count; ++n) {
          if (_values[i].type() != OopMapValue::derived_oop_value || _values[i].content_reg() != base) {
            return n;
          }

          if (derived_cost(_values[i]) > derived_cost(omv)) {
            return n;
          }
        }
        return _count;
      }
    }

    assert(false, "failed to find base");
    return -1;
  }

  int find_position(OopMapValue omv, int start) {
    assert(omv.type() != OopMapValue::derived_oop_value, "");

    int i = start;
    for (; i < _count; ++i) {
      if (omv_cost(_values[i]) > omv_cost(omv)) {
        return i;
      }
    }
    assert(i < _map->omv_count(), "bounds check");
    return i;
  }

  void insert(OopMapValue value, int pos) {
    assert(pos >= 0 && pos < _map->omv_count(), "bounds check");
    assert(pos <= _count, "sanity");

    if (pos < _count) {
      OopMapValue prev = _values[pos];

      for (int i = pos; i < _count; ++i) {
        OopMapValue tmp = _values[i+1];
        _values[i+1] = prev;
        prev = tmp;
      }
    }
    _values[pos] = value;

    ++_count;
  }

  int omv_cost(OopMapValue omv) {
    assert(omv.type() == OopMapValue::oop_value || omv.type() == OopMapValue::narrowoop_value, "");
    return reg_cost(omv.reg());
  }

  int reg_cost(VMReg reg) {
    if (reg->is_reg()) {
      return 0;
    }
    return reg->reg2stack() * VMRegImpl::stack_slot_size;
  }

  int derived_cost(OopMapValue omv) {
    return reg_cost(omv.reg());
  }
};

void OopMapSort::sort() {
  for (OopMapStream oms(_map); !oms.is_done(); oms.next()) {
    OopMapValue omv = oms.current();
    assert(omv.type() == OopMapValue::oop_value || omv.type() == OopMapValue::narrowoop_value || omv.type() == OopMapValue::derived_oop_value || omv.type() == OopMapValue::callee_saved_value, "");
  }

  for (OopMapStream oms(_map, OopMapValue::callee_saved_value); !oms.is_done(); oms.next()) {
    insert(oms.current(), _count);
  }

  int start = _count;
  for (OopMapStream oms(_map, OopMapValue::oop_value | OopMapValue::narrowoop_value); !oms.is_done(); oms.next()) {
    OopMapValue omv = oms.current();
    int pos = find_position(omv, start);
    insert(omv, pos);
  }

  for (OopMapStream oms(_map, OopMapValue::derived_oop_value); !oms.is_done(); oms.next()) {
    OopMapValue omv = oms.current();
    int pos = find_derived_position(omv, start);
    assert(pos > 0, "");
    insert(omv, pos);
  }
}

void OopMapSort::print() {
  for (int i = 0; i < _count; ++i) {
    OopMapValue omv = _values[i];
    if (omv.type() == OopMapValue::oop_value || omv.type() == OopMapValue::narrowoop_value) {
      if (omv.reg()->is_reg()) {
        tty->print_cr("[%c][%d] -> reg (%ld)", omv.type() == OopMapValue::narrowoop_value ? 'n' : 'o', i, omv.reg()->value());
      } else {
        tty->print_cr("[%c][%d] -> stack (%lx)", omv.type() == OopMapValue::narrowoop_value ? 'n' : 'o', i, omv.reg()->reg2stack() * VMRegImpl::stack_slot_size);
      }
    } else {
      if (omv.content_reg()->is_reg()) {
        tty->print_cr("[d][%d] -> reg (%ld) stack (%lx)", i, omv.content_reg()->value(), omv.reg()->reg2stack() * VMRegImpl::stack_slot_size);
      } else if (omv.reg()->is_reg()) {
        tty->print_cr("[d][%d] -> stack (%lx) reg (%ld)", i, omv.content_reg()->reg2stack() * VMRegImpl::stack_slot_size, omv.reg()->value());
      } else {
        int derived_offset = omv.reg()->reg2stack() * VMRegImpl::stack_slot_size;
        int base_offset = omv.content_reg()->reg2stack() * VMRegImpl::stack_slot_size;
        tty->print_cr("[d][%d] -> stack (%x) stack (%x)", i, base_offset, derived_offset);
      }
    }
  }
}

void OopMap::copy_and_sort_data_to(address addr) const {
  OopMapSort sort(this);
  sort.sort();
  CompressedWriteStream* stream = new CompressedWriteStream(_write_stream->position());
  sort.write(stream);

  assert(stream->position() == write_stream()->position(), "");
  memcpy(addr, stream->buffer(), stream->position());
  //copy_data_to(addr);
  //sort.print();
}

int OopMap::heap_size() const {
  int size = sizeof(OopMap);
  int align = sizeof(void *) - 1;
  size += write_stream()->position();
  // Align to a reasonable ending point
  size = ((size+align) & ~align);
  return size;
}

// frame_size units are stack-slots (4 bytes) NOT intptr_t; we can name odd
// slots to hold 4-byte values like ints and floats in the LP64 build.
void OopMap::set_xxx(VMReg reg, OopMapValue::oop_types x, VMReg optional) {

  assert(reg->value() < _locs_length, "too big reg value for stack size");
  assert( _locs_used[reg->value()] == OopMapValue::unused_value, "cannot insert twice" );
  debug_only( _locs_used[reg->value()] = x; )

  OopMapValue o(reg, x);

  if(x == OopMapValue::callee_saved_value) {
    // This can never be a stack location, so we don't need to transform it.
    assert(optional->is_reg(), "Trying to callee save a stack location");
    o.set_content_reg(optional);
  } else if(x == OopMapValue::derived_oop_value) {
    o.set_content_reg(optional);
  }

  o.write_on(write_stream());
  increment_count();
  if (x == OopMapValue::oop_value || x == OopMapValue::narrowoop_value)
    increment_num_oops();
}


void OopMap::set_oop(VMReg reg) {
  set_xxx(reg, OopMapValue::oop_value, VMRegImpl::Bad());
}


void OopMap::set_value(VMReg reg) {
  // At this time, we don't need value entries in our OopMap.
  // set_xxx(reg, OopMapValue::live_value, VMRegImpl::Bad());
}


void OopMap::set_narrowoop(VMReg reg) {
  set_xxx(reg, OopMapValue::narrowoop_value, VMRegImpl::Bad());
}


void OopMap::set_callee_saved(VMReg reg, VMReg caller_machine_register ) {
  set_xxx(reg, OopMapValue::callee_saved_value, caller_machine_register);
}


void OopMap::set_derived_oop(VMReg reg, VMReg derived_from_local_register ) {
  if( reg == derived_from_local_register ) {
    // Actually an oop, derived shares storage with base,
    set_oop(reg);
  } else {
    set_xxx(reg, OopMapValue::derived_oop_value, derived_from_local_register);
  }
}

// OopMapSet

OopMapSet::OopMapSet() {
  set_om_size(MinOopMapAllocation);
  set_om_count(0);
  OopMap** temp = NEW_RESOURCE_ARRAY(OopMap*, om_size());
  set_om_data(temp);
}


void OopMapSet::grow_om_data() {
  int new_size = om_size() * 2;
  OopMap** new_data = NEW_RESOURCE_ARRAY(OopMap*, new_size);
  memcpy(new_data,om_data(),om_size() * sizeof(OopMap*));
  set_om_size(new_size);
  set_om_data(new_data);
}

int OopMapSet::add_gc_map(int pc_offset, OopMap *map ) {
  assert(om_size() != -1,"Cannot grow a fixed OopMapSet");

  if(om_count() >= om_size()) {
    grow_om_data();
  }
  map->set_offset(pc_offset);

#ifdef ASSERT
  if(om_count() > 0) {
    OopMap* last = at(om_count()-1);
    if (last->offset() == map->offset() ) {
      fatal("OopMap inserted twice");
    }
    if(last->offset() > map->offset()) {
      tty->print_cr( "WARNING, maps not sorted: pc[%d]=%d, pc[%d]=%d",
                      om_count(),last->offset(),om_count()+1,map->offset());
    }
  }
#endif // ASSERT

  int index = om_count();
  set(index,map);
  map->_index = index;
  increment_count();
  return index;
}


int OopMapSet::heap_size() const {
  // The space we use
  int size = sizeof(OopMap);
  int align = sizeof(void *) - 1;
  size = ((size+align) & ~align);
  size += om_count() * sizeof(OopMap*);

  // Now add in the space needed for the indivdiual OopMaps
  for(int i=0; i < om_count(); i++) {
    size += at(i)->heap_size();
  }
  // We don't need to align this, it will be naturally pointer aligned
  return size;
}


OopMap* OopMapSet::singular_oop_map() {
  guarantee(om_count() == 1, "Make sure we only have a single gc point");
  return at(0);
}

OopMap* OopMapSet::find_map_at_offset(int pc_offset) const {
  int i, len = om_count();
  assert( len > 0, "must have pointer maps" );

  // Scan through oopmaps. Stop when current offset is either equal or greater
  // than the one we are looking for.
  for( i = 0; i < len; i++) {
    if( at(i)->offset() >= pc_offset )
      break;
  }

  assert( i < len, "oopmap not found" );

  OopMap* m = at(i);
  assert( m->offset() == pc_offset, "oopmap not found" );
  return m;
}

void AddDerivedOop::do_derived_oop(oop* base, oop* derived) {
#if !defined(TIERED) && !INCLUDE_JVMCI
  COMPILER1_PRESENT(ShouldNotReachHere();)
#endif // !defined(TIERED) && !INCLUDE_JVMCI
#if COMPILER2_OR_JVMCI
  DerivedPointerTable::add(derived, base);
#endif // COMPILER2_OR_JVMCI
}



void OopMapSet::oops_do(const frame *fr, const RegisterMap* reg_map, OopClosure* f, DerivedOopClosure* df) {
  // add_derived_oop: add derived oops to a table
  find_map(fr)->oops_do(fr, reg_map, f, df);
  // all_do(fr, reg_map, f, df != NULL ? df : &add_derived_oop, &do_nothing_cl);
}

// void OopMapSet::all_do(const frame *fr, const RegisterMap *reg_map,
//                        OopClosure* oop_fn, DerivedOopClosure* derived_oop_fn,
//                        OopClosure* value_fn) {
//   find_map(fr)->oops_do(fr, reg_map, oop_fn, derived_oop_fn, value_fn);
// }

ExplodedOopMap::ExplodedOopMap(const ImmutableOopMap* oopMap) {
  _oopValues = copyOopMapValues(oopMap, OopMapValue::oop_value | OopMapValue::narrowoop_value, &_nrOopValues);
  _calleeSavedValues = copyOopMapValues(oopMap, OopMapValue::callee_saved_value, &_nrCalleeSavedValuesCount);
  _derivedValues = copyOopMapValues(oopMap, OopMapValue::derived_oop_value, &_nrDerivedValues);
}

OopMapValue* ExplodedOopMap::values(int mask) {
  if (mask == (OopMapValue::oop_value | OopMapValue::narrowoop_value)) {
    return _oopValues;
  } else if (mask == OopMapValue::callee_saved_value) {
    return _calleeSavedValues;
  } else if (mask == OopMapValue::derived_oop_value) {
    return _derivedValues;
  } else {
    guarantee(false, "new type?");
    return NULL;
  }
}

int ExplodedOopMap::count(int mask) {
  if (mask == (OopMapValue::oop_value | OopMapValue::narrowoop_value)) {
    return _nrOopValues;
  } else if (mask == OopMapValue::callee_saved_value) {
    return _nrCalleeSavedValuesCount;
  } else if (mask == OopMapValue::derived_oop_value) {
    return _nrDerivedValues;
  } else {
    guarantee(false, "new type?");
    return 0;
  }
}

OopMapValue* ExplodedOopMap::copyOopMapValues(const ImmutableOopMap* oopMap, int mask, int* nr) {
  OopMapValue omv;
  int count = 0;
  // We want coop and oop oop_types
  for (OopMapStream oms(oopMap,mask); !oms.is_done(); oms.next()) {
    ++count;
  }
  *nr = count;

  OopMapValue* values = (OopMapValue*) NEW_C_HEAP_ARRAY(unsigned char, sizeof(OopMapValue) * count, mtCode);

  int i = 0;
  for (OopMapStream oms(oopMap,mask); !oms.is_done(); oms.next()) {
    assert(i < count, "overflow");
    values[i] = oms.current();
    i++;
  }

  i = 0;
  for (OopMapStream oms(oopMap,mask); !oms.is_done(); oms.next()) {
    assert(i < count, "overflow");
    assert(values[i].equals(oms.current()), "must");
    i++;
  }

  return values;
}

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
  const ImmutableOopMap* _map;
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
  OptOopMapStubGenerator(CodeBuffer* code, const ImmutableOopMap* map) : StubCodeGenerator(code), _map(map) {
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

    for (OopMapStream oms(map); !oms.is_done(); oms.next()) {
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

  void generate_thaw(const ImmutableOopMap* map) {
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
    _masm->movl(rax, map->num_oops());
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
          assert(_map->has_derived(), "");
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
          assert(_map->has_derived(), "");
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
          assert(_map->has_derived(), "");
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
          assert(_map->has_derived(), "");
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

  void generate_freeze(const ImmutableOopMap* map) {
    _masm->align(8);
    _freeze = _masm->pc();

    _masm->push(rbx);

    /* rdi is source (rsp), rsi is destination (first address), rdx (rbp address), rcx (hstack), r8 (initial index (refStack_length - index) ), r9 (fp_oop_info) */
    if (need_heapbase()) {
      _masm->push(r12);
      _masm->reinit_heapbase();
    }

    _masm->push(r13);

    if (map->has_derived()) {
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

    if (map->has_derived()) {
      _masm->pop(r14);
    }
    _masm->pop(r13);
    if (need_heapbase()) {
      _masm->pop(r12);
    }

    _masm->pop(rbx);

    _masm->movl(rax, map->num_oops());
    _masm->ret(0);
  }
};


class OopMapStubGenerator : public StubCodeGenerator {
private:
  MacroAssembler* _masm;
  bool _link_offset_loaded;
  bool _written_rbp_index;
  address _freeze;
  address _thaw;
  intptr_t _freeze_len, _thaw_len;

public:
  OopMapStubGenerator(CodeBuffer* code) : StubCodeGenerator(code) {
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

  void generate_thaw(const ImmutableOopMap* map) {
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
      for (OopMapStream oms(map,mask); !oms.is_done(); oms.next()) {
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
      for (OopMapStream oms(map,OopMapValue::derived_oop_value); !oms.is_done(); oms.next()) {
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
    _masm->movl(rax, map->num_oops());
    _masm->ret(0);

    _thaw_len = (intptr_t) _masm->pc() - (intptr_t) _thaw;
  }

  void generate_freeze(const ImmutableOopMap* map) {
    _masm->align(8);
    _freeze = _masm->pc();

    /* rdi is source (rsp), rsi is destination (first address), rdx (rbp address), rcx (hstack), r8 (initial index (refStack_length - index) ), r9 (fp_oop_info) */
    if (UseCompressedOops) {
      _masm->push(r12);
      _masm->reinit_heapbase();
    }

    if (map->has_derived()) {
      _masm->push(r11);
    }

    int pos = 0;
    {
      int mask = OopMapValue::oop_value | OopMapValue::narrowoop_value;
      for (OopMapStream oms(map,mask); !oms.is_done(); oms.next()) {
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
      for (OopMapStream oms(map,OopMapValue::derived_oop_value); !oms.is_done(); oms.next()) {
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

    if (map->has_derived()) {
      _masm->pop(r11);
    }
    if (UseCompressedOops) {
      _masm->pop(r12);
    }
    _masm->movl(rax, map->num_oops());
    _masm->ret(0);
    _freeze_len = (intptr_t) _masm->pc() - (intptr_t) _freeze;
  }
};

// NULL, fail, success (address)
void ImmutableOopMap::generate_stub() const {
  ResourceMark rm;

  /* The address of the ImmutableOopMap is put into the _freeze_stub and _thaw_stub 
   * if we can't generate the stub for some reason */

  if (_exploded == NULL) {
    Atomic::cmpxchg((address) this, &_freeze_stub, (address )NULL);
    Atomic::cmpxchg((address) this, &_thaw_stub, (address )NULL);
    return;
  }

  int size = 64 + (count() * 6 * 15) + (CheckCompressedOops ? 2048 : 0); // worst case, 6 instructions per oop, 15 bytes per instruction;

  BufferBlob* blob = BufferBlob::create("oopmap stub", size);
  if( blob == NULL) {
    Atomic::cmpxchg((address) this, &_freeze_stub, (address )NULL);
    Atomic::cmpxchg((address) this, &_thaw_stub, (address )NULL);
    return;
  }

  CodeBuffer buf(blob);
  //OptOopMapStubGenerator cgen(&buf, this);
  OopMapStubGenerator cgen(&buf);
  cgen.generate_freeze(this);
  cgen.generate_thaw(this);

  /*
  BufferBlob* blob2 = BufferBlob::create("meh", 2048);
  CodeBuffer buf2(blob2);
  OopMapStubGenerator cgen2(&buf2);
  cgen2.generate_freeze(this);
  cgen2.generate_thaw(this);

  assert(memcmp(cgen.thaw_stub(), cgen2.thaw_stub(), cgen2.thaw_length()) == 0, "");
  */

  //Atomic::cmpxchg((address) 0xba, &_stub, (address )NULL);
  if (Atomic::cmpxchg(blob->code_begin(), &_freeze_stub, (address) NULL) != NULL) {
  //  BufferBlob::free(blob);
    return;
  }
  if (Atomic::cmpxchg(cgen.thaw_stub(), &_thaw_stub, (address) NULL) != NULL) {
    return;
  }
}

void ImmutableOopMap::oops_do(const frame *fr, const RegisterMap *reg_map,
                              OopClosure* oop_fn, DerivedOopClosure* derived_oop_fn) const {
  AddDerivedOop add_derived_oop;
  if (derived_oop_fn == NULL) {
    derived_oop_fn = &add_derived_oop;
  }
  OopMapDo<OopClosure, DerivedOopClosure, SkipNullValue> visitor(oop_fn, derived_oop_fn);
  visitor.oops_do(fr, reg_map, this);
}

template<typename T>
static void iterate_all_do(const frame *fr, int mask, OopMapClosure* fn, const ImmutableOopMap* oopmap) {
  OopMapValue omv;
  for (T oms(oopmap,mask); !oms.is_done(); oms.next()) {
      omv = oms.current();
      fn->do_value(omv.reg(), omv.type());
  }
}

void ImmutableOopMap::all_do(const frame *fr, int mask, OopMapClosure* fn) const {
  if (_exploded != NULL) {
    iterate_all_do<ExplodedOopMapStream>(fr, mask, fn, this);
  } else {
    iterate_all_do<OopMapStream>(fr, mask, fn, this);
  }
}

template <typename T>
static void update_register_map1(const ImmutableOopMap* oopmap, const frame* fr, RegisterMap* reg_map) {
  for (T oms(oopmap, OopMapValue::callee_saved_value); !oms.is_done(); oms.next()) {
    OopMapValue omv = oms.current();
    VMReg reg = omv.content_reg();
    oop* loc = fr->oopmapreg_to_location(omv.reg(), reg_map);
    reg_map->set_location(reg, (address) loc);
    //DEBUG_ONLY(nof_callee++;)
  }
}

void ImmutableOopMap::update_register_map(const frame *fr, RegisterMap *reg_map) const {
  // ResourceMark rm;
  CodeBlob* cb = fr->cb();
  assert(cb != NULL, "no codeblob");
  // Any reg might be saved by a safepoint handler (see generate_handler_blob).
  assert( reg_map->_update_for_id == NULL || fr->is_older(reg_map->_update_for_id),
         "already updated this map; do not 'update' it twice!" );
  debug_only(reg_map->_update_for_id = fr->id());


  // Check if caller must update oop argument
  assert((reg_map->include_argument_oops() ||
          !cb->caller_must_gc_arguments(reg_map->thread())),
         "include_argument_oops should already be set");

  // Scan through oopmap and find location of all callee-saved registers
  // (we do not do update in place, since info could be overwritten)

  DEBUG_ONLY(int nof_callee = 0;)
  if (_exploded != NULL) {
    update_register_map1<ExplodedOopMapStream>(this, fr, reg_map);
  } else {
    update_register_map1<OopMapStream>(this, fr, reg_map);
  }

  /*
  for (OopMapStream oms(this, OopMapValue::callee_saved_value); !oms.is_done(); oms.next()) {
    OopMapValue omv = oms.current();
    VMReg reg = omv.content_reg();
    oop* loc = fr->oopmapreg_to_location(omv.reg(), reg_map);
    reg_map->set_location(reg, (address) loc);
    DEBUG_ONLY(nof_callee++;)
  }
  */

  // Check that runtime stubs save all callee-saved registers
#ifdef COMPILER2
  assert(cb == NULL || cb->is_compiled_by_c1() || cb->is_compiled_by_jvmci() || !cb->is_runtime_stub() ||
         (nof_callee >= SAVED_ON_ENTRY_REG_COUNT || nof_callee >= C_SAVED_ON_ENTRY_REG_COUNT),
         "must save all");
#endif // COMPILER2
}

const ImmutableOopMap* OopMapSet::find_map(const frame *fr) { 
  return find_map(fr->cb(), fr->pc()); 
}

const ImmutableOopMap* OopMapSet::find_map(const CodeBlob* cb, address pc) {
  assert(cb != NULL, "no codeblob");
  const ImmutableOopMap* map = cb->oop_map_for_return_address(pc);
  assert(map != NULL, "no ptr map found");
  return map;
}

// Update callee-saved register info for the following frame
void OopMapSet::update_register_map(const frame *fr, RegisterMap *reg_map) {
  find_map(fr)->update_register_map(fr, reg_map);
}

//=============================================================================
// Non-Product code

#ifndef PRODUCT

bool ImmutableOopMap::has_derived_pointer() const {
#if !defined(TIERED) && !INCLUDE_JVMCI
  COMPILER1_PRESENT(return false);
#endif // !TIERED
#if COMPILER2_OR_JVMCI
  OopMapStream oms(this,OopMapValue::derived_oop_value);
  return oms.is_done();
#else
  return false;
#endif // COMPILER2_OR_JVMCI
}

#ifndef PRODUCT
void OopMapSet::trace_codeblob_maps(const frame *fr, const RegisterMap *reg_map) {
  // Print oopmap and regmap
  tty->print_cr("------ ");
  CodeBlob* cb = fr->cb();
  const ImmutableOopMapSet* maps = cb->oop_maps();
  const ImmutableOopMap* map = cb->oop_map_for_return_address(fr->pc());
  map->print();
  if( cb->is_nmethod() ) {
    nmethod* nm = (nmethod*)cb;
    // native wrappers have no scope data, it is implied
    if (nm->is_native_method()) {
      tty->print("bci: 0 (native)");
    } else {
      ScopeDesc* scope  = nm->scope_desc_at(fr->pc());
      tty->print("bci: %d ",scope->bci());
    }
  }
  tty->cr();
  fr->print_on(tty);
  tty->print("     ");
  cb->print_value_on(tty);  tty->cr();
  reg_map->print();
  tty->print_cr("------ ");

}
#endif // PRODUCT


#endif //PRODUCT

// Printing code is present in product build for -XX:+PrintAssembly.

static
void print_register_type(OopMapValue::oop_types x, VMReg optional,
                         outputStream* st) {
  switch( x ) {
  case OopMapValue::oop_value:
    st->print("Oop");
    break;
  case OopMapValue::narrowoop_value:
    st->print("NarrowOop");
    break;
  case OopMapValue::callee_saved_value:
    st->print("Callers_");
    optional->print_on(st);
    break;
  case OopMapValue::derived_oop_value:
    st->print("Derived_oop_");
    optional->print_on(st);
    break;
  default:
    ShouldNotReachHere();
  }
}

void OopMapValue::print_on(outputStream* st) const {
  reg()->print_on(st);
  st->print("=");
  print_register_type(type(),content_reg(),st);
  st->print(" ");
}

void ImmutableOopMap::print_on(outputStream* st) const {
  OopMapValue omv;
  st->print("ImmutableOopMap{");
  for(OopMapStream oms(this); !oms.is_done(); oms.next()) {
    omv = oms.current();
    omv.print_on(st);
  }
  st->print("}");
}

void OopMap::print_on(outputStream* st) const {
  OopMapValue omv;
  st->print("OopMap{");
  for(OopMapStream oms((OopMap*)this); !oms.is_done(); oms.next()) {
    omv = oms.current();
    omv.print_on(st);
  }
  st->print("off=%d}", (int) offset());
}

void ImmutableOopMapSet::print_on(outputStream* st) const {
  const ImmutableOopMap* last = NULL;
  for (int i = 0; i < _count; ++i) {
    const ImmutableOopMapPair* pair = pair_at(i);
    const ImmutableOopMap* map = pair->get_from(this);
    if (map != last) {
      st->cr();
      map->print_on(st);
      st->print("pc offsets: ");
    }
    last = map;
    st->print("%d ", pair->pc_offset());
  }
}

void OopMapSet::print_on(outputStream* st) const {
  int i, len = om_count();

  st->print_cr("OopMapSet contains %d OopMaps\n",len);

  for( i = 0; i < len; i++) {
    OopMap* m = at(i);
    st->print_cr("#%d ",i);
    m->print_on(st);
    st->cr();
  }
}

bool OopMap::equals(const OopMap* other) const {
  if (other->_omv_count != _omv_count) {
    return false;
  }
  if (other->write_stream()->position() != write_stream()->position()) {
    return false;
  }
  if (memcmp(other->write_stream()->buffer(), write_stream()->buffer(), write_stream()->position()) != 0) {
    return false;
  }
  return true;
}

int ImmutableOopMapSet::find_slot_for_offset(int pc_offset) const {
  ImmutableOopMapPair* pairs = get_pairs();

  int i;
  for (i = 0; i < _count; ++i) {
    if (pairs[i].pc_offset() >= pc_offset) {
      break;
    }
  }
  ImmutableOopMapPair* last = &pairs[i];
  assert(last->pc_offset() == pc_offset, "oopmap not found");
  return i;
}

const ImmutableOopMap* ImmutableOopMapSet::find_map_at_offset(int pc_offset) const {
  ImmutableOopMapPair* pairs = get_pairs();

  int i;
  for (i = 0; i < _count; ++i) {
    if (pairs[i].pc_offset() >= pc_offset) {
      break;
    }
  }
  ImmutableOopMapPair* last = &pairs[i];

  assert(last->pc_offset() == pc_offset, "oopmap not found");
  return last->get_from(this);
}

ImmutableOopMap::ImmutableOopMap(const OopMap* oopmap) : _exploded(NULL), _freeze_stub(NULL), _thaw_stub(NULL), _count(oopmap->count()), _num_oops(oopmap->num_oops()) {
  _num_oops = oopmap->num_oops();
  address addr = data_addr();
  //oopmap->copy_data_to(addr);
  oopmap->copy_and_sort_data_to(addr);
  if (UseNewCode2) {
    _exploded = new ExplodedOopMap(this); // leaking memory atm
  }
}

#ifdef ASSERT
int ImmutableOopMap::nr_of_bytes() const {
  OopMapStream oms(this);

  while (!oms.is_done()) {
    oms.next();
  }
  return sizeof(ImmutableOopMap) + oms.stream_position();
}
#endif

ImmutableOopMapBuilder::ImmutableOopMapBuilder(const OopMapSet* set)
  : _set(set), _empty(NULL), _last(NULL), _empty_offset(-1), _last_offset(-1), _offset(0), _required(-1), _new_set(NULL) {
  _mapping = NEW_RESOURCE_ARRAY(Mapping, _set->size());
}

int ImmutableOopMapBuilder::size_for(const OopMap* map) const {
  return align_up((int)sizeof(ImmutableOopMap) + map->data_size(), 8);
}

int ImmutableOopMapBuilder::heap_size() {
  int base = sizeof(ImmutableOopMapSet);
  base = align_up(base, 8);

  // all of ours pc / offset pairs
  int pairs = _set->size() * sizeof(ImmutableOopMapPair);
  pairs = align_up(pairs, 8);

  for (int i = 0; i < _set->size(); ++i) {
    int size = 0;
    OopMap* map = _set->at(i);

    if (is_empty(map)) {
      /* only keep a single empty map in the set */
      if (has_empty()) {
        _mapping[i].set(Mapping::OOPMAP_EMPTY, _empty_offset, 0, map, _empty);
      } else {
        _empty_offset = _offset;
        _empty = map;
        size = size_for(map);
        _mapping[i].set(Mapping::OOPMAP_NEW, _offset, size, map);
      }
    } else if (is_last_duplicate(map)) {
      /* if this entry is identical to the previous one, just point it there */
      _mapping[i].set(Mapping::OOPMAP_DUPLICATE, _last_offset, 0, map, _last);
    } else {
      /* not empty, not an identical copy of the previous entry */
      size = size_for(map);
      _mapping[i].set(Mapping::OOPMAP_NEW, _offset, size, map);
      _last_offset = _offset;
      _last = map;
    }

    assert(_mapping[i]._map == map, "check");
    _offset += size;
  }

  int total = base + pairs + _offset;
  DEBUG_ONLY(total += 8);
  _required = total;
  return total;
}

void ImmutableOopMapBuilder::fill_pair(ImmutableOopMapPair* pair, const OopMap* map, int offset, const ImmutableOopMapSet* set) {
  assert(offset < set->nr_of_bytes(), "check");
  new ((address) pair) ImmutableOopMapPair(map->offset(), offset);
}

int ImmutableOopMapBuilder::fill_map(ImmutableOopMapPair* pair, const OopMap* map, int offset, const ImmutableOopMapSet* set) {
  fill_pair(pair, map, offset, set);
  address addr = (address) pair->get_from(_new_set); // location of the ImmutableOopMap

  new (addr) ImmutableOopMap(map);
  return size_for(map);
}

void ImmutableOopMapBuilder::fill(ImmutableOopMapSet* set, int sz) {
  ImmutableOopMapPair* pairs = set->get_pairs();

  for (int i = 0; i < set->count(); ++i) {
    const OopMap* map = _mapping[i]._map;
    ImmutableOopMapPair* pair = NULL;
    int size = 0;

    if (_mapping[i]._kind == Mapping::OOPMAP_NEW) {
      size = fill_map(&pairs[i], map, _mapping[i]._offset, set);
    } else if (_mapping[i]._kind == Mapping::OOPMAP_DUPLICATE || _mapping[i]._kind == Mapping::OOPMAP_EMPTY) {
      fill_pair(&pairs[i], map, _mapping[i]._offset, set);
    }

    const ImmutableOopMap* nv = set->find_map_at_offset(map->offset());
    //assert(memcmp(map->data(), nv->data_addr(), map->data_size()) == 0, "check identity");
  }
}

#ifdef ASSERT
void ImmutableOopMapBuilder::verify(address buffer, int size, const ImmutableOopMapSet* set) {
  for (int i = 0; i < 8; ++i) {
    assert(buffer[size - 8 + i] == (unsigned char) 0xff, "overwritten memory check");
  }

  for (int i = 0; i < set->count(); ++i) {
    const ImmutableOopMapPair* pair = set->pair_at(i);
    assert(pair->oopmap_offset() < set->nr_of_bytes(), "check size");
    const ImmutableOopMap* map = pair->get_from(set);
    int nr_of_bytes = map->nr_of_bytes();
    assert(pair->oopmap_offset() + nr_of_bytes <= set->nr_of_bytes(), "check size + size");
  }
}
#endif

ImmutableOopMapSet* ImmutableOopMapBuilder::generate_into(address buffer) {
  DEBUG_ONLY(memset(&buffer[_required-8], 0xff, 8));

  _new_set = new (buffer) ImmutableOopMapSet(_set, _required);
  fill(_new_set, _required);

  DEBUG_ONLY(verify(buffer, _required, _new_set));

  return _new_set;
}

ImmutableOopMapSet* ImmutableOopMapBuilder::build() {
  _required = heap_size();

  // We need to allocate a chunk big enough to hold the ImmutableOopMapSet and all of its ImmutableOopMaps
  address buffer = (address) NEW_C_HEAP_ARRAY(unsigned char, _required, mtCode);
  return generate_into(buffer);
}

ImmutableOopMapSet* ImmutableOopMapSet::build_from(const OopMapSet* oopmap_set) {
  ResourceMark mark;
  ImmutableOopMapBuilder builder(oopmap_set);
  return builder.build();
}


//------------------------------DerivedPointerTable---------------------------

#if COMPILER2_OR_JVMCI

class DerivedPointerEntry : public CHeapObj<mtCompiler> {
 private:
  oop*     _location; // Location of derived pointer (also pointing to the base)
  intptr_t _offset;   // Offset from base pointer
 public:
  DerivedPointerEntry(oop* location, intptr_t offset) { _location = location; _offset = offset; }
  oop* location()    { return _location; }
  intptr_t  offset() { return _offset; }
};


GrowableArray<DerivedPointerEntry*>* DerivedPointerTable::_list = NULL;
bool DerivedPointerTable::_active = false;


void DerivedPointerTable::clear() {
  // The first time, we create the list.  Otherwise it should be
  // empty.  If not, then we have probably forgotton to call
  // update_pointers after last GC/Scavenge.
  assert (!_active, "should not be active");
  assert(_list == NULL || _list->length() == 0, "table not empty");
  if (_list == NULL) {
    _list = new (ResourceObj::C_HEAP, mtCompiler) GrowableArray<DerivedPointerEntry*>(10, true); // Allocated on C heap
  }
  _active = true;
}


// Returns value of location as an int
intptr_t value_of_loc(oop *pointer) { return cast_from_oop<intptr_t>((*pointer)); }


void DerivedPointerTable::add(oop *derived_loc, oop *base_loc) {
  assert(Universe::heap()->is_in_or_null(*base_loc), "not an oop");
  assert(derived_loc != base_loc, "Base and derived in same location");
  if (_active) {
    assert(*derived_loc != (oop)base_loc, "location already added");
    assert(_list != NULL, "list must exist");
    intptr_t offset = value_of_loc(derived_loc) - value_of_loc(base_loc);
    // This assert is invalid because derived pointers can be
    // arbitrarily far away from their base.
    // assert(offset >= -1000000, "wrong derived pointer info");

    if (TraceDerivedPointers) {
      tty->print_cr(
        "Add derived pointer@" INTPTR_FORMAT
        " - Derived: " INTPTR_FORMAT
        " Base: " INTPTR_FORMAT " (@" INTPTR_FORMAT ") (Offset: " INTX_FORMAT ")",
        p2i(derived_loc), p2i((address)*derived_loc), p2i((address)*base_loc), p2i(base_loc), offset
      );
    }
    // Set derived oop location to point to base.
    *derived_loc = (oop)base_loc;
    assert_lock_strong(DerivedPointerTableGC_lock);
    DerivedPointerEntry *entry = new DerivedPointerEntry(derived_loc, offset);
    _list->append(entry);
  }
}


void DerivedPointerTable::update_pointers() {
  assert(_list != NULL, "list must exist");
  for(int i = 0; i < _list->length(); i++) {
    DerivedPointerEntry* entry = _list->at(i);
    oop* derived_loc = entry->location();
    intptr_t offset  = entry->offset();
    // The derived oop was setup to point to location of base
    oop  base        = **(oop**)derived_loc;
    assert(Universe::heap()->is_in_or_null(base), "must be an oop");

    *derived_loc = (oop)(((address)base) + offset);
    assert(value_of_loc(derived_loc) - value_of_loc(&base) == offset, "sanity check");

    if (TraceDerivedPointers) {
      tty->print_cr("Updating derived pointer@" INTPTR_FORMAT
                    " - Derived: " INTPTR_FORMAT "  Base: " INTPTR_FORMAT " (Offset: " INTX_FORMAT ")",
          p2i(derived_loc), p2i((address)*derived_loc), p2i((address)base), offset);
    }

    // Delete entry
    delete entry;
    _list->at_put(i, NULL);
  }
  // Clear list, so it is ready for next traversal (this is an invariant)
  if (TraceDerivedPointers && !_list->is_empty()) {
    tty->print_cr("--------------------------");
  }
  _list->clear();
  _active = false;
}

#endif // COMPILER2_OR_JVMCI
