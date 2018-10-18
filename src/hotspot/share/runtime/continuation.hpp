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

#include "classfile/javaClasses.hpp"
#include "classfile/javaClasses.inline.hpp"
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

class Continuation : AllStatic {
public:
  static int freeze(JavaThread* thread, FrameInfo* fi);
  static int prepare_thaw(FrameInfo* fi, bool return_barrier);
  static address thaw(FrameInfo* fi, bool return_barrier, bool exception);

  static bool is_continuation_entry_frame(const frame& f, const RegisterMap* map);
  static bool is_cont_bottom_frame(const frame& f);
  static bool is_return_barrier_entry(const address pc) { return pc == StubRoutines::cont_returnBarrier(); }
  static bool is_frame_in_continuation(JavaThread* thread, const frame& f);
  static address fix_continuation_bottom_sender(const frame* callee, RegisterMap* map, address pc);

  static frame top_frame(const frame& callee, RegisterMap* map);
  static frame sender_for_interpreter_frame(const frame& callee, RegisterMap* map);
  static frame sender_for_compiled_frame(const frame& callee, RegisterMap* map, CodeBlobLookup* lookup);

  // access frame data
  static address usp_offset_to_location(const frame& fr, const RegisterMap* map, const int usp_offset_in_bytes);
  static address interpreter_frame_expression_stack_at(const frame& fr, const RegisterMap* map, const InterpreterOopMap& oop_mask, int index);
  static address interpreter_frame_local_at(const frame& fr, const RegisterMap* map, const InterpreterOopMap& oop_mask, int index);

  static Method* interpreter_frame_method(const frame& fr, const RegisterMap* map);
  static address interpreter_frame_bcp(const frame& fr, const RegisterMap* map);

  static inline oop continuation_scope(oop cont) { return cont != NULL ? java_lang_Continuation::scope(cont) : (oop)NULL; }
  static bool is_scope_bottom(oop cont_scope, const frame& fr, const RegisterMap* map);
  
  static int PERFTEST_LEVEL;
private:
  // declared here as it's used in friend declarations
  static address oop_address(objArrayOop ref_stack, address stack_address);
};

void CONT_RegisterNativeMethods(JNIEnv *env, jclass cls);

#endif // SHARE_VM_RUNTIME_CONTINUATION_HPP
