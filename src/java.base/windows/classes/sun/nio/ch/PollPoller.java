/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
import java.nio.channels.Pipe;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import jdk.internal.misc.Unsafe;
import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;

/**
 * Simple/non-scalable implementation of Poller based on WSAPoll. This
 * implementation will be replaced with a more scalable implementation in the
 * future.
 *
 * Due to a bug in WSAPoll, this implementation does not support polling for a
 * connection to complete. If a socket is registered to poll for a connect to
 * complete then it never will be polled when the connection cannot be
 * established.
 */

class PollPoller extends Poller {
    private static JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static final NativeDispatcher ND = new SocketDispatcher();
    private static final long TEMP_BUF = UNSAFE.allocateMemory(1);

    // initial capacity of poll array
    private static final int INITIAL_CAPACITY = 16;

    // true if this is a poller for reading (POLLIN), false for writing (POLLOUT)
    private final boolean reader;

    // poll array, grows as needed
    private int pollArrayCapacity = INITIAL_CAPACITY;
    private int pollArraySize;
    private AllocatedNativeObject pollArray;

    // maps file descriptor to index in poll array
    private final Map<Integer, Integer> fdToIndex = new HashMap<>();

    // pipe and file descriptors used for wakeup
    private final Object wakeupLock = new Object();
    private boolean wakeupTriggered;
    private final Pipe pipe;
    private final FileDescriptor fd0, fd1;

    // registration updates
    private final Object updateLock = new Object();
    private final Deque<Integer> registerQueue = new ArrayDeque<>();

    // deregistration (stop) requests
    private static class DeregisterRequest {
        final int fdVal;
        DeregisterRequest(int fdVal) {
            this.fdVal = fdVal;
        }
        int fdVal() {
            return fdVal;
        }
    }
    private final Deque<DeregisterRequest> deregisterQueue = new ArrayDeque<>();

    /**
     * Creates a PollPoller to support reading (POLLIN) or writing (POLLOUT)
     * operations.
     */
    PollPoller(boolean reader) throws IOException {
        this.reader = reader;

        int size = pollArrayCapacity * SIZE_POLLFD;
        this.pollArray = new AllocatedNativeObject(size, false);

        // wakeup support
        this.pipe = makePipe();
        SourceChannelImpl source = (SourceChannelImpl) pipe.source();
        SinkChannelImpl sink = (SinkChannelImpl) pipe.sink();
        (sink.sc).socket().setTcpNoDelay(true);
        this.fd0 = source.getFD();
        this.fd1 = sink.getFD();

        // element 0 in poll array is for wakeup.
        putDescriptor(0, source.getFDVal());
        putEvents(0, Net.POLLIN);
        putRevents(0, (short) 0);
        pollArraySize = 1;
    }

    /**
     * Register the file descriptor.
     */
    @Override
    protected void implRegister(int fdVal) {
        Integer fd = Integer.valueOf(fdVal);
        synchronized (updateLock) {
            registerQueue.add(fd);
        }
        wakeup();
    }

    /**
     * Deregister the file descriptor. This method waits until the poller thread
     * has removed the file descriptor from the poll array.
     */
    @Override
    protected boolean implDeregister(int fdVal) {
        boolean interrupted = false;
        var request = new DeregisterRequest(fdVal);
        synchronized (request) {
            synchronized (updateLock) {
                deregisterQueue.add(request);
            }
            wakeup();
            try {
                request.wait();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    /**
     * Poller run loop.
     */
    @Override
    public void run() {
        try {
            for (;;) {
                // process any updates
                synchronized (updateLock) {
                    processRegisterQueue();
                    processDeregisterQueue();
                }

                // poll for wakeup and/or events
                int numPolled = poll(pollArray.address(), pollArraySize, -1);
                boolean polledWakeup = (getRevents(0) != 0);
                if (polledWakeup) {
                    numPolled--;
                }
                processEvents(numPolled);

                // clear wakeup
                if (polledWakeup) {
                    clearWakeup();
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * Process the queue of file descriptors to poll
     */
    private void processRegisterQueue() {
        assert Thread.holdsLock(updateLock);
        Integer fd;
        while ((fd = registerQueue.pollFirst()) != null) {
            short events = (reader) ? Net.POLLIN : Net.POLLOUT;
            int index = add(fd, events);
            fdToIndex.put(fd, index);
        }
    }

    /**
     * Process the queue of file descriptors to stop polling
     */
    private void processDeregisterQueue() {
        assert Thread.holdsLock(updateLock);
        DeregisterRequest request;
        while ((request = deregisterQueue.pollFirst()) != null) {
            Integer index = fdToIndex.remove(request.fdVal);
            if (index != null) {
                remove(index);
            }
            synchronized (request) {
                request.notifyAll();
            }
        }
    }

    /**
     * Process the polled events, skipping the first (0) entry in the poll array
     * as that is used by the wakeup mechanism.
     *
     * @param numPolled the number of polled sockets in the array (from index 1)
     */
    private void processEvents(int numPolled) {
        int index = 1;
        int remaining = numPolled;
        while (index < pollArraySize && remaining > 0) {
            short revents = getRevents(index);
            if (revents != 0) {
                int fd = getDescriptor(index);
                assert fdToIndex.get(fd) == index;
                polled(fd);
                remove(index);
                fdToIndex.remove(fd);
                remaining--;
            } else {
                index++;
            }
        }
    }

    /**
     * Wake up the poller thread
     */
    private void wakeup() {
        synchronized (wakeupLock) {
            if (!wakeupTriggered) {
                try {
                    ND.write(fd1, TEMP_BUF, 1);
                } catch (IOException ioe) {
                    throw new InternalError(ioe);
                }
                wakeupTriggered = true;
            }
        }
    }

    /**
     * Clear the wakeup event
     */
    private void clearWakeup() throws IOException {
        synchronized (wakeupLock) {
            ND.read(fd0, TEMP_BUF, 1);
            putRevents(0, (short) 0);
            wakeupTriggered = false;
        }

    }

    /**
     * Add a pollfd entry to the poll array.
     * 
     * @return the index of the pollfd entry in the poll array
     */
    private int add(int fd, short events) {
        expandIfNeeded();
        int index = pollArraySize;
        assert index > 0;
        putDescriptor(index, fd);
        putEvents(index, events);
        putRevents(index, (short) 0);
        pollArraySize++;
        return index;
    }

    /**
     * Removes a pollfd entry from the poll array.
     */
    private void remove(int index) {
        assert index > 0 && index < pollArraySize;

        // replace pollfd at index with the last pollfd in array
        int lastIndex = pollArraySize - 1;
        if (lastIndex != index) {
            int lastFd = getDescriptor(lastIndex);
            short lastEvents = getEvents(lastIndex);
            short lastRevents = getRevents(lastIndex);
            putDescriptor(index, lastFd);
            putEvents(index, lastEvents);
            putRevents(index, lastRevents);

            assert fdToIndex.get(lastFd) == lastIndex;
            fdToIndex.put(lastFd, index);
        }
        pollArraySize--;
    }

    /**
     * Expand poll array if at capacity.
     */
    private void expandIfNeeded() {
        if (pollArraySize == pollArrayCapacity) {
            int oldSize = pollArrayCapacity * SIZE_POLLFD;
            int newCapacity = pollArrayCapacity + INITIAL_CAPACITY;
            int newSize = newCapacity * SIZE_POLLFD;
            AllocatedNativeObject newPollArray = new AllocatedNativeObject(newSize, false);
            UNSAFE.copyMemory(pollArray.address(), newPollArray.address(), oldSize);
            pollArray.free();
            pollArray = newPollArray;
            pollArrayCapacity = newCapacity;
        }
    }

    /**
     * Returns a PipeImpl. The creation is done on the carrier thread to avoid
     * recursive parking when the loopback connection is created.
     */
    private static PipeImpl makePipe() throws IOException {
        try {
            return JLA.executeOnCarrierThread(() -> new PipeImpl(null));
        } catch (IOException ioe) {
            throw ioe;
        } catch (Throwable e) {
            throw new InternalError(e);
        }
    }

    /**
     * typedef struct pollfd {
     *   SOCKET fd;
     *   SHORT events;
     *   SHORT revents;
     * } WSAPOLLFD;
     */
    private static final short SIZE_POLLFD    = 16;
    private static final short FD_OFFSET      = 0;
    private static final short EVENTS_OFFSET  = 8;
    private static final short REVENTS_OFFSET = 10;

    private void putDescriptor(int i, int fd) {
        int offset = SIZE_POLLFD * i + FD_OFFSET;
        pollArray.putLong(offset, fd);
    }

    private int getDescriptor(int i) {
        int offset = SIZE_POLLFD * i + FD_OFFSET;
        return (int) pollArray.getLong(offset);
    }

    private void putEvents(int i, short events) {
        int offset = SIZE_POLLFD * i + EVENTS_OFFSET;
        pollArray.putShort(offset, events);
    }

    private short getEvents(int i) {
        int offset = SIZE_POLLFD * i + EVENTS_OFFSET;
        return pollArray.getShort(offset);
    }

    private void putRevents(int i, short revents) {
        int offset = SIZE_POLLFD * i + REVENTS_OFFSET;
        pollArray.putShort(offset, revents);
    }

    private short getRevents(int i) {
        int offset = SIZE_POLLFD * i + REVENTS_OFFSET;
        return pollArray.getShort(offset);
    }

    private static native int poll(long pollAddress, int numfds, int timeout)
        throws IOException;

    static {
        IOUtil.load();
    }
}