/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_VM_CLASSFILE_CLASSLOADEREXPORTS_HPP
#define SHARE_VM_CLASSFILE_CLASSLOADEREXPORTS_HPP

#include "memory/allocation.hpp"
#include "memory/allocation.inline.hpp"
#include "runtime/handles.hpp"

// Experimental support for access control. Many limitations in this
// version (including performance, locking, class laoder unloading).

// An entry in a list of loaders/packages that are allowed access

class ClassLoaderAllowEntry : public CHeapObj<mtInternal> {
 private:
  int _loader_tag;
  char* _pkg;
  unsigned int _hash;
  ClassLoaderAllowEntry* _next;
 public:
  ClassLoaderAllowEntry(int loader_tag, const char* pkg, int hash) {
    _loader_tag = loader_tag;
    _pkg = strdup(pkg);
    _hash = hash;
    _next = NULL;
  }
  int loader_tag()                             { return _loader_tag; }
  const char* package()                        { return _pkg; }
  unsigned int hash()                          { return _hash; }

  ClassLoaderAllowEntry* next()               { return _next; }
  void set_next(ClassLoaderAllowEntry* entry) { _next = entry; }
};

// Represents the export of package to a list of loaders/packages that are
// allowed access.

class ClassLoaderExportEntry : public CHeapObj<mtInternal> {
 private:
  unsigned int _hash;
  char* _pkg;
  ClassLoaderAllowEntry* _allows;
  ClassLoaderExportEntry* _next;
 public:
  ClassLoaderExportEntry(unsigned int h, const char* p) {
    _hash = h;
    _pkg = strdup(p);
    _allows = NULL;
    _next = NULL;
  }
  unsigned int hash()       { return _hash; }
  const char* package()     { return _pkg; }

  ClassLoaderExportEntry* next()              { return _next; }
  void set_next(ClassLoaderExportEntry* next) { _next = next; }

  void add_allow(int loader_tag, const char* pkg, unsigned int hash) {
    ClassLoaderAllowEntry* entry = _allows;
    ClassLoaderAllowEntry* last = NULL;
    while (entry != NULL) {
      if (hash == entry->hash() && loader_tag == entry->loader_tag() &&
         (strcmp(pkg, entry->package()) == 0))
        break;
      last = entry;
      entry = entry->next();
    }
    if (entry == NULL) {
      entry = new ClassLoaderAllowEntry(loader_tag, pkg, hash);
      if (last != NULL) {
        last->set_next(entry);
      } else {
        entry->set_next(_allows);
        _allows = entry;
      }
    }
  }

  bool can_access(int loader_tag, const char* pkg, unsigned int hash) {
    ClassLoaderAllowEntry* entry = _allows;
    while (entry != NULL) {
      if (hash == entry->hash() && loader_tag == entry->loader_tag() &&
         (strcmp(pkg, entry->package()) == 0))
        return true;
      entry = entry->next();
    }
    return false;
  }

  unsigned int allows_count() {
    unsigned int n = 0;
    ClassLoaderAllowEntry* entry = _allows;
    while (entry != NULL) {
      n++;
      entry = entry->next();
    }
    return n;
  }
};


// There is a ClassLoaderExports per class loader that has enabled package access.
// ClassLoaderExports are "kept alive" via an injected field in ClassLoader. To
// avoid refernces between loaders then each loader is given a unique tag,
// generated when its ClassLoaderExports is created.

class ClassLoaderExports : public CHeapObj<mtInternal> {
 private:
  enum Constants {
    _exports_table_size = 1009
  };

  // special for the null loader
  static ClassLoaderExports * _the_null_class_loader_exports;

  // used to compute the per-loader tag
  static int _next_loader_tag;

  // returns the unique tag for the given loader, creating if if needed
  static jint tag_for(Handle loader);

  // returns the ClassLoaderExports for the given loader or null if none
  static ClassLoaderExports* exports_for_or_null(Handle loader);

  // returns the ClassLoaderExports for the given loader, creating if if needed
  static ClassLoaderExports* exports_for(Handle loader);

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

  // hash table and size
  // ## change this class to use Hashtable template
  ClassLoaderExportEntry** _table;
  int _table_size;

  // create with a hash table of the given size
  ClassLoaderExports(int table_size) {
    _table_size = table_size;
    _table = (ClassLoaderExportEntry**) NEW_C_HEAP_ARRAY(ClassLoaderExportEntry*, _table_size, mtInternal);
    for (int i = 0; i < _table_size; i++) {
      _table[i] = NULL;
    }
  }

  // simple mapping of hash to entry in hash table
  int hash_to_index(unsigned int hash) {
    return hash % _table_size;
  }

  // return the first entry at the given index
  ClassLoaderExportEntry* first_at(int index) {
    assert(index >= 0 && index < _table_size, "index out of range");
    return _table[index];
  }

  // set the first entry at the given index
  void set_first(int index, ClassLoaderExportEntry* entry) {
    assert(index >= 0 && index < _table_size, "index out of range");
    _table[index] = entry;
  }

  // search the table for the given package, returns NULL if not found
  ClassLoaderExportEntry* find_entry(const char* pkg) {
    unsigned int hash = compute_hash(pkg);
    int index = hash_to_index(hash);
    assert(index >= 0 && index < _table_size, "index out of range");

    ClassLoaderExportEntry* entry = _table[index];
    while (entry != NULL) {
      if (entry->hash() == hash && strcmp(entry->package(), pkg) == 0) {
        break;
      }
      entry = entry->next();
    }
    return entry;
  }

  // print hash table stats
  void print_stats();

  // set or augment access control
  static bool set_package_access_impl(Handle loader, const char* pkg,
                                     objArrayHandle loaders, const char** pkgs,
                                     bool adding);

 public:

  // Set access control so that types defined by loader/pkg are accessible
  // only to the given runtime packages. Returns false if access control
  // is already set for the loader/package.
  static bool set_package_access(Handle loader, const char* pkg,
                                 objArrayHandle loaders, const char** pkgs);

  // Augment access control so that the types defined by loader/pkg are accessible
  // to the given runtime packages. Returns true if access control has been
  // updated.
  static bool add_package_access(Handle loader, const char* pkg,
                                 objArrayHandle loaders, const char** pkgs);

  // Verify that current_class can access new_class.
  static bool verify_package_access(Klass* current_class, Klass* new_class);
};

#endif // SHARE_VM_CLASSFILE_CLASSLOADEREXPORTS_HPP
