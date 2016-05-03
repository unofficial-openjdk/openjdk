/*
 * Copyright (c) 1997, 2016, Oracle and/or its affiliates. All rights reserved.
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
#include "interp_masm_x86.hpp"
#include "interpreter/interpreter.hpp"


// 8u does not have InterpreterMacroAssembler::load_earlyret_value here

void InterpreterMacroAssembler::narrow(Register result) {

  // Get method->_constMethod->_result_type
  movptr(rcx, Address(rbp, frame::interpreter_frame_method_offset * wordSize));
  movptr(rcx, Address(rcx, methodOopDesc::const_offset()));
  load_unsigned_byte(rcx, Address(rcx, constMethodOopDesc::result_type_offset()));

  Label done, notBool, notByte, notChar;

  // common case first
  cmpl(rcx, T_INT);
  jcc(Assembler::equal, done);

  // mask integer result to narrower return type.
  cmpl(rcx, T_BOOLEAN);
  jcc(Assembler::notEqual, notBool);
  andl(result, 0x1);
  jmp(done);

  bind(notBool);
  cmpl(rcx, T_BYTE);
  jcc(Assembler::notEqual, notByte);
  LP64_ONLY(movsbl(result, result);)
  NOT_LP64(shll(result, 24);)      // truncate upper 24 bits
  NOT_LP64(sarl(result, 24);)      // and sign-extend byte
  jmp(done);

  bind(notByte);
  cmpl(rcx, T_CHAR);
  jcc(Assembler::notEqual, notChar);
  LP64_ONLY(movzwl(result, result);)
  NOT_LP64(andl(result, 0xFFFF);)  // truncate upper 16 bits
  jmp(done);

  bind(notChar);
  // cmpl(rcx, T_SHORT);  // all that's left
  // jcc(Assembler::notEqual, done);
  LP64_ONLY(movswl(result, result);)
  NOT_LP64(shll(result, 16);)      // truncate upper 16 bits
  NOT_LP64(sarl(result, 16);)      // and sign-extend short

  // Nothing to do for T_INT
  bind(done);
}

