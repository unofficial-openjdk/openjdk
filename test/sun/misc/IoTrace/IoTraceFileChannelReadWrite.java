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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

/*
 * @test
 * @bug 8003322
 * @run shell ioTraceTest.sh IoTraceFileChannelReadWrite
 */
public class IoTraceFileChannelReadWrite extends IoTraceBase {

    private void testWrite(File f) throws IOException, FileNotFoundException,
            Exception {
        try (FileChannel fc = FileChannel.open(f.toPath(),
                StandardOpenOption.WRITE)) {
            ByteBuffer bb = ByteBuffer.allocate(1);
            bb.put((byte) 11);
            bb.flip();
            fc.write(bb);
        }
        expectFile(1, 0, f);
    }

    private void testRead(File f) throws IOException, FileNotFoundException,
            Exception {
        try (FileChannel fc = FileChannel.open(f.toPath(),
                StandardOpenOption.READ)) {
            ByteBuffer bb = ByteBuffer.allocate(1);
            fc.read(bb);
        }
        expectFile(0, 1, f);
    }

    public void test() throws Exception {
        IoTraceAgent.setListener(this);
        File f = File.createTempFile("IoTraceFileChannelReadWrite", ".bin");
        try {
            clear();
            testWrite(f);
            clear();
            testRead(f);
        } finally {
            f.delete();
        }
    }

    public static void main(String... args) throws Exception {
        IoTraceFileChannelReadWrite t = new IoTraceFileChannelReadWrite();
        t.test();
    }
}
