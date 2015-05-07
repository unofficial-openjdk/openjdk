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
#include "utilities/bytes.hpp"

// Allocate in C Heap not in resource area, otherwise JVM crashes.
GrowableArray<ImageDecompressor*>* ImageDecompressor::decompressors =
        new(ResourceObj::C_HEAP, mtInternal) GrowableArray<ImageDecompressor*>(2, true);

void ImageDecompressor::decompress_resource(u1* compressed, u1* uncompressed,
        u4 uncompressed_size, const ImageStrings* strings, bool is_C_heap) {
  bool has_header = false;
  u1* decompressed_resource = compressed;
  u1* compressed_resource = compressed;

  // Resource could have been transformed by a stack of decompressors
  do {
    ResourceHeader _header;
    memcpy(&_header, compressed_resource, sizeof(ResourceHeader));
    has_header = _header._magic == RESOURCE_HEADER_MAGIC;
    if (has_header) {
      decompressed_resource = is_C_heap && _header._is_terminal ?
          NEW_C_HEAP_ARRAY(u1, _header._uncompressed_size, mtClass) :
          NEW_C_HEAP_ARRAY(u1, _header._uncompressed_size, mtOther);
      const char* decompressor_name = strings->get(_header._decompressor_name_offset);
      if (decompressor_name == NULL) warning("image decompressor not found\n");
      guarantee(decompressor_name, "image decompressor not found");
      ImageDecompressor* decompressor = get_decompressor(decompressor_name);
      if (decompressor == NULL) warning("image decompressor %s not found\n",
              decompressor_name);
      guarantee(decompressor, "image decompressor not found");
      u1* compressed_resource_base = compressed_resource;
      compressed_resource += RESOURCE_HEADER_LENGTH;
      decompressor->decompress_resource(compressed_resource, decompressed_resource,
              &_header, strings);
      if(compressed_resource_base != compressed) {
        FREE_C_HEAP_ARRAY(char, compressed_resource_base);
      }
      compressed_resource = decompressed_resource;
    }
  }while(has_header);
  memcpy(uncompressed, decompressed_resource, uncompressed_size);
}

// ZIP decompressor
ZIPDecompressor* ZIPDecompressor::ZIP = new ZIPDecompressor();

void ZIPDecompressor::decompress_resource(u1* data, u1* uncompressed,
        ResourceHeader* header, const ImageStrings* strings) {
  char* msg = NULL;
  jboolean res = ClassLoader::decompress(data, header->_size, uncompressed,
          header->_uncompressed_size, &msg);
  if (!res) warning("decompression failed due to %s\n", msg);
  guarantee(res, "decompression failed");
}

// END ZIP Decompressor

// Constant Pool Plugin
CPCDecompressor* CPCDecompressor::CPC = new CPCDecompressor();

const u1* CPCDecompressor::SIZES = get_cp_entry_sizes();

void CPCDecompressor::decompress_resource(u1* data, u1* uncompressed_resource,
        ResourceHeader* header, const ImageStrings* strings) {
    u1* uncompressed_base = uncompressed_resource;
    u1* data_base = data;
    int header_size = 8; // magic + major + minor
    memcpy(uncompressed_resource, data, header_size + 2); //+ cp count
    uncompressed_resource+=header_size+2;
    data+=header_size;
    u2 cp_count = Bytes::get_Java_u2(data);
    data+=2;
    for (int i = 1; i < cp_count; i++) {
        char tag = *data;
        data+=1;
        switch (tag) {

            case EXTERNALIZED_STRING: { // String in Strings table
                *uncompressed_resource = 1;
                uncompressed_resource += 1;
                int i = decompress_int(data);
                const char * string = strings->get(i);
                int str_length = (int) strlen(string);
                Bytes::put_Java_u2(uncompressed_resource, str_length);
                uncompressed_resource += 2;
                memcpy(uncompressed_resource, string, str_length);
                uncompressed_resource += str_length;
                break;
            }

            case EXTERNALIZED_STRING_DESCRIPTOR: { // Descriptor String has been split and types added to Strings table
                *uncompressed_resource = 1;
                uncompressed_resource+=1;
                int descriptor_index = decompress_int(data);
                int indexes_length = decompress_int(data);
                u1* length_address = uncompressed_resource;
                uncompressed_resource+=2;
                int desc_length = 0;
                const char * desc_string = strings->get(descriptor_index);
                if(indexes_length > 0) {
                    u1* indexes_base = data;
                    data+=indexes_length;
                    char c = *desc_string;
                    do {
                        *uncompressed_resource = c;
                        uncompressed_resource++;
                        desc_length+=1;
                        if(c == 'L') {
                            int index = decompress_int(indexes_base);
                            const char * pkg = strings->get(index);
                            int str_length = (int) strlen(pkg);
                            if (str_length > 0) {
                                int len = str_length + 1;
                                char* fullpkg = NEW_C_HEAP_ARRAY(char, len, mtOther);
                                char* pkg_base = fullpkg;
                                memcpy(fullpkg, pkg, str_length);
                                fullpkg+=str_length;
                                *fullpkg = '/';
                                memcpy(uncompressed_resource, pkg_base, len);
                                uncompressed_resource += len;
                                FREE_C_HEAP_ARRAY(char, pkg_base);
                                desc_length+=len;
                            } else { // Empty package
                                // Nothing to do.
                            }
                            int classIndex = decompress_int(indexes_base);
                            const char * clazz = strings->get(classIndex);
                            int clazz_length = (int) strlen(clazz);
                            memcpy(uncompressed_resource, clazz, clazz_length);
                            uncompressed_resource += clazz_length;
                            desc_length+=clazz_length;
                        }
                        desc_string +=1;
                        c = *desc_string;
                    } while(c != '\0');
                } else {
                    desc_length = (int) strlen(desc_string);
                    memcpy(uncompressed_resource, desc_string, desc_length);
                    uncompressed_resource += desc_length;
                }
                Bytes::put_Java_u2(length_address, desc_length);
                break;
            }

            case CONSTANT_Utf8: { // UTF-8
                *uncompressed_resource = tag;
                uncompressed_resource += 1;
                u2 str_length = Bytes::get_Java_u2(data);
                int len = str_length + 2;
                memcpy(uncompressed_resource, data, len);
                uncompressed_resource += len;
                data += len;
                break;
            }

            case CONSTANT_Long:
            case CONSTANT_Double: {
                i++;
            }
            default: {
                *uncompressed_resource = tag;
                uncompressed_resource+=1;
                int size = SIZES[tag];
                memcpy(uncompressed_resource, data, size);
                uncompressed_resource+=size;
                data+=size;
            }
        }
    }
    int remain = header->_size - (data - data_base);
    u4 computed = uncompressed_resource - uncompressed_base + remain;
    if(header->_uncompressed_size != computed)
        printf("Failure, expecting %d but getting %d\n", header->_uncompressed_size,
                computed);
    guarantee(header->_uncompressed_size == computed,
            "Constant Pool reconstruction failed");
    memcpy(uncompressed_resource, data, remain);
}

int CPCDecompressor::decompress_int(unsigned char*& value) {
    int len = 4;
    int res = 0;
    char b1 = *value;
    if (is_compressed(b1)) { // compressed
        len = get_compressed_length(b1);
        char clearedValue = b1 &= 0x1F;
        if(len == 1) { // case should speed up...
            res = clearedValue;
        } else {
            res = (clearedValue&0xFF) << 8 * (len - 1);
            for(int i = 1; i < len; i++) {
                res|= (value[i]&0xFF) << 8 * (len - i -1);
            }
        }
    } else {
        res = (value[0] & 0xFF)<<24 | (value[1]&0xFF) << 16 |
              (value[2]&0xFF) << 8|(value[3]&0xFF);
    }
    value+= len;
    return res;
}
// END Constant Pool plugin
