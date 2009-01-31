#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)register_definitions_i486.cpp	1.11 07/05/05 17:04:19 JVM"
#endif
/*
 * Copyright 2002-2005 Sun Microsystems, Inc.  All Rights Reserved.
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

// make sure the defines don't screw up the declarations later on in this file
#define DONT_USE_REGISTER_DEFINES

#include "incls/_precompiled.incl"
#include "incls/_register_definitions_i486.cpp.incl"

REGISTER_DEFINITION(Register, noreg);
REGISTER_DEFINITION(Register, eax  );
REGISTER_DEFINITION(Register, ecx  );
REGISTER_DEFINITION(Register, edx  );
REGISTER_DEFINITION(Register, ebx  );
REGISTER_DEFINITION(Register, esp  );
REGISTER_DEFINITION(Register, ebp  );
REGISTER_DEFINITION(Register, esi  );
REGISTER_DEFINITION(Register, edi  );

REGISTER_DEFINITION(XMMRegister, xmm0 );
REGISTER_DEFINITION(XMMRegister, xmm1 );
REGISTER_DEFINITION(XMMRegister, xmm2 );
REGISTER_DEFINITION(XMMRegister, xmm3 );
REGISTER_DEFINITION(XMMRegister, xmm4 );
REGISTER_DEFINITION(XMMRegister, xmm5 );
REGISTER_DEFINITION(XMMRegister, xmm6 );
REGISTER_DEFINITION(XMMRegister, xmm7 );

REGISTER_DEFINITION(MMXRegister, mmx0 );
REGISTER_DEFINITION(MMXRegister, mmx1 );
REGISTER_DEFINITION(MMXRegister, mmx2 );
REGISTER_DEFINITION(MMXRegister, mmx3 );
REGISTER_DEFINITION(MMXRegister, mmx4 );
REGISTER_DEFINITION(MMXRegister, mmx5 );
REGISTER_DEFINITION(MMXRegister, mmx6 );
REGISTER_DEFINITION(MMXRegister, mmx7 );
