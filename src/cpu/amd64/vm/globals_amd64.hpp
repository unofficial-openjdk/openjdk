#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)globals_amd64.hpp	1.26 07/05/05 17:04:06 JVM"
#endif
/*
 * Copyright 2003-2006 Sun Microsystems, Inc.  All Rights Reserved.
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

//
// Sets the default values for platform dependent flags used by the
// runtime system.  (see globals.hpp)
//

define_pd_global(bool,  ConvertSleepToYield,      true);
define_pd_global(bool,  ShareVtableStubs,         true);
define_pd_global(bool,  CountInterpCalls,         true);

// Generate code for implicit null checks
define_pd_global(bool,  ImplicitNullChecks,       true);

// Uncommon-trap NULLs past to check cast
define_pd_global(bool,  UncommonNullCast,         true);

define_pd_global(bool, NeedsDeoptSuspend,           false); // only register window machines need this

define_pd_global(intx,  CodeEntryAlignment,       32); 
define_pd_global(uintx, TLABSize,                 0); 
define_pd_global(uintx, NewSize, ScaleForWordSize(2048 * K));
define_pd_global(intx,  InlineFrequencyCount,     100);
define_pd_global(intx,  PreInflateSpin,		  10);

define_pd_global(intx, StackYellowPages, 2);
define_pd_global(intx, StackRedPages, 1);
// Very large C++ stack frames using solaris-amd64 optimized builds
// due to lack of optimization caused by C++ compiler bugs
define_pd_global(intx, StackShadowPages, SOLARIS_ONLY(20) NOT_SOLARIS(6) DEBUG_ONLY(+2));

define_pd_global(bool, RewriteBytecodes,     true);
define_pd_global(bool, RewriteFrequentPairs, true);

