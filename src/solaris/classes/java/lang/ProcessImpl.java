/*
 * Copyright (c) 2003, 2007, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package java.lang;

import java.io.IOException;
import java.lang.Process;

/**
 * This class is for the exclusive use of ProcessBuilder.start() to
 * create new processes.
 *
 * @author Martin Buchholz
 * @since   1.5
 */
final class ProcessImpl {
    private ProcessImpl() {}    // Not instantiable

    private static byte[] toCString(String s) {
        if (s == null)
            return null;
        byte[] bytes = s.getBytes();
        byte[] result = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0,
                         result, 0,
                         bytes.length);
        result[result.length-1] = (byte)0;
        return result;
    }

    // Only for use by ProcessBuilder.start()
    static Process start(String[] cmdarray,
                         java.util.Map<String,String> environment,
                         String dir,
                         boolean redirectErrorStream)
        throws IOException
    {
        assert cmdarray != null && cmdarray.length > 0;

        // Convert arguments to a contiguous block; it's easier to do
        // memory management in Java than in C.
        byte[][] args = new byte[cmdarray.length-1][];
        int size = args.length; // For added NUL bytes
        for (int i = 0; i < args.length; i++) {
            args[i] = cmdarray[i+1].getBytes();
            size += args[i].length;
        }
        byte[] argBlock = new byte[size];
        int i = 0;
        for (byte[] arg : args) {
            System.arraycopy(arg, 0, argBlock, i, arg.length);
            i += arg.length + 1;
            // No need to write NUL bytes explicitly
        }

        int[] envc = new int[1];
        byte[] envBlock = ProcessEnvironment.toEnvironmentBlock(environment, envc);

        return new UNIXProcess
            (toCString(cmdarray[0]),
             argBlock, args.length,
             envBlock, envc[0],
             toCString(dir),
             redirectErrorStream);
    }
}
