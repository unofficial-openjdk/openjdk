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
 */

/*
 * @test
 * @bug 8058608
 * @summary JVM crash during Kerberos logins using des3-cbc-md5 on OSX
 * @compile -XDignore.symbol.file EncTypeTest.java
 * @run main/othervm EncTypeTest
 */

import java.io.File;
import java.io.FileOutputStream;
import sun.security.krb5.Config;
import sun.security.krb5.Credentials;
import sun.security.krb5.PrincipalName;
import sun.security.krb5.internal.ccache.CredentialsCache;

public class EncTypeTest {
    public static void main(String[] args) throws Exception {
        File f = new File("krb5-8058608.conf");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(
                "[libdefaults]\ndefault_tkt_enctypes = des3-cbc-md5".getBytes("UTF-8"));
        }
        f.deleteOnExit();
        System.setProperty("java.security.krb5.conf", "krb5-8058608.conf");
        Config.refresh();
        sun.security.krb5.Credentials.acquireTGTFromCache(null,null);

    }
}
