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

import java.io.IOException;

import jdk.internal.misc.Unsafe;
import static sun.nio.ch.SolarisEventPort.*;

/**
 * Poller implementation based on the Solaris ports facility.
 */

class PortPoller extends Poller {

    private static final int addressSize = Unsafe.getUnsafe().addressSize();

    private static final int OPEN_MAX = IOUtil.fdLimit();
    private static final int POLL_MAX = Math.min(OPEN_MAX-1, 512);

    private final int pfd;
    private final int event;
    private final long pollArrayAddress;
    private final AllocatedNativeObject pollArray;

    PortPoller(int event) throws IOException {
        this.pfd = port_create();
        this.event = event;

        int allocationSize = POLL_MAX * SIZEOF_PORT_EVENT;
        this.pollArray = new AllocatedNativeObject(allocationSize, true);
        this.pollArrayAddress = pollArray.address();
    }

    @Override
    protected void implRegister(int fdVal) {
        try {
            port_associate(pfd, PORT_SOURCE_FD, (long)fdVal, event);
        } catch (IOException ioe) {
            throw new InternalError("port_associate failed", ioe);
        }
    }

    @Override
    protected boolean implDeregister(int fdVal) {
        try {
            return port_dissociate(pfd, PORT_SOURCE_FD, (long)fdVal);
        } catch (IOException ioe) {
            throw new InternalError("port_dissociate failed", ioe);
        }
    }

    @Override
    public void run() {
        try {
            for (;;) {
                int updated = port_getn(pfd, pollArrayAddress, POLL_MAX, -1L);
                for (int i=0; i<updated; i++) {
                    if (getSource(i) == PORT_SOURCE_FD) {
                        int fdVal = getDescriptor(i);
                        polled(fdVal);
                    }
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private short getSource(int i) {
        int offset = SIZEOF_PORT_EVENT * i + OFFSETOF_SOURCE;
        return pollArray.getShort(offset);
    }

    private int getDescriptor(int i) {
        int offset = SIZEOF_PORT_EVENT * i + OFFSETOF_OBJECT;
        if (addressSize == 4) {
            return pollArray.getInt(offset);
        } else {
            return (int) pollArray.getLong(offset);
        }
    }
}