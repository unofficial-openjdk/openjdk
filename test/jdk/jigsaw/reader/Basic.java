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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Objects;

/**
 * Basic test for java.lang.module.ModuleReader. Usage:
 *
 * java Basic $MODULE $DIR $NAME $EXPECTED
 *
 *     $MODULE is the module name
 *     $DIR is the directory containing the module
 *     $NAME is the name of a resource in the module
 *     $EXPECTED is the file path to a copy of the resource on the file system
 */

public class Basic {

    // name of resource that will never be found
    static final String NOT_A_RESOURCE = "NotAResource";

    public static void main(String[] args) throws IOException {

        String module = args[0];
        Path dir = Paths.get(args[1]);
        String name = args[2];
        byte[] expectedBytes = Files.readAllBytes(Paths.get(args[3]));

        // find the module
        ModuleFinder finder = ModuleFinder.ofDirectories(dir);
        ModuleReference mref = finder.find(module);
        assertTrue(mref != null);

        // test the reader on this module
        test(mref, name, expectedBytes);
    }

    /**
     * Opens the given module reference and tests its ModuleReader.
     *
     * @param reference the module reference to open
     * @param name the name of a resource that exists in the module
     * @param bytes the bytes in the resource to ensure that the resource
     *              is correctly read
     */
    static void test(ModuleReference mref, String name, byte[] bytes)
        throws IOException
    {
        ModuleReader reader = mref.open();
        try (reader) {

            // test getResourceAsStream
            try (InputStream in = reader.getResourceAsStream(name)) {
                byte[] b = readAll(in);
                assertTrue(Arrays.equals(b, bytes));
            }

            // test getResourceAsBuffer
            ByteBuffer bb = reader.getResourceAsBuffer(name);
            try {
                int rem = bb.remaining();
                assertTrue(rem == bytes.length);
                byte[] b = new byte[rem];
                bb.get(b);
                assertTrue(Arrays.equals(b, bytes));
            } finally {
                reader.releaseBuffer(bb);
            }

            // test "not found"
            assertTrue(reader.getResourceAsStream(NOT_A_RESOURCE) == null);
            assertTrue(reader.getResourceAsBuffer(NOT_A_RESOURCE) == null);

            // test "null"
            try {
                reader.getResourceAsStream(null);
                assertTrue(false);
            } catch (NullPointerException expected) { }
            try {
                reader.getResourceAsBuffer(null);
                assertTrue(false);
            } catch (NullPointerException expected) { }
        }

        // test closed reader
        try {
            InputStream in = reader.getResourceAsStream(name);
            assertTrue(false);
        } catch (IOException expected) { }

        try {
            ByteBuffer bb = reader.getResourceAsBuffer(name);
            assertTrue(false);
        } catch (IOException expected) { }

    }

    /**
     * Return a byte array with all bytes read from the given input stream
     */
    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        in.transferTo(baos);
        return baos.toByteArray();
    }

    static void assertTrue(boolean e) {
        if (!e)
            throw new RuntimeException();

    }
    static void assertEquals(Object x, Object y) {
        if (!Objects.equals(x, y))
            throw new RuntimeException();
    }

}
