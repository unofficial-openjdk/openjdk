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

/**
 * @test
 * @bug 8003948
 * @run main HTest
 */
import java.io.*;
import sun.net.www.MessageHeader;

public class HTest {
    public static void main (String[] args) throws Exception {
        String prefix = System.getProperty("test.src");
        System.out.println ("TEST.SRC = " + prefix);
        for (int i=0; i<7; i++) {
            File f = new File(prefix, Integer.toString(i));
            FileInputStream fis = new FileInputStream(f);
            MessageHeader h = new MessageHeader(fis);
            String before = h.toString();
            before = before.substring(before.indexOf('{'));
            System.out.println ("Before");
            System.out.println (before);
            boolean result = h.filterNTLMResponses("WWW-Authenticate");
            String after = h.toString();
            after = after.substring(after.indexOf('{'));
            System.out.println ("After");
            System.out.println (after);
            System.out.println ("Expected");
            System.out.println (expected[i]);
            if (!expected[i].equals(after)) {
                throw new RuntimeException(Integer.toString(i) + " expected != after");
            }
            if (result != expectedResult[i]) {
                throw new RuntimeException(Integer.toString(i) + " result != expectedResult");
            }
        }
    }

    static String expected[] = {
        "{null: HTTP/1.1 200 Ok}{Foo: bar}{Bar: foo}{WWW-Authenticate: NTLM sdsds}",
        "{null: HTTP/1.1 200 Ok}{Foo: bar}{Bar: foo}{WWW-Authenticate: }",
        "{null: HTTP/1.1 200 Ok}{Foo: bar}{Bar: foo}{WWW-Authenticate: NTLM sdsds}",
        "{null: HTTP/1.1 200 Ok}{Foo: bar}{Bar: foo}{WWW-Authenticate: NTLM sdsds}",
        "{null: HTTP/1.1 200 Ok}{Foo: bar}{Bar: foo}{WWW-Authenticate: NTLM sdsds}{Bar: foo}",
        "{null: HTTP/1.1 200 Ok}{WWW-Authenticate: Negotiate}{Foo: bar}{Bar: foo}{WWW-Authenticate: NTLM}{Bar: foo}{WWW-Authenticate: Kerberos}",
        "{null: HTTP/1.1 200 Ok}{Foo: foo}{Bar: }{WWW-Authenticate: NTLM blob}{Bar: foo blob}"
    };

    static boolean[] expectedResult = {
        false, false, true, true, true, false, false
    };
}
