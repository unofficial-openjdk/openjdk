/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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

package sun.nio.ch;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Wraps a file descriptor to a TCP socket and provides methods to obtain input
 * and output streams to read/write from the socket.
 */

public class SocketStreams implements Closeable {
    private static final NativeDispatcher nd = new SocketDispatcher();

    private final Closeable parent;
    private final FileDescriptor fd;
    private final int fdVal;

    // Lock held when reading or writing
    private final ReentrantLock readLock = new ReentrantLock();
    private final ReentrantLock writeLock = new ReentrantLock();

    // The stateLock is needed when changing state
    private final Object stateLock = new Object();
    private static final int ST_CONNECTED = 0;
    private static final int ST_CLOSING = 1;
    private static final int ST_CLOSED = 2;
    private volatile int state;  // need stateLock to change

    // protected by stateLock
    private long readerThread;
    private long writerThread;

    // flags to indicate if the connection is shutdown for input and output
    private volatile boolean isInputClosed;
    private volatile boolean isOutputClosed;

    // the read timeout
    private volatile long readTimeoutNanos;

    /**
     * Creates a SocketStreams to wrap the given file description.
     */
    public SocketStreams(Closeable parent, FileDescriptor fd) throws IOException {
        this.parent = Objects.requireNonNull(parent);
        this.fd = fd;
        this.fdVal = IOUtil.fdVal(fd);
        IOUtil.configureBlocking(fd, false);
    }

    /**
     * Returns true if the socket is open.
     */
    private boolean isOpen() {
        return state == ST_CONNECTED;
    }

    /**
     * Closes a SocketException if the socket is not open.
     */
    private void ensureOpen() throws SocketException {
        if (state > ST_CONNECTED)
            throw new SocketException("socket closed");
    }

    /**
     * Disables the current thread or fiber for scheduling purposes until this
     * socket is ready for I/O, or asynchronously closed, for up to the
     * specified waiting time, unless the permit is available.
     */
    private void park(int event, long nanos) throws IOException {
        Strand s = Strand.currentStrand();
        if (PollerProvider.available() && (s instanceof Fiber)) {
            Poller.startPoll(fdVal, event);
            if (isOpen()) {
                if (nanos == 0) {
                    Fiber.park();
                } else {
                    Fiber.parkNanos(nanos);
                }
            }
        } else {
            long millis;
            if (nanos == 0) {
                millis = -1;
            } else {
                millis = MILLISECONDS.convert(nanos, NANOSECONDS);
            }
            Net.poll(fd, event, millis);
        }
    }

    /**
     * Marks the beginning of a read operation that might block.
     *
     * @throws SocketException if the socket is closed
     */
    private void beginRead() throws SocketException {
        synchronized (stateLock) {
            ensureOpen();
            readerThread = NativeThread.current();
        }
    }

    /**
     * Marks the end of a read operation that may have blocked.
     *
     * @throws SocketException is the socket is closed
     */
    private void endRead(boolean completed) throws SocketException {
        synchronized (stateLock) {
            readerThread = 0;
            int state = this.state;
            if (state == ST_CLOSING)
                stateLock.notifyAll();
            if (!completed && state > ST_CONNECTED)
                throw new SocketException("socket closed");
        }
    }

    /**
     * Marks the beginning of a write operation that might block.
     *
     * @throws SocketException if the socket is closed
     */
    private void beginWrite() throws SocketException {
        synchronized (stateLock) {
            ensureOpen();
            writerThread = NativeThread.current();
        }
    }

    /**
     * Marks the end of a write operation that may have blocked.
     *
     * @throws SocketException is the socket is closed
     */
    private void endWrite(boolean completed) throws SocketException {
        synchronized (stateLock) {
            writerThread = 0;
            int state = this.state;
            if (state == ST_CLOSING)
                stateLock.notifyAll();
            if (!completed && state > ST_CONNECTED)
                throw new SocketException("socket closed");
        }
    }

    /**
     * Reads bytes from the socket into the given buffer.
     *
     * @throws IOException if the socket is closed or an I/O occurs
     * @throws SocketTimeoutException if the read timeout elapses
     */
    private int read(ByteBuffer dst) throws IOException {
        readLock.lock();
        try {
            int n = 0;
            beginRead();
            try {
                if (isInputClosed)
                    return IOStatus.EOF;
                n = IOUtil.read(fd, dst, -1, nd);
                if (n == IOStatus.UNAVAILABLE && isOpen()) {
                    long nanos = readTimeoutNanos;
                    if (nanos > 0) {
                        // read with timeout
                        do {
                            long startTime = System.nanoTime();
                            park(Net.POLLIN, nanos);
                            n = IOUtil.read(fd, dst, -1, nd);
                            if (n == IOStatus.UNAVAILABLE) {
                                nanos -= System.nanoTime() - startTime;
                                if (nanos <= 0)
                                    throw new SocketTimeoutException();
                            }
                        } while (n == IOStatus.UNAVAILABLE && isOpen());
                    } else {
                        // read, no timeout
                        do {
                            park(Net.POLLIN, 0);
                            n = IOUtil.read(fd, dst, -1, nd);
                        } while (n == IOStatus.UNAVAILABLE && isOpen());
                    }
                }
                return n;
            } finally {
                endRead(n > 0);
            }
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Writes a sequence of bytes to this socket from the given buffer.
     *
     * @throws IOException if the socket is closed or an I/O occurs
     */
    private int write(ByteBuffer dst) throws IOException {
        writeLock.lock();
        try {
            int n = 0;
            beginWrite();
            try {
                n = IOUtil.write(fd, dst, -1, nd);
                if (n == IOStatus.UNAVAILABLE && isOpen()) {
                    do {
                        park(Net.POLLOUT, 0);
                        n = IOUtil.write(fd, dst, -1, nd);
                    } while (n == IOStatus.UNAVAILABLE && isOpen());
                }
                return n;
            } finally {
                endWrite(n > 0);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Returns the number of bytes in the socket buffer waiting to be read
     */
    private int available() throws IOException {
        readLock.lock();
        try {
            ensureOpen();
            if (isInputClosed) {
                return 0;
            } else {
                return Net.available(fd);
            }
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Closes the socket.
     *
     * This method waits for outstanding read/write operations to complete. If
     * a thread is blocked reading or writing then the socket is pre-closed and
     * the threads signalled to ensure that the outstanding I/O operations
     * complete quickly.
     */
    @Override
    public void close() throws IOException {
        boolean interrupted = false;

        synchronized (stateLock) {
            if (state > ST_CONNECTED)
                return;
            state = ST_CLOSING;

            // unpark and wait for fibers to complete I/O operations
            if (NativeThread.isFiber(readerThread) ||
                    NativeThread.isFiber(writerThread)) {
                Poller.stopPoll(fdVal);

                while (NativeThread.isFiber(readerThread) ||
                        NativeThread.isFiber(writerThread)) {
                    try {
                        stateLock.wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }

            // interrupt and wait for kernel threads to complete I/O operations
            long reader = readerThread;
            long writer = writerThread;
            if (NativeThread.isKernelThread(reader) ||
                    NativeThread.isKernelThread(writer)) {
                nd.preClose(fd);

                if (NativeThread.isKernelThread(reader))
                    NativeThread.signal(reader);
                if (NativeThread.isKernelThread(writer))
                    NativeThread.signal(writer);

                // wait for blocking I/O operations to end
                while (NativeThread.isKernelThread(readerThread) ||
                        NativeThread.isKernelThread(writerThread)) {
                    try {
                        stateLock.wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }

            state = ST_CLOSED;
            nd.close(fd);
        }

        // restore interrupt status
        if (interrupted)
            Thread.currentThread().interrupt();
    }

    /**
     * Returns an InputStream to read from the socket.
     */
    public InputStream inputStream() {
        return new InputStream() {
            private volatile boolean eof;
            @Override
            public int read() throws IOException {
                byte[] a = new byte[1];
                int n = read(a, 0, 1);
                return (n > 0) ? (a[0] & 0xff) : -1;
            }
            @Override
            public int read(byte b[], int off, int len) throws IOException {
                Objects.checkFromIndexSize(off, len, b.length);
                if (eof) {
                    return -1; // legacy SocketInputStream behavior
                } else if (len == 0) {
                    return 0;
                } else {
                    ByteBuffer dst = ByteBuffer.wrap(b, off, len);
                    int n = SocketStreams.this.read(dst);
                    if (n == -1)
                        eof = true;
                    return n;
                }
            }
            @Override
            public int available() throws IOException {
                return SocketStreams.this.available();
            }
            @Override
            public void close() throws IOException {
                parent.close();
            }
        };
    }

    /**
     * Returns an OutputStream to write to the socket.
     */
    public OutputStream outputStream() {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                byte[] a = new byte[] { (byte) b };
                write(a, 0, 1);
            }
            @Override
            public void write(byte b[], int off, int len) throws IOException {
                Objects.checkFromIndexSize(off, len, b.length);
                if (len > 0) {
                    ByteBuffer src = ByteBuffer.wrap(b, off, len);
                    while (src.hasRemaining()) {
                        SocketStreams.this.write(src);
                    }
                }
            }
            @Override
            public void close() throws IOException {
                parent.close();
            }
        };
    }

    /**
     * Shutdown the connection for reading without closing the socket.
     */
    public SocketStreams shutdownInput() throws IOException {
        synchronized (stateLock) {
            ensureOpen();
            if (!isInputClosed) {
                Net.shutdown(fd, Net.SHUT_RD);
                long reader = readerThread;
                if (NativeThread.isFiber(reader)) {
                    Poller.stopPoll(fdVal, Net.POLLIN);
                } else if (NativeThread.isKernelThread(reader)) {
                    NativeThread.signal(reader);
                }
                isInputClosed = true;
            }
        }
        return this;
    }

    /**
     * Shutdown the connection for writing without closing the socket.
     */
    public SocketStreams shutdownOutput() throws IOException {
        synchronized (stateLock) {
            ensureOpen();
            if (!isOutputClosed) {
                Net.shutdown(fd, Net.SHUT_WR);
                long writer = writerThread;
                if (NativeThread.isFiber(writer)) {
                    Poller.stopPoll(fdVal, Net.POLLOUT);
                } else if (NativeThread.isKernelThread(writer)) {
                    NativeThread.signal(writer);
                }
                isOutputClosed = true;
            }
            return this;
        }
    }

    /**
     * Sets the read timeout in millis.
     */
    public SocketStreams readTimeout(long millis) {
        readTimeoutNanos = NANOSECONDS.convert(millis, MILLISECONDS);
        return this;
    }
}
