#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)relocInfo_i486.cpp	1.18 07/05/05 17:04:19 JVM"
#endif
/*
 * Copyright 1998-2005 Sun Microsystems, Inc.  All Rights Reserved.
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
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 *  
 */

# include "incls/_precompiled.incl"
# include "incls/_relocInfo_i486.cpp.incl"


void Relocation::pd_set_data_value(address x, intptr_t o) {
  *pd_address_in_code() = x + o;
}


address Relocation::pd_call_destination(address orig_addr) {
  intptr_t adj = 0;
  if (orig_addr != NULL) {
    // We just moved this call instruction from orig_addr to addr().
    // This means its target will appear to have grown by addr() - orig_addr.
    adj = -( addr() - orig_addr );
  }
  NativeInstruction* ni = nativeInstruction_at(addr());
  if (ni->is_call())
    return nativeCall_at(addr())->destination() + adj;
  else if (ni->is_jump())
    return nativeJump_at(addr())->jump_destination() + adj;
  else if (ni->is_cond_jump())
    return nativeGeneralJump_at(addr())->jump_destination() + adj;
  else
    { ShouldNotReachHere(); return NULL; }
}


void Relocation::pd_set_call_destination(address x) {
  NativeInstruction* ni = nativeInstruction_at(addr());
  if (ni->is_call())
    nativeCall_at(addr())->set_destination(x);
  else if (ni->is_jump())
    nativeJump_at(addr())->set_jump_destination(x);
  else if (ni->is_cond_jump()) {
    // %%%% kludge this, for now, until we get a jump_destination method
    address old_dest = nativeGeneralJump_at(addr())->jump_destination();
    address disp = Assembler::locate_operand(addr(), Assembler::call32_operand);
    *(jint*)disp += (x - old_dest);
  } else
    { ShouldNotReachHere(); }
}


address* Relocation::pd_address_in_code() {
  // All embedded Intel addresses are stored in 32-bit words.
  // Since the addr points at the start of the instruction,
  // we must parse the instruction a bit to find the embedded word.
  assert(is_data(), "must be a DataRelocation");
  typedef Assembler::WhichOperand WhichOperand;
  WhichOperand which = (WhichOperand) format(); // that is, disp32 or imm32
  assert(which == Assembler::disp32_operand || which == Assembler::imm32_operand, "format unpacks ok");
  return (address*) Assembler::locate_operand(addr(), which);
}


address Relocation::pd_get_address_from_code() {
  return *pd_address_in_code();
}


int Relocation::pd_breakpoint_size() {
  // minimum breakpoint size, in short words
  return NativeIllegalInstruction::instruction_size / sizeof(short);
}

void Relocation::pd_swap_in_breakpoint(address x, short* instrs, int instrlen) {
  Untested("pd_swap_in_breakpoint");
  if (instrs != NULL) {
    assert(instrlen * sizeof(short) == NativeIllegalInstruction::instruction_size, "enough instrlen in reloc. data");
    for (int i = 0; i < instrlen; i++) {
      instrs[i] = ((short*)x)[i];
    }
  }
  NativeIllegalInstruction::insert(x);
}


void Relocation::pd_swap_out_breakpoint(address x, short* instrs, int instrlen) {
  Untested("pd_swap_out_breakpoint");
  assert(NativeIllegalInstruction::instruction_size == sizeof(short), "right address unit for update");
  NativeInstruction* ni = nativeInstruction_at(x);
  *(short*)ni->addr_at(0) = instrs[0];
}
