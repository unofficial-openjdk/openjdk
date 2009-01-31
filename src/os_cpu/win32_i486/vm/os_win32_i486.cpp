#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "%W% %E% %U% JVM"
#endif
/*
 * Copyright 1999-2007 Sun Microsystems, Inc.  All Rights Reserved.
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

// do not include  precompiled  header file
# include "incls/_os_win32_i486.cpp.incl"

extern LONG WINAPI topLevelExceptionFilter(_EXCEPTION_POINTERS* );

// Install a win32 structured exception handler around thread.
void os::os_exception_wrapper(java_call_t f, JavaValue* value, methodHandle* method, JavaCallArguments* args, Thread* thread) {
  __try {

    // We store the current thread in this wrapperthread location
    // and determine how far away this address is from the structured
    // execption pointer that FS:[0] points to.  This get_thread 
    // code can then get the thread pointer via FS.
    // 
    // Warning:  This routine must NEVER be inlined since we'd end up with
    //           multiple offsets.
    //
    volatile Thread* wrapperthread = thread;

    if ( ThreadLocalStorage::get_thread_ptr_offset() == 0 ) {
      int thread_ptr_offset;
      __asm {
        lea eax, dword ptr wrapperthread;
        sub eax, dword ptr FS:[0H];
        mov thread_ptr_offset, eax
      };
      ThreadLocalStorage::set_thread_ptr_offset(thread_ptr_offset);
    }
#ifdef ASSERT
    // Verify that the offset hasn't changed since we initally captured 
    // it. This might happen if we accidentally ended up with an
    // inlined version of this routine.
    else {
      int test_thread_ptr_offset;
      __asm {
        lea eax, dword ptr wrapperthread;
        sub eax, dword ptr FS:[0H];
        mov test_thread_ptr_offset, eax
      };
      assert(test_thread_ptr_offset == ThreadLocalStorage::get_thread_ptr_offset(), 
             "thread pointer offset from SEH changed");
    }
#endif

    f(value, method, args, thread);
  } __except(topLevelExceptionFilter((_EXCEPTION_POINTERS*)_exception_info())) {
      // Nothing to do.
  }
}

void os::initialize_thread() {
// Nothing to do.
}

typedef jlong cmpxchg_long_func_t(jlong, volatile jlong*, jlong);

jlong os::atomic_cmpxchg_long_bootstrap(jlong exchange_value, volatile jlong* dest, jlong compare_value) {
  // try to use the stub:
  cmpxchg_long_func_t* func = CAST_TO_FN_PTR(cmpxchg_long_func_t*, StubRoutines::atomic_cmpxchg_long_entry());

  if (func != NULL) {
    os::atomic_cmpxchg_long_func = func;
    return (*func)(exchange_value, dest, compare_value);
  }
  assert(Threads::number_of_threads() == 0, "for bootstrap only");

  jlong old_value = *dest;
  if (old_value == compare_value)
    *dest = exchange_value;
  return old_value;
}

cmpxchg_long_func_t* os::atomic_cmpxchg_long_func = os::atomic_cmpxchg_long_bootstrap;

ExtendedPC os::fetch_frame_from_context(void* ucVoid, 
                    intptr_t** ret_sp, intptr_t** ret_fp) {

  ExtendedPC  epc;
  CONTEXT* uc = (CONTEXT*)ucVoid;

  if (uc != NULL) {
    epc = ExtendedPC((address)uc->Eip);
    if (ret_sp) *ret_sp = (intptr_t*)uc->Esp;
    if (ret_fp) *ret_fp = (intptr_t*)uc->Ebp;
  } else {
    // construct empty ExtendedPC for return value checking
    epc = ExtendedPC(NULL);
    if (ret_sp) *ret_sp = (intptr_t *)NULL;
    if (ret_fp) *ret_fp = (intptr_t *)NULL;
  }

  return epc;
}

frame os::fetch_frame_from_context(void* ucVoid) {
  intptr_t* sp;
  intptr_t* fp;
  ExtendedPC epc = fetch_frame_from_context(ucVoid, &sp, &fp);
  return frame(sp, fp, epc.pc());
}

// VC++ does not save frame pointer on stack in optimized build. It
// can be turned off by /Oy-. If we really want to walk C frames,
// we can use the StackWalk() API.
frame os::get_sender_for_C_frame(frame* fr) {
  return frame(fr->sender_sp(), fr->link(), fr->sender_pc());
}


intptr_t* _get_previous_fp() {
  intptr_t **frameptr;
  __asm {
    mov frameptr, ebp
  };
  return *frameptr;
}

frame os::current_frame() {
  intptr_t* fp = _get_previous_fp();
  frame myframe((intptr_t*)os::current_stack_pointer(), 
                (intptr_t*)fp,
                CAST_FROM_FN_PTR(address, os::current_frame));
  if (os::is_first_C_frame(&myframe)) {
    // stack is not walkable
    return frame(NULL, NULL, NULL);
  } else {
    return os::get_sender_for_C_frame(&myframe);
  }
}

void os::print_context(outputStream *st, void *context) {
  if (context == NULL) return;

  CONTEXT* uc = (CONTEXT*)context;

  st->print_cr("Registers:");
  st->print(  "EAX=" INTPTR_FORMAT, uc->Eax);
  st->print(", EBX=" INTPTR_FORMAT, uc->Ebx);
  st->print(", ECX=" INTPTR_FORMAT, uc->Ecx);
  st->print(", EDX=" INTPTR_FORMAT, uc->Edx);
  st->cr();
  st->print(  "ESP=" INTPTR_FORMAT, uc->Esp);
  st->print(", EBP=" INTPTR_FORMAT, uc->Ebp);
  st->print(", ESI=" INTPTR_FORMAT, uc->Esi);
  st->print(", EDI=" INTPTR_FORMAT, uc->Edi);
  st->cr();
  st->print(  "EIP=" INTPTR_FORMAT, uc->Eip);
  st->print(", EFLAGS=" INTPTR_FORMAT, uc->EFlags);
  st->cr();
  st->cr();

  intptr_t *sp = (intptr_t *)uc->Esp;
  st->print_cr("Top of Stack: (sp=" PTR_FORMAT ")", sp);
  print_hex_dump(st, (address)sp, (address)(sp + 32), sizeof(intptr_t));
  st->cr();

  // Note: it may be unsafe to inspect memory near pc. For example, pc may
  // point to garbage if entry point in an nmethod is corrupted. Leave
  // this at the end, and hope for the best.
  address pc = (address)uc->Eip;
  st->print_cr("Instructions: (pc=" PTR_FORMAT ")", pc);
  print_hex_dump(st, pc - 16, pc + 16, sizeof(char));
  st->cr();
}

extern "C" int SafeFetch32 (int * adr, int Err) {
   int rv = Err ; 
   _try { 
       rv = *((volatile int *) adr) ; 
   } __except(EXCEPTION_EXECUTE_HANDLER) {
   } 
   return rv ; 
}

extern "C" intptr_t SafeFetchN (intptr_t * adr, intptr_t Err) {
   intptr_t rv = Err ; 
   _try { 
       rv = *((volatile intptr_t *) adr) ; 
   } __except(EXCEPTION_EXECUTE_HANDLER) {
   } 
   return rv ; 
}

extern "C" int SpinPause () { 
   // pause == rep:nop
   // On systems that don't support pause a rep:nop 
   // is executed as a nop.  The rep: prefix is ignored.
   _asm { 
      pause ; 
   };
   return 1 ;
}


void os::setup_fpu() {
  int fpu_cntrl_word = StubRoutines::fpu_cntrl_wrd_std();
  __asm fldcw fpu_cntrl_word;
}

