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
 * @bug 7195931 7197071 7198146
 * @summary UnsatisfiedLinkError on PKCS11.C_GetOperationState while
 *          using NSS from jre7u6+
 */
import java.net.*;
import java.io.*;
import java.security.*;
import java.lang.reflect.*;

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
        checkFileManifests();
    }

    /*
     * Iterate over the files of interest: JCE framework and providers
     */
    static private void checkFileManifests() throws Exception {
        System.out.println("=============");
        String libDirName = System.getProperty("java.home", ".") + "/lib";
        String extDirName = libDirName + "/ext";

        System.out.println("Checking Manifest in directory: \n    " +
            extDirName);

        /*
         * Current list of JCE providers, all of which currently live in
         * the extensions directory.  Add if more are created.
         */
        String[] providers = new String[]{
            "sunjce_provider.jar",
            "sunec.jar",
            "sunmscapi.jar",
            "sunpkcs11.jar",
            "ucrypto.jar"
        };

        checkManifest(libDirName, "jce.jar");
        for (String provider : providers) {
            checkManifest(extDirName, provider);
        }
        System.out.println("Passed.");
    }

    // Helper method to format the URL properly.
    static private String formatURL(String dir, String file) {
        return "jar:file:///" + dir + "/" + file + "!/";
    }

    static private String specVersion =
        System.getProperty("java.specification.version");

    /*
     * Test the root cause, which is that there were no manifest values
     * for many of the providers, and for those that had them, there was
     * no test to make sure that the impl version was appropriate for
     * the spec version.
     */
    static private void checkManifest(String dir, String file)
            throws Exception {

        System.out.println("Checking: " + file);

        String url = formatURL(dir, file);
        JarURLConnection urlc =
            (JarURLConnection) (new URL(url).openConnection());

        String implVersion;
        try {
            implVersion = urlc.getManifest().getMainAttributes().getValue(
                "Implementation-Version");
        } catch (FileNotFoundException e) {
            /*
             * If the file doesn't exist (e.g. mscapi on solaris),
             * skip it. If there are other problems, fail out.
             */
            System.out.println("    " + file + " not found, skipping...");
            return;
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

    /*
     * Workaround for unfortunately generified forName() API
     */
    @SuppressWarnings("unchecked")
    static private Class<Provider> getProviderClass(String name)
            throws Exception {
        return (Class<Provider>)Class.forName(name);
    }

    /*
     * Check the symptom, an UnsatisfiedLinkError in MessageDigests.
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
         * this is in the P11 MessageDigests, and most of those mechanisms
         * are disabled by default.
         */
        String customP11File =
            System.getProperty("TESTSRC", ".") + "/p11-solaris.txt";

        /*
         * In 7u, we don't have a 64 PKCS11 windows build yet, so we
         * have to do some dynamic checking to determine if there is
         * a PKCS11 library available to test against.  Otherwise, the
         * windows 64 bit will throw a compilation error before the
         * test is even run.
         */
        Constructor<Provider> cons;
        Provider provider;
        try {
            Class<Provider> clazz =
                getProviderClass("sun.security.pkcs11.SunPKCS11");
            cons = clazz.getConstructor(new Class[]{String.class});
            provider = cons.newInstance(new Object[]{customP11File});
        } catch (Exception ex) {
            System.out.println("Skipping test - no PKCS11 provider available");
            return;
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA1", provider);
            md.update((byte) 0x01);
            System.out.println(md.getProvider());
            md.clone();
        } catch (Exception e) {
            // These kinds of failure are ok.  We're testing the
            // UnsatisfiedLinkError here.
        }
        System.out.println("Passed.");
    }
}
