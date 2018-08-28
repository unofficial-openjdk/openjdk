/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;

import jdk.internal.misc.InnocuousThread;
import jdk.internal.misc.JavaLangAccess;
import jdk.internal.misc.SharedSecrets;

public abstract class Poller implements Runnable {
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
     * Poll file descriptor for POLLIN or POLLOUT.
     */
    public static void startPoll(int fdVal, int event) {
        if (event == Net.POLLIN) {
            READ_POLLER.register(fdVal);
        } else if (event == Net.POLLOUT) {
            WRITE_POLLER.register(fdVal);
        } else {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Unpark all strands that are polling the file descriptor.
     */
    public static void stopPoll(int fdVal, int event) {
        if (event == Net.POLLIN) {
            READ_POLLER.wakeup(fdVal);
        } else if (event == Net.POLLOUT) {
            WRITE_POLLER.wakeup(fdVal);
        } else {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Unpark all strands that are polling the file descriptor.
     */
    public static void stopPoll(int fdVal) {
        stopPoll(fdVal, Net.POLLIN);
        stopPoll(fdVal, Net.POLLOUT);
    }

    private final Map<Integer, Object> map = new ConcurrentHashMap<>();

    protected Poller() { }

    private void register(int fdVal) {
        Strand caller = Strand.currentStrand();
        Object newValue;
        do {
            newValue = caller;
            if (map.putIfAbsent(fdVal, newValue) != null) {
                // promote value to list of threads
                newValue = map.computeIfPresent(fdVal, (k, oldValue) -> {
                    if (oldValue instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Strand> list = (List<Strand>) oldValue;
                        list.add(caller);
                        return list;
                    } else {
                        List<Strand> list = new CopyOnWriteArrayList<>();
                        list.add((Strand)oldValue);
                        list.add(caller);
                        return list;
                    }
                });
            }
        } while (newValue == null);
        implRegister(fdVal);
    }

    private void wakeup(int fdVal) {
        Object value = map.remove(fdVal);
        if (value != null) {
            implDeregister(fdVal);
            if (value instanceof Strand) {
                LockSupport.unpark((Strand) value);
            } else {
                @SuppressWarnings("unchecked")
                List<Fiber> list = (List<Fiber>) value;
                list.forEach(LockSupport::unpark);
            }
        }
    }

    /**
     * Called by the polling facility when the file descriptor is polled
     */
    final protected void polled(int fdVal) {
        Object value = map.remove(fdVal);
        if (value != null) {
            if (value instanceof Strand) {
                LockSupport.unpark((Strand) value);
            } else {
                @SuppressWarnings("unchecked")
                List<Fiber> list = (List<Fiber>) value;
                list.forEach(LockSupport::unpark);
            }
        }
    }

    /**
     * Registers the file descriptor
     */
    abstract protected void implRegister(int fdVal);

    /**
     * Deletes or disarms the file descriptor
     */
    abstract protected boolean implDeregister(int fdVal);
}
