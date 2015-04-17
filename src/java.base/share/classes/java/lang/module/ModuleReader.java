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
import java.net.URL;
import java.nio.ByteBuffer;

/**
 * Locates or reads resources in a module artifact. A {@code ModuleReader} is
 * typically obtained by invoking the {@link ModuleArtifact#open() open} method
 * on a {@link ModuleArtifact ModuleArtifact}.
 *
 * @apiNote This API is currently a low level API suited for class loading.
 * The eventual API will likely define a method to locate a resource and
 * return a {@code Supplier<InputStream>}, leaving the low level API for
 * use by the JDK built-in class loaders.
 */

public interface ModuleReader extends Closeable {

    /**
     * Returns the URL for a resource in the module; {@code null} if not
     * found or the {@code ModuleReader} is closed.
     *
     * @see ClassLoader#findResource(String)
     */
    URL findResource(String name);

    /**
     * Reads a resource from the module. The element at the buffer's position
     * is the first byte of the resource, the element at the buffer's limit
     * is the last byte of the resource.
     *
     * <p> The {@code releaseBuffer} should be invoked after consuming the
     * contents of the buffer. This will ensure, for example, that direct
     * buffers are returned to a buffer pool. </p>
     *
     * @throws IOException if an I/O error occurs or the {@code ModuleReader} is
     * closed.
     */
    ByteBuffer readResource(String name) throws IOException;

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
    default void releaseBuffer(ByteBuffer bb) { }

    /**
     * Closes the module reader. Once closed then subsequent calls to locate or
     * read a resource will fail by returning {@code null} or throwing {@code
     * IOException}.
     *
     * A module reader is not required to be asynchronously closeable. If a thread
     * is reading a resource and another thread invokes the close method, then the
     * second thread may block until the read operation is complete.
     */
    @Override
    void close() throws IOException;

}