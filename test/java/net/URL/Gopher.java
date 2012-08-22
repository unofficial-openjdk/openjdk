/*
 * Copyright (c) 2012 Oracle and/or its affiliates. All rights reserved.
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
 * @bug 7189567
 * @summary java net obselete protocol
 * @run main Gopher
 * @run main/othervm -Djdk.net.registerGopherProtocol Gopher enabled
 * @run main/othervm -Djdk.net.registerGopherProtocol=false Gopher
 */

import java.net.MalformedURLException;
import java.net.URL;

public class Gopher {
    static final String GOPHER_PROP = "jdk.net.registerGopherProtocol";

    public static void main(String[] args) throws Exception {
        boolean expectEnabled = false;
        if (args.length >= 1 && args[0].equals("enabled"))
            expectEnabled = true;

        String prop = System.getProperty(GOPHER_PROP);
        boolean gopherEnabled = prop == null ? false :
                                   (prop.equalsIgnoreCase("false") ? false : true);

        // Validate system property reading
        if (expectEnabled && !gopherEnabled) {
            System.err.println(GOPHER_PROP + ": " + gopherEnabled);
            throw new RuntimeException(
                    "Failed: expected system property to be enabled, but it is not");
        }
        if (!expectEnabled && gopherEnabled) {
            System.err.println(GOPHER_PROP + ": " + gopherEnabled);
            throw new RuntimeException(
                    "Failed: expected system property to be disabled, but it is not");
        }

        try {
            new URL("gopher://anyhost:70/[anydata]");
            if (!gopherEnabled) {
                System.err.println(GOPHER_PROP + ": " + gopherEnabled);
                throw new RuntimeException("Failed: gopher should NOT be enabled");
            }
        } catch (MalformedURLException x) {
            if (gopherEnabled) {
                System.err.println(GOPHER_PROP + ": " + gopherEnabled);
                x.printStackTrace();
                throw new RuntimeException("Failed: gopher should be enabled");
            }
        }
    }
}
