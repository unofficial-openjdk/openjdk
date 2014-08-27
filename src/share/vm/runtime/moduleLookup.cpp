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
#include "classfile/javaClasses.hpp"
#include "oops/oop.inline.hpp"
#include "oops/instanceKlass.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/module.hpp"
#include "runtime/moduleLookup.hpp"

ModuleLookup* ModuleLookup::_the_null_class_loader_module_lookup = NULL;

// returns the ModuleLookup for the given loader or null if none
ModuleLookup* ModuleLookup::module_lookup_or_null(Handle loader) {
  if (loader.is_null()) {
    return _the_null_class_loader_module_lookup;
  } else {
    return java_lang_ClassLoader::module_lookup(loader());
  }
}

// returns the ModuleLookup for the given loader, creating if needed
ModuleLookup* ModuleLookup::module_lookup_for(Handle loader) {
  if (loader.is_null()) {
    if (_the_null_class_loader_module_lookup == NULL)
      _the_null_class_loader_module_lookup = new ModuleLookup(_initial_table_size);
    return _the_null_class_loader_module_lookup;
  }

  ModuleLookup** module_lookup_addr = java_lang_ClassLoader::module_lookup_addr(loader());
  ModuleLookup* module_lookup = new ModuleLookup(_initial_table_size);
  ModuleLookup* prev = (ModuleLookup*) Atomic::cmpxchg_ptr(module_lookup, module_lookup_addr, NULL);
  if (prev != NULL) {
    delete module_lookup;
    module_lookup = prev;
  }
  return module_lookup;
}

// used to lazily associate a loader/package to a module
void ModuleLookup::bind_to_module(Handle loader, const char* pkg, Module* module) {
  ModuleLookup* lookup = module_lookup_for(loader);

  unsigned int hash = compute_hash(pkg);
  int index = lookup->hash_to_index(hash);

  PackageEntry* first = lookup->first_at(index);
  PackageEntry* entry = first;
  while (entry != NULL) {
    if (entry->hash() == hash && strcmp(entry->package(), pkg) == 0) {
      break;
    }
    entry = entry->next();
  }
  if (entry == NULL) {
    entry = new PackageEntry(pkg, module, hash);
    entry->set_next(first);
    lookup->set_first(index, entry);
  }
}

// return the module for the given package (NULL if not found)
Module* ModuleLookup::lookup(const char* pkg) {
  unsigned int hash = compute_hash(pkg);
  int index = hash_to_index(hash);

  PackageEntry* first = first_at(index);
  PackageEntry* entry = first;
  while (entry != NULL) {
    if (entry->hash() == hash && strcmp(entry->package(), pkg) == 0) {
      break;
    }
    entry = entry->next();
  }

  if (entry == NULL) {
    return NULL;
  } else {
    return entry->module();
  }
}
