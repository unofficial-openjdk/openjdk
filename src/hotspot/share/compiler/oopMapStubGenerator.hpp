/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_COMPILER_OOPMAPSTUBGENERATOR_HPP
#define SHARE_COMPILER_OOPMAPSTUBGENERATOR_HPP

#include "asm/assembler.hpp"
#include "memory/allocation.hpp"

class ImmutableOopMap;
class BufferBlob;

class OopMapStubGenerator {
  const ImmutableOopMap& _oopmap;
  BufferBlob* _blob;
  address _freeze_stub;
  address _thaw_stub;

public:
  OopMapStubGenerator(const ImmutableOopMap& oopmap) : _oopmap(oopmap), _blob(NULL), _freeze_stub(NULL), _thaw_stub(NULL) {}

  address freeze_stub() { return _freeze_stub; }
  address thaw_stub() { return _thaw_stub; }

  bool generate();
  void free();
};

#endif
