/*
* Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_VM_CLASSFILE_MODULES_HPP
#define SHARE_VM_CLASSFILE_MODULES_HPP

#include "memory/allocation.hpp"
#include "runtime/handles.hpp"

class Symbol;

class Modules : AllStatic {

public:
  // define_module defines a module containing the specified packages. It binds the
  // module to its class loader by creating the ModuleEntry record in the
  // ClassLoader's ModuleEntry table, creates PackageEntry records in the class
  // loader's PackageEntry table, and, if successful, creates and returns a
  // java.lang.reflect.Module object.  As in JVM_DefineClass the jstring format
  // for all package names must use "/" and not "."
  //
  //  IllegalArgumentExceptions are thrown for the following :
  // * Class loader already has a module with that name
  // * Class loader has already defined types for any of the module's packages
  // * Module_name is 'java.base'
  // * Module_name is syntactically bad
  // * Packages contains an illegal package name
  // * Packages contains a duplicate package name
  // * A package already exists in another module for this class loader
  // * Class loader is not a subclass of java.lang.ClassLoader
  //  NullPointerExceptions are thrown if module is null.
  static void define_module(JNIEnv *env, jobject module, jstring version,
                             jstring location, jobjectArray packages);

  // This either does a qualified export of package in module from_module to module
  // to_module or, if to_module is null, does an unqualified export of package.
  // The format for the package name must use "/' not ".".
  //
  // Error conditions causing IlegalArgumentException to be throw :
  // * Module from_module does not exist
  // * Module to_module is not null and does not exist
  // * Package is not syntactically correct
  // * Package is not defined for from_module's class loader
  // * Package is not in module from_module.
  static void add_module_exports(JNIEnv *env, jobject from_module, jstring package, jobject to_module);

  // add_reads_module adds module to_module to the list of modules that from_module
  // can read.If from_module is the same as to_module then this is a no - op.
  // An IllegalArgumentException is thrown if either from_module or to_module is null or does not exist.
  static void add_reads_module(JNIEnv *env, jobject from_module, jobject to_module);

  // can_read_module returns TRUE if module asking_module can read module target_module
  // or if they are the same module.
  //
  // Throws IllegalArgumentException if:
  // * either asking_module or target_module is not a java.lang.reflect.Module
  static jboolean can_read_module(JNIEnv *env, jobject asking_module, jobject target_module);

  // If package is valid then this returns TRUE if module from_module exports
  // package to module to_module, if from_module and to_module are the same
  // module, or if package is exported without qualification.
  //
  // IllegalArgumentException is throw if:
  // * Either to_module or from_module does not exist
  // * package is syntactically incorrect
  // * package is not in from_module
  static jboolean is_exported_to_module(JNIEnv *env, jobject from_module, jstring package, jobject to_module);

  // Return the java.lang.reflect.Module object for this class object.
  static jobject get_module(JNIEnv *env, jclass clazz);

  // If package is defined by loader, return the
  // java.lang.reflect.Module object for the module in which the package is defined.
  // Returns NULL if package is invalid or not defined by loader.
  static jobject get_module(Symbol* package_name, Handle h_loader, TRAPS);

  // This adds package to module.
  // It throws IllegalArgumentException if:
  // * Module is bad
  // * Package is not syntactically correct
  // * Package is already defined for module's class loader.
  static void add_module_package(JNIEnv *env, jobject module, jstring package);

  // Return TRUE if package_name is syntactically valid, false otherwise.
  static bool verify_package_name(char *package_name);

  // Return TRUE iff package is defined by loader
  static bool is_package_defined(Symbol* package_name, Handle h_loader, TRAPS);

  static const char* default_version() { return "9.0"; }

};

#endif // SHARE_VM_CLASSFILE_MODULES_HPP
