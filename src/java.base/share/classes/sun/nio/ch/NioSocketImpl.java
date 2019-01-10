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
 * including throwing exceptions that are not specified by SocketImpl.
 */

public class NioSocketImpl extends SocketImpl {
    private static final NativeDispatcher nd = new SocketDispatcher();

    // true if this is a SocketImpl for a ServerSocket
    private final boolean server;

    // Lock held when reading or writing
    private final ReentrantLock readLock = new ReentrantLock();
    private final ReentrantLock writeLock = new ReentrantLock();

    // The stateLock is needed when changing state
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

    // used by connect/read/write/accept, protected by stateLock
    private long readerThread;
    private long writerThread;

    // read or accept timeout in millis, protected by stateLock
    private int timeout;

    // flags to indicate if the connection is shutdown for input and output
    private volatile boolean isInputClosed;
    private volatile boolean isOutputClosed;

    /**
     * Creates a instance of this SocketImpl.
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
     * Closes a SocketException if the socket is not open.
     */
    private void ensureOpen() throws SocketException {
        if (state >= ST_CLOSING)
            throw new SocketException("Socket closed");
    }

    /**
     * Closes a SocketException if the socket is not open and connected.
     */
    private void ensureOpenAndConnected() throws SocketException {
        int state = this.state;
        if (state < ST_CONNECTED)
            throw new SocketException("not connected");
        if (state > ST_CONNECTED)
            throw new SocketException("socket closed");
    }

    /**
     * Disables the current thread or fiber for scheduling purposes until this
     * socket is ready for I/O, or asynchronously closed, for up to the
     * specified waiting time, unless the permit is available.
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
                } finally{
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
                if (isInputClosed)
                    return IOStatus.EOF;
                n = IOUtil.read(fd, dst, -1, nd);
                if (n == IOStatus.UNAVAILABLE && isOpen()) {
                    long nanos = NANOSECONDS.convert(timeout, TimeUnit.MILLISECONDS);
                    if (nanos > 0) {
                        // read with timeout
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
                n = IOUtil.write(fd, dst, -1, nd);
                while (n == IOStatus.UNAVAILABLE && isOpen()) {
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
                IOUtil.configureBlocking(fd, false);
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
    private void implConnect(SocketAddress remote, long millis) throws IOException {
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
                    try {
                        FileDescriptor fd = beginConnect(address, port);
                        int n = Net.connect(fd, address, port);
                        if (n == IOStatus.UNAVAILABLE && isOpen()) {
                            long nanos = NANOSECONDS.convert(millis, MILLISECONDS);
                            if (nanos > 0) {
                                // connect with timeout
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
                                } while (n == IOStatus.UNAVAILABLE && isOpen());
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
            InetSocketAddress localAddress = Net.localAddress(fd);

            address = localAddress.getAddress();
            localport = localAddress.getPort();
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
    private FileDescriptor beginAccept() throws  SocketException {
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
    protected void accept(SocketImpl obj) throws IOException {
        if (!(obj instanceof NioSocketImpl))
            throw new UnsupportedOperationException("SocketImpl type not supported");
        NioSocketImpl si = (NioSocketImpl) obj;

        readLock.lock();
        try {
            InetSocketAddress[] isaa = new InetSocketAddress[1];

            int n = 0;
            try {
                FileDescriptor fd = beginAccept();
                n = ServerSocketChannelImpl.accept0(fd, si.fd, isaa);
                if (n == IOStatus.UNAVAILABLE && isOpen()) {
                    long nanos = NANOSECONDS.convert(timeout, TimeUnit.MILLISECONDS);
                    if (nanos > 0) {
                        // accept with timeout
                        do {
                            long startTime = System.nanoTime();
                            park(fd, Net.POLLIN, nanos);
                            n = ServerSocketChannelImpl.accept0(fd, si.fd, isaa);
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
                            n = ServerSocketChannelImpl.accept0(fd, si.fd, isaa);
                        } while (n == IOStatus.UNAVAILABLE && isOpen());
                    }
                }
            } finally {
                endAccept(n > 0);
                assert IOStatus.check(n);
            }

            // set fields in SocketImpl
            synchronized (si.stateLock) {

                try {
                    IOUtil.configureBlocking(si.fd, false);
                } catch (IOException ioe) {
                    nd.close(si.fd);
                    throw ioe;
                }
                si.stream = true;
                si.closer = FileDescriptorCloser.create(si);
                si.localport = Net.localAddress(si.fd).getPort();
                si.address = isaa[0].getAddress();
                si.port = isaa[0].getPort();
                si.state = ST_CONNECTED;
            }
        } finally {
            readLock.unlock();
        }
    }

    @Override
    protected InputStream getInputStream() throws IOException {
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
    protected OutputStream getOutputStream() throws IOException {
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
            if (state > ST_CONNECTED)
                return;
            state = ST_CLOSING;

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
                state = ST_CLOSED;
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

    private boolean booleanValue(Object value, String desc) throws SocketException {
        if (!(value instanceof Boolean))
            throw new SocketException("Bad value for " + desc);
        return (boolean) value;
    }

    private int intValue(Object value, String desc) throws SocketException {
        if (!(value instanceof Integer))
            throw new SocketException("Bad value for " + desc);
        return (int) value;
    }

    @Override
    public void setOption(int opt, Object value) throws SocketException {
        synchronized (stateLock) {
            ensureOpen();
            try {
                switch (opt) {
                case SO_LINGER: {
                    if (!(value instanceof Integer) && !(value instanceof Boolean))
                        throw new SocketException("Bad value for SO_LINGER");
                    int i = 0;
                    if (value instanceof Integer) {
                        i = ((Integer) value).intValue();
                        if (i < 0)
                            i = Integer.valueOf(-1);
                        if (i > 65535)
                            i = Integer.valueOf(65535);
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
                    if (i < 0 || i > 255)
                        throw new IllegalArgumentException("Invalid IP_TOS value");
                    ProtocolFamily family = Net.isIPv6Available() ?
                        StandardProtocolFamily.INET6 : StandardProtocolFamily.INET;
                    Net.setSocketOption(fd, family, StandardSocketOptions.IP_TOS, i);
                    break;
                }
                case TCP_NODELAY: {
                    boolean b = booleanValue(value, "TCP_NODELAY");
                    Net.setSocketOption(fd, Net.UNSPEC, StandardSocketOptions.TCP_NODELAY, b);
                    break;
                }
                case SO_SNDBUF: {
                    int i = intValue(value, "SO_SNDBUF");
                    if (i < 0)
                        throw new SocketException("bad parameter for SO_SNDBUF");
                    Net.setSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_SNDBUF, i);
                    break;
                }
                case SO_RCVBUF: {
                    int i = intValue(value, "SO_RCVBUF");
                    if (i < 0)
                        throw new SocketException("bad parameter for SO_RCVBUF");
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
                    Net.setSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_REUSEADDR, b);
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
                case SO_LINGER:
                    return Net.getSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_LINGER);
                case SO_REUSEADDR:
                    return Net.getSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_REUSEADDR);
                case SO_BINDADDR:
                    return Net.localAddress(fd).getAddress();
                case SO_SNDBUF:
                    return Net.getSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_SNDBUF);
                case SO_RCVBUF:
                    return Net.getSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_RCVBUF);
                case IP_TOS:
                    ProtocolFamily family = Net.isIPv6Available() ?
                            StandardProtocolFamily.INET6 : StandardProtocolFamily.INET;
                    return Net.getSocketOption(fd, family, StandardSocketOptions.IP_TOS);
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
            try {
                FileDescriptor fd = beginWrite();
                n = SocketChannelImpl.sendOutOfBandData(fd, (byte) data);
                if (n == IOStatus.UNAVAILABLE) {
                    throw new RuntimeException("not implemented");
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
    }

    private static int fdVal(FileDescriptor fd) {
        int fdVal = SharedSecrets.getJavaIOFileDescriptorAccess().get(fd);
        assert fdVal == IOUtil.fdVal(fd);
        return fdVal;
    }
}
