#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)parallelScavengeHeap.hpp	1.61 07/05/17 15:52:51 JVM"
#endif
/*
 * Copyright 2001-2007 Sun Microsystems, Inc.  All Rights Reserved.
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
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 *  
 */

class AdjoiningGenerations;
class GCTaskManager;
class PSAdaptiveSizePolicy;

class ParallelScavengeHeap : public CollectedHeap {
  friend class VMStructs;
 private:
  static PSYoungGen* _young_gen;
  static PSOldGen*   _old_gen;
  static PSPermGen*  _perm_gen;

  // Sizing policy for entire heap
  static PSAdaptiveSizePolicy* _size_policy;
  static PSGCAdaptivePolicyCounters*   _gc_policy_counters;

  static ParallelScavengeHeap* _psh;

  // Byte size of the reserved space for the heap
  size_t _reserved_byte_size;

  size_t _generation_alignment;
  inline void set_generation_alignment(size_t val);

  // Adjust alignment for page size (may be large page size)
  void adjust_generation_alignment_for_page_size(size_t page_size);

  // Collection of generations that are adjacent in the
  // space reserved for the heap.
  AdjoiningGenerations* _gens;

  static GCTaskManager*          _gc_task_manager;      // The task manager.

  // Private accessors
  size_t reserved_byte_size() const { return _reserved_byte_size; }

 protected:
  static inline size_t total_invocations();
  HeapWord* allocate_new_tlab(size_t size);
  void fill_all_tlabs(bool retire);

 public:
  ParallelScavengeHeap() : CollectedHeap() {
    set_generation_alignment(intra_generation_alignment());
  }

  // For use by VM operations
  enum CollectionType {
    Scavenge,
    MarkSweep
  };

  ParallelScavengeHeap::Name kind() const {
    return CollectedHeap::ParallelScavengeHeap;
  }

  static PSYoungGen* young_gen()     { return _young_gen; }
  static PSOldGen* old_gen()         { return _old_gen; }
  static PSPermGen* perm_gen()       { return _perm_gen; }

  virtual PSAdaptiveSizePolicy* size_policy() { return _size_policy; }

  static PSGCAdaptivePolicyCounters* gc_policy_counters() { return _gc_policy_counters; }

  static ParallelScavengeHeap* heap();

  static GCTaskManager* const gc_task_manager() { return _gc_task_manager; }

  AdjoiningGenerations* gens() { return _gens; }

  // Returns JNI_OK on success
  virtual jint initialize();

  void post_initialize();
  void update_counters();

  // The alignment used for generations.
  size_t generation_alignment() const { return _generation_alignment; }

  // The alignment used for eden and survivors within the young gen.
  size_t intra_generation_alignment() const { return 64 * K; }

  size_t capacity() const;
  size_t used() const;

  // Return "true" if all generations (but perm) have reached the
  // maximal committed limit that they can reach, without a garbage
  // collection.
  virtual bool is_maximal_no_gc() const;

  // Does this heap support heap inspection? (+PrintClassHistogram)
  bool supports_heap_inspection() const { return true; }

  size_t permanent_capacity() const;
  size_t permanent_used() const;

  size_t max_capacity() const;

  // Whether p is in the allocated part of the heap
  bool is_in(const void* p) const;

  bool is_in_reserved(const void* p) const;
  bool is_in_permanent(const void *p) const {    // reserved part
    return perm_gen()->reserved().contains(p);
  }

  bool is_permanent(const void *p) const {    // committed part
    return perm_gen()->is_in(p);
  }

  static bool is_in_young(oop *p);        // reserved part
  static bool is_in_old_or_perm(oop *p);  // reserved part

  // Memory allocation.   "gc_time_limit_was_exceeded" will
  // be set to true if the adaptive size policy determine that
  // an excessive amount of time is being spent doing collections
  // and caused a NULL to be returned.  If a NULL is not returned,
  // "gc_time_limit_was_exceeded" has an undefined meaning.

  HeapWord* mem_allocate(size_t size, 
			 bool is_noref, 
			 bool is_tlab,
			 bool* gc_overhead_limit_was_exceeded);
  HeapWord* failed_mem_allocate(size_t size, bool is_tlab);

  HeapWord* permanent_mem_allocate(size_t size);
  HeapWord* failed_permanent_mem_allocate(size_t size);

  // Support for System.gc()
  void collect(GCCause::Cause cause);

  // This interface assumes that it's being called by the
  // vm thread. It collects the heap assuming that the
  // heap lock is already held and that we are executing in
  // the context of the vm thread.
  void collect_as_vm_thread(GCCause::Cause cause);

  // These also should be called by the vm thread at a safepoint (e.g., from a
  // VM operation).
  // 
  // The first collects the young generation only, unless the scavenge fails; it
  // will then attempt a full gc.  The second collects the entire heap; if
  // maximum_compaction is true, it will compact everything and clear all soft
  // references.
  inline void invoke_scavenge();
  inline void invoke_full_gc(bool maximum_compaction);

  size_t large_typearray_limit() { return FastAllocateSizeLimit; }

  bool supports_inline_contig_alloc() const { return !UseNUMA; }
  HeapWord** top_addr() const { return !UseNUMA ? young_gen()->top_addr() : NULL; }
  HeapWord** end_addr() const { return !UseNUMA ? young_gen()->end_addr() : NULL; }

  void ensure_parsability(bool retire_tlabs);
  void accumulate_statistics_all_tlabs();
  void resize_all_tlabs();

  size_t unsafe_max_alloc();

  bool supports_tlab_allocation() const { return true; }

  size_t tlab_capacity(Thread* thr) const;
  size_t unsafe_max_tlab_alloc(Thread* thr) const;

  void oop_iterate(OopClosure* cl);
  void object_iterate(ObjectClosure* cl);
  void permanent_oop_iterate(OopClosure* cl);
  void permanent_object_iterate(ObjectClosure* cl);

  HeapWord* block_start(const void* addr) const;
  size_t block_size(const HeapWord* addr) const;
  bool block_is_obj(const HeapWord* addr) const;

  jlong millis_since_last_gc();

  void prepare_for_verify();
  void print() const;
  void print_on(outputStream* st) const;
  virtual void print_gc_threads_on(outputStream* st) const;
  virtual void gc_threads_do(ThreadClosure* tc) const;
  virtual void print_tracing_info() const;

  void verify(bool allow_dirty, bool silent);

  void print_heap_change(size_t prev_used);

  // Resize the young generation.  The reserved space for the
  // generation may be expanded in preparation for the resize.
  void resize_young_gen(size_t eden_size, size_t survivor_size);

  // Resize the old generation.  The reserved space for the
  // generation may be expanded in preparation for the resize.
  void resize_old_gen(size_t desired_free_space);
};

inline void ParallelScavengeHeap::set_generation_alignment(size_t val) {
  assert(align_size_up_(val, os::vm_page_size()) == val, "not aligned");
  assert(val >= intra_generation_alignment(), "alignment size is too small");
  _generation_alignment = val;
}
