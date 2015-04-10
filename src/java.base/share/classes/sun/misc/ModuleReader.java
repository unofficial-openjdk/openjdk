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

package sun.misc;

import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;

/**
 * A reader of resources in a module artifact.
 *
 * <p> A {@code ModuleReader} is used to locate or read resources in a
 * module artifact. The {@link ModuleReaders} class defines a factory
 * method to create a {@code ModuleReader} for modules packaged as a jmod,
 * modular JAR, or exploded on the file system. </p>
 *
 * @apiNote This API is currently a low level API suited for class loading.
 * The eventual API will likely define a method to locate a resource and
 * return a {@code Supplier<InputStream>}, leaving the low level API for
 * use by the JDK built-in class loaders.
 */

interface ModuleReader extends Closeable {
    /**
     * Returns the URL for a resource in the module, {@code null} if not
     * found.
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
     * @throws IOException if an I/O error occurs
     */
    ByteBuffer readResource(String name) throws IOException;

    /**
     * Returns a byte buffer to the buffer pool. This method should be
     * invoked after consuming the contents of the buffer returned by
     * the {@code readResource} method.
     *
     * @implSpec The default implementation does nothing.
     */
    default void releaseBuffer(ByteBuffer bb) { }
}