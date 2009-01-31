#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)cInterpreter.cpp	1.30 07/05/17 15:54:05 JVM"
#endif
/*
 * Copyright 2002-2007 Sun Microsystems, Inc.  All Rights Reserved.
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

/*
 * Note:
 * In order to eliminate the overhead of testing JVMTI flags
 * during non debuging execution, we generate two version of the Interpreter.
 * The first one is generated via the dependency in the includeDB mechanism 
 * and is read in as part of the _cInterpreter.cpp.incl line below.
 *
 * The second and JVMTI enabled interpreter is brought in below after
 * the line defining VM_JVMTI to 1.
 * 
 * On startup, the assembly generated to enter the Interpreter will be
 * pointed at either InterpretMethod or InterpretMethodWithChecks depending
 * on the state of the JVMTI flags..
 */
#undef VM_JVMTI

#include "incls/_precompiled.incl"
#include "incls/_cInterpreter.cpp.incl"

#ifdef CC_INTERP


#define VM_JVMTI 1

// Build the Interpreter that is used if JVMTI is enabled
#include "cInterpretMethod.hpp"

// This constructor should only be used to contruct the object to signal
// interpreter initialization. All other instances should be created by
// the frame manager.
cInterpreter::cInterpreter(messages msg) {
  if (msg != initialize) ShouldNotReachHere(); 
  _msg = msg; 
  _self_link = this;
  _prev_link = NULL;
}

// Dummy function so we can determine if a pc is within the interpreter.
// This is really a hack. Seems like adding state to thread ala last_Java_sp, etc.
// would be cleaner.
//
void cInterpreter::End_Of_Interpreter(void) {
}

// Inline static functions for Java Stack and Local manipulation

// The implementations are platform dependent. We have to worry about alignment
// issues on some machines which can change on the same platform depending on
// whether it is an LP64 machine also.
#ifdef ASSERT
void cInterpreter::verify_stack_tag(intptr_t *tos, frame::Tag tag, int offset) {
  if (TaggedStackInterpreter) {
    frame::Tag t = (frame::Tag)tos[Interpreter::expr_tag_index_at(-offset)];
    assert(t == tag, "stack tag mismatch");
  }
}
#endif // ASSERT

address cInterpreter::stack_slot(intptr_t *tos, int offset) {
  debug_only(verify_stack_tag(tos, frame::TagValue, offset));
  return (address) tos[Interpreter::expr_index_at(-offset)];
}

jint cInterpreter::stack_int(intptr_t *tos, int offset) {
  debug_only(verify_stack_tag(tos, frame::TagValue, offset));
  return *((jint*) &tos[Interpreter::expr_index_at(-offset)]);
}

jfloat cInterpreter::stack_float(intptr_t *tos, int offset) {
  debug_only(verify_stack_tag(tos, frame::TagValue, offset));
  return *((jfloat *) &tos[Interpreter::expr_index_at(-offset)]);
}

oop cInterpreter::stack_object(intptr_t *tos, int offset) {
  debug_only(verify_stack_tag(tos, frame::TagReference, offset));
  return (oop)tos [Interpreter::expr_index_at(-offset)];
}

jdouble cInterpreter::stack_double(intptr_t *tos, int offset) {
  debug_only(verify_stack_tag(tos, frame::TagValue, offset));
  debug_only(verify_stack_tag(tos, frame::TagValue, offset-1));
  return ((VMJavaVal64*) &tos[Interpreter::expr_index_at(-offset)])->d;
}

jlong cInterpreter::stack_long(intptr_t *tos, int offset) {
  debug_only(verify_stack_tag(tos, frame::TagValue, offset));
  debug_only(verify_stack_tag(tos, frame::TagValue, offset-1));
  return ((VMJavaVal64 *) &tos[Interpreter::expr_index_at(-offset)])->l;
}

void cInterpreter::tag_stack(intptr_t *tos, frame::Tag tag, int offset) {
  if (TaggedStackInterpreter)
    tos[Interpreter::expr_tag_index_at(-offset)] = (intptr_t)tag;
}

// only used for value types
void cInterpreter::set_stack_slot(intptr_t *tos, address value,
                                                        int offset) {
  tag_stack(tos, frame::TagValue, offset);
  *((address *)&tos[Interpreter::expr_index_at(-offset)]) = value;
}

void cInterpreter::set_stack_int(intptr_t *tos, int value, 
                                                       int offset) {
  tag_stack(tos, frame::TagValue, offset);
  *((jint *)&tos[Interpreter::expr_index_at(-offset)]) = value;
}

void cInterpreter::set_stack_float(intptr_t *tos, jfloat value, 
                                                         int offset) {
  tag_stack(tos, frame::TagValue, offset);
  *((jfloat *)&tos[Interpreter::expr_index_at(-offset)]) = value;
}

void cInterpreter::set_stack_object(intptr_t *tos, oop value, 
                                                          int offset) {
  tag_stack(tos, frame::TagReference, offset);
  *((oop *)&tos[Interpreter::expr_index_at(-offset)]) = value;
}

// needs to be platform dep for the 32 bit platforms.
void cInterpreter::set_stack_double(intptr_t *tos, jdouble value, 
                                                          int offset) {
  tag_stack(tos, frame::TagValue, offset);
  tag_stack(tos, frame::TagValue, offset-1);
  ((VMJavaVal64*)&tos[Interpreter::expr_index_at(-offset)])->d = value;
}

void cInterpreter::set_stack_double_from_addr(intptr_t *tos,
                                              address addr, int offset) {
  tag_stack(tos, frame::TagValue, offset);
  tag_stack(tos, frame::TagValue, offset-1);
  (((VMJavaVal64*)&tos[Interpreter::expr_index_at(-offset)])->d =
                        ((VMJavaVal64*)addr)->d);
}

void cInterpreter::set_stack_long(intptr_t *tos, jlong value, 
                                                        int offset) {
  tag_stack(tos, frame::TagValue, offset);
  ((VMJavaVal64*)&tos[Interpreter::expr_index_at(-offset+1)])->l = 0xdeedbeeb;
  tag_stack(tos, frame::TagValue, offset-1);
  ((VMJavaVal64*)&tos[Interpreter::expr_index_at(-offset)])->l = value;
}

void cInterpreter::set_stack_long_from_addr(intptr_t *tos, 
                                            address addr, int offset) {
  tag_stack(tos, frame::TagValue, offset);
  ((VMJavaVal64*)&tos[Interpreter::expr_index_at(-offset+1)])->l = 0xdeedbeeb;
  tag_stack(tos, frame::TagValue, offset-1);
  ((VMJavaVal64*)&tos[Interpreter::expr_index_at(-offset)])->l =
                        ((VMJavaVal64*)addr)->l;
}

// Locals

#ifdef ASSERT
void cInterpreter::verify_locals_tag(intptr_t *locals, frame::Tag tag,
                                     int offset) {
  if (TaggedStackInterpreter) {
    frame::Tag t = (frame::Tag)locals[Interpreter::local_tag_index_at(-offset)];
    assert(t == tag, "locals tag mismatch");
  }
}
#endif // ASSERT
address cInterpreter::locals_slot(intptr_t* locals, int offset) {
  debug_only(verify_locals_tag(locals, frame::TagValue, offset));
  return (address)locals[Interpreter::local_index_at(-offset)];
}
jint cInterpreter::locals_int(intptr_t* locals, int offset) {
  debug_only(verify_locals_tag(locals, frame::TagValue, offset));
  return (jint)locals[Interpreter::local_index_at(-offset)];
}
jfloat cInterpreter::locals_float(intptr_t* locals, int offset) {
  debug_only(verify_locals_tag(locals, frame::TagValue, offset));
  return (jfloat)locals[Interpreter::local_index_at(-offset)];
}
oop cInterpreter::locals_object(intptr_t* locals, int offset) {
  debug_only(verify_locals_tag(locals, frame::TagReference, offset));
  return (oop)locals[Interpreter::local_index_at(-offset)];
}
jdouble cInterpreter::locals_double(intptr_t* locals, int offset) {
  debug_only(verify_locals_tag(locals, frame::TagValue, offset));
  debug_only(verify_locals_tag(locals, frame::TagValue, offset));
  return ((VMJavaVal64*)&locals[Interpreter::local_index_at(-(offset+1))])->d;
}
jlong cInterpreter::locals_long(intptr_t* locals, int offset) {
  debug_only(verify_locals_tag(locals, frame::TagValue, offset));
  debug_only(verify_locals_tag(locals, frame::TagValue, offset+1));
  return ((VMJavaVal64*)&locals[Interpreter::local_index_at(-(offset+1))])->l;
}

// Returns the address of locals value.
address cInterpreter::locals_long_at(intptr_t* locals, int offset) {
  debug_only(verify_locals_tag(locals, frame::TagValue, offset));
  debug_only(verify_locals_tag(locals, frame::TagValue, offset+1));
  return ((address)&locals[Interpreter::local_index_at(-(offset+1))]);
}
address cInterpreter::locals_double_at(intptr_t* locals, int offset) {
  debug_only(verify_locals_tag(locals, frame::TagValue, offset));
  debug_only(verify_locals_tag(locals, frame::TagValue, offset+1));
  return ((address)&locals[Interpreter::local_index_at(-(offset+1))]);
}

void cInterpreter::tag_locals(intptr_t *locals, frame::Tag tag, int offset) {
  if (TaggedStackInterpreter)
    locals[Interpreter::local_tag_index_at(-offset)] = (intptr_t)tag;
}

// Used for local value or returnAddress
void cInterpreter::set_locals_slot(intptr_t *locals,
                                   address value, int offset) {
  tag_locals(locals, frame::TagValue, offset);
  *((address*)&locals[Interpreter::local_index_at(-offset)]) = value;
}
void cInterpreter::set_locals_int(intptr_t *locals,
                                   jint value, int offset) {
  tag_locals(locals, frame::TagValue, offset);
  *((jint *)&locals[Interpreter::local_index_at(-offset)]) = value;
}
void cInterpreter::set_locals_float(intptr_t *locals,
                                   jfloat value, int offset) {
  tag_locals(locals, frame::TagValue, offset);
  *((jfloat *)&locals[Interpreter::local_index_at(-offset)]) = value;
}
void cInterpreter::set_locals_object(intptr_t *locals,
                                   oop value, int offset) {
  tag_locals(locals, frame::TagReference, offset);
  *((oop *)&locals[Interpreter::local_index_at(-offset)]) = value;
}
void cInterpreter::set_locals_double(intptr_t *locals,
                                   jdouble value, int offset) {
  tag_locals(locals, frame::TagValue, offset);
  tag_locals(locals, frame::TagValue, offset+1);
  ((VMJavaVal64*)&locals[Interpreter::local_index_at(-(offset+1))])->d = value;
}
void cInterpreter::set_locals_long(intptr_t *locals,
                                   jlong value, int offset) {
  tag_locals(locals, frame::TagValue, offset);
  tag_locals(locals, frame::TagValue, offset+1);
  ((VMJavaVal64*)&locals[Interpreter::local_index_at(-(offset+1))])->l = value;
}
void cInterpreter::set_locals_double_from_addr(intptr_t *locals,
                                   address addr, int offset) {
  tag_locals(locals, frame::TagValue, offset);
  tag_locals(locals, frame::TagValue, offset+1);
  ((VMJavaVal64*)&locals[Interpreter::local_index_at(-(offset+1))])->d = ((VMJavaVal64*)addr)->d;
}
void cInterpreter::set_locals_long_from_addr(intptr_t *locals,
                                   address addr, int offset) {
  tag_locals(locals, frame::TagValue, offset);
  tag_locals(locals, frame::TagValue, offset+1);
  ((VMJavaVal64*)&locals[Interpreter::local_index_at(-(offset+1))])->l = ((VMJavaVal64*)addr)->l;
}

void cInterpreter::astore(intptr_t* tos,    int stack_offset,
                          intptr_t* locals, int locals_offset) {
  // Copy tag from stack to locals.  astore's operand can be returnAddress
  // and may not be TagReference
  if (TaggedStackInterpreter) {
    frame::Tag t = (frame::Tag) tos[Interpreter::expr_tag_index_at(-stack_offset)];
    locals[Interpreter::local_tag_index_at(-locals_offset)] = (intptr_t)t;
  }
  intptr_t value = tos[Interpreter::expr_index_at(-stack_offset)];
  locals[Interpreter::local_index_at(-locals_offset)] = value;
}


void cInterpreter::copy_stack_slot(intptr_t *tos, int from_offset,
                                   int to_offset) {
  if (TaggedStackInterpreter) {
    tos[Interpreter::expr_tag_index_at(-to_offset)] =
                      (intptr_t)tos[Interpreter::expr_tag_index_at(-from_offset)];
  }
  tos[Interpreter::expr_index_at(-to_offset)] =
                      (intptr_t)tos[Interpreter::expr_index_at(-from_offset)];
}

void cInterpreter::dup(intptr_t *tos) {
  copy_stack_slot(tos, -1, 0);
}
void cInterpreter::dup2(intptr_t *tos) {
  copy_stack_slot(tos, -2, 0);
  copy_stack_slot(tos, -1, 1);
}

void cInterpreter::dup_x1(intptr_t *tos) {
  /* insert top word two down */
  copy_stack_slot(tos, -1, 0);
  copy_stack_slot(tos, -2, -1);
  copy_stack_slot(tos, 0, -2);
}

void cInterpreter::dup_x2(intptr_t *tos) {
  /* insert top word three down  */
  copy_stack_slot(tos, -1, 0);
  copy_stack_slot(tos, -2, -1);
  copy_stack_slot(tos, -3, -2);
  copy_stack_slot(tos, 0, -3);
}
void cInterpreter::dup2_x1(intptr_t *tos) {
  /* insert top 2 slots three down */
  copy_stack_slot(tos, -1, 1);
  copy_stack_slot(tos, -2, 0);
  copy_stack_slot(tos, -3, -1);
  copy_stack_slot(tos, 1, -2);
  copy_stack_slot(tos, 0, -3);
}
void cInterpreter::dup2_x2(intptr_t *tos) {
  /* insert top 2 slots four down */
  copy_stack_slot(tos, -1, 1);
  copy_stack_slot(tos, -2, 0);
  copy_stack_slot(tos, -3, -1);
  copy_stack_slot(tos, -4, -2);
  copy_stack_slot(tos, 1, -3);
  copy_stack_slot(tos, 0, -4);
}


void cInterpreter::swap(intptr_t *tos) {
  // swap top two elements
  intptr_t val = tos[Interpreter::expr_index_at(1)];
  frame::Tag t;
  if (TaggedStackInterpreter) {
    t = (frame::Tag) tos[Interpreter::expr_tag_index_at(1)];
  }
  // Copy -2 entry to -1
  copy_stack_slot(tos, -2, -1);
  // Store saved -1 entry into -2
  tos[Interpreter::expr_tag_index_at(2)] = (intptr_t)t;
  tos[Interpreter::expr_index_at(2)] = val;
}
#endif
