/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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

package java.lang.module;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;


/**
 * Reads resources from a module.
 *
 * <p> A module reader is intended for cases where access to the resources in a
 * module are required, regardless of whether the module has been instantiated.
 * A framework that scans a collection of packaged modules on the file system,
 * for example, may use a module reader to access a specific resource in each
 * module.
 *
 * <p> A module reader is also intended to be used by {@code ClassLoader}
 * implementations that load classes and resources from modules.
 *
 * <p> A {@code ModuleReader} is {@linkplain ModuleReference#open open} upon
 * creation and is closed by invoking the {@link #close close} method.  Failure
 * to close a module reader may result in a resource leak.  The {@code
 * try-with-resources} statement provides a useful construct to ensure that
 * module readers are closed.
 *
 * @see ModuleReference
 * @since 1.9
 */

public interface ModuleReader extends Closeable {

    /**
     * Returns an input stream for reading the resource.
     *
     * @throws IOException
     *         If an I/O error occurs or the module reader is closed
     * @throws SecurityException
     *         If denied by the security manager
     *
     * @see java.lang.reflect.Module#getResourceAsStream(String)
     */
    Optional<InputStream> open(String name) throws IOException;

    /**
     * Returns a byte buffer with the contents of a resource.  The element at
     * the returned buffer's position is the first byte of the resource, the
     * element at the buffer's limit is the last byte of the resource.
     *
     * <p> The {@code release} method should be invoked after consuming the
     * contents of the buffer. This will ensure, for example, that direct
     * buffers are returned to a buffer pool in implementations that use a
     * pool of direct buffers.
     *
     * @apiNote This method is intended for high-performance class loading. It
     * is not capable (or intended) to read arbitrary large resources that
     * could potentially be 2GB or larger.
     *
     * @implSpec The default implementation invokes the {@link #open(String)
     * open} method and reads all bytes from the input stream into a byte
     * buffer.
     *
     * @throws IOException
     *         If an I/O error occurs or the module reader is closed
     * @throws SecurityException
     *         If denied by the security manager
     *
     * @see ClassLoader#defineClass(String, ByteBuffer, java.security.ProtectionDomain)
     */
    default Optional<ByteBuffer> read(String name) throws IOException {
        final int BUFFER_SIZE = 8192;
        final int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 8;

        InputStream in = open(name).orElse(null);
        if (in == null) {
            // not found
            return Optional.empty();
        }

        try (in) {
            int capacity = in.available();
            if (capacity == 0)
                capacity = BUFFER_SIZE;

            byte[] buf = new byte[capacity];
            int nread = 0;
            int n;
            for (;;) {
                // read to EOF
                while ((n = in.read(buf, nread, capacity - nread)) > 0)
                    nread += n;

                // if last call to source.read() returned -1, we are done
                // otherwise, try to read one more byte; if that failed we're done too
                if (n < 0 || (n = in.read()) < 0)
                    break;

                // one more byte was read; need to allocate a larger buffer
                if (capacity <= MAX_BUFFER_SIZE - capacity) {
                    capacity = Math.max(capacity << 1, BUFFER_SIZE);
                } else {
                    if (capacity == MAX_BUFFER_SIZE)
                        throw new OutOfMemoryError("Required array size too large");
                    capacity = MAX_BUFFER_SIZE;
                }
                buf = Arrays.copyOf(buf, capacity);
                buf[nread++] = (byte) n;
            }

            return Optional.of(ByteBuffer.wrap(buf, 0, nread));
        }
    }

    /**
     * Returns a byte buffer to the buffer pool. This method should be
     * invoked after consuming the contents of the buffer returned by
     * the {@code readResource} method. The behavior of this method when
     * invoked to release a buffer that has already been released, or
     * the behavior when invoked to release a buffer after a {@code
     * ModuleReader} is closed is implementation specific and therefore
     * not specified.
     *
     * @implSpec The default implementation does nothing.
     */
    default void release(ByteBuffer bb) { }

    /**
     * Closes the module reader. Once closed then subsequent calls to locate or
     * read a resource will fail by returning {@code null} or throwing {@code
     * IOException}.
     *
     * <p> A module reader is not required to be asynchronously closeable. If a
     * thread is reading a resource and another thread invokes the close method,
     * then the second thread may block until the read operation is complete.
     *
     * <p> The behavior of {@code InputStream}s obtained using the {@link
     * #open(String) open} method and used after the module reader is closed
     * is implementation specific and therefore not specified.
     */
    @Override
    void close() throws IOException;

}
