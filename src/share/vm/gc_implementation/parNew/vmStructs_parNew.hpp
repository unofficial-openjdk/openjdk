#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)vmStructs_parNew.hpp	1.1 07/05/01 16:48:02 JVM"
#endif
/*
 * @(#)vmStructs_parNew.hpp	1.1 07/05/01
 * 
 * Copyright 2006 Sun Microsystems, Inc. All rights reserved.
 * SUN PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

#define VM_TYPES_PARNEW(declare_type)                                     \
           declare_type(ParNewGeneration,             DefNewGeneration)

#define VM_INT_CONSTANTS_PARNEW(declare_constant)                         \
  declare_constant(Generation::ParNew)
