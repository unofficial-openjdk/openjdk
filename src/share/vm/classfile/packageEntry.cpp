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
#include "classfile/moduleEntry.hpp"
#include "classfile/packageEntry.hpp"
#include "oops/symbol.hpp"
#include "runtime/handles.inline.hpp"
#include "utilities/events.hpp"
#include "utilities/growableArray.hpp"
#include "utilities/hashtable.inline.hpp"

QualifiedExportTable::QualifiedExportTable(int table_size)
                  : GrowableArray<jweak>(table_size, true) {
  assert_locked_or_safepoint(Module_lock);
}

QualifiedExportTable::~QualifiedExportTable() {
  assert(SafepointSynchronize::is_at_safepoint(), "must be at safepoint");
  int len = this->length();

  for (int idx = 0; idx < len; idx++) {
    jweak ref = this->at(idx);
    JNIHandles::destroy_weak_global(ref);
  }
}

// Add a module this package is exported to.
void QualifiedExportTable::add_qexport(jweak module) {
  assert_locked_or_safepoint(Module_lock);
  this->append_if_missing(module);
}

// Return true if this package is exported to module.
bool QualifiedExportTable::is_qexported_to(oop module) {
  int len = this->length();

  for (int idx = 0; idx < len; idx++) {
    oop module_idx = JNIHandles::resolve(this->at(idx));
    if (module_idx == module) {
      return true;
    }
  }
  return false;
}

// Remove dead weak references within the package's exported list.
void QualifiedExportTable::purge_qualified_exports(BoolObjectClosure* is_alive_closure) {
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

// Return true if this package is exported to m.
bool PackageEntry::is_qexported_to(ModuleEntry* m) const {
  assert(m != NULL, "No module to lookup in this package's qualified exports list");
  if (!_is_exported || _qualified_exports == NULL) {
    return false;
  } else {
    return _qualified_exports->is_qexported_to(m->module());
  }
}

// Add a module to the package's qualified export list.
void PackageEntry::add_qexport(ModuleEntry* m, TRAPS) {
  assert(_is_exported == true, "Adding a qualified export to a package that is not exported");

  // Create a weak reference to the module's oop
  Handle module_h(THREAD, m->module());
  jweak module_wref = JNIHandles::make_weak_global(module_h);

  MutexLocker m1(Module_lock, THREAD);
  if (_qualified_exports == NULL) {
    // Lazily create a package's qualified exports list
    _qualified_exports = new (ResourceObj::C_HEAP, mtClass) QualifiedExportTable(QualifiedExportTable::_qexport_table_size);
  }
  _qualified_exports->add_qexport(module_wref);
  m->set_pkgs_with_qexports(true);
}

// Set the package's exported state based on the value of the ModuleEntry.
void PackageEntry::set_exported(ModuleEntry* m, TRAPS) {
  if (_exported_pending_delete != NULL) {
    // The qualified exports lists is pending safepoint deletion, a prior
    // transition occurred from qualified to unqualified.
    return;
  }

  if (m == NULL) {
    // NULL indicates the package is being unqualifically exported
    if (_is_exported && _qualified_exports != NULL) {
      // Legit to transition a package from being qualifically exported
      // to unqualified.  Clean up the qualified lists at the next
      // safepoint.
      _exported_pending_delete = _qualified_exports;
    }

    // Mark package as unqualifically exported
    _is_exported = true;
    _qualified_exports = NULL;

  } else {
    if (_is_exported && _qualified_exports == NULL) {
      // An exception could be thrown, but choose to simply ignore.
      // Illegal to convert an unqualified exported package to be qualifically exported
      return;
    }

    // Add the exported module
    _is_exported = true;
    add_qexport(m, CHECK);
  }
}

// Remove dead weak references within the package's exported list.
void PackageEntry::purge_qualified_exports(BoolObjectClosure* is_alive_closure) {
  assert(SafepointSynchronize::is_at_safepoint(), "must be at safepoint");
  if (_qualified_exports != NULL) {
    _qualified_exports->purge_qualified_exports(is_alive_closure);
  }
}

void PackageEntry::delete_qualified_exports() {
  assert(SafepointSynchronize::is_at_safepoint(), "must be at safepoint");
  if (_exported_pending_delete != NULL) {
    // If a transition occurred from qualified to unqualified, the _qualified_exports
    // field should have been NULL'ed out.
    assert(_qualified_exports == NULL, "Package's exported pending delete, exported list should not be active");
    delete _exported_pending_delete;
  }

  if (_qualified_exports != NULL) {
    delete _qualified_exports;
  }

  _exported_pending_delete = NULL;
  _qualified_exports = NULL;
}

PackageEntryTable::PackageEntryTable(int table_size)
  : Hashtable<Symbol*, mtClass>(table_size, sizeof(PackageEntry))
{
}

void PackageEntryTable::add_entry(Symbol* name, ModuleEntry* module) {
  assert_locked_or_safepoint(Module_lock);
  PackageEntry* entry = new_entry(compute_hash(name), name, module);
  add_entry(index_for(name), entry);
}

void PackageEntryTable::add_entry(int index, PackageEntry* new_entry) {
  assert_locked_or_safepoint(Module_lock);
  Hashtable<Symbol*, mtClass>::add_entry(index, (HashtableEntry<Symbol*, mtClass>*)new_entry);
}

PackageEntry* PackageEntryTable::lookup(Symbol* name, ModuleEntry* module, TRAPS) {
  PackageEntry* p = lookup_only(name);
  if (p != NULL) {
    return p;
  } else {
    // If not found, add to table. Grab the PackageEntryTable lock first.
    MutexLocker ml(Module_lock, THREAD);

    // Since look-up was done lock-free, we need to check if another thread beat
    // us in the race to insert the package.
    PackageEntry* test = lookup_only(name);
    if (test != NULL) {
      // A race occurred and another thread introduced the package.
      return test;
    } else {
      PackageEntry* entry = new_entry(compute_hash(name), name, module);
      add_entry(index_for(name), entry);
      return entry;
    }
  }
}

PackageEntry* PackageEntryTable::lookup_only(Symbol* name) {
  int index = index_for(name);

  for (PackageEntry* p = bucket(index); p != NULL; p = p->next()) {
    if (p->name()->fast_compare(name) == 0) {
      return p;
    }
  }
  return NULL;
}

  // Create package in loader's package entry table and return the entry.
  // If entry already exists, return null.  Assume Module lock was taken by
  // caller.
  PackageEntry* PackageEntryTable::locked_create_entry(Symbol* name, ModuleEntry* module, TRAPS) {
    assert_locked_or_safepoint(Module_lock);
    // Check if package already exists.  Return NULL if it does.
    if (lookup_only(name) != NULL) {
      return NULL;
    } else {
      PackageEntry* entry = new_entry(compute_hash(name), name, module);
      add_entry(index_for(name), entry);
      return entry;
    }
  }

  // Create package in loader's package entry table and return the entry.
  // If entry already exists, return null.
  PackageEntry* PackageEntryTable::create_entry(Symbol* name, ModuleEntry* module, TRAPS) {
    MutexLocker ml(Module_lock, THREAD);
    return locked_create_entry(name, module, CHECK_NULL);
  }

void PackageEntryTable::free_entry(PackageEntry* entry) {
  // If we are at a safepoint, we don't have to establish the Module_lock.
  Mutex* lock_or_null = SafepointSynchronize::is_at_safepoint() ? NULL : Module_lock;
  MutexLockerEx ml(lock_or_null, Mutex::_no_safepoint_check_flag);
  assert_locked_or_safepoint(Module_lock);

  // Clean out the C heap allocated qualified exports list first before freeing the entry
  entry->delete_qualified_exports();
  Hashtable<Symbol*, mtClass>::free_entry(entry);
}

void PackageEntryTable::delete_entry(PackageEntry* to_delete) {
  unsigned int hash = compute_hash(to_delete->name());
  int index = hash_to_index(hash);

  PackageEntry** p = bucket_addr(index);
  PackageEntry* entry = bucket(index);
  while (true) {
    assert(entry != NULL, "sanity");
    if (entry == to_delete) {
      *p = entry->next();
      free_entry(entry);
      break;
    } else {
      p = entry->next_addr();
      entry = *p;
    }
  }
}

// Remove dead entries from all packages' exported list
void PackageEntryTable::purge_all_package_exports(BoolObjectClosure* is_alive_closure) {
  assert(SafepointSynchronize::is_at_safepoint(), "must be at safepoint");
  for (int i = 0; i < table_size(); i++) {
    for (PackageEntry** p = bucket_addr(i); *p != NULL;) {
      PackageEntry* entry = *p;
      if (entry->exported_pending_delete()) {
        // exported list is pending deletion due to a transition
        // from qualified to unqualified
        entry->delete_qualified_exports();
      } else if (entry->is_qual_exported()) {
        entry->purge_qualified_exports(is_alive_closure);
      }
      *p = entry->next();
    }
  }
}

// Remove all entries from the table, this should only occur at class unloading
void PackageEntryTable::delete_all_entries() {
  assert(SafepointSynchronize::is_at_safepoint(), "must be at safepoint");
  for (int i = 0; i < table_size(); i++) {
    for (PackageEntry** p = bucket_addr(i); *p != NULL;) {
      PackageEntry* entry = *p;
      *p = entry->next();
      free_entry(entry);
    }
  }
}

#ifndef PRODUCT
void PackageEntryTable::print() {
  tty->print_cr("Package Entry Table (table_size=%d, entries=%d)",
                table_size(), number_of_entries());
  for (int index = 0; index < table_size(); index++) {
    for (PackageEntry* probe = bucket(index);
                              probe != NULL;
                              probe = probe->next()) {
      probe->print();
    }
  }
}

void PackageEntry::print() {
  tty->print_cr("package entry "PTR_FORMAT" name "PTR_FORMAT" module "PTR_FORMAT" is_exported %d next "PTR_FORMAT,
                p2i(this), p2i(literal()), p2i(module()), _is_exported, p2i(next()));
}
#endif

void PackageEntryTable::verify() {
  int element_count = 0;
  for (int index = 0; index < table_size(); index++) {
    for (PackageEntry* probe = bucket(index);
                              probe != NULL;
                              probe = probe->next()) {
      probe->verify();
      element_count++;
    }
  }
  guarantee(number_of_entries() == element_count,
            "Verify of Package Entry Table failed");
  debug_only(verify_lookup_length((double)number_of_entries() / table_size()));
}

void PackageEntry::verify() {
  // FIX ME: ?
}
