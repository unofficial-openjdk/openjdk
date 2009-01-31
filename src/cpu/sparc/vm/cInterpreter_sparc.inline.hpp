#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)cInterpreter_sparc.inline.hpp	1.9 07/05/05 17:04:27 JVM"
#endif
/*
 * Copyright 2002 Sun Microsystems, Inc.  All Rights Reserved.
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

// Inline interpreter functions for sparc

inline jfloat cInterpreter::VMfloatAdd(jfloat op1, jfloat op2) { return op1 + op2; }
inline jfloat cInterpreter::VMfloatSub(jfloat op1, jfloat op2) { return op1 - op2; }
inline jfloat cInterpreter::VMfloatMul(jfloat op1, jfloat op2) { return op1 * op2; }
inline jfloat cInterpreter::VMfloatDiv(jfloat op1, jfloat op2) { return op1 / op2; }
inline jfloat cInterpreter::VMfloatRem(jfloat op1, jfloat op2) { return fmod(op1, op2); }

inline jfloat cInterpreter::VMfloatNeg(jfloat op) { return -op; }

inline int32_t cInterpreter::VMfloatCompare(jfloat op1, jfloat op2, int32_t direction) {
  return ( op1 < op2 ? -1 : 
	       op1 > op2 ? 1 : 
		   op1 == op2 ? 0 : 
		       (direction == -1 || direction == 1) ? direction : 0);

}

inline void cInterpreter::VMmemCopy64(uint32_t to[2], const uint32_t from[2]) {
  // x86 can do unaligned copies but not 64bits at a time
  to[0] = from[0]; to[1] = from[1];
}

// The long operations depend on compiler support for "long long" on x86

inline jlong cInterpreter::VMlongAdd(jlong op1, jlong op2) {
  return op1 + op2;
}

inline jlong cInterpreter::VMlongAnd(jlong op1, jlong op2) {
  return op1 & op2;
}

inline jlong cInterpreter::VMlongDiv(jlong op1, jlong op2) {
  // QQQ what about check and throw...
  return op1 / op2;
}

inline jlong cInterpreter::VMlongMul(jlong op1, jlong op2) {
  return op1 * op2;
}

inline jlong cInterpreter::VMlongOr(jlong op1, jlong op2) {
  return op1 | op2;
}

inline jlong cInterpreter::VMlongSub(jlong op1, jlong op2) {
  return op1 - op2;
}

inline jlong cInterpreter::VMlongXor(jlong op1, jlong op2) {
  return op1 ^ op2;
}

inline jlong cInterpreter::VMlongRem(jlong op1, jlong op2) {
  return op1 % op2;
}

inline jlong cInterpreter::VMlongUshr(jlong op1, jint op2) {
  // CVM did this 0x3f mask, is the really needed??? QQQ
  return ((unsigned long long) op1) >> (op2 & 0x3F);
}

inline jlong cInterpreter::VMlongShr(jlong op1, jint op2) {
  return op1 >> (op2 & 0x3F);
}

inline jlong cInterpreter::VMlongShl(jlong op1, jint op2) {
  return op1 << (op2 & 0x3F);
}

inline jlong cInterpreter::VMlongNeg(jlong op) {
  return -op;
}

inline jlong cInterpreter::VMlongNot(jlong op) {
  return ~op;
}

inline int32_t cInterpreter::VMlongLtz(jlong op) {
  return (op <= 0);
}

inline int32_t cInterpreter::VMlongGez(jlong op) {
  return (op >= 0);
}

inline int32_t cInterpreter::VMlongEqz(jlong op) {
  return (op == 0);
}

inline int32_t cInterpreter::VMlongEq(jlong op1, jlong op2) {
  return (op1 == op2);
}

inline int32_t cInterpreter::VMlongNe(jlong op1, jlong op2) {
  return (op1 != op2);
}

inline int32_t cInterpreter::VMlongGe(jlong op1, jlong op2) {
  return (op1 >= op2);
}

inline int32_t cInterpreter::VMlongLe(jlong op1, jlong op2) {
  return (op1 <= op2);
}

inline int32_t cInterpreter::VMlongLt(jlong op1, jlong op2) {
  return (op1 < op2);
}

inline int32_t cInterpreter::VMlongGt(jlong op1, jlong op2) {
  return (op1 > op2);
}

inline int32_t cInterpreter::VMlongCompare(jlong op1, jlong op2) {
  return (VMlongLt(op1, op2) ? -1 : VMlongGt(op1, op2) ? 1 : 0);
}

// Long conversions

inline jdouble cInterpreter::VMlong2Double(jlong val) {
  return (jdouble) val;
}

inline jfloat cInterpreter::VMlong2Float(jlong val) {
  return (jfloat) val;
}

inline jint cInterpreter::VMlong2Int(jlong val) {
  return (jint) val;
}

// Double Arithmetic

inline jdouble cInterpreter::VMdoubleAdd(jdouble op1, jdouble op2) {
  return op1 + op2;
}

inline jdouble cInterpreter::VMdoubleDiv(jdouble op1, jdouble op2) {
  // Divide by zero... QQQ
  return op1 / op2;
}

inline jdouble cInterpreter::VMdoubleMul(jdouble op1, jdouble op2) {
  return op1 * op2;
}

inline jdouble cInterpreter::VMdoubleNeg(jdouble op) {
  return -op;
}

inline jdouble cInterpreter::VMdoubleRem(jdouble op1, jdouble op2) {
  return fmod(op1, op2);
}

inline jdouble cInterpreter::VMdoubleSub(jdouble op1, jdouble op2) {
  return op1 - op2;
}

inline int32_t cInterpreter::VMdoubleCompare(jdouble op1, jdouble op2, int32_t direction) {
  return ( op1 < op2 ? -1 : 
	       op1 > op2 ? 1 : 
		   op1 == op2 ? 0 : 
		       (direction == -1 || direction == 1) ? direction : 0);
}

// Double Conversions

inline jfloat cInterpreter::VMdouble2Float(jdouble val) {
  return (jfloat) val;
}

// Float Conversions

inline jdouble cInterpreter::VMfloat2Double(jfloat op) {
  return (jdouble) op;
}

// Integer Arithmetic

inline jint cInterpreter::VMintAdd(jint op1, jint op2) {
  return op1 + op2;
}

inline jint cInterpreter::VMintAnd(jint op1, jint op2) {
  return op1 & op2;
}

inline jint cInterpreter::VMintDiv(jint op1, jint op2) {
  /* it's possible we could catch this special case implicitly */
  if (op1 == 0x80000000 && op2 == -1) return op1;
  else return op1 / op2;
}

inline jint cInterpreter::VMintMul(jint op1, jint op2) {
  return op1 * op2;
}

inline jint cInterpreter::VMintNeg(jint op) {
  return -op;
}

inline jint cInterpreter::VMintOr(jint op1, jint op2) {
  return op1 | op2;
}

inline jint cInterpreter::VMintRem(jint op1, jint op2) {
  /* it's possible we could catch this special case implicitly */
  if (op1 == 0x80000000 && op2 == -1) return 0;
  else return op1 % op2;
}

inline jint cInterpreter::VMintShl(jint op1, jint op2) {
  return op1 <<  op2;
}

inline jint cInterpreter::VMintShr(jint op1, jint op2) {
  return op1 >>  op2; // QQ op2 & 0x1f??
}

inline jint cInterpreter::VMintSub(jint op1, jint op2) {
  return op1 - op2;
}

inline jint cInterpreter::VMintUshr(jint op1, jint op2) {
  return ((juint) op1) >> op2; // QQ op2 & 0x1f??
}

inline jint cInterpreter::VMintXor(jint op1, jint op2) {
  return op1 ^ op2;
}

inline jdouble cInterpreter::VMint2Double(jint val) {
  return (jdouble) val;
}

inline jfloat cInterpreter::VMint2Float(jint val) {
  return (jfloat) val;
}

inline jlong cInterpreter::VMint2Long(jint val) {
  return (jlong) val;
}

inline jchar cInterpreter::VMint2Char(jint val) {
  return (jchar) val;
}

inline jshort cInterpreter::VMint2Short(jint val) {
  return (jshort) val;
}

inline jbyte cInterpreter::VMint2Byte(jint val) {
  return (jbyte) val;
}

// The implementations are platform dependent. We have to worry about alignment
// issues on some machines which can change on the same platform depending on
// whether it is an LP64 machine also.

// We know that on LP32 mode that longs/doubles are the only thing that gives
// us alignment headaches. We also know that the worst we have is 32bit alignment
// so thing are not really too bad.
// (Also sparcworks compiler does the right thing for free if we don't use -arch..
// switches. Only gcc gives us a hard time. In LP64 mode I think we have no issue
// with alignment.

#ifdef _GNU_SOURCE
  #define ALIGN_CONVERTER        /* Needs alignment converter */
#else
  #undef ALIGN_CONVERTER        /* No alignment converter */
#endif /* _GNU_SOURCE */

#ifdef ALIGN_CONVERTER
class u8_converter {

  private:

  public:
  static jdouble get_jdouble(address p) {
    VMJavaVal64 tmp;
    tmp.v[0] = ((uint32_t*)p)[0];
    tmp.v[1] = ((uint32_t*)p)[1];
    return tmp.d;
  }

  static void put_jdouble(address p, jdouble d) {
    VMJavaVal64 tmp;
    tmp.d = d;
    ((uint32_t*)p)[0] = tmp.v[0];
    ((uint32_t*)p)[1] = tmp.v[1];
  }

  static jlong get_jlong(address p) {
    VMJavaVal64 tmp;
    tmp.v[0] = ((uint32_t*)p)[0];
    tmp.v[1] = ((uint32_t*)p)[1];
    return tmp.l;
  }

  static void put_jlong(address p, jlong l) {
    VMJavaVal64 tmp;
    tmp.l = l;
    ((uint32_t*)p)[0] = tmp.v[0];
    ((uint32_t*)p)[1] = tmp.v[1];
  }
};
#endif /* ALIGN_CONVERTER */

// SLOTS
inline jdouble JavaSlot::Double(address p) { 
#ifdef ALIGN_CONVERTER
  return u8_converter::get_jdouble(p);
#else
  return ((VMJavaVal64*)p)->d;
#endif /* ALIGN_CONVERTER */
}
inline jint JavaSlot::Int(address p) { return *(jint*)p ; }
inline jfloat JavaSlot::Float(address p) { return *(float*) p; }
inline jlong JavaSlot::Long(address p) { 
#ifdef ALIGN_CONVERTER
  return u8_converter::get_jlong(p);
#else
  return ((VMJavaVal64*)p)->l; 
#endif /* ALIGN_CONVERTER */
}

// STACK_CELL
inline oop JavaSlot::Object(address p) { VERIFY_OOP(*(oop*)p ) ; return *(oop*) p; }
inline address JavaSlot::Address(address p) { return *(address *) p; }
inline intptr_t JavaSlot::Raw(address p) { return *(intptr_t*) p; }

// For copying an internal vm representation to a slot
void JavaSlot::set_Address(address value, address p) { *(address*)p = value; }
void JavaSlot::set_Int(jint value, address p) { *(jint*)p = value; }
void JavaSlot::set_Float(jfloat value, address p) { *(jfloat *)p = value; }
void JavaSlot::set_Object(oop value, address p) { VERIFY_OOP(value); *(oop *)p = value; }

// For copying a slot representation to another slot
void JavaSlot::set_Raw(address value, address p) { *(intptr_t*)p = *(intptr_t*)value; }
void JavaSlot::set_Double(address value, address p) { 
#ifdef ALIGN_CONVERTER
  Bytes::put_native_u8(p, Bytes::get_native_u8(value));
#else
  ((VMJavaVal64*)p)->d = ((VMJavaVal64*)value)->d; // Wrong for LP64
#endif /* ALIGN_CONVERTER */
}

void JavaSlot::set_Long(address value, address p) { 
#ifdef ALIGN_CONVERTER
  Bytes::put_native_u8(p, Bytes::get_native_u8(value));
#else
  ((VMJavaVal64*)p)->l = ((VMJavaVal64*)value)->l; 
#endif /* ALIGN_CONVERTER */
}

// LOCALS
// sparc implementation - locals is array on the stack with indices going from 0..-(locals-1)
// because the locals are actually overlayed on the parameters to the call on the
// expression stack which also grows down. Strange but true...
//
inline jdouble JavaLocals::Double(int slot) { 
#ifdef ALIGN_CONVERTER
  return u8_converter::get_jdouble((address)&_base[-(slot + 1)]);
#else
  return ((VMJavaVal64*) &_base[-(slot + 1)])->d; 
#endif /* ALIGN_CONVERTER */
}
inline jint JavaLocals::Int(int slot) { return _base[-slot]; }
inline jfloat JavaLocals::Float(int slot) { return *((jfloat*) &_base[-slot]); }
inline jlong JavaLocals::Long(int slot) { 
#ifdef ALIGN_CONVERTER
  return u8_converter::get_jlong((address)&_base[-(slot + 1)]);
#else
  return ((VMJavaVal64*) &_base[-(slot + 1)])->l; 
#endif /* ALIGN_CONVERTER */
}

inline oop JavaLocals::Object(int slot) { VERIFY_OOP((oop)_base[-slot]); return (oop) _base[-slot]; }
inline address JavaLocals::Address(int slot) { return (address) _base[-slot]; }
inline intptr_t JavaLocals::Raw(int slot) { return (intptr_t) _base[-slot]; }

// For copying an internal vm representation to a slot
inline void JavaLocals::set_Address(address value, int slot) { *((address *) &_base[-slot]) = value; }
inline void JavaLocals::set_Int(jint value, int slot) { *((jint *) &_base[-slot]) = value; }
inline void JavaLocals::set_Float(jfloat value, int slot) { *((jfloat *) &_base[-slot]) = value; }
inline void JavaLocals::set_Object(oop value, int slot) { VERIFY_OOP(value); *((oop *) &_base[-slot]) = value; }
inline void JavaLocals::set_Double(jdouble value, int slot) { 
#ifdef ALIGN_CONVERTER
  // too slow
  Bytes::put_native_u8((address)&_base[-(slot+1)], Bytes::get_native_u8((address) &value));
#else
  ((VMJavaVal64*) &_base[-(slot+1)])->d = value; 
#endif /* ALIGN_CONVERTER */
}
inline void JavaLocals::set_Long(jlong value, int slot) { 
#ifdef ALIGN_CONVERTER
  // too slow
  Bytes::put_native_u8((address)&_base[-(slot+1)], Bytes::get_native_u8((address) &value));
#else
  ((VMJavaVal64*) &_base[-(slot+1)])->l = value; 
#endif /* ALIGN_CONVERTER */
}

// For copying a slot representation to another slot
inline void JavaLocals::set_Raw(address value, int slot) { *(intptr_t*)&_base[-slot] = *(intptr_t *)value; }
inline void JavaLocals::set_Double(address value, int slot) { 
#ifdef ALIGN_CONVERTER
  // too slow
  Bytes::put_native_u8((address)&_base[-(slot+1)], Bytes::get_native_u8(value));
#else
  ((VMJavaVal64 *)&_base[-(slot+1)])->d = ((VMJavaVal64*)value)->d; 
#endif /* ALIGN_CONVERTER */
}

inline void JavaLocals::set_Long(address value, int slot) { 
#ifdef ALIGN_CONVERTER
  // too slow
  Bytes::put_native_u8((address)&_base[-(slot+1)], Bytes::get_native_u8(value));
#else
  ((VMJavaVal64 *)&_base[-(slot+1)])->l = ((VMJavaVal64 *)value)->l; 
#endif /* ALIGN_CONVERTER */
}

// Return the address of the slot representation
inline address JavaLocals::Double_At(int slot) { return (address) &_base[-(slot+1)]; }
inline address JavaLocals::Long_At(int slot) { return (address) &_base[-(slot+1)]; }
inline address JavaLocals::Raw_At(int slot) { return (address) &_base[-slot]; }

inline void JavaLocals::Locals(intptr_t* _new_base) { _base = _new_base; }
inline intptr_t* JavaLocals::base(void) { return _base; }
inline intptr_t** JavaLocals::base_addr(void) { return &_base; }

// STACK
// QQQ seems like int/float might have issues with raw implemenation...

inline jdouble JavaStack::Double(int offset) { 
#ifdef ALIGN_CONVERTER
  return u8_converter::get_jdouble((address)&_tos[-offset]);
#else
  return ((VMJavaVal64*) &_tos[-offset])->d; 
#endif /* ALIGN_CONVERTER */
}
inline jint JavaStack::Int(int offset) { return *((jint*) &_tos[-offset]); }
inline jfloat JavaStack::Float(int offset) { return *((jfloat *) &_tos[-offset]); }
inline jlong JavaStack::Long(int offset) { 
#ifdef ALIGN_CONVERTER
  return u8_converter::get_jlong((address)&_tos[-offset]);
#else
  return ((VMJavaVal64 *) &_tos[-offset])->l; 
#endif /* ALIGN_CONVERTER */
}

inline oop JavaStack::Object(int offset) { VERIFY_OOP(*(oop *) &_tos[-offset]); return *((oop *) &_tos[-offset]); }
inline address JavaStack::Address(int offset) { return *((address *) &_tos[-offset]); }
inline intptr_t JavaStack::Raw(int offset) { return *((intptr_t*) &_tos[-offset]); }

// For copying an internal vm representation to a slot
inline void JavaStack::set_Address(address value, int offset) { *((address *)&_tos[-offset]) = value; }
inline void JavaStack::set_Int(jint value, int offset) { *((jint *)&_tos[-offset]) = value; }
inline void JavaStack::set_Float(jfloat value, int offset) { *((jfloat *)&_tos[-offset]) = value; }
inline void JavaStack::set_Object(oop value, int offset) { VERIFY_OOP(value); *((oop *)&_tos[-offset]) = value; }
inline void JavaStack::set_Double(jdouble value, int offset) { 
#ifdef ALIGN_CONVERTER
  // too slow
  Bytes::put_native_u8((address)&_tos[-offset], Bytes::get_native_u8((address) &value));
#else
  ((VMJavaVal64*)&_tos[-offset])->d = value; 
#endif /* ALIGN_CONVERTER */
}
inline void JavaStack::set_Long(jlong value, int offset) { 
#ifdef ALIGN_CONVERTER
  Bytes::put_native_u8((address)&_tos[-offset], Bytes::get_native_u8((address) &value));
#else
  ((VMJavaVal64*)&_tos[-offset])->l = value; 
#endif /* ALIGN_CONVERTER */
}

// For copying a slot representation to a stack location (offset)
inline void JavaStack::set_Raw(address value, int offset) { *(intptr_t*)&_tos[-offset] = *(intptr_t*)value; }
inline void JavaStack::set_Double(address value, int offset) { 
#ifdef ALIGN_CONVERTER
  Bytes::put_native_u8((address)&_tos[-offset], Bytes::get_native_u8(value));
#else
  ((VMJavaVal64*)&_tos[-offset])->d = ((VMJavaVal64*)value)->d;
#endif /* ALIGN_CONVERTER */
}
inline void JavaStack::set_Long(address value, int offset) {
#ifdef ALIGN_CONVERTER
  Bytes::put_native_u8((address)&_tos[-offset], Bytes::get_native_u8(value));
#else
  ((VMJavaVal64*)&_tos[-offset])->l = ((VMJavaVal64*)value)->l;
#endif /* ALIGN_CONVERTER */
}

// Return the address of the slot representation
inline address JavaStack::Double_At(int offset) { return (address) &_tos[-offset]; }
inline address JavaStack::Long_At(int offset) { return (address) &_tos[-offset]; }
inline address JavaStack::Raw_At(int offset) { return (address) &_tos[-offset]; }

// Stack grows down
inline void JavaStack::Pop(int count) { _tos +=count; }
inline void JavaStack::Push(int count) { _tos -= count; }
inline void JavaStack::Adjust(int count) { 
  // conceptual stack says count negative -> pop, count positive -> push
  // since stack grows down we reverse the sense
  _tos -= count; 
}

// inline void JavaStack::Tos(intptr_t* new_tos) { _tos = new_tos; }
inline void JavaStack::Reset(intptr_t* base) { _tos = base - 1; } // prepush. We don't like the knowledge leak here. QQQ
inline intptr_t* JavaStack::get_Tos() { return _tos; }
inline intptr_t* JavaStack::top() { return _tos + 1; }

