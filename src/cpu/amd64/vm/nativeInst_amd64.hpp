#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "%W% %E% %U% JVM"
#endif
/*
 * Copyright 2003-2006 Sun Microsystems, Inc.  All Rights Reserved.
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
// - - NativeJump
// - - NativeIllegalOpCode
// - - NativeReturn   
// - - NativeReturnX (return with argument)
// - - NativePushConst
// - - NativeTstRegMem

// The base class for different kinds of native instruction abstractions.
// Provides the primitive operations to manipulate code relative to this.

class NativeInstruction VALUE_OBJ_CLASS_SPEC {
  friend class Relocation;

 public:
  enum amd64_specific_constants {
    nop_instruction_code = 0x90,
    nop_instruction_size = 1
  };

  bool is_nop() { 
    return ubyte_at(0) == nop_instruction_code;
  }

  inline bool is_call();
  inline bool is_illegal();
  inline bool is_return();
  inline bool is_jump();
  inline bool is_cond_jump();
  inline bool is_safepoint_poll();
  inline bool is_mov_literal64();
 
 protected:

  address addr_at(int offset) const {
    return address(this) + offset; 
  }

  s_char sbyte_at(int offset) const {
    return *(s_char*) addr_at(offset);
  }

  u_char ubyte_at(int offset) const {
    return *(u_char*) addr_at(offset);
  }
  
  int int_at(int offset) const {
    return *(int*) addr_at(offset);
  }

  intptr_t ptr_at(int offset) const {
    return *(intptr_t*) addr_at(offset);
  }

  oop oop_at(int offset) const {
    return *(oop*) addr_at(offset);
  }

  void set_byte_at(int offset, u_char c) {
    *(u_char*) addr_at(offset) = c;
    wrote(offset);
  }

  void set_int_at(int offset, int i) {
    *(int*) addr_at(offset) = i;
    wrote(offset);
  }

  void set_ptr_at(int offset, intptr_t ptr) {
    *(intptr_t*) addr_at(offset) = ptr;
    wrote(offset);
  }

  void set_oop_at(int offset, oop o) {
    *(oop*) addr_at(offset) = o;
    wrote(offset);
  }

  // This doesn't really do anything on Intel, but it is the place
  // where cache invalidation belongs, generically:
  void wrote(int offset);

 public:
  // unit test stuff
  static void test() {}	// override for testing

  inline friend NativeInstruction* nativeInstruction_at(address address);
};

inline NativeInstruction* nativeInstruction_at(address address) {
  NativeInstruction* inst = (NativeInstruction*) address;
#ifdef ASSERT
  //inst->verify();
#endif
  return inst;
}

inline NativeCall* nativeCall_at(address address);
inline NativeCall* nativeCall_before(address return_address);


// The NativeCall is an abstraction for accessing/manipulating native
// call rel32off instructions (used to manipulate inline caches,
// primitive & dll calls, etc.).
class NativeCall : public NativeInstruction {
 public:
  enum amd64_specific_constants {
    instruction_code      = 0xE8,
    instruction_size      = 5,
    instruction_offset    = 0,
    displacement_offset	  = 1,
    return_address_offset = 5
  };

  enum { 
    cache_line_size = BytesPerWord // conservative estimate!
  };

  address instruction_address() const       { 
    return addr_at(instruction_offset);
  }

  address next_instruction_address() const { 
    return addr_at(return_address_offset); 
  }

  int displacement() const {
    return int_at(displacement_offset);
  }

  address displacement_address() const { 
    return addr_at(displacement_offset); 
  }

  address return_address() const {
    return addr_at(return_address_offset);
  }

  address destination() const {
    // Getting the destination of a call isn't safe because that call can
    // be getting patched while you're calling this.  There's only special
    // places where this can be called but not automatically verifiable by
    // checking which locks are held.  The solution is true atomic patching
    // on amd64, nyi.
    return return_address() + displacement();
  }

  void set_destination(address dest) {
    assert((labs((intptr_t) dest - (intptr_t) return_address())  &
            0xFFFFFFFF00000000) == 0,
           "must be 32bit offset");
    set_int_at(displacement_offset, dest - return_address());
  }

  void set_destination_mt_safe(address dest);

  void verify_alignment() {
    assert((intptr_t) addr_at(displacement_offset) % BytesPerInt == 0,
           "must be aligned");
  }

  void verify();
  void print();
  
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
    return 
      nativeInstruction_at(instr)->is_call() &&
      nativeCall_at(instr)->destination() == target;
  }

  // Insertion of native call instruction
  static void insert(address code_pos, address entry);

  // MT-safe patching of a call instruction.
  static void replace_mt_safe(address instr_addr, address code_buffer);  
};

inline NativeCall* nativeCall_before(address return_address) {
  NativeCall* call = (NativeCall*) (return_address - NativeCall::return_address_offset);
#ifdef ASSERT
  call->verify();
#endif
  return call;
}

inline NativeCall* nativeCall_at(address address) {
  NativeCall* call = (NativeCall*) (address - NativeCall::instruction_offset);
#ifdef ASSERT
  call->verify();
#endif
  return call;
}

// An interface for accessing/manipulating native mov reg64, imm64
// instructions.  (used to manipulate inlined 64-bit data dll calls,
// etc.)
class NativeMovConstReg : public NativeInstruction {
 public:
  enum amd64_specific_constants {
    // instruction_code     = 0xB848, // Real byte order is 0x48 (REX_W), 0xB8
    instruction_size        = 10,     // REX_W + 0xB8 + imm64
    instruction_offset      = 0,
    data_offset             = 2,      // REX_W + 0xB8
    next_instruction_offset = 10,
    register_mask           = 0x07
  };

  address instruction_address() const {
    return addr_at(instruction_offset);
  }

  address next_instruction_address() const {
    return addr_at(next_instruction_offset);
  }

  intptr_t data() const {
    return ptr_at(data_offset);
  }

  void set_data(intptr_t x) {
    set_ptr_at(data_offset, x);
  }

  void verify();
  void print();
  
  // unit test stuff
  static void test() {}

  // Creation
  inline friend NativeMovConstReg* nativeMovConstReg_at(address address);

  inline friend NativeMovConstReg* nativeMovConstReg_before(address address);
};

inline NativeMovConstReg* nativeMovConstReg_at(address address) {
  NativeMovConstReg* test =
      (NativeMovConstReg*) (address - NativeMovConstReg::instruction_offset);
#ifdef ASSERT
  test->verify();
#endif
  return test;
}

inline NativeMovConstReg* nativeMovConstReg_before(address address) {
  NativeMovConstReg* test =
      (NativeMovConstReg*) (address - NativeMovConstReg::instruction_size - NativeMovConstReg::instruction_offset);
#ifdef ASSERT
  test->verify();
#endif
  return test;
}

class NativeMovConstRegPatching : public NativeMovConstReg {
 private:
  inline friend NativeMovConstRegPatching* nativeMovConstRegPatching_at(
    address address);
};

inline NativeMovConstRegPatching* nativeMovConstRegPatching_at( address address) {
  NativeMovConstRegPatching* test = 
        (NativeMovConstRegPatching*)(address - NativeMovConstReg::instruction_offset);

#ifdef ASSERT
  test->verify();
#endif
  return test;
}

// jmp rel32off
class NativeJump : public NativeInstruction {
 public:
  enum amd64_specific_constants {
    instruction_code        = 0xE9,
    instruction_size        = 5,
    instruction_offset      = 0,
    data_offset             = 1,
    next_instruction_offset = 5
  };

  address instruction_address() const {
    return addr_at(instruction_offset);
  }
  
  address next_instruction_address() const {
    return addr_at(next_instruction_offset);
  }  
  
  address jump_destination() const {
    address dest = address(int_at(data_offset) + next_instruction_address());
    // return -1 if jump to self
    return (dest == (address) this) ? (address) -1 : dest;
  }

  void set_jump_destination(address dest) { 
    if (dest == (address) -1) { // can't encode jump to -1
      set_int_at(data_offset, -5); // jump to self
    } else {
      assert((labs((intptr_t) dest - (intptr_t) next_instruction_address())  &
              0xFFFFFFFF00000000) == 0,
             "must be 32bit offset");
      set_int_at(data_offset, int(dest - next_instruction_address()));
    }
  }

  // Creation
  inline friend NativeJump* nativeJump_at(address address);

  void verify();

  // Unit testing stuff
  static void test() {}

  // Insertion of native jump instruction
  static void insert(address code_pos, address entry);
  // MT-safe insertion of native jump at verified method entry
  static void check_verified_entry_alignment(address entry,
                                             address verified_entry);
  static void patch_verified_entry(address entry,
                                   address verified_entry,
                                   address dest);
};

inline NativeJump* nativeJump_at(address address) {
  NativeJump* jump = (NativeJump*) (address - NativeJump::instruction_offset);
#ifdef ASSERT
  jump->verify();
#endif
  return jump;
}

// Handles all kinds of jump on Intel. Long/far, conditional/unconditional
class NativeGeneralJump : public NativeInstruction {
 public:
  enum amd64_specific_constants {
    // Constans does not apply, since the lengths and offsets depends
    // on the accually jump used
    // Instruction codes:
    //   Unconditional jumps: 0xE9    (rel32off), 0xEB (rel8off)
    //   Conditional jumps:   0x0F8x  (rel32off), 0x7x (rel8off)
    unconditional_long_jump  = 0xe9,
    unconditional_short_jump = 0xeb,
    instruction_size         = 5
  };

  address instruction_address() const {
    return addr_at(0);
  }

  address jump_destination() const;

  // Creation
  inline friend NativeGeneralJump* nativeGeneralJump_at(address address);

  // Insertion of native general jump instruction
  static void insert_unconditional(address code_pos, address entry);

  // MT-safe patching of an unconditional jump instruction.
  static void replace_mt_safe(address instr_addr, address code_buffer);

  void verify();
};

inline NativeGeneralJump* nativeGeneralJump_at(address address) {
  NativeGeneralJump* jump = (NativeGeneralJump*) address;
  debug_only(jump->verify();)
  return jump;
}


class NativePopReg : public NativeInstruction {
 public:
  enum amd64_specific_constants {
    instruction_code        = 0x58,
    instruction_size        = 1,
    instruction_offset      = 0,
    data_offset             = 1,
    next_instruction_offset = 1
  };

  // Insert a pop instruction
  static void insert(address code_pos, Register reg);
};

class NativeIllegalInstruction : public NativeInstruction {
 public:
  enum amd64_specific_constants {
    instruction_code        = 0x0B0F, // ud2a, real byte order is: 0x0F, 0x0B
    instruction_size        = 2,
    instruction_offset      = 0,
    next_instruction_offset = 2
  };

  // Insert illegal opcode as specific address
  static void insert(address code_pos);
};

// return instruction that does not pop values of the stack
class NativeReturn : public NativeInstruction {
 public:
  enum amd64_specific_constants {
    instruction_code        = 0xC3,
    instruction_size        = 1,
    instruction_offset      = 0,
    next_instruction_offset = 1
  };
};

// return instruction that does pop values of the stack
class NativeReturnX : public NativeInstruction {
 public:
  enum amd64_specific_constants {
    instruction_code        = 0xC2,
    instruction_size        = 3,
    instruction_offset      = 0,
    next_instruction_offset = 3
  };
};

// Simple test vs memory
class NativeTstRegMem : public NativeInstruction {
 public:
  enum amd64_specific_constants {
    instruction_code_regImem = 0x85
  };
};

inline bool NativeInstruction::is_illegal() {
  return 
    (short) int_at(0) == (short) NativeIllegalInstruction::instruction_code;
}

inline bool NativeInstruction::is_call() {
  return ubyte_at(0) == NativeCall::instruction_code;
}

inline bool NativeInstruction::is_return() {
  return 
    ubyte_at(0) == NativeReturn::instruction_code ||
    ubyte_at(0) == NativeReturnX::instruction_code;
} 

inline bool NativeInstruction::is_jump() {
  return 
    ubyte_at(0) == NativeJump::instruction_code ||
    ubyte_at(0) == 0xEB; /* short jump */
}

inline bool NativeInstruction::is_cond_jump() { 
  return 
    (int_at(0) & 0xF0FF) == 0x800F /* long jump */ ||
    (ubyte_at(0) & 0xF0) == 0x70; /* short jump */
}

inline bool NativeInstruction::is_safepoint_poll() {
  return
    ubyte_at(0) == NativeTstRegMem::instruction_code_regImem &&
    ubyte_at(1) == 0x05 && // 00 rax 101
    ((intptr_t) addr_at(6)) + int_at(2) == (intptr_t) os::get_polling_page();
}

inline bool NativeInstruction::is_mov_literal64() {
    return ((ubyte_at(0) == Assembler::REX_W || ubyte_at(0) == Assembler::REX_WB) &&
          (ubyte_at(1) & (0xff ^ NativeMovConstReg::register_mask)) == 0xB8);
}
