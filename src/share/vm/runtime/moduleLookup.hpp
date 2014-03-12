/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_VM_RUNTIME_MODULELOOKUP_HPP
#define SHARE_VM_RUNTIME_MODULELOOKUP_HPP

#include "memory/allocation.hpp"
#include "memory/allocation.inline.hpp"
#include "runtime/handles.hpp"
#include "runtime/module.hpp"

// Used for mapping a package to a Module

class PackageEntry :  public CHeapObj<mtInternal> {
 private:
  char* _pkg;
  Module* _module;
  unsigned int _hash;
  PackageEntry* _next;
 public:
  PackageEntry(const char* pkg, Module* module, int hash) {
    _pkg = strdup(pkg);
    _module = module;
    _hash = hash;
    _next = NULL;
  }

  const char* package()  { return _pkg; }
  Module* module()       { return _module; }
  unsigned int hash()    { return _hash; }

  PackageEntry* next()               { return _next; }
  void set_next(PackageEntry* entry) { _next = entry; }
};


// Simple class to map loader/package to Module.

class ModuleLookup : public CHeapObj<mtInternal> {
 private:
  enum Constants {
    _initial_table_size = 1009
  };

  // special instance for the null loader
  static ModuleLookup * _the_null_class_loader_module_lookup;

  // returns the ModuleLookup for the given loader, creating if needed
  static ModuleLookup* module_lookup_for(Handle loader);

  // compute the hash code for the given package name
  static unsigned int compute_hash(const char* pkg) {
    unsigned int hash = 0;
    int len = (int)strlen(pkg);
    while (len-- > 0) {
      hash = 31*hash + (unsigned) *pkg;
      pkg++;
    }
    return hash;
  }

  // hash table
  PackageEntry** _table;
  int _table_size;

  // create with a hash table of the given size
  ModuleLookup(int table_size) {
    _table_size = table_size;
    _table = (PackageEntry**) NEW_C_HEAP_ARRAY(PackageEntry*, table_size, mtInternal);
    for (int i = 0; i < _table_size; i++) {
      _table[i] = NULL;
    }
  }

  // simple mapping of hash to entry in hash table
  int hash_to_index(unsigned int hash) {
    return hash % _table_size;
  }

  // return the first entry at the given index
  PackageEntry* first_at(int index) {
    assert(index >= 0 && index < _table_size, "index out of range");
    return _table[index];
  }

  // set the first entry at the given index
  void set_first(int index, PackageEntry* entry) {
    assert(index >= 0 && index < _table_size, "index out of range");
    _table[index] = entry;
  }

 public:
  // used to lazily associate a loader/package to a module
  static void bind_to_module(Handle loader, const char* pkg, Module* module);

  // returns the ModuleLookup for the given loader or null if none
  static ModuleLookup* module_lookup_or_null(Handle loader);

  // return the module for the given package (NULL if not found)
  Module* lookup(const char* pkg);
};


#endif // SHARE_VM_RUNTIME_MODULELOOKUP_HPP
