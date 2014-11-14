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

#ifndef SHARE_VM_CLASSFILE_PACKAGEENTRY_HPP
#define SHARE_VM_CLASSFILE_PACKAGEENTRY_HPP

#include "classfile/moduleEntry.hpp"
#include "oops/symbol.hpp"
#include "utilities/growableArray.hpp"
#include "utilities/hashtable.hpp"

// QualifiedExportTable is a growable array implemented
// to contain a list of modules that a particular PackageEntry
// is exported to.
class QualifiedExportTable : public GrowableArray<jweak> {
public:
  enum Constants {
    _qexport_table_size = 29   // number of entries in the qualified export list
  };
  QualifiedExportTable(int table_size);
  ~QualifiedExportTable();
  void add_qexport(jweak module);
  bool is_qexported_to(oop module);
  void purge_qualified_exports(BoolObjectClosure* is_alive_closure);
};

// A PackageEntry basically represents a Java package.  It contains:
//   - Symbol* containing the package's name.
//   - ModuleEntry* for this package's containing module.
//     NULL if the package was defined within the unnamed module.
//   - a list containing weak references to j.l.r.Module objects that this
//     package is exported to.
//   - a flag indicating if package is exported, either qualifically or
//     unqualifically.
//
// Packages that are:
//   - not exported:        _qualified_exports = NULL  && _is_exported is false
//   - qualified exports:   _qualified_exports != NULL && _is_exported is true
//   - unqualified exports: _qualified_exports = NULL  && _is_exported is true
//
class PackageEntry : public HashtableEntry<Symbol*, mtClass> {
private:
  ModuleEntry* _module;
  bool _is_exported;
  QualifiedExportTable* _exported_pending_delete; // transitioned from qualified to unqualified, delete at safepoint
  QualifiedExportTable* _qualified_exports;

public:
  void init() {
    _module = NULL;
    _is_exported = false;
    _exported_pending_delete = NULL;
    _qualified_exports = NULL;
  }

  // package name
  Symbol*            name() const               { return literal(); }
  void               set_name(Symbol* n)        { set_literal(n); }

  // the module containing the package definition
  ModuleEntry*       module() const             { return _module; }
  void               set_module(ModuleEntry* m) { _module = m; }

  // package's export state
  bool               is_exported() const                 { return _is_exported; } // qualifically or unqualifically exported
  bool               is_qual_exported() const            { return (_is_exported && (_qualified_exports != NULL)); }
  bool               is_unqual_exported() const          { return (_is_exported && (_qualified_exports == NULL)); }
  bool               exported_pending_delete() const     { return (_exported_pending_delete != NULL); }

  void               set_exported(bool e)                { _is_exported = e; }
  void               set_exported(ModuleEntry* m, TRAPS);

  // returns true if the package is defined in the unnamed module
  bool               in_unnamed_module() const  { return (_module == NULL); }

  // returns true if the package specifies m as a qualified export
  bool               is_qexported_to(ModuleEntry* m) const;

  // add the module to the package's qualified exports
  void               add_qexport(ModuleEntry* m, TRAPS);

  PackageEntry* next() const {
    return (PackageEntry*)HashtableEntry<Symbol*, mtClass>::next();
  }

  PackageEntry** next_addr() {
    return (PackageEntry**)HashtableEntry<Symbol*, mtClass>::next_addr();
  }

  // Purge dead weak references out of exported list when any given class loader is unloaded.
  void purge_qualified_exports(BoolObjectClosure* is_alive_closure);
  void delete_qualified_exports();

  void print() PRODUCT_RETURN;
  void verify();
};

// The PackageEntryTable is a Hashtable containing a list of all packages defined
// by a particular class loader.  Each package is represented as a PackageEntry node.
// The PackageEntryTable's lookup is lock free.
//
class PackageEntryTable : public Hashtable<Symbol*, mtClass> {
  friend class VMStructs;
public:
  enum Constants {
    _packagetable_entry_size = 1009  // number of entries in package entry table
  };

private:
  PackageEntry* new_entry(unsigned int hash, Symbol* name, ModuleEntry* module) {
    assert_locked_or_safepoint(Module_lock);
    PackageEntry* entry = (PackageEntry*)Hashtable<Symbol*, mtClass>::new_entry(hash, name);
    entry->init();
    if (module == NULL) {
      // Indicates the unnamed module.
      // Set the exported state to true because all packages
      // within the unnamed module are unqualifically exported
      entry->set_exported(true);
    } else {
      entry->set_module(module);
    }
    return entry;
  }

  void add_entry(int index, PackageEntry* new_entry);
  void add_entry(Symbol* name, ModuleEntry* module);
  void free_entry(PackageEntry *entry);

public:
  PackageEntryTable(int table_size);

  // Create package in loader's package entry table and return the entry.
  // If entry already exists, return null.
  PackageEntry* create_entry(Symbol* name, ModuleEntry* module, TRAPS);

  // Create package in loader's package entry table and return the entry.
  // If entry already exists, return null.  Assume Module lock was taken by
  // caller.
  PackageEntry* locked_create_entry(Symbol* name, ModuleEntry* module, TRAPS);

  // lookup Package with loader's package entry table, if not found add
  PackageEntry* lookup(Symbol* name, ModuleEntry* module, TRAPS);

  // only lookup Package within loader's package entry table
  PackageEntry* lookup_only(Symbol* Package);

  PackageEntry* bucket(int i) {
    return (PackageEntry*)Hashtable<Symbol*, mtClass>::bucket(i);
  }

  PackageEntry** bucket_addr(int i) {
    return (PackageEntry**)Hashtable<Symbol*, mtClass>::bucket_addr(i);
  }

  static unsigned int compute_hash(Symbol* name) {
    return (unsigned int)(name->identity_hash());
  }

  int index_for(Symbol* name) {
    return hash_to_index(compute_hash(name));
  }

  // purge dead weak references out of exported list
  void purge_all_package_exports(BoolObjectClosure* is_alive_closure);

  // remove obsolete package entry
  void delete_entry(PackageEntry* to_delete);
  void delete_all_entries();

  void print() PRODUCT_RETURN;
  void verify();
};

#endif // SHARE_VM_CLASSFILE_PACKAGEENTRY_HPP
