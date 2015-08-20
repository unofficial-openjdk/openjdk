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
#include "oops/symbol.hpp"
#include "prims/jni.h"
#include "runtime/handles.inline.hpp"
#include "runtime/safepoint.hpp"
#include "trace/traceMacros.hpp"
#include "utilities/events.hpp"
#include "utilities/growableArray.hpp"
#include "utilities/hashtable.inline.hpp"

ModuleEntry* ModuleEntryTable::_javabase_module = NULL;

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

void ModuleEntry::delete_reads() {
  assert(SafepointSynchronize::is_at_safepoint(), "must be at safepoint");
  delete _reads;
  _reads = NULL;
}

ModuleEntryTable::ModuleEntryTable(int table_size)
  : Hashtable<Symbol*, mtClass>(table_size, sizeof(ModuleEntry)), _unnamed_module(NULL)
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
        tty->print_cr("[deleting module: %s]", to_remove->name() != NULL ?
          to_remove->name()->as_C_string() : UNNAMED_MODULE);
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

ModuleEntryTable* ModuleEntryTable::create_module_entry_table(ClassLoaderData* loader_data) {
  assert_locked_or_safepoint(Module_lock);
  JavaThread *THREAD = JavaThread::current();
  ModuleEntryTable* module_table =
    new ModuleEntryTable(ModuleEntryTable::_moduletable_entry_size);

  if (module_table != NULL) {
    // Create ModuleEntry for unnamed module. Module entry tables have exactly
    // one unnamed module. Add it to bucket 0, no name to hash on.
    ModuleEntry* module_entry = module_table->new_entry(0, Handle(NULL), NULL, NULL, NULL, loader_data);
    module_table->add_entry(0, module_entry);
    module_table->set_unnamed_module(module_entry);
  }
  return module_table;
}

ModuleEntry* ModuleEntryTable::new_entry(unsigned int hash, Handle jlrM_handle, Symbol* name,
                                         Symbol* version, Symbol* location,
                                         ClassLoaderData* loader_data) {
  assert_locked_or_safepoint(Module_lock);
  ModuleEntry* entry = (ModuleEntry*) NEW_C_HEAP_ARRAY2(char, entry_size(), mtClass, CURRENT_PC);

  // Initialize everything BasicHashtable would
  entry->set_next(NULL);
  entry->set_hash(hash);
  entry->set_literal(name);

  // Initialize fields specific to a ModuleEntry
  entry->init();
  if (name != NULL) {
    name->increment_refcount();
  } else {
    // Unnamed modules can read all other unnamed modules.
    entry->set_can_read_unnamed();
  }

  if (!jlrM_handle.is_null()) {
    entry->set_jlrM_module(loader_data->add_handle(jlrM_handle));
  }

  entry->set_loader(loader_data);

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
  Hashtable<Symbol*, mtClass>::add_entry(index, (HashtableEntry<Symbol*, mtClass>*)new_entry);
}

ModuleEntry* ModuleEntryTable::locked_create_entry_or_null(Handle jlrM_handle,
                                                           Symbol* module_name,
                                                           Symbol *module_version,
                                                           Symbol *module_location,
                                                           ClassLoaderData* loader_data) {
  assert(module_name != NULL, "ModuleEntryTable locked_create_entry_or_null should never be called for unnamed module.");
  assert_locked_or_safepoint(Module_lock);
  // Check if module already exists.
  if (lookup_only(module_name) != NULL) {
    return NULL;
  } else {
    ModuleEntry* entry = new_entry(compute_hash(module_name), jlrM_handle, module_name,
                                   module_version, module_location, loader_data);
    add_entry(index_for(module_name), entry);
    return entry;
  }
}

// lookup_only by Symbol* to find a ModuleEntry.
ModuleEntry* ModuleEntryTable::lookup_only(Symbol* name) {
  if (name == NULL) {
    // Return this table's unnamed module
    return unnamed_module();
  }
  for (int i = 0; i < table_size(); i++) {
    for (ModuleEntry* m = bucket(i); m != NULL; m = m->next()) {
      if (m->name()->fast_compare(name) == 0) {
        return m;
      }
    }
  }
  return NULL;
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

void ModuleEntryTable::patch_javabase_entries(Handle jlrM_handle, TRAPS) {
  if (jlrM_handle.is_null()) {
    fatal("Cannot create java.lang.reflect.Module object for java.base");
  }

  if (TraceModules) {
    tty->print_cr("[MET::patch_javabase_entries, j.l.r.Module for java.base created]");
  }

  // Set jlrM_handle for java.base module in module entry table.
  assert(javabase_module() != NULL, "java.base ModuleEntry not defined");
  javabase_module()->set_jlrM_module(ClassLoaderData::the_null_class_loader_data()->add_handle(jlrM_handle));

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
  tty->print_cr("entry "PTR_FORMAT" name %s jlrM "PTR_FORMAT" loader %s version %s location %s strict %s next "PTR_FORMAT,
                p2i(this),
                name() == NULL ? UNNAMED_MODULE : name()->as_C_string(),
                p2i(jlrM_module()),
                loader()->loader_name(),
                version() != NULL ? version()->as_C_string() : "NULL",
                location() != NULL ? location()->as_C_string() : "NULL",
                BOOL_TO_STR(!can_read_unnamed()), p2i(next()));
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
  guarantee(loader() != NULL, "A module entry must be associated with a loader.");
}
