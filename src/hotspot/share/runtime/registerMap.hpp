/*
 * Copyright (c) 2002, 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_RUNTIME_REGISTERMAP_HPP
#define SHARE_RUNTIME_REGISTERMAP_HPP

#include "code/vmreg.hpp"
#include "runtime/handles.hpp"
#include "utilities/globalDefinitions.hpp"
#include "utilities/macros.hpp"

class JavaThread;

//
// RegisterMap
//
// A companion structure used for stack traversal. The RegisterMap contains
// misc. information needed in order to do correct stack traversal of stack
// frames.  Hence, it must always be passed in as an argument to
// frame::sender(RegisterMap*).
//
// In particular,
//   1) It provides access to the thread for which the stack belongs.  The
//      thread object is needed in order to get sender of a deoptimized frame.
//
//   2) It is used to pass information from a callee frame to its caller
//      frame about how the frame should be traversed.  This is used to let
//      the caller frame take care of calling oops-do of out-going
//      arguments, when the callee frame is not instantiated yet.  This
//      happens, e.g., when a compiled frame calls into
//      resolve_virtual_call.  (Hence, it is critical that the same
//      RegisterMap object is used for the entire stack walk.  Normally,
//      this is hidden by using the StackFrameStream.)  This is used when
//      doing follow_oops and oops_do.
//
//   3) The RegisterMap keeps track of the values of callee-saved registers
//      from frame to frame (hence, the name).  For some stack traversal the
//      values of the callee-saved registers does not matter, e.g., if you
//      only need the static properties such as frame type, pc, and such.
//      Updating of the RegisterMap can be turned off by instantiating the
//      register map as: RegisterMap map(thread, false);

class RegisterMap : public StackObj {
 public:
    typedef julong LocationValidType;
  enum {
    reg_count = ConcreteRegisterImpl::number_of_registers,
    location_valid_type_size = sizeof(LocationValidType)*8,
    location_valid_size = (reg_count+location_valid_type_size-1)/location_valid_type_size
  };
 private:
  intptr_t*    _location[reg_count];    // Location of registers (intptr_t* looks better than address in the debugger)
  LocationValidType _location_valid[location_valid_size];
  bool        _include_argument_oops;   // Should include argument_oop marked locations for compiler
  JavaThread* _thread;                  // Reference to current thread
  Handle      _cont;                    // The current continuation, if any
  bool        _on_hstack;               // Whether we're on the h-stack
  intptr_t**  _last_vstack_fp;          // The location of the continuation entry frame's fp when walking h-stacks

  bool        _update_map;              // Tells if the register map need to be
                                        // updated when traversing the stack
  bool        _validate_oops;           // whether to perform valid oop checks in asserts -- used only in the map use for continuation freeze/thaw
  bool        _walk_cont;               // whether to walk frames on a continuation stack

  DEBUG_ONLY(bool  _skip_missing;)     

#ifdef ASSERT
  void check_location_valid();
#else
  void check_location_valid() {}
#endif

 public:
  DEBUG_ONLY(intptr_t* _update_for_id;) // Assert that RegisterMap is not updated twice for same frame
  RegisterMap(JavaThread *thread, bool update_map = true, bool walk_cont = false, bool validate_oops = true);
  RegisterMap(const RegisterMap* map);

  address location(VMReg reg) const {
    int index = reg->value() / location_valid_type_size;
    assert(0 <= reg->value() && reg->value() < reg_count, "range check");
    assert(0 <= index && index < location_valid_size, "range check");
    if (_location_valid[index] & ((LocationValidType)1 << (reg->value() % location_valid_type_size))) {
      return (address) _location[reg->value()];
    } else {
      return pd_location(reg);
    }
  }

  address trusted_location(VMReg reg) const {
    return (address) _location[reg->value()];
  }

  void verify(RegisterMap& other) {
    for (int i = 0; i < reg_count; ++i) {
      assert(_location[i] == other._location[i], "");
    }
  }

  void update_location(VMReg reg, address loc) {
    int index = reg->value() / location_valid_type_size;
    assert(0 <= reg->value() && reg->value() < reg_count, "range check");
    assert(0 <= index && index < location_valid_size, "range check");
    assert(!_validate_oops || _update_map, "updating map that does not need updating");
    _location[reg->value()] = (intptr_t*) loc;
    check_location_valid();
  }

  void set_location(VMReg reg, address loc) {
    int index = reg->value() / location_valid_type_size;
    assert(0 <= reg->value() && reg->value() < reg_count, "range check");
    assert(0 <= index && index < location_valid_size, "range check");
    assert(!_validate_oops || _update_map, "updating map that does not need updating");
    _location[reg->value()] = (intptr_t*) loc;
    _location_valid[index] |= ((LocationValidType)1 << (reg->value() % location_valid_type_size));
    check_location_valid();
  }

  // Called by an entry frame.
  void clear();

  bool include_argument_oops() const      { return _include_argument_oops; }
  void set_include_argument_oops(bool f)  { _include_argument_oops = f; }

  JavaThread *thread() const { return _thread; }
  bool update_map()    const { return _update_map; }
  bool validate_oops() const { return _validate_oops; }
  bool walk_cont()     const { return _walk_cont; }

  bool in_cont()       const { return _on_hstack; } // Whether we are currently on the hstack
  oop  cont()          const { return _cont(); }
  void set_cont(oop cont);
  void set_cont(Handle cont);
  void set_in_cont(bool on_hstack);
  intptr_t** last_vstack_fp()            { return _last_vstack_fp; }
  void set_last_vstack_fp(intptr_t** fp) { _last_vstack_fp = fp; }

  void print_on(outputStream* st) const;
  void print() const;

#ifdef ASSERT
  void set_skip_missing(bool value) { _skip_missing = value; }
  bool should_skip_missing() const  { return _skip_missing; }
#endif

  // the following contains the definition of pd_xxx methods
#include CPU_HEADER(registerMap)

};

#endif // SHARE_RUNTIME_REGISTERMAP_HPP
