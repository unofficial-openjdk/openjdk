#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)register_amd64.hpp	1.17 07/05/05 17:04:08 JVM"
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

class VMRegImpl;
typedef VMRegImpl* VMReg;

// Use Register as shortcut
class RegisterImpl;
typedef RegisterImpl* Register;

// The implementation of integer registers for the amd64 architecture
inline Register as_Register(int encoding)
{
  return (Register)(intptr_t) encoding;
}

class RegisterImpl :
  public AbstractRegisterImpl 
{
 public:
  enum {
    number_of_registers      = 16,
    number_of_byte_registers = 16
  };

  // construction
  inline friend Register as_Register(int encoding);

  VMReg as_VMReg();

  // derived registers
  Register successor() const                          { return as_Register(encoding() + 1); }

  // accessors
  int encoding() const
  { 
    assert(is_valid(), "invalid register"); 
    return value(); 
  }

  bool is_valid() const
  {
    return 0 <= value() && value() < number_of_registers;
  }

  bool has_byte_register() const
  {
    return 0 <= value() && value() < number_of_byte_registers;
  }

  const char* name() const;
};

// The integer registers of the amd64 architecture

CONSTANT_REGISTER_DECLARATION(Register, noreg, (-1));

CONSTANT_REGISTER_DECLARATION(Register, rax,    (0));
CONSTANT_REGISTER_DECLARATION(Register, rcx,    (1));
CONSTANT_REGISTER_DECLARATION(Register, rdx,    (2));
CONSTANT_REGISTER_DECLARATION(Register, rbx,    (3));
CONSTANT_REGISTER_DECLARATION(Register, rsp,    (4));
CONSTANT_REGISTER_DECLARATION(Register, rbp,    (5));
CONSTANT_REGISTER_DECLARATION(Register, rsi,    (6));
CONSTANT_REGISTER_DECLARATION(Register, rdi,    (7));
CONSTANT_REGISTER_DECLARATION(Register, r8,     (8));
CONSTANT_REGISTER_DECLARATION(Register, r9,     (9));
CONSTANT_REGISTER_DECLARATION(Register, r10,   (10));
CONSTANT_REGISTER_DECLARATION(Register, r11,   (11));
CONSTANT_REGISTER_DECLARATION(Register, r12,   (12));
CONSTANT_REGISTER_DECLARATION(Register, r13,   (13));
CONSTANT_REGISTER_DECLARATION(Register, r14,   (14));
CONSTANT_REGISTER_DECLARATION(Register, r15,   (15));


// Use FloatRegister as shortcut
class FloatRegisterImpl;
typedef FloatRegisterImpl* FloatRegister;

// The implementation of float registers for the amd64 architecture
inline FloatRegister as_FloatRegister(int encoding)
{
  return (FloatRegister)(intptr_t) encoding;
}
class FloatRegisterImpl :
  public AbstractRegisterImpl 
{
 public:
  enum {
    number_of_registers = 16
  };

  // derived registers
  FloatRegister successor() const                          { return as_FloatRegister(encoding() + 1); }

  // construction
  inline friend FloatRegister as_FloatRegister(int encoding);

  VMReg as_VMReg();

  // accessors
  int encoding() const
  { 
    assert(is_valid(), "invalid fp register"); 
    return value();
  }

  bool is_valid() const
  {
    return 0 <= value() && value() < number_of_registers;
  }

  const char* name() const;
};

// The float registers of the amd64 architecture

CONSTANT_REGISTER_DECLARATION(FloatRegister, xmmnoreg, (-1));

CONSTANT_REGISTER_DECLARATION(FloatRegister, xmm0,      (0));
CONSTANT_REGISTER_DECLARATION(FloatRegister, xmm1,      (1));
CONSTANT_REGISTER_DECLARATION(FloatRegister, xmm2,      (2));
CONSTANT_REGISTER_DECLARATION(FloatRegister, xmm3,      (3));
CONSTANT_REGISTER_DECLARATION(FloatRegister, xmm4,      (4));
CONSTANT_REGISTER_DECLARATION(FloatRegister, xmm5,      (5));
CONSTANT_REGISTER_DECLARATION(FloatRegister, xmm6,      (6));
CONSTANT_REGISTER_DECLARATION(FloatRegister, xmm7,      (7));
CONSTANT_REGISTER_DECLARATION(FloatRegister, xmm8,      (8));
CONSTANT_REGISTER_DECLARATION(FloatRegister, xmm9,      (9));
CONSTANT_REGISTER_DECLARATION(FloatRegister, xmm10,    (10));
CONSTANT_REGISTER_DECLARATION(FloatRegister, xmm11,    (11));
CONSTANT_REGISTER_DECLARATION(FloatRegister, xmm12,    (12));
CONSTANT_REGISTER_DECLARATION(FloatRegister, xmm13,    (13));
CONSTANT_REGISTER_DECLARATION(FloatRegister, xmm14,    (14));
CONSTANT_REGISTER_DECLARATION(FloatRegister, xmm15,    (15));


// Need to know the total number of registers of all sorts for SharedInfo.
// Define a class that exports it.
class ConcreteRegisterImpl :
  public AbstractRegisterImpl 
{
 public:
  enum {
    // This number must be large enough to cover REG_COUNT (defined by c2) registers.
    // There is no requirement that any ordering here matches any ordering c2 gives
    // it's optoregs.
    number_of_registers = 
      ( RegisterImpl::number_of_registers + 
        FloatRegisterImpl::number_of_registers ) * 2 +
      1 // rflags

  };
  static const int max_gpr;
  static const int max_fpr;

};


