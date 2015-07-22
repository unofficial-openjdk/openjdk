/*
 * Copyright (c) 1997, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
 */

package sun.io;

import sun.io.*;

public class CharToByteCp942C extends CharToByteDBCS_ASCII {
   protected static final int mask1;
   protected static final int mask2;
   protected static final int shift;
   protected static final short index1[];
   protected static final String index2;
   protected static final String index2a;

   static {
      CharToByteDBCS_ASCII y = new CharToByteCp942();
      mask1 = y.mask1;
      mask2 = y.mask2;
      shift = y.shift;
      index2 = y.index2;

      /* Fix converter to pass through U+0000 to U+007F unchanged to 0x00 to 0x7f */
      String indexs = "";
      for (char c = '\0'; c < '\u0080'; ++c) indexs += c;
      index2a = y.index2a + indexs;

      int o = y.index2a.length() + 15000;
      index1 = new short[y.index1.length];
      System.arraycopy(y.index1, 0, index1, 0, y.index1.length);
      for (int i = 0; i * (1<<shift) < 128; ++i) {
         index1[i] = (short)(o + i * (1<<shift));
      }
   }

   public String getCharacterEncoding() {
      return "Cp942C";
   }

   CharToByteCp942C() {
      super();
      super.mask1 = mask1;
      super.mask2 = mask2;
      super.shift = shift;
      super.index1 = index1;
      super.index2 = index2;
      super.index2a = index2a;
   }
}
