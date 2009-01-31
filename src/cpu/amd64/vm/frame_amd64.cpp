#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)frame_amd64.cpp	1.31 07/05/05 17:04:03 JVM"
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

# include "incls/_precompiled.incl"
# include "incls/_frame_amd64.cpp.incl"


#ifdef ASSERT
void RegisterMap::check_location_valid() {
}
#endif

// Profiling/safepoint support


bool frame::safe_for_sender(JavaThread *thread)
{
  address   sp = (address)_sp;
  address   unextended_sp = (address)_unextended_sp;
  address   fp = (address)_fp;
  bool sp_safe = (sp != NULL && 
                 (sp <= thread->stack_base()) &&
                 (sp >= thread->stack_base() - thread->stack_size()));
  bool unextended_sp_safe = (unextended_sp != NULL && 
                 (unextended_sp <= thread->stack_base()) &&
                 (unextended_sp >= thread->stack_base() - thread->stack_size()));
  bool fp_safe = (fp != NULL && 
                 (fp <= thread->stack_base()) &&
                 (fp >= thread->stack_base() - thread->stack_size()));
  if (sp_safe && unextended_sp_safe && fp_safe) {
    // Unfortunately we can only check frame complete for runtime stubs and nmethod
    // other generic buffer blobs are more problematic so we just assume they are
    // ok. adapter blobs never have a frame complete and are never ok.
    if (_cb != NULL && !_cb->is_frame_complete_at(_pc)) {
      if (_cb->is_nmethod() || _cb->is_adapter_blob() || _cb->is_runtime_stub()) {
        return false;
      }
    }
    return true;
  } 
  // Note: fp == NULL is not really a prerequisite for this to be safe to
  // walk for c2. However we've modified the code such that if we get
  // a failure with fp != NULL that we then try with FP == NULL.
  // This is basically to mimic what a last_frame would look like if
  // c2 had generated it.
  if (sp_safe && unextended_sp_safe && fp == NULL) {
    // frame must be complete if fp == NULL as fp == NULL is only sensible
    // if we are looking at a nmethod and frame complete assures us of that.
    if (_cb != NULL && _cb->is_frame_complete_at(_pc) && _cb->is_compiled_by_c2()) {
      return true;
    }
  }
  return false;
}

void frame::patch_pc(Thread* thread, address pc)
{
  if (TracePcPatching) {
    tty->print_cr("patch_pc at address 0x%lx [0x%lx -> 0x%lx] ", 
                  &((address*) _sp)[-1], ((address*) _sp)[-1], pc);
  }
  ((address *)_sp)[-1] = pc; 
  _cb = CodeCache::find_blob(pc);
  if (_cb != NULL && _cb->is_nmethod() && ((nmethod*)_cb)->is_deopt_pc(_pc)) {
    address orig = (((nmethod*)_cb)->get_original_pc(this));
    assert(orig == _pc, "expected original to be stored before patching");
    _deopt_state = is_deoptimized;
    // leave _pc as is
  } else {
    _deopt_state = not_deoptimized;
    _pc = pc;
  }
}

int frame::frame_size() const 
{
  RegisterMap map(JavaThread::current(), false);
  frame sender = this->sender(&map);
  return sender.sp() - sp();
}

bool frame::is_interpreted_frame() const
{
  return Interpreter::contains(pc());
}

// sender_sp

intptr_t* frame::interpreter_frame_sender_sp() const
{
  assert(is_interpreted_frame(), "interpreted frame expected");
  return (intptr_t*) at(interpreter_frame_sender_sp_offset);
}

void frame::set_interpreter_frame_sender_sp(intptr_t* sender_sp)
{
  assert(is_interpreted_frame(), "interpreted frame expected");
  long_at_put(interpreter_frame_sender_sp_offset, (jlong) sender_sp);
}

// monitor elements

BasicObjectLock* frame::interpreter_frame_monitor_begin() const 
{
  return 
    (BasicObjectLock*) addr_at(interpreter_frame_monitor_block_bottom_offset);
}

BasicObjectLock* frame::interpreter_frame_monitor_end() const
{
  BasicObjectLock* result =
    (BasicObjectLock*) *addr_at(interpreter_frame_monitor_block_top_offset);
  // make sure the pointer points inside the frame
  assert((intptr_t) fp() > (intptr_t) result,
         "result must <  than frame pointer");
  assert((intptr_t) sp() <= (intptr_t) result,
         "result must >= than stack pointer");
  return result;
}

void frame::interpreter_frame_set_monitor_end(BasicObjectLock* value)
{
  *((BasicObjectLock**) addr_at(interpreter_frame_monitor_block_top_offset)) =
    value;
}

// Used by deoptimization
void frame::interpreter_frame_set_last_sp(intptr_t* sp) {
    *((intptr_t**)addr_at(interpreter_frame_last_sp_offset)) = sp;
}


frame frame::sender_for_entry_frame(RegisterMap* map) const 
{
  assert(map != NULL, "map must be set");
  // Java frame called from C; skip all C frames and return top C
  // frame of that chunk as the sender
  JavaFrameAnchor* jfa = entry_frame_call_wrapper()->anchor();
  assert(!entry_frame_is_first(), "next Java fp must be non zero");
  assert(jfa->last_Java_sp() > _sp, "must be above this frame on stack");  
  map->clear(); 
  assert(map->include_argument_oops(), "should be set by clear");

  if (jfa->last_Java_pc() != NULL ) {
    frame fr(jfa->last_Java_sp(), jfa->last_Java_fp(), jfa->last_Java_pc());  
    return fr;
  }
  frame fr(jfa->last_Java_sp(), jfa->last_Java_fp());  
  return fr;
}

frame frame::sender_for_interpreter_frame(RegisterMap* map) const
{
  // sp is the raw sp from the sender after adapter or interpreter extension
  intptr_t* sp = (intptr_t*) addr_at(sender_sp_offset);

  // This is the sp before any possible extension. This is handled via
  // _interpreter_sp_adjustment on sparc.

  intptr_t* unextended_sp = (intptr_t*) at(interpreter_frame_sender_sp_offset);

  // We do not need to update the callee-save register mapping because above
  // us is either another interpreter frame or a converter-frame, but never
  // directly a compiled frame.
#ifdef COMPILER2
  // The interpreter and compiler(s) always save RBP in a known
  // location on entry. We must record where that location is
  // so that if RBP was live on callout from c2 we can find
  // the saved copy no matter what it called.
  if (map->update_map()) {
    map->set_location(rbp->as_VMReg(), (address)addr_at(link_offset));
    // this is weird "H" ought to be at a higher address however the
    // oopMaps seems to have the "H" regs at the same address and the
    // vanilla register.
    // XXXX make this go away
    if (true) {
      map->set_location(rbp->as_VMReg()->next(), (address)addr_at(link_offset));
    }
  }
#endif // COMPILER2

  return frame(sp, unextended_sp, link(), sender_pc());
}


//-----------------------------sender_for_compiled_frame-----------------------
frame frame::sender_for_compiled_frame(RegisterMap* map) const 
{
  assert(map != NULL, "map must be set");

  // frame owned by optimizing compiler 
  intptr_t* sender_sp = NULL;

  assert(_cb->frame_size() >= 0, "must have non-zero frame size");
  sender_sp = unextended_sp() + _cb->frame_size();

  // On Intel the return_address is always the word on the stack
  address sender_pc = (address) *(sender_sp - 1);

  intptr_t *saved_fp = (intptr_t*) *(sender_sp - frame::sender_sp_offset);

  if (map->update_map()) {
    // Tell GC to use argument oopmaps for some runtime stubs that need it.
    // For C1, the runtime stub might not have oop maps, so set this flag
    // outside of update_register_map.
    map->set_include_argument_oops(_cb->caller_must_gc_arguments(map->thread()));
    if (_cb->oop_maps() != NULL) {
      OopMapSet::update_register_map(this, map);
    }
    // Since the prolog does the save and restore of epb there is no oopmap
    // for it so we must fill in its location as if there was an oopmap entry
    // since if our caller was compiled code there could be live jvm state in it.
    map->set_location(rbp->as_VMReg(), (address) (sender_sp - frame::sender_sp_offset));
    // this is weird "H" ought to be at a higher address however the
    // oopMaps seems to have the "H" regs at the same address and the
    // vanilla register.
    // XXXX make this go away
    if (true) {
      map->set_location(rbp->as_VMReg()->next(), (address) (sender_sp - frame::sender_sp_offset));
    }
  }


  assert(sender_sp != sp(), "must have changed");
  return frame(sender_sp, saved_fp, sender_pc);
}


frame frame::sender(RegisterMap* map) const 
{
  // Default is we done have to follow them. The sender_for_xxx will
  // update it accordingly
  map->set_include_argument_oops(false);

  if (is_entry_frame()) {
    return sender_for_entry_frame(map);
  }
  if (is_interpreted_frame()) {
    return sender_for_interpreter_frame(map);
  }
  assert(_cb == CodeCache::find_blob(pc()),"Must be the same");
  if (_cb != NULL) {
    return sender_for_compiled_frame(map);
  }
  // Must be native-compiled frame, i.e. the marshaling code for native
  // methods that exists in the core system.
  return frame(sender_sp(), link(), sender_pc());
}

bool frame::interpreter_frame_equals_unpacked_fp(intptr_t* fp)
{
  assert(is_interpreted_frame(), "must be interpreter frame");
  methodOop method = interpreter_frame_method();
  // When unpacking an optimized frame the frame pointer is
  // adjusted with: 
  int diff = (method->max_locals() - method->size_of_parameters()) *
             Interpreter::stackElementWords();
  return _fp == (fp - diff);
}

void frame::pd_gc_epilog()
{
  // nothing done here now
}

bool frame::is_interpreted_frame_valid() const
{
  assert(is_interpreted_frame(), "Not an interpreted frame");

  // These are reasonable sanity checks
  if (fp() == 0 || (intptr_t(fp()) & 0xF) != 0) {
    return false;
  }
  if (sp() == 0 || (intptr_t(sp()) & 0xF) != 0) {
    return false;
  }
  if (fp() + interpreter_frame_initial_sp_offset < sp()) {
    return false;
  }
  // These are hacks to keep us out of trouble.
  // The problem with these is that they mask other problems
  if (fp() <= sp()) { // this attempts to deal with unsigned comparison above
    return false;
  }
  if (fp() - sp() > 4096) {  // stack frames shouldn't be large.
    return false;
  }
  return true;
}

BasicType frame::interpreter_frame_result(oop* oop_result, jvalue* value_result) {
  assert(is_interpreted_frame(), "interpreted frame expected");
  methodOop method = interpreter_frame_method();
  BasicType type = method->result_type();

  intptr_t* tos_addr;
  if (method->is_native()) {
    // Prior to calling into the runtime to report the method_exit the
    // registers with possible result values (XMM0 and RAX) are pushed to 
    // the native stack. For floating point return types the return
    // value is at ESP + 2 (words). See the note in generate_native_entry.
    tos_addr = (intptr_t*)sp();
    if (type == T_FLOAT || type == T_DOUBLE) {
      // This is times two because we do a push(ltos) after pushing XMM0
      // and that takes two interpreter stack slots.
      tos_addr += 2 * Interpreter::stackElementWords();
    }
  } else {
    tos_addr = interpreter_frame_tos_address();
  }

  switch (type) {
    case T_OBJECT  : 
    case T_ARRAY   : {
      oop obj;
      if (method->is_native()) {
        obj = (oop) at(interpreter_frame_oop_temp_offset);
      } else {
        oop* obj_p = (oop*)tos_addr;
        obj = (obj_p == NULL) ? (oop)NULL : *obj_p;
      }
      assert(obj == NULL || Universe::heap()->is_in(obj), "sanity check");
      *oop_result = obj;
      break;
    }
    case T_BOOLEAN : value_result->z = *(jboolean*)tos_addr; break;
    case T_BYTE    : value_result->b = *(jbyte*)tos_addr; break;
    case T_CHAR    : value_result->c = *(jchar*)tos_addr; break;
    case T_SHORT   : value_result->s = *(jshort*)tos_addr; break;
    case T_INT     : value_result->i = *(jint*)tos_addr; break;
    case T_LONG    : value_result->j = *(jlong*)tos_addr; break;
    case T_FLOAT   : value_result->f = *(jfloat*)tos_addr; break;
    case T_DOUBLE  : value_result->d = *(jdouble*)tos_addr; break;
  }

  return type;
}

intptr_t* frame::interpreter_frame_tos_at(jint offset) const 
{ 
  int index = (Interpreter::expr_offset_in_bytes(offset)/wordSize);
  return &interpreter_frame_tos_address()[index];
}

