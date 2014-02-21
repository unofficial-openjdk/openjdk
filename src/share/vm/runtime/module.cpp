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
#include "oops/klass.hpp"
#include "runtime/module.hpp"
#include "runtime/moduleLookup.hpp"
#include "memory/allocation.inline.hpp"

// creates a new module with the given name
Module* Module::define_module(const char* name) {
  Module* module = new Module(name);
  return module;
}

// returns the module for the given Klass (or NULL)
Module* Module::module_for(Klass* k) {
  ModuleLookup* lookup = ModuleLookup::module_lookup_or_null(k->class_loader());
  if (lookup == NULL)
    return NULL;

  // ## FIXME encoding the external name is expensive in this prototype
  ResourceMark rm;
  char* name = (char*) k->external_name();
  char* last = strrchr(name, '.');
  if (last != NULL) {
    *last = '\0';
  }
  const char* pkg = (last == NULL) ? "" : name;

  return lookup->lookup(pkg);
}

// setup this module so that it requires other
void Module::add_requires(Module* other) {
  _requires->append(other);
}

// returns true if this module requires other
bool Module::requires(Module* other) {
  for (int i=0; i<_requires->length(); i++) {
    if (_requires->at(i) == other) {
      return true;
    }
  }
  return false;
}

// exports package (no permits)
void Module::export_without_permits(const char* pkg) {
  export_with_permits(pkg, NULL);
}

// exports package to other
void Module::export_with_permits(const char* pkg, Module* other) {
  unsigned int hash = compute_hash(pkg);
  int index = hash_to_index(hash);

  PackageExport* first = first_at(index);
  PackageExport* entry = first;
  while (entry != NULL) {
    if (entry->hash() == hash && strcmp(entry->package(), pkg) == 0) {
      break;
    }
    entry = entry->next();
  }
  if (entry == NULL) {
    entry = new PackageExport(pkg, hash);
    entry->set_next(first);
    set_first(index, entry);
  }
  if (other != NULL) {
    entry->add_module_permits(other);
  }
}

// Returns true if this module exports the given package without permits
bool Module::is_exported_without_permits(const char* pkg) {
  unsigned int hash = compute_hash(pkg);
  int index = hash_to_index(hash);
  PackageExport* first = first_at(index);
  PackageExport* entry = first;
  while (entry != NULL) {
    if (entry->hash() == hash && strcmp(entry->package(), pkg) == 0) {
      if (entry->has_module_permits()) {
        return false;
      } else {
        return true;
      }
    }
    entry = entry->next();
  }
  return false;
}

// Returns true if this module exports the given package to other
bool Module::is_exported_to_module(const char* pkg, Module* other) {
  unsigned int hash = compute_hash(pkg);
  int index = hash_to_index(hash);
  PackageExport* first = first_at(index);
  PackageExport* entry = first;
  while (entry != NULL) {
    if (entry->hash() == hash && strcmp(entry->package(), pkg) == 0) {
      return entry->is_permitted(other);
    }
    entry = entry->next();
  }
  return false;
}

// grant access to classes generated in the unnamed module at runtime
void Module::add_backdoor_access(const char* pkg, int loader_tag, const char* who) {
  unsigned int hash = compute_hash(pkg);
  int index = hash_to_index(hash);
  PackageExport* first = first_at(index);
  PackageExport* entry = first;
  while (entry != NULL) {
    if (entry->hash() == hash && strcmp(entry->package(), pkg) == 0) {
      break;
    }
    entry = entry->next();
  }
  if (entry == NULL) {
    entry = new PackageExport(pkg, hash);
    entry->set_next(first);
    set_first(index, entry);
  }
  entry->add_package_permits(loader_tag, who);
}

// does loader/who have access to pkg
bool Module::has_backdoor_access(const char* pkg, int loader_tag, const char* who) {
  unsigned int hash = compute_hash(pkg);
  int index = hash_to_index(hash);
  PackageExport* first = first_at(index);
  PackageExport* entry = first;
  while (entry != NULL) {
    if (entry->hash() == hash && strcmp(entry->package(), pkg) == 0) {
      return entry->is_permitted(loader_tag, who);
    }
    entry = entry->next();
  }
  return false;
}
