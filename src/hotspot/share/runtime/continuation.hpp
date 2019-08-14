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

#include "oops/oopsHierarchy.hpp"
#include "runtime/frame.hpp"
#include "runtime/globals.hpp"
#include "jni.h"

// #define CONT_DOUBLE_NOP 1

#define CONT_FULL_STACK (!UseContinuationLazyCopy)

// The order of this struct matters as it's directly manipulated by assembly code (push/pop)
struct FrameInfo {
  address pc;
  intptr_t* fp;
  intptr_t* sp;
};

class Continuations : public AllStatic {
private:
  static volatile intptr_t _exploded_miss;
  static volatile intptr_t _exploded_hit;
  static volatile intptr_t _nmethod_hit;
  static volatile intptr_t _nmethod_miss;

  static int _flags;
public:
  static void exploded_miss();
  static void exploded_hit();
  static void nmethod_miss();
  static void nmethod_hit();

  static void print_statistics();
  static void init();

  static void cleanup_keepalives();

  static int flags() { return _flags; }
};

void continuations_init();

class javaVFrame;
class JavaThread;
class OopStorage;

class Continuation : AllStatic {
private:
  static OopStorage* _weak_handles;
public:
  static void init();

  static OopStorage* weak_storage() { return _weak_handles; }

  static int freeze(JavaThread* thread, FrameInfo* fi, bool from_interpreter);
  static int prepare_thaw(FrameInfo* fi, bool return_barrier);
  static address thaw_leaf(FrameInfo* fi, bool return_barrier, bool exception);
  static address thaw(JavaThread* thread, FrameInfo* fi, bool return_barrier, bool exception);
  static int try_force_yield(JavaThread* thread, oop cont);

  static oop  get_continutation_for_frame(JavaThread* thread, const frame& f);
  static bool is_continuation_entry_frame(const frame& f, const RegisterMap* map);
  static bool is_cont_post_barrier_entry_frame(const frame& f);
  static bool is_cont_barrier_frame(const frame& f);
  static bool is_return_barrier_entry(const address pc);
  static bool is_frame_in_continuation(const frame& f, oop cont);
  static bool is_frame_in_continuation(JavaThread* thread, const frame& f);
  static bool fix_continuation_bottom_sender(JavaThread* thread, const frame& callee, address* sender_pc, intptr_t** sender_sp);
  static bool fix_continuation_bottom_sender(RegisterMap* map, const frame& callee, address* sender_pc, intptr_t** sender_sp);
  static frame fix_continuation_bottom_sender(const frame& callee, RegisterMap* map, frame f);
  static address* get_continuation_entry_pc_for_sender(Thread* thread, const frame& f, address* pc_addr);
  static address get_top_return_pc_post_barrier(JavaThread* thread, address pc);

  static frame top_frame(const frame& callee, RegisterMap* map);
  static frame sender_for_interpreter_frame(const frame& callee, RegisterMap* map);
  static frame sender_for_compiled_frame(const frame& callee, RegisterMap* map);
  static int frame_size(const frame& f, const RegisterMap* map);

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

#ifndef PRODUCT
  static void describe(FrameValues &values);
#endif

  static void nmethod_patched(nmethod* nm);

private:
  // declared here as it's used in friend declarations
  static address oop_address(objArrayOop ref_stack, int ref_sp, int index);
  static address oop_address(objArrayOop ref_stack, int ref_sp, address stack_address);
};

void CONT_RegisterNativeMethods(JNIEnv *env, jclass cls);

#endif // SHARE_VM_RUNTIME_CONTINUATION_HPP
