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

import java.net.InetAddress;

import sun.misc.IoTrace;

/**
 * Implementations of this interface can be registered with
 * {@link IoTrace#setListener(IoTraceListener)} to receive callbacks when file
 * and socket operations are performed.
 * <p>
 * The xxBegin() methods return a "context". This can be any Object. This
 * context will be passed to the corresponding xxEnd() method. This way, an
 * implementation can correlate the beginning of an operation with the end.
 * <p>
 * It is possible for a xxEnd() method to be called with a null handle. This
 * happens if tracing was started between the call to xxBegin() and xxEnd(), in
 * which case xxBegin() would not have been called. It is the implementation's
 * responsibility to not throw an exception in this case.
 * <p>
 * Implementations should never throw exceptions since this will cause
 * disruptions to the I/O operations.
 * <p>
 * An xxBegin() call is not guaranteed to be followed by an xxEnd() call, since
 * the listener in IoTrace can be reset at any time.
 */
public interface IoTraceListener {

    /**
     * Called before data is read from a socket.
     *
     * @param address
     *            the remote address the socket is bound to
     * @param port
     *            the remote port the socket is bound to
     * @param timeout
     *            the SO_TIMEOUT value of the socket (in milliseconds) or 0 if
     *            there is no timeout set
     * @return a context object
     */
    public Object socketReadBegin(InetAddress address, int port, int timeout);

    /**
     * Called after data is read from the socket.
     *
     * @param context
     *            the context returned by the previous call to socketReadBegin()
     * @param bytesRead
     *            the number of bytes read from the socket, 0 if there was an
     *            error reading from the socket
     */
    public void socketReadEnd(Object context, long bytesRead);

    /**
     * Called before data is written to a socket.
     *
     * @param address
     *            the remote address the socket is bound to
     * @param port
     *            the remote port the socket is bound to
     * @return a context object
     */
    public Object socketWriteBegin(InetAddress address, int port);

    /**
     * Called after data is written to a socket.
     *
     * @param context
     *            the context returned by the previous call to
     *            socketWriteBegin()
     * @param bytesWritten
     *            the number of bytes written to the socket, 0 if there was an
     *            error writing to the socket
     */
    public void socketWriteEnd(Object context, long bytesWritten);

    /**
     * Called before data is read from a file.
     *
     * @param path
     *            the path of the file
     * @return a context object
     */
    public Object fileReadBegin(String path);

    /**
     * Called after data is read from a file.
     *
     * @param context
     *            the context returned by the previous call to fileReadBegin()
     * @param bytesRead
     *            the number of bytes written to the file, 0 if there was an
     *            error writing to the file
     */
    public void fileReadEnd(Object context, long bytesRead);

    /**
     * Called before data is written to a file.
     *
     * @param path
     *            the path of the file
     * @return a context object
     */
    public Object fileWriteBegin(String path);

    /**
     * Called after data is written to a file.
     *
     * @param context
     *            the context returned by the previous call to fileReadBegin()
     * @param bytesWritten
     *            the number of bytes written to the file, 0 if there was an
     *            error writing to the file
     */
    public void fileWriteEnd(Object context, long bytesWritten);
}
