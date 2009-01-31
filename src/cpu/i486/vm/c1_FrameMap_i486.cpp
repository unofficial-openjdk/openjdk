#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)c1_FrameMap_i486.cpp	1.84 07/05/05 17:04:13 JVM"
#endif
/*
 * Copyright 1999-2006 Sun Microsystems, Inc.  All Rights Reserved.
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
# include "incls/_c1_FrameMap_i486.cpp.incl"

const int FrameMap::pd_c_runtime_reserved_arg_size = 0;

LIR_Opr FrameMap::map_to_opr(BasicType type, VMRegPair* reg, bool) {
  LIR_Opr opr = LIR_OprFact::illegalOpr;
  VMReg r_1 = reg->first();
  VMReg r_2 = reg->second();
  if (r_1->is_stack()) {
    // Convert stack slot to an SP offset
    // The calling convention does not count the SharedRuntime::out_preserve_stack_slots() value
    // so we must add it in here.
    int st_off = (r_1->reg2stack() + SharedRuntime::out_preserve_stack_slots()) * VMRegImpl::stack_slot_size;
    opr = LIR_OprFact::address(new LIR_Address(esp_opr, st_off, type));
  } else if (r_1->is_Register()) {
    Register reg = r_1->as_Register();
    if (r_2->is_Register()) {
      Register reg2 = r_2->as_Register();
      opr = as_long_opr(reg2, reg);
    } else if (type == T_OBJECT) {
      opr = as_oop_opr(reg);
    } else {
      opr = as_opr(reg);
    }
  } else if (r_1->is_FloatRegister()) {
    assert(type == T_DOUBLE || type == T_FLOAT, "wrong type");
    int num = r_1->as_FloatRegister()->encoding();
    if (type == T_FLOAT) {
      opr = LIR_OprFact::single_fpu(num);
    } else {
      opr = LIR_OprFact::double_fpu(num);
    }
  } else if (r_1->is_XMMRegister()) {
    assert(type == T_DOUBLE || type == T_FLOAT, "wrong type");
    int num = r_1->as_XMMRegister()->encoding();
    if (type == T_FLOAT) {
      opr = LIR_OprFact::single_xmm(num);
    } else {
      opr = LIR_OprFact::double_xmm(num);
    }
  } else {
    ShouldNotReachHere();
  }
  return opr;
}


LIR_Opr FrameMap::esi_opr;
LIR_Opr FrameMap::edi_opr;
LIR_Opr FrameMap::ebx_opr;
LIR_Opr FrameMap::eax_opr;
LIR_Opr FrameMap::edx_opr;
LIR_Opr FrameMap::ecx_opr;
LIR_Opr FrameMap::esp_opr;
LIR_Opr FrameMap::ebp_opr;

LIR_Opr FrameMap::receiver_opr;

LIR_Opr FrameMap::esi_oop_opr;
LIR_Opr FrameMap::edi_oop_opr;
LIR_Opr FrameMap::ebx_oop_opr;
LIR_Opr FrameMap::eax_oop_opr;
LIR_Opr FrameMap::edx_oop_opr;
LIR_Opr FrameMap::ecx_oop_opr;

LIR_Opr FrameMap::eax_edx_long_opr;
LIR_Opr FrameMap::ebx_ecx_long_opr;
LIR_Opr FrameMap::fpu0_float_opr;
LIR_Opr FrameMap::fpu0_double_opr;
LIR_Opr FrameMap::xmm0_float_opr;
LIR_Opr FrameMap::xmm0_double_opr;

LIR_Opr FrameMap::_caller_save_cpu_regs[] = { 0, };
LIR_Opr FrameMap::_caller_save_fpu_regs[] = { 0, };
LIR_Opr FrameMap::_caller_save_xmm_regs[] = { 0, };

XMMRegister FrameMap::_xmm_regs [8] = { 0, };

XMMRegister FrameMap::nr2xmmreg(int rnr) {
  assert(_init_done, "tables not initialized");
  return _xmm_regs[rnr];
}

//--------------------------------------------------------
//               FrameMap
//--------------------------------------------------------

void FrameMap::init() {
  if (_init_done) return;

  assert(nof_cpu_regs == 8, "wrong number of CPU registers");
  map_register(0, esi);  esi_opr = LIR_OprFact::single_cpu(0);  esi_oop_opr = LIR_OprFact::single_cpu_oop(0);
  map_register(1, edi);  edi_opr = LIR_OprFact::single_cpu(1);  edi_oop_opr = LIR_OprFact::single_cpu_oop(1);
  map_register(2, ebx);  ebx_opr = LIR_OprFact::single_cpu(2);  ebx_oop_opr = LIR_OprFact::single_cpu_oop(2);
  map_register(3, eax);  eax_opr = LIR_OprFact::single_cpu(3);  eax_oop_opr = LIR_OprFact::single_cpu_oop(3);
  map_register(4, edx);  edx_opr = LIR_OprFact::single_cpu(4);  edx_oop_opr = LIR_OprFact::single_cpu_oop(4);
  map_register(5, ecx);  ecx_opr = LIR_OprFact::single_cpu(5);  ecx_oop_opr = LIR_OprFact::single_cpu_oop(5);
  map_register(6, esp);  esp_opr = LIR_OprFact::single_cpu(6);
  map_register(7, ebp);  ebp_opr = LIR_OprFact::single_cpu(7);

  eax_edx_long_opr = LIR_OprFact::double_cpu(3 /*eax*/, 4 /*edx*/);
  ebx_ecx_long_opr = LIR_OprFact::double_cpu(2 /*ebx*/, 5 /*ecx*/);
  fpu0_float_opr   = LIR_OprFact::single_fpu(0);
  fpu0_double_opr  = LIR_OprFact::double_fpu(0);
  xmm0_float_opr   = LIR_OprFact::single_xmm(0);
  xmm0_double_opr  = LIR_OprFact::double_xmm(0);

  _caller_save_cpu_regs[0] = esi_opr;
  _caller_save_cpu_regs[1] = edi_opr;
  _caller_save_cpu_regs[2] = ebx_opr;
  _caller_save_cpu_regs[3] = eax_opr;
  _caller_save_cpu_regs[4] = edx_opr;
  _caller_save_cpu_regs[5] = ecx_opr;


  _xmm_regs[0] = xmm0;
  _xmm_regs[1] = xmm1;
  _xmm_regs[2] = xmm2;
  _xmm_regs[3] = xmm3;
  _xmm_regs[4] = xmm4;
  _xmm_regs[5] = xmm5;
  _xmm_regs[6] = xmm6;
  _xmm_regs[7] = xmm7;

  for (int i = 0; i < 8; i++) {
    _caller_save_fpu_regs[i] = LIR_OprFact::single_fpu(i);
    _caller_save_xmm_regs[i] = LIR_OprFact::single_xmm(i);
  }

  _init_done = true;

  VMRegPair regs;
  BasicType sig_bt = T_OBJECT;
  SharedRuntime::java_calling_convention(&sig_bt, &regs, 1, true);
  receiver_opr = as_oop_opr(regs.first()->as_Register());
  assert(receiver_opr == ecx_oop_opr, "rcvr ought to be ecx");
}


Address FrameMap::make_new_address(ByteSize sp_offset) const {
  // for ebp based address use this:
  // return Address(ebp, in_bytes(sp_offset) - (framesize() - 2) * 4);
  return Address(esp, in_bytes(sp_offset));
}


// ----------------mapping-----------------------
// all mapping is based on ebp addressing, except for simple leaf methods where we access 
// the locals esp based (and no frame is built)


// Frame for simple leaf methods (quick entries)
//
//   +----------+
//   | ret addr |   <- TOS
//   +----------+
//   | args     |   
//   | ......   |

// Frame for standard methods
// 
//   | .........|  <- TOS
//   | locals   |
//   +----------+
//   | old ebp  |  <- EBP
//   +----------+
//   | ret addr |
//   +----------+
//   |  args    |
//   | .........|


// For OopMaps, map a local variable or spill index to an VMRegImpl name.
// This is the offset from sp() in the frame of the slot for the index,
// skewed by VMRegImpl::stack0 to indicate a stack location (vs.a register.)
//
//           framesize +
//           stack0         stack0          0  <- VMReg
//             |              | <registers> |
//  ...........|..............|.............|  
//      0 1 2 3 x x 4 5 6 ... |                <- local indices
//      ^           ^        sp()                 ( x x indicate link
//      |           |                               and return addr)
//  arguments   non-argument locals


VMReg FrameMap::fpu_regname (int n) {
  // Return the OptoReg name for the fpu stack slot "n"
  // A spilled fpu stack slot comprises to two single-word OptoReg's.
  return as_FloatRegister(n)->as_VMReg();
}

LIR_Opr FrameMap::stack_pointer() {
  return FrameMap::esp_opr;
}


bool FrameMap::validate_frame() {
  return true;
}
