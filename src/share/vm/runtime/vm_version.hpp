#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)vm_version.hpp	1.27 07/08/08 19:44:04 JVM"
#endif
/*
 * Copyright 1997-2007 Sun Microsystems, Inc.  All Rights Reserved.
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

// VM_Version provides information about the VM.

class Abstract_VM_Version: AllStatic {
 protected:
  friend class VMStructs;
  static const char*  _s_vm_release;
  static const char*  _s_internal_vm_info_string;
  // These are set by machine-dependent initializations
  static bool         _supports_cx8;
  static unsigned int _logical_processors_per_package;
  static int          _vm_major_version;
  static int          _vm_minor_version;
  static int          _vm_build_number;
  static bool         _initialized;
 public:
  static void initialize();

  // Name
  static const char* vm_name();
  // Vendor
  static const char* vm_vendor();
  // VM version information string printed by launcher (java -version)
  static const char* vm_info_string();
  static const char* vm_release();
  static const char* vm_platform_string();

  static int vm_major_version()               { assert(_initialized, "not initialized"); return _vm_major_version; }
  static int vm_minor_version()               { assert(_initialized, "not initialized"); return _vm_minor_version; }
  static int vm_build_number()                { assert(_initialized, "not initialized"); return _vm_build_number; }

  // Gets the jvm_version_info.jvm_version defined in jvm.h
  static unsigned int jvm_version();

  // Internal version providing additional build information
  static const char* internal_vm_info_string();

  // does HW support an 8-byte compare-exchange operation?
  static bool supports_cx8()  {return _supports_cx8;}
  static unsigned int logical_processors_per_package() {
    return _logical_processors_per_package;
  }
};

