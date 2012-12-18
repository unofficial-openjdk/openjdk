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

import java.io.File;
import java.net.InetAddress;


public class IoTraceBase implements IoTraceListener {

    protected static final Object my_context = new Object() {
    };

    private String path;
    private long bytesRead;
    private long bytesWritten;
    private Object context;
    private InetAddress address;
    private int port;
    private int timeout;

    protected void clear() {
        context = null;
        bytesRead = 0;
        bytesWritten = 0;
        address = null;
        port = 0;
        timeout = 0;
        path = null;
    }

    @Override
    public Object fileWriteBegin(String p) {
        path = p;
        return my_context;
    }

    @Override
    public void fileWriteEnd(Object ctx, long bw) {
        context = ctx;
        bytesWritten = bw;
    }

    @Override
    public Object fileReadBegin(String p) {
        path = p;
        return my_context;
    }

    @Override
    public void fileReadEnd(Object ctx, long br) {
        context = ctx;
        bytesRead = br;
    }

    @Override
    public Object socketReadBegin(InetAddress address, int port,
            int timeout) {
        this.address = address;
        this.port = port;
        this.timeout = timeout;
        return my_context;
    }

    @Override
    public void socketReadEnd(Object context, long bytesRead) {
        this.context = context;
        this.bytesRead = bytesRead;
    }

    @Override
    public Object socketWriteBegin(InetAddress address, int port) {
        this.address = address;
        this.port = port;
        return my_context;
    }

    @Override
    public void socketWriteEnd(Object context, long bytesWritten) {
        this.context = context;
        this.bytesWritten = bytesWritten;
    }

    protected void expectFileRead(long br, File f) throws Exception {
        expectFile(0, br, f);
    }

    protected void expectFileWrite(long bw, File f) throws Exception {
        expectFile(bw, 0, f);
    }

    protected void expectFile(long bw, long br, File f) throws Exception {
        if (context != my_context) {
            throw new Exception("Wrong context: " + context);
        }
        if (bytesWritten != bw) {
            throw new Exception("Expected " + bw + " byte to be read, got: "
                    + bytesWritten);
        }
        if (bytesRead != br) {
            throw new Exception("Expected " + br + " byte to be read, got: "
                    + bytesWritten);
        }
        if (!path.equals(f.getPath())) {
            throw new Exception("Incorrect path: " + path + ". Expected: "
                    + f.getPath());
        }
    }

    protected void expectSocket(int br, int bw, InetAddress ia, int p, int t)
            throws Exception {
        if (context != my_context) {
            throw new Exception("Wrong context: " + context);
        }
        if (bytesWritten != bw) {
            throw new Exception("Expected " + bw + " byte to be written, got: "
                    + bytesWritten);
        }
        if (bytesRead != br) {
            throw new Exception("Expected " + br + " byte to be read, got: "
                    + bytesWritten);
        }
        if (!address.equals(ia)) {
            throw new Exception("Incorrect address: " + address
                    + ". Expected: " + ia);
        }
        if (port != p) {
            throw new Exception("Expected " + p + " port, got: " + port);
        }
        if (timeout != t) {
            throw new Exception("Expected " + t + " timeout, got: " + timeout);
        }
    }
}