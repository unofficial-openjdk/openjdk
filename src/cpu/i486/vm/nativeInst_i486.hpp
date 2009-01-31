#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)nativeInst_i486.hpp	1.80 07/06/08 20:56:42 JVM"
#endif
/*
 * Copyright 1997-2006 Sun Microsystems, Inc.  All Rights Reserved.
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

// We have interfaces for the following instructions:
// - NativeInstruction
// - - NativeCall
// - - NativeMovConstReg
// - - NativeMovConstRegPatching
// - - NativeMovRegMem
// - - NativeMovRegMemPatching
// - - NativeJump
// - - NativeIllegalOpCode
// - - NativeGeneralJump
// - - NativeReturn   
// - - NativeReturnX (return with argument)
// - - NativePushConst
// - - NativeTstRegMem

// The base class for different kinds of native instruction abstractions.
// Provides the primitive operations to manipulate code relative to this.

class NativeInstruction VALUE_OBJ_CLASS_SPEC {
  friend class Relocation;

 public:
  enum Intel_specific_constants {
    nop_instruction_code        = 0x90,
    nop_instruction_size        =    1
  };

  bool is_nop()                        { return (char_at(0)&0xFF) == nop_instruction_code; }
  inline bool is_call();
  inline bool is_illegal();
  inline bool is_return();
  inline bool is_jump();
  inline bool is_cond_jump();
  inline bool is_safepoint_poll();

 protected:
  address addr_at(int offset) const    { return address(this) + offset; }

  char char_at(int offset) const       { return  (char)*addr_at(offset); }
  int  long_at(int offset) const       { return *(jint*)addr_at(offset); }
  oop  oop_at (int offset) const       { return *(oop*) addr_at(offset); }

  void set_char_at(int offset, char c) { *addr_at(offset) = (u_char)c; wrote(offset); }
  void set_long_at(int offset, int  i) { *(jint*)addr_at(offset) = i;  wrote(offset); }
  void set_oop_at (int offset, oop  o) { *(oop*) addr_at(offset) = o;  wrote(offset); }

  // This doesn't really do anything on Intel, but it is the place where
  // cache invalidation belongs, generically:
  void wrote(int offset);

 public:

  // unit test stuff
  static void test() {}			// override for testing

  inline friend NativeInstruction* nativeInstruction_at(address address);
};

inline NativeInstruction* nativeInstruction_at(address address) {
  NativeInstruction* inst = (NativeInstruction*)address;
#ifdef ASSERT
  //inst->verify();
#endif
  return inst;
}

inline NativeCall* nativeCall_at(address address);
// The NativeCall is an abstraction for accessing/manipulating native call imm32
// instructions (used to manipulate inline caches, primitive & dll calls, etc.).

class NativeCall: public NativeInstruction {
 public:
  enum Intel_specific_constants {
    instruction_code	        = 0xE8,
    instruction_size	        =    5,
    instruction_offset	        =    0,
    displacement_offset	        =    1,
    return_address_offset	=    5
  };

  enum { cache_line_size = BytesPerWord };  // conservative estimate!

  address instruction_address() const       { return addr_at(instruction_offset); }
  address next_instruction_address() const  { return addr_at(return_address_offset); }
  int   displacement() const                { return long_at(displacement_offset); }
  address displacement_address() const      { return addr_at(displacement_offset); }
  address return_address() const            { return addr_at(return_address_offset); }
  address destination() const;
  void  set_destination(address dest)       { set_long_at(displacement_offset, dest - return_address()); }
  void  set_destination_mt_safe(address dest);

  void  verify_alignment() { assert((intptr_t)addr_at(displacement_offset) % BytesPerWord == 0, "must be aligned"); }
  void  verify();
  void  print();
  
  // Creation
  inline friend NativeCall* nativeCall_at(address address);
  inline friend NativeCall* nativeCall_before(address return_address);

  static bool is_call_at(address instr) {
    return ((*instr) & 0xFF) == NativeCall::instruction_code;
  }

  static bool is_call_before(address return_address) {
    return is_call_at(return_address - NativeCall::return_address_offset);
  }

  static bool is_call_to(address instr, address target) {
    return nativeInstruction_at(instr)->is_call() &&
      nativeCall_at(instr)->destination() == target;
  }

  // MT-safe patching of a call instruction.
  static void insert(address code_pos, address entry);

  static void replace_mt_safe(address instr_addr, address code_buffer);  
};

inline NativeCall* nativeCall_at(address address) {
  NativeCall* call = (NativeCall*)(address - NativeCall::instruction_offset);
#ifdef ASSERT
  call->verify();
#endif
  return call;
}

inline NativeCall* nativeCall_before(address return_address) {
  NativeCall* call = (NativeCall*)(return_address - NativeCall::return_address_offset);
#ifdef ASSERT
  call->verify();
#endif
  return call;
}

// An interface for accessing/manipulating native mov reg, imm32 instructions.
// (used to manipulate inlined 32bit data dll calls, etc.)
class NativeMovConstReg: public NativeInstruction {
 public:
  enum Intel_specific_constants {
    instruction_code	        = 0xB8,
    instruction_size	        =    5,
    instruction_offset	        =    0,
    data_offset	                =    1,
    next_instruction_offset	=    5,
    register_mask	        = 0x07
  };

  address instruction_address() const       { return addr_at(instruction_offset); }
  address next_instruction_address() const  { return addr_at(next_instruction_offset); }
  int   data() const                        { return long_at(data_offset); }
  void  set_data(int x)                     { set_long_at(data_offset, x); }

  void  verify();
  void  print();
  
  // unit test stuff
  static void test() {}

  // Creation
  inline friend NativeMovConstReg* nativeMovConstReg_at(address address);
  inline friend NativeMovConstReg* nativeMovConstReg_before(address address);
};

inline NativeMovConstReg* nativeMovConstReg_at(address address) {
  NativeMovConstReg* test = (NativeMovConstReg*)(address - NativeMovConstReg::instruction_offset);
#ifdef ASSERT
  test->verify();
#endif
  return test;
}

inline NativeMovConstReg* nativeMovConstReg_before(address address) {
  NativeMovConstReg* test = (NativeMovConstReg*)(address - NativeMovConstReg::instruction_size - NativeMovConstReg::instruction_offset);
#ifdef ASSERT
  test->verify();
#endif
  return test;
}

class NativeMovConstRegPatching: public NativeMovConstReg {
 private:
    friend NativeMovConstRegPatching* nativeMovConstRegPatching_at(address address) {
    NativeMovConstRegPatching* test = (NativeMovConstRegPatching*)(address - instruction_offset);
    #ifdef ASSERT
      test->verify();
    #endif
    return test;
  }
};


// An interface for accessing/manipulating native moves of the form:
//	mov[b/w/l] [reg + offset], reg   (instruction_code_reg2mem)
//      mov[b/w/l] reg, [reg+offset]     (instruction_code_mem2reg
//      mov[s/z]x[w/b] [reg + offset], reg 
//      fld_s  [reg+offset]
//      fld_d  [reg+offset]
//	fstp_s [reg + offset]
//	fstp_d [reg + offset]
//
// Warning: These routines must be able to handle any instruction sequences
// that are generated as a result of the load/store byte,word,long
// macros.  For example: The load_unsigned_byte instruction generates
// an xor reg,reg inst prior to generating the movb instruction.  This
// class must skip the xor instruction.  

class NativeMovRegMem: public NativeInstruction {
 public:
  enum Intel_specific_constants {
    instruction_code_xor		= 0x33,
    instruction_extended_prefix	        = 0x0F,
    instruction_code_mem2reg_movzxb     = 0xB6,
    instruction_code_mem2reg_movsxb     = 0xBE,
    instruction_code_mem2reg_movzxw     = 0xB7,
    instruction_code_mem2reg_movsxw     = 0xBF,
    instruction_operandsize_prefix      = 0x66,
    instruction_code_reg2meml	        = 0x89,
    instruction_code_mem2regl	        = 0x8b,
    instruction_code_reg2memb	        = 0x88,
    instruction_code_mem2regb	        = 0x8a,
    instruction_code_float_s	        = 0xd9,
    instruction_code_float_d	        = 0xdd,
    instruction_code_long_volatile      = 0xdf,
    instruction_code_xmm_ss_prefix      = 0xf3,
    instruction_code_xmm_sd_prefix      = 0xf2,
    instruction_code_xmm_code           = 0x0f,
    instruction_code_xmm_load           = 0x10,
    instruction_code_xmm_store          = 0x11,
    instruction_code_xmm_lpd            = 0x12,
    
    instruction_size	                = 4,
    instruction_offset	                = 0,
    data_offset	                        = 2,
    next_instruction_offset	        = 4
  };

  address instruction_address() const { 
    if (*addr_at(instruction_offset)   == instruction_operandsize_prefix && 
        *addr_at(instruction_offset+1) != instruction_code_xmm_code) {
      return addr_at(instruction_offset+1); // Not SSE instructions
    }
    else if (*addr_at(instruction_offset) == instruction_extended_prefix) {
      return addr_at(instruction_offset+1);
    }
    else if (*addr_at(instruction_offset) == instruction_code_xor) {
      return addr_at(instruction_offset+2);
    }
    else return addr_at(instruction_offset);
  }

  address next_instruction_address() const {
    switch (*addr_at(instruction_offset)) {
    case instruction_operandsize_prefix:
      if (*addr_at(instruction_offset+1) == instruction_code_xmm_code)
        return instruction_address() + instruction_size; // SSE instructions
    case instruction_extended_prefix:
      return instruction_address() + instruction_size + 1;
    case instruction_code_reg2meml:
    case instruction_code_mem2regl:
    case instruction_code_reg2memb:
    case instruction_code_mem2regb:
    case instruction_code_xor:
      return instruction_address() + instruction_size + 2;
    default:
      return instruction_address() + instruction_size;
    }
  }
  int   offset() const{ 
    if (*addr_at(instruction_offset)   == instruction_operandsize_prefix && 
        *addr_at(instruction_offset+1) != instruction_code_xmm_code) {
      return long_at(data_offset+1); // Not SSE instructions
    }
    else if (*addr_at(instruction_offset) == instruction_extended_prefix) {
      return long_at(data_offset+1); 
    }
    else if (*addr_at(instruction_offset) == instruction_code_xor || 
             *addr_at(instruction_offset) == instruction_code_xmm_ss_prefix ||
             *addr_at(instruction_offset) == instruction_code_xmm_sd_prefix ||
             *addr_at(instruction_offset) == instruction_operandsize_prefix) {
      return long_at(data_offset+2); 
    }
    else return long_at(data_offset); 
  }

  void  set_offset(int x) {
    if (*addr_at(instruction_offset)   == instruction_operandsize_prefix && 
        *addr_at(instruction_offset+1) != instruction_code_xmm_code) {
      set_long_at(data_offset+1, x); // Not SSE instructions
    }
    else if (*addr_at(instruction_offset) == instruction_extended_prefix) {
      set_long_at(data_offset+1, x); 
    }
    else if (*addr_at(instruction_offset) == instruction_code_xor || 
             *addr_at(instruction_offset) == instruction_code_xmm_ss_prefix ||
             *addr_at(instruction_offset) == instruction_code_xmm_sd_prefix ||
             *addr_at(instruction_offset) == instruction_operandsize_prefix) {
      set_long_at(data_offset+2, x); 
    }
    else set_long_at(data_offset, x); 
  }

  void  add_offset_in_bytes(int add_offset)     { set_offset ( ( offset() + add_offset ) ); }
  void  copy_instruction_to(address new_instruction_address);

  void verify();
  void print ();

  // unit test stuff
  static void test() {}

 private:
  inline friend NativeMovRegMem* nativeMovRegMem_at (address address);
};

inline NativeMovRegMem* nativeMovRegMem_at (address address) {
  NativeMovRegMem* test = (NativeMovRegMem*)(address - NativeMovRegMem::instruction_offset);
#ifdef ASSERT
  test->verify();
#endif
  return test;
}

class NativeMovRegMemPatching: public NativeMovRegMem {
 private:
  friend NativeMovRegMemPatching* nativeMovRegMemPatching_at (address address) {
    NativeMovRegMemPatching* test = (NativeMovRegMemPatching*)(address - instruction_offset);
    #ifdef ASSERT
      test->verify();
    #endif
    return test;
  }
};



// An interface for accessing/manipulating native leal instruction of form:
//        leal reg, [reg + offset]

class NativeLoadAddress: public NativeMovRegMem {
 public:
  enum Intel_specific_constants {
    instruction_code	        = 0x8D
  };

  void verify();
  void print ();

  // unit test stuff
  static void test() {}

 private:
  friend NativeLoadAddress* nativeLoadAddress_at (address address) {
    NativeLoadAddress* test = (NativeLoadAddress*)(address - instruction_offset);
    #ifdef ASSERT
      test->verify();
    #endif
    return test;
  }
};

// far jump
class NativeJump: public NativeInstruction {
 public:
  enum Intel_specific_constants {
    instruction_code	        = 0xe9,
    instruction_size	        =    5,
    instruction_offset	        =    0,
    data_offset	                =    1,
    next_instruction_offset	=    5
  };

  address instruction_address() const       { return addr_at(instruction_offset); }
  address next_instruction_address() const  { return addr_at(next_instruction_offset); }  
  address jump_destination() const          { return address (long_at(data_offset)+next_instruction_address()); }
  void  set_jump_destination(address dest)  { set_long_at(data_offset, int(dest - next_instruction_address()) ); }

  // Creation
  inline friend NativeJump* nativeJump_at(address address);

  void verify();

  // Unit testing stuff
  static void test() {}

  // Insertion of native jump instruction
  static void insert(address code_pos, address entry);
  // MT-safe insertion of native jump at verified method entry
  static void check_verified_entry_alignment(address entry, address verified_entry);
  static void patch_verified_entry(address entry, address verified_entry, address dest);
};

inline NativeJump* nativeJump_at(address address) {
  NativeJump* jump = (NativeJump*)(address - NativeJump::instruction_offset);
#ifdef ASSERT
  jump->verify();
#endif
  return jump;
}

// Handles all kinds of jump on Intel. Long/far, conditional/unconditional
class NativeGeneralJump: public NativeInstruction {
 public:
  enum Intel_specific_constants {
    // Constans does not apply, since the lengths and offsets depends on the accually jump
    // used
    // Instruction codes:
    //   Unconditional jumps: 0xE9    (long), 0xEB (short)
    //   Conditional jumps:   0x0F8x  (long), 0x7x (short)
    unconditional_long_jump  = 0xe9,
    unconditional_short_jump = 0xeb,
    instruction_size = 5
  };

  address instruction_address() const       { return addr_at(0); }  
  address jump_destination()	const;          

  // Creation
  inline friend NativeGeneralJump* nativeGeneralJump_at(address address);

  // Insertion of native general jump instruction
  static void insert_unconditional(address code_pos, address entry);
  static void replace_mt_safe(address instr_addr, address code_buffer);  

  void verify();
};

inline NativeGeneralJump* nativeGeneralJump_at(address address) {
  NativeGeneralJump* jump = (NativeGeneralJump*)(address);
  debug_only(jump->verify();)    
  return jump;
}

class NativePopReg : public NativeInstruction {
 public:
  enum Intel_specific_constants {
    instruction_code	        = 0x58,
    instruction_size	        =    1,
    instruction_offset	        =    0,
    data_offset	                =    1,
    next_instruction_offset	=    1
  };

  // Insert a pop instruction
  static void insert(address code_pos, Register reg);
};


class NativeIllegalInstruction: public NativeInstruction {
 public:
  enum Intel_specific_constants {
    instruction_code	        = 0x0B0F,    // Real byte order is: 0x0F, 0x0B
    instruction_size	        =    2,
    instruction_offset	        =    0,    
    next_instruction_offset	=    2
  };

  // Insert illegal opcode as specific address
  static void insert(address code_pos);
};

// return instruction that does not pop values of the stack
class NativeReturn: public NativeInstruction {
 public:
  enum Intel_specific_constants {
    instruction_code	        = 0xC3, 
    instruction_size	        =    1,
    instruction_offset	        =    0,    
    next_instruction_offset	=    1
  };
};

// return instruction that does pop values of the stack
class NativeReturnX: public NativeInstruction {
 public:
  enum Intel_specific_constants {
    instruction_code	        = 0xC2, 
    instruction_size	        =    2,
    instruction_offset	        =    0,    
    next_instruction_offset	=    2
  };
};

// Simple test vs memory
class NativeTstRegMem: public NativeInstruction {
 public:
  enum Intel_specific_constants {
    instruction_code_memXregl   = 0x85
  };
};

inline bool NativeInstruction::is_illegal()	 { return (short)long_at(0) == (short)NativeIllegalInstruction::instruction_code; }
inline bool NativeInstruction::is_call()	 { return (char_at(0)&0xFF) == NativeCall::instruction_code; }
inline bool NativeInstruction::is_return()	 { return (char_at(0)&0xFF) == NativeReturn::instruction_code ||
 							  (char_at(0)&0xFF) == NativeReturnX::instruction_code; } 
inline bool NativeInstruction::is_jump()         { return (char_at(0)&0xFF) == NativeJump::instruction_code ||
                                                          (char_at(0)&0xFF) == 0xEB; /* short jump */ }
inline bool NativeInstruction::is_cond_jump()    { return (long_at(0)&0xF0FF) == 0x800F /* long jump */ ||
                                                          (char_at(0)&0xF0)   == 0x70;  /* short jump */ }
inline bool NativeInstruction::is_safepoint_poll()
                                                 { return ( (char_at(0)&0xFF) == NativeMovRegMem::instruction_code_mem2regl ||
                                                            (char_at(0)&0xFF) == NativeTstRegMem::instruction_code_memXregl ) &&
                                                          (char_at(1)&0xC7) == 0x05 && /* Mod R/M == disp32 */
                                                          (os::is_poll_address((address)long_at(2))); }

