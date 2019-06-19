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

#ifndef SHARE_RUNTIME_STACKVALUE_HPP
#define SHARE_RUNTIME_STACKVALUE_HPP

#include "code/debugInfo.hpp"
#include "code/location.hpp"
#include "runtime/handles.hpp"

class BasicLock;
class RegisterMap;

class StackValue : public ResourceObj {
 private:
  BasicType _type;
  intptr_t  _integer_value; // Blank java stack slot value
  Handle    _handle_value;  // Java stack slot value interpreted as a Handle
 public:

  StackValue(intptr_t value) {
    _type              = T_INT;
    _integer_value     = value;
  }

  StackValue(Handle value, intptr_t scalar_replaced = 0) {
    _type                = T_OBJECT;
    _integer_value       = scalar_replaced;
    _handle_value        = value;
    assert(_integer_value == 0 ||  _handle_value.is_null(), "not null object should not be marked as scalar replaced");
  }

  StackValue() {
    _type           = T_CONFLICT;
    _integer_value  = 0;
  }

  // Only used during deopt- preserve object type.
  StackValue(intptr_t o, BasicType t) {
    assert(t == T_OBJECT, "should not be used");
    _type          = t;
    _integer_value = o;
  }

  Handle get_obj() const {
    assert(type() == T_OBJECT, "type check");
    return _handle_value;
  }

  bool obj_is_scalar_replaced() const {
    assert(type() == T_OBJECT, "type check");
    return _integer_value != 0;
  }

  void set_obj(Handle value) {
    assert(type() == T_OBJECT, "type check");
    _handle_value = value;
  }

  intptr_t get_int() const {
    assert(type() == T_INT, "type check");
    return _integer_value;
  }

  // For special case in deopt.
  intptr_t get_int(BasicType t) const {
    assert(t == T_OBJECT && type() == T_OBJECT, "type check");
    return _integer_value;
  }

  void set_int(intptr_t value) {
    assert(type() == T_INT, "type check");
    _integer_value = value;
  }

  BasicType type() const { return  _type; }

  bool equal(StackValue *value) {
    if (_type != value->_type) return false;
    if (_type == T_OBJECT)
      return (_handle_value == value->_handle_value);
    else {
      assert(_type == T_INT, "sanity check");
      // [phh] compare only low addressed portions of intptr_t slots
      return (*(int *)&_integer_value == *(int *)&value->_integer_value);
    }
  }

  static BasicLock*  resolve_monitor_lock(const frame* fr, Location location);

  template<typename RegisterMapT>
  static StackValue* create_stack_value(const frame* fr, const RegisterMapT* reg_map, ScopeValue* sv) {
    return create_stack_value(sv, stack_value_address(fr, reg_map, sv), reg_map->in_cont());
  }

  template<typename RegisterMapT>
  static address stack_value_address(const frame* fr, const RegisterMapT* reg_map, ScopeValue* sv) {
    if (!sv->is_location())
      return NULL;
    Location loc = ((LocationValue *)sv)->location();
    if (loc.type() == Location::invalid)
      return NULL;
    
    address value_addr;
    if (!reg_map->in_cont()) {
      value_addr = loc.is_register()
          // Value was in a callee-save register
          ? reg_map->location(VMRegImpl::as_VMReg(loc.register_number()))
          // Else value was directly saved on the stack. The frame's original stack pointer,
          // before any extension by its callee (due to Compiler1 linkage on SPARC), must be used.
          : ((address)fr->unextended_sp()) + loc.stack_offset();

      assert(value_addr == NULL || reg_map->thread()->is_in_usable_stack(value_addr), INTPTR_FORMAT, p2i(value_addr));
    } else {
      value_addr = loc.is_register()
          ? Continuation::reg_to_location(*fr, reg_map->as_RegisterMap(), VMRegImpl::as_VMReg(loc.register_number()), loc.type() == Location::oop || loc.type() == Location::narrowoop)
          : Continuation::usp_offset_to_location(*fr, reg_map->as_RegisterMap(), loc.stack_offset(), loc.type() == Location::oop || loc.type() == Location::narrowoop);
        
      assert(value_addr == NULL || Continuation::is_in_usable_stack(value_addr, reg_map->as_RegisterMap()), INTPTR_FORMAT, p2i(value_addr));
    }
    return value_addr;
  }

#ifndef PRODUCT
 public:
  // Printing
  void print_on(outputStream* st) const;
#endif

private:
  static StackValue* create_stack_value(ScopeValue* sv, address value_addr, bool in_cont);
};

#endif // SHARE_RUNTIME_STACKVALUE_HPP
