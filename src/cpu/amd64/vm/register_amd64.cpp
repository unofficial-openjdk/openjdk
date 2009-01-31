#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)register_amd64.cpp	1.11 07/05/05 17:04:07 JVM"
#endif
/*
 * Copyright 2003-2007 Sun Microsystems, Inc.  All Rights Reserved.
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

#include "incls/_precompiled.incl"
#include "incls/_register_amd64.cpp.incl"

const int ConcreteRegisterImpl::max_gpr = RegisterImpl::number_of_registers << 1;
const int ConcreteRegisterImpl::max_fpr = ConcreteRegisterImpl::max_gpr + 2 * FloatRegisterImpl::number_of_registers;

const char* RegisterImpl::name() const 
{
  const char* names[number_of_registers] = {
    "rax", "rcx", "rdx", "rbx", "rsp", "rbp", "rsi", "rdi",
    "r8",  "r9",  "r10", "r11", "r12", "r13", "r14", "r15"
  };
  return is_valid() ? names[encoding()] : "noreg";
}

const char* FloatRegisterImpl::name() const 
{
  const char* names[number_of_registers] = {
    "xmm0",  "xmm1", "xmm2",  "xmm3",  "xmm4",  "xmm5",  "xmm6",  "xmm7",
    "xmm8",  "xmm9", "xmm10", "xmm11", "xmm12", "xmm13", "xmm14", "xmm15"
  };
  return is_valid() ? names[encoding()] : "xmmnoreg";
}

