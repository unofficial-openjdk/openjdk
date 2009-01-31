#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)runtime_i486.cpp	1.110 07/05/05 17:04:19 JVM"
#endif
/*
 * Copyright 1998-2006 Sun Microsystems, Inc.  All Rights Reserved.
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
#include "incls/_runtime_i486.cpp.incl"

#define __ masm->

ExceptionBlob*     OptoRuntime::_exception_blob;

//------------------------------generate_exception_blob---------------------------
// creates exception blob at the end
// Using exception blob, this code is jumped from a compiled method.
// 
// Given an exception pc at a call we call into the runtime for the
// handler in this method. This handler might merely restore state
// (i.e. callee save registers) unwind the frame and jump to the
// exception handler for the nmethod if there is no Java level handler
// for the nmethod.
//
// This code is entered with a jmp.
// 
// Arguments:
//   eax: exception oop
//   edx: exception pc
//
// Results:
//   eax: exception oop
//   edx: exception pc in caller or ???
//   destination: exception handler of caller
// 
// Note: the exception pc MUST be at a call (precise debug information)
//       Only register eax, edx, ecx are not callee saved.
//

void OptoRuntime::generate_exception_blob() {

  // Capture info about frame layout  
  enum layout { 
    thread_off,                 // last_java_sp                
    // The frame sender code expects that rbp will be in the "natural" place and
    // will override any oopMap setting for it. We must therefore force the layout
    // so that it agrees with the frame sender code.
    ebp_off,                
    return_off,                 // slot for return address
    framesize 
  };

  // allocate space for the code
  ResourceMark rm;
  // setup code generation tools  
  CodeBuffer   buffer("exception_blob", 512, 512);
  MacroAssembler* masm = new MacroAssembler(&buffer);

  OopMapSet *oop_maps = new OopMapSet();

  address start = __ pc();  

  __ pushl(edx);
  __ subl(esp, return_off * wordSize);   // Prolog!

  // ebp location is implicitly known
  __ movl(Address(esp,ebp_off  *wordSize),ebp);
          
  // Store exception in Thread object. We cannot pass any arguments to the
  // handle_exception call, since we do not want to make any assumption
  // about the size of the frame where the exception happened in.
  __ get_thread(ecx);
  __ movl(Address(ecx, JavaThread::exception_oop_offset()), eax);
  __ movl(Address(ecx, JavaThread::exception_pc_offset()),  edx);

  // This call does all the hard work.  It checks if an exception handler
  // exists in the method.  
  // If so, it returns the handler address.
  // If not, it prepares for stack-unwinding, restoring the callee-save 
  // registers of the frame being removed.
  //  
  __ movl(Address(esp, thread_off * wordSize), ecx); // Thread is first argument
  __ set_last_Java_frame(ecx, noreg, noreg, NULL);
  __ call(CAST_FROM_FN_PTR(address, OptoRuntime::handle_exception_C), relocInfo::runtime_call_type);

  // No registers to map, ebp is known implicitly
  oop_maps->add_gc_map( __ pc() - start,  new OopMap( framesize, 0 ));
  __ get_thread(ecx);
  __ reset_last_Java_frame(ecx, false, false);

  // Restore callee-saved registers
  __ movl(ebp, Address(esp, ebp_off * wordSize));

  __ addl(esp, return_off * wordSize);   // Epilog!
  __ popl(edx); // Exception pc


  // eax: exception handler for given <exception oop/exception pc>
  
  // We have a handler in eax (could be deopt blob)
  // edx - throwing pc, deopt blob will need it.

  __ pushl(eax); 

  // ecx contains handler address

  __ get_thread(ecx);           // TLS
  // Get the exception
  __ movl(eax, Address(ecx, JavaThread::exception_oop_offset()));
  // Get the exception pc in case we are deoptimized
  __ movl(edx, Address(ecx, JavaThread::exception_pc_offset()));
#ifdef ASSERT
  __ movl(Address(ecx, JavaThread::exception_handler_pc_offset()), 0);
  __ movl(Address(ecx, JavaThread::exception_pc_offset()), 0); 
#endif
  // Clear the exception oop so GC no longer processes it as a root.
  __ movl(Address(ecx, JavaThread::exception_oop_offset()), 0);

  __ popl(ecx);

  // eax: exception oop
  // ecx: exception handler
  // edx: exception pc
  __ jmp (ecx);

  // -------------
  // make sure all code is generated
  masm->flush();  

  _exception_blob = ExceptionBlob::create(&buffer, oop_maps, framesize);  
}
