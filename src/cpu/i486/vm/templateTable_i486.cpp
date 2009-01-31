#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)templateTable_i486.cpp	1.319 07/05/17 15:47:10 JVM"
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

#include "incls/_precompiled.incl"
#include "incls/_templateTable_i486.cpp.incl"

#define __ _masm->

//----------------------------------------------------------------------------------------------------
// Platform-dependent initialization

void TemplateTable::pd_initialize() {
  // No i486 specific initialization
}

//----------------------------------------------------------------------------------------------------
// Address computation

// local variables
static inline Address iaddress(int n)            { 
  return Address(edi, Interpreter::local_offset_in_bytes(n));
}

static inline Address laddress(int n)            { return iaddress(n + 1); }
static inline Address haddress(int n)            { return iaddress(n + 0); }
static inline Address faddress(int n)            { return iaddress(n); }
static inline Address daddress(int n)            { return laddress(n); }
static inline Address aaddress(int n)            { return iaddress(n); }

static inline Address iaddress(Register r)       { 
  return Address(edi, r, Interpreter::stackElementScale(), Interpreter::value_offset_in_bytes());
}
static inline Address laddress(Register r)       { 
  return Address(edi, r, Interpreter::stackElementScale(), Interpreter::local_offset_in_bytes(1));
}
static inline Address haddress(Register r)       { 
  return Address(edi, r, Interpreter::stackElementScale(), Interpreter::local_offset_in_bytes(0));
}

static inline Address faddress(Register r)       { return iaddress(r); };
static inline Address daddress(Register r)       { 
  assert(!TaggedStackInterpreter, "This doesn't work");
  return laddress(r);
};
static inline Address aaddress(Register r)       { return iaddress(r); };

// expression stack
// (Note: Must not use symmetric equivalents at_esp_m1/2 since they store
// data beyond the esp which is potentially unsafe in an MT environment;
// an interrupt may overwrite that data.)
static inline Address at_esp   () {
  return Address(esp);
}

// At top of Java expression stack which may be different than esp().  It
// isn't for category 1 objects.
static inline Address at_tos   () {
  Address tos = Address(esp,  Interpreter::expr_offset_in_bytes(0));
  return tos;
}

static inline Address at_tos_p1() {
  return Address(esp,  Interpreter::expr_offset_in_bytes(1));
}

static inline Address at_tos_p2() {
  return Address(esp,  Interpreter::expr_offset_in_bytes(2));
}

// Condition conversion
static Assembler::Condition j_not(TemplateTable::Condition cc) {
  switch (cc) {
    case TemplateTable::equal        : return Assembler::notEqual;
    case TemplateTable::not_equal    : return Assembler::equal;
    case TemplateTable::less         : return Assembler::greaterEqual;
    case TemplateTable::less_equal   : return Assembler::greater;
    case TemplateTable::greater      : return Assembler::lessEqual;
    case TemplateTable::greater_equal: return Assembler::less;
  }
  ShouldNotReachHere();
  return Assembler::zero;
}


//----------------------------------------------------------------------------------------------------
// Miscelaneous helper routines

Address TemplateTable::at_bcp(int offset) {
  assert(_desc->uses_bcp(), "inconsistent uses_bcp information");
  return Address(esi, offset);
}


void TemplateTable::patch_bytecode(Bytecodes::Code bytecode, Register bc,
                                   Register scratch,
                                   bool load_bc_into_scratch/*=true*/) {
                                   
  if (!RewriteBytecodes) return;
  // the pair bytecodes have already done the load.
  if (load_bc_into_scratch) __ movl(bc, bytecode);
  Label patch_done;
  if (JvmtiExport::can_post_breakpoint()) {
    Label fast_patch;
    // if a breakpoint is present we can't rewrite the stream directly
    __ movzxb(scratch, at_bcp(0));
    __ cmpl(scratch, Bytecodes::_breakpoint);
    __ jcc(Assembler::notEqual, fast_patch);
    __ get_method(scratch);
    // Let breakpoint table handling rewrite to quicker bytecode 
    __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::set_original_bytecode_at), scratch, esi, bc);
#ifndef ASSERT
    __ jmpb(patch_done);
    __ bind(fast_patch);
  }
#else
    __ jmp(patch_done);
    __ bind(fast_patch);
  }
  Label okay;
  __ load_unsigned_byte(scratch, at_bcp(0));
  __ cmpl(scratch, (int)Bytecodes::java_code(bytecode));
  __ jccb(Assembler::equal, okay);
  __ cmpl(scratch, bc);
  __ jcc(Assembler::equal, okay);
  __ stop("patching the wrong bytecode");
  __ bind(okay);
#endif
  // patch bytecode
  __ movb(at_bcp(0), bc);
  __ bind(patch_done);
}

//----------------------------------------------------------------------------------------------------
// Individual instructions

void TemplateTable::nop() {
  transition(vtos, vtos);
  // nothing to do
}

void TemplateTable::shouldnotreachhere() {
  transition(vtos, vtos);
  __ stop("shouldnotreachhere bytecode");
}



void TemplateTable::aconst_null() {
  transition(vtos, atos);
  __ xorl(eax, eax);
}


void TemplateTable::iconst(int value) {
  transition(vtos, itos);
  if (value == 0) {
    __ xorl(eax, eax);
  } else {
    __ movl(eax, value);
  }
}


void TemplateTable::lconst(int value) {
  transition(vtos, ltos);
  if (value == 0) {
    __ xorl(eax, eax);
  } else {
    __ movl(eax, value);
  }
  assert(value >= 0, "check this code");
  __ xorl(edx, edx);
}


void TemplateTable::fconst(int value) {
  transition(vtos, ftos);
         if (value == 0) { __ fldz();
  } else if (value == 1) { __ fld1();
  } else if (value == 2) { __ fld1(); __ fld1(); __ faddp(); // should do a better solution here
  } else                 { ShouldNotReachHere();
  }
}


void TemplateTable::dconst(int value) {
  transition(vtos, dtos);
         if (value == 0) { __ fldz();
  } else if (value == 1) { __ fld1();
  } else                 { ShouldNotReachHere();
  }
}


void TemplateTable::bipush() {
  transition(vtos, itos);
  __ load_signed_byte(eax, at_bcp(1));
}


void TemplateTable::sipush() {
  transition(vtos, itos);
  __ load_unsigned_word(eax, at_bcp(1));
  __ bswap(eax);
  __ sarl(eax, 16);
}

void TemplateTable::ldc(bool wide) {
  transition(vtos, vtos);
  Label call_ldc, notFloat, notClass, Done;

  if (wide) {
    __ get_unsigned_2_byte_index_at_bcp(ebx, 1);
  } else {
    __ load_unsigned_byte(ebx, at_bcp(1));
  }
  __ get_cpool_and_tags(ecx, eax);
  const int base_offset = constantPoolOopDesc::header_size() * wordSize;
  const int tags_offset = typeArrayOopDesc::header_size(T_BYTE) * wordSize;

  // get type
  __ xorl(edx, edx);
  __ movb(edx, Address(eax, ebx, Address::times_1, tags_offset));

  // unresolved string - get the resolved string
  __ cmpl(edx, JVM_CONSTANT_UnresolvedString);
  __ jccb(Assembler::equal, call_ldc);

  // unresolved class - get the resolved class
  __ cmpl(edx, JVM_CONSTANT_UnresolvedClass);
  __ jccb(Assembler::equal, call_ldc);

  // unresolved class in error (resolution failed) - call into runtime
  // so that the same error from first resolution attempt is thrown.
  __ cmpl(edx, JVM_CONSTANT_UnresolvedClassInError);
  __ jccb(Assembler::equal, call_ldc);

  // resolved class - need to call vm to get java mirror of the class
  __ cmpl(edx, JVM_CONSTANT_Class);
  __ jcc(Assembler::notEqual, notClass);

  __ bind(call_ldc);
  __ movl(ecx, wide);
  call_VM(eax, CAST_FROM_FN_PTR(address, InterpreterRuntime::ldc), ecx);
  __ push(atos);
  __ jmp(Done);

  __ bind(notClass);
  __ cmpl(edx, JVM_CONSTANT_Float);
  __ jccb(Assembler::notEqual, notFloat);
  // ftos
  __ fld_s(    Address(ecx, ebx, Address::times_4, base_offset));
  __ push(ftos);
  __ jmp(Done);

  __ bind(notFloat);
#ifdef ASSERT
  { Label L;
    __ cmpl(edx, JVM_CONSTANT_Integer);
    __ jcc(Assembler::equal, L);
    __ cmpl(edx, JVM_CONSTANT_String);
    __ jcc(Assembler::equal, L);
    __ stop("unexpected tag type in ldc");
    __ bind(L);
  }
#endif
  Label isOop;
  // atos and itos
  __ movl(eax, Address(ecx, ebx, Address::times_4, base_offset));
  // String is only oop type we will see here
  __ cmpl(edx, JVM_CONSTANT_String);
  __ jccb(Assembler::equal, isOop);
  __ push(itos);
  __ jmp(Done);
  __ bind(isOop);
  __ push(atos);

  if (VerifyOops) {
    __ verify_oop(eax);
  }
  __ bind(Done);
}

void TemplateTable::ldc2_w() {
  transition(vtos, vtos);
  Label Long, Done;
  __ get_unsigned_2_byte_index_at_bcp(ebx, 1);

  __ get_cpool_and_tags(ecx, eax);
  const int base_offset = constantPoolOopDesc::header_size() * wordSize;
  const int tags_offset = typeArrayOopDesc::header_size(T_BYTE) * wordSize;

  // get type
  __ cmpb(Address(eax, ebx, Address::times_1, tags_offset), JVM_CONSTANT_Double);
  __ jccb(Assembler::notEqual, Long);
  // dtos
  __ fld_d(    Address(ecx, ebx, Address::times_4, base_offset));
  __ push(dtos);
  __ jmpb(Done);

  __ bind(Long);
  // ltos
  __ movl(eax, Address(ecx, ebx, Address::times_4, base_offset + 0 * wordSize));
  __ movl(edx, Address(ecx, ebx, Address::times_4, base_offset + 1 * wordSize));

  __ push(ltos);

  __ bind(Done);
}


void TemplateTable::locals_index(Register reg, int offset) {
  __ load_unsigned_byte(reg, at_bcp(offset));
  __ negl(reg);	
}


void TemplateTable::iload() {
  transition(vtos, itos);
  if (RewriteFrequentPairs) { 
    Label rewrite, done;

    // get next byte
    __ load_unsigned_byte(ebx, at_bcp(Bytecodes::length_for(Bytecodes::_iload)));
    // if _iload, wait to rewrite to iload2.  We only want to rewrite the
    // last two iloads in a pair.  Comparing against fast_iload means that
    // the next bytecode is neither an iload or a caload, and therefore
    // an iload pair.
    __ cmpl(ebx, Bytecodes::_iload);
    __ jcc(Assembler::equal, done);

    __ cmpl(ebx, Bytecodes::_fast_iload);
    __ movl(ecx, Bytecodes::_fast_iload2);
    __ jccb(Assembler::equal, rewrite);

    // if _caload, rewrite to fast_icaload
    __ cmpl(ebx, Bytecodes::_caload);
    __ movl(ecx, Bytecodes::_fast_icaload);
    __ jccb(Assembler::equal, rewrite);

    // rewrite so iload doesn't check again.
    __ movl(ecx, Bytecodes::_fast_iload);

    // rewrite
    // ecx: fast bytecode
    __ bind(rewrite);
    patch_bytecode(Bytecodes::_iload, ecx, ebx, false);
    __ bind(done);
  }

  // Get the local value into tos
  locals_index(ebx);
  __ movl(eax, iaddress(ebx));
  debug_only(__ verify_local_tag(frame::TagValue, ebx));
}


void TemplateTable::fast_iload2() {
  transition(vtos, itos);
  locals_index(ebx);
  __ movl(eax, iaddress(ebx));
  debug_only(__ verify_local_tag(frame::TagValue, ebx));
  __ push(itos);
  locals_index(ebx, 3);
  __ movl(eax, iaddress(ebx));
  debug_only(__ verify_local_tag(frame::TagValue, ebx));
}
  
void TemplateTable::fast_iload() {
  transition(vtos, itos);
  locals_index(ebx);
  __ movl(eax, iaddress(ebx));
  debug_only(__ verify_local_tag(frame::TagValue, ebx));
}


void TemplateTable::lload() {
  transition(vtos, ltos);
  locals_index(ebx);
  __ movl(eax, laddress(ebx));
  __ movl(edx, haddress(ebx));
  debug_only(__ verify_local_tag(frame::TagCategory2, ebx));
}


void TemplateTable::fload() {
  transition(vtos, ftos);
  locals_index(ebx);
  __ fld_s(faddress(ebx));
  debug_only(__ verify_local_tag(frame::TagValue, ebx));
}


void TemplateTable::dload() {
  transition(vtos, dtos);
  locals_index(ebx);
  if (TaggedStackInterpreter) {
    // Get double out of locals array, onto temp stack and load with
    // float instruction into ST0
    __ movl(eax, laddress(ebx));
    __ movl(edx, haddress(ebx));
    __ pushl(edx);  // push hi first
    __ pushl(eax);
    __ fld_d(Address(esp));
    __ addl(esp, 2*wordSize);
    debug_only(__ verify_local_tag(frame::TagCategory2, ebx));
  } else {
    __ fld_d(daddress(ebx));
  }
}


void TemplateTable::aload() {
  transition(vtos, atos);
  locals_index(ebx);
  __ movl(eax, iaddress(ebx));
  debug_only(__ verify_local_tag(frame::TagReference, ebx));
}


void TemplateTable::locals_index_wide(Register reg) {
  __ movl(reg, at_bcp(2));
  __ bswap(reg);
  __ shrl(reg, 16);
  __ negl(reg);	
}


void TemplateTable::wide_iload() {
  transition(vtos, itos);
  locals_index_wide(ebx);
  __ movl(eax, iaddress(ebx));
  debug_only(__ verify_local_tag(frame::TagValue, ebx));
}


void TemplateTable::wide_lload() {
  transition(vtos, ltos);
  locals_index_wide(ebx);
  __ movl(eax, laddress(ebx));
  __ movl(edx, haddress(ebx));
  debug_only(__ verify_local_tag(frame::TagCategory2, ebx));
}


void TemplateTable::wide_fload() {
  transition(vtos, ftos);
  locals_index_wide(ebx);
  __ fld_s(faddress(ebx));
  debug_only(__ verify_local_tag(frame::TagValue, ebx));
}


void TemplateTable::wide_dload() {
  transition(vtos, dtos);
  locals_index_wide(ebx);
  if (TaggedStackInterpreter) {
    // Get double out of locals array, onto temp stack and load with
    // float instruction into ST0
    __ movl(eax, laddress(ebx));
    __ movl(edx, haddress(ebx));
    __ pushl(edx);  // push hi first
    __ pushl(eax);
    __ fld_d(Address(esp));
    __ addl(esp, 2*wordSize);
    debug_only(__ verify_local_tag(frame::TagCategory2, ebx));
  } else {
    __ fld_d(daddress(ebx));
  }
}


void TemplateTable::wide_aload() {
  transition(vtos, atos);
  locals_index_wide(ebx);
  __ movl(eax, iaddress(ebx));
  debug_only(__ verify_local_tag(frame::TagReference, ebx));
}

void TemplateTable::index_check(Register array, Register index) {
  // Pop ptr into array
  __ pop_ptr(array);
  index_check_without_pop(array, index);
}

void TemplateTable::index_check_without_pop(Register array, Register index) {
  // destroys ebx
  // check array
  __ null_check(array, arrayOopDesc::length_offset_in_bytes());
  // check index
  __ cmpl(index, Address(array, arrayOopDesc::length_offset_in_bytes()));
  if (index != ebx) {
    // ??? convention: move aberrant index into ebx for exception message
    assert(ebx != array, "different registers");
    __ movl(ebx, index);
  }
  __ jcc(Assembler::aboveEqual, Interpreter::_throw_ArrayIndexOutOfBoundsException_entry, relocInfo::none);
}


void TemplateTable::iaload() {
  transition(itos, itos);
  // edx: array
  index_check(edx, eax);  // kills ebx
  // eax: index
  __ movl(eax, Address(edx, eax, Address::times_4, arrayOopDesc::base_offset_in_bytes(T_INT)));
}


void TemplateTable::laload() {
  transition(itos, ltos);
  // eax: index
  // edx: array
  index_check(edx, eax);
  __ movl(ebx, eax);
  // ebx: index
  __ movl(eax, Address(edx, ebx, Address::times_8, arrayOopDesc::base_offset_in_bytes(T_LONG) + 0 * wordSize));
  __ movl(edx, Address(edx, ebx, Address::times_8, arrayOopDesc::base_offset_in_bytes(T_LONG) + 1 * wordSize));
}


void TemplateTable::faload() {
  transition(itos, ftos);
  // edx: array
  index_check(edx, eax);  // kills ebx
  // eax: index
  __ fld_s(Address(edx, eax, Address::times_4, arrayOopDesc::base_offset_in_bytes(T_FLOAT)));
}


void TemplateTable::daload() {
  transition(itos, dtos);
  // edx: array
  index_check(edx, eax);  // kills ebx
  // eax: index
  __ fld_d(Address(edx, eax, Address::times_8, arrayOopDesc::base_offset_in_bytes(T_DOUBLE)));
}


void TemplateTable::aaload() {
  transition(itos, atos);
  // edx: array
  index_check(edx, eax);  // kills ebx
  // eax: index
  __ movl(eax, Address(edx, eax, Address::times_4, arrayOopDesc::base_offset_in_bytes(T_OBJECT)));
}


void TemplateTable::baload() {
  transition(itos, itos);
  // edx: array
  index_check(edx, eax);  // kills ebx
  // eax: index
  // can do better code for P5 - fix this at some point
  __ load_signed_byte(ebx, Address(edx, eax, Address::times_1, arrayOopDesc::base_offset_in_bytes(T_BYTE)));
  __ movl(eax, ebx);
}


void TemplateTable::caload() {
  transition(itos, itos);
  // edx: array
  index_check(edx, eax);  // kills ebx
  // eax: index
  // can do better code for P5 - may want to improve this at some point
  __ load_unsigned_word(ebx, Address(edx, eax, Address::times_2, arrayOopDesc::base_offset_in_bytes(T_CHAR)));
  __ movl(eax, ebx);
}

// iload followed by caload frequent pair
void TemplateTable::fast_icaload() {
  transition(vtos, itos);
  // load index out of locals
  locals_index(ebx);
  __ movl(eax, iaddress(ebx));
  debug_only(__ verify_local_tag(frame::TagValue, ebx));

  // edx: array
  index_check(edx, eax);
  // eax: index
  __ load_unsigned_word(ebx, Address(edx, eax, Address::times_2, arrayOopDesc::base_offset_in_bytes(T_CHAR)));
  __ movl(eax, ebx);
}

void TemplateTable::saload() {
  transition(itos, itos);
  // edx: array
  index_check(edx, eax);  // kills ebx
  // eax: index
  // can do better code for P5 - may want to improve this at some point
  __ load_signed_word(ebx, Address(edx, eax, Address::times_2, arrayOopDesc::base_offset_in_bytes(T_SHORT)));
  __ movl(eax, ebx);
}


void TemplateTable::iload(int n) {
  transition(vtos, itos);
  __ movl(eax, iaddress(n));
  debug_only(__ verify_local_tag(frame::TagValue, n));
}


void TemplateTable::lload(int n) {
  transition(vtos, ltos);
  __ movl(eax, laddress(n));
  __ movl(edx, haddress(n));
  debug_only(__ verify_local_tag(frame::TagCategory2, n));
}


void TemplateTable::fload(int n) {
  transition(vtos, ftos);
  __ fld_s(faddress(n));
  debug_only(__ verify_local_tag(frame::TagValue, n));
}


void TemplateTable::dload(int n) {
  transition(vtos, dtos);
  if (TaggedStackInterpreter) {
    // Get double out of locals array, onto temp stack and load with
    // float instruction into ST0
    __ movl(eax, laddress(n));
    __ movl(edx, haddress(n));
    __ pushl(edx);  // push hi first
    __ pushl(eax);
    __ fld_d(Address(esp));
    __ addl(esp, 2*wordSize);  // reset esp
    debug_only(__ verify_local_tag(frame::TagCategory2, n));
  } else {
    __ fld_d(daddress(n));
  }
}


void TemplateTable::aload(int n) {
  transition(vtos, atos);
  __ movl(eax, aaddress(n));
  debug_only(__ verify_local_tag(frame::TagReference, n));
}


void TemplateTable::aload_0() {
  transition(vtos, atos);
  // According to bytecode histograms, the pairs:
  //
  // _aload_0, _fast_igetfield
  // _aload_0, _fast_agetfield
  // _aload_0, _fast_fgetfield
  //
  // occur frequently. If RewriteFrequentPairs is set, the (slow) _aload_0
  // bytecode checks if the next bytecode is either _fast_igetfield, 
  // _fast_agetfield or _fast_fgetfield and then rewrites the
  // current bytecode into a pair bytecode; otherwise it rewrites the current
  // bytecode into _fast_aload_0 that doesn't do the pair check anymore.
  //
  // Note: If the next bytecode is _getfield, the rewrite must be delayed,
  //       otherwise we may miss an opportunity for a pair.
  //
  // Also rewrite frequent pairs
  //   aload_0, aload_1
  //   aload_0, iload_1
  // These bytecodes with a small amount of code are most profitable to rewrite
  if (RewriteFrequentPairs) {
    Label rewrite, done;
    // get next byte
    __ load_unsigned_byte(ebx, at_bcp(Bytecodes::length_for(Bytecodes::_aload_0)));

    // do actual aload_0
    aload(0);

    // if _getfield then wait with rewrite
    __ cmpl(ebx, Bytecodes::_getfield);
    __ jcc(Assembler::equal, done);

    // if _igetfield then reqrite to _fast_iaccess_0
    assert(Bytecodes::java_code(Bytecodes::_fast_iaccess_0) == Bytecodes::_aload_0, "fix bytecode definition");
    __ cmpl(ebx, Bytecodes::_fast_igetfield);
    __ movl(ecx, Bytecodes::_fast_iaccess_0);
    __ jccb(Assembler::equal, rewrite);

    // if _agetfield then reqrite to _fast_aaccess_0
    assert(Bytecodes::java_code(Bytecodes::_fast_aaccess_0) == Bytecodes::_aload_0, "fix bytecode definition");
    __ cmpl(ebx, Bytecodes::_fast_agetfield);
    __ movl(ecx, Bytecodes::_fast_aaccess_0);
    __ jccb(Assembler::equal, rewrite);

    // if _fgetfield then reqrite to _fast_faccess_0
    assert(Bytecodes::java_code(Bytecodes::_fast_faccess_0) == Bytecodes::_aload_0, "fix bytecode definition");
    __ cmpl(ebx, Bytecodes::_fast_fgetfield);
    __ movl(ecx, Bytecodes::_fast_faccess_0);
    __ jccb(Assembler::equal, rewrite);

    // else rewrite to _fast_aload0
    assert(Bytecodes::java_code(Bytecodes::_fast_aload_0) == Bytecodes::_aload_0, "fix bytecode definition");
    __ movl(ecx, Bytecodes::_fast_aload_0);

    // rewrite
    // ecx: fast bytecode
    __ bind(rewrite);
    patch_bytecode(Bytecodes::_aload_0, ecx, ebx, false);

    __ bind(done);
  } else {
    aload(0);
  }
}

void TemplateTable::istore() {
  transition(itos, vtos);
  locals_index(ebx);
  __ movl(iaddress(ebx), eax);
  __ tag_local(frame::TagValue, ebx);
}


void TemplateTable::lstore() {
  transition(ltos, vtos);
  locals_index(ebx);
  __ movl(laddress(ebx), eax);
  __ movl(haddress(ebx), edx);
  __ tag_local(frame::TagCategory2, ebx);
}


void TemplateTable::fstore() {
  transition(ftos, vtos);
  locals_index(ebx);
  __ fstp_s(faddress(ebx));
  __ tag_local(frame::TagValue, ebx);
}


void TemplateTable::dstore() {
  transition(dtos, vtos);
  locals_index(ebx);
  if (TaggedStackInterpreter) {
    // Store double on stack and reload into locals nonadjacently
    __ subl(esp, 2 * wordSize);
    __ fstp_d(Address(esp));
    __ popl(eax);
    __ popl(edx);
    __ movl(laddress(ebx), eax);
    __ movl(haddress(ebx), edx);
    __ tag_local(frame::TagCategory2, ebx);
  } else {
    __ fstp_d(daddress(ebx));
  }
}


void TemplateTable::astore() {
  transition(vtos, vtos);
  __ pop_ptr(eax, edx);   // will need to pop tag too
  locals_index(ebx);
  __ movl(aaddress(ebx), eax);
  __ tag_local(edx, ebx);    // need to store same tag in local may be returnAddr
}


void TemplateTable::wide_istore() {
  transition(vtos, vtos);
  __ pop_i(eax);
  locals_index_wide(ebx);
  __ movl(iaddress(ebx), eax);
  __ tag_local(frame::TagValue, ebx);
}


void TemplateTable::wide_lstore() {
  transition(vtos, vtos);
  __ pop_l(eax, edx);
  locals_index_wide(ebx);
  __ movl(laddress(ebx), eax);
  __ movl(haddress(ebx), edx);
  __ tag_local(frame::TagCategory2, ebx);
}


void TemplateTable::wide_fstore() {
  wide_istore();
}


void TemplateTable::wide_dstore() {
  wide_lstore();
}


void TemplateTable::wide_astore() {
  transition(vtos, vtos);
  __ pop_ptr(eax, edx);
  locals_index_wide(ebx);
  __ movl(aaddress(ebx), eax);
  __ tag_local(edx, ebx);
}


void TemplateTable::iastore() {
  transition(itos, vtos);
  __ pop_i(ebx);
  // eax: value
  // edx: array
  index_check(edx, ebx);  // prefer index in ebx
  // ebx: index
  __ movl(Address(edx, ebx, Address::times_4, arrayOopDesc::base_offset_in_bytes(T_INT)), eax);
}


void TemplateTable::lastore() {
  transition(ltos, vtos);
  __ pop_i(ebx);
  // eax: low(value)
  // ecx: array
  // edx: high(value)
  index_check(ecx, ebx);  // prefer index in ebx
  // ebx: index
  __ movl(Address(ecx, ebx, Address::times_8, arrayOopDesc::base_offset_in_bytes(T_LONG) + 0 * wordSize), eax);
  __ movl(Address(ecx, ebx, Address::times_8, arrayOopDesc::base_offset_in_bytes(T_LONG) + 1 * wordSize), edx);
}


void TemplateTable::fastore() {
  transition(ftos, vtos);
  __ pop_i(ebx);
  // edx: array
  // st0: value
  index_check(edx, ebx);  // prefer index in ebx
  // ebx: index
  __ fstp_s(Address(edx, ebx, Address::times_4, arrayOopDesc::base_offset_in_bytes(T_FLOAT)));
}


void TemplateTable::dastore() {
  transition(dtos, vtos);
  __ pop_i(ebx);
  // edx: array
  // st0: value
  index_check(edx, ebx);  // prefer index in ebx
  // ebx: index
  __ fstp_d(Address(edx, ebx, Address::times_8, arrayOopDesc::base_offset_in_bytes(T_DOUBLE)));
}


void TemplateTable::aastore() {
  Label is_null, ok_is_subtype, done;
  transition(vtos, vtos);
  // stack: ..., array, index, value
  __ movl(eax, at_tos());     // Value
  __ movl(ecx, at_tos_p1());  // Index
  __ movl(edx, at_tos_p2());  // Array
  index_check_without_pop(edx, ecx);      // kills ebx
  // do array store check - check for NULL value first
  __ testl(eax, eax);
  __ jcc(Assembler::zero, is_null);

  // Move subklass into EBX
  __ movl(ebx, Address(eax, oopDesc::klass_offset_in_bytes()));
  // Move superklass into EAX
  __ movl(eax, Address(edx, oopDesc::klass_offset_in_bytes()));
  __ movl(eax, Address(eax, sizeof(oopDesc) + objArrayKlass::element_klass_offset_in_bytes()));
  // Compress array+index*4+12 into a single register.  Frees ECX.
  __ leal(edx, Address(edx, ecx, Address::times_4, arrayOopDesc::base_offset_in_bytes(T_OBJECT)));

  // Generate subtype check.  Blows ECX.  Resets EDI to locals.
  // Superklass in EAX.  Subklass in EBX.
  __ gen_subtype_check( ebx, ok_is_subtype );

  // Come here on failure
  // object is at TOS
  __ jmp(Interpreter::_throw_ArrayStoreException_entry, relocInfo::none);

  // Come here on success
  __ bind(ok_is_subtype);
  __ movl(eax, at_esp());     // Value
  __ movl(Address(edx), eax);
  __ store_check(edx);
  __ jmpb(done);

  // Have a NULL in EAX, EDX=array, ECX=index.  Store NULL at ary[idx]
  __ bind(is_null);
  __ profile_null_seen(ebx);
  __ movl(Address(edx, ecx, Address::times_4, arrayOopDesc::base_offset_in_bytes(T_OBJECT)), eax);

  // Pop stack arguments
  __ bind(done);
  __ addl(esp, 3 * Interpreter::stackElementSize());
}


void TemplateTable::bastore() {
  transition(itos, vtos);
  __ pop_i(ebx);
  // eax: value
  // edx: array
  index_check(edx, ebx);  // prefer index in ebx
  // ebx: index
  __ movb(Address(edx, ebx, Address::times_1, arrayOopDesc::base_offset_in_bytes(T_BYTE)), eax);
}


void TemplateTable::castore() {
  transition(itos, vtos);
  __ pop_i(ebx);
  // eax: value
  // edx: array
  index_check(edx, ebx);  // prefer index in ebx
  // ebx: index
  __ movw(Address(edx, ebx, Address::times_2, arrayOopDesc::base_offset_in_bytes(T_CHAR)), eax);
}


void TemplateTable::sastore() {
  castore();
}


void TemplateTable::istore(int n) {
  transition(itos, vtos);
  __ movl(iaddress(n), eax);
  __ tag_local(frame::TagValue, n);
}


void TemplateTable::lstore(int n) {
  transition(ltos, vtos);
  __ movl(laddress(n), eax);
  __ movl(haddress(n), edx);
  __ tag_local(frame::TagCategory2, n);
}


void TemplateTable::fstore(int n) {
  transition(ftos, vtos);
  __ fstp_s(faddress(n));
  __ tag_local(frame::TagValue, n);
}


void TemplateTable::dstore(int n) {
  transition(dtos, vtos);
  if (TaggedStackInterpreter) {
    __ subl(esp, 2 * wordSize);
    __ fstp_d(Address(esp));
    __ popl(eax);
    __ popl(edx);
    __ movl(laddress(n), eax);
    __ movl(haddress(n), edx);
    __ tag_local(frame::TagCategory2, n);
  } else {
    __ fstp_d(daddress(n));
  }
}


void TemplateTable::astore(int n) {
  transition(vtos, vtos);
  __ pop_ptr(eax, edx);
  __ movl(aaddress(n), eax);
  __ tag_local(edx, n);
}


void TemplateTable::pop() {
  transition(vtos, vtos);
  __ addl(esp, Interpreter::stackElementSize());
}


void TemplateTable::pop2() {
  transition(vtos, vtos);
  __ addl(esp, 2*Interpreter::stackElementSize());
}


void TemplateTable::dup() {
  transition(vtos, vtos);
  // stack: ..., a
  __ load_ptr_and_tag(0, eax, edx);
  __ push_ptr(eax, edx);
  // stack: ..., a, a
}


void TemplateTable::dup_x1() {
  transition(vtos, vtos);
  // stack: ..., a, b
  __ load_ptr_and_tag(0, eax, edx);  // load b
  __ load_ptr_and_tag(1, ecx, ebx);  // load a
  __ store_ptr_and_tag(1, eax, edx); // store b
  __ store_ptr_and_tag(0, ecx, ebx); // store a
  __ push_ptr(eax, edx);             // push b
  // stack: ..., b, a, b
}


void TemplateTable::dup_x2() {
  transition(vtos, vtos);
  // stack: ..., a, b, c
  __ load_ptr_and_tag(0, eax, edx);  // load c
  __ load_ptr_and_tag(2, ecx, ebx);  // load a
  __ store_ptr_and_tag(2, eax, edx); // store c in a
  __ push_ptr(eax, edx);             // push c
  // stack: ..., c, b, c, c
  __ load_ptr_and_tag(2, eax, edx);  // load b
  __ store_ptr_and_tag(2, ecx, ebx); // store a in b
  // stack: ..., c, a, c, c
  __ store_ptr_and_tag(1, eax, edx); // store b in c
  // stack: ..., c, a, b, c
}


void TemplateTable::dup2() {
  transition(vtos, vtos);
  // stack: ..., a, b
  __ load_ptr_and_tag(1, eax, edx);  // load a
  __ push_ptr(eax, edx);             // push a
  __ load_ptr_and_tag(1, eax, edx);  // load b
  __ push_ptr(eax, edx);             // push b
  // stack: ..., a, b, a, b
}


void TemplateTable::dup2_x1() {
  transition(vtos, vtos);
  // stack: ..., a, b, c
  __ load_ptr_and_tag(0, ecx, ebx);  // load c
  __ load_ptr_and_tag(1, eax, edx);  // load b
  __ push_ptr(eax, edx);             // push b
  __ push_ptr(ecx, ebx);             // push c
  // stack: ..., a, b, c, b, c
  __ store_ptr_and_tag(3, ecx, ebx); // store c in b
  // stack: ..., a, c, c, b, c
  __ load_ptr_and_tag(4, ecx, ebx);  // load a
  __ store_ptr_and_tag(2, ecx, ebx); // store a in 2nd c
  // stack: ..., a, c, a, b, c
  __ store_ptr_and_tag(4, eax, edx); // store b in a
  // stack: ..., b, c, a, b, c
  // stack: ..., b, c, a, b, c
}


void TemplateTable::dup2_x2() {
  transition(vtos, vtos);
  // stack: ..., a, b, c, d
  __ load_ptr_and_tag(0, ecx, ebx);  // load d
  __ load_ptr_and_tag(1, eax, edx);  // load c
  __ push_ptr(eax, edx);             // push c
  __ push_ptr(ecx, ebx);             // push d
  // stack: ..., a, b, c, d, c, d
  __ load_ptr_and_tag(4, eax, edx);  // load b
  __ store_ptr_and_tag(2, eax, edx); // store b in d
  __ store_ptr_and_tag(4, ecx, ebx); // store d in b
  // stack: ..., a, d, c, b, c, d
  __ load_ptr_and_tag(5, ecx, ebx);  // load a
  __ load_ptr_and_tag(3, eax, edx);  // load c
  __ store_ptr_and_tag(3, ecx, ebx); // store a in c
  __ store_ptr_and_tag(5, eax, edx); // store c in a
  // stack: ..., c, d, a, b, c, d
  // stack: ..., c, d, a, b, c, d
}


void TemplateTable::swap() {
  transition(vtos, vtos);
  // stack: ..., a, b
  __ load_ptr_and_tag(1, ecx, ebx);  // load a
  __ load_ptr_and_tag(0, eax, edx);  // load b
  __ store_ptr_and_tag(0, ecx, ebx); // store a in b
  __ store_ptr_and_tag(1, eax, edx); // store b in a
  // stack: ..., b, a
}


void TemplateTable::iop2(Operation op) {
  transition(itos, itos);
  switch (op) {
    case add  :                    __ pop_i(edx); __ addl (eax, edx); break;
    case sub  : __ movl(edx, eax); __ pop_i(eax); __ subl (eax, edx); break;
    case mul  :                    __ pop_i(edx); __ imull(eax, edx); break;
    case _and :                    __ pop_i(edx); __ andl (eax, edx); break;
    case _or  :                    __ pop_i(edx); __ orl  (eax, edx); break;
    case _xor :                    __ pop_i(edx); __ xorl (eax, edx); break;
    case shl  : __ movl(ecx, eax); __ pop_i(eax); __ shll (eax);      break; // implicit masking of lower 5 bits by Intel shift instr.
    case shr  : __ movl(ecx, eax); __ pop_i(eax); __ sarl (eax);      break; // implicit masking of lower 5 bits by Intel shift instr.
    case ushr : __ movl(ecx, eax); __ pop_i(eax); __ shrl (eax);      break; // implicit masking of lower 5 bits by Intel shift instr.
    default   : ShouldNotReachHere();
  }
}


void TemplateTable::lop2(Operation op) {
  transition(ltos, ltos);
  __ pop_l(ebx, ecx);
  switch (op) {
    case add : __ addl(eax, ebx); __ adcl(edx, ecx); break;
    case sub : __ subl(ebx, eax); __ sbbl(ecx, edx);
               __ movl(eax, ebx); __ movl(edx, ecx); break;
    case _and: __ andl(eax, ebx); __ andl(edx, ecx); break;
    case _or : __ orl (eax, ebx); __ orl (edx, ecx); break;
    case _xor: __ xorl(eax, ebx); __ xorl(edx, ecx); break;
    default : ShouldNotReachHere();
  }
}


void TemplateTable::idiv() {
  transition(itos, itos);
  __ movl(ecx, eax);
  __ pop_i(eax);
  // Note: could xor eax and ecx and compare with (-1 ^ min_int). If
  //       they are not equal, one could do a normal division (no correction
  //       needed), which may speed up this implementation for the common case.
  //       (see also JVM spec., p.243 & p.271)
  __ corrected_idivl(ecx);
}


void TemplateTable::irem() {
  transition(itos, itos);
  __ movl(ecx, eax);
  __ pop_i(eax);
  // Note: could xor eax and ecx and compare with (-1 ^ min_int). If
  //       they are not equal, one could do a normal division (no correction
  //       needed), which may speed up this implementation for the common case.
  //       (see also JVM spec., p.243 & p.271)
  __ corrected_idivl(ecx);
  __ movl(eax, edx);
}


void TemplateTable::lmul() {
  transition(ltos, ltos);
  __ pop_l(ebx, ecx);
  __ pushl(ecx); __ pushl(ebx);
  __ pushl(edx); __ pushl(eax);
  __ lmul(2 * wordSize, 0);
  __ addl(esp, 4 * wordSize);  // take off temporaries
}


void TemplateTable::ldiv() {
  transition(ltos, ltos);
  __ pop_l(ebx, ecx);
  __ pushl(ecx); __ pushl(ebx);
  __ pushl(edx); __ pushl(eax);
  // check if y = 0
  __ orl(eax, edx);
  __ jcc(Assembler::zero, Interpreter::_throw_ArithmeticException_entry, relocInfo::none);
  __ call_VM_leaf(CAST_FROM_FN_PTR(address, SharedRuntime::ldiv));
  __ addl(esp, 4 * wordSize);  // take off temporaries
}


void TemplateTable::lrem() {
  transition(ltos, ltos);
  __ pop_l(ebx, ecx);
  __ pushl(ecx); __ pushl(ebx);
  __ pushl(edx); __ pushl(eax);
  // check if y = 0
  __ orl(eax, edx);
  __ jcc(Assembler::zero, Interpreter::_throw_ArithmeticException_entry, relocInfo::none);
  __ call_VM_leaf(CAST_FROM_FN_PTR(address, SharedRuntime::lrem));
  __ addl(esp, 4 * wordSize);
}


void TemplateTable::lshl() {
  transition(itos, ltos);
  __ movl(ecx, eax);                             // get shift count
  __ pop_l(eax, edx);                            // get shift value
  __ lshl(edx, eax);
}


void TemplateTable::lshr() {
  transition(itos, ltos);
  __ movl(ecx, eax);                             // get shift count
  __ pop_l(eax, edx);                            // get shift value
  __ lshr(edx, eax, true);
}


void TemplateTable::lushr() {
  transition(itos, ltos);
  __ movl(ecx, eax);                             // get shift count
  __ pop_l(eax, edx);                            // get shift value
  __ lshr(edx, eax);
}


void TemplateTable::fop2(Operation op) {
  transition(ftos, ftos);
  __ pop_ftos_to_esp();  // pop ftos into esp
  switch (op) {
    case add: __ fadd_s (at_esp());                break;
    case sub: __ fsubr_s(at_esp());                break;
    case mul: __ fmul_s (at_esp());                break;
    case div: __ fdivr_s(at_esp());                break;
    case rem: __ fld_s  (at_esp()); __ fremr(eax); break;
    default : ShouldNotReachHere();
  }
  __ f2ieee();
  __ popl(eax);  // pop float thing off
}


void TemplateTable::dop2(Operation op) {
  transition(dtos, dtos);
  __ pop_dtos_to_esp();  // pop dtos into esp
  
  switch (op) {
    case add: __ fadd_d (at_esp());                break;
    case sub: __ fsubr_d(at_esp());                break;
    case mul: {
      Label L_strict;
      Label L_join;
      const Address access_flags      (ecx, methodOopDesc::access_flags_offset());
      __ get_method(ecx);
      __ movl(ecx, access_flags);
      __ testl(ecx, JVM_ACC_STRICT);
      __ jccb(Assembler::notZero, L_strict);
      __ fmul_d (at_esp());
      __ jmpb(L_join);
      __ bind(L_strict);
      __ fld_x(Address((int)StubRoutines::addr_fpu_subnormal_bias1(), relocInfo::none));
      __ fmulp();
      __ fmul_d (at_esp());
      __ fld_x(Address((int)StubRoutines::addr_fpu_subnormal_bias2(), relocInfo::none));
      __ fmulp();
      __ bind(L_join);
      break;
    }
    case div: {
      Label L_strict;
      Label L_join;
      const Address access_flags      (ecx, methodOopDesc::access_flags_offset());
      __ get_method(ecx);
      __ movl(ecx, access_flags);
      __ testl(ecx, JVM_ACC_STRICT);
      __ jccb(Assembler::notZero, L_strict);
      __ fdivr_d(at_esp());
      __ jmp(L_join);
      __ bind(L_strict);
      __ fld_x(Address((int)StubRoutines::addr_fpu_subnormal_bias1(), relocInfo::none));
      __ fmul_d (at_esp());
      __ fdivrp();
      __ fld_x(Address((int)StubRoutines::addr_fpu_subnormal_bias2(), relocInfo::none));
      __ fmulp();
      __ bind(L_join);
      break;
    }
    case rem: __ fld_d  (at_esp()); __ fremr(eax); break;
    default : ShouldNotReachHere();
  }
  __ d2ieee();
  // Pop double precision number from esp.
  __ popl(eax);
  __ popl(edx);
}


void TemplateTable::ineg() {
  transition(itos, itos);
  __ negl(eax);
}


void TemplateTable::lneg() {
  transition(ltos, ltos);
  __ lneg(edx, eax);
}


void TemplateTable::fneg() {
  transition(ftos, ftos);
  __ fchs();
}


void TemplateTable::dneg() {
  transition(dtos, dtos);
  __ fchs();
}


void TemplateTable::iinc() {
  transition(vtos, vtos);
  __ load_signed_byte(edx, at_bcp(2));           // get constant
  locals_index(ebx);
  __ addl(iaddress(ebx), edx);
}


void TemplateTable::wide_iinc() {
  transition(vtos, vtos);
  __ movl(edx, at_bcp(4));                       // get constant
  locals_index_wide(ebx);
  __ bswap(edx);                                 // swap bytes & sign-extend constant
  __ sarl(edx, 16);
  __ addl(iaddress(ebx), edx);
  // Note: should probably use only one movl to get both
  //       the index and the constant -> fix this
}


void TemplateTable::convert() {
  // Checking
#ifdef ASSERT
  { TosState tos_in  = ilgl;
    TosState tos_out = ilgl;
    switch (bytecode()) {
      case Bytecodes::_i2l: // fall through
      case Bytecodes::_i2f: // fall through
      case Bytecodes::_i2d: // fall through
      case Bytecodes::_i2b: // fall through
      case Bytecodes::_i2c: // fall through
      case Bytecodes::_i2s: tos_in = itos; break;
      case Bytecodes::_l2i: // fall through
      case Bytecodes::_l2f: // fall through
      case Bytecodes::_l2d: tos_in = ltos; break;
      case Bytecodes::_f2i: // fall through
      case Bytecodes::_f2l: // fall through
      case Bytecodes::_f2d: tos_in = ftos; break;
      case Bytecodes::_d2i: // fall through
      case Bytecodes::_d2l: // fall through
      case Bytecodes::_d2f: tos_in = dtos; break;
      default             : ShouldNotReachHere();
    }
    switch (bytecode()) {
      case Bytecodes::_l2i: // fall through
      case Bytecodes::_f2i: // fall through
      case Bytecodes::_d2i: // fall through
      case Bytecodes::_i2b: // fall through
      case Bytecodes::_i2c: // fall through
      case Bytecodes::_i2s: tos_out = itos; break;
      case Bytecodes::_i2l: // fall through
      case Bytecodes::_f2l: // fall through
      case Bytecodes::_d2l: tos_out = ltos; break;
      case Bytecodes::_i2f: // fall through
      case Bytecodes::_l2f: // fall through
      case Bytecodes::_d2f: tos_out = ftos; break;
      case Bytecodes::_i2d: // fall through
      case Bytecodes::_l2d: // fall through
      case Bytecodes::_f2d: tos_out = dtos; break;
      default             : ShouldNotReachHere();
    }
    transition(tos_in, tos_out);
  }
#endif // ASSERT

  // Conversion
  // (Note: use pushl(ecx)/popl(ecx) for 1/2-word stack-ptr manipulation)
  switch (bytecode()) {
    case Bytecodes::_i2l:
      __ extend_sign(edx, eax);
      break;
    case Bytecodes::_i2f:
      __ pushl(eax);         // store int on tos
      __ fild_s(at_esp());   // load int to ST0
      __ f2ieee();           // truncate to float size
      __ popl(ecx);          // adjust esp
      break;
    case Bytecodes::_i2d:
      __ pushl(eax);         // add one slot for d2ieee()
      __ pushl(eax);         // store int on tos
      __ fild_s(at_esp());   // load int to ST0
      __ d2ieee();           // truncate to double size
      __ popl(ecx);          // adjust esp
      __ popl(ecx);
      break;
    case Bytecodes::_i2b:
      __ shll(eax, 24);      // truncate upper 24 bits
      __ sarl(eax, 24);      // and sign-extend byte
      break;
    case Bytecodes::_i2c:
      __ andl(eax, 0xFFFF);  // truncate upper 16 bits
      break;
    case Bytecodes::_i2s:
      __ shll(eax, 16);      // truncate upper 16 bits
      __ sarl(eax, 16);      // and sign-extend short
      break;
    case Bytecodes::_l2i:
      /* nothing to do */
      break;
    case Bytecodes::_l2f:
      __ pushl(edx);         // store long on tos
      __ pushl(eax);
      __ fild_d(at_esp());   // load long to ST0
      __ f2ieee();           // truncate to float size
      __ popl(ecx);          // adjust esp
      __ popl(ecx);
      break;
    case Bytecodes::_l2d:
      __ pushl(edx);         // store long on tos
      __ pushl(eax);
      __ fild_d(at_esp());   // load long to ST0
      __ d2ieee();           // truncate to double size
      __ popl(ecx);          // adjust esp
      __ popl(ecx);
      break;
    case Bytecodes::_f2i:
      __ pushl(ecx);         // reserve space for argument
      __ fstp_s(at_esp());   // pass float argument on stack
      __ call_VM_leaf(CAST_FROM_FN_PTR(address, SharedRuntime::f2i), 1);
      break;
    case Bytecodes::_f2l:
      __ pushl(ecx);         // reserve space for argument
      __ fstp_s(at_esp());   // pass float argument on stack
      __ call_VM_leaf(CAST_FROM_FN_PTR(address, SharedRuntime::f2l), 1);
      break;
    case Bytecodes::_f2d:
      /* nothing to do */
      break;
    case Bytecodes::_d2i:
      __ pushl(ecx);         // reserve space for argument
      __ pushl(ecx);
      __ fstp_d(at_esp());   // pass double argument on stack
      __ call_VM_leaf(CAST_FROM_FN_PTR(address, SharedRuntime::d2i), 2);
      break;
    case Bytecodes::_d2l:
      __ pushl(ecx);         // reserve space for argument
      __ pushl(ecx);
      __ fstp_d(at_esp());   // pass double argument on stack
      __ call_VM_leaf(CAST_FROM_FN_PTR(address, SharedRuntime::d2l), 2);
      break;
    case Bytecodes::_d2f:
      __ pushl(ecx);         // reserve space for f2ieee()
      __ f2ieee();           // truncate to float size
      __ popl(ecx);          // adjust esp
      break;
    default             :
      ShouldNotReachHere();
  }
}


void TemplateTable::lcmp() {
  transition(ltos, itos);
  // y = edx:eax
  __ pop_l(ebx, ecx);             // get x = ecx:ebx
  __ lcmp2int(ecx, ebx, edx, eax);// ecx := cmp(x, y)
  __ movl(eax, ecx);
}


void TemplateTable::float_cmp(bool is_float, int unordered_result) {
  if (is_float) {
    __ pop_ftos_to_esp();
    __ fld_s(at_esp());
  } else {
    __ pop_dtos_to_esp();
    __ fld_d(at_esp());
    __ popl(edx);
  }
  __ popl(ecx);
  __ fcmp2int(eax, unordered_result < 0);
}


void TemplateTable::branch(bool is_jsr, bool is_wide) {
  __ get_method(ecx);           // ECX holds method
  __ profile_taken_branch(eax,ebx); // EAX holds updated MDP, EBX holds bumped taken count

  const ByteSize be_offset = methodOopDesc::backedge_counter_offset() + InvocationCounter::counter_offset();
  const ByteSize inv_offset = methodOopDesc::invocation_counter_offset() + InvocationCounter::counter_offset();
  const int method_offset = frame::interpreter_frame_method_offset * wordSize;

  // Load up EDX with the branch displacement
  __ movl(edx, at_bcp(1));
  __ bswap(edx);
  if (!is_wide) __ sarl(edx, 16);

  // Handle all the JSR stuff here, then exit.
  // It's much shorter and cleaner than intermingling with the
  // non-JSR normal-branch stuff occuring below.
  if (is_jsr) {
    // Pre-load the next target bytecode into EBX
    __ load_unsigned_byte(ebx, Address(esi, edx, Address::times_1, 0));

    // compute return address as bci in eax
    __ leal(eax, at_bcp((is_wide ? 5 : 3) - in_bytes(constMethodOopDesc::codes_offset())));
    __ subl(eax, Address(ecx, methodOopDesc::const_offset()));
    // Adjust the bcp in ESI by the displacement in EDX
    __ addl(esi, edx);
    // Push return address
    __ push_i(eax);
    // jsr returns vtos
    __ dispatch_only_noverify(vtos);
    return;
  }

  // Normal (non-jsr) branch handling

  // Adjust the bcp in ESI by the displacement in EDX
  __ addl(esi, edx);

  assert(UseLoopCounter || !UseOnStackReplacement, "on-stack-replacement requires loop counters");
  Label backedge_counter_overflow;
  Label profile_method;
  Label dispatch;
  if (UseLoopCounter) {
    // increment backedge counter for backward branches
    // eax: MDO
    // ebx: MDO bumped taken-count
    // ecx: method
    // edx: target offset
    // esi: target bcp
    // edi: locals pointer
    __ testl(edx, edx);             // check if forward or backward branch
    __ jcc(Assembler::positive, dispatch); // count only if backward branch

    // increment counter 
    __ movl(eax, Address(ecx, be_offset));        // load backedge counter
    __ increment(eax, InvocationCounter::count_increment); // increment counter
    __ movl(Address(ecx, be_offset), eax);        // store counter

    __ movl(eax, Address(ecx, inv_offset));    // load invocation counter
    __ andl(eax, InvocationCounter::count_mask_value);     // and the status bits
    __ addl(eax, Address(ecx, be_offset));        // add both counters

    if (ProfileInterpreter) {
      // Test to see if we should create a method data oop
      __ cmpl(eax, Address(int(&InvocationCounter::InterpreterProfileLimit),
                           relocInfo::none));
      __ jcc(Assembler::less, dispatch);

      // if no method data exists, go to profile method
      __ test_method_data_pointer(eax, profile_method);

      if (UseOnStackReplacement) {
        // check for overflow against ebx which is the MDO taken count
        __ cmpl(ebx, Address(int(&InvocationCounter::InterpreterBackwardBranchLimit),
                             relocInfo::none));
        __ jcc(Assembler::below, dispatch);

        // When ProfileInterpreter is on, the backedge_count comes from the 
        // methodDataOop, which value does not get reset on the call to 
        // frequency_counter_overflow().  To avoid excessive calls to the overflow
        // routine while the method is being compiled, add a second test to make 
        // sure the overflow function is called only once every overflow_frequency.
        const int overflow_frequency = 1024;
	__ andl(ebx, overflow_frequency-1);
        __ jcc(Assembler::zero, backedge_counter_overflow);

      }
    } else {
      if (UseOnStackReplacement) {
        // check for overflow against eax, which is the sum of the counters
        __ cmpl(eax, Address(int(&InvocationCounter::InterpreterBackwardBranchLimit),
                             relocInfo::none));
        __ jcc(Assembler::aboveEqual, backedge_counter_overflow);

      }
    }
    __ bind(dispatch);
  }

  // Pre-load the next target bytecode into EBX
  __ load_unsigned_byte(ebx, Address(esi));

  // continue with the bytecode @ target
  // eax: return bci for jsr's, unused otherwise
  // ebx: target bytecode
  // esi: target bcp
  __ dispatch_only(vtos);

  if (UseLoopCounter) {
    if (ProfileInterpreter) {
      // Out-of-line code to allocate method data oop.
      __ bind(profile_method);
      __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::profile_method), esi);
      __ load_unsigned_byte(ebx, Address(esi));  // restore target bytecode
      __ movl(ecx, Address(ebp, method_offset));
      __ movl(ecx, Address(ecx, in_bytes(methodOopDesc::method_data_offset())));
      __ movl(Address(ebp, frame::interpreter_frame_mdx_offset * wordSize), ecx);
      __ test_method_data_pointer(ecx, dispatch);
      // offset non-null mdp by MDO::data_offset() + IR::profile_method()
      __ addl(ecx, in_bytes(methodDataOopDesc::data_offset()));
      __ addl(ecx, eax);
      __ movl(Address(ebp, frame::interpreter_frame_mdx_offset * wordSize), ecx);
      __ jmp(dispatch);
    }

    if (UseOnStackReplacement) {

      // invocation counter overflow
      __ bind(backedge_counter_overflow);
      __ negl(edx);
      __ addl(edx, esi);        // branch bcp
      call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::frequency_counter_overflow), edx);
      __ load_unsigned_byte(ebx, Address(esi));  // restore target bytecode

      // eax: osr nmethod (osr ok) or NULL (osr not possible)
      // ebx: target bytecode
      // edx: scratch
      // edi: locals pointer
      // esi: bcp
      __ testl(eax, eax);                        // test result
      __ jcc(Assembler::zero, dispatch);         // no osr if null
      // nmethod may have been invalidated (VM may block upon call_VM return)
      __ movl(ecx, Address(eax, nmethod::entry_bci_offset()));
      __ cmpl(ecx, InvalidOSREntryBci);
      __ jcc(Assembler::equal, dispatch);
      
      // We have the address of an on stack replacement routine in eax        
      // We need to prepare to execute the OSR method. First we must
      // migrate the locals and monitors off of the stack.

      __ movl(esi, eax);                             // save the nmethod

      const Register thread = ecx;
      __ get_thread(thread);
      call_VM(noreg, CAST_FROM_FN_PTR(address, SharedRuntime::OSR_migration_begin));
      // eax is OSR buffer, move it to expected parameter location
      __ movl(ecx, eax);

      // pop the interpreter frame
      __ movl(edx, Address(ebp, frame::interpreter_frame_sender_sp_offset * wordSize)); // get sender sp
      __ leave();                                // remove frame anchor
      __ popl(edi);                              // get return address
      __ movl(esp, edx);                         // set sp to sender sp


      Label skip;
      Label chkint;

      // The interpreter frame we have removed may be returning to
      // either the callstub or the interpreter. Since we will
      // now be returning from a compiled (OSR) nmethod we must
      // adjust the return to the return were it can handler compiled
      // results and clean the fpu stack. This is very similar to
      // what a i2c adapter must do.

      // Are we returning to the call stub?

      __ cmpl(edi, (int)StubRoutines::_call_stub_return_address);
      __ jcc(Assembler::notEqual, chkint);

      // yes adjust to the specialized call stub  return.
      assert(StubRoutines::i486::get_call_stub_compiled_return() != NULL, "must be set");
      __ movl(edi, (intptr_t) StubRoutines::i486::get_call_stub_compiled_return());
      __ jmp(skip);

      __ bind(chkint);

      // Are we returning to the interpreter? Look for sentinel

      __ cmpl(Address(edi, -8), Interpreter::return_sentinel);
      __ jcc(Assembler::notEqual, skip);

      // Adjust to compiled return back to interpreter

      __ movl(edi, Address(edi, -4));
      __ bind(skip);

      // Align stack pointer for compiled code (note that caller is
      // responsible for undoing this fixup by remembering the old SP
      // in an ebp-relative location)
      __ andl(esp, -(StackAlignmentInBytes));

      // push the (possibly adjusted) return address
      __ pushl(edi);

      // and begin the OSR nmethod
      __ jmp(Address(esi, nmethod::osr_entry_point_offset()));
    }
  }
}


void TemplateTable::if_0cmp(Condition cc) {
  transition(itos, vtos);
  // assume branch is more often taken than not (loops use backward branches)
  Label not_taken;
  __ testl(eax, eax);
  __ jcc(j_not(cc), not_taken);
  branch(false, false);
  __ bind(not_taken);
  __ profile_not_taken_branch(eax);
}


void TemplateTable::if_icmp(Condition cc) {
  transition(itos, vtos);
  // assume branch is more often taken than not (loops use backward branches)
  Label not_taken;
  __ pop_i(edx);
  __ cmpl(edx, eax);
  __ jcc(j_not(cc), not_taken);
  branch(false, false);
  __ bind(not_taken);
  __ profile_not_taken_branch(eax);
}


void TemplateTable::if_nullcmp(Condition cc) {
  transition(atos, vtos);
  // assume branch is more often taken than not (loops use backward branches)
  Label not_taken;
  __ testl(eax, eax);
  __ jcc(j_not(cc), not_taken);
  branch(false, false);
  __ bind(not_taken);
  __ profile_not_taken_branch(eax);
}


void TemplateTable::if_acmp(Condition cc) {
  transition(atos, vtos);
  // assume branch is more often taken than not (loops use backward branches)
  Label not_taken;
  __ pop_ptr(edx);
  __ cmpl(edx, eax);
  __ jcc(j_not(cc), not_taken);
  branch(false, false);
  __ bind(not_taken);
  __ profile_not_taken_branch(eax);
}


void TemplateTable::ret() {
  transition(vtos, vtos);
  locals_index(ebx);
  __ movl(ebx, iaddress(ebx));                   // get return bci, compute return bcp
  __ profile_ret(ebx, ecx);
  __ get_method(eax);
  __ movl(esi, Address(eax, methodOopDesc::const_offset()));
  __ leal(esi, Address(esi, ebx, Address::times_1,
                       constMethodOopDesc::codes_offset()));
  __ dispatch_next(vtos);
}


void TemplateTable::wide_ret() {
  transition(vtos, vtos);
  locals_index_wide(ebx);
  __ movl(ebx, iaddress(ebx));                   // get return bci, compute return bcp
  __ profile_ret(ebx, ecx);
  __ get_method(eax);
  __ movl(esi, Address(eax, methodOopDesc::const_offset()));
  __ leal(esi, Address(esi, ebx, Address::times_1, constMethodOopDesc::codes_offset()));
  __ dispatch_next(vtos);
}


void TemplateTable::tableswitch() {
  Label default_case, continue_execution;
  transition(itos, vtos);
  // align esi
  __ leal(ebx, at_bcp(wordSize));
  __ andl(ebx, -wordSize);
  // load lo & hi
  __ movl(ecx, Address(ebx, 1 * wordSize));
  __ movl(edx, Address(ebx, 2 * wordSize));
  __ bswap(ecx);
  __ bswap(edx);
  // check against lo & hi
  __ cmpl(eax, ecx);
  __ jccb(Assembler::less, default_case);
  __ cmpl(eax, edx);
  __ jccb(Assembler::greater, default_case);
  // lookup dispatch offset
  __ subl(eax, ecx);
  __ movl(edx, Address(ebx, eax, Address::times_4, 3 * wordSize));
  __ profile_switch_case(eax, ebx, ecx);
  // continue execution
  __ bind(continue_execution);
  __ bswap(edx);
  __ load_unsigned_byte(ebx, Address(esi, edx, Address::times_1));
  __ addl(esi, edx);
  __ dispatch_only(vtos);
  // handle default
  __ bind(default_case);
  __ profile_switch_default(eax);
  __ movl(edx, Address(ebx));
  __ jmp(continue_execution);
}


void TemplateTable::lookupswitch() {
  transition(itos, itos);
  __ stop("lookupswitch bytecode should have been rewritten");
}


void TemplateTable::fast_linearswitch() {
  transition(itos, vtos);
  Label loop_entry, loop, found, continue_execution;  
  // bswap eax so we can avoid bswapping the table entries
  __ bswap(eax);
  // align esi
  __ leal(ebx, at_bcp(wordSize));                // btw: should be able to get rid of this instruction (change offsets below)
  __ andl(ebx, -wordSize);
  // set counter
  __ movl(ecx, Address(ebx, wordSize));  
  __ bswap(ecx);
  __ jmpb(loop_entry);
  // table search
  __ bind(loop);
  __ cmpl(eax, Address(ebx, ecx, Address::times_8, 2 * wordSize));
  __ jccb(Assembler::equal, found);
  __ bind(loop_entry);
  __ decrement(ecx);
  __ jcc(Assembler::greaterEqual, loop);
  // default case
  __ profile_switch_default(eax);
  __ movl(edx, Address(ebx));
  __ jmpb(continue_execution);
  // entry found -> get offset
  __ bind(found);
  __ movl(edx, Address(ebx, ecx, Address::times_8, 3 * wordSize));
  __ profile_switch_case(ecx, eax, ebx);
  // continue execution
  __ bind(continue_execution);  
  __ bswap(edx);
  __ load_unsigned_byte(ebx, Address(esi, edx, Address::times_1));
  __ addl(esi, edx);
  __ dispatch_only(vtos);
}


void TemplateTable::fast_binaryswitch() {
  transition(itos, vtos);
  // Implementation using the following core algorithm:
  //
  // int binary_search(int key, LookupswitchPair* array, int n) {
  //   // Binary search according to "Methodik des Programmierens" by
  //   // Edsger W. Dijkstra and W.H.J. Feijen, Addison Wesley Germany 1985.
  //   int i = 0;
  //   int j = n;
  //   while (i+1 < j) {
  //     // invariant P: 0 <= i < j <= n and (a[i] <= key < a[j] or Q)
  //     // with      Q: for all i: 0 <= i < n: key < a[i]
  //     // where a stands for the array and assuming that the (inexisting)
  //     // element a[n] is infinitely big.
  //     int h = (i + j) >> 1;
  //     // i < h < j
  //     if (key < array[h].fast_match()) {
  //       j = h;
  //     } else {
  //       i = h;
  //     }
  //   }
  //   // R: a[i] <= key < a[i+1] or Q
  //   // (i.e., if key is within array, i is the correct index)
  //   return i;
  // }

  // register allocation
  const Register key   = eax;                    // already set (tosca)
  const Register array = ebx;
  const Register i     = ecx;
  const Register j     = edx;
  const Register h     = edi;                    // needs to be restored
  const Register temp  = esi;
  // setup array
  __ save_bcp();

  __ leal(array, at_bcp(3*wordSize));            // btw: should be able to get rid of this instruction (change offsets below)
  __ andl(array, -wordSize);
  // initialize i & j
  __ xorl(i, i);                                 // i = 0;
  __ movl(j, Address(array, -wordSize));         // j = length(array);    
  // Convert j into native byteordering  
  __ bswap(j);
  // and start
  Label entry;
  __ jmp(entry);

  // binary search loop
  { Label loop;
    __ bind(loop);
    // int h = (i + j) >> 1;
    __ leal(h, Address(i, j, Address::times_1)); // h = i + j;
    __ sarl(h, 1);                               // h = (i + j) >> 1;
    // if (key < array[h].fast_match()) {
    //   j = h;
    // } else {
    //   i = h;
    // }
    // Convert array[h].match to native byte-ordering before compare
    __ movl(temp, Address(array, h, Address::times_8, 0*wordSize));
    __ bswap(temp);
    __ cmpl(key, temp);
    if (VM_Version::supports_cmov()) {
      __ cmovl(Assembler::less        , j, h);   // j = h if (key <  array[h].fast_match())
      __ cmovl(Assembler::greaterEqual, i, h);   // i = h if (key >= array[h].fast_match())
    } else {
      Label set_i, end_of_if;
      __ jccb(Assembler::greaterEqual, set_i);    // {
      __ movl(j, h);                             //   j = h;
      __ jmp(end_of_if);                         // }
      __ bind(set_i);                            // else {
      __ movl(i, h);                             //   i = h;
      __ bind(end_of_if);                        // }
    }
    // while (i+1 < j)
    __ bind(entry);
    __ leal(h, Address(i, 1));                   // i+1
    __ cmpl(h, j);                               // i+1 < j
    __ jcc(Assembler::less, loop);
  }

  // end of binary search, result index is i (must check again!)
  Label default_case;
  // Convert array[i].match to native byte-ordering before compare
  __ movl(temp, Address(array, i, Address::times_8, 0*wordSize));
  __ bswap(temp);
  __ cmpl(key, temp);
  __ jcc(Assembler::notEqual, default_case);

  // entry found -> j = offset
  __ movl(j , Address(array, i, Address::times_8, 1*wordSize));
  __ profile_switch_case(i, key, array);
  __ bswap(j);
  __ restore_bcp();
  __ restore_locals();                           // restore edi
  __ load_unsigned_byte(ebx, Address(esi, j, Address::times_1));
  
  __ addl(esi, j);
  __ dispatch_only(vtos);

  // default case -> j = default offset
  __ bind(default_case);
  __ profile_switch_default(i);
  __ movl(j, Address(array, -2*wordSize));
  __ bswap(j);
  __ restore_bcp();
  __ restore_locals();                           // restore edi
  __ load_unsigned_byte(ebx, Address(esi, j, Address::times_1));
  __ addl(esi, j);
  __ dispatch_only(vtos);
}


void TemplateTable::_return(TosState state) {
  transition(state, state);
  assert(_desc->calls_vm(), "inconsistent calls_vm information"); // call in remove_activation

  if (_desc->bytecode() == Bytecodes::_return_register_finalizer) {
    assert(state == vtos, "only valid state");
    __ movl(eax, aaddress(0));
    __ movl(edi, Address(eax, oopDesc::klass_offset_in_bytes()));
    __ movl(edi, Address(edi, Klass::access_flags_offset_in_bytes() + sizeof(oopDesc)));
    __ testl(edi, JVM_ACC_HAS_FINALIZER);
    Label skip_register_finalizer;
    __ jcc(Assembler::zero, skip_register_finalizer);

    __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::register_finalizer), eax);

    __ bind(skip_register_finalizer);
  }

  __ remove_activation(state, esi);
  __ jmp(esi);
}


// ----------------------------------------------------------------------------
// Volatile variables demand their effects be made known to all CPU's in
// order.  Store buffers on most chips allow reads & writes to reorder; the
// JMM's ReadAfterWrite.java test fails in -Xint mode without some kind of
// memory barrier (i.e., it's not sufficient that the interpreter does not
// reorder volatile references, the hardware also must not reorder them).
// 
// According to the new Java Memory Model (JMM):
// (1) All volatiles are serialized wrt to each other.  
// ALSO reads & writes act as aquire & release, so:
// (2) A read cannot let unrelated NON-volatile memory refs that happen after
// the read float up to before the read.  It's OK for non-volatile memory refs
// that happen before the volatile read to float down below it.
// (3) Similar a volatile write cannot let unrelated NON-volatile memory refs
// that happen BEFORE the write float down to after the write.  It's OK for
// non-volatile memory refs that happen after the volatile write to float up
// before it.
//
// We only put in barriers around volatile refs (they are expensive), not
// _between_ memory refs (that would require us to track the flavor of the
// previous memory refs).  Requirements (2) and (3) require some barriers
// before volatile stores and after volatile loads.  These nearly cover
// requirement (1) but miss the volatile-store-volatile-load case.  This final
// case is placed after volatile-stores although it could just as well go
// before volatile-loads.
void TemplateTable::volatile_barrier( ) {
  // Helper function to insert a is-volatile test and memory barrier
  if( !os::is_MP() ) return;	// Not needed on single CPU
  __ membar();
}

void TemplateTable::resolve_cache_and_index(int byte_no, Register Rcache, Register index) {
  assert(byte_no == 1 || byte_no == 2, "byte_no out of range");

  Register temp = ebx;

  assert_different_registers(Rcache, index, temp);

  const int shift_count = (1 + byte_no)*BitsPerByte;
  Label resolved;
  __ get_cache_and_index_at_bcp(Rcache, index, 1);
  __ movl(temp, Address(Rcache, index, Address::times_4, constantPoolCacheOopDesc::base_offset() + ConstantPoolCacheEntry::indices_offset()));
  __ shrl(temp, shift_count);
  // have we resolved this bytecode? 
  __ andl(temp, 0xFF);
  __ cmpl(temp, (int)bytecode());
  __ jcc(Assembler::equal, resolved);

  // resolve first time through
  address entry;
  switch (bytecode()) {
    case Bytecodes::_getstatic      : // fall through
    case Bytecodes::_putstatic      : // fall through
    case Bytecodes::_getfield       : // fall through
    case Bytecodes::_putfield       : entry = CAST_FROM_FN_PTR(address, InterpreterRuntime::resolve_get_put); break;
    case Bytecodes::_invokevirtual  : // fall through
    case Bytecodes::_invokespecial  : // fall through
    case Bytecodes::_invokestatic   : // fall through
    case Bytecodes::_invokeinterface: entry = CAST_FROM_FN_PTR(address, InterpreterRuntime::resolve_invoke);  break;
    default                         : ShouldNotReachHere();                                 break;
  }
  __ movl(temp, (int)bytecode()); 
  __ call_VM(noreg, entry, temp);
  // Update registers with resolved info
  __ get_cache_and_index_at_bcp(Rcache, index, 1);
  __ bind(resolved);
}


// The cache and index registers must be set before call
void TemplateTable::load_field_cp_cache_entry(Register obj,
                                              Register cache,
                                              Register index,
                                              Register off,
                                              Register flags,
                                              bool is_static = false) {
  assert_different_registers(cache, index, flags, off);

  ByteSize cp_base_offset = constantPoolCacheOopDesc::base_offset();
  // Field offset
  __ movl(off, Address(cache, index, Address::times_4, 
           in_bytes(cp_base_offset + ConstantPoolCacheEntry::f2_offset())));
  // Flags    
  __ movl(flags, Address(cache, index, Address::times_4,
           in_bytes(cp_base_offset + ConstantPoolCacheEntry::flags_offset())));

  // klass     overwrite register
  if (is_static) {
    __ movl(obj, Address(cache, index, Address::times_4,
             in_bytes(cp_base_offset + ConstantPoolCacheEntry::f1_offset())));
  }
}

void TemplateTable::load_invoke_cp_cache_entry(int byte_no,
                                               Register method,
                                               Register itable_index,
                                               Register flags,
                                               bool is_invokevirtual,
                                               bool is_invokevfinal /*unused*/) {
  // setup registers
  const Register cache = ecx;
  const Register index = edx;
  assert_different_registers(method, flags);
  assert_different_registers(method, cache, index);
  assert_different_registers(itable_index, flags);
  assert_different_registers(itable_index, cache, index);
  // determine constant pool cache field offsets
  const int method_offset = in_bytes(
    constantPoolCacheOopDesc::base_offset() +
      (is_invokevirtual
       ? ConstantPoolCacheEntry::f2_offset()
       : ConstantPoolCacheEntry::f1_offset()
      )
    );
  const int flags_offset = in_bytes(constantPoolCacheOopDesc::base_offset() +
                                    ConstantPoolCacheEntry::flags_offset());
  // access constant pool cache fields
  const int index_offset = in_bytes(constantPoolCacheOopDesc::base_offset() +
                                    ConstantPoolCacheEntry::f2_offset());

  resolve_cache_and_index(byte_no, cache, index);

  assert(wordSize == 4, "adjust code below");
  __ movl(method, Address(cache, index, Address::times_4, method_offset));
  if (itable_index != noreg) {
    __ movl(itable_index, Address(cache, index, Address::times_4, index_offset));
  }
  __ movl(flags , Address(cache, index, Address::times_4, flags_offset ));
}


// The registers cache and index expected to be set before call.
// Correct values of the cache and index registers are preserved.
void TemplateTable::jvmti_post_field_access(Register cache,
                                            Register index,
                                            bool is_static,
                                            bool has_tos) {
  if (JvmtiExport::can_post_field_access()) {
    // Check to see if a field access watch has been set before we take
    // the time to call into the VM.
    Label L1;
    assert_different_registers(cache, index, eax);
    __ movl(eax, Address((int)JvmtiExport::get_field_access_count_addr(), relocInfo::none));
    __ testl(eax,eax);
    __ jcc(Assembler::zero, L1);

    // cache entry pointer
    __ addl(cache, in_bytes(constantPoolCacheOopDesc::base_offset()));
    __ shll(index, LogBytesPerWord);
    __ addl(cache, index);
    if (is_static) {
      __ movl(eax, 0);      // NULL object reference
    } else {
      __ pop(atos);         // Get the object
      __ verify_oop(eax);
      __ push(atos);        // Restore stack state
    }
    // eax:   object pointer or NULL
    // cache: cache entry pointer
    __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::post_field_access),
               eax, cache);
    __ get_cache_and_index_at_bcp(cache, index, 1);
    __ bind(L1);
  } 
}

void TemplateTable::pop_and_check_object(Register r) {
  __ pop_ptr(r);
  __ null_check(r);  // for field access must check obj.
  __ verify_oop(r);
}

void TemplateTable::getfield_or_static(int byte_no, bool is_static) {
  transition(vtos, vtos);

  const Register cache = ecx;
  const Register index = edx;
  const Register obj   = ecx;
  const Register off   = ebx;
  const Register flags = eax;

  resolve_cache_and_index(byte_no, cache, index);
  jvmti_post_field_access(cache, index, is_static, false);
  load_field_cp_cache_entry(obj, cache, index, off, flags, is_static);

  if (!is_static) pop_and_check_object(obj);

  const Address lo(obj, off, Address::times_1, 0*wordSize);
  const Address hi(obj, off, Address::times_1, 1*wordSize);

  Label Done, notByte, notInt, notShort, notChar, notLong, notFloat, notObj, notDouble;

  __ shrl(flags, ConstantPoolCacheEntry::tosBits);
  assert(btos == 0, "change code, btos != 0");
  // btos
  __ andl(flags, 0x0f);
  __ jcc(Assembler::notZero, notByte);

  __ load_signed_byte(eax, lo ); 
  __ push(btos);
  // Rewrite bytecode to be faster
  if (!is_static) {
    patch_bytecode(Bytecodes::_fast_bgetfield, ecx, ebx);
  }
  __ jmp(Done);

  __ bind(notByte);
  // itos
  __ cmpl(flags, itos );
  __ jcc(Assembler::notEqual, notInt);

  __ movl(eax, lo );
  __ push(itos);
  // Rewrite bytecode to be faster
  if (!is_static) {
    patch_bytecode(Bytecodes::_fast_igetfield, ecx, ebx);
  }
  __ jmp(Done);

  __ bind(notInt);
  // atos
  __ cmpl(flags, atos );
  __ jcc(Assembler::notEqual, notObj);

  __ movl(eax, lo );
  __ push(atos);
  if (!is_static) {
    patch_bytecode(Bytecodes::_fast_agetfield, ecx, ebx);
  }
  __ jmp(Done);

  __ bind(notObj);
  // ctos
  __ cmpl(flags, ctos );
  __ jcc(Assembler::notEqual, notChar);

  __ load_unsigned_word(eax, lo ); 
  __ push(ctos);
  if (!is_static) {
    patch_bytecode(Bytecodes::_fast_cgetfield, ecx, ebx);
  }
  __ jmp(Done);

  __ bind(notChar);
  // stos
  __ cmpl(flags, stos );
  __ jcc(Assembler::notEqual, notShort);

  __ load_signed_word(eax, lo );
  __ push(stos);
  if (!is_static) {
    patch_bytecode(Bytecodes::_fast_sgetfield, ecx, ebx);
  }
  __ jmp(Done);

  __ bind(notShort);
  // ltos
  __ cmpl(flags, ltos );
  __ jcc(Assembler::notEqual, notLong);

  // Generate code as if volatile.  There just aren't enough registers to
  // save that information and this code is faster than the test.
  __ fild_d(lo);		// Must load atomically
  __ subl(esp,2*wordSize);	// Make space for store
  __ fistp_d(Address(esp,0));
  __ popl(eax);
  __ popl(edx);

  __ push(ltos);
  // Don't rewrite to _fast_lgetfield for potential volatile case.
  __ jmp(Done);

  __ bind(notLong);
  // ftos
  __ cmpl(flags, ftos );
  __ jcc(Assembler::notEqual, notFloat);

  __ fld_s(lo);
  __ push(ftos);
  if (!is_static) {
    patch_bytecode(Bytecodes::_fast_fgetfield, ecx, ebx);
  }
  __ jmp(Done);

  __ bind(notFloat);
  // dtos
  __ cmpl(flags, dtos );
  __ jcc(Assembler::notEqual, notDouble);

  __ fld_d(lo);
  __ push(dtos);
  if (!is_static) {
    patch_bytecode(Bytecodes::_fast_dgetfield, ecx, ebx);
  }
  __ jmpb(Done);

  __ bind(notDouble);
  
  __ stop("Bad state");

  __ bind(Done);
  // Doug Lea believes this is not needed with current Sparcs (TSO) and Intel (PSO).
  // volatile_barrier( );
}


void TemplateTable::getfield(int byte_no) {
  getfield_or_static(byte_no, false);
}


void TemplateTable::getstatic(int byte_no) {
  getfield_or_static(byte_no, true);
}

// The registers cache and index expected to be set before call.
// The function may destroy various registers, just not the cache and index registers.
void TemplateTable::jvmti_post_field_mod(Register cache, Register index, bool is_static) {

  ByteSize cp_base_offset = constantPoolCacheOopDesc::base_offset();

  if (JvmtiExport::can_post_field_modification()) {
    // Check to see if a field modification watch has been set before we take
    // the time to call into the VM.
    Label L1;
    assert_different_registers(cache, index, eax);
    __ movl(eax, Address((int)JvmtiExport::get_field_modification_count_addr(), relocInfo::none));
    __ testl(eax, eax);
    __ jcc(Assembler::zero, L1);

    // The cache and index registers have been already set.
    // This allows to eliminate this call but the cache and index
    // registers have to be correspondingly used after this line.
    __ get_cache_and_index_at_bcp(eax, edx, 1);

    if (is_static) {
      // Life is simple.  Null out the object pointer.
      __ xorl(ebx, ebx);
    } else {
      // Life is harder. The stack holds the value on top, followed by the object.
      // We don't know the size of the value, though; it could be one or two words
      // depending on its type. As a result, we must find the type to determine where
      // the object is.
      Label two_word, valsize_known;
      __ movl(ecx, Address(eax, edx, Address::times_4, in_bytes(cp_base_offset +
                                   ConstantPoolCacheEntry::flags_offset())));
      __ movl(ebx, esp);
      __ shrl(ecx, ConstantPoolCacheEntry::tosBits);
      // Make sure we don't need to mask ecx for tosBits after the above shift
      ConstantPoolCacheEntry::verify_tosBits();
      __ cmpl(ecx, ltos);
      __ jccb(Assembler::equal, two_word);
      __ cmpl(ecx, dtos);
      __ jccb(Assembler::equal, two_word);
      __ addl(ebx, Interpreter::expr_offset_in_bytes(1)); // one word jvalue (not ltos, dtos)
      __ jmpb(valsize_known);

      __ bind(two_word);
      __ addl(ebx, Interpreter::expr_offset_in_bytes(2)); // two words jvalue
    
      __ bind(valsize_known);
      // setup object pointer
      __ movl(ebx, Address(ebx, 0));
    }
    // cache entry pointer
    __ addl(eax, in_bytes(cp_base_offset));
    __ shll(edx, LogBytesPerWord);
    __ addl(eax, edx);
    // object (tos)
    __ movl(ecx, esp);
    // ebx: object pointer set up above (NULL if static)
    // eax: cache entry pointer
    // ecx: jvalue object on the stack
    __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::post_field_modification),
               ebx, eax, ecx);
    __ get_cache_and_index_at_bcp(cache, index, 1);
    __ bind(L1);
  }
}


void TemplateTable::putfield_or_static(int byte_no, bool is_static) {
  transition(vtos, vtos);

  const Register cache = ecx;
  const Register index = edx;
  const Register obj   = ecx;
  const Register off   = ebx;
  const Register flags = eax;

  resolve_cache_and_index(byte_no, cache, index);
  jvmti_post_field_mod(cache, index, is_static);
  load_field_cp_cache_entry(obj, cache, index, off, flags, is_static);

  // Doug Lea believes this is not needed with current Sparcs (TSO) and Intel (PSO).
  // volatile_barrier( );

  Label notVolatile, Done;
  __ movl(edx, flags);
  __ shrl(edx, ConstantPoolCacheEntry::volatileField);
  __ andl(edx, 0x1);

  // field addresses
  const Address lo(obj, off, Address::times_1, 0*wordSize);
  const Address hi(obj, off, Address::times_1, 1*wordSize);

  Label notByte, notInt, notShort, notChar, notLong, notFloat, notObj, notDouble;

  __ shrl(flags, ConstantPoolCacheEntry::tosBits);
  assert(btos == 0, "change code, btos != 0");
  // btos
  __ andl(flags, 0x0f);
  __ jcc(Assembler::notZero, notByte);

  __ pop(btos);
  if (!is_static) pop_and_check_object(obj);
  __ movb(lo, eax );
  if (!is_static) {
    patch_bytecode(Bytecodes::_fast_bputfield, ecx, ebx);
  }
  __ jmp(Done);

  __ bind(notByte);
  // itos
  __ cmpl(flags, itos );
  __ jcc(Assembler::notEqual, notInt);

  __ pop(itos);
  if (!is_static) pop_and_check_object(obj);

  __ movl(lo, eax );
  if (!is_static) {
    patch_bytecode(Bytecodes::_fast_iputfield, ecx, ebx);
  }
  __ jmp(Done);
  
  __ bind(notInt);
  // atos
  __ cmpl(flags, atos );
  __ jcc(Assembler::notEqual, notObj);

  __ pop(atos);
  if (!is_static) pop_and_check_object(obj);

  __ movl(lo, eax );
  __ store_check(obj, lo);  // Need to mark card
  if (!is_static) {
    patch_bytecode(Bytecodes::_fast_aputfield, ecx, ebx);
  }
  __ jmp(Done);

  __ bind(notObj);
  // ctos
  __ cmpl(flags, ctos );
  __ jcc(Assembler::notEqual, notChar);

  __ pop(ctos);
  if (!is_static) pop_and_check_object(obj);
  __ movw(lo, eax );
  if (!is_static) {
    patch_bytecode(Bytecodes::_fast_cputfield, ecx, ebx);
  }
  __ jmp(Done);

  __ bind(notChar);
  // stos
  __ cmpl(flags, stos );
  __ jcc(Assembler::notEqual, notShort);

  __ pop(stos);
  if (!is_static) pop_and_check_object(obj);
  __ movw(lo, eax );
  if (!is_static) {
    patch_bytecode(Bytecodes::_fast_sputfield, ecx, ebx);
  }
  __ jmp(Done);

  __ bind(notShort);
  // ltos
  __ cmpl(flags, ltos );
  __ jcc(Assembler::notEqual, notLong);

  Label notVolatileLong;
  __ testl(edx, edx);
  __ jcc(Assembler::zero, notVolatileLong);

  __ pop(ltos);  // overwrites edx, do this after testing volatile.
  if (!is_static) pop_and_check_object(obj);
  
  // Replace with real volatile test
  __ pushl(edx);
  __ pushl(eax);                // Must update atomically with FIST
  __ fild_d(Address(esp,0));	// So load into FPU register
  __ fistp_d(lo);		// and put into memory atomically
  __ addl(esp,2*wordSize);
  volatile_barrier();
  // Don't rewrite volatile version
  __ jmp(notVolatile);

  __ bind(notVolatileLong);

  __ pop(ltos);  // overwrites edx
  if (!is_static) pop_and_check_object(obj);
  __ movl(hi, edx);
  __ movl(lo, eax);
  if (!is_static) {
    patch_bytecode(Bytecodes::_fast_lputfield, ecx, ebx);
  }
  __ jmp(notVolatile);

  __ bind(notLong);
  // ftos
  __ cmpl(flags, ftos );
  __ jcc(Assembler::notEqual, notFloat);

  __ pop(ftos);
  if (!is_static) pop_and_check_object(obj);
  __ fstp_s(lo);
  if (!is_static) {
    patch_bytecode(Bytecodes::_fast_fputfield, ecx, ebx);
  }
  __ jmp(Done);

  __ bind(notFloat);
  // dtos
  __ cmpl(flags, dtos );
  __ jcc(Assembler::notEqual, notDouble);

  __ pop(dtos);
  if (!is_static) pop_and_check_object(obj);
  __ fstp_d(lo);
  if (!is_static) {
    patch_bytecode(Bytecodes::_fast_dputfield, ecx, ebx);
  }
  __ jmp(Done);

  __ bind(notDouble);
 
  __ stop("Bad state");

  __ bind(Done);

  // Check for volatile store
  __ testl(edx, edx);
  __ jcc(Assembler::zero, notVolatile);
  volatile_barrier( );
  __ bind(notVolatile);
}


void TemplateTable::putfield(int byte_no) {
  putfield_or_static(byte_no, false);
}


void TemplateTable::putstatic(int byte_no) {
  putfield_or_static(byte_no, true);
}

void TemplateTable::jvmti_post_fast_field_mod() {
  if (JvmtiExport::can_post_field_modification()) {
    // Check to see if a field modification watch has been set before we take
    // the time to call into the VM.
    Label L2;
    __ movl(ecx, Address((int)JvmtiExport::get_field_modification_count_addr(), relocInfo::none));
    __ testl(ecx,ecx);
    __ jcc(Assembler::zero, L2);
    __ pop_ptr(ebx);               // copy the object pointer from tos
    __ verify_oop(ebx);
    __ push_ptr(ebx);              // put the object pointer back on tos
    __ subl(esp, sizeof(jvalue));  // add space for a jvalue object      
    __ movl(ecx, esp);
    __ push_ptr(ebx);                 // save object pointer so we can steal ebx
    __ movl(ebx, 0);
    const Address lo_value(ecx, ebx, Address::times_1, 0*wordSize);
    const Address hi_value(ecx, ebx, Address::times_1, 1*wordSize);
    switch (bytecode()) {          // load values into the jvalue object
    case Bytecodes::_fast_bputfield: __ movb(lo_value, eax); break;
    case Bytecodes::_fast_sputfield: __ movw(lo_value, eax); break;
    case Bytecodes::_fast_cputfield: __ movw(lo_value, eax); break;
    case Bytecodes::_fast_iputfield: __ movl(lo_value, eax);                         break;
    case Bytecodes::_fast_lputfield: __ movl(hi_value, edx); __ movl(lo_value, eax); break;
    // need to call fld_s() after fstp_s() to restore the value for below
    case Bytecodes::_fast_fputfield: __ fstp_s(lo_value); __ fld_s(lo_value);        break;
    // need to call fld_d() after fstp_d() to restore the value for below
    case Bytecodes::_fast_dputfield: __ fstp_d(lo_value); __ fld_d(lo_value);        break;
    // since ecx is not an object we don't call store_check() here
    case Bytecodes::_fast_aputfield: __ movl(lo_value, eax);                         break;
    default:  ShouldNotReachHere();
    }
    __ pop_ptr(ebx);  // restore copy of object pointer

    // Save eax and sometimes edx because call_VM() will clobber them,
    // then use them for JVM/DI purposes
    __ pushl(eax);
    if (bytecode() == Bytecodes::_fast_lputfield) __ pushl(edx);
    // access constant pool cache entry
    __ get_cache_entry_pointer_at_bcp(eax, edx, 1);
    __ verify_oop(ebx);
    // ebx: object pointer copied above
    // eax: cache entry pointer
    // ecx: jvalue object on the stack
    __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::post_field_modification), ebx, eax, ecx);
    if (bytecode() == Bytecodes::_fast_lputfield) __ popl(edx);  // restore high value
    __ popl(eax);     // restore lower value   
    __ addl(esp, sizeof(jvalue));  // release jvalue object space
    __ bind(L2);
  }
}

void TemplateTable::fast_storefield(TosState state) {
  transition(state, vtos);

  ByteSize base = constantPoolCacheOopDesc::base_offset();

  jvmti_post_fast_field_mod();

  // access constant pool cache
  __ get_cache_and_index_at_bcp(ecx, ebx, 1);

  // test for volatile with edx but edx is tos register for lputfield.
  if (bytecode() == Bytecodes::_fast_lputfield) __ pushl(edx);
  __ movl(edx, Address(ecx, ebx, Address::times_4, in_bytes(base +
                       ConstantPoolCacheEntry::flags_offset())));

  // replace index with field offset from cache entry
  __ movl(ebx, Address(ecx, ebx, Address::times_4, in_bytes(base + ConstantPoolCacheEntry::f2_offset())));

  // Doug Lea believes this is not needed with current Sparcs (TSO) and Intel (PSO).
  // volatile_barrier( );

  Label notVolatile, Done;
  __ shrl(edx, ConstantPoolCacheEntry::volatileField);
  __ andl(edx, 0x1);
  // Check for volatile store
  __ testl(edx, edx);
  __ jcc(Assembler::zero, notVolatile);

  if (bytecode() == Bytecodes::_fast_lputfield) __ popl(edx);

  // Get object from stack
  pop_and_check_object(ecx);

  // field addresses
  const Address lo(ecx, ebx, Address::times_1, 0*wordSize);
  const Address hi(ecx, ebx, Address::times_1, 1*wordSize);

  // access field
  switch (bytecode()) {
    case Bytecodes::_fast_bputfield: __ movb(lo, eax); break;
    case Bytecodes::_fast_sputfield: // fall through
    case Bytecodes::_fast_cputfield: __ movw(lo, eax); break;
    case Bytecodes::_fast_iputfield: __ movl(lo, eax); break;
    case Bytecodes::_fast_lputfield: __ movl(hi, edx); __ movl(lo, eax);        break;
    case Bytecodes::_fast_fputfield: __ fstp_s(lo); break;
    case Bytecodes::_fast_dputfield: __ fstp_d(lo); break;
    case Bytecodes::_fast_aputfield: __ movl(lo, eax); __ store_check(ecx, lo); break;
    default:
      ShouldNotReachHere();
  }

  Label done;
  volatile_barrier( );
  __ jmpb(done);

  // Same code as above, but don't need edx to test for volatile.
  __ bind(notVolatile);

  if (bytecode() == Bytecodes::_fast_lputfield) __ popl(edx);

  // Get object from stack
  pop_and_check_object(ecx);

  // access field
  switch (bytecode()) {
    case Bytecodes::_fast_bputfield: __ movb(lo, eax); break;
    case Bytecodes::_fast_sputfield: // fall through
    case Bytecodes::_fast_cputfield: __ movw(lo, eax); break;
    case Bytecodes::_fast_iputfield: __ movl(lo, eax); break;
    case Bytecodes::_fast_lputfield: __ movl(hi, edx); __ movl(lo, eax);        break;
    case Bytecodes::_fast_fputfield: __ fstp_s(lo); break;
    case Bytecodes::_fast_dputfield: __ fstp_d(lo); break;
    case Bytecodes::_fast_aputfield: __ movl(lo, eax); __ store_check(ecx, lo); break;
    default:
      ShouldNotReachHere();
  }
  __ bind(done);
}


void TemplateTable::fast_accessfield(TosState state) {
  transition(atos, state);

  // do the JVMTI work here to avoid disturbing the register state below
  if (JvmtiExport::can_post_field_access()) {
    // Check to see if a field access watch has been set before we take
    // the time to call into the VM.
    Label L1;
    __ movl(ecx, Address((int)JvmtiExport::get_field_access_count_addr(), relocInfo::none));
    __ testl(ecx,ecx);
    __ jcc(Assembler::zero, L1);
    // access constant pool cache entry
    __ get_cache_entry_pointer_at_bcp(ecx, edx, 1);
    __ push_ptr(eax);  // save object pointer before call_VM() clobbers it
    __ verify_oop(eax);
    // eax: object pointer copied above
    // ecx: cache entry pointer
    __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::post_field_access), eax, ecx);
    __ pop_ptr(eax);   // restore object pointer
    __ bind(L1);
  }

  // access constant pool cache
  __ get_cache_and_index_at_bcp(ecx, ebx, 1);
  // replace index with field offset from cache entry
  __ movl(ebx, Address(ecx, ebx, Address::times_4, in_bytes(constantPoolCacheOopDesc::base_offset() + ConstantPoolCacheEntry::f2_offset())));


  // eax: object
  __ verify_oop(eax);
  __ null_check(eax);
  // field addresses
  const Address lo = Address(eax, ebx, Address::times_1, 0*wordSize);
  const Address hi = Address(eax, ebx, Address::times_1, 1*wordSize);

  // access field
  switch (bytecode()) {
    case Bytecodes::_fast_bgetfield: __ movsxb(eax, lo );                 break;
    case Bytecodes::_fast_sgetfield: __ load_signed_word(eax, lo );       break;
    case Bytecodes::_fast_cgetfield: __ load_unsigned_word(eax, lo );     break;
    case Bytecodes::_fast_igetfield: __ movl(eax, lo);                    break;
    case Bytecodes::_fast_lgetfield: __ stop("should not be rewritten");  break;
    case Bytecodes::_fast_fgetfield: __ fld_s(lo);                        break;
    case Bytecodes::_fast_dgetfield: __ fld_d(lo);                        break;
    case Bytecodes::_fast_agetfield: __ movl(eax, lo); __ verify_oop(eax); break;
    default:
      ShouldNotReachHere();
  }

  // Doug Lea believes this is not needed with current Sparcs(TSO) and Intel(PSO)
  // volatile_barrier( );
}

void TemplateTable::fast_xaccess(TosState state) {
  transition(vtos, state);
  // get receiver
  __ movl(eax, aaddress(0));
  debug_only(__ verify_local_tag(frame::TagReference, 0));
  // access constant pool cache
  __ get_cache_and_index_at_bcp(ecx, edx, 2);
  __ movl(ebx, Address(ecx, edx, Address::times_4, in_bytes(constantPoolCacheOopDesc::base_offset() + ConstantPoolCacheEntry::f2_offset())));
  // make sure exception is reported in correct bcp range (getfield is next instruction)
  __ increment(esi);
  __ null_check(eax);
  const Address lo = Address(eax, ebx, Address::times_1, 0*wordSize);
  if (state == itos) {
    __ movl(eax, lo);
  } else if (state == atos) {
    __ movl(eax, lo);
    __ verify_oop(eax);
  } else if (state == ftos) {
    __ fld_s(lo);
  } else {
    ShouldNotReachHere();
  }
  __ decrement(esi);
}



//----------------------------------------------------------------------------------------------------
// Calls

void TemplateTable::count_calls(Register method, Register temp) {  
  // implemented elsewhere
  ShouldNotReachHere();
}


void TemplateTable::prepare_invoke(Register method, Register index, int byte_no, Bytecodes::Code code) {
  // determine flags
  const bool is_invokeinterface  = code == Bytecodes::_invokeinterface;
  const bool is_invokevirtual    = code == Bytecodes::_invokevirtual;
  const bool is_invokespecial    = code == Bytecodes::_invokespecial;
  const bool load_receiver       = code != Bytecodes::_invokestatic;
  const bool receiver_null_check = is_invokespecial;
  const bool save_flags = is_invokeinterface || is_invokevirtual;
  // setup registers & access constant pool cache
  const Register recv   = ecx;
  const Register flags  = edx;  
  assert_different_registers(method, index, recv, flags);

  // save 'interpreter return address'
  __ save_bcp();

  load_invoke_cp_cache_entry(byte_no, method, index, flags, is_invokevirtual);

  // load receiver if needed (note: no return address pushed yet)
  if (load_receiver) {
    __ movl(recv, flags);
    __ andl(recv, 0xFF);
    // recv count is 0 based?
    __ movl(recv, Address(esp, recv, Interpreter::stackElementScale(), -Interpreter::expr_offset_in_bytes(1)));
    __ verify_oop(recv);
  }

  // do null check if needed
  if (receiver_null_check) {
    __ null_check(recv);
  }

  if (save_flags) {
    __ movl(esi, flags);
  }

  // compute return type
  __ shrl(flags, ConstantPoolCacheEntry::tosBits);
  // Make sure we don't need to mask flags for tosBits after the above shift
  ConstantPoolCacheEntry::verify_tosBits();
  // load return address
  { const int table =
      is_invokeinterface
      ? (int)Interpreter::return_5_addrs_by_index_table()
      : (int)Interpreter::return_3_addrs_by_index_table();
    __ movl(flags, Address(noreg, flags, Address::times_4, table));
  }

  // push return address
  __ pushl(flags);

  // Restore flag value from the constant pool cache, and restore esi
  // for later null checks.  esi is the bytecode pointer
  if (save_flags) {
    __ movl(flags, esi);
    __ restore_bcp();
  }
}


void TemplateTable::invokevirtual_helper(Register index, Register recv,
                        Register flags) {

  // Uses temporary registers eax, edx
  assert_different_registers(index, recv, eax, edx);

  // Test for an invoke of a final method
  Label notFinal;
  __ movl(eax, flags);
  __ andl(eax, (1 << ConstantPoolCacheEntry::vfinalMethod));
  __ jcc(Assembler::zero, notFinal);

  Register method = index;  // method must be ebx
  assert(method == ebx, "methodOop must be ebx for interpreter calling convention");

  // do the call - the index is actually the method to call
  __ verify_oop(method);

  // It's final, need a null check here!
  __ null_check(recv);

  // profile this call
  __ profile_final_call(eax);

  __ jump_from_interpreted(method, eax);

  __ bind(notFinal);

  // get receiver klass
  __ null_check(recv, oopDesc::klass_offset_in_bytes());
  // Keep recv in ecx for callee expects it there
  __ movl(eax, Address(recv, oopDesc::klass_offset_in_bytes()));
  __ verify_oop(eax);

  // profile this call
  __ profile_virtual_call(eax, edi, edx);

  // get target methodOop & entry point
  const int base = instanceKlass::vtable_start_offset() * wordSize;    
  assert(vtableEntry::size() * wordSize == 4, "adjust the scaling in the code below");
  __ movl(method, Address(eax, index, Address::times_4, base + vtableEntry::method_offset_in_bytes()));  
  __ jump_from_interpreted(method, edx);
}


void TemplateTable::invokevirtual(int byte_no) {
  transition(vtos, vtos);
  prepare_invoke(ebx, noreg, byte_no, bytecode());

  // ebx: index
  // ecx: receiver    
  // edx: flags    

  invokevirtual_helper(ebx, ecx, edx);
}


void TemplateTable::invokespecial(int byte_no) {
  transition(vtos, vtos);
  prepare_invoke(ebx, noreg, byte_no, bytecode());
  // do the call
  __ verify_oop(ebx);
  __ profile_call(eax);
  __ jump_from_interpreted(ebx, eax);
}


void TemplateTable::invokestatic(int byte_no) {
  transition(vtos, vtos);
  prepare_invoke(ebx, noreg, byte_no, bytecode());
  // do the call
  __ verify_oop(ebx);
  __ profile_call(eax);
  __ jump_from_interpreted(ebx, eax);
}


void TemplateTable::fast_invokevfinal(int byte_no) {
  transition(vtos, vtos);
  __ stop("fast_invokevfinal not used on x86");
}


void TemplateTable::invokeinterface(int byte_no) {
  transition(vtos, vtos);
  prepare_invoke(eax, ebx, byte_no, bytecode());
  
  // eax: Interface
  // ebx: index
  // ecx: receiver    
  // edx: flags

  // Special case of invokeinterface called for virtual method of
  // java.lang.Object.  See cpCacheOop.cpp for details.
  // This code isn't produced by javac, but could be produced by
  // another compliant java compiler.
  Label notMethod;
  __ movl(edi, edx);
  __ andl(edi, (1 << ConstantPoolCacheEntry::methodInterface));
  __ jcc(Assembler::zero, notMethod);

  invokevirtual_helper(ebx, ecx, edx);
  __ bind(notMethod);

  // Get receiver klass into edx - also a null check
  __ restore_locals();  // restore edi
  __ movl(edx, Address(ecx, oopDesc::klass_offset_in_bytes()));
  __ verify_oop(edx);

  // profile this call
  __ profile_virtual_call(edx, esi, edi);

  __ movl(edi, edx); // Save klassOop in edi

  // Compute start of first itableOffsetEntry (which is at the end of the vtable)
  const int base = instanceKlass::vtable_start_offset() * wordSize;    
  assert(vtableEntry::size() * wordSize == 4, "adjust the scaling in the code below");
  __ movl(esi, Address(edx, instanceKlass::vtable_length_offset() * wordSize)); // Get length of vtable
  __ leal(edx, Address(edx, esi, Address::times_4, base));
  if (HeapWordsPerLong > 1) {
    // Round up to align_object_offset boundary
    __ round_to(edx, BytesPerLong);
  }

  Label entry, search, interface_ok;
  
  __ jmpb(entry);   
  __ bind(search);
  __ addl(edx, itableOffsetEntry::size() * wordSize);
  
  __ bind(entry);

  // Check that the entry is non-null.  A null entry means that the receiver
  // class doesn't implement the interface, and wasn't the same as the
  // receiver class checked when the interface was resolved.
  __ pushl(edx);
  __ movl(edx, Address(edx, itableOffsetEntry::interface_offset_in_bytes()));
  __ testl(edx, edx);
  __ jcc(Assembler::notZero, interface_ok);
  // throw exception
  __ popl(edx);          // pop saved register first.
  __ popl(ebx);          // pop return address (pushed by prepare_invoke)
  __ restore_bcp();      // esi must be correct for exception handler   (was destroyed)
  __ restore_locals();   // make sure locals pointer is correct as well (was destroyed)
  __ call_VM(noreg, CAST_FROM_FN_PTR(address,
                   InterpreterRuntime::throw_IncompatibleClassChangeError));
  // the call_VM checks for exception, so we should never return here.
  __ should_not_reach_here();
  __ bind(interface_ok);

    __ popl(edx);

    __ cmpl(eax, Address(edx, itableOffsetEntry::interface_offset_in_bytes()));
    __ jcc(Assembler::notEqual, search);
        
    __ movl(edx, Address(edx, itableOffsetEntry::offset_offset_in_bytes()));      
    __ addl(edx, edi); // Add offset to klassOop
    assert(itableMethodEntry::size() * wordSize == 4, "adjust the scaling in the code below");
    __ movl(ebx, Address(edx, ebx, Address::times_4));
    // ebx: methodOop to call
    // ecx: receiver
    // Check for abstract method error
    // Note: This should be done more efficiently via a throw_abstract_method_error
    //       interpreter entry point and a conditional jump to it in case of a null
    //       method.
    { Label L;
      __ testl(ebx, ebx);
      __ jcc(Assembler::notZero, L);
      // throw exception
	  // note: must restore interpreter registers to canonical
	  //       state for exception handling to work correctly!
	  __ popl(ebx);          // pop return address (pushed by prepare_invoke)
	  __ restore_bcp();      // esi must be correct for exception handler   (was destroyed)
	  __ restore_locals();   // make sure locals pointer is correct as well (was destroyed)
      __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::throw_AbstractMethodError));
      // the call_VM checks for exception, so we should never return here.
      __ should_not_reach_here();
      __ bind(L);
    }

  // do the call
  // ecx: receiver
  // ebx: methodOop
  __ jump_from_interpreted(ebx, edx);
}

//----------------------------------------------------------------------------------------------------
// Allocation

void TemplateTable::_new() {
  transition(vtos, atos);
  __ get_unsigned_2_byte_index_at_bcp(edx, 1);
  Label slow_case;
  Label done;
  Label initialize_header;
  Label initialize_object;  // including clearing the fields
  Label allocate_shared;

  __ get_cpool_and_tags(ecx, eax);
  // get instanceKlass
  __ movl(ecx, Address(ecx, edx, Address::times_4, sizeof(constantPoolOopDesc)));
  __ pushl(ecx);  // save the contexts of klass for initializing the header

  // make sure the class we're about to instantiate has been resolved. 
  // Note: slow_case does a pop of stack, which is why we loaded class/pushed above
  const int tags_offset = typeArrayOopDesc::header_size(T_BYTE) * wordSize;
  __ cmpb(Address(eax, edx, Address::times_1, tags_offset), JVM_CONSTANT_Class);
  __ jcc(Assembler::notEqual, slow_case);

  // make sure klass is initialized & doesn't have finalizer
  // make sure klass is fully initialized
  __ cmpl(Address(ecx, instanceKlass::init_state_offset_in_bytes() + sizeof(oopDesc)), instanceKlass::fully_initialized);
  __ jcc(Assembler::notEqual, slow_case);

  // get instance_size in instanceKlass (scaled to a count of bytes)
  __ movl(edx, Address(ecx, Klass::layout_helper_offset_in_bytes() + sizeof(oopDesc)));
  // test to see if it has a finalizer or is malformed in some way
  __ testl(edx, Klass::_lh_instance_slow_path_bit);
  __ jcc(Assembler::notZero, slow_case);

  // 
  // Allocate the instance
  // 1) Try to allocate in the TLAB
  // 2) if fail and the object is large allocate in the shared Eden
  // 3) if the above fails (or is not applicable), go to a slow case
  // (creates a new TLAB, etc.)

  const bool allow_shared_alloc =
    Universe::heap()->supports_inline_contig_alloc() && !CMSIncrementalMode;

  if (UseTLAB) {
    const Register thread = ecx;

    __ get_thread(thread);
    __ movl(eax, Address(thread, in_bytes(JavaThread::tlab_top_offset())));
    __ leal(ebx, Address(eax, edx, Address::times_1));
    __ cmpl(ebx, Address(thread, in_bytes(JavaThread::tlab_end_offset())));
    __ jcc(Assembler::above, allow_shared_alloc ? allocate_shared : slow_case);
    __ movl(Address(thread, in_bytes(JavaThread::tlab_top_offset())), ebx);
    if (ZeroTLAB) {
      // the fields have been already cleared
      __ jmp(initialize_header);
    } else {
      // initialize both the header and fields
      __ jmp(initialize_object);
    }
  }

  // Allocation in the shared Eden, if allowed.
  //
  // edx: instance size in bytes
  if (allow_shared_alloc) {
    __ bind(allocate_shared);

    Label retry;
    __ bind(retry);
    __ movl(eax, Address((int)Universe::heap()->top_addr(), relocInfo::none));
    __ leal(ebx, Address(eax, edx, Address::times_1));
    __ cmpl(ebx, Address((int)Universe::heap()->end_addr(), relocInfo::none));
    __ jcc(Assembler::above, slow_case);

    // Compare eax with the top addr, and if still equal, store the new
    // top addr in ebx at the address of the top addr pointer. Sets ZF if was
    // equal, and clears it otherwise. Use lock prefix for atomicity on MPs.
    //
    // eax: object begin
    // ebx: object end
    // edx: instance size in bytes
    if (os::is_MP()) __ lock();
    __ cmpxchg(ebx, Address((int)Universe::heap()->top_addr(), relocInfo::none));

    // if someone beat us on the allocation, try again, otherwise continue 
    __ jcc(Assembler::notEqual, retry);
  }

  if (UseTLAB || Universe::heap()->supports_inline_contig_alloc()) {
    // The object is initialized before the header.  If the object size is
    // zero, go directly to the header initialization.
    __ bind(initialize_object);
    __ decrement(edx, sizeof(oopDesc));
    __ jcc(Assembler::zero, initialize_header);

  // Initialize topmost object field, divide edx by 8, check if odd and
  // test if zero.
    __ xorl(ecx, ecx);    // use zero reg to clear memory (shorter code)
    __ shrl(edx, LogBytesPerLong); // divide by 2*oopSize and set carry flag if odd

  // edx must have been multiple of 8
#ifdef ASSERT
    // make sure edx was multiple of 8
    Label L;
    // Ignore partial flag stall after shrl() since it is debug VM
    __ jccb(Assembler::carryClear, L);
    __ stop("object size is not multiple of 2 - adjust this code");
    __ bind(L);
    // edx must be > 0, no extra check needed here
#endif

    // initialize remaining object fields: edx was a multiple of 8
    { Label loop;
    __ bind(loop);
    __ movl(Address(eax, edx, Address::times_8, sizeof(oopDesc) - 1*oopSize), ecx);
    __ movl(Address(eax, edx, Address::times_8, sizeof(oopDesc) - 2*oopSize), ecx);
    __ decrement(edx);
    __ jcc(Assembler::notZero, loop);
    }

    // initialize object header only.
    __ bind(initialize_header);
    if (UseBiasedLocking) {
      __ popl(ecx);   // get saved klass back in the register.
      __ movl(ebx, Address(ecx, Klass::prototype_header_offset_in_bytes() + klassOopDesc::klass_part_offset_in_bytes()));
      __ movl(Address(eax, oopDesc::mark_offset_in_bytes ()), ebx);
    } else {
      __ movl(Address(eax, oopDesc::mark_offset_in_bytes ()),
              (int)markOopDesc::prototype()); // header
      __ popl(ecx);   // get saved klass back in the register.
    }
    __ movl(Address(eax, oopDesc::klass_offset_in_bytes()), ecx);  // klass

    {
      SkipIfEqual skip_if(_masm, &DTraceAllocProbes, 0);
      // Trigger dtrace event for fastpath
      __ push(atos);
      __ call_VM_leaf(
           CAST_FROM_FN_PTR(address, SharedRuntime::dtrace_object_alloc), eax);
      __ pop(atos);
    }

    __ jmp(done);
  }

  // slow case
  __ bind(slow_case);
  __ popl(ecx);   // restore stack pointer to what it was when we came in.
  __ get_constant_pool(eax);
  __ get_unsigned_2_byte_index_at_bcp(edx, 1);
  call_VM(eax, CAST_FROM_FN_PTR(address, InterpreterRuntime::_new), eax, edx);

  // continue
  __ bind(done);
}


void TemplateTable::newarray() {
  transition(itos, atos);
  __ push_i(eax);                                 // make sure everything is on the stack
  __ load_unsigned_byte(edx, at_bcp(1));
  call_VM(eax, CAST_FROM_FN_PTR(address, InterpreterRuntime::newarray), edx, eax);
  __ pop_i(edx);                                  // discard size
}


void TemplateTable::anewarray() {
  transition(itos, atos);
  __ get_unsigned_2_byte_index_at_bcp(edx, 1);
  __ get_constant_pool(ecx);
  call_VM(eax, CAST_FROM_FN_PTR(address, InterpreterRuntime::anewarray), ecx, edx, eax);
}


void TemplateTable::arraylength() {
  transition(atos, itos);
  __ null_check(eax, arrayOopDesc::length_offset_in_bytes());
  __ movl(eax, Address(eax, arrayOopDesc::length_offset_in_bytes()));
}


void TemplateTable::checkcast() {
  transition(atos, atos);
  Label done, is_null, ok_is_subtype, quicked, resolved;
  __ testl(eax, eax);   // Object is in EAX
  __ jcc(Assembler::zero, is_null);

  // Get cpool & tags index
  __ get_cpool_and_tags(ecx, edx); // ECX=cpool, EDX=tags array
  __ get_unsigned_2_byte_index_at_bcp(ebx, 1); // EBX=index
  // See if bytecode has already been quicked
  __ cmpb(Address(edx, ebx, Address::times_1, typeArrayOopDesc::header_size(T_BYTE) * wordSize), JVM_CONSTANT_Class);
  __ jcc(Assembler::equal, quicked);

  __ push(atos);
  call_VM(eax, CAST_FROM_FN_PTR(address, InterpreterRuntime::quicken_io_cc) );
  __ pop_ptr(edx);
  __ jmpb(resolved);

  // Get superklass in EAX and subklass in EBX
  __ bind(quicked);
  __ movl(edx, eax);          // Save object in EDX; EAX needed for subtype check
  __ movl(eax, Address(ecx, ebx, Address::times_4, sizeof(constantPoolOopDesc)));

  __ bind(resolved);
  __ movl(ebx, Address(edx, oopDesc::klass_offset_in_bytes()));

  // Generate subtype check.  Blows ECX.  Resets EDI.  Object in EDX.
  // Superklass in EAX.  Subklass in EBX.
  __ gen_subtype_check( ebx, ok_is_subtype );

  // Come here on failure
  __ pushl(edx);
  // object is at TOS
  __ jmp(Interpreter::_throw_ClassCastException_entry, relocInfo::none);

  // Come here on success
  __ bind(ok_is_subtype);
  __ movl(eax,edx);           // Restore object in EDX

  // Collect counts on whether this check-cast sees NULLs a lot or not.
  if (ProfileInterpreter) {
    __ jmp(done);
    __ bind(is_null);
    __ profile_null_seen(ecx);
  } else {
    __ bind(is_null);   // same as 'done'
  }
  __ bind(done);
}


void TemplateTable::instanceof() {
  transition(atos, itos);
  Label done, is_null, ok_is_subtype, quicked, resolved;
  __ testl(eax, eax);
  __ jcc(Assembler::zero, is_null);

  // Get cpool & tags index
  __ get_cpool_and_tags(ecx, edx); // ECX=cpool, EDX=tags array
  __ get_unsigned_2_byte_index_at_bcp(ebx, 1); // EBX=index
  // See if bytecode has already been quicked
  __ cmpb(Address(edx, ebx, Address::times_1, typeArrayOopDesc::header_size(T_BYTE) * wordSize), JVM_CONSTANT_Class);
  __ jcc(Assembler::equal, quicked);

  __ push(atos);
  call_VM(eax, CAST_FROM_FN_PTR(address, InterpreterRuntime::quicken_io_cc) );
  __ pop_ptr(edx);
  __ movl(edx, Address(edx, oopDesc::klass_offset_in_bytes()));
  __ jmp(resolved);

  // Get superklass in EAX and subklass in EDX
  __ bind(quicked);
  __ movl(edx, Address(eax, oopDesc::klass_offset_in_bytes()));
  __ movl(eax, Address(ecx, ebx, Address::times_4, sizeof(constantPoolOopDesc)));

  __ bind(resolved);

  // Generate subtype check.  Blows ECX.  Resets EDI.
  // Superklass in EAX.  Subklass in EDX.
  __ gen_subtype_check( edx, ok_is_subtype );

  // Come here on failure
  __ xorl(eax,eax);
  __ jmpb(done);
  // Come here on success
  __ bind(ok_is_subtype);
  __ movl(eax, 1);

  // Collect counts on whether this test sees NULLs a lot or not.
  if (ProfileInterpreter) {
    __ jmp(done);
    __ bind(is_null);
    __ profile_null_seen(ecx);
  } else {
    __ bind(is_null);   // same as 'done'
  }
  __ bind(done);
  // eax = 0: obj == NULL or  obj is not an instanceof the specified klass
  // eax = 1: obj != NULL and obj is     an instanceof the specified klass
}


//----------------------------------------------------------------------------------------------------
// Breakpoints
void TemplateTable::_breakpoint() {
  
  // Note: We get here even if we are single stepping..
  // jbug inists on setting breakpoints at every bytecode 
  // even if we are in single step mode.  
 
  transition(vtos, vtos);

  // get the unpatched byte code
  __ get_method(ecx);
  __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::get_original_bytecode_at), ecx, esi);
  __ movl(ebx, eax);

  // post the breakpoint event
  __ get_method(ecx);
  __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::_breakpoint), ecx, esi);

  // complete the execution of original bytecode
  __ dispatch_only_normal(vtos);
} 


//----------------------------------------------------------------------------------------------------
// Exceptions

void TemplateTable::athrow() {
  transition(atos, vtos);
  __ null_check(eax);
  __ jmp(Interpreter::throw_exception_entry(), relocInfo::none);
}


//----------------------------------------------------------------------------------------------------
// Synchronization
//
// Note: monitorenter & exit are symmetric routines; which is reflected
//       in the assembly code structure as well
//
// Stack layout:
//
// [expressions  ] <--- esp               = expression stack top
// ..
// [expressions  ]
// [monitor entry] <--- monitor block top = expression stack bot
// ..
// [monitor entry]
// [frame data   ] <--- monitor block bot
// ...
// [saved ebp    ] <--- ebp


void TemplateTable::monitorenter() {
  transition(atos, vtos);

  // check for NULL object
  __ null_check(eax);

  const Address monitor_block_top(ebp, frame::interpreter_frame_monitor_block_top_offset * wordSize);
  const Address monitor_block_bot(ebp, frame::interpreter_frame_initial_sp_offset        * wordSize);
  const int entry_size =         (     frame::interpreter_frame_monitor_size()           * wordSize);
  Label allocated;

  // initialize entry pointer
  __ xorl(edx, edx);                             // points to free slot or NULL

  // find a free slot in the monitor block (result in edx)
  { Label entry, loop, exit;
    __ movl(ecx, monitor_block_top);             // points to current entry, starting with top-most entry
    __ leal(ebx, monitor_block_bot);             // points to word before bottom of monitor block
    __ jmpb(entry);

    __ bind(loop);
    __ cmpl(Address(ecx, BasicObjectLock::obj_offset_in_bytes()), NULL_WORD);  // check if current entry is used

// TODO - need new func here - kbt
    if (VM_Version::supports_cmov()) {
      __ cmovl(Assembler::equal, edx, ecx);      // if not used then remember entry in edx
    } else {
      Label L;
      __ jccb(Assembler::notEqual, L);
      __ movl(edx, ecx);                         // if not used then remember entry in edx
      __ bind(L);
    }
    __ cmpl(eax, Address(ecx, BasicObjectLock::obj_offset_in_bytes()));   // check if current entry is for same object
    __ jccb(Assembler::equal, exit);              // if same object then stop searching
    __ addl(ecx, entry_size);                    // otherwise advance to next entry
    __ bind(entry);
    __ cmpl(ecx, ebx);                           // check if bottom reached
    __ jcc(Assembler::notEqual, loop);           // if not at bottom then check this entry
    __ bind(exit);
  }

  __ testl(edx, edx);                            // check if a slot has been found
  __ jccb(Assembler::notZero, allocated);         // if found, continue with that one

  // allocate one if there's no free slot
  { Label entry, loop;
    // 1. compute new pointers                   // esp: old expression stack top
    __ movl(edx, monitor_block_bot);             // edx: old expression stack bottom
    __ subl(esp, entry_size);                    // move expression stack top
    __ subl(edx, entry_size);                    // move expression stack bottom
    __ movl(ecx, esp);                           // set start value for copy loop
    __ movl(monitor_block_bot, edx);             // set new monitor block top
    __ jmp(entry);
    // 2. move expression stack contents
    __ bind(loop);
    __ movl(ebx, Address(ecx, entry_size));      // load expression stack word from old location
    __ movl(Address(ecx), ebx);                  // and store it at new location
    __ addl(ecx, wordSize);                      // advance to next word
    __ bind(entry);
    __ cmpl(ecx, edx);                           // check if bottom reached
    __ jcc(Assembler::notEqual, loop);           // if not at bottom then copy next word
  }
  
  // call run-time routine
  // edx: points to monitor entry
  __ bind(allocated);

  // Increment bcp to point to the next bytecode, so exception handling for async. exceptions work correctly. 
  // The object has already been poped from the stack, so the expression stack looks correct.
  __ increment(esi);

  __ movl(Address(edx, BasicObjectLock::obj_offset_in_bytes()), eax);     // store object  
  __ lock_object(edx);  

  // check to make sure this monitor doesn't cause stack overflow after locking
  __ save_bcp();  // in case of exception
  __ generate_stack_overflow_check(0);

  // The bcp has already been incremented. Just need to dispatch to next instruction.
  __ dispatch_next(vtos);
}


void TemplateTable::monitorexit() {
  transition(atos, vtos);

  // check for NULL object
  __ null_check(eax);

  const Address monitor_block_top(ebp, frame::interpreter_frame_monitor_block_top_offset * wordSize);
  const Address monitor_block_bot(ebp, frame::interpreter_frame_initial_sp_offset        * wordSize);
  const int entry_size =         (     frame::interpreter_frame_monitor_size()           * wordSize);
  Label found;

  // find matching slot
  { Label entry, loop;
    __ movl(edx, monitor_block_top);             // points to current entry, starting with top-most entry
    __ leal(ebx, monitor_block_bot);             // points to word before bottom of monitor block
    __ jmpb(entry);

    __ bind(loop);
    __ cmpl(eax, Address(edx, BasicObjectLock::obj_offset_in_bytes()));   // check if current entry is for same object
    __ jcc(Assembler::equal, found);             // if same object then stop searching
    __ addl(edx, entry_size);                    // otherwise advance to next entry
    __ bind(entry);
    __ cmpl(edx, ebx);                           // check if bottom reached
    __ jcc(Assembler::notEqual, loop);           // if not at bottom then check this entry
  }

  // error handling. Unlocking was not block-structured
  Label end;
  __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::throw_illegal_monitor_state_exception));
  __ should_not_reach_here();

  // call run-time routine
  // ecx: points to monitor entry
  __ bind(found);
  __ push_ptr(eax);                                 // make sure object is on stack (contract with oopMaps)  
  __ unlock_object(edx);    
  __ pop_ptr(eax);                                  // discard object  
  __ bind(end);
}


//----------------------------------------------------------------------------------------------------
// Wide instructions

void TemplateTable::wide() {
  transition(vtos, vtos);
  __ load_unsigned_byte(ebx, at_bcp(1));
  __ jmp(Address(noreg, ebx, Address::times_4, int(Interpreter::_wentry_point)));
  // Note: the esi increment step is part of the individual wide bytecode implementations
}


//----------------------------------------------------------------------------------------------------
// Multi arrays

void TemplateTable::multianewarray() {
  transition(vtos, atos);
  __ load_unsigned_byte(eax, at_bcp(3)); // get number of dimensions
  // last dim is on top of stack; we want address of first one:
  // first_addr = last_addr + (ndims - 1) * stackElementSize - 1*wordsize
  // the latter wordSize to point to the beginning of the array.
  __ leal(  eax, Address(esp, eax, Interpreter::stackElementScale(), -wordSize));
  call_VM(eax, CAST_FROM_FN_PTR(address, InterpreterRuntime::multianewarray), eax);     // pass in eax
  __ load_unsigned_byte(ebx, at_bcp(3));
  __ leal(esp, Address(esp, ebx, Interpreter::stackElementScale()));  // get rid of counts
}
