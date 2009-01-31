#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "%W% %E% %U% JVM"
#endif
/*
 * Copyright 1999 Sun Microsystems, Inc.  All Rights Reserved.
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

#include "incls/_precompiled.incl"
#include "incls/_ciFlags.cpp.incl"

// ciFlags
//
// This class represents klass or method flags

// ------------------------------------------------------------------
// ciFlags::print_klass_flags
void ciFlags::print_klass_flags() {
  if (is_public()) {
    tty->print("public");
  } else {
    tty->print("DEFAULT_ACCESS");
  }

  if (is_final()) {
    tty->print(",final");
  }
  if (is_super()) {
    tty->print(",super");
  }
  if (is_interface()) {
    tty->print(",interface");
  }
  if (is_abstract()) {
    tty->print(",abstract");
  }
}

// ------------------------------------------------------------------
// ciFlags::print_member_flags
void ciFlags::print_member_flags() {
  if (is_public()) {
    tty->print("public");
  } else if (is_private()) {
    tty->print("private");
  } else if (is_protected()) {
    tty->print("protected");
  } else {
    tty->print("DEFAULT_ACCESS");
  }

  if (is_static()) {
    tty->print(",static");
  }
  if (is_final()) {
    tty->print(",final");
  }
  if (is_synchronized()) {
    tty->print(",synchronized");
  }
  if (is_volatile()) {
    tty->print(",volatile");
  }
  if (is_transient()) {
    tty->print(",transient");
  }
  if (is_native()) {
    tty->print(",native");
  }
  if (is_abstract()) {
    tty->print(",abstract");
  }
  if (is_strict()) {
    tty->print(",strict");
  }
    
}

// ------------------------------------------------------------------
// ciFlags::print
void ciFlags::print() {
  tty->print(" flags=%x", _flags);
}
