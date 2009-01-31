#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)cInterpreter_i486.hpp	1.9 07/05/05 17:04:13 JVM"
#endif
/*
 * Copyright 2002 Sun Microsystems, Inc.  All Rights Reserved.
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

// Platform specific for C++ based Interpreter

private:

    // this is a "shadow" frame used to build links to outer C++ interpreter
    // frames while are executing the current method.
    // 
    address   _saved_ebp;                 /* ebp chain for walking the linked interpreter states */
    address   _saved_return;              /* saved return address to call stub */
    interpreterState _self_link;          /*  Previous interpreter state  */ /* sometimes points to self??? */
    address   _result_handler;            /* temp for saving native result handler */

    address   _extra_junk1;               /* temp to save on recompiles */
    address   _extra_junk2;               /* temp to save on recompiles */
    address   _extra_junk3;               /* temp to save on recompiles */
    // address dummy_for_native2;         /* a native frame result handler would be here... */
    // address dummy_for_native1;         /* native result type stored here in a interpreter native frame */
    address   _extra_junk4;               /* temp to save on recompiles */
    address   _extra_junk5;               /* temp to save on recompiles */
    address   _extra_junk6;               /* temp to save on recompiles */
public:
    address get_saved_ebp() { return _saved_ebp; }
    address get_saved_return() { return _saved_return; } // QQQ entry frame is always call_stub here... Unfortunately we must know
							 // we have an interpreter frame...
// Have a real problem with sp() vs. raw_sp(). When creating a frame we want to
// always pass in the raw_sp so that for c1/c2 where raw_sp is also top of expression
// stack sp() will return tos, for C++ interpreter raw_sp is nothing but the hardware
// register. Since the os side doesn't know apriori whether it has a interpreted vs.
// compiled frame it will alway create using the raw_sp. If other users attempt to
// create a new frame like: frame(cf->sp(), cf->fp()) the value returned for sp()
// if cf is interpreted is not the raw_sp and we are screwed. This happens indirectly
// when frames are created via last_Java_sp and last_Java_fp. Yuck.
#define SET_LAST_JAVA_FRAME()                                                      \
	/* QQQ Hmm could we point to shadow and do aways with current??? */        \
	THREAD->set_last_Java_fp(current.fp());                                    \
	/* dummy pc will be at sp[-1] as expected */                               \
	/* Set a dummy pc recognizable as interpreter but unpatchable */           \
	SET_STACK_ADDR(CAST_FROM_FN_PTR(address, cInterpreter::InterpretMethod)+1, 0); \
	THREAD->set_last_Java_sp(topOfStack.top());                             

#define RESET_LAST_JAVA_FRAME()                                 \
	THREAD->set_last_Java_sp(NULL);                         \
	THREAD->set_last_Java_fp(NULL);                         \

