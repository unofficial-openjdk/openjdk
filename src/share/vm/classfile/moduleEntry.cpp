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

#include "precompiled.hpp"
#include "classfile/classLoaderData.hpp"
#include "classfile/javaClasses.hpp"
#include "classfile/moduleEntry.hpp"
#include "memory/resourceArea.hpp"
#include "oops/oop.inline.hpp"
#include "oops/symbol.hpp"
#include "prims/jni.h"
#include "runtime/handles.inline.hpp"
#include "runtime/safepoint.hpp"
#include "trace/traceMacros.hpp"
#include "utilities/events.hpp"
#include "utilities/growableArray.hpp"
#include "utilities/hashtable.inline.hpp"

bool ModuleEntryTable::_javabase_created = false;
ModuleEntry* ModuleEntryTable::_java_base_module;

// Returns true if this module can read module m
bool ModuleEntry::can_read(ModuleEntry* m) const {
  assert(m != NULL, "No module to lookup in this module's reads list");
  if (!this->is_named()) return true; // Unnamed modules read everyone.
  MutexLocker m1(Module_lock);
  if (_reads == NULL) {
    return false;
  } else {
    return _reads->contains(m);
  }
}

// Add a new module to this module's reads list
void ModuleEntry::add_read(ModuleEntry* m) {
  MutexLocker m1(Module_lock);
  if (m == NULL) {
    set_can_read_unnamed();
  } else {
    if (_reads == NULL) {
      // Lazily create a module's reads list
      // Initial size is 101.
      _reads = new (ResourceObj::C_HEAP, mtClass) GrowableArray<ModuleEntry*>(101, true);
    }
    _reads->append_if_missing(m);
  }
}

bool ModuleEntry::has_reads() const {
  return _reads != NULL && !_reads->is_empty();
}

// Purge dead module entries out of reads list.
void ModuleEntry::purge_reads() {
  assert(SafepointSynchronize::is_at_safepoint(), "must be at safepoint");
  if (_reads != NULL) {
    // Go backwards because this removes entries that are dead.
    int len = _reads->length();
    for (int idx = len - 1; idx >= 0; idx--) {
      ModuleEntry* module_idx = _reads->at(idx);
      ClassLoaderData* cld = module_idx->loader();
      if (cld->is_unloading()) {
        _reads->delete_at(idx);
      }
    }
  }
}

void ModuleEntry::delete_reads() {
  assert(SafepointSynchronize::is_at_safepoint(), "must be at safepoint");
  delete _reads;
  _reads = NULL;
}

ModuleEntryTable::ModuleEntryTable(int table_size)
  : Hashtable<oop, mtClass>(table_size, sizeof(ModuleEntry)), _unnamed_module(NULL)
{
}

ModuleEntryTable::~ModuleEntryTable() {
  assert_locked_or_safepoint(Module_lock);

  // Walk through all buckets and all entries in each bucket,
  // freeing each entry.
  for (int i = 0; i < table_size(); ++i) {
    for (ModuleEntry* m = bucket(i); m != NULL;) {
      ModuleEntry* to_remove = m;
      // read next before freeing.
      m = m->next();

      if (TraceModules) {
        ResourceMark rm;
        tty->print("[deleting module: %s, ", to_remove->name()->as_C_string());
        to_remove->loader()->print_value();
        tty->cr();
      }

      // Clean out the C heap allocated reads list first before freeing the entry
      to_remove->delete_reads();
      if (to_remove->name() != NULL) {
        to_remove->name()->decrement_refcount();
      }
      if (to_remove->version() != NULL) {
        to_remove->version()->decrement_refcount();
      }
      if (to_remove->location() != NULL) {
        to_remove->location()->decrement_refcount();
      }

      // Unlink from the Hashtable prior to freeing
      unlink_entry(to_remove);
      FREE_C_HEAP_ARRAY(char, to_remove);
    }
  }
  assert(number_of_entries() == 0, "should have removed all entries");
  assert(new_entry_free_list() == NULL, "entry present on ModuleEntryTable's free list");
  free_buckets();
}

ModuleEntryTable* ModuleEntryTable::create_module_entry_table(ClassLoaderData* class_loader) {
  assert_locked_or_safepoint(Module_lock);
  JavaThread *THREAD = JavaThread::current();
  ModuleEntryTable* modules =
    new ModuleEntryTable(ModuleEntryTable::_moduletable_entry_size);

  if (modules != NULL) {
    // Create ModuleEntry for unnamed module. Module entry tables have exactly
    // one unnamed module.
    ModuleEntry* module_entry = modules->new_entry(0, NULL, NULL, NULL,
                                                   NULL, class_loader);
    modules->add_entry(0, module_entry);
    modules->set_unnamed_module(module_entry);
  }
  return modules;
}

ModuleEntry* ModuleEntryTable::new_entry(unsigned int hash, oop module, Symbol* name,
                                         Symbol* version, Symbol* location,
                                         ClassLoaderData* class_loader) {
  assert_locked_or_safepoint(Module_lock);

  ModuleEntry* entry = (ModuleEntry*) NEW_C_HEAP_ARRAY2(char, entry_size(), mtClass, CURRENT_PC);

  // Initialize everything BasicHashtable would
  entry->set_next(NULL);
  entry->set_hash(hash);
  entry->set_literal(module);

  // Initialize fields specific to a ModuleEntry
  entry->init();
  if (name != NULL) {
    entry->set_name(name);
    name->increment_refcount();
  } else {
    // Unnamed modules can read all other unnamed modules.
    entry->set_can_read_unnamed();
  }
  entry->set_loader(class_loader);
  if (version != NULL) {
    entry->set_version(version);
    version->increment_refcount();
  }
  if (location != NULL) {
    entry->set_location(location);
    location->increment_refcount();
  }
  TRACE_INIT_MODULE_ID(entry);

  return entry;
}

void ModuleEntryTable::add_entry(int index, ModuleEntry* new_entry) {
  assert_locked_or_safepoint(Module_lock);
  Hashtable<oop, mtClass>::add_entry(index, (HashtableEntry<oop, mtClass>*)new_entry);
}

ModuleEntry* ModuleEntryTable::locked_create_entry_or_null(oop module, Symbol* module_name,
                                                           Symbol *module_version,
                                                           Symbol *module_location,
                                                           ClassLoaderData* loader) {
  assert_locked_or_safepoint(Module_lock);
  // Check if module already exists.
  if (lookup_only(module_name) != NULL) {
    return NULL;
  } else {
    ModuleEntry* entry = new_entry(compute_hash(module), module, module_name,
                                   module_version, module_location, loader);
    add_entry(index_for(module), entry);
    return entry;
  }
}

// lookup_only by Symbol* to find a ModuleEntry. Before a java.lang.reflect.Module
// exists only the module name is available.
ModuleEntry* ModuleEntryTable::lookup_only(Symbol* name) {
  for (int i = 0; i < table_size(); i++) {
    for (ModuleEntry* m = bucket(i); m != NULL; m = m->next()) {
      if (m->name()->fast_compare(name) == 0) {
        return m;
      }
    }
  }
  return NULL;
}

// Once a j.l.r.Module has been created for java.base during
// VM initialization, set its corresponding ModuleEntry correctly.
void ModuleEntryTable::set_javabase_entry(oop m) {
  Thread* THREAD = Thread::current();

  ModuleEntry* jb_module = lookup_only(vmSymbols::java_base());
  if (jb_module == NULL) {
    vm_exit_during_initialization("No module entry for java.base located");
  }

  // Set the j.l.r.M for java.base's ModuleEntry as well as the static
  // field within all ModuleEntryTables.
  jb_module->set_module(m);

  // Store the ModuleEntry pointer in the oop.
  java_lang_reflect_Module::set_module_entry(m, jb_module);

  _javabase_created = true;
}

void ModuleEntryTable::patch_javabase_entries(TRAPS) {
  ResourceMark rm;

  // Create the java.lang.reflect.Module object for module 'java.base'.
  Handle java_base = java_lang_String::create_from_str(vmSymbols::java_base()->as_C_string(), CHECK);
  Handle jlrM_handle = java_lang_reflect_Module::create(
                         Handle(ClassLoaderData::the_null_class_loader_data()->class_loader()), java_base, CHECK);
  if (jlrM_handle.is_null()) {
    fatal("Cannot create java.lang.reflect.Module object for java.base");
  }

  if (TraceModules) {
    tty->print_cr("[MET::patch_javabase_entries, j.l.r.Module for java.base created]");
  }

  // Set jlrM_handle for java.base module in module entry table.
  ClassLoaderData::the_null_class_loader_data()->modules()->set_javabase_entry(jlrM_handle());

  // Do the fixups for classes that have already been created.
  GrowableArray <Klass*>* list = java_lang_Class::fixup_jlrM_list();
  int list_length = list->length();
  for (int i = 0; i < list_length; i++) {
    Klass* k = list->at(i);
    assert(k->is_klass(), "List should only hold classes");
    EXCEPTION_MARK;
    KlassHandle kh(THREAD, k);
    java_lang_Class::fixup_jlrM(kh, jlrM_handle, CATCH);
    if (TraceModules) {
      tty->print_cr("[MET::patch_javabase_entries, patching class %s]", k->external_name());
    }
  }
  delete java_lang_Class::fixup_jlrM_list();
  java_lang_Class::set_fixup_jlrM_list(NULL);

  if (TraceModules) {
    tty->print_cr("[MET::patch_javabase_entries, patching complete, fixup array deleted]");
  }
}

void ModuleEntryTable::oops_do(OopClosure* f) {
  for (int i = 0; i < table_size(); i++) {
    for (ModuleEntry* probe = bucket(i);
                              probe != NULL;
                              probe = probe->next()) {
      probe->oops_do(f);
    }
  }
}

// Remove dead modules from all other alive modules' reads list.
// This should only occur at class unloading.
void ModuleEntryTable::purge_all_module_reads() {
  assert(SafepointSynchronize::is_at_safepoint(), "must be at safepoint");
  for (int i = 0; i < table_size(); i++) {
    for (ModuleEntry* entry = bucket(i);
                      entry != NULL;
                      entry = entry->next()) {
      entry->purge_reads();
    }
  }
}

#ifndef PRODUCT
void ModuleEntryTable::print() {
  tty->print_cr("Module Entry Table (table_size=%d, entries=%d)",
                table_size(), number_of_entries());
  for (int i = 0; i < table_size(); i++) {
    for (ModuleEntry* probe = bucket(i);
                              probe != NULL;
                              probe = probe->next()) {
      probe->print();
    }
  }
}

void ModuleEntry::print() {
  ResourceMark rm;
  tty->print_cr("entry "PTR_FORMAT" oop "PTR_FORMAT" name %s loader %s version %s location %s strict %s pkgs_with_qexports %d next "PTR_FORMAT,
                p2i(this), p2i(literal()),
                name() == NULL ? UNNAMED_MODULE : name()->as_C_string(),
                loader()->loader_name(),
                version() != NULL ? version()->as_C_string() : "NULL",
                location() != NULL ? location()->as_C_string() : "NULL",
                BOOL_TO_STR(!can_read_unnamed()), _pkgs_with_qexports, p2i(next()));
}
#endif

void ModuleEntryTable::verify() {
  int element_count = 0;
  for (int i = 0; i < table_size(); i++) {
    for (ModuleEntry* probe = bucket(i);
                              probe != NULL;
                              probe = probe->next()) {
      probe->verify();
      element_count++;
    }
  }
  guarantee(number_of_entries() == element_count,
            "Verify of Module Entry Table failed");
  debug_only(verify_lookup_length((double)number_of_entries() / table_size()));
}

void ModuleEntry::verify() {
  guarantee(literal()->is_oop(), "must be an oop");
}

void ModuleEntry::module_reads_do(ModuleClosure* const f) {
  assert_locked_or_safepoint(Module_lock);
  assert(f != NULL, "invariant");

  if (_reads != NULL) {
    int reads_len = _reads->length();
    for (int i = 0; i < reads_len; ++i) {
      f->do_module(_reads->at(i));
    }
  }
}
