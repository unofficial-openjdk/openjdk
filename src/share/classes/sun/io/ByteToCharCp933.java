/*
 * Copyright (c) 1997, 2003, Oracle and/or its affiliates. All rights reserved.
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

import sun.nio.cs.ext.IBM933;

/**
* Tables and data to convert Cp933 to Unicode.
*
* @author Malcolm Ayres, assisted by UniMap program
*/
public class ByteToCharCp933
        extends ByteToCharDBCS_EBCDIC

{
        private final static IBM933 nioCoder = new IBM933();

        // Return the character set id
        public String getCharacterEncoding()
        {
                return "Cp933";
        }


        public ByteToCharCp933() {
                super();
                super.mask1 = 0xFFF0;
                super.mask2 = 0x000F;
                super.shift = 4;
                super.singleByteToChar = nioCoder.getDecoderSingleByteMappings();
                super.index1 = nioCoder.getDecoderIndex1();
                super.index2 = nioCoder.getDecoderIndex2();
        }
}
