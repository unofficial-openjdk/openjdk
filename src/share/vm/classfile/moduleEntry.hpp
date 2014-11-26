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

#ifndef SHARE_VM_CLASSFILE_MODULEENTRY_HPP
#define SHARE_VM_CLASSFILE_MODULEENTRY_HPP

#include "classfile/classLoaderData.hpp"
#include "classfile/vmSymbols.hpp"
#include "oops/symbol.hpp"
#include "prims/jni.h"
#include "runtime/mutexLocker.hpp"
#include "utilities/growableArray.hpp"
#include "utilities/hashtable.hpp"

// ReadsModuleTable is a growable array implemented
// to contain a list of modules that a particular ModuleEntry
// can read.
class ReadsModuleTable : public GrowableArray<jweak> {
public:
  enum Constants {
    _reads_table_size = 29   // number of initial entries
  };
  ReadsModuleTable(int table_size);
  ~ReadsModuleTable();
  void add_read(jweak module);
  bool can_read(oop module);
  void purge_reads(BoolObjectClosure* is_alive_closure);
};

// A ModuleEntry describes a module that has been defined by a call to JVM_DefineModule.
// It contains:
//   - a pointer to the java.lang.reflect.Module object for this module.
//   - Symbol* containing the module's name.
//   - ClassLoaderData*, class loader of this module.
//   - a list containing weak references to j.l.r.Module objects that this
//     module can read.
//   - a flag indicating if any of the packages defined within this module have qualified
//     exports.
//
class ModuleEntry : public HashtableEntry<oop, mtClass> {
private:
  Symbol* _name;
  ClassLoaderData* _loader;
  ReadsModuleTable* _reads; // list of modules that are readable by this module
  bool _pkgs_with_qexports; // this module contains 1 or more packages with qualified exports

public:
  void init() {
    _name = NULL;
    _loader = NULL;
    _reads = NULL;
    _pkgs_with_qexports = false;
  }

  oop                module() const                 { return literal(); }
  void               set_module(oop m)              { set_literal(m); }

  Symbol*            name() const                   { return _name; }
  void               set_name(Symbol* n)            { _name = n; }

  ClassLoaderData*   loader() const                 { return _loader; }
  void               set_loader(ClassLoaderData* l) { _loader = l; }

  bool               can_read(ModuleEntry* m) const;
  void               add_read(ModuleEntry* m, TRAPS);

  bool               pkgs_with_qexports()           { return _pkgs_with_qexports; }
  void               set_pkgs_with_qexports(bool q) { _pkgs_with_qexports = q; }

  ModuleEntry* next() const {
    return (ModuleEntry*)HashtableEntry<oop, mtClass>::next();
  }
  ModuleEntry** next_addr() {
    return (ModuleEntry**)HashtableEntry<oop, mtClass>::next_addr();
  }

  // GC support
  void oops_do(OopClosure* f) {
    f->do_oop(literal_addr());
  }

  // Purge dead weak references out of reads list when any given class loader is unloaded.
  void purge_reads(BoolObjectClosure* is_alive_closure);
  void delete_reads();

  void print() PRODUCT_RETURN;
  void verify();
};

// The ModuleEntryTable is a Hashtable containing a list of all modules defined
// by a particular class loader.  Each module is represented as a ModuleEntry node.
//
// Each ModuleEntryTable contains a _javabase_created field indicating if java.base's
// corresponding j.l.r.Module has been created.  Having this field provided a way to create a
// ModuleEntry node for java.base very early in bootstrapping in order to obtain
// java.base's containing packages.  This occurs prior to creation of the j.l.r.Module
// for java.base during VM initialization.  java.base is the only module that does
// not have a corresponding JVM_DefineModule() invocation.  It is the JVM's
// responsibility to create.  Note, within the null boot class loader's ModulEntry table,
// the ModuleEntry for java.base is hashed at bucket 0.
//
// The ModuleEntryTable's lookup is lock free.  Note, that the fastest lookup is to
// invoke lookup_only with a j.l.r.M.  A lookup_only capability based on a module's
// name is also provided.
//
class ModuleEntryTable : public Hashtable<oop, mtClass> {
  friend class VMStructs;
public:
  enum Constants {
    _moduletable_entry_size  = 1009  // number of entries in module entry table
  };

private:
  static bool _javabase_created;

  ModuleEntry* new_entry(unsigned int hash, oop module, Symbol* name, ClassLoaderData* class_loader) {
    assert_locked_or_safepoint(Module_lock);
    ModuleEntry* entry = (ModuleEntry*) Hashtable<oop, mtClass>::new_entry(hash, module);
    entry->init();
    entry->set_name(name);
    entry->set_loader(class_loader);
    return entry;
  }

  void set_javabase_entry(oop m);
  void add_entry(int index, ModuleEntry* new_entry);
  void free_entry(ModuleEntry *entry);

public:
  ModuleEntryTable(int table_size);

  // Create module in loader's module entry table, if already exists then
  // return null.  Assume Module_lock has been locked by caller.
  ModuleEntry* locked_create_entry_or_null(oop module, Symbol* module_name,
                                           ClassLoaderData* loader);

  // only lookup module within loader's module entry table
  ModuleEntry* lookup_only(Symbol* name);
  ModuleEntry* lookup_only(oop module);

  ModuleEntry* bucket(int i) {
    return (ModuleEntry*)Hashtable<oop, mtClass>::bucket(i);
  }

  ModuleEntry** bucket_addr(int i) {
    return (ModuleEntry**)Hashtable<oop, mtClass>::bucket_addr(i);
  }

  static bool javabase_created() { return _javabase_created; }
  static void patch_javabase_entries(TRAPS);

  unsigned int compute_hash(oop module) {
    if (module == NULL) {
      // java.base prior to creation of its j.l.r.M
      return 0;
    }

    ModuleEntryTable* met = ClassLoaderData::the_null_class_loader_data()->modules();
    assert(met != NULL, "The null class loader's moduleEntry table should be defined");
    if (_javabase_created && (module == met->lookup_only(vmSymbols::java_base())->module())) {
      // java.base after creation of its j.l.r.M
      return 0;
    } else {
      return (unsigned int)(module->identity_hash());
    }
  }

  int index_for(oop module) {
    return hash_to_index(compute_hash(module));
  }

  // GC support
  void oops_do(OopClosure* f);

  // purge dead weak references out of reads list
  void purge_all_module_reads(BoolObjectClosure* is_alive_closure);

  void delete_entry(ModuleEntry* to_delete);
  void delete_all_entries();

  void print() PRODUCT_RETURN;
  void verify();
};

#endif // SHARE_VM_CLASSFILE_MODULEENTRY_HPP
