/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
 * @bug 7195931 7197071
 * @summary UnsatisfiedLinkError on PKCS11.C_GetOperationState while
 *          using NSS from jre7u6+
 */
import java.net.*;
import java.io.*;
import java.security.*;

/**
 * When the Java specification version is incremented, all of the providers
 * must be recompiled with the proper implementation version to match.
 */
public class CheckManifestForRelease {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws Exception {
        checkP11MessageDigestClone();
        checkManifest();
    }

    /*
     * Check the root cause, no manifest values.
     */
    static private void checkManifest() throws Exception {
        System.out.println("=============");
        String specVersion = System.getProperty("java.specification.version");
        String extDirName = System.getProperty("java.home", ".") + "/"
                + "lib/ext";

        // Current list of JCE providers.  Add if more are created.
        String[] files = new String[]{
            "sunjce_provider.jar",
            "sunec.jar",
            "sunmscapi.jar",
            "sunpkcs11.jar",
            "ucrypto.jar"
        };

        System.out.println("Checking in " + extDirName);

        for (String file : files) {
            System.out.println("Checking: " + file);
            String urlString = "jar:file:" + extDirName + "/" + file + "!/";
            JarURLConnection urlc =
                    (JarURLConnection) (new URL(urlString).openConnection());

            String implVersion;
            try {
                /*
                 * If the file doesn't exist (e.g. mscapi on solaris),
                 * skip it. If there are other problems, fail out.
                 */
                implVersion = urlc.getManifest().getMainAttributes().getValue(
                        "Implementation-Version");
            } catch (FileNotFoundException e) {
                System.out.println("    " + file + " not found, skipping...");
                continue;
            }

            if (implVersion == null) {
                throw new Exception(
                        "Implementation-Version not found in Manifest");
            }

            if (!implVersion.startsWith(specVersion)) {
                throw new Exception(
                        "Implementation-Version does not match " +
                        "Specification-Version");
            }
        }
    }

    /*
     * Check the symptom, an UnsatisfiedLinkError
     */
    static private void checkP11MessageDigestClone() throws Exception {
        System.out.println("=============");
        System.out.println("Checking for UnsatisfiedLinkError");
        String os = System.getProperty("os.name");
        // Only run on Solaris
        if (!os.equals("SunOS")) {
            return;
        }

        /*
         * We have to do some gyrations here, since the code to exercise
         * this is in the P11 MessageDigests, and most of mechanisms are
         * disabled by default.
         */
        String customP11File =
                System.getProperty("TESTSRC", ".") + "/p11-solaris.txt";

        try {
            Provider provider =
                new sun.security.pkcs11.SunPKCS11(customP11File);

            MessageDigest md = MessageDigest.getInstance("SHA1", provider);
            md.update((byte) 0x01);
            System.out.println(md.getProvider());

            md.clone();
        } catch (Exception e) {
            // These kinds of failure is ok.  We're testing the
            // UnsatisfiedLinkError here.
            return;
        }
    }
}
