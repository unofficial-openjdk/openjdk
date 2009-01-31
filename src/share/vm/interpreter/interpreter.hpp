#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)interpreter.hpp	1.153 07/05/17 15:54:31 JVM"
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

// This file contains the platform-independant parts
// of the interpreter and the interpreter generator.

//------------------------------------------------------------------------------------------------------------------------
// An InterpreterCodelet is a piece of interpreter code. All
// interpreter code is generated into little codelets which
// contain extra information for debugging and printing purposes.

class InterpreterCodelet: public Stub {
  friend class VMStructs;
 private:
  int         _size;                             // the size in bytes
  const char* _description;                      // a description of the codelet, for debugging & printing
  Bytecodes::Code _bytecode;                     // associated bytecode if any

 public:
  // Initialization/finalization
  void    initialize(int size)                   { _size = size; }
  void    finalize()                             { ShouldNotCallThis(); }

  // General info/converters
  int     size() const                           { return _size; }
  static  int code_size_to_size(int code_size)   { return round_to(sizeof(InterpreterCodelet), CodeEntryAlignment) + code_size; }

  // Code info
  address code_begin() const                     { return (address)this + round_to(sizeof(InterpreterCodelet), CodeEntryAlignment); }
  address code_end() const                       { return (address)this + size(); }

  // Debugging
  void    verify();
  void    print();

  // Interpreter-specific initialization
  void    initialize(const char* description, Bytecodes::Code bytecode);

  // Interpreter-specific attributes
  int         code_size() const                  { return code_end() - code_begin(); }
  const char* description() const                { return _description; }
  Bytecodes::Code bytecode() const               { return _bytecode; }
};


#ifndef CC_INTERP
//------------------------------------------------------------------------------------------------------------------------
// A little wrapper class to group tosca-specific entry points into a unit.
// (tosca = Top-Of-Stack CAche)

class EntryPoint VALUE_OBJ_CLASS_SPEC {
 private:
  address _entry[number_of_states];

 public:
  // Construction
  EntryPoint();
  EntryPoint(address bentry, address centry, address sentry, address aentry, address ientry, address lentry, address fentry, address dentry, address ventry);

  // Attributes
  address entry(TosState state) const;                // return target address for a given tosca state
  void    set_entry(TosState state, address entry);   // set    target address for a given tosca state
  void    print();

  // Comparison
  bool operator == (const EntryPoint& y);             // for debugging only
};


//------------------------------------------------------------------------------------------------------------------------
// A little wrapper class to group tosca-specific dispatch tables into a unit.

class DispatchTable VALUE_OBJ_CLASS_SPEC {
 public:
  enum { length = 1 << BitsPerByte };                 // an entry point for each byte value (also for undefined bytecodes)

 private:
  address _table[number_of_states][length];	      // dispatch tables, indexed by tosca and bytecode

 public:
  // Attributes
  EntryPoint entry(int i) const;                      // return entry point for a given bytecode i
  void       set_entry(int i, EntryPoint& entry);     // set    entry point for a given bytecode i
  address*   table_for(TosState state) 		{ return _table[state]; }
  address*   table_for()			{ return table_for((TosState)0); }
  int	     distance_from(address *table)	{ return table - table_for(); }
  int	     distance_from(TosState state)	{ return distance_from(table_for(state)); }

  // Comparison
  bool operator == (DispatchTable& y);                // for debugging only
};

#endif // CC_INTERP

//------------------------------------------------------------------------------------------------------------------------
// The C++ interface to the bytecode interpreter.

class AbstractInterpreter: AllStatic {
  friend class VMStructs;
  friend class Interpreter;
 public:
  enum MethodKind {        
    zerolocals,                                                 // method needs locals initialization
    zerolocals_synchronized,                                    // method needs locals initialization & is synchronized
    native,                                                     // native method
    native_synchronized,                                        // native method & is synchronized
    empty,                                                      // empty method (code: _return)
    accessor,                                                   // accessor method (code: _aload_0, _getfield, _(a|i)return)
    abstract,                                                   // abstract method (throws an AbstractMethodException)
    java_lang_math_sin,                                         // implementation of java.lang.Math.sin   (x)
    java_lang_math_cos,                                         // implementation of java.lang.Math.cos   (x)
    java_lang_math_tan,                                         // implementation of java.lang.Math.tan   (x)
    java_lang_math_abs,                                         // implementation of java.lang.Math.abs   (x)
    java_lang_math_sqrt,                                        // implementation of java.lang.Math.sqrt  (x)
    java_lang_math_log,                                         // implementation of java.lang.Math.log   (x)
    java_lang_math_log10,                                       // implementation of java.lang.Math.log10 (x)
    number_of_method_entries,
    invalid = -1
  };

  enum SomeConstants {
#ifndef CC_INTERP
    number_of_return_entries  = 9,                              // number of return entry points
    number_of_deopt_entries   = 9,                              // number of deoptimization entry points
    number_of_return_addrs    = 9,                              // number of return addresses
#endif // CC_INTERP
    number_of_result_handlers = 10                              // number of result handlers for native calls
  };    

 protected:
  static StubQueue* _code;                                      // the interpreter code (codelets)
  
  static address    _rethrow_exception_entry;                   // rethrows an activation in previous frame
#ifdef HOTSWAP
  static address    _remove_activation_preserving_args_entry;   // continuation address when current frame is being popped
#endif // HOTSWAP

#ifndef CC_INTERP
  static address    _throw_ArrayIndexOutOfBoundsException_entry;
  static address    _throw_ArrayStoreException_entry;
  static address    _throw_ArithmeticException_entry;
  static address    _throw_ClassCastException_entry;
  static address    _throw_NullPointerException_entry;
  static address    _throw_StackOverflowError_entry;
  static address    _throw_exception_entry;

  static address    _remove_activation_entry;                   // continuation address if an exception is not handled by current frame

#ifndef PRODUCT
  static EntryPoint _trace_code;
#endif // !PRODUCT
  static EntryPoint _return_entry[number_of_return_entries];    // entry points to return to from a call
  static EntryPoint _earlyret_entry;                            // entry point to return early from a call
  static EntryPoint _deopt_entry[number_of_deopt_entries];      // entry points to return to from a deoptimization
  static EntryPoint _continuation_entry;
  static EntryPoint _safept_entry;

  static address    _return_3_addrs_by_index[number_of_return_addrs];     // for invokevirtual   return entries
  static address    _return_5_addrs_by_index[number_of_return_addrs];     // for invokeinterface return entries

  static DispatchTable _active_table;                           // the active    dispatch table (used by the interpreter for dispatch)
  static DispatchTable _normal_table;                           // the normal    dispatch table (used to set the active table in normal mode)
  static DispatchTable _safept_table;                           // the safepoint dispatch table (used to set the active table for safepoints)
  static address       _wentry_point[DispatchTable::length];    // wide instructions only (vtos tosca always)
  
#endif // CC_INTERP
  static bool       _notice_safepoints;                         // true if safepoints are activated

  static address    _native_entry_begin;                        // Region for native entry code
  static address    _native_entry_end;

  // method entry points
  static address    _entry_table[number_of_method_entries];     // entry points for a given method
  static address    _native_abi_to_tosca[number_of_result_handlers];  // for native method result handlers
#ifdef CC_INTERP
  // tosca result -> stack result
  static address    _tosca_to_stack[number_of_result_handlers];  // converts tosca to C++ interpreter stack result
  // stack result -> stack result
  static address    _stack_to_stack[number_of_result_handlers];  // pass result between C++ interpreter calls
  // stack result -> native abi result
  static address    _stack_to_native_abi[number_of_result_handlers];  // converts C++ interpreter results to native abi
#endif
  static address    _slow_signature_handler;                              // the native method generic (slow) signature handler


  
  friend class      TemplateTable;
  friend class      AbstractInterpreterGenerator;
  friend class              InterpreterGenerator;
  friend class      InterpreterMacroAssembler;

 public:
  // Initialization/debugging
  static void       initialize();
  static StubQueue* code()                                      { return _code; }
  // this only returns whether a pc is within generated code for the interpreter.
#ifdef CC_INTERP
 private:
  // for the c++ based interpreter this misses much code.  make sure it doesn't get called.
#endif // CC_INTERP
  static bool       contains(address pc)                        { return _code->contains(pc); }

 public:

  // Method activation
  static MethodKind method_kind(methodHandle m);
  static address    entry_for_kind(MethodKind k)                { assert(0 <= k && k < number_of_method_entries, "illegal kind"); return _entry_table[k]; }
  static address    entry_for_method(methodHandle m)            { return _entry_table[method_kind(m)]; }

  static void       print_method_kind(MethodKind kind)          PRODUCT_RETURN;

  // Runtime support

  static address    rethrow_exception_entry()                   { return _rethrow_exception_entry; }

  static address    return_entry  (TosState state, int length); // length = invoke bytecode length (to advance to next bytecode)
  static address    deopt_entry   (TosState state, int length); // length = invoke bytecode length (to advance to next bytecode)

#ifdef HOTSWAP
  static address    remove_activation_preserving_args_entry()   { return _remove_activation_preserving_args_entry; }
#endif // HOTSWAP

#ifndef CC_INTERP
  static address    remove_activation_early_entry(TosState state) { return _earlyret_entry.entry(state); }
  static address    remove_activation_entry()                   { return _remove_activation_entry; }
  static address    throw_exception_entry()                     { return _throw_exception_entry; }
  static address    throw_ArithmeticException_entry()           { return _throw_ArithmeticException_entry; }
  static address    throw_NullPointerException_entry()          { return _throw_NullPointerException_entry; }
  static address    throw_StackOverflowError_entry()            { return _throw_StackOverflowError_entry; }

  // Code generation
#ifndef PRODUCT
  static address    trace_code    (TosState state)              { return _trace_code.entry(state); }
#endif // !PRODUCT
  static address    continuation  (TosState state)              { return _continuation_entry.entry(state); }
  static address*   dispatch_table(TosState state)              { return _active_table.table_for(state); }
  static address*   dispatch_table()                            { return _active_table.table_for(); }
  static int        distance_from_dispatch_table(TosState state){ return _active_table.distance_from(state); }
  static address*   normal_table(TosState state)                { return _normal_table.table_for(state); }
  static address*   normal_table()                              { return _normal_table.table_for(); }

  // Support for invokes
  static address*   return_3_addrs_by_index_table()             { return _return_3_addrs_by_index; }
  static address*   return_5_addrs_by_index_table()             { return _return_5_addrs_by_index; }
  static int        TosState_as_index(TosState state);          // computes index into return_3_entry_by_index table
#endif // CC_INTERP


  // Activation size in words for a method that is just being called.
  // Parameters haven't been pushed so count them too.
  static int        size_top_interpreter_activation(methodOop method);

  // Deoptimization support
  static address    continuation_for(methodOop method,
				     address bcp,
				     int callee_parameters,
				     bool is_top_frame,
				     bool& use_next_mdp);

  // share implementation of size_activation and layout_activation:
  static int        size_activation(methodOop method,
				    int temps,
                                    int popframe_args,
				    int monitors,
				    int callee_params,
				    int callee_locals,
				    bool is_top_frame);

  static int       layout_activation(methodOop method,
				      int temps,
                                      int popframe_args,
				      int monitors,
				      int callee_params,
				      int callee_locals,
				      frame* caller,
				      frame* interpreter_frame,
				      bool is_top_frame);

  // Runtime support
  static bool       is_not_reached(                       methodHandle method, int bci);
  // Safepoint support
  static void       notice_safepoints();                        // stops the thread when reaching a safepoint
  static void       ignore_safepoints();                        // ignores safepoints

  // Support for native calls
  static address    slow_signature_handler()                    { return _slow_signature_handler; }
  static address    result_handler(BasicType type)              { return _native_abi_to_tosca[BasicType_as_index(type)]; }
  static int        BasicType_as_index(BasicType type);         // computes index into result_handler_by_index table
  static bool       in_native_entry(address pc)                 { return _native_entry_begin <= pc && pc < _native_entry_end; }
  // Debugging/printing
  static InterpreterCodelet* codelet_containing(address pc)     { return (InterpreterCodelet*)_code->stub_containing(pc); }
  static void       print();                                    // prints the interpreter code
#ifdef CC_INTERP
  static address    native_result_to_tosca()                    { return (address)_native_abi_to_tosca; } // aka result handler
  static address    tosca_result_to_stack()                     { return (address)_tosca_to_stack; }
  static address    stack_result_to_stack()                     { return (address)_stack_to_stack; }
  static address    stack_result_to_native()                    { return (address)_stack_to_native_abi; }

  static address    native_result_to_tosca(int index)           { return _native_abi_to_tosca[index]; } // aka result handler
  static address    tosca_result_to_stack(int index)            { return _tosca_to_stack[index]; }
  static address    stack_result_to_stack(int index)            { return _stack_to_stack[index]; }
  static address    stack_result_to_native(int index)           { return _stack_to_native_abi[index]; }
#endif /* CC_INTERP */

  // Support for Tagged Stacks
  //
  // Tags are stored on the Java Expression stack above the value:
  // 
  //  tag   
  //  value
  //
  // For double values:
  //
  //  tag2  
  //  high word
  //  tag1
  //  low word

 public:
  static int stackElementWords()   { return TaggedStackInterpreter ? 2 : 1; }
  static int stackElementSize()    { return stackElementWords()*wordSize; }
  static int logStackElementSize() { return
                 TaggedStackInterpreter? LogBytesPerWord+1 : LogBytesPerWord; }

  // Tag is at pointer, value is one below for a stack growing down
  // (or above for stack growing up)
  static int  value_offset_in_bytes()  {
    return TaggedStackInterpreter ?
      frame::interpreter_frame_expression_stack_direction() * wordSize : 0;
  }
  static int  tag_offset_in_bytes()    { 
    assert(TaggedStackInterpreter, "should not call this");
    return 0;
  }

  // Tagged Locals
  // Locals are stored relative to Llocals:
  //
  // tag    <- Llocals[n]
  // value
  //
  // Category 2 types are indexed as:
  //
  // tag    <- Llocals[-n]
  // high word
  // tag    <- Llocals[-n+1]
  // low word
  //

  // Local values relative to locals[n]
  static int  local_offset_in_bytes(int n) {
    return ((frame::interpreter_frame_expression_stack_direction() * n) *
            stackElementSize()) + value_offset_in_bytes();
  }
  static int  local_tag_offset_in_bytes(int n) {
    assert(TaggedStackInterpreter, "should not call this");
    return ((frame::interpreter_frame_expression_stack_direction() * n) * 
            stackElementSize()) + tag_offset_in_bytes();
  }

};


//------------------------------------------------------------------------------------------------------------------------
// The interpreter generator.

class Template;
class AbstractInterpreterGenerator: public StackObj {
 protected:
  InterpreterMacroAssembler* _masm;

#ifndef CC_INTERP
  // entry points for shared code sequence
  address _unimplemented_bytecode;
  address _illegal_bytecode_sequence;
#endif

  // shared code sequences
  // Converter for native abi result to tosca result
  address generate_result_handler_for(BasicType type);
#ifdef CC_INTERP
  address generate_tosca_to_stack_converter(BasicType type);
  address generate_stack_to_stack_converter(BasicType type);
  address generate_stack_to_native_abi_converter(BasicType type);
#endif
  address generate_slow_signature_handler();
#ifndef CC_INTERP
  address generate_error_exit(const char* msg);
  address generate_StackOverflowError_handler();
  address generate_exception_handler(const char* name, const char* message) {
    return generate_exception_handler_common(name, message, false);
  }
  address generate_klass_exception_handler(const char* name) {
    return generate_exception_handler_common(name, NULL, true);
  }
  address generate_exception_handler_common(const char* name, const char* message, bool pass_oop);
  address generate_ClassCastException_handler();
  address generate_ArrayIndexOutOfBounds_handler(const char* name);
  address generate_continuation_for(TosState state);
  address generate_return_entry_for(TosState state, int step);
  address generate_earlyret_entry_for(TosState state);
  address generate_deopt_entry_for(TosState state, int step);
  address generate_safept_entry_for(TosState state, address runtime_entry);
  void    generate_throw_exception();
#endif // CC_INTERP

  // entry point generator
  address generate_method_entry(AbstractInterpreter::MethodKind kind);
  void    generate_fast_accessor_code(); // implements UseFastAccessorMethods
  
#ifndef CC_INTERP
  // Instruction generation
  void generate_and_dispatch (Template* t, TosState tos_out = ilgl);
  void set_vtos_entry_points (Template* t, address& bep, address& cep, address& sep, address& aep, address& iep, address& lep, address& fep, address& dep, address& vep);
  void set_short_entry_points(Template* t, address& bep, address& cep, address& sep, address& aep, address& iep, address& lep, address& fep, address& dep, address& vep);
  void set_wide_entry_point  (Template* t, address& wep);

  void set_entry_points(Bytecodes::Code code);
  void set_unimplemented(int i);
  void set_entry_points_for_all_bytes();
  void set_safepoints_for_all_bytes();

  // Helpers for generate_and_dispatch
  address generate_trace_code(TosState state)   PRODUCT_RETURN0;
  void count_bytecode()                         PRODUCT_RETURN;  
  void histogram_bytecode(Template* t)          PRODUCT_RETURN;
  void histogram_bytecode_pair(Template* t)     PRODUCT_RETURN;
  void trace_bytecode(Template* t)              PRODUCT_RETURN;
  void stop_interpreter_at()                    PRODUCT_RETURN;
#endif // CC_INTERP

  void bang_stack_shadow_pages(bool native_call);

  void generate_all();

 public:
  AbstractInterpreterGenerator(StubQueue* _code);
};
