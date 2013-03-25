/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_VM_GC_IMPLEMENTATION_SHARED_COPYFAILEDINFO_HPP
#define SHARE_VM_GC_IMPLEMENTATION_SHARED_COPYFAILEDINFO_HPP

#include "runtime/thread.hpp"
#include "utilities/globalDefinitions.hpp"

class CopyFailedInfo VALUE_OBJ_CLASS_SPEC {
  size_t    _first_size;
  size_t    _smallest_size;
  size_t    _total_size;
  uint      _count;
  OSThread* _thread;

 public:
  CopyFailedInfo() : _first_size(0), _smallest_size(0), _total_size(0), _count(0), _thread(NULL) {}

  void register_copy_failure(size_t size) {
    if (_first_size == 0) {
      _first_size = size;
      _smallest_size = size;
      _thread = Thread::current()->osthread();
    } else if (size < _smallest_size) {
      _smallest_size = size;
    }
    _total_size += size;
    _count++;
    assert(_thread == Thread::current()->osthread(), "The PromotionFailedInfo should be thread local.");
  }

  void reset() {
    _first_size = 0;
    _smallest_size = 0;
    _total_size = 0;
    _count = 0;
    _thread = NULL;
  }

  bool has_failed() const { return _count != 0; }
  size_t first_size() const { return _first_size; }
  size_t smallest_size() const { return _smallest_size; }
  size_t total_size() const { return _total_size; }
  uint failed_count() const { return _count; }
  OSThread* thread() const { return _thread; }
};

class PromotionFailedInfo : public CopyFailedInfo {};

#endif /* SHARE_VM_GC_IMPLEMENTATION_SHARED_COPYFAILEDINFO_HPP */
