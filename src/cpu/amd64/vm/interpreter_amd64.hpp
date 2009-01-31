#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)interpreter_amd64.hpp	1.15 07/05/05 17:04:07 JVM"
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

class Interpreter : public AbstractInterpreter {

 public:
  // offset from esp of n'th item on top of stack (esp is below the item)
  static int expr_offset_in_bytes(int i) { return stackElementSize()*i ; }
  static int expr_tag_offset_in_bytes(int i) {
    assert(TaggedStackInterpreter, "should not call this");
    return expr_offset_in_bytes(i) + wordSize;
  }

  // Size of interpreter code.  Increase if too small.  Interpreter will
  // fail with a guarantee ("not enough space for interpreter generation");
  // if too small.
  // Run with +PrintInterpreterSize to get the VM to print out the size used.
  // Max size with JVMTI and TaggedStackInterpreter
  const static int InterpreterCodeSize = 200 * 1024;
};

// Generation of Interpreter
//
// The InterpreterGenerator generates the interpreter into Interpreter::_code.
//
// After we enter a method and are executing the templates for each bytecode
// the following describes the register usage expected. This state is valid
// when we start to execute a bytecode and when we execute the next bytecode
// Note that rax/rdx are special in the depending on the tosca they may or
// may not be live at entry/exit of the interpretation of a bytecode.
//
// XXX update this list:
// rax: freely usable/caches tos
// rbx: freely usable
// rcx: freely usable
// rdx: freely usable/caches tos
// rdi: data index, points to beginning of locals section on stack
// rsi: freely usable
// rbp: frame pointer
// rsp: stack pointer (top-most element may be cached in registers)
// r13: source index, points to beginning of bytecode (bcp)
// r8-r15: freely usable
class InterpreterGenerator
  : public AbstractInterpreterGenerator 
{
  friend class AbstractInterpreterGenerator;

 public:
  InterpreterGenerator(StubQueue* code);
 private:

  address generate_asm_interpreter_entry(bool synchronized);
  address generate_native_entry(bool synchronized);
  address generate_abstract_entry(void);
  address generate_math_entry(AbstractInterpreter::MethodKind kind);
  address generate_empty_entry(void);
  address generate_accessor_entry(void);
  static address frame_manager_return;
  static address frame_manager_sync_return;
  void lock_method(void);
  void generate_fixed_frame(bool native_call); // asm interpreter only
  void generate_stack_overflow_check(void);

  void generate_counter_incr(
                Label* overflow, 
                Label* profile_method,
                Label* profile_method_continue);
  void generate_counter_overflow(Label* do_continue);
};

