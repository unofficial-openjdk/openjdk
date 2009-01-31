#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)register_i486.hpp	1.26 07/05/05 17:04:19 JVM"
#endif
/*
 * Copyright 2000-2007 Sun Microsystems, Inc.  All Rights Reserved.
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


// The implementation of integer registers for the ia32 architecture
inline Register as_Register(int encoding) {
  return (Register)(intptr_t) encoding;
}

class RegisterImpl: public AbstractRegisterImpl {
 public:
  enum {
    number_of_registers      = 8,
    number_of_byte_registers = 4
  };

  // derived registers, offsets, and addresses
  Register successor() const                          { return as_Register(encoding() + 1); }

  // construction
  inline friend Register as_Register(int encoding);

  VMReg as_VMReg();

  // accessors
  int   encoding() const                         { assert(is_valid(), "invalid register"); return (int)this; }
  bool  is_valid() const                         { return 0 <= (int)this && (int)this < number_of_registers; }
  bool  has_byte_register() const                { return 0 <= (int)this && (int)this < number_of_byte_registers; }
  const char* name() const;
};

// The integer registers of the ia32 architecture

CONSTANT_REGISTER_DECLARATION(Register, noreg, (-1));

CONSTANT_REGISTER_DECLARATION(Register, eax  , ( 0));
CONSTANT_REGISTER_DECLARATION(Register, ecx  , ( 1));
CONSTANT_REGISTER_DECLARATION(Register, edx  , ( 2));
CONSTANT_REGISTER_DECLARATION(Register, ebx  , ( 3));

CONSTANT_REGISTER_DECLARATION(Register, esp  , ( 4));
CONSTANT_REGISTER_DECLARATION(Register, ebp  , ( 5));
CONSTANT_REGISTER_DECLARATION(Register, esi  , ( 6));
CONSTANT_REGISTER_DECLARATION(Register, edi  , ( 7));


// Use FloatRegister as shortcut
class FloatRegisterImpl;
typedef FloatRegisterImpl* FloatRegister;

inline FloatRegister as_FloatRegister(int encoding) {
  return (FloatRegister)(intptr_t) encoding;
}

// The implementation of floating point registers for the ia32 architecture
class FloatRegisterImpl: public AbstractRegisterImpl {
 public:
  enum {
    number_of_registers = 8
  };

  // construction
  inline friend FloatRegister as_FloatRegister(int encoding);

  VMReg as_VMReg();

  // derived registers, offsets, and addresses
  FloatRegister successor() const                          { return as_FloatRegister(encoding() + 1); }

  // accessors
  int   encoding() const                          { assert(is_valid(), "invalid register"); return (int)this; }
  bool  is_valid() const                          { return 0 <= (int)this && (int)this < number_of_registers; }
  const char* name() const;

};

// Use XMMRegister as shortcut
class XMMRegisterImpl;
typedef XMMRegisterImpl* XMMRegister;

// Use MMXRegister as shortcut
class MMXRegisterImpl;
typedef MMXRegisterImpl* MMXRegister;

inline XMMRegister as_XMMRegister(int encoding) {
  return (XMMRegister)encoding;
}

inline MMXRegister as_MMXRegister(int encoding) {
  return (MMXRegister)encoding;
}

// The implementation of XMM registers for the IA32 architecture
class XMMRegisterImpl: public AbstractRegisterImpl {
 public:
  enum {
    number_of_registers = 8
  };

  // construction
  friend XMMRegister as_XMMRegister(int encoding);

  VMReg as_VMReg();

  // derived registers, offsets, and addresses
  XMMRegister successor() const                          { return as_XMMRegister(encoding() + 1); }

  // accessors
  int   encoding() const                          { assert(is_valid(), "invalid register"); return (int)this; }
  bool  is_valid() const                          { return 0 <= (int)this && (int)this < number_of_registers; }
  const char* name() const;
};


// The XMM registers, for P3 and up chips
CONSTANT_REGISTER_DECLARATION(XMMRegister, xnoreg , (-1));
CONSTANT_REGISTER_DECLARATION(XMMRegister, xmm0 , ( 0));
CONSTANT_REGISTER_DECLARATION(XMMRegister, xmm1 , ( 1));
CONSTANT_REGISTER_DECLARATION(XMMRegister, xmm2 , ( 2));
CONSTANT_REGISTER_DECLARATION(XMMRegister, xmm3 , ( 3));
CONSTANT_REGISTER_DECLARATION(XMMRegister, xmm4 , ( 4));
CONSTANT_REGISTER_DECLARATION(XMMRegister, xmm5 , ( 5));
CONSTANT_REGISTER_DECLARATION(XMMRegister, xmm6 , ( 6));
CONSTANT_REGISTER_DECLARATION(XMMRegister, xmm7 , ( 7));

// The implementation of MMX registers for the IA32 architecture
class MMXRegisterImpl: public AbstractRegisterImpl {
 public:
  enum {
    number_of_registers = 8
  };

  // construction
  friend MMXRegister as_MMXRegister(int encoding);

  // accessors
  int   encoding() const                          { assert(is_valid(), "invalid register"); return (int)this; }
  bool  is_valid() const                          { return 0 <= (int)this && (int)this < number_of_registers; }
  const char* name() const;
};


// The MMX registers, for P3 and up chips
CONSTANT_REGISTER_DECLARATION(MMXRegister, mnoreg , (-1));
CONSTANT_REGISTER_DECLARATION(MMXRegister, mmx0 , ( 0));
CONSTANT_REGISTER_DECLARATION(MMXRegister, mmx1 , ( 1));
CONSTANT_REGISTER_DECLARATION(MMXRegister, mmx2 , ( 2));
CONSTANT_REGISTER_DECLARATION(MMXRegister, mmx3 , ( 3));
CONSTANT_REGISTER_DECLARATION(MMXRegister, mmx4 , ( 4));
CONSTANT_REGISTER_DECLARATION(MMXRegister, mmx5 , ( 5));
CONSTANT_REGISTER_DECLARATION(MMXRegister, mmx6 , ( 6));
CONSTANT_REGISTER_DECLARATION(MMXRegister, mmx7 , ( 7));

// Need to know the total number of registers of all sorts for SharedInfo.
// Define a class that exports it.
class ConcreteRegisterImpl : public AbstractRegisterImpl {
 public:
  enum {
  // A big enough number for C2: all the registers plus flags
  // This number must be large enough to cover REG_COUNT (defined by c2) registers.
  // There is no requirement that any ordering here matches any ordering c2 gives
  // it's optoregs.

    number_of_registers =      RegisterImpl::number_of_registers + 
                           2 * FloatRegisterImpl::number_of_registers + 
                           2 * XMMRegisterImpl::number_of_registers +
                           1 // eflags
  };

  static const int max_gpr;
  static const int max_fpr;
  static const int max_xmm;

};
