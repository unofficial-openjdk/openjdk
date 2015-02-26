/*
 * Copyright (c) 2010, 2015, Oracle and/or its affiliates. All rights reserved.
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
/**
 * @test
 * @library ..
 * @bug 6581254 6986789 7196009 8062170
 * @summary Allow '~', '+', and quoted paths in config file
 * @author Valerie Peng
 */

import java.security.*;
import java.io.*;

public class ConfigShortPath {

    private static final String[] configNames = {
        "csp.cfg", "cspPlus.cfg", "cspSpace.cfg", "cspQuotedPath.cfg"
    };

    public static void main(String[] args) throws Exception {
        Provider p = PKCS11Test.getSunPKCS11();
        if (p == null) {
            System.out.println("Skipping test - no PKCS11 provider available");
            return;
        }
        String testSrc = System.getProperty("test.src", ".");
        for (int i = 0; i < configNames.length; i++) {
            String configFile = testSrc + File.separator + configNames[i];

            System.out.println("Testing against " + configFile);
            try {
                Provider pkcs11 = p.configure(configFile);
            } catch (ProviderException pe) {
                System.out.println(pe);
                String causeMsg = pe.getMessage();
                // Indicate failure if due to parsing config
                if (causeMsg.indexOf("Unexpected") != -1) {
                    throw pe;
                }
                // Consider the test passes if the exception is
                // thrown after parsing, i.e. due to the absolute
                // path requirement or the non-existent path.
            } catch (Exception ex) {
                // unexpected exception
                throw new RuntimeException("Unexpected Exception", ex);
            }
        }
    }
}
