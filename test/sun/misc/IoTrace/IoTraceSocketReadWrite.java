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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/*
 * @test
 * @bug 8003322
 * @run shell ioTraceTest.sh IoTraceSocketReadWrite
 */
public class IoTraceSocketReadWrite extends IoTraceBase {

    public void test() throws Exception {
        IoTraceAgent.setListener(this);

        ServerSocket ss = null;
        Socket s1 = null, s2 = null;
        OutputStream os = null;
        InputStream is = null;

        try {
            InetAddress iaddr = InetAddress.getLocalHost();
            ss = new ServerSocket(0);
            s1 = new Socket(iaddr, ss.getLocalPort());
            s2 = ss.accept();

            os = s1.getOutputStream();
            is = s2.getInputStream();

            os.write((byte) 11);
            is.read();

            expectSocket(1, 1, s2.getInetAddress(), s2.getPort(), 0);

        } finally {
            if (ss != null) {
                ss.close();
            }
            if (s1 != null) {
                s1.close();
            }
            if (s2 != null) {
                s2.close();
            }
        }
    }

    public static void main(String... args) throws Exception {
        IoTraceSocketReadWrite t = new IoTraceSocketReadWrite();
        t.test();
    }
}