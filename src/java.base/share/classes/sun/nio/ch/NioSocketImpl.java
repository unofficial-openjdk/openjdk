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

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketImpl;
import java.net.SocketOption;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.Strands;
import jdk.internal.ref.CleanerFactory;
import sun.net.NetHooks;
import sun.net.ResourceManager;
import sun.net.ext.ExtendedSocketOptions;
import sun.net.util.SocketExceptions;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static sun.net.ext.ExtendedSocketOptions.SOCK_STREAM;

/**
 * NIO based SocketImpl.
 *
 * This implementation attempts to be compatible with legacy PlainSocketImpl,
 * including behavior and exceptions that are not specified by SocketImpl.
 *
 * The underlying socket used by this SocketImpl is initially configured
 * blocking. If a connect, accept or read is attempted with a timeout, or a
 * fiber invokes a blocking operation, then the socket is changed to non-blocking
 * mode. When in non-blocking mode, operations that don't complete immediately
 * will poll the socket when invoked on a thread, or park when invoked on a
 * fiber.
 *
 * Behavior differences to examine:
 * 1. "Connection reset" handling differs to PlainSocketImpl for cases where
 * an application continues to call read or available after a reset.
 * 2. Bounds checks on SocketInputStream/SocketOutputStream throws AIOOBE.
 * 3. SocketInputStream/SocketOutputStream limit I/O buffer size to 128K.
 * 4. Solaris specific SO_FLOW_SLA option not implemented yet.
 */

public class NioSocketImpl extends SocketImpl {
    private static final NativeDispatcher nd = new SocketDispatcher();

    // true if this is a SocketImpl for a ServerSocket
    private final boolean server;

    // Lock held when reading, accepting or connecting
    private final ReentrantLock readLock = new ReentrantLock();

    // Lock held when writing or connecting
    private final ReentrantLock writeLock = new ReentrantLock();

    // The stateLock for read/changing state
    private final Object stateLock = new Object();
    private static final int ST_NEW = 0;
    private static final int ST_UNCONNECTED = 1;
    private static final int ST_CONNECTING = 2;
    private static final int ST_CONNECTED = 3;
    private static final int ST_CLOSING = 4;
    private static final int ST_CLOSED = 5;
    private volatile int state;  // need stateLock to change

    // set by SocketImpl.create, protected by stateLock
    private boolean stream;
    private FileDescriptorCloser closer;

    // lazily set to true when the socket is configured non-blocking
    private volatile boolean nonBlocking;

    // used by connect/read/write/accept, protected by stateLock
    private long readerThread;
    private long writerThread;

    // true is SO_REUSEADDR is emulated
    private boolean isReuseAddress;

    // read or accept timeout in millis
    private volatile int timeout;

    // flags to indicate if the connection is shutdown for input and output
    private volatile boolean isInputClosed;
    private volatile boolean isOutputClosed;

    /**
     * Creates a instance of this SocketImpl.
     * @param server true if this is a SocketImpl for a ServerSocket
     */
    public NioSocketImpl(boolean server) {
        this.server = server;
    }

    /**
     * Returns true if the socket is open.
     */
    private boolean isOpen() {
        return state < ST_CLOSING;
    }

    /**
     * Throws SocketException if the socket is not open.
     */
    private void ensureOpen() throws SocketException {
        if (state >= ST_CLOSING)
            throw new SocketException("Socket closed");
    }

    /**
     * Throws SocketException if the socket is not open and connected.
     */
    private void ensureOpenAndConnected() throws SocketException {
        int state = this.state;
        if (state < ST_CONNECTED)
            throw new SocketException("Not connected");
        if (state > ST_CONNECTED)
            throw new SocketException("Socket closed");
    }

    /**
     * Disables the current thread or fiber for scheduling purposes until the
     * given file descriptor is ready for I/O, or asynchronously closed, for up
     * to the specified waiting time.
     * @throws IOException if an I/O error occurs of the fiber is cancelled
     */
    private void park(FileDescriptor fd, int event, long nanos) throws IOException {
        Object strand = Strands.currentStrand();
        if (PollerProvider.available() && (strand instanceof Fiber)) {
            int fdVal = fdVal(fd);
            Poller.register(strand, fdVal, event);
            if (isOpen()) {
                try {
                    if (Fiber.cancelled()) {
                        // throw SocketException for now
                        throw new SocketException("I/O operation cancelled");
                    }
                    if (nanos == 0) {
                        Strands.parkFiber();
                    } else {
                        Strands.parkFiber(nanos);
                    }
                    if (Fiber.cancelled()) {
                        // throw SocketException for now
                        throw new SocketException("I/O operation cancelled");
                    }
                } finally {
                    Poller.deregister(strand, fdVal, event);
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
     * Ensures that the socket is configured non-blocking when the current
     * strand is a fiber or a timeout is specified.
     * @throws IOException if there is an I/O error changing the blocking mode
     */
    private void maybeConfigureNonBlocking(FileDescriptor fd, int timeout)
        throws IOException
    {
        assert readLock.isHeldByCurrentThread() || writeLock.isHeldByCurrentThread();
        if (!nonBlocking
                && (timeout > 0 || Strands.currentStrand() instanceof Fiber)) {
            IOUtil.configureBlocking(fd, false);
            nonBlocking = true;
        }
    }

    /**
     * Marks the beginning of a read operation that might block.
     * @throws SocketException if the socket is closed or not connected
     */
    private FileDescriptor beginRead() throws SocketException {
        synchronized (stateLock) {
            ensureOpenAndConnected();
            readerThread = NativeThread.current();
            assert fd != null;
            return fd;
        }
    }

    /**
     * Marks the end of a read operation that may have blocked.
     * @throws SocketException is the socket is closed
     */
    private void endRead(boolean completed) throws SocketException {
        synchronized (stateLock) {
            readerThread = 0;
            int state = this.state;
            if (state == ST_CLOSING)
                stateLock.notifyAll();
            if (!completed && state >= ST_CLOSING)
                throw new SocketException("Socket closed");
        }
    }

    /**
     * Reads bytes from the socket into the given buffer.
     * @throws IOException if the socket is closed or an I/O occurs
     * @throws SocketTimeoutException if the read timeout elapses
     */
    private int read(ByteBuffer dst) throws IOException {
        readLock.lock();
        try {
            int n = 0;
            FileDescriptor fd = beginRead();
            try {
                if (isInputClosed) {
                    return IOStatus.EOF;
                }
                int timeout = this.timeout;
                maybeConfigureNonBlocking(fd, timeout);
                n = IOUtil.read(fd, dst, -1, nd);
                if (statusImpliesRetry(n) && isOpen()) {
                    if (timeout > 0) {
                        // read with timeout
                        assert nonBlocking;
                        long nanos = NANOSECONDS.convert(timeout, TimeUnit.MILLISECONDS);
                        do {
                            long startTime = System.nanoTime();
                            park(fd, Net.POLLIN, nanos);
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
                            park(fd, Net.POLLIN, 0);
                            n = IOUtil.read(fd, dst, -1, nd);
                        } while (statusImpliesRetry(n) && isOpen());
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
     * Marks the beginning of a write operation that might block.
     * @throws SocketException if the socket is closed or not connected
     */
    private FileDescriptor beginWrite() throws SocketException {
        synchronized (stateLock) {
            ensureOpenAndConnected();
            writerThread = NativeThread.current();
            assert fd != null;
            return fd;
        }
    }

    /**
     * Marks the end of a write operation that may have blocked.
     * @throws SocketException is the socket is closed
     */
    private void endWrite(boolean completed) throws SocketException {
        synchronized (stateLock) {
            writerThread = 0;
            int state = this.state;
            if (state == ST_CLOSING)
                stateLock.notifyAll();
            if (!completed && state >= ST_CLOSING)
                throw new SocketException("Socket closed");
        }
    }

    /**
     * Writes a sequence of bytes to this socket from the given buffer.
     * @throws IOException if the socket is closed or an I/O occurs
     */
    private int write(ByteBuffer dst) throws IOException {
        writeLock.lock();
        try {
            int n = 0;
            FileDescriptor fd = beginWrite();
            try {
                maybeConfigureNonBlocking(fd, 0);
                n = IOUtil.write(fd, dst, -1, nd);
                while (statusImpliesRetry(n) && isOpen()) {
                    park(fd, Net.POLLOUT, 0);
                    n = IOUtil.write(fd, dst, -1, nd);
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
     * Creates the socket.
     * @param stream {@code true} for a streams socket
     */
    @Override
    protected void create(boolean stream) throws IOException {
        synchronized (stateLock) {
            assert state == ST_NEW;
            if (!stream)
                ResourceManager.beforeUdpCreate();
            FileDescriptor fd = null;
            try {
                if (server) {
                    assert stream;
                    fd = Net.serverSocket(true);
                } else {
                    fd = Net.socket(stream);
                }
            } catch (IOException ioe) {
                if (!stream)
                    ResourceManager.afterUdpClose();
                if (fd != null)
                    nd.close(fd);
                throw ioe;
            }
            this.fd = fd;
            this.stream = stream;
            this.closer = FileDescriptorCloser.create(this);
            this.state = ST_UNCONNECTED;
        }
    }

    /**
     * For use by ServerSocket to set the state and other fields after a
     * connection is accepted by a ServerSocket using a custom SocketImpl.
     * The protected fields defined by SocketImpl should be set.
     */
    public void postCustomAccept() throws IOException {
        synchronized (stateLock) {
            assert state == ST_NEW;
            assert fd.valid() && localport != 0 && address != null && port != 0;
            IOUtil.configureBlocking(fd, true);
            stream = true;
            closer = FileDescriptorCloser.create(this);
            state = ST_CONNECTED;
        }
    }

    /**
     * For use by ServerSocket to copy the state from this connected SocketImpl
     * to a target SocketImpl. If the target SocketImpl is not a newly created
     * SocketImpl then it is first closed to release any resources. The target
     * SocketImpl becomes the owner of the file descriptor, this SocketImpl
     * is marked as closed and should be discarded.
     */
    public void copyTo(SocketImpl si) {
        if (si instanceof NioSocketImpl) {
            NioSocketImpl nsi = (NioSocketImpl) si;
            synchronized (nsi.stateLock) {
                if (nsi.state != ST_NEW) {
                    try {
                        nsi.close();
                    } catch (IOException ignore) { }
                }
                synchronized (this.stateLock) {
                    // this SocketImpl should be connected
                    assert state == ST_CONNECTED && fd.valid()
                        && localport != 0 && address != null && port != 0;

                    // copy fields
                    nsi.fd = this.fd;
                    nsi.stream = this.stream;
                    nsi.closer = FileDescriptorCloser.create(nsi);
                    nsi.localport = this.localport;
                    nsi.address = this.address;
                    nsi.port = this.port;
                    nsi.state = ST_CONNECTED;

                    // disable closer to prevent GC'ing of this impl from
                    // closing the file descriptor
                    this.closer.disable();
                    this.state = ST_CLOSED;
                }
            }
        } else {
            synchronized (this.stateLock) {
                // this SocketImpl should be connected
                assert state == ST_CONNECTED && fd.valid()
                        && localport != 0 && address != null && port != 0;

                // set fields in foreign impl
                setSocketImplFields(si, fd, localport, address, port);

                // disable closer to prevent GC'ing of this impl from
                // closing the file descriptor
                this.closer.disable();
                this.state = ST_CLOSED;
            }
        }
    }

    /**
     * Marks the beginning of a connect operation that might block.
     * @throws SocketException if the socket is closed or already connected
     */
    private FileDescriptor beginConnect(InetAddress address, int port)
        throws IOException
    {
        synchronized (stateLock) {
            int state = this.state;
            if (state >= ST_CLOSING)
                throw new SocketException("Socket closed");
            if (state == ST_CONNECTED)
                throw new SocketException("Already connected");
            assert state == ST_UNCONNECTED;
            this.state = ST_CONNECTING;

            // invoke beforeTcpConnect hook if not already bound
            if (localport == 0) {
                NetHooks.beforeTcpConnect(fd, address, port);
            }

            // save the remote address/port
            this.address = address;
            this.port = port;

            readerThread = NativeThread.current();
            assert fd != null;
            return fd;
        }
    }

    /**
     * Marks the end of a connect operation that may have blocked.
     * @throws SocketException is the socket is closed
     */
    private void endConnect(boolean completed) throws IOException {
        synchronized (stateLock) {
            readerThread = 0;
            int state = this.state;
            if (state == ST_CLOSING)
                stateLock.notifyAll();
            if (completed && state == ST_CONNECTING) {
                this.state = ST_CONNECTED;
                localport = Net.localAddress(fd).getPort();
            } else if (!completed && state >= ST_CLOSING) {
                throw new SocketException("Socket closed");
            }
        }
    }

    /**
     * Connect the socket. Closes the socket if connection cannot be established.
     * @throws IllegalArgumentException if the address is not an InetSocketAddress
     * @throws UnknownHostException if the InetSocketAddress is not resolved
     * @throws IOException if the connection cannot be established
     */
    private void implConnect(SocketAddress remote, int millis) throws IOException {
        if (!(remote instanceof InetSocketAddress))
            throw new IllegalArgumentException("Unsupported address type");
        InetSocketAddress isa = (InetSocketAddress) remote;
        if (isa.isUnresolved()) {
            throw new UnknownHostException(isa.getHostName());
        }

        InetAddress address = isa.getAddress();
        if (address.isAnyLocalAddress())
            address = InetAddress.getLocalHost();
        int port = isa.getPort();

        try {
            readLock.lock();
            try {
                writeLock.lock();
                try {
                    boolean connected = false;
                    FileDescriptor fd = beginConnect(address, port);
                    try {
                        maybeConfigureNonBlocking(fd, millis);
                        int n = Net.connect(fd, address, port);
                        if (statusImpliesRetry(n) && isOpen()) {
                            if (millis > 0) {
                                // connect with timeout
                                assert nonBlocking;
                                long nanos = NANOSECONDS.convert(millis, MILLISECONDS);
                                do {
                                    long startTime = System.nanoTime();
                                    park(fd, Net.POLLOUT, nanos);
                                    n = SocketChannelImpl.checkConnect(fd, false);
                                    if (n == IOStatus.UNAVAILABLE) {
                                        nanos -= System.nanoTime() - startTime;
                                        if (nanos <= 0)
                                            throw new SocketTimeoutException();
                                    }
                                } while (n == IOStatus.UNAVAILABLE && isOpen());
                            } else {
                                // connect, no timeout
                                do {
                                    park(fd, Net.POLLOUT, 0);
                                    n = SocketChannelImpl.checkConnect(fd, false);
                                } while (statusImpliesRetry(n) && isOpen());
                            }
                        }
                        connected = (n > 0) && isOpen();
                    } finally {
                        endConnect(connected);
                    }
                } finally {
                    writeLock.unlock();
                }
            } finally {
                readLock.unlock();
            }
        } catch (IOException ioe) {
            close();
            throw SocketExceptions.of(ioe, isa);
        }
    }

    @Override
    protected void connect(String host, int port) throws IOException {
        implConnect(new InetSocketAddress(host, port), timeout);
    }

    @Override
    protected void connect(InetAddress address, int port) throws IOException {
        implConnect(new InetSocketAddress(address, port), timeout);
    }

    @Override
    protected void connect(SocketAddress address, int timeout) throws IOException {
        implConnect(address, timeout);
    }

    @Override
    protected void bind(InetAddress host, int port) throws IOException {
        synchronized (stateLock) {
            ensureOpen();
            if (localport != 0)
                throw new SocketException("Already bound");
            NetHooks.beforeTcpBind(fd, host, port);
            Net.bind(fd, host, port);
            // set the address field to the address specified to the method to
            // keep compatibility with PlainSocketImpl. When binding to 0.0.0.0
            // then the actual local address will be ::0 when IPv6 is enabled.
            address = host;
            localport = Net.localAddress(fd).getPort();
        }
    }

    @Override
    protected void listen(int backlog) throws IOException {
        synchronized (stateLock) {
            ensureOpen();
            if (localport == 0)
                throw new SocketException("Not bound");
            Net.listen(fd, backlog < 1 ? 50 : backlog);
        }
    }

    /**
     * Marks the beginning of an accept operation that might block.
     * @throws SocketException if the socket is closed
     */
    private FileDescriptor beginAccept() throws SocketException {
        synchronized (stateLock) {
            ensureOpen();
            if (!stream)
                throw new SocketException("Not a stream socket");
            if (localport == 0)
                throw new SocketException("Not bound");
            readerThread = NativeThread.current();
            assert fd != null;
            return fd;
        }
    }

    /**
     * Marks the end of an accept operation that may have blocked.
     * @throws SocketException is the socket is closed
     */
    private void endAccept(boolean completed) throws SocketException {
        synchronized (stateLock) {
            int state = this.state;
            readerThread = 0;
            if (state == ST_CLOSING)
                stateLock.notifyAll();
            if (!completed && state >= ST_CLOSING)
                throw new SocketException("Socket closed");
        }
    }

    @Override
    protected void accept(SocketImpl si) throws IOException {
        // accept a connection
        FileDescriptor newfd = new FileDescriptor();
        InetSocketAddress[] isaa = new InetSocketAddress[1];
        readLock.lock();
        try {
            int n = 0;
            FileDescriptor fd = beginAccept();
            try {
                int timeout = this.timeout;
                maybeConfigureNonBlocking(fd, timeout);
                n = ServerSocketChannelImpl.accept0(fd, newfd, isaa);
                if (statusImpliesRetry(n) && isOpen()) {
                    if (timeout > 0) {
                        // accept with timeout
                        assert nonBlocking;
                        long nanos = NANOSECONDS.convert(timeout, TimeUnit.MILLISECONDS);
                        do {
                            long startTime = System.nanoTime();
                            park(fd, Net.POLLIN, nanos);
                            n = ServerSocketChannelImpl.accept0(fd, newfd, isaa);
                            if (n == IOStatus.UNAVAILABLE) {
                                nanos -= System.nanoTime() - startTime;
                                if (nanos <= 0)
                                    throw new SocketTimeoutException();
                            }
                        } while (n == IOStatus.UNAVAILABLE && isOpen());
                    } else {
                        // accept, no timeout
                        do {
                            park(fd, Net.POLLIN, 0);
                            n = ServerSocketChannelImpl.accept0(fd, newfd, isaa);
                        } while (statusImpliesRetry(n) && isOpen());
                    }
                }
            } finally {
                endAccept(n > 0);
                assert IOStatus.check(n);
            }
        } finally {
            readLock.unlock();
        }

        // get local address and configure accepted socket to blocking mode
        InetSocketAddress localAddress;
        try {
            localAddress = Net.localAddress(newfd);
            IOUtil.configureBlocking(newfd, true);
        } catch (IOException ioe) {
            nd.close(newfd);
            throw ioe;
        }

        // set the fields
        InetSocketAddress remoteAddress = isaa[0];
        if (si instanceof NioSocketImpl) {
            NioSocketImpl nsi = (NioSocketImpl) si;
            synchronized (nsi.stateLock) {
                nsi.fd = newfd;
                nsi.stream = true;
                nsi.closer = FileDescriptorCloser.create(nsi);
                nsi.localport = localAddress.getPort();
                nsi.address = remoteAddress.getAddress();
                nsi.port = remoteAddress.getPort();
                nsi.state = ST_CONNECTED;
            }
        } else {
            // set fields in foreign impl
            setSocketImplFields(si, newfd,
                                localAddress.getPort(),
                                remoteAddress.getAddress(),
                                remoteAddress.getPort());
        }
    }

    @Override
    protected InputStream getInputStream() {
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
                    int n = NioSocketImpl.this.read(dst);
                    if (n == -1)
                        eof = true;
                    return n;
                }
            }
            @Override
            public int available() throws IOException {
                return NioSocketImpl.this.available();
            }
            @Override
            public void close() throws IOException {
                NioSocketImpl.this.close();
            }
        };
    }

    @Override
    protected OutputStream getOutputStream() {
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
                        NioSocketImpl.this.write(src);
                    }
                }
            }
            @Override
            public void close() throws IOException {
                NioSocketImpl.this.close();
            }
        };
    }

    @Override
    protected int available() throws IOException {
        readLock.lock();
        try {
            ensureOpenAndConnected();
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
     * Closes the socket, signalling and waiting for blocking I/O operations
     * to complete. If invoked on a fiber then it pins the carrier thread until
     * blocking I/O operations have completed.
     */
    @Override
    protected void close() throws IOException {
        boolean interrupted = false;

        synchronized (stateLock) {
            int state = this.state;
            if (state >= ST_CLOSING)
                return;
            if (state == ST_NEW) {
                // stillborn
                this.state = ST_CLOSED;
                return;
            }
            this.state = ST_CLOSING;
            assert fd != null && closer != null;

            // shutdown output when linger interval not set
            try {
                var SO_LINGER = StandardSocketOptions.SO_LINGER;
                if ((int) Net.getSocketOption(fd, Net.UNSPEC, SO_LINGER) != 0) {
                    Net.shutdown(fd, Net.SHUT_WR);
                }
            } catch (IOException ignore) { }

            // unpark and wait for fibers to complete I/O operations
            if (NativeThread.isFiber(readerThread) ||
                    NativeThread.isFiber(writerThread)) {
                Poller.stopPoll(fdVal(fd));

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

            // close file descriptor
            try {
                closer.run();
            } finally {
                this.state = ST_CLOSED;
            }
        }

        // restore interrupt status
        if (interrupted)
            Thread.currentThread().interrupt();
    }

    @Override
    protected Set<SocketOption<?>> supportedOptions() {
        Set<SocketOption<?>> options = new HashSet<>();
        options.addAll(super.supportedOptions());
        options.addAll(ExtendedSocketOptions.options(SOCK_STREAM));
        if (Net.isReusePortAvailable())
            options.add(StandardSocketOptions.SO_REUSEPORT);
        return Collections.unmodifiableSet(options);
    }

    private boolean booleanValue(Object value, String desc) {
        if (!(value instanceof Boolean))
            throw new IllegalArgumentException("Bad value for " + desc);
        return (boolean) value;
    }

    private int intValue(Object value, String desc) {
        if (!(value instanceof Integer))
            throw new IllegalArgumentException("Bad value for " + desc);
        return (int) value;
    }

    @Override
    public void setOption(int opt, Object value) throws SocketException {
        synchronized (stateLock) {
            ensureOpen();
            try {
                switch (opt) {
                case SO_LINGER: {
                    int i;
                    if (value instanceof Boolean) {
                        boolean b = booleanValue(value, "SO_LINGER");
                        if (b) {
                            throw new IllegalArgumentException("SO_LINGER not be set to true");
                        }
                        i = -1;
                    } else {
                        i = intValue(value, "SO_LINGER");
                    }
                    Net.setSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_LINGER, i);
                    break;
                }
                case SO_TIMEOUT: {
                    int i = intValue(value, "SO_TIMEOUT");
                    if (i < 0)
                        throw new IllegalArgumentException("timeout < 0");
                    timeout = i;
                    break;
                }
                case IP_TOS: {
                    int i = intValue(value, "IP_TOS");
                    Net.setSocketOption(fd, protocolFamily(), StandardSocketOptions.IP_TOS, i);
                    break;
                }
                case TCP_NODELAY: {
                    boolean b = booleanValue(value, "TCP_NODELAY");
                    Net.setSocketOption(fd, Net.UNSPEC, StandardSocketOptions.TCP_NODELAY, b);
                    break;
                }
                case SO_SNDBUF: {
                    int i = intValue(value, "SO_SNDBUF");
                    Net.setSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_SNDBUF, i);
                    break;
                }
                case SO_RCVBUF: {
                    int i = intValue(value, "SO_RCVBUF");
                    Net.setSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_RCVBUF, i);
                    break;
                }
                case SO_KEEPALIVE: {
                    boolean b = booleanValue(value, "SO_KEEPALIVE");
                    Net.setSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_KEEPALIVE, b);
                    break;
                }
                case SO_OOBINLINE: {
                    boolean b = booleanValue(value, "SO_OOBINLINE");
                    Net.setSocketOption(fd, Net.UNSPEC, ExtendedSocketOption.SO_OOBINLINE, b);
                    break;
                }
                case SO_REUSEADDR: {
                    boolean b = booleanValue(value, "SO_REUSEADDR");
                    if (Net.useExclusiveBind()) {
                        isReuseAddress = b;
                    } else {
                        Net.setSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_REUSEADDR, b);
                    }
                    break;
                }
                case SO_REUSEPORT: {
                    if (!Net.isReusePortAvailable())
                        throw new UnsupportedOperationException("SO_REUSEPORT not supported");
                    boolean b = booleanValue(value, "SO_REUSEPORT");
                    Net.setSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_REUSEPORT, b);
                    break;
                }
                default:
                    throw new SocketException("Unknown option " + opt);
                }
            } catch (IOException ioe) {
                throw new SocketException(ioe.getMessage());
            }
        }
    }

    @Override
    public Object getOption(int opt) throws SocketException {
        synchronized (stateLock) {
            ensureOpen();
            try {
                switch (opt) {
                case SO_TIMEOUT:
                    return timeout;
                case TCP_NODELAY:
                    return Net.getSocketOption(fd, Net.UNSPEC, StandardSocketOptions.TCP_NODELAY);
                case SO_OOBINLINE:
                    return Net.getSocketOption(fd, Net.UNSPEC, ExtendedSocketOption.SO_OOBINLINE);
                case SO_LINGER: {
                    int i = (int) Net.getSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_LINGER);
                    if (i == -1) {
                        return Boolean.FALSE;
                    } else {
                        return i;
                    }
                }
                case SO_REUSEADDR:
                    if (Net.useExclusiveBind()) {
                        return isReuseAddress;
                    } else {
                        return Net.getSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_REUSEADDR);
                    }
                case SO_BINDADDR:
                    return Net.localAddress(fd).getAddress();
                case SO_SNDBUF:
                    return Net.getSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_SNDBUF);
                case SO_RCVBUF:
                    return Net.getSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_RCVBUF);
                case IP_TOS:
                    return Net.getSocketOption(fd, protocolFamily(), StandardSocketOptions.IP_TOS);
                case SO_KEEPALIVE:
                    return Net.getSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_KEEPALIVE);
                case SO_REUSEPORT:
                    if (!Net.isReusePortAvailable())
                        throw new UnsupportedOperationException("SO_REUSEPORT not supported");
                    return Net.getSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_REUSEPORT);
                default:
                    throw new SocketException("Unknown option " + opt);
                }
            } catch (IOException ioe) {
                throw new SocketException(ioe.getMessage());
            }
        }
    }

    @Override
    protected <T> void setOption(SocketOption<T> opt, T value) throws IOException {
        synchronized (stateLock) {
            ensureOpen();
            if (supportedOptions().contains(opt)) {
                ExtendedSocketOptions extended = ExtendedSocketOptions.getInstance();
                if (extended.isOptionSupported(opt)) {
                    extended.setOption(fd, opt, value);
                } else {
                    super.setOption(opt, value);
                }
            } else {
                throw new UnsupportedOperationException(opt.name());
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected <T> T getOption(SocketOption<T> opt) throws IOException {
        synchronized (stateLock) {
            ensureOpen();
            if (supportedOptions().contains(opt)) {
                ExtendedSocketOptions extended = ExtendedSocketOptions.getInstance();
                if (extended.isOptionSupported(opt)) {
                    return (T) extended.getOption(fd, opt);
                } else {
                    return super.getOption(opt);
                }
            } else {
                throw new UnsupportedOperationException(opt.name());
            }
        }
    }

    @Override
    protected void shutdownInput() throws IOException {
        synchronized (stateLock) {
            ensureOpenAndConnected();
            if (!isInputClosed) {
                Net.shutdown(fd, Net.SHUT_RD);
                long reader = readerThread;
                if (NativeThread.isFiber(reader)) {
                    Poller.stopPoll(fdVal(fd), Net.POLLIN);
                } else if (NativeThread.isKernelThread(reader)) {
                    NativeThread.signal(reader);
                }
                isInputClosed = true;
            }
        }
    }

    @Override
    protected void shutdownOutput() throws IOException {
        synchronized (stateLock) {
            ensureOpenAndConnected();
            if (!isOutputClosed) {
                Net.shutdown(fd, Net.SHUT_WR);
                long writer = writerThread;
                if (NativeThread.isFiber(writer)) {
                    Poller.stopPoll(fdVal(fd), Net.POLLOUT);
                } else if (NativeThread.isKernelThread(writer)) {
                    NativeThread.signal(writer);
                }
                isOutputClosed = true;
            }
        }
    }

    @Override
    protected boolean supportsUrgentData() {
        return true;
    }

    @Override
    protected void sendUrgentData(int data) throws IOException {
        writeLock.lock();
        try {
            int n = 0;
            FileDescriptor fd = beginWrite();
            try {
                maybeConfigureNonBlocking(fd, 0);
                do {
                    n = SocketChannelImpl.sendOutOfBandData(fd, (byte) data);
                } while (n == IOStatus.INTERRUPTED && isOpen());
                if (n == IOStatus.UNAVAILABLE) {
                    throw new RuntimeException("not implemented yet");
                }
            } finally {
                endWrite(n > 0);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * A task that closes a SocketImpl's file descriptor. The task runs when the
     * SocketImpl is explicitly closed and when the SocketImpl becomes phantom
     * reachable.
     */
    private static class FileDescriptorCloser implements Runnable {
        private static final VarHandle CLOSED;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                CLOSED = l.findVarHandle(FileDescriptorCloser.class,
                                         "closed",
                                         boolean.class);
            } catch (Exception e) {
                throw new InternalError(e);
            }
        }
        
        private final FileDescriptor fd;
        private final boolean stream;
        private volatile boolean closed;

        FileDescriptorCloser(FileDescriptor fd, boolean stream) {
            this.fd = fd;
            this.stream = stream;
        }

        static FileDescriptorCloser create(NioSocketImpl impl) {
            assert Thread.holdsLock(impl.stateLock);
            var closer = new FileDescriptorCloser(impl.fd, impl.stream);
            CleanerFactory.cleaner().register(impl, closer);
            return closer;
        }
        
        @Override
        public void run() {
            if (CLOSED.compareAndSet(this, false, true)) {
                try {
                    nd.close(fd);
                } catch (IOException ioe) {
                    throw new RuntimeException(ioe);
                } finally {
                    if (!stream) {
                        // decrement 
                        ResourceManager.afterUdpClose();
                    }
                }
            }
        }

        boolean disable() {
            return CLOSED.compareAndSet(this, false, true);
        }
    }

    /**
     * Returns true if the error code is UNAVAILABLE or INTERRUPTED, the
     * error codes to indicate that an I/O operation should be retried.
     */
    private static boolean statusImpliesRetry(int n) {
        return n == IOStatus.UNAVAILABLE || n == IOStatus.INTERRUPTED;
    }

    /**
     * Returns the socket protocol family
     */
    private static ProtocolFamily protocolFamily() {
        if (Net.isIPv6Available()) {
            return StandardProtocolFamily.INET6;
        } else {
            return StandardProtocolFamily.INET;
        }
    }

    /**
     * Returns the native file descriptor
     */
    private static int fdVal(FileDescriptor fd) {
        int fdVal = SharedSecrets.getJavaIOFileDescriptorAccess().get(fd);
        assert fdVal == IOUtil.fdVal(fd);
        return fdVal;
    }

    /**
     * Sets the SocketImpl fields to the given values.
     */
    private static void setSocketImplFields(SocketImpl si,
                                            FileDescriptor fd,
                                            int localport,
                                            InetAddress address,
                                            int port)
    {
        PrivilegedExceptionAction<Void> pa = () -> {
            setSocketImplField(si, "fd", fd);
            setSocketImplField(si, "localport", localport);
            setSocketImplField(si, "address", address);
            setSocketImplField(si, "port", port);
            return null;
        };
        try {
            AccessController.doPrivileged(pa);
        } catch (PrivilegedActionException pae) {
            throw new InternalError(pae);
        }
    }

    private static void setSocketImplField(SocketImpl si, String name, Object value)
        throws Exception
    {
        Field field = SocketImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(si, value);
    }
}
