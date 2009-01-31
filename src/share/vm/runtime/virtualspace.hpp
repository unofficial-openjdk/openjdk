#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "%W% %E% %U% JVM"
#endif
/*
 * Copyright 1997-2005 Sun Microsystems, Inc.  All Rights Reserved.
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

// ReservedSpace is a data structure for reserving a contiguous address range.

class ReservedSpace VALUE_OBJ_CLASS_SPEC {
  friend class VMStructs;
 private:
  char*  _base;
  size_t _size;
  bool   _special;

  // ReservedSpace
  ReservedSpace(char* base, size_t size, bool large);
  void initialize(size_t size, size_t forced_base_alignment,
                  bool large,  char* requested_address = NULL);

 public:
  // Constructor
  ReservedSpace(size_t size);
  ReservedSpace(size_t size, size_t forced_base_alignment,
                bool large, char* requested_address = NULL);

  // Accessors
  char*  base()   { return _base;   }
  size_t size()   { return _size;   }
  bool   special(){ return _special;}

  bool is_reserved() { return _base != NULL; }
  void release();

  // Splitting
  ReservedSpace first_part(size_t partition_size,
                           bool split = false, bool realloc = true);
  ReservedSpace last_part (size_t partition_size);

  // Alignment
  static size_t page_align_size_up(size_t size);
  static size_t page_align_size_down(size_t size);
  static size_t allocation_align_size_up(size_t size);
  static size_t allocation_align_size_down(size_t size);
};


// VirtualSpace is data structure for committing a previously reserved address range in smaller chunks.

class VirtualSpace VALUE_OBJ_CLASS_SPEC {
  friend class VMStructs;
 private:
  // Reserved area
  char* _low_boundary;
  char* _high_boundary;

  // Committed area
  char* _low;
  char* _high;

  // The entire space has been committed and pinned in memory, no
  // os::commit_memory() or os::uncommit_memory().
  bool _special;

  // MPSS Support
  // Each virtualspace region has a lower, middle, and upper region.
  // Each region has an end boundary and a high pointer which is the 
  // high water mark for the last allocated byte.
  // The lower and upper unaligned to LargePageSizeInBytes uses default page.
  // size.  The middle region uses large page size.
  char* _lower_high;  
  char* _middle_high;
  char* _upper_high;

  char* _lower_high_boundary;
  char* _middle_high_boundary;
  char* _upper_high_boundary;

  size_t _lower_alignment;
  size_t _middle_alignment;
  size_t _upper_alignment;

  // MPSS Accessors
  char* lower_high() const { return _lower_high; }
  char* middle_high() const { return _middle_high; }
  char* upper_high() const { return _upper_high; }

  char* lower_high_boundary() const { return _lower_high_boundary; }
  char* middle_high_boundary() const { return _middle_high_boundary; }
  char* upper_high_boundary() const { return _upper_high_boundary; }
  
  size_t lower_alignment() const { return _lower_alignment; }
  size_t middle_alignment() const { return _middle_alignment; }
  size_t upper_alignment() const { return _upper_alignment; }

 public:
  // Committed area
  char* low()  const { return _low; }
  char* high() const { return _high; }

  // Reserved area
  char* low_boundary()  const { return _low_boundary; }
  char* high_boundary() const { return _high_boundary; }

  bool special() const { return _special; }

 public:
  // Initialization
  VirtualSpace();
  bool initialize(ReservedSpace rs, size_t committed_byte_size);
  
  // Destruction
  ~VirtualSpace();

  // Testers (all sizes are byte sizes)
  size_t committed_size()   const;
  size_t reserved_size()    const;
  size_t uncommitted_size() const;
  bool   contains(const void* p)  const;

  // Operations
  // returns true on success, false otherwise
  bool expand_by(size_t bytes, bool pre_touch = false);
  void shrink_by(size_t bytes);
  void release();

  void check_for_contiguity() PRODUCT_RETURN;

  // Debugging
  void print() PRODUCT_RETURN;
};
