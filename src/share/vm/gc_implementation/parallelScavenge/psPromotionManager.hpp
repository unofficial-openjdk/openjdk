#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)psPromotionManager.hpp	1.19 07/05/05 17:05:30 JVM"
#endif
/*
 * Copyright 2002-2007 Sun Microsystems, Inc.  All Rights Reserved.
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

//
// psPromotionManager is used by a single thread to manage object survival
// during a scavenge. The promotion manager contains thread local data only.
//
// NOTE! Be carefull when allocating the stacks on cheap. If you are going
// to use a promotion manager in more than one thread, the stacks MUST be
// on cheap. This can lead to memory leaks, though, as they are not auto
// deallocated.
//
// FIX ME FIX ME Add a destructor, and don't rely on the user to drain/flush/deallocate!
//

// Move to some global location
#define HAS_BEEN_MOVED 0x1501d01d
// End move to some global location


class MutableSpace;
class PSOldGen;
class ParCompactionManager;

class PSPromotionManager : public CHeapObj {
  friend class PSScavenge;
  friend class PSRefProcTaskExecutor;
 private:
  static PSPromotionManager**  _manager_array;
  static OopStarTaskQueueSet*  _stack_array_depth;
  static OopTaskQueueSet*      _stack_array_breadth;
  static PSOldGen*             _old_gen;
  static MutableSpace*         _young_space;
  
  PSYoungPromotionLAB          _young_lab;
  PSOldPromotionLAB            _old_lab;
  bool                         _young_gen_is_full;
  bool                         _old_gen_is_full;
  PrefetchQueue                _prefetch_queue;

  OopStarTaskQueue             _claimed_stack_depth;
  GrowableArray<oop*>*         _overflow_stack_depth;
  OopTaskQueue                 _claimed_stack_breadth;
  GrowableArray<oop>*          _overflow_stack_breadth;

  bool                         _depth_first;
  bool                         _totally_drain;
  uint                         _target_stack_size;

  // Accessors
  static PSOldGen* old_gen()              { return _old_gen; }
  static MutableSpace* young_space()      { return _young_space; }

  inline static PSPromotionManager* manager_array(int index);
  inline void claim_or_forward_internal_depth(oop* p);
  inline void claim_or_forward_internal_breadth(oop* p);

  GrowableArray<oop*>* overflow_stack_depth()  { return _overflow_stack_depth; }
  GrowableArray<oop>* overflow_stack_breadth()   { return _overflow_stack_breadth; }

 protected:
  static OopStarTaskQueueSet* stack_array_depth() { return _stack_array_depth; }
  static OopTaskQueueSet*     stack_array_breadth() { return _stack_array_breadth; }

 public:
  // Static
  static void initialize();

  static void pre_scavenge();
  static void post_scavenge();

  static PSPromotionManager* gc_thread_promotion_manager(int index);
  static PSPromotionManager* vm_thread_promotion_manager();

  static bool steal_depth(int queue_num, int* seed, StarTask& t) {
    assert(stack_array_depth() != NULL, "invariant");
    return stack_array_depth()->steal(queue_num, seed, t);
  }

  static bool steal_breadth(int queue_num, int* seed, Task& t) {
    assert(stack_array_breadth() != NULL, "invariant");
    return stack_array_breadth()->steal(queue_num, seed, t);
  }

  PSPromotionManager();

  // Accessors
  OopStarTaskQueue* claimed_stack_depth() {
    return &_claimed_stack_depth;
  }
  OopTaskQueue* claimed_stack_breadth() {
    return &_claimed_stack_breadth;
  }

  bool young_gen_is_full()             { return _young_gen_is_full; }

  bool old_gen_is_full()               { return _old_gen_is_full; }
  void set_old_gen_is_full(bool state) { _old_gen_is_full = state; }

  // Promotion methods
  oop copy_to_survivor_space(oop o, bool depth_first);
  oop oop_promotion_failed(oop obj, markOop obj_mark);
  void handle_stack_overflow();

  void reset();

  void flush_labs();
  void drain_stacks(bool totally_drain) {
    if (depth_first()) {
      drain_stacks_depth(totally_drain);
    } else {
      drain_stacks_breadth(totally_drain);
    }
  }
  void drain_stacks_depth(bool totally_drain);
  void drain_stacks_breadth(bool totally_drain);

  bool claimed_stack_empty() {
    if (depth_first()) {
      return claimed_stack_depth()->size() <= 0;
    } else {
      return claimed_stack_breadth()->size() <= 0;
    }
  }
  bool overflow_stack_empty() {
    if (depth_first()) {
      return overflow_stack_depth()->length() <= 0;
    } else {
      return overflow_stack_breadth()->length() <= 0;
    }
  }
  bool stacks_empty() {
    return claimed_stack_empty() && overflow_stack_empty();
  }
  bool depth_first() {
    return _depth_first;
  }

  inline void flush_prefetch_queue();
  inline void claim_or_forward_depth(oop* p);
  inline void claim_or_forward_breadth(oop* p);
};
