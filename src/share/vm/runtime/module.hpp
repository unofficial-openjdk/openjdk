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

#ifndef SHARE_VM_RUNTIME_MODULE_HPP
#define SHARE_VM_RUNTIME_MODULE_HPP

#include "memory/allocation.hpp"
#include "memory/allocation.inline.hpp"
#include "oops/klass.hpp"

class Module;

// A permit to access types in packages loaded by a class loader
class PackagePermit : public CHeapObj<mtInternal> {
 private:
  int _loader_tag;
  char* _pkg;
 public:
  PackagePermit(int loader_tag, const char* pkg) {
    _loader_tag = loader_tag;
    _pkg = strdup(pkg);
  }

  int loader_tag()      { return _loader_tag; }
  const char* package() { return _pkg; }
};

// A module export, allowed a package to be exported to anyone,
// specific modules or specific packages (the latter via the backdoor)
class PackageExport :  public CHeapObj<mtInternal> {
 private:
  char* _pkg;
  GrowableArray<Module*>* _module_permits;

  // additional loader/packages with backdoor access
  GrowableArray<PackagePermit*>* _package_permits;

  unsigned int _hash;
  PackageExport* _next;
 public:
  PackageExport(const char* pkg, int hash) {
    _pkg = strdup(pkg);
    _module_permits = NULL;
    _package_permits = NULL;
    _hash = hash;
    _next = NULL;
  }

  const char* package()         { return _pkg; }
  bool has_module_permits()     { return _module_permits != NULL; }

  unsigned int hash()   { return _hash; }
  PackageExport* next() { return _next; }
  void set_next(PackageExport* entry) { _next = entry; }

  void add_module_permits(Module* other) {
    if (_module_permits == NULL) {
      _module_permits = new (ResourceObj::C_HEAP, mtInternal)GrowableArray<Module*>(8, true);
    }
    _module_permits->append(other);
  }

  bool is_permitted(Module* other) {
    if (_module_permits == NULL) {
       return true;
    }
    for (int i=0; i<_module_permits->length(); i++) {
      if (_module_permits->at(i) == other) {
        return true;
      }
    }
    return false;
  }

  void add_package_permits(int loader_tag, const char* pkg) {
    if (_package_permits == NULL) {
      _package_permits = new (ResourceObj::C_HEAP, mtInternal)GrowableArray<PackagePermit*>(2, true);
    }
    _package_permits->append(new PackagePermit(loader_tag, pkg));
  }

  bool is_permitted(int loader_tag, const char* pkg) {
    if (_package_permits == NULL) {
      return false;
    }
    for (int i=0; i<_package_permits->length(); i++) {
      PackagePermit* permit = _package_permits->at(i);
      if (permit->loader_tag() == loader_tag &&
          strcmp(permit->package(), pkg) == 0) return true;
    }
    return false;
  }
};


// A Module
class Module : public CHeapObj<mtInternal> {
 private:
  enum Constants {
    _table_size = 17
  };

  char* _name;  // module name
  GrowableArray<Module*>* _requires; // list of modules required by this module

  PackageExport** _exports; // packages exported by this module

  Module(const char* name) {
    _name = strdup(name);
    _requires = new (ResourceObj::C_HEAP, mtInternal)GrowableArray<Module*>(8, true);

    _exports = (PackageExport**) NEW_C_HEAP_ARRAY(PackageExport*, _table_size, mtInternal);
    for (int i = 0; i < _table_size; i++) {
      _exports[i] = NULL;
    }
  }

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

  // simple mapping of hash to entry in hash table
  int hash_to_index(unsigned int hash) {
    return hash % _table_size;
  }

  // return the first entry at the given index
  PackageExport* first_at(int index) {
    assert(index >= 0 && index < _table_size, "index out of range");
    return _exports[index];
  }

  // set the first entry at the given index
  void set_first(int index, PackageExport* entry) {
    assert(index >= 0 && index < _table_size, "index out of range");
    _exports[index] = entry;
  }

 public:
  // creates a new module with the given name
  static Module* define_module(const char* name);

  // returns the module for the given Klass (or NULL)
  static Module* module_for(Klass* k);

  // the module name
  char* name() { return _name; }

  // setup this module so that it requires other
  void add_requires(Module* other);

  // returns true if this module requires other
  bool requires(Module* other);

  // export packages
  void export_without_permits(const char* pkg);
  void export_with_permits(const char* pkg, Module* other);

  // returns true if exported without permits
  bool is_exported_without_permits(const char* pkg);

  // returns true if exported other
  bool is_exported_to_module(const char* pkg, Module* other);

  // special access needed for classes in the unnamed module at runtime
  void add_backdoor_access(const char* pkg, int loader_tag, const char* who);
  bool has_backdoor_access(const char* pkg, int loader_tag, const char* who);
};

#endif // SHARE_VM_RUNTIME_MODULE_HPP
