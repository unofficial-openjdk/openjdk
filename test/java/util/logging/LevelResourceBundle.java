/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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

import java.util.*;
import java.util.logging.*;

/*
 * @test
 * @bug 8020228
 * @summary test logging.properties localized
 * @run main LevelResourceBundle
 */

public class LevelResourceBundle {
    public static void main(String args[]) throws Exception {
        final String name = "SEVERE";
        String en = getLocalizedMessage(Locale.getDefault(), name);
        String fr = getLocalizedMessage(Locale.FRANCE, name);
        if (!name.equals(en)) {
             throw new RuntimeException("Expect " + name + " equals " + en);
        }
        if (name.equals(fr)) {
             throw new RuntimeException("Expect " + name + " not equals " + fr);
        }
    }

    private static final String RBNAME = "sun.util.logging.resources.logging";
    private static String getLocalizedMessage(Locale locale, String key) {
        ResourceBundle rb = ResourceBundle.getBundle(RBNAME, locale);
        return rb.getString(key);
    }
}
