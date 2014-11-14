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

#include "precompiled.hpp"
#include "classfile/classLoaderData.hpp"
#include "classfile/javaClasses.hpp"
#include "classfile/moduleEntry.hpp"
#include "oops/symbol.hpp"
#include "prims/jni.h"
#include "runtime/safepoint.hpp"
#include "utilities/events.hpp"
#include "utilities/growableArray.hpp"
#include "utilities/hashtable.hpp"

bool ModuleEntryTable::_javabase_created;

ReadsModuleTable::ReadsModuleTable(int table_size)
                  : GrowableArray<jweak>(table_size, true) {
  assert_locked_or_safepoint(Module_lock);
}

ReadsModuleTable::~ReadsModuleTable() {
  assert(SafepointSynchronize::is_at_safepoint(), "must be at safepoint");
  int len = this->length();

  for (int idx = 0; idx < len; idx++) {
    jweak ref = this->at(idx);
    JNIHandles::destroy_weak_global(ref);
  }
}

// Add a readable module
void ReadsModuleTable::add_read(jweak module) {
  assert_locked_or_safepoint(Module_lock);
  this->append_if_missing(module);
}

// Return true if this module can read module m
bool ReadsModuleTable::can_read(oop m) {
  int len = this->length();
  for (int idx = 0; idx < len; idx++) {
    oop module_idx = JNIHandles::resolve(this->at(idx));
    if (module_idx == m) {
      return true;
    }
  }
  return false;
}

// Remove dead weak references within the reads list
void ReadsModuleTable::purge_reads(BoolObjectClosure* is_alive_closure) {
  assert(SafepointSynchronize::is_at_safepoint(), "must be at safepoint");
  int len = this->length();

  // Go backwards because this removes entries that are dead.
  for (int idx = len - 1; idx >= 0; idx--) {
    oop module_idx = JNIHandles::resolve(this->at(idx));
    if (!is_alive_closure->do_object_b(module_idx)) {
      this->remove_at(idx);
    }
  }
}

// Returns true if this module can read module m
bool ModuleEntry::can_read(ModuleEntry* m) const {
  assert(m != NULL, "No module to lookup in this module's reads list");
  assert(_reads != NULL, "No reads list to lookup module entry in");
  if (_reads == NULL) {
    return false;
  } else {
    return _reads->can_read(m->module());
  }
}

// Add a new module to this module's reads list
void ModuleEntry::add_read(ModuleEntry* m, TRAPS) {
  assert(m != NULL, "No module to add to this module's reads list");

  // Create a weak reference to the module's oop
  Handle module_h(THREAD, m->module());
  jweak module_wref = JNIHandles::make_weak_global(module_h);

  MutexLocker m1(Module_lock, CHECK);
  if (_reads == NULL) {
    // Lazily create a module's reads list
    _reads = new (ResourceObj::C_HEAP, mtClass) ReadsModuleTable(ReadsModuleTable::_reads_table_size);
  }
  _reads->add_read(module_wref);
}

// Purge dead weak references out of reads list when any given class loader is unloaded.
void ModuleEntry::purge_reads(BoolObjectClosure* is_alive_closure) {
  assert(SafepointSynchronize::is_at_safepoint(), "must be at safepoint");
  if (_reads != NULL) {
    _reads->purge_reads(is_alive_closure);
  }
}

void ModuleEntry::delete_reads() {
  assert(SafepointSynchronize::is_at_safepoint(), "must be at safepoint");
  delete _reads;
  _reads = NULL;
}

ModuleEntryTable::ModuleEntryTable(int table_size)
  : Hashtable<oop, mtClass>(table_size, sizeof(ModuleEntry))
{
}

void ModuleEntryTable::add_entry(oop module, Symbol *name, ClassLoaderData* loader) {
  assert_locked_or_safepoint(Module_lock);
  ModuleEntry* entry = new_entry(compute_hash(module), module, name, loader);
  add_entry(index_for(module), entry);
}

void ModuleEntryTable::add_entry(int index, ModuleEntry* new_entry) {
  assert_locked_or_safepoint(Module_lock);
  Hashtable<oop, mtClass>::add_entry(index, (HashtableEntry<oop, mtClass>*)new_entry);
}

ModuleEntry* ModuleEntryTable::locked_create_entry(oop module, Symbol* module_name,
                                                    ClassLoaderData* loader, TRAPS) {
  assert_locked_or_safepoint(Module_lock);
  // Check if module already exists.
  if (lookup_only(module) != NULL) {
    return NULL;
  } else {
    ModuleEntry* entry = new_entry(compute_hash(module), module, module_name, loader);
    add_entry(index_for(module), entry);
    return entry;
  }
}

ModuleEntry* ModuleEntryTable::create_entry(oop module, Symbol* module_name,
                                            ClassLoaderData* loader, TRAPS) {
    // Grab the Module lock first.
    MutexLocker ml(Module_lock, THREAD);
    return locked_create_entry(module, module_name, loader, CHECK_NULL);
}

// lookup_only by Symbol* is a slow way to find a ModuleEntry and should not be used often
// Before a java.lang.Reflect.Module exists, however, only the name is available.
ModuleEntry* ModuleEntryTable::lookup_only(Symbol* name) {
  for (int index = 0; index < table_size(); index++) {
    for (ModuleEntry* m = bucket(index); m != NULL; m = m->next()) {
      if (m->name()->fast_compare(name) == 0) {
        return m;
      }
    }
  }
  return NULL;
}

ModuleEntry* ModuleEntryTable::lookup_only(oop module) {
  int index = index_for(module);
  for (ModuleEntry* m = bucket(index); m != NULL; m = m->next()) {
    if (m->module() == module) {
      return m;
    }
  }
  return NULL;
}

// Once a j.l.r.Module has been created for java.base during
// VM initialization, set its corresponding ModuleEntry correctly.
void ModuleEntryTable::set_javabase_entry(oop m) {
  Thread* THREAD = Thread::current();

  ModuleEntry* jb_module = lookup_only(vmSymbols::java_base());
  assert(jb_module != NULL, "No entry created for java.base?");

  // Set the j.l.r.M for java.base's ModuleEntry as well as the static
  // field within all ModuleEntryTables.
  jb_module->set_module(m);
  _javabase_created = true;
}

void ModuleEntryTable::patch_javabase_entries(TRAPS) {
  ResourceMark rm;

  // Create the java.lang.reflect.Module object for module 'java.base'.
  Handle java_base = java_lang_String::create_from_str(vmSymbols::java_base()->as_C_string(), CHECK);
  Handle jlrM_handle = java_lang_reflect_Module::create(Handle(ClassLoaderData::the_null_class_loader_data()->class_loader()), java_base, CHECK);
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
  for (int index = 0; index < table_size(); index++) {
    for (ModuleEntry* probe = bucket(index);
                              probe != NULL;
                              probe = probe->next()) {
      probe->oops_do(f);
    }
  }
}

void ModuleEntryTable::free_entry(ModuleEntry* entry) {
  // If we are at a safepoint, we don't have to establish the Module_lock.
  Mutex* lock_or_null = SafepointSynchronize::is_at_safepoint() ? NULL : Module_lock;
  MutexLockerEx ml(lock_or_null, Mutex::_no_safepoint_check_flag);
  assert_locked_or_safepoint(Module_lock);

  // Clean out the C heap allocated reads list first before freeing the entry
  entry->delete_reads();
  Hashtable<oop, mtClass>::free_entry(entry);
}

void ModuleEntryTable::delete_entry(ModuleEntry* to_delete) {
  unsigned int hash = compute_hash(to_delete->module());
  int index = hash_to_index(hash);

  ModuleEntry** m = bucket_addr(index);
  ModuleEntry* entry = bucket(index);
  while (true) {
    assert(entry != NULL, "sanity");
    if (entry == to_delete) {
      *m = entry->next();
      free_entry(entry);
      break;
    } else {
      m = entry->next_addr();
      entry = *m;
    }
  }
}

// Remove dead modules from all other alive modules' reads list.
// This should only occur at class unloading.
void ModuleEntryTable::purge_all_module_reads(BoolObjectClosure* is_alive_closure) {
  assert(SafepointSynchronize::is_at_safepoint(), "must be at safepoint");
  for (int i = 0; i < table_size(); i++) {
    for (ModuleEntry** m = bucket_addr(i); *m != NULL;) {
      ModuleEntry* entry = *m;
      entry->purge_reads(is_alive_closure);
      *m = entry->next();
    }
  }
}

// Remove all entries from the table, this should only occur at class unloading.
void ModuleEntryTable::delete_all_entries() {
  assert(SafepointSynchronize::is_at_safepoint(), "must be at safepoint");
  for (int i = 0; i < table_size(); i++) {
    for (ModuleEntry** m = bucket_addr(i); *m != NULL;) {
      ModuleEntry* entry = *m;
      *m = entry->next();
      free_entry(entry);
    }
  }
}

#ifndef PRODUCT
void ModuleEntryTable::print() {
  tty->print_cr("Module Entry Table (table_size=%d, entries=%d)",
                table_size(), number_of_entries());
  for (int index = 0; index < table_size(); index++) {
    for (ModuleEntry* probe = bucket(index);
                              probe != NULL;
                              probe = probe->next()) {
      probe->print();
    }
  }
}

void ModuleEntry::print() {
  tty->print_cr("entry "PTR_FORMAT" oop "PTR_FORMAT" name "PTR_FORMAT" loader "PTR_FORMAT" pkgs_with_qexports %d next "PTR_FORMAT,
                p2i(this), p2i(literal()), p2i(name()), p2i(loader()), _pkgs_with_qexports, p2i(next()));
}
#endif

void ModuleEntryTable::verify() {
  int element_count = 0;
  for (int index = 0; index < table_size(); index++) {
    for (ModuleEntry* probe = bucket(index);
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
