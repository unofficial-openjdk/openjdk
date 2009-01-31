#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)frame_amd64.hpp	1.18 07/05/05 17:04:06 JVM"
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

// A frame represents a physical stack frame (an activation).  Frames
// can be C or Java frames, and the Java frames can be interpreted or
// compiled.  In contrast, vframes represent source-level activations,
// so that one physical frame can correspond to multiple source level
// frames because of inlining.  A frame is comprised of {pc, fp, sp}

// Layout of interpreter frame:
//    [expression stack      ] * <- sp
//    [monitors              ]   \
//     ...                        | monitor block size
//    [monitors              ]   /
//    [monitor block size    ]
//    [byte code index/pointr]          = bcx()         bcx_offset
//    [pointer to locals     ]          = locals()      locals_offset
//    [constant pool cache   ]          = cache()       cache_offset
//    [methodData	     ]          = mdp()         mdx_offset
//    [methodOop             ]          = method()      method_offset
//    [old stack pointer     ]          (sender_sp)     sender_sp_offset
//    [old frame pointer     ]   <- fp  = link()
//    [return pc             ]
//    [oop temporary         ]                     (only for native calls)
//    [locals and parameters ]   
//                               <- sender sp

 public:
  enum {
    pc_return_offset                                 =  0,
    // All frames
    link_offset                                      =  0,
    return_addr_offset                               =  1,
    sender_sp_offset                                 =  2,

    // Interpreter frames
    interpreter_frame_result_handler_offset          =  3, // for native calls only
    interpreter_frame_oop_temp_offset                =  2, // for native calls only

    interpreter_frame_sender_sp_offset               = -1,
    // outgoing sp before a call to an invoked method
    interpreter_frame_last_sp_offset                 = interpreter_frame_sender_sp_offset - 1,
    interpreter_frame_method_offset                  = interpreter_frame_last_sp_offset - 1,
    interpreter_frame_mdx_offset                     = interpreter_frame_method_offset - 1,
    interpreter_frame_cache_offset                   = interpreter_frame_mdx_offset - 1,
    interpreter_frame_locals_offset                  = interpreter_frame_cache_offset - 1,
    interpreter_frame_bcx_offset                     = interpreter_frame_locals_offset - 1,    
    interpreter_frame_initial_sp_offset              = interpreter_frame_bcx_offset - 1,

    interpreter_frame_monitor_block_top_offset       = interpreter_frame_initial_sp_offset,
    interpreter_frame_monitor_block_bottom_offset    = interpreter_frame_initial_sp_offset,

    // Entry frames.  See call stub in stubGenerator_amd64.cpp.
#ifdef _WIN64
    entry_frame_after_call_words                     =  8,
    entry_frame_call_wrapper_offset                  =  2,
#else
    entry_frame_after_call_words                     = 13,
    entry_frame_call_wrapper_offset                  = -6,
#endif

    // Native frames XXX What's that for???
    native_frame_initial_param_offset                =  2,

    // Native caller frames
#ifdef _WIN64
    arg_reg_save_area_bytes                          = 32 // Register argument save area
#else
    arg_reg_save_area_bytes                          =  0
#endif
  };

  jlong long_at(int offset) const
  {
    return *long_at_addr(offset);
  }

  void long_at_put(int offset, jlong value)
  {
    *long_at_addr(offset) = value; 
  }

 private:
  // any additional field beyond _sp and _pc:
  intptr_t* _fp; // frame pointer
  intptr_t* _unextended_sp; // caller's original sp 

  jlong* long_at_addr(int offset) const
  {
    return (jlong*) addr_at(offset); 
  }

 public:
  // Constructors
  frame(intptr_t* sp, intptr_t* fp, address pc);

  frame(intptr_t* sp, intptr_t* unextended_sp, intptr_t* fp, address pc);

  frame(intptr_t* sp, intptr_t* fp);

  // accessors for the instance variables
  intptr_t* fp() const 
  {
    return _fp; 
  }

  inline address* sender_pc_addr() const;

  // return address of param, zero origin index.
  inline address* native_param_addr(int idx) const;
  
  // expression stack tos if we are nested in a java call
  intptr_t* interpreter_frame_last_sp() const;
  void interpreter_frame_set_last_sp(intptr_t* sp);
