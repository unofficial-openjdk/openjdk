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

#ifndef SHARE_VM_CLASSFILE_IMAGEDECOMPRESSOR_HPP
#define SHARE_VM_CLASSFILE_IMAGEDECOMPRESSOR_HPP

#include "classfile/classLoader.hpp"
#include "classfile/imageFile.hpp"
#include "utilities/growableArray.hpp"

// Resource located in image can have an header
struct ResourceHeader {
  u4 _magic; // Resource header
  u4 _size;  // Resource size
  u4 _uncompressed_size;  // Expected uncompressed size
  u4 _decompressor_name_offset;  // Strings table decompressor offset
  u4 _decompressor_config_offset; // Strings table config offset
  u1 _is_terminal; // Last decompressor 1, otherwise 0.
};

class ImageDecompressor: public CHeapObj<mtClass> {

private:
  const char* _name;
  static const u1 RESOURCE_HEADER_LENGTH = 21;
  static const u4 RESOURCE_HEADER_MAGIC = 0xCAFEFAFA;
  static GrowableArray<ImageDecompressor*>* decompressors;

  inline const char* get_name() const { return _name; }

  inline static void add_decompressor(ImageDecompressor* decompressor) {
     decompressors->append(decompressor);
  }

  inline static ImageDecompressor* get_decompressor(const char * decompressor_name) {
    for (int i = 0; i < decompressors->length(); i++) {
      ImageDecompressor* decompressor = decompressors->at(i);
      if (strcmp(decompressor_name, decompressor->get_name()) == 0) {
        return decompressor;
      }
    }
    return NULL;
  }

public:
  ImageDecompressor(const char* name) : _name(name) {
      add_decompressor(this);
  }
  virtual void decompress_resource(u1* data, u1* uncompressed,
    ResourceHeader* header, const ImageStrings* strings) {}
  static void decompress_resource(u1* compressed, u1* uncompressed,
    u4 uncompressed_size, const ImageStrings* strings, bool is_C_heap);
};

class ZIPDecompressor : public ImageDecompressor {
private:
  static ZIPDecompressor* ZIP;
public:
  ZIPDecompressor() : ImageDecompressor("zip") { }
  void decompress_resource(u1* data, u1* uncompressed, ResourceHeader* header,
    const ImageStrings* strings);
};

#endif // SHARE_VM_CLASSFILE_IMAGEDECOMPRESSOR_HPP
