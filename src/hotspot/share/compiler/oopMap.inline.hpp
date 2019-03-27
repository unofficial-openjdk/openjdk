/*
 * Copyright (c) 1998, 2015, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_VM_COMPILER_OOPMAP_INLINE_HPP
#define SHARE_VM_COMPILER_OOPMAP_INLINE_HPP

#include "gc/shared/collectedHeap.hpp"

inline const ImmutableOopMap* ImmutableOopMapSet::find_map_at_slot(int slot, int pc_offset) const {
  assert(slot >= 0 && slot < _count, "bounds");
  ImmutableOopMapPair* pairs = get_pairs();
  ImmutableOopMapPair* last = &pairs[slot];
  assert(last->pc_offset() == pc_offset, "oopmap not found");
  return last->get_from(this);
}

inline const ImmutableOopMap* ImmutableOopMapPair::get_from(const ImmutableOopMapSet* set) const {
  return set->oopmap_at_offset(_oopmap_offset);
}

inline bool SkipNullValue::should_skip(oop val) {
  return val == (oop)NULL || Universe::is_narrow_oop_base(val);
}

template <typename OopFnT, typename DerivedOopFnT, typename ValueFilterT>
template <typename OopMapStreamT>
void OopMapDo<OopFnT, DerivedOopFnT, ValueFilterT>::iterate_oops_do(const frame *fr, const RegisterMap *reg_map, const ImmutableOopMap* oopmap) {
  NOT_PRODUCT(if (TraceCodeBlobStacks) OopMapSet::trace_codeblob_maps(fr, reg_map);)

  // handle derived pointers first (otherwise base pointer may be
  // changed before derived pointer offset has been collected)
  if (reg_map->validate_oops())
    walk_derived_pointers<OopMapStreamT>(fr, oopmap, reg_map);

  OopMapValue omv;
  // We want coop and oop oop_types
  int mask = OopMapValue::oop_value | OopMapValue::narrowoop_value;
  {
    for (OopMapStreamT oms(oopmap,mask); !oms.is_done(); oms.next()) {
      omv = oms.current();
      oop* loc = fr->oopmapreg_to_location(omv.reg(),reg_map);
      // It should be an error if no location can be found for a
      // register mentioned as contained an oop of some kind.  Maybe
      // this was allowed previously because value_value items might
      // be missing?
#ifdef ASSERT
    if (loc == NULL) {
      if (reg_map->should_skip_missing())
        continue;
      VMReg reg = omv.reg();
      tty->print_cr("missing saved register: reg: " INTPTR_FORMAT " %s loc: %p", reg->value(), reg->name(), loc);
      fr->print_on(tty);
    }
#endif
      guarantee(loc != NULL, "missing saved register");
      if ( omv.type() == OopMapValue::oop_value ) {
        oop val = *loc;
        if (ValueFilterT::should_skip(val)) { // TODO: UGLY (basically used to decide if we're freezing/thawing continuation)
          // Ignore NULL oops and decoded NULL narrow oops which
          // equal to Universe::narrow_oop_base when a narrow oop
          // implicit null check is used in compiled code.
          // The narrow_oop_base could be NULL or be the address
          // of the page below heap depending on compressed oops mode.
          continue;
        }
#ifdef ASSERT
        // We can not verify the oop here if we are using ZGC, the oop
        // will be bad in case we had a safepoint between a load and a
        // load barrier.
        if (!UseZGC && reg_map->validate_oops() &&
            ((((uintptr_t)loc & (sizeof(*loc)-1)) != 0) ||
             !Universe::heap()->is_in_or_null(*loc))) {
          tty->print_cr("# Found non oop pointer.  Dumping state at failure");
          // try to dump out some helpful debugging information
          OopMapSet::trace_codeblob_maps(fr, reg_map);
          omv.print();
          tty->print_cr("register r");
          omv.reg()->print();
          tty->print_cr("loc = %p *loc = %p\n", loc, (address)*loc);
          // os::print_location(tty, (intptr_t)*loc);
          tty->print("pc: "); os::print_location(tty, (intptr_t)fr->pc());
          fr->print_value_on(tty, NULL);
          // do the real assert.
          assert(Universe::heap()->is_in_or_null(*loc), "found non oop pointer");
        }
#endif // ASSERT
        _oop_fn->do_oop(loc);
      } else if ( omv.type() == OopMapValue::narrowoop_value ) {
        narrowOop *nl = (narrowOop*)loc;
#ifndef VM_LITTLE_ENDIAN
        VMReg vmReg = omv.reg();
        // Don't do this on SPARC float registers as they can be individually addressed
        if (!vmReg->is_stack() SPARC_ONLY(&& !vmReg->is_FloatRegister())) {
          // compressed oops in registers only take up 4 bytes of an
          // 8 byte register but they are in the wrong part of the
          // word so adjust loc to point at the right place.
          nl = (narrowOop*)((address)nl + 4);
        }
#endif
        _oop_fn->do_oop(nl);
      }
    }
  }

  // When thawing continuation frames, we want to walk derived pointers
  // after walking oops
  if (!reg_map->validate_oops())
    walk_derived_pointers<OopMapStreamT>(fr, oopmap, reg_map);
}

template <typename OopMapFnT, typename DerivedOopFnT, typename ValueFilterT>
template <typename OopMapStreamT>
void OopMapDo<OopMapFnT, DerivedOopFnT, ValueFilterT>::walk_derived_pointers(const frame *fr, const ImmutableOopMap* map, const RegisterMap *reg_map) {
  OopMapStreamT oms(map,OopMapValue::derived_oop_value);
  if (!oms.is_done()) {
#ifndef TIERED
    COMPILER1_PRESENT(ShouldNotReachHere();)
#if INCLUDE_JVMCI
    if (UseJVMCICompiler) {
      ShouldNotReachHere();
    }
#endif
#endif // !TIERED

    if (_lock_derived_table) {
      assert (reg_map->validate_oops(), "");
      // Protect the operation on the derived pointers.  This
      // protects the addition of derived pointers to the shared
      // derived pointer table in DerivedPointerTable::add().
      MutexLockerEx x(DerivedPointerTableGC_lock, Mutex::_no_safepoint_check_flag);
      walk_derived_pointers1<OopMapStreamT>(oms, fr, reg_map);
    } else {
      walk_derived_pointers1<OopMapStreamT>(oms, fr, reg_map);
    }
  }
}

template <typename OopMapFnT, typename DerivedOopFnT, typename ValueFilterT>
template <typename OopMapStreamT>
void OopMapDo<OopMapFnT, DerivedOopFnT, ValueFilterT>::walk_derived_pointers1(OopMapStreamT& oms, const frame *fr, const RegisterMap *reg_map) {
  assert (fr != NULL, "");
  assert (_derived_oop_fn != NULL, "");
  OopMapValue omv;
  do {
    omv = oms.current();
    oop* loc = fr->oopmapreg_to_location(omv.reg(),reg_map);

    DEBUG_ONLY(if (reg_map->should_skip_missing()) continue;)
    guarantee(loc != NULL, "missing saved register");
    oop *derived_loc = loc;
    oop *base_loc    = fr->oopmapreg_to_location(omv.content_reg(), reg_map);
    // Ignore NULL oops and decoded NULL narrow oops which
    // equal to Universe::narrow_oop_base when a narrow oop
    // implicit null check is used in compiled code.
    // The narrow_oop_base could be NULL or be the address
    // of the page below heap depending on compressed oops mode.
    if (base_loc != NULL && *base_loc != (oop)NULL && !Universe::is_narrow_oop_base(*base_loc)) {
      _derived_oop_fn->do_derived_oop(base_loc, derived_loc);
    }
    oms.next();
  } while (!oms.is_done());
}


template <typename OopFnT, typename DerivedOopFnT, typename ValueFilterT>
void OopMapDo<OopFnT, DerivedOopFnT, ValueFilterT>::oops_do(const frame *fr, const RegisterMap *reg_map, const ImmutableOopMap* oopmap) {
  if (oopmap->_exploded != NULL) {
    iterate_oops_do<ExplodedOopMapStream>(fr, reg_map, oopmap);
  } else {
    iterate_oops_do<OopMapStream>(fr, reg_map, oopmap);
  }
}

#endif

