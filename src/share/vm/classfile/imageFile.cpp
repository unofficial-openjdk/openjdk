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
#include "classfile/imageFile.hpp"

#ifdef TARGET_OS_FAMILY_linux
# include "os_linux.inline.hpp"
#endif
#ifdef TARGET_OS_FAMILY_solaris
# include "os_solaris.inline.hpp"
#endif
#ifdef TARGET_OS_FAMILY_windows
# include "os_windows.inline.hpp"
#endif
#ifdef TARGET_OS_FAMILY_aix
# include "os_aix.inline.hpp"
#endif
#ifdef TARGET_OS_FAMILY_bsd
# include "os_bsd.inline.hpp"
#endif

#include "runtime/mutex.hpp"
#include "utilities/bytes.hpp"


// Compute the Perfect Hashing hash code for the supplied string.
u4 ImageStrings::hashCode(const char* string, u4 seed) {
  u1* bytes = (u1*)string;

  // Ensure better uniformity.
  if (seed == 0) {
    seed = HASH_MULTIPLIER;
  }

  // Compute hash code.
  for (u1 byte = *bytes++; byte; byte = *bytes++) {
    seed = (seed * HASH_MULTIPLIER) ^ byte;
  }

  // Ensure the result is unsigned.
  return seed & 0x7FFFFFFF;
}

// Test to see if string begins with start.  If so returns remaining portion
// of string.  Otherwise, NULL.
const char* ImageStrings::startsWith(const char* string, const char* start) {
  char ch1, ch2;

  // Match up the strings the best we can.
  while ((ch1 = *string) && (ch2 = *start)) {
    if (ch1 != ch2) {
      // Mismatch, return NULL.
      return NULL;
    }

    string++, start++;
  }

  // Return remainder of string.
  return string;
}

ImageLocation::ImageLocation(u1* data) {
  // Deflate the attribute stream into an array of attributes.
  memset(_attributes, 0, sizeof(_attributes));
  u1 byte;

  while ((byte = *data) != ATTRIBUTE_END) {
    u1 kind = attributeKind(byte);
    u1 n = attributeLength(byte);
    assert(kind < ATTRIBUTE_COUNT, "invalid image location attribute");
    _attributes[kind] = attributeValue(data + 1, n);
    data += n + 1;
  }
}

ImageFile::ImageFile(const char* name) {
  // Copy the image file name.
  _name = NEW_C_HEAP_ARRAY(char, strlen(name)+1, mtClass);
  strcpy(_name, name);

  // Initialize for a closed file.
  _fd = -1;
  _memoryMapped = true;
  _indexData = NULL;
}

ImageFile::~ImageFile() {
  // Ensure file is closed.
  close();

  // Free up name.
  FREE_C_HEAP_ARRAY(char, _name, mtClass);
}

bool ImageFile::open() {
  // If file exists open for reading.
  struct stat st;
  if (os::stat(_name, &st) != 0 ||
    (st.st_mode & S_IFREG) != S_IFREG ||
    (_fd = os::open(_name, 0, O_RDONLY)) == -1) {
    return false;
  }

  // Read image file header and verify.
  u8 headerSize = sizeof(ImageHeader);
  if (os::read(_fd, &_header, headerSize) != headerSize ||
    _header._magic != IMAGE_MAGIC ||
    _header._majorVersion != MAJOR_VERSION ||
    _header._minorVersion != MINOR_VERSION) {
    close();
    return false;
  }

  // Memory map index.
  _indexSize = indexSize();
  _indexData = (u1*)os::map_memory(_fd, _name, 0, NULL, _indexSize, true, false);

  // Failing that, read index into C memory.
  if (_indexData == NULL) {
    _memoryMapped = false;
    _indexData = NEW_RESOURCE_ARRAY(u1, _indexSize);

    if (os::seek_to_file_offset(_fd, 0) == -1) {
      close();
      return false;
    }

    if (os::read(_fd, _indexData, _indexSize) != _indexSize) {
      close();
      return false;
    }

    return true;
  }

// Used to advance a pointer, unstructured.
#undef nextPtr
#define nextPtr(base, fromType, count, toType) (toType*)((fromType*)(base) + (count))
  // Pull tables out from the index.
  _redirectTable = nextPtr(_indexData, u1, headerSize, s4);
  _offsetsTable = nextPtr(_redirectTable, s4, _header._locationCount, u4);
  _locationBytes = nextPtr(_offsetsTable, u4, _header._locationCount, u1);
  _stringBytes = nextPtr(_locationBytes, u1, _header._locationsSize, u1);
#undef nextPtr

  // Successful open.
  return true;
}

void ImageFile::close() {
  // Dealllocate the index.
  if (_indexData) {
    if (_memoryMapped) {
      os::unmap_memory((char*)_indexData, _indexSize);
    } else {
      FREE_RESOURCE_ARRAY(u1, _indexData, _indexSize);
    }

    _indexData = NULL;
  }

  // close file.
  if (_fd != -1) {
    os::close(_fd);
    _fd = -1;
  }

}

// Return the attribute stream for a named resourced.
u1* ImageFile::findLocationData(const char* name) const {
  // Compute hash.
  u4 hash = ImageStrings::hashCode(name) % _header._locationCount;
  s4 redirect = _redirectTable[hash];

  if (!redirect) {
    return NULL;
  }

  u4 index;

  if (redirect < 0) {
    // If no collision.
    index = -redirect - 1;
  } else {
    // If collision, recompute hash code.
    index = ImageStrings::hashCode(name, redirect) % _header._locationCount;
  }

  assert(index < _header._locationCount, "index exceeds location count");
  u4 offset = _offsetsTable[index];
  assert(offset < _header._locationsSize, "offset exceeds location attributes size");

  return _locationBytes + offset;
}

// Verify that a found location matches the supplied path name.
bool ImageFile::verifyLocation(ImageLocation& location, const char* name) const {
  // Retrieve each path component string.
  ImageStrings strings(_stringBytes, _header._stringsSize);
  // Match a path with each subcomponent without concatenation (copy).
  // Match up path parent.
  const char* parent = location.getAttribute(ImageLocation::ATTRIBUTE_PARENT, strings);
  const char* next = ImageStrings::startsWith(name, parent);
  // Continue only if a complete match.
  if (!next) return false;
  // Match up path base.
  const char* base = location.getAttribute(ImageLocation::ATTRIBUTE_BASE, strings);
  next = ImageStrings::startsWith(next, base);
  // Continue only if a complete match.
  if (!next) return false;
  // Match up path extension.
  const char* extension = location.getAttribute(ImageLocation::ATTRIBUTE_EXTENSION, strings);
  next = ImageStrings::startsWith(next, extension);

  // True only if complete match and no more characters.
  return next && *next == '\0';
}

// Return the resource for the supplied location.
u1* ImageFile::getResource(ImageLocation& location) const {
  // Retrieve the byte offset and size of the resource.
  u8 offset = _indexSize + location.getAttribute(ImageLocation::ATTRIBUTE_OFFSET);
  u8 size = location.getAttribute(ImageLocation::ATTRIBUTE_UNCOMPRESSED);
  u8 compressedSize = location.getAttribute(ImageLocation::ATTRIBUTE_COMPRESSED);
  u8 readSize = compressedSize ? compressedSize : size;

  // Allocate space for the resource.
  u1* data = NEW_RESOURCE_ARRAY(u1, readSize);

  bool isRead = os::read_at(_fd, data, readSize, offset) == readSize;
  guarantee(isRead, "error reading from image or short read");

  // If not compressed, just return the data.
  if (!compressedSize) {
    return data;
  }

  u1* uncompressed = NEW_RESOURCE_ARRAY(u1, size);
  char* msg = NULL;
  jboolean res = ClassLoader::decompress(data, compressedSize, uncompressed, size, &msg);
  FREE_RESOURCE_ARRAY(u1, data, readSize);
  if (!res) {
      FREE_RESOURCE_ARRAY(u1, uncompressed, size);
      warning("compression failed due to %s\n", msg);
      return NULL;
  }

  return uncompressed;
}

void ImageFile::getResource(const char* name, u1*& buffer, u8& size) const {
  buffer = NULL;
  size = 0;
  u1* data = findLocationData(name);
  if (data) {
    ImageLocation location(data);
    if (verifyLocation(location, name)) {
      size = location.getAttribute(ImageLocation::ATTRIBUTE_UNCOMPRESSED);
      buffer = getResource(location);
    }
  }
}

GrowableArray<const char*>* ImageFile::packages(const char* name) {
  char entry[JVM_MAXPATHLEN];
  if (jio_snprintf(entry, sizeof(entry), "%s/packages.offsets", name) == -1) {
    return NULL;
  }

  u1* buffer;
  u8 size;

  getResource(entry, buffer, size);

  if (!buffer) {
    tty->print_cr("ERROR: %s\n", entry);
    return NULL;
  }

  ImageStrings strings(_stringBytes, _header._stringsSize);
  GrowableArray<const char*>* pkgs = new GrowableArray<const char*>();
  int count = size / 4;
  for (int i = 0; i < count; i++) {
    u4 offset = Bytes::get_Java_u4(buffer + (i*4));
    const char* p = strings.get(offset);
    pkgs->append(p);
  }

  return pkgs;
}
