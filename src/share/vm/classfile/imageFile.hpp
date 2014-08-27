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

#ifndef SHARE_VM_CLASSFILE_IMAGEFILE_HPP
#define SHARE_VM_CLASSFILE_IMAGEFILE_HPP

#include "memory/allocation.hpp"
#include "memory/allocation.inline.hpp"
#include "utilities/globalDefinitions.hpp"

class Mutex;

// Manage image file index string table.  Each string is UTF-8 with zero byte terminator.
class ImageStrings {
private:
  // Data bytes for strings.
  u1* _data;
  // Maximum offset of strings.
  u4 _size;

public:
  // Prime used to generate hash for Perfect Hashing.
  static const u4 HASH_MULTIPLIER = 0x01000193;

  ImageStrings(u1* data, u4 size) : _data(data), _size(size) {}

  // Return the UTF-8 string beginning at offset.
  inline const char* get(u4 offset) const {
    assert(offset < _size, "offset exceeds string table size");
    return (const char*)(_data + offset);
  }

  // Compute the Perfect Hashing hash code for the supplied string.
  inline static u4 hashCode(const char* string) {
    return hashCode(string, HASH_MULTIPLIER);
  }

  // Compute the Perfect Hashing hash code for the supplied string, resuming at base.
  static u4 hashCode(const char* string, u4 base);

  // Test to see if string begins with start.  If so returns remaining portion
  // of string.  Otherwise, NULL.
  static const char* startsWith(const char* string, const char* start);

};

// Manage image file location attribute streams.  A location's attributes are compressed into
// a stream of bytes.  Each attribute sequence begins with a header byte containing the
// attribute kind and the number of bytes that following containing the attribute value.  An
// attribute's stream is terminated with a header byte of zero.  ImageLocation inflates the
// stream into individual values.  Unspecified values have value zero.
class ImageLocation {
public:
  // Attibute kind enumeration.
  static const u1 ATTRIBUTE_END = 0;      // End of attributes marker
  static const u1 ATTRIBUTE_BASE = 1;     // String table offset of resource path base
  static const u1 ATTRIBUTE_PARENT = 2;   // String table offset of resource path parent
  static const u1 ATTRIBUTE_EXTENSION = 3;  // String table offset of resource path extension
  static const u1 ATTRIBUTE_OFFSET = 4;   // Container byte offset of resource
  static const u1 ATTRIBUTE_COMPRESSED = 5; // Container byte size of compressed resource
  static const u1 ATTRIBUTE_UNCOMPRESSED = 6; // Container byte offset of uncompressed resource
  static const u1 ATTRIBUTE_COUNT = 7;    // Number of attribute kinds

private:
  // Values of inflated attributes.
  u8 _attributes[ATTRIBUTE_COUNT];

  // Return the attribute value number of bytes.
  inline static u1 attributeLength(u1 data) {
    return (data & 0x7) + 1;
  }

  // Return the attribute kind.
  inline static u1 attributeKind(u1 data) {
    u1 kind = data >> 3;
    assert(kind < ATTRIBUTE_COUNT, "invalid attribute kind");
    return kind;
  }

  // Return the attribute length.
  inline static u8 attributeValue(u1* data, u1 n) {
    assert(0 < n && n <= 8, "invalid attribute value length");
    u8 value = 0;

    // Most significant bytes first.
    for (u1 i = 0; i < n; i++) {
      value <<= 8;
      value |= data[i];
    }

    return value;
  }

public:
  ImageLocation(u1* data);

  // Retrieve an attribute value from the inflated array.
  inline u8 getAttribute(u1 kind) const {
    assert(ATTRIBUTE_END < kind && kind < ATTRIBUTE_COUNT, "invalid attribute kind");
    return _attributes[kind];
  }

  // Retrieve an attribute string value from the inflated array.
  inline const char* getAttribute(u4 kind, const ImageStrings& strings) const {
    return strings.get((u4)getAttribute(kind));
  }
};

class ImageFile: public CHeapObj<mtClass> {
private:
  // Image file marker.
  static const u4 IMAGE_MAGIC = 0xCAFEDADA;
  // Image file major version number.
  static const u2 MAJOR_VERSION = 0;
  // Image file minor version number.
  static const u2 MINOR_VERSION = 1;

  struct ImageHeader {
    u4 _magic;         // Image file marker
    u2 _majorVersion;  // Iamge file major vestion number
    u2 _minorVersion;  // Iamge file minor vestion number
    u4 _locationCount; // Number of locations managed in index.
    u4 _locationsSize; // Number of bytes in attribute table.
    u4 _stringsSize;   // Number of bytes in string table.
  };

  char* _name;         // Name of image
  int _fd;             // File descriptor
  Mutex* _readLock;    // Needed for atomic seek and read
  bool _memoryMapped;  // Is file memory mapped
  ImageHeader _header; // Image header
  u8 _indexSize;       // Total size of index
  u1* _indexData;      // Raw index data
  s4* _redirectTable;  // Perfect hash redirect table
  u4* _offsetsTable;   // Location offset table
  u1* _locationBytes;  // Location attributes
  u1* _stringBytes;    // String table

  // Compute number of bytes in image file index.
  inline u8 indexSize() {
    return sizeof(ImageHeader) +
    _header._locationCount * sizeof(u4) * 2 +
    _header._locationsSize +
    _header._stringsSize;
  }

public:
  ImageFile(const char* name);
  ~ImageFile();

  // Open image file for access.
  bool open();
  // Close image file.
  void close();

  // Retrieve name of image file.
  inline const char* name() const {
    return _name;
  }

  // Return number of locations in image file index.
  inline u4 getLocationCount() const {
    return _header._locationCount;
  }

  // Return location attribute stream for location i.
  inline u1* getLocationData(u4 i) const {
    return _locationBytes + _offsetsTable[i];
  }

  // Return the attribute stream for a named resourced.
  u1* findLocationData(const char* name) const;

  // Verify that a found location matches the supplied path name.
  bool verifyLocation(ImageLocation& location, const char* name) const;

  // Return the reource for the supplied location info.
  u1* getResource(ImageLocation& location) const;
};

#endif // SHARE_VM_CLASSFILE_IMAGEFILE_HPP
