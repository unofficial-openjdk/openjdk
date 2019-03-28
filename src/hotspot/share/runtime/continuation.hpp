/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#ifndef SHARE_VM_RUNTIME_CONTINUATION_HPP
#define SHARE_VM_RUNTIME_CONTINUATION_HPP

#include "runtime/globals.hpp"

#define CONT_FULL_STACK (!UseNewCode)

// The order of this struct matters as it's directly manipulated by assembly code (push/pop)
struct FrameInfo {
  address pc;
  intptr_t* fp;
  intptr_t* sp;
};

class Continuations : public AllStatic {
private:
  static volatile long _exploded_miss;
  static volatile long _exploded_hit;
  static volatile long _nmethod_hit;
  static volatile long _nmethod_miss;
public:
  static void exploded_miss();
  static void exploded_hit();
  static void nmethod_miss();
  static void nmethod_hit();

  static void print_statistics();
};

class javaVFrame;

class Continuation : AllStatic {
public:
  static int freeze0(JavaThread* thread, FrameInfo* fi, bool safepoint_yield);
  static int freeze(JavaThread* thread, FrameInfo* fi);
  static int prepare_thaw(FrameInfo* fi, bool return_barrier);
  static address thaw(FrameInfo* fi, bool return_barrier, bool exception);
  static int try_force_yield(JavaThread* thread, oop cont);

  static oop  get_continutation_for_frame(JavaThread* thread, const frame& f);
  static bool is_continuation_entry_frame(const frame& f, const RegisterMap* map);
  static bool is_cont_bottom_frame(const frame& f);
  static bool is_return_barrier_entry(const address pc);
  static bool is_frame_in_continuation(const frame& f, oop cont);
  static bool is_frame_in_continuation(JavaThread* thread, const frame& f);
  static void fix_continuation_bottom_sender(const frame* callee, RegisterMap* map, address* sender_pc, intptr_t** sender_sp);

  static frame top_frame(const frame& callee, RegisterMap* map);
  static frame sender_for_interpreter_frame(const frame& callee, RegisterMap* map);
  static frame sender_for_compiled_frame(const frame& callee, RegisterMap* map);

  static bool has_last_Java_frame(Handle continuation);
  static frame last_frame(Handle continuation, RegisterMap *map);
  static javaVFrame* last_java_vframe(Handle continuation, RegisterMap *map);

  // access frame data
  static bool is_in_usable_stack(address addr, const RegisterMap* map);
  static int usp_offset_to_index(const frame& fr, const RegisterMap* map, const int usp_offset_in_bytes);
  static address usp_offset_to_location(const frame& fr, const RegisterMap* map, const int usp_offset_in_bytes);
  static address usp_offset_to_location(const frame& fr, const RegisterMap* map, const int usp_offset_in_bytes, bool is_oop);
  static address reg_to_location(const frame& fr, const RegisterMap* map, VMReg reg);
  static address reg_to_location(const frame& fr, const RegisterMap* map, VMReg reg, bool is_oop);
  static address reg_to_location(oop cont, const frame& fr, const RegisterMap* map, VMReg reg, bool is_oop);
  static address interpreter_frame_expression_stack_at(const frame& fr, const RegisterMap* map, const InterpreterOopMap& oop_mask, int index);
  static address interpreter_frame_local_at(const frame& fr, const RegisterMap* map, const InterpreterOopMap& oop_mask, int index);

  static Method* interpreter_frame_method(const frame& fr, const RegisterMap* map);
  static address interpreter_frame_bcp(const frame& fr, const RegisterMap* map);

  static oop continuation_scope(oop cont);
  static bool is_scope_bottom(oop cont_scope, const frame& fr, const RegisterMap* map);
  
  static int PERFTEST_LEVEL;
private:
  // declared here as it's used in friend declarations
  static address oop_address(objArrayOop ref_stack, int ref_sp, int index);
  static address oop_address(objArrayOop ref_stack, int ref_sp, address stack_address);
  static FrameInfo* get_thread_cont_frame(JavaThread* thread);
};

void CONT_RegisterNativeMethods(JNIEnv *env, jclass cls);

#endif // SHARE_VM_RUNTIME_CONTINUATION_HPP
