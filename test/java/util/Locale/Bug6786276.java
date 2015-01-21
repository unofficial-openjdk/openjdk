/*
 * Copyright 2015 Red Hat, Inc.
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
 */
/*
 * @test
 * @bug 6786276
 * @summary Verify that the ISO-3166 code for Serbia & Montenegro is still present
 */

import java.util.Arrays;
import java.util.Locale;

public class Bug6786276
{
    public static void main(String[] args)
    {
	String[] localeCodes = Locale.getISOCountries();
	System.err.println("Locale codes: " + Arrays.toString(localeCodes));
	int serbiaMontenegro = Arrays.binarySearch(localeCodes, "CS");
	if (serbiaMontenegro >= 0) {
	    System.out.println("Serbia & Montenegro ISO code present, index "
			       + serbiaMontenegro);
	} else {
	    System.out.println("Serbia & Montenegro ISO code not present");
	    throw new RuntimeException("Serbia & Montenegro ISO code not present.");
	}
    }
}
