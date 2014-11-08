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

#include "classfile/classLoader.hpp"
#include "memory/allocation.hpp"
#include "memory/allocation.inline.hpp"
#include "utilities/globalDefinitions.hpp"

// Image files are an alternate file format for storing classes and resources. The
// goal is to supply file access which is faster and smaller that the jar format.
// It should be noted that unlike jars information stored in an image is in native
// endian format. This allows the image to be memory mapped into memory without
// endian translation.  This also means that images are platform dependent.
//
// Image files are structured as three sections;
//
//         +-----------+
//         |  Header   |
//         +-----------+
//         |           |
//         | Directory |
//         |           |
//         +-----------+
//         |           |
//         |           |
//         | Resources |
//         |           |
//         |           |
//         +-----------+
//
// The header contains information related to identification and description of
// contents.
//
//         +-------------------------+
//         |   Magic (0xCAFEDADA)    |
//         +------------+------------+
//         | Major Vers | Minor Vers |
//         +------------+------------+
//         |      Location Count     |
//         +-------------------------+
//         |      Attributes Size    |
//         +-------------------------+
//         |       Strings Size      |
//         +-------------------------+
//
// Magic - means of identifying validity of the file.  This avoids requiring a
//         special file extension.
// Major vers, minor vers - differences in version numbers indicate structural
//                          changes in the image.
// Location count - number of locations/resources in the file.  This count is also
//                  the length of lookup tables used in the directory.
// Attributes size - number of bytes in the region used to store location attribute
//                   streams.
// Strings size - the size of the region used to store strings used by the
//                directory and meta data.
//
// The directory contains information related to resource lookup. The algorithm
// used for lookup is "A Practical Minimal Perfect Hashing Method"
// (http://homepages.dcc.ufmg.br/~nivio/papers/wea05.pdf). Given a path string
// in the form <package>/<base>.<extension>  return the resource location
// information;
//
//     redirectIndex = hash(path, DEFAULT_SEED) % count;
//     redirect = redirectTable[redirectIndex];
//     if (redirect == 0) return not found;
//     locationIndex = redirect < 0 ? -1 - redirect : hash(path, redirect) % count;
//     location = locationTable[locationIndex];
//     if (!verify(location, path)) return not found;
//     return location;
//
// Note: The hash function takes an initial seed value.  A different seed value
// usually returns a different result for strings that would otherwise collide with
// other seeds. The verify function guarantees the found resource location is
// indeed the resource we are looking for.
//
// The following is the format of the directory;
//
//         +-------------------+
//         |   Redirect Table  |
//         +-------------------+
//         | Attribute Offsets |
//         +-------------------+
//         |   Attribute Data  |
//         +-------------------+
//         |      Strings      |
//         +-------------------+
//
// Redirect Table - Array of 32-bit signed values representing actions that
//                  should take place for hashed strings that map to that
//                  value.  Negative values indicate no hash collision and can be
//                  quickly converted to indices into attribute offsets.  Positive
//                  values represent a new seed for hashing an index into attribute
//                  offsets.  Zero indicates not found.
// Attribute Offsets - Array of 32-bit unsigned values representing offsets into
//                     attribute data.  Attribute offsets can be iterated to do a
//                     full survey of resources in the image.
// Attribute Data - Bytes representing compact attribute data for locations. (See
//                  comments in ImageLocation.)
// Strings - Collection of zero terminated UTF-8 strings used by the directory and
//           image meta data.  Each string is accessed by offset.  Each string is
//           unique.  Offset zero is reserved for the empty string.
//
// Note that the memory mapped directory assumes 32 bit alignment of the image
// header, the redirect table and the attribute offsets.
//


// Manage image file string table.
class ImageStrings {
private:
  // Data bytes for strings.
  u1* _data;
  // Number of bytes in the string table.
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

  // Compute the Perfect Hashing hash code for the supplied string, starting at seed.
  static u4 hashCode(const char* string, u4 seed);

  // Test to see if string begins with start.  If so returns remaining portion
  // of string.  Otherwise, NULL.  Used to test sections of a path without
  // copying.
  static const char* startsWith(const char* string, const char* start);

};

// Manage image file location attribute streams.  Within an image, a location's
// attributes are compressed into a stream of bytes.  An attribute stream is
// composed of individual attribute sequences.  Each attribute sequence begins with
// a header byte containing the attribute 'kind' (upper 5 bits of header) and the
// 'length' less 1 (lower 3 bits of header) of bytes that follow containing the
// attribute value.  Attribute values present as most significant byte first.
//
// Ex. Container offset (ATTRIBUTE_OFFSET) 0x33562 would be represented as 0x22
// (kind = 4, length = 3), 0x03, 0x35, 0x62.
//
// An attribute stream is terminated with a header kind of ATTRIBUTE_END (header
// byte of zero.)
//
// ImageLocation inflates the stream into individual values stored in the long
// array _attributes. This allows an attribute value can be quickly accessed by
// direct indexing. Unspecified values default to zero.
//
// Notes:
//  - Even though ATTRIBUTE_END is used to mark the end of the attribute stream,
//    streams will contain zero byte values to represent lesser significant bits.
//    Thus, detecting a zero byte is not sufficient to detect the end of an attribute
//    stream.
//  - ATTRIBUTE_OFFSET represents the number of bytes from the beginning of the region
//    storing the resources.  Thus, in an image this represents the number of bytes
//    after the directory.
//  - Currently, compressed resources are represented by having a non-zero
//    ATTRIBUTE_COMPRESSED value.  This represents the number of bytes stored in the
//    image, and the value of ATTRIBUTE_UNCOMPRESSED represents number of bytes of the
//    inflated resource in memory. If the ATTRIBUTE_COMPRESSED is zero then the value
//    of ATTRIBUTE_UNCOMPRESSED represents both the number of bytes in the image and
//    in memory.  In the future, additional compression techniques will be used and
//    represented differently.
//  - Package strings include trailing slash and extensions include prefix period.
//
class ImageLocation {
public:
  // Attribute kind enumeration.
  static const u1 ATTRIBUTE_END = 0; // End of attribute stream marker
  static const u1 ATTRIBUTE_BASE = 1; // String table offset of resource path base
  static const u1 ATTRIBUTE_PARENT = 2; // String table offset of resource path parent
  static const u1 ATTRIBUTE_EXTENSION = 3; // String table offset of resource path extension
  static const u1 ATTRIBUTE_OFFSET = 4; // Container byte offset of resource
  static const u1 ATTRIBUTE_COMPRESSED = 5; // In image byte size of the compressed resource
  static const u1 ATTRIBUTE_UNCOMPRESSED = 6; // In memory byte size of the uncompressed resource
  static const u1 ATTRIBUTE_COUNT = 7; // Number of attribute kinds

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

// Manage the image file.
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
    u2 _majorVersion;  // Image file major version number
    u2 _minorVersion;  // Image file minor version number
    u4 _locationCount; // Number of locations managed in index.
    u4 _locationsSize; // Number of bytes in attribute table.
    u4 _stringsSize;   // Number of bytes in string table.
  };

  char* _name;         // Name of image
  int _fd;             // File descriptor
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

  // Return a string table accessor.
  inline const ImageStrings getStrings() const {
    return ImageStrings(_stringBytes, _header._stringsSize);
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

  // Return the resource for the supplied location info.
  u1* getResource(ImageLocation& location) const;

  // Return the resource associated with the name else NULL if not found.
  void getResource(const char* name, u1*& buffer, u8& size) const;

  // Return an array of packages for a given module
  GrowableArray<const char*>* packages(const char* name);
};

#endif // SHARE_VM_CLASSFILE_IMAGEFILE_HPP
