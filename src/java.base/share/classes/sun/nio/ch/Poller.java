/*
 * Copyright (c) 2017, 2018, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOError;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

import jdk.internal.misc.InnocuousThread;
import jdk.internal.misc.JavaLangAccess;
import jdk.internal.misc.SharedSecrets;

abstract class Poller implements Runnable {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();
    private static final Poller READ_POLLER;
    private static final Poller WRITE_POLLER;

    static {
        try {
            READ_POLLER = startPollerThread("Read-Poller", PollerProvider.readPoller());
            WRITE_POLLER = startPollerThread("Write-Poller", PollerProvider.writePoller());
        } catch (IOException ioe) {
            throw new IOError(ioe);
        }
    }

    private static Poller startPollerThread(String name, Poller poller) {
        try {
            Thread t = JLA.executeOnCarrierThread(() ->
                    InnocuousThread.newSystemThread(name, poller));
            t.setDaemon(true);
            t.start();
            return poller;
        } catch (Exception e) {
            throw new InternalError(e);
        }
    }

    /**
     * Registers a strand to be unparked when a file descriptor is ready for I/O.
     *
     * @throws IllegalArgumentException if the event is not POLLIN or POLLOUT
     * @throws IllegalStateException if another strand is already registered
     *         to be unparked when the file descriptor is ready for this event
     */
    static void register(Strand strand, int fdVal, int event) {
        if (event == Net.POLLIN) {
            READ_POLLER.register(strand, fdVal);
        } else if (event == Net.POLLOUT) {
            WRITE_POLLER.register(strand, fdVal);
        } else {
            throw new IllegalArgumentException("Unknown event " + event);
        }
    }

    /**
     * Deregister a strand so that it will not be unparked when a file descriptor
     * is ready for I/O. This method is a no-op if the strand is not registered.
     *
     * @throws IllegalArgumentException if the event is not POLLIN or POLLOUT
     */
    static void deregister(Strand strand, int fdVal, int event) {
        if (event == Net.POLLIN) {
            READ_POLLER.deregister(strand, fdVal);
        } else if (event == Net.POLLOUT) {
            WRITE_POLLER.deregister(strand, fdVal);
        } else {
            throw new IllegalArgumentException("Unknown event " + event);
        }
    }

    /**
     * Stops polling the file descriptor for the given event and unpark any
     * strand registered to be unparked when the file descriptor is ready for I/O.
     */
    static void stopPoll(int fdVal, int event) {
        if (event == Net.POLLIN) {
            READ_POLLER.wakeup(fdVal);
        } else if (event == Net.POLLOUT) {
            WRITE_POLLER.wakeup(fdVal);
        } else {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Stops polling the file descriptor and unpark any strands that are registered
     * to be unparked when the file descriptor is ready for I/O.
     */
    static void stopPoll(int fdVal) {
        stopPoll(fdVal, Net.POLLIN);
        stopPoll(fdVal, Net.POLLOUT);
    }

    private final Map<Integer, Strand> map = new ConcurrentHashMap<>();

    protected Poller() { }

    private void register(Strand strand, int fdVal) {
        Strand previous = map.putIfAbsent(fdVal, strand);
        if (previous != null && previous != strand) {
            throw new IllegalStateException();
        }
        implRegister(fdVal);
    }
    private void deregister(Strand strand, int fdVal) {
        if (map.remove(fdVal, strand)) {
            implDeregister(fdVal);
        }
    }

    private void wakeup(int fdVal) {
        Strand strand = map.remove(fdVal);
        if (strand != null) {
            implDeregister(fdVal);
            LockSupport.unpark(strand);
        }
    }

    /**
     * Called by the polling facility when the file descriptor is polled
     */
    final protected void polled(int fdVal) {
        Strand strand = map.remove(fdVal);
        if (strand != null) {
            LockSupport.unpark(strand);
        }
    }

    /**
     * Register the file descriptor
     */
    abstract protected void implRegister(int fdVal);

    /**
     * Deregister (or disarm) the file descriptor
     */
    abstract protected boolean implDeregister(int fdVal);
}
