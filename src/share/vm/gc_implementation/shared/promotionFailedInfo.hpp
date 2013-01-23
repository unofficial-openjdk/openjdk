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

#ifndef SHARE_VM_GC_IMPLEMENTATION_SHARED_PROMOTIONFAILEDINFO_HPP
#define SHARE_VM_GC_IMPLEMENTATION_SHARED_PROMOTIONFAILEDINFO_HPP

#include "utilities/globalDefinitions.hpp"

class PromotionFailedInfo VALUE_OBJ_CLASS_SPEC {
  uint   _promotion_failed_count;
  size_t _promotion_failed_size;
 public:
  PromotionFailedInfo() : _promotion_failed_count(0), _promotion_failed_size(0) {}

  void register_promotion_failed(size_t size) {
    _promotion_failed_size += size;
    _promotion_failed_count++;
  }

  void set_promotion_failed(size_t size, uint count) {
    _promotion_failed_size = size;
    _promotion_failed_count = count;
  }

  void reset() {
    _promotion_failed_size = 0;
    _promotion_failed_count = 0;
  }

  bool promotion_failed() const { return _promotion_failed_size > 0; }
  size_t promotion_failed_size() const { return _promotion_failed_size; }
  uint promotion_failed_count() const { return _promotion_failed_count; }
};


#endif /* SHARE_VM_GC_IMPLEMENTATION_SHARED_PROMOTIONFAILEDINFO_HPP */

