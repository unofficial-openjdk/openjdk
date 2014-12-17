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
#include "runtime/os.inline.hpp"
#include "utilities/bytes.hpp"

// Used to advance a pointer, unstructured.
#undef NEXTPTR
#define NEXTPTR(base, fromType, count, toType) (toType*)((fromType*)(base) + (count))

// Compute the Perfect Hashing hash code for the supplied string.
s4 ImageStrings::hash_code(const char* string, s4 seed) {
  u1* bytes = (u1*)string;

  // Compute hash code.
  for (u1 byte = *bytes++; byte; byte = *bytes++) {
    seed = (seed * HASH_MULTIPLIER) ^ byte;
  }

  // Ensure the result is unsigned.
  return seed & 0x7FFFFFFF;
}

s4 ImageStrings::find(const char* name, s4* redirect, s4 length) {
  if (!length) {
    return NOT_FOUND;
  }

  s4 hash_code = ImageStrings::hash_code(name);
  s4 index = hash_code % length;
  s4 value = redirect[index];

  if (value > 0 ) {
    // Collision value, need to rehash.
    hash_code = ImageStrings::hash_code(name, value);

    return hash_code % length;
  } else if (value < 0) {
    // Direct access.
    return -1 - value;
  }

  return NOT_FOUND;
}

const char* ImageStrings::starts_with(const char* string, const char* start) {
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
    u1 kind = attribute_kind(byte);
    u1 n = attribute_length(byte);
    assert(kind < ATTRIBUTE_COUNT, "invalid image location attribute");
    _attributes[kind] = attribute_value(data + 1, n);
    data += n + 1;
  }
}

ImageModuleData::ImageModuleData(ImageFile* image_file)
      : _image_file(image_file), _strings(image_file->get_strings()) {
  char name[JVM_MAXPATHLEN];
  module_data_name(name, image_file->name());
  _image_file->get_resource(name, _data, _data_size, true);
  guarantee(_data, "missing module data");

  _header = NEXTPTR(_data, u1, 0, Header);
  int ptm_count = _header->_ptm_count;
  int mtp_count = _header->_mtp_count;
  _ptm_redirect = NEXTPTR(_header, Header, 1, s4);
  _ptm_data = NEXTPTR(_ptm_redirect, s4, ptm_count, PTMData);
  _mtp_redirect = NEXTPTR(_ptm_data, PTMData, ptm_count, s4);
  _mtp_data = NEXTPTR(_mtp_redirect, s4, mtp_count, MTPData);
  _mtp_packages = NEXTPTR(_mtp_data, MTPData, mtp_count, s4);
}

ImageModuleData::~ImageModuleData() {
  if (_data) {
    FREE_C_HEAP_ARRAY(u1, _data, mtClass);
  }
}

void ImageModuleData::module_data_name(char* buffer, const char* image_file_name) {
  const char* slash = strrchr(image_file_name, os::file_separator()[0]);
  const char* name = slash ? slash + 1 : (char *)image_file_name;
  const char* dot = strrchr(name, '.');

  if (dot) {
    int length = dot - name;
    strncpy(buffer, name, length);
    buffer[length] = '\0';
  } else {
    strcpy(buffer, name);
  }

  strcat(buffer, ".jdata");
}

const char* ImageModuleData::package_to_module(const char* package_name) {
  s4 index = ImageStrings::find(package_name, _ptm_redirect, _header->_ptm_count);

  if (index != ImageStrings::NOT_FOUND) {
    PTMData* data = _ptm_data + index;

    if (strcmp(package_name, get_string(data->_name_offset)) != 0) {
      return NULL;
    }

    return get_string(data->_module_name_offset);
  }

  return NULL;
}

GrowableArray<const char*>* ImageModuleData::module_to_packages(const char* module_name) {
  s4 index = ImageStrings::find(module_name, _mtp_redirect, _header->_mtp_count);

  if (index != ImageStrings::NOT_FOUND) {
    MTPData* data = _mtp_data + index;

    if (strcmp(module_name, get_string(data->_name_offset)) != 0) {
      return NULL;
    }

    GrowableArray<const char*>* packages = new GrowableArray<const char*>();
    for (int i = 0; i < data->_package_count; i++) {
      u4 package_name_offset = _mtp_packages[data->_package_offset + i];
      const char* package_name = get_string(package_name_offset);
      packages->append(package_name);
    }

    return packages;
  }

  return NULL;
}


ImageFile::ImageFile(const char* name) {
  // Copy the image file name.
  _name = NEW_C_HEAP_ARRAY(char, strlen(name)+1, mtClass);
  strcpy(_name, name);

  // Initialize for a closed file.
  _fd = -1;
  _memory_mapped = true;
  _index_data = NULL;
  _module_data = NULL;
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
  u8 header_size = sizeof(ImageHeader);
  if (os::read(_fd, &_header, header_size) != header_size ||
    _header._magic != IMAGE_MAGIC ||
    _header._major_version != MAJOR_VERSION ||
    _header._minor_version != MINOR_VERSION) {
    close();
    return false;
  }

  // Memory map index.
  _index_size = index_size();
  _index_data = (u1*)os::map_memory(_fd, _name, 0, NULL, _index_size, true, false);

  // Failing that, read index into C memory.
  if (_index_data == NULL) {
    _memory_mapped = false;
    _index_data = NEW_C_HEAP_ARRAY(u1, _index_size, mtClass);

    if (os::seek_to_file_offset(_fd, 0) == -1) {
      close();
      return false;
    }

    if (os::read(_fd, _index_data, _index_size) != _index_size) {
      close();
      return false;
    }

    return true;
  }

  // Pull tables out from the index.
  _redirect_table = NEXTPTR(_index_data, u1, header_size, s4);
  _offsets_table = NEXTPTR(_redirect_table, s4, _header._location_count, u4);
  _location_bytes = NEXTPTR(_offsets_table, u4, _header._location_count, u1);
  _string_bytes = NEXTPTR(_location_bytes, u1, _header._locations_size, u1);

  // Load module meta data.
  _module_data = new ImageModuleData(this);

  // Successful open.
  return true;
}

void ImageFile::close() {
  // Deallocate module meta data.
  if (_module_data) {
    delete _module_data;
    _module_data = NULL;
  }

  // Dealllocate the index.
  if (_index_data) {
    if (_memory_mapped) {
      os::unmap_memory((char*)_index_data, _index_size);
    } else {
      FREE_C_HEAP_ARRAY(u1, _index_data, mtClass);
    }

    _index_data = NULL;
  }

  // close file.
  if (_fd != -1) {
    os::close(_fd);
    _fd = -1;
  }

}

// Return the attribute stream for a named resourced.
u1* ImageFile::find_location_data(const char* path) const {
  s4 index = ImageStrings::find(path, _redirect_table, _header._location_count);

  if (index == ImageStrings::NOT_FOUND) {
    return NULL;
  }

  guarantee((u4)index < _header._location_count, "index exceeds location count");
  u4 offset = _offsets_table[index];
  guarantee((u4)offset < _header._locations_size, "offset exceeds location attributes size");

  if (offset == 0) {
    return NULL;
  }

  return _location_bytes + offset;
}

void ImageFile::location_path(ImageLocation& location, char* path, size_t max) const {
  ImageStrings strings(_string_bytes, _header._strings_size);
  char* next = path;
  size_t length;

  const char* module = location.get_attribute(ImageLocation::ATTRIBUTE_MODULE, strings);
  if (*module != '\0') {
    length = strlen(module);
    guarantee(next - path + length + 2 < max, "buffer overflow");
    *next++ = '/';
    strcpy(next, module); next += length;
    *next++ = '/';
  }

  const char* parent = location.get_attribute(ImageLocation::ATTRIBUTE_PARENT, strings);
  if (*parent != '\0') {
    length = strlen(parent);
    guarantee(next - path + length + 1 < max, "buffer overflow");
    strcpy(next, parent); next += length;
    *next++ = '/';
  }

  const char* base = location.get_attribute(ImageLocation::ATTRIBUTE_BASE, strings);
  length = strlen(base);
  guarantee(next - path + length < max, "buffer overflow");
  strcpy(next, base); next += length;

  const char* extension = location.get_attribute(ImageLocation::ATTRIBUTE_EXTENSION, strings);
  if (*extension != '\0') {
    length = strlen(extension);
    guarantee(next - path + length + 1 < max, "buffer overflow");
    *next++ = '.';
    strcpy(next, extension); next += length;
  }

  guarantee((size_t)(next - path) < max, "buffer overflow");
  *next = '\0';
}

// Verify that a found location matches the supplied path.
bool ImageFile::verify_location(ImageLocation& location, const char* path) const {
  ImageStrings strings(_string_bytes, _header._strings_size);
  const char* next = path;

  const char* module = location.get_attribute(ImageLocation::ATTRIBUTE_MODULE, strings);
  if (*module != '\0') {
    if (*next++ != '/') return false;
    if (!(next = ImageStrings::starts_with(next, module))) return false;
    if (*next++ != '/') return false;
  }

  const char* parent = location.get_attribute(ImageLocation::ATTRIBUTE_PARENT, strings);
  if (*parent != '\0') {
    if (!(next = ImageStrings::starts_with(next, parent))) return false;
    if (*next++ != '/') return false;
  }

  const char* base = location.get_attribute(ImageLocation::ATTRIBUTE_BASE, strings);
  if (!(next = ImageStrings::starts_with(next, base))) return false;

  const char* extension = location.get_attribute(ImageLocation::ATTRIBUTE_EXTENSION, strings);
  if (*extension != '\0') {
    if (*next++ != '.') return false;
    if (!(next = ImageStrings::starts_with(next, extension))) return false;
  }

  // True only if complete match and no more characters.
  return *next == '\0';
}

// Return the resource for the supplied location.
u1* ImageFile::get_resource(ImageLocation& location, bool is_C_heap) const {
  // Retrieve the byte offset and size of the resource.
  u8 offset = _index_size + location.get_attribute(ImageLocation::ATTRIBUTE_OFFSET);
  u8 size = location.get_attribute(ImageLocation::ATTRIBUTE_UNCOMPRESSED);
  u8 compressed_size = location.get_attribute(ImageLocation::ATTRIBUTE_COMPRESSED);
  u8 read_size = compressed_size ? compressed_size : size;

  // Allocate space for the resource.
  u1* data = is_C_heap && !compressed_size ? NEW_C_HEAP_ARRAY(u1, read_size, mtClass)
                                           : NEW_RESOURCE_ARRAY(u1, read_size);

  bool is_read = os::read_at(_fd, data, read_size, offset) == read_size;
  guarantee(is_read, "error reading from image or short read");

  // If not compressed, just return the data.
  if (!compressed_size) {
    return data;
  }

  u1* uncompressed = is_C_heap ? NEW_C_HEAP_ARRAY(u1, size, mtClass)
                               : NEW_RESOURCE_ARRAY(u1, size);
  char* msg = NULL;
  jboolean res = ClassLoader::decompress(data, compressed_size, uncompressed, size, &msg);
  if (!res) warning("decompression failed due to %s\n", msg);
  guarantee(res, "decompression failed");

  return uncompressed;
}

void ImageFile::get_resource(const char* path, u1*& buffer, u8& size, bool is_C_heap) const {
  buffer = NULL;
  size = 0;
  u1* data = find_location_data(path);
  if (data) {
    ImageLocation location(data);
    if (verify_location(location, path)) {
      size = location.get_attribute(ImageLocation::ATTRIBUTE_UNCOMPRESSED);
      buffer = get_resource(location, is_C_heap);
    }
  }
}

GrowableArray<const char*>* ImageFile::packages(const char* module_name) {
  guarantee(_module_data, "image file not opened");
  return _module_data->module_to_packages(module_name);
}
