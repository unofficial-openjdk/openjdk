/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
#include "classfile/imageDecompressor.hpp"
#include "classfile/imageFile.hpp"
#include "runtime/mutex.hpp"
#include "runtime/mutexLocker.hpp"
#include "runtime/os.inline.hpp"
#include "utilities/endian.hpp"
#include "utilities/growableArray.hpp"

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

s4 ImageStrings::find(Endian* endian, const char* name, s4* redirect, u4 length) {
  if (!redirect || !length) {
    return NOT_FOUND;
  }

  s4 hash_code = ImageStrings::hash_code(name);
  s4 index = hash_code % length;
  s4 value = endian->get(redirect[index]);

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
    guarantee(kind < ATTRIBUTE_COUNT, "invalid image location attribute");
    _attributes[kind] = attribute_value(data + 1, n);
    data += n + 1;
  }
}

ImageModuleData::ImageModuleData(const ImageFileReader* image_file,
        const char* module_data_name) :
    _image_file(image_file),
    _endian(image_file->endian()),
    _strings(image_file->get_strings()) {
  _image_file->get_resource(module_data_name, _data, _data_size, true);
  guarantee(_data, "missing module data");

  _header = NEXTPTR(_data, u1, 0, Header);
  u4 ptm_count = _header->ptm_count(_endian);
  u4 mtp_count = _header->mtp_count(_endian);

  _ptm_redirect = NEXTPTR(_header, Header, 1, s4);
  _ptm_data = NEXTPTR(_ptm_redirect, s4, ptm_count, PTMData);
  _mtp_redirect = NEXTPTR(_ptm_data, PTMData, ptm_count, s4);
  _mtp_data = NEXTPTR(_mtp_redirect, s4, mtp_count, MTPData);
  _mtp_packages = NEXTPTR(_mtp_data, MTPData, mtp_count, s4);
}

ImageModuleData::~ImageModuleData() {
  if (_data) {
    FREE_C_HEAP_ARRAY(u1, _data);
  }
}

void ImageModuleData::module_data_name(char* buffer, const char* image_file_name) {
  const char* slash = strrchr(image_file_name, os::file_separator()[0]);
  const char* name = slash ? slash + 1 : (char *)image_file_name;
  const char* dot = strrchr(name, '.');
  guarantee(dot, "missing extension on jimage name");
  int length = dot - name;
  strncpy(buffer, name, length);
  buffer[length] = '\0';

  strcat(buffer, ".jdata");
}

const char* ImageModuleData::package_to_module(const char* package_name) {
  s4 index = ImageStrings::find(_endian, package_name, _ptm_redirect,
                                  _header->ptm_count(_endian));

  if (index != ImageStrings::NOT_FOUND) {
    PTMData* data = _ptm_data + index;

    if (strcmp(package_name, get_string(data->name_offset(_endian))) != 0) {
      return NULL;
    }

    return get_string(data->module_name_offset(_endian));
  }

  return NULL;
}

GrowableArray<const char*>* ImageModuleData::module_to_packages(const char* module_name) {
  s4 index = ImageStrings::find(_endian, module_name, _mtp_redirect,
                                  _header->mtp_count(_endian));

  if (index != ImageStrings::NOT_FOUND) {
    MTPData* data = _mtp_data + index;

    if (strcmp(module_name, get_string(data->name_offset(_endian))) != 0) {
      return NULL;
    }

    GrowableArray<const char*>* packages = new GrowableArray<const char*>();
    s4 package_offset = data->package_offset(_endian);
    for (u4 i = 0; i < data->package_count(_endian); i++) {
      u4 package_name_offset = mtp_package(package_offset + i);
      const char* package_name = get_string(package_name_offset);
      packages->append(package_name);
    }

    return packages;
  }

  return NULL;
}

GrowableArray<ImageFileReader*>* ImageFileReader::_reader_table =
  new(ResourceObj::C_HEAP, mtInternal) GrowableArray<ImageFileReader*>(2, true);

ImageFileReader* ImageFileReader::open(const char* name, bool big_endian) {
  MutexLockerEx il(ImageFileReaderTable_lock,  Mutex::_no_safepoint_check_flag);
  ImageFileReader* reader;

  for (int i = 0; i < _reader_table->length(); i++) {
    reader = _reader_table->at(i);

    if (strcmp(reader->name(), name) == 0) {
      reader->inc_use();
      return reader;
    }
  }

  reader = new ImageFileReader(name, big_endian);
  bool opened = reader->open();

  if (!opened) {
    delete reader;
    return NULL;
  }

  reader->inc_use();
  _reader_table->append(reader);
  return reader;
}

void ImageFileReader::close(ImageFileReader *reader) {
  MutexLockerEx il(ImageFileReaderTable_lock,  Mutex::_no_safepoint_check_flag);

  if (reader->dec_use()) {
    _reader_table->remove(reader);
    delete reader;
  }
}

// Return an id for the specifed ImageFileReader.
u8 ImageFileReader::readerToID(ImageFileReader *reader) {
  return (u8)reader;
}

// Return an id for the specifed ImageFileReader.
ImageFileReader* ImageFileReader::idToReader(u8 id) {
  ImageFileReader* reader = (ImageFileReader*)id;
#ifndef PRODUCT
  MutexLockerEx il(ImageFileReaderTable_lock,  Mutex::_no_safepoint_check_flag);
  guarantee(_reader_table->contains(reader), "bad image id");
#endif
  return reader;
}

ImageFileReader::ImageFileReader(const char* name, bool big_endian) {
  // Copy the image file name.
  _name = NEW_C_HEAP_ARRAY(char, strlen(name)+1, mtClass);
  strcpy(_name, name);
  // Initialize for a closed file.
  _fd = -1;
  _endian = Endian::get_handler(big_endian);
  _index_data = NULL;
}

ImageFileReader::~ImageFileReader() {
  // Ensure file is closed.
  close();

  // Free up name.
  if (_name) {
    FREE_C_HEAP_ARRAY(char, _name);
    _name = NULL;
  }
}

bool ImageFileReader::open() {
  // If file exists open for reading.
  struct stat st;
  if (os::stat(_name, &st) != 0 ||
    (st.st_mode & S_IFREG) != S_IFREG ||
    (_fd = os::open(_name, 0, O_RDONLY)) == -1) {
    return false;
  }

  // Read image file header and verify.
  size_t header_size = sizeof(ImageHeader);
  if (!read_at((u1*)&_header, header_size, 0) ||
    _header.magic(_endian) != IMAGE_MAGIC ||
    _header.major_version(_endian) != MAJOR_VERSION ||
    _header.minor_version(_endian) != MINOR_VERSION) {
    close();

    return false;
  }

  // Memory map index.
  _index_size = index_size();
  off_t map_size = (off_t)(MemoryMapImage ? st.st_size : _index_size);
  _index_data = (u1*)os::map_memory(_fd, _name, 0, NULL, map_size, true, false);
  guarantee(_index_data, "image file not memory mapped");

  // Pull tables out from the index.
  u4 length = table_length();
  _redirect_table = NEXTPTR(_index_data, u1, header_size, s4);
  _offsets_table = NEXTPTR(_redirect_table, s4, length, u4);
  _location_bytes = NEXTPTR(_offsets_table, u4, length, u1);
  _string_bytes = NEXTPTR(_location_bytes, u1, locations_size(), u1);

  // Successful open.
  return true;
}

void ImageFileReader::close() {
  // Dealllocate the index.
  if (_index_data) {
    os::unmap_memory((char*)_index_data, _index_size);
    _index_data = NULL;
  }

  // close file.
  if (_fd != -1) {
    os::close(_fd);
    _fd = -1;
  }
}

// Read directly from the file.
bool ImageFileReader::read_at(u1* data, u8 size, u8 offset) const {
  u8 read = os::read_at(_fd, data, size, offset);

  return read == size;
}

// Return the attribute stream for a named resource.
u1* ImageFileReader::find_location_data(const char* path) const {
  s4 index = ImageStrings::find(_endian, path, _redirect_table, table_length());

  if (index == ImageStrings::NOT_FOUND) {
    return NULL;
  }

  return get_location_data(index);
}

void ImageFileReader::location_path(ImageLocation& location, char* path, size_t max) const {
  ImageStrings strings(_string_bytes, _header.strings_size(_endian));
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
bool ImageFileReader::verify_location(ImageLocation& location, const char* path) const {
  ImageStrings strings(_string_bytes, _header.strings_size(_endian));
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
u1* ImageFileReader::get_resource(ImageLocation& location, bool is_C_heap) const {
  // Retrieve the byte offset and size of the resource.
  u8 offset = location.get_attribute(ImageLocation::ATTRIBUTE_OFFSET);
  u8 uncompressed_size = location.get_attribute(ImageLocation::ATTRIBUTE_UNCOMPRESSED);
  u8 compressed_size = location.get_attribute(ImageLocation::ATTRIBUTE_COMPRESSED);

  if (compressed_size) {
    u1* compressed_data = MemoryMapImage ? get_data_address() + offset
                                         : NEW_RESOURCE_ARRAY(u1, compressed_size);
    if (!MemoryMapImage) {
      bool is_read = read_at(compressed_data, compressed_size, _index_size + offset);
      guarantee(is_read, "error reading from image or short read");
    }

    u1* uncompressed_data = is_C_heap ? NEW_C_HEAP_ARRAY(u1, uncompressed_size, mtClass)
                                      : NEW_RESOURCE_ARRAY(u1, uncompressed_size);
    const ImageStrings strings = get_strings();
    ImageDecompressor::decompress_resource(compressed_data, uncompressed_data, uncompressed_size,
            &strings, is_C_heap);
    if (!MemoryMapImage) {
        FREE_RESOURCE_ARRAY(u1, compressed_data, compressed_size);
    }
    return uncompressed_data;
  } else {
    if (MemoryMapImage && !is_C_heap) {
      return get_data_address() + offset;
    }

    u1* uncompressed_data = is_C_heap ? NEW_C_HEAP_ARRAY(u1, uncompressed_size, mtClass)
                                      : NEW_RESOURCE_ARRAY(u1, uncompressed_size);
    bool is_read = read_at(uncompressed_data, uncompressed_size, _index_size + offset);
    guarantee(is_read, "error reading from image or short read");

    return uncompressed_data;
  }
}

void ImageFileReader::get_resource(const char* path, u1*& buffer, u8& size, bool is_C_heap) const {
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



