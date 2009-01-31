#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)interpreter_i486.cpp	1.373 07/06/28 16:50:05 JVM"
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
#include "incls/_interpreter_i486.cpp.incl"

#define __ _masm->

// Initialize the sentinel used to distinguish an interpreter return address.
const int Interpreter::return_sentinel = 0xfeedbeed;

const int method_offset = frame::interpreter_frame_method_offset * wordSize;
const int bci_offset    = frame::interpreter_frame_bcx_offset    * wordSize;
const int locals_offset = frame::interpreter_frame_locals_offset * wordSize;

//------------------------------------------------------------------------------------------------------------------------

address AbstractInterpreterGenerator::generate_StackOverflowError_handler() {
  address entry = __ pc();

  // Note: There should be a minimal interpreter frame set up when stack
  // overflow occurs since we check explicitly for it now.
  // 
#ifdef ASSERT
  { Label L;
    __ leal(eax, Address(ebp,
                frame::interpreter_frame_monitor_block_top_offset * wordSize));
    __ cmpl(eax, esp);  // eax = maximal esp for current ebp
                        //  (stack grows negative)
    __ jcc(Assembler::aboveEqual, L); // check if frame is complete
    __ stop ("interpreter frame not set up");
    __ bind(L);
  }
#endif // ASSERT
  // Restore bcp under the assumption that the current frame is still 
  // interpreted
  __ restore_bcp();

  // expression stack must be empty before entering the VM if an exception
  // happened
  __ empty_expression_stack();
  __ empty_FPU_stack();
  // throw exception
  __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::throw_StackOverflowError));
  return entry;
}

address AbstractInterpreterGenerator::generate_ArrayIndexOutOfBounds_handler(const char* name) {
  address entry = __ pc();
  // expression stack must be empty before entering the VM if an exception happened
  __ empty_expression_stack();
  __ empty_FPU_stack();
  // setup parameters
  // ??? convention: expect aberrant index in register ebx
  __ movl(eax, (int)name);
  __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::throw_ArrayIndexOutOfBoundsException), eax, ebx);
  return entry;
}

address AbstractInterpreterGenerator::generate_ClassCastException_handler() {
  address entry = __ pc();
  // object is at TOS
  __ popl(eax);
  // expression stack must be empty before entering the VM if an exception
  // happened
  __ empty_expression_stack();
  __ empty_FPU_stack();
  __ call_VM(noreg, 
             CAST_FROM_FN_PTR(address, 
                              InterpreterRuntime::throw_ClassCastException), 
             eax);
  return entry;
}

address AbstractInterpreterGenerator::generate_exception_handler_common(const char* name, const char* message, bool pass_oop) {
  assert(!pass_oop || message == NULL, "either oop or message but not both");
  address entry = __ pc();
  if (pass_oop) {
    // object is at TOS
    __ popl(ebx);
  }
  // expression stack must be empty before entering the VM if an exception happened
  __ empty_expression_stack();
  __ empty_FPU_stack();
  // setup parameters
  __ movl(eax, (int)name);
  if (pass_oop) {
    __ call_VM(eax, CAST_FROM_FN_PTR(address, InterpreterRuntime::create_klass_exception), eax, ebx);
  } else {
    __ movl(ebx, (int)message);
    __ call_VM(eax, CAST_FROM_FN_PTR(address, InterpreterRuntime::create_exception), eax, ebx);
  }
  // throw exception
  __ jmp(Interpreter::throw_exception_entry(), relocInfo::none);
  return entry;
}


address AbstractInterpreterGenerator::generate_continuation_for(TosState state) {
  address entry = __ pc();
  // NULL last_sp until next java call
  __ movl(Address(ebp, frame::interpreter_frame_last_sp_offset * wordSize), NULL_WORD);
  __ dispatch_next(state);
  return entry;
}


address AbstractInterpreterGenerator::generate_return_entry_for(TosState state, int step) {
  Label interpreter_entry;
  address compiled_entry = __ pc();

#ifdef COMPILER2
  // The FPU stack is clean if UseSSE >= 2 but must be cleaned in other cases
  if ((state == ftos && UseSSE < 1) || (state == dtos && UseSSE < 2)) {
    for (int i = 1; i < 8; i++) {
	__ ffree(i);
    }
  } else if (UseSSE < 2) {
    __ empty_FPU_stack();
  }
#endif
  if ((state == ftos && UseSSE < 1) || (state == dtos && UseSSE < 2)) {
    __ MacroAssembler::verify_FPU(1, "generate_return_entry_for compiled");
  } else {
    __ MacroAssembler::verify_FPU(0, "generate_return_entry_for compiled");
  }

  __ jmp(interpreter_entry, relocInfo::none);
  // emit a sentinel we can test for when converting an interpreter
  // entry point to a compiled entry point.
  __ a_long(Interpreter::return_sentinel);
  __ a_long((int)compiled_entry);
  address entry = __ pc();
  __ bind(interpreter_entry);

  // In SSE mode, interpreter returns FP results in xmm0 but they need
  // to end up back on the FPU so it can operate on them.
  if (state == ftos && UseSSE >= 1) {
    __ subl(esp, wordSize);
    __ movflt(Address(esp, 0), xmm0);
    __ fld_s(Address(esp, 0));
    __ addl(esp, wordSize);
  } else if (state == dtos && UseSSE >= 2) {
    __ subl(esp, 2*wordSize);
    __ movdbl(Address(esp, 0), xmm0);
    __ fld_d(Address(esp, 0));
    __ addl(esp, 2*wordSize);
  }

  __ MacroAssembler::verify_FPU(state == ftos || state == dtos ? 1 : 0, "generate_return_entry_for in interpreter");

  // Restore stack bottom in case i2c adjusted stack
  __ movl(esp, Address(ebp, frame::interpreter_frame_last_sp_offset * wordSize));
  // and NULL it as marker that esp is now tos until next java call
  __ movl(Address(ebp, frame::interpreter_frame_last_sp_offset * wordSize), NULL_WORD);

  __ restore_bcp();
  __ restore_locals();
  __ get_cache_and_index_at_bcp(ebx, ecx, 1);
  __ movl(ebx, Address(ebx, ecx, 
		    Address::times_4, constantPoolCacheOopDesc::base_offset() +
                    ConstantPoolCacheEntry::flags_offset()));
  __ andl(ebx, 0xFF);
  __ leal(esp, Address(esp, ebx, Interpreter::stackElementScale()));
  __ dispatch_next(state, step);
  return entry;
}


address AbstractInterpreterGenerator::generate_deopt_entry_for(TosState state, int step) {
  address entry = __ pc();

  // In SSE mode, FP results are in xmm0
  if (state == ftos && UseSSE > 0) {
    __ subl(esp, wordSize);
    __ movflt(Address(esp, 0), xmm0);
    __ fld_s(Address(esp, 0));
    __ addl(esp, wordSize);
  } else if (state == dtos && UseSSE >= 2) {
    __ subl(esp, 2*wordSize);
    __ movdbl(Address(esp, 0), xmm0);
    __ fld_d(Address(esp, 0));
    __ addl(esp, 2*wordSize);
  }

  __ MacroAssembler::verify_FPU(state == ftos || state == dtos ? 1 : 0, "generate_deopt_entry_for in interpreter");

  // The stack is not extended by deopt but we must NULL last_sp as this
  // entry is like a "return".
  __ movl(Address(ebp, frame::interpreter_frame_last_sp_offset * wordSize), NULL_WORD);
  __ restore_bcp();
  __ restore_locals();
  // handle exceptions
  { Label L;
    const Register thread = ecx;
    __ get_thread(thread);
    __ cmpl(Address(thread, Thread::pending_exception_offset()), NULL_WORD);
    __ jcc(Assembler::zero, L);    
    __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::throw_pending_exception));
    __ should_not_reach_here();
    __ bind(L);
  }
  __ dispatch_next(state, step);
  return entry;
}


int AbstractInterpreter::BasicType_as_index(BasicType type) {
  int i = 0;
  switch (type) {
    case T_BOOLEAN: i = 0; break;
    case T_CHAR   : i = 1; break;
    case T_BYTE   : i = 2; break;
    case T_SHORT  : i = 3; break;
    case T_INT    : // fall through
    case T_LONG   : // fall through
    case T_VOID   : i = 4; break;
    case T_FLOAT  : i = 5; break;  // have to treat float and double separately for SSE
    case T_DOUBLE : i = 6; break;
    case T_OBJECT : // fall through
    case T_ARRAY  : i = 7; break;
    default       : ShouldNotReachHere();
  }
  assert(0 <= i && i < AbstractInterpreter::number_of_result_handlers, "index out of bounds");
  return i;
}


address AbstractInterpreterGenerator::generate_result_handler_for(BasicType type) {
  address entry = __ pc();
  switch (type) {
    case T_BOOLEAN: __ c2bool(eax);            break;
    case T_CHAR   : __ andl(eax, 0xFFFF);      break;
    case T_BYTE   : __ sign_extend_byte (eax); break;
    case T_SHORT  : __ sign_extend_short(eax); break;
    case T_INT    : /* nothing to do */        break;
    case T_DOUBLE :
    case T_FLOAT  :
      { const Register t = InterpreterRuntime::SignatureHandlerGenerator::temp();
        __ popl(t);                            // remove return address first
        __ pop_dtos_to_esp();
        // Must return a result for interpreter or compiler. In SSE
        // mode, results are returned in xmm0 and the FPU stack must
        // be empty.
        if (type == T_FLOAT && UseSSE >= 1) {
          // Load ST0
          __ fld_d(Address(esp, 0));
          // Store as float and empty fpu stack
          __ fstp_s(Address(esp, 0));
          // and reload
          __ movflt(xmm0, Address(esp, 0));
        } else if (type == T_DOUBLE && UseSSE >= 2 ) {
          __ movdbl(xmm0, Address(esp, 0));
        } else {
          // restore ST0
          __ fld_d(Address(esp));
        }
        // and pop the temp
        __ addl(esp, 2 * wordSize);
        __ pushl(t);                           // restore return address
      }
      break;
    case T_OBJECT :
      // retrieve result from frame
      __ movl(eax, Address(ebp, frame::interpreter_frame_oop_temp_offset*wordSize));
      // and verify it
      __ verify_oop(eax);
      break;
    default       : ShouldNotReachHere();
  }
  __ ret(0);                                   // return from result handler
  return entry;
}


address AbstractInterpreterGenerator::generate_slow_signature_handler() {
  address entry = __ pc();
  // ebx: method
  // ecx: temporary
  // edi: pointer to locals
  // esp: end of copied parameters area
  __ movl(ecx, esp);
  __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::slow_signature_handler), ebx, edi, ecx);
  __ ret(0);
  return entry;
}


address AbstractInterpreterGenerator::generate_safept_entry_for(TosState state, address runtime_entry) {
  address entry = __ pc();
  __ push(state);
  __ call_VM(noreg, runtime_entry);
  __ dispatch_via(vtos, Interpreter::_normal_table.table_for(vtos));
  return entry;
}



// Helpers for commoning out cases in the various type of method entries.
//

// increment invocation count & check for overflow
//
// Note: checking for negative value instead of overflow
//       so we have a 'sticky' overflow test
//
// ebx: method
// ecx: invocation counter
//
void InterpreterGenerator::generate_counter_incr(Label* overflow, Label* profile_method, Label* profile_method_continue) {

  const Address invocation_counter(ebx, methodOopDesc::invocation_counter_offset() + InvocationCounter::counter_offset());
  const Address backedge_counter  (ebx, methodOopDesc::backedge_counter_offset() + InvocationCounter::counter_offset());

  if (ProfileInterpreter) { // %%% Merge this into methodDataOop
    __ increment(Address(ebx,methodOopDesc::interpreter_invocation_counter_offset()));
  }
  // Update standard invocation counters
  __ movl(eax, backedge_counter);              	// load backedge counter

  __ increment(ecx, InvocationCounter::count_increment);
  __ andl(eax, InvocationCounter::count_mask_value);  // mask out the status bits

  __ movl(invocation_counter, ecx);            	// save invocation count
  __ addl(ecx, eax);            		// add both counters

  // profile_method is non-null only for interpreted method so
  // profile_method != NULL == !native_call

  if (ProfileInterpreter && profile_method != NULL) {
    // Test to see if we should create a method data oop
    __ cmpl(ecx, Address(int(&InvocationCounter::InterpreterProfileLimit), relocInfo::none));
    __ jcc(Assembler::less, *profile_method_continue);

    // if no method data exists, go to profile_method
    __ test_method_data_pointer(eax, *profile_method); 
  }

  __ cmpl(ecx, Address(int(&InvocationCounter::InterpreterInvocationLimit), relocInfo::none));
  __ jcc(Assembler::aboveEqual, *overflow);

}

void InterpreterGenerator::generate_counter_overflow(Label* do_continue) {

  // Asm interpreter on entry
  // edi - locals
  // esi - bcp
  // ebx - method
  // edx - cpool
  // ebp - interpreter frame

  // On return (i.e. jump to entry_point) [ back to invocation of interpreter ]
  // ebx - method
  // ecx - rcvr (assuming there is one)
  // top of stack return address of interpreter caller
  // esp - sender_sp

  const Address size_of_parameters(ebx, methodOopDesc::size_of_parameters_offset());

  // InterpreterRuntime::frequency_counter_overflow takes one argument
  // indicating if the counter overflow occurs at a backwards branch (non-NULL bcp).
  // The call returns the address of the verified entry point for the method or NULL
  // if the compilation did not complete (either went background or bailed out).
  __ movl(eax, (int)false);
  __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::frequency_counter_overflow), eax);

  __ movl(ebx, Address(ebp, method_offset));   // restore methodOop

  // Preserve invariant that esi/edi contain bcp/locals of sender frame  
  // and jump to the interpreted entry. 
  __ jmp(*do_continue, relocInfo::none);

}

void InterpreterGenerator::generate_stack_overflow_check(void) {
  // see if we've got enough room on the stack for locals plus overhead.
  // the expression stack grows down incrementally, so the normal guard
  // page mechanism will work for that.
  //
  // Registers live on entry:
  //
  // edx: number of additional locals this frame needs (what we must check)
  // ebx: methodOop

  // destroyed on exit
  // eax

  // NOTE:  since the additional locals are also always pushed (wasn't obvious in
  // generate_method_entry) so the guard should work for them too. 
  //

  // monitor entry size: see picture of stack set (generate_method_entry) and frame_i486.hpp
  const int entry_size    = frame::interpreter_frame_monitor_size() * wordSize;

  // total overhead size: entry_size + (saved ebp thru expr stack bottom).
  // be sure to change this if you add/subtract anything to/from the overhead area
  const int overhead_size = -(frame::interpreter_frame_initial_sp_offset*wordSize) + entry_size;

  const int page_size = os::vm_page_size();

  Label after_frame_check;

  // see if the frame is greater than one page in size. If so,
  // then we need to verify there is enough stack space remaining
  // for the additional locals.
  __ cmpl(edx, (page_size - overhead_size)/Interpreter::stackElementSize());
  __ jcc(Assembler::belowEqual, after_frame_check);

  // compute esp as if this were going to be the last frame on
  // the stack before the red zone

  Label after_frame_check_pop;

  // must preserve esi

  __ pushl(esi);

  const Register thread = esi;

  __ get_thread(thread);

  const Address stack_base(thread, Thread::stack_base_offset());
  const Address stack_size(thread, Thread::stack_size_offset());

  // locals + overhead, in bytes
  __ leal(eax, Address(noreg, edx, Interpreter::stackElementScale(), overhead_size));

#ifdef ASSERT
  Label stack_base_okay, stack_size_okay;
  // verify that thread stack base is non-zero
  __ cmpl(stack_base, 0);
  __ jcc(Assembler::notEqual, stack_base_okay);
  __ stop("stack base is zero");
  __ bind(stack_base_okay);
  // verify that thread stack size is non-zero
  __ cmpl(stack_size, 0);
  __ jcc(Assembler::notEqual, stack_size_okay);
  __ stop("stack size is zero");
  __ bind(stack_size_okay);
#endif

  // Add stack base to locals and subtract stack size
  __ addl(eax, stack_base);
  __ subl(eax, stack_size);

  // add in the redzone and yellow size
  __ addl(eax, (StackRedPages+StackYellowPages) * page_size);

  // check against the current stack bottom
  __ cmpl(esp, eax);
  __ jcc(Assembler::above, after_frame_check_pop);

  __ popl(esi);  // get saved bcp
  __ popl(eax);  // get return address
  __ jmp(Interpreter::throw_StackOverflowError_entry(), relocInfo::runtime_call_type);

  // all done with frame size check
  __ bind(after_frame_check_pop);
  __ popl(esi);

  __ bind(after_frame_check);
}

// Allocate monitor and lock method (asm interpreter)
// ebx - methodOop
// 
void InterpreterGenerator::lock_method(void) {
  // synchronize method
  const Address access_flags      (ebx, methodOopDesc::access_flags_offset());
  const Address monitor_block_top (ebp, frame::interpreter_frame_monitor_block_top_offset * wordSize);
  const int entry_size            = frame::interpreter_frame_monitor_size() * wordSize;

  #ifdef ASSERT
    { Label L;
      __ movl(eax, access_flags);
      __ testl(eax, JVM_ACC_SYNCHRONIZED);
      __ jcc(Assembler::notZero, L);
      __ stop("method doesn't need synchronization");
      __ bind(L);
    }
  #endif // ASSERT
  // get synchronization object
  { Label done;
    const int mirror_offset = klassOopDesc::klass_part_offset_in_bytes() + Klass::java_mirror_offset_in_bytes();
    __ movl(eax, access_flags);
    __ testl(eax, JVM_ACC_STATIC);
    __ movl(eax, Address(edi, Interpreter::local_offset_in_bytes(0)));                                         // get receiver (assume this is frequent case)
    __ jcc(Assembler::zero, done);
    __ movl(eax, Address(ebx, methodOopDesc::constants_offset()));
    __ movl(eax, Address(eax, constantPoolOopDesc::pool_holder_offset_in_bytes()));
    __ movl(eax, Address(eax, mirror_offset));
    __ bind(done);
  }
  // add space for monitor & lock
  __ subl(esp, entry_size);                                             // add space for a monitor entry
  __ movl(monitor_block_top, esp);                                      // set new monitor block top
  __ movl(Address(esp, BasicObjectLock::obj_offset_in_bytes()), eax);   // store object
  __ movl(edx, esp);                                                    // object address
  __ lock_object(edx);          
}

//
// Generate a fixed interpreter frame. This is identical setup for interpreted methods
// and for native methods hence the shared code.

void InterpreterGenerator::generate_fixed_frame(bool native_call) {
  // initialize fixed part of activation frame
  __ pushl(eax);                                      // save return address  
  __ enter();                                         // save old & set new ebp


  __ pushl(esi);                                      // set sender sp
  __ pushl(NULL_WORD);                                // leave last_sp as null
  __ movl(esi, Address(ebx,methodOopDesc::const_offset())); // get constMethodOop
  __ leal(esi, Address(esi,constMethodOopDesc::codes_offset())); // get codebase
  __ pushl(ebx);                                      // save methodOop
  if (ProfileInterpreter) {
    Label method_data_continue;
    __ movl(edx, Address(ebx, in_bytes(methodOopDesc::method_data_offset())));
    __ testl(edx, edx);
    __ jcc(Assembler::zero, method_data_continue);
    __ addl(edx, in_bytes(methodDataOopDesc::data_offset()));
    __ bind(method_data_continue);
    __ pushl(edx);                                      // set the mdp (method data pointer)
  } else {
    __ pushl(0);
  }

  __ movl(edx, Address(ebx, methodOopDesc::constants_offset()));
  __ movl(edx, Address(edx, constantPoolOopDesc::cache_offset_in_bytes()));
  __ pushl(edx);                                      // set constant pool cache
  __ pushl(edi);                                      // set locals pointer
  if (native_call) {
    __ pushl(0);                                      // no bcp
  } else {
    __ pushl(esi);                                    // set bcp
    }
  __ pushl(0);                                        // reserve word for pointer to expression stack bottom
  __ movl(Address(esp), esp);                         // set expression stack bottom
}


// End of helpers

//
// Various method entries
//------------------------------------------------------------------------------------------------------------------------
//
//
address InterpreterGenerator::generate_math_entry(AbstractInterpreter::MethodKind kind) {

  // ebx: methodOop
  // ecx: scratrch
  // esi: sender sp

  if (!InlineIntrinsics) return NULL; // Generate a vanilla entry

  address entry_point = __ pc();

  // These don't need a safepoint check because they aren't virtually
  // callable. We won't enter these intrinsics from compiled code.
  // If in the future we added an intrinsic which was virtually callable
  // we'd have to worry about how to safepoint so that this code is used.

  // mathematical functions inlined by compiler
  // (interpreter must provide identical implementation
  // in order to avoid monotonicity bugs when switching
  // from interpreter to compiler in the middle of some
  // computation)
  //
  // stack: [ ret adr ] <-- esp
  //        [ lo(arg) ]
  //        [ hi(arg) ]
  //

  // Note: For JDK 1.2 StrictMath doesn't exist and Math.sin/cos/sqrt are
  //       native methods. Interpreter::method_kind(...) does a check for
  //       native methods first before checking for intrinsic methods and
  //       thus will never select this entry point. Make sure it is not
  //       called accidentally since the SharedRuntime entry points will
  //       not work for JDK 1.2.
  //
  // We no longer need to check for JDK 1.2 since it's EOL'ed.
  // The following check existed in pre 1.6 implementation,
  //    if (Universe::is_jdk12x_version()) {
  //      __ should_not_reach_here();
  //    }
  // Universe::is_jdk12x_version() always returns false since
  // the JDK version is not yet determined when this method is called.
  // This method is called during interpreter_init() whereas 
  // JDK version is only determined when universe2_init() is called.

  // Note: For JDK 1.3 StrictMath exists and Math.sin/cos/sqrt are
  //       java methods.  Interpreter::method_kind(...) will select
  //       this entry point for the corresponding methods in JDK 1.3.
  // get argument
  if (TaggedStackInterpreter) {
    __ pushl(Address(esp, 3*wordSize));  // push hi (and note esp -= wordSize)
    __ pushl(Address(esp, 2*wordSize));  // push lo
    __ fld_d(Address(esp));  // get double in ST0
    __ addl(esp, 2*wordSize);
  } else {
    __ fld_d(Address(esp, 1*wordSize));
  }
  switch (kind) {
    case Interpreter::java_lang_math_sin :
	__ trigfunc('s');
	break;
    case Interpreter::java_lang_math_cos :
	__ trigfunc('c');
	break;
    case Interpreter::java_lang_math_tan :
	__ trigfunc('t');
	break;
    case Interpreter::java_lang_math_sqrt: 
	__ fsqrt();
	break;
    case Interpreter::java_lang_math_abs:
	__ fabs();
	break;
    case Interpreter::java_lang_math_log:
	__ flog();
	// Store to stack to convert 80bit precision back to 64bits
	__ push_fTOS();
	__ pop_fTOS();
	break;
    case Interpreter::java_lang_math_log10:
	__ flog10();
	// Store to stack to convert 80bit precision back to 64bits
	__ push_fTOS();
	__ pop_fTOS();
	break;
    default                              : 
	ShouldNotReachHere();
  }

  // return double result in xmm0 for interpreter and compilers.
  if (UseSSE >= 2) {
    __ subl(esp, 2*wordSize);
    __ fstp_d(Address(esp, 0));
    __ movdbl(xmm0, Address(esp, 0));
    __ addl(esp, 2*wordSize);
  }

  // done, result in FPU ST(0) or XMM0
  __ popl(edi);                              // get return address
  __ movl(esp, esi);                         // set sp to sender sp
  __ jmp(edi);

  return entry_point;    
}


// Abstract method entry
// Attempt to execute abstract method. Throw exception
address InterpreterGenerator::generate_abstract_entry(void) {

  // ebx: methodOop
  // ecx: receiver (unused)
  // esi: previous interpreter state (C++ interpreter) must preserve

  // esi: sender SP

  address entry_point = __ pc();

  // abstract method entry
  // remove return address. Not really needed, since exception handling throws away expression stack
  __ popl(ebx);             

  // adjust stack to what a normal return would do
  __ movl(esp, esi);
  // throw exception
  __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::throw_AbstractMethodError));
  // the call_VM checks for exception, so we should never return here.
  __ should_not_reach_here();

  return entry_point;
}


// Empty method, generate a very fast return.

address InterpreterGenerator::generate_empty_entry(void) {

  // ebx: methodOop
  // ecx: receiver (unused)
  // esi: previous interpreter state (C++ interpreter) must preserve
  // esi: sender sp must set sp to this value on return

  if (!UseFastEmptyMethods) return NULL;

  address entry_point = __ pc();

  Label slow_path;
  __ cmpl(Address((int)SafepointSynchronize::address_of_state(), 
          relocInfo::none), SafepointSynchronize::_not_synchronized);
  __ jcc(Assembler::notEqual, slow_path);

  // do nothing for empty methods (do not even increment invocation counter)
  // Code: _return
  // _return
  // return w/o popping parameters
  __ popl(eax);
  __ movl(esp, esi);
  __ jmp(eax);

  __ bind(slow_path);
  (void) generate_asm_interpreter_entry(false);
  return entry_point;
}

// Call an accessor method (assuming it is resolved, otherwise drop into vanilla (slow path) entry

address InterpreterGenerator::generate_accessor_entry(void) {

  // ebx: methodOop
  // ecx: receiver (preserve for slow entry into asm interpreter)

  // esi: senderSP must preserved for slow path, set SP to it on fast path

  address entry_point = __ pc();
  Label xreturn_path;

  // do fastpath for resolved accessor methods
  if (UseFastAccessorMethods) {
    Label slow_path;
    __ cmpl(Address((int)SafepointSynchronize::address_of_state(), 
            relocInfo::none), SafepointSynchronize::_not_synchronized);
    __ jcc(Assembler::notEqual, slow_path);
    // Code: _aload_0, _(i|a)getfield, _(i|a)return or any rewrites thereof; parameter size = 1
    // Note: We can only use this code if the getfield has been resolved
    //       and if we don't have a null-pointer exception => check for
    //       these conditions first and use slow path if necessary.
    // ebx: method
    // ecx: receiver
    __ movl(eax, Address(esp, wordSize));

    // check if local 0 != NULL and read field
    __ testl(eax, eax);
    __ jcc(Assembler::zero, slow_path);

    __ movl(edi, Address(ebx, methodOopDesc::constants_offset()));
    // read first instruction word and extract bytecode @ 1 and index @ 2
    __ movl(edx, Address(ebx, methodOopDesc::const_offset()));
    __ movl(edx, Address(edx, constMethodOopDesc::codes_offset()));
    // Shift codes right to get the index on the right.
    // The bytecode fetched looks like <index><0xb4><0x2a>
    __ shrl(edx, 2*BitsPerByte);
    __ shll(edx, exact_log2(in_words(ConstantPoolCacheEntry::size())));
    __ movl(edi, Address(edi, constantPoolOopDesc::cache_offset_in_bytes()));

    // eax: local 0
    // ebx: method
    // ecx: receiver - do not destroy since it is needed for slow path!
    // ecx: scratch
    // edx: constant pool cache index
    // edi: constant pool cache
    // esi: sender sp

    // check if getfield has been resolved and read constant pool cache entry
    // check the validity of the cache entry by testing whether _indices field
    // contains Bytecode::_getfield in b1 byte.
    assert(in_words(ConstantPoolCacheEntry::size()) == 4, "adjust shift below");
    __ movl(ecx, 
	    Address(edi, 
		    edx, 
		    Address::times_4, constantPoolCacheOopDesc::base_offset() + ConstantPoolCacheEntry::indices_offset()));
    __ shrl(ecx, 2*BitsPerByte);
    __ andl(ecx, 0xFF);
    __ cmpl(ecx, Bytecodes::_getfield);
    __ jcc(Assembler::notEqual, slow_path);

    // Note: constant pool entry is not valid before bytecode is resolved
    __ movl(ecx, 
	    Address(edi, 
		    edx, 
		    Address::times_4, constantPoolCacheOopDesc::base_offset() + ConstantPoolCacheEntry::f2_offset()));
    __ movl(edx, 
	    Address(edi, 
		    edx, 
		    Address::times_4, constantPoolCacheOopDesc::base_offset() + ConstantPoolCacheEntry::flags_offset()));

    Label notByte, notShort, notChar;
    const Address field_address (eax, ecx, Address::times_1);

    // Need to differentiate between igetfield, agetfield, bgetfield etc.
    // because they are different sizes.
    // Use the type from the constant pool cache
    __ shrl(edx, ConstantPoolCacheEntry::tosBits);
    // Make sure we don't need to mask edx for tosBits after the above shift
    ConstantPoolCacheEntry::verify_tosBits();
    __ cmpl(edx, btos);
    __ jcc(Assembler::notEqual, notByte);
    __ load_signed_byte(eax, field_address);
    __ jmp(xreturn_path);

    __ bind(notByte);
    __ cmpl(edx, stos);
    __ jcc(Assembler::notEqual, notShort);
    __ load_signed_word(eax, field_address);
    __ jmp(xreturn_path);

    __ bind(notShort);
    __ cmpl(edx, ctos);
    __ jcc(Assembler::notEqual, notChar);
    __ load_unsigned_word(eax, field_address);
    __ jmp(xreturn_path);

    __ bind(notChar);
#ifdef ASSERT
    Label okay;
    __ cmpl(edx, atos);
    __ jcc(Assembler::equal, okay);
    __ cmpl(edx, itos);
    __ jcc(Assembler::equal, okay);
    __ stop("what type is this?");
    __ bind(okay);
#endif // ASSERT
    // All the rest are a 32 bit wordsize
    __ movl(eax, field_address);

    __ bind(xreturn_path);

    // _ireturn/_areturn
    __ popl(edi);                              // get return address
    __ movl(esp, esi);                         // set sp to sender sp
    __ jmp(edi);

    // generate a vanilla interpreter entry as the slow path
    __ bind(slow_path);
    (void) generate_asm_interpreter_entry(false);
  } else {
    (void) generate_asm_interpreter_entry(false);
  }

  return entry_point;
}


//
// Interpreter stub for calling a native method. (asm interpreter)
// This sets up a somewhat different looking stack for calling the native method
// than the typical interpreter frame setup.
//

address InterpreterGenerator::generate_native_entry(bool synchronized) {
  // determine code generation flags
  bool inc_counter  = UseCompiler || CountCompiledCalls;

  // ebx: methodOop
  // esi: sender sp
  // esi: previous interpreter state (C++ interpreter) must preserve
  address entry_point = __ pc();


  const Address size_of_parameters(ebx, methodOopDesc::size_of_parameters_offset());
  const Address invocation_counter(ebx, methodOopDesc::invocation_counter_offset() + InvocationCounter::counter_offset());
  const Address access_flags      (ebx, methodOopDesc::access_flags_offset());

  // get parameter size (always needed)
  __ load_unsigned_word(ecx, size_of_parameters);

  // native calls don't need the stack size check since they have no expression stack
  // and the arguments are already on the stack and we only add a handful of words
  // to the stack 

  // ebx: methodOop
  // ecx: size of parameters
  // esi: sender sp

  __ popl(eax);                                       // get return address
  // for natives the size of locals is zero

  // compute beginning of parameters (edi)
  __ leal(edi, Address(esp, ecx, Interpreter::stackElementScale(), -wordSize));


  // add 2 zero-initialized slots for native calls
  // NULL result handler
  __ pushl(NULL_WORD);
  // NULL oop temp (mirror or jni oop result)
  __ pushl(NULL_WORD);

  if (inc_counter) __ movl(ecx, invocation_counter);  // (pre-)fetch invocation count
  // initialize fixed part of activation frame

  generate_fixed_frame(true);

  // make sure method is native & not abstract
#ifdef ASSERT
  __ movl(eax, access_flags);
  {
    Label L;
    __ testl(eax, JVM_ACC_NATIVE);
    __ jcc(Assembler::notZero, L);
    __ stop("tried to execute non-native method as native");
    __ bind(L);
  }
  { Label L;
    __ testl(eax, JVM_ACC_ABSTRACT);
    __ jcc(Assembler::zero, L);
    __ stop("tried to execute abstract method in interpreter");
    __ bind(L);
  }
#endif

  // Since at this point in the method invocation the exception handler
  // would try to exit the monitor of synchronized methods which hasn't
  // been entered yet, we set the thread local variable
  // _do_not_unlock_if_synchronized to true. The remove_activation will
  // check this flag.

  __ get_thread(eax);
  const Address do_not_unlock_if_synchronized(eax,
        in_bytes(JavaThread::do_not_unlock_if_synchronized_offset()));
  __ movbool(do_not_unlock_if_synchronized, true);

  // increment invocation count & check for overflow
  Label invocation_counter_overflow;
  if (inc_counter) {
    generate_counter_incr(&invocation_counter_overflow, NULL, NULL);
  }

  Label continue_after_compile;
  __ bind(continue_after_compile);

  bang_stack_shadow_pages(true);

  // reset the _do_not_unlock_if_synchronized flag
  __ get_thread(eax);
  __ movbool(do_not_unlock_if_synchronized, false);

  // check for synchronized methods
  // Must happen AFTER invocation_counter check and stack overflow check,
  // so method is not locked if overflows.
  //
  if (synchronized) {
    lock_method();
  } else {
    // no synchronization necessary
#ifdef ASSERT
      { Label L;
        __ movl(eax, access_flags);
        __ testl(eax, JVM_ACC_SYNCHRONIZED);
        __ jcc(Assembler::zero, L);
        __ stop("method needs synchronization");
        __ bind(L);
      }
#endif
  }

  // start execution
#ifdef ASSERT
  { Label L;
    const Address monitor_block_top (ebp,
                 frame::interpreter_frame_monitor_block_top_offset * wordSize);
    __ movl(eax, monitor_block_top);
    __ cmpl(eax, esp);
    __ jcc(Assembler::equal, L);
    __ stop("broken stack frame setup in interpreter");
    __ bind(L);
  }
#endif

  // jvmti/dtrace support
  __ notify_method_entry();

  // work registers
  const Register method = ebx;
  const Register thread = edi;
  const Register t      = ecx;    

  // allocate space for parameters
  __ get_method(method);
  __ verify_oop(method);
  __ load_unsigned_word(t, Address(method, methodOopDesc::size_of_parameters_offset()));
  __ shll(t, Interpreter::logStackElementSize());
  __ addl(t, 2*wordSize);     // allocate two more slots for JNIEnv and possible mirror
  __ subl(esp, t);
  __ andl(esp, -(StackAlignmentInBytes)); // gcc needs 16 byte aligned stacks to do XMM intrinsics

  // get signature handler
  { Label L;
    __ movl(t, Address(method, methodOopDesc::signature_handler_offset()));
    __ testl(t, t);
    __ jcc(Assembler::notZero, L);
    __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::prepare_native_call), method);
    __ get_method(method);
    __ movl(t, Address(method, methodOopDesc::signature_handler_offset()));
    __ bind(L);
  }

  // call signature handler
  assert(InterpreterRuntime::SignatureHandlerGenerator::from() == edi, "adjust this code");
  assert(InterpreterRuntime::SignatureHandlerGenerator::to  () == esp, "adjust this code");
  assert(InterpreterRuntime::SignatureHandlerGenerator::temp() == t  , "adjust this code");
  // The generated handlers do not touch EBX (the method oop).
  // However, large signatures cannot be cached and are generated 
  // each time here.  The slow-path generator will blow EBX
  // sometime, so we must reload it after the call.
  __ call(t, relocInfo::none);
  __ get_method(method);	// slow path call blows EBX on DevStudio 5.0

  // result handler is in eax
  // set result handler
  __ movl(Address(ebp, frame::interpreter_frame_result_handler_offset*wordSize), eax);

  // pass mirror handle if static call
  { Label L;
    const int mirror_offset = klassOopDesc::klass_part_offset_in_bytes() + Klass::java_mirror_offset_in_bytes();
    __ movl(t, Address(method, methodOopDesc::access_flags_offset()));
    __ testl(t, JVM_ACC_STATIC);
    __ jcc(Assembler::zero, L);
    // get mirror
    __ movl(t, Address(method, methodOopDesc:: constants_offset()));
    __ movl(t, Address(t, constantPoolOopDesc::pool_holder_offset_in_bytes()));
    __ movl(t, Address(t, mirror_offset));
    // copy mirror into activation frame
    __ movl(Address(ebp, frame::interpreter_frame_oop_temp_offset * wordSize), t);
    // pass handle to mirror
    __ leal(t, Address(ebp, frame::interpreter_frame_oop_temp_offset * wordSize));
    __ movl(Address(esp, wordSize), t);
    __ bind(L);
  }

  // get native function entry point
  { Label L;
    __ movl(eax, Address(method, methodOopDesc::native_function_offset()));
    __ cmpl(eax, (int)SharedRuntime::native_method_throw_unsatisfied_link_error_entry());
    __ jcc(Assembler::notEqual, L);
    __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::prepare_native_call), method);
    __ get_method(method);
    __ verify_oop(method);
    __ movl(eax, Address(method, methodOopDesc::native_function_offset()));
    __ bind(L);
  }

  // pass JNIEnv
  __ get_thread(thread);
  __ leal(t, Address(thread, JavaThread::jni_environment_offset()));
  __ movl(Address(esp), t);

  // set_last_Java_frame_before_call
  // It is enough that the pc()
  // points into the right code segment. It does not have to be the correct return pc.
  __ set_last_Java_frame(thread, noreg, ebp, __ pc());

  // change thread state
#ifdef ASSERT
  { Label L;
    __ movl(t, Address(thread, JavaThread::thread_state_offset()));
    __ cmpl(t, _thread_in_Java);
    __ jcc(Assembler::equal, L);
    __ stop("Wrong thread state in native stub");
    __ bind(L);
  }
#endif

  // Change state to native 
  __ movl(Address(thread, JavaThread::thread_state_offset()), _thread_in_native);    
  __ call(eax, relocInfo::none);

  // result potentially in edx:eax or ST0

  // Either restore the MXCSR register after returning from the JNI Call
  // or verify that it wasn't changed.
  if (VM_Version::supports_sse()) {
    if (RestoreMXCSROnJNICalls) {
      __ ldmxcsr(Address((int) StubRoutines::addr_mxcsr_std(), relocInfo::none));
    }
    else if (CheckJNICalls ) {
      __ call(CAST_FROM_FN_PTR(address, StubRoutines::i486::verify_mxcsr_entry()), 
              relocInfo::runtime_call_type);
    }
  }

  // Either restore the x87 floating pointer control word after returning
  // from the JNI call or verify that it wasn't changed.
  if (CheckJNICalls) {
    __ call(StubRoutines::i486::verify_fpu_cntrl_wrd_entry(), relocInfo::runtime_call_type);
  }

  // save potential result in ST(0) & edx:eax
  // (if result handler is the T_FLOAT or T_DOUBLE handler, result must be in ST0 -
  // the check is necessary to avoid potential Intel FPU overflow problems by saving/restoring 'empty' FPU registers)
  // It is safe to do this push because state is _thread_in_native and return address will be found
  // via _last_native_pc and not via _last_jave_sp

  // NOTE: the order of theses push(es) is known to frame::interpreter_frame_result.
  // If the order changes or anything else is added to the stack the code in
  // interpreter_frame_result will have to be changed.

  { Label L;
    Label push_double;
    __ cmpl(Address(ebp, (frame::interpreter_frame_oop_temp_offset + 1)*wordSize), 
           (int)AbstractInterpreter::result_handler(T_FLOAT));
    __ jcc(Assembler::equal, push_double);
    __ cmpl(Address(ebp, (frame::interpreter_frame_oop_temp_offset + 1)*wordSize), 
           (int)AbstractInterpreter::result_handler(T_DOUBLE));
    __ jcc(Assembler::notEqual, L);
    __ bind(push_double);
    __ push(dtos);      
    __ bind(L);
  }
  __ push(ltos);

  // change thread state
  __ get_thread(thread);
  __ movl(Address(thread, JavaThread::thread_state_offset()), _thread_in_native_trans);
  if(os::is_MP()) { 
    if (UseMembar) {
      __ membar(); // Force this write out before the read below
    } else {
      // Write serialization page so VM thread can do a pseudo remote membar.
      // We use the current thread pointer to calculate a thread specific
      // offset to write to within the page. This minimizes bus traffic
      // due to cache line collision.
      __ serialize_memory(thread, ecx);
    }
  }

  if (AlwaysRestoreFPU) {
    //  Make sure the control word is correct. 
    __ fldcw(Address((int) StubRoutines::addr_fpu_cntrl_wrd_std(), relocInfo::none));
  }

  // check for safepoint operation in progress and/or pending suspend requests
  { Label Continue;

    __ cmpl(Address((int)SafepointSynchronize::address_of_state(), relocInfo::none), SafepointSynchronize::_not_synchronized);

    Label L;
    __ jcc(Assembler::notEqual, L);
    __ cmpl(Address(thread, JavaThread::suspend_flags_offset()), 0);
    __ jcc(Assembler::equal, Continue);
    __ bind(L);

    // Don't use call_VM as it will see a possible pending exception and forward it
    // and never return here preventing us from clearing _last_native_pc down below.
    // Also can't use call_VM_leaf either as it will check to see if esi & edi are
    // preserved and correspond to the bcp/locals pointers. So we do a runtime call
    // by hand.
    //
    __ pushl(thread);
    __ call(CAST_FROM_FN_PTR(address, JavaThread::check_special_condition_for_native_trans), relocInfo::runtime_call_type);
    __ increment(esp, wordSize);
    __ get_thread(thread);

    __ bind(Continue);
  }

  // change thread state
  __ movl(Address(thread, JavaThread::thread_state_offset()), _thread_in_Java);

  __ reset_last_Java_frame(thread, true, true);

  // reset handle block
  __ movl(t, Address(thread, JavaThread::active_handles_offset()));
  __ movl(Address(t, JNIHandleBlock::top_offset_in_bytes()), 0);

  // If result was an oop then unbox and save it in the frame
  { Label L;
    Label no_oop, store_result;
    __ cmpl(Address(ebp, frame::interpreter_frame_result_handler_offset*wordSize), 
           (int)AbstractInterpreter::result_handler(T_OBJECT));
    __ jcc(Assembler::notEqual, no_oop);
    __ cmpl(Address(esp), NULL_WORD);
    __ pop(ltos);
    __ testl(eax, eax);
    __ jcc(Assembler::zero, store_result);
    // unbox
    __ movl(eax, Address(eax));
    __ bind(store_result);
    __ movl(Address(ebp, (frame::interpreter_frame_oop_temp_offset)*wordSize), eax);
    // keep stack depth as expected by pushing oop which will eventually be discarded
    __ push(ltos);
    __ bind(no_oop);
  }

  {
     Label no_reguard;
     __ cmpl(Address(thread, JavaThread::stack_guard_state_offset()), JavaThread::stack_guard_yellow_disabled);
     __ jcc(Assembler::notEqual, no_reguard);

     __ pushad();
     __ call(CAST_FROM_FN_PTR(address, SharedRuntime::reguard_yellow_pages), relocInfo::runtime_call_type);
     __ popad();

     __ bind(no_reguard);
   }

  // restore esi to have legal interpreter frame, 
  // i.e., bci == 0 <=> esi == code_base()
  // Can't call_VM until bcp is within reasonable.
  __ get_method(method);      // method is junk from thread_in_native to now.
  __ verify_oop(method);
  __ movl(esi, Address(method,methodOopDesc::const_offset())); // get constMethodOop
  __ leal(esi, Address(esi,constMethodOopDesc::codes_offset()));    // get codebase

  // handle exceptions (exception handling will handle unlocking!)
  { Label L;
    __ cmpl(Address(thread, Thread::pending_exception_offset()), NULL_WORD);
    __ jcc(Assembler::zero, L);
    // Note: At some point we may want to unify this with the code used in call_VM_base();
    //       i.e., we should use the StubRoutines::forward_exception code. For now this
    //       doesn't work here because the esp is not correctly set at this point.
    __ MacroAssembler::call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::throw_pending_exception));
    __ should_not_reach_here();
    __ bind(L);
  }

  // do unlocking if necessary
  { Label L;
    __ movl(t, Address(method, methodOopDesc::access_flags_offset()));
    __ testl(t, JVM_ACC_SYNCHRONIZED);
    __ jcc(Assembler::zero, L);
    // the code below should be shared with interpreter macro assembler implementation
    { Label unlock;
      // BasicObjectLock will be first in list, since this is a synchronized method. However, need
      // to check that the object has not been unlocked by an explicit monitorexit bytecode.        
      const Address monitor(ebp, frame::interpreter_frame_initial_sp_offset * wordSize - (int)sizeof(BasicObjectLock));

      __ leal(edx, monitor);                   // address of first monitor

      __ movl(t, Address(edx, BasicObjectLock::obj_offset_in_bytes()));
      __ testl(t, t);
      __ jcc(Assembler::notZero, unlock);
				
      // Entry already unlocked, need to throw exception
      __ MacroAssembler::call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::throw_illegal_monitor_state_exception));
      __ should_not_reach_here();
  
      __ bind(unlock);        
      __ unlock_object(edx);             
    }
    __ bind(L);
  }    

  // jvmti/dtrace support
  // Note: This must happen _after_ handling/throwing any exceptions since
  //       the exception handler code notifies the runtime of method exits
  //       too. If this happens before, method entry/exit notifications are
  //       not properly paired (was bug - gri 11/22/99).
  __ notify_method_exit(vtos, InterpreterMacroAssembler::NotifyJVMTI);

  // restore potential result in edx:eax, call result handler to restore potential result in ST0 & handle result
  __ pop(ltos);
  __ movl(t, Address(ebp, frame::interpreter_frame_result_handler_offset*wordSize));
  __ call(t, relocInfo::none);

  // remove activation
  __ movl(t, Address(ebp, frame::interpreter_frame_sender_sp_offset * wordSize)); // get sender sp
  __ leave();                                // remove frame anchor
  __ popl(edi);                              // get return address
  __ movl(esp, t);                           // set sp to sender sp
  __ jmp(edi);

  if (inc_counter) {
    // Handle overflow of counter and compile method
    __ bind(invocation_counter_overflow);
    generate_counter_overflow(&continue_after_compile);
  }

  return entry_point;
}


//
// Generic interpreted method entry to (asm) interpreter
//
address InterpreterGenerator::generate_asm_interpreter_entry(bool synchronized) {
  // determine code generation flags
  bool inc_counter  = UseCompiler || CountCompiledCalls;

  // ebx: methodOop
  // esi: sender sp
  address entry_point = __ pc();


  const Address size_of_parameters(ebx, methodOopDesc::size_of_parameters_offset());
  const Address size_of_locals    (ebx, methodOopDesc::size_of_locals_offset());
  const Address invocation_counter(ebx, methodOopDesc::invocation_counter_offset() + InvocationCounter::counter_offset());
  const Address access_flags      (ebx, methodOopDesc::access_flags_offset());

  // get parameter size (always needed)
  __ load_unsigned_word(ecx, size_of_parameters);

  // ebx: methodOop
  // ecx: size of parameters

  // esi: sender_sp (could differ from sp+wordSize if we were called via c2i )

  __ load_unsigned_word(edx, size_of_locals);       // get size of locals in words
  __ subl(edx, ecx);                                // edx = no. of additional locals

  // see if we've got enough room on the stack for locals plus overhead.
  generate_stack_overflow_check();

  // get return address
  __ popl(eax);                                       

  // compute beginning of parameters (edi)
  __ leal(edi, Address(esp, ecx, Interpreter::stackElementScale(), -wordSize));

  // edx - # of additional locals
  // allocate space for locals
  // explicitly initialize locals
  {
    Label exit, loop;
    __ testl(edx, edx);
    __ jcc(Assembler::lessEqual, exit);               // do nothing if edx <= 0
    __ bind(loop);
    if (TaggedStackInterpreter) __ pushl(NULL_WORD);  // push tag
    __ pushl(NULL_WORD);                              // initialize local variables
    __ decrement(edx);                                // until everything initialized
    __ jcc(Assembler::greater, loop);
    __ bind(exit);
  }

  if (inc_counter) __ movl(ecx, invocation_counter);  // (pre-)fetch invocation count
  // initialize fixed part of activation frame
  generate_fixed_frame(false);

  // make sure method is not native & not abstract
#ifdef ASSERT
  __ movl(eax, access_flags);
  {
    Label L;
    __ testl(eax, JVM_ACC_NATIVE);
    __ jcc(Assembler::zero, L);
    __ stop("tried to execute native method as non-native");
    __ bind(L);
  }
  { Label L;
    __ testl(eax, JVM_ACC_ABSTRACT);
    __ jcc(Assembler::zero, L);
    __ stop("tried to execute abstract method in interpreter");
    __ bind(L);
  }
#endif

  // Since at this point in the method invocation the exception handler
  // would try to exit the monitor of synchronized methods which hasn't
  // been entered yet, we set the thread local variable
  // _do_not_unlock_if_synchronized to true. The remove_activation will
  // check this flag.

  __ get_thread(eax);
  const Address do_not_unlock_if_synchronized(eax,
        in_bytes(JavaThread::do_not_unlock_if_synchronized_offset()));
  __ movbool(do_not_unlock_if_synchronized, true);

  // increment invocation count & check for overflow
  Label invocation_counter_overflow;
  Label profile_method;
  Label profile_method_continue;
  if (inc_counter) {
    generate_counter_incr(&invocation_counter_overflow, &profile_method, &profile_method_continue);
    if (ProfileInterpreter) {
      __ bind(profile_method_continue);
    }
  }
  Label continue_after_compile;
  __ bind(continue_after_compile);

  bang_stack_shadow_pages(false);

  // reset the _do_not_unlock_if_synchronized flag
  __ get_thread(eax);
  __ movbool(do_not_unlock_if_synchronized, false);

  // check for synchronized methods
  // Must happen AFTER invocation_counter check and stack overflow check,
  // so method is not locked if overflows.
  //
  if (synchronized) {
    // Allocate monitor and lock method
    lock_method();
  } else {
    // no synchronization necessary
#ifdef ASSERT
      { Label L;
        __ movl(eax, access_flags);
        __ testl(eax, JVM_ACC_SYNCHRONIZED);
        __ jcc(Assembler::zero, L);
        __ stop("method needs synchronization");
        __ bind(L);
      }
#endif
  }

  // start execution
#ifdef ASSERT
  { Label L;
     const Address monitor_block_top (ebp,
                 frame::interpreter_frame_monitor_block_top_offset * wordSize);
    __ movl(eax, monitor_block_top);
    __ cmpl(eax, esp);
    __ jcc(Assembler::equal, L);
    __ stop("broken stack frame setup in interpreter");
    __ bind(L);
  }
#endif

  // jvmti support
  __ notify_method_entry();
 
  __ dispatch_next(vtos);

  // invocation counter overflow
  if (inc_counter) {
    if (ProfileInterpreter) {
      // We have decided to profile this method in the interpreter
      __ bind(profile_method);

      __ call_VM(noreg, CAST_FROM_FN_PTR(address, InterpreterRuntime::profile_method), esi, true);

      __ movl(ebx, Address(ebp, method_offset));   // restore methodOop
      __ movl(eax, Address(ebx, in_bytes(methodOopDesc::method_data_offset())));
      __ movl(Address(ebp, frame::interpreter_frame_mdx_offset * wordSize), eax);
      __ test_method_data_pointer(eax, profile_method_continue);
      __ addl(eax, in_bytes(methodDataOopDesc::data_offset()));
      __ movl(Address(ebp, frame::interpreter_frame_mdx_offset * wordSize), eax);
      __ jmp(profile_method_continue);
    }
    // Handle overflow of counter and compile method
    __ bind(invocation_counter_overflow);
    generate_counter_overflow(&continue_after_compile);
  }

  return entry_point;
}

//------------------------------------------------------------------------------------------------------------------------
// Entry points
// 
// Here we generate the various kind of entries into the interpreter.
// The two main entry type are generic bytecode methods and native call method.
// These both come in synchronized and non-synchronized versions but the
// frame layout they create is very similar. The other method entry
// types are really just special purpose entries that are really entry
// and interpretation all in one. These are for trivial methods like
// accessor, empty, or special math methods.
//
// When control flow reaches any of the entry types for the interpreter
// the following holds ->
//
// Arguments:
//
// ebx: methodOop
// ecx: receiver
//
//
// Stack layout immediately at entry
//
// [ return address     ] <--- esp
// [ parameter n        ]
//   ...
// [ parameter 1        ]
// [ expression stack   ] (caller's java expression stack)

// Assuming that we don't go to one of the trivial specialized
// entries the stack will look like below when we are ready to execute
// the first bytecode (or call the native routine). The register usage
// will be as the template based interpreter expects (see interpreter_i486.hpp).
//
// local variables follow incoming parameters immediately; i.e.
// the return address is moved to the end of the locals).
//
// [ monitor entry      ] <--- esp
//   ...
// [ monitor entry      ]
// [ expr. stack bottom ]
// [ saved esi          ]
// [ current edi        ]
// [ methodOop          ]
// [ saved ebp          ] <--- ebp
// [ return address     ]
// [ local variable m   ]
//   ...
// [ local variable 1   ]
// [ parameter n        ]
//   ...
// [ parameter 1        ] <--- edi

address AbstractInterpreterGenerator::generate_method_entry(AbstractInterpreter::MethodKind kind) {
  // determine code generation flags
  bool synchronized = false;
  address entry_point = NULL;

  switch (kind) {    
    case Interpreter::zerolocals             :                                                                             break;
    case Interpreter::zerolocals_synchronized: synchronized = true;                                                        break;
    case Interpreter::native                 : entry_point = ((InterpreterGenerator*)this)->generate_native_entry(false);  break;
    case Interpreter::native_synchronized    : entry_point = ((InterpreterGenerator*)this)->generate_native_entry(true);   break;
    case Interpreter::empty                  : entry_point = ((InterpreterGenerator*)this)->generate_empty_entry();        break;
    case Interpreter::accessor               : entry_point = ((InterpreterGenerator*)this)->generate_accessor_entry();     break;
    case Interpreter::abstract               : entry_point = ((InterpreterGenerator*)this)->generate_abstract_entry();     break;

    case Interpreter::java_lang_math_sin     : // fall thru
    case Interpreter::java_lang_math_cos     : // fall thru
    case Interpreter::java_lang_math_tan     : // fall thru
    case Interpreter::java_lang_math_abs     : // fall thru
    case Interpreter::java_lang_math_log     : // fall thru
    case Interpreter::java_lang_math_log10   : // fall thru
    case Interpreter::java_lang_math_sqrt    : entry_point = ((InterpreterGenerator*)this)->generate_math_entry(kind);     break;
    default                                  : ShouldNotReachHere();                                                       break;
  }

  if (entry_point) return entry_point;

  return ((InterpreterGenerator*)this)->generate_asm_interpreter_entry(synchronized);

}

// How much stack a method activation needs in words.
int AbstractInterpreter::size_top_interpreter_activation(methodOop method) {

  const int entry_size    = frame::interpreter_frame_monitor_size();

  // total overhead size: entry_size + (saved ebp thru expr stack bottom).
  // be sure to change this if you add/subtract anything to/from the overhead area
  const int overhead_size = -(frame::interpreter_frame_initial_sp_offset) + entry_size;

  const int stub_code = 4;  // see generate_call_stub
  const int method_stack = (method->max_locals() + method->max_stack()) *
                           Interpreter::stackElementWords();
  return overhead_size + method_stack + stub_code;
}

// This method tells the deoptimizer how big an interpreted frame must be:
int AbstractInterpreter::size_activation(methodOop method,
					 int tempcount,
					 int popframe_extra_args,
					 int moncount,
					 int callee_param_count,
					 int callee_locals,
					 bool is_top_frame) {
  return layout_activation(method, 
                           tempcount,
                           popframe_extra_args,
                           moncount,
                           callee_param_count,
                           callee_locals,
                           (frame*) NULL,
                           (frame*) NULL,
                           is_top_frame);
}


int AbstractInterpreter::layout_activation(methodOop method,
                                           int tempcount,
                                           int popframe_extra_args,
                                           int moncount,
                                           int callee_param_count,
                                           int callee_locals,
                                           frame* caller,
                                           frame* interpreter_frame,
                                           bool is_top_frame) {
  // Note: This calculation must exactly parallel the frame setup
  // in AbstractInterpreterGenerator::generate_method_entry.
  // If interpreter_frame!=NULL, set up the method, locals, and monitors.
  // The frame interpreter_frame, if not NULL, is guaranteed to be the right size,
  // as determined by a previous call to this method.
  // It is also guaranteed to be walkable even though it is in a skeletal state

  // fixed size of an interpreter frame:
  int max_locals = method->max_locals() * Interpreter::stackElementWords();
  int extra_locals = (method->max_locals() - method->size_of_parameters()) *
                     Interpreter::stackElementWords();

  int overhead = frame::sender_sp_offset - frame::interpreter_frame_initial_sp_offset;
  // Our locals were accounted for by the caller (or last_frame_adjust on the transistion)
  // Since the callee parameters already account for the callee's params we only need to account for
  // the extra locals.

  
  int size = overhead +
         ((callee_locals - callee_param_count)*Interpreter::stackElementWords()) + 
         (moncount*frame::interpreter_frame_monitor_size()) + 
         tempcount*Interpreter::stackElementWords() + popframe_extra_args;

  if (interpreter_frame != NULL) {
#ifdef ASSERT
    assert(caller->unextended_sp() == interpreter_frame->interpreter_frame_sender_sp(), "Frame not properly walkable");
    assert(caller->sp() == interpreter_frame->sender_sp(), "Frame not properly walkable(2)");
#endif

    interpreter_frame->interpreter_frame_set_method(method);
    // NOTE the difference in using sender_sp and interpreter_frame_sender_sp
    // interpreter_frame_sender_sp is the original sp of the caller (the unextended_sp)
    // and sender_sp is fp+8
    intptr_t* locals = interpreter_frame->sender_sp() + max_locals - 1;

    interpreter_frame->interpreter_frame_set_locals(locals);
    BasicObjectLock* montop = interpreter_frame->interpreter_frame_monitor_begin();
    BasicObjectLock* monbot = montop - moncount;
    interpreter_frame->interpreter_frame_set_monitor_end(monbot);

    // Set last_sp 
    intptr_t*  esp = (intptr_t*) monbot  - 
                     tempcount*Interpreter::stackElementWords() - 
                     popframe_extra_args;
    interpreter_frame->interpreter_frame_set_last_sp(esp);

    // All frames but the initial (oldest) interpreter frame we fill in have a
    // value for sender_sp that allows walking the stack but isn't
    // truly correct. Correct the value here.

    if (extra_locals != 0 && 
	interpreter_frame->sender_sp() == interpreter_frame->interpreter_frame_sender_sp() ) {
      interpreter_frame->set_interpreter_frame_sender_sp(caller->sp() + extra_locals);
    }
    *interpreter_frame->interpreter_frame_cache_addr() = 
      method->constants()->cache();
  }
  return size;
}

void Deoptimization::unwind_callee_save_values(frame* f, vframeArray* vframe_array) {

  // This code is sort of the equivalent of C2IAdapter::setup_stack_frame back in
  // the days we had adapter frames. When we deoptimize a situation where a
  // compiled caller calls a compiled caller will have registers it expects
  // to survive the call to the callee. If we deoptimize the callee the only
  // way we can restore these registers is to have the oldest interpreter
  // frame that we create restore these values. That is what this routine
  // will accomplish.

  // At the moment we have modified c2 to not have any callee save registers
  // so this problem does not exist and this routine is just a place holder.

  assert(f->is_interpreted_frame(), "must be interpreted");
}


//------------------------------------------------------------------------------------------------------------------------
// Exceptions

void AbstractInterpreterGenerator::generate_throw_exception() {
  // Entry point in previous activation (i.e., if the caller was interpreted)
  Interpreter::_rethrow_exception_entry = __ pc();

  // Restore sp to interpreter_frame_last_sp even though we are going
  // to empty the expression stack for the exception processing. 
  __ movl(Address(ebp, frame::interpreter_frame_last_sp_offset * wordSize), NULL_WORD);
  // eax: exception
  // edx: return address/pc that threw exception
  __ restore_bcp();                              // esi points to call/send
  __ restore_locals();

  // Entry point for exceptions thrown within interpreter code
  Interpreter::_throw_exception_entry = __ pc();  
  // expression stack is undefined here
  // eax: exception
  // esi: exception bcp
  __ verify_oop(eax);

  // expression stack must be empty before entering the VM in case of an exception
  __ empty_expression_stack();
  __ empty_FPU_stack();
  // find exception handler address and preserve exception oop
  __ call_VM(edx, CAST_FROM_FN_PTR(address, InterpreterRuntime::exception_handler_for_exception), eax);
  // eax: exception handler entry point
  // edx: preserved exception oop
  // esi: bcp for exception handler
  __ push_ptr(edx);                              // push exception which is now the only value on the stack
  __ jmp(eax);                                   // jump to exception handler (may be _remove_activation_entry!)

  // If the exception is not handled in the current frame the frame is removed and
  // the exception is rethrown (i.e. exception continuation is _rethrow_exception).
  //
  // Note: At this point the bci is still the bxi for the instruction which caused
  //       the exception and the expression stack is empty. Thus, for any VM calls
  //       at this point, GC will find a legal oop map (with empty expression stack).

  // In current activation
  // tos: exception
  // esi: exception bcp

  //
  // JVMTI PopFrame support
  //

   Interpreter::_remove_activation_preserving_args_entry = __ pc();
  __ empty_expression_stack();
  __ empty_FPU_stack();
  // Set the popframe_processing bit in pending_popframe_condition indicating that we are
  // currently handling popframe, so that call_VMs that may happen later do not trigger new
  // popframe handling cycles.
  __ get_thread(ecx);
  __ movl(edx, Address(ecx, JavaThread::popframe_condition_offset()));
  __ orl(edx, JavaThread::popframe_processing_bit);
  __ movl(Address(ecx, JavaThread::popframe_condition_offset()), edx);

  {
    // Check to see whether we are returning to a deoptimized frame.
    // (The PopFrame call ensures that the caller of the popped frame is
    // either interpreted or compiled and deoptimizes it if compiled.)
    // In this case, we can't call dispatch_next() after the frame is
    // popped, but instead must save the incoming arguments and restore
    // them after deoptimization has occurred.
    //
    // Note that we don't compare the return PC against the
    // deoptimization blob's unpack entry because of the presence of
    // adapter frames in C2.
    Label caller_not_deoptimized;
    __ movl(edx, Address(ebp, frame::return_addr_offset * wordSize));
    __ super_call_VM_leaf(CAST_FROM_FN_PTR(address, InterpreterRuntime::interpreter_contains), edx);
    __ testl(eax, eax);
    __ jcc(Assembler::notZero, caller_not_deoptimized);

    // Compute size of arguments for saving when returning to deoptimized caller
    __ get_method(eax);
    __ verify_oop(eax);    
    __ load_unsigned_word(eax, Address(eax, in_bytes(methodOopDesc::size_of_parameters_offset())));
    __ shll(eax, Interpreter::logStackElementSize());
    __ restore_locals();
    __ subl(edi, eax);
    __ addl(edi, wordSize);
    // Save these arguments
    __ get_thread(ecx);
    __ super_call_VM_leaf(CAST_FROM_FN_PTR(address, Deoptimization::popframe_preserve_args), ecx, eax, edi);

    __ remove_activation(vtos, edx, 
			 /* throw_monitor_exception */ false, 
			 /* install_monitor_exception */ false,
			 /* notify_jvmdi */ false);

    // Inform deoptimization that it is responsible for restoring these arguments
    __ get_thread(ecx);
    __ movl(Address(ecx, JavaThread::popframe_condition_offset()), JavaThread::popframe_force_deopt_reexecution_bit);

    // Continue in deoptimization handler
    __ jmp(edx);

    __ bind(caller_not_deoptimized);
  }

  __ remove_activation(vtos, edx, 
                       /* throw_monitor_exception */ false, 
                       /* install_monitor_exception */ false,
                       /* notify_jvmdi */ false);

  // Finish with popframe handling
  // A previous I2C followed by a deoptimization might have moved the
  // outgoing arguments further up the stack. PopFrame expects the
  // mutations to those outgoing arguments to be preserved and other
  // constraints basically require this frame to look exactly as
  // though it had previously invoked an interpreted activation with
  // no space between the top of the expression stack (current
  // last_sp) and the top of stack. Rather than force deopt to
  // maintain this kind of invariant all the time we call a small
  // fixup routine to move the mutated arguments onto the top of our
  // expression stack if necessary.
  __ movl(eax, esp);
  __ movl(ebx, Address(ebp, frame::interpreter_frame_last_sp_offset * wordSize));
  __ get_thread(ecx);
  // PC must point into interpreter here
  __ set_last_Java_frame(ecx, noreg, ebp, __ pc());
  __ super_call_VM_leaf(CAST_FROM_FN_PTR(address, InterpreterRuntime::popframe_move_outgoing_args), ecx, eax, ebx);
  __ get_thread(ecx);
  __ reset_last_Java_frame(ecx, true, true);
  // Restore the last_sp and null it out
  __ movl(esp, Address(ebp, frame::interpreter_frame_last_sp_offset * wordSize));
  __ movl(Address(ebp, frame::interpreter_frame_last_sp_offset * wordSize), NULL_WORD);

  __ restore_bcp();
  __ restore_locals();
  // The method data pointer was incremented already during
  // call profiling. We have to restore the mdp for the current bcp.
  if (ProfileInterpreter) {
    __ set_method_data_pointer_for_bcp();
  }

  // Clear the popframe condition flag
  __ get_thread(ecx);
  __ movl(Address(ecx, JavaThread::popframe_condition_offset()), JavaThread::popframe_inactive);

  __ dispatch_next(vtos);
  // end of PopFrame support

  Interpreter::_remove_activation_entry = __ pc();
  
  // preserve exception over this code sequence
  __ pop_ptr(eax);
  __ get_thread(ecx);
  __ movl(Address(ecx, JavaThread::vm_result_offset()), eax);
  // remove the activation (without doing throws on illegalMonitorExceptions)
  __ remove_activation(vtos, edx, false, true, false);
  // restore exception
  __ get_thread(ecx);
  __ movl(eax, Address(ecx, JavaThread::vm_result_offset()));
  __ movl(Address(ecx, JavaThread::vm_result_offset()), NULL_WORD);
  __ verify_oop(eax);

  // Inbetween activations - previous activation type unknown yet
  // compute continuation point - the continuation point expects
  // the following registers set up:
  //
  // eax: exception
  // edx: return address/pc that threw exception
  // esp: expression stack of caller
  // ebp: ebp of caller
  __ pushl(eax);                                 // save exception
  __ pushl(edx);                                 // save return address
  __ super_call_VM_leaf(CAST_FROM_FN_PTR(address, SharedRuntime::exception_handler_for_return_address), edx);
  __ movl(ebx, eax);                             // save exception handler
  __ popl(edx);                                  // restore return address
  __ popl(eax);                                  // restore exception
  // Note that an "issuing PC" is actually the next PC after the call
  __ jmp(ebx);                                   // jump to exception handler of caller
}


//
// JVMTI ForceEarlyReturn support
//
address AbstractInterpreterGenerator::generate_earlyret_entry_for(TosState state) {
  address entry = __ pc(); 

  __ restore_bcp();
  __ restore_locals();
  __ empty_expression_stack();
  __ empty_FPU_stack();
  __ load_earlyret_value(state);

  __ get_thread(ecx);
  __ movl(ecx, Address(ecx, JavaThread::jvmti_thread_state_offset()));
  const Address cond_addr(ecx, JvmtiThreadState::earlyret_state_offset());

  // Clear the earlyret state
  __ movl(cond_addr, JvmtiThreadState::earlyret_inactive);

  __ remove_activation(state, esi,
                       false, /* throw_monitor_exception */
                       false, /* install_monitor_exception */
                       true); /* notify_jvmdi */
  __ jmp(esi);
  return entry;
} // end of ForceEarlyReturn support


//------------------------------------------------------------------------------------------------------------------------
// Helper for vtos entry point generation

void AbstractInterpreterGenerator::set_vtos_entry_points (Template* t, address& bep, address& cep, address& sep, address& aep, address& iep, address& lep, address& fep, address& dep, address& vep) {
  assert(t->is_valid() && t->tos_in() == vtos, "illegal template");
  Label L;
  fep = __ pc(); __ push(ftos); __ jmp(L);
  dep = __ pc(); __ push(dtos); __ jmp(L);
  lep = __ pc(); __ push(ltos); __ jmp(L);
  aep = __ pc(); __ push(atos); __ jmp(L);
  bep = cep = sep =             // fall through 
  iep = __ pc(); __ push(itos); // fall through
  vep = __ pc(); __ bind(L);    // fall through
  generate_and_dispatch(t);
}


//------------------------------------------------------------------------------------------------------------------------
// Generation of individual instructions

// helpers for generate_and_dispatch


InterpreterGenerator::InterpreterGenerator(StubQueue* code) 
 : AbstractInterpreterGenerator(code) {
   generate_all(); // down here so it can be "virtual"
}

//------------------------------------------------------------------------------------------------------------------------

// Non-product code
#ifndef PRODUCT
address AbstractInterpreterGenerator::generate_trace_code(TosState state) {
  address entry = __ pc();

  // prepare expression stack
  __ popl(ecx);         // pop return address so expression stack is 'pure'
  __ push(state);       // save tosca

  // pass tosca registers as arguments & call tracer
  __ call_VM(noreg, CAST_FROM_FN_PTR(address, SharedRuntime::trace_bytecode), ecx, eax, edx);
  __ movl(ecx, eax);    // make sure return address is not destroyed by pop(state)
  __ pop(state);        // restore tosca

  // return
  __ jmp(ecx);

  return entry;
}


void AbstractInterpreterGenerator::count_bytecode() { 
  __ increment(Address((int)&BytecodeCounter::_counter_value, relocInfo::none)); 
}


void AbstractInterpreterGenerator::histogram_bytecode(Template* t) { 
  __ increment(Address((int)&BytecodeHistogram::_counters[t->bytecode()], relocInfo::none));
}


void AbstractInterpreterGenerator::histogram_bytecode_pair(Template* t) { 
  __ movl(ebx, Address((int)&BytecodePairHistogram::_index, relocInfo::none));
  __ shrl(ebx, BytecodePairHistogram::log2_number_of_codes);
  __ orl(ebx, ((int)t->bytecode()) << BytecodePairHistogram::log2_number_of_codes);
  __ movl(Address((int)&BytecodePairHistogram::_index, relocInfo::none), ebx);  
  __ increment(Address(noreg, ebx, Address::times_4, (int)BytecodePairHistogram::_counters));
}


void AbstractInterpreterGenerator::trace_bytecode(Template* t) {
  // Call a little run-time stub to avoid blow-up for each bytecode.
  // The run-time runtime saves the right registers, depending on
  // the tosca in-state for the given template.
  address entry = Interpreter::trace_code(t->tos_in());
  assert(entry != NULL, "entry must have been generated");
  __ call(entry, relocInfo::none);
}


void AbstractInterpreterGenerator::stop_interpreter_at() {
  Label L;
  __ cmpl(Address(int(&BytecodeCounter::_counter_value), relocInfo::none), StopInterpreterAt);
  __ jcc(Assembler::notEqual, L);
  __ int3();
  __ bind(L);
}
#endif // !PRODUCT
