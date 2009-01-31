#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)interpreter.cpp	1.246 07/06/08 15:21:43 JVM"
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
#include "incls/_interpreter.cpp.incl"

# define __ _masm->


//------------------------------------------------------------------------------------------------------------------------
// Implementation of InterpreterCodelet

void InterpreterCodelet::initialize(const char* description, Bytecodes::Code bytecode) {
  _description       = description;
  _bytecode          = bytecode;
}


void InterpreterCodelet::verify() {
}


void InterpreterCodelet::print() {
  if (PrintInterpreter) {
    tty->cr();
    tty->print_cr("----------------------------------------------------------------------");
  }

  if (description() != NULL) tty->print("%s  ", description());
  if (bytecode()    >= 0   ) tty->print("%d %s  ", bytecode(), Bytecodes::name(bytecode()));
  tty->print_cr("[" INTPTR_FORMAT ", " INTPTR_FORMAT "]  %d bytes",
		code_begin(), code_end(), code_size());

  if (PrintInterpreter) {
    tty->cr();
    Disassembler::decode(code_begin(), code_end(), tty);
  }
}


//------------------------------------------------------------------------------------------------------------------------
// Implementation of AbstractInterpreter


// Define a prototype interface
DEF_STUB_INTERFACE(InterpreterCodelet);


void AbstractInterpreter::initialize() {
  if (_code != NULL) return;

  // assertions
#ifndef CC_INTERP
  assert((int)Bytecodes::number_of_codes <= (int)DispatchTable::length, 
         "dispatch table too small");
#endif /* !CC_INTERP */

  // make sure 'imported' classes are initialized
  if (CountBytecodes || TraceBytecodes || StopInterpreterAt) BytecodeCounter::reset();
  if (PrintBytecodeHistogram)                                BytecodeHistogram::reset();
  if (PrintBytecodePairHistogram)                            BytecodePairHistogram::reset();
#ifndef CC_INTERP
  TemplateTable::initialize();
#endif /* !CC_INTERP */
  InvocationCounter::reinitialize(DelayCompilationDuringStartup);

  // generate interpreter
  { ResourceMark rm;
    TraceTime timer("Interpreter generation", TraceStartupTime);    
    int code_size = Interpreter::InterpreterCodeSize;
    NOT_PRODUCT(code_size *= 4;)  // debug uses extra interpreter code space
    _code = new StubQueue(new InterpreterCodeletInterface, code_size, NULL,
                          "Interpreter");
    InterpreterGenerator g(_code);
    if (PrintInterpreter) print();
  }

#ifdef CC_INTERP
  {
    // Allow c++ interpreter to do one initialization now that switches are set, etc.
    cInterpreter start_msg(cInterpreter::initialize);
    if (JvmtiExport::can_post_interpreter_events())
      cInterpreter::InterpretMethodWithChecks(&start_msg);
    else
      cInterpreter::InterpretMethod(&start_msg);
  }
#else
  // initialize dispatch table
  _active_table = _normal_table;
#endif // CC_INTERP
}


void AbstractInterpreter::print() {
  tty->cr();
  tty->print_cr("----------------------------------------------------------------------");
  tty->print_cr("Interpreter");
  tty->cr();
  tty->print_cr("code size        = %6dK bytes", (int)_code->used_space()/1024);
  tty->print_cr("total space      = %6dK bytes", (int)_code->total_space()/1024);
  tty->print_cr("wasted space     = %6dK bytes", (int)_code->available_space()/1024);
  tty->cr();
  tty->print_cr("# of codelets    = %6d"      , _code->number_of_stubs());
  tty->print_cr("avg codelet size = %6d bytes", _code->used_space() / _code->number_of_stubs());
  tty->cr();
  _code->print();
  tty->print_cr("----------------------------------------------------------------------");
  tty->cr();
}


void interpreter_init() {
  Interpreter::initialize();
#ifndef PRODUCT
  if (TraceBytecodes) BytecodeTracer::set_closure(BytecodeTracer::std_closure());
#endif // PRODUCT
  // need to hit every safepoint in order to call zapping routine
  // register the interpreter
  VTune::register_stub(
    "Interpreter",
    AbstractInterpreter::code()->code_start(),
    AbstractInterpreter::code()->code_end()
  );
  Forte::register_stub(
    "Interpreter",
    AbstractInterpreter::code()->code_start(),
    AbstractInterpreter::code()->code_end()
  );

  // notify JVMTI profiler
  if (JvmtiExport::should_post_dynamic_code_generated()) {
    JvmtiExport::post_dynamic_code_generated("Interpreter",
					     AbstractInterpreter::code()->code_start(),
					     AbstractInterpreter::code()->code_end());
  }
}


#ifndef CC_INTERP
//------------------------------------------------------------------------------------------------------------------------
// Implementation of EntryPoint

EntryPoint::EntryPoint() {
  assert(number_of_states == 9, "check the code below");
  _entry[btos] = NULL;
  _entry[ctos] = NULL;
  _entry[stos] = NULL;
  _entry[atos] = NULL;
  _entry[itos] = NULL;
  _entry[ltos] = NULL;
  _entry[ftos] = NULL;
  _entry[dtos] = NULL;
  _entry[vtos] = NULL;
}


EntryPoint::EntryPoint(address bentry, address centry, address sentry, address aentry, address ientry, address lentry, address fentry, address dentry, address ventry) {
  assert(number_of_states == 9, "check the code below");
  _entry[btos] = bentry;
  _entry[ctos] = centry;
  _entry[stos] = sentry;
  _entry[atos] = aentry;
  _entry[itos] = ientry;
  _entry[ltos] = lentry;
  _entry[ftos] = fentry;
  _entry[dtos] = dentry;
  _entry[vtos] = ventry;
}


void EntryPoint::set_entry(TosState state, address entry) {
  assert(0 <= state && state < number_of_states, "state out of bounds");
  _entry[state] = entry;
}


address EntryPoint::entry(TosState state) const {
  assert(0 <= state && state < number_of_states, "state out of bounds");
  return _entry[state];
}


void EntryPoint::print() {
  tty->print("[");
  for (int i = 0; i < number_of_states; i++) {
    if (i > 0) tty->print(", ");
    tty->print(INTPTR_FORMAT, _entry[i]);
  }
  tty->print("]");
}


bool EntryPoint::operator == (const EntryPoint& y) {
  int i = number_of_states;
  while (i-- > 0) {
    if (_entry[i] != y._entry[i]) return false;
  }
  return true;
}


//------------------------------------------------------------------------------------------------------------------------
// Implementation of DispatchTable

EntryPoint DispatchTable::entry(int i) const {
  assert(0 <= i && i < length, "index out of bounds");
  return
    EntryPoint(
      _table[btos][i],
      _table[ctos][i],
      _table[stos][i],
      _table[atos][i],
      _table[itos][i],
      _table[ltos][i],
      _table[ftos][i],
      _table[dtos][i],
      _table[vtos][i]
    );
}


void DispatchTable::set_entry(int i, EntryPoint& entry) {
  assert(0 <= i && i < length, "index out of bounds");
  assert(number_of_states == 9, "check the code below");
  _table[btos][i] = entry.entry(btos);
  _table[ctos][i] = entry.entry(ctos);
  _table[stos][i] = entry.entry(stos);
  _table[atos][i] = entry.entry(atos);
  _table[itos][i] = entry.entry(itos);
  _table[ltos][i] = entry.entry(ltos);
  _table[ftos][i] = entry.entry(ftos);
  _table[dtos][i] = entry.entry(dtos);
  _table[vtos][i] = entry.entry(vtos);
}


bool DispatchTable::operator == (DispatchTable& y) {
  int i = length;
  while (i-- > 0) {
    EntryPoint t = y.entry(i); // for compiler compatibility (BugId 4150096)
    if (!(entry(i) == t)) return false;
  }
  return true;
}
#endif // CC_INTERP


//------------------------------------------------------------------------------------------------------------------------
// Implementation of interpreter

StubQueue* AbstractInterpreter::_code                                       = NULL;
bool       AbstractInterpreter::_notice_safepoints                          = false;

address    AbstractInterpreter::_rethrow_exception_entry                    = NULL;
#ifndef CC_INTERP
address    AbstractInterpreter::_remove_activation_entry                    = NULL;
address    AbstractInterpreter::_remove_activation_preserving_args_entry    = NULL;


address    AbstractInterpreter::_throw_ArrayIndexOutOfBoundsException_entry = NULL;
address    AbstractInterpreter::_throw_ArrayStoreException_entry            = NULL;
address    AbstractInterpreter::_throw_ArithmeticException_entry            = NULL;
address    AbstractInterpreter::_throw_ClassCastException_entry             = NULL;
address    AbstractInterpreter::_throw_NullPointerException_entry           = NULL;
address    AbstractInterpreter::_throw_StackOverflowError_entry             = NULL;
address    AbstractInterpreter::_throw_exception_entry                      = NULL;

#ifndef PRODUCT
EntryPoint AbstractInterpreter::_trace_code;
#endif // !PRODUCT
EntryPoint AbstractInterpreter::_return_entry[AbstractInterpreter::number_of_return_entries];
EntryPoint AbstractInterpreter::_earlyret_entry;
EntryPoint AbstractInterpreter::_deopt_entry [AbstractInterpreter::number_of_deopt_entries ];
EntryPoint AbstractInterpreter::_continuation_entry;
EntryPoint AbstractInterpreter::_safept_entry;

address    AbstractInterpreter::_return_3_addrs_by_index[AbstractInterpreter::number_of_return_addrs];
address    AbstractInterpreter::_return_5_addrs_by_index[AbstractInterpreter::number_of_return_addrs];

DispatchTable AbstractInterpreter::_active_table;
DispatchTable AbstractInterpreter::_normal_table;
DispatchTable AbstractInterpreter::_safept_table;
address    AbstractInterpreter::_wentry_point[DispatchTable::length];
#endif // CC_INTERP

address    AbstractInterpreter::_native_entry_begin                         = NULL;
address    AbstractInterpreter::_native_entry_end                           = NULL;
address    AbstractInterpreter::_slow_signature_handler;
address    AbstractInterpreter::_entry_table            [AbstractInterpreter::number_of_method_entries];
address    AbstractInterpreter::_native_abi_to_tosca    [AbstractInterpreter::number_of_result_handlers];
#ifdef CC_INTERP
address    AbstractInterpreter::_tosca_to_stack         [AbstractInterpreter::number_of_result_handlers];
address    AbstractInterpreter::_stack_to_stack         [AbstractInterpreter::number_of_result_handlers];
address    AbstractInterpreter::_stack_to_native_abi    [AbstractInterpreter::number_of_result_handlers];
#endif


//------------------------------------------------------------------------------------------------------------------------
// A CodeletMark serves as an automatic creator/initializer for Codelets
// (As a subclass of ResourceMark it automatically GC's the allocated
// code buffer and assemblers).

class CodeletMark: ResourceMark {
 private:
  InterpreterCodelet*         _clet;
  InterpreterMacroAssembler** _masm;
  CodeBuffer                  _cb;

  int codelet_size() {
    // Request the whole code buffer (minus a little for alignment).
    // The commit call below trims it back for each codelet.
    int codelet_size = AbstractInterpreter::code()->available_space() - 2*K;

    // Guarantee there's a little bit of code space left.
    guarantee (codelet_size > 0 && (size_t)codelet_size >  2*K,
               "not enough space for interpreter generation");

    return codelet_size;
  }

 public:
  CodeletMark(
    InterpreterMacroAssembler*& masm,
    const char* description,
    Bytecodes::Code bytecode = Bytecodes::_illegal):
    _clet((InterpreterCodelet*)AbstractInterpreter::code()->request(codelet_size())),
    _cb(_clet->code_begin(), _clet->code_size())

  { // request all space (add some slack for Codelet data)
    assert (_clet != NULL, "we checked not enough space already");

    // initialize Codelet attributes
    _clet->initialize(description, bytecode);
    // create assembler for code generation
    masm  = new InterpreterMacroAssembler(&_cb);
    _masm = &masm;
  }  
  
  ~CodeletMark() {
    // align so printing shows nop's instead of random code at the end (Codelets are aligned)
    (*_masm)->align(wordSize);
    // make sure all code is in code buffer
    (*_masm)->flush();


    // commit Codelet
    AbstractInterpreter::code()->commit((*_masm)->code()->pure_code_size());
    // make sure nobody can use _masm outside a CodeletMark lifespan
    *_masm = NULL;
  }
};


//------------------------------------------------------------------------------------------------------------------------
// Generation of complete interpreter

AbstractInterpreterGenerator::AbstractInterpreterGenerator(StubQueue* _code) {
  _masm                      = NULL;
#ifndef CC_INTERP
  _unimplemented_bytecode    = NULL;
  _illegal_bytecode_sequence = NULL;
#endif // CC_INTERP
}


void AbstractInterpreterGenerator::generate_all() {
#ifndef CC_INTERP
  { CodeletMark cm(_masm, "error exits");
    _unimplemented_bytecode    = generate_error_exit("unimplemented bytecode");
    _illegal_bytecode_sequence = generate_error_exit("illegal bytecode sequence - method not verified");
  }

#ifndef PRODUCT
  if (TraceBytecodes) {
    CodeletMark cm(_masm, "bytecode tracing support");
    Interpreter::_trace_code =
      EntryPoint(
        generate_trace_code(btos),
        generate_trace_code(ctos),
        generate_trace_code(stos),
        generate_trace_code(atos),
        generate_trace_code(itos),
        generate_trace_code(ltos),
        generate_trace_code(ftos),
        generate_trace_code(dtos),
        generate_trace_code(vtos)
      );
  }
#endif // !PRODUCT

  { CodeletMark cm(_masm, "return entry points");
    for (int i = 0; i < Interpreter::number_of_return_entries; i++) {
      Interpreter::_return_entry[i] =
        EntryPoint(
          generate_return_entry_for(itos, i),
          generate_return_entry_for(itos, i),
          generate_return_entry_for(itos, i),
          generate_return_entry_for(atos, i),
          generate_return_entry_for(itos, i),
          generate_return_entry_for(ltos, i),
          generate_return_entry_for(ftos, i),
          generate_return_entry_for(dtos, i),
          generate_return_entry_for(vtos, i)
        );
    }
  }

  { CodeletMark cm(_masm, "earlyret entry points");
    Interpreter::_earlyret_entry =
      EntryPoint(
        generate_earlyret_entry_for(btos),
        generate_earlyret_entry_for(ctos),
        generate_earlyret_entry_for(stos),
        generate_earlyret_entry_for(atos),
        generate_earlyret_entry_for(itos),
        generate_earlyret_entry_for(ltos),
        generate_earlyret_entry_for(ftos),
        generate_earlyret_entry_for(dtos),
        generate_earlyret_entry_for(vtos)
      );
  }

  { CodeletMark cm(_masm, "deoptimization entry points");
    for (int i = 0; i < Interpreter::number_of_deopt_entries; i++) {
      Interpreter::_deopt_entry[i] =
        EntryPoint(
          generate_deopt_entry_for(itos, i),
          generate_deopt_entry_for(itos, i),
          generate_deopt_entry_for(itos, i),
          generate_deopt_entry_for(atos, i),
          generate_deopt_entry_for(itos, i),
          generate_deopt_entry_for(ltos, i),
          generate_deopt_entry_for(ftos, i),
          generate_deopt_entry_for(dtos, i),
          generate_deopt_entry_for(vtos, i)
        );
    }
  }

#endif // !CC_INTERP

  { CodeletMark cm(_masm, "result handlers for native calls");
    const BasicType types[Interpreter::number_of_result_handlers] = {
      T_BOOLEAN,
      T_CHAR   ,
      T_BYTE   ,
      T_SHORT  ,
      T_INT    ,
      T_LONG   ,
      T_VOID   ,
      T_FLOAT  ,
      T_DOUBLE ,
      T_OBJECT
    };
    // The various result converter stublets.
    int is_generated[Interpreter::number_of_result_handlers];
    memset(is_generated, 0, sizeof(is_generated));
#ifdef CC_INTERP
    int _tosca_to_stack_is_generated[Interpreter::number_of_result_handlers];
    int _stack_to_stack_is_generated[Interpreter::number_of_result_handlers];
    int _stack_to_native_abi_is_generated[Interpreter::number_of_result_handlers];

    memset(_tosca_to_stack_is_generated, 0, sizeof(_tosca_to_stack_is_generated));
    memset(_stack_to_stack_is_generated, 0, sizeof(_stack_to_stack_is_generated));
    memset(_stack_to_native_abi_is_generated, 0, sizeof(_stack_to_native_abi_is_generated));
#endif
    for (int i = 0; i < Interpreter::number_of_result_handlers; i++) {
      BasicType type = types[i];
      if (!is_generated[Interpreter::BasicType_as_index(type)]++) {
	Interpreter::_native_abi_to_tosca[Interpreter::BasicType_as_index(type)] = generate_result_handler_for(type);
      }
#ifdef CC_INTERP
      if (!_tosca_to_stack_is_generated[Interpreter::BasicType_as_index(type)]++) {
	Interpreter::_tosca_to_stack[Interpreter::BasicType_as_index(type)] = generate_tosca_to_stack_converter(type);
      }
      if (!_stack_to_stack_is_generated[Interpreter::BasicType_as_index(type)]++) {
	Interpreter::_stack_to_stack[Interpreter::BasicType_as_index(type)] = generate_stack_to_stack_converter(type);
      }
      if (!_stack_to_native_abi_is_generated[Interpreter::BasicType_as_index(type)]++) {
	Interpreter::_stack_to_native_abi[Interpreter::BasicType_as_index(type)] = generate_stack_to_native_abi_converter(type);
      }
#endif
    }
  }

  { CodeletMark cm(_masm, "slow signature handler");
    Interpreter::_slow_signature_handler = generate_slow_signature_handler();
  }

#ifndef CC_INTERP
  for (int j = 0; j < number_of_states; j++) {
    const TosState states[] = {btos, ctos, stos, itos, ltos, ftos, dtos, atos, vtos};
    Interpreter::_return_3_addrs_by_index[Interpreter::TosState_as_index(states[j])] = Interpreter::return_entry(states[j], 3);
    Interpreter::_return_5_addrs_by_index[Interpreter::TosState_as_index(states[j])] = Interpreter::return_entry(states[j], 5);
  }

  { CodeletMark cm(_masm, "continuation entry points");
    Interpreter::_continuation_entry =
      EntryPoint(
        generate_continuation_for(btos),
        generate_continuation_for(ctos),
        generate_continuation_for(stos),
        generate_continuation_for(atos),
        generate_continuation_for(itos),
        generate_continuation_for(ltos),
        generate_continuation_for(ftos),
        generate_continuation_for(dtos),
        generate_continuation_for(vtos)
      );
  }

  { CodeletMark cm(_masm, "safepoint entry points");
    Interpreter::_safept_entry =
      EntryPoint(
	generate_safept_entry_for(btos, CAST_FROM_FN_PTR(address, InterpreterRuntime::at_safepoint)),
	generate_safept_entry_for(ctos, CAST_FROM_FN_PTR(address, InterpreterRuntime::at_safepoint)),
	generate_safept_entry_for(stos, CAST_FROM_FN_PTR(address, InterpreterRuntime::at_safepoint)),
	generate_safept_entry_for(atos, CAST_FROM_FN_PTR(address, InterpreterRuntime::at_safepoint)),
        generate_safept_entry_for(itos, CAST_FROM_FN_PTR(address, InterpreterRuntime::at_safepoint)),
        generate_safept_entry_for(ltos, CAST_FROM_FN_PTR(address, InterpreterRuntime::at_safepoint)),
        generate_safept_entry_for(ftos, CAST_FROM_FN_PTR(address, InterpreterRuntime::at_safepoint)),
        generate_safept_entry_for(dtos, CAST_FROM_FN_PTR(address, InterpreterRuntime::at_safepoint)),
        generate_safept_entry_for(vtos, CAST_FROM_FN_PTR(address, InterpreterRuntime::at_safepoint))
      );
  }

  { CodeletMark cm(_masm, "exception handling");
    // (Note: this is not safepoint safe because thread may return to compiled code)
    generate_throw_exception();
  }

  { CodeletMark cm(_masm, "throw exception entrypoints");
    Interpreter::_throw_ArrayIndexOutOfBoundsException_entry = generate_ArrayIndexOutOfBounds_handler("java/lang/ArrayIndexOutOfBoundsException");
    Interpreter::_throw_ArrayStoreException_entry            = generate_klass_exception_handler("java/lang/ArrayStoreException"                 );
    Interpreter::_throw_ArithmeticException_entry            = generate_exception_handler("java/lang/ArithmeticException"           , "/ by zero");
    Interpreter::_throw_ClassCastException_entry             = generate_ClassCastException_handler();
    Interpreter::_throw_NullPointerException_entry           = generate_exception_handler("java/lang/NullPointerException"          , NULL       );
    Interpreter::_throw_StackOverflowError_entry             = generate_StackOverflowError_handler();
  }
#endif // !CC_INTERP


#ifdef CC_INTERP

#define method_entry(kind) Interpreter::_entry_table[Interpreter::kind] = generate_method_entry(Interpreter::kind)

  { CodeletMark cm(_masm, "(kind = frame_manager)");
    // all non-native method kinds  
    method_entry(zerolocals);
    method_entry(zerolocals_synchronized);
    method_entry(empty);
    method_entry(accessor);
    method_entry(abstract);
    method_entry(java_lang_math_sin   );
    method_entry(java_lang_math_cos   );
    method_entry(java_lang_math_tan   );
    method_entry(java_lang_math_abs   );
    method_entry(java_lang_math_sqrt  );
    method_entry(java_lang_math_log   );
    method_entry(java_lang_math_log10 );
    Interpreter::_native_entry_begin = Interpreter::code()->code_end();
    method_entry(native);
    method_entry(native_synchronized);
    Interpreter::_native_entry_end = Interpreter::code()->code_end();
  }

#else

#define method_entry(kind)                                                                    \
  { CodeletMark cm(_masm, "method entry point (kind = " #kind ")");                    \
    Interpreter::_entry_table[Interpreter::kind] = generate_method_entry(Interpreter::kind);  \
  }

  // all non-native method kinds  
  method_entry(zerolocals)
  method_entry(zerolocals_synchronized)
  method_entry(empty)
  method_entry(accessor)
  method_entry(abstract)
  method_entry(java_lang_math_sin  )
  method_entry(java_lang_math_cos  )
  method_entry(java_lang_math_tan  )
  method_entry(java_lang_math_abs  )
  method_entry(java_lang_math_sqrt )
  method_entry(java_lang_math_log  )
  method_entry(java_lang_math_log10)

  // all native method kinds (must be one contiguous block)
  Interpreter::_native_entry_begin = Interpreter::code()->code_end();
  method_entry(native)
  method_entry(native_synchronized)
  Interpreter::_native_entry_end = Interpreter::code()->code_end();

#endif // !CC_INTERP

#undef method_entry

#ifndef CC_INTERP
  // Bytecodes
  set_entry_points_for_all_bytes();
  set_safepoints_for_all_bytes();
#endif // !CC_INTERP
}


//------------------------------------------------------------------------------------------------------------------------

#ifndef CC_INTERP
address AbstractInterpreterGenerator::generate_error_exit(const char* msg) {
  address entry = __ pc();
  __ stop(msg);
  return entry;
}


//------------------------------------------------------------------------------------------------------------------------

void AbstractInterpreterGenerator::set_entry_points_for_all_bytes() {
  for (int i = 0; i < DispatchTable::length; i++) {
    Bytecodes::Code code = (Bytecodes::Code)i;
    if (Bytecodes::is_defined(code)) {
      set_entry_points(code);
    } else {
      set_unimplemented(i);
    }
  }
}


void AbstractInterpreterGenerator::set_safepoints_for_all_bytes() {
  for (int i = 0; i < DispatchTable::length; i++) {
    Bytecodes::Code code = (Bytecodes::Code)i;
    if (Bytecodes::is_defined(code)) Interpreter::_safept_table.set_entry(code, Interpreter::_safept_entry);
  }
}


void AbstractInterpreterGenerator::set_unimplemented(int i) {
  address e = _unimplemented_bytecode;
  EntryPoint entry(e, e, e, e, e, e, e, e, e);
  Interpreter::_normal_table.set_entry(i, entry);
  Interpreter::_wentry_point[i] = _unimplemented_bytecode;
}


void AbstractInterpreterGenerator::set_entry_points(Bytecodes::Code code) {
  CodeletMark cm(_masm, Bytecodes::name(code), code);
  // initialize entry points
  assert(_unimplemented_bytecode    != NULL, "should have been generated before");
  assert(_illegal_bytecode_sequence != NULL, "should have been generated before");
  address bep = _illegal_bytecode_sequence;
  address cep = _illegal_bytecode_sequence;
  address sep = _illegal_bytecode_sequence;
  address aep = _illegal_bytecode_sequence;
  address iep = _illegal_bytecode_sequence;
  address lep = _illegal_bytecode_sequence;
  address fep = _illegal_bytecode_sequence;
  address dep = _illegal_bytecode_sequence;
  address vep = _unimplemented_bytecode;
  address wep = _unimplemented_bytecode;
  // code for short & wide version of bytecode
  if (Bytecodes::is_defined(code)) {
    Template* t = TemplateTable::template_for(code);
    assert(t->is_valid(), "just checking");
    set_short_entry_points(t, bep, cep, sep, aep, iep, lep, fep, dep, vep);
  }
  if (Bytecodes::wide_is_defined(code)) {
    Template* t = TemplateTable::template_for_wide(code);
    assert(t->is_valid(), "just checking");
    set_wide_entry_point(t, wep);
  }
  // set entry points
  EntryPoint entry(bep, cep, sep, aep, iep, lep, fep, dep, vep);
  Interpreter::_normal_table.set_entry(code, entry);
  Interpreter::_wentry_point[code] = wep;
}


void AbstractInterpreterGenerator::set_wide_entry_point(Template* t, address& wep) {
  assert(t->is_valid(), "template must exist");
  assert(t->tos_in() == vtos, "only vtos tos_in supported for wide instructions")
  wep = __ pc(); generate_and_dispatch(t);
}


void AbstractInterpreterGenerator::set_short_entry_points(Template* t, address& bep, address& cep, address& sep, address& aep, address& iep, address& lep, address& fep, address& dep, address& vep) {
  assert(t->is_valid(), "template must exist");
  switch (t->tos_in()) {
    case btos: vep = __ pc(); __ pop(btos); bep = __ pc(); generate_and_dispatch(t); break;
    case ctos: vep = __ pc(); __ pop(ctos); sep = __ pc(); generate_and_dispatch(t); break;
    case stos: vep = __ pc(); __ pop(stos); sep = __ pc(); generate_and_dispatch(t); break;
    case atos: vep = __ pc(); __ pop(atos); aep = __ pc(); generate_and_dispatch(t); break;
    case itos: vep = __ pc(); __ pop(itos); iep = __ pc(); generate_and_dispatch(t); break;
    case ltos: vep = __ pc(); __ pop(ltos); lep = __ pc(); generate_and_dispatch(t); break;
    case ftos: vep = __ pc(); __ pop(ftos); fep = __ pc(); generate_and_dispatch(t); break;
    case dtos: vep = __ pc(); __ pop(dtos); dep = __ pc(); generate_and_dispatch(t); break;
    case vtos: set_vtos_entry_points(t, bep, cep, sep, aep, iep, lep, fep, dep, vep);     break;
    default  : ShouldNotReachHere();                                                 break;
  }
}


//------------------------------------------------------------------------------------------------------------------------

void AbstractInterpreterGenerator::generate_and_dispatch(Template* t, TosState tos_out) {
#ifndef CC_INTERP
  if (PrintBytecodeHistogram)                                    histogram_bytecode(t);
#ifndef PRODUCT
  // debugging code
  if (CountBytecodes || TraceBytecodes || StopInterpreterAt > 0) count_bytecode();
  if (PrintBytecodePairHistogram)                                histogram_bytecode_pair(t);
  if (TraceBytecodes)                                            trace_bytecode(t);
  if (StopInterpreterAt > 0)                                     stop_interpreter_at();
  __ verify_FPU(1, t->tos_in());
#endif // !PRODUCT
  int step;
  if (!t->does_dispatch()) { 
    step = t->is_wide() ? Bytecodes::wide_length_for(t->bytecode()) : Bytecodes::length_for(t->bytecode());
    if (tos_out == ilgl) tos_out = t->tos_out();
    // compute bytecode size
    assert(step > 0, "just checkin'");    
    // setup stuff for dispatching next bytecode 
    if (ProfileInterpreter && VerifyDataPointer
        && methodDataOopDesc::bytecode_has_profile(t->bytecode())) {
      __ verify_method_data_pointer();
    }
    __ dispatch_prolog(tos_out, step);
  }
  // generate template
  t->generate(_masm);
  // advance
  if (t->does_dispatch()) {
#ifdef ASSERT
    // make sure execution doesn't go beyond this point if code is broken
    __ should_not_reach_here();
#endif // ASSERT
  } else {
    // dispatch to next bytecode
    __ dispatch_epilog(tos_out, step);
  }
#endif
}
#endif /* !CC_INTERP */


//------------------------------------------------------------------------------------------------------------------------
// Entry points

AbstractInterpreter::MethodKind AbstractInterpreter::method_kind(methodHandle m) {
  // Abstract method?
  if (m->is_abstract()) return abstract;

  // Native method?
  // Note: This test must come _before_ the test for intrinsic
  //       methods. See also comments below.
  if (m->is_native()) {
    return m->is_synchronized() ? native_synchronized : native;
  } 

  // Synchronized?
  if (m->is_synchronized()) {
    return zerolocals_synchronized;
  } 

  if (RegisterFinalizersAtInit && m->code_size() == 1 &&
      m->intrinsic_id() == vmIntrinsics::_Object_init) {
    // We need to execute the special return bytecode to check for
    // finalizer registration so create a normal frame.
    return zerolocals;
  }

  // Empty method?
  if (m->is_empty_method()) {
    return empty;
  } 
  
  // Accessor method?
  if (m->is_accessor()) {
    assert(m->size_of_parameters() == 1, "fast code for accessors assumes parameter size = 1");
    return accessor;
  }
  
  // Special intrinsic method?
  // Note: This test must come _after_ the test for native methods,
  //       otherwise we will run into problems with JDK 1.2, see also
  //       AbstractInterpreterGenerator::generate_method_entry() for
  //       for details.
  switch (m->intrinsic_id()) {
    case vmIntrinsics::_dsin  : return java_lang_math_sin  ;
    case vmIntrinsics::_dcos  : return java_lang_math_cos  ;
    case vmIntrinsics::_dtan  : return java_lang_math_tan  ;
    case vmIntrinsics::_dabs  : return java_lang_math_abs  ;
    case vmIntrinsics::_dsqrt : return java_lang_math_sqrt ;
    case vmIntrinsics::_dlog  : return java_lang_math_log  ;
    case vmIntrinsics::_dlog10: return java_lang_math_log10;
  }

  // Note: for now: zero locals for all non-empty methods
  return zerolocals;  
}


// Return true if the interpreter can prove that the given bytecode has
// not yet been executed (in Java semantics, not in actual operation).
bool AbstractInterpreter::is_not_reached(methodHandle method, int bci) {
  address bcp = method->bcp_from(bci);

  if (!Bytecode_at(bcp)->must_rewrite()) {
    // might have been reached
    return false;
  }

  // the bytecode might not be rewritten if the method is an accessor, etc.
  address ientry = method->interpreter_entry();
  if (ientry != entry_for_kind(AbstractInterpreter::zerolocals) &&
      ientry != entry_for_kind(AbstractInterpreter::zerolocals_synchronized))
    return false;  // interpreter does not run this method!

  // otherwise, we can be sure this bytecode has never been executed
  return true;
}


#ifndef PRODUCT
void AbstractInterpreter::print_method_kind(MethodKind kind) {
  switch (kind) {
    case zerolocals             : tty->print("zerolocals"             ); break;
    case zerolocals_synchronized: tty->print("zerolocals_synchronized"); break;
    case native                 : tty->print("native"                 ); break;
    case native_synchronized    : tty->print("native_synchronized"    ); break;
    case empty                  : tty->print("empty"                  ); break;
    case accessor               : tty->print("accessor"               ); break;
    case abstract               : tty->print("abstract"               ); break;
    case java_lang_math_sin     : tty->print("java_lang_math_sin"     ); break;
    case java_lang_math_cos     : tty->print("java_lang_math_cos"     ); break;
    case java_lang_math_tan     : tty->print("java_lang_math_tan"     ); break;
    case java_lang_math_abs     : tty->print("java_lang_math_abs"     ); break;
    case java_lang_math_sqrt    : tty->print("java_lang_math_sqrt"    ); break;
    case java_lang_math_log     : tty->print("java_lang_math_log"     ); break;
    case java_lang_math_log10   : tty->print("java_lang_math_log10"   ); break;
    default                     : ShouldNotReachHere();
  }
}
#endif // PRODUCT


#ifndef CC_INTERP
address AbstractInterpreter::return_entry(TosState state, int length) {
  guarantee(0 <= length && length < Interpreter::number_of_return_entries, "illegal length");
  return _return_entry[length].entry(state);
}


address AbstractInterpreter::deopt_entry(TosState state, int length) {
  guarantee(0 <= length && length < Interpreter::number_of_deopt_entries, "illegal length");
  return _deopt_entry[length].entry(state);
}

#endif /* CC_INTERP */

static BasicType constant_pool_type(methodOop method, int index) {
  constantTag tag = method->constants()->tag_at(index);
       if (tag.is_int              ()) return T_INT;
  else if (tag.is_float            ()) return T_FLOAT;
  else if (tag.is_long             ()) return T_LONG;
  else if (tag.is_double           ()) return T_DOUBLE;
  else if (tag.is_string           ()) return T_OBJECT;
  else if (tag.is_unresolved_string()) return T_OBJECT;
  else if (tag.is_klass            ()) return T_OBJECT;
  else if (tag.is_unresolved_klass ()) return T_OBJECT;
  ShouldNotReachHere();
  return T_ILLEGAL;
}


//------------------------------------------------------------------------------------------------------------------------
// Deoptimization support

// If deoptimization happens, this method returns the point where to continue in
// interpreter. For calls (invokexxxx, newxxxx) the continuation is at next
// bci and the top of stack is in eax/edx/FPU tos.
// For putfield/getfield, put/getstatic, the continuation is at the same
// bci and the TOS is on stack.

// Note: deopt_entry(type, 0) means reexecute bytecode
//       deopt_entry(type, length) means continue at next bytecode

address AbstractInterpreter::continuation_for(methodOop method, address bcp, int callee_parameters, bool is_top_frame, bool& use_next_mdp) {
  assert(method->contains(bcp), "just checkin'");
  Bytecodes::Code code   = Bytecodes::java_code_at(bcp);
  int             bci    = method->bci_from(bcp);
  int             length = -1; // initial value for debugging
  // compute continuation length
  length = Bytecodes::length_at(bcp);
  // compute result type
  BasicType type = T_ILLEGAL;
  // when continuing after a compiler safepoint, re-execute the bytecode
  // (an invoke is continued after the safepoint)
  use_next_mdp = true;
  switch (code) {
    case Bytecodes::_lookupswitch:
    case Bytecodes::_tableswitch:
    case Bytecodes::_fast_binaryswitch:
    case Bytecodes::_fast_linearswitch:
    // recompute condtional expression folded into _if<cond>
    case Bytecodes::_lcmp      :
    case Bytecodes::_fcmpl     :
    case Bytecodes::_fcmpg     :
    case Bytecodes::_dcmpl     :
    case Bytecodes::_dcmpg     :
    case Bytecodes::_ifnull    :
    case Bytecodes::_ifnonnull :
    case Bytecodes::_goto      :
    case Bytecodes::_goto_w    :
    case Bytecodes::_ifeq      :
    case Bytecodes::_ifne      :
    case Bytecodes::_iflt      :
    case Bytecodes::_ifge      :
    case Bytecodes::_ifgt      :
    case Bytecodes::_ifle      :
    case Bytecodes::_if_icmpeq :
    case Bytecodes::_if_icmpne :
    case Bytecodes::_if_icmplt :
    case Bytecodes::_if_icmpge :
    case Bytecodes::_if_icmpgt :
    case Bytecodes::_if_icmple :
    case Bytecodes::_if_acmpeq :
    case Bytecodes::_if_acmpne :
    // special cases
    case Bytecodes::_getfield  :
    case Bytecodes::_putfield  :
    case Bytecodes::_getstatic :
    case Bytecodes::_putstatic :
    case Bytecodes::_aastore   :
      // reexecute the operation and TOS value is on stack
      assert(is_top_frame, "must be top frame");
      use_next_mdp = false;
      return deopt_entry(vtos, 0);
      break;

#ifdef COMPILER1
    case Bytecodes::_athrow    :
      assert(is_top_frame, "must be top frame");
      use_next_mdp = false;
      return Interpreter::rethrow_exception_entry();
      break;
#endif /* COMPILER1 */

    case Bytecodes::_invokevirtual  :
    case Bytecodes::_invokespecial  :
    case Bytecodes::_invokestatic   :
    case Bytecodes::_invokeinterface: {
      Thread *thread = Thread::current();
      ResourceMark rm(thread);
      methodHandle mh(thread, method);
      type = Bytecode_invoke_at(mh, bci)->result_type(thread);
      // since the cache entry might not be initialized:
      // (NOT needed for the old calling convension)
      if (!is_top_frame) {
        int index = Bytes::get_native_u2(bcp+1);
        method->constants()->cache()->entry_at(index)->set_parameter_size(callee_parameters);
      }
      break;
    }
    
    case Bytecodes::_ldc   : 
      type = constant_pool_type( method, *(bcp+1) ); 
      break;

    case Bytecodes::_ldc_w : // fall through
    case Bytecodes::_ldc2_w: 
      type = constant_pool_type( method, Bytes::get_Java_u2(bcp+1) ); 
      break;

    case Bytecodes::_return: {
      // This is used for deopt during registration of finalizers
      // during Object.<init>.  We simply need to resume execution at
      // the standard return vtos bytecode to pop the frame normally.
      // reexecuting the real bytecode would cause double registration
      // of the finalizable object.
#ifndef CC_INTERP
      assert(is_top_frame, "must be on top");
      return _normal_table.entry(Bytecodes::_return).entry(vtos);
#endif // CC_INTERP
    }

    default:
      type = Bytecodes::result_type(code);
      break;
  }

  // return entry point for computed continuation state & bytecode length
  return
    is_top_frame
    ? deopt_entry (as_TosState(type), length)
    : return_entry(as_TosState(type), length);
}


#ifndef CC_INTERP

//------------------------------------------------------------------------------------------------------------------------
// Suport for invokes

int AbstractInterpreter::TosState_as_index(TosState state) {
  assert( state < number_of_states , "Invalid state in TosState_as_index");
  assert(0 <= (int)state && (int)state < AbstractInterpreter::number_of_return_addrs, "index out of bounds");
  return (int)state;
}

#endif // CC_INTERP

//------------------------------------------------------------------------------------------------------------------------
// Safepoint suppport

#ifndef CC_INTERP
static inline void copy_table(address* from, address* to, int size) {
  // Copy non-overlapping tables. The copy has to occur word wise for MT safety.
  while (size-- > 0) *to++ = *from++;
}
#endif

void AbstractInterpreter::notice_safepoints() {
  if (!_notice_safepoints) {
    // switch to safepoint dispatch table
    _notice_safepoints = true;
#ifndef CC_INTERP
    copy_table((address*)&_safept_table, (address*)&_active_table, sizeof(_active_table) / sizeof(address));
#endif
  }
}


// switch from the dispatch table which notices safepoints back to the
// normal dispatch table.  So that we can notice single stepping points,
// keep the safepoint dispatch table if we are single stepping in JVMTI.
// Note that the should_post_single_step test is exactly as fast as the 
// JvmtiExport::_enabled test and covers both cases.
void AbstractInterpreter::ignore_safepoints() {
  if (_notice_safepoints) {
    if (!JvmtiExport::should_post_single_step()) {
      // switch to normal dispatch table
      _notice_safepoints = false;
#ifndef CC_INTERP
      copy_table((address*)&_normal_table, (address*)&_active_table, sizeof(_active_table) / sizeof(address));
#endif
    }
  }
}

void AbstractInterpreterGenerator::bang_stack_shadow_pages(bool native_call) {
  // Quick & dirty stack overflow checking: bang the stack & handle trap.
  // Note that we do the banging after the frame is setup, since the exception
  // handling code expects to find a valid interpreter frame on the stack.
  // Doing the banging earlier fails if the caller frame is not an interpreter
  // frame.
  // (Also, the exception throwing code expects to unlock any synchronized
  // method receiever, so do the banging after locking the receiver.)

  // Bang each page in the shadow zone. We can't assume it's been done for
  // an interpreter frame with greater than a page of locals, so each page
  // needs to be checked.  Only true for non-native.
  if (UseStackBanging) {
    const int start_page = native_call ? StackShadowPages : 1;
    const int page_size = os::vm_page_size();
    for (int pages = start_page; pages <= StackShadowPages ; pages++) {
      __ bang_stack_with_offset(pages*page_size);
    }
  }
}
