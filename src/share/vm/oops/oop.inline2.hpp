#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "%W% %E% %U% JVM"
#endif
/*
 * Copyright 2000-2006 Sun Microsystems, Inc.  All Rights Reserved.
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

// Implementation of all inlined member functions defined in oop.hpp
// We need a separate file to avoid circular references

// Separate this out to break dependency.
inline bool oopDesc::is_perm() const {
  return Universe::heap()->is_in_permanent(this);
}

// Check for NULL also.
inline bool oopDesc::is_perm_or_null() const {
  return this == NULL || is_perm();
}

// is_perm only verifies that oop is in the reserved space for
// the perm gen. Things like forte stackwalking need something that
// assures us that the pointer is in the commited area so we don't
// segv checking suspicious frame contents.

inline bool oopDesc::is_perm_and_alloced() const {
  return Universe::heap()->is_permanent(this);
}


