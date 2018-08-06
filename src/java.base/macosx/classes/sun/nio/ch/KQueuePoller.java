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

import java.io.IOException;

import static sun.nio.ch.KQueue.EV_ADD;
import static sun.nio.ch.KQueue.EV_DELETE;
import static sun.nio.ch.KQueue.EV_ONESHOT;


/**
 * Poller implementation based on the kqueue facility.
 */

class KQueuePoller extends Poller {
    private static final int MAX_EVENTS_TO_POLL = 512;

    private final int kqfd;
    private final int filter;
    private final long address;

    KQueuePoller(int filter) throws IOException {
        this.kqfd = KQueue.create();
        this.filter = filter;
        this.address = KQueue.allocatePollArray(MAX_EVENTS_TO_POLL);
    }

    @Override
    protected void implRegister(int fdVal) {
        int err = KQueue.register(kqfd, fdVal, filter, (EV_ADD|EV_ONESHOT));
        if (err != 0)
            throw new InternalError("kevent failed: " + err);
    }

    @Override
    protected boolean implDeregister(int fdVal) {
        int err = KQueue.register(kqfd, fdVal, filter, EV_DELETE);
        return (err == 0);
    }

    @Override
    public void run() {
        try {
            for (;;) {
                int n = KQueue.poll(kqfd, address, MAX_EVENTS_TO_POLL, -1L);
                while (n-- > 0) {
                    long keventAddress = KQueue.getEvent(address, n);
                    int fdVal = KQueue.getDescriptor(keventAddress);
                    polled(fdVal);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
