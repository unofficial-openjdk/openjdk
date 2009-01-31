#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)nativeInst_i486.cpp	1.75 07/06/08 20:56:40 JVM"
#endif
/*
 * Copyright 1997-2007 Sun Microsystems, Inc.  All Rights Reserved.
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
# include "incls/_nativeInst_i486.cpp.incl"

void NativeInstruction::wrote(int offset) {
  ICache::invalidate_word(addr_at(offset));
}


void NativeCall::verify() {
  // Make sure code pattern is actually a call imm32 instruction.
  int inst = char_at(0) & 0xFF;
  if (inst != instruction_code) {
    tty->print_cr("Addr: " INTPTR_FORMAT " Code: 0x%x", instruction_address(),
                                                        inst);
    fatal("not a call imm32");
  }
}

address NativeCall::destination() const {
  // Getting the destination of a call isn't safe because that call can
  // be getting patched while you're calling this.  There's only special
  // places where this can be called but not automatically verifiable by
  // checking which locks are held.  The solution is true atomic patching
  // on x86, nyi.
  return return_address() + displacement();
}
 
void NativeCall::print() {
  tty->print_cr("0x%x: call 0x%x", instruction_address(), destination());
}

// Inserts a native call instruction at a given pc
void NativeCall::insert(address code_pos, address entry) {
  *code_pos = instruction_code;
  *((long *)(code_pos+1)) = (int)entry - ((int)code_pos + 1 + sizeof(long));
  ICache::invalidate_range(code_pos, instruction_size);
}

// MT-safe patching of a call instruction.
// First patches first word of instruction to two jmp's that jmps to them
// selfs (spinlock). Then patches the last byte, and then atomicly replaces
// the jmp's with the first 4 byte of the new instruction.
void NativeCall::replace_mt_safe(address instr_addr, address code_buffer) {
  assert(Patching_lock->is_locked() ||
         SafepointSynchronize::is_at_safepoint(), "concurrent code patching"); 
   assert (instr_addr != NULL, "illegal address for code patching");
#ifdef ASSERT
   NativeCall* n_call =  nativeCall_at (instr_addr); // checking that it is a call 
   if (os::is_MP()) {
     assert(((intx)instr_addr % BytesPerWord == 0), "must be aligned");
   }
#endif // ASSERT

   // Tempoary code
   unsigned char patch[4];
   assert(sizeof(patch)==sizeof(jint), "sanity check");
   patch[0] = 0xEB;       // jmp rel8
   patch[1] = 0xFE;       // jmp to self
   patch[2] = 0xEB;
   patch[3] = 0xFE;
   
   // First patch dummy jmp in place
   *(jint*)instr_addr = *(jint *)patch;

   // Patch 4th byte
   address byte_4_adr = instr_addr+4;
   *byte_4_adr = code_buffer[4];

   // Patch bytes 0-3
   *(jint*)instr_addr = *(jint *)code_buffer;  

#ifdef ASSERT
   // verify patching
   for ( int i = 0; i < NativeCall::instruction_size; i++) { // bytewise comparing
     address ptr = (address)((int)code_buffer + i);     
     int a_byte = (*ptr) & 0xFF;
     assert(*((address)((int)instr_addr + i)) == a_byte, "mt safe patching failed");
   }
#endif

   ICache::invalidate_range(instr_addr, NativeCall::instruction_size);
}


// Similar to replace_mt_safe, but just changes the destination.  The
// important thing is that free-running threads are able to execute this
// call instruction at all times.  If the displacement field is aligned
// we can simply rely on atomicity of 32-bit writes to make sure other threads
// will see no intermediate states.  Otherwise, the first two bytes of the
// call are guaranteed to be aligned, and can be atomically patched to a
// self-loop to guard the instruction while we change the other bytes.

// We cannot rely on locks here, since the free-running threads must run at
// full speed.
//
// Used in the runtime linkage of calls; see class CompiledIC.
// (Cf. 4506997 and 4479829, where threads witnessed garbage displacements.)
void NativeCall::set_destination_mt_safe(address dest) {
  debug_only(verify());
  // Make sure patching code is locked.  No two threads can patch at the same
  // time but one may be executing this code.
  assert(Patching_lock->is_locked() ||
         SafepointSynchronize::is_at_safepoint(), "concurrent code patching"); 
  // Both C1 and C2 should now be generating code which aligns the patched address
  // to be within a single cache line except that C1 does not do the alignment on
  // uniprocessor systems.
  assert(!os::is_MP() || ((uintptr_t)displacement_address() / cache_line_size ==
      ((uintptr_t)displacement_address()+3) / cache_line_size), "destination should be aligned");
  if ((uintptr_t)displacement_address() / cache_line_size ==
      ((uintptr_t)displacement_address()+3) / cache_line_size) {
    // Simple case:  The destination lies within a single cache line.
    set_destination(dest);
  } else if ((uintptr_t)instruction_address() / cache_line_size ==
	     ((uintptr_t)instruction_address()+1) / cache_line_size) {
    // Tricky case:  The instruction prefix lies within a single cache line.
    int disp = dest - return_address();
    int call_opcode = instruction_address()[0];

    // First patch dummy jump in place:
    {
      unsigned char patch_jump[2];
      patch_jump[0] = 0xEB;       // jmp rel8
      patch_jump[1] = 0xFE;       // jmp to self
      assert(sizeof(patch_jump)==sizeof(short), "sanity check");
      *(short*)instruction_address() = *(short*)patch_jump;
    }

    OrderAccess::fence();
    // (Note: We assume any reader which has already started to read
    // the unpatched call will completely read the whole unpatched call
    // without seeing the next writes we are about to make.)

    // Next, patch the last three bytes:
    unsigned char patch_disp[5];
    patch_disp[0] = call_opcode;
    *(int*)&patch_disp[1] = disp;
    assert(sizeof(patch_disp)==instruction_size, "sanity check");
    for (int i = sizeof(short); i < instruction_size; i++)
      instruction_address()[i] = patch_disp[i];

    OrderAccess::fence();
    // (Note: We assume that any reader which reads the opcode we are
    // about to repatch will also read the writes we just made.)

    // Finally, overwrite the jump:
    *(short*)instruction_address() = *(short*)&patch_disp[0];
    
    debug_only(verify());
    guarantee(destination() == dest, "patch succeeded");
  } else {
    // Impossible:  One or the other must be atomically writable.
    ShouldNotReachHere();
  }
  ICache::invalidate_range(instruction_address(), instruction_size);
}


void NativeMovConstReg::verify() {
  // make sure code pattern is actually a mov reg, imm32 instruction
  u_char test_byte = *(u_char*)instruction_address();
  u_char test_byte_2 = test_byte & ( 0xff ^ register_mask);
  if (test_byte_2 != instruction_code) fatal("not a mov reg, imm32");
}


void NativeMovConstReg::print() {
  tty->print_cr("0x%x: mov reg, 0x%x", instruction_address(), data());
}

//-------------------------------------------------------------------

void NativeMovRegMem::copy_instruction_to(address new_instruction_address) {
  int inst_size = instruction_size;

  // See if there's an instruction size prefix override.
  if ( *(address(this))   == instruction_operandsize_prefix &&
       *(address(this)+1) != instruction_code_xmm_code ) { // Not SSE instr
    inst_size += 1;
  }
  if ( *(address(this)) == instruction_extended_prefix ) inst_size += 1;

  for (int i = 0; i < instruction_size; i++) {
    *(new_instruction_address + i) = *(address(this) + i);
  }
}

void NativeMovRegMem::verify() {
  // make sure code pattern is actually a mov [reg+offset], reg instruction
  u_char test_byte = *(u_char*)instruction_address();
  if ( ! ( (test_byte == instruction_code_reg2memb) 
      || (test_byte == instruction_code_mem2regb) 
      || (test_byte == instruction_code_mem2regl) 
      || (test_byte == instruction_code_reg2meml) 
      || (test_byte == instruction_code_mem2reg_movzxb )
      || (test_byte == instruction_code_mem2reg_movzxw )
      || (test_byte == instruction_code_mem2reg_movsxb )
      || (test_byte == instruction_code_mem2reg_movsxw )
      || (test_byte == instruction_code_float_s) 
      || (test_byte == instruction_code_float_d)
      || (test_byte == instruction_code_long_volatile) ) )
  {
    u_char byte1 = ((u_char*)instruction_address())[1];
    u_char byte2 = ((u_char*)instruction_address())[2];
    if ((test_byte != instruction_code_xmm_ss_prefix &&
         test_byte != instruction_code_xmm_sd_prefix &&
         test_byte != instruction_operandsize_prefix) ||
        byte1 != instruction_code_xmm_code ||
        (byte2 != instruction_code_xmm_load && 
         byte2 != instruction_code_xmm_lpd  && 
         byte2 != instruction_code_xmm_store)) {
	  fatal ("not a mov [reg+offs], reg instruction");
    }
  }
}


void NativeMovRegMem::print() {
  tty->print_cr("0x%x: mov reg, [reg + %x]", instruction_address(), offset());
}

//-------------------------------------------------------------------

void NativeLoadAddress::verify() {
  // make sure code pattern is actually a mov [reg+offset], reg instruction
  u_char test_byte = *(u_char*)instruction_address();
  if ( ! (test_byte == instruction_code) ) {
    fatal ("not a lea reg, [reg+offs] instruction");
  }
}


void NativeLoadAddress::print() {
  tty->print_cr("0x%x: lea [reg + %x], reg", instruction_address(), offset());
}

//--------------------------------------------------------------------------------

void NativeJump::verify() {
  if (*(u_char*)instruction_address() != instruction_code) fatal("not a jump instruction");  
}


void NativeJump::insert(address code_pos, address entry) {
  *code_pos = 0xE9;
  *((long *)(code_pos+1)) = (int)entry - ((int)code_pos + 1 + sizeof(long));
  ICache::invalidate_range(code_pos, instruction_size);
}

void NativeJump::check_verified_entry_alignment(address entry, address verified_entry) {
  // Patching to not_entrant can happen while activations of the method are
  // in use. The patching in that instance must happen only when certain
  // alignment restrictions are true. These guarantees check those
  // conditions.
  // Must be 4 byte aligned
  guarantee (((uintptr_t)verified_entry & 3) == 0, "illegal address for code patching");
  // First 5 bytes must be within the same cache line - 4827828
  guarantee ((uintptr_t)verified_entry / 32 == ((uintptr_t)verified_entry + 4) / 32,
             "illegal address for code patching(3)");
}


// MT safe inserting of a jump over an unknown instruction sequence (used by nmethod::makeZombie)
// The problem: jmp <dest> is a 5-byte instruction. Atomical write can be only with 4 bytes.
// First patches the first word atomically to be a jump to itself.
// Then patches the last byte  and then atomically patches the first word (4-bytes),
// thus inserting the desired jump
// This code is mt-safe with the following conditions: entry point is 4 byte aligned,
// entry point is in same cache line as unverified entry point, and the instruction being 
// patched is >= 5 byte (size of patch).
// 
// In C2 the 5+ byte sized instruction is enforced by code in MachPrologNode::emit.
// In C1 the restriction is enforced by CodeEmitter::method_entry
//
void NativeJump::patch_verified_entry(address entry, address verified_entry, address dest) {
  unsigned char code_buffer[5];
  // complete jump instruction (to be inserted) is in code_buffer;
  address tmp = code_buffer;
  *tmp = 0xE9;
  tmp++;
  *((jint *)(tmp)) = (intptr_t)dest - ((intptr_t)(verified_entry + 1) + sizeof(int));

  check_verified_entry_alignment(entry, verified_entry);

  //temporary code

  unsigned char patch[4];
  assert(sizeof(patch)==sizeof(jint), "sanity check");
  patch[0] = 0xEB;       // jmp rel8
  patch[1] = 0xFE;       // jmp to self
  patch[2] = 0xEB;
  patch[3] = 0xFE;

  // First patch dummy jmp in place
  *(jint*)verified_entry = *(jint *)patch;

  // Patch 5th byte (from jump instruction)
  address byte_5_adr = verified_entry + 4;
  *byte_5_adr = code_buffer[4];

  // Patch bytes 0-3 (from jump instruction)
  *(jint*)verified_entry = *(jint *)code_buffer;  

  ICache::invalidate_range(verified_entry, NativeJump::instruction_size);
}

void NativePopReg::insert(address code_pos, Register reg) {
  assert(NativePopReg::instruction_size == sizeof(char), "right address unit for update");
  *code_pos = (char)(instruction_code | reg->encoding())&0xFF;  
  ICache::invalidate_range(code_pos, instruction_size);
}


void NativeIllegalInstruction::insert(address code_pos) {
  assert(NativeIllegalInstruction::instruction_size == sizeof(short), "right address unit for update");
  *(short *)code_pos = instruction_code;
  ICache::invalidate_range(code_pos, instruction_size);
}

void NativeGeneralJump::verify() {
  assert(((NativeInstruction *)this)->is_jump() ||
	 ((NativeInstruction *)this)->is_cond_jump(), "not a general jump instruction");
}


void NativeGeneralJump::insert_unconditional(address code_pos, address entry) {
  *code_pos = unconditional_long_jump;
  *((long *)(code_pos+1)) = (int)entry - ((int)code_pos + 1 + sizeof(long));
  ICache::invalidate_range(code_pos, instruction_size);
}


// MT-safe patching of a long jump instruction.
// First patches first word of instruction to two jmp's that jmps to them
// selfs (spinlock). Then patches the last byte, and then atomicly replaces
// the jmp's with the first 4 byte of the new instruction.
void NativeGeneralJump::replace_mt_safe(address instr_addr, address code_buffer) {
   assert (instr_addr != NULL, "illegal address for code patching");
#ifdef ASSERT
   NativeGeneralJump* n_jump =  nativeGeneralJump_at (instr_addr); // checking that it is a jump
#endif // ASSERT

   // Temporary code
   unsigned char patch[4];
   assert(sizeof(patch)==sizeof(jint), "sanity check");
   patch[0] = 0xEB;       // jmp rel8
   patch[1] = 0xFE;       // jmp to self
   patch[2] = 0xEB;
   patch[3] = 0xFE;
   
   // First patch dummy jmp in place
   *(jint*)instr_addr = *(jint *)patch;

   // Patch 4th byte
   address byte_4_adr = instr_addr+4;
   *byte_4_adr = code_buffer[4];

   // Patch bytes 0-3
   *(jint*)instr_addr = *(jint *)code_buffer;  

#ifdef ASSERT
   // verify patching
   for ( int i = 0; i < NativeCall::instruction_size; i++) { // bytewise comparing
     address ptr = (address)((int)code_buffer + i);     
     int a_byte = (*ptr) & 0xFF;
     assert(*((address)((int)instr_addr + i)) == a_byte, "mt safe patching failed");
   }
#endif

   ICache::invalidate_range(instr_addr, NativeGeneralJump::instruction_size);
}



address NativeGeneralJump::jump_destination() const {  
  int op_code = char_at(0)&0xFF;
  bool is_long = (op_code == 0xE9 || op_code == 0x0F);
  int  offset  = (op_code == 0x0F)  ? 2 : 1;
  int  length  = offset + ((is_long) ? 4 : 1);
  
  if (is_long) 
    return addr_at(0) + length + long_at(offset);
  else
    return addr_at(0) + length + ((int)(char_at(offset)));
}
