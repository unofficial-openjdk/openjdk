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

#ifndef SHARE_VM_GC_IMPLEMENTATION_SHARED_GCHEAPSUMMARY_HPP
#define SHARE_VM_GC_IMPLEMENTATION_SHARED_GCHEAPSUMMARY_HPP

#include "memory/allocation.hpp"

class VirtualSpaceSummary : public StackObj {
  HeapWord* _start;
  HeapWord* _committed_end;
  HeapWord* _reserved_end;
public:
  VirtualSpaceSummary(HeapWord* start, HeapWord* committed_end, HeapWord* reserved_end) :
      _start(start), _committed_end(committed_end), _reserved_end(reserved_end) { }

  HeapWord* start() const { return _start; }
  HeapWord* committed_end() const { return _committed_end; }
  HeapWord* reserved_end() const { return _reserved_end; }
  size_t committed_size() const { return (uintptr_t)_committed_end - (uintptr_t)_start;  }
  size_t reserved_size() const { return (uintptr_t)_reserved_end - (uintptr_t)_start; }
};

class SpaceSummary : public StackObj {
  HeapWord* _start;
  HeapWord* _end;
  size_t    _used;
public:
  SpaceSummary(HeapWord* start, HeapWord* end, size_t used) :
      _start(start), _end(end), _used(used) { }

  HeapWord* start() const { return _start; }
  HeapWord* end() const { return _end; }
  size_t used() const { return _used; }
  size_t size() const { return (uintptr_t)_end - (uintptr_t)_start; }
};

class GCHeapSummary;
class PSHeapSummary;

class GCHeapSummaryVisitor {
 public:
  virtual void visit(const GCHeapSummary* heap_summary) const = 0;
  virtual void visit(const PSHeapSummary* heap_summary) const {}
};

class GCHeapSummary : public StackObj {
  VirtualSpaceSummary _heap;
  size_t _used;

 public:
   GCHeapSummary(VirtualSpaceSummary& heap_space, size_t used) :
       _heap(heap_space), _used(used) { }

  const VirtualSpaceSummary& heap() const { return _heap; }
  size_t used() const { return _used; }

   virtual void accept(GCHeapSummaryVisitor* visitor) const {
     visitor->visit(this);
   }
};

class PSHeapSummary : public GCHeapSummary {
  VirtualSpaceSummary  _old;
  SpaceSummary         _old_space;
  VirtualSpaceSummary  _young;
  SpaceSummary         _eden;
  SpaceSummary         _from;
  SpaceSummary         _to;
 public:
   PSHeapSummary(VirtualSpaceSummary& heap_space, size_t heap_used, VirtualSpaceSummary old, SpaceSummary old_space, VirtualSpaceSummary young, SpaceSummary eden, SpaceSummary from, SpaceSummary to) :
       GCHeapSummary(heap_space, heap_used), _old(old), _old_space(old_space), _young(young), _eden(eden), _from(from), _to(to) { }
   const VirtualSpaceSummary& old() const { return _old; }
   const SpaceSummary& old_space() const { return _old_space; }
   const VirtualSpaceSummary& young() const { return _young; }
   const SpaceSummary& eden() const { return _eden; }
   const SpaceSummary& from() const { return _from; }
   const SpaceSummary& to() const { return _to; }

   virtual void accept(GCHeapSummaryVisitor* visitor) const {
     visitor->visit(this);
   }
};

class PermGenSummary : public StackObj {
  VirtualSpaceSummary _perm_space;
  SpaceSummary        _object_space;

 public:
  PermGenSummary(const VirtualSpaceSummary& perm_space, const SpaceSummary& object_space) :
       _perm_space(perm_space), _object_space(object_space) { }

  const VirtualSpaceSummary& perm_space() const { return _perm_space; }
  const SpaceSummary& object_space() const { return _object_space; }
};

#endif // SHARE_VM_GC_IMPLEMENTATION_SHARED_GCHEAPSUMMARY_HPP
